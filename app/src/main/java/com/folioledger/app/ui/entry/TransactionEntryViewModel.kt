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
    val isKeypadVisible: Boolean = true,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val savedSuccessfullyCounter: Int = 0,
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
    private val computationDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val nextSplitLocalId = java.util.concurrent.atomic.AtomicLong(1L)
    private var lastUsedLedgerDateEpochDay: Long = LocalDate.now().toEpochDay()
    private val formState = MutableStateFlow(EntryFormControlState())

    private val activeAccountsForEntryDateFlow = formState
        .map { it.ledgerDateEpochDay }
        .distinctUntilChanged()
        .flatMapLatest { refEpochDay ->
            accountRepository.observeActiveAccounts(refEpochDay)
        }

    private val referenceDataFlow = combine(
        activeAccountsForEntryDateFlow,
        categoryAndTagRepository.observeCategoryTree(CategoryType.EXPENSE),
        categoryAndTagRepository.observeCategoryTree(CategoryType.INCOME),
        categoryAndTagRepository.observeAllTags(),
        categoryAndTagRepository.observeBookmarks()
    ) { accounts, expenseCats, incomeCats, tags, bookmarks ->
        ReferenceBundle(
            accounts = accounts,
            expenseCategories = expenseCats,
            incomeCategories = incomeCats,
            tags = tags,
            bookmarks = bookmarks
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ReferenceBundle(
            accounts = emptyList(),
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
        formState.map { it.mode to (it.editingTransactionId > 0L) }.distinctUntilChanged(),
        payeeCatalogFlow
    ) { query, (mode, isEditing), catalog ->
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
        val selectedAccount = refs.accounts.firstOrNull { it.id == form.selectedAccountId }
            ?: refs.accounts.firstOrNull()
        val transferToAccount = refs.accounts.firstOrNull { it.id == form.transferToAccountId }
            ?: refs.accounts.firstOrNull { it.id != selectedAccount?.id }

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

        val resolvedCategoryId = if (form.editingTransactionId != 0L) {
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
                selectedAccount?.groupType == AccountGroup.CREDIT_CARD
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

        TransactionEntryUiState(
            editingTransactionId = form.editingTransactionId,
            mode = form.mode,
            ledgerDateEpochDay = form.ledgerDateEpochDay,
            timestampEpochMillis = form.timestampEpochMillis,
            expressionBuffer = form.expressionBuffer,
            evaluatedAmount = evaluatedMoney,
            expressionHasOperator = hasOperator,
            activeAccounts = refs.accounts,
            selectedAccount = selectedAccount,
            transferToAccount = transferToAccount,
            transferFeeInput = form.transferFeeInput,
            expenseCategories = refs.expenseCategories,
            incomeCategories = refs.incomeCategories,
            selectedCategoryId = resolvedCategoryNode?.id ?: if (form.editingTransactionId != 0L && form.selectedCategoryId == null) null else form.selectedCategoryId,
            selectedCategoryName = resolvedCategoryNode?.name ?: if (form.editingTransactionId != 0L && form.selectedCategoryId == null) null else form.selectedCategoryName,
            selectedParentCategoryName = resolvedParentName ?: if (form.editingTransactionId != 0L && form.selectedCategoryId == null) null else form.selectedParentCategoryName,
            selectedCategoryColorHex = resolvedCategoryNode?.colorHex ?: if (form.editingTransactionId != 0L && form.selectedCategoryId == null) null else form.selectedCategoryColorHex,
            selectedCategoryIconName = resolvedCategoryNode?.iconName ?: if (form.editingTransactionId != 0L && form.selectedCategoryId == null) null else form.selectedCategoryIconName,
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
            autofillBannerText = form.autofillBannerText,
            canUndoAutofill = form.autofillUndoSnapshot != null
        )
    }.flowOn(computationDispatcher)
    .catch { throwable ->
        if (throwable is kotlinx.coroutines.CancellationException) throw throwable
        emit(
            TransactionEntryUiState(
                errorMessage = "Entry calculation error: ${throwable.localizedMessage ?: "Invalid input"}"
            )
        )
    }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = TransactionEntryUiState(
            ledgerDateEpochDay = LocalDate.now().toEpochDay(),
            timestampEpochMillis = System.currentTimeMillis()
        )
    )

    private var autofillJob: kotlinx.coroutines.Job? = null

    fun resetForNewTransaction(
        defaultMode: TransactionType = TransactionType.EXPENSE,
        preselectedAccountId: Long? = null,
        initialEpochDay: Long? = null
    ) {
        autofillJob?.cancel()
        val targetEpochDay = initialEpochDay ?: lastUsedLedgerDateEpochDay
        lastUsedLedgerDateEpochDay = targetEpochDay
        payeeQueryFlow.value = ""
        formState.update { current ->
            EntryFormControlState(
                editingTransactionId = 0L,
                mode = defaultMode,
                ledgerDateEpochDay = targetEpochDay,
                timestampEpochMillis = System.currentTimeMillis(),
                expressionBuffer = "0",
                selectedAccountId = preselectedAccountId ?: current.selectedAccountId,
                isKeypadVisible = true,
                savedSuccessfullyCounter = current.savedSuccessfullyCounter,
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
            current.copy(
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
        formState.update { current ->
            val isTransferMode = mode == TransactionType.TRANSFER || mode == TransactionType.CC_SETTLEMENT
            val sanitizedPayee = if (!isTransferMode && current.payeeOrDescription.contains(" → ")) {
                ""
            } else {
                current.payeeOrDescription
            }
            current.copy(
                mode = mode,
                transferToAccountId = if (isTransferMode) current.transferToAccountId else null,
                transferFeeInput = if (isTransferMode) current.transferFeeInput else "",
                selectedCategoryId = if (isTransferMode) null else current.selectedCategoryId,
                selectedCategoryName = if (isTransferMode) null else current.selectedCategoryName,
                selectedParentCategoryName = if (isTransferMode) null else current.selectedParentCategoryName,
                isSplit = (mode == TransactionType.EXPENSE || mode == TransactionType.INCOME) && current.isSplit,
                isInstallmentEnabled = (mode == TransactionType.EXPENSE) && current.isInstallmentEnabled,
                payeeOrDescription = sanitizedPayee,
                isModeManuallySet = true,
                autofillBannerText = null,
                autofillUndoSnapshot = null,
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
                errorMessage = null
            )
        }
    }

    fun selectSourceAccount(accountId: Long) {
        autofillJob?.cancel()
        val accounts = referenceDataFlow.value.accounts
        formState.update { current ->
            val sourceAcc = accounts.firstOrNull { it.id == accountId }
            val currentDest = accounts.firstOrNull { it.id == current.transferToAccountId }
            val isDestCurrencyMismatch = sourceAcc != null && currentDest != null &&
                !currentDest.currencyCode.equals(sourceAcc.currencyCode, ignoreCase = true)
            val nextTransferTo = if (current.transferToAccountId == accountId || isDestCurrencyMismatch) {
                accounts.firstOrNull {
                    it.id != accountId && (sourceAcc == null || it.currencyCode.equals(sourceAcc.currencyCode, ignoreCase = true))
                }?.id
            } else {
                current.transferToAccountId
            }
            current.copy(
                selectedAccountId = accountId,
                transferToAccountId = nextTransferTo,
                isAccountManuallySet = true,
                autofillBannerText = null,
                autofillUndoSnapshot = null,
                errorMessage = null
            )
        }
    }

    fun selectTransferDestinationAccount(accountId: Long) {
        autofillJob?.cancel()
        val accounts = referenceDataFlow.value.accounts
        val dest = accounts.firstOrNull { it.id == accountId }
        val sourceId = formState.value.selectedAccountId
        val source = accounts.firstOrNull { it.id == sourceId } ?: accounts.firstOrNull()
        if (dest != null && source != null && !dest.currencyCode.equals(source.currencyCode, ignoreCase = true)) {
            formState.update {
                it.copy(
                    transferToAccountId = accountId,
                    isDestinationManuallySet = true,
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
                    autofillBannerText = null,
                    autofillUndoSnapshot = null,
                    errorMessage = null
                )
            }
        }
    }

    fun updateTransferFeeInput(feeInput: String) {
        formState.update { it.copy(transferFeeInput = feeInput, errorMessage = null) }
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
        onSuccess: ((CategoryNode) -> Unit)? = null
    ) {
        viewModelScope.launch {
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
            }
        }
    }

    fun setKeypadVisible(visible: Boolean) {
        formState.update { it.copy(isKeypadVisible = visible) }
    }

    fun updateAmountInput(rawInput: String) {
        val sanitized = rawInput.filter { it.isDigit() || it == '.' || it in "+-×÷*/" }
        val cleaned = when {
            sanitized.isEmpty() -> "0"
            sanitized == "." -> "0."
            sanitized.length > 1 && sanitized.startsWith("0") && !sanitized.startsWith("0.") -> {
                sanitized.dropWhile { it == '0' }.ifEmpty { "0" }
            }
            else -> sanitized
        }
        formState.update { current ->
            current.copy(expressionBuffer = cleaned, errorMessage = null)
        }
    }

    fun onKeypadKeyPress(key: String) {
        val currentForm = formState.value
        val refs = referenceDataFlow.value
        val selectedAccount = refs.accounts.firstOrNull { it.id == currentForm.selectedAccountId }
            ?: refs.accounts.firstOrNull()
        val currencyCode = selectedAccount?.currencyCode ?: userPreferencesFlow.value.primaryCurrencyCode
        val fractionDigits = SupportedCurrencies.fromCode(currencyCode).fractionDigits
        formState.update { current ->
            val buf = current.expressionBuffer
            val nextBuffer = when (key) {
                "C" -> "0"
                "⌫" -> {
                    if (buf.length <= 1) "0" else buf.dropLast(1)
                }
                "=" -> {
                    val evaluated = evaluateBufferSafely(buf, fractionDigits)
                    formatMinorPlain(evaluated, fractionDigits)
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
                    if (fractionDigits == 0 || lastNumberToken.contains('.')) {
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
                        val intPart = lastNumberToken.substringBefore('.')
                        val fracPart = if (lastNumberToken.contains('.')) lastNumberToken.substringAfter('.') else null
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
                        val intPart = lastNumberToken.substringBefore('.')
                        val fracPart = if (lastNumberToken.contains('.')) lastNumberToken.substringAfter('.') else null
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
            current.copy(expressionBuffer = nextBuffer, errorMessage = null)
        }
    }

    fun toggleSplitMode() {
        val currentForm = formState.value
        val refs = referenceDataFlow.value
        val selectedAccount = refs.accounts.firstOrNull { it.id == currentForm.selectedAccountId }
            ?: refs.accounts.firstOrNull()
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
                val halfMinor = currentTotal.amountMinor / 2L
                val remainderMinor = currentTotal.amountMinor - halfMinor
                listOf(
                    SplitLineDraft(
                        localId = nextSplitLocalId.getAndIncrement(),
                        categoryId = current.selectedCategoryId ?: defaultCat?.id ?: 0L,
                        categoryName = current.selectedCategoryName ?: defaultCat?.name ?: "Category 1",
                        categoryColorHex = current.selectedCategoryColorHex ?: defaultCat?.colorHex ?: "#059669",
                        categoryIconName = current.selectedCategoryIconName ?: defaultCat?.iconName ?: "category",
                        amountInput = formatMinorPlain(MinorMoney(halfMinor), digits)
                    ),
                    SplitLineDraft(
                        localId = nextSplitLocalId.getAndIncrement(),
                        categoryId = defaultCat?.id ?: 0L,
                        categoryName = defaultCat?.name ?: "Category 2",
                        categoryColorHex = defaultCat?.colorHex ?: "#2563EB",
                        categoryIconName = defaultCat?.iconName ?: "category",
                        amountInput = formatMinorPlain(MinorMoney(remainderMinor), digits)
                    )
                )
            } else {
                current.splitDrafts
            }

            current.copy(
                isSplit = enabling,
                isInstallmentEnabled = !enabling && current.isInstallmentEnabled,
                isRecurring = if (enabling) false else current.isRecurring,
                splitDrafts = initialDrafts,
                errorMessage = null
            )
        }
    }

    fun addSplitLine() {
        val currentForm = formState.value
        val refs = referenceDataFlow.value
        val selectedAccount = refs.accounts.firstOrNull { it.id == currentForm.selectedAccountId }
            ?: refs.accounts.firstOrNull()
        val currencySpec = SupportedCurrencies.fromCode(
            selectedAccount?.currencyCode ?: userPreferencesFlow.value.primaryCurrencyCode
        )
        val treeForMode = if (currentForm.mode == TransactionType.INCOME) refs.incomeCategories else refs.expenseCategories
        val defaultCat = treeForMode.firstOrNull()
        val digits = currencySpec.fractionDigits
        val parentTotal = evaluateBufferSafely(currentForm.expressionBuffer, digits)
        var totalAllocatedMinor = 0L
        for (draft in currentForm.splitDrafts) {
            totalAllocatedMinor += evaluateBufferSafely(draft.amountInput, digits).amountMinor
        }
        val remainingMinor = (parentTotal.amountMinor - totalAllocatedMinor).coerceAtLeast(0L)
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
                errorMessage = null
            )
        }
    }

    fun updateSplitLineAmount(localId: Long, amountInput: String) {
        formState.update { current ->
            current.copy(
                splitDrafts = current.splitDrafts.map { draft ->
                    if (draft.localId == localId) draft.copy(amountInput = amountInput) else draft
                },
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
                }
            )
        }
    }

    fun assignRemainingToSplitLine(localId: Long) {
        val currentForm = formState.value
        val refs = referenceDataFlow.value
        val selectedAccount = refs.accounts.firstOrNull { it.id == currentForm.selectedAccountId }
            ?: refs.accounts.firstOrNull()
        val currencySpec = SupportedCurrencies.fromCode(
            selectedAccount?.currencyCode ?: userPreferencesFlow.value.primaryCurrencyCode
        )
        val digits = currencySpec.fractionDigits
        val parentTotal = evaluateBufferSafely(currentForm.expressionBuffer, digits)
        val targetDraft = currentForm.splitDrafts.firstOrNull { it.localId == localId } ?: return

        var otherAllocatedMinor = 0L
        for (draft in currentForm.splitDrafts) {
            if (draft.localId != localId) {
                otherAllocatedMinor += evaluateBufferSafely(draft.amountInput, digits).amountMinor
            }
        }
        val remainingForTargetMinor = parentTotal.amountMinor - otherAllocatedMinor
        if (remainingForTargetMinor <= 0L) return
        val formatted = formatMinorPlain(MinorMoney(remainingForTargetMinor), digits)
        updateSplitLineAmount(localId, formatted)
    }

    fun removeSplitLine(localId: Long) {
        formState.update { current ->
            if (current.splitDrafts.size <= 2) {
                current.copy(errorMessage = "Split transactions require at least 2 split lines.")
            } else {
                current.copy(
                    splitDrafts = current.splitDrafts.filterNot { it.localId == localId },
                    errorMessage = null
                )
            }
        }
    }

    fun toggleInstallmentEnabled() {
        formState.update { current ->
            val next = !current.isInstallmentEnabled
            current.copy(
                isInstallmentEnabled = next,
                isRecurring = if (next) false else current.isRecurring,
                isSplit = !next && current.isSplit,
                errorMessage = null
            )
        }
    }

    fun toggleRecurringMode() {
        formState.update { current ->
            val next = !current.isRecurring
            current.copy(
                isRecurring = next,
                isInstallmentEnabled = if (next) false else current.isInstallmentEnabled,
                isSplit = if (next) false else current.isSplit,
                transferFeeInput = if (next) "" else current.transferFeeInput,
                errorMessage = null
            )
        }
    }

    fun setRecurringFrequency(frequency: RepeatFrequency) {
        formState.update { current ->
            current.copy(
                isRecurring = true,
                recurringFrequency = frequency,
                isInstallmentEnabled = false,
                isSplit = false,
                transferFeeInput = "",
                errorMessage = null
            )
        }
    }

    fun disableRecurringMode() {
        formState.update { it.copy(isRecurring = false, errorMessage = null) }
    }

    fun setInstallmentMonthsCount(months: Int) {
        formState.update {
            it.copy(installmentMonthsCount = months.coerceIn(2, 60), errorMessage = null)
        }
    }

    fun updatePayeeOrDescription(value: String) {
        autofillJob?.cancel()
        payeeQueryFlow.value = value
        formState.update { it.copy(payeeOrDescription = value, errorMessage = null) }
    }

    fun updateNote(value: String) {
        formState.update { it.copy(note = value, errorMessage = null) }
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
                val effectiveAccount = refs.accounts.firstOrNull { it.id == current.selectedAccountId }
                    ?: refs.accounts.firstOrNull()
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
                        activeAccounts = refs.accounts,
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

                val resolvedAccount = refs.accounts.firstOrNull { it.id == resolved.updatedAccountId }
                    ?: refs.accounts.firstOrNull()
                val keepInstallment = current.isInstallmentEnabled &&
                    resolved.updatedMode == TransactionType.EXPENSE &&
                    resolvedAccount?.groupType == AccountGroup.CREDIT_CARD

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
        formState.update { current ->
            val snapshot = current.autofillUndoSnapshot ?: return@update current
            current.copy(
                mode = snapshot.previousMode,
                selectedAccountId = snapshot.previousAccountId,
                transferToAccountId = snapshot.previousTransferToAccountId,
                selectedCategoryId = snapshot.previousCategoryId,
                selectedCategoryName = snapshot.previousCategoryName,
                selectedParentCategoryName = snapshot.previousParentCategoryName,
                selectedCategoryColorHex = snapshot.previousCategoryColorHex,
                selectedCategoryIconName = snapshot.previousCategoryIconName,
                selectedTags = snapshot.previousTags,
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
        viewModelScope.launch {
            val rules = repo.observeAllRecurringRules().first()
            val rule = rules.firstOrNull { it.id == ruleId } ?: return@launch
            if (isRecurringCycleAlreadyLogged(rule.nextOccurrenceEpochDay, expectedDueEpochDay)) {
                onAlreadyLogged()
                return@launch
            }
            val spec = SupportedCurrencies.fromCode(rule.currencyCode)
            val isTransferMode = rule.type == TransactionType.TRANSFER || rule.type == TransactionType.CC_SETTLEMENT
            val plainAmount = formatMinorPlain(rule.amount, spec.fractionDigits)
            val loadedPayee = rule.payeeOrDescription.ifEmpty { rule.title }
            payeeQueryFlow.value = loadedPayee
            formState.update { current ->
                EntryFormControlState(
                    editingTransactionId = 0L,
                    mode = rule.type,
                    expressionBuffer = plainAmount,
                    selectedAccountId = rule.accountId,
                    transferToAccountId = if (isTransferMode) rule.transferToAccountId else null,
                    selectedCategoryId = if (isTransferMode) null else rule.categoryId,
                    payeeOrDescription = loadedPayee,
                    note = rule.note,
                    ledgerDateEpochDay = rule.nextOccurrenceEpochDay,
                    timestampEpochMillis = System.currentTimeMillis(),
                    linkedRecurringRuleId = rule.id,
                    isKeypadVisible = false,
                    savedSuccessfullyCounter = current.savedSuccessfullyCounter,
                    isAccountManuallySet = true,
                    isDestinationManuallySet = true,
                    isCategoryManuallySet = true,
                    isTagsManuallySet = true,
                    isModeManuallySet = true,
                    autofillBannerText = null,
                    autofillUndoSnapshot = null
                )
            }
            onReady()
        }
    }

    fun applyBookmarkById(bookmarkId: Long) {
        resetForNewTransaction(initialEpochDay = java.time.LocalDate.now().toEpochDay())
        viewModelScope.launch {
            val bookmarks = categoryAndTagRepository.observeBookmarks().first()
            val match = bookmarks.firstOrNull { it.id == bookmarkId } ?: return@launch
            // Wait until accounts/preferences have loaded on cold start
            referenceDataFlow.first { it.accounts.isNotEmpty() || it.expenseCategories.isNotEmpty() }
            applyBookmark(match)
        }
    }

    fun applyBookmark(bookmark: TransactionBookmark) {
        autofillJob?.cancel()
        val bookmarkAccount = referenceDataFlow.value.accounts.firstOrNull { it.id == bookmark.accountId }
        val bookmarkCurrencySpec = SupportedCurrencies.fromCode(
            bookmarkAccount?.currencyCode ?: userPreferencesFlow.value.primaryCurrencyCode
        )
        val plainAmount = formatMinorPlain(bookmark.amount, bookmarkCurrencySpec.fractionDigits)
        val isTransferMode = bookmark.type == TransactionType.TRANSFER || bookmark.type == TransactionType.CC_SETTLEMENT
        payeeQueryFlow.value = bookmark.payeeOrDescription
        formState.update { current ->
            current.copy(
                mode = bookmark.type,
                expressionBuffer = plainAmount,
                selectedAccountId = bookmark.accountId,
                transferToAccountId = if (isTransferMode) bookmark.transferToAccountId else null,
                selectedCategoryId = if (isTransferMode) null else bookmark.categoryId,
                payeeOrDescription = bookmark.payeeOrDescription,
                note = bookmark.note,
                selectedTags = bookmark.tags,
                isSplit = false,
                isRecurring = false,
                isInstallmentEnabled = false,
                linkedRecurringRuleId = null,
                errorMessage = null,
                isAccountManuallySet = true,
                isDestinationManuallySet = true,
                isCategoryManuallySet = true,
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
        val refs = referenceDataFlow.value
        val account = refs.accounts.firstOrNull { it.id == currentForm.selectedAccountId }
            ?: refs.accounts.firstOrNull() ?: return
        val transferToAccount = refs.accounts.firstOrNull { it.id == currentForm.transferToAccountId }
            ?: refs.accounts.firstOrNull { it.id != account.id }
        val currencySpec = SupportedCurrencies.fromCode(
            account.currencyCode ?: userPreferencesFlow.value.primaryCurrencyCode
        )
        val evaluatedAmount = evaluateBufferSafely(currentForm.expressionBuffer, currencySpec.fractionDigits)
        val isTransferMode = currentForm.mode == TransactionType.TRANSFER || currentForm.mode == TransactionType.CC_SETTLEMENT
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
            categoryAndTagRepository.upsertBookmark(bookmark)
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
                current
            } else {
                acquiredSaveLock = true
                current.copy(isSaving = true, errorMessage = null)
            }
        }
        if (!acquiredSaveLock) return

        val latestForm = formState.value
        val refs = referenceDataFlow.value
        val treeForMode = if (latestForm.mode == TransactionType.INCOME) refs.incomeCategories else refs.expenseCategories
        val effectiveCategoryId = latestForm.selectedCategoryId
            ?: if (latestForm.editingTransactionId == 0L) treeForMode.firstOrNull()?.id else null
        val effectiveCategoryNode = findCategoryNodeById(treeForMode, effectiveCategoryId)
        val effectiveParentName = findParentCategoryName(treeForMode, effectiveCategoryId)
            ?: latestForm.selectedParentCategoryName

        val sourceAccount = refs.accounts.firstOrNull { it.id == latestForm.selectedAccountId }
            ?: refs.accounts.firstOrNull()
        if (sourceAccount == null) {
            formState.update { it.copy(isSaving = false, errorMessage = "Please select an account.") }
            return
        }

        val isTransferMode = latestForm.mode == TransactionType.TRANSFER || latestForm.mode == TransactionType.CC_SETTLEMENT
        val destAccount = if (isTransferMode) {
            refs.accounts.firstOrNull { it.id == latestForm.transferToAccountId }
                ?: refs.accounts.firstOrNull { it.id != sourceAccount.id }
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
        } else if (!latestForm.isSplit && effectiveCategoryId == null) {
            formState.update { it.copy(isSaving = false, errorMessage = "Please select a category.") }
            return
        }

        val currencySpec = SupportedCurrencies.fromCode(
            sourceAccount.currencyCode ?: userPreferencesFlow.value.primaryCurrencyCode
        )
        val amount = evaluateBufferSafely(
            rawExpression = latestForm.expressionBuffer,
            fractionDigits = currencySpec.fractionDigits
        )
        if (!amount.isPositive) {
            formState.update { it.copy(isSaving = false, errorMessage = "Enter an amount greater than zero.") }
            return
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

        viewModelScope.launch {
            try {
                val existingId = latestForm.editingTransactionId
                val isExpenseOrIncome = latestForm.mode == TransactionType.EXPENSE || latestForm.mode == TransactionType.INCOME

                val transferFee = if (isTransferMode && !latestForm.isRecurring) {
                    evaluateBufferSafely(
                        rawExpression = latestForm.transferFeeInput,
                        fractionDigits = currencySpec.fractionDigits
                    )
                } else {
                    MinorMoney.ZERO
                }

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
                    !latestForm.isSplit
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

                    latestForm.isSplit -> {
                        transactionRepository.upsertSplitTransaction(baseTx)
                    }

                    else -> {
                        transactionRepository.upsertSingleTransaction(baseTx)
                    }
                }

                saveResult.fold(
                    onSuccess = {
                        val linkedRuleId = latestForm.linkedRecurringRuleId
                        if (existingId == 0L && linkedRuleId != null && analyticsAndBudgetRepository != null) {
                            withContext(NonCancellable + ioDispatcher) {
                                analyticsAndBudgetRepository.advanceRecurringScheduleIfDue(
                                    ruleId = linkedRuleId,
                                    currentDueEpochDay = latestForm.ledgerDateEpochDay
                                )
                            }
                        }
                        lastUsedLedgerDateEpochDay = latestForm.ledgerDateEpochDay
                        payeeQueryFlow.value = ""
                        autofillJob?.cancel()
                        formState.update { current ->
                            EntryFormControlState(
                                editingTransactionId = 0L,
                                mode = current.mode,
                                selectedAccountId = current.selectedAccountId,
                                ledgerDateEpochDay = latestForm.ledgerDateEpochDay,
                                savedSuccessfullyCounter = current.savedSuccessfullyCounter + 1
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

    private data class ReferenceBundle(
        val accounts: List<AccountSummary>,
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