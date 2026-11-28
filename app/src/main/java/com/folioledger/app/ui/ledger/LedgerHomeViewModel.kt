/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/ui/ledger/LedgerHomeViewModel.kt
package com.folioledger.app.ui.ledger

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folioledger.app.R
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.BudgetProgress
import com.folioledger.app.domain.model.CategoryNode
import com.folioledger.app.domain.model.CategorySpendStat
import com.folioledger.app.domain.model.CategoryType
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.PeriodLedgerSummary
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionBookmark
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionSearchFilter
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.model.UNCATEGORIZED_CATEGORY_ID
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.domain.repository.AccountRepository
import com.folioledger.app.domain.repository.AnalyticsAndBudgetRepository
import com.folioledger.app.domain.repository.CategoryAndTagRepository
import com.folioledger.app.domain.repository.SettingsAndBackupRepository
import com.folioledger.app.domain.repository.TransactionRepository
import com.folioledger.app.domain.util.LedgerBucketingCalculator
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

// Backwards-compatible typealiases for domain models
typealias FinancialPeriodRange = com.folioledger.app.domain.model.FinancialPeriodRange
typealias DailyLedgerGroup = com.folioledger.app.domain.model.DailyLedgerGroup
typealias CalendarDayCell = com.folioledger.app.domain.model.CalendarDayCell
typealias WeeklyLedgerBucket = com.folioledger.app.domain.model.WeeklyLedgerBucket
typealias YearlyMonthOverviewItem = com.folioledger.app.domain.model.YearlyMonthOverviewItem
typealias AccountPeriodFlowSummary = com.folioledger.app.domain.model.AccountPeriodFlowSummary

object LedgerPeriodCalculator {
    fun resolveClampedDate(yearMonth: YearMonth, desiredDayOfMonth: Int): LocalDate = LedgerBucketingCalculator.resolveClampedDate(yearMonth, desiredDayOfMonth)

    fun computeFinancialMonthRange(
        referenceDateEpochDay: Long,
        monthStartDay: Int,
    ): FinancialPeriodRange = LedgerBucketingCalculator.computeFinancialMonthRange(referenceDateEpochDay, monthStartDay)

    fun stepFinancialMonth(
        currentReferenceEpochDay: Long,
        monthStartDay: Int,
        deltaMonths: Long,
    ): Long = LedgerBucketingCalculator.stepFinancialMonth(currentReferenceEpochDay, monthStartDay, deltaMonths)

    fun computeFinancialYearMonthRanges(
        anchorYear: Int,
        monthStartDay: Int,
    ): List<FinancialPeriodRange> = LedgerBucketingCalculator.computeFinancialYearMonthRanges(anchorYear, monthStartDay)
}

enum class LedgerSubTab(
    val title: String,
    @param:StringRes val titleRes: Int,
) {
    DAILY("Daily", R.string.subtab_daily),
    CALENDAR("Calendar", R.string.subtab_calendar),
    WEEKLY("Weekly", R.string.subtab_weekly),
    MONTHLY("Monthly", R.string.subtab_monthly),
    SUMMARY("Summary", R.string.subtab_summary),
}

@Immutable
data class LedgerHomeUiState(
    val selectedSubTab: LedgerSubTab = LedgerSubTab.DAILY,
    val referenceDateEpochDay: Long = 0L,
    val todayEpochDay: Long = 0L,
    val activePeriodRange: FinancialPeriodRange = FinancialPeriodRange(
        startEpochDay = 0L,
        endEpochDay = 0L,
        title = "",
        subtitle = "",
        anchorYear = 2026,
        anchorMonthValue = 1,
    ),
    val periodSummary: PeriodLedgerSummary = PeriodLedgerSummary(
        startEpochDay = 0L,
        endEpochDay = 0L,
        totalIncome = MinorMoney.ZERO,
        totalExpense = MinorMoney.ZERO,
        netCashflow = MinorMoney.ZERO,
        carryOverFromPreviousPeriod = MinorMoney.ZERO,
    ),
    val scopedAccountId: Long? = null,
    val scopedAccount: AccountSummary? = null,
    val userPreferences: UserPreferences = UserPreferences(),
    val currencySpec: CurrencySpec = SupportedCurrencies.INR,
    val amountFilterCurrencySpec: CurrencySpec = SupportedCurrencies.INR,
    val numberGrouping: NumberGroupingSystem = NumberGroupingSystem.INDIAN,
    val dailyGroups: List<DailyLedgerGroup> = emptyList(),
    val calendarWeekdayHeaders: List<String> = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
    val calendarCells: List<CalendarDayCell> = emptyList(),
    val selectedCalendarDayEpochDay: Long = 0L,
    val selectedCalendarDayTransactions: List<TransactionItem> = emptyList(),
    val selectedCalendarDayIncome: MinorMoney = MinorMoney.ZERO,
    val selectedCalendarDayExpense: MinorMoney = MinorMoney.ZERO,
    val selectedCalendarDayTransfer: MinorMoney = MinorMoney.ZERO,
    val weeklyBuckets: List<WeeklyLedgerBucket> = emptyList(),
    val expandedWeekIndices: Set<Int> = setOf(1),
    val yearlyMonthItems: List<YearlyMonthOverviewItem> = emptyList(),
    val expandedYearlyMonths: Set<Int> = emptySet(),
    val yearlyIncomeTotal: MinorMoney = MinorMoney.ZERO,
    val yearlyExpenseTotal: MinorMoney = MinorMoney.ZERO,
    val yearlyNetTotal: MinorMoney = MinorMoney.ZERO,
    val topExpenseCategories: List<CategorySpendStat> = emptyList(),
    val budgetProgressList: List<BudgetProgress> = emptyList(),
    val accountPeriodFlows: List<AccountPeriodFlowSummary> = emptyList(),
    val savingsRatePercent: Float = 0f,
    val searchFilter: TransactionSearchFilter = TransactionSearchFilter(),
    val filterAllPeriods: Boolean = false,
    val minAmountFilterInput: String = "",
    val maxAmountFilterInput: String = "",
    val isSearchBarVisible: Boolean = false,
    val isFilterSheetOpen: Boolean = false,
    val activeFilterCount: Int = 0,
    val isFilterActive: Boolean = false,
    val isFilteredRefundOnly: Boolean = false,
    val filteredTransactions: List<TransactionItem> = emptyList(),
    val filteredDailyGroups: List<DailyLedgerGroup> = emptyList(),
    val filteredIncomeTotal: MinorMoney = MinorMoney.ZERO,
    val filteredExpenseTotal: MinorMoney = MinorMoney.ZERO,
    val filteredNetTotal: MinorMoney = MinorMoney.ZERO,
    val filteredTransferTotal: MinorMoney = MinorMoney.ZERO,
    val availableAccounts: List<AccountSummary> = emptyList(),
    val allAccounts: List<AccountSummary> = emptyList(),
    val availableCategories: List<CategoryNode> = emptyList(),
    val expenseCategories: List<CategoryNode> = emptyList(),
    val incomeCategories: List<CategoryNode> = emptyList(),
    val expenseFilterCount: Int = 0,
    val incomeFilterCount: Int = 0,
    val accountFilterCount: Int = 0,
    val availableTags: List<String> = emptyList(),
    val bookmarks: List<TransactionBookmark> = emptyList(),
    val inspectedTransaction: TransactionItem? = null,
    val isExportingCsv: Boolean = false,
    val snackbarMessage: String? = null,
    val isInitialDataLoaded: Boolean = false,
    val scrollToTopSignal: Int = 0,
)

private data class LedgerControlState(
    val selectedSubTab: LedgerSubTab = LedgerSubTab.DAILY,
    val referenceDateEpochDay: Long = LocalDate.now().toEpochDay(),
    val selectedCalendarDayEpochDay: Long = LocalDate.now().toEpochDay(),
    val scopedAccountId: Long? = null,
    val expandedWeekIndices: Set<Int>? = null,
    val expandedYearlyMonths: Set<Int>? = null,
    val searchFilter: TransactionSearchFilter = TransactionSearchFilter(),
    val filterAllPeriods: Boolean = false,
    val minAmountFilterInput: String = "",
    val maxAmountFilterInput: String = "",
    val isSearchBarVisible: Boolean = false,
    val isFilterSheetOpen: Boolean = false,
    val inspectedTransaction: TransactionItem? = null,
    val isExportingCsv: Boolean = false,
    val snackbarMessage: String? = null,
    val scrollToTopSignal: Int = 0,
)

private data class PeriodDrivenBundle(
    val periodRange: FinancialPeriodRange,
    val periodSummary: PeriodLedgerSummary,
    val periodTransactions: List<TransactionItem>,
    val yearRanges: List<FinancialPeriodRange>,
    val yearTransactions: List<TransactionItem>,
    val categoryStats: List<CategorySpendStat>,
    val budgets: List<BudgetProgress>,
)

private data class FilterExecutionInputs(
    val refEpochDay: Long,
    val scopedAccountId: Long?,
    val nonQueryFilter: TransactionSearchFilter,
    val minAmountInput: String,
    val maxAmountInput: String,
    val filterAllPeriods: Boolean,
)

private data class StaticReferencesBundle(
    val accounts: List<AccountSummary>,
    val categories: List<CategoryNode>,
    val tags: List<String>,
    val bookmarks: List<TransactionBookmark>,
)

private data class MonthOverviewBase(
    val monthIndex: Int,
    val periodRange: FinancialPeriodRange,
    val isCurrentActiveMonth: Boolean,
    val incomeTotal: MinorMoney,
    val expenseTotal: MinorMoney,
    val netTotal: MinorMoney,
    val transactionCount: Int,
    val precomputedWeeklyBuckets: List<WeeklyLedgerBucket>,
)

private data class ComputedFilteredData(
    val scopedFilteredTransactions: List<TransactionItem>,
    val filteredDailyGroups: List<DailyLedgerGroup>,
    val filteredInc: MinorMoney,
    val filteredExp: MinorMoney,
    val filteredTransferTotal: MinorMoney,
    val isRefundOnly: Boolean = false,
)

private data class BaseComputedLedgerData(
    val refs: StaticReferencesBundle,
    val prefs: UserPreferences,
    val periodRange: FinancialPeriodRange,
    val yearRanges: List<FinancialPeriodRange>,
    val topExpenseCategories: List<CategorySpendStat>,
    val budgets: List<BudgetProgress>,
    val scopedAccount: AccountSummary?,
    val activeScopedAccountId: Long?,
    val currencySpec: CurrencySpec,
    val activePeriodTransactions: List<TransactionItem>,
    val activeYearTransactions: List<TransactionItem>,
    val resolvedPeriodSummary: PeriodLedgerSummary,
    val dailyGroups: List<DailyLedgerGroup>,
    val weekdayHeaders: List<String>,
    val calendarCells: List<CalendarDayCell>,
    val weeklyBuckets: List<WeeklyLedgerBucket>,
    val monthOverviewBases: List<MonthOverviewBase>,
    val yearlyIncome: MinorMoney,
    val yearlyExpense: MinorMoney,
    val yearlyNet: MinorMoney,
    val accountFlows: List<AccountPeriodFlowSummary>,
    val savingsRate: Float,
    val expenseCats: List<CategoryNode>,
    val incomeCats: List<CategoryNode>,
    val expenseCategoryIds: Set<Long>,
    val incomeCategoryIds: Set<Long>,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LedgerHomeViewModel(
    private val transactionRepository: TransactionRepository,
    private val analyticsAndBudgetRepository: AnalyticsAndBudgetRepository,
    private val accountRepository: AccountRepository,
    private val categoryAndTagRepository: CategoryAndTagRepository,
    private val settingsAndBackupRepository: SettingsAndBackupRepository,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val initialTodayEpochDay: Long = LocalDate.now().toEpochDay()
    private val controlState = MutableStateFlow(
        LedgerControlState(
            referenceDateEpochDay = initialTodayEpochDay,
            selectedCalendarDayEpochDay = initialTodayEpochDay,
        ),
    )
    private var lastObservedTodayEpochDay: Long = initialTodayEpochDay
    private val todayEpochDayFlow = MutableStateFlow(initialTodayEpochDay)
    private val isDeletingTransactionLock = AtomicBoolean(false)
    private val isExportingCsvLock = AtomicBoolean(false)

    private val userPreferencesFlow = settingsAndBackupRepository.observeUserPreferences()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = UserPreferences(),
        )

    private val periodPreferencesFlow = userPreferencesFlow
        .distinctUntilChanged { old, new ->
            old.monthStartDay == new.monthStartDay &&
                old.carryOverEnabled == new.carryOverEnabled
        }

    private val periodDrivenFlow: Flow<PeriodDrivenBundle> = combine(
        controlState.map { it.referenceDateEpochDay }.distinctUntilChanged(),
        periodPreferencesFlow,
    ) { refEpochDay, prefs ->
        refEpochDay to prefs
    }.flatMapLatest { (refEpochDay, prefs) ->
        val periodRange = LedgerPeriodCalculator.computeFinancialMonthRange(
            referenceDateEpochDay = refEpochDay,
            monthStartDay = prefs.monthStartDay,
        )
        val yearRanges = LedgerPeriodCalculator.computeFinancialYearMonthRanges(
            anchorYear = periodRange.anchorYear,
            monthStartDay = prefs.monthStartDay,
        )
        val yearStart = yearRanges.first().startEpochDay
        val yearEnd = yearRanges.last().endEpochDay

        val summaryAndTxFlow = combine(
            analyticsAndBudgetRepository.observePeriodLedgerSummary(
                startEpochDay = periodRange.startEpochDay,
                endEpochDay = periodRange.endEpochDay,
                includeCarryOver = prefs.carryOverEnabled,
            ),
            transactionRepository.observeTransactionsInRange(
                startEpochDay = periodRange.startEpochDay,
                endEpochDay = periodRange.endEpochDay,
            ),
            transactionRepository.observeTransactionsInRange(
                startEpochDay = yearStart,
                endEpochDay = yearEnd,
            ),
        ) { summary, periodTxns, yearTxns ->
            Triple(summary, periodTxns, yearTxns)
        }

        val statsAndBudgetsFlow = combine(
            analyticsAndBudgetRepository.observeCategorySpendStats(
                startEpochDay = periodRange.startEpochDay,
                endEpochDay = periodRange.endEpochDay,
                type = CategoryType.EXPENSE,
            ),
            todayEpochDayFlow.flatMapLatest { todayEpochDay ->
                val budgetRefDay = if (todayEpochDay in periodRange.startEpochDay..periodRange.endEpochDay) {
                    todayEpochDay
                } else {
                    periodRange.endEpochDay
                }
                analyticsAndBudgetRepository.observeBudgetProgressList(
                    startEpochDay = periodRange.startEpochDay,
                    endEpochDay = periodRange.endEpochDay,
                    referenceDateEpochDay = budgetRefDay,
                    todayEpochDay = todayEpochDay,
                )
            },
        ) { stats, budgets ->
            stats to budgets
        }

        combine(summaryAndTxFlow, statsAndBudgetsFlow) { (summary, periodTxns, yearTxns), (stats, budgets) ->
            PeriodDrivenBundle(
                periodRange = periodRange,
                periodSummary = summary,
                periodTransactions = periodTxns,
                yearRanges = yearRanges,
                yearTransactions = yearTxns,
                categoryStats = stats,
                budgets = budgets,
            )
        }
    }.flowOn(computationDispatcher)

    private val debouncedQueryFlow: Flow<String> = controlState
        .map { it.searchFilter.queryText }
        .distinctUntilChanged()
        .debounce { query ->
            if (query.isBlank()) 0L else 150L
        }

    private val scopedAccountIdFlow = controlState.map { it.scopedAccountId }.distinctUntilChanged()

    private val allAccountsForRefDateFlow: Flow<List<AccountSummary>> = controlState
        .map { it.referenceDateEpochDay }
        .distinctUntilChanged()
        .flatMapLatest { refEpochDay ->
            accountRepository.observeAllAccounts(refEpochDay)
        }
        .shareIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            replay = 1,
        )

    private val staticReferencesFlow: Flow<StaticReferencesBundle> = combine(
        allAccountsForRefDateFlow,
        categoryAndTagRepository.observeAllCategoryTrees(),
        categoryAndTagRepository.observeAllTags(),
        categoryAndTagRepository.observeBookmarks(),
    ) { accounts, categories, tags, bookmarks ->
        StaticReferencesBundle(accounts, categories, tags, bookmarks)
    }.flowOn(computationDispatcher)

    private val resolvedAmountFilterCurrencyFlow: Flow<String?> = combine(
        scopedAccountIdFlow,
        controlState.map { it.searchFilter.accountIds }.distinctUntilChanged(),
        allAccountsForRefDateFlow,
    ) { scopedId, filterAccountIds, accounts ->
        if (scopedId != null) {
            accounts.firstOrNull { it.id == scopedId }?.currencyCode
        } else if (filterAccountIds.isNotEmpty()) {
            accounts.filter { it.id in filterAccountIds }
                .map { it.currencyCode.trim().uppercase(Locale.ROOT) }
                .distinct()
                .singleOrNull()
        } else {
            null
        }
    }.distinctUntilChanged()

    private val filterInputsFlow: Flow<FilterExecutionInputs> = controlState
        .map { ctrl ->
            FilterExecutionInputs(
                refEpochDay = ctrl.referenceDateEpochDay,
                scopedAccountId = ctrl.scopedAccountId,
                nonQueryFilter = ctrl.searchFilter.copy(queryText = ""),
                minAmountInput = ctrl.minAmountFilterInput,
                maxAmountInput = ctrl.maxAmountFilterInput,
                filterAllPeriods = ctrl.filterAllPeriods,
            )
        }
        .distinctUntilChanged()

    private val filterCurrencyPrefsFlow = userPreferencesFlow
        .map { prefs -> Triple(prefs.monthStartDay, prefs.primaryCurrencyCode, prefs.secondaryCurrencies) }
        .distinctUntilChanged()

    private val filteredTransactionsFlow: Flow<List<TransactionItem>> = combine(
        filterInputsFlow,
        debouncedQueryFlow,
        resolvedAmountFilterCurrencyFlow,
        filterCurrencyPrefsFlow,
    ) { inputs, debouncedQuery, amountFilterCurrencyCode, (monthStartDay, primaryCurrencyCode, secondaryCurrencies) ->
        val periodRange = LedgerPeriodCalculator.computeFinancialMonthRange(
            referenceDateEpochDay = inputs.refEpochDay,
            monthStartDay = monthStartDay,
        )
        val targetCurrency = amountFilterCurrencyCode ?: primaryCurrencyCode
        val currencySpec = SupportedCurrencies.fromCode(targetCurrency)
        val resolvedSearchFilter = resolveSearchFilterWithAmountBounds(
            baseFilter = inputs.nonQueryFilter.copy(queryText = debouncedQuery),
            minInput = inputs.minAmountInput,
            maxInput = inputs.maxAmountInput,
            fractionDigits = currencySpec.fractionDigits,
        )

        if (!isUserFilterNonEmpty(resolvedSearchFilter)) {
            null
        } else {
            val rateMap = secondaryCurrencies.associate {
                it.code.trim().uppercase(Locale.ROOT) to it.exchangeRateToPrimary
            }
            val effectiveAccountIds = if (inputs.scopedAccountId != null && resolvedSearchFilter.accountIds.isEmpty()) {
                setOf(inputs.scopedAccountId)
            } else {
                resolvedSearchFilter.accountIds
            }
            resolvedSearchFilter.copy(
                accountIds = effectiveAccountIds,
                startEpochDay = if (inputs.filterAllPeriods) null else periodRange.startEpochDay,
                endEpochDay = if (inputs.filterAllPeriods) null else periodRange.endEpochDay,
                targetCurrencyCode = targetCurrency,
                primaryCurrencyCode = primaryCurrencyCode,
                secondaryExchangeRates = rateMap,
            )
        }
    }.distinctUntilChanged()
        .flatMapLatest { effectiveFilter ->
            if (effectiveFilter == null) {
                flowOf(emptyList())
            } else {
                transactionRepository.searchAndFilterTransactions(effectiveFilter)
            }
        }

    private val baseComputedDataFlow: Flow<BaseComputedLedgerData> = combine(
        periodDrivenFlow,
        staticReferencesFlow,
        scopedAccountIdFlow,
        userPreferencesFlow,
        todayEpochDayFlow,
    ) { periodBundle, refs, scopedAccountId, prefs, todayEpochDay ->
        val periodRange = periodBundle.periodRange

        val scopedAccount = scopedAccountId?.let { targetId ->
            refs.accounts.firstOrNull { it.id == targetId }
        }
        val activeScopedAccountId = scopedAccount?.id

        val currencySpec = SupportedCurrencies.fromCode(
            scopedAccount?.currencyCode ?: prefs.primaryCurrencyCode,
        )

        val activePeriodTransactions = if (activeScopedAccountId != null) {
            periodBundle.periodTransactions.filter { tx ->
                tx.accountId == activeScopedAccountId || tx.transferToAccountId == activeScopedAccountId
            }
        } else {
            periodBundle.periodTransactions
        }

        val activeYearTransactions = if (activeScopedAccountId != null) {
            periodBundle.yearTransactions.filter { tx ->
                tx.accountId == activeScopedAccountId || tx.transferToAccountId == activeScopedAccountId
            }
        } else {
            periodBundle.yearTransactions
        }

        val resolvedPeriodSummary = if (activeScopedAccountId != null) {
            val (scopedInc, scopedExp) = LedgerBucketingCalculator.computeIncomeAndExpenseTotals(
                transactions = activePeriodTransactions,
                scopedAccountId = activeScopedAccountId,
                primaryCurrencySpec = currencySpec,
                secondaryCurrencies = prefs.secondaryCurrencies,
            )
            PeriodLedgerSummary(
                startEpochDay = periodRange.startEpochDay,
                endEpochDay = periodRange.endEpochDay,
                totalIncome = scopedInc,
                totalExpense = scopedExp,
                netCashflow = scopedInc - scopedExp,
                carryOverFromPreviousPeriod = MinorMoney.ZERO,
            )
        } else {
            val (allInc, allExp) = LedgerBucketingCalculator.computeIncomeAndExpenseTotals(
                transactions = activePeriodTransactions,
                scopedAccountId = null,
                primaryCurrencySpec = currencySpec,
                secondaryCurrencies = prefs.secondaryCurrencies,
            )
            PeriodLedgerSummary(
                startEpochDay = periodRange.startEpochDay,
                endEpochDay = periodRange.endEpochDay,
                totalIncome = allInc,
                totalExpense = allExp,
                netCashflow = allInc - allExp,
                carryOverFromPreviousPeriod = periodBundle.periodSummary.carryOverFromPreviousPeriod,
            )
        }

        val dailyGroups = LedgerBucketingCalculator.buildDailyGroups(
            transactions = activePeriodTransactions,
            todayEpochDay = todayEpochDay,
            scopedAccountId = activeScopedAccountId,
            primaryCurrencySpec = currencySpec,
            secondaryCurrencies = prefs.secondaryCurrencies,
        )

        val weekdayHeaders = LedgerBucketingCalculator.buildWeekdayHeaders(prefs.weekStartDayIso)
        val calendarCells = LedgerBucketingCalculator.buildCalendarGridCells(
            periodRange = periodRange,
            weekStartDayIso = prefs.weekStartDayIso,
            transactions = activePeriodTransactions,
            todayEpochDay = todayEpochDay,
            scopedAccountId = activeScopedAccountId,
            primaryCurrencySpec = currencySpec,
            secondaryCurrencies = prefs.secondaryCurrencies,
            numberGrouping = prefs.numberGrouping,
        )

        val weeklyBuckets = LedgerBucketingCalculator.buildWeeklyBucketsForPeriod(
            periodRange = periodRange,
            weekStartDayIso = prefs.weekStartDayIso,
            transactions = activePeriodTransactions,
            scopedAccountId = activeScopedAccountId,
            primaryCurrencySpec = currencySpec,
            secondaryCurrencies = prefs.secondaryCurrencies,
        )

        val monthOverviewBases = periodBundle.yearRanges.mapIndexed { idx, monthRange ->
            val monthTxns = activeYearTransactions.filter {
                it.ledgerDateEpochDay in monthRange.startEpochDay..monthRange.endEpochDay
            }
            val (mIncome, mExpense) = LedgerBucketingCalculator.computeIncomeAndExpenseTotals(
                transactions = monthTxns,
                scopedAccountId = activeScopedAccountId,
                primaryCurrencySpec = currencySpec,
                secondaryCurrencies = prefs.secondaryCurrencies,
            )
            val precomputedWeeks = LedgerBucketingCalculator.buildWeeklyBucketsForPeriod(
                periodRange = monthRange,
                weekStartDayIso = prefs.weekStartDayIso,
                transactions = monthTxns,
                scopedAccountId = activeScopedAccountId,
                primaryCurrencySpec = currencySpec,
                secondaryCurrencies = prefs.secondaryCurrencies,
            )
            MonthOverviewBase(
                monthIndex = idx + 1,
                periodRange = monthRange,
                isCurrentActiveMonth = todayEpochDay in monthRange.startEpochDay..monthRange.endEpochDay,
                incomeTotal = mIncome,
                expenseTotal = mExpense,
                netTotal = mIncome - mExpense,
                transactionCount = monthTxns.size,
                precomputedWeeklyBuckets = precomputedWeeks,
            )
        }

        val yearlyIncome = MinorMoney(monthOverviewBases.fold(0L) { acc, m -> MoneyFormatterAndCalculator.safeAdd(acc, m.incomeTotal.amountMinor) })
        val yearlyExpense = MinorMoney(monthOverviewBases.fold(0L) { acc, m -> MoneyFormatterAndCalculator.safeAdd(acc, m.expenseTotal.amountMinor) })
        val yearlyNet = yearlyIncome - yearlyExpense

        val accountFlows = LedgerBucketingCalculator.buildAccountPeriodFlows(
            transactions = activePeriodTransactions,
            accounts = refs.accounts,
            scopedAccountId = activeScopedAccountId,
        )
        val topExpenseCategories = if (activeScopedAccountId != null) {
            LedgerBucketingCalculator.buildScopedCategorySpendStats(
                transactions = activePeriodTransactions,
                categories = refs.categories,
                maxCategories = 6,
                scopedAccountId = activeScopedAccountId,
            )
        } else {
            periodBundle.categoryStats.take(6)
        }
        val savingsRate = if (resolvedPeriodSummary.totalIncome.isPositive) {
            MoneyFormatterAndCalculator.calculatePercentage(
                resolvedPeriodSummary.netCashflow.amountMinor,
                resolvedPeriodSummary.totalIncome.amountMinor,
            ).coerceIn(-100f, 100f)
        } else {
            0f
        }

        val expenseCats = refs.categories.filter { it.type == CategoryType.EXPENSE }
        val incomeCats = refs.categories.filter { it.type == CategoryType.INCOME }

        val expenseCategoryIds = buildSet {
            expenseCats.forEach { parent ->
                add(parent.id)
                parent.subcategories.forEach { add(it.id) }
            }
        }
        val incomeCategoryIds = buildSet {
            incomeCats.forEach { parent ->
                add(parent.id)
                parent.subcategories.forEach { add(it.id) }
            }
        }

        BaseComputedLedgerData(
            refs = refs,
            prefs = prefs,
            periodRange = periodRange,
            yearRanges = periodBundle.yearRanges,
            topExpenseCategories = topExpenseCategories,
            budgets = periodBundle.budgets,
            scopedAccount = scopedAccount,
            activeScopedAccountId = activeScopedAccountId,
            currencySpec = currencySpec,
            activePeriodTransactions = activePeriodTransactions,
            activeYearTransactions = activeYearTransactions,
            resolvedPeriodSummary = resolvedPeriodSummary,
            dailyGroups = dailyGroups,
            weekdayHeaders = weekdayHeaders,
            calendarCells = calendarCells,
            weeklyBuckets = weeklyBuckets,
            monthOverviewBases = monthOverviewBases,
            yearlyIncome = yearlyIncome,
            yearlyExpense = yearlyExpense,
            yearlyNet = yearlyNet,
            accountFlows = accountFlows,
            savingsRate = savingsRate,
            expenseCats = expenseCats,
            incomeCats = incomeCats,
            expenseCategoryIds = expenseCategoryIds,
            incomeCategoryIds = incomeCategoryIds,
        )
    }.flowOn(computationDispatcher)

    private val computedFilteredFlow: Flow<ComputedFilteredData> = combine(
        filteredTransactionsFlow,
        staticReferencesFlow,
        userPreferencesFlow,
        scopedAccountIdFlow,
        todayEpochDayFlow,
    ) { filteredTxns, refs, prefs, scopedAccountId, todayEpochDay ->
        val scopedAccount = scopedAccountId?.let { targetId ->
            refs.accounts.firstOrNull { it.id == targetId }
        }
        val activeScopedAccountId = scopedAccount?.id
        val currencySpec = SupportedCurrencies.fromCode(
            scopedAccount?.currencyCode ?: prefs.primaryCurrencyCode,
        )
        val scopedFilteredTransactions = if (activeScopedAccountId != null) {
            filteredTxns.filter { tx ->
                tx.accountId == activeScopedAccountId || tx.transferToAccountId == activeScopedAccountId
            }
        } else {
            filteredTxns
        }
        val filteredDailyGroups = LedgerBucketingCalculator.buildDailyGroups(
            transactions = scopedFilteredTransactions,
            todayEpochDay = todayEpochDay,
            scopedAccountId = activeScopedAccountId,
            primaryCurrencySpec = currencySpec,
            secondaryCurrencies = prefs.secondaryCurrencies,
        )
        val (filteredInc, filteredExp) = LedgerBucketingCalculator.computeIncomeAndExpenseTotals(
            transactions = scopedFilteredTransactions,
            scopedAccountId = activeScopedAccountId,
            primaryCurrencySpec = currencySpec,
            secondaryCurrencies = prefs.secondaryCurrencies,
        )
        val filteredTransferTotal = LedgerBucketingCalculator.computeTransferTotal(
            transactions = scopedFilteredTransactions,
            primaryCurrencySpec = currencySpec,
            secondaryCurrencies = prefs.secondaryCurrencies,
        )
        val isRefundOnly = scopedFilteredTransactions.isNotEmpty() &&
            scopedFilteredTransactions.all { it.type == TransactionType.REFUND }
        ComputedFilteredData(
            scopedFilteredTransactions = scopedFilteredTransactions,
            filteredDailyGroups = filteredDailyGroups,
            filteredInc = filteredInc,
            filteredExp = filteredExp,
            filteredTransferTotal = filteredTransferTotal,
            isRefundOnly = isRefundOnly,
        )
    }.flowOn(computationDispatcher)

    val uiState: StateFlow<LedgerHomeUiState> = combine(
        controlState,
        baseComputedDataFlow,
        computedFilteredFlow,
        todayEpochDayFlow,
    ) { ctrl, base, filtered, todayEpochDay ->
        val refs = base.refs
        val prefs = base.prefs
        val periodRange = base.periodRange
        val activeScopedAccountId = base.activeScopedAccountId

        val effectiveSelectedCalendarDay = if (
            ctrl.selectedCalendarDayEpochDay in periodRange.startEpochDay..periodRange.endEpochDay
        ) {
            ctrl.selectedCalendarDayEpochDay
        } else {
            todayEpochDay.coerceIn(periodRange.startEpochDay, periodRange.endEpochDay)
        }

        val selectedDayTxns = base.activePeriodTransactions
            .filter { it.ledgerDateEpochDay == effectiveSelectedCalendarDay }
            .sortedByDescending { it.timestampEpochMillis }

        val (selectedDayIncome, selectedDayExpense) = LedgerBucketingCalculator.computeIncomeAndExpenseTotals(
            transactions = selectedDayTxns,
            scopedAccountId = activeScopedAccountId,
            primaryCurrencySpec = base.currencySpec,
            secondaryCurrencies = prefs.secondaryCurrencies,
        )

        val selectedDayTransfer = LedgerBucketingCalculator.computeTransferTotal(
            transactions = selectedDayTxns,
            primaryCurrencySpec = base.currencySpec,
            secondaryCurrencies = prefs.secondaryCurrencies,
        )

        val isCurrentPeriod = todayEpochDay in periodRange.startEpochDay..periodRange.endEpochDay
        val defaultExpandedWeeks = if (isCurrentPeriod) {
            val activeWeek = base.weeklyBuckets.firstOrNull { bucket ->
                todayEpochDay in bucket.startEpochDay..bucket.endEpochDay
            }?.weekIndex ?: base.weeklyBuckets.lastOrNull()?.weekIndex ?: 1
            setOf(activeWeek)
        } else {
            emptySet()
        }
        val effectiveExpandedWeeks = ctrl.expandedWeekIndices ?: defaultExpandedWeeks

        val yearStartDay = base.yearRanges.firstOrNull()?.startEpochDay ?: 0L
        val yearEndDay = base.yearRanges.lastOrNull()?.endEpochDay ?: Long.MAX_VALUE
        val isCurrentYear = todayEpochDay in yearStartDay..yearEndDay
        val defaultExpandedMonths = if (isCurrentYear) {
            val activeMonthIndex = base.yearRanges.indexOfFirst {
                todayEpochDay in it.startEpochDay..it.endEpochDay
            }.let { if (it >= 0) it + 1 else periodRange.anchorMonthValue }
            setOf(activeMonthIndex)
        } else {
            emptySet()
        }
        val effectiveExpandedMonths = ctrl.expandedYearlyMonths ?: defaultExpandedMonths

        val yearlyMonthItems = base.monthOverviewBases.map { mBase ->
            YearlyMonthOverviewItem(
                monthIndex = mBase.monthIndex,
                periodRange = mBase.periodRange,
                isCurrentActiveMonth = mBase.isCurrentActiveMonth,
                incomeTotal = mBase.incomeTotal,
                expenseTotal = mBase.expenseTotal,
                netTotal = mBase.netTotal,
                transactionCount = mBase.transactionCount,
                weeklyBuckets = if (mBase.monthIndex in effectiveExpandedMonths) mBase.precomputedWeeklyBuckets else emptyList(),
            )
        }

        val selectedAccountCurrency = if (activeScopedAccountId == null && ctrl.searchFilter.accountIds.isNotEmpty()) {
            refs.accounts.filter { it.id in ctrl.searchFilter.accountIds }
                .map { it.currencyCode.trim().uppercase(Locale.ROOT) }
                .distinct()
                .singleOrNull()
        } else {
            null
        }
        val amountFilterCurrencySpec = if (activeScopedAccountId != null) {
            base.currencySpec
        } else {
            SupportedCurrencies.fromCode(selectedAccountCurrency ?: prefs.primaryCurrencyCode)
        }

        val activeSearchFilter = resolveSearchFilterWithAmountBounds(
            baseFilter = ctrl.searchFilter,
            minInput = ctrl.minAmountFilterInput,
            maxInput = ctrl.maxAmountFilterInput,
            fractionDigits = amountFilterCurrencySpec.fractionDigits,
        )
        val filterCount = countActiveUserFilters(activeSearchFilter)
        val filterActive = isUserFilterNonEmpty(activeSearchFilter)

        val uncategorizedGoesToIncome = TransactionType.INCOME in activeSearchFilter.types &&
            TransactionType.EXPENSE !in activeSearchFilter.types &&
            TransactionType.REFUND !in activeSearchFilter.types

        var expFilterCount = 0
        if (TransactionType.EXPENSE in activeSearchFilter.types) expFilterCount++
        if (TransactionType.REFUND in activeSearchFilter.types) expFilterCount++
        expFilterCount += activeSearchFilter.categoryIds.count { it in base.expenseCategoryIds }
        if (UNCATEGORIZED_CATEGORY_ID in activeSearchFilter.categoryIds && !uncategorizedGoesToIncome) expFilterCount++
        expFilterCount += activeSearchFilter.tags.size

        var incFilterCount = 0
        if (TransactionType.INCOME in activeSearchFilter.types) incFilterCount++
        incFilterCount += activeSearchFilter.categoryIds.count { it in base.incomeCategoryIds }
        if (UNCATEGORIZED_CATEGORY_ID in activeSearchFilter.categoryIds && uncategorizedGoesToIncome) incFilterCount++

        var accFilterCount = 0
        accFilterCount += activeSearchFilter.accountIds.size
        if (TransactionType.TRANSFER in activeSearchFilter.types || TransactionType.CC_SETTLEMENT in activeSearchFilter.types) accFilterCount++
        if (activeSearchFilter.minAmount != null || activeSearchFilter.maxAmount != null) accFilterCount++

        val filteredDailyGroups = if (filterActive) {
            filtered.filteredDailyGroups
        } else {
            emptyList()
        }

        val updatedInspected = ctrl.inspectedTransaction?.let { current ->
            if (filterActive) {
                filtered.scopedFilteredTransactions.firstOrNull { it.id == current.id }
                    ?: base.activePeriodTransactions.firstOrNull { it.id == current.id }
            } else {
                base.activePeriodTransactions.firstOrNull { it.id == current.id }
            }
        }

        val uiAvailableAccounts = refs.accounts.filter { account ->
            !account.isArchived || account.id in activeSearchFilter.accountIds
        }

        LedgerHomeUiState(
            isInitialDataLoaded = true,
            selectedSubTab = ctrl.selectedSubTab,
            referenceDateEpochDay = ctrl.referenceDateEpochDay,
            todayEpochDay = todayEpochDay,
            activePeriodRange = periodRange,
            periodSummary = base.resolvedPeriodSummary,
            scopedAccountId = activeScopedAccountId,
            scopedAccount = base.scopedAccount,
            userPreferences = prefs,
            currencySpec = base.currencySpec,
            amountFilterCurrencySpec = amountFilterCurrencySpec,
            numberGrouping = prefs.numberGrouping,
            dailyGroups = base.dailyGroups,
            calendarWeekdayHeaders = base.weekdayHeaders,
            calendarCells = base.calendarCells,
            selectedCalendarDayEpochDay = effectiveSelectedCalendarDay,
            selectedCalendarDayTransactions = selectedDayTxns,
            selectedCalendarDayIncome = selectedDayIncome,
            selectedCalendarDayExpense = selectedDayExpense,
            selectedCalendarDayTransfer = selectedDayTransfer,
            weeklyBuckets = base.weeklyBuckets,
            expandedWeekIndices = effectiveExpandedWeeks,
            yearlyMonthItems = yearlyMonthItems,
            expandedYearlyMonths = effectiveExpandedMonths,
            yearlyIncomeTotal = base.yearlyIncome,
            yearlyExpenseTotal = base.yearlyExpense,
            yearlyNetTotal = base.yearlyNet,
            topExpenseCategories = base.topExpenseCategories,
            budgetProgressList = base.budgets,
            accountPeriodFlows = base.accountFlows,
            savingsRatePercent = base.savingsRate,
            searchFilter = activeSearchFilter,
            filterAllPeriods = ctrl.filterAllPeriods,
            minAmountFilterInput = ctrl.minAmountFilterInput,
            maxAmountFilterInput = ctrl.maxAmountFilterInput,
            isSearchBarVisible = ctrl.isSearchBarVisible,
            isFilterSheetOpen = ctrl.isFilterSheetOpen,
            activeFilterCount = filterCount,
            isFilterActive = filterActive,
            isFilteredRefundOnly = filterActive && filtered.isRefundOnly,
            filteredTransactions = filtered.scopedFilteredTransactions,
            filteredDailyGroups = filteredDailyGroups,
            filteredIncomeTotal = filtered.filteredInc,
            filteredExpenseTotal = filtered.filteredExp,
            filteredNetTotal = filtered.filteredInc - filtered.filteredExp,
            filteredTransferTotal = filtered.filteredTransferTotal,
            availableAccounts = uiAvailableAccounts,
            allAccounts = refs.accounts,
            availableCategories = refs.categories,
            expenseCategories = base.expenseCats,
            incomeCategories = base.incomeCats,
            expenseFilterCount = expFilterCount,
            incomeFilterCount = incFilterCount,
            accountFilterCount = accFilterCount,
            availableTags = refs.tags,
            bookmarks = refs.bookmarks,
            inspectedTransaction = updatedInspected,
            isExportingCsv = ctrl.isExportingCsv,
            snackbarMessage = ctrl.snackbarMessage,
            scrollToTopSignal = ctrl.scrollToTopSignal,
        )
    }.flowOn(computationDispatcher)
        .catch { throwable ->
            if (throwable is kotlinx.coroutines.CancellationException) throw throwable
            emit(
                LedgerHomeUiState(
                    isInitialDataLoaded = true,
                    todayEpochDay = todayEpochDayFlow.value,
                    snackbarMessage = "Ledger calculation error: ${throwable.localizedMessage ?: "Overflow"}",
                ),
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = LedgerHomeUiState(
                referenceDateEpochDay = initialTodayEpochDay,
                todayEpochDay = initialTodayEpochDay,
                selectedCalendarDayEpochDay = initialTodayEpochDay,
            ),
        )

    fun setScopedAccount(accountId: Long?) {
        controlState.update { current ->
            val cleanedFilter = if (
                accountId != null &&
                current.searchFilter.accountIds.any { it != accountId }
            ) {
                current.searchFilter.copy(accountIds = emptySet())
            } else {
                current.searchFilter
            }
            current.copy(
                scopedAccountId = accountId,
                searchFilter = cleanedFilter,
                inspectedTransaction = null,
            )
        }
    }

    fun selectSubTab(subTab: LedgerSubTab) {
        controlState.update { it.copy(selectedSubTab = subTab) }
    }

    fun navigateToPreviousPeriod() = stepPeriod(deltaSign = -1L)

    fun navigateToNextPeriod() = stepPeriod(deltaSign = 1L)

    private fun stepPeriod(deltaSign: Long) {
        val monthStartDay = userPreferencesFlow.value.monthStartDay
        val activeFractionDigits = uiState.value.amountFilterCurrencySpec.fractionDigits
        val today = LocalDate.now().toEpochDay()
        todayEpochDayFlow.value = today
        var didStep = false
        controlState.update { current ->
            val resolvedFilter = resolveSearchFilterWithAmountBounds(
                baseFilter = current.searchFilter,
                minInput = current.minAmountFilterInput,
                maxInput = current.maxAmountFilterInput,
                fractionDigits = activeFractionDigits,
            )
            val filteringActive = isUserFilterNonEmpty(resolvedFilter)
            if (filteringActive && current.filterAllPeriods) {
                didStep = false
                return@update current
            }
            didStep = true

            val currentRange = LedgerPeriodCalculator.computeFinancialMonthRange(
                referenceDateEpochDay = current.referenceDateEpochDay,
                monthStartDay = monthStartDay,
            )
            val deltaMonths = if (!filteringActive && current.selectedSubTab == LedgerSubTab.MONTHLY) {
                12L * deltaSign
            } else {
                1L * deltaSign
            }
            val targetEpochDay = LedgerPeriodCalculator.stepFinancialMonth(
                currentReferenceEpochDay = current.referenceDateEpochDay,
                monthStartDay = monthStartDay,
                deltaMonths = deltaMonths,
            )
            val targetPeriod = LedgerPeriodCalculator.computeFinancialMonthRange(targetEpochDay, monthStartDay)
            val targetSelectedDay = if (today in targetPeriod.startEpochDay..targetPeriod.endEpochDay) {
                today
            } else {
                targetEpochDay
            }
            current.copy(
                referenceDateEpochDay = targetSelectedDay,
                selectedCalendarDayEpochDay = targetSelectedDay,
                expandedWeekIndices = null,
                expandedYearlyMonths = if (targetPeriod.anchorYear != currentRange.anchorYear) null else current.expandedYearlyMonths,
                inspectedTransaction = null,
            )
        }
        if (didStep) {
            lastObservedTodayEpochDay = today
        }
    }

    fun jumpToToday() {
        val today = LocalDate.now().toEpochDay()
        lastObservedTodayEpochDay = today
        todayEpochDayFlow.value = today
        controlState.update {
            it.copy(
                referenceDateEpochDay = today,
                selectedCalendarDayEpochDay = today,
                expandedWeekIndices = null,
                expandedYearlyMonths = null,
                inspectedTransaction = null,
                scrollToTopSignal = it.scrollToTopSignal + 1,
            )
        }
    }

    fun resetToDailySubTabAndJumpToToday() {
        val today = LocalDate.now().toEpochDay()
        lastObservedTodayEpochDay = today
        todayEpochDayFlow.value = today
        controlState.update {
            it.copy(
                selectedSubTab = LedgerSubTab.DAILY,
                referenceDateEpochDay = today,
                selectedCalendarDayEpochDay = today,
                expandedWeekIndices = null,
                expandedYearlyMonths = null,
                inspectedTransaction = null,
                scrollToTopSignal = it.scrollToTopSignal + 1,
            )
        }
    }

    fun selectCalendarDay(epochDay: Long) {
        controlState.update { it.copy(selectedCalendarDayEpochDay = epochDay) }
    }

    fun toggleWeekExpanded(weekIndex: Int) {
        val monthStartDay = userPreferencesFlow.value.monthStartDay
        val weekStartDayIso = userPreferencesFlow.value.weekStartDayIso
        val today = LocalDate.now().toEpochDay()
        todayEpochDayFlow.value = today
        controlState.update { current ->
            val currentRange = LedgerPeriodCalculator.computeFinancialMonthRange(
                referenceDateEpochDay = current.referenceDateEpochDay,
                monthStartDay = monthStartDay,
            )
            val isCurrentPeriod = today in currentRange.startEpochDay..currentRange.endEpochDay
            val defaultExpanded = if (isCurrentPeriod) {
                val buckets = LedgerBucketingCalculator.buildWeeklyBucketsForPeriod(
                    periodRange = currentRange,
                    weekStartDayIso = weekStartDayIso,
                    transactions = emptyList(),
                )
                val activeWeek = buckets.firstOrNull { bucket ->
                    today in bucket.startEpochDay..bucket.endEpochDay
                }?.weekIndex ?: buckets.lastOrNull()?.weekIndex ?: 1
                setOf(activeWeek)
            } else {
                emptySet()
            }
            val base = current.expandedWeekIndices ?: defaultExpanded
            val next = if (weekIndex in base) {
                base - weekIndex
            } else {
                base + weekIndex
            }
            current.copy(expandedWeekIndices = next)
        }
    }

    fun toggleYearlyMonthExpanded(monthIndex: Int) {
        val monthStartDay = userPreferencesFlow.value.monthStartDay
        val today = LocalDate.now().toEpochDay()
        todayEpochDayFlow.value = today
        controlState.update { current ->
            val currentRange = LedgerPeriodCalculator.computeFinancialMonthRange(
                referenceDateEpochDay = current.referenceDateEpochDay,
                monthStartDay = monthStartDay,
            )
            val yearRanges = LedgerPeriodCalculator.computeFinancialYearMonthRanges(
                anchorYear = currentRange.anchorYear,
                monthStartDay = monthStartDay,
            )
            val isCurrentYear = today in (yearRanges.first().startEpochDay..yearRanges.last().endEpochDay)
            val defaultExpanded = if (isCurrentYear) {
                val activeMonthIdx = yearRanges.indexOfFirst {
                    today in it.startEpochDay..it.endEpochDay
                }.let { if (it >= 0) it + 1 else currentRange.anchorMonthValue }
                setOf(activeMonthIdx)
            } else {
                emptySet()
            }
            val baseSet = current.expandedYearlyMonths ?: defaultExpanded
            val next = if (monthIndex in baseSet) {
                baseSet - monthIndex
            } else {
                baseSet + monthIndex
            }
            current.copy(expandedYearlyMonths = next)
        }
    }

    fun drillDownToMonth(startEpochDay: Long) {
        val monthStartDay = userPreferencesFlow.value.monthStartDay
        val today = LocalDate.now().toEpochDay()
        lastObservedTodayEpochDay = today
        todayEpochDayFlow.value = today
        val targetPeriod = LedgerPeriodCalculator.computeFinancialMonthRange(startEpochDay, monthStartDay)
        val targetSelectedDay = if (today in targetPeriod.startEpochDay..targetPeriod.endEpochDay) {
            today
        } else {
            startEpochDay
        }
        controlState.update { current ->
            current.copy(
                referenceDateEpochDay = targetSelectedDay,
                selectedCalendarDayEpochDay = targetSelectedDay,
                selectedSubTab = LedgerSubTab.DAILY,
                expandedWeekIndices = null,
                inspectedTransaction = null,
            )
        }
    }

    fun drillDownToWeek(monthStartEpochDay: Long, weekIndex: Int) {
        val monthStartDay = userPreferencesFlow.value.monthStartDay
        val today = LocalDate.now().toEpochDay()
        lastObservedTodayEpochDay = today
        todayEpochDayFlow.value = today
        val targetPeriod = LedgerPeriodCalculator.computeFinancialMonthRange(monthStartEpochDay, monthStartDay)
        val targetSelectedDay = if (today in targetPeriod.startEpochDay..targetPeriod.endEpochDay) {
            today
        } else {
            monthStartEpochDay
        }
        controlState.update { current ->
            current.copy(
                referenceDateEpochDay = targetSelectedDay,
                selectedCalendarDayEpochDay = targetSelectedDay,
                selectedSubTab = LedgerSubTab.WEEKLY,
                expandedWeekIndices = setOf(weekIndex),
                inspectedTransaction = null,
            )
        }
    }

    fun inspectTransaction(transaction: TransactionItem?) {
        controlState.update { it.copy(inspectedTransaction = transaction) }
    }

    fun deleteTransaction(transactionId: Long) {
        if (!isDeletingTransactionLock.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val result = transactionRepository.deleteTransaction(transactionId)
                result.fold(
                    onSuccess = {
                        controlState.update {
                            it.copy(
                                inspectedTransaction = null,
                                snackbarMessage = "Transaction deleted from ledger",
                            )
                        }
                    },
                    onFailure = { err ->
                        controlState.update {
                            it.copy(snackbarMessage = err.message ?: "Unable to delete transaction")
                        }
                    },
                )
            } finally {
                isDeletingTransactionLock.set(false)
            }
        }
    }

    // Search & Reinforced Multi-Filter Actions

    fun toggleSearchBar() {
        controlState.update { current ->
            val isCurrentlyShown = current.isSearchBarVisible || current.searchFilter.queryText.isNotBlank()
            val nextVisible = !isCurrentlyShown
            current.copy(
                isSearchBarVisible = nextVisible,
                searchFilter = if (!nextVisible) {
                    current.searchFilter.copy(queryText = "")
                } else {
                    current.searchFilter
                },
            )
        }
    }

    fun updateSearchQuery(query: String) {
        controlState.update { current ->
            current.copy(searchFilter = current.searchFilter.copy(queryText = query))
        }
    }

    fun setFilterSheetOpen(open: Boolean) {
        controlState.update { it.copy(isFilterSheetOpen = open) }
    }

    fun toggleFilterTransactionType(type: TransactionType) {
        controlState.update { current ->
            val existing = current.searchFilter.types
            val next = if (type in existing) existing - type else existing + type
            current.copy(searchFilter = current.searchFilter.copy(types = next))
        }
    }

    fun toggleFilterAccountId(accountId: Long) {
        controlState.update { current ->
            val existing = current.searchFilter.accountIds
            if (current.scopedAccountId == accountId && existing.isEmpty()) {
                return@update current.copy(scopedAccountId = null)
            }
            val baseSet = if (current.scopedAccountId != null && existing.isEmpty()) {
                existing + current.scopedAccountId
            } else {
                existing
            }
            val isAdding = accountId !in baseSet
            val next = if (isAdding) baseSet + accountId else baseSet - accountId
            current.copy(
                scopedAccountId = null,
                searchFilter = current.searchFilter.copy(accountIds = next),
            )
        }
    }

    fun toggleFilterCategoryId(categoryId: Long) {
        controlState.update { current ->
            val existing = current.searchFilter.categoryIds
            val next = if (categoryId in existing) existing - categoryId else existing + categoryId
            current.copy(searchFilter = current.searchFilter.copy(categoryIds = next))
        }
    }

    fun toggleFilterTag(tag: String) {
        controlState.update { current ->
            val existing = current.searchFilter.tags
            val next = if (tag in existing) existing - tag else existing + tag
            current.copy(searchFilter = current.searchFilter.copy(tags = next))
        }
    }

    fun toggleFilterTransfers() {
        controlState.update { current ->
            val existing = current.searchFilter.types
            val hasTransfers = TransactionType.TRANSFER in existing || TransactionType.CC_SETTLEMENT in existing
            val next = if (hasTransfers) {
                existing - TransactionType.TRANSFER - TransactionType.CC_SETTLEMENT
            } else {
                existing + TransactionType.TRANSFER + TransactionType.CC_SETTLEMENT
            }
            current.copy(searchFilter = current.searchFilter.copy(types = next))
        }
    }

    fun updateFilterAmountBounds(minInput: String, maxInput: String) {
        controlState.update { current ->
            current.copy(
                minAmountFilterInput = minInput,
                maxAmountFilterInput = maxInput,
            )
        }
    }

    fun setFilterAllPeriods(allPeriods: Boolean) {
        controlState.update { current ->
            current.copy(filterAllPeriods = allPeriods)
        }
    }

    @Suppress("unused")
    fun restrictFilterToActivePeriod(restrict: Boolean) {
        setFilterAllPeriods(!restrict)
    }

    fun clearAllFilters() {
        controlState.update { current ->
            current.copy(
                searchFilter = TransactionSearchFilter(),
                filterAllPeriods = false,
                minAmountFilterInput = "",
                maxAmountFilterInput = "",
                isSearchBarVisible = false,
                isFilterSheetOpen = false,
                inspectedTransaction = null,
            )
        }
    }

    fun exportActivePeriodToCsv(destinationUriString: String) {
        if (!isExportingCsvLock.compareAndSet(false, true)) return
        val period = uiState.value.activePeriodRange
        val scopedAccId = uiState.value.scopedAccountId
        val scopedName = uiState.value.scopedAccount?.name
        viewModelScope.launch {
            controlState.update { it.copy(isExportingCsv = true) }
            try {
                val result = settingsAndBackupRepository.exportTransactionsToCsv(
                    destinationUriString = destinationUriString,
                    startEpochDay = period.startEpochDay,
                    endEpochDay = period.endEpochDay,
                    accountId = scopedAccId,
                )
                result.fold(
                    onSuccess = { rowCount ->
                        val label = if (scopedName != null) "$scopedName (${period.title})" else period.title
                        controlState.update {
                            it.copy(
                                isExportingCsv = false,
                                snackbarMessage = "Exported $rowCount transactions for $label to CSV",
                            )
                        }
                    },
                    onFailure = { err ->
                        controlState.update {
                            it.copy(
                                isExportingCsv = false,
                                snackbarMessage = err.message ?: "CSV export failed",
                            )
                        }
                    },
                )
            } finally {
                isExportingCsvLock.set(false)
                controlState.update { current ->
                    if (current.isExportingCsv) current.copy(isExportingCsv = false) else current
                }
            }
        }
    }

    fun refreshData() {
        val today = LocalDate.now().toEpochDay()
        val prevToday = lastObservedTodayEpochDay
        if (today != prevToday) {
            lastObservedTodayEpochDay = today
            todayEpochDayFlow.value = today
            controlState.update { current ->
                if (current.referenceDateEpochDay == prevToday) {
                    current.copy(
                        referenceDateEpochDay = today,
                        selectedCalendarDayEpochDay = if (current.selectedCalendarDayEpochDay == prevToday) today else current.selectedCalendarDayEpochDay,
                    )
                } else {
                    current
                }
            }
        }
    }

    fun clearSnackbarMessage() {
        controlState.update { it.copy(snackbarMessage = null) }
    }

    private fun resolveSearchFilterWithAmountBounds(
        baseFilter: TransactionSearchFilter,
        minInput: String,
        maxInput: String,
        fractionDigits: Int,
    ): TransactionSearchFilter {
        val parsedMin = parseOptionalMoneyFilter(minInput, fractionDigits)
        val parsedMax = parseOptionalMoneyFilter(maxInput, fractionDigits)
        val (effectiveMin, effectiveMax) = if (
            parsedMin != null &&
            parsedMax != null &&
            parsedMin.amountMinor > parsedMax.amountMinor
        ) {
            null to null
        } else {
            parsedMin to parsedMax
        }
        return baseFilter.copy(
            minAmount = effectiveMin,
            maxAmount = effectiveMax,
        )
    }

    private fun parseOptionalMoneyFilter(input: String, fractionDigits: Int): MinorMoney? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        return MoneyFormatterAndCalculator.evaluateNonNegativeExpressionToMinor(
            expression = trimmed,
            fractionDigits = fractionDigits,
        ).getOrNull()
    }

    private fun isUserFilterNonEmpty(filter: TransactionSearchFilter): Boolean = filter.queryText.isNotBlank() ||
        filter.accountIds.isNotEmpty() ||
        filter.categoryIds.isNotEmpty() ||
        filter.types.isNotEmpty() ||
        filter.tags.isNotEmpty() ||
        filter.minAmount != null ||
        filter.maxAmount != null

    private fun countActiveUserFilters(filter: TransactionSearchFilter): Int {
        var count = 0
        if (filter.queryText.isNotBlank()) count++
        count += filter.types.count { it != TransactionType.TRANSFER && it != TransactionType.CC_SETTLEMENT }
        if (TransactionType.TRANSFER in filter.types || TransactionType.CC_SETTLEMENT in filter.types) count++
        count += filter.accountIds.size
        count += filter.categoryIds.size
        count += filter.tags.size
        if (filter.minAmount != null || filter.maxAmount != null) count++
        return count
    }
}
