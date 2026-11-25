package com.folioledger.app.ui.accounts

import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NetWorthOverview
import com.folioledger.app.domain.model.TransactionSearchFilter
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.domain.repository.AccountRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class AccountsViewModelTest {

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
                    when (method.name) {
                        "observeUserPreferences" -> flowOf(UserPreferences())
                        "observeNetWorthOverview" -> flowOf(
                            NetWorthOverview(
                                totalAssets = MinorMoney.ZERO,
                                totalLiabilities = MinorMoney.ZERO,
                                netWorth = MinorMoney.ZERO,
                                accountsByGroup = emptyMap()
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
                else -> null
            }
        } as T
    }

    private fun createViewModel(
        accounts: List<AccountSummary> = emptyList(),
        accountRepoMock: AccountRepository? = null,
        txRepoMock: TransactionRepository? = null
    ): AccountsViewModel {
        val accountRepo = accountRepoMock ?: Proxy.newProxyInstance(
            AccountRepository::class.java.classLoader,
            arrayOf(AccountRepository::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "observeAllAccounts" -> flowOf(accounts)
                "observeActiveAccounts" -> flowOf(accounts)
                "observeNetWorthOverview" -> flowOf(
                    NetWorthOverview(
                        totalAssets = MinorMoney.ZERO,
                        totalLiabilities = MinorMoney.ZERO,
                        netWorth = MinorMoney.ZERO,
                        accountsByGroup = emptyMap()
                    )
                )
                else -> null
            }
        } as AccountRepository

        val txRepo = txRepoMock ?: Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "searchAndFilterTransactions" -> flowOf(emptyList<Any>())
                else -> null
            }
        } as TransactionRepository

        return AccountsViewModel(
            accountRepository = accountRepo,
            transactionRepository = txRepo,
            settingsAndBackupRepository = createStub<SettingsAndBackupRepository>(),
            computationDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun openCreateAccountDialog_initializesDialogState_withDefaults() = runTest {
        val bank = AccountSummary(1L, "HDFC Bank", AccountGroup.BANK_ACCOUNT, "INR", MinorMoney(50000_00L))
        val vm = createViewModel(accounts = listOf(bank))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.openCreateAccountDialog(AccountGroup.BANK_ACCOUNT)
        testScheduler.advanceUntilIdle()

        val dialog = vm.uiState.value.editorDialogState
        assertNotNull(dialog)
        assertEquals(AccountGroup.BANK_ACCOUNT, dialog?.groupType)
        assertFalse(dialog?.isEditing == true)
        assertNull(dialog?.validationError)

        vm.dismissAccountEditorDialog()
        testScheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.editorDialogState)
        job.cancel()
    }

    @Test
    fun saveAccountFromDialog_withBlankName_setsValidationError() = runTest {
        val vm = createViewModel()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.openCreateAccountDialog(AccountGroup.CASH)
        testScheduler.advanceUntilIdle()

        // Name is left blank ("")
        vm.updateEditorDialogState { it.copy(name = "") }
        testScheduler.advanceUntilIdle()

        vm.saveAccountFromDialog()
        testScheduler.advanceUntilIdle()

        val dialog = vm.uiState.value.editorDialogState
        assertNotNull(dialog)
        assertEquals("Account name is required.", dialog?.validationError)
        job.cancel()
    }

    @Test
    fun openReconcileDialog_andSubmit_triggersReconciliation() = runTest {
        var reconciledAccountId: Long? = null
        var reconciledAmount: MinorMoney? = null
        val accountRepo = Proxy.newProxyInstance(
            AccountRepository::class.java.classLoader,
            arrayOf(AccountRepository::class.java)
        ) { _, method, args ->
            when {
                method.name.startsWith("observeAllAccounts") -> flowOf(emptyList<AccountSummary>())
                method.name.startsWith("observeActiveAccounts") -> flowOf(emptyList<AccountSummary>())
                method.name.startsWith("observeNetWorthOverview") -> flowOf(
                    NetWorthOverview(
                        totalAssets = MinorMoney.ZERO,
                        totalLiabilities = MinorMoney.ZERO,
                        netWorth = MinorMoney.ZERO,
                        accountsByGroup = emptyMap()
                    )
                )
                method.name.startsWith("reconcileAccountBalance") -> {
                    reconciledAccountId = args?.getOrNull(0) as? Long
                    val rawAmount = args?.getOrNull(1)
                    reconciledAmount = when (rawAmount) {
                        is MinorMoney -> rawAmount
                        is Long -> MinorMoney(rawAmount)
                        else -> null
                    }
                    Result.success(1L)
                }
                args != null && args.isNotEmpty() && args.last() is kotlin.coroutines.Continuation<*> -> Result.success(1L)
                else -> null
            }
        } as AccountRepository

        val vm = createViewModel(accountRepoMock = accountRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        val account = AccountSummary(
            id = 42L,
            name = "Salary Checking",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(10000_00L)
        )
        vm.openReconcileDialog(account)
        testScheduler.advanceUntilIdle()

        val dialog = vm.uiState.value.reconcileDialogState
        assertNotNull(dialog)
        assertEquals(42L, dialog?.accountId)

        // Set target balance to 12,000
        vm.updateReconcileDialogState { it.copy(targetBalanceInput = "12000") }
        testScheduler.advanceUntilIdle()

        vm.submitReconcileDialog()
        testScheduler.advanceUntilIdle()

        assertNull("Dialog should be dismissed after submission", vm.uiState.value.reconcileDialogState)
        assertEquals(42L, reconciledAccountId)
        assertEquals(MinorMoney(12000_00L), reconciledAmount)
        job.cancel()
    }

    @Test
    fun openSettlementSheetForAccountId_onEmptyAccounts_timesOutSafelyWithoutHanging() = runTest {
        val vm = createViewModel(accounts = emptyList())
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        // Calling on an empty accounts list should time out safely after 3_000ms virtual delay
        vm.openSettlementSheetForAccountId(999L)
        testScheduler.advanceUntilIdle()

        assertNull("Settlement sheet should not open when account is absent", vm.uiState.value.settlementSheetState)
        job.cancel()
    }

    @Test
    fun openSettlementSheetForAccountId_rejectsInvalidNonCardAndArchivedAccounts() = runTest {
        val checkingAccount = AccountSummary(
            id = 10L,
            name = "Checking",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "USD",
            currentBalance = MinorMoney(5000_00L)
        )
        val archivedCard = AccountSummary(
            id = 11L,
            name = "Old Card",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "USD",
            currentBalance = MinorMoney(-1000_00L),
            isArchived = true
        )
        val activeCard = AccountSummary(
            id = 12L,
            name = "Active Card",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "USD",
            currentBalance = MinorMoney(-2500_00L),
            isArchived = false
        )
        val vm = createViewModel(accounts = listOf(checkingAccount, archivedCard, activeCard))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.openSettlementSheetForAccountId(0L)
        vm.openSettlementSheetForAccountId(-5L)
        vm.openSettlementSheetForAccountId(10L) // non-credit-card
        vm.openSettlementSheetForAccountId(11L) // archived credit card
        testScheduler.advanceUntilIdle()
        assertNull("Settlement sheet must not open for invalid/non-card/archived accounts", vm.uiState.value.settlementSheetState)

        vm.openSettlementSheetForAccountId(12L)
        testScheduler.advanceUntilIdle()
        assertEquals(12L, vm.uiState.value.settlementSheetState?.creditCardAccount?.id)
        job.cancel()
    }

    @Test
    fun submitReconcileDialog_whenRapidDoubleTapped_atomicallyExecutesOnlyOnce() = runTest {
        var reconcileCount = 0
        val accountRepo = Proxy.newProxyInstance(
            AccountRepository::class.java.classLoader,
            arrayOf(AccountRepository::class.java)
        ) { _, method, _ ->
            when {
                method.name.startsWith("observeAllAccounts") -> flowOf(emptyList<AccountSummary>())
                method.name.startsWith("observeActiveAccounts") -> flowOf(emptyList<AccountSummary>())
                method.name.startsWith("reconcileAccountBalance") -> {
                    reconcileCount++
                    Result.success(100L)
                }
                else -> null
            }
        } as AccountRepository

        val vm = createViewModel(accountRepoMock = accountRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        val account = AccountSummary(
            id = 42L,
            name = "Salary Checking",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(10000_00L)
        )
        vm.openReconcileDialog(account)
        testScheduler.advanceUntilIdle()

        vm.updateReconcileDialogState { it.copy(targetBalanceInput = "12000") }
        testScheduler.advanceUntilIdle()

        // Rapid double invocation before dispatching coroutines
        vm.submitReconcileDialog()
        vm.submitReconcileDialog()
        testScheduler.advanceUntilIdle()

        assertEquals("Reconcile balance must be invoked exactly once even on rapid double tap", 1, reconcileCount)
        job.cancel()
    }
}
