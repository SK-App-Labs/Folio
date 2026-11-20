package com.folioledger.app.data

import com.folioledger.app.data.local.FolioDatabase
import com.folioledger.app.data.local.dao.AccountDao
import com.folioledger.app.data.local.dao.BudgetAndRecurringDao
import com.folioledger.app.data.local.dao.CategoryAndTagDao
import com.folioledger.app.data.local.dao.TransactionDao
import com.folioledger.app.data.local.entity.AccountEntity
import com.folioledger.app.data.local.entity.TransactionEntity
import com.folioledger.app.data.local.entity.TransactionWithDetails
import com.folioledger.app.data.repository.TransactionRepositoryImpl
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.SplitLineItem
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import kotlinx.coroutines.test.runTest
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
        openingBalanceMinor = 1000_00L
    )

    private val existingTransactionsMap = mutableMapOf<Long, TransactionWithDetails>()

    private val accountDao: AccountDao = createStub { methodName, args ->
        if (methodName == "getAccountByIdOnce") {
            testAccount
        } else null
    }

    private val transactionDao: TransactionDao = createStub { methodName, args ->
        if (methodName == "getTransactionByIdOnce") {
            val id = args?.get(0) as? Long
            existingTransactionsMap[id]
        } else null
    }

    private val categoryAndTagDao: CategoryAndTagDao = createStub()
    private val budgetAndRecurringDao: BudgetAndRecurringDao = createStub()

    // FolioDatabase dummy instance created via Objenesis or Unsafe if needed, or by subclassing
    private val dummyDatabase: FolioDatabase = createDummyDatabase()

    private lateinit var repository: TransactionRepositoryImpl

    @Before
    fun setup() {
        existingTransactionsMap.clear()
        repository = TransactionRepositoryImpl(
            transactionDao = transactionDao,
            accountDao = accountDao,
            categoryAndTagDao = categoryAndTagDao,
            budgetAndRecurringDao = budgetAndRecurringDao,
            database = dummyDatabase
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
                ledgerDateEpochDay = 100L
            ),
            splits = emptyList(),
            tags = emptyList()
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
            payeeOrDescription = "Overwritten"
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
                ledgerDateEpochDay = 100L
            ),
            splits = emptyList(),
            tags = emptyList()
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
            payeeOrDescription = "Overwritten"
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
                ledgerDateEpochDay = 100L
            ),
            splits = emptyList(),
            tags = emptyList()
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
                ledgerDateEpochDay = 100L
            ),
            splits = emptyList(),
            tags = emptyList()
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
                SplitLineItem(categoryId = 11L, amount = MinorMoney(40_00L))
            )
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
                ledgerDateEpochDay = 100L
            ),
            splits = emptyList(),
            tags = emptyList()
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
                SplitLineItem(categoryId = 11L, amount = MinorMoney(30_00L))
            )
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
                ledgerDateEpochDay = 100L
            ),
            splits = emptyList(),
            tags = emptyList()
        )

        val baseTransaction = TransactionItem(
            id = 0L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(300_00L),
            currencyCode = "USD",
            accountId = 1L,
            accountName = "Checking",
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 100L
        )

        val result = repository.createInstallmentPlanTransactions(
            baseTransaction = baseTransaction,
            totalInstallmentAmount = MinorMoney(300_00L),
            monthsCount = 3,
            startDateEpochDay = 100L,
            replaceTransactionId = 52L
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
                ledgerDateEpochDay = 100L
            ),
            splits = emptyList(),
            tags = emptyList()
        )

        val baseTransaction = TransactionItem(
            id = 0L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(200_00L),
            currencyCode = "USD",
            accountId = 1L,
            accountName = "Checking",
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 100L
        )

        val result = repository.createInstallmentPlanTransactions(
            baseTransaction = baseTransaction,
            totalInstallmentAmount = MinorMoney(200_00L),
            monthsCount = 2,
            startDateEpochDay = 100L,
            replaceTransactionId = 53L
        )
        assertTrue("Expected failure replacing RECONCILIATION with installment plan", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(result.exceptionOrNull()?.message?.contains("Opening balance and reconciliation entries cannot be replaced") == true)
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        inline fun <reified T : Any> createStub(
            crossinline handler: (methodName: String, args: Array<out Any>?) -> Any? = { _, _ -> null }
        ): T {
            return Proxy.newProxyInstance(
                T::class.java.classLoader,
                arrayOf(T::class.java)
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
