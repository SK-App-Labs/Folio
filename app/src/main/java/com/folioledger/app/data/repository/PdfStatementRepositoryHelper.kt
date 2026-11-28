/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.folioledger.app.data.backup.AutoBackupRetentionEngine
import com.folioledger.app.data.backup.SessionChangeTracker
import com.folioledger.app.data.local.DefaultCategorySeeder
import com.folioledger.app.data.local.FolioDatabase
import com.folioledger.app.data.local.dao.CategoryAndTagDao
import com.folioledger.app.data.local.entity.AccountEntity
import com.folioledger.app.data.local.entity.BookmarkEntity
import com.folioledger.app.data.local.entity.BudgetEntity
import com.folioledger.app.data.local.entity.CategoryEntity
import com.folioledger.app.data.local.entity.InstallmentPlanEntity
import com.folioledger.app.data.local.entity.RecurringRuleEntity
import com.folioledger.app.data.local.entity.TagEntity
import com.folioledger.app.data.local.entity.TransactionEntity
import com.folioledger.app.data.local.entity.TransactionSplitEntity
import com.folioledger.app.data.local.entity.TransactionTagCrossRef
import com.folioledger.app.data.local.entity.TransactionWithDetails
import com.folioledger.app.data.pdf.AccountSnapshotPdfItem
import com.folioledger.app.data.pdf.AccountStatementPdfData
import com.folioledger.app.data.pdf.AnnualTaxPdfData
import com.folioledger.app.data.pdf.CategoryAggregateItem
import com.folioledger.app.data.pdf.PassbookPeriodPresetResolver
import com.folioledger.app.data.pdf.PdfStatementEngine
import com.folioledger.app.data.pdf.TaxDeductionPdfItem
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountPassbookEntryUiModel
import com.folioledger.app.domain.model.AutoBackupRetention
import com.folioledger.app.domain.model.CategoryNode
import com.folioledger.app.domain.model.CategoryType
import com.folioledger.app.domain.model.DuplicateTransactionSample
import com.folioledger.app.domain.model.FiscalYearType
import com.folioledger.app.domain.model.ImportCsvPreview
import com.folioledger.app.domain.model.ImportCsvResult
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.PdfExportResult
import com.folioledger.app.domain.model.RepeatFrequency
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.SplitLineItem
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionBookmark
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.domain.repository.CategoryAndTagRepository
import com.folioledger.app.domain.repository.SettingsAndBackupRepository
import com.folioledger.app.domain.usecase.AccountingRulesEngine
import com.folioledger.app.domain.util.CreditCardCycleCalculator
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.pow

class PdfStatementRepositoryHelper(
    private val context: Context,
    private val database: FolioDatabase,
    private val userPreferences: UserPreferencesDataSource,
) {

    private data class FiscalRangeInfo(
        val startEpochDay: Long,
        val endEpochDay: Long,
        val title: String,
        val subtitle: String,
    )

    private fun mapDetailsToTransactionItem(
        txWithDetails: TransactionWithDetails,
        accountsById: Map<Long, AccountEntity>,
        categoriesById: Map<Long, CategoryEntity>,
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
                note = split.note,
            )
        }

        val mappedTags = txWithDetails.tags.map { it.name }
        val preservesTransferTarget = tx.type == TransactionType.TRANSFER ||
            tx.type == TransactionType.CC_SETTLEMENT ||
            tx.type == TransactionType.OPENING_BALANCE ||
            tx.type == TransactionType.RECONCILIATION
        val isTransferType = tx.type == TransactionType.TRANSFER || tx.type == TransactionType.CC_SETTLEMENT

        return TransactionItem(
            id = tx.id,
            type = tx.type,
            timestampEpochMillis = tx.timestampEpochMillis,
            ledgerDateEpochDay = tx.ledgerDateEpochDay,
            amount = MinorMoney(tx.amountMinor),
            currencyCode = tx.currencyCode,
            accountId = tx.accountId,
            accountName = sourceAccount?.name ?: "Unknown Account",
            transferToAccountId = if (preservesTransferTarget) tx.transferToAccountId else null,
            transferToAccountName = if (isTransferType) targetAccount?.name else null,
            transferFee = if (isTransferType) MinorMoney(tx.transferFeeMinor) else MinorMoney.ZERO,
            categoryId = if (isTransferType) null else tx.categoryId,
            categoryName = if (isTransferType) null else (if (tx.isSplit) "Split" else category?.name),
            parentCategoryName = if (isTransferType) null else parentCategory?.name,
            categoryColorHex = if (isTransferType) null else (category?.colorHex ?: parentCategory?.colorHex),
            categoryIconName = if (isTransferType) null else (category?.iconName ?: parentCategory?.iconName),
            isSplit = !isTransferType && tx.isSplit,
            splits = if (isTransferType) emptyList() else mappedSplits,
            tags = mappedTags,
            payeeOrDescription = tx.payeeOrDescription,
            note = tx.note,
        )
    }

    suspend fun generateAccountStatementPdf(
        accountId: Long,
        startEpochDay: Long,
        endEpochDay: Long,
        includeNotesAndTags: Boolean,
        includeRunningBalance: Boolean,
    ): Result<PdfExportResult> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            val prefs = userPreferences.observeUserPreferences().first()
            val allAccountEntities = database.accountDao().getAllAccountsOnce()
            val accountEntity = allAccountEntities.firstOrNull { it.id == accountId }
                ?: error("Account with ID $accountId not found")
            val allCategories = database.categoryAndTagDao().getAllCategoriesOnce().associateBy { it.id }
            val accountsById = allAccountEntities.associateBy { it.id }

            val (anchorAccount, effectiveAccountIds) = resolveStatementScope(accountEntity, allAccountEntities)

            val allTxWithDetails = database.transactionDao().getAllTransactionsWithDetailsOnce()
            val scopedTxWithDetails = allTxWithDetails.filter { details ->
                details.transaction.accountId in effectiveAccountIds ||
                    (details.transaction.transferToAccountId != null && details.transaction.transferToAccountId in effectiveAccountIds)
            }
            val allTransactions = scopedTxWithDetails.map { txDetails ->
                mapDetailsToTransactionItem(txDetails, accountsById, allCategories)
            }

            val matchingChronological = allTransactions
                .filter { tx ->
                    when (tx.type) {
                        TransactionType.EXPENSE,
                        TransactionType.INCOME,
                        TransactionType.REFUND,
                        TransactionType.OPENING_BALANCE,
                        TransactionType.RECONCILIATION,
                        -> tx.accountId in effectiveAccountIds

                        TransactionType.TRANSFER,
                        TransactionType.CC_SETTLEMENT,
                        ->
                            tx.accountId in effectiveAccountIds ||
                                (tx.transferToAccountId != null && tx.transferToAccountId in effectiveAccountIds)
                    }
                }
                .sortedWith(
                    compareBy<TransactionItem> { it.ledgerDateEpochDay }
                        .thenBy { if (it.type == TransactionType.OPENING_BALANCE) 0 else 1 }
                        .thenBy { it.timestampEpochMillis }
                        .thenBy { it.id },
                )

            val todayEpoch = LocalDate.now().toEpochDay()
            val resolvedStartEpochDay: Long
            val resolvedEndEpochDay: Long
            if (startEpochDay == PassbookPeriodPresetResolver.ALL_TIME_SENTINEL_EPOCH_DAY) {
                val earliestTxDay = matchingChronological.minOfOrNull { it.ledgerDateEpochDay }
                val latestTxDay = matchingChronological.maxOfOrNull { it.ledgerDateEpochDay }
                val createdEpoch = java.time.Instant.ofEpochMilli(anchorAccount.createdAtEpochMillis)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()
                    .toEpochDay()
                val baseStart = minOf(earliestTxDay ?: createdEpoch, todayEpoch)
                val baseEnd = maxOf(latestTxDay ?: todayEpoch, todayEpoch, endEpochDay)
                resolvedStartEpochDay = minOf(baseStart, baseEnd)
                resolvedEndEpochDay = maxOf(baseStart, baseEnd)
            } else {
                resolvedStartEpochDay = minOf(startEpochDay, endEpochDay)
                resolvedEndEpochDay = maxOf(startEpochDay, endEpochDay)
            }

            val deltasWithTx: List<Pair<TransactionItem, Long>> = matchingChronological.map { tx ->
                val deltaMinor = AccountingRulesEngine.computeSignedDeltaMinorForAccount(
                    tx = tx,
                    effectiveAccountIds = effectiveAccountIds,
                    isSourceLiabilityAccount = anchorAccount.groupType.isLiabilityByDefault,
                )
                tx to deltaMinor
            }

            val hasOpeningTx = deltasWithTx.any { it.first.type == TransactionType.OPENING_BALANCE }
            val initialAnchor = if (hasOpeningTx) {
                0L
            } else if (anchorAccount.groupType.isLiabilityByDefault && anchorAccount.openingBalanceMinor > 0L) {
                safeNegate(anchorAccount.openingBalanceMinor)
            } else {
                anchorAccount.openingBalanceMinor
            }

            var runningBalanceMinor = initialAnchor
            var periodOpeningBalanceMinor = runningBalanceMinor
            var periodInflowsMinor = 0L
            var periodOutflowsMinor = 0L

            val periodEntries = mutableListOf<AccountPassbookEntryUiModel>()

            for ((tx, deltaMinor) in deltasWithTx) {
                if (tx.ledgerDateEpochDay < resolvedStartEpochDay) {
                    runningBalanceMinor = safeAdd(runningBalanceMinor, deltaMinor)
                    periodOpeningBalanceMinor = runningBalanceMinor
                } else if (tx.ledgerDateEpochDay in resolvedStartEpochDay..resolvedEndEpochDay) {
                    runningBalanceMinor = safeAdd(runningBalanceMinor, deltaMinor)
                    if (deltaMinor > 0L) {
                        periodInflowsMinor = safeAdd(periodInflowsMinor, deltaMinor)
                    } else if (deltaMinor < 0L) {
                        periodOutflowsMinor = safeAdd(periodOutflowsMinor, safeAbs(deltaMinor))
                    }
                    val viaCardName = if (accountEntity.groupType != AccountGroup.DEBIT_CARD &&
                        tx.accountId != accountEntity.id &&
                        tx.accountId in effectiveAccountIds
                    ) {
                        accountsById[tx.accountId]?.name
                    } else {
                        null
                    }
                    periodEntries.add(
                        AccountPassbookEntryUiModel(
                            transaction = tx,
                            signedDelta = MinorMoney(deltaMinor),
                            isInflow = deltaMinor >= 0L,
                            runningBalanceAfter = MinorMoney(runningBalanceMinor),
                            viaLinkedDebitCardName = viaCardName,
                        ),
                    )
                } else {
                    runningBalanceMinor = safeAdd(runningBalanceMinor, deltaMinor)
                }
            }

            val startDate = LocalDate.ofEpochDay(resolvedStartEpochDay)
            val endDate = LocalDate.ofEpochDay(resolvedEndEpochDay)
            val dFmt = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)
            val periodTitle = "${startDate.format(dFmt)} \u2013 ${endDate.format(dFmt)}"
            val currencySpec = SupportedCurrencies.fromCode(accountEntity.currencyCode)

            val isCreditCard = accountEntity.groupType == AccountGroup.CREDIT_CARD
            val groupTitle = when (accountEntity.groupType) {
                AccountGroup.BANK_ACCOUNT -> "Bank Account"
                AccountGroup.CREDIT_CARD -> "Credit Card"
                AccountGroup.DEBIT_CARD -> "Debit Card"
                AccountGroup.CASH -> "Cash & Wallet"
                AccountGroup.INVESTMENT -> "Investment & Wealth"
                AccountGroup.LOAN -> "Loan & Liability"
                AccountGroup.SAVINGS -> "Savings Account"
                AccountGroup.PREPAID_WALLET -> "Prepaid Wallet"
                AccountGroup.OVERDRAFT -> "Overdraft Account"
                else -> "Account"
            }

            val creditCardStatementPayable = if (isCreditCard) {
                val settlementDay = (accountEntity.settlementDayOfMonth ?: CreditCardCycleCalculator.DEFAULT_STATEMENT_DAY).coerceIn(1, 31)
                val dueDay = (accountEntity.paymentDueDayOfMonth ?: CreditCardCycleCalculator.DEFAULT_PAYMENT_DUE_DAY).coerceIn(1, 31)
                val ccTransactions = allTransactions.filter {
                    it.accountId == accountEntity.id ||
                        (it.transferToAccountId != null && it.transferToAccountId == accountEntity.id)
                }
                val creditLimitMoney = accountEntity.creditLimitMinor?.let { MinorMoney(it) }
                val anchorEpochDay = ccTransactions.firstOrNull { it.type == TransactionType.OPENING_BALANCE }?.ledgerDateEpochDay
                    ?: java.time.Instant.ofEpochMilli(accountEntity.createdAtEpochMillis)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                        .toEpochDay()
                val initialUnbilledMoney = if (accountEntity.initialUnbilledMinor != null) {
                    val lastStatementCloseDate = CreditCardCycleCalculator.resolveLastStatementCloseDate(
                        referenceDateEpochDay = resolvedEndEpochDay,
                        settlementDayOfMonth = settlementDay,
                    )
                    if (anchorEpochDay <= lastStatementCloseDate.toEpochDay()) {
                        null
                    } else {
                        MinorMoney(accountEntity.initialUnbilledMinor)
                    }
                } else {
                    null
                }
                val ccMetrics = AccountingRulesEngine.computeCreditCardMetrics(
                    cardAccountId = accountEntity.id,
                    openingBalance = MinorMoney(accountEntity.openingBalanceMinor),
                    transactions = ccTransactions,
                    settlementDayOfMonth = settlementDay,
                    paymentDueDayOfMonth = dueDay,
                    referenceDateEpochDay = resolvedEndEpochDay,
                    creditLimit = creditLimitMoney,
                    initialUnbilledAmount = initialUnbilledMoney,
                    openingAnchorEpochDay = anchorEpochDay,
                    initialUnbilledAtCreation = accountEntity.initialUnbilledMinor?.let { MinorMoney(it) },
                )
                ccMetrics.statementPayable
            } else {
                null
            }

            val data = AccountStatementPdfData(
                accountName = accountEntity.name,
                accountGroupTitle = groupTitle,
                institutionOrDetails = null,
                periodTitle = periodTitle,
                periodSubtitle = "Statement Period: $periodTitle",
                currencySpec = currencySpec,
                numberGrouping = prefs.numberGrouping,
                openingBalance = MinorMoney(periodOpeningBalanceMinor),
                totalInflows = MinorMoney(periodInflowsMinor),
                totalOutflows = MinorMoney(periodOutflowsMinor),
                netPeriodFlow = MinorMoney(safeSubtract(periodInflowsMinor, periodOutflowsMinor)),
                closingBalance = MinorMoney(safeSubtract(safeAdd(periodOpeningBalanceMinor, periodInflowsMinor), periodOutflowsMinor)),
                transactionCount = periodEntries.size,
                includeRunningBalance = includeRunningBalance,
                includeNotesAndTags = includeNotesAndTags,
                isCreditCard = isCreditCard,
                creditCardLimit = accountEntity.creditLimitMinor?.let { MinorMoney(it) },
                creditCardStatementPayable = creditCardStatementPayable,
                creditCardCycleText = if (isCreditCard) "Statement Settlement Day: ${accountEntity.settlementDayOfMonth ?: CreditCardCycleCalculator.DEFAULT_STATEMENT_DAY}" else null,
                creditCardDueDateText = if (isCreditCard) "Payment Due Day: ${accountEntity.paymentDueDayOfMonth ?: CreditCardCycleCalculator.DEFAULT_PAYMENT_DUE_DAY}" else null,
                entries = periodEntries,
                generatedTimestampText = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.US)),
            )

            val allowPublicMirror = !prefs.biometricLockEnabled && !prefs.isDemoModeActive
            PdfStatementEngine.generateAccountStatementPdf(context, data, allowPublicMirror).getOrThrow()
        }
    }

    suspend fun generateAnnualTaxStatementPdf(
        fiscalYearType: FiscalYearType,
        anchorYear: Int,
        customStartEpochDay: Long?,
        customEndEpochDay: Long?,
        selectedTaxTags: Set<String>,
    ): Result<PdfExportResult> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            val prefs = userPreferences.observeUserPreferences().first()
            val primaryCurrencySpec = SupportedCurrencies.fromCode(prefs.primaryCurrencyCode)
            val secondaryCurrencies = prefs.secondaryCurrencies
            val secondaryMap = secondaryCurrencies.associateBy { it.code.trim().uppercase(Locale.US) }

            val rangeInfo = when (fiscalYearType) {
                FiscalYearType.CALENDAR_YEAR -> {
                    val s = LocalDate.of(anchorYear, 1, 1).toEpochDay()
                    val e = LocalDate.of(anchorYear, 12, 31).toEpochDay()
                    FiscalRangeInfo(s, e, "Calendar Year $anchorYear", "01 Jan $anchorYear \u2013 31 Dec $anchorYear")
                }

                FiscalYearType.APRIL_FISCAL_YEAR -> {
                    val s = LocalDate.of(anchorYear, 4, 1).toEpochDay()
                    val e = LocalDate.of(anchorYear + 1, 3, 31).toEpochDay()
                    val nextShort = ((anchorYear + 1) % 100).toString().padStart(2, '0')
                    FiscalRangeInfo(s, e, "Fiscal Year $anchorYear\u2013$nextShort", "01 Apr $anchorYear \u2013 31 Mar ${anchorYear + 1}")
                }

                FiscalYearType.JULY_FISCAL_YEAR -> {
                    val s = LocalDate.of(anchorYear, 7, 1).toEpochDay()
                    val e = LocalDate.of(anchorYear + 1, 6, 30).toEpochDay()
                    val nextShort = ((anchorYear + 1) % 100).toString().padStart(2, '0')
                    FiscalRangeInfo(s, e, "Fiscal Year $anchorYear\u2013$nextShort", "01 Jul $anchorYear \u2013 30 Jun ${anchorYear + 1}")
                }

                FiscalYearType.CUSTOM_RANGE -> {
                    val rawS = customStartEpochDay ?: LocalDate.of(anchorYear, 1, 1).toEpochDay()
                    val rawE = customEndEpochDay ?: LocalDate.of(anchorYear, 12, 31).toEpochDay()
                    val s = minOf(rawS, rawE)
                    val e = maxOf(rawS, rawE)
                    val sDate = LocalDate.ofEpochDay(s)
                    val eDate = LocalDate.ofEpochDay(e)
                    val dFmt = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)
                    FiscalRangeInfo(s, e, "Financial & Tax Audit Statement", "${sDate.format(dFmt)} \u2013 ${eDate.format(dFmt)}")
                }
            }

            val startEpochDay = rangeInfo.startEpochDay
            val endEpochDay = rangeInfo.endEpochDay

            val allAccountEntities = database.accountDao().getAllAccountsOnce()
            val accountsById = allAccountEntities.associateBy { it.id }
            val allCategories = database.categoryAndTagDao().getAllCategoriesOnce().associateBy { it.id }

            val allTxWithDetails = database.transactionDao().getAllTransactionsWithDetailsOnce()
            val allTransactions = allTxWithDetails.map { txDetails ->
                mapDetailsToTransactionItem(txDetails, accountsById, allCategories)
            }

            val periodTransactions = allTransactions.filter {
                it.ledgerDateEpochDay in startEpochDay..endEpochDay
            }

            var grossInflowsMinor = 0L
            var totalExpensesMinor = 0L

            val incomeByCategory = mutableMapOf<String, MutableList<Long>>()
            val expenseByCategory = mutableMapOf<String, MutableList<Long>>()
            val taxDeductions = mutableListOf<TaxDeductionPdfItem>()
            val dFmt = DateTimeFormatter.ofPattern("dd MMM", Locale.US)

            val normalizedSelectedTaxTags = selectedTaxTags
                .map { it.trim().removePrefix("#").lowercase(Locale.US) }
                .filter { it.isNotEmpty() }
                .toSet()

            for (tx in periodTransactions) {
                val sec = secondaryMap[tx.currencyCode.trim().uppercase(Locale.US)]
                fun toPrimaryMinor(rawMinor: Long): Long = MoneyFormatterAndCalculator.convertToPrimaryMinor(
                    sourceMinor = rawMinor,
                    sourceCurrencyCode = tx.currencyCode,
                    primaryCurrencyCode = primaryCurrencySpec.code,
                    exchangeRate = sec?.exchangeRateToPrimary,
                )

                val convertedMinor = toPrimaryMinor(tx.amount.amountMinor)
                val convertedFeeMinor = toPrimaryMinor(tx.transferFee.amountMinor)
                val matchedTaxTag = findMatchingTaxTag(tx.tags, normalizedSelectedTaxTags)
                val allowCategoryFallback = normalizedSelectedTaxTags.isEmpty()

                when (tx.type) {
                    TransactionType.INCOME -> {
                        grossInflowsMinor = safeAdd(grossInflowsMinor, convertedMinor)
                        if (tx.isSplit && tx.splits.isNotEmpty()) {
                            for (split in tx.splits) {
                                val splitConverted = toPrimaryMinor(split.amount.amountMinor)
                                val splitCatName = split.categoryName.ifBlank { "Miscellaneous Income" }
                                incomeByCategory.getOrPut(splitCatName) { mutableListOf() }.add(splitConverted)
                            }
                        } else {
                            val catName = tx.categoryName ?: "Miscellaneous Income"
                            incomeByCategory.getOrPut(catName) { mutableListOf() }.add(convertedMinor)
                        }
                    }

                    TransactionType.REFUND -> {
                        totalExpensesMinor = safeSubtract(totalExpensesMinor, convertedMinor)
                        if (tx.isSplit && tx.splits.isNotEmpty()) {
                            val txDate = LocalDate.ofEpochDay(tx.ledgerDateEpochDay)
                            val tagLabel = matchedTaxTag ?: "Refund / Reversal"
                            for (split in tx.splits) {
                                val splitConverted = toPrimaryMinor(split.amount.amountMinor)
                                val splitCatName = split.categoryName.ifBlank { "General Expense" }
                                expenseByCategory.getOrPut(splitCatName) { mutableListOf() }.add(-splitConverted)
                                if (matchedTaxTag != null || (allowCategoryFallback && isCategoryTaxDeductible(split.categoryName))) {
                                    taxDeductions.add(
                                        TaxDeductionPdfItem(
                                            dateText = txDate.format(dFmt),
                                            payee = tx.payeeOrDescription.ifBlank { split.categoryName.ifBlank { "Refund" } },
                                            categoryName = split.categoryName.ifBlank { "General" },
                                            tagOrDeductionType = tagLabel,
                                            note = split.note.ifBlank { tx.note }.ifBlank { "Refund reversal" },
                                            amountMinor = -split.amount.amountMinor,
                                            currencyCode = tx.currencyCode,
                                            convertedMinor = -splitConverted,
                                        ),
                                    )
                                }
                            }
                        } else {
                            val catName = tx.categoryName ?: "General Expense"
                            expenseByCategory.getOrPut(catName) { mutableListOf() }.add(-convertedMinor)
                            if (matchedTaxTag != null || (allowCategoryFallback && isCategoryTaxDeductible(tx.categoryName))) {
                                val tagLabel = matchedTaxTag ?: "Refund / Reversal"
                                val txDate = LocalDate.ofEpochDay(tx.ledgerDateEpochDay)
                                taxDeductions.add(
                                    TaxDeductionPdfItem(
                                        dateText = txDate.format(dFmt),
                                        payee = tx.payeeOrDescription.ifBlank { tx.categoryName ?: "Refund" },
                                        categoryName = tx.categoryName ?: "General",
                                        tagOrDeductionType = tagLabel,
                                        note = tx.note.ifBlank { "Refund reversal" },
                                        amountMinor = -tx.amount.amountMinor,
                                        currencyCode = tx.currencyCode,
                                        convertedMinor = -convertedMinor,
                                    ),
                                )
                            }
                        }
                    }

                    TransactionType.EXPENSE -> {
                        val totalCharge = safeAdd(convertedMinor, convertedFeeMinor)
                        totalExpensesMinor = safeAdd(totalExpensesMinor, totalCharge)
                        if (tx.isSplit && tx.splits.isNotEmpty()) {
                            for (split in tx.splits) {
                                val splitConverted = toPrimaryMinor(split.amount.amountMinor)
                                val splitCatName = split.categoryName.ifBlank { "General Expense" }
                                expenseByCategory.getOrPut(splitCatName) { mutableListOf() }.add(splitConverted)
                            }
                        } else {
                            val catName = tx.categoryName ?: "General Expense"
                            expenseByCategory.getOrPut(catName) { mutableListOf() }.add(totalCharge)
                        }

                        if (tx.isSplit && tx.splits.isNotEmpty()) {
                            val txDate = LocalDate.ofEpochDay(tx.ledgerDateEpochDay)
                            val tagLabel = matchedTaxTag ?: "Deductible Split"
                            for (split in tx.splits) {
                                if (matchedTaxTag != null || (allowCategoryFallback && isCategoryTaxDeductible(split.categoryName))) {
                                    val splitConverted = toPrimaryMinor(split.amount.amountMinor)
                                    taxDeductions.add(
                                        TaxDeductionPdfItem(
                                            dateText = txDate.format(dFmt),
                                            payee = tx.payeeOrDescription.ifBlank { split.categoryName.ifBlank { "Expense" } },
                                            categoryName = split.categoryName.ifBlank { "General" },
                                            tagOrDeductionType = tagLabel,
                                            note = split.note.ifBlank { tx.note }.ifBlank { null },
                                            amountMinor = split.amount.amountMinor,
                                            currencyCode = tx.currencyCode,
                                            convertedMinor = splitConverted,
                                        ),
                                    )
                                }
                            }
                        } else if (matchedTaxTag != null || (allowCategoryFallback && isCategoryTaxDeductible(tx.categoryName))) {
                            val tagLabel = matchedTaxTag ?: "Deductible"
                            val txDate = LocalDate.ofEpochDay(tx.ledgerDateEpochDay)
                            val totalDeductibleMinor = safeAdd(tx.amount.amountMinor, tx.transferFee.amountMinor)
                            taxDeductions.add(
                                TaxDeductionPdfItem(
                                    dateText = txDate.format(dFmt),
                                    payee = tx.payeeOrDescription.ifBlank { tx.categoryName ?: "Expense" },
                                    categoryName = tx.categoryName ?: "General",
                                    tagOrDeductionType = tagLabel,
                                    note = tx.note.ifBlank { null },
                                    amountMinor = totalDeductibleMinor,
                                    currencyCode = tx.currencyCode,
                                    convertedMinor = totalCharge,
                                ),
                            )
                        }
                    }

                    TransactionType.TRANSFER,
                    TransactionType.CC_SETTLEMENT,
                    -> {
                        if (convertedFeeMinor > 0L) {
                            totalExpensesMinor = safeAdd(totalExpensesMinor, convertedFeeMinor)
                            val catName = tx.categoryName ?: "Transfer Fees"
                            expenseByCategory.getOrPut(catName) { mutableListOf() }.add(convertedFeeMinor)
                            if (matchedTaxTag != null) {
                                val txDate = LocalDate.ofEpochDay(tx.ledgerDateEpochDay)
                                taxDeductions.add(
                                    TaxDeductionPdfItem(
                                        dateText = txDate.format(dFmt),
                                        payee = tx.payeeOrDescription.ifBlank { "Transfer Fee" },
                                        categoryName = catName,
                                        tagOrDeductionType = matchedTaxTag,
                                        note = tx.note.ifBlank { null },
                                        amountMinor = tx.transferFee.amountMinor,
                                        currencyCode = tx.currencyCode,
                                        convertedMinor = convertedFeeMinor,
                                    ),
                                )
                            }
                        }
                    }

                    else -> Unit
                }
            }

            if (totalExpensesMinor < 0L) {
                val refundSurplusMinor = safeAbs(totalExpensesMinor)
                grossInflowsMinor = safeAdd(grossInflowsMinor, refundSurplusMinor)
                incomeByCategory.getOrPut("Net Refund Credits") { mutableListOf() }.add(refundSurplusMinor)
                totalExpensesMinor = 0L
            }

            val incomeSchedules = incomeByCategory.map { (catName, minors) ->
                val sum = minors.fold(0L) { acc, m -> safeAdd(acc, m) }
                val pct = if (grossInflowsMinor > 0L) MoneyFormatterAndCalculator.calculatePercentage(sum, grossInflowsMinor) else 0f
                CategoryAggregateItem(
                    categoryName = catName,
                    count = minors.size,
                    amountMinor = sum,
                    convertedAmountMinor = sum,
                    percentageOfTotal = pct,
                )
            }.sortedByDescending { it.convertedAmountMinor }

            val expensePositiveTotal = expenseByCategory.values.map { minors ->
                minors.fold(0L) { acc, m -> safeAdd(acc, m) }
            }.filter { it > 0L }.fold(0L) { acc, v -> safeAdd(acc, v) }

            val expenseSchedules = expenseByCategory.mapNotNull { (catName, minors) ->
                val sum = minors.fold(0L) { acc, m -> safeAdd(acc, m) }
                if (sum <= 0L) {
                    null
                } else {
                    val pct = if (expensePositiveTotal > 0L) MoneyFormatterAndCalculator.calculatePercentage(sum, expensePositiveTotal) else 0f
                    CategoryAggregateItem(
                        categoryName = catName,
                        count = minors.size,
                        amountMinor = sum,
                        convertedAmountMinor = sum,
                        percentageOfTotal = pct,
                    )
                }
            }.sortedByDescending { it.convertedAmountMinor }

            val totalTaxDeductionsMinor = taxDeductions.fold(0L) { acc, t -> safeAdd(acc, t.convertedMinor) }
            val netSavingsMinor = safeSubtract(grossInflowsMinor, totalExpensesMinor)
            val savingsRate = if (grossInflowsMinor > 0L) {
                MoneyFormatterAndCalculator.calculatePercentage(netSavingsMinor.coerceAtLeast(0L), grossInflowsMinor)
            } else {
                0f
            }

            // Account Balance Sheet Snapshot as of endEpochDay
            var totalAssetsMinor = 0L
            var totalLiabilitiesMinor = 0L
            val accountsSnapshot = mutableListOf<AccountSnapshotPdfItem>()

            val debitCardsByParent = allAccountEntities
                .filter { it.groupType == AccountGroup.DEBIT_CARD && it.parentAccountId != null }
                .groupBy { it.parentAccountId!! }

            val txByAccount = mutableMapOf<Long, MutableList<TransactionItem>>()
            for (tx in allTransactions) {
                txByAccount.getOrPut(tx.accountId) { mutableListOf() }.add(tx)
                if (tx.transferToAccountId != null) {
                    txByAccount.getOrPut(tx.transferToAccountId) { mutableListOf() }.add(tx)
                }
            }

            for (acc in allAccountEntities.filter {
                !it.isArchived &&
                    it.includeInNetWorth &&
                    !(it.groupType == AccountGroup.DEBIT_CARD && it.parentAccountId != null)
            }) {
                val childDebitCardIds = debitCardsByParent[acc.id]?.map { it.id }.orEmpty()
                val accEffectiveIds = setOf(acc.id) + childDebitCardIds
                val accTxChronological = accEffectiveIds.flatMap { txByAccount[it].orEmpty() }
                    .distinctBy { it.id }
                    .filter { tx ->
                        when (tx.type) {
                            TransactionType.EXPENSE,
                            TransactionType.INCOME,
                            TransactionType.REFUND,
                            TransactionType.OPENING_BALANCE,
                            TransactionType.RECONCILIATION,
                            -> tx.accountId in accEffectiveIds

                            TransactionType.TRANSFER,
                            TransactionType.CC_SETTLEMENT,
                            ->
                                tx.accountId in accEffectiveIds ||
                                    (tx.transferToAccountId != null && tx.transferToAccountId in accEffectiveIds)
                        }
                    }

                val hasOpening = accTxChronological.any { it.type == TransactionType.OPENING_BALANCE }
                val initialAnchor = if (hasOpening) {
                    0L
                } else if (acc.groupType.isLiabilityByDefault && acc.openingBalanceMinor > 0L) {
                    safeNegate(acc.openingBalanceMinor)
                } else {
                    acc.openingBalanceMinor
                }
                var running = initialAnchor
                for (tx in accTxChronological) {
                    if (tx.ledgerDateEpochDay <= endEpochDay) {
                        val delta = AccountingRulesEngine.computeSignedDeltaMinorForAccount(
                            tx = tx,
                            effectiveAccountIds = accEffectiveIds,
                            isSourceLiabilityAccount = acc.groupType.isLiabilityByDefault,
                        )
                        running = safeAdd(running, delta)
                    }
                }

                val sec = secondaryMap[acc.currencyCode.trim().uppercase(Locale.US)]
                val converted = MoneyFormatterAndCalculator.convertToPrimaryMinor(
                    sourceMinor = running,
                    sourceCurrencyCode = acc.currencyCode,
                    primaryCurrencyCode = primaryCurrencySpec.code,
                    exchangeRate = sec?.exchangeRateToPrimary,
                )

                val isAsset = converted >= 0L
                if (isAsset) {
                    totalAssetsMinor = safeAdd(totalAssetsMinor, converted)
                } else {
                    totalLiabilitiesMinor = safeAdd(totalLiabilitiesMinor, safeAbs(converted))
                }

                val groupName = when (acc.groupType) {
                    AccountGroup.BANK_ACCOUNT -> "Bank"
                    AccountGroup.CREDIT_CARD -> "Credit Card"
                    AccountGroup.DEBIT_CARD -> "Debit Card"
                    AccountGroup.CASH -> "Cash"
                    AccountGroup.INVESTMENT -> "Investment"
                    AccountGroup.LOAN -> "Loan"
                    AccountGroup.SAVINGS -> "Savings"
                    AccountGroup.PREPAID_WALLET -> "Prepaid Wallet"
                    AccountGroup.OVERDRAFT -> "Overdraft"
                    else -> "Other"
                }

                accountsSnapshot.add(
                    AccountSnapshotPdfItem(
                        accountName = acc.name,
                        groupName = groupName,
                        currencyCode = acc.currencyCode,
                        nativeBalanceMinor = running,
                        convertedBalanceMinor = converted,
                        isAsset = isAsset,
                    ),
                )
            }

            val netWorthMinor = safeSubtract(totalAssetsMinor, totalLiabilitiesMinor)

            val valuationNotes = secondaryCurrencies.map { sec ->
                "1 ${sec.code} = ${sec.exchangeRateToPrimary} ${primaryCurrencySpec.code}"
            }

            val data = AnnualTaxPdfData(
                fiscalPeriodTitle = rangeInfo.title,
                fiscalPeriodSubtitle = rangeInfo.subtitle,
                fiscalYearType = fiscalYearType,
                primaryCurrencySpec = primaryCurrencySpec,
                secondaryCurrencies = secondaryCurrencies,
                numberGrouping = prefs.numberGrouping,
                grossInflowsMinor = grossInflowsMinor,
                totalExpensesMinor = totalExpensesMinor,
                netSavingsMinor = netSavingsMinor,
                savingsRatePercent = savingsRate,
                incomeSources = incomeSchedules,
                expenseCategories = expenseSchedules,
                taxDeductions = taxDeductions,
                totalTaxDeductionsMinor = totalTaxDeductionsMinor,
                accountsSnapshot = accountsSnapshot,
                totalAssetsMinor = totalAssetsMinor,
                totalLiabilitiesMinor = totalLiabilitiesMinor,
                netWorthMinor = netWorthMinor,
                valuationExchangeNotes = valuationNotes,
                generatedTimestampText = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.US)),
            )

            val allowPublicMirror = !prefs.biometricLockEnabled && !prefs.isDemoModeActive
            PdfStatementEngine.generateAnnualTaxStatementPdf(context, data, allowPublicMirror).getOrThrow()
        }
    }

    private fun safeAdd(a: Long, b: Long): Long = com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeAdd(a, b)
    private fun safeSubtract(a: Long, b: Long): Long = com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeSubtract(a, b)
    private fun safeAbs(value: Long): Long = com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeAbs(value)
    private fun safeNegate(x: Long): Long = if (x == Long.MIN_VALUE) Long.MAX_VALUE else -x
}

internal fun findMatchingTaxTag(
    tags: List<String>,
    normalizedSelectedTaxTags: Set<String>,
): String? {
    if (tags.isEmpty()) return null
    val match = if (normalizedSelectedTaxTags.isNotEmpty()) {
        tags.firstOrNull { raw ->
            val clean = raw.trim().removePrefix("#").lowercase(Locale.US)
            clean in normalizedSelectedTaxTags || normalizedSelectedTaxTags.any { sel ->
                clean.startsWith("$sel-") || clean.startsWith("${sel}_") || clean.startsWith("$sel/")
            }
        }
    } else {
        tags.firstOrNull { raw ->
            raw.contains("tax", true) || raw.contains("business", true) ||
                raw.contains("deduct", true) || raw.contains("medical", true) ||
                raw.contains("donation", true) || raw.contains("charity", true)
        }
    }
    return match?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() }?.let { "#$it" }
}

internal fun isCategoryTaxDeductible(catName: String?): Boolean {
    if (catName.isNullOrBlank()) return false
    return catName.contains("tax", true) || catName.contains("donation", true) ||
        catName.contains("charity", true) || catName.contains("medical", true) ||
        catName.contains("insurance", true)
}

internal data class StatementScope(val anchorAccount: AccountEntity, val effectiveAccountIds: Set<Long>)

internal fun resolveStatementScope(targetAccount: AccountEntity, allAccounts: List<AccountEntity>): StatementScope {
    val accountsById = allAccounts.associateBy { it.id }
    val anchorAccount = if (targetAccount.groupType == AccountGroup.DEBIT_CARD && targetAccount.parentAccountId != null) {
        accountsById[targetAccount.parentAccountId] ?: targetAccount
    } else {
        targetAccount
    }
    val linkedDebitCards = allAccounts.filter {
        it.groupType == AccountGroup.DEBIT_CARD && it.parentAccountId == anchorAccount.id
    }
    val effectiveAccountIds: Set<Long> = buildSet {
        add(targetAccount.id)
        add(anchorAccount.id)
        linkedDebitCards.forEach { add(it.id) }
    }
    return StatementScope(anchorAccount, effectiveAccountIds)
}
