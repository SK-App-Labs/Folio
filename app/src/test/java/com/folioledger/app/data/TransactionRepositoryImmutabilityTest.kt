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
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
            return allocateMethod.invoke(unsafe, implClass) as FolioDatabase
        }
    }
}
