/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/ui/settings/SettingsViewModel.kt
package com.folioledger.app.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.AutoBackupRetention
import com.folioledger.app.domain.model.CategoryNode
import com.folioledger.app.domain.model.CategoryType
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.CsvImportPreset
import com.folioledger.app.domain.model.FiscalYearType
import com.folioledger.app.domain.model.ImportCsvPreview
import com.folioledger.app.domain.model.ImportCsvResult
import com.folioledger.app.domain.model.InstallmentPlanWithProgress
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.PdfExportResult
import com.folioledger.app.domain.model.RecurringCommitmentItem
import com.folioledger.app.domain.model.RepeatFrequency
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionBookmark
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.domain.repository.AccountRepository
import com.folioledger.app.domain.repository.AnalyticsAndBudgetRepository
import com.folioledger.app.domain.repository.CategoryAndTagRepository
import com.folioledger.app.domain.repository.SettingsAndBackupRepository
import com.folioledger.app.domain.util.DailyLogReminderCalculator
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import com.folioledger.app.domain.util.RecurringBurdenCalculator
import com.folioledger.app.ui.components.suggestIconAndColorForCategoryName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale

private data class SettingsInternalControlState(
    val referenceDateEpochDay: Long = LocalDate.now().toEpochDay(),
    val isRecurringManagerOpen: Boolean = false,
    val isCategoryManagerOpen: Boolean = false,
    val selectedCategoryManagerType: CategoryType = CategoryType.EXPENSE,
    val categoryEditorState: CategoryEditorDialogState? = null,
    val isSavingCategory: Boolean = false,
    val categoryPendingDeleteId: Long? = null,
    val isTagManagerOpen: Boolean = false,
    val isBookmarkManagerOpen: Boolean = false,
    val isPdfReportsSheetOpen: Boolean = false,
    val lastGeneratedPdfResult: PdfExportResult? = null,
    val isGeneratingPdf: Boolean = false,
    val pendingRestoreUriString: String? = null,
    val csvPreview: ImportCsvPreview? = null,
    val csvImportResult: ImportCsvResult? = null,
    val isBusyWithFileOperation: Boolean = false,
    val fileOperationStatusLabel: String? = null,
    val snackbarMessage: String? = null,
    val lastCompletedFileForShare: File? = null,
    val lastExportedFileSuccess: FileExportSuccessUiModel? = null,
    val autoBackupSnapshots: List<BackupSnapshotDisplayItem> = emptyList(),
    val dismissedComputedError: String? = null
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val settingsAndBackupRepository: SettingsAndBackupRepository,
    private val categoryAndTagRepository: CategoryAndTagRepository,
    private val accountRepository: AccountRepository,
    private val analyticsAndBudgetRepository: AnalyticsAndBudgetRepository,
    private val billingManager: com.folioledger.app.data.billing.FolioBillingManager? = null,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val controlState = MutableStateFlow(SettingsInternalControlState())
    private val fileOpMutex = Mutex()

    private val categoriesAndMetadataFlow = combine(
        categoryAndTagRepository.observeCategoryTree(CategoryType.EXPENSE),
        categoryAndTagRepository.observeCategoryTree(CategoryType.INCOME),
        categoryAndTagRepository.observeAllTags(),
        categoryAndTagRepository.observeBookmarks()
    ) { expenseCats, incomeCats, tags, bookmarks ->
        CategoriesAndMetadataBundle(
            expenseCategories = expenseCats,
            incomeCategories = incomeCats,
            tags = tags,
            bookmarks = bookmarks
        )
    }

    private val accountsPairFlow = controlState
        .map { it.referenceDateEpochDay }
        .distinctUntilChanged()
        .flatMapLatest { refEpochDay ->
            accountRepository.observeAllAccounts(refEpochDay).map { allAccounts ->
                allAccounts.filter { !it.isArchived } to allAccounts
            }
        }

    private val recurringAndInstallmentsFlow = controlState
        .map { it.referenceDateEpochDay }
        .distinctUntilChanged()
        .flatMapLatest { refEpochDay ->
            combine(
                analyticsAndBudgetRepository.observeAllRecurringRules(),
                analyticsAndBudgetRepository.observeInstallmentPlansWithProgress(refEpochDay)
            ) { rules, plans ->
                rules to plans
            }
        }

    private val _hasLoadedUserPreferences = MutableStateFlow(false)
    val hasLoadedUserPreferences: StateFlow<Boolean> = _hasLoadedUserPreferences.asStateFlow()

    val userPreferences: StateFlow<UserPreferences> = settingsAndBackupRepository
        .observeUserPreferences()
        .onEach { _hasLoadedUserPreferences.value = true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserPreferences()
        )

    private val billingControlFlow = combine(
        controlState,
        billingManager?.billingState ?: kotlinx.coroutines.flow.flowOf(com.folioledger.app.data.billing.TipJarBillingState())
    ) { ctrl, billing ->
        ctrl to billing
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        userPreferences,
        categoriesAndMetadataFlow,
        accountsPairFlow,
        recurringAndInstallmentsFlow,
        billingControlFlow
    ) { prefs, metadata, (accounts, allAccounts), (recurringRules, installmentPlans), (ctrl, billing) ->
        val currencySpec = SupportedCurrencies.fromCode(prefs.primaryCurrencyCode)
        var computedError: String? = null
        val burden = try {
            RecurringBurdenCalculator.computeMonthlyBurden(
                recurringRules = recurringRules,
                installmentPlans = installmentPlans,
                primaryCurrencyCode = prefs.primaryCurrencyCode,
                secondaryCurrencies = prefs.secondaryCurrencies
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            computedError = "Burden calculation error: ${e.localizedMessage ?: "Unexpected error"}"
            com.folioledger.app.domain.util.RecurringBurdenSummary(
                activeRecurringCount = 0,
                monthlyRecurringExpense = MinorMoney.ZERO,
                monthlyRecurringTransfer = MinorMoney.ZERO,
                monthlyInstallments = MinorMoney.ZERO,
                totalMonthlyFixedBurden = MinorMoney.ZERO
            )
        }
        val formattedMonthly = MoneyFormatterAndCalculator.formatMinorMoney(
            money = burden.totalMonthlyFixedBurden,
            currencySpec = currencySpec,
            grouping = prefs.numberGrouping,
            includeSymbol = true
        )
        val pendingDeleteCategory = ctrl.categoryPendingDeleteId?.let { targetId ->
            (metadata.expenseCategories + metadata.incomeCategories)
                .flatMap { parent -> listOf(parent) + parent.subcategories }
                .firstOrNull { it.id == targetId }
        }
        val activeComputedError = computedError?.takeIf { it != ctrl.dismissedComputedError }

        SettingsUiState(
            referenceDateEpochDay = ctrl.referenceDateEpochDay,
            userPreferences = prefs,
            currencySpec = currencySpec,
            accounts = accounts,
            allAccounts = allAccounts.ifEmpty { accounts },
            expenseCategories = metadata.expenseCategories,
            incomeCategories = metadata.incomeCategories,
            tags = metadata.tags,
            bookmarks = metadata.bookmarks,
            recurringRules = recurringRules,
            installmentPlans = installmentPlans,
            activeRecurringCount = burden.activeRecurringCount,
            activeMonthlyRecurringBurden = burden.monthlyRecurringExpense,
            activeMonthlyTransfersBurden = burden.monthlyRecurringTransfer,
            activeMonthlyInstallmentsBurden = burden.monthlyInstallments,
            totalMonthlyFixedBurden = burden.totalMonthlyFixedBurden,
            formattedMonthlyBurden = formattedMonthly,
            isRecurringManagerOpen = ctrl.isRecurringManagerOpen,
            isCategoryManagerOpen = ctrl.isCategoryManagerOpen,
            selectedCategoryManagerType = ctrl.selectedCategoryManagerType,
            categoryEditorState = ctrl.categoryEditorState,
            isSavingCategory = ctrl.isSavingCategory,
            categoryPendingDelete = pendingDeleteCategory,
            isTagManagerOpen = ctrl.isTagManagerOpen,
            isBookmarkManagerOpen = ctrl.isBookmarkManagerOpen,
            isPdfReportsSheetOpen = ctrl.isPdfReportsSheetOpen,
            lastGeneratedPdfResult = ctrl.lastGeneratedPdfResult,
            isGeneratingPdf = ctrl.isGeneratingPdf,
            pendingRestoreUriString = ctrl.pendingRestoreUriString,
            csvPreview = ctrl.csvPreview,
            csvImportResult = ctrl.csvImportResult,
            isBusyWithFileOperation = ctrl.isBusyWithFileOperation,
            fileOperationStatusLabel = ctrl.fileOperationStatusLabel,
            snackbarMessage = ctrl.snackbarMessage ?: activeComputedError,
            lastCompletedFileForShare = ctrl.lastCompletedFileForShare,
            lastExportedFileSuccess = ctrl.lastExportedFileSuccess,
            autoBackupSnapshots = ctrl.autoBackupSnapshots,
            tipJarBillingState = billing
        )
    }.flowOn(computationDispatcher)
    .catch { throwable ->
        if (throwable is CancellationException) throw throwable
        emit(
            SettingsUiState(
                snackbarMessage = "Settings error: ${throwable.localizedMessage ?: "Unexpected error"}"
            )
        )
    }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SettingsUiState()
    )

    init {
        refreshAutoBackupFiles()
    }

    fun refreshData() {
        val today = LocalDate.now().toEpochDay()
        controlState.update { current ->
            if (current.referenceDateEpochDay != today) {
                current.copy(referenceDateEpochDay = today)
            } else {
                current
            }
        }
        if (billingManager?.billingState?.value?.pendingMessage != null) {
            billingManager.queryPurchases()
        }
    }

    fun setRecurringManagerOpen(isOpen: Boolean) {
        controlState.update { it.copy(isRecurringManagerOpen = isOpen) }
    }

    fun toggleRecurringRuleActive(ruleId: Long, isActive: Boolean) {
        viewModelScope.launch {
            analyticsAndBudgetRepository.updateRecurringRuleActive(ruleId, isActive).fold(
                onSuccess = {
                    controlState.update {
                        it.copy(
                            snackbarMessage = if (isActive) "Recurring rule resumed" else "Recurring rule paused"
                        )
                    }
                },
                onFailure = { error ->
                    controlState.update {
                        it.copy(snackbarMessage = error.message ?: "Unable to update recurring rule")
                    }
                }
            )
        }
    }

    fun deleteRecurringRule(ruleId: Long) {
        viewModelScope.launch {
            analyticsAndBudgetRepository.deleteRecurringRule(ruleId).fold(
                onSuccess = {
                    controlState.update { it.copy(snackbarMessage = "Recurring rule deleted") }
                },
                onFailure = { error ->
                    controlState.update {
                        it.copy(snackbarMessage = error.message ?: "Unable to delete recurring rule")
                    }
                }
            )
        }
    }

    fun upsertRecurringRule(rule: RecurringCommitmentItem) {
        viewModelScope.launch {
            analyticsAndBudgetRepository.upsertRecurringRule(rule).fold(
                onSuccess = {
                    controlState.update { it.copy(snackbarMessage = "Recurring rule updated") }
                },
                onFailure = { error ->
                    controlState.update {
                        it.copy(snackbarMessage = error.message ?: "Unable to update recurring rule")
                    }
                }
            )
        }
    }

    fun deleteInstallmentPlan(planId: Long) {
        viewModelScope.launch {
            analyticsAndBudgetRepository.deleteInstallmentPlan(planId).fold(
                onSuccess = {
                    controlState.update { it.copy(snackbarMessage = "Installment plan removed") }
                },
                onFailure = { error ->
                    controlState.update {
                        it.copy(snackbarMessage = error.message ?: "Unable to remove installment plan")
                    }
                }
            )
        }
    }

    // UserPreferences Update Actions

    fun setPrimaryCurrency(currencyCode: String) {
        val spec = SupportedCurrencies.getByCode(currencyCode)
        if (spec == null) {
            controlState.update { it.copy(snackbarMessage = "Unsupported currency code: $currencyCode") }
            return
        }
        viewModelScope.launch {
            var rebasedCount = 0
            var clearedStaleRates = false
            settingsAndBackupRepository.updateUserPreferences { current ->
                val oldPrimary = current.primaryCurrencyCode.uppercase(Locale.ROOT)
                val newPrimary = spec.code.uppercase(Locale.ROOT)
                if (oldPrimary == newPrimary) return@updateUserPreferences current

                val oldSpec = SupportedCurrencies.fromCode(oldPrimary)
                val pivotSec = current.secondaryCurrencies.firstOrNull {
                    it.code.equals(newPrimary, ignoreCase = true)
                }
                val hasValidPivot = pivotSec != null &&
                    pivotSec.exchangeRateToPrimary.isFinite() &&
                    pivotSec.exchangeRateToPrimary > 0.0

                val nextSecondaries = if (hasValidPivot) {
                    val pivotBd = BigDecimal.valueOf(pivotSec!!.exchangeRateToPrimary)
                    val remapped = current.secondaryCurrencies
                        .filter {
                            !it.code.equals(newPrimary, ignoreCase = true) &&
                                !it.code.equals(oldPrimary, ignoreCase = true)
                        }
                        .mapNotNull { sec ->
                            if (!sec.exchangeRateToPrimary.isFinite() || sec.exchangeRateToPrimary <= 0.0) {
                                null
                            } else {
                                val newRate = BigDecimal.valueOf(sec.exchangeRateToPrimary)
                                    .divide(pivotBd, 6, RoundingMode.HALF_UP)
                                    .stripTrailingZeros()
                                    .toDouble()
                                if (newRate.isFinite() && newRate in 1e-6..1_000_000_000.0) {
                                    sec.copy(exchangeRateToPrimary = newRate)
                                } else null
                            }
                        }
                    val inverseOldRate = BigDecimal.ONE
                        .divide(pivotBd, 6, RoundingMode.HALF_UP)
                        .stripTrailingZeros()
                        .toDouble()
                    val validInverse = inverseOldRate.isFinite() && inverseOldRate in 1e-6..1_000_000_000.0
                    rebasedCount = remapped.size + if (validInverse) 1 else 0
                    if (validInverse) {
                        (remapped + SecondaryCurrency(code = oldPrimary, exchangeRateToPrimary = inverseOldRate))
                            .sortedBy { it.code }
                    } else {
                        remapped.sortedBy { it.code }
                    }
                } else {
                    if (current.secondaryCurrencies.isNotEmpty()) {
                        clearedStaleRates = true
                    }
                    emptyList()
                }

                val nextGrouping = if (current.numberGrouping == oldSpec.defaultGrouping) {
                    spec.defaultGrouping
                } else {
                    current.numberGrouping
                }

                current.copy(
                    primaryCurrencyCode = spec.code,
                    secondaryCurrencies = nextSecondaries,
                    numberGrouping = nextGrouping
                )
            }
            val msg = when {
                rebasedCount > 0 ->
                    "Primary currency set to ${spec.displayName} (${spec.symbol}). Existing exchange rates rebased."
                clearedStaleRates ->
                    "Primary currency set to ${spec.displayName} (${spec.symbol}). Previous secondary rates were cleared—re-add exchange rates relative to ${spec.code}."
                else ->
                    "Primary currency set to ${spec.displayName} (${spec.symbol})"
            }
            controlState.update { it.copy(snackbarMessage = msg) }
        }
    }

    fun addOrUpdateSecondaryCurrency(code: String, rate: Double) {
        val trimmedCode = code.trim().uppercase(Locale.ROOT)
        if (trimmedCode.isEmpty() || !rate.isFinite() || rate !in 1e-6..1_000_000_000.0) {
            controlState.update {
                it.copy(snackbarMessage = "Enter a valid finite exchange rate between 0.000001 and 1,000,000,000.")
            }
            return
        }
        val spec = SupportedCurrencies.getByCode(trimmedCode)
        if (spec == null) {
            controlState.update { it.copy(snackbarMessage = "Unsupported currency code: $trimmedCode") }
            return
        }
        viewModelScope.launch {
            var collidedWithPrimary = false
            settingsAndBackupRepository.updateUserPreferences { current ->
                if (spec.code.equals(current.primaryCurrencyCode, ignoreCase = true)) {
                    collidedWithPrimary = true
                    return@updateUserPreferences current
                }
                val updatedList = current.secondaryCurrencies
                    .filter { !it.code.equals(spec.code, ignoreCase = true) }
                    .plus(SecondaryCurrency(code = spec.code, exchangeRateToPrimary = rate))
                    .sortedBy { it.code }
                current.copy(secondaryCurrencies = updatedList)
            }
            val formattedRate = BigDecimal.valueOf(rate).stripTrailingZeros().toPlainString()
            controlState.update {
                it.copy(
                    snackbarMessage = if (collidedWithPrimary) {
                        "${spec.code} is already your primary currency."
                    } else {
                        "Secondary currency ${spec.code} updated (1 ${spec.code} = $formattedRate)"
                    }
                )
            }
        }
    }

    fun removeSecondaryCurrency(code: String) {
        val trimmedCode = code.trim().uppercase()
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                val updatedList = current.secondaryCurrencies.filter { it.code != trimmedCode }
                current.copy(secondaryCurrencies = updatedList)
            }
            controlState.update {
                it.copy(snackbarMessage = "Removed $trimmedCode from secondary currencies")
            }
        }
    }

    fun setNumberGrouping(grouping: NumberGroupingSystem) {
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(numberGrouping = grouping)
            }
        }
    }

    fun setMonthStartDay(day: Int) {
        val safeDay = day.coerceIn(1, 31)
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(monthStartDay = safeDay)
            }
            controlState.update {
                it.copy(snackbarMessage = "Financial month start day set to Day $safeDay")
            }
        }
    }

    fun setWeekStartDayIso(dayIso: Int) {
        val safeIso = dayIso.coerceIn(1, 7)
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(weekStartDayIso = safeIso)
            }
        }
    }

    fun setSubcategoriesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(subcategoriesEnabled = enabled)
            }
        }
    }

    fun setCarryOverEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(carryOverEnabled = enabled)
            }
        }
    }

    fun setThemeMode(themeMode: String) {
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(themeMode = themeMode)
            }
        }
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(dynamicColorEnabled = enabled)
            }
        }
    }

    fun setIconPackTheme(iconPackTheme: String) {
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(iconPackTheme = iconPackTheme)
            }
        }
    }

    fun setAccentPalette(accentPalette: String) {
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(accentPalette = accentPalette)
            }
        }
    }

    fun setBiometricLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(biometricLockEnabled = enabled)
            }
            controlState.update {
                it.copy(
                    snackbarMessage = if (enabled) {
                        "Biometric / Device Credential app lock enabled"
                    } else {
                        "App lock disabled"
                    }
                )
            }
        }
    }

    fun showCustomSnackbar(message: String) {
        controlState.update { it.copy(snackbarMessage = message) }
    }

    fun setRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(remindersEnabled = enabled)
            }
            controlState.update {
                it.copy(
                    snackbarMessage = if (enabled) {
                        "Payment & bill reminders enabled"
                    } else {
                        "Payment & bill reminders disabled"
                    }
                )
            }
        }
    }

    fun setCcReminderLeadDays(days: Int) {
        val safeDays = days.coerceIn(0, 14)
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(ccReminderLeadDays = safeDays)
            }
        }
    }

    fun setBillReminderLeadDays(days: Int) {
        val safeDays = days.coerceIn(0, 7)
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(billReminderLeadDays = safeDays)
            }
        }
    }

    fun setReminderTime(hourOfDay: Int, minute: Int) {
        val safeHour = hourOfDay.coerceIn(0, 23)
        val safeMin = minute.coerceIn(0, 59)
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(
                    reminderHourOfDay = safeHour,
                    reminderMinute = safeMin
                )
            }
        }
    }

    fun setDailyLogReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(dailyLogReminderEnabled = enabled)
            }
            controlState.update {
                it.copy(
                    snackbarMessage = if (enabled) {
                        "Daily log reminder enabled"
                    } else {
                        "Daily log reminder disabled"
                    }
                )
            }
        }
    }

    fun setDailyLogReminderSchedule(hourOfDay: Int, minute: Int, daysMask: Int) {
        val safeHour = hourOfDay.coerceIn(0, 23)
        val safeMin = minute.coerceIn(0, 59)
        val safeMask = DailyLogReminderCalculator.normalizeDaysMask(daysMask)
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(
                    dailyLogReminderHour = safeHour,
                    dailyLogReminderMinute = safeMin,
                    dailyLogReminderDaysMask = safeMask
                )
            }
        }
    }

    fun setDailyLogReminderSkipIfLoggedToday(skip: Boolean) {
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(dailyLogReminderSkipIfLoggedToday = skip)
            }
        }
    }

    fun setDailyLogReminderSnoozeMinutes(minutes: Int) {
        val safeMinutes = DailyLogReminderCalculator.normalizeSnoozeMinutes(minutes)
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(dailyLogReminderSnoozeMinutes = safeMinutes)
            }
        }
    }

    fun setDailyLogReminderMessage(message: String) {
        val sanitized = message.trim().take(DailyLogReminderCalculator.MAX_CUSTOM_MESSAGE_LENGTH)
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(dailyLogReminderMessage = sanitized)
            }
        }
    }

    fun setHideAmountsOnLockScreen(hide: Boolean) {
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(hideAmountsOnLockScreen = hide)
            }
        }
    }

    fun refreshTipJarProducts() {
        billingManager?.refresh()
    }

    fun launchTipPurchase(activity: android.app.Activity, skuId: String) {
        billingManager?.launchBillingFlow(activity, skuId)
    }

    fun restoreSupporterPurchases() {
        billingManager?.restorePurchases()
    }

    // 2-Level Category & Subcategory CRUD Actions

    fun setCategoryManagerOpen(
        open: Boolean,
        initialType: CategoryType? = null
    ) {
        controlState.update { current ->
            current.copy(
                isCategoryManagerOpen = open,
                selectedCategoryManagerType = initialType ?: current.selectedCategoryManagerType,
                categoryEditorState = if (!open) null else current.categoryEditorState
            )
        }
    }

    fun selectCategoryManagerType(type: CategoryType) {
        controlState.update { it.copy(selectedCategoryManagerType = type) }
    }

    fun openCreateMainCategoryDialog(
        type: CategoryType? = null
    ) {
        controlState.update { current ->
            val resolvedType = type ?: current.selectedCategoryManagerType
            val defaultColor = if (resolvedType == CategoryType.EXPENSE) "#E11D48" else "#059669"
            val defaultIcon = if (resolvedType == CategoryType.EXPENSE) "shopping_cart" else "payments"
            current.copy(
                categoryEditorState = CategoryEditorDialogState(
                    isEditing = false,
                    categoryId = 0L,
                    name = "",
                    type = resolvedType,
                    parentId = null,
                    parentCategoryName = null,
                    iconName = defaultIcon,
                    colorHex = defaultColor,
                    isSmartIconMatched = false,
                    isIconManuallyOverridden = false,
                    validationError = null
                )
            )
        }
    }

    fun openCreateSubcategoryDialog(parentCategory: CategoryNode) {
        controlState.update {
            it.copy(
                categoryEditorState = CategoryEditorDialogState(
                    isEditing = false,
                    categoryId = 0L,
                    name = "",
                    type = parentCategory.type,
                    parentId = parentCategory.id,
                    parentCategoryName = parentCategory.name,
                    iconName = parentCategory.iconName,
                    colorHex = parentCategory.colorHex,
                    isSmartIconMatched = false,
                    isIconManuallyOverridden = false,
                    validationError = null
                )
            )
        }
    }

    fun openEditCategoryDialog(
        category: CategoryNode,
        parentCategoryName: String? = null
    ) {
        controlState.update {
            it.copy(
                categoryEditorState = CategoryEditorDialogState(
                    isEditing = true,
                    categoryId = category.id,
                    name = category.name,
                    type = category.type,
                    parentId = category.parentId,
                    parentCategoryName = parentCategoryName,
                    iconName = category.iconName,
                    colorHex = category.colorHex,
                    isSmartIconMatched = false,
                    isIconManuallyOverridden = true,
                    validationError = null
                )
            )
        }
    }

    fun onCategoryNameInputChanged(newName: String) {
        controlState.update { current ->
            val existing = current.categoryEditorState ?: return@update current
            if (!existing.isEditing && !existing.isIconManuallyOverridden) {
                val suggestion = suggestIconAndColorForCategoryName(newName, existing.type)
                if (suggestion != null) {
                    current.copy(
                        categoryEditorState = existing.copy(
                            name = newName,
                            iconName = suggestion.first,
                            colorHex = suggestion.second,
                            isSmartIconMatched = true,
                            validationError = null
                        )
                    )
                } else {
                    current.copy(
                        categoryEditorState = existing.copy(
                            name = newName,
                            isSmartIconMatched = newName.isNotBlank() && existing.isSmartIconMatched,
                            validationError = null
                        )
                    )
                }
            } else {
                current.copy(
                    categoryEditorState = existing.copy(
                        name = newName,
                        validationError = null
                    )
                )
            }
        }
    }

    fun updateCategoryEditorState(
        transform: (CategoryEditorDialogState) -> CategoryEditorDialogState
    ) {
        controlState.update { current ->
            val existing = current.categoryEditorState ?: return@update current
            val updated = transform(existing)
            val iconOrColorChangedManually = updated.iconName != existing.iconName ||
                    updated.colorHex != existing.colorHex
            current.copy(
                categoryEditorState = updated.copy(
                    isIconManuallyOverridden = iconOrColorChangedManually || updated.isIconManuallyOverridden,
                    isSmartIconMatched = !iconOrColorChangedManually && updated.isSmartIconMatched,
                    validationError = updated.validationError
                )
            )
        }
    }

    fun dismissCategoryEditorDialog() {
        controlState.update { it.copy(categoryEditorState = null) }
    }

    fun saveCategoryFromDialog() {
        val currentCtrl = controlState.value
        if (currentCtrl.isSavingCategory) return
        val editor = currentCtrl.categoryEditorState ?: return
        val trimmedName = editor.name.trim()
        if (trimmedName.isEmpty()) {
            updateCategoryEditorState {
                it.copy(validationError = "Category name cannot be empty.")
            }
            return
        }

        // Check for duplicate category or subcategory name
        val activeTree = if (editor.type == CategoryType.EXPENSE) {
            uiState.value.expenseCategories
        } else {
            uiState.value.incomeCategories
        }
        val isDuplicate = if (editor.parentId == null) {
            activeTree.any { it.id != editor.categoryId && it.name.equals(trimmedName, ignoreCase = true) }
        } else {
            val parent = activeTree.firstOrNull { it.id == editor.parentId }
            parent?.subcategories?.any { it.id != editor.categoryId && it.name.equals(trimmedName, ignoreCase = true) } == true
        }
        if (isDuplicate) {
            updateCategoryEditorState {
                it.copy(validationError = "A category with this name already exists.")
            }
            return
        }

        val categoryNode = CategoryNode(
            id = editor.categoryId,
            name = trimmedName,
            type = editor.type,
            parentId = editor.parentId,
            iconName = editor.iconName,
            colorHex = editor.colorHex,
            subcategories = emptyList()
        )

        controlState.update { it.copy(isSavingCategory = true) }
        viewModelScope.launch {
            try {
                val result = categoryAndTagRepository.upsertCategory(categoryNode)
                result.fold(
                    onSuccess = {
                        controlState.update {
                            it.copy(
                                categoryEditorState = null,
                                isSavingCategory = false,
                                snackbarMessage = if (editor.isEditing) {
                                    "Updated category \"${categoryNode.name}\""
                                } else {
                                    "Created category \"${categoryNode.name}\""
                                }
                            )
                        }
                    },
                    onFailure = { error ->
                        controlState.update { current ->
                            current.copy(
                                isSavingCategory = false,
                                categoryEditorState = current.categoryEditorState?.copy(
                                    validationError = error.message ?: "Failed to save category."
                                )
                            )
                        }
                    }
                )
            } finally {
                controlState.update { it.copy(isSavingCategory = false) }
            }
        }
    }

    fun requestDeleteCategory(categoryId: Long) {
        controlState.update { it.copy(categoryPendingDeleteId = categoryId) }
    }

    fun cancelDeleteCategory() {
        controlState.update { it.copy(categoryPendingDeleteId = null) }
    }

    fun deleteCategory(categoryId: Long, reassignToCategoryId: Long? = null) {
        viewModelScope.launch {
            val result = categoryAndTagRepository.deleteCategory(
                categoryId = categoryId,
                reassignToCategoryId = reassignToCategoryId
            )
            result.fold(
                onSuccess = {
                    controlState.update {
                        it.copy(
                            categoryPendingDeleteId = null,
                            categoryEditorState = null,
                            snackbarMessage = "Category deleted"
                        )
                    }
                },
                onFailure = { error ->
                    controlState.update {
                        it.copy(
                            categoryPendingDeleteId = null,
                            snackbarMessage = error.message ?: "Unable to delete category"
                        )
                    }
                }
            )
        }
    }

    // Tag & Bookmark Management Actions

    fun setTagManagerOpen(open: Boolean) {
        controlState.update { it.copy(isTagManagerOpen = open) }
    }

    fun createTag(rawTagName: String) {
        val cleaned = rawTagName.trim().trimStart('#').trim()
        if (cleaned.isEmpty()) return
        if (',' in cleaned) {
            controlState.update { it.copy(snackbarMessage = "Tag names cannot contain commas.") }
            return
        }
        viewModelScope.launch {
            val result = categoryAndTagRepository.createTag(cleaned)
            result.fold(
                onSuccess = {
                    controlState.update { it.copy(snackbarMessage = "Created tag #$cleaned") }
                },
                onFailure = { error ->
                    controlState.update {
                        it.copy(snackbarMessage = error.message ?: "Failed to create tag")
                    }
                }
            )
        }
    }

    fun renameTag(oldTagName: String, newTagNameRaw: String) {
        val cleanedNew = newTagNameRaw.trim().trimStart('#').trim()
        if (cleanedNew.isEmpty() || cleanedNew == oldTagName) return
        if (',' in cleanedNew) {
            controlState.update { it.copy(snackbarMessage = "Tag names cannot contain commas.") }
            return
        }
        val mergeTarget = uiState.value.tags.firstOrNull {
            !it.equals(oldTagName, ignoreCase = true) && it.equals(cleanedNew, ignoreCase = true)
        }
        viewModelScope.launch {
            val result = categoryAndTagRepository.renameTag(oldTagName, cleanedNew)
            result.fold(
                onSuccess = {
                    controlState.update {
                        it.copy(
                            snackbarMessage = if (mergeTarget != null) {
                                "Merged #$oldTagName into #$mergeTarget"
                            } else {
                                "Renamed #$oldTagName to #$cleanedNew"
                            }
                        )
                    }
                },
                onFailure = { error ->
                    controlState.update {
                        it.copy(snackbarMessage = error.message ?: "Failed to rename tag")
                    }
                }
            )
        }
    }

    fun deleteTag(tagName: String) {
        viewModelScope.launch {
            val result = categoryAndTagRepository.deleteTag(tagName)
            result.fold(
                onSuccess = {
                    controlState.update { it.copy(snackbarMessage = "Deleted tag #$tagName") }
                },
                onFailure = { error ->
                    controlState.update {
                        it.copy(snackbarMessage = error.message ?: "Failed to delete tag")
                    }
                }
            )
        }
    }

    fun setBookmarkManagerOpen(open: Boolean) {
        controlState.update { it.copy(isBookmarkManagerOpen = open) }
    }

    fun deleteBookmark(bookmarkId: Long) {
        viewModelScope.launch {
            val result = categoryAndTagRepository.deleteBookmark(bookmarkId)
            result.fold(
                onSuccess = {
                    controlState.update { it.copy(snackbarMessage = "Bookmark template deleted") }
                },
                onFailure = { error ->
                    controlState.update {
                        it.copy(snackbarMessage = error.message ?: "Unable to delete bookmark")
                    }
                }
            )
        }
    }

    private fun launchFileOperation(
        statusLabel: String,
        isPdf: Boolean = false,
        nonCancellable: Boolean = false,
        guardDemoMode: Boolean = false,
        demoBlockedMessage: String = "Exit Demo Sandbox before performing this action.",
        block: suspend () -> Unit
    ) {
        if (guardDemoMode && userPreferences.value.isDemoModeActive) {
            controlState.update { it.copy(snackbarMessage = demoBlockedMessage) }
            return
        }
        viewModelScope.launch {
            if (!fileOpMutex.tryLock()) {
                controlState.update { it.copy(snackbarMessage = "Another operation is already in progress.") }
                return@launch
            }
            controlState.update {
                it.copy(
                    isBusyWithFileOperation = true,
                    isGeneratingPdf = isPdf,
                    fileOperationStatusLabel = statusLabel
                )
            }
            try {
                if (nonCancellable) withContext(NonCancellable) { block() } else block()
            } finally {
                controlState.update {
                    it.copy(
                        isBusyWithFileOperation = false,
                        isGeneratingPdf = false,
                        fileOperationStatusLabel = null
                    )
                }
                fileOpMutex.unlock()
            }
        }
    }

    // Backup, Restore & CSV Export Actions

    fun createPortableZipBackup(destinationUriString: String) {
        launchFileOperation(
            statusLabel = "Creating portable ZIP backup...",
            guardDemoMode = true,
            demoBlockedMessage = "Exit Demo Sandbox before creating a backup."
        ) {
            val result = settingsAndBackupRepository.createPortableZipBackup(destinationUriString)
            result.fold(
                onSuccess = {
                    val latestFile = settingsAndBackupRepository.getLatestBackupFile()
                    val cleanFileName = settingsAndBackupRepository.resolveDisplayName(
                        uriString = destinationUriString,
                        fallback = "Folio_Backup.zip"
                    )
                    controlState.update {
                        it.copy(
                            lastCompletedFileForShare = null,
                            lastExportedFileSuccess = FileExportSuccessUiModel(
                                title = "Backup Created!",
                                subtitle = "Portable ZIP backup archive saved to your device",
                                fileName = cleanFileName,
                                fileSizeBytes = latestFile?.length() ?: 0L,
                                file = latestFile,
                                publicDisplayPath = "Downloads/Folio/$cleanFileName"
                            ),
                            snackbarMessage = null
                        )
                    }
                    refreshAutoBackupFiles()
                },
                onFailure = { error ->
                    controlState.update {
                        it.copy(
                            lastCompletedFileForShare = null,
                            lastExportedFileSuccess = null,
                            snackbarMessage = error.message ?: "Backup creation failed"
                        )
                    }
                }
            )
        }
    }

    fun shareLatestBackup(onFileReady: (File) -> Unit) {
        viewModelScope.launch {
            val latestFile = withContext(ioDispatcher) {
                settingsAndBackupRepository.getLatestBackupFile()
            }
            if (latestFile != null) {
                onFileReady(latestFile)
            } else {
                controlState.update {
                    it.copy(snackbarMessage = "No local backup file found to share yet.")
                }
            }
        }
    }

    fun clearLastCompletedFileForShare() {
        controlState.update { it.copy(lastCompletedFileForShare = null) }
    }

    fun clearLastExportedFileSuccess() {
        controlState.update { it.copy(lastExportedFileSuccess = null) }
    }

    fun requestRestoreConfirmation(sourceUriString: String) {
        if (userPreferences.value.isDemoModeActive) {
            controlState.update {
                it.copy(snackbarMessage = "Exit Demo Sandbox before restoring a backup.")
            }
            return
        }
        controlState.update { it.copy(pendingRestoreUriString = sourceUriString) }
    }

    fun cancelPendingRestore() {
        controlState.update { it.copy(pendingRestoreUriString = null) }
    }

    fun confirmRestoreFromZipBackup() {
        val sourceUri = controlState.value.pendingRestoreUriString ?: return
        launchFileOperation(
            statusLabel = "Restoring ledger database from ZIP backup...",
            nonCancellable = true,
            guardDemoMode = true,
            demoBlockedMessage = "Exit Demo Sandbox before restoring a backup."
        ) {
            controlState.update { it.copy(pendingRestoreUriString = null) }
            val result = settingsAndBackupRepository.restoreFromZipBackup(sourceUri)
            result.fold(
                onSuccess = {
                    controlState.update {
                        it.copy(snackbarMessage = "Ledger database & settings restored successfully")
                    }
                    refreshAutoBackupFiles()
                },
                onFailure = { error ->
                    controlState.update {
                        it.copy(snackbarMessage = error.message ?: "Restore from ZIP backup failed")
                    }
                }
            )
        }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsAndBackupRepository.setAutoBackupEnabled(enabled)
            refreshAutoBackupFiles()
        }
    }

    fun setAutoBackupPublicMirrorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(autoBackupPublicMirrorEnabled = enabled)
            }
        }
    }

    fun setAutoBackupRetention(retention: AutoBackupRetention) {
        viewModelScope.launch {
            settingsAndBackupRepository.setAutoBackupRetention(retention)
            refreshAutoBackupFiles()
        }
    }

    fun refreshAutoBackupFiles() {
        viewModelScope.launch {
            val snapshots = withContext(ioDispatcher) {
                val files = settingsAndBackupRepository.listAutoBackupFiles()
                files.map { file ->
                    val sizeKb = (file.length() / 1024L).coerceAtLeast(1L)
                    val parsedEpoch = com.folioledger.app.data.backup.AutoBackupRetentionEngine
                        .parseBackupFile(file)
                        ?.epochMillis
                    val timestamp = parsedEpoch ?: file.lastModified()
                    BackupSnapshotDisplayItem(
                        file = file,
                        fileName = file.name,
                        sizeKb = sizeKb,
                        relativeTimeText = com.folioledger.app.domain.util.BackupTimeFormatter.formatBackupRelativeTime(timestamp)
                    )
                }
            }
            controlState.update { it.copy(autoBackupSnapshots = snapshots) }
        }
    }

    fun exportAllTransactionsToCsv(destinationUriString: String) {
        launchFileOperation(
            statusLabel = "Exporting full ledger to CSV...",
            guardDemoMode = true,
            demoBlockedMessage = "Exit Demo Sandbox before exporting ledger CSVs."
        ) {
            val result = settingsAndBackupRepository.exportTransactionsToCsv(
                destinationUriString = destinationUriString,
                startEpochDay = null,
                endEpochDay = null
            )
            result.fold(
                onSuccess = { count ->
                    val cleanFileName = settingsAndBackupRepository.resolveDisplayName(
                        uriString = destinationUriString,
                        fallback = "Folio_Ledger_Export.csv"
                    )
                    val exportedFile = settingsAndBackupRepository.getExportedFile(cleanFileName)
                    controlState.update {
                        it.copy(
                            lastCompletedFileForShare = null,
                            lastExportedFileSuccess = FileExportSuccessUiModel(
                                title = "Ledger Export Complete!",
                                subtitle = "$count transactions exported to CSV",
                                fileName = cleanFileName,
                                fileSizeBytes = exportedFile?.length() ?: 0L,
                                file = exportedFile,
                                publicDisplayPath = "Downloads/Folio/$cleanFileName"
                            ),
                            snackbarMessage = null
                        )
                    }
                },
                onFailure = { error ->
                    controlState.update {
                        it.copy(
                            lastCompletedFileForShare = null,
                            lastExportedFileSuccess = null,
                            snackbarMessage = error.message ?: "CSV export failed"
                        )
                    }
                }
            )
        }
    }

    fun downloadCsvTemplate(destinationFileName: String = "Folio_Import_Template.csv") {
        launchFileOperation(
            statusLabel = "Generating CSV import template..."
        ) {
            val result = settingsAndBackupRepository.exportCsvTemplate(destinationFileName)
            result.fold(
                onSuccess = { file ->
                    val cleanFileName = destinationFileName
                        .substringAfterLast('/')
                        .substringAfterLast('\\')
                        .ifBlank { "Folio_Import_Template.csv" }
                    val exportedFile = file ?: settingsAndBackupRepository.getExportedFile(cleanFileName)
                    controlState.update {
                        it.copy(
                            lastCompletedFileForShare = null,
                            lastExportedFileSuccess = FileExportSuccessUiModel(
                                title = "Template Downloaded!",
                                subtitle = "Side-by-side CSV template ready for entry",
                                fileName = cleanFileName,
                                fileSizeBytes = exportedFile?.length() ?: 0L,
                                file = exportedFile,
                                publicDisplayPath = "Downloads/Folio/$cleanFileName"
                            ),
                            snackbarMessage = null
                        )
                    }
                },
                onFailure = { error ->
                    controlState.update {
                        it.copy(
                            lastCompletedFileForShare = null,
                            lastExportedFileSuccess = null,
                            snackbarMessage = error.message ?: "Failed to generate CSV template"
                        )
                    }
                }
            )
        }
    }

    fun clearAllData() {
        launchFileOperation(
            statusLabel = "Clearing all local data...",
            nonCancellable = true,
            guardDemoMode = true,
            demoBlockedMessage = "Exit Demo Sandbox before clearing all local data."
        ) {
            val result = settingsAndBackupRepository.clearAllData()
            result.fold(
                onSuccess = {
                    controlState.update {
                        it.copy(snackbarMessage = "All ledger data cleared and reset to default state")
                    }
                    refreshAutoBackupFiles()
                },
                onFailure = { error ->
                    controlState.update {
                        it.copy(snackbarMessage = error.message ?: "Failed to clear local data")
                    }
                }
            )
        }
    }

    fun dismissWelcomeSheet() {
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { it.copy(hasSeenWelcomeSheet = true) }
        }
    }

    fun completeOnboardingFresh(currencyCode: String, grouping: NumberGroupingSystem) {
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                applyFreshOnboardingPreferences(current, currencyCode, grouping)
            }
        }
    }

    fun enterDemoMode(
        currencyCode: String? = null,
        numberGrouping: NumberGroupingSystem? = null,
        onComplete: () -> Unit = {}
    ) {
        launchFileOperation(
            statusLabel = "Preparing interactive demo sandbox...",
            nonCancellable = true
        ) {
            if (numberGrouping != null) {
                settingsAndBackupRepository.updateUserPreferences { it.copy(numberGrouping = numberGrouping) }
            }
            val result = settingsAndBackupRepository.enterDemoMode(currencyCode)
            result.fold(
                onSuccess = {
                    controlState.update {
                        it.copy(snackbarMessage = "Demo mode active. Your real ledger is safely preserved.")
                    }
                    onComplete()
                },
                onFailure = { error ->
                    controlState.update {
                        it.copy(snackbarMessage = error.message ?: "Failed to enter demo mode")
                    }
                }
            )
        }
    }

    fun exitDemoMode(onComplete: () -> Unit = {}) {
        launchFileOperation(
            statusLabel = "Restoring your real ledger...",
            nonCancellable = true
        ) {
            val result = settingsAndBackupRepository.exitDemoMode()
            result.fold(
                onSuccess = {
                    controlState.update {
                        it.copy(snackbarMessage = "Ledger restored successfully")
                    }
                    onComplete()
                },
                onFailure = { error ->
                    controlState.update {
                        it.copy(snackbarMessage = error.message ?: "Failed to restore ledger")
                    }
                }
            )
        }
    }

    fun loadCsvPreview(uriString: String, preset: CsvImportPreset = CsvImportPreset.AUTO_DETECT) {
        launchFileOperation(
            statusLabel = "Analyzing CSV file...",
            guardDemoMode = true,
            demoBlockedMessage = "Exit Demo Sandbox before importing CSV files."
        ) {
            val result = settingsAndBackupRepository.previewTransactionsCsv(uriString, preset)
            result.fold(
                onSuccess = { preview ->
                    controlState.update { it.copy(csvPreview = preview) }
                },
                onFailure = { error ->
                    controlState.update {
                        it.copy(snackbarMessage = error.message ?: "Failed to read CSV file")
                    }
                }
            )
        }
    }

    fun repreviewCsvWithPreset(newPreset: CsvImportPreset) {
        val current = controlState.value.csvPreview ?: return
        loadCsvPreview(current.sourceUriString, newPreset)
    }

    fun confirmCsvImport(skipDuplicates: Boolean = true) {
        val preview = controlState.value.csvPreview ?: return
        val resolvedPreset = preview.selectedPreset.takeIf { it != CsvImportPreset.AUTO_DETECT } ?: preview.detectedPreset
        launchFileOperation(
            statusLabel = "Importing transactions to ledger...",
            nonCancellable = true,
            guardDemoMode = true,
            demoBlockedMessage = "Exit Demo Sandbox before importing CSV files."
        ) {
            controlState.update { it.copy(csvPreview = null) }
            val result = settingsAndBackupRepository.importTransactionsFromCsv(
                sourceUriString = preview.sourceUriString,
                skipDuplicates = skipDuplicates,
                preset = resolvedPreset
            )
            result.fold(
                onSuccess = { importResult ->
                    controlState.update {
                        it.copy(
                            csvImportResult = importResult,
                            snackbarMessage = "Imported ${importResult.importedCount} transactions"
                        )
                    }
                },
                onFailure = { error ->
                    controlState.update {
                        it.copy(snackbarMessage = error.message ?: "CSV import failed")
                    }
                }
            )
        }
    }

    fun dismissCsvPreview() {
        val uriStr = controlState.value.csvPreview?.sourceUriString
        if (uriStr != null) {
            viewModelScope.launch(ioDispatcher) {
                settingsAndBackupRepository.cleanupCachedCsvPreview(uriStr)
            }
        }
        controlState.update { it.copy(csvPreview = null) }
    }

    fun dismissCsvImportResult() {
        controlState.update { it.copy(csvImportResult = null) }
    }

    fun clearSnackbarMessage() {
        val currentUiMessage = uiState.value.snackbarMessage
        controlState.update { current ->
            if (current.snackbarMessage != null) {
                current.copy(snackbarMessage = null)
            } else {
                current.copy(dismissedComputedError = currentUiMessage)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // PDF Statements & Tax Reports Actions
    // ---------------------------------------------------------------------------------------------

    fun openPdfReportsSheet() {
        controlState.update { it.copy(isPdfReportsSheetOpen = true) }
    }

    fun closePdfReportsSheet() {
        controlState.update { it.copy(isPdfReportsSheetOpen = false) }
    }

    fun clearLastGeneratedPdfResult() {
        controlState.update { it.copy(lastGeneratedPdfResult = null) }
    }

    fun generateAccountStatementPdf(
        accountId: Long,
        startEpochDay: Long,
        endEpochDay: Long,
        includeNotesAndTags: Boolean = true,
        includeRunningBalance: Boolean = true
    ) {
        launchFileOperation(
            statusLabel = "Generating PDF Statement...",
            isPdf = true
        ) {
            val result = settingsAndBackupRepository.generateAccountStatementPdf(
                accountId = accountId,
                startEpochDay = startEpochDay,
                endEpochDay = endEpochDay,
                includeNotesAndTags = includeNotesAndTags,
                includeRunningBalance = includeRunningBalance
            )
            result.fold(
                onSuccess = { pdfResult ->
                    controlState.update {
                        it.copy(
                            lastGeneratedPdfResult = pdfResult,
                            lastCompletedFileForShare = pdfResult.file,
                            snackbarMessage = "Saved to ${pdfResult.publicDisplayPath}"
                        )
                    }
                },
                onFailure = { error ->
                    controlState.update {
                        it.copy(snackbarMessage = error.message ?: "Failed to generate PDF statement")
                    }
                }
            )
        }
    }

    fun generateAnnualTaxStatementPdf(
        fiscalYearType: FiscalYearType,
        anchorYear: Int,
        customStartEpochDay: Long? = null,
        customEndEpochDay: Long? = null,
        selectedTaxTags: Set<String> = emptySet()
    ) {
        launchFileOperation(
            statusLabel = "Generating Tax & Financial Report...",
            isPdf = true
        ) {
            val result = settingsAndBackupRepository.generateAnnualTaxStatementPdf(
                fiscalYearType = fiscalYearType,
                anchorYear = anchorYear,
                customStartEpochDay = customStartEpochDay,
                customEndEpochDay = customEndEpochDay,
                selectedTaxTags = selectedTaxTags
            )
            result.fold(
                onSuccess = { pdfResult ->
                    controlState.update {
                        it.copy(
                            lastGeneratedPdfResult = pdfResult,
                            lastCompletedFileForShare = pdfResult.file,
                            snackbarMessage = "Saved to ${pdfResult.publicDisplayPath}"
                        )
                    }
                },
                onFailure = { error ->
                    controlState.update {
                        it.copy(snackbarMessage = error.message ?: "Failed to generate Tax Report")
                    }
                }
            )
        }
    }

    private data class CategoriesAndMetadataBundle(
        val expenseCategories: List<CategoryNode>,
        val incomeCategories: List<CategoryNode>,
        val tags: List<String>,
        val bookmarks: List<TransactionBookmark>
    )

    companion object {
        internal fun applyFreshOnboardingPreferences(
            current: UserPreferences,
            currencyCode: String,
            grouping: NumberGroupingSystem
        ): UserPreferences {
            val spec = SupportedCurrencies.fromCode(currencyCode)
            val cleanSecondaries = current.secondaryCurrencies.filter { it.code != spec.code }
            return current.copy(
                primaryCurrencyCode = spec.code,
                secondaryCurrencies = cleanSecondaries,
                numberGrouping = grouping,
                hasSeenWelcomeSheet = true
            )
        }
    }
}