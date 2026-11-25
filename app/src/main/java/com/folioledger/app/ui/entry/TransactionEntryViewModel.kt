/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/ui/entry/TransactionEntryViewModel.kt
package com.folioledger.app.ui.entry

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.CategoryNode
import com.folioledger.app.domain.model.CategoryType
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.PayeeAutofillTemplate
import com.folioledger.app.domain.model.PayeeSuggestion
import com.folioledger.app.domain.model.RepeatFrequency
import com.folioledger.app.domain.model.SplitLineItem
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionBookmark
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.domain.repository.AccountRepository
import com.folioledger.app.domain.repository.AnalyticsAndBudgetRepository
import com.folioledger.app.domain.repository.CategoryAndTagRepository
import com.folioledger.app.domain.repository.SettingsAndBackupRepository
import com.folioledger.app.domain.repository.TransactionRepository
import com.folioledger.app.domain.util.PayeeAutofillResolution
import com.folioledger.app.domain.util.PayeeAutofillResolver
import com.folioledger.app.domain.util.PayeeSuggestionEngine
import kotlinx.coroutines.flow.first
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import com.folioledger.app.domain.util.TransactionEntryRulesEngine
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AutofillUndoSnapshot(
    val previousMode: TransactionType,
    val previousAccountId: Long?,
    val previousTransferToAccountId: Long?,
    val previousCategoryId: Long?,
    val previousCategoryName: String?,
    val previousParentCategoryName: String?,
    val previousCategoryColorHex: String?,
    val previousCategoryIconName: String?,
    val previousTags: List<String>
)

private data class EntryFormControlState(
    val editingTransactionId: Long = 0L,
    val mode: TransactionType = TransactionType.EXPENSE,
    val ledgerDateEpochDay: Long = LocalDate.now().toEpochDay(),
    val timestampEpochMillis: Long = System.currentTimeMillis(),
    val expressionBuffer: String = "0",
    val selectedAccountId: Long? = null,
    val transferToAccountId: Long? = null,
    val transferFeeInput: String = "",
    val selectedCategoryId: Long? = null,
    val selectedCategoryName: String? = null,
    val selectedParentCategoryName: String? = null,
    val selectedCategoryColorHex: String? = null,
    val selectedCategoryIconName: String? = null,
    val isSplit: Boolean = false,
    val splitDrafts: List<SplitLineDraft> = emptyList(),
    val isInstallmentEnabled: Boolean = false,
    val installmentMonthsCount: Int = 3,
    val isRecurring: Boolean = false,
    val recurringFrequency: RepeatFrequency = RepeatFrequency.EVERY_MONTH,
    val payeeOrDescription: String = "",
    val note: String = "",
    val selectedTags: List<String> = emptyList(),
    val installmentIndex: Int? = null,
    val installmentTotalCount: Int? = null,
    val linkedRecurringRuleId: Long? = null,
    val linkedRecurringRuleDueEpochDay: Long? = null,
    val isKeypadVisible: Boolean = true,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val savedSuccessfullyCounter: Int = 0,
    val formSyncVersion: Int = 0,
    val hasUserEdits: Boolean = false,
    val isAccountManuallySet: Boolean = false,
    val isDestinationManuallySet: Boolean = false,
    val isCategoryManuallySet: Boolean = false,
    val isTagsManuallySet: Boolean = false,
    val isModeManuallySet: Boolean = false,
    val autofillBannerText: String? = null,
    val autofillUndoSnapshot: AutofillUndoSnapshot? = null
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TransactionEntryViewModel(
    private val transactionRepository: TransactionRepository,
    accountRepository: AccountRepository,
    private val categoryAndTagRepository: CategoryAndTagRepository,
    settingsAndBackupRepository: SettingsAndBackupRepository,
    private val analyticsAndBudgetRepository: AnalyticsAndBudgetRepository? = null,
    private val computationDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val nextSplitLocalId = java.util.concurrent.atomic.AtomicLong(1L)
    private var lastUsedLedgerDateEpochDay: Long = LocalDate.now().toEpochDay()
    private val formState = MutableStateFlow(EntryFormControlState())

    private val allAccountsForEntryDateFlow = formState
        .map { it.ledgerDateEpochDay }
        .distinctUntilChanged()
        .flatMapLatest { refEpochDay ->
            accountRepository.observeAllAccounts(refEpochDay)
        }

    private val rawReferenceBundleFlow = combine(
        allAccountsForEntryDateFlow,
        categoryAndTagRepository.observeCategoryTree(CategoryType.EXPENSE),
        categoryAndTagRepository.observeCategoryTree(CategoryType.INCOME),
        categoryAndTagRepository.observeAllTags(),
        categoryAndTagRepository.observeBookmarks()
    ) { allAccounts, expenseCats, incomeCats, tags, bookmarks ->
        ReferenceBundle(
            allAccounts = allAccounts,
            activeAccounts = allAccounts.filter { !it.isArchived },
            expenseCategories = expenseCats,
            incomeCategories = incomeCats,
            tags = tags,
            bookmarks = bookmarks
        )
    }

    private val referenceDataFlow = rawReferenceBundleFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ReferenceBundle(
            allAccounts = emptyList(),
            activeAccounts = emptyList(),
            expenseCategories = emptyList(),
            incomeCategories = emptyList(),
            tags = emptyList(),
            bookmarks = emptyList()
        )
    )

    private val userPreferencesFlow = settingsAndBackupRepository.observeUserPreferences()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = UserPreferences()
        )

    private val payeeCatalogFlow: StateFlow<List<PayeeSuggestion>> =
        transactionRepository.observePayeeSuggestions(limit = 500)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = emptyList()
            )

    private val payeeQueryFlow = MutableStateFlow("")

    val payeeSuggestions: StateFlow<List<PayeeSuggestion>> = combine(
        payeeQueryFlow,
        formState.map { it.mode }.distinctUntilChanged(),
        payeeCatalogFlow
    ) { query, mode, catalog ->
        PayeeSuggestionEngine.rankSuggestions(
            query = query,
            currentMode = mode,
            catalog = catalog,
            maxResults = 8
        )
    }.flowOn(computationDispatcher)
     .stateIn(
         scope = viewModelScope,
         started = SharingStarted.WhileSubscribed(5_000L),
         initialValue = emptyList()
     )

    val uiState: StateFlow<TransactionEntryUiState> = combine(
        formState,
        referenceDataFlow,
        userPreferencesFlow
    ) { form, refs, prefs ->
        try {
            val isEditing = form.editingTransactionId > 0L
            val selectedAccount = if (isEditing) {
                refs.allAccounts.firstOrNull { it.id == form.selectedAccountId }
            } else {
                val explicitSource = refs.activeAccounts.firstOrNull { it.id == form.selectedAccountId }
                explicitSource ?: if (form.isAccountManuallySet) null else refs.activeAccounts.firstOrNull()
            }
            val transferToAccount = if (isEditing) {
                refs.allAccounts.firstOrNull { it.id == form.transferToAccountId }
            } else {
                val explicitDest = form.transferToAccountId?.let { id ->
                    refs.activeAccounts.firstOrNull { it.id == id && it.id != selectedAccount?.id }
                }
                explicitDest ?: if (form.isDestinationManuallySet) {
                    null
                } else {
                    resolveDefaultDestination(refs.activeAccounts, selectedAccount, form.mode)
                }
            }

            val currencySpec = SupportedCurrencies.fromCode(
                selectedAccount?.currencyCode ?: prefs.primaryCurrencyCode
            )
            val evaluatedMoney = evaluateBufferSafely(
                rawExpression = form.expressionBuffer,
                fractionDigits = currencySpec.fractionDigits
            )

            val treeForMode = if (form.mode == TransactionType.INCOME) {
                refs.incomeCategories
            } else {
                refs.expenseCategories
            }
            val defaultCategory = treeForMode.firstOrNull()

            val resolvedCategoryId = if (isEditing) {
                form.selectedCategoryId
            } else {
                form.selectedCategoryId ?: defaultCategory?.id
            }
            val resolvedCategoryNode = if (resolvedCategoryId != null) {
                findCategoryNodeById(treeForMode, resolvedCategoryId)
            } else {
                null
            }
            val resolvedParentName = if (resolvedCategoryId != null) {
                findParentCategoryName(treeForMode, resolvedCategoryId)
            } else {
                null
            }

            val resolvedSplits = form.splitDrafts.map { draft ->
                val lineMoney = evaluateBufferSafely(draft.amountInput, currencySpec.fractionDigits)
                val catNode = findCategoryNodeById(treeForMode, draft.categoryId)
                draft.copy(
                    evaluatedAmount = lineMoney,
                    categoryName = catNode?.name ?: draft.categoryName,
                    categoryColorHex = catNode?.colorHex ?: draft.categoryColorHex,
                    categoryIconName = catNode?.iconName ?: draft.categoryIconName
                )
            }

            val domainSplits = resolvedSplits.map { draft ->
                SplitLineItem(
                    id = draft.localId,
                    categoryId = draft.categoryId,
                    categoryName = draft.categoryName,
                    amount = draft.evaluatedAmount,
                    note = draft.note
                )
            }

            val splitValidation = TransactionEntryRulesEngine.validateSplitTransaction(
                parentTotal = evaluatedMoney,
                splits = domainSplits
            )

            val isCCExpense = form.mode == TransactionType.EXPENSE &&
                    selectedAccount?.groupType == AccountGroup.CREDIT_CARD &&
                    form.installmentIndex == null
            val effectiveInstallmentEnabled = form.isInstallmentEnabled && isCCExpense && !form.isSplit

            val schedulePreview = if (effectiveInstallmentEnabled) {
                TransactionEntryRulesEngine.generateInstallmentSchedule(
                    totalAmount = evaluatedMoney,
                    monthsCount = form.installmentMonthsCount,
                    startDateEpochDay = form.ledgerDateEpochDay
                )
            } else {
                emptyList()
            }

            val hasOperator = form.expressionBuffer.drop(1).any { ch ->
                ch == '+' || ch == '-' || ch == '×' || ch == '÷' || ch == '*' || ch == '/'
            }

            val keepUnresolvedCategory = resolvedCategoryId != null && (isEditing || resolvedCategoryNode != null)
            TransactionEntryUiState(
                editingTransactionId = form.editingTransactionId,
                mode = form.mode,
                ledgerDateEpochDay = form.ledgerDateEpochDay,
                timestampEpochMillis = form.timestampEpochMillis,
                expressionBuffer = form.expressionBuffer,
                evaluatedAmount = evaluatedMoney,
                expressionHasOperator = hasOperator,
                activeAccounts = refs.activeAccounts,
                selectedAccount = selectedAccount,
                transferToAccount = transferToAccount,
                transferFeeInput = form.transferFeeInput,
                expenseCategories = refs.expenseCategories,
                incomeCategories = refs.incomeCategories,
                selectedCategoryId = resolvedCategoryNode?.id ?: if (keepUnresolvedCategory) resolvedCategoryId else null,
                selectedCategoryName = resolvedCategoryNode?.name ?: if (keepUnresolvedCategory) form.selectedCategoryName else null,
                selectedParentCategoryName = resolvedParentName ?: if (keepUnresolvedCategory) form.selectedParentCategoryName else null,
                selectedCategoryColorHex = resolvedCategoryNode?.colorHex ?: if (keepUnresolvedCategory) form.selectedCategoryColorHex else null,
                selectedCategoryIconName = resolvedCategoryNode?.iconName ?: if (keepUnresolvedCategory) form.selectedCategoryIconName else null,
                isSplit = form.isSplit && (form.mode == TransactionType.EXPENSE || form.mode == TransactionType.INCOME),
                splitDrafts = resolvedSplits,
                splitValidation = splitValidation,
                isInstallmentEnabled = effectiveInstallmentEnabled,
                installmentMonthsCount = form.installmentMonthsCount,
                installmentSchedulePreview = schedulePreview,
                isRecurring = form.isRecurring,
                recurringFrequency = form.recurringFrequency,
                payeeOrDescription = form.payeeOrDescription,
                note = form.note,
                installmentIndex = form.installmentIndex,
                installmentTotalCount = form.installmentTotalCount,
                selectedTags = form.selectedTags,
                availableTags = refs.tags,
                bookmarks = refs.bookmarks,
                currencySpec = currencySpec,
                numberGrouping = prefs.numberGrouping,
                subcategoriesEnabled = prefs.subcategoriesEnabled,
                linkedRecurringRuleId = form.linkedRecurringRuleId,
                isKeypadVisible = form.isKeypadVisible,
                isSaving = form.isSaving,
                errorMessage = form.errorMessage,
                savedSuccessfullyCounter = form.savedSuccessfullyCounter,
                formSyncVersion = form.formSyncVersion,
                hasUserEdits = form.hasUserEdits,
                accountCurrencySpecs = refs.allAccounts.associate { it.id to SupportedCurrencies.fromCode(it.currencyCode) },
                autofillBannerText = form.autofillBannerText,
                canUndoAutofill = form.autofillUndoSnapshot != null
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            TransactionEntryUiState(
                editingTransactionId = form.editingTransactionId,
                ledgerDateEpochDay = form.ledgerDateEpochDay,
                timestampEpochMillis = form.timestampEpochMillis,
                formSyncVersion = form.formSyncVersion,
                errorMessage = "Entry calculation error: ${t.localizedMessage ?: "Invalid input"}"
            )
        }
    }.flowOn(computationDispatcher)
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = TransactionEntryUiState(
            ledgerDateEpochDay = LocalDate.now().toEpochDay(),
            timestampEpochMillis = System.currentTimeMillis()
        )
    )

    private var autofillJob: kotlinx.coroutines.Job? = null
    private var bookmarkLoadJob: kotlinx.coroutines.Job? = null
    private val isCreatingCategory = java.util.concurrent.atomic.AtomicBoolean(false)

    fun resetForNewTransaction(
        defaultMode: TransactionType = TransactionType.EXPENSE,
        preselectedAccountId: Long? = null,
        initialEpochDay: Long? = null
    ) {
        autofillJob?.cancel()
        bookmarkLoadJob?.cancel()
        val targetEpochDay = initialEpochDay ?: lastUsedLedgerDateEpochDay
        lastUsedLedgerDateEpochDay = targetEpochDay
        payeeQueryFlow.value = ""
        val activeIds = referenceDataFlow.value.activeAccounts.map { it.id }.toSet()
        formState.update { current ->
            val candidateAccountId = preselectedAccountId
                ?: current.selectedAccountId?.takeIf { activeIds.isEmpty() || it in activeIds }
            EntryFormControlState(
                editingTransactionId = 0L,
                mode = defaultMode,
                ledgerDateEpochDay = targetEpochDay,
                timestampEpochMillis = buildAnchoredTimestamp(targetEpochDay),
                expressionBuffer = "0",
                selectedAccountId = candidateAccountId,
                isKeypadVisible = true,
                savedSuccessfullyCounter = current.savedSuccessfullyCounter,
                formSyncVersion = current.formSyncVersion + 1,
                isAccountManuallySet = preselectedAccountId != null,
                isDestinationManuallySet = false,
                isCategoryManuallySet = false,
                isTagsManuallySet = false,
                isModeManuallySet = false,
                autofillBannerText = null,
                autofillUndoSnapshot = null
            )
        }
    }

    fun loadTransactionItemForEdit(tx: TransactionItem): Boolean {
        if (tx.type == TransactionType.OPENING_BALANCE || tx.type == TransactionType.RECONCILIATION) {
            return false
        }
        autofillJob?.cancel()
        bookmarkLoadJob?.cancel()
        val spec = SupportedCurrencies.fromCode(tx.currencyCode)
        val plainAmount = formatMinorPlain(tx.amount.abs(), spec.fractionDigits)
        val draftSplits = tx.splits.map { split ->
            SplitLineDraft(
                localId = nextSplitLocalId.getAndIncrement(),
                categoryId = split.categoryId ?: 0L,
                categoryName = split.categoryName.ifBlank { "Category" },
                amountInput = formatMinorPlain(split.amount.abs(), spec.fractionDigits),
                evaluatedAmount = split.amount.abs(),
                note = split.note
            )
        }
        lastUsedLedgerDateEpochDay = tx.ledgerDateEpochDay
        payeeQueryFlow.value = tx.payeeOrDescription

        formState.update { current ->
            EntryFormControlState(
                editingTransactionId = tx.id,
                mode = tx.type,
                ledgerDateEpochDay = tx.ledgerDateEpochDay,
                timestampEpochMillis = tx.timestampEpochMillis,
                expressionBuffer = plainAmount,
                selectedAccountId = tx.accountId,
                transferToAccountId = tx.transferToAccountId,
                transferFeeInput = if (tx.transferFee.isPositive) {
                    formatMinorPlain(tx.transferFee, spec.fractionDigits)
                } else {
                    ""
                },
                selectedCategoryId = tx.categoryId,
                selectedCategoryName = tx.categoryName,
                selectedParentCategoryName = tx.parentCategoryName,
                selectedCategoryColorHex = tx.categoryColorHex,
                selectedCategoryIconName = tx.categoryIconName,
                isSplit = tx.isSplit,
                splitDrafts = draftSplits,
                isInstallmentEnabled = false,
                isRecurring = false,
                linkedRecurringRuleId = tx.recurringRuleId,
                installmentIndex = tx.installmentIndex,
                installmentTotalCount = tx.installmentTotalCount,
                payeeOrDescription = tx.payeeOrDescription,
                note = tx.note,
                selectedTags = tx.tags,
                isKeypadVisible = true,
                isSaving = false,
                errorMessage = null,
                savedSuccessfullyCounter = current.savedSuccessfullyCounter,
                formSyncVersion = current.formSyncVersion + 1,
                isAccountManuallySet = true,
                isDestinationManuallySet = true,
                isCategoryManuallySet = true,
                isTagsManuallySet = true,
                isModeManuallySet = true,
                autofillBannerText = null,
                autofillUndoSnapshot = null
            )
        }
        return true
    }

    @Suppress("unused")
    fun loadTransactionForEdit(transactionId: Long) {
        viewModelScope.launch {
            val tx = transactionRepository.observeTransactionById(transactionId).firstOrNull() ?: return@launch
            loadTransactionItemForEdit(tx)
        }
    }

    fun setMode(mode: TransactionType) {
        autofillJob?.cancel()
        val refs = referenceDataFlow.value
        val activeAccounts = refs.activeAccounts
        formState.update { current ->
            val isTransferMode = mode == TransactionType.TRANSFER || mode == TransactionType.CC_SETTLEMENT
            val oldIsIncome = current.mode == TransactionType.INCOME
            val newIsIncome = mode == TransactionType.INCOME
            val clearCategory = isTransferMode || (oldIsIncome != newIsIncome)
            val supportsRecurring = mode == TransactionType.EXPENSE ||
                mode == TransactionType.INCOME ||
                mode == TransactionType.TRANSFER
            val nextIsSplit = (mode == TransactionType.EXPENSE || mode == TransactionType.INCOME) && current.isSplit

            val sanitizedPayee = if (!isTransferMode && current.payeeOrDescription.contains(" → ")) {
                payeeQueryFlow.value = ""
                ""
            } else {
                current.payeeOrDescription
            }

            val nextSplitDrafts = when {
                !nextIsSplit -> if (clearCategory) emptyList() else current.splitDrafts
                clearCategory -> {
                    val selectedAccount = (refs.allAccounts.firstOrNull { it.id == current.selectedAccountId }
                        ?: activeAccounts.firstOrNull())
                    val digits = SupportedCurrencies.fromCode(
                        selectedAccount?.currencyCode ?: userPreferencesFlow.value.primaryCurrencyCode
                    ).fractionDigits
                    val currentTotal = evaluateBufferSafely(current.expressionBuffer, digits)
                    val tree = if (newIsIncome) refs.incomeCategories else refs.expenseCategories
                    createInitialSplitDrafts(
                        totalAmount = currentTotal,
                        fractionDigits = digits,
                        defaultCat = tree.firstOrNull(),
                        preselectedCategoryId = null,
                        preselectedCategoryName = null,
                        preselectedColorHex = null,
                        preselectedIconName = null
                    )
                }
                else -> current.splitDrafts
            }

            val didPayeeOrSplitChange = sanitizedPayee != current.payeeOrDescription ||
                nextSplitDrafts != current.splitDrafts ||
                (!isTransferMode && current.transferFeeInput.isNotEmpty())

            val keepInstallmentMetadata = mode == TransactionType.EXPENSE && !nextIsSplit

            current.copy(
                mode = mode,
                transferToAccountId = if (isTransferMode) current.transferToAccountId else null,
                transferFeeInput = if (isTransferMode) current.transferFeeInput else "",
                isDestinationManuallySet = if (isTransferMode) current.isDestinationManuallySet else false,
                selectedCategoryId = if (clearCategory) null else current.selectedCategoryId,
                selectedCategoryName = if (clearCategory) null else current.selectedCategoryName,
                selectedParentCategoryName = if (clearCategory) null else current.selectedParentCategoryName,
                selectedCategoryColorHex = if (clearCategory) null else current.selectedCategoryColorHex,
                selectedCategoryIconName = if (clearCategory) null else current.selectedCategoryIconName,
                isCategoryManuallySet = if (clearCategory) false else current.isCategoryManuallySet,
                isSplit = nextIsSplit,
                splitDrafts = nextSplitDrafts,
                isInstallmentEnabled = (mode == TransactionType.EXPENSE) && current.isInstallmentEnabled,
                installmentIndex = if (keepInstallmentMetadata) current.installmentIndex else null,
                installmentTotalCount = if (keepInstallmentMetadata) current.installmentTotalCount else null,
                isRecurring = current.isRecurring && supportsRecurring,
                payeeOrDescription = sanitizedPayee,
                isModeManuallySet = true,
                hasUserEdits = true,
                autofillBannerText = null,
                autofillUndoSnapshot = null,
                formSyncVersion = if (didPayeeOrSplitChange) current.formSyncVersion + 1 else current.formSyncVersion,
                errorMessage = null
            )
        }
    }

    fun setLedgerDateEpochDay(epochDay: Long) {
        lastUsedLedgerDateEpochDay = epochDay
        formState.update { current ->
            val zone = ZoneId.systemDefault()
            val currentTime = Instant.ofEpochMilli(current.timestampEpochMillis).atZone(zone).toLocalTime()
            val newDate = LocalDate.ofEpochDay(epochDay)
            val newTimestamp = newDate.atTime(currentTime).atZone(zone).toInstant().toEpochMilli()
            current.copy(
                ledgerDateEpochDay = epochDay,
                timestampEpochMillis = newTimestamp,
                hasUserEdits = true,
                errorMessage = null
            )
        }
    }

    fun stepLedgerDateByDays(deltaDays: Long) {
        formState.update { current ->
            val nextDateEpochDay = current.ledgerDateEpochDay + deltaDays
            lastUsedLedgerDateEpochDay = nextDateEpochDay
            val zone = ZoneId.systemDefault()
            val currentTime = Instant.ofEpochMilli(current.timestampEpochMillis).atZone(zone).toLocalTime()
            val newDate = LocalDate.ofEpochDay(nextDateEpochDay)
            val newTimestamp = newDate.atTime(currentTime).atZone(zone).toInstant().toEpochMilli()
            current.copy(
                ledgerDateEpochDay = nextDateEpochDay,
                timestampEpochMillis = newTimestamp,
                hasUserEdits = true,
                errorMessage = null
            )
        }
    }

    fun setLedgerTime(hour: Int, minute: Int) {
        formState.update { current ->
            val date = LocalDate.ofEpochDay(current.ledgerDateEpochDay)
            val time = LocalTime.of(hour, minute)
            val zone = ZoneId.systemDefault()
            val newTimestamp = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
            current.copy(
                timestampEpochMillis = newTimestamp,
                hasUserEdits = true,
                errorMessage = null
            )
        }
    }

    fun selectSourceAccount(accountId: Long) {
        autofillJob?.cancel()
        val refs = referenceDataFlow.value
        val activeAccounts = refs.activeAccounts
        val allAccounts = refs.allAccounts
        formState.update { current ->
            val oldAcc = allAccounts.firstOrNull { it.id == current.selectedAccountId }
                ?: activeAccounts.firstOrNull()
            val sourceAcc = allAccounts.firstOrNull { it.id == accountId }
            val oldSpec = SupportedCurrencies.fromCode(
                oldAcc?.currencyCode ?: userPreferencesFlow.value.primaryCurrencyCode
            )
            val newSpec = SupportedCurrencies.fromCode(
                sourceAcc?.currencyCode ?: userPreferencesFlow.value.primaryCurrencyCode
            )

            val currentDest = allAccounts.firstOrNull { it.id == current.transferToAccountId }
            val isDestCurrencyMismatch = sourceAcc != null && currentDest != null &&
                !currentDest.currencyCode.equals(sourceAcc.currencyCode, ignoreCase = true)
            val isDestInvalidForSettlement = current.mode == TransactionType.CC_SETTLEMENT &&
                currentDest != null && currentDest.groupType != AccountGroup.CREDIT_CARD
            val nextTransferTo = if (current.transferToAccountId == accountId || isDestCurrencyMismatch || isDestInvalidForSettlement) {
                resolveDefaultDestination(activeAccounts, sourceAcc, current.mode)?.id
            } else {
                current.transferToAccountId
            }

            val shouldRescaleDown = newSpec.fractionDigits < oldSpec.fractionDigits
            val nextBuffer = if (shouldRescaleDown) {
                rescaleExpressionDown(current.expressionBuffer, oldSpec.fractionDigits, newSpec.fractionDigits)
            } else {
                current.expressionBuffer
            }

            val nextTransferFee = if (shouldRescaleDown && current.transferFeeInput.isNotBlank()) {
                rescaleOptionalFeeDown(current.transferFeeInput, oldSpec.fractionDigits, newSpec.fractionDigits)
            } else {
                current.transferFeeInput
            }

            val nextSplits = if (shouldRescaleDown && current.splitDrafts.isNotEmpty()) {
                rescaleSplitsDown(current.splitDrafts, oldSpec.fractionDigits, newSpec.fractionDigits)
            } else {
                current.splitDrafts
            }

            val isCreditCardExpenseTarget = current.mode == TransactionType.EXPENSE &&
                sourceAcc?.groupType == AccountGroup.CREDIT_CARD &&
                !current.isSplit
            val keepInstallment = current.isInstallmentEnabled &&
                isCreditCardExpenseTarget &&
                current.installmentIndex == null

            val didRescaleAnyField = shouldRescaleDown && (
                nextBuffer != current.expressionBuffer ||
                    nextTransferFee != current.transferFeeInput ||
                    nextSplits != current.splitDrafts
                )

            current.copy(
                selectedAccountId = accountId,
                transferToAccountId = nextTransferTo,
                expressionBuffer = nextBuffer,
                transferFeeInput = nextTransferFee,
                splitDrafts = nextSplits,
                isInstallmentEnabled = keepInstallment,
                installmentIndex = if (isCreditCardExpenseTarget) current.installmentIndex else null,
                installmentTotalCount = if (isCreditCardExpenseTarget) current.installmentTotalCount else null,
                formSyncVersion = if (didRescaleAnyField) current.formSyncVersion + 1 else current.formSyncVersion,
                isAccountManuallySet = true,
                hasUserEdits = true,
                autofillBannerText = null,
                autofillUndoSnapshot = null,
                errorMessage = null
            )
        }
    }

    fun selectTransferDestinationAccount(accountId: Long) {
        autofillJob?.cancel()
        val refs = referenceDataFlow.value
        val dest = refs.allAccounts.firstOrNull { it.id == accountId }
        val sourceId = formState.value.selectedAccountId
        val source = refs.allAccounts.firstOrNull { it.id == sourceId } ?: refs.activeAccounts.firstOrNull()
        if (dest != null && source != null && !dest.currencyCode.equals(source.currencyCode, ignoreCase = true)) {
            formState.update {
                it.copy(
                    transferToAccountId = accountId,
                    isDestinationManuallySet = true,
                    hasUserEdits = true,
                    autofillBannerText = null,
                    autofillUndoSnapshot = null,
                    errorMessage = "Transfer accounts must use the same currency (${source.currencyCode} \u2260 ${dest.currencyCode})."
                )
            }
        } else {
            formState.update {
                it.copy(
                    transferToAccountId = accountId,
                    isDestinationManuallySet = true,
                    hasUserEdits = true,
                    autofillBannerText = null,
                    autofillUndoSnapshot = null,
                    errorMessage = null
                )
            }
        }
    }

    fun updateTransferFeeInput(feeInput: String) {
        formState.update { it.copy(transferFeeInput = feeInput, hasUserEdits = true, errorMessage = null) }
    }

    fun selectCategory(category: CategoryNode, parentName: String? = null) {
        autofillJob?.cancel()
        formState.update {
            it.copy(
                selectedCategoryId = category.id,
                selectedCategoryName = category.name,
                selectedParentCategoryName = parentName,
                selectedCategoryColorHex = category.colorHex,
                selectedCategoryIconName = category.iconName,
                isCategoryManuallySet = true,
                hasUserEdits = true,
                autofillBannerText = null,
                autofillUndoSnapshot = null,
                errorMessage = null
            )
        }
    }

    fun createAndSelectCategory(
        name: String,
        parentId: Long? = null,
        parentName: String? = null,
        iconName: String,
        colorHex: String,
        splitLineLocalId: Long? = null,
        onFailure: (() -> Unit)? = null,
        onSuccess: ((CategoryNode) -> Unit)? = null
    ) {
        if (!isCreatingCategory.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val type = if (formState.value.mode == TransactionType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
                val newNode = CategoryNode(
                    id = 0L,
                    name = name.trim(),
                    type = type,
                    parentId = parentId,
                    iconName = iconName,
                    colorHex = colorHex
                )
                val result = categoryAndTagRepository.upsertCategory(newNode)
                result.onSuccess { createdId ->
                    val createdNode = CategoryNode(
                        id = createdId,
                        name = name.trim(),
                        type = type,
                        parentId = parentId,
                        iconName = iconName,
                        colorHex = colorHex
                    )
                    if (splitLineLocalId != null) {
                        updateSplitLineCategory(splitLineLocalId, createdNode)
                    } else {
                        selectCategory(createdNode, parentName)
                    }
                    onSuccess?.invoke(createdNode)
                }.onFailure { err ->
                    formState.update {
                        it.copy(errorMessage = err.message ?: "Unable to create category.")
                    }
                    onFailure?.invoke()
                }
            } finally {
                isCreatingCategory.set(false)
            }
        }
    }

    fun setKeypadVisible(visible: Boolean) {
        formState.update { it.copy(isKeypadVisible = visible) }
    }

    fun updateAmountInput(rawInput: String) {
        val currentForm = formState.value
        val refs = referenceDataFlow.value
        val selectedAccount = refs.allAccounts.firstOrNull { it.id == currentForm.selectedAccountId }
            ?: refs.activeAccounts.firstOrNull()
        val fractionDigits = SupportedCurrencies.fromCode(
            selectedAccount?.currencyCode ?: userPreferencesFlow.value.primaryCurrencyCode
        ).fractionDigits

        val normalized = EntryAmountInputSanitizer.sanitizeExpressionInput(
            rawInput = rawInput,
            fractionDigits = fractionDigits
        )
        val cleaned = normalized.ifEmpty { "0" }
        formState.update { current ->
            current.copy(expressionBuffer = cleaned, hasUserEdits = true, errorMessage = null)
        }
    }

    fun onKeypadKeyPress(key: String) {
        val currentForm = formState.value
        val refs = referenceDataFlow.value
        val selectedAccount = refs.allAccounts.firstOrNull { it.id == currentForm.selectedAccountId }
            ?: refs.activeAccounts.firstOrNull()
        val currencyCode = selectedAccount?.currencyCode ?: userPreferencesFlow.value.primaryCurrencyCode
        val fractionDigits = SupportedCurrencies.fromCode(currencyCode).fractionDigits
        formState.update { current ->
            val buf = current.expressionBuffer
            if (key == "=") {
                val evalResult = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor(
                    expression = buf.trim(),
                    fractionDigits = fractionDigits
                )
                return@update evalResult.fold(
                    onSuccess = { evaluated ->
                        current.copy(
                            expressionBuffer = formatMinorPlain(evaluated, fractionDigits),
                            hasUserEdits = true,
                            formSyncVersion = current.formSyncVersion + 1,
                            errorMessage = null
                        )
                    },
                    onFailure = { err ->
                        current.copy(
                            errorMessage = err.message ?: "Invalid expression."
                        )
                    }
                )
            }
            val nextBuffer = when (key) {
                "C" -> "0"
                "⌫" -> {
                    if (buf.length <= 1) "0" else buf.dropLast(1)
                }
                "+", "-", "×", "÷" -> {
                    if (buf.isEmpty() || buf == "0") {
                        buf
                    } else if (buf.last() in charArrayOf('+', '-', '×', '÷', '*', '/')) {
                        buf.dropLast(1) + key
                    } else {
                        buf + key
                    }
                }
                "." -> {
                    val lastNumberToken = buf.split('+', '-', '×', '÷', '*', '/').lastOrNull().orEmpty()
                    if (fractionDigits == 0 || lastNumberToken.contains('.') || lastNumberToken.contains(',')) {
                        buf
                    } else {
                        "$buf."
                    }
                }
                "00" -> {
                    if (buf == "0") {
                        "0"
                    } else {
                        val lastNumberToken = buf.split('+', '-', '×', '÷', '*', '/').lastOrNull().orEmpty()
                        val intPart = lastNumberToken.substringBefore('.').substringBefore(',')
                        val fracPart = when {
                            lastNumberToken.contains('.') -> lastNumberToken.substringAfter('.')
                            lastNumberToken.contains(',') -> lastNumberToken.substringAfter(',')
                            else -> null
                        }
                        if (fracPart != null) {
                            val remainingDecimals = fractionDigits - fracPart.length
                            when {
                                remainingDecimals >= 2 -> "${buf}00"
                                remainingDecimals == 1 -> "${buf}0"
                                else -> buf
                            }
                        } else {
                            if (intPart.length + 2 <= 12) "${buf}00" else buf
                        }
                    }
                }
                else -> {
                    if (key.all { it.isDigit() }) {
                        val lastNumberToken = buf.split('+', '-', '×', '÷', '*', '/').lastOrNull().orEmpty()
                        val intPart = lastNumberToken.substringBefore('.').substringBefore(',')
                        val fracPart = when {
                            lastNumberToken.contains('.') -> lastNumberToken.substringAfter('.')
                            lastNumberToken.contains(',') -> lastNumberToken.substringAfter(',')
                            else -> null
                        }
                        val canAppend = if (fracPart != null) {
                            fracPart.length + key.length <= fractionDigits
                        } else {
                            intPart.length + key.length <= 12
                        }
                        if (!canAppend) {
                            buf
                        } else if (buf == "0") {
                            key
                        } else {
                            buf + key
                        }
                    } else {
                        if (buf == "0") key else buf + key
                    }
                }
            }
            current.copy(
                expressionBuffer = nextBuffer.take(64),
                formSyncVersion = current.formSyncVersion + 1,
                hasUserEdits = true,
                errorMessage = null
            )
        }
    }

    fun toggleSplitMode() {
        val currentForm = formState.value
        val refs = referenceDataFlow.value
        val selectedAccount = refs.allAccounts.firstOrNull { it.id == currentForm.selectedAccountId }
            ?: refs.activeAccounts.firstOrNull()
        val currencySpec = SupportedCurrencies.fromCode(
            selectedAccount?.currencyCode ?: userPreferencesFlow.value.primaryCurrencyCode
        )
        val treeForMode = if (currentForm.mode == TransactionType.INCOME) refs.incomeCategories else refs.expenseCategories
        val defaultCat = treeForMode.firstOrNull()
        val digits = currencySpec.fractionDigits
        val currentTotal = evaluateBufferSafely(currentForm.expressionBuffer, digits)

        formState.update { current ->
            val enabling = !current.isSplit
            val initialDrafts = if (enabling && current.splitDrafts.isEmpty()) {
                createInitialSplitDrafts(
                    totalAmount = currentTotal,
                    fractionDigits = digits,
                    defaultCat = defaultCat,
                    preselectedCategoryId = current.selectedCategoryId,
                    preselectedCategoryName = current.selectedCategoryName,
                    preselectedColorHex = current.selectedCategoryColorHex,
                    preselectedIconName = current.selectedCategoryIconName
                )
            } else {
                current.splitDrafts
            }

            current.copy(
                isSplit = enabling,
                isInstallmentEnabled = !enabling && current.isInstallmentEnabled,
                installmentIndex = if (enabling) null else current.installmentIndex,
                installmentTotalCount = if (enabling) null else current.installmentTotalCount,
                isRecurring = if (enabling) false else current.isRecurring,
                splitDrafts = initialDrafts,
                formSyncVersion = current.formSyncVersion + 1,
                hasUserEdits = true,
                errorMessage = null
            )
        }
    }

    fun addSplitLine() {
        val currentForm = formState.value
        val refs = referenceDataFlow.value
        val selectedAccount = refs.allAccounts.firstOrNull { it.id == currentForm.selectedAccountId }
            ?: refs.activeAccounts.firstOrNull()
        val currencySpec = SupportedCurrencies.fromCode(
            selectedAccount?.currencyCode ?: userPreferencesFlow.value.primaryCurrencyCode
        )
        val treeForMode = if (currentForm.mode == TransactionType.INCOME) refs.incomeCategories else refs.expenseCategories
        val defaultCat = treeForMode.firstOrNull()
        val digits = currencySpec.fractionDigits
        val parentTotal = evaluateBufferSafely(currentForm.expressionBuffer, digits)
        var totalAllocatedMinor = 0L
        for (draft in currentForm.splitDrafts) {
            totalAllocatedMinor = MoneyFormatterAndCalculator.safeAdd(
                totalAllocatedMinor,
                evaluateBufferSafely(draft.amountInput, digits).amountMinor
            )
        }
        val remainingMinor = MoneyFormatterAndCalculator.safeSubtract(parentTotal.amountMinor, totalAllocatedMinor)
            .coerceAtLeast(0L)
        val initialInput = if (remainingMinor > 0L) {
            formatMinorPlain(MinorMoney(remainingMinor), digits)
        } else {
            "0"
        }

        formState.update { current ->
            current.copy(
                splitDrafts = current.splitDrafts + SplitLineDraft(
                    localId = nextSplitLocalId.getAndIncrement(),
                    categoryId = defaultCat?.id ?: 0L,
                    categoryName = defaultCat?.name ?: "Select Category",
                    categoryColorHex = defaultCat?.colorHex ?: "#059669",
                    categoryIconName = defaultCat?.iconName ?: "category",
                    amountInput = initialInput
                ),
                formSyncVersion = current.formSyncVersion + 1,
                hasUserEdits = true,
                errorMessage = null
            )
        }
    }

    fun updateSplitLineAmount(localId: Long, amountInput: String) {
        val currentForm = formState.value
        val refs = referenceDataFlow.value
        val selectedAccount = refs.allAccounts.firstOrNull { it.id == currentForm.selectedAccountId }
            ?: refs.activeAccounts.firstOrNull()
        val fractionDigits = SupportedCurrencies.fromCode(
            selectedAccount?.currencyCode ?: userPreferencesFlow.value.primaryCurrencyCode
        ).fractionDigits
        val sanitized = EntryAmountInputSanitizer.sanitizeDecimalInput(
            rawInput = amountInput,
            fractionDigits = fractionDigits
        )
        formState.update { current ->
            current.copy(
                splitDrafts = current.splitDrafts.map { draft ->
                    if (draft.localId == localId) draft.copy(amountInput = sanitized) else draft
                },
                hasUserEdits = true,
                errorMessage = null
            )
        }
    }

    fun updateSplitLineCategory(localId: Long, category: CategoryNode) {
        formState.update { current ->
            current.copy(
                splitDrafts = current.splitDrafts.map { draft ->
                    if (draft.localId == localId) {
                        draft.copy(
                            categoryId = category.id,
                            categoryName = category.name,
                            categoryColorHex = category.colorHex,
                            categoryIconName = category.iconName
                        )
                    } else {
                        draft
                    }
                },
                hasUserEdits = true,
                errorMessage = null
            )
        }
    }

    @Suppress("unused")
    fun updateSplitLineNote(localId: Long, note: String) {
        formState.update { current ->
            current.copy(
                splitDrafts = current.splitDrafts.map { draft ->
                    if (draft.localId == localId) draft.copy(note = note) else draft
                },
                hasUserEdits = true
            )
        }
    }

    fun assignRemainingToSplitLine(localId: Long) {
        val currentForm = formState.value
        val refs = referenceDataFlow.value
        val selectedAccount = refs.allAccounts.firstOrNull { it.id == currentForm.selectedAccountId }
            ?: refs.activeAccounts.firstOrNull()
        val currencySpec = SupportedCurrencies.fromCode(
            selectedAccount?.currencyCode ?: userPreferencesFlow.value.primaryCurrencyCode
        )
        val digits = currencySpec.fractionDigits
        val parentTotal = evaluateBufferSafely(currentForm.expressionBuffer, digits)
        currentForm.splitDrafts.firstOrNull { it.localId == localId } ?: return

        var otherAllocatedMinor = 0L
        for (draft in currentForm.splitDrafts) {
            if (draft.localId != localId) {
                otherAllocatedMinor = MoneyFormatterAndCalculator.safeAdd(
                    otherAllocatedMinor,
                    evaluateBufferSafely(draft.amountInput, digits).amountMinor
                )
            }
        }
        val remainingForTargetMinor = MoneyFormatterAndCalculator.safeSubtract(parentTotal.amountMinor, otherAllocatedMinor)
        if (remainingForTargetMinor <= 0L) return
        val formatted = formatMinorPlain(MinorMoney(remainingForTargetMinor), digits)
        formState.update { current ->
            current.copy(
                splitDrafts = current.splitDrafts.map { draft ->
                    if (draft.localId == localId) draft.copy(amountInput = formatted) else draft
                },
                formSyncVersion = current.formSyncVersion + 1,
                hasUserEdits = true,
                errorMessage = null
            )
        }
    }

    fun removeSplitLine(localId: Long) {
        formState.update { current ->
            if (current.splitDrafts.size <= 2) {
                current.copy(errorMessage = "Split transactions require at least 2 split lines.")
            } else {
                current.copy(
                    splitDrafts = current.splitDrafts.filterNot { it.localId == localId },
                    formSyncVersion = current.formSyncVersion + 1,
                    hasUserEdits = true,
                    errorMessage = null
                )
            }
        }
    }

    fun toggleInstallmentEnabled() {
        formState.update { current ->
            if (current.installmentIndex != null ||
                (current.editingTransactionId == 0L && current.linkedRecurringRuleId != null)
            ) {
                return@update current
            }
            val next = !current.isInstallmentEnabled
            current.copy(
                isInstallmentEnabled = next,
                isRecurring = if (next) false else current.isRecurring,
                isSplit = !next && current.isSplit,
                hasUserEdits = true,
                errorMessage = null
            )
        }
    }

    fun toggleRecurringMode() {
        formState.update { current ->
            if (current.editingTransactionId == 0L && current.linkedRecurringRuleId != null) {
                return@update current
            }
            val next = !current.isRecurring
            val clearedFee = next && current.transferFeeInput.isNotEmpty()
            current.copy(
                isRecurring = next,
                isInstallmentEnabled = if (next) false else current.isInstallmentEnabled,
                isSplit = if (next) false else current.isSplit,
                transferFeeInput = if (next) "" else current.transferFeeInput,
                formSyncVersion = if (clearedFee) current.formSyncVersion + 1 else current.formSyncVersion,
                hasUserEdits = true,
                errorMessage = null
            )
        }
    }

    fun setRecurringFrequency(frequency: RepeatFrequency) {
        formState.update { current ->
            if (current.editingTransactionId == 0L && current.linkedRecurringRuleId != null) {
                return@update current
            }
            val clearedFee = current.transferFeeInput.isNotEmpty()
            current.copy(
                isRecurring = true,
                recurringFrequency = frequency,
                isInstallmentEnabled = false,
                isSplit = false,
                transferFeeInput = "",
                formSyncVersion = if (clearedFee) current.formSyncVersion + 1 else current.formSyncVersion,
                hasUserEdits = true,
                errorMessage = null
            )
        }
    }

    fun disableRecurringMode() {
        formState.update { it.copy(isRecurring = false, hasUserEdits = true, errorMessage = null) }
    }

    fun setInstallmentMonthsCount(months: Int) {
        formState.update {
            it.copy(installmentMonthsCount = months.coerceIn(2, 60), hasUserEdits = true, errorMessage = null)
        }
    }

    fun updatePayeeOrDescription(value: String) {
        autofillJob?.cancel()
        payeeQueryFlow.value = value
        formState.update { it.copy(payeeOrDescription = value, hasUserEdits = true, errorMessage = null) }
    }

    fun updateNote(value: String) {
        formState.update { it.copy(note = value, hasUserEdits = true, errorMessage = null) }
    }

    fun toggleTagSelection(tag: String) {
        autofillJob?.cancel()
        formState.update { current ->
            val nextTags = if (tag in current.selectedTags) {
                current.selectedTags - tag
            } else {
                current.selectedTags + tag
            }
            current.copy(
                selectedTags = nextTags,
                isTagsManuallySet = true,
                hasUserEdits = true,
                autofillBannerText = null,
                autofillUndoSnapshot = null
            )
        }
    }

    fun createAndSelectTag(rawTagName: String) {
        val cleaned = rawTagName.trim().removePrefix("#").trim()
        if (cleaned.isEmpty()) return
        viewModelScope.launch {
            categoryAndTagRepository.createTag(cleaned)
            formState.update { current ->
                if (cleaned in current.selectedTags) {
                    current
                } else {
                    current.copy(
                        selectedTags = current.selectedTags + cleaned,
                        isTagsManuallySet = true,
                        hasUserEdits = true,
                        autofillBannerText = null,
                        autofillUndoSnapshot = null
                    )
                }
            }
        }
    }

    fun applyPayeeSuggestion(suggestion: PayeeSuggestion) {
        autofillJob?.cancel()
        payeeQueryFlow.value = suggestion.displayName
        formState.update { it.copy(payeeOrDescription = suggestion.displayName, errorMessage = null) }
        autofillJob = viewModelScope.launch {
            val template = transactionRepository.getPayeeAutofillTemplate(suggestion.latestTransactionId)
            val refs = referenceDataFlow.value
            val userPrefs = userPreferencesFlow.value

            var finalUpdatedPayee = suggestion.displayName

            formState.update { current ->
                val effectiveAccount = refs.activeAccounts.firstOrNull { it.id == current.selectedAccountId }
                    ?: refs.activeAccounts.firstOrNull()
                val currentSpec = SupportedCurrencies.fromCode(
                    effectiveAccount?.currencyCode ?: userPrefs.primaryCurrencyCode
                )
                val evaluatedAmount = evaluateBufferSafely(current.expressionBuffer, currentSpec.fractionDigits)

                val resolved = if (template != null) {
                    PayeeAutofillResolver.resolve(
                        template = template,
                        isEditMode = current.editingTransactionId > 0L,
                        currentMode = current.mode,
                        currentAccountId = current.selectedAccountId,
                        currentTransferToAccountId = current.transferToAccountId,
                        currentCategoryId = current.selectedCategoryId,
                        currentCategoryName = current.selectedCategoryName,
                        currentParentCategoryName = current.selectedParentCategoryName,
                        currentCategoryColorHex = current.selectedCategoryColorHex,
                        currentCategoryIconName = current.selectedCategoryIconName,
                        currentTags = current.selectedTags,
                        isSplit = current.isSplit,
                        isAccountManuallySet = current.isAccountManuallySet,
                        isDestinationManuallySet = current.isDestinationManuallySet,
                        isCategoryManuallySet = current.isCategoryManuallySet,
                        isTagsManuallySet = current.isTagsManuallySet,
                        isModeManuallySet = current.isModeManuallySet,
                        activeAccounts = refs.activeAccounts,
                        activeExpenseCategories = refs.expenseCategories,
                        activeIncomeCategories = refs.incomeCategories,
                        activeTags = refs.tags,
                        subcategoriesEnabled = userPrefs.subcategoriesEnabled,
                        evaluatedAmount = evaluatedAmount
                    )
                } else {
                    PayeeAutofillResolution(
                        updatedPayee = suggestion.displayName,
                        updatedMode = current.mode,
                        updatedAccountId = current.selectedAccountId,
                        updatedTransferToAccountId = current.transferToAccountId,
                        updatedCategoryId = current.selectedCategoryId,
                        updatedCategoryName = current.selectedCategoryName,
                        updatedParentCategoryName = current.selectedParentCategoryName,
                        updatedCategoryColorHex = current.selectedCategoryColorHex,
                        updatedCategoryIconName = current.selectedCategoryIconName,
                        updatedTags = current.selectedTags,
                        bannerText = null,
                        didApplyAnyMetadataChange = false
                    )
                }

                finalUpdatedPayee = resolved.updatedPayee

                val undoSnapshot = if (resolved.didApplyAnyMetadataChange) {
                    current.autofillUndoSnapshot ?: AutofillUndoSnapshot(
                        previousMode = current.mode,
                        previousAccountId = current.selectedAccountId,
                        previousTransferToAccountId = current.transferToAccountId,
                        previousCategoryId = current.selectedCategoryId,
                        previousCategoryName = current.selectedCategoryName,
                        previousParentCategoryName = current.selectedParentCategoryName,
                        previousCategoryColorHex = current.selectedCategoryColorHex,
                        previousCategoryIconName = current.selectedCategoryIconName,
                        previousTags = current.selectedTags
                    )
                } else current.autofillUndoSnapshot

                val resolvedAccount = refs.activeAccounts.firstOrNull { it.id == resolved.updatedAccountId }
                    ?: refs.activeAccounts.firstOrNull()
                val keepInstallment = current.isInstallmentEnabled &&
                    resolved.updatedMode == TransactionType.EXPENSE &&
                    resolvedAccount?.groupType == AccountGroup.CREDIT_CARD &&
                    current.installmentIndex == null

                current.copy(
                    payeeOrDescription = resolved.updatedPayee,
                    mode = resolved.updatedMode,
                    selectedAccountId = resolved.updatedAccountId,
                    transferToAccountId = resolved.updatedTransferToAccountId,
                    selectedCategoryId = resolved.updatedCategoryId,
                    selectedCategoryName = resolved.updatedCategoryName,
                    selectedParentCategoryName = resolved.updatedParentCategoryName,
                    selectedCategoryColorHex = resolved.updatedCategoryColorHex,
                    selectedCategoryIconName = resolved.updatedCategoryIconName,
                    selectedTags = resolved.updatedTags,
                    isInstallmentEnabled = keepInstallment,
                    hasUserEdits = true,
                    formSyncVersion = current.formSyncVersion + 1,
                    autofillBannerText = resolved.bannerText,
                    autofillUndoSnapshot = undoSnapshot,
                    errorMessage = null
                )
            }

            if (coroutineContext.isActive && formState.value.payeeOrDescription == finalUpdatedPayee) {
                payeeQueryFlow.value = finalUpdatedPayee
            }
        }
    }

    fun undoPayeeAutofill() {
        autofillJob?.cancel()
        val refs = referenceDataFlow.value
        val allAccounts = refs.allAccounts
        val activeAccounts = refs.activeAccounts
        val primaryCode = userPreferencesFlow.value.primaryCurrencyCode
        formState.update { current ->
            val snapshot = current.autofillUndoSnapshot ?: return@update current
            val currentAcc = allAccounts.firstOrNull { it.id == current.selectedAccountId }
                ?: activeAccounts.firstOrNull()
            val restoredAcc = allAccounts.firstOrNull { it.id == snapshot.previousAccountId }
                ?: activeAccounts.firstOrNull()
            val currentSpec = SupportedCurrencies.fromCode(currentAcc?.currencyCode ?: primaryCode)
            val restoredSpec = SupportedCurrencies.fromCode(restoredAcc?.currencyCode ?: primaryCode)
            val shouldRescaleDown = restoredSpec.fractionDigits < currentSpec.fractionDigits
            val nextBuffer = if (shouldRescaleDown) {
                rescaleExpressionDown(current.expressionBuffer, currentSpec.fractionDigits, restoredSpec.fractionDigits)
            } else {
                current.expressionBuffer
            }
            val nextTransferFee = if (shouldRescaleDown && current.transferFeeInput.isNotBlank()) {
                rescaleOptionalFeeDown(current.transferFeeInput, currentSpec.fractionDigits, restoredSpec.fractionDigits)
            } else {
                current.transferFeeInput
            }
            val nextSplits = if (shouldRescaleDown && current.splitDrafts.isNotEmpty()) {
                rescaleSplitsDown(current.splitDrafts, currentSpec.fractionDigits, restoredSpec.fractionDigits)
            } else {
                current.splitDrafts
            }
            val keepInstallment = current.isInstallmentEnabled &&
                snapshot.previousMode == TransactionType.EXPENSE &&
                restoredAcc?.groupType == AccountGroup.CREDIT_CARD &&
                current.installmentIndex == null
            current.copy(
                mode = snapshot.previousMode,
                selectedAccountId = snapshot.previousAccountId,
                transferToAccountId = snapshot.previousTransferToAccountId,
                expressionBuffer = nextBuffer,
                transferFeeInput = nextTransferFee,
                splitDrafts = nextSplits,
                selectedCategoryId = snapshot.previousCategoryId,
                selectedCategoryName = snapshot.previousCategoryName,
                selectedParentCategoryName = snapshot.previousParentCategoryName,
                selectedCategoryColorHex = snapshot.previousCategoryColorHex,
                selectedCategoryIconName = snapshot.previousCategoryIconName,
                selectedTags = snapshot.previousTags,
                isInstallmentEnabled = keepInstallment,
                formSyncVersion = current.formSyncVersion + 1,
                autofillBannerText = null,
                autofillUndoSnapshot = null
            )
        }
    }

    fun dismissAutofillBanner() {
        formState.update { it.copy(autofillBannerText = null) }
    }

    fun loadRecurringRuleForEntry(
        ruleId: Long,
        expectedDueEpochDay: Long? = null,
        onReady: () -> Unit = {},
        onAlreadyLogged: () -> Unit = {}
    ) {
        val repo = analyticsAndBudgetRepository ?: return
        autofillJob?.cancel()
        bookmarkLoadJob?.cancel()
        viewModelScope.launch {
            val rules = repo.observeAllRecurringRules().first()
            val rule = rules.firstOrNull { it.id == ruleId } ?: return@launch
            if (isRecurringCycleAlreadyLogged(rule.nextOccurrenceEpochDay, expectedDueEpochDay)) {
                onAlreadyLogged()
                return@launch
            }
            val refs = rawReferenceBundleFlow.first()
            val sourceAcc = refs.activeAccounts.firstOrNull { it.id == rule.accountId }
            val isTransferMode = rule.type == TransactionType.TRANSFER || rule.type == TransactionType.CC_SETTLEMENT
            val destAcc = if (sourceAcc != null && isTransferMode) {
                refs.activeAccounts.firstOrNull {
                    it.id == rule.transferToAccountId &&
                        it.id != sourceAcc.id &&
                        it.currencyCode.equals(sourceAcc.currencyCode, ignoreCase = true) &&
                        (rule.type != TransactionType.CC_SETTLEMENT || it.groupType == AccountGroup.CREDIT_CARD)
                }
            } else null

            val spec = SupportedCurrencies.fromCode(rule.currencyCode)
            val plainAmount = formatMinorPlain(rule.amount, spec.fractionDigits)
            val loadedPayee = rule.payeeOrDescription.ifEmpty { rule.title }
            val archivedError = when {
                sourceAcc == null ->
                    "Recurring rule account is archived or unavailable. Please select an active account."
                isTransferMode && destAcc == null ->
                    "Recurring rule destination account is archived or unavailable. Please select a destination account."
                else -> null
            }
            payeeQueryFlow.value = loadedPayee
            formState.update { current ->
                EntryFormControlState(
                    editingTransactionId = 0L,
                    mode = rule.type,
                    expressionBuffer = plainAmount,
                    selectedAccountId = sourceAcc?.id,
                    transferToAccountId = destAcc?.id,
                    selectedCategoryId = if (isTransferMode) null else rule.categoryId,
                    payeeOrDescription = loadedPayee,
                    note = rule.note,
                    ledgerDateEpochDay = rule.nextOccurrenceEpochDay,
                    timestampEpochMillis = buildAnchoredTimestamp(rule.nextOccurrenceEpochDay),
                    linkedRecurringRuleId = rule.id,
                    linkedRecurringRuleDueEpochDay = rule.nextOccurrenceEpochDay,
                    isKeypadVisible = false,
                    savedSuccessfullyCounter = current.savedSuccessfullyCounter,
                    formSyncVersion = current.formSyncVersion + 1,
                    isAccountManuallySet = true,
                    isDestinationManuallySet = true,
                    isCategoryManuallySet = true,
                    isTagsManuallySet = true,
                    isModeManuallySet = true,
                    errorMessage = archivedError,
                    autofillBannerText = null,
                    autofillUndoSnapshot = null
                )
            }
            onReady()
        }
    }

    fun applyBookmarkById(bookmarkId: Long) {
        bookmarkLoadJob?.cancel()
        resetForNewTransaction(initialEpochDay = LocalDate.now().toEpochDay())
        bookmarkLoadJob = viewModelScope.launch {
            val bookmarks = categoryAndTagRepository.observeBookmarks().first()
            val match = bookmarks.firstOrNull { it.id == bookmarkId } ?: return@launch
            val refs = rawReferenceBundleFlow.first()
            applyBookmarkInternal(match, refs)
        }
    }

    fun applyBookmark(bookmark: TransactionBookmark) {
        autofillJob?.cancel()
        bookmarkLoadJob?.cancel()
        bookmarkLoadJob = viewModelScope.launch {
            val freshRefs = rawReferenceBundleFlow.first()
            applyBookmarkInternal(bookmark, freshRefs)
        }
    }

    private fun applyBookmarkInternal(bookmark: TransactionBookmark, refs: ReferenceBundle) {
        val bookmarkAccount = refs.activeAccounts.firstOrNull { it.id == bookmark.accountId }
        if (bookmarkAccount == null) {
            formState.update {
                it.copy(errorMessage = "Bookmarked account is archived or unavailable.")
            }
            return
        }
        val bookmarkCurrencySpec = SupportedCurrencies.fromCode(bookmarkAccount.currencyCode)
        val plainAmount = formatMinorPlain(bookmark.amount, bookmarkCurrencySpec.fractionDigits)
        val isTransferMode = bookmark.type == TransactionType.TRANSFER || bookmark.type == TransactionType.CC_SETTLEMENT
        val resolvedDestId = if (isTransferMode) {
            refs.activeAccounts.firstOrNull {
                it.id == bookmark.transferToAccountId &&
                    it.id != bookmarkAccount.id &&
                    it.currencyCode.equals(bookmarkAccount.currencyCode, ignoreCase = true) &&
                    (bookmark.type != TransactionType.CC_SETTLEMENT || it.groupType == AccountGroup.CREDIT_CARD)
            }?.id
        } else null
        val destMissingError = if (isTransferMode && resolvedDestId == null) {
            "Bookmarked destination account is archived or unavailable. Please select a destination account."
        } else null

        val tree = if (bookmark.type == TransactionType.INCOME) refs.incomeCategories else refs.expenseCategories
        val catNode = if (!isTransferMode) findCategoryNodeById(tree, bookmark.categoryId) else null
        val catParentName = if (catNode != null) findParentCategoryName(tree, catNode.id) else null

        payeeQueryFlow.value = bookmark.payeeOrDescription
        formState.update { current ->
            current.copy(
                editingTransactionId = 0L,
                mode = bookmark.type,
                expressionBuffer = plainAmount,
                selectedAccountId = bookmarkAccount.id,
                transferToAccountId = resolvedDestId,
                transferFeeInput = "",
                selectedCategoryId = catNode?.id,
                selectedCategoryName = catNode?.name,
                selectedParentCategoryName = catParentName,
                selectedCategoryColorHex = catNode?.colorHex,
                selectedCategoryIconName = catNode?.iconName,
                payeeOrDescription = bookmark.payeeOrDescription,
                note = bookmark.note,
                selectedTags = bookmark.tags,
                isSplit = false,
                splitDrafts = emptyList(),
                isRecurring = false,
                isInstallmentEnabled = false,
                installmentIndex = null,
                installmentTotalCount = null,
                linkedRecurringRuleId = null,
                linkedRecurringRuleDueEpochDay = null,
                formSyncVersion = current.formSyncVersion + 1,
                hasUserEdits = true,
                errorMessage = destMissingError,
                isAccountManuallySet = true,
                isDestinationManuallySet = isTransferMode,
                isCategoryManuallySet = catNode != null,
                isTagsManuallySet = true,
                isModeManuallySet = true,
                autofillBannerText = null,
                autofillUndoSnapshot = null
            )
        }
    }

    fun saveCurrentAsBookmark(title: String) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) return
        val currentForm = formState.value
        if (currentForm.isSplit) {
            formState.update { it.copy(errorMessage = "Split transactions cannot be saved as templates.") }
            return
        }
        val refs = referenceDataFlow.value
        val explicitSource = refs.activeAccounts.firstOrNull { it.id == currentForm.selectedAccountId }
        val account = explicitSource ?: if (currentForm.isAccountManuallySet) null else refs.activeAccounts.firstOrNull()
        if (account == null) {
            formState.update { it.copy(errorMessage = "Select an active account before saving a bookmark.") }
            return
        }
        val isTransferMode = currentForm.mode == TransactionType.TRANSFER || currentForm.mode == TransactionType.CC_SETTLEMENT
        val transferToAccount = if (isTransferMode) {
            val explicitDest = currentForm.transferToAccountId?.let { id ->
                refs.activeAccounts.firstOrNull { it.id == id && it.id != account.id }
            }
            explicitDest ?: if (currentForm.isDestinationManuallySet) {
                null
            } else {
                resolveDefaultDestination(refs.activeAccounts, account, currentForm.mode)
            }
        } else null

        if (isTransferMode) {
            if (transferToAccount == null || transferToAccount.id == account.id) {
                formState.update { it.copy(errorMessage = "Select a valid destination account before saving a transfer bookmark.") }
                return
            }
            if (!transferToAccount.currencyCode.equals(account.currencyCode, ignoreCase = true)) {
                formState.update {
                    it.copy(
                        errorMessage = "Transfer accounts must use the same currency (${account.currencyCode} \u2260 ${transferToAccount.currencyCode})."
                    )
                }
                return
            }
            if (currentForm.mode == TransactionType.CC_SETTLEMENT && transferToAccount.groupType != AccountGroup.CREDIT_CARD) {
                formState.update { it.copy(errorMessage = "Credit card settlements require a credit card destination account.") }
                return
            }
        }

        val currencySpec = SupportedCurrencies.fromCode(
            account.currencyCode ?: userPreferencesFlow.value.primaryCurrencyCode
        )
        val evaluatedAmount = evaluateBufferSafely(currentForm.expressionBuffer, currencySpec.fractionDigits)
        if (!evaluatedAmount.isPositive) {
            formState.update { it.copy(errorMessage = "Enter an amount greater than zero to save a bookmark.") }
            return
        }
        val treeForMode = if (currentForm.mode == TransactionType.INCOME) refs.incomeCategories else refs.expenseCategories
        val resolvedCategoryId = if (isTransferMode) null else {
            currentForm.selectedCategoryId ?: treeForMode.firstOrNull()?.id
        }

        val bookmark = TransactionBookmark(
            id = 0L,
            title = trimmedTitle,
            type = currentForm.mode,
            amount = evaluatedAmount,
            accountId = account.id,
            transferToAccountId = if (isTransferMode) transferToAccount?.id else null,
            categoryId = resolvedCategoryId,
            payeeOrDescription = currentForm.payeeOrDescription.trim(),
            note = currentForm.note.trim(),
            tags = currentForm.selectedTags
        )
        viewModelScope.launch {
            categoryAndTagRepository.upsertBookmark(bookmark).onFailure { err ->
                formState.update {
                    it.copy(errorMessage = err.message ?: "Unable to save bookmark.")
                }
            }
        }
    }

    fun deleteBookmark(bookmarkId: Long) {
        viewModelScope.launch {
            categoryAndTagRepository.deleteBookmark(bookmarkId)
        }
    }

    @Suppress("unused")
    fun clearErrorMessage() {
        formState.update { it.copy(errorMessage = null) }
    }

    fun saveTransaction(onSuccess: (() -> Unit)? = null) {
        autofillJob?.cancel()
        var acquiredSaveLock = false
        formState.update { current ->
            if (current.isSaving) {
                acquiredSaveLock = false
                current
            } else {
                acquiredSaveLock = true
                current.copy(isSaving = true, errorMessage = null)
            }
        }
        if (!acquiredSaveLock) return

        val latestForm = formState.value
        val refs = referenceDataFlow.value
        val isEditing = latestForm.editingTransactionId > 0L
        val treeForMode = if (latestForm.mode == TransactionType.INCOME) refs.incomeCategories else refs.expenseCategories
        val effectiveCategoryId = latestForm.selectedCategoryId
            ?: if (!isEditing) treeForMode.firstOrNull()?.id else null
        val effectiveCategoryNode = findCategoryNodeById(treeForMode, effectiveCategoryId)
        val effectiveParentName = findParentCategoryName(treeForMode, effectiveCategoryId)
            ?: latestForm.selectedParentCategoryName

        val sourceAccount = if (isEditing) {
            refs.allAccounts.firstOrNull { it.id == latestForm.selectedAccountId }
        } else {
            val explicitSource = refs.activeAccounts.firstOrNull { it.id == latestForm.selectedAccountId }
            explicitSource ?: if (latestForm.isAccountManuallySet) null else refs.activeAccounts.firstOrNull()
        }
        if (sourceAccount == null) {
            formState.update {
                it.copy(
                    isSaving = false,
                    errorMessage = if (isEditing) "The original account no longer exists. Please select an active account." else "Please select an account."
                )
            }
            return
        }

        val isTransferMode = latestForm.mode == TransactionType.TRANSFER || latestForm.mode == TransactionType.CC_SETTLEMENT
        val destAccount = if (isTransferMode) {
            if (isEditing) {
                refs.allAccounts.firstOrNull { it.id == latestForm.transferToAccountId }
            } else {
                val explicitDest = latestForm.transferToAccountId?.let { id ->
                    refs.activeAccounts.firstOrNull { it.id == id && it.id != sourceAccount.id }
                }
                explicitDest ?: if (latestForm.isDestinationManuallySet) {
                    null
                } else {
                    resolveDefaultDestination(refs.activeAccounts, sourceAccount, latestForm.mode)
                }
            }
        } else null

        if (isTransferMode) {
            if (destAccount == null || destAccount.id == sourceAccount.id) {
                formState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Select a different destination account for the transfer."
                    )
                }
                return
            }
            if (!destAccount.currencyCode.equals(sourceAccount.currencyCode, ignoreCase = true)) {
                formState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Transfer accounts must use the same currency (${sourceAccount.currencyCode} \u2260 ${destAccount.currencyCode})."
                    )
                }
                return
            }
            if (latestForm.mode == TransactionType.CC_SETTLEMENT && destAccount.groupType != AccountGroup.CREDIT_CARD) {
                formState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Credit card settlements require a credit card destination account."
                    )
                }
                return
            }
        } else if (!latestForm.isSplit && effectiveCategoryId == null) {
            formState.update { it.copy(isSaving = false, errorMessage = "Please select a category.") }
            return
        }

        val currencySpec = SupportedCurrencies.fromCode(
            sourceAccount.currencyCode ?: userPreferencesFlow.value.primaryCurrencyCode
        )
        val strictAmountResult = evaluateStrictMainAmount(
            rawExpression = latestForm.expressionBuffer,
            fractionDigits = currencySpec.fractionDigits
        )
        val amount = strictAmountResult.getOrElse { err ->
            formState.update {
                it.copy(
                    isSaving = false,
                    errorMessage = err.message ?: "Enter an amount greater than zero."
                )
            }
            return
        }
        val validatedTransferFee = if (isTransferMode && !latestForm.isRecurring && latestForm.transferFeeInput.isNotBlank()) {
            parseStrictOptionalFee(
                rawFee = latestForm.transferFeeInput,
                fractionDigits = currencySpec.fractionDigits
            ).getOrElse {
                formState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Enter a valid transfer fee."
                    )
                }
                return
            }
        } else {
            MinorMoney.ZERO
        }

        if (latestForm.isSplit) {
            val evaluatedSplits = latestForm.splitDrafts.map { draft ->
                val lineMoney = evaluateBufferSafely(draft.amountInput, currencySpec.fractionDigits)
                SplitLineItem(
                    id = draft.localId,
                    categoryId = draft.categoryId,
                    categoryName = draft.categoryName,
                    amount = lineMoney,
                    note = draft.note
                )
            }
            val splitValidation = TransactionEntryRulesEngine.validateSplitTransaction(
                parentTotal = amount,
                splits = evaluatedSplits
            )
            if (!splitValidation.isValid) {
                formState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = splitValidation.errorMessage
                            ?: "Split amounts must match the transaction total."
                    )
                }
                return
            }
        }

        if (latestForm.isInstallmentEnabled && sourceAccount.groupType == AccountGroup.CREDIT_CARD && latestForm.mode == TransactionType.EXPENSE && !latestForm.isSplit) {
            val safeMonths = latestForm.installmentMonthsCount.coerceIn(2, 60)
            if (amount.amountMinor < safeMonths) {
                formState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Amount is too small for $safeMonths monthly installments."
                    )
                }
                return
            }
        }

        viewModelScope.launch {
            try {
                val existingId = latestForm.editingTransactionId
                val isExpenseOrIncome = latestForm.mode == TransactionType.EXPENSE || latestForm.mode == TransactionType.INCOME

                val transferFee = validatedTransferFee

                val defaultPayee = when {
                    isTransferMode -> "${sourceAccount.name} → ${destAccount?.name ?: ""}"
                    latestForm.mode == TransactionType.REFUND -> "Refund"
                    else -> ""
                }

                val baseTx = TransactionItem(
                    id = existingId,
                    type = latestForm.mode,
                    timestampEpochMillis = latestForm.timestampEpochMillis,
                    ledgerDateEpochDay = latestForm.ledgerDateEpochDay,
                    amount = amount,
                    currencyCode = sourceAccount.currencyCode,
                    accountId = sourceAccount.id,
                    accountName = sourceAccount.name,
                    transferToAccountId = if (isTransferMode) destAccount?.id else null,
                    transferToAccountName = if (isTransferMode) destAccount?.name else null,
                    transferFee = transferFee,
                    categoryId = if (isTransferMode) null else effectiveCategoryId,
                    categoryName = if (isTransferMode) null else (effectiveCategoryNode?.name ?: latestForm.selectedCategoryName),
                    parentCategoryName = if (isTransferMode) null else effectiveParentName,
                    categoryColorHex = if (isTransferMode) null else (effectiveCategoryNode?.colorHex ?: latestForm.selectedCategoryColorHex),
                    categoryIconName = if (isTransferMode) null else (effectiveCategoryNode?.iconName ?: latestForm.selectedCategoryIconName),
                    isSplit = isExpenseOrIncome && latestForm.isSplit,
                    splits = if (isExpenseOrIncome && latestForm.isSplit) {
                        latestForm.splitDrafts.map { draft ->
                            SplitLineItem(
                                id = 0L,
                                categoryId = draft.categoryId,
                                categoryName = draft.categoryName,
                                amount = evaluateBufferSafely(draft.amountInput, currencySpec.fractionDigits),
                                note = draft.note.trim()
                            )
                        }
                    } else {
                        emptyList()
                    },
                    tags = latestForm.selectedTags,
                    payeeOrDescription = latestForm.payeeOrDescription.trim().ifEmpty { defaultPayee },
                    note = latestForm.note.trim(),
                    installmentIndex = latestForm.installmentIndex,
                    installmentTotalCount = latestForm.installmentTotalCount,
                    recurringRuleId = latestForm.linkedRecurringRuleId
                )

                val isCreditCardExpense = sourceAccount.groupType == AccountGroup.CREDIT_CARD &&
                    latestForm.mode == TransactionType.EXPENSE &&
                    !latestForm.isSplit &&
                    latestForm.installmentIndex == null
                val linkedRuleId = latestForm.linkedRecurringRuleId
                val saveResult: Result<Any> = when {
                    latestForm.isInstallmentEnabled && isCreditCardExpense -> {
                        transactionRepository.createInstallmentPlanTransactions(
                            baseTransaction = baseTx,
                            totalInstallmentAmount = amount,
                            monthsCount = latestForm.installmentMonthsCount,
                            startDateEpochDay = latestForm.ledgerDateEpochDay,
                            replaceTransactionId = if (existingId > 0L) existingId else null
                        )
                    }

                    latestForm.isRecurring -> {
                        transactionRepository.upsertRecurringTransaction(
                            transaction = baseTx,
                            frequency = latestForm.recurringFrequency
                        )
                    }

                    existingId == 0L && linkedRuleId != null -> {
                        transactionRepository.logDueRecurringTransaction(
                            transaction = baseTx,
                            ruleId = linkedRuleId,
                            currentDueEpochDay = latestForm.linkedRecurringRuleDueEpochDay ?: latestForm.ledgerDateEpochDay
                        )
                    }

                    latestForm.isSplit -> {
                        transactionRepository.upsertSplitTransaction(baseTx)
                    }

                    else -> {
                        transactionRepository.upsertSingleTransaction(baseTx)
                    }
                }

                saveResult.fold(
                    onSuccess = {
                        lastUsedLedgerDateEpochDay = latestForm.ledgerDateEpochDay
                        payeeQueryFlow.value = ""
                        autofillJob?.cancel()
                        val activeIds = referenceDataFlow.value.activeAccounts.map { it.id }.toSet()
                        formState.update { current ->
                            val nextMode = if (current.mode == TransactionType.CC_SETTLEMENT) TransactionType.TRANSFER else current.mode
                            val nextAccountId = current.selectedAccountId?.takeIf { activeIds.isEmpty() || it in activeIds }
                            EntryFormControlState(
                                editingTransactionId = 0L,
                                mode = nextMode,
                                selectedAccountId = nextAccountId,
                                ledgerDateEpochDay = latestForm.ledgerDateEpochDay,
                                timestampEpochMillis = buildAnchoredTimestamp(latestForm.ledgerDateEpochDay),
                                savedSuccessfullyCounter = current.savedSuccessfullyCounter + 1,
                                formSyncVersion = current.formSyncVersion + 1
                            )
                        }
                        onSuccess?.invoke()
                    },
                    onFailure = { error ->
                        formState.update {
                            it.copy(
                                isSaving = false,
                                errorMessage = error.message ?: "Unable to save transaction."
                            )
                        }
                    }
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                formState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = t.message ?: "Unable to save transaction."
                    )
                }
            }
        }
    }

    private fun evaluateStrictMainAmount(rawExpression: String, fractionDigits: Int): Result<MinorMoney> {
        val trimmed = rawExpression.trim()
        if (trimmed.isEmpty() || trimmed == "0") {
            return Result.failure(IllegalArgumentException("Enter an amount greater than zero."))
        }
        return MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor(
            expression = trimmed,
            fractionDigits = fractionDigits
        )
    }

    private fun parseStrictOptionalFee(rawFee: String, fractionDigits: Int): Result<MinorMoney> {
        val trimmed = rawFee.trim()
        if (trimmed.isEmpty()) return Result.success(MinorMoney.ZERO)
        val evalResult = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor(
            expression = trimmed,
            fractionDigits = fractionDigits
        )
        if (evalResult.isSuccess) return evalResult
        val normalizedDecimal = trimmed.replace(',', '.')
        val parsedZero = normalizedDecimal.toBigDecimalOrNull()
        if (parsedZero != null && parsedZero.compareTo(java.math.BigDecimal.ZERO) == 0) {
            return Result.success(MinorMoney.ZERO)
        }
        return Result.failure(IllegalArgumentException("Enter a valid transfer fee."))
    }

    private fun rescaleExpressionDown(
        rawExpression: String,
        sourceFractionDigits: Int,
        targetFractionDigits: Int
    ): String {
        val evaluated = evaluateBufferSafely(rawExpression, sourceFractionDigits)
        val roundedMinor = MoneyFormatterAndCalculator.convertMinorMoney(
            sourceMinor = evaluated.amountMinor,
            sourceFractionDigits = sourceFractionDigits,
            targetFractionDigits = targetFractionDigits,
            rateToTarget = 1.0
        )
        return formatMinorPlain(MinorMoney(roundedMinor), targetFractionDigits)
    }

    private fun rescaleOptionalFeeDown(
        rawFee: String,
        sourceFractionDigits: Int,
        targetFractionDigits: Int
    ): String {
        val feeEval = evaluateBufferSafely(rawFee, sourceFractionDigits)
        val roundedFee = MoneyFormatterAndCalculator.convertMinorMoney(
            sourceMinor = feeEval.amountMinor,
            sourceFractionDigits = sourceFractionDigits,
            targetFractionDigits = targetFractionDigits,
            rateToTarget = 1.0
        )
        return if (roundedFee > 0L) formatMinorPlain(MinorMoney(roundedFee), targetFractionDigits) else ""
    }

    private fun rescaleSplitsDown(
        drafts: List<SplitLineDraft>,
        sourceFractionDigits: Int,
        targetFractionDigits: Int
    ): List<SplitLineDraft> = drafts.map { draft ->
        draft.copy(
            amountInput = rescaleExpressionDown(
                rawExpression = draft.amountInput,
                sourceFractionDigits = sourceFractionDigits,
                targetFractionDigits = targetFractionDigits
            )
        )
    }

    private fun evaluateBufferSafely(rawExpression: String, fractionDigits: Int): MinorMoney {
        val trimmed = rawExpression.trim()
        if (trimmed.isEmpty() || trimmed == "0") return MinorMoney.ZERO

        val direct = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor(
            expression = trimmed,
            fractionDigits = fractionDigits
        ).getOrNull()
        if (direct != null) return direct

        val withoutTrailingOp = trimmed.trimEnd('+', '-', '×', '÷', '*', '/', '.')
        if (withoutTrailingOp.isEmpty()) return MinorMoney.ZERO

        return MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor(
            expression = withoutTrailingOp,
            fractionDigits = fractionDigits
        ).getOrDefault(MinorMoney.ZERO)
    }

    private fun formatMinorPlain(money: MinorMoney, fractionDigits: Int): String {
        if (!money.isPositive) return "0"
        val formatted = MoneyFormatterAndCalculator.formatMinorMoney(
            money = money,
            currencySpec = CurrencySpec(
                code = "TMP",
                symbol = "",
                displayName = "",
                fractionDigits = fractionDigits,
                defaultGrouping = NumberGroupingSystem.INTERNATIONAL
            ),
            includeSymbol = false
        ).replace(",", "")
        return if (formatted.contains('.')) {
            formatted.trimEnd('0').trimEnd('.')
        } else {
            formatted
        }
    }

    private fun findCategoryNodeById(tree: List<CategoryNode>, id: Long?): CategoryNode? {
        if (id == null) return null
        return tree.firstNotNullOfOrNull { parent ->
            if (parent.id == id) parent else parent.subcategories.firstOrNull { it.id == id }
        }
    }

    private fun findParentCategoryName(tree: List<CategoryNode>, id: Long?): String? {
        if (id == null) return null
        return tree.firstOrNull { parent -> parent.subcategories.any { it.id == id } }?.name
    }

    private fun buildAnchoredTimestamp(epochDay: Long): Long {
        val zone = ZoneId.systemDefault()
        return LocalDate.ofEpochDay(epochDay)
            .atTime(LocalTime.now())
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    private fun resolveDefaultDestination(
        accounts: List<AccountSummary>,
        source: AccountSummary?,
        mode: TransactionType
    ): AccountSummary? {
        return accounts.firstOrNull {
            it.id != source?.id &&
                (source == null || it.currencyCode.equals(source.currencyCode, ignoreCase = true)) &&
                (mode != TransactionType.CC_SETTLEMENT || it.groupType == AccountGroup.CREDIT_CARD)
        }
    }

    private fun createInitialSplitDrafts(
        totalAmount: MinorMoney,
        fractionDigits: Int,
        defaultCat: CategoryNode?,
        preselectedCategoryId: Long?,
        preselectedCategoryName: String?,
        preselectedColorHex: String?,
        preselectedIconName: String?
    ): List<SplitLineDraft> {
        val halfMinor = totalAmount.amountMinor / 2L
        val remainderMinor = totalAmount.amountMinor - halfMinor
        return listOf(
            SplitLineDraft(
                localId = nextSplitLocalId.getAndIncrement(),
                categoryId = preselectedCategoryId ?: defaultCat?.id ?: 0L,
                categoryName = preselectedCategoryName ?: defaultCat?.name ?: "Category 1",
                categoryColorHex = preselectedColorHex ?: defaultCat?.colorHex ?: "#059669",
                categoryIconName = preselectedIconName ?: defaultCat?.iconName ?: "category",
                amountInput = formatMinorPlain(MinorMoney(halfMinor), fractionDigits)
            ),
            SplitLineDraft(
                localId = nextSplitLocalId.getAndIncrement(),
                categoryId = defaultCat?.id ?: 0L,
                categoryName = defaultCat?.name ?: "Category 2",
                categoryColorHex = defaultCat?.colorHex ?: "#2563EB",
                categoryIconName = defaultCat?.iconName ?: "category",
                amountInput = formatMinorPlain(MinorMoney(remainderMinor), fractionDigits)
            )
        )
    }

    private data class ReferenceBundle(
        val allAccounts: List<AccountSummary>,
        val activeAccounts: List<AccountSummary>,
        val expenseCategories: List<CategoryNode>,
        val incomeCategories: List<CategoryNode>,
        val tags: List<String>,
        val bookmarks: List<TransactionBookmark>
    )

    companion object {
        internal fun isRecurringCycleAlreadyLogged(
            nextOccurrenceEpochDay: Long,
            expectedDueEpochDay: Long?
        ): Boolean = expectedDueEpochDay != null && nextOccurrenceEpochDay > expectedDueEpochDay
    }
}