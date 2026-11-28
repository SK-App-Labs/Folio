/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.stats

import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSavingsGoalConfig
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.BudgetPeriodType
import com.folioledger.app.domain.model.BudgetProgress
import com.folioledger.app.domain.model.CategorySpendStat
import com.folioledger.app.domain.model.CategoryType
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NetWorthOverview
import com.folioledger.app.domain.model.PeriodLedgerSummary
import com.folioledger.app.domain.model.StatsPeriodGranularity
import com.folioledger.app.domain.model.UNCATEGORIZED_CATEGORY_ID
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.domain.repository.AccountRepository
import com.folioledger.app.domain.repository.AnalyticsAndBudgetRepository
import com.folioledger.app.domain.repository.CategoryAndTagRepository
import com.folioledger.app.domain.repository.SettingsAndBackupRepository
import com.folioledger.app.domain.repository.TransactionRepository
import com.folioledger.app.domain.util.SavingsGoalCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class StatsAndBudgetsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private inline fun <reified T : Any> createStub(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, args ->
        val name = method.name
        when {
            method.returnType == kotlinx.coroutines.flow.Flow::class.java -> {
                when {
                    name.startsWith("observeUserPreferences") -> flowOf(UserPreferences())

                    name.startsWith("observePeriodLedgerSummary") -> flowOf(
                        PeriodLedgerSummary(
                            startEpochDay = 0L,
                            endEpochDay = 0L,
                            totalIncome = MinorMoney.ZERO,
                            totalExpense = MinorMoney.ZERO,
                            netCashflow = MinorMoney.ZERO,
                            carryOverFromPreviousPeriod = MinorMoney.ZERO,
                        ),
                    )

                    name.startsWith("observeNetWorthOverview") -> flowOf(
                        NetWorthOverview(
                            totalAssets = MinorMoney.ZERO,
                            totalLiabilities = MinorMoney.ZERO,
                            netWorth = MinorMoney.ZERO,
                            accountsByGroup = emptyMap<AccountGroup, List<AccountSummary>>(),
                        ),
                    )

                    else -> flowOf(emptyList<Any>())
                }
            }

            method.returnType == Result::class.java -> Result.success(1L)

            method.returnType == List::class.java -> emptyList<Any>()

            method.returnType == Boolean::class.javaPrimitiveType -> false

            method.returnType == Int::class.javaPrimitiveType -> 0

            method.returnType == Long::class.javaPrimitiveType -> 0L

            args != null && args.isNotEmpty() && args.last() is kotlin.coroutines.Continuation<*> -> Result.success(1L)

            else -> null
        }
    } as T

    private fun createViewModel(
        analyticsRepoMock: AnalyticsAndBudgetRepository? = null,
        settingsRepoMock: SettingsAndBackupRepository? = null,
        accountRepoMock: AccountRepository? = null,
    ): StatsAndBudgetsViewModel = StatsAndBudgetsViewModel(
        analyticsAndBudgetRepository = analyticsRepoMock ?: createStub<AnalyticsAndBudgetRepository>(),
        categoryAndTagRepository = createStub<CategoryAndTagRepository>(),
        transactionRepository = createStub<TransactionRepository>(),
        settingsAndBackupRepository = settingsRepoMock ?: createStub<SettingsAndBackupRepository>(),
        accountRepository = accountRepoMock ?: createStub<AccountRepository>(),
        computationDispatcher = testDispatcher,
    )

    @Test
    fun selectSubTab_and_selectPeriodGranularity_updatesState() = runTest {
        val vm = createViewModel()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        assertEquals(StatsSubTab.STATS, vm.uiState.value.selectedSubTab)
        assertEquals(StatsPeriodGranularity.MONTHLY, vm.uiState.value.periodGranularity)

        vm.selectPeriodGranularity(StatsPeriodGranularity.ANNUALLY)
        testScheduler.advanceUntilIdle()
        assertEquals(StatsPeriodGranularity.ANNUALLY, vm.uiState.value.periodGranularity)

        vm.selectSubTab(StatsSubTab.BUDGETS)
        testScheduler.advanceUntilIdle()
        assertEquals(StatsSubTab.BUDGETS, vm.uiState.value.selectedSubTab)

        job.cancel()
    }

    @Test
    fun deleteBudget_invokesRepository_andSurfacesFeedback() = runTest {
        var deletedBudgetId: Long? = null
        val analyticsRepo = Proxy.newProxyInstance(
            AnalyticsAndBudgetRepository::class.java.classLoader,
            arrayOf(AnalyticsAndBudgetRepository::class.java),
        ) { _, method, args ->
            val name = method.name
            when {
                name.startsWith("observePeriodLedgerSummary") -> flowOf(
                    PeriodLedgerSummary(
                        startEpochDay = 0L,
                        endEpochDay = 0L,
                        totalIncome = MinorMoney.ZERO,
                        totalExpense = MinorMoney.ZERO,
                        netCashflow = MinorMoney.ZERO,
                        carryOverFromPreviousPeriod = MinorMoney.ZERO,
                    ),
                )

                name.startsWith("deleteBudget") -> {
                    deletedBudgetId = args?.getOrNull(0) as? Long
                    Result.success(Unit)
                }

                args != null && args.isNotEmpty() && args.last() is kotlin.coroutines.Continuation<*> -> Result.success(1L)

                else -> flowOf(emptyList<Any>())
            }
        } as AnalyticsAndBudgetRepository

        val vm = createViewModel(analyticsRepoMock = analyticsRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.deleteBudget(99L)
        testScheduler.advanceUntilIdle()

        assertEquals(99L, deletedBudgetId)
        assertEquals("Budget removed", vm.uiState.value.snackbarMessage)

        job.cancel()
    }

    @Test
    fun openCategoryDrillDown_and_dismiss_updatesDrillDownState() = runTest {
        val stat = CategorySpendStat(
            categoryId = 10L,
            categoryName = "Entertainment",
            colorHex = "#E91E63",
            iconName = "movie",
            netAmount = MinorMoney(2500_00L),
            percentageOfTotal = 25f,
            transactionCount = 5,
        )
        val analyticsRepo = Proxy.newProxyInstance(
            AnalyticsAndBudgetRepository::class.java.classLoader,
            arrayOf(AnalyticsAndBudgetRepository::class.java),
        ) { _, method, _ ->
            val name = method.name
            when {
                name.startsWith("observeCategorySpendStats") -> flowOf(listOf(stat))

                name.startsWith("observePeriodLedgerSummary") -> flowOf(
                    PeriodLedgerSummary(
                        startEpochDay = 0L,
                        endEpochDay = 0L,
                        totalIncome = MinorMoney.ZERO,
                        totalExpense = MinorMoney.ZERO,
                        netCashflow = MinorMoney.ZERO,
                        carryOverFromPreviousPeriod = MinorMoney.ZERO,
                    ),
                )

                else -> flowOf(emptyList<Any>())
            }
        } as AnalyticsAndBudgetRepository

        val vm = createViewModel(analyticsRepoMock = analyticsRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.drillDownState)

        vm.openCategoryDrillDown(stat)
        testScheduler.advanceUntilIdle()

        assertNotNull(vm.uiState.value.drillDownState)
        assertEquals("Entertainment", vm.uiState.value.drillDownState?.categoryStat?.categoryName)

        vm.dismissCategoryDrillDown()
        testScheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.drillDownState)

        job.cancel()
    }

    @Test
    fun saveBudgetFromDialog_customBudgetBlankName_setsValidationError() = runTest {
        val vm = createViewModel()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.openCreateBudgetDialog(preselectedCategoryId = null, initialPeriodType = BudgetPeriodType.CUSTOM)
        testScheduler.advanceUntilIdle()

        vm.updateBudgetEditorState {
            it.copy(
                customName = "   ",
                limitAmountInput = "500",
                startDateEpochDay = 100L,
                endDateEpochDay = 114L,
            )
        }
        testScheduler.advanceUntilIdle()

        vm.saveBudgetFromDialog()
        testScheduler.advanceUntilIdle()

        val editorState = vm.uiState.value.budgetEditorDialogState
        assertNotNull(editorState)
        assertEquals(
            "Please enter a name for your custom budget (e.g. Europe Trip).",
            editorState?.validationError,
        )

        job.cancel()
    }

    @Test
    fun deleteBudget_lastBudgetOfActiveCadenceFilter_resetsSelectedCadenceFilter() = runTest {
        val monthlyBudget = BudgetProgress(
            budgetId = 10L,
            categoryId = null,
            categoryName = "Overall",
            customName = "",
            colorHex = "#2196F3",
            periodType = BudgetPeriodType.MONTHLY,
            periodStartEpochDay = 1L,
            periodEndEpochDay = 30L,
            baseLimitAmount = MinorMoney(20000_00L),
            limitAmount = MinorMoney(20000_00L),
            spentAmount = MinorMoney.ZERO,
            remainingAmount = MinorMoney(20000_00L),
            usagePercentage = 0f,
        )
        val weeklyBudget = BudgetProgress(
            budgetId = 42L,
            categoryId = 1L,
            categoryName = "Groceries",
            customName = "",
            colorHex = "#4CAF50",
            periodType = BudgetPeriodType.WEEKLY,
            periodStartEpochDay = 100L,
            periodEndEpochDay = 106L,
            baseLimitAmount = MinorMoney(5000_00L),
            limitAmount = MinorMoney(5000_00L),
            spentAmount = MinorMoney.ZERO,
            remainingAmount = MinorMoney(5000_00L),
            usagePercentage = 0f,
        )
        val analyticsRepo = Proxy.newProxyInstance(
            AnalyticsAndBudgetRepository::class.java.classLoader,
            arrayOf(AnalyticsAndBudgetRepository::class.java),
        ) { _, method, args ->
            val name = method.name
            when {
                name.startsWith("observeBudgetProgressList") -> flowOf(listOf(monthlyBudget, weeklyBudget))

                name.startsWith("observePeriodLedgerSummary") -> flowOf(
                    PeriodLedgerSummary(
                        startEpochDay = 0L,
                        endEpochDay = 0L,
                        totalIncome = MinorMoney.ZERO,
                        totalExpense = MinorMoney.ZERO,
                        netCashflow = MinorMoney.ZERO,
                        carryOverFromPreviousPeriod = MinorMoney.ZERO,
                    ),
                )

                name.startsWith("deleteBudget") -> Result.success(Unit)

                args != null && args.isNotEmpty() && args.last() is kotlin.coroutines.Continuation<*> -> Result.success(1L)

                else -> flowOf(emptyList<Any>())
            }
        } as AnalyticsAndBudgetRepository

        val vm = createViewModel(analyticsRepoMock = analyticsRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.selectBudgetCadenceFilter(BudgetPeriodType.WEEKLY)
        testScheduler.advanceUntilIdle()
        assertEquals(BudgetPeriodType.WEEKLY, vm.uiState.value.selectedBudgetCadenceFilter)
        assertEquals(StatsPeriodGranularity.WEEKLY, vm.uiState.value.periodGranularity)

        vm.deleteBudget(42L)
        testScheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.selectedBudgetCadenceFilter)
        assertEquals(StatsPeriodGranularity.MONTHLY, vm.uiState.value.periodGranularity)

        job.cancel()
    }

    @Test
    fun livePreferencesUpdate_updatesSavingsTargetAndGrouping_withoutRestartingPeriod() = runTest {
        val prefsFlow = MutableStateFlow(UserPreferences(monthlySavingsTargetMinor = 10_000_00L))
        val settingsRepo = Proxy.newProxyInstance(
            SettingsAndBackupRepository::class.java.classLoader,
            arrayOf(SettingsAndBackupRepository::class.java),
        ) { _, method, _ ->
            if (method.name.startsWith("observeUserPreferences")) {
                prefsFlow
            } else {
                flowOf(emptyList<Any>())
            }
        } as SettingsAndBackupRepository

        val vm = createViewModel(settingsRepoMock = settingsRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        assertEquals(MinorMoney(10_000_00L), vm.uiState.value.monthlySavingsProgress.targetAmount)

        prefsFlow.value = UserPreferences(monthlySavingsTargetMinor = 50_000_00L)
        testScheduler.advanceUntilIdle()

        assertEquals(MinorMoney(50_000_00L), vm.uiState.value.monthlySavingsProgress.targetAmount)

        job.cancel()
    }

    @Test
    fun openCategoryDrillDown_inCashflowIncomeDriver_preservesIncomeCategoryType() = runTest {
        val incomeStat = CategorySpendStat(
            categoryId = 77L,
            categoryName = "Salary",
            colorHex = "#10B981",
            iconName = "payments",
            netAmount = MinorMoney(100_000_00L),
            percentageOfTotal = 100f,
            transactionCount = 1,
        )
        val analyticsRepo = Proxy.newProxyInstance(
            AnalyticsAndBudgetRepository::class.java.classLoader,
            arrayOf(AnalyticsAndBudgetRepository::class.java),
        ) { _, method, args ->
            val name = method.name
            when {
                name.startsWith("observeCategorySpendStats") && args?.getOrNull(2) == CategoryType.INCOME ->
                    flowOf(listOf(incomeStat))

                name.startsWith("observePeriodLedgerSummary") -> flowOf(
                    PeriodLedgerSummary(0L, 0L, MinorMoney.ZERO, MinorMoney.ZERO, MinorMoney.ZERO, MinorMoney.ZERO),
                )

                else -> flowOf(emptyList<Any>())
            }
        } as AnalyticsAndBudgetRepository

        val vm = createViewModel(analyticsRepoMock = analyticsRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        assertEquals(CategoryType.EXPENSE, vm.uiState.value.selectedCategoryType)
        vm.openCategoryDrillDown(incomeStat, CategoryType.INCOME)
        testScheduler.advanceUntilIdle()

        assertEquals(CategoryType.INCOME, vm.uiState.value.drillDownState?.categoryType)
        assertEquals("Salary", vm.uiState.value.drillDownState?.categoryStat?.categoryName)
        assertNull(vm.uiState.value.drillDownState?.matchingBudget)

        job.cancel()
    }

    @Test
    fun saveBudgetFromDialog_duplicateNonCustomBudget_setsValidationErrorInsteadOfOverwriting() = runTest {
        var upsertCalled = false
        val existingMonthly = BudgetProgress(
            budgetId = 55L,
            categoryId = 10L,
            categoryName = "Food",
            customName = "",
            colorHex = "#EF4444",
            periodType = BudgetPeriodType.MONTHLY,
            periodStartEpochDay = 1L,
            periodEndEpochDay = 30L,
            baseLimitAmount = MinorMoney(5000_00L),
            limitAmount = MinorMoney(5000_00L),
            spentAmount = MinorMoney.ZERO,
            remainingAmount = MinorMoney(5000_00L),
            usagePercentage = 0f,
        )
        val analyticsRepo = Proxy.newProxyInstance(
            AnalyticsAndBudgetRepository::class.java.classLoader,
            arrayOf(AnalyticsAndBudgetRepository::class.java),
        ) { _, method, args ->
            val name = method.name
            when {
                name.startsWith("observeBudgetProgressList") -> flowOf(listOf(existingMonthly))

                name.startsWith("observePeriodLedgerSummary") -> flowOf(
                    PeriodLedgerSummary(0L, 0L, MinorMoney.ZERO, MinorMoney.ZERO, MinorMoney.ZERO, MinorMoney.ZERO),
                )

                name.startsWith("upsertBudget") -> {
                    upsertCalled = true
                    55L
                }

                args != null && args.isNotEmpty() && args.last() is kotlin.coroutines.Continuation<*> -> Unit

                else -> flowOf(emptyList<Any>())
            }
        } as AnalyticsAndBudgetRepository

        val vm = createViewModel(analyticsRepoMock = analyticsRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.openCreateCategoryBudgetDialog()
        testScheduler.advanceUntilIdle()
        vm.updateBudgetEditorState {
            it.copy(
                selectedCategoryId = 10L,
                selectedCategoryName = "Food",
                periodType = BudgetPeriodType.MONTHLY,
                limitAmountInput = "9000",
            )
        }
        vm.saveBudgetFromDialog()
        testScheduler.advanceUntilIdle()

        assertEquals(false, upsertCalled)
        assertNotNull(vm.uiState.value.budgetEditorDialogState?.validationError)

        job.cancel()
    }

    @Test
    fun editBudgetCadence_lastOfFilteredCadence_resetsSelectedCadenceFilter_and_customCancelDoesNotStick() = runTest {
        val monthlyBudget = BudgetProgress(
            budgetId = 10L, categoryId = null, categoryName = "Overall", customName = "",
            colorHex = "#2196F3", periodType = BudgetPeriodType.MONTHLY,
            periodStartEpochDay = 1L, periodEndEpochDay = 30L,
            baseLimitAmount = MinorMoney(20000_00L), limitAmount = MinorMoney(20000_00L),
            spentAmount = MinorMoney.ZERO, remainingAmount = MinorMoney(20000_00L), usagePercentage = 0f,
        )
        val weeklyBudget = BudgetProgress(
            budgetId = 42L, categoryId = 1L, categoryName = "Groceries", customName = "",
            colorHex = "#4CAF50", periodType = BudgetPeriodType.WEEKLY,
            periodStartEpochDay = 100L, periodEndEpochDay = 106L,
            baseLimitAmount = MinorMoney(5000_00L), limitAmount = MinorMoney(5000_00L),
            spentAmount = MinorMoney.ZERO, remainingAmount = MinorMoney(5000_00L), usagePercentage = 0f,
        )
        val analyticsRepo = Proxy.newProxyInstance(
            AnalyticsAndBudgetRepository::class.java.classLoader,
            arrayOf(AnalyticsAndBudgetRepository::class.java),
        ) { _, method, args ->
            val name = method.name
            when {
                name.startsWith("observeBudgetProgressList") -> flowOf(listOf(monthlyBudget, weeklyBudget))

                name.startsWith("observePeriodLedgerSummary") -> flowOf(
                    PeriodLedgerSummary(0L, 0L, MinorMoney.ZERO, MinorMoney.ZERO, MinorMoney.ZERO, MinorMoney.ZERO),
                )

                name.startsWith("upsertBudget") -> 42L

                args != null && args.isNotEmpty() && args.last() is kotlin.coroutines.Continuation<*> -> Unit

                else -> flowOf(emptyList<Any>())
            }
        } as AnalyticsAndBudgetRepository

        val vm = createViewModel(analyticsRepoMock = analyticsRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        // Cancelling Custom picker keeps MONTHLY granularity intact
        vm.openCustomDateRangePicker()
        testScheduler.advanceUntilIdle()
        assertEquals(true, vm.uiState.value.isCustomDatePickerOpen)
        assertEquals(StatsPeriodGranularity.MONTHLY, vm.uiState.value.periodGranularity)
        vm.setCustomDatePickerOpen(false)
        testScheduler.advanceUntilIdle()
        assertEquals(StatsPeriodGranularity.MONTHLY, vm.uiState.value.periodGranularity)

        // Filtering to WEEKLY then editing weeklyBudget -> MONTHLY resets filter & granularity
        vm.selectBudgetCadenceFilter(BudgetPeriodType.WEEKLY)
        testScheduler.advanceUntilIdle()
        vm.openEditBudgetDialog(weeklyBudget)
        vm.updateBudgetEditorState { it.copy(periodType = BudgetPeriodType.MONTHLY) }
        vm.saveBudgetFromDialog()
        testScheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.selectedBudgetCadenceFilter)
        assertEquals(StatsPeriodGranularity.MONTHLY, vm.uiState.value.periodGranularity)

        job.cancel()
    }

    @Test
    fun savingsTargetAndGoal_invalidInputPreservesStateWithValidationError() = runTest {
        val prefsFlow = MutableStateFlow(UserPreferences(monthlySavingsTargetMinor = 25_000_00L))
        val settingsRepo = Proxy.newProxyInstance(
            SettingsAndBackupRepository::class.java.classLoader,
            arrayOf(SettingsAndBackupRepository::class.java),
        ) { _, method, _ ->
            if (method.name.startsWith("observeUserPreferences")) prefsFlow else flowOf(emptyList<Any>())
        } as SettingsAndBackupRepository

        val vm = createViewModel(settingsRepoMock = settingsRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.openMonthlySavingsTargetDialog()
        vm.saveMonthlySavingsTarget("0")
        testScheduler.advanceUntilIdle()

        assertEquals(true, vm.uiState.value.isMonthlySavingsTargetDialogOpen)
        assertNotNull(vm.uiState.value.monthlySavingsTargetValidationError)
        assertEquals(MinorMoney(25_000_00L), vm.uiState.value.monthlySavingsProgress.targetAmount)

        vm.openAddSavingsGoalSheet(defaultAccountId = 5L)
        vm.saveSavingsGoal(accountId = 5L, title = "Emergency", targetAmountInput = "-100", targetDateEpochDay = null)
        testScheduler.advanceUntilIdle()

        assertEquals(true, vm.uiState.value.isSavingsGoalSheetOpen)
        assertEquals(false, vm.uiState.value.isEditingSavingsGoal)
        assertNotNull(vm.uiState.value.savingsGoalValidationError)

        // Clearing validation errors allows a second invalid submission to emit again
        vm.clearMonthlySavingsTargetValidationError()
        testScheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.monthlySavingsTargetValidationError)
        vm.saveMonthlySavingsTarget("-5")
        testScheduler.advanceUntilIdle()
        assertNotNull(vm.uiState.value.monthlySavingsTargetValidationError)

        vm.clearSavingsGoalValidationError()
        testScheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.savingsGoalValidationError)
        vm.saveSavingsGoal(accountId = 5L, title = "Emergency", targetAmountInput = "0", targetDateEpochDay = null)
        testScheduler.advanceUntilIdle()
        assertNotNull(vm.uiState.value.savingsGoalValidationError)

        job.cancel()
    }

    @Test
    fun openAddSavingsGoalSheet_existingGoalFallback_preservesExistingFields_andRejectsDuplicateNewSave() = runTest {
        val existingGoalJson = SavingsGoalCalculator.upsertGoal(
            currentJson = "",
            config = AccountSavingsGoalConfig(
                accountId = 5L,
                goalTitle = "Emergency",
                targetAmountMinor = 50_000_00L,
            ),
        )
        var updatePrefsCalled = false
        val prefsFlow = MutableStateFlow(UserPreferences(savingsGoalsJson = existingGoalJson))
        val settingsRepo = Proxy.newProxyInstance(
            SettingsAndBackupRepository::class.java.classLoader,
            arrayOf(SettingsAndBackupRepository::class.java),
        ) { _, method, args ->
            when {
                method.name.startsWith("observeUserPreferences") -> prefsFlow

                method.name.startsWith("updateUserPreferences") -> {
                    updatePrefsCalled = true
                    Unit
                }

                args != null && args.isNotEmpty() && args.last() is kotlin.coroutines.Continuation<*> -> Unit

                else -> flowOf(emptyList<Any>())
            }
        } as SettingsAndBackupRepository

        val account5 = AccountSummary(
            id = 5L,
            name = "Vault",
            groupType = AccountGroup.SAVINGS,
            currencyCode = "INR",
            currentBalance = MinorMoney(10_000_00L),
        )
        val account6 = AccountSummary(
            id = 6L,
            name = "Brokerage",
            groupType = AccountGroup.INVESTMENT,
            currencyCode = "INR",
            currentBalance = MinorMoney(5_000_00L),
        )
        val accountsFlow = MutableStateFlow(listOf(account5))
        val accountRepo = Proxy.newProxyInstance(
            AccountRepository::class.java.classLoader,
            arrayOf(AccountRepository::class.java),
        ) { _, method, _ ->
            if (method.name.startsWith("observeAllAccounts")) accountsFlow else flowOf(emptyList<Any>())
        } as AccountRepository

        val vm = createViewModel(settingsRepoMock = settingsRepo, accountRepoMock = accountRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        // Only goaled account exists -> openAddSavingsGoalSheet delegates to edit without wiping fields
        vm.openAddSavingsGoalSheet()
        testScheduler.advanceUntilIdle()
        assertEquals(true, vm.uiState.value.isEditingSavingsGoal)
        assertEquals(5L, vm.uiState.value.editingSavingsGoalAccountId)
        assertEquals("Emergency", vm.uiState.value.editingSavingsGoalTitle)
        assertEquals("50000", vm.uiState.value.editingSavingsGoalTargetAmountInput)
        vm.closeSavingsGoalSheet()

        // With an un-goaled account available, new mode opens on account 6; saving over account 5 is rejected
        accountsFlow.value = listOf(account5, account6)
        testScheduler.advanceUntilIdle()
        vm.openAddSavingsGoalSheet()
        testScheduler.advanceUntilIdle()
        assertEquals(false, vm.uiState.value.isEditingSavingsGoal)
        assertEquals(6L, vm.uiState.value.editingSavingsGoalAccountId)

        vm.saveSavingsGoal(accountId = 5L, title = "Overwrite", targetAmountInput = "1000")
        testScheduler.advanceUntilIdle()
        assertEquals(false, updatePrefsCalled)
        assertNotNull(vm.uiState.value.savingsGoalValidationError)

        job.cancel()
    }

    @Test
    fun openCustomDateRangePicker_doesNotRestartPeriodQueries_and_uncategorizedDoesNotCrossPollinate() = runTest {
        var periodSummaryCalls = 0
        val expenseUncat = CategorySpendStat(
            categoryId = UNCATEGORIZED_CATEGORY_ID,
            categoryName = "Uncategorized",
            colorHex = "#9E9E9E",
            iconName = "help",
            netAmount = MinorMoney(900_00L),
            percentageOfTotal = 100f,
            transactionCount = 2,
        )
        val analyticsRepo = Proxy.newProxyInstance(
            AnalyticsAndBudgetRepository::class.java.classLoader,
            arrayOf(AnalyticsAndBudgetRepository::class.java),
        ) { _, method, args ->
            val name = method.name
            when {
                name.startsWith("observePeriodLedgerSummary") -> {
                    periodSummaryCalls++
                    flowOf(PeriodLedgerSummary(0L, 0L, MinorMoney.ZERO, MinorMoney.ZERO, MinorMoney.ZERO, MinorMoney.ZERO))
                }

                name.startsWith("observeCategorySpendStats") && args?.getOrNull(2) == CategoryType.EXPENSE ->
                    flowOf(listOf(expenseUncat))

                else -> flowOf(emptyList<Any>())
            }
        } as AnalyticsAndBudgetRepository

        val vm = createViewModel(analyticsRepoMock = analyticsRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        val initialCalls = periodSummaryCalls
        vm.openCustomDateRangePicker()
        testScheduler.advanceUntilIdle()
        vm.setCustomDatePickerOpen(false)
        testScheduler.advanceUntilIdle()
        assertEquals(initialCalls, periodSummaryCalls)

        // Opening uncategorized INCOME when only EXPENSE has uncategorized must NOT fall back to expenseUncat
        vm.openCategoryDrillDown(expenseUncat, CategoryType.INCOME)
        testScheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.drillDownState)

        job.cancel()
    }
}
