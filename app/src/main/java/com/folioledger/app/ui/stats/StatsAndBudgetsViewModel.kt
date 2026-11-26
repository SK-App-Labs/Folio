/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

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
import com.folioledger.app.domain.model.UNCATEGORIZED_CATEGORY_ID
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.domain.repository.AccountRepository
import com.folioledger.app.domain.repository.AnalyticsAndBudgetRepository
import com.folioledger.app.domain.repository.CategoryAndTagRepository
import com.folioledger.app.domain.repository.SettingsAndBackupRepository
import com.folioledger.app.domain.repository.TransactionRepository
import com.folioledger.app.domain.util.CardMilestoneCalculator
import com.folioledger.app.domain.util.CategoryDrillDownCalculator
import com.folioledger.app.domain.util.CreditCardCockpitCalculator
import com.folioledger.app.domain.util.LedgerBucketingCalculator
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import com.folioledger.app.domain.util.NetWorthPredictiveCalculator
import com.folioledger.app.domain.util.SavingsGoalCalculator
import com.folioledger.app.domain.util.StatsPeriodCalculator
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.CancellationException
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
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default
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

    private val earliestNonRenewingMilestoneStartFlow: Flow<Long?> = userPreferencesFlow
        .map { prefs ->
            CardMilestoneCalculator.parseMilestones(prefs.cardMilestonesJson)
                .values
                .filter { !it.isAutoRenewing }
                .minOfOrNull { it.cycleStartDateEpochDay }
        }
        .distinctUntilChanged()

    private val netWorthWindowTxsFlow = combine(
        netWorthQueryParamsFlow,
        todayEpochDayFlow,
        earliestNonRenewingMilestoneStartFlow
    ) { key, todayEpochDay, earliestNonRenewingStart ->
        Triple(key, todayEpochDay, earliestNonRenewingStart)
    }
        .distinctUntilChanged()
        .flatMapLatest { (key, todayEpochDay, earliestNonRenewingStart) ->
            val refDate = LocalDate.ofEpochDay(key.referenceDateEpochDay)
            val todayDate = LocalDate.ofEpochDay(todayEpochDay)
            val windowStartEpochDay = if (key.requiresAllTimeHistory) {
                Long.MIN_VALUE
            } else {
                // Guarantee at least:
                // 1) 12 months prior to today for rolling 1-year Credit Card Milestones & YTD spend
                // 2) Earliest fixed non-auto-renewing milestone start if older
                // 3) 7 months prior to refDate (and Jan 1 of refDate.year - 6 months) for trend bars
                val oneYearBeforeToday = todayDate.minusYears(1).withDayOfMonth(1).toEpochDay()
                val sevenMonthsBeforeRef = refDate.minusMonths(7).withDayOfMonth(1).toEpochDay()
                val annualStart = LocalDate.of(refDate.year, 1, 1).minusMonths(6).toEpochDay()
                val baseStart = minOf(oneYearBeforeToday, sevenMonthsBeforeRef, annualStart)
                if (earliestNonRenewingStart != null) minOf(baseStart, earliestNonRenewingStart) else baseStart
            }
            val windowEndEpochDay = if (key.requiresAllTimeHistory) {
                Long.MAX_VALUE
            } else {
                maxOf(todayEpochDay + 14L, refDate.plusMonths(13).toEpochDay())
            }
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
            val isCustom = ctrl.periodGranularity == StatsPeriodGranularity.CUSTOM
            PeriodQueryParametersKey(
                granularity = ctrl.periodGranularity,
                referenceDateEpochDay = ctrl.referenceDateEpochDay,
                customStartEpochDay = if (isCustom) ctrl.customStartEpochDay else 0L,
                customEndEpochDay = if (isCustom) ctrl.customEndEpochDay else 0L
            )
        }.distinctUntilChanged(),
        userPreferencesFlow.map { prefs ->
            Triple(prefs.monthStartDay, prefs.weekStartDayIso, prefs.carryOverEnabled)
        }.distinctUntilChanged()
    ) { key, (monthStartDay, weekStartDayIso, carryOverEnabled) ->
        PeriodQueryParameters(
            granularity = key.granularity,
            referenceDateEpochDay = key.referenceDateEpochDay,
            customStartEpochDay = key.customStartEpochDay,
            customEndEpochDay = key.customEndEpochDay,
            monthStartDay = monthStartDay,
            weekStartDayIso = weekStartDayIso,
            carryOverEnabled = carryOverEnabled
        )
    }.distinctUntilChanged()

    private val periodAnalyticsFlow: Flow<PeriodAnalyticsBundle> = periodQueryParametersFlow.flatMapLatest { params ->
        val periodRange = StatsPeriodCalculator.computePeriodRange(
            granularity = params.granularity,
            referenceDateEpochDay = params.referenceDateEpochDay,
            monthStartDay = params.monthStartDay,
            weekStartDayIso = params.weekStartDayIso,
            customStartEpochDay = params.customStartEpochDay,
            customEndEpochDay = params.customEndEpochDay
        )

        val priorPeriodRange = when (params.granularity) {
            StatsPeriodGranularity.WEEKLY -> StatsPeriodCalculator.computeWeeklyRange(
                referenceDateEpochDay = LocalDate.ofEpochDay(periodRange.startEpochDay).minusDays(1).toEpochDay(),
                weekStartDayIso = params.weekStartDayIso
            )
            StatsPeriodGranularity.MONTHLY -> StatsPeriodCalculator.computeFinancialMonthRange(
                referenceDateEpochDay = LocalDate.ofEpochDay(periodRange.startEpochDay).minusDays(1).toEpochDay(),
                monthStartDay = params.monthStartDay
            )
            StatsPeriodGranularity.ANNUALLY -> StatsPeriodCalculator.computeAnnualRange(
                referenceDateEpochDay = LocalDate.ofEpochDay(periodRange.startEpochDay).minusDays(1).toEpochDay(),
                monthStartDay = params.monthStartDay
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
                includeCarryOver = params.carryOverEnabled &&
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
                monthStartDay = params.monthStartDay.coerceIn(1, 31)
            ),
            todayEpochDayFlow.flatMapLatest { todayEpochDay ->
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
                val rankingsStartDay = if (isAllTime) Long.MIN_VALUE else periodRange.startEpochDay
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
        val prefs: UserPreferences,
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
        val monthlySavingsPeriodTitle: String,
        val accountSavingsGoals: List<AccountSavingsGoal>,
        val payeesByFrequency: List<SpendRankingItem>,
        val todayEpochDay: Long,
        val errorMessage: String? = null
    )

    private val coreAnalyticsFlow: Flow<StatsComputedCore> = combine(
        periodAnalyticsFlow,
        categoryTreesFlow,
        allAccountsFlow,
        netWorthWindowTxsFlow,
        combine(
            userPreferencesFlow,
            controlState.map { it.referenceDateEpochDay to it.periodGranularity }.distinctUntilChanged(),
            todayEpochDayFlow
        ) { prefs, refAndGran, todayDay -> Triple(prefs, refAndGran, todayDay) }
    ) { bundle, (expenseTree, incomeTree), allAccounts, allTxs, (prefs, refAndGran, todayEpochDay) ->
        val (refEpochDay, granularity) = refAndGran
        val currencySpec = SupportedCurrencies.fromCode(prefs.primaryCurrencyCode)

        try {
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

            // Anchor Monthly Wealth Savings to the financial month even when viewing Weekly/Annual budgets
            val savingsMonthRange = if (granularity == StatsPeriodGranularity.MONTHLY) {
                bundle.periodRange
            } else {
                StatsPeriodCalculator.computeFinancialMonthRange(
                    referenceDateEpochDay = refEpochDay,
                    monthStartDay = prefs.monthStartDay
                )
            }
            val (savingsTxs, savingsSummary) = if (granularity == StatsPeriodGranularity.MONTHLY) {
                bundle.periodTransactions to bundle.periodSummary
            } else {
                val monthTxs = allTxs.filter {
                    it.ledgerDateEpochDay in savingsMonthRange.startEpochDay..savingsMonthRange.endEpochDay
                }
                val (monthIncome, monthExpense) = LedgerBucketingCalculator.computeIncomeAndExpenseTotals(
                    transactions = monthTxs,
                    primaryCurrencySpec = currencySpec,
                    secondaryCurrencies = prefs.secondaryCurrencies
                )
                val monthSummary = PeriodLedgerSummary(
                    startEpochDay = savingsMonthRange.startEpochDay,
                    endEpochDay = savingsMonthRange.endEpochDay,
                    totalIncome = monthIncome,
                    totalExpense = monthExpense,
                    netCashflow = MinorMoney(
                        MoneyFormatterAndCalculator.safeSubtract(monthIncome.amountMinor, monthExpense.amountMinor)
                    ),
                    carryOverFromPreviousPeriod = MinorMoney.ZERO
                )
                monthTxs to monthSummary
            }

            val monthlySavingsProgress = SavingsGoalCalculator.computeMonthlySavingsProgress(
                allAccounts = allAccounts,
                periodTransactions = savingsTxs,
                periodSummary = savingsSummary,
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
                prefs = prefs,
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
                monthlySavingsPeriodTitle = savingsMonthRange.title,
                accountSavingsGoals = accountSavingsGoals,
                payeesByFrequency = payeesByFrequency,
                todayEpochDay = todayEpochDay
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StatsComputedCore(
                bundle = bundle,
                prefs = prefs,
                expenseTree = expenseTree,
                incomeTree = incomeTree,
                allAccounts = allAccounts,
                allTxs = allTxs,
                currencySpec = currencySpec,
                netWorthTrend = emptyList(),
                netWorthVelocity = NetWorthVelocityState(),
                emergencyRunwayLiquid = EmergencyRunwayState(),
                emergencyRunwayWithInvestments = EmergencyRunwayState(),
                creditHealthList = emptyList(),
                assetAccountsForSettlement = emptyList(),
                savingsGoalEligibleAccounts = emptyList(),
                creditCockpitState = CreditCardCockpitUiState(),
                overallBudget = null,
                overallBudgets = emptyList(),
                categoryBudgets = emptyList(),
                customBudgets = emptyList(),
                safeToSpendState = SafeToSpendState(),
                shortfallAlerts = emptyList(),
                monthlySavingsProgress = MonthlySavingsProgress(
                    targetAmount = MinorMoney(prefs.monthlySavingsTargetMinor),
                    savedAmount = MinorMoney.ZERO,
                    remainingAmount = MinorMoney(prefs.monthlySavingsTargetMinor),
                    progressPercentage = 0f,
                    unallocatedSurplus = MinorMoney.ZERO,
                    isConfigured = prefs.monthlySavingsTargetMinor > 0L
                ),
                monthlySavingsPeriodTitle = bundle.periodRange.title,
                accountSavingsGoals = emptyList(),
                payeesByFrequency = emptyList(),
                todayEpochDay = todayEpochDay,
                errorMessage = "Analytics calculation error: ${e.localizedMessage ?: "Calculation error"}"
            )
        }
    }.flowOn(computationDispatcher)

    val uiState: StateFlow<StatsAndBudgetsUiState> = combine(
        controlState,
        coreAnalyticsFlow
    ) { ctrl, core ->
        val bundle = core.bundle
        val prefs = core.prefs

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

        var drillDownError: String? = null
        val drillDownState = ctrl.drillDownCategoryId?.let { targetCatId ->
            runCatching {
                val effectiveCategoryType = ctrl.drillDownCategoryType ?: ctrl.selectedCategoryType
                val effectiveStats = if (effectiveCategoryType == CategoryType.EXPENSE) {
                    bundle.expenseStats
                } else {
                    bundle.incomeStats
                }
                val effectiveTree = if (effectiveCategoryType == CategoryType.EXPENSE) {
                    core.expenseTree
                } else {
                    core.incomeTree
                }
                val stat = effectiveStats.firstOrNull { it.categoryId == targetCatId }

                if (stat != null) {
                    val matchingCategoryIds = CategoryDrillDownCalculator.collectCategoryAndSubcategoryIds(
                        tree = effectiveTree,
                        targetCategoryId = targetCatId
                    )
                    val matchingTxns = CategoryDrillDownCalculator.filterMatchingTransactions(
                        transactions = bundle.periodTransactions,
                        categoryType = effectiveCategoryType,
                        categoryName = stat.categoryName,
                        matchingCategoryIds = matchingCategoryIds
                    )

                    val matchingBudget = if (effectiveCategoryType == CategoryType.EXPENSE) {
                        core.categoryBudgets.firstOrNull { it.categoryId == targetCatId }
                    } else {
                        null
                    }

                    val categoryTrendBars = CategoryDrillDownCalculator.computeCategoryHistoricalTrend(
                        trendBars = bundle.trendBars,
                        allTransactions = core.allTxs,
                        categoryType = effectiveCategoryType,
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
                        categoryType = effectiveCategoryType,
                        transactions = matchingTxns,
                        matchingBudget = matchingBudget,
                        categorySixMonthTrendBars = categoryTrendBars,
                        subcategoryBreakdown = subcategoryBreakdown
                    )
                } else {
                    null
                }
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                drillDownError = drillDownError ?: "Drill-down calculation error: ${e.localizedMessage ?: "Calculation error"}"
                null
            }
        }

        val sortedPayees = if (ctrl.selectedPayeeRankingSortMode == PayeeSortMode.BY_FREQUENCY) {
            core.payeesByFrequency
        } else {
            bundle.payeeRankings
        }

        val payeeDrillDownState = ctrl.drillDownPayeeName?.let { targetPayee ->
            runCatching {
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
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                drillDownError = drillDownError ?: "Drill-down calculation error: ${e.localizedMessage ?: "Calculation error"}"
                null
            }
        }

        val tagDrillDownState = ctrl.drillDownTagName?.let { targetTag ->
            runCatching {
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
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                drillDownError = drillDownError ?: "Drill-down calculation error: ${e.localizedMessage ?: "Calculation error"}"
                null
            }
        }

        val emergencyRunway = if (ctrl.isRunwayIncludingInvestments) {
            core.emergencyRunwayWithInvestments
        } else {
            core.emergencyRunwayLiquid
        }

        val availableCadences = bundle.budgets.map { it.periodType }.toSet()
        val effectiveCadenceFilter = ctrl.selectedBudgetCadenceFilter?.takeIf {
            availableCadences.size > 1 && it in availableCadences
        }

        StatsAndBudgetsUiState(
            selectedSubTab = ctrl.selectedSubTab,
            selectedOverviewMode = ctrl.selectedOverviewMode,
            periodGranularity = ctrl.periodGranularity,
            selectedCategoryType = ctrl.selectedCategoryType,
            selectedRankingMode = ctrl.selectedRankingMode,
            isRankingsAllTime = ctrl.isRankingsAllTime,
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
            selectedBudgetCadenceFilter = effectiveCadenceFilter,
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
            monthlySavingsPeriodTitle = core.monthlySavingsPeriodTitle,
            accountSavingsGoals = core.accountSavingsGoals,
            isMonthlySavingsTargetDialogOpen = ctrl.isMonthlySavingsTargetDialogOpen,
            monthlySavingsTargetInput = ctrl.monthlySavingsTargetInput,
            monthlySavingsTargetValidationError = ctrl.monthlySavingsTargetValidationError,
            isSavingsGoalSheetOpen = ctrl.isSavingsGoalSheetOpen,
            isEditingSavingsGoal = ctrl.isEditingSavingsGoal,
            editingSavingsGoalAccountId = ctrl.editingSavingsGoalAccountId,
            editingSavingsGoalTitle = ctrl.editingSavingsGoalTitle,
            editingSavingsGoalTargetAmountInput = ctrl.editingSavingsGoalTargetAmountInput,
            editingSavingsGoalTargetDateEpochDay = ctrl.editingSavingsGoalTargetDateEpochDay,
            savingsGoalValidationError = ctrl.savingsGoalValidationError,
            snackbarMessage = ctrl.snackbarMessage ?: core.errorMessage ?: drillDownError
        )
    }.catch { throwable ->
        if (throwable is CancellationException) throw throwable
        val ctrl = controlState.value
        emit(
            StatsAndBudgetsUiState(
                selectedSubTab = ctrl.selectedSubTab,
                selectedOverviewMode = ctrl.selectedOverviewMode,
                periodGranularity = ctrl.periodGranularity,
                selectedCategoryType = ctrl.selectedCategoryType,
                referenceDateEpochDay = ctrl.referenceDateEpochDay,
                customStartEpochDay = ctrl.customStartEpochDay,
                customEndEpochDay = ctrl.customEndEpochDay,
                snackbarMessage = "Analytics error: ${throwable.localizedMessage ?: "Unexpected error"}"
            )
        )
    }.flowOn(computationDispatcher)
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
                drillDownCategoryId = null,
                drillDownCategoryType = null
            )
        }
    }

    fun openCustomDateRangePicker() {
        val prefs = userPreferencesFlow.value
        controlState.update { current ->
            val currentRange = if (current.periodGranularity != StatsPeriodGranularity.CUSTOM) {
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
                customStartEpochDay = currentRange?.startEpochDay ?: current.customStartEpochDay,
                customEndEpochDay = currentRange?.endEpochDay ?: current.customEndEpochDay,
                isCustomDatePickerOpen = true
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
                drillDownCategoryId = null,
                drillDownCategoryType = null
            )
        }
    }

    fun selectCategoryType(type: CategoryType) {
        controlState.update {
            it.copy(
                selectedCategoryType = type,
                highlightedDonutCategoryId = null,
                drillDownCategoryId = null,
                drillDownCategoryType = null
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
                drillDownCategoryId = null,
                drillDownCategoryType = null
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
                drillDownCategoryId = null,
                drillDownCategoryType = null
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
                    drillDownCategoryId = null,
                    drillDownCategoryType = null
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
                drillDownCategoryType = null,
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

    fun openCategoryDrillDown(
        stat: CategorySpendStat,
        categoryType: CategoryType = controlState.value.selectedCategoryType
    ) {
        controlState.update {
            it.copy(
                highlightedDonutCategoryId = stat.categoryId,
                drillDownCategoryId = stat.categoryId,
                drillDownCategoryType = categoryType
            )
        }
    }

    fun dismissCategoryDrillDown() {
        controlState.update {
            it.copy(
                drillDownCategoryId = null,
                drillDownCategoryType = null
            )
        }
    }

    fun selectOverviewMode(mode: OverviewMode) {
        controlState.update { it.copy(selectedOverviewMode = mode) }
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
        val isOverall = preselectedCategoryId == null || preselectedCategoryId == UNCATEGORIZED_CATEGORY_ID
        val targetCategoryId = if (isOverall) null else preselectedCategoryId

        if (!isCustom) {
            val matching = if (targetCategoryId == null) {
                state.overallBudgetProgressList.firstOrNull { it.periodType == initialPeriodType }
            } else {
                state.categoryBudgetProgressList.firstOrNull { it.categoryId == targetCategoryId && it.periodType == initialPeriodType }
            }
            if (matching != null) {
                openEditBudgetDialog(matching)
                return
            }
        }

        val selectedNode = if (!isOverall) {
            CategoryDrillDownCalculator.findCategoryNode(state.expenseCategoryTree, targetCategoryId)
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
        val targetCadence = state.selectedBudgetCadenceFilter ?: BudgetPeriodType.MONTHLY
        val budgetedCatIds = state.categoryBudgetProgressList
            .filter { it.periodType == targetCadence }
            .mapNotNull { it.categoryId }
            .toSet()
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
                    periodType = targetCadence,
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

    private fun setBudgetEditorValidationError(message: String) {
        controlState.update { current ->
            val existing = current.budgetEditorDialogState ?: return@update current
            current.copy(budgetEditorDialogState = existing.copy(validationError = message))
        }
    }

    fun dismissBudgetEditorDialog() {
        controlState.update { it.copy(budgetEditorDialogState = null) }
    }

    fun saveBudgetFromDialog(limitInput: String? = null, customName: String? = null) {
        if (controlState.value.isSavingBudget) return
        val baseEditor = controlState.value.budgetEditorDialogState ?: return
        val editor = baseEditor.copy(
            limitAmountInput = limitInput ?: baseEditor.limitAmountInput,
            customName = customName ?: baseEditor.customName
        )
        val currencySpec = SupportedCurrencies.fromCode(userPreferencesFlow.value.primaryCurrencyCode)

        if (!editor.isOverallMonthlyBudget && editor.selectedCategoryId == null) {
            setBudgetEditorValidationError("Please select an expense category for this budget.")
            return
        }

        if (editor.periodType == BudgetPeriodType.CUSTOM) {
            if (editor.customName.isBlank()) {
                setBudgetEditorValidationError("Please enter a name for your custom budget (e.g. Europe Trip).")
                return
            }
            if (editor.startDateEpochDay == null || editor.endDateEpochDay == null) {
                setBudgetEditorValidationError("Please select a date range for your custom budget.")
                return
            }
        }

        val evaluatedLimit = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor(
            expression = editor.limitAmountInput,
            fractionDigits = currencySpec.fractionDigits
        ).getOrNull()

        if (evaluatedLimit == null || !evaluatedLimit.isPositive) {
            setBudgetEditorValidationError("Enter a valid positive budget limit amount.")
            return
        }

        val targetCategoryId = if (editor.isOverallMonthlyBudget) null else editor.selectedCategoryId
        if (editor.periodType != BudgetPeriodType.CUSTOM) {
            val duplicate = uiState.value.budgetProgressList.firstOrNull { existing ->
                existing.periodType == editor.periodType &&
                    existing.categoryId == targetCategoryId &&
                    existing.budgetId != editor.budgetId
            }
            if (duplicate != null) {
                setBudgetEditorValidationError(
                    "A ${editor.periodType.displayLabel.lowercase(Locale.ROOT)} budget for ${duplicate.displayTitle} already exists. Edit it instead."
                )
                return
            }
        }

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
                onSuccess = { savedId ->
                    val label = when {
                        editor.customName.isNotBlank() -> editor.customName
                        editor.isOverallMonthlyBudget -> "${editor.periodType.displayLabel} Overall Budget"
                        else -> editor.selectedCategoryName
                    }
                    controlState.update { current ->
                        val updatedCadences = uiState.value.budgetProgressList
                            .filter { it.budgetId != editor.budgetId && it.budgetId != savedId }
                            .map { it.periodType }
                            .toSet() + editor.periodType
                        val shouldResetFilter = current.selectedBudgetCadenceFilter != null &&
                            (current.selectedBudgetCadenceFilter !in updatedCadences || updatedCadences.size <= 1)

                        current.copy(
                            isSavingBudget = false,
                            budgetEditorDialogState = null,
                            snackbarMessage = "Saved budget for $label",
                            selectedBudgetCadenceFilter = if (shouldResetFilter) null else current.selectedBudgetCadenceFilter,
                            periodGranularity = if (shouldResetFilter) StatsPeriodGranularity.MONTHLY else current.periodGranularity
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
        if (controlState.value.isSavingBudget) return
        viewModelScope.launch {
            controlState.update { it.copy(isSavingBudget = true) }
            val result = analyticsAndBudgetRepository.deleteBudget(budgetId)
            if (result.isSuccess) {
                controlState.update { current ->
                    val remainingBudgets = uiState.value.budgetProgressList.filter { it.budgetId != budgetId }
                    val remainingCadences = remainingBudgets.map { it.periodType }.toSet()
                    val shouldResetFilter = current.selectedBudgetCadenceFilter != null &&
                            (current.selectedBudgetCadenceFilter !in remainingCadences || remainingCadences.size <= 1)

                    current.copy(
                        isSavingBudget = false,
                        budgetEditorDialogState = null,
                        snackbarMessage = "Budget removed",
                        selectedBudgetCadenceFilter = if (shouldResetFilter) null else current.selectedBudgetCadenceFilter,
                        periodGranularity = if (shouldResetFilter) StatsPeriodGranularity.MONTHLY else current.periodGranularity
                    )
                }
            } else {
                controlState.update {
                    it.copy(
                        isSavingBudget = false,
                        snackbarMessage = result.exceptionOrNull()?.message ?: "Unable to delete budget"
                    )
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
                monthlySavingsTargetInput = currentInput,
                monthlySavingsTargetValidationError = null
            )
        }
    }

    fun closeMonthlySavingsTargetDialog() {
        controlState.update {
            it.copy(
                isMonthlySavingsTargetDialogOpen = false,
                monthlySavingsTargetValidationError = null
            )
        }
    }

    fun updateMonthlySavingsTargetInput(input: String) {
        controlState.update {
            it.copy(
                monthlySavingsTargetInput = input,
                monthlySavingsTargetValidationError = null
            )
        }
    }

    fun clearMonthlySavingsTargetValidationError() {
        controlState.update { it.copy(monthlySavingsTargetValidationError = null) }
    }

    fun saveMonthlySavingsTarget(targetInput: String? = null) {
        val input = (targetInput ?: controlState.value.monthlySavingsTargetInput).trim()
        val parsed = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor(
            expression = input,
            fractionDigits = uiState.value.currencySpec.fractionDigits
        ).getOrNull()
        if (parsed == null || !parsed.isPositive) {
            controlState.update {
                it.copy(monthlySavingsTargetValidationError = "Enter a valid positive monthly target amount.")
            }
            return
        }
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(monthlySavingsTargetMinor = parsed.amountMinor)
            }
            controlState.update {
                it.copy(
                    isMonthlySavingsTargetDialogOpen = false,
                    monthlySavingsTargetValidationError = null
                )
            }
        }
    }

    fun removeMonthlySavingsTarget() {
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                current.copy(monthlySavingsTargetMinor = 0L)
            }
            controlState.update {
                it.copy(
                    isMonthlySavingsTargetDialogOpen = false,
                    monthlySavingsTargetValidationError = null
                )
            }
        }
    }

    fun openAddSavingsGoalSheet(defaultAccountId: Long? = null) {
        val state = uiState.value
        val existingGoalAccountIds = state.accountSavingsGoals.map { it.accountId }.toSet()
        val ungoaledAccounts = state.savingsGoalEligibleAccounts.filter { it.id !in existingGoalAccountIds }
        val preferredPool = ungoaledAccounts.ifEmpty { state.savingsGoalEligibleAccounts }
        val availableAccount = preferredPool.firstOrNull {
            it.groupType in setOf(AccountGroup.SAVINGS, AccountGroup.INVESTMENT)
        } ?: preferredPool.firstOrNull()
        val chosenAccountId = defaultAccountId ?: availableAccount?.id
        val existingGoal = chosenAccountId?.let { id ->
            state.accountSavingsGoals.firstOrNull { it.accountId == id }
        }
        if (existingGoal != null) {
            openEditSavingsGoalSheet(existingGoal)
            return
        }

        controlState.update {
            it.copy(
                isSavingsGoalSheetOpen = true,
                isEditingSavingsGoal = false,
                editingSavingsGoalAccountId = chosenAccountId,
                editingSavingsGoalTitle = "Savings Goal",
                editingSavingsGoalTargetAmountInput = "",
                editingSavingsGoalTargetDateEpochDay = null,
                savingsGoalValidationError = null
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
                isEditingSavingsGoal = true,
                editingSavingsGoalAccountId = goal.accountId,
                editingSavingsGoalTitle = goal.goalTitle,
                editingSavingsGoalTargetAmountInput = targetInput,
                editingSavingsGoalTargetDateEpochDay = goal.targetDateEpochDay,
                savingsGoalValidationError = null
            )
        }
    }

    fun closeSavingsGoalSheet() {
        controlState.update {
            it.copy(
                isSavingsGoalSheetOpen = false,
                isEditingSavingsGoal = false,
                savingsGoalValidationError = null
            )
        }
    }

    fun clearSavingsGoalValidationError() {
        controlState.update { it.copy(savingsGoalValidationError = null) }
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
                editingSavingsGoalTargetDateEpochDay = targetDateEpochDay,
                savingsGoalValidationError = null
            )
        }
    }

    fun saveSavingsGoal(
        accountId: Long? = null,
        title: String? = null,
        targetAmountInput: String? = null,
        targetDateEpochDay: Long? = null
    ) {
        val ctrl = controlState.value
        val effectiveAccountId = accountId ?: ctrl.editingSavingsGoalAccountId ?: return
        val alreadyHasGoal = uiState.value.accountSavingsGoals.any { it.accountId == effectiveAccountId }
        if (!ctrl.isEditingSavingsGoal && alreadyHasGoal) {
            controlState.update {
                it.copy(
                    savingsGoalValidationError = "This account already has a savings goal. Edit it from the list instead."
                )
            }
            return
        }
        val effectiveTitle = title ?: ctrl.editingSavingsGoalTitle
        val effectiveTargetInput = targetAmountInput ?: ctrl.editingSavingsGoalTargetAmountInput
        val effectiveTargetDate = targetDateEpochDay ?: ctrl.editingSavingsGoalTargetDateEpochDay

        val targetAccount = uiState.value.savingsGoalEligibleAccounts.firstOrNull { it.id == effectiveAccountId }
        val accountSpec = SupportedCurrencies.fromCode(
            targetAccount?.currencyCode ?: uiState.value.currencySpec.code
        )
        val targetMinor = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor(
            expression = effectiveTargetInput,
            fractionDigits = accountSpec.fractionDigits
        ).getOrNull()?.amountMinor ?: 0L
        if (targetMinor <= 0L) {
            controlState.update {
                it.copy(savingsGoalValidationError = "Enter a valid positive savings goal target amount.")
            }
            return
        }

        val config = AccountSavingsGoalConfig(
            accountId = effectiveAccountId,
            goalTitle = effectiveTitle.ifBlank { "Savings Goal" },
            targetAmountMinor = targetMinor,
            targetDateEpochDay = effectiveTargetDate
        )

        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                val updatedJson = SavingsGoalCalculator.upsertGoal(current.savingsGoalsJson, config)
                current.copy(savingsGoalsJson = updatedJson)
            }
            controlState.update {
                it.copy(
                    isSavingsGoalSheetOpen = false,
                    isEditingSavingsGoal = false,
                    savingsGoalValidationError = null
                )
            }
        }
    }

    fun removeSavingsGoal(accountId: Long) {
        viewModelScope.launch {
            settingsAndBackupRepository.updateUserPreferences { current ->
                val updatedJson = SavingsGoalCalculator.removeGoal(current.savingsGoalsJson, accountId)
                current.copy(savingsGoalsJson = updatedJson)
            }
            controlState.update {
                it.copy(
                    isSavingsGoalSheetOpen = false,
                    isEditingSavingsGoal = false,
                    savingsGoalValidationError = null
                )
            }
        }
    }
}

