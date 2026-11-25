/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/data/repository/AccountRepositoryImpl.kt
package com.folioledger.app.data.repository

import com.folioledger.app.data.local.dao.AccountDao
import com.folioledger.app.data.local.dao.TransactionBalanceProjection
import com.folioledger.app.data.local.dao.TransactionDao
import com.folioledger.app.data.local.entity.AccountEntity
import com.folioledger.app.data.local.entity.TransactionEntity
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NetWorthOverview
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.repository.AccountRepository
import com.folioledger.app.domain.repository.SettingsAndBackupRepository
import com.folioledger.app.domain.usecase.AccountingRulesEngine
import com.folioledger.app.domain.util.CreditCardCycleCalculator
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeAdd
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeSubtract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import androidx.room.withTransaction
import com.folioledger.app.data.local.FolioDatabase

class AccountRepositoryImpl(
    private val accountDao: AccountDao,
    private val transactionDao: TransactionDao,
    private val settingsRepository: SettingsAndBackupRepository,
    private val database: FolioDatabase
) : AccountRepository {

    override fun observeNetWorthOverview(referenceDateEpochDay: Long): Flow<NetWorthOverview> {
        return combine(
            observeAllAccounts(referenceDateEpochDay),
            settingsRepository.observeUserPreferences()
        ) { allSummaries, prefs ->
            val activeSummaries = allSummaries.filter { !it.isArchived }
            val primarySpec = SupportedCurrencies.fromCode(prefs.primaryCurrencyCode)

            var totalAssetsMinor = 0L
            var totalLiabilitiesMinor = 0L

            for (account in activeSummaries) {
                if (!account.includeInNetWorth || account.groupType == AccountGroup.DEBIT_CARD) {
                    continue
                }
                val rawBalanceMinor = account.currentBalance.amountMinor
                if (rawBalanceMinor == 0L) continue

                val convertedBalanceMinor = MoneyFormatterAndCalculator.convertToPrimaryMinor(
                    sourceMinor = rawBalanceMinor,
                    sourceCurrencyCode = account.currencyCode,
                    primaryCurrencyCode = primarySpec.code,
                    secondaryCurrencies = prefs.secondaryCurrencies
                )

                if (convertedBalanceMinor > 0L) {
                    totalAssetsMinor = safeAdd(totalAssetsMinor, convertedBalanceMinor)
                } else if (convertedBalanceMinor < 0L) {
                    totalLiabilitiesMinor = safeAdd(totalLiabilitiesMinor, safeAbs(convertedBalanceMinor))
                }
            }

            val totalAssets = MinorMoney(totalAssetsMinor)
            val totalLiabilities = MinorMoney(totalLiabilitiesMinor)
            val netWorth = totalAssets - totalLiabilities
            val accountsByGroup = activeSummaries.groupBy { it.groupType }

            NetWorthOverview(
                totalAssets = totalAssets,
                totalLiabilities = totalLiabilities,
                netWorth = netWorth,
                accountsByGroup = accountsByGroup
            )
        }
    }

    override fun observeActiveAccounts(referenceDateEpochDay: Long): Flow<List<AccountSummary>> {
        return observeAllAccounts(referenceDateEpochDay).map { summaries ->
            summaries.filter { !it.isArchived }
        }
    }

    override fun observeAllAccounts(referenceDateEpochDay: Long): Flow<List<AccountSummary>> {
        val monthStartFlow = settingsRepository.observeUserPreferences()
            .map { it.monthStartDay }
            .distinctUntilChanged()
        return combine(
            accountDao.observeAllAccounts(),
            transactionDao.observeTransactionBalanceProjections(),
            monthStartFlow
        ) { accountEntities, allProjections, monthStartDay ->
            computeAccountSummaries(
                accountEntities = accountEntities,
                allTransactions = allProjections,
                referenceDateEpochDay = referenceDateEpochDay,
                monthStartDay = monthStartDay
            )
        }.flowOn(Dispatchers.Default)
    }

    override fun observeAccountById(
        accountId: Long,
        referenceDateEpochDay: Long
    ): Flow<AccountSummary?> {
        return observeAllAccounts(referenceDateEpochDay).map { summaries ->
            summaries.firstOrNull { it.id == accountId }
        }
    }

    override suspend fun upsertAccount(
        account: AccountSummary,
        openingBalance: MinorMoney,
        openingDateEpochDay: Long
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
        val trimmedName = account.name.trim()
        require(trimmedName.isNotEmpty()) { "Account name cannot be blank." }

        val existing = if (account.id != 0L) {
            accountDao.getAccountByIdOnce(account.id)
        } else {
            null
        }

        val targetBalanceMinor = if (!openingBalance.isZero) {
            openingBalance.amountMinor
        } else {
            account.currentBalance.amountMinor
        }

        val resolvedOpeningMinor: Long
        val initialTx: TransactionEntity?

        if (existing != null) {
            // Immutable opening anchor: preserve legacy or previously recorded opening balance as-is.
            // Balance reconciliations are tracked via reconcileAccountBalance to prevent delta-guessing bugs.
            resolvedOpeningMinor = existing.openingBalanceMinor
            initialTx = null
        } else {
            if (account.groupType == AccountGroup.CREDIT_CARD) {
                resolvedOpeningMinor = safeAbs(targetBalanceMinor)
                initialTx = null
            } else if (account.groupType == AccountGroup.DEBIT_CARD) {
                resolvedOpeningMinor = 0L
                initialTx = null
            } else if (targetBalanceMinor != 0L) {
                // Event-sourced starting balance: Record as TransactionType.OPENING_BALANCE
                resolvedOpeningMinor = 0L
                val isOutflow = targetBalanceMinor < 0L || account.groupType.isLiabilityByDefault
                val txEpochMillis = LocalDate.ofEpochDay(openingDateEpochDay)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
                initialTx = TransactionEntity(
                    id = 0L,
                    type = TransactionType.OPENING_BALANCE,
                    timestampEpochMillis = txEpochMillis,
                    ledgerDateEpochDay = openingDateEpochDay,
                    amountMinor = safeAbs(targetBalanceMinor),
                    currencyCode = account.currencyCode.ifBlank { "INR" },
                    accountId = 0L, // will be replaced in insertAccountWithInitialBalance
                    transferToAccountId = if (isOutflow) 0L else null,
                    payeeOrDescription = "Opening Balance",
                    note = "Initial starting balance"
                )
            } else {
                resolvedOpeningMinor = 0L
                initialTx = null
            }
        }

        val resolvedGroupType = existing?.groupType ?: account.groupType
        val resolvedParentId = if (resolvedGroupType == AccountGroup.DEBIT_CARD) {
            if (existing != null && existing.parentAccountId != null && existing.parentAccountId != account.parentAccountId) {
                val cardTxCount = transactionDao.getTransactionCountForAccount(existing.id)
                require(cardTxCount == 0) {
                    "Cannot change the linked bank account of a debit card that already has transaction history."
                }
            }
            requireNotNull(account.parentAccountId) {
                "Debit cards must be linked to a parent bank account."
            }
        } else {
            null
        }
        val parentEntity = resolvedParentId?.let { parentId ->
            require(parentId != account.id) {
                "A debit card cannot be linked to itself."
            }
            val candidate = accountDao.getAccountByIdOnce(parentId)
                ?: throw IllegalArgumentException("Linked parent bank account #$parentId not found.")
            require(
                candidate.groupType == AccountGroup.BANK_ACCOUNT ||
                    candidate.groupType == AccountGroup.SAVINGS ||
                    candidate.groupType == AccountGroup.OVERDRAFT
            ) {
                "Debit cards can only be linked to a Bank, Savings, or Overdraft account."
            }
            candidate
        }
        val resolvedCurrency = when {
            resolvedGroupType == AccountGroup.DEBIT_CARD && parentEntity != null -> {
                resolveDebitCardCurrency(existing?.currencyCode, parentEntity.currencyCode)
            }
            else -> existing?.currencyCode ?: account.currencyCode.ifBlank { "INR" }
        }
        val entity = AccountEntity(
            id = account.id,
            name = trimmedName,
            groupType = resolvedGroupType,
            currencyCode = resolvedCurrency,
            openingBalanceMinor = resolvedOpeningMinor,
            initialUnbilledMinor = if (resolvedGroupType == AccountGroup.CREDIT_CARD) {
                if (existing != null) existing.initialUnbilledMinor else account.creditCardUnbilledAmount?.abs()?.amountMinor
            } else {
                null
            },
            creditLimitMinor = account.creditLimit?.amountMinor,
            settlementDayOfMonth = account.settlementDayOfMonth?.coerceIn(1, 31),
            paymentDueDayOfMonth = account.paymentDueDayOfMonth?.coerceIn(1, 31),
            parentAccountId = resolvedParentId,
            includeInNetWorth = if (resolvedGroupType == AccountGroup.DEBIT_CARD) {
                false
            } else {
                account.includeInNetWorth
            },
            isArchived = account.isArchived,
            colorHex = account.colorHex.ifBlank { "#10B981" },
            iconName = account.iconName.ifBlank { "account_balance_wallet" },
            sortOrder = existing?.sortOrder ?: account.sortOrder,
            isPinned = account.isPinned,
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: System.currentTimeMillis()
        )

            accountDao.insertAccountWithInitialBalance(entity, initialTx)
        }
    }

    override suspend fun setAccountPinned(
        accountId: Long,
        isPinned: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            accountDao.setAccountPinned(accountId, isPinned)
        }
    }

    override suspend fun toggleAccountPinned(
        accountId: Long
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            val account = accountDao.getAccountByIdOnce(accountId)
                ?: throw IllegalArgumentException("Account #$accountId not found.")
            accountDao.setAccountPinned(accountId, !account.isPinned)
        }
    }

    override suspend fun reconcileAccountBalance(
        accountId: Long,
        reconciledBalance: MinorMoney,
        reconciledDateEpochDay: Long,
        note: String
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            val performReconciliation: suspend () -> Long = {
                val account = accountDao.getAccountByIdOnce(accountId)
                    ?: throw IllegalArgumentException("Account #$accountId not found.")
                require(account.groupType != AccountGroup.DEBIT_CARD) {
                    "Debit cards do not hold direct balances; reconcile the linked bank account instead."
                }

                val allProjections = transactionDao.getTransactionBalanceProjectionsOnce()
                val userPrefs = settingsRepository.observeUserPreferences().first()
                val allSummaries = computeAccountSummaries(
                    accountEntities = accountDao.getAllAccountsOnce(),
                    allTransactions = allProjections,
                    referenceDateEpochDay = reconciledDateEpochDay,
                    monthStartDay = userPrefs.monthStartDay
                )
                val summary = allSummaries.firstOrNull { it.id == accountId }
                    ?: throw IllegalStateException("Could not compute live balance for account #$accountId")

                val currentMinor = summary.currentBalance.amountMinor
                val targetMinor = reconciledBalance.amountMinor
                val deltaMinor = safeSubtract(targetMinor, currentMinor)

                if (deltaMinor == 0L) {
                    0L
                } else {
                    val isOutflow = deltaMinor < 0L
                    val todayEpochDay = LocalDate.now().toEpochDay()
                    val txEpochMillis = if (reconciledDateEpochDay == todayEpochDay) {
                        System.currentTimeMillis()
                    } else {
                        LocalDate.ofEpochDay(reconciledDateEpochDay)
                            .atTime(23, 59, 59)
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli()
                    }

                    val reconciliationTx = TransactionEntity(
                        id = 0L,
                        type = TransactionType.RECONCILIATION,
                        timestampEpochMillis = txEpochMillis,
                        ledgerDateEpochDay = reconciledDateEpochDay,
                        amountMinor = safeAbs(deltaMinor),
                        currencyCode = account.currencyCode,
                        accountId = accountId,
                        transferToAccountId = if (isOutflow) accountId else null,
                        payeeOrDescription = if (account.groupType == AccountGroup.INVESTMENT) "Market Valuation Update" else "Balance Reconciliation",
                        note = note.trim().ifEmpty { if (account.groupType == AccountGroup.INVESTMENT) "Mark-to-market valuation adjustment" else "Manual balance adjustment" }
                    )

                    transactionDao.insertTransactionRaw(reconciliationTx)
                }
            }

            database.withTransaction { performReconciliation() }
        }
    }

    override suspend fun setAccountArchived(
        accountId: Long,
        isArchived: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            database.withTransaction {
                if (!isArchived) {
                    val account = accountDao.getAccountByIdOnce(accountId)
                    if (account?.groupType == AccountGroup.DEBIT_CARD && account.parentAccountId != null) {
                        val parent = accountDao.getAccountByIdOnce(account.parentAccountId)
                        require(parent == null || !parent.isArchived) {
                            "Unarchive the linked parent bank account (${parent?.name}) before unarchiving this debit card."
                        }
                    }
                }
                accountDao.setAccountArchived(accountId = accountId, isArchived = isArchived)
                if (isArchived) {
                    accountDao.archiveLinkedChildAccounts(accountId)
                    accountDao.deactivateRecurringRulesForAccountFamily(accountId)
                }
            }
        }
    }

    override suspend fun deleteAccount(
        accountId: Long,
        deleteTransactions: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            database.withTransaction {
                val linkedChildCount = accountDao.getLinkedChildAccountCount(accountId)
                val txCount = transactionDao.getTransactionCountForAccountFamily(accountId)
                validateAccountDeletionCascade(txCount, accountId, deleteTransactions, linkedChildCount)
                if (deleteTransactions) {
                    transactionDao.deleteTransactionsForAccount(accountId)
                }
                accountDao.deleteTransferBookmarksTargetingAccount(accountId)
                accountDao.deactivateTransferRecurringRulesTargetingAccount(accountId)
                accountDao.deleteAccountById(accountId)
            }
        }
    }

    override suspend fun getTransactionCountForAccount(accountId: Long): Int = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            transactionDao.getTransactionCountForAccountFamily(accountId)
        }.getOrDefault(0)
    }

    override suspend fun settleCreditCardStatement(
        sourceAssetAccountId: Long,
        creditCardAccountId: Long,
        settlementAmount: MinorMoney,
        timestampEpochMillis: Long,
        ledgerDateEpochDay: Long,
        note: String
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
        require(sourceAssetAccountId != creditCardAccountId) {
            "Source payment account and destination credit card must be distinct."
        }
        require(settlementAmount.isPositive) {
            "Credit card settlement amount must be strictly positive."
        }

        val sourceAccount = accountDao.getAccountByIdOnce(sourceAssetAccountId)
            ?: throw IllegalArgumentException("Source account #$sourceAssetAccountId not found.")
        val creditCardAccount = accountDao.getAccountByIdOnce(creditCardAccountId)
            ?: throw IllegalArgumentException("Credit card account #$creditCardAccountId not found.")
        require(!sourceAccount.isArchived && !creditCardAccount.isArchived) {
            "Cannot settle using an archived account."
        }
        require(sourceAccount.groupType != AccountGroup.DEBIT_CARD) {
            "Pay credit card settlements from a bank, savings, or cash account rather than a debit card directly."
        }
        require(creditCardAccount.groupType == AccountGroup.CREDIT_CARD) {
            "Destination account must be a CREDIT_CARD account."
        }
        require(sourceAccount.currencyCode.equals(creditCardAccount.currencyCode, ignoreCase = true)) {
            "Currency mismatch: Source account (${sourceAccount.currencyCode}) and Credit Card (${creditCardAccount.currencyCode}) must have the same currency."
        }

        val settlementTx = TransactionEntity(
            id = 0L,
            type = TransactionType.CC_SETTLEMENT,
            timestampEpochMillis = timestampEpochMillis,
            ledgerDateEpochDay = ledgerDateEpochDay,
            amountMinor = settlementAmount.amountMinor,
            currencyCode = creditCardAccount.currencyCode,
            accountId = sourceAssetAccountId,
            transferToAccountId = creditCardAccountId,
            transferFeeMinor = 0L,
            categoryId = null,
            isSplit = false,
            payeeOrDescription = "Bill Settlement - ${creditCardAccount.name}",
            note = note.trim()
        )

            transactionDao.insertTransactionRaw(settlementTx)
        }
    }

    private fun computeAccountSummaries(
        accountEntities: List<AccountEntity>,
        allTransactions: List<TransactionBalanceProjection>,
        referenceDateEpochDay: Long,
        monthStartDay: Int
    ): List<AccountSummary> {
        val accountsById = accountEntities.associateBy { it.id }
        val (finMonthStartDay, finMonthEndDay) = AccountingRulesEngine.computeFinancialMonthRange(
            referenceDateEpochDay = referenceDateEpochDay,
            monthStartDay = monthStartDay.coerceIn(1, 31)
        )

        val debitCardParentMap: Map<Long, Long> = accountEntities
            .filter { it.groupType == AccountGroup.DEBIT_CARD && it.parentAccountId != null }
            .associate { it.id to it.parentAccountId!! }

        fun resolveEffectiveAccountId(rawAccountId: Long): Long =
            debitCardParentMap[rawAccountId] ?: rawAccountId

        val hasOpeningBalanceTx = HashSet<Long>()
        for (tx in allTransactions) {
            if (tx.type == TransactionType.OPENING_BALANCE) {
                hasOpeningBalanceTx.add(tx.accountId)
            }
        }

        val runningBalancesMinor = HashMap<Long, Long>(accountEntities.size)
        for (entity in accountEntities) {
            if (
                entity.groupType != AccountGroup.CREDIT_CARD &&
                entity.groupType != AccountGroup.DEBIT_CARD
            ) {
                val initialSigned = if (hasOpeningBalanceTx.contains(entity.id)) {
                    0L
                } else if (
                    entity.groupType.isLiabilityByDefault && entity.openingBalanceMinor > 0L
                ) {
                    if (entity.openingBalanceMinor == Long.MIN_VALUE) Long.MAX_VALUE else -entity.openingBalanceMinor
                } else {
                    entity.openingBalanceMinor
                }
                runningBalancesMinor[entity.id] = initialSigned
            }
        }

        val debitCardPeriodSpendMap = HashMap<Long, Long>()
        for (entity in accountEntities) {
            if (entity.groupType == AccountGroup.DEBIT_CARD) {
                debitCardPeriodSpendMap[entity.id] = 0L
            }
        }

        val investmentPrincipalMap = HashMap<Long, Long>()
        for (entity in accountEntities) {
            if (entity.groupType == AccountGroup.INVESTMENT) {
                val initialOpening = if (hasOpeningBalanceTx.contains(entity.id)) 0L else entity.openingBalanceMinor
                investmentPrincipalMap[entity.id] = initialOpening
            }
        }

        for (tx in allTransactions) {
            if (tx.ledgerDateEpochDay > referenceDateEpochDay) continue

            val amountMinor = safeAbs(tx.amountMinor)
            val feeMinor = safeAbs(tx.transferFeeMinor)

            if (
                debitCardPeriodSpendMap.containsKey(tx.accountId) &&
                tx.ledgerDateEpochDay in finMonthStartDay..finMonthEndDay
            ) {
                val currentSpend = debitCardPeriodSpendMap[tx.accountId] ?: 0L
                val updatedSpend = when (tx.type) {
                    TransactionType.EXPENSE ->
                        safeAdd(currentSpend, safeAdd(amountMinor, feeMinor))
                    TransactionType.REFUND ->
                        safeSubtract(currentSpend, amountMinor)
                    TransactionType.TRANSFER,
                    TransactionType.CC_SETTLEMENT ->
                        safeAdd(currentSpend, feeMinor)
                    TransactionType.INCOME,
                    TransactionType.OPENING_BALANCE,
                    TransactionType.RECONCILIATION -> currentSpend
                }
                debitCardPeriodSpendMap[tx.accountId] = updatedSpend
            }

            val effectiveSourceId = resolveEffectiveAccountId(tx.accountId)
            val effectiveTargetId = tx.transferToAccountId?.let { resolveEffectiveAccountId(it) }

            when (tx.type) {
                TransactionType.INCOME,
                TransactionType.REFUND -> {
                    runningBalancesMinor[effectiveSourceId]?.let { current ->
                        runningBalancesMinor[effectiveSourceId] = safeAdd(current, amountMinor)
                    }
                }
                TransactionType.EXPENSE -> {
                    val totalOutflow = safeAdd(amountMinor, feeMinor)
                    runningBalancesMinor[effectiveSourceId]?.let { current ->
                        runningBalancesMinor[effectiveSourceId] = safeSubtract(current, totalOutflow)
                    }
                }
                TransactionType.TRANSFER,
                TransactionType.CC_SETTLEMENT -> {
                    val totalOutflow = safeAdd(amountMinor, feeMinor)
                    runningBalancesMinor[effectiveSourceId]?.let { current ->
                        runningBalancesMinor[effectiveSourceId] = safeSubtract(current, totalOutflow)
                    }
                    if (effectiveTargetId != null) {
                        runningBalancesMinor[effectiveTargetId]?.let { currentTarget ->
                            runningBalancesMinor[effectiveTargetId] = safeAdd(currentTarget, amountMinor)
                        }
                    }
                }
                TransactionType.OPENING_BALANCE -> {
                    val isLiability = accountsById[effectiveSourceId]?.groupType?.isLiabilityByDefault == true
                    val isOutflow = tx.transferToAccountId != null || isLiability
                    runningBalancesMinor[effectiveSourceId]?.let { current ->
                        runningBalancesMinor[effectiveSourceId] = if (isOutflow) {
                            safeSubtract(current, amountMinor)
                        } else {
                            safeAdd(current, amountMinor)
                        }
                    }
                }
                TransactionType.RECONCILIATION -> {
                    val isOutflow = tx.transferToAccountId != null
                    runningBalancesMinor[effectiveSourceId]?.let { current ->
                        runningBalancesMinor[effectiveSourceId] = if (isOutflow) {
                            safeSubtract(current, amountMinor)
                        } else {
                            safeAdd(current, amountMinor)
                        }
                    }
                }
            }

            // Investment Principal Tracking (correctly excludes future transactions)
            val sourceEntity = accountsById[tx.accountId]
            val targetEntity = tx.transferToAccountId?.let { accountsById[it] }

            if (tx.type == TransactionType.OPENING_BALANCE && sourceEntity?.groupType == AccountGroup.INVESTMENT) {
                investmentPrincipalMap[tx.accountId]?.let { current ->
                    val isOutflow = tx.transferToAccountId != null
                    val absMinor = safeAbs(tx.amountMinor)
                    investmentPrincipalMap[tx.accountId] = if (isOutflow) {
                        safeSubtract(current, absMinor)
                    } else {
                        safeAdd(current, absMinor)
                    }
                }
            } else if (tx.type == TransactionType.TRANSFER && targetEntity?.groupType == AccountGroup.INVESTMENT) {
                investmentPrincipalMap[tx.transferToAccountId]?.let { current ->
                    investmentPrincipalMap[tx.transferToAccountId] = safeAdd(current, safeAbs(tx.amountMinor))
                }
            }
            if (tx.type == TransactionType.TRANSFER && sourceEntity?.groupType == AccountGroup.INVESTMENT && tx.transferToAccountId != null && tx.transferToAccountId != tx.accountId) {
                investmentPrincipalMap[tx.accountId]?.let { current ->
                    investmentPrincipalMap[tx.accountId] = safeSubtract(current, safeAbs(tx.amountMinor))
                }
            }
        }

        // Performance Optimization: Pre-group credit card transactions once to eliminate O(N * M) full scans
        val ccIds = accountEntities.filter { it.groupType == AccountGroup.CREDIT_CARD }.map { it.id }.toSet()
        val ccTxMap = HashMap<Long, MutableList<TransactionItem>>(ccIds.size)
        for (cardId in ccIds) {
            ccTxMap[cardId] = ArrayList()
        }
        for (tx in allTransactions) {
            val isSource = ccIds.contains(tx.accountId)
            val isTarget = tx.transferToAccountId != null && ccIds.contains(tx.transferToAccountId)
            if (!isSource && !isTarget) continue

            if (isSource) {
                val cardEntity = accountsById[tx.accountId]
                ccTxMap[tx.accountId]?.add(
                    TransactionItem(
                        id = tx.id,
                        type = tx.type,
                        timestampEpochMillis = tx.timestampEpochMillis,
                        ledgerDateEpochDay = tx.ledgerDateEpochDay,
                        amount = MinorMoney(tx.amountMinor),
                        currencyCode = cardEntity?.currencyCode ?: "INR",
                        accountId = tx.accountId,
                        accountName = cardEntity?.name ?: "Credit Card",
                        transferToAccountId = tx.transferToAccountId,
                        transferToAccountName = tx.transferToAccountId?.let { accountsById[it]?.name },
                        transferFee = MinorMoney(tx.transferFeeMinor),
                        categoryId = null,
                        payeeOrDescription = "",
                        note = ""
                    )
                )
            }
            if (isTarget && tx.transferToAccountId != tx.accountId) {
                val cardEntity = accountsById[tx.transferToAccountId]
                val normalizedType = if (tx.type == TransactionType.TRANSFER) TransactionType.CC_SETTLEMENT else tx.type
                ccTxMap[tx.transferToAccountId]?.add(
                    TransactionItem(
                        id = tx.id,
                        type = normalizedType,
                        timestampEpochMillis = tx.timestampEpochMillis,
                        ledgerDateEpochDay = tx.ledgerDateEpochDay,
                        amount = MinorMoney(tx.amountMinor),
                        currencyCode = cardEntity?.currencyCode ?: "INR",
                        accountId = tx.accountId,
                        accountName = accountsById[tx.accountId]?.name ?: "Source",
                        transferToAccountId = tx.transferToAccountId,
                        transferToAccountName = cardEntity?.name,
                        transferFee = MinorMoney.ZERO,
                        categoryId = null,
                        payeeOrDescription = "",
                        note = ""
                    )
                )
            }
        }

        return accountEntities.map { entity ->
            val rawOpening = if (entity.groupType.isLiabilityByDefault && entity.openingBalanceMinor > 0L) {
                if (entity.openingBalanceMinor == Long.MIN_VALUE) Long.MAX_VALUE else -entity.openingBalanceMinor
            } else {
                entity.openingBalanceMinor
            }
            val liveBalanceMinor = runningBalancesMinor[entity.id] ?: if (hasOpeningBalanceTx.contains(entity.id)) 0L else rawOpening
            val (invPrincipal, unrealizedPnl, returnPct) = if (entity.groupType == AccountGroup.INVESTMENT) {
                val principalMinor = investmentPrincipalMap[entity.id] ?: 0L
                val pnlMinor = safeSubtract(liveBalanceMinor, principalMinor)
                val pct = if (principalMinor > 0L) {
                    MoneyFormatterAndCalculator.calculatePercentage(pnlMinor, principalMinor)
                } else {
                    0f
                }
                Triple(MinorMoney(principalMinor), MinorMoney(pnlMinor), pct)
            } else {
                Triple(null, null, null)
            }
            when (entity.groupType) {
                AccountGroup.CREDIT_CARD -> {
                    val ccTransactions = ccTxMap[entity.id] ?: emptyList()

                    val settlementDay = (entity.settlementDayOfMonth ?: CreditCardCycleCalculator.DEFAULT_STATEMENT_DAY).coerceIn(1, 31)
                    val dueDay = (entity.paymentDueDayOfMonth ?: CreditCardCycleCalculator.DEFAULT_PAYMENT_DUE_DAY).coerceIn(1, 31)
                    val creditLimitMoney = entity.creditLimitMinor?.let { MinorMoney(it) }
                    val anchorEpochDay = ccTransactions.firstOrNull { it.type == TransactionType.OPENING_BALANCE }?.ledgerDateEpochDay
                        ?: java.time.Instant.ofEpochMilli(entity.createdAtEpochMillis)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate()
                            .toEpochDay()
                    val initialUnbilledMoney = if (entity.initialUnbilledMinor != null) {
                        val lastStatementCloseDate = CreditCardCycleCalculator.resolveLastStatementCloseDate(
                            referenceDateEpochDay = referenceDateEpochDay,
                            settlementDayOfMonth = settlementDay
                        )
                        if (anchorEpochDay <= lastStatementCloseDate.toEpochDay()) {
                            null
                        } else {
                            MinorMoney(entity.initialUnbilledMinor)
                        }
                    } else {
                        null
                    }
                    val ccMetrics = AccountingRulesEngine.computeCreditCardMetrics(
                        cardAccountId = entity.id,
                        openingBalance = MinorMoney(entity.openingBalanceMinor),
                        transactions = ccTransactions,
                        settlementDayOfMonth = settlementDay,
                        referenceDateEpochDay = referenceDateEpochDay,
                        creditLimit = creditLimitMoney,
                        initialUnbilledAmount = initialUnbilledMoney,
                        paymentDueDayOfMonth = dueDay,
                        openingAnchorEpochDay = anchorEpochDay,
                        initialUnbilledAtCreation = entity.initialUnbilledMinor?.let { MinorMoney(it) }
                    )

                    AccountSummary(
                        id = entity.id,
                        name = entity.name,
                        groupType = entity.groupType,
                        currencyCode = entity.currencyCode,
                        currentBalance = ccMetrics.currentBalance,
                        creditLimit = creditLimitMoney,
                        creditCardStatementPayable = ccMetrics.statementPayable,
                        creditCardPriorOverduePayable = ccMetrics.priorOverduePayable,
                        creditCardUnbilledAmount = ccMetrics.unbilledAmount,
                        settlementDayOfMonth = settlementDay,
                        paymentDueDayOfMonth = dueDay,
                        parentAccountId = null,
                        debitCardPeriodSpend = null,
                        includeInNetWorth = entity.includeInNetWorth,
                        isArchived = entity.isArchived,
                        colorHex = entity.colorHex,
                        iconName = entity.iconName,
                        isPinned = entity.isPinned,
                        sortOrder = entity.sortOrder,
                        investedPrincipal = invPrincipal,
                        unrealizedPnl = unrealizedPnl,
                        returnPercentage = returnPct
                    )
                }

                AccountGroup.DEBIT_CARD -> {
                    val parentBalanceMinor = entity.parentAccountId?.let { parentId ->
                        runningBalancesMinor[parentId] ?: 0L
                    } ?: 0L
                    val periodSpendMinor = (debitCardPeriodSpendMap[entity.id] ?: 0L).coerceAtLeast(0L)

                    AccountSummary(
                        id = entity.id,
                        name = entity.name,
                        groupType = entity.groupType,
                        currencyCode = entity.currencyCode,
                        currentBalance = MinorMoney(parentBalanceMinor),
                        creditLimit = null,
                        creditCardStatementPayable = null,
                        creditCardUnbilledAmount = null,
                        settlementDayOfMonth = null,
                        paymentDueDayOfMonth = null,
                        parentAccountId = entity.parentAccountId,
                        debitCardPeriodSpend = MinorMoney(periodSpendMinor),
                        includeInNetWorth = false,
                        isArchived = entity.isArchived,
                        colorHex = entity.colorHex,
                        iconName = entity.iconName,
                        isPinned = entity.isPinned,
                        sortOrder = entity.sortOrder,
                        investedPrincipal = invPrincipal,
                        unrealizedPnl = unrealizedPnl,
                        returnPercentage = returnPct
                    )
                }

                else -> {
                    AccountSummary(
                        id = entity.id,
                        name = entity.name,
                        groupType = entity.groupType,
                        currencyCode = entity.currencyCode,
                        currentBalance = MinorMoney(liveBalanceMinor),
                        creditLimit = entity.creditLimitMinor?.let { MinorMoney(it) },
                        creditCardStatementPayable = null,
                        creditCardUnbilledAmount = null,
                        settlementDayOfMonth = entity.settlementDayOfMonth,
                        paymentDueDayOfMonth = entity.paymentDueDayOfMonth,
                        parentAccountId = entity.parentAccountId,
                        debitCardPeriodSpend = null,
                        includeInNetWorth = entity.includeInNetWorth,
                        isArchived = entity.isArchived,
                        colorHex = entity.colorHex,
                        iconName = entity.iconName,
                        isPinned = entity.isPinned,
                        sortOrder = entity.sortOrder,
                        investedPrincipal = invPrincipal,
                        unrealizedPnl = unrealizedPnl,
                        returnPercentage = returnPct
                    )
                }
            }
        }
    }

    private fun safeAbs(value: Long): Long = com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeAbs(value)

    companion object {
        internal fun resolveDebitCardCurrency(existingCardCurrency: String?, parentBankCurrency: String): String {
            if (existingCardCurrency != null) {
                require(existingCardCurrency.equals(parentBankCurrency, ignoreCase = true)) {
                    "Debit card currency ($existingCardCurrency) must match linked bank account currency ($parentBankCurrency)."
                }
                return existingCardCurrency
            }
            return parentBankCurrency
        }

        internal fun validateAccountDeletionCascade(
            txCount: Int,
            accountId: Long,
            deleteTransactions: Boolean,
            linkedChildCount: Int = 0
        ) {
            require(linkedChildCount == 0) {
                "Delete the $linkedChildCount linked debit card(s) before deleting account #$accountId."
            }
            if (!deleteTransactions) {
                require(txCount == 0) {
                    "Cannot delete account #$accountId while $txCount transactions still reference it."
                }
            }
        }
    }
}
