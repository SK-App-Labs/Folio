/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

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

    private inline fun <reified T : Any> createStub(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
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
                            accountsByGroup = emptyMap(),
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

            else -> null
        }
    } as T

    private class FakeAccountRepository(
        private val accounts: List<AccountSummary> = emptyList(),
        private val onReconcile: suspend (Long, MinorMoney, Long, String) -> Result<Long> = { _, _, _, _ -> Result.success(1L) },
        private val onUpsert: suspend (AccountSummary, MinorMoney, Long) -> Result<Long> = { _, _, _ -> Result.success(1L) },
    ) : AccountRepository {
        override fun observeNetWorthOverview(referenceDateEpochDay: Long) = flowOf(
            NetWorthOverview(
                totalAssets = MinorMoney.ZERO,
                totalLiabilities = MinorMoney.ZERO,
                netWorth = MinorMoney.ZERO,
                accountsByGroup = emptyMap(),
            ),
        )
        override fun observeActiveAccounts(referenceDateEpochDay: Long) = flowOf(accounts.filter { !it.isArchived })
        override fun observeAllAccounts(referenceDateEpochDay: Long) = flowOf(accounts)
        override fun observeAccountById(accountId: Long, referenceDateEpochDay: Long) = flowOf(accounts.firstOrNull { it.id == accountId })
        override suspend fun upsertAccount(account: AccountSummary, openingBalance: MinorMoney, openingDateEpochDay: Long) = onUpsert(account, openingBalance, openingDateEpochDay)
        override suspend fun setAccountArchived(accountId: Long, isArchived: Boolean) = Result.success(Unit)
        override suspend fun setAccountPinned(accountId: Long, isPinned: Boolean) = Result.success(Unit)
        override suspend fun toggleAccountPinned(accountId: Long) = Result.success(Unit)
        override suspend fun deleteAccount(accountId: Long, deleteTransactions: Boolean) = Result.success(Unit)
        override suspend fun getTransactionCountForAccount(accountId: Long) = 0
        override suspend fun settleCreditCardStatement(
            sourceAssetAccountId: Long,
            creditCardAccountId: Long,
            settlementAmount: MinorMoney,
            timestampEpochMillis: Long,
            ledgerDateEpochDay: Long,
            note: String,
        ) = Result.success(1L)
        override suspend fun reconcileAccountBalance(
            accountId: Long,
            reconciledBalance: MinorMoney,
            reconciledDateEpochDay: Long,
            note: String,
        ) = onReconcile(accountId, reconciledBalance, reconciledDateEpochDay, note)
    }

    private fun createViewModel(
        accounts: List<AccountSummary> = emptyList(),
        accountRepoMock: AccountRepository? = null,
        txRepoMock: TransactionRepository? = null,
    ): AccountsViewModel {
        val accountRepo = accountRepoMock ?: FakeAccountRepository(accounts = accounts)

        val txRepo = txRepoMock ?: Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java),
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
        val accountRepo = FakeAccountRepository(
            onReconcile = { accountId, amount, _, _ ->
                reconciledAccountId = accountId
                reconciledAmount = amount
                Result.success(1L)
            },
        )

        val vm = createViewModel(accountRepoMock = accountRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        val account = AccountSummary(
            id = 42L,
            name = "Salary Checking",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(10000_00L),
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
            currentBalance = MinorMoney(5000_00L),
        )
        val archivedCard = AccountSummary(
            id = 11L,
            name = "Old Card",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "USD",
            currentBalance = MinorMoney(-1000_00L),
            isArchived = true,
        )
        val activeCard = AccountSummary(
            id = 12L,
            name = "Active Card",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "USD",
            currentBalance = MinorMoney(-2500_00L),
            isArchived = false,
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
        val accountRepo = FakeAccountRepository(
            onReconcile = { _, _, _, _ ->
                reconcileCount++
                Result.success(100L)
            },
        )

        val vm = createViewModel(accountRepoMock = accountRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        val account = AccountSummary(
            id = 42L,
            name = "Salary Checking",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(10000_00L),
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

    @Test
    fun parseInputToMinorOrZero_handlesBlankSignOnlyZerosAndSignedExpressionsSafely() {
        assertNull(parseInputToMinorOrZero("", 2, allowBlankAsZero = false))
        assertEquals(MinorMoney.ZERO, parseInputToMinorOrZero("", 2, allowBlankAsZero = true))
        assertNull(parseInputToMinorOrZero("-", 2))
        assertNull(parseInputToMinorOrZero("+", 2))
        assertEquals(MinorMoney.ZERO, parseInputToMinorOrZero("0.000", 3))
        assertEquals(MinorMoney.ZERO, parseInputToMinorOrZero("0,00", 2))
        assertEquals(MinorMoney.ZERO, parseInputToMinorOrZero("-0", 2))
        assertEquals(MinorMoney.ZERO, parseInputToMinorOrZero("100-100", 2))
        assertEquals(MinorMoney(50_00L), parseInputToMinorOrZero("-50+100", 2))
        assertEquals(MinorMoney(-25_50L), parseInputToMinorOrZero("-25.50", 2))
    }

    @Test
    fun reconcileFailurePreservesDialogWithError_andEditsClearValidationError() = runTest {
        val accountRepo = FakeAccountRepository(
            onReconcile = { _, _, _, _ -> Result.failure(IllegalStateException("Disk full")) },
        )

        val vm = createViewModel(accountRepoMock = accountRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        val account = AccountSummary(1L, "Checking", AccountGroup.BANK_ACCOUNT, "USD", MinorMoney(500_00L))
        vm.openReconcileDialog(account)
        vm.updateReconcileDialogState { it.copy(targetBalanceInput = "600") }
        vm.submitReconcileDialog()
        testScheduler.advanceUntilIdle()

        val dialogAfterFailure = vm.uiState.value.reconcileDialogState
        assertEquals("Disk full", dialogAfterFailure?.validationError)
        assertEquals("600", dialogAfterFailure?.targetBalanceInput)

        vm.updateReconcileDialogState { it.copy(targetBalanceInput = "650") }
        testScheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.reconcileDialogState?.validationError)
        job.cancel()
    }

    @Test
    fun saveAccountFromDialog_rejectsNegativeCreditCardDebtAndDoubleSubmit_andOpenSettlementRejectsArchivedCard() = runTest {
        var upsertCount = 0
        val archivedCard = AccountSummary(
            id = 55L,
            name = "Archived Platinum",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "USD",
            currentBalance = MinorMoney(-100_00L),
            isArchived = true,
        )
        val accountRepo = FakeAccountRepository(
            accounts = listOf(archivedCard),
            onUpsert = { _, _, _ ->
                upsertCount++
                Result.success(10L)
            },
        )

        val vm = createViewModel(accounts = listOf(archivedCard), accountRepoMock = accountRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.openSettlementSheet(archivedCard)
        testScheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.settlementSheetState)
        assertEquals("Unarchive this credit card before recording a settlement.", vm.uiState.value.snackbarMessage)

        vm.openReconcileDialog(archivedCard)
        testScheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.reconcileDialogState)
        assertEquals("Unarchive this account before reconciling its balance.", vm.uiState.value.snackbarMessage)

        vm.openCreateAccountDialog(AccountGroup.CREDIT_CARD)
        vm.updateEditorDialogState {
            it.copy(name = "New Card", creditLimitInput = "5000", statementPayableInput = "-500")
        }
        vm.saveAccountFromDialog()
        testScheduler.advanceUntilIdle()
        assertEquals(0, upsertCount)
        assertEquals(
            "Enter a valid non-negative Billed Statement Due amount.",
            vm.uiState.value.editorDialogState?.validationError,
        )

        vm.updateEditorDialogState { it.copy(statementPayableInput = "100") }
        testScheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.editorDialogState?.validationError)

        vm.saveAccountFromDialog()
        vm.saveAccountFromDialog()
        testScheduler.advanceUntilIdle()
        assertEquals(1, upsertCount)
        job.cancel()
    }
}
