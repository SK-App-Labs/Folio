package com.folioledger.app.ui.ledger

import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NetWorthOverview
import com.folioledger.app.domain.model.PeriodLedgerSummary
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionSearchFilter
import com.folioledger.app.domain.model.TransactionType
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerHomeViewModelTest {

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
        txList: List<TransactionItem> = emptyList()
    ): LedgerHomeViewModel {
        val txRepo = Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java)
        ) { _, method, args ->
            when {
                method.name.startsWith("searchAndFilterTransactions") -> {
                    val filter = args?.firstOrNull() as? TransactionSearchFilter
                    val filtered = if (filter != null && filter.queryText.isNotBlank()) {
                        txList.filter { it.payeeOrDescription.contains(filter.queryText, ignoreCase = true) }
                    } else {
                        txList
                    }
                    flowOf(filtered)
                }
                method.name.startsWith("observeTransactionsInRange") ||
                method.name.startsWith("observeFilteredTransactions") ||
                method.name.startsWith("observeAllTransactions") -> flowOf(txList)
                else -> flowOf(emptyList<Any>())
            }
        } as TransactionRepository

        return LedgerHomeViewModel(
            transactionRepository = txRepo,
            analyticsAndBudgetRepository = createStub<AnalyticsAndBudgetRepository>(),
            accountRepository = createStub<AccountRepository>(),
            categoryAndTagRepository = createStub<CategoryAndTagRepository>(),
            settingsAndBackupRepository = createStub<SettingsAndBackupRepository>(),
            computationDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun selectSubTab_updatesSelectedSubTabStateImmediately() = runTest {
        val vm = createViewModel()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        assertEquals(LedgerSubTab.DAILY, vm.uiState.value.selectedSubTab)

        vm.selectSubTab(LedgerSubTab.MONTHLY)
        testScheduler.advanceUntilIdle()
        assertEquals(LedgerSubTab.MONTHLY, vm.uiState.value.selectedSubTab)

        vm.selectSubTab(LedgerSubTab.CALENDAR)
        testScheduler.advanceUntilIdle()
        assertEquals(LedgerSubTab.CALENDAR, vm.uiState.value.selectedSubTab)

        vm.selectSubTab(LedgerSubTab.SUMMARY)
        testScheduler.advanceUntilIdle()
        assertEquals(LedgerSubTab.SUMMARY, vm.uiState.value.selectedSubTab)

        job.cancel()
    }

    @Test
    fun stepFinancialMonth_advancesAndRewindsReferenceDate() = runTest {
        val vm = createViewModel()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        val initialPeriod = vm.uiState.value.activePeriodRange
        val initialRef = vm.uiState.value.referenceDateEpochDay

        // Navigate forward by 1 period
        vm.navigateToNextPeriod()
        testScheduler.advanceUntilIdle()
        val nextPeriod = vm.uiState.value.activePeriodRange
        assertTrue("Forward step should advance financial period", nextPeriod.startEpochDay > initialPeriod.startEpochDay)

        // Navigate backward by 1 period -> returns to initial month range
        vm.navigateToPreviousPeriod()
        testScheduler.advanceUntilIdle()
        assertEquals(initialPeriod.startEpochDay, vm.uiState.value.activePeriodRange.startEpochDay)

        // Jump back to today
        vm.jumpToToday()
        testScheduler.advanceUntilIdle()
        assertEquals(initialRef, vm.uiState.value.referenceDateEpochDay)

        job.cancel()
    }

    @Test
    fun updateSearchQuery_updatesQueryAndFiltersTransactions() = runTest {
        val todayEpochDay = LocalDate.now().toEpochDay()
        val tx1 = TransactionItem(
            id = 1L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = todayEpochDay,
            amount = MinorMoney(5000L),
            currencyCode = "INR",
            accountId = 1L,
            accountName = "Checking",
            payeeOrDescription = "Organic Supermarket",
            note = "Weekly groceries"
        )
        val tx2 = TransactionItem(
            id = 2L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 2000L,
            ledgerDateEpochDay = todayEpochDay,
            amount = MinorMoney(3000L),
            currencyCode = "INR",
            accountId = 1L,
            accountName = "Checking",
            payeeOrDescription = "Electric Bill",
            note = "Utility payment"
        )
        val vm = createViewModel(txList = listOf(tx1, tx2))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        // Search for "Supermarket"
        vm.updateSearchQuery("Supermarket")
        testScheduler.advanceTimeBy(300L)
        testScheduler.advanceUntilIdle()

        assertEquals("Supermarket", vm.uiState.value.searchFilter.queryText)
        assertTrue(vm.uiState.value.isFilterActive)
        assertEquals(1, vm.uiState.value.filteredTransactions.size)
        assertEquals("Organic Supermarket", vm.uiState.value.filteredTransactions.first().payeeOrDescription)

        // Clear query
        vm.updateSearchQuery("")
        testScheduler.advanceTimeBy(300L)
        testScheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.isFilterActive)
        assertEquals(2, vm.uiState.value.dailyGroups.flatMap { it.transactions }.size)

        job.cancel()
    }
}
