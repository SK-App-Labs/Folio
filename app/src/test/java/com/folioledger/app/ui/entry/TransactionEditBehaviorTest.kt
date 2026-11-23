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
        txRepo: TransactionRepository? = null
    ): TransactionEntryViewModel {
        val accountRepo = Proxy.newProxyInstance(
            AccountRepository::class.java.classLoader,
            arrayOf(AccountRepository::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "observeActiveAccounts", "observeAllAccounts" -> flowOf(accounts)
                else -> null
            }
        } as AccountRepository

        val catRepo = Proxy.newProxyInstance(
            CategoryAndTagRepository::class.java.classLoader,
            arrayOf(CategoryAndTagRepository::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "observeCategoryTree" -> flowOf(expenseCategories)
                else -> flowOf(emptyList<Any>())
            }
        } as CategoryAndTagRepository

        return TransactionEntryViewModel(
            transactionRepository = txRepo ?: createStub<TransactionRepository>(),
            accountRepository = accountRepo,
            categoryAndTagRepository = catRepo,
            settingsAndBackupRepository = createStub<SettingsAndBackupRepository>(),
            analyticsAndBudgetRepository = analyticsAndBudgetRepo,
            computationDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
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
        var advanceRecurringScheduleCalled = false
        var capturedRuleId: Long? = null
        var capturedDueEpochDay: Long? = null
        val analyticsRepo = Proxy.newProxyInstance(
            AnalyticsAndBudgetRepository::class.java.classLoader,
            arrayOf(AnalyticsAndBudgetRepository::class.java),
            java.lang.reflect.InvocationHandler { _, method, args ->
                when {
                    method?.name == "observeAllRecurringRules" -> flowOf(listOf(recurringRule))
                    method?.name?.startsWith("advanceRecurringScheduleIfDue") == true -> {
                        advanceRecurringScheduleCalled = true
                        capturedRuleId = args?.getOrNull(0) as? Long
                        capturedDueEpochDay = args?.getOrNull(1) as? Long
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

        // Load recurring rule for a new transaction entry (existingId == 0L)
        vm.loadRecurringRuleForEntry(42L)
        testScheduler.advanceUntilIdle()

        // Save new transaction (existingId == 0L)
        vm.saveTransaction()
        testScheduler.advanceUntilIdle()

        assertTrue("Logging new transaction from recurring rule MUST advance recurring rule schedule", advanceRecurringScheduleCalled)
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
        var advanceRecurringScheduleCalled = false
        var capturedDueEpochDay: Long? = null
        val analyticsRepo = Proxy.newProxyInstance(
            AnalyticsAndBudgetRepository::class.java.classLoader,
            arrayOf(AnalyticsAndBudgetRepository::class.java),
            java.lang.reflect.InvocationHandler { _, method, args ->
                when {
                    method?.name == "observeAllRecurringRules" -> flowOf(listOf(recurringRule))
                    method?.name?.startsWith("advanceRecurringScheduleIfDue") == true -> {
                        advanceRecurringScheduleCalled = true
                        capturedDueEpochDay = args?.getOrNull(1) as? Long
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

        vm.loadRecurringRuleForEntry(42L)
        testScheduler.advanceUntilIdle()

        // User edits transaction date (e.g. paid early on 20028L)
        vm.setLedgerDateEpochDay(20028L)
        testScheduler.advanceUntilIdle()

        vm.saveTransaction()
        testScheduler.advanceUntilIdle()

        assertTrue("Schedule advancement must still be called", advanceRecurringScheduleCalled)
        assertEquals("Advancement must pass the rule's original due date (20030L), not edited ledger date", 20030L, capturedDueEpochDay)
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
}
