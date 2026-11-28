/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/ui/accounts/AccountsViewModel.kt
package com.folioledger.app.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NetWorthOverview
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionSearchFilter
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.repository.AccountRepository
import com.folioledger.app.domain.repository.SettingsAndBackupRepository
import com.folioledger.app.domain.repository.TransactionRepository
import com.folioledger.app.domain.util.AccountPassbookCalculator
import com.folioledger.app.domain.util.CurrencyDisplayPolicy
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

typealias CreditCardCycleWindow = com.folioledger.app.domain.util.CreditCardCycleWindow
typealias CreditCardCycleCalculator = com.folioledger.app.domain.util.CreditCardCycleCalculator

private data class AccountsInternalControlState(
    val referenceDateEpochDay: Long = LocalDate.now().toEpochDay(),
    val showArchivedAccounts: Boolean = false,
    val collapsedGroups: Set<AccountGroup> = emptySet(),
    val inspectedAccountId: Long? = null,
    val passbookMonthOffset: Int = 0,
    val editorDialogState: AccountEditorDialogState? = null,
    val settlementSheetState: CreditCardSettlementSheetState? = null,
    val reconcileDialogState: ReconcileBalanceDialogState? = null,
    val pendingDeletionAccountId: Long? = null,
    val pendingDeletionAccountName: String? = null,
    val pendingDeletionTxCount: Int = 0,
    val pendingDeletionLinkedCardsCount: Int = 0,
    val isSaving: Boolean = false,
    val snackbarMessage: String? = null,
)

private data class AccountsProjectionKey(
    val referenceDateEpochDay: Long,
    val showArchivedAccounts: Boolean,
    val inspectedAccountId: Long?,
    val passbookMonthOffset: Int,
)

private data class AccountsComputedCore(
    val resolvedOverview: NetWorthOverview,
    val allAccounts: List<AccountSummary>,
    val assetAccounts: List<AccountSummary>,
    val bankCandidates: List<AccountSummary>,
    val groupTotalsByGroup: Map<AccountGroup, MinorMoney>,
    val creditCardGroupPayableTotal: MinorMoney,
    val creditCardGroupUnbilledTotal: MinorMoney,
    val recentTransactionsForSelectedAccount: List<TransactionItem>,
    val inspectedAccount: AccountSummary?,
    val passbookModel: com.folioledger.app.domain.model.AccountPassbookUiModel?,
    val currencySpec: CurrencySpec,
    val primaryCurrencyCode: String,
    val secondaryCurrencies: List<SecondaryCurrency>,
    val numberGrouping: NumberGroupingSystem,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AccountsViewModel(
    private val accountRepository: AccountRepository,
    transactionRepository: TransactionRepository,
    settingsAndBackupRepository: SettingsAndBackupRepository,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val controlState = MutableStateFlow(AccountsInternalControlState())

    private val userPreferencesFlow = settingsAndBackupRepository.observeUserPreferences().distinctUntilChanged()

    private val currencyPreferencesFlow = userPreferencesFlow
        .map { Pair(it.primaryCurrencyCode, it.secondaryCurrencies) }
        .distinctUntilChanged()

    private val referenceDateFlow = controlState
        .map { it.referenceDateEpochDay }
        .distinctUntilChanged()

    private val allAccountsFlow: StateFlow<List<AccountSummary>> = referenceDateFlow
        .flatMapLatest { refEpochDay ->
            accountRepository.observeAllAccounts(refEpochDay)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    private val netWorthFlow = combine(
        allAccountsFlow,
        currencyPreferencesFlow,
    ) { allSummaries, (primaryCurrencyCode, secondaryCurrencies) ->
        val activeSummaries = allSummaries.filter { !it.isArchived }
        val primarySpec = SupportedCurrencies.fromCode(primaryCurrencyCode)

        var totalAssetsMinor = 0L
        var totalLiabilitiesMinor = 0L

        for (account in activeSummaries) {
            if (!account.includeInNetWorth || account.groupType == AccountGroup.DEBIT_CARD) {
                continue
            }
            val rawBalanceMinor = account.currentBalance.amountMinor
            if (rawBalanceMinor == 0L) continue

            val convertedBalanceMinor = MoneyFormatterAndCalculator.convertToPrimaryMinor(
                sourceMinor = rawBalanceMinor,
                sourceCurrencyCode = account.currencyCode,
                primaryCurrencyCode = primarySpec.code,
                secondaryCurrencies = secondaryCurrencies,
            )

            if (convertedBalanceMinor > 0L) {
                totalAssetsMinor = MoneyFormatterAndCalculator.safeAdd(totalAssetsMinor, convertedBalanceMinor)
            } else if (convertedBalanceMinor < 0L) {
                totalLiabilitiesMinor = MoneyFormatterAndCalculator.safeAdd(totalLiabilitiesMinor, MoneyFormatterAndCalculator.safeAbs(convertedBalanceMinor))
            }
        }

        NetWorthOverview(
            totalAssets = MinorMoney(totalAssetsMinor),
            totalLiabilities = MinorMoney(totalLiabilitiesMinor),
            netWorth = MinorMoney(totalAssetsMinor) - MinorMoney(totalLiabilitiesMinor),
            accountsByGroup = activeSummaries.groupBy { it.groupType },
        )
    }

    private val passbookTransactionsFlow = combine(
        controlState.map { it.inspectedAccountId }.distinctUntilChanged(),
        allAccountsFlow.map { list -> list.map { Triple(it.id, it.groupType, it.parentAccountId) } }.distinctUntilChanged(),
    ) { inspectedId, accountMeta ->
        if (inspectedId == null) {
            emptySet()
        } else {
            buildSet {
                add(inspectedId)
                val target = accountMeta.firstOrNull { it.first == inspectedId }
                if (target?.second == AccountGroup.DEBIT_CARD && target.third != null) {
                    val parentId = target.third!!
                    add(parentId)
                    accountMeta.filter { it.third == parentId }.forEach { add(it.first) }
                } else {
                    accountMeta.filter { it.third == inspectedId }.forEach { add(it.first) }
                }
            }
        }
    }.distinctUntilChanged().flatMapLatest { accountIds ->
        if (accountIds.isEmpty()) {
            flowOf(emptyList())
        } else {
            transactionRepository.searchAndFilterTransactions(TransactionSearchFilter(accountIds = accountIds))
        }
    }

    private val projectionKeyFlow = controlState.map {
        AccountsProjectionKey(
            referenceDateEpochDay = it.referenceDateEpochDay,
            showArchivedAccounts = it.showArchivedAccounts,
            inspectedAccountId = it.inspectedAccountId,
            passbookMonthOffset = it.passbookMonthOffset,
        )
    }.distinctUntilChanged()

    private val computedCoreFlow: Flow<AccountsComputedCore> = combine(
        netWorthFlow,
        allAccountsFlow,
        userPreferencesFlow,
        passbookTransactionsFlow,
        projectionKeyFlow,
    ) { netWorth, allAccounts, prefs, allTransactions, key ->
        val currencySpec = SupportedCurrencies.fromCode(prefs.primaryCurrencyCode)
        val visibleAccounts = if (key.showArchivedAccounts) {
            allAccounts
        } else {
            allAccounts.filter { !it.isArchived }
        }

        val groupedAccounts = visibleAccounts.groupBy { it.groupType }
        val resolvedOverview = netWorth.copy(accountsByGroup = groupedAccounts)

        val groupTotalsByGroup = groupedAccounts.mapValues { (_, accounts) ->
            var totalMinor = 0L
            for (acc in accounts) {
                val rawMinor = if (acc.groupType == AccountGroup.DEBIT_CARD) {
                    acc.debitCardPeriodSpend?.amountMinor ?: 0L
                } else {
                    acc.currentBalance.amountMinor
                }
                if (rawMinor == 0L) continue
                val convertedMinor = MoneyFormatterAndCalculator.convertToPrimaryMinor(
                    sourceMinor = rawMinor,
                    sourceCurrencyCode = acc.currencyCode,
                    primaryCurrencyCode = currencySpec.code,
                    secondaryCurrencies = prefs.secondaryCurrencies,
                )
                totalMinor = MoneyFormatterAndCalculator.safeAdd(totalMinor, convertedMinor)
            }
            MinorMoney(totalMinor)
        }

        var ccPayableTotalMinor = 0L
        var ccUnbilledTotalMinor = 0L
        val ccAccounts = groupedAccounts[AccountGroup.CREDIT_CARD].orEmpty()
        for (acc in ccAccounts) {
            val payableRaw = acc.creditCardStatementPayable?.amountMinor ?: 0L
            if (payableRaw > 0L) {
                val converted = MoneyFormatterAndCalculator.convertToPrimaryMinor(
                    sourceMinor = payableRaw,
                    sourceCurrencyCode = acc.currencyCode,
                    primaryCurrencyCode = currencySpec.code,
                    secondaryCurrencies = prefs.secondaryCurrencies,
                )
                ccPayableTotalMinor = MoneyFormatterAndCalculator.safeAdd(ccPayableTotalMinor, converted)
            }
            val totalDebt = if (acc.currentBalance.isNegative) {
                MoneyFormatterAndCalculator.safeAbs(acc.currentBalance.amountMinor)
            } else {
                0L
            }
            val unbilledRaw = acc.creditCardUnbilledAmount?.amountMinor
                ?: if (totalDebt > payableRaw) totalDebt - payableRaw else 0L
            if (unbilledRaw > 0L) {
                val converted = MoneyFormatterAndCalculator.convertToPrimaryMinor(
                    sourceMinor = unbilledRaw,
                    sourceCurrencyCode = acc.currencyCode,
                    primaryCurrencyCode = currencySpec.code,
                    secondaryCurrencies = prefs.secondaryCurrencies,
                )
                ccUnbilledTotalMinor = MoneyFormatterAndCalculator.safeAdd(ccUnbilledTotalMinor, converted)
            }
        }

        val assetAccounts = allAccounts.filter { acc ->
            !acc.isArchived && (
                acc.groupType == AccountGroup.BANK_ACCOUNT ||
                    acc.groupType == AccountGroup.CASH ||
                    acc.groupType == AccountGroup.SAVINGS ||
                    acc.groupType == AccountGroup.PREPAID_WALLET
                )
        }

        val bankCandidates = allAccounts.filter { acc ->
            !acc.isArchived && (
                acc.groupType == AccountGroup.BANK_ACCOUNT ||
                    acc.groupType == AccountGroup.SAVINGS ||
                    acc.groupType == AccountGroup.OVERDRAFT
                )
        }

        val inspectedAccount = key.inspectedAccountId?.let { targetId ->
            allAccounts.firstOrNull { it.id == targetId }
        }

        val passbookModel = if (inspectedAccount != null) {
            AccountPassbookCalculator.buildAccountPassbookUiModel(
                inspectedAccount = inspectedAccount,
                allAccounts = allAccounts,
                allTransactions = allTransactions,
                monthStartDay = prefs.monthStartDay,
                referenceDateEpochDay = key.referenceDateEpochDay,
                passbookMonthOffset = key.passbookMonthOffset,
            )
        } else {
            null
        }

        val periodTxnsNewestFirst = passbookModel?.entries?.map { it.transaction }.orEmpty()

        AccountsComputedCore(
            resolvedOverview = resolvedOverview,
            allAccounts = allAccounts,
            assetAccounts = assetAccounts,
            bankCandidates = bankCandidates,
            groupTotalsByGroup = groupTotalsByGroup,
            creditCardGroupPayableTotal = MinorMoney(ccPayableTotalMinor),
            creditCardGroupUnbilledTotal = MinorMoney(ccUnbilledTotalMinor),
            recentTransactionsForSelectedAccount = periodTxnsNewestFirst,
            inspectedAccount = inspectedAccount,
            passbookModel = passbookModel,
            currencySpec = currencySpec,
            primaryCurrencyCode = prefs.primaryCurrencyCode,
            secondaryCurrencies = prefs.secondaryCurrencies,
            numberGrouping = prefs.numberGrouping,
        )
    }.flowOn(computationDispatcher)

    val uiState: StateFlow<AccountsUiState> = combine(
        computedCoreFlow,
        controlState,
    ) { core, ctrl ->
        AccountsUiState(
            netWorthOverview = core.resolvedOverview,
            allAccounts = core.allAccounts,
            assetAccountsForSettlement = core.assetAccounts,
            parentBankCandidates = core.bankCandidates,
            groupTotalsByGroup = core.groupTotalsByGroup,
            creditCardGroupPayableTotal = core.creditCardGroupPayableTotal,
            creditCardGroupUnbilledTotal = core.creditCardGroupUnbilledTotal,
            recentTransactionsForSelectedAccount = core.recentTransactionsForSelectedAccount,
            inspectedAccountId = ctrl.inspectedAccountId,
            inspectedAccount = core.inspectedAccount,
            passbookMonthOffset = ctrl.passbookMonthOffset,
            accountPassbook = core.passbookModel,
            currencySpec = core.currencySpec,
            primaryCurrencyCode = core.primaryCurrencyCode,
            secondaryCurrencies = core.secondaryCurrencies,
            numberGrouping = core.numberGrouping,
            referenceDateEpochDay = ctrl.referenceDateEpochDay,
            showArchivedAccounts = ctrl.showArchivedAccounts,
            collapsedGroups = ctrl.collapsedGroups,
            editorDialogState = ctrl.editorDialogState,
            settlementSheetState = ctrl.settlementSheetState,
            reconcileDialogState = ctrl.reconcileDialogState,
            pendingDeletionAccountId = ctrl.pendingDeletionAccountId,
            pendingDeletionAccountName = ctrl.pendingDeletionAccountName,
            pendingDeletionTxCount = ctrl.pendingDeletionTxCount,
            pendingDeletionLinkedCardsCount = ctrl.pendingDeletionLinkedCardsCount,
            isSaving = ctrl.isSaving,
            snackbarMessage = ctrl.snackbarMessage,
        )
    }.flowOn(computationDispatcher)
        .catch { throwable ->
            if (throwable is kotlinx.coroutines.CancellationException) throw throwable
            emit(
                AccountsUiState(
                    referenceDateEpochDay = controlState.value.referenceDateEpochDay,
                    snackbarMessage = "Accounts calculation error: ${throwable.localizedMessage ?: "Calculation error"}",
                ),
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = AccountsUiState(
                referenceDateEpochDay = LocalDate.now().toEpochDay(),
            ),
        )

    fun refreshData() {
        val today = LocalDate.now().toEpochDay()
        controlState.update { current ->
            if (current.referenceDateEpochDay != today) {
                current.copy(referenceDateEpochDay = today)
            } else {
                current
            }
        }
    }

    fun toggleGroupCollapsed(group: AccountGroup) {
        controlState.update { current ->
            val next = if (group in current.collapsedGroups) {
                current.collapsedGroups - group
            } else {
                current.collapsedGroups + group
            }
            current.copy(collapsedGroups = next)
        }
    }

    fun toggleShowArchivedAccounts() {
        controlState.update { current ->
            current.copy(showArchivedAccounts = !current.showArchivedAccounts)
        }
    }

    fun toggleAccountPin(accountId: Long) {
        viewModelScope.launch {
            val result = accountRepository.toggleAccountPinned(accountId)
            if (result.isFailure) {
                controlState.update {
                    it.copy(snackbarMessage = "Failed to update pin: ${result.exceptionOrNull()?.message}")
                }
            }
        }
    }

    fun selectAccountForInspection(accountId: Long?) {
        controlState.update {
            it.copy(
                inspectedAccountId = accountId,
                passbookMonthOffset = 0,
            )
        }
    }

    fun openReconcileDialog(account: AccountSummary) {
        if (account.isArchived) {
            controlState.update {
                it.copy(snackbarMessage = "Unarchive this account before reconciling its balance.")
            }
            return
        }
        val spec = SupportedCurrencies.fromCode(account.currencyCode)
        val isLiability = account.groupType.isLiabilityByDefault
        val isInvestment = account.groupType == AccountGroup.INVESTMENT
        val initialAmount = if (isLiability) {
            MinorMoney(MoneyFormatterAndCalculator.safeNegate(account.currentBalance.amountMinor))
        } else {
            account.currentBalance
        }
        val initialInput = formatMinorPlainForInput(initialAmount, spec.fractionDigits)
        controlState.update {
            it.copy(
                reconcileDialogState = ReconcileBalanceDialogState(
                    accountId = account.id,
                    accountName = account.name,
                    currencyCode = account.currencyCode,
                    recordedBalance = account.currentBalance,
                    targetBalanceInput = initialInput,
                    reconcileDateEpochDay = controlState.value.referenceDateEpochDay,
                    note = "",
                    isLiability = isLiability,
                    isInvestment = isInvestment,
                ),
            )
        }
    }

    fun updateReconcileDialogState(transform: (ReconcileBalanceDialogState) -> ReconcileBalanceDialogState) {
        controlState.update { current ->
            val existing = current.reconcileDialogState ?: return@update current
            current.copy(reconcileDialogState = transform(existing).copy(validationError = null))
        }
    }

    private fun setReconcileValidationError(message: String) {
        controlState.update { current ->
            current.copy(reconcileDialogState = current.reconcileDialogState?.copy(validationError = message))
        }
    }

    fun dismissReconcileDialog() {
        controlState.update { it.copy(reconcileDialogState = null) }
    }

    fun submitReconcileDialog() {
        val ctrl = controlState.value
        val dialog = ctrl.reconcileDialogState ?: return
        val spec = SupportedCurrencies.fromCode(dialog.currencyCode)
        val parsed = parseInputToMinorOrZero(
            rawInput = dialog.targetBalanceInput,
            fractionDigits = spec.fractionDigits,
            allowBlankAsZero = false,
        )
        if (parsed == null) {
            setReconcileValidationError("Invalid target balance.")
            return
        }

        var acquired = false
        controlState.update { current ->
            if (current.isSaving) {
                current
            } else {
                acquired = true
                current.copy(isSaving = true)
            }
        }
        if (!acquired) return

        val targetBalance = if (dialog.isLiability) {
            MinorMoney(MoneyFormatterAndCalculator.safeNegate(parsed.amountMinor))
        } else {
            parsed
        }

        viewModelScope.launch {
            val result = accountRepository.reconcileAccountBalance(
                accountId = dialog.accountId,
                reconciledBalance = targetBalance,
                reconciledDateEpochDay = dialog.reconcileDateEpochDay,
                note = dialog.note,
            )
            result.fold(
                onSuccess = { txId ->
                    controlState.update {
                        it.copy(
                            isSaving = false,
                            reconcileDialogState = null,
                            snackbarMessage = if (txId == 0L) {
                                "Balances already match \u2014 no adjustment needed."
                            } else {
                                "Account balance reconciled successfully."
                            },
                        )
                    }
                },
                onFailure = { error ->
                    controlState.update { current ->
                        current.copy(
                            isSaving = false,
                            reconcileDialogState = current.reconcileDialogState?.copy(
                                validationError = error.message ?: "Failed to reconcile balance.",
                            ),
                        )
                    }
                },
            )
        }
    }

    fun dismissInspectedAccountSheet() {
        controlState.update {
            it.copy(
                inspectedAccountId = null,
                passbookMonthOffset = 0,
            )
        }
    }

    fun stepPassbookMonth(deltaMonths: Int) {
        controlState.update { current ->
            current.copy(passbookMonthOffset = current.passbookMonthOffset + deltaMonths)
        }
    }

    fun resetPassbookToCurrentMonth() {
        controlState.update { current ->
            current.copy(passbookMonthOffset = 0)
        }
    }

    fun clearSnackbarMessage() {
        controlState.update { it.copy(snackbarMessage = null) }
    }

    fun openCreateAccountDialog(defaultGroup: AccountGroup = AccountGroup.BANK_ACCOUNT) {
        val currentState = uiState.value
        val refEpochDay = controlState.value.referenceDateEpochDay
        val allAccounts = allAccountsFlow.value.ifEmpty { currentState.allAccounts }
        val bankCandidates = currentState.parentBankCandidates.ifEmpty {
            allAccounts.filter { acc ->
                !acc.isArchived && (
                    acc.groupType == AccountGroup.BANK_ACCOUNT ||
                        acc.groupType == AccountGroup.SAVINGS ||
                        acc.groupType == AccountGroup.OVERDRAFT
                    )
            }
        }
        val defaultIcon = defaultIconForGroup(defaultGroup)
        val defaultColor = defaultColorForGroup(defaultGroup)
        val defaultParentId = if (defaultGroup == AccountGroup.DEBIT_CARD) {
            bankCandidates.firstOrNull()?.id
        } else {
            null
        }
        val initialCurrency = if (defaultGroup == AccountGroup.DEBIT_CARD && defaultParentId != null) {
            bankCandidates.firstOrNull { it.id == defaultParentId }?.currencyCode ?: currentState.currencySpec.code
        } else {
            currentState.currencySpec.code
        }

        val defaultStatementDay = CreditCardCycleCalculator.DEFAULT_STATEMENT_DAY
        val defaultDueDay = CreditCardCycleCalculator.DEFAULT_PAYMENT_DUE_DAY
        val initialAnchorEpochDay = if (defaultGroup == AccountGroup.CREDIT_CARD) {
            CreditCardCycleCalculator.computeCycleWindow(
                referenceDateEpochDay = refEpochDay,
                settlementDayOfMonth = defaultStatementDay,
                paymentDueDayOfMonth = defaultDueDay,
            ).lastStatementEnd.toEpochDay()
        } else {
            refEpochDay
        }

        controlState.update {
            it.copy(
                editorDialogState = AccountEditorDialogState(
                    isEditing = false,
                    accountId = 0L,
                    name = "",
                    groupType = defaultGroup,
                    currencyCode = initialCurrency,
                    openingBalanceInput = "0",
                    openingDateEpochDay = initialAnchorEpochDay,
                    creditLimitInput = if (defaultGroup == AccountGroup.CREDIT_CARD) "100000" else "",
                    statementPayableInput = "",
                    unbilledAmountInput = "",
                    settlementDayOfMonth = defaultStatementDay,
                    paymentDueDayOfMonth = defaultDueDay,
                    parentAccountId = defaultParentId,
                    includeInNetWorth = true,
                    isPinned = false,
                    isArchived = false,
                    colorHex = defaultColor,
                    iconName = defaultIcon,
                    validationError = null,
                ),
            )
        }
    }

    fun openEditAccountDialog(account: AccountSummary) {
        val spec = SupportedCurrencies.fromCode(account.currencyCode)
        val isLiability = account.groupType.isLiabilityByDefault
        val isLiabilityInCredit = isLiability && account.currentBalance.amountMinor > 0L
        val balanceFormatted = if (isLiability) {
            formatMinorPlainForInput(account.currentBalance.abs(), spec.fractionDigits)
        } else {
            formatMinorPlainForInput(account.currentBalance, spec.fractionDigits)
        }
        val limitFormatted = account.creditLimit?.let {
            formatMinorPlainForInput(it.abs(), spec.fractionDigits)
        } ?: ""
        val payableFormatted = account.creditCardStatementPayable?.let {
            formatMinorPlainForInput(it.abs(), spec.fractionDigits)
        } ?: ""
        val unbilledFormatted = account.creditCardUnbilledAmount?.let {
            formatMinorPlainForInput(it.abs(), spec.fractionDigits)
        } ?: ""
        val lockedParentName = account.parentAccountId?.let { parentId ->
            val all = allAccountsFlow.value.ifEmpty { uiState.value.allAccounts }
            all.firstOrNull { it.id == parentId }?.let { parent ->
                if (parent.isArchived) "${parent.name} (Archived)" else parent.name
            }
        }

        controlState.update {
            it.copy(
                editorDialogState = AccountEditorDialogState(
                    isEditing = true,
                    accountId = account.id,
                    name = account.name,
                    groupType = account.groupType,
                    currencyCode = account.currencyCode,
                    openingBalanceInput = balanceFormatted,
                    openingDateEpochDay = LocalDate.now().toEpochDay(),
                    creditLimitInput = limitFormatted,
                    statementPayableInput = payableFormatted,
                    unbilledAmountInput = unbilledFormatted,
                    settlementDayOfMonth = (account.settlementDayOfMonth ?: CreditCardCycleCalculator.DEFAULT_STATEMENT_DAY).coerceIn(1, 31),
                    paymentDueDayOfMonth = (account.paymentDueDayOfMonth ?: CreditCardCycleCalculator.DEFAULT_PAYMENT_DUE_DAY).coerceIn(1, 31),
                    parentAccountId = account.parentAccountId,
                    lockedParentAccountName = lockedParentName,
                    includeInNetWorth = account.includeInNetWorth,
                    isPinned = account.isPinned,
                    isArchived = account.isArchived,
                    colorHex = account.colorHex,
                    iconName = account.iconName,
                    validationError = null,
                    isLiabilityInCredit = isLiabilityInCredit,
                ),
            )
        }
    }

    fun updateEditorDialogState(transform: (AccountEditorDialogState) -> AccountEditorDialogState) {
        controlState.update { current ->
            val existing = current.editorDialogState ?: return@update current
            current.copy(editorDialogState = transform(existing).copy(validationError = null))
        }
    }

    private fun setEditorValidationError(message: String) {
        controlState.update { current ->
            current.copy(editorDialogState = current.editorDialogState?.copy(validationError = message))
        }
    }

    fun dismissAccountEditorDialog() {
        controlState.update { it.copy(editorDialogState = null) }
    }

    fun requestAccountDeletion(accountId: Long) {
        viewModelScope.launch {
            val txCount = accountRepository.getTransactionCountForAccount(accountId)
            val all = allAccountsFlow.value.ifEmpty { uiState.value.allAccounts }
            val account = all.firstOrNull { it.id == accountId }
            val linkedCardsCount = all.count { it.parentAccountId == accountId }
            controlState.update {
                it.copy(
                    editorDialogState = null,
                    pendingDeletionAccountId = accountId,
                    pendingDeletionAccountName = account?.name ?: "Account #$accountId",
                    pendingDeletionTxCount = txCount,
                    pendingDeletionLinkedCardsCount = linkedCardsCount,
                )
            }
        }
    }

    fun confirmDeleteAccount(accountId: Long, deleteTransactions: Boolean) {
        var acquired = false
        controlState.update { current ->
            if (current.isSaving) {
                current
            } else {
                acquired = true
                current.copy(isSaving = true)
            }
        }
        if (!acquired) return

        viewModelScope.launch {
            val result = accountRepository.deleteAccount(accountId, deleteTransactions)
            controlState.update { current ->
                current.copy(
                    isSaving = false,
                    editorDialogState = null,
                    inspectedAccountId = if (current.inspectedAccountId == accountId) null else current.inspectedAccountId,
                    pendingDeletionAccountId = null,
                    pendingDeletionAccountName = null,
                    pendingDeletionTxCount = 0,
                    pendingDeletionLinkedCardsCount = 0,
                    snackbarMessage = if (result.isSuccess) "Account deleted successfully." else "Failed to delete account: ${result.exceptionOrNull()?.message}",
                )
            }
        }
    }

    fun cancelPendingDeletion() {
        controlState.update {
            it.copy(
                pendingDeletionAccountId = null,
                pendingDeletionAccountName = null,
                pendingDeletionTxCount = 0,
                pendingDeletionLinkedCardsCount = 0,
            )
        }
    }

    fun saveAccountFromDialog() {
        val editor = controlState.value.editorDialogState ?: return
        val trimmedName = editor.name.trim()
        if (trimmedName.isEmpty()) {
            setEditorValidationError("Account name is required.")
            return
        }

        val spec = SupportedCurrencies.fromCode(editor.currencyCode)
        val fractionDigits = spec.fractionDigits

        if (editor.groupType == AccountGroup.DEBIT_CARD && editor.parentAccountId == null) {
            setEditorValidationError("Please link this Debit Card to a Parent Bank Account.")
            return
        }

        val statementPayable = if (editor.groupType == AccountGroup.CREDIT_CARD &&
            !editor.isEditing && editor.statementPayableInput.isNotBlank()
        ) {
            val parsed = parseInputToMinorOrZero(
                rawInput = editor.statementPayableInput,
                fractionDigits = fractionDigits,
                allowBlankAsZero = true,
            )
            if (parsed == null || parsed.isNegative) {
                setEditorValidationError("Enter a valid non-negative Billed Statement Due amount.")
                return
            }
            parsed
        } else {
            null
        }

        val unbilledAmount = if (editor.groupType == AccountGroup.CREDIT_CARD &&
            !editor.isEditing && editor.unbilledAmountInput.isNotBlank()
        ) {
            val parsed = parseInputToMinorOrZero(
                rawInput = editor.unbilledAmountInput,
                fractionDigits = fractionDigits,
                allowBlankAsZero = true,
            )
            if (parsed == null || parsed.isNegative) {
                setEditorValidationError("Enter a valid non-negative Unbilled Spend amount.")
                return
            }
            parsed
        } else {
            null
        }

        val parsedOpeningBalance = if (!editor.isEditing) {
            val parsed = parseInputToMinorOrZero(
                rawInput = editor.openingBalanceInput,
                fractionDigits = fractionDigits,
                allowBlankAsZero = true,
            )
            if (parsed == null) {
                setEditorValidationError("Invalid balance amount.")
                return
            }
            parsed
        } else {
            MinorMoney.ZERO
        }

        val effectiveOpeningMagnitude = if (editor.groupType == AccountGroup.CREDIT_CARD) {
            if (editor.isEditing) {
                MinorMoney.ZERO
            } else {
                MinorMoney(
                    MoneyFormatterAndCalculator.safeAdd(
                        statementPayable?.amountMinor ?: 0L,
                        unbilledAmount?.amountMinor ?: 0L,
                    ),
                )
            }
        } else {
            parsedOpeningBalance
        }

        val signedBalance = when {
            editor.isEditing -> MinorMoney.ZERO

            // Preserved from existing entity in repository
            editor.groupType == AccountGroup.DEBIT_CARD -> MinorMoney.ZERO

            editor.groupType.isLiabilityByDefault && effectiveOpeningMagnitude.isPositive ->
                MinorMoney(MoneyFormatterAndCalculator.safeNegate(effectiveOpeningMagnitude.amountMinor))

            else -> effectiveOpeningMagnitude
        }

        val creditLimit = if (editor.groupType == AccountGroup.CREDIT_CARD) {
            val parsedLimit = parseInputToMinorOrZero(
                rawInput = editor.creditLimitInput,
                fractionDigits = fractionDigits,
                allowBlankAsZero = false,
            )
            if (parsedLimit == null || !parsedLimit.isPositive) {
                setEditorValidationError("Enter a valid positive Credit Limit for this Credit Card.")
                return
            }
            parsedLimit
        } else {
            null
        }

        var acquired = false
        controlState.update { current ->
            if (current.isSaving) {
                current
            } else {
                acquired = true
                current.copy(isSaving = true)
            }
        }
        if (!acquired) return

        val accountSummary = AccountSummary(
            id = editor.accountId,
            name = trimmedName,
            groupType = editor.groupType,
            currencyCode = spec.code,
            currentBalance = signedBalance,
            creditLimit = creditLimit,
            creditCardStatementPayable = statementPayable,
            creditCardUnbilledAmount = unbilledAmount,
            settlementDayOfMonth = if (editor.groupType == AccountGroup.CREDIT_CARD) {
                editor.settlementDayOfMonth.coerceIn(1, 31)
            } else {
                null
            },
            paymentDueDayOfMonth = if (editor.groupType == AccountGroup.CREDIT_CARD) {
                editor.paymentDueDayOfMonth.coerceIn(1, 31)
            } else {
                null
            },
            parentAccountId = if (editor.groupType == AccountGroup.DEBIT_CARD) {
                editor.parentAccountId
            } else {
                null
            },
            debitCardPeriodSpend = if (editor.groupType == AccountGroup.DEBIT_CARD) {
                MinorMoney.ZERO
            } else {
                null
            },
            includeInNetWorth = if (editor.groupType == AccountGroup.DEBIT_CARD) false else editor.includeInNetWorth,
            isPinned = editor.isPinned,
            isArchived = editor.isArchived,
            colorHex = editor.colorHex,
            iconName = editor.iconName,
        )

        viewModelScope.launch {
            val result = accountRepository.upsertAccount(
                account = accountSummary,
                openingBalance = signedBalance,
                openingDateEpochDay = editor.openingDateEpochDay,
            )
            result.fold(
                onSuccess = {
                    controlState.update {
                        it.copy(
                            isSaving = false,
                            editorDialogState = null,
                            snackbarMessage = if (editor.isEditing) {
                                "Updated account \"${accountSummary.name}\""
                            } else {
                                "Created account \"${accountSummary.name}\""
                            },
                        )
                    }
                },
                onFailure = { error ->
                    controlState.update { state ->
                        state.copy(
                            isSaving = false,
                            editorDialogState = state.editorDialogState?.copy(
                                validationError = error.message ?: "Failed to save account.",
                            ),
                        )
                    }
                },
            )
        }
    }

    fun setAccountArchived(accountId: Long, isArchived: Boolean) {
        var acquired = false
        controlState.update { current ->
            if (current.isSaving) {
                current
            } else {
                acquired = true
                current.copy(isSaving = true)
            }
        }
        if (!acquired) return

        viewModelScope.launch {
            val result = accountRepository.setAccountArchived(accountId, isArchived)
            result.fold(
                onSuccess = {
                    controlState.update {
                        it.copy(
                            isSaving = false,
                            editorDialogState = null,
                            snackbarMessage = if (isArchived) {
                                "Account archived"
                            } else {
                                "Account restored from archive"
                            },
                        )
                    }
                },
                onFailure = { err ->
                    controlState.update {
                        it.copy(
                            isSaving = false,
                            snackbarMessage = err.message ?: "Unable to update archive status",
                        )
                    }
                },
            )
        }
    }

    fun openSettlementSheetForAccountId(accountId: Long) {
        if (accountId <= 0L) return
        viewModelScope.launch {
            val account = uiState.value.allAccounts.firstOrNull { it.id == accountId }
                ?: withTimeoutOrNull(3_000L) {
                    allAccountsFlow.filter { it.isNotEmpty() }.first()
                }?.firstOrNull { it.id == accountId }
                ?: return@launch
            if (account.groupType != AccountGroup.CREDIT_CARD || account.isArchived) return@launch
            openSettlementSheet(account)
        }
    }

    fun openSettlementSheet(creditCard: AccountSummary) {
        if (creditCard.isArchived) {
            controlState.update {
                it.copy(snackbarMessage = "Unarchive this credit card before recording a settlement.")
            }
            return
        }
        val refEpochDay = controlState.value.referenceDateEpochDay
        val allAccounts = allAccountsFlow.value.ifEmpty { uiState.value.allAccounts }
        val eligibleSource = allAccounts.firstOrNull { acc ->
            !acc.isArchived &&
                acc.currencyCode.equals(creditCard.currencyCode, ignoreCase = true) &&
                (
                    acc.groupType == AccountGroup.BANK_ACCOUNT ||
                        acc.groupType == AccountGroup.CASH ||
                        acc.groupType == AccountGroup.SAVINGS ||
                        acc.groupType == AccountGroup.PREPAID_WALLET
                    )
        }
        val defaultSourceId = eligibleSource?.id
        val spec = SupportedCurrencies.fromCode(creditCard.currencyCode)

        val statementDue = creditCard.creditCardStatementPayable ?: MinorMoney.ZERO
        val totalDebt = if (creditCard.currentBalance.isNegative) creditCard.currentBalance.abs() else MinorMoney.ZERO
        val initialPreset = if (statementDue.isPositive) {
            SettlementAmountPreset.STATEMENT_PAYABLE
        } else {
            SettlementAmountPreset.FULL_OUTSTANDING
        }
        val initialMinor = if (statementDue.isPositive) statementDue else totalDebt

        controlState.update {
            it.copy(
                settlementSheetState = CreditCardSettlementSheetState(
                    creditCardAccount = creditCard,
                    selectedSourceAccountId = defaultSourceId,
                    preset = initialPreset,
                    amountInput = formatMinorPlainForInput(initialMinor, spec.fractionDigits),
                    ledgerDateEpochDay = refEpochDay,
                    note = "CC Bill Settlement - ${creditCard.name}",
                    validationError = null,
                ),
            )
        }
    }

    fun updateSettlementSheetState(
        transform: (CreditCardSettlementSheetState) -> CreditCardSettlementSheetState,
    ) {
        controlState.update { current ->
            val existing = current.settlementSheetState ?: return@update current
            current.copy(settlementSheetState = transform(existing).copy(validationError = null))
        }
    }

    fun selectSettlementPreset(preset: SettlementAmountPreset) {
        val sheet = controlState.value.settlementSheetState ?: return
        val card = sheet.creditCardAccount
        val spec = SupportedCurrencies.fromCode(card.currencyCode)
        val newAmountInput = when (preset) {
            SettlementAmountPreset.STATEMENT_PAYABLE -> {
                val payable = card.creditCardStatementPayable ?: MinorMoney.ZERO
                formatMinorPlainForInput(payable.abs(), spec.fractionDigits)
            }

            SettlementAmountPreset.FULL_OUTSTANDING -> {
                val debt = if (card.currentBalance.isNegative) card.currentBalance.abs() else MinorMoney.ZERO
                formatMinorPlainForInput(debt, spec.fractionDigits)
            }

            SettlementAmountPreset.CUSTOM -> sheet.amountInput
        }
        updateSettlementSheetState {
            it.copy(preset = preset, amountInput = newAmountInput)
        }
    }

    private fun setSettlementValidationError(message: String) {
        controlState.update { current ->
            current.copy(settlementSheetState = current.settlementSheetState?.copy(validationError = message))
        }
    }

    fun dismissSettlementSheet() {
        controlState.update { it.copy(settlementSheetState = null) }
    }

    fun submitCreditCardSettlement(onSuccess: (() -> Unit)? = null) {
        if (controlState.value.isSaving) return
        val sheet = controlState.value.settlementSheetState ?: return
        val sourceId = sheet.selectedSourceAccountId
        if (sourceId == null) {
            setSettlementValidationError("Select a source Bank or Cash account to pay from.")
            return
        }

        val allAccounts = allAccountsFlow.value.ifEmpty { uiState.value.allAccounts }
        val sourceAccount = allAccounts.firstOrNull { it.id == sourceId }
        if (sourceAccount != null && !sourceAccount.currencyCode.equals(sheet.creditCardAccount.currencyCode, ignoreCase = true)) {
            setSettlementValidationError(
                "Source account currency (${sourceAccount.currencyCode}) must match credit card currency (${sheet.creditCardAccount.currencyCode}).",
            )
            return
        }

        val spec = SupportedCurrencies.fromCode(sheet.creditCardAccount.currencyCode)
        val evaluatedResult = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor(
            expression = sheet.amountInput,
            fractionDigits = spec.fractionDigits,
        )

        val settlementMoney = evaluatedResult.getOrNull()
        if (settlementMoney == null || !settlementMoney.isPositive) {
            setSettlementValidationError("Enter a valid positive settlement amount.")
            return
        }

        var acquired = false
        controlState.update { current ->
            if (current.isSaving) {
                current
            } else {
                acquired = true
                current.copy(isSaving = true)
            }
        }
        if (!acquired) return

        viewModelScope.launch {
            val result = accountRepository.settleCreditCardStatement(
                sourceAssetAccountId = sourceId,
                creditCardAccountId = sheet.creditCardAccount.id,
                settlementAmount = settlementMoney,
                timestampEpochMillis = System.currentTimeMillis(),
                ledgerDateEpochDay = sheet.ledgerDateEpochDay,
                note = sheet.note.trim(),
            )
            result.fold(
                onSuccess = {
                    val prefs = userPreferencesFlow.first()
                    val displayPolicy = CurrencyDisplayPolicy(
                        primaryCurrencyCode = prefs.primaryCurrencyCode,
                        hidePrimaryCurrencySymbol = prefs.hidePrimaryCurrencySymbol,
                    )
                    val formatted = MoneyFormatterAndCalculator.formatMinorMoney(
                        money = settlementMoney,
                        currencySpec = spec,
                        grouping = prefs.numberGrouping,
                        includeSymbol = displayPolicy.shouldShowSymbol(spec),
                    )
                    controlState.update {
                        it.copy(
                            isSaving = false,
                            settlementSheetState = null,
                            snackbarMessage = "Settled $formatted towards ${sheet.creditCardAccount.name}",
                        )
                    }
                    onSuccess?.invoke()
                },
                onFailure = { error ->
                    controlState.update { state ->
                        state.copy(
                            isSaving = false,
                            settlementSheetState = state.settlementSheetState?.copy(
                                validationError = error.message ?: "Failed to record settlement.",
                            ),
                        )
                    }
                },
            )
        }
    }

    companion object {
        fun defaultIconForGroup(group: AccountGroup): String = when (group) {
            AccountGroup.CASH -> "account_balance_wallet"
            AccountGroup.BANK_ACCOUNT -> "account_balance"
            AccountGroup.DEBIT_CARD -> "credit_card"
            AccountGroup.CREDIT_CARD -> "credit_card"
            AccountGroup.SAVINGS -> "savings"
            AccountGroup.PREPAID_WALLET -> "account_balance_wallet"
            AccountGroup.INVESTMENT -> "trending_up"
            AccountGroup.OVERDRAFT -> "account_balance"
            AccountGroup.LOAN -> "trending_down"
            AccountGroup.OTHER -> "payments"
        }

        fun defaultColorForGroup(group: AccountGroup): String = when (group) {
            AccountGroup.CASH -> "#D97706"
            AccountGroup.BANK_ACCOUNT -> "#059669"
            AccountGroup.DEBIT_CARD -> "#0D9488"
            AccountGroup.CREDIT_CARD -> "#E11D48"
            AccountGroup.SAVINGS -> "#10B981"
            AccountGroup.PREPAID_WALLET -> "#4F46E5"
            AccountGroup.INVESTMENT -> "#2563EB"
            AccountGroup.OVERDRAFT -> "#EA580C"
            AccountGroup.LOAN -> "#BE123C"
            AccountGroup.OTHER -> "#475569"
        }
    }
}

internal fun parseInputToMinorOrZero(
    rawInput: String,
    fractionDigits: Int,
    allowBlankAsZero: Boolean = false,
): MinorMoney? {
    val cleaned = rawInput.trim()
    if (cleaned.isEmpty()) return if (allowBlankAsZero) MinorMoney.ZERO else null
    if (cleaned == "-" || cleaned == "+") return null

    val zeroCandidate = cleaned.removePrefix("-").removePrefix("+").trim().replace(',', '.')
    if (zeroCandidate.matches(Regex("^0+(\\.0+)?$"))) return MinorMoney.ZERO

    return MoneyFormatterAndCalculator.evaluateSignedExpressionToMinor(
        expression = cleaned,
        fractionDigits = fractionDigits,
    ).getOrNull()
}

internal fun formatMinorPlainForInput(money: MinorMoney, fractionDigits: Int): String {
    if (money.isZero) return "0"
    val isNegative = money.isNegative
    val formatted = MoneyFormatterAndCalculator.formatMinorMoney(
        money = money.abs(),
        currencySpec = CurrencySpec(
            code = "TMP",
            symbol = "",
            displayName = "",
            fractionDigits = fractionDigits,
            defaultGrouping = NumberGroupingSystem.INTERNATIONAL,
        ),
        includeSymbol = false,
    ).replace(",", "")
    val clean = if (formatted.contains('.')) {
        formatted.trimEnd('0').trimEnd('.')
    } else {
        formatted
    }
    return if (isNegative) "-$clean" else clean
}
