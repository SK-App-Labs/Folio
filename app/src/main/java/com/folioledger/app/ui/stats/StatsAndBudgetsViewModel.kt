// File: app/src/main/java/com/folioledger/app/ui/stats/StatsAndBudgetsViewModel.kt
package com.folioledger.app.ui.stats

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folioledger.app.R
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSavingsGoal
import com.folioledger.app.domain.model.AccountSavingsGoalConfig
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.BudgetPeriodType
import com.folioledger.app.domain.model.BudgetProgress
import com.folioledger.app.domain.model.CardMilestoneConfig
import com.folioledger.app.domain.model.CardMilestoneInfo
import com.folioledger.app.domain.model.CategoryNode
import com.folioledger.app.domain.model.CategorySpendStat
import com.folioledger.app.domain.model.CategoryType
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.MonthlySavingsProgress
import com.folioledger.app.domain.model.MonthlyTrendBar
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.PeriodLedgerSummary
import com.folioledger.app.domain.model.RecurringCommitmentItem
import com.folioledger.app.domain.model.RepeatFrequency
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.SpendRankingItem
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.domain.repository.AccountRepository
import com.folioledger.app.domain.repository.AnalyticsAndBudgetRepository
import com.folioledger.app.domain.repository.CategoryAndTagRepository
import com.folioledger.app.domain.repository.SettingsAndBackupRepository
import com.folioledger.app.domain.repository.TransactionRepository
import com.folioledger.app.domain.usecase.AccountingRulesEngine
import com.folioledger.app.domain.util.CardMilestoneCalculator
import com.folioledger.app.domain.util.CreditCardCockpitCalculator
import com.folioledger.app.domain.util.MerchantAndTagAnalyticsCalculator
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import com.folioledger.app.domain.util.NetWorthPredictiveCalculator
import com.folioledger.app.domain.util.RecurringScheduleCalculator
import com.folioledger.app.domain.util.SafeToSpendCalculator
import com.folioledger.app.domain.util.SavingsGoalCalculator
import com.folioledger.app.domain.util.StatsPeriodCalculator
import com.folioledger.app.domain.util.CategoryDrillDownCalculator
import com.folioledger.app.ui.accounts.CreditCardCycleCalculator
import com.folioledger.app.ui.accounts.CreditCardCycleWindow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class StatsAndBudgetsViewModel(
    private val analyticsAndBudgetRepository: AnalyticsAndBudgetRepository,
    categoryAndTagRepository: CategoryAndTagRepository,
    transactionRepository: TransactionRepository,
    private val settingsAndBackupRepository: SettingsAndBackupRepository,
    private val accountRepository: AccountRepository,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val controlState = MutableStateFlow(StatsInternalControlState())
    private var lastObservedTodayEpochDay: Long = LocalDate.now().toEpochDay()
    private val todayEpochDayFlow = MutableStateFlow(LocalDate.now().toEpochDay())

    private val userPreferencesFlow = settingsAndBackupRepository.observeUserPreferences()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = UserPreferences()
        )

    private val allAccountsFlow = todayEpochDayFlow
        .flatMapLatest { todayDay ->
            accountRepository.observeAllAccounts(todayDay)
        }

    private data class AnalyticsHistoryWindowKey(
        val granularity: StatsPeriodGranularity,
        val referenceDateEpochDay: Long,
        val requiresAllTimeHistory: Boolean
    )

    private val netWorthQueryParamsFlow = controlState
        .map { ctrl ->
            AnalyticsHistoryWindowKey(
                granularity = ctrl.periodGranularity,
                referenceDateEpochDay = ctrl.referenceDateEpochDay,
                requiresAllTimeHistory = ctrl.isRankingsAllTime ||
                        ctrl.drillDownPayeeName != null ||
                        ctrl.drillDownTagName != null
            )
        }
        .distinctUntilChanged()

    private val netWorthWindowTxsFlow = combine(
        netWorthQueryParamsFlow,
        todayEpochDayFlow
    ) { key, todayEpochDay -> key to todayEpochDay }
        .distinctUntilChanged()
        .flatMapLatest { (key, todayEpochDay) ->
            val refDate = LocalDate.ofEpochDay(key.referenceDateEpochDay)
            val todayDate = LocalDate.ofEpochDay(todayEpochDay)
            val windowStartEpochDay = if (key.requiresAllTimeHistory) {
                0L
            } else {
                // Guarantee at least:
                // 1) 12 months prior to today for rolling 1-year Credit Card Milestones & YTD spend
                // 2) 7 months prior to refDate (and Jan 1 of refDate.year - 6 months) for 6-month trend bars
                val oneYearBeforeToday = todayDate.minusYears(1).withDayOfMonth(1).toEpochDay()
                val sevenMonthsBeforeRef = refDate.minusMonths(7).withDayOfMonth(1).toEpochDay()
                val annualStart = LocalDate.of(refDate.year, 1, 1).minusMonths(6).toEpochDay()
                minOf(oneYearBeforeToday, sevenMonthsBeforeRef, annualStart)
            }
            val windowEndEpochDay = maxOf(todayEpochDay, refDate.plusMonths(13).toEpochDay())
            transactionRepository.observeTransactionsInRange(windowStartEpochDay, windowEndEpochDay)
        }

    private val categoryTreesFlow: Flow<Pair<List<CategoryNode>, List<CategoryNode>>> = combine(
        categoryAndTagRepository.observeCategoryTree(CategoryType.EXPENSE),
        categoryAndTagRepository.observeCategoryTree(CategoryType.INCOME)
    ) { expenseTree, incomeTree ->
        expenseTree to incomeTree
    }

    private data class PeriodQueryParametersKey(
        val granularity: StatsPeriodGranularity,
        val referenceDateEpochDay: Long,
        val customStartEpochDay: Long,
        val customEndEpochDay: Long
    )

    private val periodQueryParametersFlow = combine(
        controlState.map { ctrl ->
            PeriodQueryParametersKey(
                granularity = ctrl.periodGranularity,
                referenceDateEpochDay = ctrl.referenceDateEpochDay,
                customStartEpochDay = ctrl.customStartEpochDay,
                customEndEpochDay = ctrl.customEndEpochDay
            )
        }.distinctUntilChanged(),
        userPreferencesFlow
    ) { key, prefs ->
        PeriodQueryParameters(
            granularity = key.granularity,
            referenceDateEpochDay = key.referenceDateEpochDay,
            customStartEpochDay = key.customStartEpochDay,
            customEndEpochDay = key.customEndEpochDay,
            isRankingsAllTime = false,
            prefs = prefs
        )
    }.distinctUntilChanged()

    private val periodAnalyticsFlow: Flow<PeriodAnalyticsBundle> = periodQueryParametersFlow.flatMapLatest { params ->
        val prefs = params.prefs
        val periodRange = StatsPeriodCalculator.computePeriodRange(
            granularity = params.granularity,
            referenceDateEpochDay = params.referenceDateEpochDay,
            monthStartDay = prefs.monthStartDay,
            weekStartDayIso = prefs.weekStartDayIso,
            customStartEpochDay = params.customStartEpochDay,
            customEndEpochDay = params.customEndEpochDay
        )

        val priorPeriodRange = when (params.granularity) {
            StatsPeriodGranularity.WEEKLY -> StatsPeriodCalculator.computeWeeklyRange(
                referenceDateEpochDay = LocalDate.ofEpochDay(periodRange.startEpochDay).minusDays(1).toEpochDay(),
                weekStartDayIso = prefs.weekStartDayIso
            )
            StatsPeriodGranularity.MONTHLY -> StatsPeriodCalculator.computeFinancialMonthRange(
                referenceDateEpochDay = LocalDate.ofEpochDay(periodRange.startEpochDay).minusDays(1).toEpochDay(),
                monthStartDay = prefs.monthStartDay
            )
            StatsPeriodGranularity.ANNUALLY -> StatsPeriodCalculator.computeAnnualRange(
                referenceDateEpochDay = LocalDate.ofEpochDay(periodRange.startEpochDay).minusDays(1).toEpochDay(),
                monthStartDay = prefs.monthStartDay
            )
            StatsPeriodGranularity.CUSTOM -> {
                val span = periodRange.endEpochDay - periodRange.startEpochDay + 1
                StatsPeriodCalculator.computeCustomRange(
                    customStartEpochDay = periodRange.startEpochDay - span,
                    customEndEpochDay = periodRange.startEpochDay - 1
                )
            }
        }

        val currentStatsFlow = combine(
            analyticsAndBudgetRepository.observePeriodLedgerSummary(
                startEpochDay = periodRange.startEpochDay,
                endEpochDay = periodRange.endEpochDay,
                includeCarryOver = prefs.carryOverEnabled &&
                        params.granularity == StatsPeriodGranularity.MONTHLY
            ),
            analyticsAndBudgetRepository.observeCategorySpendStats(
                startEpochDay = periodRange.startEpochDay,
                endEpochDay = periodRange.endEpochDay,
                type = CategoryType.EXPENSE
            ),
            analyticsAndBudgetRepository.observeCategorySpendStats(
                startEpochDay = periodRange.startEpochDay,
                endEpochDay = periodRange.endEpochDay,
                type = CategoryType.INCOME
            )
        ) { summary, expenseStats, incomeStats ->
            Triple(summary, expenseStats, incomeStats)
        }

        val priorStatsFlow = combine(
            analyticsAndBudgetRepository.observePeriodLedgerSummary(
                startEpochDay = priorPeriodRange.startEpochDay,
                endEpochDay = priorPeriodRange.endEpochDay,
                includeCarryOver = false
            ),
            analyticsAndBudgetRepository.observeCategorySpendStats(
                startEpochDay = priorPeriodRange.startEpochDay,
                endEpochDay = priorPeriodRange.endEpochDay,
                type = CategoryType.EXPENSE
            ),
            analyticsAndBudgetRepository.observeCategorySpendStats(
                startEpochDay = priorPeriodRange.startEpochDay,
                endEpochDay = priorPeriodRange.endEpochDay,
                type = CategoryType.INCOME
            )
        ) { priorSummary, priorExpense, priorIncome ->
            Triple(priorSummary, priorExpense, priorIncome)
        }

        val summaryAndCategoryStatsFlow = combine(
            currentStatsFlow,
            priorStatsFlow
        ) { (summary, expenseStats, incomeStats), (priorSummary, priorExpense, priorIncome) ->
            val enrichedExpenses = CategoryDrillDownCalculator.attachComparativeDeltas(expenseStats, priorExpense)
            val enrichedIncomes = CategoryDrillDownCalculator.attachComparativeDeltas(incomeStats, priorIncome)
            EnrichedPeriodStats(
                summary = summary,
                priorSummary = priorSummary,
                expenseStats = enrichedExpenses,
                incomeStats = enrichedIncomes
            )
        }

        val trendsAndBudgetsFlow = combine(
            analyticsAndBudgetRepository.observeSixMonthTrendBars(
                referenceDateEpochDay = periodRange.endEpochDay,
                monthStartDay = prefs.monthStartDay.coerceIn(1, 31)
            ),
            run {
                val todayEpochDay = LocalDate.now().toEpochDay()
                val budgetReferenceEpochDay = if (todayEpochDay in periodRange.startEpochDay..periodRange.endEpochDay) {
                    todayEpochDay
                } else {
                    periodRange.endEpochDay
                }
                analyticsAndBudgetRepository.observeBudgetProgressList(
                    startEpochDay = periodRange.startEpochDay,
                    endEpochDay = periodRange.endEpochDay,
                    referenceDateEpochDay = budgetReferenceEpochDay,
                    todayEpochDay = todayEpochDay
                )
            },
            transactionRepository.observeTransactionsInRange(
                startEpochDay = periodRange.startEpochDay,
                endEpochDay = periodRange.endEpochDay
            ),
            analyticsAndBudgetRepository.observeActiveRecurringCommitments()
        ) { trendBars, budgets, periodTxns, commitments ->
            TrendsAndBudgetsBundle(trendBars, budgets, periodTxns, commitments)
        }

        val rankingsFlow = controlState
            .map { it.isRankingsAllTime }
            .distinctUntilChanged()
            .flatMapLatest { isAllTime ->
                val rankingsStartDay = if (isAllTime) 0L else periodRange.startEpochDay
                val rankingsEndDay = if (isAllTime) Long.MAX_VALUE else periodRange.endEpochDay
                combine(
                    analyticsAndBudgetRepository.observeTagSpendRankings(
                        startEpochDay = rankingsStartDay,
                        endEpochDay = rankingsEndDay
                    ),
                    analyticsAndBudgetRepository.observePayeeOrNoteRankings(
                        startEpochDay = rankingsStartDay,
                        endEpochDay = rankingsEndDay
                    )
                ) { tags, payees ->
                    tags to payees
                }
            }

        combine(
            summaryAndCategoryStatsFlow,
            trendsAndBudgetsFlow,
            rankingsFlow
        ) { enrichedStats, trendsBundle, (tags, payees) ->
            PeriodAnalyticsBundle(
                prefs = prefs,
                periodRange = periodRange,
                periodSummary = enrichedStats.summary,
                priorPeriodSummary = enrichedStats.priorSummary,
                expenseStats = enrichedStats.expenseStats,
                incomeStats = enrichedStats.incomeStats,
                trendBars = trendsBundle.trendBars,
                budgets = trendsBundle.budgets,
                tagRankings = tags,
                payeeRankings = payees,
                periodTransactions = trendsBundle.periodTransactions,
                recurringCommitments = trendsBundle.recurringCommitments
            )
        }
    }.flowOn(computationDispatcher)

    private data class StatsComputedCore(
        val bundle: PeriodAnalyticsBundle,
        val expenseTree: List<CategoryNode>,
        val incomeTree: List<CategoryNode>,
        val allAccounts: List<AccountSummary>,
        val allTxs: List<TransactionItem>,
        val currencySpec: CurrencySpec,
        val netWorthTrend: List<NetWorthGrowthTrendItem>,
        val netWorthVelocity: NetWorthVelocityState,
        val emergencyRunwayLiquid: EmergencyRunwayState,
        val emergencyRunwayWithInvestments: EmergencyRunwayState,
        val creditHealthList: List<CreditCardHealthUiModel>,
        val assetAccountsForSettlement: List<AccountSummary>,
        val savingsGoalEligibleAccounts: List<AccountSummary>,
        val creditCockpitState: CreditCardCockpitUiState,
        val overallBudget: BudgetProgress?,
        val overallBudgets: List<BudgetProgress>,
        val categoryBudgets: List<BudgetProgress>,
        val customBudgets: List<BudgetProgress>,
        val safeToSpendState: SafeToSpendState,
        val shortfallAlerts: List<AccountShortfallAlert>,
        val monthlySavingsProgress: MonthlySavingsProgress,
        val accountSavingsGoals: List<AccountSavingsGoal>,
        val payeesByFrequency: List<SpendRankingItem>,
        val todayEpochDay: Long
    )

    private val coreAnalyticsFlow: Flow<StatsComputedCore> = combine(
        periodAnalyticsFlow,
        categoryTreesFlow,
        allAccountsFlow,
        netWorthWindowTxsFlow,
        combine(
            controlState.map { it.referenceDateEpochDay to it.periodGranularity }.distinctUntilChanged(),
            todayEpochDayFlow
        ) { refAndGran, todayDay -> refAndGran to todayDay }
    ) { bundle, (expenseTree, incomeTree), allAccounts, allTxs, (refAndGran, todayEpochDay) ->
        val (refEpochDay, granularity) = refAndGran
        val prefs = bundle.prefs
        val currencySpec = SupportedCurrencies.fromCode(prefs.primaryCurrencyCode)

        val netWorthTrend = computeNetWorthTrend(
            allAccounts = allAccounts,
            allTransactions = allTxs,
            referenceDateEpochDay = refEpochDay,
            periodGranularity = granularity,
            primaryCurrencyCode = prefs.primaryCurrencyCode,
            secondaryCurrencies = prefs.secondaryCurrencies,
            todayEpochDay = todayEpochDay
        )

        val creditHealthList = computeCreditCardHealthList(allAccounts)
        val overallBudgets = bundle.budgets.filter { it.categoryId == null && it.periodType != BudgetPeriodType.CUSTOM }
        val primaryOverallBudget = overallBudgets.firstOrNull { it.periodType == BudgetPeriodType.MONTHLY }
            ?: overallBudgets.firstOrNull()
        val categoryBudgets = bundle.budgets.filter { it.categoryId != null && it.periodType != BudgetPeriodType.CUSTOM }
        val customBudgets = bundle.budgets.filter { it.periodType == BudgetPeriodType.CUSTOM }

        val assetAccountsForSettlement = allAccounts.filter {
            !it.isArchived && (
                it.groupType == AccountGroup.BANK_ACCOUNT ||
                it.groupType == AccountGroup.CASH ||
                it.groupType == AccountGroup.SAVINGS ||
                it.groupType == AccountGroup.PREPAID_WALLET
            )
        }

        val savingsGoalEligibleAccounts = allAccounts.filter {
            !it.isArchived && (
                it.groupType == AccountGroup.BANK_ACCOUNT ||
                it.groupType == AccountGroup.SAVINGS ||
                it.groupType == AccountGroup.INVESTMENT ||
                it.groupType == AccountGroup.CASH ||
                it.groupType == AccountGroup.PREPAID_WALLET
            )
        }

        val creditCockpitState = computeCreditCockpitState(
            allAccounts = allAccounts,
            allTxs = allTxs,
            periodTransactions = bundle.periodTransactions,
            trendBars = bundle.trendBars,
            referenceDateEpochDay = todayEpochDay,
            milestonesJson = prefs.cardMilestonesJson,
            primaryCurrencyCode = prefs.primaryCurrencyCode,
            secondaryCurrencies = prefs.secondaryCurrencies
        )

        val safeToSpendState = StatsPredictiveCalculations.computeSafeToSpend(
            allAccounts = allAccounts,
            recurringRules = bundle.recurringCommitments,
            futureTransactions = allTxs,
            todayEpochDay = todayEpochDay,
            periodStartEpochDay = bundle.periodRange.startEpochDay,
            periodEndEpochDay = bundle.periodRange.endEpochDay,
            primaryCurrencyCode = prefs.primaryCurrencyCode,
            secondaryCurrencies = prefs.secondaryCurrencies
        )

        val shortfallAlerts = StatsPredictiveCalculations.computeShortfallAlerts(
            allAccounts = allAccounts,
            recurringRules = bundle.recurringCommitments,
            futureTransactions = allTxs,
            todayEpochDay = todayEpochDay,
            primaryCurrencyCode = prefs.primaryCurrencyCode,
            secondaryCurrencies = prefs.secondaryCurrencies
        )

        val monthlySavingsProgress = SavingsGoalCalculator.computeMonthlySavingsProgress(
            allAccounts = allAccounts,
            periodTransactions = bundle.periodTransactions,
            periodSummary = bundle.periodSummary,
            monthlySavingsTargetMinor = prefs.monthlySavingsTargetMinor,
            primaryCurrencyCode = prefs.primaryCurrencyCode,
            secondaryCurrencies = prefs.secondaryCurrencies
        )

        val accountSavingsGoals = SavingsGoalCalculator.computeAccountSavingsGoals(
            allAccounts = allAccounts,
            goalsJson = prefs.savingsGoalsJson
        )

        val payeesByFrequency = bundle.payeeRankings.sortedWith(
            compareByDescending<SpendRankingItem> { it.transactionCount }
                .thenByDescending { it.totalAmount }
        )

        StatsComputedCore(
            bundle = bundle,
            expenseTree = expenseTree,
            incomeTree = incomeTree,
            allAccounts = allAccounts,
            allTxs = allTxs,
            currencySpec = currencySpec,
            netWorthTrend = netWorthTrend,
            netWorthVelocity = StatsPredictiveCalculations.computeNetWorthVelocity(netWorthTrend),
            emergencyRunwayLiquid = StatsPredictiveCalculations.computeEmergencyRunway(
                allAccounts = allAccounts,
                trendBars = bundle.trendBars,
                primaryCurrencyCode = prefs.primaryCurrencyCode,
                secondaryCurrencies = prefs.secondaryCurrencies,
                includesInvestments = false
            ),
            emergencyRunwayWithInvestments = StatsPredictiveCalculations.computeEmergencyRunway(
                allAccounts = allAccounts,
                trendBars = bundle.trendBars,
                primaryCurrencyCode = prefs.primaryCurrencyCode,
                secondaryCurrencies = prefs.secondaryCurrencies,
                includesInvestments = true
            ),
            creditHealthList = creditHealthList,
            assetAccountsForSettlement = assetAccountsForSettlement,
            savingsGoalEligibleAccounts = savingsGoalEligibleAccounts,
            creditCockpitState = creditCockpitState,
            overallBudget = primaryOverallBudget,
            overallBudgets = overallBudgets,
            categoryBudgets = categoryBudgets,
            customBudgets = customBudgets,
            safeToSpendState = safeToSpendState,
            shortfallAlerts = shortfallAlerts,
            monthlySavingsProgress = monthlySavingsProgress,
            accountSavingsGoals = accountSavingsGoals,
            payeesByFrequency = payeesByFrequency,
            todayEpochDay = todayEpochDay
        )
    }.flowOn(computationDispatcher)

    val uiState: StateFlow<StatsAndBudgetsUiState> = combine(
        controlState,
        coreAnalyticsFlow
    ) { ctrl, core ->
        val bundle = core.bundle
        val prefs = bundle.prefs

        val activeStats = if (ctrl.selectedCategoryType == CategoryType.EXPENSE) {
            bundle.expenseStats
        } else {
            bundle.incomeStats
        }
        val activeTree = if (ctrl.selectedCategoryType == CategoryType.EXPENSE) {
            core.expenseTree
        } else {
            core.incomeTree
        }

        val drillDownState = ctrl.drillDownCategoryId?.let { targetCatId ->
            val stat = activeStats.firstOrNull { it.categoryId == targetCatId }
                ?: bundle.expenseStats.firstOrNull { it.categoryId == targetCatId }
                ?: bundle.incomeStats.firstOrNull { it.categoryId == targetCatId }

            if (stat != null) {
                val matchingCategoryIds = CategoryDrillDownCalculator.collectCategoryAndSubcategoryIds(
                    tree = activeTree,
                    targetCategoryId = targetCatId
                )
                val matchingTxns = CategoryDrillDownCalculator.filterMatchingTransactions(
                    transactions = bundle.periodTransactions,
                    categoryType = ctrl.selectedCategoryType,
                    categoryName = stat.categoryName,
                    matchingCategoryIds = matchingCategoryIds
                )

                val matchingBudget = core.categoryBudgets.firstOrNull { it.categoryId == targetCatId }

                val categoryTrendBars = CategoryDrillDownCalculator.computeCategoryHistoricalTrend(
                    trendBars = bundle.trendBars,
                    allTransactions = core.allTxs,
                    categoryType = ctrl.selectedCategoryType,
                    categoryName = stat.categoryName,
                    matchingCategoryIds = matchingCategoryIds,
                    primaryCurrencyCode = prefs.primaryCurrencyCode,
                    secondaryCurrencies = prefs.secondaryCurrencies
                )

                val subcategoryBreakdown = CategoryDrillDownCalculator.computeSubcategoryBreakdown(
                    transactions = matchingTxns,
                    parentNetAmount = stat.netAmount,
                    parentCategoryName = stat.categoryName,
                    matchingCategoryIds = matchingCategoryIds,
                    primaryCurrencyCode = prefs.primaryCurrencyCode,
                    secondaryCurrencies = prefs.secondaryCurrencies
                )

                CategoryDrillDownState(
                    categoryStat = stat,
                    categoryType = ctrl.selectedCategoryType,
                    transactions = matchingTxns,
                    matchingBudget = matchingBudget,
                    categorySixMonthTrendBars = categoryTrendBars,
                    subcategoryBreakdown = subcategoryBreakdown
                )
            } else {
                null
            }
        }

        val sortedPayees = if (ctrl.selectedPayeeRankingSortMode == PayeeSortMode.BY_FREQUENCY) {
            core.payeesByFrequency
        } else {
            bundle.payeeRankings
        }

        val payeeDrillDownState = ctrl.drillDownPayeeName?.let { targetPayee ->
            StatsPredictiveCalculations.computePayeeDrillDownState(
                payeeName = targetPayee,
                allTxs = core.allTxs,
                trendBars = bundle.trendBars,
                startEpochDay = bundle.periodRange.startEpochDay,
                endEpochDay = bundle.periodRange.endEpochDay,
                isAllTime = ctrl.isRankingsAllTime,
                primaryCurrencyCode = prefs.primaryCurrencyCode,
                secondaryCurrencies = prefs.secondaryCurrencies
            )
        }

        val tagDrillDownState = ctrl.drillDownTagName?.let { targetTag ->
            StatsPredictiveCalculations.computeTagProjectDrillDownState(
                tagName = targetTag,
                allTxs = core.allTxs,
                trendBars = bundle.trendBars,
                startEpochDay = bundle.periodRange.startEpochDay,
                endEpochDay = bundle.periodRange.endEpochDay,
                isAllTime = ctrl.isRankingsAllTime,
                primaryCurrencyCode = prefs.primaryCurrencyCode,
                secondaryCurrencies = prefs.secondaryCurrencies
            )
        }

        val emergencyRunway = if (ctrl.isRunwayIncludingInvestments) {
            core.emergencyRunwayWithInvestments
        } else {
            core.emergencyRunwayLiquid
        }

        StatsAndBudgetsUiState(
            selectedSubTab = ctrl.selectedSubTab,
            selectedOverviewMode = ctrl.selectedOverviewMode,
            periodGranularity = ctrl.periodGranularity,
            selectedCategoryType = ctrl.selectedCategoryType,
            selectedRankingMode = ctrl.selectedRankingMode,
            isRankingsAllTime = ctrl.isRankingsAllTime,
            selectedTrendChartMode = ctrl.selectedTrendChartMode,
            selectedNetWorthMetricFilter = ctrl.selectedNetWorthMetricFilter,
            referenceDateEpochDay = ctrl.referenceDateEpochDay,
            todayEpochDay = core.todayEpochDay,
            customStartEpochDay = ctrl.customStartEpochDay,
            customEndEpochDay = ctrl.customEndEpochDay,
            isCustomDatePickerOpen = ctrl.isCustomDatePickerOpen,
            activePeriodRange = bundle.periodRange,
            periodSummary = bundle.periodSummary,
            priorPeriodSummary = bundle.priorPeriodSummary,
            userPreferences = prefs,
            currencySpec = core.currencySpec,
            numberGrouping = prefs.numberGrouping,
            expenseCategoryStats = bundle.expenseStats,
            incomeCategoryStats = bundle.incomeStats,
            highlightedDonutCategoryId = ctrl.highlightedDonutCategoryId,
            sixMonthTrendBars = bundle.trendBars,
            sixMonthNetWorthTrend = core.netWorthTrend,
            netWorthVelocity = core.netWorthVelocity,
            emergencyRunway = emergencyRunway,
            totalAvailableCredit = core.creditCockpitState.totalAvailableCredit,
            totalCreditLimit = core.creditCockpitState.totalCreditLimit,
            totalUsedCredit = core.creditCockpitState.totalCcDebt,
            overallCreditUtilizationPercent = core.creditCockpitState.aggregateUtilizationPercent,
            creditCardHealthList = core.creditHealthList,
            creditCockpitState = core.creditCockpitState,
            assetAccountsForSettlement = core.assetAccountsForSettlement,
            savingsGoalEligibleAccounts = core.savingsGoalEligibleAccounts,
            budgetProgressList = bundle.budgets,
            overallBudgetProgress = core.overallBudget,
            overallBudgetProgressList = core.overallBudgets,
            categoryBudgetProgressList = core.categoryBudgets,
            customBudgetProgressList = core.customBudgets,
            selectedBudgetCadenceFilter = ctrl.selectedBudgetCadenceFilter,
            expenseCategoryTree = core.expenseTree,
            incomeCategoryTree = core.incomeTree,
            tagRankings = bundle.tagRankings,
            payeeOrNoteRankings = sortedPayees,
            selectedPayeeRankingSortMode = ctrl.selectedPayeeRankingSortMode,
            drillDownState = drillDownState,
            payeeDrillDownState = payeeDrillDownState,
            tagDrillDownState = tagDrillDownState,
            safeToSpend = core.safeToSpendState,
            shortfallAlerts = core.shortfallAlerts,
            budgetEditorDialogState = ctrl.budgetEditorDialogState,
            isSavingBudget = ctrl.isSavingBudget,
            monthlySavingsProgress = core.monthlySavingsProgress,
            accountSavingsGoals = core.accountSavingsGoals,
            isMonthlySavingsTargetDialogOpen = ctrl.isMonthlySavingsTargetDialogOpen,
            monthlySavingsTargetInput = ctrl.monthlySavingsTargetInput,
            isSavingsGoalSheetOpen = ctrl.isSavingsGoalSheetOpen,
            editingSavingsGoalAccountId = ctrl.editingSavingsGoalAccountId,
            editingSavingsGoalTitle = ctrl.editingSavingsGoalTitle,
            editingSavingsGoalTargetAmountInput = ctrl.editingSavingsGoalTargetAmountInput,
            editingSavingsGoalTargetDateEpochDay = ctrl.editingSavingsGoalTargetDateEpochDay,
            snackbarMessage = ctrl.snackbarMessage
        )
    }.flowOn(computationDispatcher)
    .catch { throwable ->
        if (throwable is kotlinx.coroutines.CancellationException) throw throwable
        emit(
            StatsAndBudgetsUiState(
                snackbarMessage = "Analytics calculation error: ${throwable.localizedMessage ?: "Calculation error"}"
            )
        )
    }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = StatsAndBudgetsUiState(
            referenceDateEpochDay = LocalDate.now().toEpochDay(),
            customStartEpochDay = LocalDate.now().withDayOfMonth(1).toEpochDay(),
            customEndEpochDay = LocalDate.now().toEpochDay()
        )
    )

    fun refreshData() {
        val today = LocalDate.now().toEpochDay()
        val prevToday = lastObservedTodayEpochDay
        if (today != prevToday) {
            lastObservedTodayEpochDay = today
            todayEpochDayFlow.value = today
            controlState.update { current ->
                if (current.referenceDateEpochDay == prevToday) {
                    current.copy(referenceDateEpochDay = today)
                } else {
                    current
                }
            }
        }
    }

    fun selectSubTab(subTab: StatsSubTab) {
        controlState.update { current ->
            if (subTab == StatsSubTab.BUDGETS) {
                val adaptedGranularity = when (current.selectedBudgetCadenceFilter) {
                    BudgetPeriodType.WEEKLY -> StatsPeriodGranularity.WEEKLY
                    BudgetPeriodType.ANNUAL -> StatsPeriodGranularity.ANNUALLY
                    else -> StatsPeriodGranularity.MONTHLY
                }
                current.copy(
                    selectedSubTab = subTab,
                    periodGranularity = adaptedGranularity
                )
            } else {
                current.copy(selectedSubTab = subTab)
            }
        }
    }

    fun selectBudgetCadenceFilter(filter: BudgetPeriodType?) {
        controlState.update { current ->
            val adaptedGranularity = when (filter) {
                BudgetPeriodType.WEEKLY -> StatsPeriodGranularity.WEEKLY
                BudgetPeriodType.ANNUAL -> StatsPeriodGranularity.ANNUALLY
                else -> StatsPeriodGranularity.MONTHLY
            }
            current.copy(
                selectedBudgetCadenceFilter = filter,
                periodGranularity = adaptedGranularity
            )
        }
    }

    fun toggleRankingsAllTime() {
        controlState.update { it.copy(isRankingsAllTime = !it.isRankingsAllTime) }
    }

    fun selectPeriodGranularity(granularity: StatsPeriodGranularity) {
        val prefs = userPreferencesFlow.value
        controlState.update { current ->
            val shouldInitCustomFromCurrent = granularity == StatsPeriodGranularity.CUSTOM &&
                    current.periodGranularity != StatsPeriodGranularity.CUSTOM
            val currentRange = if (shouldInitCustomFromCurrent) {
                StatsPeriodCalculator.computePeriodRange(
                    granularity = current.periodGranularity,
                    referenceDateEpochDay = current.referenceDateEpochDay,
                    monthStartDay = prefs.monthStartDay,
                    weekStartDayIso = prefs.weekStartDayIso,
                    customStartEpochDay = current.customStartEpochDay,
                    customEndEpochDay = current.customEndEpochDay
                )
            } else null

            current.copy(
                periodGranularity = granularity,
                customStartEpochDay = currentRange?.startEpochDay ?: current.customStartEpochDay,
                customEndEpochDay = currentRange?.endEpochDay ?: current.customEndEpochDay,
                isCustomDatePickerOpen = granularity == StatsPeriodGranularity.CUSTOM &&
                        current.periodGranularity != StatsPeriodGranularity.CUSTOM,
                highlightedDonutCategoryId = null,
                drillDownCategoryId = null
            )
        }
    }

    fun setCustomDatePickerOpen(open: Boolean) {
        controlState.update { it.copy(isCustomDatePickerOpen = open) }
    }

    fun setCustomDateRange(startEpochDay: Long, endEpochDay: Long) {
        val safeStart = minOf(startEpochDay, endEpochDay)
        val safeEnd = maxOf(startEpochDay, endEpochDay)
        controlState.update {
            it.copy(
                periodGranularity = StatsPeriodGranularity.CUSTOM,
                customStartEpochDay = safeStart,
                customEndEpochDay = safeEnd,
                referenceDateEpochDay = safeEnd,
                isCustomDatePickerOpen = false,
                highlightedDonutCategoryId = null,
                drillDownCategoryId = null
            )
        }
    }

    fun selectCategoryType(type: CategoryType) {
        controlState.update {
            it.copy(
                selectedCategoryType = type,
                highlightedDonutCategoryId = null,
                drillDownCategoryId = null
            )
        }
    }

    fun selectRankingMode(mode: RankingMode) {
        controlState.update { it.copy(selectedRankingMode = mode) }
    }

    fun selectPayeeSortMode(mode: PayeeSortMode) {
        controlState.update { it.copy(selectedPayeeRankingSortMode = mode) }
    }

    fun openPayeeDrillDown(payeeName: String) {
        controlState.update { it.copy(drillDownPayeeName = payeeName) }
    }

    fun dismissPayeeDrillDown() {
        controlState.update { it.copy(drillDownPayeeName = null) }
    }

    fun openTagDrillDown(tagName: String) {
        controlState.update { it.copy(drillDownTagName = tagName) }
    }

    fun dismissTagDrillDown() {
        controlState.update { it.copy(drillDownTagName = null) }
    }

    fun navigateToPreviousPeriod() {
        val prefs = userPreferencesFlow.value
        controlState.update { current ->
            val stepped = StatsPeriodCalculator.stepPeriod(
                granularity = current.periodGranularity,
                referenceDateEpochDay = current.referenceDateEpochDay,
                monthStartDay = prefs.monthStartDay,
                customStartEpochDay = current.customStartEpochDay,
                customEndEpochDay = current.customEndEpochDay,
                delta = -1L
            )
            current.copy(
                referenceDateEpochDay = stepped.referenceDateEpochDay,
                customStartEpochDay = stepped.customStartEpochDay,
                customEndEpochDay = stepped.customEndEpochDay,
                highlightedDonutCategoryId = null,
                drillDownCategoryId = null
            )
        }
    }

    fun navigateToNextPeriod() {
        val prefs = userPreferencesFlow.value
        controlState.update { current ->
            val stepped = StatsPeriodCalculator.stepPeriod(
                granularity = current.periodGranularity,
                referenceDateEpochDay = current.referenceDateEpochDay,
                monthStartDay = prefs.monthStartDay,
                customStartEpochDay = current.customStartEpochDay,
                customEndEpochDay = current.customEndEpochDay,
                delta = 1L
            )
            current.copy(
                referenceDateEpochDay = stepped.referenceDateEpochDay,
                customStartEpochDay = stepped.customStartEpochDay,
                customEndEpochDay = stepped.customEndEpochDay,
                highlightedDonutCategoryId = null,
                drillDownCategoryId = null
            )
        }
    }

    fun jumpToToday() {
        val today = LocalDate.now().toEpochDay()
        lastObservedTodayEpochDay = today
        todayEpochDayFlow.value = today
        controlState.update { current ->
            if (current.periodGranularity == StatsPeriodGranularity.CUSTOM) {
                current.copy(isCustomDatePickerOpen = true)
            } else {
                current.copy(
                    referenceDateEpochDay = today,
                    highlightedDonutCategoryId = null,
                    drillDownCategoryId = null
                )
            }
        }
    }

    fun resetToOverviewSubTabAndJumpToToday() {
        val today = LocalDate.now().toEpochDay()
        lastObservedTodayEpochDay = today
        todayEpochDayFlow.value = today
        controlState.update { current ->
            current.copy(
                selectedSubTab = StatsSubTab.STATS,
                selectedOverviewMode = OverviewMode.CATEGORIES,
                referenceDateEpochDay = today,
                highlightedDonutCategoryId = null,
                drillDownCategoryId = null,
                drillDownPayeeName = null,
                drillDownTagName = null,
                isCustomDatePickerOpen = false,
                periodGranularity = if (current.periodGranularity == StatsPeriodGranularity.CUSTOM) {
                    StatsPeriodGranularity.MONTHLY
                } else {
                    current.periodGranularity
                }
            )
        }
    }

    fun onDonutSliceSelected(stat: CategorySpendStat?) {
        controlState.update {
            it.copy(highlightedDonutCategoryId = stat?.categoryId)
        }
    }

    fun openCategoryDrillDown(stat: CategorySpendStat) {
        controlState.update {
            it.copy(
                highlightedDonutCategoryId = stat.categoryId,
                drillDownCategoryId = stat.categoryId
            )
        }
    }

    fun dismissCategoryDrillDown() {
        controlState.update { it.copy(drillDownCategoryId = null) }
    }

    fun selectOverviewMode(mode: OverviewMode) {
        controlState.update { it.copy(selectedOverviewMode = mode) }
    }

    fun selectTrendChartMode(mode: TrendChartMode) {
        controlState.update { it.copy(selectedTrendChartMode = mode) }
    }

    fun selectNetWorthChartMetricFilter(filter: NetWorthChartMetricFilter) {
        controlState.update { it.copy(selectedNetWorthMetricFilter = filter) }
    }

    fun toggleRunwayIncludesInvestments() {
        controlState.update { it.copy(isRunwayIncludingInvestments = !it.isRunwayIncludingInvestments) }
    }

    // Budget CRUD Actions

    fun openCreateBudgetDialog(
        preselectedCategoryId: Long? = null,
        initialPeriodType: BudgetPeriodType = BudgetPeriodType.MONTHLY
    ) {
        val state = uiState.value
        val todayEpochDay = LocalDate.now().toEpochDay()
        val isCustom = initialPeriodType == BudgetPeriodType.CUSTOM
        val isOverall = preselectedCategoryId == null

        if (!isCustom) {
            val matching = if (isOverall) {
                state.overallBudgetProgressList.firstOrNull { it.periodType == initialPeriodType }
            } else {
                state.categoryBudgetProgressList.firstOrNull { it.categoryId == preselectedCategoryId && it.periodType == initialPeriodType }
            }
            if (matching != null) {
                openEditBudgetDialog(matching)
                return
            }
        }

        val selectedNode = if (!isOverall) {
            CategoryDrillDownCalculator.findCategoryNode(state.expenseCategoryTree, preselectedCategoryId)
                ?: state.expenseCategoryTree.firstOrNull()
        } else {
            null
        }

        val start = if (isCustom) todayEpochDay else null
        val end = if (isCustom) todayEpochDay + 13L else null

        controlState.update {
            it.copy(
                budgetEditorDialogState = BudgetEditorDialogState(
                    isEditing = false,
                    budgetId = 0L,
                    isOverallMonthlyBudget = isOverall,
                    selectedCategoryId = selectedNode?.id,
                    selectedCategoryName = selectedNode?.name ?: "Overall Budget",
                    selectedCategoryColorHex = selectedNode?.colorHex ?: "#059669",
                    customName = "",
                    limitAmountInput = "",
                    alertThresholdPercent = 80,
                    periodType = initialPeriodType,
                    startDateEpochDay = start,
                    endDateEpochDay = end,
                    rolloverEnabled = false,
                    rolloverIncludeDeficit = false,
                    validationError = null
                )
            )
        }
    }

    fun openCreateCategoryBudgetDialog() {
        val state = uiState.value
        val budgetedCatIds = state.categoryBudgetProgressList.mapNotNull { it.categoryId }.toSet()
        val firstUnbudgeted = state.expenseCategoryTree.firstOrNull { it.id !in budgetedCatIds }
            ?: state.expenseCategoryTree.firstOrNull()

        controlState.update {
            it.copy(
                budgetEditorDialogState = BudgetEditorDialogState(
                    isEditing = false,
                    budgetId = 0L,
                    isOverallMonthlyBudget = false,
                    selectedCategoryId = firstUnbudgeted?.id,
                    selectedCategoryName = firstUnbudgeted?.name ?: "Select Category",
                    selectedCategoryColorHex = firstUnbudgeted?.colorHex ?: "#059669",
                    customName = "",
                    limitAmountInput = "",
                    alertThresholdPercent = 80,
                    periodType = it.selectedBudgetCadenceFilter ?: BudgetPeriodType.MONTHLY,
                    startDateEpochDay = null,
                    endDateEpochDay = null,
                    rolloverEnabled = false,
                    rolloverIncludeDeficit = false,
                    validationError = null
                )
            )
        }
    }

    fun openEditBudgetDialog(budget: BudgetProgress) {
        val currencySpec = SupportedCurrencies.fromCode(userPreferencesFlow.value.primaryCurrencyCode)
        val formattedLimit = formatMinorPlainForInput(budget.baseLimitAmount, currencySpec.fractionDigits)

        controlState.update {
            it.copy(
                budgetEditorDialogState = BudgetEditorDialogState(
                    isEditing = true,
                    budgetId = budget.budgetId,
                    isOverallMonthlyBudget = budget.categoryId == null,
                    selectedCategoryId = budget.categoryId,
                    selectedCategoryName = budget.categoryName,
                    selectedCategoryColorHex = budget.colorHex,
                    customName = budget.customName,
                    limitAmountInput = formattedLimit,
                    alertThresholdPercent = budget.alertThresholdPercent.coerceIn(50, 100),
                    periodType = budget.periodType,
                    startDateEpochDay = budget.startDateEpochDay,
                    endDateEpochDay = budget.endDateEpochDay,
                    rolloverEnabled = budget.rolloverEnabled,
                    rolloverIncludeDeficit = budget.rolloverIncludeDeficit,
                    validationError = null
                )
            )
        }
    }

    fun updateBudgetEditorState(
        transform: (BudgetEditorDialogState) -> BudgetEditorDialogState
    ) {
        controlState.update { current ->
            val existing = current.budgetEditorDialogState ?: return@update current
            current.copy(budgetEditorDialogState = transform(existing).copy(validationError = null))
        }
    }

    fun dismissBudgetEditorDialog() {
        controlState.update { it.copy(budgetEditorDialogState = null) }
    }

    fun saveBudgetFromDialog() {
        val editor = controlState.value.budgetEditorDialogState ?: return
        val currencySpec = SupportedCurrencies.fromCode(userPreferencesFlow.value.primaryCurrencyCode)

        if (!editor.isOverallMonthlyBudget && editor.selectedCategoryId == null) {
            updateBudgetEditorState {
                it.copy(validationError = "Please select an expense category for this budget.")
            }
            return
        }

        if (editor.periodType == BudgetPeriodType.CUSTOM) {
            if (editor.customName.isBlank()) {
                updateBudgetEditorState {
                    it.copy(validationError = "Please enter a name for your custom budget (e.g. Europe Trip).")
                }
                return
            }
            if (editor.startDateEpochDay == null || editor.endDateEpochDay == null) {
                updateBudgetEditorState {
                    it.copy(validationError = "Please select a date range for your custom budget.")
                }
                return
            }
        }

        val evaluatedLimit = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor(
            expression = editor.limitAmountInput,
            fractionDigits = currencySpec.fractionDigits
        ).getOrNull()

        if (evaluatedLimit == null || !evaluatedLimit.isPositive) {
            updateBudgetEditorState {
                it.copy(validationError = "Enter a valid positive budget limit amount.")
            }
            return
        }

        val targetCategoryId = if (editor.isOverallMonthlyBudget) null else editor.selectedCategoryId

        viewModelScope.launch {
            controlState.update { it.copy(isSavingBudget = true) }
            val result = analyticsAndBudgetRepository.upsertBudget(
                budgetId = editor.budgetId,
                categoryId = targetCategoryId,
                name = editor.customName,
                limitAmount = evaluatedLimit,
                alertThresholdPercent = editor.alertThresholdPercent.coerceIn(50, 100),
                periodType = editor.periodType,
                startDateEpochDay = editor.startDateEpochDay,
                endDateEpochDay = editor.endDateEpochDay,
                rolloverEnabled = editor.rolloverEnabled,
                rolloverIncludeDeficit = editor.rolloverIncludeDeficit
            )
            result.fold(
                onSuccess = {
                    val label = when {
                        editor.customName.isNotBlank() -> editor.customName
                        editor.isOverallMonthlyBudget -> "${editor.periodType.displayLabel} Overall Budget"
                        else -> editor.selectedCategoryName
                    }
                    controlState.update {
                        it.copy(
                            isSavingBudget = false,
                            budgetEditorDialogState = null,
                            snackbarMessage = "Saved budget for $label"
                        )
                    }
                },
                onFailure = { err ->
                    controlState.update { state ->
                        state.copy(
                            isSavingBudget = false,
                            budgetEditorDialogState = state.budgetEditorDialogState?.copy(
                                validationError = err.message ?: "Unable to save budget."
                            )
                        )
                    }
                }
            )
        }
    }

    fun deleteBudget(budgetId: Long) {
        viewModelScope.launch {
            val result = analyticsAndBudgetRepository.deleteBudget(budgetId)
            if (result.isSuccess) {
                controlState.update {
                    it.copy(
                        budgetEditorDialogState = null,
                        snackbarMessage = "Budget removed"
                    )
                }
            } else {
                controlState.update {
                    it.copy(snackbarMessage = result.exceptionOrNull()?.message ?: "Unable to delete budget")
                }
            }
        }
    }

    fun clearSnackbarMessage() {
        controlState.update { it.copy(snackbarMessage = null) }
    }

    private fun computeNetWorthTrend(
        allAccounts: List<AccountSummary>,
        allTransactions: List<TransactionItem>,
        referenceDateEpochDay: Long,
        periodGranularity: StatsPeriodGranularity,
        primaryCurrencyCode: String,
        secondaryCurrencies: List<SecondaryCurrency>,
        todayEpochDay: Long
    ): List<NetWorthGrowthTrendItem> = NetWorthPredictiveCalculator.computeNetWorthTrend(
        allAccounts = allAccounts,
        allTransactions = allTransactions,
        referenceDateEpochDay = referenceDateEpochDay,
        periodGranularity = periodGranularity,
        primaryCurrencyCode = primaryCurrencyCode,
        secondaryCurrencies = secondaryCurrencies,
        todayEpochDay = todayEpochDay
    )

    private fun computeCreditCardHealthList(
        allAccounts: List<AccountSummary>
    ): List<CreditCardHealthUiModel> = CreditCardCockpitCalculator.computeCreditCardHealthList(allAccounts)

    private fun formatMinorPlainForInput(money: MinorMoney, fractionDigits: Int): String {
        if (!money.isPositive) return ""
        val formatted = MoneyFormatterAndCalculator.formatMinorMoney(
            money = money.abs(),
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

    private fun computeCreditCockpitState(
        allAccounts: List<AccountSummary>,
        allTxs: List<TransactionItem>,
        periodTransactions: List<TransactionItem>,
        trendBars: List<MonthlyTrendBar>,
        referenceDateEpochDay: Long,
        milestonesJson: String? = null,
        primaryCurrencyCode: String = "INR",
        secondaryCurrencies: List<com.folioledger.app.domain.model.SecondaryCurrency> = emptyList()
    ): CreditCardCockpitUiState = CreditCardCockpitCalculator.computeCreditCockpitState(
        allAccounts = allAccounts,
        allTxs = allTxs,
        periodTransactions = periodTransactions,
        trendBars = trendBars,
        referenceDateEpochDay = referenceDateEpochDay,
        milestonesJson = milestonesJson,
        primaryCurrencyCode = primaryCurrencyCode,
        secondaryCurrencies = secondaryCurrencies
    )

    fun saveCardMilestoneConfig(
        accountId: Long,
        milestoneTitle: String,
        cycleStartDateEpochDay: Long,
        targetAmountMinor: Long,
        annualFeeSavedMinor: Long?,
        isAutoRenewing: Boolean
    ) {
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                val updatedJson = CardMilestoneCalculator.upsertMilestone(
                    currentJson = current.cardMilestonesJson,
                    config = CardMilestoneConfig(
                        accountId = accountId,
                        milestoneTitle = milestoneTitle.ifBlank { "Annual Fee Waiver" },
                        cycleStartDateEpochDay = cycleStartDateEpochDay,
                        targetAmountMinor = targetAmountMinor,
                        annualFeeSavedMinor = annualFeeSavedMinor,
                        isAutoRenewing = isAutoRenewing
                    )
                )
                current.copy(cardMilestonesJson = updatedJson)
            }
        }
    }

    fun openMonthlySavingsTargetDialog() {
        val currentTarget = uiState.value.monthlySavingsProgress.targetAmount
        val currentInput = if (currentTarget.isPositive) {
            formatMinorPlainForInput(
                currentTarget,
                uiState.value.currencySpec.fractionDigits
            )
        } else ""
        controlState.update {
            it.copy(
                isMonthlySavingsTargetDialogOpen = true,
                monthlySavingsTargetInput = currentInput
            )
        }
    }

    fun closeMonthlySavingsTargetDialog() {
        controlState.update { it.copy(isMonthlySavingsTargetDialogOpen = false) }
    }

    fun updateMonthlySavingsTargetInput(input: String) {
        controlState.update { it.copy(monthlySavingsTargetInput = input) }
    }

    fun saveMonthlySavingsTarget() {
        val input = controlState.value.monthlySavingsTargetInput.trim()
        val parsedMinor = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor(
            expression = input,
            fractionDigits = uiState.value.currencySpec.fractionDigits
        ).getOrNull()?.amountMinor ?: 0L
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(monthlySavingsTargetMinor = parsedMinor)
            }
            controlState.update { it.copy(isMonthlySavingsTargetDialogOpen = false) }
        }
    }

    fun removeMonthlySavingsTarget() {
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(monthlySavingsTargetMinor = 0L)
            }
            controlState.update { it.copy(isMonthlySavingsTargetDialogOpen = false) }
        }
    }

    fun openAddSavingsGoalSheet(defaultAccountId: Long? = null) {
        val availableAccount = uiState.value.savingsGoalEligibleAccounts.firstOrNull {
            it.groupType in setOf(AccountGroup.SAVINGS, AccountGroup.INVESTMENT)
        } ?: uiState.value.savingsGoalEligibleAccounts.firstOrNull()

        controlState.update {
            it.copy(
                isSavingsGoalSheetOpen = true,
                editingSavingsGoalAccountId = defaultAccountId ?: availableAccount?.id,
                editingSavingsGoalTitle = "Savings Goal",
                editingSavingsGoalTargetAmountInput = "",
                editingSavingsGoalTargetDateEpochDay = null
            )
        }
    }

    fun openEditSavingsGoalSheet(goal: AccountSavingsGoal) {
        val goalCurrencySpec = SupportedCurrencies.fromCode(goal.currencyCode)
        val targetInput = formatMinorPlainForInput(
            goal.targetAmount,
            goalCurrencySpec.fractionDigits
        )
        controlState.update {
            it.copy(
                isSavingsGoalSheetOpen = true,
                editingSavingsGoalAccountId = goal.accountId,
                editingSavingsGoalTitle = goal.goalTitle,
                editingSavingsGoalTargetAmountInput = targetInput,
                editingSavingsGoalTargetDateEpochDay = goal.targetDateEpochDay
            )
        }
    }

    fun closeSavingsGoalSheet() {
        controlState.update { it.copy(isSavingsGoalSheetOpen = false) }
    }

    fun updateEditingSavingsGoal(
        accountId: Long?,
        title: String,
        targetAmountInput: String,
        targetDateEpochDay: Long?
    ) {
        controlState.update {
            it.copy(
                editingSavingsGoalAccountId = accountId,
                editingSavingsGoalTitle = title,
                editingSavingsGoalTargetAmountInput = targetAmountInput,
                editingSavingsGoalTargetDateEpochDay = targetDateEpochDay
            )
        }
    }

    fun saveSavingsGoal() {
        val ctrl = controlState.value
        val accountId = ctrl.editingSavingsGoalAccountId ?: return
        val targetAccount = uiState.value.savingsGoalEligibleAccounts.firstOrNull { it.id == accountId }
        val accountSpec = SupportedCurrencies.fromCode(
            targetAccount?.currencyCode ?: uiState.value.currencySpec.code
        )
        val targetMinor = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor(
            expression = ctrl.editingSavingsGoalTargetAmountInput,
            fractionDigits = accountSpec.fractionDigits
        ).getOrNull()?.amountMinor ?: 0L
        if (targetMinor <= 0L) return

        val config = AccountSavingsGoalConfig(
            accountId = accountId,
            goalTitle = ctrl.editingSavingsGoalTitle.ifBlank { "Savings Goal" },
            targetAmountMinor = targetMinor,
            targetDateEpochDay = ctrl.editingSavingsGoalTargetDateEpochDay
        )

        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                val updatedJson = SavingsGoalCalculator.upsertGoal(current.savingsGoalsJson, config)
                current.copy(savingsGoalsJson = updatedJson)
            }
            controlState.update { it.copy(isSavingsGoalSheetOpen = false) }
        }
    }

    fun removeSavingsGoal(accountId: Long) {
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                val updatedJson = SavingsGoalCalculator.removeGoal(current.savingsGoalsJson, accountId)
                current.copy(savingsGoalsJson = updatedJson)
            }
            controlState.update { it.copy(isSavingsGoalSheetOpen = false) }
        }
    }
}

