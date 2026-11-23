package com.folioledger.app.ui.stats

import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.BudgetPeriodType
import com.folioledger.app.domain.model.BudgetProgress
import com.folioledger.app.domain.model.CategorySpendStat
import com.folioledger.app.domain.model.CategoryType
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NetWorthOverview
import com.folioledger.app.domain.model.PeriodLedgerSummary
import com.folioledger.app.domain.model.StatsPeriodGranularity
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.domain.repository.AccountRepository
import com.folioledger.app.domain.repository.AnalyticsAndBudgetRepository
import com.folioledger.app.domain.repository.CategoryAndTagRepository
import com.folioledger.app.domain.repository.SettingsAndBackupRepository
import com.folioledger.app.domain.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private inline fun <reified T : Any> createStub(): T {
        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java)
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
                                carryOverFromPreviousPeriod = MinorMoney.ZERO
                            )
                        )
                        name.startsWith("observeNetWorthOverview") -> flowOf(
                            NetWorthOverview(
                                totalAssets = MinorMoney.ZERO,
                                totalLiabilities = MinorMoney.ZERO,
                                netWorth = MinorMoney.ZERO,
                                accountsByGroup = emptyMap<AccountGroup, List<AccountSummary>>()
                            )
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
    }

    private fun createViewModel(
        analyticsRepoMock: AnalyticsAndBudgetRepository? = null
    ): StatsAndBudgetsViewModel {
        return StatsAndBudgetsViewModel(
            analyticsAndBudgetRepository = analyticsRepoMock ?: createStub<AnalyticsAndBudgetRepository>(),
            categoryAndTagRepository = createStub<CategoryAndTagRepository>(),
            transactionRepository = createStub<TransactionRepository>(),
            settingsAndBackupRepository = createStub<SettingsAndBackupRepository>(),
            accountRepository = createStub<AccountRepository>(),
            computationDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
    }

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
            arrayOf(AnalyticsAndBudgetRepository::class.java)
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
                        carryOverFromPreviousPeriod = MinorMoney.ZERO
                    )
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
            transactionCount = 5
        )
        val analyticsRepo = Proxy.newProxyInstance(
            AnalyticsAndBudgetRepository::class.java.classLoader,
            arrayOf(AnalyticsAndBudgetRepository::class.java)
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
                        carryOverFromPreviousPeriod = MinorMoney.ZERO
                    )
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
                endDateEpochDay = 114L
            )
        }
        testScheduler.advanceUntilIdle()

        vm.saveBudgetFromDialog()
        testScheduler.advanceUntilIdle()

        val editorState = vm.uiState.value.budgetEditorDialogState
        assertNotNull(editorState)
        assertEquals(
            "Please enter a name for your custom budget (e.g. Europe Trip).",
            editorState?.validationError
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
            usagePercentage = 0f
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
            usagePercentage = 0f
        )
        val analyticsRepo = Proxy.newProxyInstance(
            AnalyticsAndBudgetRepository::class.java.classLoader,
            arrayOf(AnalyticsAndBudgetRepository::class.java)
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
                        carryOverFromPreviousPeriod = MinorMoney.ZERO
                    )
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
}
