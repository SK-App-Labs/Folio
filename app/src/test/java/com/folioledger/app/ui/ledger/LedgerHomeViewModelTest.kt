/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.ledger

import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NetWorthOverview
import com.folioledger.app.domain.model.PeriodLedgerSummary
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
        txList: List<TransactionItem> = emptyList(),
        accountsList: List<AccountSummary> = emptyList(),
        settingsRepoMock: SettingsAndBackupRepository? = null
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

        val accountRepo = if (accountsList.isEmpty()) {
            createStub<AccountRepository>()
        } else {
            Proxy.newProxyInstance(
                AccountRepository::class.java.classLoader,
                arrayOf(AccountRepository::class.java)
            ) { _, method, _ ->
                when {
                    method.name.startsWith("observeActiveAccounts") -> flowOf(accountsList.filter { !it.isArchived })
                    method.name.startsWith("observeAllAccounts") -> flowOf(accountsList)
                    method.name.startsWith("observeNetWorthOverview") -> flowOf(
                        NetWorthOverview(
                            totalAssets = MinorMoney.ZERO,
                            totalLiabilities = MinorMoney.ZERO,
                            netWorth = MinorMoney.ZERO,
                            accountsByGroup = emptyMap<AccountGroup, List<AccountSummary>>()
                        )
                    )
                    else -> flowOf(emptyList<Any>())
                }
            } as AccountRepository
        }

        return LedgerHomeViewModel(
            transactionRepository = txRepo,
            analyticsAndBudgetRepository = createStub<AnalyticsAndBudgetRepository>(),
            accountRepository = accountRepo,
            categoryAndTagRepository = createStub<CategoryAndTagRepository>(),
            settingsAndBackupRepository = settingsRepoMock ?: createStub<SettingsAndBackupRepository>(),
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

    @Test
    fun livePreferencesUpdate_updatesWeekdayHeadersImmediately_withoutPeriodReset() = runTest {
        val prefsFlow = MutableStateFlow(UserPreferences(weekStartDayIso = 1))
        val settingsRepo = Proxy.newProxyInstance(
            SettingsAndBackupRepository::class.java.classLoader,
            arrayOf(SettingsAndBackupRepository::class.java)
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

        assertEquals("Mon", vm.uiState.value.calendarWeekdayHeaders.first())

        prefsFlow.value = UserPreferences(weekStartDayIso = 7)
        testScheduler.advanceUntilIdle()

        assertEquals("Sun", vm.uiState.value.calendarWeekdayHeaders.first())

        job.cancel()
    }

    @Test
    fun filterCountParity_transfersAsOne_uncategorizedNoDoubleCount_andExcludesFilterAllPeriods() = runTest {
        val vm = createViewModel()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.toggleFilterTransfers()
        testScheduler.advanceUntilIdle()
        assertEquals(1, vm.uiState.value.activeFilterCount)
        assertEquals(1, vm.uiState.value.accountFilterCount)
        assertEquals(0, vm.uiState.value.expenseFilterCount)
        assertEquals(0, vm.uiState.value.incomeFilterCount)

        vm.toggleFilterCategoryId(UNCATEGORIZED_CATEGORY_ID)
        testScheduler.advanceUntilIdle()
        assertEquals(2, vm.uiState.value.activeFilterCount)
        assertEquals(1, vm.uiState.value.expenseFilterCount)
        assertEquals(0, vm.uiState.value.incomeFilterCount)

        // Enabling filterAllPeriods should not desync activeFilterCount vs sheet tab badges
        vm.setFilterAllPeriods(true)
        testScheduler.advanceUntilIdle()
        assertEquals(2, vm.uiState.value.activeFilterCount)

        // With INCOME-only type filter active, uncategorized shifts cleanly to income tab
        vm.toggleFilterTransactionType(TransactionType.INCOME)
        testScheduler.advanceUntilIdle()
        assertEquals(3, vm.uiState.value.activeFilterCount)
        assertEquals(0, vm.uiState.value.expenseFilterCount)
        assertEquals(2, vm.uiState.value.incomeFilterCount)

        job.cancel()
    }

    @Test
    fun amountFilterBounds_invertedRangeSuppressed_andCurrencyRescaleUpdatesBounds() = runTest {
        val prefsFlow = MutableStateFlow(UserPreferences(primaryCurrencyCode = "INR"))
        val settingsRepo = Proxy.newProxyInstance(
            SettingsAndBackupRepository::class.java.classLoader,
            arrayOf(SettingsAndBackupRepository::class.java)
        ) { _, method, _ ->
            if (method.name.startsWith("observeUserPreferences")) prefsFlow else flowOf(emptyList<Any>())
        } as SettingsAndBackupRepository

        val vm = createViewModel(settingsRepoMock = settingsRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.updateFilterAmountBounds("500", "100")
        testScheduler.advanceUntilIdle()
        assertEquals(null, vm.uiState.value.searchFilter.minAmount)
        assertEquals(null, vm.uiState.value.searchFilter.maxAmount)

        vm.updateFilterAmountBounds("10", "50")
        testScheduler.advanceUntilIdle()
        assertEquals(1000L, vm.uiState.value.searchFilter.minAmount?.amountMinor)
        assertEquals(5000L, vm.uiState.value.searchFilter.maxAmount?.amountMinor)

        // Switch primary currency to 3-decimal KWD -> stored raw inputs rescale dynamically
        prefsFlow.value = UserPreferences(primaryCurrencyCode = "KWD")
        testScheduler.advanceUntilIdle()
        assertEquals(10000L, vm.uiState.value.searchFilter.minAmount?.amountMinor)
        assertEquals(50000L, vm.uiState.value.searchFilter.maxAmount?.amountMinor)

        job.cancel()
    }

    @Test
    fun periodNavigation_whileFilteringOnMonthlyTab_stepsOneMonth_andNoOpsOnAllPeriods() = runTest {
        val vm = createViewModel()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.selectSubTab(LedgerSubTab.MONTHLY)
        vm.updateSearchQuery("Coffee")
        vm.setFilterAllPeriods(true)
        testScheduler.advanceTimeBy(300L)
        testScheduler.advanceUntilIdle()

        val initialStart = vm.uiState.value.activePeriodRange.startEpochDay
        vm.navigateToNextPeriod()
        testScheduler.advanceUntilIdle()
        assertEquals(initialStart, vm.uiState.value.activePeriodRange.startEpochDay)

        vm.setFilterAllPeriods(false)
        testScheduler.advanceUntilIdle()
        vm.navigateToNextPeriod()
        testScheduler.advanceUntilIdle()

        val expectedNext = LedgerPeriodCalculator.computeFinancialMonthRange(
            LedgerPeriodCalculator.stepFinancialMonth(initialStart, 1, 1L),
            1
        ).startEpochDay
        assertEquals(expectedNext, vm.uiState.value.activePeriodRange.startEpochDay)

        job.cancel()
    }

    @Test
    fun archivedAccount_remainsInspectableWhenScoped_andExcludedFromUnselectedFilterChips() = runTest {
        val active = AccountSummary(
            id = 1L,
            name = "Active Bank",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(10000L),
            isArchived = false
        )
        val archived = AccountSummary(
            id = 2L,
            name = "Old Card",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "USD",
            currentBalance = MinorMoney(0L),
            isArchived = true
        )
        val vm = createViewModel(accountsList = listOf(active, archived))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(1L), vm.uiState.value.availableAccounts.map { it.id })
        assertEquals(listOf(1L, 2L), vm.uiState.value.allAccounts.map { it.id })

        vm.setScopedAccount(2L)
        testScheduler.advanceUntilIdle()
        assertEquals(2L, vm.uiState.value.scopedAccount?.id)
        assertEquals("USD", vm.uiState.value.currencySpec.code)

        // Partial account filter overlap clears conflicting accountIds when scoping to one account
        vm.setScopedAccount(null)
        vm.toggleFilterAccountId(1L)
        vm.toggleFilterAccountId(2L)
        testScheduler.advanceUntilIdle()
        assertEquals(setOf(1L, 2L), vm.uiState.value.searchFilter.accountIds)

        vm.setScopedAccount(1L)
        testScheduler.advanceUntilIdle()
        assertEquals(1L, vm.uiState.value.scopedAccount?.id)
        assertTrue(vm.uiState.value.searchFilter.accountIds.isEmpty())

        job.cancel()
    }

    @Test
    fun zeroAmountBounds_scopedAccountChipSync_andForeignAccountFilterCurrency() = runTest {
        val inrAccount = AccountSummary(
            id = 1L,
            name = "Primary INR",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(50000L)
        )
        val usdAccount = AccountSummary(
            id = 2L,
            name = "Travel USD",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "USD",
            currentBalance = MinorMoney(0L)
        )
        val refundTx = TransactionItem(
            id = 10L,
            type = TransactionType.REFUND,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = LocalDate.now().toEpochDay(),
            amount = MinorMoney(5000L),
            currencyCode = "INR",
            accountId = 1L,
            accountName = "Primary INR"
        )
        val vm = createViewModel(
            txList = listOf(refundTx),
            accountsList = listOf(inrAccount, usdAccount)
        )
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        // Zero amount bounds must be accepted (whereas negative bounds are rejected)
        vm.updateFilterAmountBounds("0", "0")
        testScheduler.advanceUntilIdle()
        assertEquals(0L, vm.uiState.value.searchFilter.minAmount?.amountMinor)
        assertEquals(0L, vm.uiState.value.searchFilter.maxAmount?.amountMinor)
        assertTrue(vm.uiState.value.isFilterActive)

        vm.updateFilterAmountBounds("-50", "")
        testScheduler.advanceUntilIdle()
        assertEquals(null, vm.uiState.value.searchFilter.minAmount)
        assertFalse(vm.uiState.value.isFilterActive)

        // Single foreign-currency account chip updates amountFilterCurrencySpec
        vm.toggleFilterAccountId(2L)
        testScheduler.advanceUntilIdle()
        assertEquals("USD", vm.uiState.value.amountFilterCurrencySpec.code)

        // Mixed-currency account selection reverts amountFilterCurrencySpec to primary INR
        vm.toggleFilterAccountId(1L)
        testScheduler.advanceUntilIdle()
        assertEquals("INR", vm.uiState.value.amountFilterCurrencySpec.code)

        // Scoped account interactions preserve selection when expanding, and unscope when retapped
        vm.clearAllFilters()
        vm.setScopedAccount(1L)
        testScheduler.advanceUntilIdle()
        vm.toggleFilterAccountId(1L)
        testScheduler.advanceUntilIdle()
        assertEquals(null, vm.uiState.value.scopedAccountId)
        assertTrue(vm.uiState.value.searchFilter.accountIds.isEmpty())

        vm.setScopedAccount(1L)
        testScheduler.advanceUntilIdle()
        vm.toggleFilterAccountId(2L)
        testScheduler.advanceUntilIdle()
        assertEquals(null, vm.uiState.value.scopedAccountId)
        assertEquals(setOf(1L, 2L), vm.uiState.value.searchFilter.accountIds)

        // Refund-only filtered result flags isFilteredRefundOnly cleanly
        vm.clearAllFilters()
        vm.toggleFilterTransactionType(TransactionType.REFUND)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.isFilteredRefundOnly)

        // toggleSearchBar handles queries typed via the filter sheet even when isSearchBarVisible == false
        vm.updateSearchQuery("hotel")
        testScheduler.advanceTimeBy(300L)
        testScheduler.advanceUntilIdle()
        assertEquals("hotel", vm.uiState.value.searchFilter.queryText)
        vm.toggleSearchBar()
        testScheduler.advanceTimeBy(300L)
        testScheduler.advanceUntilIdle()
        assertEquals("", vm.uiState.value.searchFilter.queryText)
        assertFalse(vm.uiState.value.isSearchBarVisible)
        assertTrue(vm.uiState.value.isFilterActive)

        job.cancel()
    }
}
