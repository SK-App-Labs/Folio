// File: app/src/main/java/com/folioledger/app/data/repository/TransactionRepositoryImpl.kt
package com.folioledger.app.data.repository

import androidx.room.withTransaction
import com.folioledger.app.data.local.FolioDatabase
import com.folioledger.app.data.local.dao.AccountDao
import com.folioledger.app.data.local.dao.BudgetAndRecurringDao
import com.folioledger.app.data.local.dao.CategoryAndTagDao
import com.folioledger.app.data.local.dao.PayeeSuggestionProjection
import com.folioledger.app.data.local.dao.TransactionDao
import com.folioledger.app.data.local.entity.AccountEntity
import com.folioledger.app.data.local.entity.CategoryEntity
import com.folioledger.app.data.local.entity.InstallmentPlanEntity
import com.folioledger.app.data.local.entity.RecurringRuleEntity
import com.folioledger.app.data.local.entity.TransactionEntity
import com.folioledger.app.data.local.entity.TransactionSplitEntity
import com.folioledger.app.data.local.entity.TransactionWithDetails
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.PayeeAutofillTemplate
import com.folioledger.app.domain.model.PayeeSuggestion
import com.folioledger.app.domain.model.RepeatFrequency
import com.folioledger.app.domain.model.SplitLineItem
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionSearchFilter
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.model.UNCATEGORIZED_CATEGORY_ID
import com.folioledger.app.domain.repository.TransactionRepository
import com.folioledger.app.domain.usecase.AccountingRulesEngine
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import com.folioledger.app.domain.util.RecurringScheduleCalculator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class TransactionRepositoryImpl(
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val categoryAndTagDao: CategoryAndTagDao,
    private val budgetAndRecurringDao: BudgetAndRecurringDao,
    private val database: FolioDatabase
) : TransactionRepository {

    override fun observeTransactionsInRange(
        startEpochDay: Long,
        endEpochDay: Long,
        accountId: Long?
    ): Flow<List<TransactionItem>> {
        val rawFlow = if (accountId == null) {
            transactionDao.observeTransactionsInRange(startEpochDay, endEpochDay)
        } else {
            transactionDao.observeTransactionsInRangeForAccount(startEpochDay, endEpochDay, accountId)
        }
        return combineWithMetadata(rawFlow)
    }

    override fun observeTransactionsForDay(epochDay: Long): Flow<List<TransactionItem>> {
        return combineWithMetadata(transactionDao.observeTransactionsForDay(epochDay))
    }

    override fun observeTransactionsForAccount(accountId: Long): Flow<List<TransactionItem>> {
        return combineWithMetadata(transactionDao.observeTransactionsForAccount(accountId))
    }

    override fun observeTransactionById(transactionId: Long): Flow<TransactionItem?> {
        return combine(
            transactionDao.observeTransactionById(transactionId),
            accountDao.observeAllAccounts(),
            categoryAndTagDao.observeAllCategories()
        ) { txWithDetails, accounts, categories ->
            if (txWithDetails == null) {
                null
            } else {
                val accountsById = accounts.associateBy { it.id }
                val categoriesById = categories.associateBy { it.id }
                mapToTransactionItem(txWithDetails, accountsById, categoriesById)
            }
        }.flowOn(Dispatchers.Default)
    }

    override fun searchAndFilterTransactions(
        filter: TransactionSearchFilter
    ): Flow<List<TransactionItem>> {
        val rawTxFlow = when {
            filter.startEpochDay != null && filter.endEpochDay != null -> {
                when {
                    filter.accountIds.size == 1 -> {
                        transactionDao.observeTransactionsInRangeForAccount(
                            startDay = filter.startEpochDay,
                            endDay = filter.endEpochDay,
                            accountId = filter.accountIds.first()
                        )
                    }
                    filter.accountIds.size in 2..400 -> {
                        transactionDao.observeTransactionsInRangeForAccounts(
                            startDay = filter.startEpochDay,
                            endDay = filter.endEpochDay,
                            accountIds = filter.accountIds.toList()
                        )
                    }
                    else -> {
                        transactionDao.observeTransactionsInRange(filter.startEpochDay, filter.endEpochDay)
                    }
                }
            }
            filter.accountIds.size == 1 -> {
                transactionDao.observeTransactionsForAccount(filter.accountIds.first())
            }
            filter.accountIds.size in 2..400 -> {
                transactionDao.observeTransactionsForAccounts(filter.accountIds.toList())
            }
            else -> {
                transactionDao.observeAllTransactions()
            }
        }
        return combine(
            rawTxFlow,
            accountDao.observeAllAccounts(),
            categoryAndTagDao.observeAllCategories()
        ) { allTransactions, accounts, categories ->
            val accountsById = accounts.associateBy { it.id }
            val categoriesById = categories.associateBy { it.id }

            val filterIncludesUncategorized = UNCATEGORIZED_CATEGORY_ID in filter.categoryIds
            val explicitCategoryIds = filter.categoryIds - UNCATEGORIZED_CATEGORY_ID

            val expandedCategoryIds: Set<Long> = if (explicitCategoryIds.isEmpty()) {
                emptySet()
            } else {
                buildSet {
                    addAll(explicitCategoryIds)
                    for (cat in categories) {
                        if (cat.parentId != null && cat.parentId in explicitCategoryIds) {
                            add(cat.id)
                        }
                    }
                }
            }

            val normalizedQuery = filter.queryText.trim().lowercase()
            val normalizedFilterTags = filter.tags.map { it.trim().lowercase() }.toSet()

            allTransactions
                .asSequence()
                .map { mapToTransactionItem(it, accountsById, categoriesById) }
                .filter { item ->
                    matchesSearchFilter(
                        item = item,
                        filter = filter,
                        expandedCategoryIds = expandedCategoryIds,
                        normalizedFilterTags = normalizedFilterTags,
                        normalizedQuery = normalizedQuery
                    )
                }
                .toList()
        }.flowOn(Dispatchers.Default)
    }

    override suspend fun upsertSingleTransaction(transaction: TransactionItem): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatchingNonCancellation {
            require(transaction.amount.isPositive) {
                "Transaction amount must be strictly positive."
            }
            require(!transaction.transferFee.isNegative) {
                "Transfer fee cannot be negative."
            }

            require(transaction.type != TransactionType.OPENING_BALANCE) {
                "Opening balance transactions cannot be created or modified directly."
            }
            require(transaction.type != TransactionType.RECONCILIATION) {
                "Reconciliation transactions cannot be created or modified directly."
            }

            if (transaction.id > 0L) {
                val existing = transactionDao.getTransactionByIdOnce(transaction.id)
                if (existing?.transaction?.type == TransactionType.OPENING_BALANCE || existing?.transaction?.type == TransactionType.RECONCILIATION) {
                    throw IllegalArgumentException("Opening balance and reconciliation entries cannot be modified directly.")
                }
            }

            val account = accountDao.getAccountByIdOnce(transaction.accountId)
                ?: throw IllegalArgumentException("Account #${transaction.accountId} not found.")
            val resolvedCurrency = transaction.currencyCode.ifBlank {
                account.currencyCode
            }

            val isTransferType = transaction.type == TransactionType.TRANSFER || transaction.type == TransactionType.CC_SETTLEMENT
            if (isTransferType) {
                require(transaction.transferToAccountId != null) {
                    "Transfers and credit card settlements require a destination account."
                }
                require(transaction.transferToAccountId != transaction.accountId) {
                    "Source and destination accounts for a transfer must be different."
                }
                val toAccount = accountDao.getAccountByIdOnce(transaction.transferToAccountId)
                    ?: throw IllegalArgumentException("Destination account #${transaction.transferToAccountId} not found.")
                require(account.currencyCode.equals(toAccount.currencyCode, ignoreCase = true)) {
                    "Currency mismatch: Transfers require both accounts to share the same currency (${account.currencyCode} \u2260 ${toAccount.currencyCode})."
                }
            }

            val entity = TransactionEntity(
                id = transaction.id,
                type = transaction.type,
                timestampEpochMillis = transaction.timestampEpochMillis,
                ledgerDateEpochDay = transaction.ledgerDateEpochDay,
                amountMinor = transaction.amount.amountMinor,
                currencyCode = resolvedCurrency,
                accountId = transaction.accountId,
                transferToAccountId = if (isTransferType) transaction.transferToAccountId else null,
                transferFeeMinor = if (isTransferType) transaction.transferFee.amountMinor else 0L,
                categoryId = if (isTransferType) null else transaction.categoryId,
                isSplit = false,
                payeeOrDescription = transaction.payeeOrDescription.trim(),
                note = transaction.note.trim(),
                installmentIndex = transaction.installmentIndex,
                installmentTotalCount = transaction.installmentTotalCount,
                recurringRuleId = transaction.recurringRuleId
            )

                transactionDao.upsertTransactionWithDetails(
                    transaction = entity,
                    splits = emptyList(),
                    tagNames = transaction.tags
                )
            }
        }

    override suspend fun upsertSplitTransaction(transaction: TransactionItem): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatchingNonCancellation {
            AccountingRulesEngine.validateSplitTransaction(
                totalAmount = transaction.amount,
                splits = transaction.splits,
                requireCategoryIds = true
            ).getOrThrow()

            require(transaction.type != TransactionType.OPENING_BALANCE) {
                "Opening balance transactions cannot be created or modified directly."
            }
            require(transaction.type != TransactionType.RECONCILIATION) {
                "Reconciliation transactions cannot be created or modified directly."
            }

            if (transaction.id > 0L) {
                val existing = transactionDao.getTransactionByIdOnce(transaction.id)
                if (existing?.transaction?.type == TransactionType.OPENING_BALANCE || existing?.transaction?.type == TransactionType.RECONCILIATION) {
                    throw IllegalArgumentException("Opening balance and reconciliation entries cannot be modified directly.")
                }
            }

            val account = accountDao.getAccountByIdOnce(transaction.accountId)
                ?: throw IllegalArgumentException("Account #${transaction.accountId} not found.")
            val resolvedCurrency = transaction.currencyCode.ifBlank {
                account.currencyCode
            }

            val entity = TransactionEntity(
                id = transaction.id,
                type = transaction.type,
                timestampEpochMillis = transaction.timestampEpochMillis,
                ledgerDateEpochDay = transaction.ledgerDateEpochDay,
                amountMinor = transaction.amount.amountMinor,
                currencyCode = resolvedCurrency,
                accountId = transaction.accountId,
                transferToAccountId = null,
                transferFeeMinor = 0L,
                categoryId = null,
                isSplit = true,
                payeeOrDescription = transaction.payeeOrDescription.trim(),
                note = transaction.note.trim(),
                installmentIndex = transaction.installmentIndex,
                installmentTotalCount = transaction.installmentTotalCount,
                recurringRuleId = transaction.recurringRuleId
            )

            val splitEntities = transaction.splits.map { split ->
                TransactionSplitEntity(
                    id = split.id,
                    transactionId = transaction.id,
                    categoryId = split.categoryId,
                    amountMinor = split.amount.amountMinor,
                    note = split.note.trim()
                )
            }

                transactionDao.upsertTransactionWithDetails(
                    transaction = entity,
                    splits = splitEntities,
                    tagNames = transaction.tags
                )
            }
        }

    override suspend fun recordTransfer(
        fromAccountId: Long,
        toAccountId: Long,
        amount: MinorMoney,
        transferFee: MinorMoney,
        timestampEpochMillis: Long,
        ledgerDateEpochDay: Long,
        note: String,
        tags: List<String>
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
        require(fromAccountId != toAccountId) {
            "Source and destination accounts for a transfer must be different."
        }
        require(amount.isPositive) {
            "Transfer amount must be strictly positive."
        }
        require(!transferFee.isNegative) {
            "Transfer fee cannot be negative."
        }

        val fromAccount = accountDao.getAccountByIdOnce(fromAccountId)
            ?: throw IllegalArgumentException("Source account #$fromAccountId not found.")
        val toAccount = accountDao.getAccountByIdOnce(toAccountId)
            ?: throw IllegalArgumentException("Destination account #$toAccountId not found.")

        require(fromAccount.currencyCode.equals(toAccount.currencyCode, ignoreCase = true)) {
            "Currency mismatch: Transfers require both accounts to share the same currency (${fromAccount.currencyCode} \u2260 ${toAccount.currencyCode})."
        }

        val entity = TransactionEntity(
            id = 0L,
            type = TransactionType.TRANSFER,
            timestampEpochMillis = timestampEpochMillis,
            ledgerDateEpochDay = ledgerDateEpochDay,
            amountMinor = amount.amountMinor,
            currencyCode = fromAccount.currencyCode,
            accountId = fromAccountId,
            transferToAccountId = toAccountId,
            transferFeeMinor = transferFee.amountMinor,
            categoryId = null,
            isSplit = false,
            payeeOrDescription = "${fromAccount.name} \u2192 ${toAccount.name}",
            note = note.trim()
        )

            transactionDao.upsertTransactionWithDetails(
                transaction = entity,
                splits = emptyList(),
                tagNames = tags
            )
        }
    }

    override suspend fun recordRefund(
        accountId: Long,
        categoryId: Long?,
        refundAmount: MinorMoney,
        timestampEpochMillis: Long,
        ledgerDateEpochDay: Long,
        originalTransactionId: Long?,
        payeeOrDescription: String,
        note: String
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
        require(refundAmount.isPositive) {
            "Refund amount must be strictly positive."
        }

        val account = accountDao.getAccountByIdOnce(accountId)
            ?: throw IllegalArgumentException("Account #$accountId not found.")

        val entity = TransactionEntity(
            id = 0L,
            type = TransactionType.REFUND,
            timestampEpochMillis = timestampEpochMillis,
            ledgerDateEpochDay = ledgerDateEpochDay,
            amountMinor = refundAmount.amountMinor,
            currencyCode = account.currencyCode,
            accountId = accountId,
            transferToAccountId = null,
            transferFeeMinor = 0L,
            categoryId = categoryId,
            isSplit = false,
            payeeOrDescription = payeeOrDescription.trim().ifEmpty { "Refund" },
            note = note.trim(),
            originalTransactionId = originalTransactionId
        )

            transactionDao.insertTransactionRaw(entity)
        }
    }

    override suspend fun createInstallmentPlanTransactions(
        baseTransaction: TransactionItem,
        totalInstallmentAmount: MinorMoney,
        monthsCount: Int,
        startDateEpochDay: Long,
        replaceTransactionId: Long?
    ): Result<List<Long>> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            if (replaceTransactionId != null && replaceTransactionId > 0L) {
                val existing = transactionDao.getTransactionByIdOnce(replaceTransactionId)
                if (existing?.transaction?.type == TransactionType.OPENING_BALANCE || existing?.transaction?.type == TransactionType.RECONCILIATION) {
                    throw IllegalArgumentException("Opening balance and reconciliation entries cannot be replaced by an installment plan.")
                }
            }

            database.withTransaction {
                if (replaceTransactionId != null && replaceTransactionId > 0L) {
                    transactionDao.deleteTransactionById(replaceTransactionId)
                }

                val schedule = AccountingRulesEngine.generateInstallmentSchedule(
                    totalAmount = totalInstallmentAmount,
                    monthsCount = monthsCount,
                    startDateEpochDay = startDateEpochDay
                )

                val account = accountDao.getAccountByIdOnce(baseTransaction.accountId)
                    ?: throw IllegalArgumentException("Account #${baseTransaction.accountId} not found.")
                val resolvedCurrency = baseTransaction.currencyCode.ifBlank {
                    account.currencyCode
                }
                val baseTitle = baseTransaction.payeeOrDescription.trim().ifEmpty { "Installment Plan" }

                val planEntity = InstallmentPlanEntity(
                    id = 0L,
                    title = baseTitle,
                    accountId = baseTransaction.accountId,
                    categoryId = baseTransaction.categoryId,
                    totalAmountMinor = totalInstallmentAmount.amountMinor,
                    monthsCount = monthsCount,
                    startDateEpochDay = startDateEpochDay
                )

                val installmentEntities = schedule.mapIndexed { index, (scheduledEpochDay, installmentMoney) ->
                    val installmentNumber = index + 1
                    TransactionEntity(
                        id = 0L,
                        type = TransactionType.EXPENSE,
                        timestampEpochMillis = scheduledEpochDay * MILLIS_PER_DAY + 12 * 3600 * 1000L,
                        ledgerDateEpochDay = scheduledEpochDay,
                        amountMinor = installmentMoney.amountMinor,
                        currencyCode = resolvedCurrency,
                        accountId = baseTransaction.accountId,
                        transferToAccountId = null,
                        transferFeeMinor = 0L,
                        categoryId = baseTransaction.categoryId,
                        isSplit = false,
                        payeeOrDescription = "$baseTitle ($installmentNumber/$monthsCount)",
                        note = baseTransaction.note.trim(),
                        installmentIndex = installmentNumber,
                        installmentTotalCount = monthsCount
                    )
                }

                transactionDao.insertInstallmentPlanWithTransactions(
                    plan = planEntity,
                    installmentTransactions = installmentEntities,
                    tagNames = baseTransaction.tags
                )
            }
        }
    }

    override suspend fun upsertRecurringTransaction(
        transaction: TransactionItem,
        frequency: RepeatFrequency
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            validateRecurringTransaction(transaction.isSplit)
            database.withTransaction {
            val anchorDay = LocalDate.ofEpochDay(transaction.ledgerDateEpochDay).dayOfMonth
            val nextOccurrenceDate = RecurringScheduleCalculator.computeNextOccurrence(
                currentDate = LocalDate.ofEpochDay(transaction.ledgerDateEpochDay),
                frequency = frequency,
                anchorDayOfMonth = anchorDay
            )
            val sourceAccount = accountDao.getAccountByIdOnce(transaction.accountId)
                ?: throw IllegalArgumentException("Account #${transaction.accountId} not found.")
            val targetAccount = if (transaction.transferToAccountId != null) {
                accountDao.getAccountByIdOnce(transaction.transferToAccountId)
            } else null
            val resolvedCurrency = transaction.currencyCode.ifBlank {
                sourceAccount.currencyCode
            }

            val resolvedTitle = transaction.payeeOrDescription.trim().ifEmpty {
                when {
                    transaction.type == TransactionType.TRANSFER && targetAccount != null ->
                        "${sourceAccount?.name ?: "Account"} \u2192 ${targetAccount.name}"
                    transaction.categoryName != null -> transaction.categoryName
                    else -> "Recurring ${frequency.displayName}"
                }
            }

            val ruleEntity = RecurringRuleEntity(
                id = 0L,
                title = resolvedTitle,
                type = transaction.type,
                amountMinor = transaction.amount.amountMinor,
                currencyCode = resolvedCurrency,
                accountId = transaction.accountId,
                transferToAccountId = transaction.transferToAccountId,
                categoryId = transaction.categoryId,
                frequency = frequency,
                startDateEpochDay = transaction.ledgerDateEpochDay,
                nextOccurrenceEpochDay = nextOccurrenceDate.toEpochDay(),
                endDateEpochDay = null,
                payeeOrDescription = transaction.payeeOrDescription.trim().ifEmpty { resolvedTitle },
                note = transaction.note.trim(),
                isActive = true
            )
            val ruleId = budgetAndRecurringDao.upsertRecurringRule(ruleEntity)

            val txWithRule = transaction.copy(recurringRuleId = ruleId)
                if (transaction.isSplit) {
                    upsertSplitTransaction(txWithRule).getOrThrow()
                } else {
                    upsertSingleTransaction(txWithRule).getOrThrow()
                }
            }
        }
    }

    override suspend fun deleteTransaction(transactionId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            val tx = transactionDao.getTransactionByIdOnce(transactionId)
            if (tx?.transaction?.type == TransactionType.OPENING_BALANCE) {
                throw IllegalArgumentException("Opening balance transactions are immutable and cannot be deleted directly.")
            }
            if (tx?.transaction?.type == TransactionType.RECONCILIATION) {
                throw IllegalArgumentException("Reconciliation transactions are immutable and cannot be deleted directly.")
            }
            transactionDao.deleteTransactionById(transactionId)
        }
    }

    override fun observePayeeSuggestions(limit: Int): Flow<List<PayeeSuggestion>> {
        return transactionDao.observePayeeSuggestions(limit)
            .map { projections -> deduplicatePayeeProjections(projections) }
            .flowOn(Dispatchers.Default)
    }

    override suspend fun getPayeeAutofillTemplate(latestTransactionId: Long): PayeeAutofillTemplate? = withContext(Dispatchers.IO) {
        val details = transactionDao.getTransactionByIdOnce(latestTransactionId) ?: return@withContext null
        val tx = details.transaction
        PayeeAutofillTemplate(
            payeeName = tx.payeeOrDescription.trim(),
            type = tx.type,
            accountId = tx.accountId,
            transferToAccountId = tx.transferToAccountId,
            categoryId = tx.categoryId,
            isSplit = tx.isSplit,
            tags = details.tags.map { it.name }
        )
    }

    private fun combineWithMetadata(
        sourceFlow: Flow<List<TransactionWithDetails>>
    ): Flow<List<TransactionItem>> {
        return combine(
            sourceFlow,
            accountDao.observeAllAccounts(),
            categoryAndTagDao.observeAllCategories()
        ) { txList, accounts, categories ->
            val accountsById = accounts.associateBy { it.id }
            val categoriesById = categories.associateBy { it.id }
            txList.map { txWithDetails ->
                mapToTransactionItem(txWithDetails, accountsById, categoriesById)
            }
        }.flowOn(Dispatchers.Default)
    }

    private fun mapToTransactionItem(
        txWithDetails: TransactionWithDetails,
        accountsById: Map<Long, AccountEntity>,
        categoriesById: Map<Long, CategoryEntity>
    ): TransactionItem {
        val tx = txWithDetails.transaction
        val sourceAccount = accountsById[tx.accountId]
        val targetAccount = tx.transferToAccountId?.let { accountsById[it] }
        val category = tx.categoryId?.let { categoriesById[it] }
        val parentCategory = category?.parentId?.let { categoriesById[it] }

        val mappedSplits = txWithDetails.splits.map { split ->
            val splitCategory = categoriesById[split.categoryId]
            SplitLineItem(
                id = split.id,
                categoryId = split.categoryId,
                categoryName = splitCategory?.name ?: "",
                amount = MinorMoney(split.amountMinor),
                note = split.note
            )
        }

        val mappedTags = txWithDetails.tags.map { it.name }

        val preservesTransferTarget = tx.type == TransactionType.TRANSFER ||
                tx.type == TransactionType.CC_SETTLEMENT ||
                tx.type == TransactionType.OPENING_BALANCE ||
                tx.type == TransactionType.RECONCILIATION
        val isTransferType = tx.type == TransactionType.TRANSFER || tx.type == TransactionType.CC_SETTLEMENT
        val effectiveTransferToAccountId = if (preservesTransferTarget) tx.transferToAccountId else null
        val effectiveTransferToAccountName = if (isTransferType) targetAccount?.name else null
        val effectiveTransferFee = if (isTransferType) MinorMoney(tx.transferFeeMinor) else MinorMoney.ZERO
        val effectiveCategoryId = if (isTransferType) null else tx.categoryId

        return TransactionItem(
            id = tx.id,
            type = tx.type,
            timestampEpochMillis = tx.timestampEpochMillis,
            ledgerDateEpochDay = tx.ledgerDateEpochDay,
            amount = MinorMoney(tx.amountMinor),
            currencyCode = tx.currencyCode,
            accountId = tx.accountId,
            accountName = sourceAccount?.name ?: "Unknown Account",
            transferToAccountId = effectiveTransferToAccountId,
            transferToAccountName = effectiveTransferToAccountName,
            transferFee = effectiveTransferFee,
            categoryId = effectiveCategoryId,
            categoryName = if (isTransferType) null else (if (tx.isSplit) "Split" else category?.name),
            parentCategoryName = if (isTransferType) null else parentCategory?.name,
            categoryColorHex = if (isTransferType) null else (category?.colorHex ?: parentCategory?.colorHex),
            categoryIconName = if (isTransferType) null else (category?.iconName ?: parentCategory?.iconName),
            isSplit = !isTransferType && tx.isSplit,
            splits = if (isTransferType) emptyList() else mappedSplits,
            tags = mappedTags,
            payeeOrDescription = tx.payeeOrDescription,
            note = tx.note,
            installmentIndex = transactionIdOrNull(tx.installmentIndex),
            installmentTotalCount = transactionIdOrNull(tx.installmentTotalCount),
            recurringRuleId = tx.recurringRuleId
        )
    }

    private fun transactionIdOrNull(value: Int?): Int? = value

    companion object {
        private const val MILLIS_PER_DAY = 86_400_000L

        internal fun validateRecurringTransaction(isSplit: Boolean) {
            require(!isSplit) { "Split transactions cannot be scheduled as recurring rules." }
        }

        internal fun deduplicatePayeeProjections(
            projections: List<PayeeSuggestionProjection>
        ): List<PayeeSuggestion> {
            return projections
                .groupBy { it.displayName.trim().lowercase(Locale.ROOT) }
                .map { (normKey, group) ->
                    val latest = group.maxWith(
                        compareBy<PayeeSuggestionProjection> { it.lastUsedEpochMillis }
                            .thenBy { it.latestTransactionId }
                    )
                    PayeeSuggestion(
                        latestTransactionId = latest.latestTransactionId,
                        displayName = latest.displayName.trim(),
                        normalizedKey = normKey,
                        usageCount = group.sumOf { it.usageCount },
                        lastUsedEpochMillis = latest.lastUsedEpochMillis,
                        lastType = latest.lastType,
                        lastAccountId = latest.lastAccountId,
                        lastTransferToAccountId = latest.lastTransferToAccountId,
                        lastCategoryId = latest.lastCategoryId,
                        lastIsSplit = latest.lastIsSplit
                    )
                }
                .sortedWith(
                    compareByDescending<PayeeSuggestion> { it.lastUsedEpochMillis }
                        .thenByDescending { it.usageCount }
                )
        }
    }
}

internal fun matchesSearchFilter(
    item: TransactionItem,
    filter: TransactionSearchFilter,
    expandedCategoryIds: Set<Long> = filter.categoryIds - UNCATEGORIZED_CATEGORY_ID,
    normalizedFilterTags: Set<String> = filter.tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
    normalizedQuery: String = filter.queryText.trim().lowercase()
): Boolean {
    if (filter.startEpochDay != null && item.ledgerDateEpochDay < filter.startEpochDay) {
        return false
    }
    if (filter.endEpochDay != null && item.ledgerDateEpochDay > filter.endEpochDay) {
        return false
    }
    if (filter.accountIds.isNotEmpty()) {
        val matchesSource = item.accountId in filter.accountIds
        val matchesTarget = item.transferToAccountId != null &&
                item.transferToAccountId in filter.accountIds
        if (!matchesSource && !matchesTarget) return false
    }
    if (filter.categoryIds.isNotEmpty()) {
        val filterIncludesUncategorized = UNCATEGORIZED_CATEGORY_ID in filter.categoryIds
        val isCategorizableType = item.type == TransactionType.EXPENSE ||
            item.type == TransactionType.INCOME ||
            item.type == TransactionType.REFUND
        val matchesUncategorized = filterIncludesUncategorized && isCategorizableType && if (item.isSplit) {
            item.splits.isEmpty() || item.splits.any {
                it.categoryId == null || it.categoryId == 0L || it.categoryId == UNCATEGORIZED_CATEGORY_ID
            }
        } else {
            item.categoryId == null || item.categoryId == 0L || item.categoryId == UNCATEGORIZED_CATEGORY_ID
        }
        val matchesExplicit = expandedCategoryIds.isNotEmpty() && (
            (item.categoryId != null && item.categoryId in expandedCategoryIds) ||
            item.splits.any { it.categoryId in expandedCategoryIds }
        )
        if (!matchesUncategorized && !matchesExplicit) return false
    }
    if (filter.types.isNotEmpty() && item.type !in filter.types) {
        return false
    }
    if (normalizedFilterTags.isNotEmpty()) {
        val hasMatchingTag = item.tags.any { it.lowercase() in normalizedFilterTags }
        if (!hasMatchingTag) return false
    }
    val effectiveAmount = if (filter.targetCurrencyCode != null && !item.currencyCode.equals(filter.targetCurrencyCode, ignoreCase = true)) {
        val targetSpec = SupportedCurrencies.fromCode(filter.targetCurrencyCode)
        val itemSpec = SupportedCurrencies.fromCode(item.currencyCode)
        val rate = filter.secondaryExchangeRates[item.currencyCode.uppercase()]
        if (rate != null && rate > 0.0) {
            val converted = MoneyFormatterAndCalculator.convertMinorMoney(
                sourceMinor = item.amount.amountMinor,
                sourceFractionDigits = itemSpec.fractionDigits,
                targetFractionDigits = targetSpec.fractionDigits,
                rateToTarget = rate
            )
            MinorMoney(converted)
        } else if (itemSpec.fractionDigits != targetSpec.fractionDigits) {
            val converted = MoneyFormatterAndCalculator.convertMinorMoney(
                sourceMinor = item.amount.amountMinor,
                sourceFractionDigits = itemSpec.fractionDigits,
                targetFractionDigits = targetSpec.fractionDigits,
                rateToTarget = 1.0
            )
            MinorMoney(converted)
        } else {
            item.amount
        }
    } else {
        item.amount
    }

    if (filter.minAmount != null && effectiveAmount < filter.minAmount) {
        return false
    }
    if (filter.maxAmount != null && effectiveAmount > filter.maxAmount) {
        return false
    }
    if (normalizedQuery.isNotEmpty()) {
        val matchesText = item.payeeOrDescription.lowercase().contains(normalizedQuery) ||
                item.note.lowercase().contains(normalizedQuery) ||
                (item.categoryName?.lowercase()?.contains(normalizedQuery) == true) ||
                (item.parentCategoryName?.lowercase()?.contains(normalizedQuery) == true) ||
                item.accountName.lowercase().contains(normalizedQuery) ||
                (item.transferToAccountName?.lowercase()?.contains(normalizedQuery) == true) ||
                item.tags.any { it.lowercase().contains(normalizedQuery) } ||
                item.splits.any {
                    it.note.lowercase().contains(normalizedQuery) ||
                            it.categoryName.lowercase().contains(normalizedQuery)
                }
        if (!matchesText) return false
    }
    return true
}
