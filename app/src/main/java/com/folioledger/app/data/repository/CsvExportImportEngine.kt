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
import com.folioledger.app.data.pdf.PdfStatementEngine
import com.folioledger.app.data.pdf.TaxDeductionPdfItem
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AutoBackupRetention
import com.folioledger.app.domain.model.CsvImportPreset
import com.folioledger.app.domain.util.CompetitorCsvParser
import com.folioledger.app.domain.util.NormalizedCsvTransaction
import java.io.BufferedReader
import com.folioledger.app.domain.model.CategoryNode
import com.folioledger.app.domain.model.CategoryType
import com.folioledger.app.domain.model.DuplicateTransactionSample
import com.folioledger.app.domain.model.ImportCsvPreview
import com.folioledger.app.domain.model.ImportCsvResult
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NumberGroupingSystem
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
import com.folioledger.app.domain.util.CategorySuggestionHelper.suggestIconAndColorForCategoryName
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_CSV_IMPORT_BYTES = 25 * 1024 * 1024L // 25 MB

internal fun escapeCsvField(value: String): String {
    if (value.isEmpty()) return ""
    val trimmedStart = value.trimStart(' ', '\t', '\r', '\n')
    val sanitized = if (
        trimmedStart.startsWith("=") ||
        trimmedStart.startsWith("+") ||
        trimmedStart.startsWith("-") ||
        trimmedStart.startsWith("@") ||
        value.startsWith("\t") ||
        value.startsWith("\r")
    ) {
        "'$value"
    } else {
        value
    }
    val needsQuotes = sanitized.contains(',') ||
            sanitized.contains('"') ||
            sanitized.contains('\n') ||
            sanitized.contains('\r')
    return if (needsQuotes) {
        "\"${sanitized.replace("\"", "\"\"")}\""
    } else {
        sanitized
    }
}

class CsvExportImportEngine(
    private val context: Context,
    private val database: FolioDatabase,
    private val userPreferences: UserPreferencesDataSource
) {

    suspend fun exportTransactionsToCsv(
        destinationUriString: String,
        startEpochDay: Long?,
        endEpochDay: Long?,
        accountId: Long?
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            require(!userPreferences.observeUserPreferences().first().isDemoModeActive) {
                "Exit Demo Sandbox before exporting transactions to CSV."
            }
            StorageAccessUtils.openOutputStreamForLocation(context, destinationUriString).use { rawStream ->
                exportTransactionsToCsvStream(
                    outputStream = rawStream,
                    startEpochDay = startEpochDay,
                    endEpochDay = endEpochDay,
                    accountId = accountId
                )
            }
        }
    }

    suspend fun exportCsvTemplate(
        destinationUriString: String
    ): Result<File?> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            StorageAccessUtils.openOutputStreamForLocation(context, destinationUriString).use { rawStream ->
                exportCsvTemplateStream(rawStream)
            }
            val cleanFileName = destinationUriString
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .ifBlank { "Folio_Import_Template.csv" }
            val internalDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "FolioBackups")
            val internalFile = File(internalDir, cleanFileName)
            if (internalFile.exists()) internalFile else null
        }
    }

    suspend fun exportCsvTemplateStream(
        outputStream: OutputStream
    ): Unit = withContext(Dispatchers.IO) {
        outputStream.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        val writer = BufferedWriter(OutputStreamWriter(outputStream, StandardCharsets.UTF_8))
        writer.write(generateSideBySideCsvTemplateContent())
        writer.flush()
    }

    suspend fun previewTransactionsCsv(
        sourceUriString: String,
        preset: CsvImportPreset = CsvImportPreset.AUTO_DETECT
    ): Result<ImportCsvPreview> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            require(!userPreferences.observeUserPreferences().first().isDemoModeActive) {
                "Exit Demo Sandbox before importing CSV files."
            }
            val isAlreadyCached = try {
                val f = File(sourceUriString)
                f.exists() && f.isFile && f.parentFile?.canonicalPath == context.cacheDir.canonicalPath &&
                    f.name.startsWith("pending_import_") && f.name.endsWith(".csv")
            } catch (_: Exception) {
                false
            }

            val cachedFile = if (isAlreadyCached) {
                File(sourceUriString)
            } else {
                // Clean up any stale pending import files in cacheDir
                context.cacheDir.listFiles { file ->
                    file.isFile && file.name.startsWith("pending_import_") && file.name.endsWith(".csv")
                }?.forEach { it.delete() }

                // Cache incoming file locally to prevent Android Scoped Storage URI permission expiration
                val newFile = File(context.cacheDir, "pending_import_${System.currentTimeMillis()}.csv")
                StorageAccessUtils.openInputStreamForLocation(context, sourceUriString).use { input ->
                    newFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesCopied = 0L
                        var bytes = input.read(buffer)
                        while (bytes >= 0) {
                            bytesCopied += bytes
                            if (bytesCopied > MAX_CSV_IMPORT_BYTES) {
                                newFile.delete()
                                throw IllegalArgumentException("CSV file exceeds maximum allowed size (25 MB).")
                            }
                            output.write(buffer, 0, bytes)
                            bytes = input.read(buffer)
                        }
                    }
                }
                newFile
            }
            cachedFile.inputStream().use { cachedStream ->
                previewCsvStream(cachedStream, cachedFile.absolutePath, preset)
            }
        }
    }

    suspend fun importTransactionsFromCsv(
        sourceUriString: String,
        skipDuplicates: Boolean,
        preset: CsvImportPreset = CsvImportPreset.AUTO_DETECT
    ): Result<ImportCsvResult> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            require(!userPreferences.observeUserPreferences().first().isDemoModeActive) {
                "Exit Demo Sandbox before importing CSV files."
            }
            StorageAccessUtils.openInputStreamForLocation(context, sourceUriString).use { rawStream ->
                importCsvStream(rawStream, skipDuplicates, preset)
            }
        }.also {
            // Clean up cached pending import file if source was in cacheDir
            cleanupCachedCsvPreview(sourceUriString)
        }
    }

    fun cleanupCachedCsvPreview(sourceUriString: String?) {
        if (sourceUriString.isNullOrBlank()) return
        if (sourceUriString.contains("pending_import_")) {
            runCatching {
                val file = File(sourceUriString)
                if (file.exists() && file.isFile && file.parentFile?.canonicalPath == context.cacheDir.canonicalPath) {
                    file.delete()
                }
            }
        }
    }

    suspend fun exportTransactionsToCsvStream(
        outputStream: OutputStream,
        startEpochDay: Long? = null,
        endEpochDay: Long? = null,
        accountId: Long? = null
    ): Int = withContext(Dispatchers.IO) {
        val allAccounts = database.accountDao().getAllAccountsOnce()
        val accountsById = allAccounts.associateBy { it.id }

        val allCategories = database.categoryAndTagDao().getAllCategoriesOnce()
        val categoriesById = allCategories.associateBy { it.id }

        val txDao = database.transactionDao()
        val openingDatesByAccountId = txDao.getAllTransactionsRawOnce()
            .filter { it.type == TransactionType.OPENING_BALANCE }
            .associate { it.accountId to it.ledgerDateEpochDay }

        val filteredTransactions = if (startEpochDay != null && endEpochDay != null) {
            if (accountId != null) {
                txDao.observeTransactionsInRangeForAccount(startEpochDay, endEpochDay, accountId).first()
            } else {
                txDao.observeTransactionsInRange(startEpochDay, endEpochDay).first()
            }
        } else {
            val allTransactions = txDao.getAllTransactionsWithDetailsOnce()
            allTransactions.filter { txWithDetails ->
                val day = txWithDetails.transaction.ledgerDateEpochDay
                val afterStart = startEpochDay == null || day >= startEpochDay
                val beforeEnd = endEpochDay == null || day <= endEpochDay
                val matchesAccount = accountId == null ||
                    txWithDetails.transaction.accountId == accountId ||
                    txWithDetails.transaction.transferToAccountId == accountId
                afterStart && beforeEnd && matchesAccount
            }
        }

        // Write UTF-8 BOM so Microsoft Excel opens the CSV seamlessly with UTF-8 characters
        outputStream.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        val writer = BufferedWriter(OutputStreamWriter(outputStream, StandardCharsets.UTF_8))

        // 1. ACCOUNTS & STARTING BALANCES SECTION
        writer.write("[ACCOUNTS]")
        writer.newLine()
        writer.write("Name,Group,Currency,StartingBalance,AnchorDate,CreditLimit,SettlementDay")
        writer.newLine()
        writer.write("# Hint: (Account Name),(BANK_ACCOUNT|CREDIT_CARD|CASH|SAVINGS|PREPAID_WALLET|LOAN),(Currency ISO),(Starting Balance e.g. 50000.00),(Anchor Date YYYY-MM-DD),(Credit Limit),(Settlement Day 1-31)")
        writer.newLine()
        val exportedAccounts = if (accountId != null) {
            allAccounts.filter { it.id == accountId }
        } else {
            allAccounts
        }
        for (acc in exportedAccounts) {
            val spec = SupportedCurrencies.fromCode(acc.currencyCode)
            val balanceStr = formatDecimalAmountPlain(acc.openingBalanceMinor, spec.fractionDigits)
            val anchorEpochDay = openingDatesByAccountId[acc.id]
                ?: java.time.Instant.ofEpochMilli(acc.createdAtEpochMillis)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()
                    .toEpochDay()
            val anchorDateStr = LocalDate.ofEpochDay(anchorEpochDay).toString()
            val limitStr = acc.creditLimitMinor?.let { formatDecimalAmountPlain(it, spec.fractionDigits) } ?: ""
            val billDayStr = acc.settlementDayOfMonth?.toString() ?: ""
            val row = listOf(
                escapeCsvField(acc.name),
                acc.groupType.name,
                acc.currencyCode,
                balanceStr,
                anchorDateStr,
                limitStr,
                billDayStr
            )
            writer.write(row.joinToString(","))
            writer.newLine()
        }
        writer.newLine()

        // 2. CATEGORIES & SUBCATEGORIES SECTION
        writer.write("[CATEGORIES]")
        writer.newLine()
        writer.write("MainCategory,Subcategory,Type,Icon,Color")
        writer.newLine()
        writer.write("# Hint: (Main Category Name),(Optional Subcategory),(EXPENSE|INCOME),(Icon Name e.g. restaurant),(Hex Color e.g. #FF5722)")
        writer.newLine()
        for (cat in allCategories) {
            val parent = cat.parentId?.let { categoriesById[it] }
            val mainName = parent?.name ?: cat.name
            val subName = if (parent != null) cat.name else ""
            val row = listOf(
                escapeCsvField(mainName),
                escapeCsvField(subName),
                cat.type.name,
                escapeCsvField(cat.iconName),
                escapeCsvField(cat.colorHex)
            )
            writer.write(row.joinToString(","))
            writer.newLine()
        }
        writer.newLine()

        // 3. TRANSACTIONS SECTION
        writer.write("[TRANSACTIONS]")
        writer.newLine()
        writer.write(
            "ID,Date,Type,Amount,Currency,Account,TransferToAccount,Category,Subcategory,TransferFee,PayeeOrDescription,Note,Tags"
        )
        writer.newLine()
        writer.write("# Hint: (Optional ID),(YYYY-MM-DD),(EXPENSE|INCOME|TRANSFER|CC_SETTLEMENT|REFUND),(Amount e.g. 250.50),(Currency),(Source Account),(Destination Account if Transfer),(Leave blank if Transfer),(Subcategory),(Transfer Fee),(Payee/Description),(Note),(Tags semicolon separated)")
        writer.newLine()

        for (item in filteredTransactions) {
            val tx = item.transaction
            val dateIso = LocalDate.ofEpochDay(tx.ledgerDateEpochDay).toString()
            val accountName = accountsById[tx.accountId]?.name ?: ""
            val transferAccountName = tx.transferToAccountId?.let { accountsById[it]?.name } ?: ""

            val isTransfer = tx.type == TransactionType.TRANSFER || tx.type == TransactionType.CC_SETTLEMENT
            val categoryEntity = if (isTransfer) null else tx.categoryId?.let { categoriesById[it] }
            val parentCategoryEntity = categoryEntity?.parentId?.let { categoriesById[it] }
            val mainCategoryName = when {
                tx.isSplit -> "Split Transaction"
                parentCategoryEntity != null -> parentCategoryEntity.name
                categoryEntity != null -> categoryEntity.name
                else -> ""
            }
            val subCategoryName = when {
                tx.isSplit -> item.splits.joinToString(" | ") { split ->
                    val splitCatName = categoriesById[split.categoryId]?.name ?: "Category"
                    val currSpec = SupportedCurrencies.fromCode(tx.currencyCode)
                    "$splitCatName:${formatDecimalAmountPlain(split.amountMinor, currSpec.fractionDigits)}"
                }
                parentCategoryEntity != null -> categoryEntity.name
                else -> ""
            }

            val currencySpec = SupportedCurrencies.fromCode(tx.currencyCode)
            val formattedAmount = formatDecimalAmountPlain(tx.amountMinor, currencySpec.fractionDigits)
            val formattedFee = if (tx.transferFeeMinor > 0L) {
                formatDecimalAmountPlain(tx.transferFeeMinor, currencySpec.fractionDigits)
            } else {
                ""
            }
            val tagsJoined = item.tags.joinToString(";") { it.name }

            val rowColumns = listOf(
                tx.id.toString(),
                dateIso,
                tx.type.name,
                formattedAmount,
                tx.currencyCode,
                escapeCsvField(accountName),
                escapeCsvField(transferAccountName),
                escapeCsvField(mainCategoryName),
                escapeCsvField(subCategoryName),
                formattedFee,
                escapeCsvField(tx.payeeOrDescription),
                escapeCsvField(tx.note),
                escapeCsvField(tagsJoined)
            )
            writer.write(rowColumns.joinToString(","))
            writer.newLine()
        }

        writer.flush()
        filteredTransactions.size
    }

    private suspend fun previewCsvStream(
        inputStream: java.io.InputStream,
        sourceUriString: String,
        preset: CsvImportPreset = CsvImportPreset.AUTO_DETECT
    ): ImportCsvPreview {
        val reader = CompetitorCsvParser.openBomAwareReader(inputStream)
        var firstLine: String? = null
        while (true) {
            val raw = readLogicalCsvRecord(reader) ?: break
            val clean = raw.trim().removePrefix("\uFEFF").removePrefix("ï»¿")
            if (clean.isNotEmpty() && !clean.startsWith("#") && !clean.startsWith("//")) {
                firstLine = clean
                break
            }
        }
        if (firstLine == null) throw IllegalArgumentException("The selected CSV file is empty.")

        val isExplicitFolio = preset == CsvImportPreset.FOLIO_NATIVE
        val hasSectionHeader = detectSectionHeader(firstLine) != null
        val isSideBySide = isSideBySideHeader(parseCsvLine(firstLine))

        val resolvedPreset = if (isExplicitFolio || hasSectionHeader || isSideBySide) {
            CsvImportPreset.FOLIO_NATIVE
        } else if (preset == CsvImportPreset.AUTO_DETECT) {
            val delimiter = CompetitorCsvParser.detectCsvDelimiter(firstLine)
            val headers = CompetitorCsvParser.parseCsvLineWithDelimiter(firstLine, delimiter)
            CompetitorCsvParser.detectPresetFromHeader(firstLine, headers)
        } else {
            preset
        }

        val existingAccounts = database.accountDao().getAllAccountsOnce()
        val existingAccountNames = existingAccounts.map { it.name.trim().lowercase() }.toSet()
        val existingCategories = database.categoryAndTagDao().getAllCategoriesOnce()
        val existingCategoryNames = existingCategories.map { it.name.trim().lowercase() }.toSet()
        val defaultCurrency = runCatching {
            userPreferences.observeUserPreferences().first().primaryCurrencyCode
        }.getOrNull()?.ifBlank { "INR" } ?: "INR"

        val existingTransactions = database.transactionDao().getAllTransactionsRawOnce()
        val accountMap = existingAccounts.associateBy { it.name.trim().lowercase() }

        val preExistingFingerprints = existingTransactions.map { tx ->
            "${tx.ledgerDateEpochDay}|${tx.accountId}|${tx.type.name}|${tx.amountMinor}|${tx.payeeOrDescription.trim().lowercase()}"
        }.toMutableSet()

        if (resolvedPreset.isCompetitor) {
            val catMapById = existingCategories.associateBy { it.id }
            val existingSubCategoryKeys = existingCategories.filter { it.parentId != null }.map { sub ->
                val p = catMapById[sub.parentId]
                "${p?.name?.trim()?.lowercase()}|${sub.name.trim().lowercase()}"
            }.toSet()

            val competitorResult = CompetitorCsvParser.parseCompetitorCsv(reader, firstLine, resolvedPreset, defaultCurrency)
            val newAccounts = mutableSetOf<String>()
            val newCategories = mutableSetOf<String>()
            var validCount = 0
            var duplicateCount = 0
            val duplicateSamples = mutableListOf<DuplicateTransactionSample>()
            val warnings = competitorResult.warnings.toMutableList()

            for (tx in competitorResult.transactions) {
                val accKey = tx.accountName.trim().lowercase()
                val existingAcc = accountMap[accKey]
                if (existingAcc != null && tx.currencyCode != null && !existingAcc.currencyCode.equals(tx.currencyCode, ignoreCase = true)) {
                    warnings.add("Row skipped: Account '${existingAcc.name}' has currency ${existingAcc.currencyCode}, but imported row specifies ${tx.currencyCode}. Multi-currency accounts are not permitted.")
                    continue
                }
                if (existingAcc == null) {
                    newAccounts.add(tx.accountName.trim())
                }

                if (tx.type == TransactionType.TRANSFER) {
                    val destName = tx.transferToAccountName?.trim()
                    if (destName.isNullOrBlank()) {
                        warnings.add("Row skipped: Transfer missing destination account for '${tx.payeeOrDescription.ifBlank { tx.accountName }}'.")
                        continue
                    }
                    val destKey = destName.lowercase()
                    val destAcc = accountMap[destKey]
                    val srcCurrency = existingAcc?.currencyCode ?: tx.currencyCode ?: defaultCurrency
                    val destCurrency = destAcc?.currencyCode ?: tx.transferToCurrencyCode ?: tx.currencyCode ?: defaultCurrency
                    if (!srcCurrency.equals(destCurrency, ignoreCase = true)) {
                        warnings.add("Row skipped: Cross-currency transfer between '$srcCurrency' and '$destCurrency' is not supported.")
                        continue
                    }
                    if (destAcc == null) {
                        newAccounts.add(destName)
                    }
                }

                if (tx.categoryName != null && tx.type != TransactionType.TRANSFER && tx.type != TransactionType.RECONCILIATION) {
                    val parentName = tx.categoryName.trim()
                    val subName = tx.subCategoryName?.trim()?.ifBlank { null }
                    if (subName != null) {
                        val subKey = "${parentName.lowercase()}|${subName.lowercase()}"
                        if (!existingSubCategoryKeys.contains(subKey)) {
                            newCategories.add("$parentName \u203A $subName")
                        }
                    } else {
                        if (!existingCategoryNames.contains(parentName.lowercase())) {
                            newCategories.add(parentName)
                        }
                    }
                }

                val accIdKey = existingAcc?.id?.toString() ?: accKey
                val fp = "${tx.epochDay}|$accIdKey|${tx.type.name}|${tx.amountMinor}|${tx.payeeOrDescription.trim().lowercase()}"

                if (preExistingFingerprints.contains(fp)) {
                    duplicateCount++
                    if (duplicateSamples.size < 20) {
                        val txCurrency = tx.currencyCode ?: existingAcc?.currencyCode ?: defaultCurrency
                        val spec = SupportedCurrencies.fromCode(txCurrency)
                        duplicateSamples.add(
                            DuplicateTransactionSample(
                                date = tx.rawDate.ifBlank { "Unknown Date" },
                                accountName = tx.accountName.ifBlank { "Account" },
                                payee = tx.payeeOrDescription.ifBlank { "(No payee)" },
                                amount = tx.rawAmountForDisplay.ifBlank { formatDecimalAmountPlain(tx.amountMinor, spec.fractionDigits) }
                            )
                        )
                    }
                } else {
                    validCount++
                    preExistingFingerprints.add(fp)
                }
            }

            return ImportCsvPreview(
                sourceUriString = sourceUriString,
                totalRowsRead = competitorResult.totalRowsRead,
                validTransactionCount = validCount,
                duplicateCount = duplicateCount,
                newAccountsToCreate = newAccounts.toList(),
                newCategoriesToCreate = newCategories.toList(),
                warningMessages = warnings,
                accountsWithOpeningBalance = emptyList(),
                duplicateSamples = duplicateSamples,
                detectedPreset = resolvedPreset,
                selectedPreset = preset,
                consolidatedTransferCount = competitorResult.consolidatedTransferCount,
                skippedScheduledCount = competitorResult.skippedScheduledCount
            )
        }

        val existingAnchorKeys = existingTransactions
            .filter { it.type == TransactionType.OPENING_BALANCE }
            .map { "${it.accountId}|${it.ledgerDateEpochDay}" }
            .toMutableSet()

        var currentSection = CsvSectionState.LEGACY
        var colMap: Map<String, Int> = emptyMap()
        var sideBySideColMap: SideBySideColMap? = null

        val detectedFirst = detectSectionHeader(firstLine)
        if (detectedFirst != null) {
            currentSection = detectedFirst
        } else {
            val headerTokens = parseCsvLine(firstLine)
            if (isSideBySideHeader(headerTokens)) {
                currentSection = CsvSectionState.SIDE_BY_SIDE
                sideBySideColMap = parseSideBySideColMap(headerTokens)
            } else {
                colMap = headerTokens.mapIndexed { idx, name ->
                    name.trim().lowercase().replace("_", "").replace(" ", "") to idx
                }.toMap()
            }
        }

        var totalRows = 0
        var validCount = 0
        var duplicateCount = 0
        val duplicateSamples = mutableListOf<DuplicateTransactionSample>()
        val newAccounts = mutableSetOf<String>()
        val newCategories = mutableSetOf<String>()
        val accountsWithOpeningBalance = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val accountCurrencies = mutableMapOf<String, String>()

        var line = readLogicalCsvRecord(reader)
        var lineNum = 1
        while (line != null) {
            lineNum++
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.all { it == ',' || it.isWhitespace() }) {
                val sec = detectSectionHeader(trimmed)
                if (sec != null) {
                    currentSection = sec
                    colMap = emptyMap()
                    sideBySideColMap = null
                    line = readLogicalCsvRecord(reader)
                    continue
                }

                if (trimmed.startsWith("#") || trimmed.startsWith("//")) {
                    line = readLogicalCsvRecord(reader)
                    continue
                }

                if (colMap.isEmpty() && sideBySideColMap == null) {
                    val tokens = parseCsvLine(trimmed)
                    if (currentSection == CsvSectionState.SIDE_BY_SIDE || isSideBySideHeader(tokens)) {
                        currentSection = CsvSectionState.SIDE_BY_SIDE
                        sideBySideColMap = parseSideBySideColMap(tokens)
                    } else {
                        colMap = tokens.mapIndexed { idx, name ->
                            name.trim().lowercase().replace("_", "").replace(" ", "") to idx
                        }.toMap()
                    }
                    line = readLogicalCsvRecord(reader)
                    continue
                }

                totalRows++
                val tokens = parseCsvLine(trimmed)

                when (currentSection) {
                    CsvSectionState.SIDE_BY_SIDE -> {
                        val sbs = sideBySideColMap ?: parseSideBySideColMap(tokens).also { sideBySideColMap = it }
                        val accName = sbs.accNameIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                        if (accName.isNotEmpty()) {
                            val curIdx = sbs.accCurrencyIdx
                            val rawCur = curIdx?.let { tokens.getOrNull(it)?.trim() }?.ifEmpty { null }
                            if (rawCur != null) {
                                accountCurrencies[accName.lowercase()] = rawCur
                            }
                            if (!existingAccountNames.contains(accName.lowercase())) {
                                newAccounts.add(accName)
                            }
                            val balIdx = sbs.accStartingBalIdx
                            val dateIdx = sbs.accAnchorDateIdx
                            val rawBal = balIdx?.let { tokens.getOrNull(it)?.trim() }
                            val rawDate = dateIdx?.let { tokens.getOrNull(it)?.trim() }
                            if (!rawBal.isNullOrEmpty() && rawBal != "0" && rawBal != "0.00") {
                                val anchorStr = if (!rawDate.isNullOrEmpty()) " as of $rawDate" else ""
                                accountsWithOpeningBalance.add("$accName: Starting $rawBal$anchorStr")
                                val anchorEpochDay = if (!rawDate.isNullOrEmpty()) {
                                    parseDateStringToEpochDay(rawDate) ?: LocalDate.now().toEpochDay()
                                } else {
                                    LocalDate.now().toEpochDay()
                                }
                                val existingAcc = accountMap[accName.lowercase()]
                                if (existingAcc != null) {
                                    existingAnchorKeys.add("${existingAcc.id}|$anchorEpochDay")
                                }
                                existingAnchorKeys.add("name:${accName.lowercase()}|$anchorEpochDay")
                            }
                        }

                        val mainName = sbs.catMainIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                        val subName = sbs.catSubIdx?.let { tokens.getOrNull(it)?.trim() }?.ifEmpty { null }
                        if (mainName.isNotEmpty()) {
                            if (!existingCategoryNames.contains(mainName.lowercase())) {
                                newCategories.add(mainName)
                            }
                            if (subName != null) {
                                newCategories.add("$mainName \u203A $subName")
                            }
                        }

                        val rawDate = sbs.txDateIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                        val rawAmount = sbs.txAmountIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                        val accountName = sbs.txAccountIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""

                        if (rawDate.isNotEmpty() || rawAmount.isNotEmpty() || accountName.isNotEmpty()) {
                            val existingAcc = accountMap[accountName.lowercase()]
                            val knownCurrency = existingAcc?.currencyCode ?: accountCurrencies[accountName.lowercase()]
                            val rawCurrency = sbs.txCurrencyIdx?.let { tokens.getOrNull(it)?.trim() }?.ifBlank { null }
                            if (knownCurrency != null && rawCurrency != null && !rawCurrency.equals(knownCurrency, ignoreCase = true)) {
                                if (warnings.size < 10) {
                                    val displayName = existingAcc?.name ?: accountName
                                    warnings.add("Row $lineNum: Account '$displayName' has currency $knownCurrency, but row specifies $rawCurrency.")
                                }
                                line = readLogicalCsvRecord(reader)
                                continue
                            }
                            val epochDay = parseDateStringToEpochDay(rawDate)
                            val currency = resolveCsvRowCurrency(rawCurrency, knownCurrency, defaultCurrency)
                            val spec = SupportedCurrencies.fromCode(currency)
                            val amountMinor = parseAmountDecimalToMinor(rawAmount, spec.fractionDigits)

                            if (epochDay == null) {
                                if (warnings.size < 5) warnings.add("Row $lineNum: Unrecognized date format '$rawDate'")
                            } else if (amountMinor == null || amountMinor == 0L) {
                                if (warnings.size < 5) warnings.add("Row $lineNum: Transaction amount must be non-zero ('$rawAmount')")
                            } else if (accountName.isEmpty()) {
                                if (warnings.size < 5) warnings.add("Row $lineNum: Missing account name")
                            } else {
                                if (!existingAccountNames.contains(accountName.lowercase())) {
                                    newAccounts.add(accountName)
                                }
                                accountCurrencies.putIfAbsent(accountName.lowercase(), currency)

                                val transferAccountIdx = sbs.txTransferAccountIdx
                                val transferAccountName = transferAccountIdx?.let { tokens.getOrNull(it)?.trim() }?.ifEmpty { null }

                                val typeIdx = sbs.txTypeIdx
                                val rawType = typeIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                                val type = when {
                                    rawType.equals("TRANSFER", ignoreCase = true) -> TransactionType.TRANSFER
                                    rawType.equals("CC_SETTLEMENT", ignoreCase = true) || rawType.contains("SETTLEMENT", ignoreCase = true) -> TransactionType.CC_SETTLEMENT
                                    rawType.equals("EXPENSE", ignoreCase = true) -> TransactionType.EXPENSE
                                    rawType.equals("INCOME", ignoreCase = true) -> TransactionType.INCOME
                                    rawType.equals("REFUND", ignoreCase = true) -> TransactionType.REFUND
                                    rawType.equals("OPENING_BALANCE", ignoreCase = true) -> TransactionType.OPENING_BALANCE
                                    rawType.equals("RECONCILIATION", ignoreCase = true) -> TransactionType.RECONCILIATION
                                    transferAccountName != null -> TransactionType.TRANSFER
                                    amountMinor < 0L -> TransactionType.EXPENSE
                                    else -> TransactionType.INCOME
                                }

                                val isTransfer = (type == TransactionType.TRANSFER || type == TransactionType.CC_SETTLEMENT) && transferAccountName != null
                                var isCrossCurrencyMismatch = false
                                if (isTransfer) {
                                    val srcCur = accountMap[accountName.lowercase()]?.currencyCode
                                        ?: accountCurrencies[accountName.lowercase()] ?: currency
                                    val tgtCur = accountMap[transferAccountName!!.lowercase()]?.currencyCode
                                        ?: accountCurrencies[transferAccountName.lowercase()] ?: currency
                                    if (!srcCur.equals(tgtCur, ignoreCase = true)) {
                                        isCrossCurrencyMismatch = true
                                        if (warnings.size < 10) {
                                            warnings.add("Row $lineNum: Cross-currency transfer/settlement from $accountName ($srcCur) to $transferAccountName ($tgtCur) will be skipped.")
                                        }
                                    } else {
                                        if (!existingAccountNames.contains(transferAccountName.lowercase())) {
                                            newAccounts.add(transferAccountName)
                                        }
                                        accountCurrencies.putIfAbsent(transferAccountName.lowercase(), tgtCur)
                                    }
                                }
                                val catIdx = sbs.txCategoryIdx
                                val subCatIdx = sbs.txSubcategoryIdx
                                val categoryName = if (isTransfer) null else catIdx?.let { tokens.getOrNull(it)?.trim() }?.ifEmpty { null }
                                val subCategoryName = if (isTransfer) null else subCatIdx?.let { tokens.getOrNull(it)?.trim() }?.ifEmpty { null }
                                val isSplit = !isTransfer && (categoryName?.equals("Split Transaction", ignoreCase = true) == true)
                                if (isSplit && !subCategoryName.isNullOrBlank() && subCategoryName.contains(":")) {
                                    subCategoryName.split("|").forEach { part ->
                                        val colon = part.lastIndexOf(':')
                                        if (colon != -1) {
                                            val sCat = part.substring(0, colon).trim()
                                            if (sCat.isNotEmpty() && !existingCategoryNames.contains(sCat.lowercase())) {
                                                newCategories.add(sCat)
                                            }
                                        }
                                    }
                                } else if (categoryName != null && !isSplit) {
                                    if (!existingCategoryNames.contains(categoryName.lowercase())) {
                                        newCategories.add(categoryName)
                                    }
                                    if (subCategoryName != null) {
                                        newCategories.add("$categoryName \u203A $subCategoryName")
                                    }
                                }

                                val payeeIdx = sbs.txPayeeIdx
                                val payee = payeeIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""

                                val existingAcc = accountMap[accountName.lowercase()]
                                val accIdKey = if (existingAcc != null) existingAcc.id.toString() else "name:${accountName.lowercase()}"
                                val isAnchorDuplicate = type == TransactionType.OPENING_BALANCE && (
                                    (existingAcc != null && "${existingAcc.id}|$epochDay" in existingAnchorKeys) ||
                                    "$accIdKey|$epochDay" in existingAnchorKeys
                                )
                                val fingerprint = "${epochDay}|$accIdKey|${type.name}|${MoneyFormatterAndCalculator.safeAbs(amountMinor)}|${payee.lowercase()}"

                                if (isCrossCurrencyMismatch) {
                                    // Cross-currency transfer/settlement will be skipped on import; do not count as valid or duplicate
                                } else if (isAnchorDuplicate || preExistingFingerprints.contains(fingerprint)) {
                                    duplicateCount++
                                    if (duplicateSamples.size < 20) {
                                        duplicateSamples.add(
                                            DuplicateTransactionSample(
                                                date = rawDate.ifBlank { "Unknown Date" },
                                                accountName = accountName.ifBlank { "Account" },
                                                payee = payee.ifBlank { "(No payee)" },
                                                amount = rawAmount.ifBlank { "0" }
                                            )
                                        )
                                    }
                                } else {
                                    validCount++
                                    preExistingFingerprints.add(fingerprint)
                                    if (type == TransactionType.OPENING_BALANCE) {
                                        existingAnchorKeys.add("$accIdKey|$epochDay")
                                        if (existingAcc != null) existingAnchorKeys.add("${existingAcc.id}|$epochDay")
                                    }
                                }
                            }
                        }
                    }
                    CsvSectionState.ACCOUNTS -> {
                        val nameIdx = colMap["name"] ?: colMap["account"] ?: colMap["accountname"] ?: 0
                        val name = tokens.getOrNull(nameIdx)?.trim() ?: ""
                        if (name.isNotEmpty()) {
                            val curIdx = colMap["currency"] ?: colMap["currencycode"]
                            val rawCur = curIdx?.let { tokens.getOrNull(it)?.trim() }?.ifEmpty { null }
                            if (rawCur != null) {
                                accountCurrencies[name.lowercase()] = rawCur
                            }
                            if (!existingAccountNames.contains(name.lowercase())) {
                                newAccounts.add(name)
                            }
                            val balIdx = colMap["startingbalance"] ?: colMap["openingbalance"] ?: colMap["balance"]
                            val dateIdx = colMap["anchordate"] ?: colMap["openingdate"] ?: colMap["date"]
                            val rawBal = balIdx?.let { tokens.getOrNull(it)?.trim() }
                            val rawDate = dateIdx?.let { tokens.getOrNull(it)?.trim() }
                            if (!rawBal.isNullOrEmpty() && rawBal != "0" && rawBal != "0.00") {
                                val anchorStr = if (!rawDate.isNullOrEmpty()) " as of $rawDate" else ""
                                accountsWithOpeningBalance.add("$name: Starting $rawBal$anchorStr")
                                val anchorEpochDay = if (!rawDate.isNullOrEmpty()) {
                                    parseDateStringToEpochDay(rawDate) ?: LocalDate.now().toEpochDay()
                                } else {
                                    LocalDate.now().toEpochDay()
                                }
                                val existingAcc = accountMap[name.lowercase()]
                                if (existingAcc != null) {
                                    existingAnchorKeys.add("${existingAcc.id}|$anchorEpochDay")
                                }
                                existingAnchorKeys.add("name:${name.lowercase()}|$anchorEpochDay")
                            }
                        }
                    }
                    CsvSectionState.CATEGORIES -> {
                        val mainIdx = colMap["maincategory"] ?: colMap["category"] ?: colMap["name"] ?: 0
                        val subIdx = colMap["subcategory"] ?: colMap["subname"] ?: colMap["sub"]
                        val mainName = tokens.getOrNull(mainIdx)?.trim() ?: ""
                        val subName = subIdx?.let { tokens.getOrNull(it)?.trim() }?.ifEmpty { null }
                        if (mainName.isNotEmpty()) {
                            if (!existingCategoryNames.contains(mainName.lowercase())) {
                                newCategories.add(mainName)
                            }
                            if (subName != null) {
                                newCategories.add("$mainName \u203A $subName")
                            }
                        }
                    }
                    CsvSectionState.TRANSACTIONS,
                    CsvSectionState.LEGACY -> {
                        val dateIdx = colMap["date"] ?: colMap["txdate"] ?: colMap["transactiondate"] ?: colMap["txndate"] ?: colMap["postingdate"] ?: colMap["valuedate"]
                        val amountIdx = colMap["amount"] ?: colMap["amountformatted"] ?: colMap["amountminor"] ?: colMap["txnamount"] ?: colMap["transactionamount"] ?: colMap["total"] ?: colMap["netamount"] ?: colMap["value"]
                        val accountIdx = colMap["account"] ?: colMap["accountname"] ?: colMap["fromaccount"] ?: colMap["sourceaccount"] ?: colMap["bank"] ?: colMap["wallet"]

                        if (dateIdx == null || amountIdx == null || accountIdx == null) {
                            val missingCols = mutableListOf<String>()
                            if (dateIdx == null) missingCols.add("Date")
                            if (amountIdx == null) missingCols.add("Amount")
                            if (accountIdx == null) missingCols.add("Account")
                            if (warnings.size < 5) warnings.add("Row $lineNum: Missing column(s) in header: ${missingCols.joinToString(", ")}.")
                        } else {
                            val rawDate = tokens.getOrNull(dateIdx) ?: ""
                            val epochDay = parseDateStringToEpochDay(rawDate)
                            val accountName = tokens.getOrNull(accountIdx)?.trim() ?: ""
                            val existingAcc = accountMap[accountName.lowercase()]
                            val knownCurrency = existingAcc?.currencyCode ?: accountCurrencies[accountName.lowercase()]
                            val currencyIdx = colMap["currency"]
                            val rawCurrency = currencyIdx?.let { tokens.getOrNull(it)?.trim() }?.ifBlank { null }
                            if (knownCurrency != null && rawCurrency != null && !rawCurrency.equals(knownCurrency, ignoreCase = true)) {
                                if (warnings.size < 10) {
                                    val displayName = existingAcc?.name ?: accountName
                                    warnings.add("Row $lineNum: Account '$displayName' has currency $knownCurrency, but row specifies $rawCurrency.")
                                }
                                line = readLogicalCsvRecord(reader)
                                continue
                            }
                            val currency = resolveCsvRowCurrency(rawCurrency, knownCurrency, defaultCurrency)
                            val spec = SupportedCurrencies.fromCode(currency)
                            val rawAmount = tokens.getOrNull(amountIdx) ?: ""
                            val amountMinor = parseAmountDecimalToMinor(rawAmount, spec.fractionDigits)

                            if (epochDay == null) {
                                if (warnings.size < 5) warnings.add("Row $lineNum: Unrecognized date format '$rawDate'")
                            } else if (amountMinor == null || amountMinor == 0L) {
                                if (warnings.size < 5) warnings.add("Row $lineNum: Transaction amount must be non-zero ('$rawAmount')")
                            } else if (accountName.isEmpty()) {
                                if (warnings.size < 5) warnings.add("Row $lineNum: Missing account name")
                            } else {
                                if (!existingAccountNames.contains(accountName.lowercase())) {
                                    newAccounts.add(accountName)
                                }
                                accountCurrencies.putIfAbsent(accountName.lowercase(), currency)

                                val transferAccountIdx = colMap["transfertoaccount"] ?: colMap["transferto"]
                                val transferAccountName = transferAccountIdx?.let { tokens.getOrNull(it)?.trim() }?.ifEmpty { null }

                                val typeIdx = colMap["type"]
                                val rawType = typeIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                                val type = when {
                                    rawType.equals("TRANSFER", ignoreCase = true) -> TransactionType.TRANSFER
                                    rawType.equals("CC_SETTLEMENT", ignoreCase = true) || rawType.contains("SETTLEMENT", ignoreCase = true) -> TransactionType.CC_SETTLEMENT
                                    rawType.equals("EXPENSE", ignoreCase = true) -> TransactionType.EXPENSE
                                    rawType.equals("INCOME", ignoreCase = true) -> TransactionType.INCOME
                                    rawType.equals("REFUND", ignoreCase = true) -> TransactionType.REFUND
                                    rawType.equals("OPENING_BALANCE", ignoreCase = true) -> TransactionType.OPENING_BALANCE
                                    rawType.equals("RECONCILIATION", ignoreCase = true) -> TransactionType.RECONCILIATION
                                    transferAccountName != null -> TransactionType.TRANSFER
                                    amountMinor < 0L -> TransactionType.EXPENSE
                                    else -> TransactionType.INCOME
                                }

                                val isTransfer = (type == TransactionType.TRANSFER || type == TransactionType.CC_SETTLEMENT) && transferAccountName != null
                                var isCrossCurrencyMismatch = false
                                if (isTransfer) {
                                    val srcCur = accountMap[accountName.lowercase()]?.currencyCode
                                        ?: accountCurrencies[accountName.lowercase()] ?: currency
                                    val tgtCur = accountMap[transferAccountName!!.lowercase()]?.currencyCode
                                        ?: accountCurrencies[transferAccountName.lowercase()] ?: currency
                                    if (!srcCur.equals(tgtCur, ignoreCase = true)) {
                                        isCrossCurrencyMismatch = true
                                        if (warnings.size < 10) {
                                            warnings.add("Row $lineNum: Cross-currency transfer/settlement from $accountName ($srcCur) to $transferAccountName ($tgtCur) will be skipped.")
                                        }
                                    } else {
                                        if (!existingAccountNames.contains(transferAccountName.lowercase())) {
                                            newAccounts.add(transferAccountName)
                                        }
                                        accountCurrencies.putIfAbsent(transferAccountName.lowercase(), tgtCur)
                                    }
                                }
                                val catIdx = colMap["category"]
                                val subCatIdx = colMap["subcategory"]
                                val categoryName = if (isTransfer) null else catIdx?.let { tokens.getOrNull(it)?.trim() }?.ifEmpty { null }
                                val subCategoryName = if (isTransfer) null else subCatIdx?.let { tokens.getOrNull(it)?.trim() }?.ifEmpty { null }
                                val isSplit = !isTransfer && (categoryName?.equals("Split Transaction", ignoreCase = true) == true)
                                if (isSplit && !subCategoryName.isNullOrBlank() && subCategoryName.contains(":")) {
                                    subCategoryName.split("|").forEach { part ->
                                        val colon = part.lastIndexOf(':')
                                        if (colon != -1) {
                                            val sCat = part.substring(0, colon).trim()
                                            if (sCat.isNotEmpty() && !existingCategoryNames.contains(sCat.lowercase())) {
                                                newCategories.add(sCat)
                                            }
                                        }
                                    }
                                } else if (categoryName != null && !isSplit) {
                                    if (!existingCategoryNames.contains(categoryName.lowercase())) {
                                        newCategories.add(categoryName)
                                    }
                                    if (subCategoryName != null) {
                                        newCategories.add("$categoryName \u203A $subCategoryName")
                                    }
                                }

                                val payeeIdx = colMap["payeeordescription"] ?: colMap["payee"] ?: colMap["description"]
                                val payee = payeeIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""

                                val existingAcc = accountMap[accountName.lowercase()]
                                val accIdKey = if (existingAcc != null) existingAcc.id.toString() else "name:${accountName.lowercase()}"
                                val isAnchorDuplicate = type == TransactionType.OPENING_BALANCE && (
                                    (existingAcc != null && "${existingAcc.id}|$epochDay" in existingAnchorKeys) ||
                                    "$accIdKey|$epochDay" in existingAnchorKeys
                                )
                                val fingerprint = "${epochDay}|$accIdKey|${type.name}|${MoneyFormatterAndCalculator.safeAbs(amountMinor)}|${payee.lowercase()}"

                                if (isCrossCurrencyMismatch) {
                                    // Cross-currency transfer/settlement will be skipped on import; do not count as valid or duplicate
                                } else if (isAnchorDuplicate || preExistingFingerprints.contains(fingerprint)) {
                                    duplicateCount++
                                    if (duplicateSamples.size < 20) {
                                        duplicateSamples.add(
                                            DuplicateTransactionSample(
                                                date = rawDate.ifBlank { "Unknown Date" },
                                                accountName = accountName.ifBlank { "Account" },
                                                payee = payee.ifBlank { "(No payee)" },
                                                amount = rawAmount.ifBlank { "0" }
                                            )
                                        )
                                    }
                                } else {
                                    validCount++
                                    preExistingFingerprints.add(fingerprint)
                                    if (type == TransactionType.OPENING_BALANCE) {
                                        existingAnchorKeys.add("$accIdKey|$epochDay")
                                        if (existingAcc != null) existingAnchorKeys.add("${existingAcc.id}|$epochDay")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            line = readLogicalCsvRecord(reader)
        }

        return ImportCsvPreview(
            sourceUriString = sourceUriString,
            totalRowsRead = totalRows,
            validTransactionCount = validCount,
            duplicateCount = duplicateCount,
            newAccountsToCreate = newAccounts.toList(),
            newCategoriesToCreate = newCategories.toList(),
            warningMessages = warnings,
            accountsWithOpeningBalance = accountsWithOpeningBalance,
            duplicateSamples = duplicateSamples,
            detectedPreset = CsvImportPreset.FOLIO_NATIVE,
            selectedPreset = preset,
            consolidatedTransferCount = 0,
            skippedScheduledCount = 0
        )
    }

    private suspend fun importCompetitorCsvInternal(
        reader: BufferedReader,
        firstLine: String,
        resolvedPreset: CsvImportPreset,
        defaultCurrency: String,
        skipDuplicates: Boolean,
        accountNameToEntity: MutableMap<String, AccountEntity>,
        preExistingFingerprints: MutableSet<String>,
        newCategoriesCreated: MutableList<String>,
        getOrCreateAccount: suspend (String, String, Long) -> AccountEntity,
        getOrCreateCategory: suspend (String, String?, CategoryType) -> CategoryEntity?,
        getOrCreateTag: suspend (String) -> TagEntity?
    ): ImportCsvResult {
        val txDao = database.transactionDao()
        val competitorResult = CompetitorCsvParser.parseCompetitorCsv(reader, firstLine, resolvedPreset, defaultCurrency)
        val errorMessages = competitorResult.warnings.toMutableList()
        var compImportedCount = 0
        var compSkippedCount = 0
        val compNewAccountsCreated = mutableListOf<String>()

        for (tx in competitorResult.transactions) {
            val accKey = tx.accountName.trim().lowercase()
            val existingAcc = accountNameToEntity[accKey]
            if (existingAcc != null && tx.currencyCode != null && !existingAcc.currencyCode.equals(tx.currencyCode, ignoreCase = true)) {
                errorMessages.add("Row skipped: Account '${existingAcc.name}' has currency ${existingAcc.currencyCode}, but imported row specifies ${tx.currencyCode}. Multi-currency accounts are not permitted.")
                compSkippedCount++
                continue
            }

            if (tx.type == TransactionType.TRANSFER) {
                val destName = tx.transferToAccountName?.trim()
                if (destName.isNullOrBlank()) {
                    errorMessages.add("Row skipped: Transfer missing destination account for '${tx.payeeOrDescription.ifBlank { tx.accountName }}'.")
                    compSkippedCount++
                    continue
                }
                val destKey = destName.lowercase()
                val existingDest = accountNameToEntity[destKey]
                val srcCurrency = existingAcc?.currencyCode ?: tx.currencyCode ?: defaultCurrency
                val destCurrency = existingDest?.currencyCode ?: tx.transferToCurrencyCode ?: tx.currencyCode ?: defaultCurrency
                if (!srcCurrency.equals(destCurrency, ignoreCase = true)) {
                    errorMessages.add("Row skipped: Cross-currency transfer between '$srcCurrency' and '$destCurrency' is not supported.")
                    compSkippedCount++
                    continue
                }
            }

            val isNewAcc = existingAcc == null
            val account = getOrCreateAccount(tx.accountName, tx.currencyCode ?: defaultCurrency, tx.epochDay)
            if (isNewAcc) {
                compNewAccountsCreated.add(account.name)
            }

            val fp = "${tx.epochDay}|${account.id}|${tx.type.name}|${tx.amountMinor}|${tx.payeeOrDescription.trim().lowercase()}"
            if (preExistingFingerprints.contains(fp)) {
                if (skipDuplicates) {
                    compSkippedCount++
                    continue
                }
            }

            val destAccountId = if (tx.type == TransactionType.TRANSFER && tx.transferToAccountName != null) {
                val destKey = tx.transferToAccountName.trim().lowercase()
                val isNewDest = !accountNameToEntity.containsKey(destKey)
                val destAcc = getOrCreateAccount(tx.transferToAccountName, tx.transferToCurrencyCode ?: tx.currencyCode ?: defaultCurrency, tx.epochDay)
                if (isNewDest) {
                    compNewAccountsCreated.add(destAcc.name)
                }
                destAcc.id
            } else if (tx.type == TransactionType.RECONCILIATION && tx.isOutflowReconciliation) {
                account.id
            } else {
                null
            }

            val categoryType = if (tx.type == TransactionType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
            val categoryEntity = if (tx.type != TransactionType.TRANSFER && tx.type != TransactionType.RECONCILIATION && tx.categoryName != null) {
                getOrCreateCategory(tx.categoryName, tx.subCategoryName, categoryType)
            } else null

            val createdEpochMillis = tx.epochDay * 86_400_000L + 12 * 3600 * 1000L
            val entity = TransactionEntity(
                id = 0L,
                type = tx.type,
                timestampEpochMillis = createdEpochMillis,
                ledgerDateEpochDay = tx.epochDay,
                amountMinor = tx.amountMinor,
                currencyCode = account.currencyCode,
                accountId = account.id,
                transferToAccountId = destAccountId,
                transferFeeMinor = tx.transferFeeMinor,
                categoryId = categoryEntity?.id,
                isSplit = false,
                payeeOrDescription = tx.payeeOrDescription,
                note = tx.note
            )

            val newTxId = txDao.insertNewTransactionRaw(entity)

            if (tx.tags.isNotEmpty()) {
                val refs = tx.tags.mapNotNull { tagName ->
                    val tag = getOrCreateTag(tagName)
                    tag?.let { TransactionTagCrossRef(transactionId = newTxId, tagId = it.id) }
                }
                if (refs.isNotEmpty()) {
                    txDao.insertTagCrossRefs(refs)
                }
            }

            preExistingFingerprints.add(fp)
            SessionChangeTracker.markDirty()
            compImportedCount++
        }

        return ImportCsvResult(
            totalRowsRead = competitorResult.totalRowsRead,
            importedCount = compImportedCount,
            updatedCount = 0,
            skippedCount = compSkippedCount,
            newAccountsCreated = compNewAccountsCreated.distinct(),
            newCategoriesCreated = newCategoriesCreated.distinct(),
            errorMessages = errorMessages,
            accountsConfiguredCount = 0
        )
    }

    private suspend fun importCsvStream(
        inputStream: java.io.InputStream,
        skipDuplicates: Boolean,
        preset: CsvImportPreset = CsvImportPreset.AUTO_DETECT
    ): ImportCsvResult = database.withTransaction {
        val reader = CompetitorCsvParser.openBomAwareReader(inputStream)
        var firstLine: String? = null
        while (true) {
            val raw = readLogicalCsvRecord(reader) ?: break
            val clean = raw.trim().removePrefix("\uFEFF").removePrefix("ï»¿")
            if (clean.isNotEmpty() && !clean.startsWith("#") && !clean.startsWith("//")) {
                firstLine = clean
                break
            }
        }
        if (firstLine == null) throw IllegalArgumentException("The selected CSV file is empty.")

        val isExplicitFolio = preset == CsvImportPreset.FOLIO_NATIVE
        val hasSectionHeader = detectSectionHeader(firstLine) != null
        val isSideBySide = isSideBySideHeader(parseCsvLine(firstLine))

        val resolvedPreset = if (isExplicitFolio || hasSectionHeader || isSideBySide) {
            CsvImportPreset.FOLIO_NATIVE
        } else if (preset == CsvImportPreset.AUTO_DETECT) {
            val delimiter = CompetitorCsvParser.detectCsvDelimiter(firstLine)
            val headers = CompetitorCsvParser.parseCsvLineWithDelimiter(firstLine, delimiter)
            CompetitorCsvParser.detectPresetFromHeader(firstLine, headers)
        } else {
            preset
        }

        val accountDao = database.accountDao()
        val catTagDao = database.categoryAndTagDao()
        val txDao = database.transactionDao()

        val defaultCurrency = runCatching {
            userPreferences.observeUserPreferences().first().primaryCurrencyCode
        }.getOrNull()?.ifBlank { "INR" } ?: "INR"

        val accountNameToEntity = accountDao.getAllAccountsOnce()
            .associateBy { it.name.trim().lowercase() }
            .toMutableMap()

        val allCategories = catTagDao.getAllCategoriesOnce().toMutableList()
        val categoryNameToEntity = allCategories
            .associateBy { "${it.name.trim().lowercase()}|${it.type.name}" }
            .toMutableMap()

        val tagNameToEntity = catTagDao.getAllTagsOnce()
            .associateBy { it.name.trim().lowercase() }
            .toMutableMap()

        val rawTxs = txDao.getAllTransactionsRawOnce()
        val existingTxById = rawTxs.associateBy { it.id }

        val preExistingFingerprints = rawTxs.map { tx ->
            "${tx.ledgerDateEpochDay}|${tx.accountId}|${tx.type.name}|${tx.amountMinor}|${tx.payeeOrDescription.trim().lowercase()}"
        }.toMutableSet()

        val existingAnchorKeys = rawTxs
            .filter { it.type == TransactionType.OPENING_BALANCE }
            .map { "${it.accountId}|${it.ledgerDateEpochDay}" }
            .toMutableSet()

        suspend fun getOrCreateAccount(
            name: String,
            currency: String,
            groupOverride: AccountGroup? = null,
            limitMinor: Long? = null,
            billDay: Int? = null,
            anchorDateEpochDay: Long? = null
        ): AccountEntity {
            val key = name.trim().lowercase()
            accountNameToEntity[key]?.let { return it }

            val group = groupOverride ?: parseAccountGroupFromTokens(null, name)
            val defaultColor = when (group) {
                AccountGroup.CREDIT_CARD -> "#E11D48"
                AccountGroup.CASH -> "#D97706"
                AccountGroup.BANK_ACCOUNT -> "#059669"
                AccountGroup.LOAN -> "#BE123C"
                else -> "#475569"
            }
            val defaultIcon = when (group) {
                AccountGroup.CREDIT_CARD -> "credit_card"
                AccountGroup.CASH -> "account_balance_wallet"
                AccountGroup.BANK_ACCOUNT -> "account_balance"
                AccountGroup.LOAN -> "trending_down"
                else -> "payments"
            }

            val anchorMillis = anchorDateEpochDay?.let { it * 86400000L + 12 * 3600 * 1000L } ?: System.currentTimeMillis()
            val settlementDay = if (group == AccountGroup.CREDIT_CARD) {
                billDay ?: CreditCardCycleCalculator.DEFAULT_STATEMENT_DAY
            } else {
                billDay
            }
            val dueDay = if (group == AccountGroup.CREDIT_CARD) {
                billDay?.let { ((it + 20 - 1) % 28) + 1 } ?: CreditCardCycleCalculator.DEFAULT_PAYMENT_DUE_DAY
            } else {
                null
            }
            val newEntity = AccountEntity(
                id = 0L,
                name = name.trim(),
                groupType = group,
                currencyCode = currency.ifBlank { "INR" },
                openingBalanceMinor = 0L,
                creditLimitMinor = limitMinor,
                settlementDayOfMonth = settlementDay,
                paymentDueDayOfMonth = dueDay,
                colorHex = defaultColor,
                iconName = defaultIcon,
                createdAtEpochMillis = anchorMillis
            )
            val newId = accountDao.insertNewAccountRaw(newEntity)
            val created = newEntity.copy(id = newId)
            accountNameToEntity[key] = created
            return created
        }

        val newCategoriesCreated = mutableListOf<String>()

        suspend fun getOrCreateCategory(
            name: String?,
            subName: String?,
            type: CategoryType,
            iconOverride: String? = null,
            colorOverride: String? = null
        ): CategoryEntity? {
            val mainTrimmed = name?.trim()?.ifEmpty { null } ?: return null
            val subTrimmed = subName?.trim()?.ifEmpty { null }

            val mainKey = "${mainTrimmed.lowercase()}|${type.name}"
            var parent = categoryNameToEntity[mainKey]
            if (parent == null) {
                val suggestion = suggestIconAndColorForCategoryName(mainTrimmed, type)
                val newMain = CategoryEntity(
                    id = 0L,
                    name = mainTrimmed,
                    type = type,
                    parentId = null,
                    iconName = iconOverride ?: suggestion?.first ?: if (type == CategoryType.EXPENSE) "shopping_bag" else "payments",
                    colorHex = colorOverride ?: suggestion?.second ?: if (type == CategoryType.EXPENSE) "#E11D48" else "#059669"
                )
                val newId = catTagDao.insertNewCategoryRaw(newMain)
                parent = newMain.copy(id = newId)
                categoryNameToEntity[mainKey] = parent
                allCategories.add(parent)
                newCategoriesCreated.add(mainTrimmed)
            }

            if (subTrimmed == null) return parent

            val existingSub = allCategories.firstOrNull {
                it.parentId == parent.id && it.name.equals(subTrimmed, ignoreCase = true)
            }
            if (existingSub != null) return existingSub

            val subSuggestion = suggestIconAndColorForCategoryName(subTrimmed, type)
            val newSub = CategoryEntity(
                id = 0L,
                name = subTrimmed,
                type = type,
                parentId = parent.id,
                iconName = iconOverride ?: subSuggestion?.first ?: parent.iconName,
                colorHex = colorOverride ?: subSuggestion?.second ?: parent.colorHex
            )
            val subId = catTagDao.insertNewCategoryRaw(newSub)
            val createdSub = newSub.copy(id = subId)
            allCategories.add(createdSub)
            newCategoriesCreated.add("$mainTrimmed \u203A $subTrimmed")
            return createdSub
        }

        suspend fun getOrCreateTag(name: String): TagEntity? {
            val trimmedName = name.trim().removePrefix("#").trim()
            if (trimmedName.isEmpty()) return null
            val key = trimmedName.lowercase()
            tagNameToEntity[key]?.let { return it }
            val existing = catTagDao.getTagByNameOnce(trimmedName)
            if (existing != null) {
                tagNameToEntity[key] = existing
                return existing
            }
            val newTag = TagEntity(id = 0L, name = trimmedName)
            val newId = catTagDao.insertTag(newTag)
            val created = newTag.copy(id = newId)
            tagNameToEntity[key] = created
            return created
        }

        if (resolvedPreset.isCompetitor) {
            return@withTransaction importCompetitorCsvInternal(
                reader = reader,
                firstLine = firstLine,
                resolvedPreset = resolvedPreset,
                defaultCurrency = defaultCurrency,
                skipDuplicates = skipDuplicates,
                accountNameToEntity = accountNameToEntity,
                preExistingFingerprints = preExistingFingerprints,
                newCategoriesCreated = newCategoriesCreated,
                getOrCreateAccount = { name, cur, epoch -> getOrCreateAccount(name, cur, anchorDateEpochDay = epoch) },
                getOrCreateCategory = { name, sub, type -> getOrCreateCategory(name, sub, type) },
                getOrCreateTag = { name -> getOrCreateTag(name) }
            )
        }

        var totalRows = 0
        var importedCount = 0
        var updatedCount = 0
        var skippedCount = 0
        var accountsConfiguredCount = 0
        val newAccountsCreated = mutableListOf<String>()
        val errorMessages = mutableListOf<String>()

        suspend fun parseAndBuildSplits(
            rawSplitsStr: String,
            txId: Long,
            categoryType: CategoryType,
            fractionDigits: Int,
            expectedTotalMinor: Long? = null
        ): List<TransactionSplitEntity> {
            if (rawSplitsStr.isBlank()) return emptyList()
            val parts = rawSplitsStr.split("|")
            val rawTokens = mutableListOf<Pair<String, String>>()
            for (part in parts) {
                val trimmed = part.trim()
                if (trimmed.isEmpty()) continue
                val colonIdx = trimmed.lastIndexOf(':')
                if (colonIdx == -1) continue
                val catName = trimmed.substring(0, colonIdx).trim()
                val amtStr = trimmed.substring(colonIdx + 1).trim()
                rawTokens.add(catName to amtStr)
            }
            if (rawTokens.isEmpty()) return emptyList()

            // Strategy 1: Decimal interpretation (e.g. "Food:100.00" -> 10000 minor, or "Food:100" -> 10000 minor via parseAmountDecimalToMinor)
            val decimalSplits = rawTokens.map { (catName, amtStr) ->
                val amtMinor = parseAmountDecimalToMinor(amtStr, fractionDigits) ?: 0L
                catName to MoneyFormatterAndCalculator.safeAbs(amtMinor)
            }
            val decimalSum = decimalSplits.fold(0L) { acc, p -> MoneyFormatterAndCalculator.safeAdd(acc, p.second) }

            // Strategy 2: Raw minor integer interpretation (e.g. legacy export "Food:10000" -> 10000 minor)
            val rawMinorSplits = rawTokens.map { (catName, amtStr) ->
                val amtMinor = amtStr.toLongOrNull() ?: (parseAmountDecimalToMinor(amtStr, fractionDigits) ?: 0L)
                catName to MoneyFormatterAndCalculator.safeAbs(amtMinor)
            }
            val rawMinorSum = rawMinorSplits.fold(0L) { acc, p -> MoneyFormatterAndCalculator.safeAdd(acc, p.second) }

            val chosenSplits = when {
                expectedTotalMinor != null && decimalSum == expectedTotalMinor -> decimalSplits
                expectedTotalMinor != null && rawMinorSum == expectedTotalMinor -> rawMinorSplits
                rawTokens.any { it.second.contains(".") || it.second.contains(",") } -> decimalSplits
                else -> rawMinorSplits
            }

            val finalSum = chosenSplits.fold(0L) { acc, p -> MoneyFormatterAndCalculator.safeAdd(acc, p.second) }

            // Split-Sum Invariant: If expected total is provided, splits MUST sum to expected total and have at least 2 items
            if (expectedTotalMinor != null && (chosenSplits.size < 2 || finalSum != expectedTotalMinor)) {
                return emptyList()
            }

            return chosenSplits.map { (catName, amountMinor) ->
                val catEntity = if (catName.isNotEmpty()) {
                    val parsedCat = if (catName.contains("\u203A") || catName.contains("›") || catName.contains("â€º")) {
                        val sep = if (catName.contains("\u203A")) "\u203A" else if (catName.contains("›")) "›" else "â€º"
                        val p = catName.split(sep)
                        Pair(p[0].trim(), p.getOrNull(1)?.trim())
                    } else if (catName.contains(">")) {
                        val p = catName.split(">")
                        Pair(p[0].trim(), p.getOrNull(1)?.trim())
                    } else {
                        Pair(catName, null)
                    }
                    if (parsedCat.first.isNotEmpty()) {
                        getOrCreateCategory(parsedCat.first, parsedCat.second, categoryType)
                    } else null
                } else null

                TransactionSplitEntity(
                    id = 0L,
                    transactionId = txId,
                    categoryId = catEntity?.id,
                    amountMinor = amountMinor,
                    note = ""
                )
            }
        }

        var currentSection = CsvSectionState.LEGACY
        var colMap: Map<String, Int> = emptyMap()
        var sideBySideColMap: SideBySideColMap? = null

        val detectedFirst = detectSectionHeader(firstLine)
        if (detectedFirst != null) {
            currentSection = detectedFirst
        } else {
            val headerTokens = parseCsvLine(firstLine)
            if (isSideBySideHeader(headerTokens)) {
                currentSection = CsvSectionState.SIDE_BY_SIDE
                sideBySideColMap = parseSideBySideColMap(headerTokens)
            } else {
                colMap = headerTokens.mapIndexed { idx, name ->
                    name.trim().lowercase().replace("_", "").replace(" ", "") to idx
                }.toMap()
            }
        }

        var line = readLogicalCsvRecord(reader)
        var lineNum = 1
        while (line != null) {
            lineNum++
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.all { it == ',' || it.isWhitespace() }) {
                val sec = detectSectionHeader(trimmed)
                if (sec != null) {
                    currentSection = sec
                    colMap = emptyMap()
                    sideBySideColMap = null
                    line = readLogicalCsvRecord(reader)
                    continue
                }

                if (trimmed.startsWith("#") || trimmed.startsWith("//")) {
                    line = readLogicalCsvRecord(reader)
                    continue
                }

                if (colMap.isEmpty() && sideBySideColMap == null) {
                    val tokens = parseCsvLine(trimmed)
                    if (currentSection == CsvSectionState.SIDE_BY_SIDE || isSideBySideHeader(tokens)) {
                        currentSection = CsvSectionState.SIDE_BY_SIDE
                        sideBySideColMap = parseSideBySideColMap(tokens)
                    } else {
                        colMap = tokens.mapIndexed { idx, name ->
                            name.trim().lowercase().replace("_", "").replace(" ", "") to idx
                        }.toMap()
                    }
                    line = readLogicalCsvRecord(reader)
                    continue
                }

                totalRows++
                val tokens = parseCsvLine(trimmed)

                when (currentSection) {
                    CsvSectionState.SIDE_BY_SIDE -> {
                        val sbs = sideBySideColMap ?: parseSideBySideColMap(tokens).also { sideBySideColMap = it }
                        val accName = sbs.accNameIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                        if (accName.isNotEmpty()) {
                            val existingAcc = accountNameToEntity[accName.lowercase()]
                            val rawCurrency = sbs.accCurrencyIdx?.let { tokens.getOrNull(it)?.trim() }?.ifBlank { null }
                            val isCurrencyMismatch = existingAcc != null && rawCurrency != null && !rawCurrency.equals(existingAcc.currencyCode, ignoreCase = true)
                            if (isCurrencyMismatch) {
                                if (errorMessages.size < 10) {
                                    errorMessages.add("Row $lineNum: Account '${existingAcc!!.name}' has currency ${existingAcc.currencyCode}, but account section specifies $rawCurrency.")
                                }
                            }

                            val rawGroup = sbs.accGroupIdx?.let { tokens.getOrNull(it)?.trim() }
                            val group = parseAccountGroupFromTokens(rawGroup, accName)

                            val currency = resolveCsvRowCurrency(rawCurrency, existingAcc?.currencyCode, defaultCurrency)
                            val spec = SupportedCurrencies.fromCode(currency)

                            val rawBal = sbs.accStartingBalIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                            val balMinor = parseAmountDecimalToMinor(rawBal, spec.fractionDigits) ?: 0L

                            val rawDate = sbs.accAnchorDateIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                            val anchorEpochDay = parseDateStringToEpochDay(rawDate) ?: LocalDate.now().toEpochDay()

                            val rawLimit = sbs.accCreditLimitIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                            val limitMinor = parseAmountDecimalToMinor(rawLimit, spec.fractionDigits)

                            val billDay = sbs.accSettlementDayIdx?.let { tokens.getOrNull(it)?.trim() }

                            val isNew = !accountNameToEntity.containsKey(accName.lowercase())
                            val acc = getOrCreateAccount(
                                name = accName,
                                currency = currency,
                                groupOverride = group,
                                limitMinor = limitMinor,
                                billDay = billDay?.toIntOrNull(),
                                anchorDateEpochDay = anchorEpochDay
                            )
                            if (isNew) {
                                newAccountsCreated.add(accName)
                            }

                            if (balMinor != 0L && !isCurrencyMismatch) {
                                if (acc.groupType == AccountGroup.CREDIT_CARD) {
                                    if (isNew || acc.openingBalanceMinor == 0L) {
                                        val updatedAcc = acc.copy(openingBalanceMinor = MoneyFormatterAndCalculator.safeAbs(balMinor))
                                        accountDao.updateAccount(updatedAcc)
                                        accountNameToEntity[accName.lowercase()] = updatedAcc
                                        accountsConfiguredCount++
                                    }
                                } else {
                                    val isOutflow = balMinor < 0L || acc.groupType.isLiabilityByDefault
                                    val anchorKey = "${acc.id}|$anchorEpochDay"
                                    val alreadyHasAnchor = anchorKey in existingAnchorKeys
                                    if (!alreadyHasAnchor) {
                                        existingAnchorKeys.add(anchorKey)
                                        val anchorTx = TransactionEntity(
                                            id = 0L,
                                            type = TransactionType.OPENING_BALANCE,
                                            timestampEpochMillis = anchorEpochDay * 86400000L + 12 * 3600 * 1000L,
                                            ledgerDateEpochDay = anchorEpochDay,
                                            amountMinor = MoneyFormatterAndCalculator.safeAbs(balMinor),
                                            currencyCode = acc.currencyCode,
                                            accountId = acc.id,
                                            transferToAccountId = if (isOutflow) acc.id else null,
                                            payeeOrDescription = "Opening Balance",
                                            note = "Initial starting balance"
                                        )
                                        txDao.insertNewTransactionRaw(anchorTx)
                                        val anchorFingerprint = "${anchorEpochDay}|${acc.id}|${TransactionType.OPENING_BALANCE.name}|${MoneyFormatterAndCalculator.safeAbs(balMinor)}|opening balance"
                                        preExistingFingerprints.add(anchorFingerprint)
                                        accountsConfiguredCount++
                                    }
                                }
                            }
                        }

                        val mainName = sbs.catMainIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                        val subName = sbs.catSubIdx?.let { tokens.getOrNull(it)?.trim() }?.ifEmpty { null }
                        if (mainName.isNotEmpty()) {
                            val rawType = sbs.catTypeIdx?.let { tokens.getOrNull(it)?.trim() } ?: "EXPENSE"
                            val catType = if (rawType.equals("INCOME", ignoreCase = true)) CategoryType.INCOME else CategoryType.EXPENSE
                            getOrCreateCategory(mainName, subName, catType, null, null)
                        }

                        val rawDate = sbs.txDateIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                        val rawAmount = sbs.txAmountIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                        val accountName = sbs.txAccountIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""

                        if (rawDate.isNotEmpty() || rawAmount.isNotEmpty() || accountName.isNotEmpty()) {
                            val existingAcc = accountNameToEntity[accountName.lowercase()]
                            val rawCurrency = sbs.txCurrencyIdx?.let { tokens.getOrNull(it)?.trim() }?.ifBlank { null }
                            if (existingAcc != null && rawCurrency != null && !rawCurrency.equals(existingAcc.currencyCode, ignoreCase = true)) {
                                skippedCount++
                                if (errorMessages.size < 10) {
                                    errorMessages.add("Row $lineNum: Account '${existingAcc.name}' has currency ${existingAcc.currencyCode}, but transaction row specifies $rawCurrency.")
                                }
                                line = readLogicalCsvRecord(reader)
                                continue
                            }
                            val epochDay = parseDateStringToEpochDay(rawDate)
                            val currency = resolveCsvRowCurrency(rawCurrency, existingAcc?.currencyCode, defaultCurrency)
                            val spec = SupportedCurrencies.fromCode(currency)
                            val amountMinor = parseAmountDecimalToMinor(rawAmount, spec.fractionDigits)

                            if (epochDay == null || amountMinor == null || amountMinor == 0L || accountName.isEmpty()) {
                                val reasons = mutableListOf<String>()
                                if (epochDay == null) reasons.add("Invalid date '$rawDate'")
                                if (amountMinor == null || amountMinor == 0L) reasons.add("Transaction amount must be non-zero ('$rawAmount')")
                                if (accountName.isEmpty()) reasons.add("Missing account name")
                                if (errorMessages.size < 10) {
                                    errorMessages.add("Row $lineNum: ${reasons.joinToString(", ")}.")
                                }
                                skippedCount++
                            } else {
                                val isNewAcc = !accountNameToEntity.containsKey(accountName.lowercase())
                                val sourceAccount = getOrCreateAccount(accountName, currency)
                                if (isNewAcc) {
                                    newAccountsCreated.add(accountName)
                                }

                                val transferAccountIdx = sbs.txTransferAccountIdx
                                val transferAccountName = transferAccountIdx?.let { tokens.getOrNull(it)?.trim() }?.ifEmpty { null }
                                val rawType = sbs.txTypeIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                                val type = when {
                                    rawType.equals("TRANSFER", ignoreCase = true) -> TransactionType.TRANSFER
                                    rawType.equals("CC_SETTLEMENT", ignoreCase = true) || rawType.contains("SETTLEMENT", ignoreCase = true) -> TransactionType.CC_SETTLEMENT
                                    rawType.equals("EXPENSE", ignoreCase = true) -> TransactionType.EXPENSE
                                    rawType.equals("INCOME", ignoreCase = true) -> TransactionType.INCOME
                                    rawType.equals("REFUND", ignoreCase = true) -> TransactionType.REFUND
                                    rawType.equals("OPENING_BALANCE", ignoreCase = true) -> TransactionType.OPENING_BALANCE
                                    rawType.equals("RECONCILIATION", ignoreCase = true) -> TransactionType.RECONCILIATION
                                    transferAccountName != null -> TransactionType.TRANSFER
                                    amountMinor < 0L -> TransactionType.EXPENSE
                                    else -> TransactionType.INCOME
                                }

                                val isTransferOrSettlement = transferAccountName != null && (type == TransactionType.TRANSFER || type == TransactionType.CC_SETTLEMENT)
                                if (isTransferOrSettlement) {
                                    val expectedTargetCurrency = accountNameToEntity[transferAccountName!!.lowercase()]?.currencyCode ?: currency
                                    if (!expectedTargetCurrency.equals(sourceAccount.currencyCode, ignoreCase = true)) {
                                        skippedCount++
                                        if (errorMessages.size < 10) {
                                            errorMessages.add("Row $lineNum: Cross-currency transfer/settlement from ${sourceAccount.name} (${sourceAccount.currencyCode}) to $transferAccountName ($expectedTargetCurrency) is not permitted.")
                                        }
                                        line = readLogicalCsvRecord(reader)
                                        continue
                                    }
                                }

                                val transferAccount = if (isTransferOrSettlement) {
                                    val isNewTransferAcc = !accountNameToEntity.containsKey(transferAccountName!!.lowercase())
                                    val createdTransferAcc = getOrCreateAccount(transferAccountName, currency)
                                    if (isNewTransferAcc) {
                                        newAccountsCreated.add(transferAccountName)
                                    }
                                    createdTransferAcc
                                } else {
                                    null
                                }

                                val hasCurrencyMismatch = transferAccount != null &&
                                    !transferAccount.currencyCode.equals(sourceAccount.currencyCode, ignoreCase = true)
                                if (hasCurrencyMismatch && isTransferOrSettlement) {
                                    skippedCount++
                                    if (errorMessages.size < 10) {
                                        errorMessages.add("Row $lineNum: Cross-currency transfer/settlement from ${sourceAccount.name} (${sourceAccount.currencyCode}) to ${transferAccount!!.name} (${transferAccount.currencyCode}) is not permitted.")
                                    }
                                    line = readLogicalCsvRecord(reader)
                                    continue
                                }

                                if (type == TransactionType.OPENING_BALANCE && sourceAccount.groupType == AccountGroup.CREDIT_CARD) {
                                    if (sourceAccount.openingBalanceMinor == 0L && amountMinor != 0L) {
                                        val updatedAcc = sourceAccount.copy(
                                            openingBalanceMinor = MoneyFormatterAndCalculator.safeAbs(amountMinor)
                                        )
                                        accountDao.updateAccount(updatedAcc)
                                        accountNameToEntity[sourceAccount.name.lowercase()] = updatedAcc
                                        accountsConfiguredCount++
                                    } else {
                                        skippedCount++
                                    }
                                    line = readLogicalCsvRecord(reader)
                                    continue
                                }

                                val preservesOutflowTarget = (type == TransactionType.OPENING_BALANCE || type == TransactionType.RECONCILIATION) &&
                                    (transferAccountName != null || amountMinor < 0L || (type == TransactionType.OPENING_BALANCE && sourceAccount.groupType.isLiabilityByDefault))

                                val validTransferTarget = if (transferAccount != null && transferAccount.id != sourceAccount.id) {
                                    transferAccount.id
                                } else {
                                    null
                                }

                                val (effectiveType, resolvedTransferToAccountId) = when {
                                    type == TransactionType.CC_SETTLEMENT -> {
                                        if (validTransferTarget != null) {
                                            TransactionType.CC_SETTLEMENT to validTransferTarget
                                        } else if (sourceAccount.groupType == AccountGroup.CREDIT_CARD) {
                                            TransactionType.REFUND to null
                                        } else {
                                            TransactionType.EXPENSE to null
                                        }
                                    }
                                    type == TransactionType.TRANSFER -> {
                                        if (validTransferTarget != null) {
                                            TransactionType.TRANSFER to validTransferTarget
                                        } else if (amountMinor < 0L) {
                                            TransactionType.EXPENSE to null
                                        } else {
                                            TransactionType.INCOME to null
                                        }
                                    }
                                    preservesOutflowTarget -> type to sourceAccount.id
                                    else -> type to null
                                }

                                val rawFee = sbs.txFeeIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                                val feeMinor = parseAmountDecimalToMinor(rawFee, spec.fractionDigits) ?: 0L
                                val safeFeeMinor = MoneyFormatterAndCalculator.safeAbs(feeMinor)

                                val rawId = sbs.txIdIdx?.let { tokens.getOrNull(it)?.trim() }?.toLongOrNull()

                                val isTransfer = effectiveType == TransactionType.TRANSFER || effectiveType == TransactionType.CC_SETTLEMENT
                                val rawCat = sbs.txCategoryIdx?.let { tokens.getOrNull(it)?.trim() }
                                val rawSubcat = sbs.txSubcategoryIdx?.let { tokens.getOrNull(it)?.trim() }
                                val isSplitCategory = rawCat?.equals("Split Transaction", ignoreCase = true) == true
                                val isExportedSplit = !isTransfer && isSplitCategory && (!rawSubcat.isNullOrBlank() && rawSubcat.contains(":"))
                                val payee = sbs.txPayeeIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                                val note = sbs.txNoteIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                                val tagsStr = sbs.txTagsIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                                val tagList = tagsStr.split(";", ",").map { it.trim().removePrefix("#").trim() }.filter { it.isNotEmpty() }

                                val absAmount = MoneyFormatterAndCalculator.safeAbs(amountMinor)
                                val fingerprint = "${epochDay}|${sourceAccount.id}|${effectiveType.name}|${absAmount}|${payee.lowercase()}"
                                val isOpeningBalanceDuplicate = effectiveType == TransactionType.OPENING_BALANCE &&
                                    ("${sourceAccount.id}|$epochDay" in existingAnchorKeys)

                                val targetTxId = if (rawId != null && rawId > 0L && existingTxById.containsKey(rawId)) rawId else 0L
                                val splitEntities = if (isExportedSplit && !rawSubcat.isNullOrBlank()) {
                                    parseAndBuildSplits(
                                        rawSubcat,
                                        targetTxId,
                                        if (effectiveType == TransactionType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE,
                                        spec.fractionDigits,
                                        expectedTotalMinor = absAmount
                                    )
                                } else {
                                    emptyList()
                                }
                                val isSplitTx = splitEntities.isNotEmpty()

                                val categoryEntity = if (isTransfer || isSplitTx) {
                                    null
                                } else {
                                    val categoryType = if (effectiveType == TransactionType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
                                    getOrCreateCategory(rawCat, rawSubcat, categoryType)
                                }

                                if (rawId != null && rawId > 0L && existingTxById.containsKey(rawId)) {
                                    val existingTx = existingTxById[rawId]!!
                                    if (isImmutableAnchorUpdate(existingTx.type, effectiveType)) {
                                        skippedCount++
                                        if (errorMessages.size < 10) {
                                            errorMessages.add("Row $lineNum: Cannot overwrite or convert transaction #${existingTx.id} into an immutable balance anchor.")
                                        }
                                        line = readLogicalCsvRecord(reader)
                                        continue
                                    }
                                    val updatedTx = existingTx.copy(
                                        type = effectiveType,
                                        ledgerDateEpochDay = epochDay,
                                        timestampEpochMillis = epochDay * 86_400_000L + 12 * 3600 * 1000L,
                                        amountMinor = absAmount,
                                        currencyCode = sourceAccount.currencyCode,
                                        accountId = sourceAccount.id,
                                        transferToAccountId = resolvedTransferToAccountId,
                                        transferFeeMinor = safeFeeMinor,
                                        categoryId = categoryEntity?.id,
                                        isSplit = isSplitTx,
                                        payeeOrDescription = payee,
                                        note = note
                                    )
                                    txDao.updateTransactionRaw(updatedTx)

                                    txDao.deleteSplitsForTransaction(rawId)
                                    if (splitEntities.isNotEmpty()) {
                                        txDao.insertSplits(splitEntities)
                                    }

                                    txDao.deleteTagCrossRefsForTransaction(rawId)
                                    if (tagList.isNotEmpty()) {
                                        val refs = tagList.mapNotNull { tagName ->
                                            val tag = getOrCreateTag(tagName)
                                            tag?.let { TransactionTagCrossRef(transactionId = rawId, tagId = it.id) }
                                        }
                                        if (refs.isNotEmpty()) {
                                            txDao.insertTagCrossRefs(refs)
                                        }
                                    }
                                    updatedCount++
                                } else if (isOpeningBalanceDuplicate || (skipDuplicates && preExistingFingerprints.contains(fingerprint))) {
                                    skippedCount++
                                } else {
                                    if (effectiveType == TransactionType.OPENING_BALANCE) {
                                        existingAnchorKeys.add("${sourceAccount.id}|$epochDay")
                                    }
                                    val newTx = TransactionEntity(
                                        id = 0L,
                                        type = effectiveType,
                                        timestampEpochMillis = epochDay * 86_400_000L + 12 * 3600 * 1000L,
                                        ledgerDateEpochDay = epochDay,
                                        amountMinor = absAmount,
                                        currencyCode = sourceAccount.currencyCode,
                                        accountId = sourceAccount.id,
                                        transferToAccountId = resolvedTransferToAccountId,
                                        transferFeeMinor = safeFeeMinor,
                                        categoryId = categoryEntity?.id,
                                        isSplit = isSplitTx,
                                        payeeOrDescription = payee,
                                        note = note
                                    )
                                    val insertedId = txDao.insertNewTransactionRaw(newTx)
                                    if (splitEntities.isNotEmpty()) {
                                        txDao.insertSplits(splitEntities.map { it.copy(transactionId = insertedId) })
                                    }
                                    if (tagList.isNotEmpty()) {
                                        val refs = tagList.mapNotNull { tagName ->
                                            val tag = getOrCreateTag(tagName)
                                            tag?.let { TransactionTagCrossRef(transactionId = insertedId, tagId = it.id) }
                                        }
                                        if (refs.isNotEmpty()) {
                                            txDao.insertTagCrossRefs(refs)
                                        }
                                    }
                                    preExistingFingerprints.add(fingerprint)
                                    importedCount++
                                }
                            }
                        }
                    }
                    CsvSectionState.ACCOUNTS -> {
                        val nameIdx = colMap["name"] ?: colMap["account"] ?: colMap["accountname"] ?: 0
                        val name = tokens.getOrNull(nameIdx)?.trim() ?: ""
                        if (name.isNotEmpty()) {
                            val existingAcc = accountNameToEntity[name.lowercase()]
                            val rawCurrency = colMap["currency"]?.let { tokens.getOrNull(it)?.trim() }?.ifBlank { null }
                            val isCurrencyMismatch = existingAcc != null && rawCurrency != null && !rawCurrency.equals(existingAcc.currencyCode, ignoreCase = true)
                            if (isCurrencyMismatch) {
                                if (errorMessages.size < 10) {
                                    errorMessages.add("Row $lineNum: Account '${existingAcc!!.name}' has currency ${existingAcc.currencyCode}, but account section specifies $rawCurrency.")
                                }
                            }

                            val rawGroup = colMap["group"]?.let { tokens.getOrNull(it)?.trim() }
                                ?: colMap["grouptype"]?.let { tokens.getOrNull(it)?.trim() }
                            val group = parseAccountGroupFromTokens(rawGroup, name)

                            val currency = resolveCsvRowCurrency(rawCurrency, existingAcc?.currencyCode, defaultCurrency)
                            val spec = SupportedCurrencies.fromCode(currency)

                            val rawBal = colMap["startingbalance"]?.let { tokens.getOrNull(it)?.trim() }
                                ?: colMap["openingbalance"]?.let { tokens.getOrNull(it)?.trim() }
                                ?: colMap["balance"]?.let { tokens.getOrNull(it)?.trim() }
                                ?: ""
                            val balMinor = parseAmountDecimalToMinor(rawBal, spec.fractionDigits) ?: 0L

                            val rawDate = colMap["anchordate"]?.let { tokens.getOrNull(it)?.trim() }
                                ?: colMap["openingdate"]?.let { tokens.getOrNull(it)?.trim() }
                                ?: colMap["date"]?.let { tokens.getOrNull(it)?.trim() }
                                ?: ""
                            val anchorEpochDay = parseDateStringToEpochDay(rawDate) ?: LocalDate.now().toEpochDay()

                            val rawLimit = colMap["creditlimit"]?.let { tokens.getOrNull(it)?.trim() }
                                ?: colMap["limit"]?.let { tokens.getOrNull(it)?.trim() }
                                ?: ""
                            val limitMinor = parseAmountDecimalToMinor(rawLimit, spec.fractionDigits)

                            val billDay = colMap["settlementday"]?.let { tokens.getOrNull(it)?.trim() }
                                ?: colMap["billday"]?.let { tokens.getOrNull(it)?.trim() }
                                ?: colMap["statementday"]?.let { tokens.getOrNull(it)?.trim() }

                            val isNew = !accountNameToEntity.containsKey(name.lowercase())
                            val acc = getOrCreateAccount(
                                name = name,
                                currency = currency,
                                groupOverride = group,
                                limitMinor = limitMinor,
                                billDay = billDay?.toIntOrNull(),
                                anchorDateEpochDay = anchorEpochDay
                            )
                            if (isNew) {
                                newAccountsCreated.add(name)
                            }

                            if (balMinor != 0L && !isCurrencyMismatch) {
                                if (acc.groupType == AccountGroup.CREDIT_CARD) {
                                    if (isNew || acc.openingBalanceMinor == 0L) {
                                        val updatedAcc = acc.copy(openingBalanceMinor = MoneyFormatterAndCalculator.safeAbs(balMinor))
                                        accountDao.updateAccount(updatedAcc)
                                        accountNameToEntity[name.lowercase()] = updatedAcc
                                        accountsConfiguredCount++
                                    }
                                } else {
                                    val isOutflow = balMinor < 0L || acc.groupType.isLiabilityByDefault
                                    val anchorKey = "${acc.id}|$anchorEpochDay"
                                    val alreadyHasAnchor = anchorKey in existingAnchorKeys
                                    if (!alreadyHasAnchor) {
                                        existingAnchorKeys.add(anchorKey)
                                        val anchorTx = TransactionEntity(
                                            id = 0L,
                                            type = TransactionType.OPENING_BALANCE,
                                            timestampEpochMillis = anchorEpochDay * 86400000L + 12 * 3600 * 1000L,
                                            ledgerDateEpochDay = anchorEpochDay,
                                            amountMinor = MoneyFormatterAndCalculator.safeAbs(balMinor),
                                            currencyCode = acc.currencyCode,
                                            accountId = acc.id,
                                            transferToAccountId = if (isOutflow) acc.id else null,
                                            payeeOrDescription = "Opening Balance",
                                            note = "Initial starting balance"
                                        )
                                        txDao.insertNewTransactionRaw(anchorTx)
                                        val anchorFingerprint = "${anchorEpochDay}|${acc.id}|${TransactionType.OPENING_BALANCE.name}|${MoneyFormatterAndCalculator.safeAbs(balMinor)}|opening balance"
                                        preExistingFingerprints.add(anchorFingerprint)
                                        accountsConfiguredCount++
                                    }
                                }
                            }
                        }
                    }
                    CsvSectionState.CATEGORIES -> {
                        val mainIdx = colMap["maincategory"] ?: colMap["category"] ?: colMap["name"] ?: 0
                        val subIdx = colMap["subcategory"] ?: colMap["subname"] ?: colMap["sub"]
                        val mainName = tokens.getOrNull(mainIdx)?.trim() ?: ""
                        val subName = subIdx?.let { tokens.getOrNull(it)?.trim() }?.ifEmpty { null }
                        val rawType = colMap["type"]?.let { tokens.getOrNull(it)?.trim() } ?: "EXPENSE"
                        val catType = if (rawType.equals("INCOME", ignoreCase = true)) CategoryType.INCOME else CategoryType.EXPENSE
                        val iconOverride = colMap["icon"]?.let { tokens.getOrNull(it)?.trim() }?.ifEmpty { null }
                        val colorOverride = colMap["color"]?.let { tokens.getOrNull(it)?.trim() }?.ifEmpty { null }

                        if (mainName.isNotEmpty()) {
                            getOrCreateCategory(mainName, subName, catType, iconOverride, colorOverride)
                        }
                    }
                    CsvSectionState.TRANSACTIONS,
                    CsvSectionState.LEGACY -> {
                        val dateIdx = colMap["date"] ?: colMap["txdate"] ?: colMap["transactiondate"] ?: colMap["txndate"] ?: colMap["postingdate"] ?: colMap["valuedate"]
                        val amountIdx = colMap["amount"] ?: colMap["amountformatted"] ?: colMap["amountminor"] ?: colMap["txnamount"] ?: colMap["transactionamount"] ?: colMap["total"] ?: colMap["netamount"] ?: colMap["value"]
                        val accountIdx = colMap["account"] ?: colMap["accountname"] ?: colMap["fromaccount"] ?: colMap["sourceaccount"] ?: colMap["bank"] ?: colMap["wallet"]

                        if (dateIdx == null || amountIdx == null || accountIdx == null) {
                            val missingCols = mutableListOf<String>()
                            if (dateIdx == null) missingCols.add("Date")
                            if (amountIdx == null) missingCols.add("Amount")
                            if (accountIdx == null) missingCols.add("Account")
                            if (errorMessages.size < 10) {
                                errorMessages.add("Row $lineNum: Missing column(s) in header: ${missingCols.joinToString(", ")}.")
                            }
                            skippedCount++
                            line = readLogicalCsvRecord(reader)
                            continue
                        }

                        val rawDate = tokens.getOrNull(dateIdx) ?: ""
                        val accountName = tokens.getOrNull(accountIdx)?.trim() ?: ""
                        val existingAcc = accountNameToEntity[accountName.lowercase()]
                        val currencyIdx = colMap["currency"]
                        val rawCurrency = currencyIdx?.let { tokens.getOrNull(it)?.trim() }?.ifBlank { null }
                        if (existingAcc != null && rawCurrency != null && !rawCurrency.equals(existingAcc.currencyCode, ignoreCase = true)) {
                            skippedCount++
                            if (errorMessages.size < 10) {
                                errorMessages.add("Row $lineNum: Account '${existingAcc.name}' has currency ${existingAcc.currencyCode}, but transaction row specifies $rawCurrency.")
                            }
                            line = readLogicalCsvRecord(reader)
                            continue
                        }

                        val epochDay = parseDateStringToEpochDay(rawDate)
                        val currency = resolveCsvRowCurrency(rawCurrency, existingAcc?.currencyCode, defaultCurrency)
                        val spec = SupportedCurrencies.fromCode(currency)
                        val rawAmount = tokens.getOrNull(amountIdx) ?: ""
                        val amountMinor = parseAmountDecimalToMinor(rawAmount, spec.fractionDigits)

                        if (epochDay == null || amountMinor == null || amountMinor == 0L || accountName.isEmpty()) {
                            val reasons = mutableListOf<String>()
                            if (epochDay == null) reasons.add("Invalid date '$rawDate'")
                            if (amountMinor == null || amountMinor == 0L) reasons.add("Transaction amount must be non-zero ('$rawAmount')")
                            if (accountName.isEmpty()) reasons.add("Missing account name")
                            if (errorMessages.size < 10) {
                                errorMessages.add("Row $lineNum: ${reasons.joinToString(", ")}.")
                            }
                            skippedCount++
                            line = readLogicalCsvRecord(reader)
                            continue
                        }

                        val isNewAcc = !accountNameToEntity.containsKey(accountName.lowercase())
                        val sourceAccount = getOrCreateAccount(accountName, currency)
                        if (isNewAcc) {
                            newAccountsCreated.add(accountName)
                        }

                        val transferAccountIdx = colMap["transfertoaccount"] ?: colMap["transferto"]
                        val transferAccountName = transferAccountIdx?.let { tokens.getOrNull(it)?.trim() }?.ifEmpty { null }
                        val rawType = colMap["type"]?.let { tokens.getOrNull(it)?.trim() } ?: ""
                        val type = when {
                            rawType.equals("TRANSFER", ignoreCase = true) -> TransactionType.TRANSFER
                            rawType.equals("CC_SETTLEMENT", ignoreCase = true) || rawType.contains("SETTLEMENT", ignoreCase = true) -> TransactionType.CC_SETTLEMENT
                            rawType.equals("EXPENSE", ignoreCase = true) -> TransactionType.EXPENSE
                            rawType.equals("INCOME", ignoreCase = true) -> TransactionType.INCOME
                            rawType.equals("REFUND", ignoreCase = true) -> TransactionType.REFUND
                            rawType.equals("OPENING_BALANCE", ignoreCase = true) -> TransactionType.OPENING_BALANCE
                            rawType.equals("RECONCILIATION", ignoreCase = true) -> TransactionType.RECONCILIATION
                            transferAccountName != null -> TransactionType.TRANSFER
                            amountMinor < 0L -> TransactionType.EXPENSE
                            else -> TransactionType.INCOME
                        }

                        val isTransferOrSettlement = transferAccountName != null && (type == TransactionType.TRANSFER || type == TransactionType.CC_SETTLEMENT)
                        if (isTransferOrSettlement) {
                            val expectedTargetCurrency = accountNameToEntity[transferAccountName!!.lowercase()]?.currencyCode ?: currency
                            if (!expectedTargetCurrency.equals(sourceAccount.currencyCode, ignoreCase = true)) {
                                skippedCount++
                                if (errorMessages.size < 10) {
                                    errorMessages.add("Row $lineNum: Cross-currency transfer/settlement from ${sourceAccount.name} (${sourceAccount.currencyCode}) to $transferAccountName ($expectedTargetCurrency) is not permitted.")
                                }
                                line = readLogicalCsvRecord(reader)
                                continue
                            }
                        }

                        val transferAccount = if (isTransferOrSettlement) {
                            val isNewTransferAcc = !accountNameToEntity.containsKey(transferAccountName!!.lowercase())
                            val createdTransferAcc = getOrCreateAccount(transferAccountName, currency)
                            if (isNewTransferAcc) {
                                newAccountsCreated.add(transferAccountName)
                            }
                            createdTransferAcc
                        } else {
                            null
                        }

                        val hasCurrencyMismatch = transferAccount != null &&
                            !transferAccount.currencyCode.equals(sourceAccount.currencyCode, ignoreCase = true)
                        if (hasCurrencyMismatch && isTransferOrSettlement) {
                            skippedCount++
                            if (errorMessages.size < 10) {
                                errorMessages.add("Row $lineNum: Cross-currency transfer/settlement from ${sourceAccount.name} (${sourceAccount.currencyCode}) to ${transferAccount!!.name} (${transferAccount.currencyCode}) is not permitted.")
                            }
                            line = readLogicalCsvRecord(reader)
                            continue
                        }

                        if (type == TransactionType.OPENING_BALANCE && sourceAccount.groupType == AccountGroup.CREDIT_CARD) {
                            if (sourceAccount.openingBalanceMinor == 0L && amountMinor != 0L) {
                                val updatedAcc = sourceAccount.copy(
                                    openingBalanceMinor = MoneyFormatterAndCalculator.safeAbs(amountMinor)
                                )
                                accountDao.updateAccount(updatedAcc)
                                accountNameToEntity[sourceAccount.name.lowercase()] = updatedAcc
                                accountsConfiguredCount++
                            } else {
                                skippedCount++
                            }
                            line = readLogicalCsvRecord(reader)
                            continue
                        }

                        val preservesOutflowTarget = (type == TransactionType.OPENING_BALANCE || type == TransactionType.RECONCILIATION) &&
                            (transferAccountName != null || amountMinor < 0L || (type == TransactionType.OPENING_BALANCE && sourceAccount.groupType.isLiabilityByDefault))

                        val validTransferTarget = if (transferAccount != null && transferAccount.id != sourceAccount.id) {
                            transferAccount.id
                        } else {
                            null
                        }

                        val (effectiveType, resolvedTransferToAccountId) = when {
                            type == TransactionType.CC_SETTLEMENT -> {
                                if (validTransferTarget != null) {
                                    TransactionType.CC_SETTLEMENT to validTransferTarget
                                } else if (sourceAccount.groupType == AccountGroup.CREDIT_CARD) {
                                    TransactionType.REFUND to null
                                } else {
                                    TransactionType.EXPENSE to null
                                }
                            }
                            type == TransactionType.TRANSFER -> {
                                if (validTransferTarget != null) {
                                    TransactionType.TRANSFER to validTransferTarget
                                } else if (amountMinor < 0L) {
                                    TransactionType.EXPENSE to null
                                } else {
                                    TransactionType.INCOME to null
                                }
                            }
                            preservesOutflowTarget -> type to sourceAccount.id
                            else -> type to null
                        }

                        val transferFeeIdx = colMap["transferfee"] ?: colMap["transferfeeminor"]
                        val rawFee = transferFeeIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                        val feeMinor = parseAmountDecimalToMinor(rawFee, spec.fractionDigits) ?: 0L

                        val isTransfer = effectiveType == TransactionType.TRANSFER || effectiveType == TransactionType.CC_SETTLEMENT
                        val rawCat = colMap["category"]?.let { tokens.getOrNull(it)?.trim() }
                        val rawSubcat = colMap["subcategory"]?.let { tokens.getOrNull(it)?.trim() }
                        val isSplitCategory = rawCat?.equals("Split Transaction", ignoreCase = true) == true
                        val isExportedSplit = !isTransfer && isSplitCategory && (!rawSubcat.isNullOrBlank() && rawSubcat.contains(":"))

                        val categoryEntity = if (isTransfer || isExportedSplit || isSplitCategory) {
                            null // Strictly suppress category creation for transfers and split transactions!
                        } else {
                            val categoryType = if (effectiveType == TransactionType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
                            getOrCreateCategory(rawCat, rawSubcat, categoryType)
                        }

                        val payeeIdx = colMap["payeeordescription"] ?: colMap["payee"] ?: colMap["description"]
                        val payee = payeeIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                        val noteIdx = colMap["note"]
                        val note = noteIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                        val tagsIdx = colMap["tags"]
                        val tagsStr = tagsIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                        val tagList = tagsStr.split(";", ",").map { it.trim().removePrefix("#").trim() }.filter { it.isNotEmpty() }

                        val absAmount = MoneyFormatterAndCalculator.safeAbs(amountMinor)
                        val fingerprint = "${epochDay}|${sourceAccount.id}|${effectiveType.name}|${absAmount}|${payee.lowercase()}"
                        val isOpeningBalanceDuplicate = effectiveType == TransactionType.OPENING_BALANCE &&
                            ("${sourceAccount.id}|$epochDay" in existingAnchorKeys)

                        val idIdx = colMap["id"]
                        val rawId = idIdx?.let { tokens.getOrNull(it)?.trim() }?.toLongOrNull()

                        if (rawId != null && rawId > 0L && existingTxById.containsKey(rawId)) {
                            val existingTx = existingTxById[rawId]!!
                            if (isImmutableAnchorUpdate(existingTx.type, effectiveType)) {
                                skippedCount++
                                if (errorMessages.size < 10) {
                                    errorMessages.add("Row $lineNum: Cannot overwrite or convert transaction #${existingTx.id} into an immutable balance anchor.")
                                }
                                line = readLogicalCsvRecord(reader)
                                continue
                            }
                            val splitEntities = if (isExportedSplit && !rawSubcat.isNullOrBlank()) {
                                parseAndBuildSplits(
                                    rawSubcat,
                                    rawId,
                                    if (effectiveType == TransactionType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE,
                                    spec.fractionDigits,
                                    expectedTotalMinor = absAmount
                                )
                            } else {
                                emptyList()
                            }
                            val isSplitTx = splitEntities.isNotEmpty()
                            val effectiveCategoryId = if (isTransfer || isSplitTx) null else categoryEntity?.id

                            val updatedTx = existingTx.copy(
                                type = effectiveType,
                                ledgerDateEpochDay = epochDay,
                                timestampEpochMillis = epochDay * 86_400_000L + 12 * 3600 * 1000L,
                                amountMinor = absAmount,
                                currencyCode = sourceAccount.currencyCode,
                                accountId = sourceAccount.id,
                                transferToAccountId = resolvedTransferToAccountId,
                                transferFeeMinor = MoneyFormatterAndCalculator.safeAbs(feeMinor),
                                categoryId = effectiveCategoryId,
                                isSplit = isSplitTx,
                                payeeOrDescription = payee,
                                note = note
                            )
                            txDao.updateTransactionRaw(updatedTx)

                            txDao.deleteSplitsForTransaction(rawId)
                            if (splitEntities.isNotEmpty()) {
                                txDao.insertSplits(splitEntities)
                            }

                            txDao.deleteTagCrossRefsForTransaction(rawId)
                            if (tagList.isNotEmpty()) {
                                val refs = tagList.mapNotNull { tagName ->
                                    val tag = getOrCreateTag(tagName)
                                    tag?.let { TransactionTagCrossRef(transactionId = rawId, tagId = it.id) }
                                }
                                if (refs.isNotEmpty()) {
                                    txDao.insertTagCrossRefs(refs)
                                }
                            }
                            preExistingFingerprints.add(fingerprint)
                            updatedCount++
                        } else if (isOpeningBalanceDuplicate || (skipDuplicates && preExistingFingerprints.contains(fingerprint))) {
                            skippedCount++
                        } else {
                            if (effectiveType == TransactionType.OPENING_BALANCE) {
                                existingAnchorKeys.add("${sourceAccount.id}|$epochDay")
                            }
                            val splitEntities = if (isExportedSplit && !rawSubcat.isNullOrBlank()) {
                                parseAndBuildSplits(
                                    rawSubcat,
                                    0L,
                                    if (effectiveType == TransactionType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE,
                                    spec.fractionDigits,
                                    expectedTotalMinor = absAmount
                                )
                            } else {
                                emptyList()
                            }
                            val isSplitTx = splitEntities.isNotEmpty()
                            val effectiveCategoryId = if (isTransfer || isSplitTx) null else categoryEntity?.id

                            val newTx = TransactionEntity(
                                id = 0L,
                                type = effectiveType,
                                timestampEpochMillis = epochDay * 86_400_000L + 12 * 3600 * 1000L,
                                ledgerDateEpochDay = epochDay,
                                amountMinor = absAmount,
                                currencyCode = sourceAccount.currencyCode,
                                accountId = sourceAccount.id,
                                transferToAccountId = resolvedTransferToAccountId,
                                transferFeeMinor = MoneyFormatterAndCalculator.safeAbs(feeMinor),
                                categoryId = effectiveCategoryId,
                                isSplit = isSplitTx,
                                payeeOrDescription = payee,
                                note = note
                            )
                            val insertedId = txDao.insertNewTransactionRaw(newTx)
                            if (splitEntities.isNotEmpty()) {
                                txDao.insertSplits(splitEntities.map { it.copy(transactionId = insertedId) })
                            }
                            if (tagList.isNotEmpty()) {
                                val refs = tagList.mapNotNull { tagName ->
                                    val tag = getOrCreateTag(tagName)
                                    tag?.let { TransactionTagCrossRef(transactionId = insertedId, tagId = it.id) }
                                }
                                if (refs.isNotEmpty()) {
                                    txDao.insertTagCrossRefs(refs)
                                }
                            }
                            preExistingFingerprints.add(fingerprint)
                            importedCount++
                        }
                    }
                }
            }
            line = readLogicalCsvRecord(reader)
        }

        if (importedCount > 0 || updatedCount > 0 || accountsConfiguredCount > 0) {
            SessionChangeTracker.markDirty()
        }

        ImportCsvResult(
            totalRowsRead = totalRows,
            importedCount = importedCount,
            updatedCount = updatedCount,
            skippedCount = skippedCount,
            newAccountsCreated = newAccountsCreated.distinct(),
            newCategoriesCreated = newCategoriesCreated.distinct(),
            errorMessages = errorMessages,
            accountsConfiguredCount = accountsConfiguredCount
        )
    }

    private fun formatDecimalAmountPlain(minor: Long, fractionDigits: Int): String {
        if (fractionDigits <= 0) return minor.toString()
        val isNeg = minor < 0L
        val absMinor = MoneyFormatterAndCalculator.safeAbs(minor)
        val factor = when (fractionDigits) {
            1 -> 10L
            2 -> 100L
            3 -> 1000L
            4 -> 10000L
            else -> {
                var f = 1L
                for (i in 1..fractionDigits) { f *= 10L }
                f
            }
        }
        val whole = absMinor / factor
        val frac = absMinor % factor
        val fracStr = frac.toString().padStart(fractionDigits, '0')
        val res = "$whole.$fracStr"
        return if (isNeg) "-$res" else res
    }

}

internal fun readLogicalCsvRecord(reader: java.io.BufferedReader, maxLinesPerRecord: Int = 10): String? {
    val firstLine = reader.readLine() ?: return null
    var quoteCount = firstLine.count { it == '"' }
    if (quoteCount % 2 == 0) return firstLine

    val sb = StringBuilder(firstLine)
    var extraLines = 0
    while (quoteCount % 2 != 0 && extraLines < maxLinesPerRecord) {
        val nextLine = reader.readLine() ?: break
        if (detectSectionHeader(nextLine.trim()) != null) {
            break
        }
        sb.append('\n').append(nextLine)
        quoteCount += nextLine.count { it == '"' }
        extraLines++
    }
    return sb.toString()
}

internal fun parseCsvLine(line: String): List<String> {
    val tokens = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' -> {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    sb.append('"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            }
            c == ',' && !inQuotes -> {
                tokens.add(sb.toString())
                sb.setLength(0)
            }
            else -> {
                sb.append(c)
            }
        }
        i++
    }
    tokens.add(sb.toString())
    return tokens
}

internal enum class CsvSectionState {
    LEGACY,
    ACCOUNTS,
    CATEGORIES,
    TRANSACTIONS,
    SIDE_BY_SIDE
}

internal fun detectSectionHeader(line: String): CsvSectionState? {
    val trimmed = line.trim().uppercase()
    if (trimmed.isEmpty()) return null

    if (trimmed.startsWith("#") || trimmed.startsWith("//")) {
        // Any comment or hint with colons or commas is a comment/hint, not a Markdown section header
        if (trimmed.contains(":") || trimmed.contains(",")) {
            return null
        }
    }

    // Side-by-side banner detection: contains multiple section markers across columns
    val hasAccMarker = trimmed.contains("[ACCOUNT") || trimmed.contains("=== ACCOUNT") || trimmed.contains("## ACCOUNT")
    val hasCatMarker = trimmed.contains("[CATEGOR") || trimmed.contains("=== CATEGOR") || trimmed.contains("## CATEGOR")
    val hasTxMarker = trimmed.contains("[TRANSACTION") || trimmed.contains("=== TRANSACTION") || trimmed.contains("## TRANSACTION")
    val markerCount = (if (hasAccMarker) 1 else 0) + (if (hasCatMarker) 1 else 0) + (if (hasTxMarker) 1 else 0)
    if (markerCount >= 2) {
        return CsvSectionState.SIDE_BY_SIDE
    }

    // Banner without brackets/equals, e.g. "ACCOUNTS,,,,,,,CATEGORIES,,,,TRANSACTIONS,,,,,,,,,"
    if (!trimmed.contains("DATE") && !trimmed.contains("AMOUNT") && !trimmed.contains("BALANCE") && !trimmed.contains("CURRENCY")) {
        val hasAccountsWord = trimmed.contains("ACCOUNT")
        val hasCategoriesWord = trimmed.contains("CATEGOR")
        val hasTransactionsWord = trimmed.contains("TRANSACTION")
        val wordCount = (if (hasAccountsWord) 1 else 0) + (if (hasCategoriesWord) 1 else 0) + (if (hasTransactionsWord) 1 else 0)
        if (wordCount >= 2 && trimmed.contains(",")) {
            return CsvSectionState.SIDE_BY_SIDE
        }
    }

    // Single section header detection
    val tokens = parseCsvLine(trimmed)
    val firstToken = tokens.firstOrNull()?.trim() ?: ""
    val otherTokensNonEmpty = tokens.drop(1).any { it.isNotBlank() }

    // If there are other non-empty columns (like in a header row or data row), it cannot be a single section header
    if (otherTokensNonEmpty) {
        return null
    }

    val clean = firstToken
        .replace("=", "")
        .replace("[", "")
        .replace("]", "")
        .replace("#", "")
        .trim()

    return when {
        clean.startsWith("ACCOUNT") -> CsvSectionState.ACCOUNTS
        clean.startsWith("CATEGOR") -> CsvSectionState.CATEGORIES
        clean.startsWith("TRANSACTION") -> CsvSectionState.TRANSACTIONS
        else -> null
    }
}

internal data class SideBySideColMap(
    val accNameIdx: Int? = null,
    val accGroupIdx: Int? = null,
    val accCurrencyIdx: Int? = null,
    val accStartingBalIdx: Int? = null,
    val accAnchorDateIdx: Int? = null,
    val accCreditLimitIdx: Int? = null,
    val accSettlementDayIdx: Int? = null,

    val catMainIdx: Int? = null,
    val catSubIdx: Int? = null,
    val catTypeIdx: Int? = null,

    val txIdIdx: Int? = null,
    val txDateIdx: Int? = null,
    val txTypeIdx: Int? = null,
    val txAmountIdx: Int? = null,
    val txCurrencyIdx: Int? = null,
    val txAccountIdx: Int? = null,
    val txTransferAccountIdx: Int? = null,
    val txFeeIdx: Int? = null,
    val txCategoryIdx: Int? = null,
    val txSubcategoryIdx: Int? = null,
    val txPayeeIdx: Int? = null,
    val txNoteIdx: Int? = null,
    val txTagsIdx: Int? = null
)

internal fun isSideBySideHeader(tokens: List<String>): Boolean {
    val normalized = tokens.map { it.trim().lowercase().replace("_", "").replace(" ", "") }
    val hasDate = normalized.any { it == "date" || it == "txdate" || it == "transactiondate" || it == "postingdate" }
    val hasAmount = normalized.any { it == "amount" || it == "txnamount" || it == "amountformatted" }

    // Side-by-side spreadsheets contain account balance/limit definitions or category definitions alongside transactions
    val hasAccountDefs = normalized.any {
        it == "startingbalance" || it == "openingbalance" || it == "creditlimit" || it == "settlementday"
    }
    val hasCategoryType = normalized.any { it == "categorytype" }
    val hasDuplicateCategory = normalized.count { it == "category" || it == "maincategory" || it == "categoryname" } >= 2
    val hasDuplicateAccount = normalized.count { it == "account" || it == "accountname" || it == "sourceaccount" } >= 2

    return hasDate && hasAmount && (hasAccountDefs || hasCategoryType || (hasDuplicateCategory && hasDuplicateAccount))
}

internal fun parseSideBySideColMap(tokens: List<String>): SideBySideColMap {
    val normalizedTokens = tokens.map { it.trim().lowercase().replace("_", "").replace(" ", "") }
    val dateIdx = normalizedTokens.indexOfFirst { token ->
        token == "date" || token == "txdate" || token == "transactiondate" || token == "postingdate"
    }.takeIf { it >= 0 }

    var accName: Int? = null
    var accGroup: Int? = null
    var accCurrency: Int? = null
    var accStartingBal: Int? = null
    var accAnchorDate: Int? = null
    var accCreditLimit: Int? = null
    var accSettlementDay: Int? = null

    var catMain: Int? = null
    var catSub: Int? = null
    var catType: Int? = null

    var txId: Int? = null
    var txDate: Int? = dateIdx
    var txType: Int? = null
    var txAmount: Int? = null
    var txCurrency: Int? = null
    var txAccount: Int? = null
    var txTransferAccount: Int? = null
    var txFee: Int? = null
    var txCategory: Int? = null
    var txSubcategory: Int? = null
    var txPayee: Int? = null
    var txNote: Int? = null
    var txTags: Int? = null

    normalizedTokens.forEachIndexed { idx, col ->
        if (col.isBlank()) return@forEachIndexed

        val isBeforeTx = dateIdx == null || idx < dateIdx
        if (isBeforeTx) {
            when {
                col == "id" || col == "txid" || col == "transactionid" -> txId = idx
                col == "transferfee" || col == "transferfeeminor" || col == "fee" -> txFee = idx
                col == "accountname" || col == "account" || (col == "name" && accName == null) -> accName = idx
                col == "accountgroup" || col == "group" || col == "grouptype" -> accGroup = idx
                col == "accountcurrency" || (col == "currency" && accCurrency == null) -> accCurrency = idx
                col == "startingbalance" || col == "openingbalance" || col == "balance" -> accStartingBal = idx
                col == "anchordate" || col == "openingdate" -> accAnchorDate = idx
                col == "creditlimit" || col == "limit" -> accCreditLimit = idx
                col == "settlementday" || col == "billday" || col == "statementday" -> accSettlementDay = idx

                col == "categorytype" || (col == "type" && catType == null) -> catType = idx
                col == "subcategory" || col == "subname" || col == "sub" -> catSub = idx
                col == "category" || col == "categoryname" || col == "maincategory" || (col == "name" && accName != null) -> catMain = idx
            }
        } else {
            when {
                idx == dateIdx -> txDate = idx
                col == "id" || col == "txid" || col == "transactionid" -> txId = idx
                col == "transferfee" || col == "transferfeeminor" || col == "fee" -> txFee = idx
                col == "amount" || col == "txnamount" || col == "amountformatted" || col == "total" -> txAmount = idx
                col == "transfertoaccount" || col == "transferto" || col == "toaccount" || col == "destinationaccount" -> txTransferAccount = idx
                col == "account" || col == "accountname" || col == "sourceaccount" || col == "fromaccount" || col == "bank" || col == "wallet" -> txAccount = idx
                col == "type" || col == "txtype" || col == "transactiontype" -> txType = idx
                col == "currency" || col == "txcurrency" -> txCurrency = idx
                col == "subcategory" || col == "subname" || col == "subcat" -> txSubcategory = idx
                col == "category" || col == "categoryname" || col == "maincategory" -> txCategory = idx
                col == "payee" || col == "payeeordescription" || col == "description" || col == "merchant" -> txPayee = idx
                col == "note" || col == "notes" || col == "memo" -> txNote = idx
                col == "tags" || col == "tag" || col == "label" || col == "labels" -> txTags = idx
            }
        }
    }

    if (accName == null && (accStartingBal != null || accCreditLimit != null)) {
        accName = 0
    }

    return SideBySideColMap(
        accNameIdx = accName,
        accGroupIdx = accGroup,
        accCurrencyIdx = accCurrency,
        accStartingBalIdx = accStartingBal,
        accAnchorDateIdx = accAnchorDate,
        accCreditLimitIdx = accCreditLimit,
        accSettlementDayIdx = accSettlementDay,
        catMainIdx = catMain,
        catSubIdx = catSub,
        catTypeIdx = catType,
        txIdIdx = txId,
        txDateIdx = txDate,
        txTypeIdx = txType,
        txAmountIdx = txAmount,
        txCurrencyIdx = txCurrency,
        txAccountIdx = txAccount,
        txTransferAccountIdx = txTransferAccount,
        txFeeIdx = txFee,
        txCategoryIdx = txCategory,
        txSubcategoryIdx = txSubcategory,
        txPayeeIdx = txPayee,
        txNoteIdx = txNote,
        txTagsIdx = txTags
    )
}

internal fun generateSideBySideCsvTemplateContent(): String {
    val sb = StringBuilder()
    sb.append("[ACCOUNTS],,,,,,,,[CATEGORIES],,,,[TRANSACTIONS],,,,,,,,,,\n")
    sb.append("Account Name,Group,Account Currency,Starting Balance,Anchor Date,Credit Limit,Settlement Day,,Category,Subcategory,Category Type,,Date,Type,Amount,Currency,Account,Transfer To Account,Category,Subcategory,Payee,Note,Tags\n")
    sb.append("# e.g. Checking Account,# BANK_ACCOUNT | CREDIT_CARD | CASH | SAVINGS | LOAN,# ISO Code (USD/EUR/INR),# e.g. 5000.00,# YYYY-MM-DD,# e.g. 10000.00,# 1-31,,# Main Category Name,# Optional Subcategory,# EXPENSE | INCOME,,# YYYY-MM-DD,# EXPENSE | INCOME | TRANSFER,# e.g. 45.50,# ISO Code,# Source Account,# Destination if Transfer,# Category,# Subcategory,# Merchant / Payee,# Optional Note,# Semicolon-separated tags (e.g. groceries; food)\n")
    return sb.toString()
}

private val CSV_DATE_FORMATTERS = listOf(
    DateTimeFormatter.ISO_LOCAL_DATE,
    DateTimeFormatter.ofPattern("d/M/yyyy", Locale.US),
    DateTimeFormatter.ofPattern("d-M-yyyy", Locale.US),
    DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US),
    DateTimeFormatter.ofPattern("yyyy/M/d", Locale.US),
    DateTimeFormatter.ofPattern("d.M.yyyy", Locale.US),
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US),
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
)

private val CC_ACCOUNT_REGEX = Regex("""\b(cc|credit|credit\s*card)\b""")
private val OD_ACCOUNT_REGEX = Regex("""\b(od|overdraft)\b""")

internal fun parseAccountGroupFromTokens(rawGroup: String?, accountName: String): AccountGroup {
    val clean = rawGroup?.trim()?.uppercase()?.replace(" ", "_") ?: ""
    if (clean.isNotEmpty()) {
        if (clean == "BANK") return AccountGroup.BANK_ACCOUNT
        if (clean == "WALLET") return AccountGroup.PREPAID_WALLET
        if (clean == "CC" || clean == "CREDIT" || clean == "CARD") return AccountGroup.CREDIT_CARD
        if (clean == "DEBIT") return AccountGroup.DEBIT_CARD
        if (clean == "OD" || clean == "OVERDRAFT") return AccountGroup.OVERDRAFT
        runCatching { return AccountGroup.valueOf(clean) }
    }
    val lower = accountName.lowercase()
    return when {
        CC_ACCOUNT_REGEX.containsMatchIn(lower) -> AccountGroup.CREDIT_CARD
        lower.contains("debit") -> AccountGroup.DEBIT_CARD
        lower.contains("cash") || lower.contains("wallet") -> AccountGroup.CASH
        lower.contains("savings") -> AccountGroup.SAVINGS
        lower.contains("bank") || lower.contains("a/c") || lower.contains("checking") -> AccountGroup.BANK_ACCOUNT
        lower.contains("loan") || lower.contains("emi") || lower.contains("mortgage") || lower.contains("borrow") -> AccountGroup.LOAN
        OD_ACCOUNT_REGEX.containsMatchIn(lower) -> AccountGroup.OVERDRAFT
        lower.contains("invest") || lower.contains("mutual") || lower.contains("stock") -> AccountGroup.INVESTMENT
        else -> AccountGroup.OTHER
    }
}

internal fun parseAmountDecimalToMinor(raw: String, fractionDigits: Int): Long? {
    var cleaned = raw
        .replace("\u00A0", " ")
        .replace("$", "")
        .replace("\u20B9", "") // ₹
        .replace("\u20AC", "") // €
        .replace("\u00A3", "") // £
        .replace("\u00A5", "") // ¥
        .replace("₹", "")
        .replace("€", "")
        .replace("£", "")
        .replace("¥", "")
        .replace("â‚¹", "")
        .replace("â‚¬", "")
        .replace("Â£", "")
        .trim()
    if (cleaned.isEmpty()) return null

    // Support accounting negative format: "(125.50)" -> "-125.50"
    val isParenNegative = cleaned.startsWith("(") && cleaned.endsWith(")")
    if (isParenNegative) {
        cleaned = cleaned.substring(1, cleaned.length - 1).trim()
    }

    // Strip leading/trailing 3-letter ISO currency codes (e.g. "USD 120.50" or "120.50 EUR")
    cleaned = cleaned.replace(Regex("^[A-Za-z]{3}\\s+"), "")
        .replace(Regex("\\s+[A-Za-z]{3}$"), "")
        .replace(" ", "")

    val lastComma = cleaned.lastIndexOf(',')
    val lastDot = cleaned.lastIndexOf('.')

    cleaned = when {
        lastComma != -1 && lastDot != -1 -> {
            if (lastComma > lastDot) {
                // European format: 1.234,56 -> 1234.56
                cleaned.replace(".", "").replace(',', '.')
            } else {
                // Standard format: 1,234.56 -> 1234.56
                cleaned.replace(",", "")
            }
        }
        lastComma != -1 -> {
            val commaCount = cleaned.count { it == ',' }
            val digitsAfterComma = cleaned.length - lastComma - 1
            // Treat single comma as decimal separator unless it's clearly 3-digit thousands grouping (e.g. "1,000" for 2-decimal currency)
            if (
                commaCount == 1 &&
                digitsAfterComma in 1..fractionDigits &&
                (digitsAfterComma != 3 || java.text.DecimalFormatSymbols.getInstance().decimalSeparator == ',')
            ) {
                cleaned.replace(',', '.')
            } else {
                cleaned.replace(",", "")
            }
        }
        else -> cleaned
    }

    if (isParenNegative && !cleaned.startsWith("-")) {
        cleaned = "-$cleaned"
    }

    return try {
        val bd = BigDecimal(cleaned)
        bd.movePointRight(fractionDigits)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    } catch (_: Exception) {
        null
    }
}

internal fun parseDateStringToEpochDay(raw: String): Long? {
    val cleaned = raw.trim()
        .substringBefore('T')
        .replace(Regex("\\s+\\d{1,2}:\\d{2}(:\\d{2})?.*$"), "")
        .trim()
    if (cleaned.isEmpty()) return null
    for (formatter in CSV_DATE_FORMATTERS) {
        try {
            return LocalDate.parse(cleaned, formatter).toEpochDay()
        } catch (_: Exception) {
            // Try next formatter
        }
    }
    return null
}

internal fun resolveCsvRowCurrency(
    rawCurrency: String?,
    existingAccountCurrency: String?,
    defaultCurrency: String
): String = rawCurrency?.ifBlank { null } ?: existingAccountCurrency ?: defaultCurrency

internal fun isImmutableAnchorUpdate(
    existingType: TransactionType,
    newType: TransactionType
): Boolean = existingType == TransactionType.OPENING_BALANCE ||
    existingType == TransactionType.RECONCILIATION ||
    newType == TransactionType.OPENING_BALANCE ||
    newType == TransactionType.RECONCILIATION


