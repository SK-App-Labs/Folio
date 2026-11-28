/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.data

import com.folioledger.app.data.local.FolioDatabase
import com.folioledger.app.data.local.dao.AccountDao
import com.folioledger.app.data.local.dao.BudgetAndRecurringDao
import com.folioledger.app.data.local.dao.CategoryAndTagDao
import com.folioledger.app.data.local.dao.TransactionDao
import com.folioledger.app.data.local.entity.AccountEntity
import com.folioledger.app.data.local.entity.RecurringRuleEntity
import com.folioledger.app.data.local.entity.TransactionEntity
import com.folioledger.app.data.local.entity.TransactionWithDetails
import com.folioledger.app.data.repository.TransactionRepositoryImpl
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.RepeatFrequency
import com.folioledger.app.domain.model.SplitLineItem
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class TransactionRepositoryImmutabilityTest {

    private val testAccount = AccountEntity(
        id = 1L,
        name = "Checking",
        currencyCode = "USD",
        groupType = AccountGroup.BANK_ACCOUNT,
        openingBalanceMinor = 1000_00L,
    )
    private val testSavingsAccount = AccountEntity(
        id = 2L,
        name = "Savings",
        currencyCode = "USD",
        groupType = AccountGroup.SAVINGS,
        openingBalanceMinor = 500_00L,
    )
    private val testCreditCard = AccountEntity(
        id = 3L,
        name = "Sapphire Card",
        currencyCode = "USD",
        groupType = AccountGroup.CREDIT_CARD,
        openingBalanceMinor = 0L,
    )

    private val existingTransactionsMap = mutableMapOf<Long, TransactionWithDetails>()
    private val persistedTransactions = mutableListOf<TransactionEntity>()
    private val persistedRules = mutableMapOf<Long, RecurringRuleEntity>()
    private val deletedPlanIds = mutableListOf<Long>()
    private var existingOccurrenceOverride = false
    private var remainingPlanTxnsCount = 0

    private val accountDao: AccountDao = createStub { methodName, args ->
        if (methodName == "getAccountByIdOnce") {
            when (args?.get(0) as? Long) {
                1L -> testAccount
                2L -> testSavingsAccount
                3L -> testCreditCard
                else -> null
            }
        } else {
            null
        }
    }

    private val transactionDao: TransactionDao = createStub { methodName, args ->
        when (methodName) {
            "getTransactionByIdOnce" -> {
                val id = args?.get(0) as? Long
                existingTransactionsMap[id]
            }

            "upsertTransactionWithDetails" -> {
                val entity = args?.get(0) as? TransactionEntity
                if (entity != null) persistedTransactions.add(entity)
                101L
            }

            "existsRecurringOccurrence" -> existingOccurrenceOverride

            "countTransactionsForInstallmentPlan" -> remainingPlanTxnsCount

            "deleteInstallmentPlanById" -> {
                val planId = args?.get(0) as? Long
                if (planId != null) deletedPlanIds.add(planId)
                Unit
            }

            else -> null
        }
    }

    private val categoryAndTagDao: CategoryAndTagDao = createStub()
    private val budgetAndRecurringDao: BudgetAndRecurringDao = createStub { methodName, args ->
        when (methodName) {
            "getRecurringRuleByIdOnce" -> {
                val id = args?.get(0) as? Long
                persistedRules[id]
            }

            "updateRecurringRule" -> {
                val rule = args?.get(0) as? RecurringRuleEntity
                if (rule != null) persistedRules[rule.id] = rule
                Unit
            }

            "upsertRecurringRule" -> {
                val rule = args?.get(0) as? RecurringRuleEntity
                if (rule != null) persistedRules[rule.id.takeIf { it > 0L } ?: 77L] = rule
                77L
            }

            else -> null
        }
    }

    private lateinit var repository: TransactionRepositoryImpl

    @Before
    fun setup() {
        existingTransactionsMap.clear()
        persistedTransactions.clear()
        persistedRules.clear()
        deletedPlanIds.clear()
        existingOccurrenceOverride = false
        remainingPlanTxnsCount = 0
        repository = TransactionRepositoryImpl(
            transactionDao = transactionDao,
            accountDao = accountDao,
            categoryAndTagDao = categoryAndTagDao,
            budgetAndRecurringDao = budgetAndRecurringDao,
            database = null,
        )
    }

    @Test
    fun upsertSingleTransaction_whenPersistedRowIsOpeningBalance_throwsIllegalArgumentException() = runTest {
        existingTransactionsMap[42L] = TransactionWithDetails(
            transaction = TransactionEntity(
                id = 42L,
                type = TransactionType.OPENING_BALANCE,
                amountMinor = 500_00L,
                currencyCode = "USD",
                accountId = 1L,
                timestampEpochMillis = 1000L,
                ledgerDateEpochDay = 100L,
            ),
            splits = emptyList(),
            tags = emptyList(),
        )

        val incomingUpdate = TransactionItem(
            id = 42L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(500_00L),
            currencyCode = "USD",
            accountId = 1L,
            accountName = "Checking",
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 100L,
            payeeOrDescription = "Overwritten",
        )

        val result = repository.upsertSingleTransaction(incomingUpdate)
        assertTrue("Expected failure modifying OPENING_BALANCE row", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(result.exceptionOrNull()?.message?.contains("Opening balance and reconciliation entries cannot be modified directly") == true)
    }

    @Test
    fun upsertSingleTransaction_whenPersistedRowIsReconciliation_throwsIllegalArgumentException() = runTest {
        existingTransactionsMap[43L] = TransactionWithDetails(
            transaction = TransactionEntity(
                id = 43L,
                type = TransactionType.RECONCILIATION,
                amountMinor = 20_00L,
                currencyCode = "USD",
                accountId = 1L,
                timestampEpochMillis = 1000L,
                ledgerDateEpochDay = 100L,
            ),
            splits = emptyList(),
            tags = emptyList(),
        )

        val incomingUpdate = TransactionItem(
            id = 43L,
            type = TransactionType.INCOME,
            amount = MinorMoney(20_00L),
            currencyCode = "USD",
            accountId = 1L,
            accountName = "Checking",
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 100L,
            payeeOrDescription = "Overwritten",
        )

        val result = repository.upsertSingleTransaction(incomingUpdate)
        assertTrue("Expected failure modifying RECONCILIATION row", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun deleteTransaction_whenPersistedRowIsReconciliation_throwsIllegalArgumentException() = runTest {
        existingTransactionsMap[44L] = TransactionWithDetails(
            transaction = TransactionEntity(
                id = 44L,
                type = TransactionType.RECONCILIATION,
                amountMinor = 15_00L,
                currencyCode = "USD",
                accountId = 1L,
                timestampEpochMillis = 1000L,
                ledgerDateEpochDay = 100L,
            ),
            splits = emptyList(),
            tags = emptyList(),
        )

        val result = repository.deleteTransaction(44L)
        assertTrue("Expected failure deleting RECONCILIATION row", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(result.exceptionOrNull()?.message?.contains("Reconciliation transactions are immutable") == true)
    }

    @Test
    fun upsertSplitTransaction_whenPersistedRowIsOpeningBalance_throwsIllegalArgumentException() = runTest {
        existingTransactionsMap[50L] = TransactionWithDetails(
            transaction = TransactionEntity(
                id = 50L,
                type = TransactionType.OPENING_BALANCE,
                amountMinor = 100_00L,
                currencyCode = "USD",
                accountId = 1L,
                timestampEpochMillis = 1000L,
                ledgerDateEpochDay = 100L,
            ),
            splits = emptyList(),
            tags = emptyList(),
        )

        val splitUpdate = TransactionItem(
            id = 50L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(100_00L),
            currencyCode = "USD",
            accountId = 1L,
            accountName = "Checking",
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 100L,
            isSplit = true,
            splits = listOf(
                SplitLineItem(categoryId = 10L, amount = MinorMoney(60_00L)),
                SplitLineItem(categoryId = 11L, amount = MinorMoney(40_00L)),
            ),
        )

        val result = repository.upsertSplitTransaction(splitUpdate)
        assertTrue("Expected failure modifying OPENING_BALANCE row via split", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(result.exceptionOrNull()?.message?.contains("Opening balance and reconciliation entries cannot be modified directly") == true)
    }

    @Test
    fun upsertSplitTransaction_whenPersistedRowIsReconciliation_throwsIllegalArgumentException() = runTest {
        existingTransactionsMap[51L] = TransactionWithDetails(
            transaction = TransactionEntity(
                id = 51L,
                type = TransactionType.RECONCILIATION,
                amountMinor = 80_00L,
                currencyCode = "USD",
                accountId = 1L,
                timestampEpochMillis = 1000L,
                ledgerDateEpochDay = 100L,
            ),
            splits = emptyList(),
            tags = emptyList(),
        )

        val splitUpdate = TransactionItem(
            id = 51L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(80_00L),
            currencyCode = "USD",
            accountId = 1L,
            accountName = "Checking",
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 100L,
            isSplit = true,
            splits = listOf(
                SplitLineItem(categoryId = 10L, amount = MinorMoney(50_00L)),
                SplitLineItem(categoryId = 11L, amount = MinorMoney(30_00L)),
            ),
        )

        val result = repository.upsertSplitTransaction(splitUpdate)
        assertTrue("Expected failure modifying RECONCILIATION row via split", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun createInstallmentPlanTransactions_whenReplaceTransactionIsOpeningBalance_throwsIllegalArgumentException() = runTest {
        existingTransactionsMap[52L] = TransactionWithDetails(
            transaction = TransactionEntity(
                id = 52L,
                type = TransactionType.OPENING_BALANCE,
                amountMinor = 300_00L,
                currencyCode = "USD",
                accountId = 1L,
                timestampEpochMillis = 1000L,
                ledgerDateEpochDay = 100L,
            ),
            splits = emptyList(),
            tags = emptyList(),
        )

        val baseTransaction = TransactionItem(
            id = 0L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(300_00L),
            currencyCode = "USD",
            accountId = 1L,
            accountName = "Checking",
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 100L,
        )

        val result = repository.createInstallmentPlanTransactions(
            baseTransaction = baseTransaction,
            totalInstallmentAmount = MinorMoney(300_00L),
            monthsCount = 3,
            startDateEpochDay = 100L,
            replaceTransactionId = 52L,
        )
        assertTrue("Expected failure replacing OPENING_BALANCE with installment plan", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(result.exceptionOrNull()?.message?.contains("Opening balance and reconciliation entries cannot be replaced") == true)
    }

    @Test
    fun createInstallmentPlanTransactions_whenReplaceTransactionIsReconciliation_throwsIllegalArgumentException() = runTest {
        existingTransactionsMap[53L] = TransactionWithDetails(
            transaction = TransactionEntity(
                id = 53L,
                type = TransactionType.RECONCILIATION,
                amountMinor = 200_00L,
                currencyCode = "USD",
                accountId = 1L,
                timestampEpochMillis = 1000L,
                ledgerDateEpochDay = 100L,
            ),
            splits = emptyList(),
            tags = emptyList(),
        )

        val baseTransaction = TransactionItem(
            id = 0L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(200_00L),
            currencyCode = "USD",
            accountId = 1L,
            accountName = "Checking",
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 100L,
        )

        val result = repository.createInstallmentPlanTransactions(
            baseTransaction = baseTransaction,
            totalInstallmentAmount = MinorMoney(200_00L),
            monthsCount = 2,
            startDateEpochDay = 100L,
            replaceTransactionId = 53L,
        )
        assertTrue("Expected failure replacing RECONCILIATION with installment plan", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(result.exceptionOrNull()?.message?.contains("Opening balance and reconciliation entries cannot be replaced") == true)
    }

    @Test
    fun upsertSplitTransaction_rejectsTransferAndCcSettlementTypes() = runTest {
        val transferSplit = TransactionItem(
            id = 0L,
            type = TransactionType.TRANSFER,
            amount = MinorMoney(100_00L),
            currencyCode = "USD",
            accountId = 1L,
            accountName = "Checking",
            transferToAccountId = 2L,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 100L,
            isSplit = true,
            splits = listOf(
                SplitLineItem(categoryId = 10L, amount = MinorMoney(60_00L)),
                SplitLineItem(categoryId = 11L, amount = MinorMoney(40_00L)),
            ),
        )
        val transferResult = repository.upsertSplitTransaction(transferSplit)
        assertTrue(transferResult.isFailure)
        assertTrue(transferResult.exceptionOrNull() is IllegalArgumentException)

        val settlementResult = repository.upsertSplitTransaction(transferSplit.copy(type = TransactionType.CC_SETTLEMENT))
        assertTrue(settlementResult.isFailure)
        assertTrue(settlementResult.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun upsertSingleTransaction_ccSettlementTargetingNonCreditCard_throwsIllegalArgumentException() = runTest {
        val invalidSettlement = TransactionItem(
            id = 0L,
            type = TransactionType.CC_SETTLEMENT,
            amount = MinorMoney(250_00L),
            currencyCode = "USD",
            accountId = 1L,
            accountName = "Checking",
            transferToAccountId = 2L, // SAVINGS account, not CREDIT_CARD
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 100L,
        )

        val result = repository.upsertSingleTransaction(invalidSettlement)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun upsertSingleTransaction_alwaysEnforcesAccountCurrencyCode() = runTest {
        val mismatchedCurrencyTx = TransactionItem(
            id = 0L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(50_00L),
            currencyCode = "EUR", // Checking account is USD
            accountId = 1L,
            accountName = "Checking",
            categoryId = 10L,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 100L,
        )

        val result = repository.upsertSingleTransaction(mismatchedCurrencyTx)
        assertTrue(result.isSuccess)
        assertEquals("USD", persistedTransactions.single().currencyCode)
    }

    @Test
    fun createInstallmentPlanTransactions_nonCreditCardAccount_throwsIllegalArgumentException() = runTest {
        val bankTx = TransactionItem(
            id = 0L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(300_00L),
            currencyCode = "USD",
            accountId = 1L, // Checking (BANK_ACCOUNT)
            accountName = "Checking",
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 100L,
        )
        val result = repository.createInstallmentPlanTransactions(
            baseTransaction = bankTx,
            totalInstallmentAmount = MinorMoney(300_00L),
            monthsCount = 3,
            startDateEpochDay = 100L,
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun deleteTransaction_lastInstallmentLegDeletesOrphanedPlan() = runTest {
        existingTransactionsMap[99L] = TransactionWithDetails(
            transaction = TransactionEntity(
                id = 99L,
                type = TransactionType.EXPENSE,
                amountMinor = 100_00L,
                currencyCode = "USD",
                accountId = 3L,
                timestampEpochMillis = 1000L,
                ledgerDateEpochDay = 100L,
                installmentPlanId = 555L,
                installmentIndex = 1,
                installmentTotalCount = 3,
            ),
            splits = emptyList(),
            tags = emptyList(),
        )
        remainingPlanTxnsCount = 0

        val result = repository.deleteTransaction(99L)
        assertTrue(result.isSuccess)
        assertEquals(listOf(555L), deletedPlanIds)
    }

    @Test
    fun logDueRecurringTransaction_rejectsDuplicateAndAtomicallyAdvancesSchedule() = runTest {
        persistedRules[88L] = RecurringRuleEntity(
            id = 88L,
            title = "Internet",
            type = TransactionType.EXPENSE,
            amountMinor = 50_00L,
            currencyCode = "USD",
            accountId = 1L,
            categoryId = 10L,
            frequency = RepeatFrequency.EVERY_MONTH,
            startDateEpochDay = 20000L,
            nextOccurrenceEpochDay = 20000L,
        )
        val tx = TransactionItem(
            id = 0L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(50_00L),
            currencyCode = "USD",
            accountId = 1L,
            accountName = "Checking",
            categoryId = 10L,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 20000L,
        )

        val first = repository.logDueRecurringTransaction(tx, ruleId = 88L, currentDueEpochDay = 20000L)
        assertTrue(first.isSuccess)
        assertTrue(persistedRules[88L]!!.nextOccurrenceEpochDay > 20000L)

        existingOccurrenceOverride = true
        val duplicate = repository.logDueRecurringTransaction(tx, ruleId = 88L, currentDueEpochDay = 20000L)
        assertTrue(duplicate.isFailure)
        assertTrue(duplicate.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun upsertRecurringTransaction_sanitizesTransferCategoryAndRejectsOpeningBalance() = runTest {
        val openingRuleTx = TransactionItem(
            id = 0L,
            type = TransactionType.OPENING_BALANCE,
            amount = MinorMoney(50_00L),
            currencyCode = "USD",
            accountId = 1L,
            accountName = "Checking",
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 20000L,
        )
        assertTrue(repository.upsertRecurringTransaction(openingRuleTx, RepeatFrequency.EVERY_MONTH).isFailure)

        val transferRuleTx = TransactionItem(
            id = 0L,
            type = TransactionType.TRANSFER,
            amount = MinorMoney(50_00L),
            currencyCode = "USD",
            accountId = 1L,
            accountName = "Checking",
            transferToAccountId = 2L,
            categoryId = 99L, // must be cleared on transfer rule
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 20000L,
        )
        val ok = repository.upsertRecurringTransaction(transferRuleTx, RepeatFrequency.EVERY_MONTH)
        assertTrue(ok.isSuccess)
        assertNull(persistedRules[77L]?.categoryId)
    }

    @Test
    fun logDueRecurringTransaction_staleNotificationOrEditedDateCycleAlreadyAdvanced_rejectsWithoutInserting() = runTest {
        persistedRules[89L] = RecurringRuleEntity(
            id = 89L,
            title = "Rent",
            type = TransactionType.EXPENSE,
            amountMinor = 1000_00L,
            currencyCode = "USD",
            accountId = 1L,
            categoryId = 10L,
            frequency = RepeatFrequency.EVERY_MONTH,
            startDateEpochDay = 20000L,
            nextOccurrenceEpochDay = 20030L,
        )
        val earlyTx = TransactionItem(
            id = 0L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(1000_00L),
            currencyCode = "USD",
            accountId = 1L,
            accountName = "Checking",
            categoryId = 10L,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 20028L, // paid 2 days early
        )

        // First tap succeeds and advances nextOccurrenceEpochDay beyond 20030L
        assertTrue(repository.logDueRecurringTransaction(earlyTx, ruleId = 89L, currentDueEpochDay = 20030L).isSuccess)
        assertEquals(1, persistedTransactions.size)

        // Stale notification re-tap with another date (e.g. 20029L) must fail BEFORE inserting
        val staleRetry = repository.logDueRecurringTransaction(
            earlyTx.copy(ledgerDateEpochDay = 20029L),
            ruleId = 89L,
            currentDueEpochDay = 20030L,
        )
        assertTrue(staleRetry.isFailure)
        assertTrue(staleRetry.exceptionOrNull() is IllegalStateException)
        assertEquals("Duplicate stale tap must not insert a second transaction", 1, persistedTransactions.size)
    }

    @Test
    fun logDueRecurringTransaction_splitTransaction_persistsSplitsAndAdvancesSchedule() = runTest {
        persistedRules[90L] = RecurringRuleEntity(
            id = 90L,
            title = "Utility Bundle",
            type = TransactionType.EXPENSE,
            amountMinor = 100_00L,
            currencyCode = "USD",
            accountId = 1L,
            categoryId = 10L,
            frequency = RepeatFrequency.EVERY_MONTH,
            startDateEpochDay = 20000L,
            nextOccurrenceEpochDay = 20030L,
        )
        val splitBill = TransactionItem(
            id = 0L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(100_00L),
            currencyCode = "USD",
            accountId = 1L,
            accountName = "Checking",
            isSplit = true,
            splits = listOf(
                SplitLineItem(categoryId = 10L, amount = MinorMoney(60_00L)),
                SplitLineItem(categoryId = 11L, amount = MinorMoney(40_00L)),
            ),
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 20030L,
        )

        val result = repository.logDueRecurringTransaction(splitBill, ruleId = 90L, currentDueEpochDay = 20030L)
        assertTrue(result.isSuccess)
        assertTrue(persistedTransactions.single().isSplit)
        assertTrue(persistedRules[90L]!!.nextOccurrenceEpochDay > 20030L)
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        inline fun <reified T : Any> createStub(
            crossinline handler: (methodName: String, args: Array<out Any>?) -> Any? = { _, _ -> null },
        ): T {
            return Proxy.newProxyInstance(
                T::class.java.classLoader,
                arrayOf(T::class.java),
            ) { _, method, args ->
                val custom = handler(method.name, args)
                if (custom != null) return@newProxyInstance custom
                when {
                    method.returnType == Result::class.java -> Result.success(1L)
                    method.returnType == List::class.java -> emptyList<Any>()
                    method.returnType == Boolean::class.javaPrimitiveType -> false
                    method.returnType == Long::class.javaPrimitiveType -> 0L
                    method.returnType == Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            } as T
        }

        private fun createDummyDatabase(): FolioDatabase {
            val implClass = Class.forName("com.folioledger.app.data.local.FolioDatabase_Impl")
            return implClass.getDeclaredConstructor().newInstance() as FolioDatabase
        }
    }
}
