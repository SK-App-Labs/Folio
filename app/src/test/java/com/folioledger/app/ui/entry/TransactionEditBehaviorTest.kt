/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.entry

import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.CategoryNode
import com.folioledger.app.domain.model.CategoryType
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.RecurringCommitmentItem
import com.folioledger.app.domain.model.RepeatFrequency
import com.folioledger.app.domain.model.TransactionItem
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionEditBehaviorTest {

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
            when {
                method.returnType == kotlinx.coroutines.flow.Flow::class.java -> {
                    if (method.name == "observeUserPreferences") {
                        flowOf(UserPreferences())
                    } else {
                        flowOf(emptyList<Any>())
                    }
                }
                args != null && args.isNotEmpty() && args.last() is kotlin.coroutines.Continuation<*> -> Result.success(1L)
                method.returnType == Result::class.java -> Result.success(1L)
                method.returnType == Boolean::class.javaPrimitiveType -> false
                method.returnType == Int::class.javaPrimitiveType -> 0
                method.returnType == Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        } as T
    }

    private fun createViewModel(
        accounts: List<AccountSummary> = emptyList(),
        analyticsAndBudgetRepo: AnalyticsAndBudgetRepository? = null,
        expenseCategories: List<CategoryNode> = emptyList(),
        incomeCategories: List<CategoryNode> = emptyList(),
        txRepo: TransactionRepository? = null
    ): TransactionEntryViewModel {
        val accountRepo = Proxy.newProxyInstance(
            AccountRepository::class.java.classLoader,
            arrayOf(AccountRepository::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "observeActiveAccounts" -> flowOf(accounts.filter { !it.isArchived })
                "observeAllAccounts" -> flowOf(accounts)
                else -> null
            }
        } as AccountRepository

        val catRepo = Proxy.newProxyInstance(
            CategoryAndTagRepository::class.java.classLoader,
            arrayOf(CategoryAndTagRepository::class.java)
        ) { _, method, args ->
            when (method.name) {
                "observeCategoryTree" -> {
                    val catType = args?.getOrNull(0) as? CategoryType
                    if (catType == CategoryType.INCOME) flowOf(incomeCategories) else flowOf(expenseCategories)
                }
                else -> flowOf(emptyList<Any>())
            }
        } as CategoryAndTagRepository

        return TransactionEntryViewModel(
            transactionRepository = txRepo ?: createStub<TransactionRepository>(),
            accountRepository = accountRepo,
            categoryAndTagRepository = catRepo,
            settingsAndBackupRepository = createStub<SettingsAndBackupRepository>(),
            analyticsAndBudgetRepository = analyticsAndBudgetRepo,
            computationDispatcher = testDispatcher
        )
    }

    @Test
    fun `loadTransactionItemForEdit rejects OPENING_BALANCE and RECONCILIATION baseline ledger types`() = runTest {
        val vm = createViewModel()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }

        val openingBalanceTx = TransactionItem(
            id = 1L,
            type = TransactionType.OPENING_BALANCE,
            timestampEpochMillis = 1700000000000L,
            ledgerDateEpochDay = 20000L,
            amount = MinorMoney(100000L),
            currencyCode = "INR",
            accountId = 1L,
            accountName = "Bank Account",
            payeeOrDescription = "Opening Balance"
        )

        val reconciliationTx = TransactionItem(
            id = 2L,
            type = TransactionType.RECONCILIATION,
            timestampEpochMillis = 1700000000000L,
            ledgerDateEpochDay = 20000L,
            amount = MinorMoney(5000L),
            currencyCode = "INR",
            accountId = 1L,
            accountName = "Bank Account",
            payeeOrDescription = "Reconciliation"
        )

        assertFalse(vm.loadTransactionItemForEdit(openingBalanceTx))
        assertFalse(vm.loadTransactionItemForEdit(reconciliationTx))
        job.cancel()
    }

    @Test
    fun `loadTransactionItemForEdit with CC_SETTLEMENT preserves settlement mode and destination account`() = runTest {
        val hdfc = AccountSummary(
            id = 1L,
            name = "HDFC Bank",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(1000000L)
        )
        val regalia = AccountSummary(
            id = 2L,
            name = "Regalia Credit Card",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "INR",
            currentBalance = MinorMoney(-150000L)
        )
        val vm = createViewModel(accounts = listOf(hdfc, regalia))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }

        val tx = TransactionItem(
            id = 42L,
            type = TransactionType.CC_SETTLEMENT,
            timestampEpochMillis = 1700000000000L,
            ledgerDateEpochDay = 20000L,
            amount = MinorMoney(150000L),
            currencyCode = "INR",
            accountId = 1L,
            accountName = "HDFC Bank",
            transferToAccountId = 2L,
            transferToAccountName = "Regalia Credit Card",
            payeeOrDescription = "Card Bill Payment"
        )

        val loaded = vm.loadTransactionItemForEdit(tx)
        assertTrue(loaded)
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(TransactionType.CC_SETTLEMENT, state.mode)
        assertEquals(1L, state.selectedAccount?.id)
        assertEquals(2L, state.transferToAccount?.id)
        assertEquals("1500", state.expressionBuffer)
        job.cancel()
    }

    @Test
    fun `loadTransactionItemForEdit with uncategorized expense preserves null categoryId`() = runTest {
        val vm = createViewModel()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }

        val tx = TransactionItem(
            id = 99L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 1700000000000L,
            ledgerDateEpochDay = 20000L,
            amount = MinorMoney(50000L),
            currencyCode = "INR",
            accountId = 1L,
            accountName = "Cash",
            categoryId = null,
            categoryName = null,
            payeeOrDescription = "Street Market Vendor"
        )

        val loaded = vm.loadTransactionItemForEdit(tx)
        assertTrue(loaded)
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertNull(state.selectedCategoryId)
        assertNull(state.selectedCategoryName)
        job.cancel()
    }

    @Test
    fun `loadTransactionItemForEdit with installment preserves installment indices`() = runTest {
        val vm = createViewModel()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }

        val tx = TransactionItem(
            id = 101L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 1700000000000L,
            ledgerDateEpochDay = 20000L,
            amount = MinorMoney(33333L),
            currencyCode = "INR",
            accountId = 5L,
            accountName = "OneCard",
            categoryId = 10L,
            categoryName = "Electronics",
            payeeOrDescription = "Laptop EMI",
            installmentIndex = 2,
            installmentTotalCount = 6
        )

        val loaded = vm.loadTransactionItemForEdit(tx)
        assertTrue(loaded)
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(2, state.installmentIndex)
        assertEquals(6, state.installmentTotalCount)
        job.cancel()
    }

    @Test
    fun `saveTransaction rejects cross-currency transfers and sets error message`() = runTest {
        val usdAccount = AccountSummary(
            id = 1L,
            name = "USD Checking",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "USD",
            currentBalance = MinorMoney(100000L)
        )
        val inrAccount = AccountSummary(
            id = 2L,
            name = "INR Savings",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(500000L)
        )
        val vm = createViewModel(accounts = listOf(usdAccount, inrAccount))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.setMode(TransactionType.TRANSFER)
        vm.selectSourceAccount(1L)
        vm.selectTransferDestinationAccount(2L)
        vm.onKeypadKeyPress("1")
        vm.onKeypadKeyPress("0")
        vm.onKeypadKeyPress("0")
        testScheduler.advanceUntilIdle()

        vm.saveTransaction()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("Transfer accounts must use the same currency (USD ≠ INR).", state.errorMessage)
        assertFalse(state.isSaving)
        job.cancel()
    }

    @Test
    fun `loadRecurringRuleForEntry resets isRecurring, isInstallmentEnabled and stale fields`() = runTest {
        val recurringRule = RecurringCommitmentItem(
            id = 42L,
            title = "Netflix Subscription",
            type = TransactionType.EXPENSE,
            amount = MinorMoney(64900L),
            currencyCode = "INR",
            accountId = 1L,
            categoryId = 10L,
            frequency = RepeatFrequency.EVERY_MONTH,
            startDateEpochDay = 20000L,
            nextOccurrenceEpochDay = 20030L,
            payeeOrDescription = "Netflix",
            note = "Monthly plan"
        )
        val analyticsRepo = Proxy.newProxyInstance(
            AnalyticsAndBudgetRepository::class.java.classLoader,
            arrayOf(AnalyticsAndBudgetRepository::class.java),
            java.lang.reflect.InvocationHandler { _, method, _ ->
                when (method?.name) {
                    "observeAllRecurringRules" -> flowOf(listOf(recurringRule))
                    else -> null
                }
            }
        ) as AnalyticsAndBudgetRepository

        val vm = createViewModel(analyticsAndBudgetRepo = analyticsRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        // Simulate user previously toggling isRecurring = true and adding a tag in a previous draft
        vm.setRecurringFrequency(RepeatFrequency.EVERY_WEEK)
        vm.toggleTagSelection("streaming")
        testScheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.isRecurring)
        assertTrue(vm.uiState.value.selectedTags.contains("streaming"))

        // Now load the recurring rule occurrence (e.g. from Due Bill notification)
        vm.loadRecurringRuleForEntry(42L)
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        // Verify that isRecurring was cleanly reset to false to avoid duplicate rule creation
        assertFalse("isRecurring must be reset to false so it doesn't create duplicate rule", state.isRecurring)
        assertFalse("isInstallmentEnabled must be false", state.isInstallmentEnabled)
        assertTrue("selectedTags must be empty", state.selectedTags.isEmpty())
        assertEquals(42L, state.linkedRecurringRuleId)
        assertEquals("Netflix", state.payeeOrDescription)
        assertEquals(20030L, state.ledgerDateEpochDay)
        job.cancel()
    }

    @Test
    fun `saveTransaction when converting existing credit card expense to installment plan passes replaceTransactionId atomically`() = runTest {
        val creditCard = AccountSummary(
            id = 5L,
            name = "HDFC Card",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "INR",
            currentBalance = MinorMoney(-100000L),
            colorHex = "#10B981",
            iconName = "credit_card",
            includeInNetWorth = true,
            isArchived = false
        )

        var deleteTransactionCalled = false
        var capturedReplaceTransactionId: Long? = null
        var createInstallmentsCalled = false

        val txRepo = Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java)
        ) { _, method, args ->
            when {
                method.returnType == kotlinx.coroutines.flow.Flow::class.java -> flowOf(emptyList<Any>())
                method.name == "deleteTransaction" || method.name.startsWith("deleteTransaction") -> {
                    deleteTransactionCalled = true
                    Result.success(Unit)
                }
                method.name.startsWith("createInstallmentPlanTransactions") -> {
                    createInstallmentsCalled = true
                    capturedReplaceTransactionId = args?.getOrNull(4) as? Long
                    Result.success(listOf(101L, 102L, 103L))
                }
                else -> Result.success(1L)
            }
        } as TransactionRepository

        val vm = createViewModel(
            accounts = listOf(creditCard),
            txRepo = txRepo
        )
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        val existingExpense = TransactionItem(
            id = 99L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 1700000000000L,
            ledgerDateEpochDay = 20000L,
            amount = MinorMoney(300000L),
            currencyCode = "INR",
            accountId = 5L,
            accountName = "HDFC Card",
            categoryId = 10L,
            categoryName = "Electronics",
            payeeOrDescription = "Laptop Purchase"
        )
        vm.loadTransactionItemForEdit(existingExpense)
        testScheduler.advanceUntilIdle()

        // Enable installment plan (3 months)
        vm.toggleInstallmentEnabled()
        vm.setInstallmentMonthsCount(3)
        testScheduler.advanceUntilIdle()

        // Save
        vm.saveTransaction()
        testScheduler.advanceUntilIdle()

        assertTrue("createInstallmentPlanTransactions must be called", createInstallmentsCalled)
        assertEquals(99L, capturedReplaceTransactionId)
        assertFalse("Standalone deleteTransaction must NOT be called to preserve atomicity", deleteTransactionCalled)
        job.cancel()
    }

    @Test
    fun `onKeypadKeyPress with 00 appends single zero when 1 decimal digit remains`() = runTest {
        val vm = createViewModel()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }

        // Start with 12.5 (1 decimal digit, standard 2 fraction digits)
        vm.updateAmountInput("12.5")
        testScheduler.advanceUntilIdle()
        assertEquals("12.5", vm.uiState.value.expressionBuffer)

        // Press "00" -> should complete to "12.50"
        vm.onKeypadKeyPress("00")
        testScheduler.advanceUntilIdle()
        assertEquals("12.50", vm.uiState.value.expressionBuffer)

        // Press "00" again -> already full fraction digits, remains "12.50"
        vm.onKeypadKeyPress("00")
        testScheduler.advanceUntilIdle()
        assertEquals("12.50", vm.uiState.value.expressionBuffer)

        // Reset to "12."
        vm.updateAmountInput("12.")
        testScheduler.advanceUntilIdle()
        vm.onKeypadKeyPress("00")
        testScheduler.advanceUntilIdle()
        assertEquals("12.00", vm.uiState.value.expressionBuffer)

        // Integer buffer "12" + "00" -> "1200"
        vm.updateAmountInput("12")
        testScheduler.advanceUntilIdle()
        vm.onKeypadKeyPress("00")
        testScheduler.advanceUntilIdle()
        assertEquals("1200", vm.uiState.value.expressionBuffer)

        // Zero buffer "0" + "00" -> "0"
        vm.updateAmountInput("0")
        testScheduler.advanceUntilIdle()
        vm.onKeypadKeyPress("00")
        testScheduler.advanceUntilIdle()
        assertEquals("0", vm.uiState.value.expressionBuffer)

        job.cancel()
    }

    @Test
    fun `saveTransaction when editing historical recurring transaction does not advance recurring rule`() = runTest {
        val recurringRule = RecurringCommitmentItem(
            id = 42L,
            title = "Netflix Subscription",
            type = TransactionType.EXPENSE,
            amount = MinorMoney(64900L),
            currencyCode = "INR",
            accountId = 1L,
            categoryId = 10L,
            frequency = RepeatFrequency.EVERY_MONTH,
            startDateEpochDay = 20000L,
            nextOccurrenceEpochDay = 20030L,
            payeeOrDescription = "Netflix",
            note = "Monthly plan"
        )
        var advanceRecurringScheduleCalled = false
        val analyticsRepo = Proxy.newProxyInstance(
            AnalyticsAndBudgetRepository::class.java.classLoader,
            arrayOf(AnalyticsAndBudgetRepository::class.java),
            java.lang.reflect.InvocationHandler { _, method, _ ->
                when {
                    method?.name == "observeAllRecurringRules" -> flowOf(listOf(recurringRule))
                    method?.name?.startsWith("advanceRecurringScheduleIfDue") == true -> {
                        advanceRecurringScheduleCalled = true
                        Result.success(Unit)
                    }
                    else -> null
                }
            }
        ) as AnalyticsAndBudgetRepository

        val account = AccountSummary(
            id = 1L,
            name = "Main Bank",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(100000L)
        )
        val vm = createViewModel(accounts = listOf(account), analyticsAndBudgetRepo = analyticsRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        // Load an existing transaction originally generated by rule 42L
        val existingTx = TransactionItem(
            id = 999L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 1700000000000L,
            ledgerDateEpochDay = 20000L,
            amount = MinorMoney(64900L),
            currencyCode = "INR",
            accountId = 1L,
            accountName = "Main Bank",
            categoryId = 10L,
            categoryName = "Entertainment",
            payeeOrDescription = "Netflix",
            recurringRuleId = 42L
        )
        vm.loadTransactionItemForEdit(existingTx)
        testScheduler.advanceUntilIdle()

        // Save the edit on existing transaction (existingId == 999L)
        vm.saveTransaction()
        testScheduler.advanceUntilIdle()

        assertFalse("Editing historical transaction must NOT advance recurring rule schedule", advanceRecurringScheduleCalled)
        job.cancel()
    }

    @Test
    fun `saveTransaction when logging new transaction from recurring rule advances recurring rule`() = runTest {
        val recurringRule = RecurringCommitmentItem(
            id = 42L,
            title = "Netflix Subscription",
            type = TransactionType.EXPENSE,
            amount = MinorMoney(64900L),
            currencyCode = "INR",
            accountId = 1L,
            categoryId = 10L,
            frequency = RepeatFrequency.EVERY_MONTH,
            startDateEpochDay = 20000L,
            nextOccurrenceEpochDay = 20030L,
            payeeOrDescription = "Netflix",
            note = "Monthly plan"
        )
        var logDueRecurringCalled = false
        var capturedRuleId: Long? = null
        var capturedDueEpochDay: Long? = null
        val txRepo = Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java)
        ) { _, method, args ->
            when {
                method.name.startsWith("logDueRecurringTransaction") -> {
                    logDueRecurringCalled = true
                    capturedRuleId = args?.getOrNull(1) as? Long
                    capturedDueEpochDay = args?.getOrNull(2) as? Long
                    Result.success(1L)
                }
                method.returnType == kotlinx.coroutines.flow.Flow::class.java -> flowOf(emptyList<Any>())
                args != null && args.isNotEmpty() && args.last() is kotlin.coroutines.Continuation<*> -> Result.success(1L)
                method.returnType == Result::class.java -> Result.success(1L)
                else -> null
            }
        } as TransactionRepository

        val analyticsRepo = Proxy.newProxyInstance(
            AnalyticsAndBudgetRepository::class.java.classLoader,
            arrayOf(AnalyticsAndBudgetRepository::class.java),
            java.lang.reflect.InvocationHandler { _, method, _ ->
                when {
                    method?.name == "observeAllRecurringRules" -> flowOf(listOf(recurringRule))
                    else -> null
                }
            }
        ) as AnalyticsAndBudgetRepository

        val account = AccountSummary(
            id = 1L,
            name = "Main Bank",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(100000L)
        )
        val vm = createViewModel(accounts = listOf(account), analyticsAndBudgetRepo = analyticsRepo, txRepo = txRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        // Load recurring rule for a new transaction entry (existingId == 0L)
        vm.loadRecurringRuleForEntry(42L)
        testScheduler.advanceUntilIdle()

        // Save new transaction (existingId == 0L)
        vm.saveTransaction()
        testScheduler.advanceUntilIdle()

        assertTrue("Logging new transaction from recurring rule MUST atomically advance recurring rule schedule", logDueRecurringCalled)
        assertEquals(42L, capturedRuleId)
        assertEquals(20030L, capturedDueEpochDay)
        job.cancel()
    }

    @Test
    fun `saveTransaction when logging new transaction from recurring rule with edited date preserves rule due date for advancement`() = runTest {
        val recurringRule = RecurringCommitmentItem(
            id = 42L,
            title = "Netflix Subscription",
            type = TransactionType.EXPENSE,
            amount = MinorMoney(64900L),
            currencyCode = "INR",
            accountId = 1L,
            categoryId = 10L,
            frequency = RepeatFrequency.EVERY_MONTH,
            startDateEpochDay = 20000L,
            nextOccurrenceEpochDay = 20030L,
            payeeOrDescription = "Netflix",
            note = "Monthly plan"
        )
        var logDueRecurringCalled = false
        var capturedDueEpochDay: Long? = null
        val txRepo = Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java)
        ) { _, method, args ->
            when {
                method.name.startsWith("logDueRecurringTransaction") -> {
                    logDueRecurringCalled = true
                    capturedDueEpochDay = args?.getOrNull(2) as? Long
                    Result.success(1L)
                }
                method.returnType == kotlinx.coroutines.flow.Flow::class.java -> flowOf(emptyList<Any>())
                args != null && args.isNotEmpty() && args.last() is kotlin.coroutines.Continuation<*> -> Result.success(1L)
                method.returnType == Result::class.java -> Result.success(1L)
                else -> null
            }
        } as TransactionRepository

        val analyticsRepo = Proxy.newProxyInstance(
            AnalyticsAndBudgetRepository::class.java.classLoader,
            arrayOf(AnalyticsAndBudgetRepository::class.java),
            java.lang.reflect.InvocationHandler { _, method, _ ->
                when {
                    method?.name == "observeAllRecurringRules" -> flowOf(listOf(recurringRule))
                    else -> null
                }
            }
        ) as AnalyticsAndBudgetRepository

        val account = AccountSummary(
            id = 1L,
            name = "Main Bank",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(100000L)
        )
        val vm = createViewModel(accounts = listOf(account), analyticsAndBudgetRepo = analyticsRepo, txRepo = txRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.loadRecurringRuleForEntry(42L)
        testScheduler.advanceUntilIdle()

        // User edits transaction date (e.g. paid early on 20028L)
        vm.setLedgerDateEpochDay(20028L)
        testScheduler.advanceUntilIdle()

        vm.saveTransaction()
        testScheduler.advanceUntilIdle()

        assertTrue("Atomic recurring log & schedule advancement must be called", logDueRecurringCalled)
        assertEquals("Advancement must pass the rule's original due date (20030L), not edited ledger date", 20030L, capturedDueEpochDay)
        job.cancel()
    }

    @Test
    fun `saveTransaction when logging due recurring bill as split still routes through logDueRecurringTransaction`() = runTest {
        val recurringRule = RecurringCommitmentItem(
            id = 42L,
            title = "Utility Bill",
            type = TransactionType.EXPENSE,
            amount = MinorMoney(100000L),
            currencyCode = "INR",
            accountId = 1L,
            categoryId = 10L,
            frequency = RepeatFrequency.EVERY_MONTH,
            startDateEpochDay = 20000L,
            nextOccurrenceEpochDay = 20030L,
            payeeOrDescription = "Electric Co"
        )
        var logDueRecurringCalled = false
        var capturedIsSplit = false
        val txRepo = Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java)
        ) { _, method, args ->
            when {
                method.name.startsWith("logDueRecurringTransaction") -> {
                    logDueRecurringCalled = true
                    capturedIsSplit = (args?.getOrNull(0) as? TransactionItem)?.isSplit == true
                    Result.success(1L)
                }
                method.returnType == kotlinx.coroutines.flow.Flow::class.java -> flowOf(emptyList<Any>())
                args != null && args.isNotEmpty() && args.last() is kotlin.coroutines.Continuation<*> -> Result.success(1L)
                method.returnType == Result::class.java -> Result.success(1L)
                else -> null
            }
        } as TransactionRepository

        val analyticsRepo = Proxy.newProxyInstance(
            AnalyticsAndBudgetRepository::class.java.classLoader,
            arrayOf(AnalyticsAndBudgetRepository::class.java),
            java.lang.reflect.InvocationHandler { _, method, _ ->
                when (method?.name) {
                    "observeAllRecurringRules" -> flowOf(listOf(recurringRule))
                    else -> null
                }
            }
        ) as AnalyticsAndBudgetRepository

        val account = AccountSummary(
            id = 1L,
            name = "Main Bank",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(200000L)
        )
        val foodCategory = CategoryNode(id = 10L, name = "Utilities", type = CategoryType.EXPENSE, parentId = null, colorHex = "#059669", iconName = "category")
        val otherCategory = CategoryNode(id = 11L, name = "Home", type = CategoryType.EXPENSE, parentId = null, colorHex = "#2563EB", iconName = "home")
        val vm = createViewModel(
            accounts = listOf(account),
            analyticsAndBudgetRepo = analyticsRepo,
            expenseCategories = listOf(foodCategory, otherCategory),
            txRepo = txRepo
        )
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.loadRecurringRuleForEntry(42L)
        testScheduler.advanceUntilIdle()
        vm.toggleSplitMode()
        testScheduler.advanceUntilIdle()
        val splitDrafts = vm.uiState.value.splitDrafts
        vm.updateSplitLineCategory(splitDrafts[1].localId, otherCategory)
        testScheduler.advanceUntilIdle()

        vm.saveTransaction()
        testScheduler.advanceUntilIdle()

        assertTrue("Split recurring log must still route through logDueRecurringTransaction", logDueRecurringCalled)
        assertTrue("Split flag must be preserved on logged recurring bill", capturedIsSplit)
        job.cancel()
    }

    @Test
    fun `saveTransaction with default category preselection saves transaction without tapping category picker`() = runTest {
        val account = AccountSummary(
            id = 1L,
            name = "Main Bank",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(100000L)
        )
        val foodCategory = CategoryNode(
            id = 101L,
            name = "Food & Dining",
            type = CategoryType.EXPENSE,
            parentId = null,
            iconName = "restaurant",
            colorHex = "#FF5722"
        )
        var capturedTx: TransactionItem? = null
        val txRepo = Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java),
            java.lang.reflect.InvocationHandler { _, method, args ->
                when {
                    method?.returnType == kotlinx.coroutines.flow.Flow::class.java -> flowOf(emptyList<Any>())
                    method?.name?.startsWith("upsertSingleTransaction") == true -> {
                        capturedTx = args?.getOrNull(0) as? TransactionItem
                        Result.success(1L)
                    }
                    else -> Result.success(1L)
                }
            }
        ) as TransactionRepository

        val vm = createViewModel(
            accounts = listOf(account),
            expenseCategories = listOf(foodCategory),
            txRepo = txRepo
        )
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.resetForNewTransaction(defaultMode = TransactionType.EXPENSE)
        testScheduler.advanceUntilIdle()

        // Verify default category is visually selected in uiState
        assertEquals(101L, vm.uiState.value.selectedCategoryId)
        assertEquals("Food & Dining", vm.uiState.value.selectedCategoryName)

        // Type amount 50
        vm.onKeypadKeyPress("5")
        vm.onKeypadKeyPress("0")
        testScheduler.advanceUntilIdle()

        // Save directly without opening category picker
        vm.saveTransaction()
        testScheduler.advanceUntilIdle()

        assertNull("Save should succeed without error", vm.uiState.value.errorMessage)
        assertTrue("Transaction should be captured", capturedTx != null)
        assertEquals(101L, capturedTx?.categoryId)
        assertEquals("Food & Dining", capturedTx?.categoryName)
        job.cancel()
    }

    @Test
    fun `saveTransaction when repository returns failure resets isSaving and sets errorMessage`() = runTest {
        val account = AccountSummary(1L, "Main Bank", AccountGroup.BANK_ACCOUNT, "INR", MinorMoney(100000L))
        val txRepo = Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java)
        ) { _, method, _ ->
            when {
                method.returnType == kotlinx.coroutines.flow.Flow::class.java -> flowOf(emptyList<Any>())
                method.name.startsWith("upsertSingleTransaction") -> {
                    Result.failure<Long>(IllegalStateException("Database write failed"))
                }
                else -> {
                    Result.success(1L)
                }
            }
        } as TransactionRepository

        val vm = createViewModel(accounts = listOf(account), txRepo = txRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.onKeypadKeyPress("5")
        vm.onKeypadKeyPress("0")
        testScheduler.advanceUntilIdle()

        vm.saveTransaction()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse("isSaving lock must be released on repository failure", state.isSaving)
        assertTrue("Error message must be surfaced", !state.errorMessage.isNullOrBlank())
        job.cancel()
    }

    @Test
    fun `editing transaction on archived account preserves archived account without falling back to active account`() = runTest {
        val activeUsd = AccountSummary(1L, "Active Checking", AccountGroup.BANK_ACCOUNT, "USD", MinorMoney(1000_00L), isArchived = false)
        val archivedJpy = AccountSummary(2L, "Old Tokyo Bank", AccountGroup.BANK_ACCOUNT, "JPY", MinorMoney(50_000L), isArchived = true)
        var savedTx: TransactionItem? = null
        val txRepo = Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java)
        ) { _, method, args ->
            when {
                method.name.startsWith("upsertSingleTransaction") -> {
                    savedTx = args?.getOrNull(0) as? TransactionItem
                    Result.success(10L)
                }
                method.returnType == kotlinx.coroutines.flow.Flow::class.java -> flowOf(emptyList<Any>())
                else -> Result.success(1L)
            }
        } as TransactionRepository

        val vm = createViewModel(accounts = listOf(activeUsd, archivedJpy), txRepo = txRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        val historicalJpyTx = TransactionItem(
            id = 10L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 1700000000000L,
            ledgerDateEpochDay = 20000L,
            amount = MinorMoney(1500L), // ¥1,500
            currencyCode = "JPY",
            accountId = 2L,
            accountName = "Old Tokyo Bank",
            categoryId = 10L,
            categoryName = "Dining",
            payeeOrDescription = "Ramen"
        )
        assertTrue(vm.loadTransactionItemForEdit(historicalJpyTx))
        testScheduler.advanceUntilIdle()

        assertEquals(2L, vm.uiState.value.selectedAccount?.id)
        assertEquals("JPY", vm.uiState.value.currencySpec.code)

        vm.updateNote("Updated note")
        vm.saveTransaction()
        testScheduler.advanceUntilIdle()

        assertEquals(2L, savedTx?.accountId)
        assertEquals("JPY", savedTx?.currencyCode)
        assertEquals(1500L, savedTx?.amount?.amountMinor)
        job.cancel()
    }

    @Test
    fun `setMode switching between EXPENSE and INCOME clears opposite category, splits, and invalid recurring state`() = runTest {
        val account = AccountSummary(1L, "Main Bank", AccountGroup.BANK_ACCOUNT, "USD", MinorMoney(1000_00L))
        val expenseCat = CategoryNode(id = 10L, name = "Food", type = CategoryType.EXPENSE, parentId = null, colorHex = "#E11D48", iconName = "fastfood")
        val vm = createViewModel(accounts = listOf(account), expenseCategories = listOf(expenseCat))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.selectCategory(expenseCat)
        vm.toggleSplitMode()
        testScheduler.advanceUntilIdle()
        assertEquals(10L, vm.uiState.value.selectedCategoryId)
        assertEquals(2, vm.uiState.value.splitDrafts.size)

        vm.setMode(TransactionType.INCOME)
        testScheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.selectedCategoryId)
        assertNull(vm.uiState.value.selectedCategoryColorHex)
        assertEquals(0L, vm.uiState.value.splitDrafts[0].categoryId)

        vm.toggleRecurringMode()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.isRecurring)

        vm.setMode(TransactionType.REFUND)
        testScheduler.advanceUntilIdle()
        assertFalse("Recurring must clear when switching to REFUND", vm.uiState.value.isRecurring)
        job.cancel()
    }

    @Test
    fun `updateAmountInput preserves decimal comma on 2-decimal currency and strips decimals on JPY`() = runTest {
        val usdAcc = AccountSummary(1L, "USD", AccountGroup.BANK_ACCOUNT, "USD", MinorMoney(1000_00L))
        val jpyAcc = AccountSummary(2L, "JPY", AccountGroup.BANK_ACCOUNT, "JPY", MinorMoney(10000L))
        val vm = createViewModel(accounts = listOf(usdAcc, jpyAcc))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.updateAmountInput("12,50")
        testScheduler.advanceUntilIdle()
        assertEquals("12,50", vm.uiState.value.expressionBuffer)
        assertEquals(1250L, vm.uiState.value.evaluatedAmount.amountMinor)

        vm.selectSourceAccount(2L)
        testScheduler.advanceUntilIdle()
        vm.updateAmountInput("12,50")
        testScheduler.advanceUntilIdle()
        assertEquals("1250", vm.uiState.value.expressionBuffer)
        job.cancel()
    }

    @Test
    fun `selectSourceAccount clears EMI when leaving credit card and rescales USD decimals to JPY`() = runTest {
        val card = AccountSummary(1L, "Visa", AccountGroup.CREDIT_CARD, "USD", MinorMoney(0L))
        val jpyBank = AccountSummary(2L, "Tokyo", AccountGroup.BANK_ACCOUNT, "JPY", MinorMoney(50000L))
        val vm = createViewModel(accounts = listOf(card, jpyBank))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.updateAmountInput("12.50")
        vm.toggleInstallmentEnabled()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.isInstallmentEnabled)

        vm.selectSourceAccount(2L)
        testScheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.isInstallmentEnabled)
        assertEquals("13", vm.uiState.value.expressionBuffer)
        assertEquals(13L, vm.uiState.value.evaluatedAmount.amountMinor)
        job.cancel()
    }

    @Test
    fun `keypad equals preserves expression and surfaces error on division by zero`() = runTest {
        val account = AccountSummary(1L, "Main Bank", AccountGroup.BANK_ACCOUNT, "USD", MinorMoney(1000_00L))
        val vm = createViewModel(accounts = listOf(account))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.updateAmountInput("100÷0")
        vm.onKeypadKeyPress("=")
        testScheduler.advanceUntilIdle()

        assertEquals("100÷0", vm.uiState.value.expressionBuffer)
        assertTrue(!vm.uiState.value.errorMessage.isNullOrBlank())
        job.cancel()
    }

    @Test
    fun `applyBookmark rejects archived account cleanly`() = runTest {
        val activeAcc = AccountSummary(1L, "Active", AccountGroup.BANK_ACCOUNT, "USD", MinorMoney(100_00L), isArchived = false)
        val archivedAcc = AccountSummary(2L, "Archived", AccountGroup.BANK_ACCOUNT, "JPY", MinorMoney(5000L), isArchived = true)
        val vm = createViewModel(accounts = listOf(activeAcc, archivedAcc))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        val bookmark = com.folioledger.app.domain.model.TransactionBookmark(
            id = 5L,
            title = "Tokyo Lunch",
            type = TransactionType.EXPENSE,
            amount = MinorMoney(1200L),
            accountId = 2L,
            categoryId = 10L
        )
        vm.applyBookmark(bookmark)
        testScheduler.advanceUntilIdle()

        assertTrue(!vm.uiState.value.errorMessage.isNullOrBlank())
        assertEquals(1L, vm.uiState.value.selectedAccount?.id)
        job.cancel()
    }

    @Test
    fun `inspecting existing transaction has no unsaved changes until mutated`() = runTest {
        val account = AccountSummary(1L, "Main Bank", AccountGroup.BANK_ACCOUNT, "USD", MinorMoney(1000_00L))
        val expenseCat = CategoryNode(id = 10L, name = "Food", type = CategoryType.EXPENSE, parentId = null, colorHex = "#E11D48", iconName = "fastfood")
        val vm = createViewModel(accounts = listOf(account), expenseCategories = listOf(expenseCat))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        val tx = TransactionItem(
            id = 42L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(25_00L),
            currencyCode = "USD",
            accountId = 1L,
            accountName = "Main Bank",
            categoryId = 10L,
            payeeOrDescription = "Coffee Shop",
            note = "Morning latte",
            ledgerDateEpochDay = 20000L,
            timestampEpochMillis = 1_700_000_000_000L
        )
        assertTrue(vm.loadTransactionItemForEdit(tx))
        testScheduler.advanceUntilIdle()

        assertEquals(42L, vm.uiState.value.editingTransactionId)
        assertFalse(vm.uiState.value.hasUnsavedChanges)

        vm.updateNote("Updated note")
        testScheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.hasUnsavedChanges)
        job.cancel()
    }

    @Test
    fun `recurring toggles clear transferFeeInput and bump formSyncVersion`() = runTest {
        val bank1 = AccountSummary(1L, "Checking", AccountGroup.BANK_ACCOUNT, "USD", MinorMoney(1000_00L))
        val bank2 = AccountSummary(2L, "Savings", AccountGroup.BANK_ACCOUNT, "USD", MinorMoney(2000_00L))
        val vm = createViewModel(accounts = listOf(bank1, bank2))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.setMode(TransactionType.TRANSFER)
        vm.updateTransferFeeInput("15.00")
        testScheduler.advanceUntilIdle()
        val v0 = vm.uiState.value.formSyncVersion

        vm.toggleRecurringMode()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.isRecurring)
        assertEquals("", vm.uiState.value.transferFeeInput)
        assertTrue(vm.uiState.value.formSyncVersion > v0)

        vm.disableRecurringMode()
        vm.updateTransferFeeInput("9.50")
        testScheduler.advanceUntilIdle()
        val v1 = vm.uiState.value.formSyncVersion

        vm.setRecurringFrequency(RepeatFrequency.MONTHLY)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.isRecurring)
        assertEquals("", vm.uiState.value.transferFeeInput)
        assertTrue(vm.uiState.value.formSyncVersion > v1)
        job.cancel()
    }

    @Test
    fun `applyBookmark clears stale transferFeeInput and selectSourceAccount rescales fee to JPY`() = runTest {
        val usdAcc = AccountSummary(1L, "USD Bank", AccountGroup.BANK_ACCOUNT, "USD", MinorMoney(1000_00L))
        val jpyAcc = AccountSummary(2L, "JPY Bank", AccountGroup.BANK_ACCOUNT, "JPY", MinorMoney(50000L))
        val expenseCat = CategoryNode(id = 10L, name = "Food", type = CategoryType.EXPENSE, parentId = null, colorHex = "#E11D48", iconName = "fastfood")
        val vm = createViewModel(accounts = listOf(usdAcc, jpyAcc), expenseCategories = listOf(expenseCat))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.setMode(TransactionType.TRANSFER)
        vm.updateTransferFeeInput("12.50")
        testScheduler.advanceUntilIdle()
        val v0 = vm.uiState.value.formSyncVersion

        vm.selectSourceAccount(2L)
        testScheduler.advanceUntilIdle()
        assertEquals("13", vm.uiState.value.transferFeeInput)
        assertTrue(vm.uiState.value.formSyncVersion > v0)

        val bookmark = com.folioledger.app.domain.model.TransactionBookmark(
            id = 7L,
            title = "Coffee",
            type = TransactionType.EXPENSE,
            amount = MinorMoney(500L),
            accountId = 1L,
            categoryId = 10L
        )
        vm.applyBookmark(bookmark)
        testScheduler.advanceUntilIdle()
        assertEquals("", vm.uiState.value.transferFeeInput)
        assertTrue(vm.uiState.value.hasUnsavedChanges)
        job.cancel()
    }

    @Test
    fun `applyBookmark blocks silent destination fallback when transfer destination is archived`() = runTest {
        val checking = AccountSummary(1L, "Checking", AccountGroup.BANK_ACCOUNT, "USD", MinorMoney(1000_00L))
        val archivedDest = AccountSummary(2L, "Old Savings", AccountGroup.BANK_ACCOUNT, "USD", MinorMoney(500_00L), isArchived = true)
        val unrelatedActive = AccountSummary(3L, "Wallet", AccountGroup.CASH, "USD", MinorMoney(200_00L))
        var saveCalled = false
        val txRepo = Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java)
        ) { _, method, args ->
            when {
                method.name == "observePayeeSuggestions" -> flowOf(emptyList<Any>())
                method.name == "upsertSingleTransaction" -> {
                    saveCalled = true
                    Result.success(1L)
                }
                method.returnType == kotlinx.coroutines.flow.Flow::class.java -> flowOf(emptyList<Any>())
                args != null && args.isNotEmpty() && args.last() is kotlin.coroutines.Continuation<*> -> Result.success(1L)
                else -> null
            }
        } as TransactionRepository

        val vm = createViewModel(
            accounts = listOf(checking, archivedDest, unrelatedActive),
            txRepo = txRepo
        )
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        val transferBookmark = com.folioledger.app.domain.model.TransactionBookmark(
            id = 11L,
            title = "Monthly Savings",
            type = TransactionType.TRANSFER,
            amount = MinorMoney(250_00L),
            accountId = 1L,
            transferToAccountId = 2L
        )
        vm.applyBookmark(transferBookmark)
        testScheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.transferToAccount)
        assertTrue(vm.uiState.value.errorMessage?.contains("destination", ignoreCase = true) == true)

        vm.saveTransaction()
        testScheduler.advanceUntilIdle()
        assertFalse(saveCalled)
        job.cancel()
    }

    @Test
    fun `saveTransaction surfaces expression arithmetic error and validates transfer fee strictly`() = runTest {
        val bank1 = AccountSummary(1L, "Bank A", AccountGroup.BANK_ACCOUNT, "USD", MinorMoney(1000_00L))
        val bank2 = AccountSummary(2L, "Bank B", AccountGroup.BANK_ACCOUNT, "USD", MinorMoney(1000_00L))
        val cat = CategoryNode(10L, "Food", CategoryType.EXPENSE, null, "#E11D48", "fastfood")
        val vm = createViewModel(accounts = listOf(bank1, bank2), expenseCategories = listOf(cat))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.updateAmountInput("100÷0")
        vm.saveTransaction()
        testScheduler.advanceUntilIdle()
        val divError = vm.uiState.value.errorMessage.orEmpty()
        assertTrue(divError.isNotBlank())
        assertTrue(divError != "Enter an amount greater than zero.")

        vm.setMode(TransactionType.TRANSFER)
        vm.updateAmountInput("50")
        vm.updateTransferFeeInput("$5")
        vm.saveTransaction()
        testScheduler.advanceUntilIdle()
        assertEquals("Enter a valid transfer fee.", vm.uiState.value.errorMessage)

        vm.updateTransferFeeInput("0.00")
        var saved = false
        vm.saveTransaction { saved = true }
        testScheduler.advanceUntilIdle()
        assertTrue(saved)
        job.cancel()
    }

    @Test
    fun `due recurring logging guards toggles and rejects archived rule account fallback`() = runTest {
        val archivedCard = AccountSummary(1L, "Old Visa", AccountGroup.CREDIT_CARD, "USD", MinorMoney(0L), isArchived = true)
        val activeCard = AccountSummary(2L, "Active Visa", AccountGroup.CREDIT_CARD, "USD", MinorMoney(0L), isArchived = false)
        val rule1 = RecurringCommitmentItem(
            id = 5L,
            title = "Cloud",
            type = TransactionType.EXPENSE,
            amount = MinorMoney(10_00L),
            currencyCode = "USD",
            accountId = 2L,
            accountName = "Active Visa",
            categoryId = 10L,
            frequency = RepeatFrequency.MONTHLY,
            nextOccurrenceEpochDay = 20050L,
            payeeOrDescription = "Cloud Inc"
        )
        val ruleArchived = rule1.copy(id = 6L, accountId = 1L, accountName = "Old Visa")
        val analyticsRepo = Proxy.newProxyInstance(
            AnalyticsAndBudgetRepository::class.java.classLoader,
            arrayOf(AnalyticsAndBudgetRepository::class.java)
        ) { _, method, _ ->
            if (method.name == "observeAllRecurringRules") flowOf(listOf(rule1, ruleArchived)) else flowOf(emptyList<Any>())
        } as AnalyticsAndBudgetRepository

        val vm = createViewModel(accounts = listOf(archivedCard, activeCard), analyticsAndBudgetRepo = analyticsRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.loadRecurringRuleForEntry(ruleId = 5L, expectedDueEpochDay = 20050L)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.isLoggingDueRecurringRule)
        assertNull(vm.uiState.value.errorMessage)

        vm.toggleRecurringMode()
        vm.setRecurringFrequency(RepeatFrequency.WEEKLY)
        vm.toggleInstallmentEnabled()
        testScheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.isRecurring)
        assertFalse(vm.uiState.value.isInstallmentEnabled)

        vm.loadRecurringRuleForEntry(ruleId = 6L, expectedDueEpochDay = 20050L)
        testScheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.selectedAccount)
        assertTrue(vm.uiState.value.errorMessage?.contains("archived", ignoreCase = true) == true)
        job.cancel()
    }

    @Test
    fun `editing installment clears installment metadata on mode, account, or split changes`() = runTest {
        val card = AccountSummary(1L, "Card", AccountGroup.CREDIT_CARD, "USD", MinorMoney(0L))
        val bank = AccountSummary(2L, "Bank", AccountGroup.BANK_ACCOUNT, "USD", MinorMoney(1000_00L))
        val cat = CategoryNode(10L, "Electronics", CategoryType.EXPENSE, null, "#E11D48", "devices")
        val vm = createViewModel(accounts = listOf(card, bank), expenseCategories = listOf(cat))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        val emiTx = TransactionItem(
            id = 88L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(100_00L),
            currencyCode = "USD",
            accountId = 1L,
            accountName = "Card",
            categoryId = 10L,
            payeeOrDescription = "Phone",
            ledgerDateEpochDay = 20000L,
            timestampEpochMillis = 1_700_000_000_000L,
            installmentIndex = 1,
            installmentTotalCount = 6
        )

        vm.loadTransactionItemForEdit(emiTx)
        testScheduler.advanceUntilIdle()
        assertEquals(1, vm.uiState.value.installmentIndex)
        vm.setMode(TransactionType.INCOME)
        testScheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.installmentIndex)
        assertNull(vm.uiState.value.installmentTotalCount)

        vm.loadTransactionItemForEdit(emiTx)
        testScheduler.advanceUntilIdle()
        vm.selectSourceAccount(2L)
        testScheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.installmentIndex)
        assertNull(vm.uiState.value.installmentTotalCount)

        vm.loadTransactionItemForEdit(emiTx)
        testScheduler.advanceUntilIdle()
        vm.toggleSplitMode()
        testScheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.installmentIndex)
        assertNull(vm.uiState.value.installmentTotalCount)
        job.cancel()
    }

    @Test
    fun `input sanitizer caps decimals and strips leading zeros consistently and split bookmark save is rejected`() = runTest {
        assertEquals("12.34", EntryAmountInputSanitizer.sanitizeExpressionInput("12.349", 2))
        assertEquals("5", EntryAmountInputSanitizer.sanitizeExpressionInput("05", 2))
        assertEquals("0.", EntryAmountInputSanitizer.sanitizeExpressionInput(".", 2))
        assertEquals("12", EntryAmountInputSanitizer.sanitizeExpressionInput("12.50", 0))
        assertEquals("1250", EntryAmountInputSanitizer.sanitizeExpressionInput("1,250", 0))
        assertEquals("1250.50", EntryAmountInputSanitizer.sanitizeExpressionInput("1,250.50", 2))
        assertEquals("1250", EntryAmountInputSanitizer.sanitizeExpressionInput("1,250", 2))
        assertEquals("100000.00", EntryAmountInputSanitizer.sanitizeExpressionInput("1,00,000.00", 2))
        assertEquals("1250.50", EntryAmountInputSanitizer.sanitizeExpressionInput("1.250,50", 2))
        assertEquals("1250.50+2500.25", EntryAmountInputSanitizer.sanitizeExpressionInput("1,250.50+2,500.25", 2))
        val nearCapExpression = "1".repeat(62) + "+."
        assertTrue(EntryAmountInputSanitizer.sanitizeExpressionInput(nearCapExpression, 2).length <= 64)

        val bank = AccountSummary(1L, "Bank", AccountGroup.BANK_ACCOUNT, "USD", MinorMoney(1000_00L))
        val cat = CategoryNode(10L, "Food", CategoryType.EXPENSE, null, "#E11D48", "fastfood")
        val vm = createViewModel(accounts = listOf(bank), expenseCategories = listOf(cat))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.updateAmountInput("05.999")
        testScheduler.advanceUntilIdle()
        assertEquals("5.99", vm.uiState.value.expressionBuffer)

        vm.toggleSplitMode()
        testScheduler.advanceUntilIdle()
        val firstLineId = vm.uiState.value.splitDrafts.first().localId
        vm.updateSplitLineAmount(firstLineId, "10.555")
        testScheduler.advanceUntilIdle()
        assertEquals("10.55", vm.uiState.value.splitDrafts.first().amountInput)

        vm.saveCurrentAsBookmark("Split Lunch")
        testScheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.errorMessage?.contains("Split", ignoreCase = true) == true)
        job.cancel()
    }
}
