package com.folioledger.app.domain.util

import com.folioledger.app.domain.model.CsvImportPreset
import com.folioledger.app.domain.model.TransactionType
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale
import kotlin.math.abs

data class NormalizedCsvTransaction(
    val lineNum: Int,
    val epochDay: Long,
    val rawDate: String,
    val type: TransactionType,
    val amountMinor: Long,            // Always positive (> 0L)
    val isOutflowReconciliation: Boolean = false, // Maps to transferToAccountId = sourceAccount.id for RECONCILIATION
    val currencyCode: String?,        // Uppercased ISO or null (inherits account/default)
    val accountName: String,
    val transferToAccountName: String? = null,
    val transferToCurrencyCode: String? = null,
    val transferFeeMinor: Long = 0L,
    val categoryName: String? = null,
    val subCategoryName: String? = null,
    val payeeOrDescription: String = "",
    val note: String = "",
    val tags: List<String> = emptyList(),
    val rawAmountForDisplay: String = "",
    val localDateTimeEpochSeconds: Long? = null,
    val isTransferLeg: Boolean = false,
    val isOutflowLeg: Boolean = false,
    val isCashewBalanceCorrection: Boolean = false
)

data class CompetitorCsvParseResult(
    val resolvedPreset: CsvImportPreset,
    val totalRowsRead: Int,
    val transactions: List<NormalizedCsvTransaction>,
    val consolidatedTransferCount: Int,
    val skippedScheduledCount: Int,
    val warnings: List<String>
)

object CompetitorCsvParser {

    enum class DateOrder { DMY, MDY }

    // ------------------------------------------------------------------------
    // 1. BOM-Aware Charset Reader
    // ------------------------------------------------------------------------

    fun openBomAwareReader(inputStream: InputStream): BufferedReader {
        val bis = if (inputStream is BufferedInputStream) inputStream else BufferedInputStream(inputStream)
        bis.mark(4)
        val bom = ByteArray(3)
        val read = bis.read(bom, 0, 3)

        val reader = when {
            read >= 2 && bom[0] == 0xFE.toByte() && bom[1] == 0xFF.toByte() -> {
                bis.reset()
                bis.skip(2)
                InputStreamReader(bis, StandardCharsets.UTF_16BE)
            }
            read >= 2 && bom[0] == 0xFF.toByte() && bom[1] == 0xFE.toByte() -> {
                bis.reset()
                bis.skip(2)
                InputStreamReader(bis, StandardCharsets.UTF_16LE)
            }
            read >= 3 && bom[0] == 0xEF.toByte() && bom[1] == 0xBB.toByte() && bom[2] == 0xBF.toByte() -> {
                // UTF-8 BOM, 3 bytes already skipped
                InputStreamReader(bis, StandardCharsets.UTF_8)
            }
            else -> {
                bis.reset()
                InputStreamReader(bis, StandardCharsets.UTF_8)
            }
        }
        return BufferedReader(reader)
    }

    // ------------------------------------------------------------------------
    // 2. Delimiter Auto-Detection & Line Parser (RFC 4180)
    // ------------------------------------------------------------------------

    fun detectCsvDelimiter(headerLine: String): Char {
        var inQuotes = false
        var tabCount = 0
        var semiCount = 0
        var commaCount = 0

        for (ch in headerLine) {
            if (ch == '"') {
                inQuotes = !inQuotes
            } else if (!inQuotes) {
                when (ch) {
                    '\t' -> tabCount++
                    ';' -> semiCount++
                    ',' -> commaCount++
                }
            }
        }

        return when {
            tabCount >= 2 -> '\t'
            semiCount >= 2 && semiCount >= commaCount -> ';'
            else -> ','
        }
    }

    fun parseCsvLineWithDelimiter(line: String, delimiter: Char = ','): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        val len = line.length

        while (i < len) {
            val c = line[i]
            if (c == '"') {
                if (inQuotes && i + 1 < len && line[i + 1] == '"') {
                    sb.append('"')
                    i++ // Skip escaped quote
                } else {
                    inQuotes = !inQuotes
                }
            } else if (c == delimiter && !inQuotes) {
                tokens.add(sb.toString().trim())
                sb.setLength(0)
            } else {
                sb.append(c)
            }
            i++
        }
        tokens.add(sb.toString().trim())
        return tokens
    }

    fun readNextCsvRecord(reader: BufferedReader): String? {
        val firstLine = reader.readLine() ?: return null
        val sb = StringBuilder(firstLine)
        var count = countQuotes(firstLine)
        while (count % 2 != 0) {
            val nextLine = reader.readLine() ?: break
            sb.append("\n").append(nextLine)
            count += countQuotes(nextLine)
        }
        return sb.toString()
    }

    private fun countQuotes(s: String): Int {
        var count = 0
        for (i in 0 until s.length) {
            if (s[i] == '"') count++
        }
        return count
    }

    fun buildFirstWinsHeaderMap(headers: List<String>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        for (idx in headers.indices) {
            val key = normalizeHeaderKey(headers[idx])
            if (key.isNotEmpty()) {
                map.putIfAbsent(key, idx)
            }
        }
        return map
    }

    fun normalizeHeaderKey(header: String): String {
        return header.trim()
            .removePrefix("\uFEFF")
            .removePrefix("ï»¿")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]"), "")
    }

    // ------------------------------------------------------------------------
    // 3. Preset Auto-Detection
    // ------------------------------------------------------------------------

    fun detectPresetFromHeader(rawHeaderLine: String, headers: List<String>): CsvImportPreset {
        val cleanRaw = rawHeaderLine.trim().removePrefix("\uFEFF").removePrefix("ï»¿").lowercase(Locale.US)
        val normalized = headers.map { normalizeHeaderKey(it) }.toSet()

        fun has(key: String) = normalized.contains(key)
        fun hasAny(vararg keys: String) = keys.any { normalized.contains(it) }

        return when {
            // Ivy Wallet: has toaccount AND (transferamount || receiveamount || duedate) AND title
            has("toaccount") && (has("transferamount") || has("receiveamount") || has("duedate")) && has("title") ->
                CsvImportPreset.IVY_WALLET

            // Cashew: has income AND (categoryname || subcategoryname || objective) AND account
            has("income") && (has("categoryname") || has("subcategoryname") || has("objective")) && has("account") ->
                CsvImportPreset.CASHEW

            // Wallet by BudgetBakers: has refcurrencyamount OR (paymenttype AND transfer AND labels)
            has("refcurrencyamount") || (has("paymenttype") && has("transfer") && has("labels")) ->
                CsvImportPreset.WALLET_BUDGETBAKERS

            // YNAB: has outflow AND inflow AND (categorygroup || categorygroupcategory)
            has("outflow") && has("inflow") && (has("categorygroup") || has("categorygroupcategory")) ->
                CsvImportPreset.YNAB

            // Realbyte Money Manager: has incomeexpense AND (account || accounts)
            has("incomeexpense") && (has("account") || has("accounts")) ->
                CsvImportPreset.REALBYTE_MONEY_MANAGER

            // Intuit Mint: has transactiontype AND accountname AND (originaldescription || labels)
            has("transactiontype") && has("accountname") && (has("originaldescription") || has("labels")) ->
                CsvImportPreset.INTUIT_MINT

            // Money Lover: has wallet AND category AND note AND (id || event) AND NOT toaccount
            has("wallet") && has("category") && has("note") && (has("id") || has("event")) && !has("toaccount") ->
                CsvImportPreset.MONEY_LOVER

            // Bluecoins: has categorygroup AND exchangerate AND account
            has("categorygroup") && has("exchangerate") && has("account") ->
                CsvImportPreset.BLUECOINS

            // Financisto: has date AND time AND originalamount AND parent
            has("date") && has("time") && has("originalamount") && has("parent") ->
                CsvImportPreset.FINANCISTO

            // Monefy: has convertedamount AND account AND category AND amount
            has("convertedamount") && has("account") && has("category") && has("amount") ->
                CsvImportPreset.MONEFY

            // 1Money: has fromaccount AND (toaccountcategory || toaccount) AND type AND NOT transfertoaccount
            has("fromaccount") && (has("toaccountcategory") || has("toaccount")) && has("type") && !has("transfertoaccount") ->
                CsvImportPreset.ONE_MONEY

            // Spendee: has date AND wallet AND type AND categoryname AND amount AND currency (or unambiguous spendee headers)
            has("wallet") && has("categoryname") && has("type") && has("currency") ->
                CsvImportPreset.SPENDEE

            // Money Manager (KTW): Standard UTF-8 transaction export
            has("ktw") || (has("account") && has("type") && has("category") && has("amount") && has("description") && cleanRaw.contains("ktw")) ->
                CsvImportPreset.KTW_MONEY_MANAGER

            // Fortune City: category-based income/expense & 2-digit dates
            has("city") || (has("category") && cleanRaw.contains("fortunecity")) ->
                CsvImportPreset.FORTUNE_CITY

            else -> CsvImportPreset.FOLIO_NATIVE
        }
    }

    // ------------------------------------------------------------------------
    // 4. Date Parsing & File-Level Disambiguation
    // ------------------------------------------------------------------------

    private val SLASH_DATE_REGEX = Regex("""^(\d{1,2})[/.-](\d{1,2})[/.-](\d{2,4})""")

    fun disambiguateDateOrder(dateStrings: List<String>, presetDefault: DateOrder = DateOrder.DMY): DateOrder {
        var sawPart1Over12 = false
        var sawPart2Over12 = false

        for (str in dateStrings) {
            val match = SLASH_DATE_REGEX.find(str.trim()) ?: continue
            val part1 = match.groupValues[1].toIntOrNull() ?: continue
            val part2 = match.groupValues[2].toIntOrNull() ?: continue

            if (part1 > 12) sawPart1Over12 = true
            if (part2 > 12) sawPart2Over12 = true

            if (sawPart1Over12) return DateOrder.DMY
            if (sawPart2Over12) return DateOrder.MDY
        }

        return presetDefault
    }

    fun parseDateAndSeconds(
        raw: String,
        dateOrder: DateOrder
    ): Pair<Long, Long?>? {
        val trimmed = raw.trim().removePrefix("\uFEFF").removePrefix("ï»¿")
        if (trimmed.isEmpty()) return null

        // 1. Try ISO LocalDateTime / OffsetDateTime
        try {
            val ldt = LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_DATE_TIME)
            return Pair(ldt.toLocalDate().toEpochDay(), ldt.toEpochSecond(ZoneOffset.UTC))
        } catch (_: Exception) {}

        // 2. Try Standard formatters
        val formatters = if (dateOrder == DateOrder.DMY) {
            listOf(
                "yyyy-MM-dd HH:mm:ss.SSSSSS",
                "yyyy-MM-dd HH:mm:ss.SSS",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd",
                "dd/MM/yyyy HH:mm:ss",
                "dd/MM/yyyy HH:mm",
                "dd/MM/yyyy",
                "dd-MM-yyyy HH:mm:ss",
                "dd-MM-yyyy HH:mm",
                "dd-MM-yyyy",
                "dd.MM.yyyy HH:mm:ss",
                "dd.MM.yyyy HH:mm",
                "dd.MM.yyyy",
                "dd-MM-yy HH:mm",
                "dd-MM-yy",
                "dd/MM/yy HH:mm",
                "dd/MM/yy",
                "d MMM yyyy HH:mm:ss",
                "d MMM yyyy HH:mm",
                "d MMM yyyy",
                "d MMMM yyyy",
                "MMM d, yyyy HH:mm:ss",
                "MMM d, yyyy",
                "MM/dd/yyyy HH:mm:ss",
                "MM/dd/yyyy HH:mm",
                "MM/dd/yyyy"
            )
        } else {
            listOf(
                "MM/dd/yyyy HH:mm:ss",
                "MM/dd/yyyy HH:mm",
                "MM/dd/yyyy",
                "M/d/yyyy HH:mm:ss",
                "M/d/yyyy HH:mm",
                "M/d/yyyy",
                "MM-dd-yyyy HH:mm:ss",
                "MM-dd-yyyy",
                "MMM d, yyyy HH:mm:ss",
                "MMM d, yyyy",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd",
                "dd/MM/yyyy HH:mm:ss",
                "dd/MM/yyyy"
            )
        }

        // Handle 2-digit years with base 2000
        for (pattern in formatters) {
            try {
                val dtf = DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .appendPattern(pattern)
                    .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
                    .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
                    .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
                    .toFormatter(Locale.US)

                val parsed = dtf.parse(trimmed)
                val year = if (parsed.isSupported(ChronoField.YEAR)) {
                    var y = parsed.getLong(ChronoField.YEAR)
                    if (y in 0..99) y += 2000
                    y.toInt()
                } else 2025

                val month = parsed.get(ChronoField.MONTH_OF_YEAR)
                val day = parsed.get(ChronoField.DAY_OF_MONTH)
                val date = LocalDate.of(year, month, day)

                val epochSec = if (parsed.isSupported(ChronoField.HOUR_OF_DAY) &&
                    (trimmed.contains(":") || trimmed.contains("T"))) {
                    val hour = parsed.get(ChronoField.HOUR_OF_DAY)
                    val min = parsed.get(ChronoField.MINUTE_OF_HOUR)
                    val sec = if (parsed.isSupported(ChronoField.SECOND_OF_MINUTE)) parsed.get(ChronoField.SECOND_OF_MINUTE) else 0
                    LocalDateTime.of(date, java.time.LocalTime.of(hour, min, sec)).toEpochSecond(ZoneOffset.UTC)
                } else null

                return Pair(date.toEpochDay(), epochSec)
            } catch (_: Exception) {}
        }

        // Fallback: extract date component before space or 'T'
        val firstPart = trimmed.substringBefore(' ').substringBefore('T').trim()
        try {
            val ld = LocalDate.parse(firstPart)
            return Pair(ld.toEpochDay(), null)
        } catch (_: Exception) {}

        return null
    }

    // ------------------------------------------------------------------------
    // 5. Amount Parsing Utility
    // ------------------------------------------------------------------------

    fun parseAmountToMinor(raw: String, fractionDigits: Int = 2): Long? {
        var cleaned = raw.trim()
            .replace("$", "")
            .replace("€", "")
            .replace("£", "")
            .replace("₹", "")
            .replace("¥", "")
            .replace("₩", "")
            .replace(" ", "")
            .replace("\u00A0", "")

        val isParen = cleaned.startsWith("(") && cleaned.endsWith(")")
        if (isParen) {
            cleaned = cleaned.substring(1, cleaned.length - 1).trim()
        }

        // Disambiguate European vs US grouping
        // e.g. 1.250,50 vs 1,250.50
        val lastComma = cleaned.lastIndexOf(',')
        val lastDot = cleaned.lastIndexOf('.')
        if (lastComma > lastDot) {
            // European format: 1.250,50 -> remove dots, replace comma with dot
            cleaned = cleaned.replace(".", "").replace(',', '.')
        } else {
            // US format: 1,250.50 -> remove commas
            cleaned = cleaned.replace(",", "")
        }

        if (isParen && !cleaned.startsWith("-")) {
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

    // ------------------------------------------------------------------------
    // 6. Main Competitor CSV Parse Dispatcher
    // ------------------------------------------------------------------------

    fun parseCompetitorCsv(
        reader: BufferedReader,
        headerLine: String,
        preset: CsvImportPreset,
        defaultCurrency: String
    ): CompetitorCsvParseResult {
        val delimiter = detectCsvDelimiter(headerLine)
        val headers = parseCsvLineWithDelimiter(headerLine, delimiter)
        val headerMap = buildFirstWinsHeaderMap(headers)

        // Read all remaining data lines into memory for date order disambiguation & processing
        val rawLines = mutableListOf<String>()
        var record: String? = readNextCsvRecord(reader)
        while (record != null) {
            val clean = record.trim().removePrefix("\uFEFF").removePrefix("ï»¿")
            if (clean.isNotEmpty() && !clean.startsWith("#") && !clean.startsWith("//")) {
                rawLines.add(record)
            }
            record = readNextCsvRecord(reader)
        }

        // Determine date order
        val dateIdx = findDateColumnIndex(headerMap, preset)
        val sampleDates = rawLines.take(100).mapNotNull { raw ->
            val cols = parseCsvLineWithDelimiter(raw, delimiter)
            if (dateIdx in cols.indices) cols[dateIdx] else null
        }
        val defaultOrder = when (preset) {
            CsvImportPreset.INTUIT_MINT, CsvImportPreset.YNAB -> DateOrder.MDY
            CsvImportPreset.MONEFY, CsvImportPreset.MONEY_LOVER, CsvImportPreset.FORTUNE_CITY -> DateOrder.DMY
            else -> DateOrder.DMY
        }
        val dateOrder = disambiguateDateOrder(sampleDates, defaultOrder)

        val rawTransactions = mutableListOf<NormalizedCsvTransaction>()
        var skippedScheduledCount = 0
        val warnings = mutableListOf<String>()

        var lineNum = 1 // 1 is header
        for (rawRow in rawLines) {
            lineNum++
            val cols = parseCsvLineWithDelimiter(rawRow, delimiter)
            if (cols.all { it.isBlank() }) continue

            val result = mapRowToTransaction(
                cols = cols,
                headerMap = headerMap,
                preset = preset,
                lineNum = lineNum,
                dateOrder = dateOrder,
                defaultCurrency = defaultCurrency
            )

            if (result.isSkippedScheduled) {
                skippedScheduledCount++
            } else if (result.warning != null) {
                warnings.add(result.warning)
            } else if (result.transaction != null) {
                rawTransactions.add(result.transaction)
            }
        }

        // Realbyte Money Manager companion transfer-in deduplication
        if (preset == CsvImportPreset.REALBYTE_MONEY_MANAGER) {
            val transferOuts = rawTransactions.filter { it.type == TransactionType.TRANSFER }
            val deduplicated = mutableListOf<NormalizedCsvTransaction>()
            for (tx in rawTransactions) {
                if (tx.isTransferLeg && !tx.isOutflowLeg) {
                    val isCompanion = transferOuts.any { out ->
                        out.epochDay == tx.epochDay &&
                            out.amountMinor == tx.amountMinor &&
                            out.transferToAccountName.equals(tx.accountName, ignoreCase = true)
                    }
                    if (isCompanion) {
                        continue
                    }
                }
                deduplicated.add(tx)
            }
            rawTransactions.clear()
            rawTransactions.addAll(deduplicated)
        }

        // Consolidate transfer pairs
        val consolidated = consolidateTransferPairs(rawTransactions, warnings)

        return CompetitorCsvParseResult(
            resolvedPreset = preset,
            totalRowsRead = rawLines.size,
            transactions = consolidated.transactions,
            consolidatedTransferCount = consolidated.consolidatedCount,
            skippedScheduledCount = skippedScheduledCount,
            warnings = warnings
        )
    }

    private fun findDateColumnIndex(headerMap: Map<String, Int>, preset: CsvImportPreset): Int {
        return headerMap["date"]
            ?: headerMap["period"]
            ?: headerMap["time"]
            ?: headerMap["datetime"]
            ?: headerMap["transactiondate"]
            ?: 0
    }

    // ------------------------------------------------------------------------
    // 7. Per-Preset Row Mapper
    // ------------------------------------------------------------------------

    data class RowMapResult(
        val transaction: NormalizedCsvTransaction? = null,
        val isSkippedScheduled: Boolean = false,
        val warning: String? = null
    )

    private fun mapRowToTransaction(
        cols: List<String>,
        headerMap: Map<String, Int>,
        preset: CsvImportPreset,
        lineNum: Int,
        dateOrder: DateOrder,
        defaultCurrency: String
    ): RowMapResult {
        fun col(key: String): String = headerMap[key]?.let { idx ->
            if (idx in cols.indices) cols[idx].trim() else ""
        } ?: ""

        fun colByIdx(idx: Int): String = if (idx in cols.indices) cols[idx].trim() else ""

        return when (preset) {
            CsvImportPreset.IVY_WALLET -> mapIvyWalletRow(cols, headerMap, lineNum, dateOrder, defaultCurrency)
            CsvImportPreset.CASHEW -> mapCashewRow(cols, headerMap, lineNum, dateOrder, defaultCurrency)
            CsvImportPreset.REALBYTE_MONEY_MANAGER -> mapRealbyteRow(cols, headerMap, lineNum, dateOrder, defaultCurrency)
            CsvImportPreset.WALLET_BUDGETBAKERS -> mapBudgetBakersRow(cols, headerMap, lineNum, dateOrder, defaultCurrency)
            CsvImportPreset.MONEY_LOVER -> mapMoneyLoverRow(cols, headerMap, lineNum, dateOrder, defaultCurrency)
            CsvImportPreset.YNAB -> mapYnabRow(cols, headerMap, lineNum, dateOrder, defaultCurrency)
            CsvImportPreset.INTUIT_MINT -> mapMintRow(cols, headerMap, lineNum, dateOrder, defaultCurrency)
            CsvImportPreset.SPENDEE -> mapSpendeeRow(cols, headerMap, lineNum, dateOrder, defaultCurrency)
            CsvImportPreset.MONEFY -> mapMonefyRow(cols, headerMap, lineNum, dateOrder, defaultCurrency)
            CsvImportPreset.ONE_MONEY -> mapOneMoneyRow(cols, headerMap, lineNum, dateOrder, defaultCurrency)
            CsvImportPreset.BLUECOINS -> mapBluecoinsRow(cols, headerMap, lineNum, dateOrder, defaultCurrency)
            CsvImportPreset.FINANCISTO -> mapFinancistoRow(cols, headerMap, lineNum, dateOrder, defaultCurrency)
            CsvImportPreset.KTW_MONEY_MANAGER -> mapKtwRow(cols, headerMap, lineNum, dateOrder, defaultCurrency)
            CsvImportPreset.FORTUNE_CITY -> mapFortuneCityRow(cols, headerMap, lineNum, dateOrder, defaultCurrency)
            else -> RowMapResult()
        }
    }

    // --- Ivy Wallet ---
    private fun mapIvyWalletRow(
        cols: List<String>,
        h: Map<String, Int>,
        lineNum: Int,
        dateOrder: DateOrder,
        defaultCurrency: String
    ): RowMapResult {
        val rawDate = h["date"]?.let { cols.getOrNull(it)?.trim() } ?: cols.getOrNull(1)?.trim() ?: ""
        val rawDueDate = h["duedate"]?.let { cols.getOrNull(it)?.trim() } ?: ""

        // If Date is blank AND Due Date is non-blank -> scheduled unpaid transaction, skip!
        if (rawDate.isBlank() && rawDueDate.isNotBlank()) {
            return RowMapResult(isSkippedScheduled = true)
        }

        val dateResult = parseDateAndSeconds(rawDate, dateOrder)
            ?: return RowMapResult(warning = "Row $lineNum: Invalid or missing date '$rawDate'")

        val rawType = (h["type"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(2) ?: "").trim().uppercase(Locale.US)
        val title = (h["title"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(5) ?: "").trim()
        val description = (h["description"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(6) ?: "").trim()
        val account = (h["account"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(3) ?: "").trim().ifBlank { "Ivy Account" }
        val currency = (h["currency"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(4) ?: defaultCurrency).trim().uppercase(Locale.US)
        val rawCategory = (h["category"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(7) ?: "").trim()

        if (rawType == "TRANSFER") {
            val toAccount = (h["toaccount"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(9) ?: "").trim()
            val rawTransferAmt = h["transferamount"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(7) ?: ""
            val rawReceiveAmt = h["receiveamount"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(10) ?: ""
            val transferCurrency = (h["transfercurrency"]?.let { cols.getOrNull(it) } ?: currency).trim().uppercase(Locale.US)
            val receiveCurrency = (h["receivecurrency"]?.let { cols.getOrNull(it) } ?: transferCurrency).trim().uppercase(Locale.US)

            val transferMinor = parseAmountToMinor(rawTransferAmt) ?: 0L
            val receiveMinor = parseAmountToMinor(rawReceiveAmt) ?: 0L

            if (transferCurrency != receiveCurrency) {
                // Cross-currency transfer
                return RowMapResult(warning = "Row $lineNum: Skipped cross-currency transfer ($transferCurrency -> $receiveCurrency)")
            }

            val finalAmount: Long
            val feeMinor: Long
            if (transferMinor > receiveMinor && receiveMinor > 0L) {
                finalAmount = receiveMinor
                feeMinor = transferMinor - receiveMinor
            } else {
                finalAmount = transferMinor
                feeMinor = 0L
            }

            if (finalAmount <= 0L) {
                return RowMapResult(warning = "Row $lineNum: Skipped zero/rounded amount")
            }

            return RowMapResult(
                transaction = NormalizedCsvTransaction(
                    lineNum = lineNum,
                    epochDay = dateResult.first,
                    rawDate = rawDate,
                    type = TransactionType.TRANSFER,
                    amountMinor = finalAmount,
                    currencyCode = transferCurrency,
                    accountName = account,
                    transferToAccountName = toAccount,
                    transferFeeMinor = feeMinor,
                    categoryName = null,
                    payeeOrDescription = title,
                    note = description,
                    rawAmountForDisplay = rawTransferAmt,
                    localDateTimeEpochSeconds = dateResult.second
                )
            )
        }

        // Non-transfer row
        val rawAmount = (h["amount"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(4) ?: "").trim()
        val amountMinor = parseAmountToMinor(rawAmount) ?: 0L
        if (amountMinor == 0L) {
            return RowMapResult(warning = "Row $lineNum: Skipped zero/rounded amount")
        }

        val absAmount = abs(amountMinor)

        // Reconciliation check: Title == "Adjust balance" and Category is blank
        if (title.equals("Adjust balance", ignoreCase = true) && rawCategory.isBlank()) {
            val isOutflow = rawType == "EXPENSE" || amountMinor < 0L
            return RowMapResult(
                transaction = NormalizedCsvTransaction(
                    lineNum = lineNum,
                    epochDay = dateResult.first,
                    rawDate = rawDate,
                    type = TransactionType.RECONCILIATION,
                    amountMinor = absAmount,
                    isOutflowReconciliation = isOutflow,
                    currencyCode = currency,
                    accountName = account,
                    categoryName = null,
                    payeeOrDescription = title,
                    note = description,
                    rawAmountForDisplay = rawAmount,
                    localDateTimeEpochSeconds = dateResult.second
                )
            )
        }

        val type = if (rawType == "INCOME" || amountMinor > 0L && rawType != "EXPENSE") {
            TransactionType.INCOME
        } else {
            TransactionType.EXPENSE
        }

        return RowMapResult(
            transaction = NormalizedCsvTransaction(
                lineNum = lineNum,
                epochDay = dateResult.first,
                rawDate = rawDate,
                type = type,
                amountMinor = absAmount,
                currencyCode = currency,
                accountName = account,
                categoryName = rawCategory.ifBlank { null },
                payeeOrDescription = title,
                note = description,
                rawAmountForDisplay = rawAmount,
                localDateTimeEpochSeconds = dateResult.second
            )
        )
    }

    // --- Cashew ---
    private fun mapCashewRow(
        cols: List<String>,
        h: Map<String, Int>,
        lineNum: Int,
        dateOrder: DateOrder,
        defaultCurrency: String
    ): RowMapResult {
        fun col(key: String): String = h[key]?.let { idx -> if (idx in cols.indices) cols[idx].trim() else "" } ?: ""

        val rawDate = col("date").ifBlank { cols.getOrNull(0) ?: "" }
        val dateResult = parseDateAndSeconds(rawDate, dateOrder)
            ?: return RowMapResult(warning = "Row $lineNum: Invalid date '$rawDate'")

        val rawAmount = col("amount").ifBlank { cols.getOrNull(3) ?: "0" }
        val amountMinor = parseAmountToMinor(rawAmount) ?: 0L
        if (amountMinor == 0L) return RowMapResult(warning = "Row $lineNum: Skipped zero amount")

        val account = col("account").ifBlank { cols.getOrNull(2) ?: "Cashew Account" }
        val category = col("categoryname").ifBlank { col("category") }
        val subCategory = col("subcategoryname").ifBlank { col("subcategory") }
        val title = col("title").ifBlank { col("payee") }
        val note = col("note")
        val currency = col("currency").ifBlank { defaultCurrency }.uppercase(Locale.US)
        val icon = col("iconname").ifBlank { col("icon") }.lowercase(Locale.US)
        val color = col("color").lowercase(Locale.US)

        val isBalanceCorrection = category.equals("Balance Correction", ignoreCase = true) ||
            (icon.contains("charts") && color.contains("607d8b"))

        val isOutflow = amountMinor < 0L
        val absAmount = abs(amountMinor)

        if (isBalanceCorrection) {
            val isTransferCandidate = note.contains("→") || note.contains("←") ||
                title.contains("Transfer", ignoreCase = true) ||
                isBalanceCorrection
            return RowMapResult(
                transaction = NormalizedCsvTransaction(
                    lineNum = lineNum,
                    epochDay = dateResult.first,
                    rawDate = rawDate,
                    type = if (isOutflow) TransactionType.EXPENSE else TransactionType.INCOME,
                    amountMinor = absAmount,
                    currencyCode = currency,
                    accountName = account,
                    categoryName = null,
                    subCategoryName = null,
                    payeeOrDescription = title,
                    note = note,
                    rawAmountForDisplay = rawAmount,
                    localDateTimeEpochSeconds = dateResult.second,
                    isTransferLeg = isTransferCandidate,
                    isOutflowLeg = isOutflow,
                    isCashewBalanceCorrection = true
                )
            )
        }

        val isIncomeCol = col("income").lowercase(Locale.US)
        val isIncome = isIncomeCol == "true" || isIncomeCol == "1" || amountMinor > 0L

        return RowMapResult(
            transaction = NormalizedCsvTransaction(
                lineNum = lineNum,
                epochDay = dateResult.first,
                rawDate = rawDate,
                type = if (isIncome) TransactionType.INCOME else TransactionType.EXPENSE,
                amountMinor = absAmount,
                currencyCode = currency,
                accountName = account,
                categoryName = category.ifBlank { null },
                subCategoryName = subCategory.ifBlank { null },
                payeeOrDescription = title,
                note = note,
                rawAmountForDisplay = rawAmount,
                localDateTimeEpochSeconds = dateResult.second
            )
        )
    }

    // --- Realbyte Money Manager ---
    private fun mapRealbyteRow(
        cols: List<String>,
        h: Map<String, Int>,
        lineNum: Int,
        dateOrder: DateOrder,
        defaultCurrency: String
    ): RowMapResult {
        fun col(key: String): String = h[key]?.let { idx -> if (idx in cols.indices) cols[idx].trim() else "" } ?: ""

        val rawDate = col("date").ifBlank { col("period") }.ifBlank { cols.getOrNull(0) ?: "" }
        val dateResult = parseDateAndSeconds(rawDate, dateOrder)
            ?: return RowMapResult(warning = "Row $lineNum: Invalid date '$rawDate'")

        val account = col("account").ifBlank { col("accounts") }.ifBlank { cols.getOrNull(1) ?: "Default Account" }
        val category = col("category").ifBlank { cols.getOrNull(2) ?: "" }
        val subCategory = col("subcategory").ifBlank { cols.getOrNull(3) ?: "" }
        val note = col("note").ifBlank { cols.getOrNull(4) ?: "" } // Payee
        val description = col("description").ifBlank { cols.getOrNull(5) ?: "" }
        val incExp = col("incomeexpense").ifBlank { cols.getOrNull(6) ?: "" }.lowercase(Locale.US)
        val rawAmount = col("amount").ifBlank { cols.getOrNull(8) ?: cols.getOrNull(7) ?: "" }
        val currency = col("currency").ifBlank { defaultCurrency }.uppercase(Locale.US)

        val amountMinor = parseAmountToMinor(rawAmount) ?: 0L
        if (amountMinor == 0L) return RowMapResult(warning = "Row $lineNum: Skipped zero amount")
        val absAmount = abs(amountMinor)

        if (incExp.contains("transfer-out")) {
            // Realbyte single-row transfer: destination account is in the Category column!
            return RowMapResult(
                transaction = NormalizedCsvTransaction(
                    lineNum = lineNum,
                    epochDay = dateResult.first,
                    rawDate = rawDate,
                    type = TransactionType.TRANSFER,
                    amountMinor = absAmount,
                    currencyCode = currency,
                    accountName = account,
                    transferToAccountName = category.ifBlank { "Destination Account" },
                    categoryName = null,
                    subCategoryName = null,
                    payeeOrDescription = note,
                    note = description,
                    rawAmountForDisplay = rawAmount,
                    localDateTimeEpochSeconds = dateResult.second
                )
            )
        }

        if (incExp.contains("transfer-in")) {
            // Companion transfer-in leg; flag as transfer leg for deduplication
            return RowMapResult(
                transaction = NormalizedCsvTransaction(
                    lineNum = lineNum,
                    epochDay = dateResult.first,
                    rawDate = rawDate,
                    type = TransactionType.INCOME,
                    amountMinor = absAmount,
                    currencyCode = currency,
                    accountName = account,
                    categoryName = null,
                    payeeOrDescription = note,
                    note = description,
                    rawAmountForDisplay = rawAmount,
                    localDateTimeEpochSeconds = dateResult.second,
                    isTransferLeg = true,
                    isOutflowLeg = false
                )
            )
        }

        val type = if (incExp.contains("income") || amountMinor > 0L) TransactionType.INCOME else TransactionType.EXPENSE

        return RowMapResult(
            transaction = NormalizedCsvTransaction(
                lineNum = lineNum,
                epochDay = dateResult.first,
                rawDate = rawDate,
                type = type,
                amountMinor = absAmount,
                currencyCode = currency,
                accountName = account,
                categoryName = category.ifBlank { null },
                subCategoryName = subCategory.ifBlank { null },
                payeeOrDescription = note,
                note = description,
                rawAmountForDisplay = rawAmount,
                localDateTimeEpochSeconds = dateResult.second
            )
        )
    }

    // --- Wallet by BudgetBakers ---
    private fun mapBudgetBakersRow(
        cols: List<String>,
        h: Map<String, Int>,
        lineNum: Int,
        dateOrder: DateOrder,
        defaultCurrency: String
    ): RowMapResult {
        fun col(key: String): String = h[key]?.let { idx -> if (idx in cols.indices) cols[idx].trim() else "" } ?: ""

        val rawDate = col("date").ifBlank { cols.getOrNull(0) ?: "" }
        val dateResult = parseDateAndSeconds(rawDate, dateOrder)
            ?: return RowMapResult(warning = "Row $lineNum: Invalid date '$rawDate'")

        val account = col("account").ifBlank { "Wallet Account" }
        val category = col("category")
        val rawAmount = col("amount")
        val currency = col("currency").ifBlank { defaultCurrency }.uppercase(Locale.US)
        val rawType = col("type").lowercase(Locale.US)
        val note = col("note")
        val payee = col("payee")
        val isTransfer = col("transfer").equals("true", ignoreCase = true)
        val labels = col("labels")
        val tags = if (labels.isNotBlank()) labels.split(Regex("[|,;]")).map { it.trim() }.filter { it.isNotEmpty() } else emptyList()

        val amountMinor = parseAmountToMinor(rawAmount) ?: 0L
        if (amountMinor == 0L) return RowMapResult(warning = "Row $lineNum: Skipped zero amount")
        val absAmount = abs(amountMinor)
        val isOutflow = rawType.contains("expense") || amountMinor < 0L

        if (isTransfer) {
            return RowMapResult(
                transaction = NormalizedCsvTransaction(
                    lineNum = lineNum,
                    epochDay = dateResult.first,
                    rawDate = rawDate,
                    type = if (isOutflow) TransactionType.EXPENSE else TransactionType.INCOME,
                    amountMinor = absAmount,
                    currencyCode = currency,
                    accountName = account,
                    categoryName = null,
                    payeeOrDescription = payee,
                    note = note,
                    tags = tags,
                    rawAmountForDisplay = rawAmount,
                    localDateTimeEpochSeconds = dateResult.second,
                    isTransferLeg = true,
                    isOutflowLeg = isOutflow
                )
            )
        }

        return RowMapResult(
            transaction = NormalizedCsvTransaction(
                lineNum = lineNum,
                epochDay = dateResult.first,
                rawDate = rawDate,
                type = if (isOutflow) TransactionType.EXPENSE else TransactionType.INCOME,
                amountMinor = absAmount,
                currencyCode = currency,
                accountName = account,
                categoryName = category.ifBlank { null },
                payeeOrDescription = payee,
                note = note,
                tags = tags,
                rawAmountForDisplay = rawAmount,
                localDateTimeEpochSeconds = dateResult.second
            )
        )
    }

    // --- Money Lover ---
    private fun mapMoneyLoverRow(
        cols: List<String>,
        h: Map<String, Int>,
        lineNum: Int,
        dateOrder: DateOrder,
        defaultCurrency: String
    ): RowMapResult {
        fun col(key: String): String = h[key]?.let { idx -> if (idx in cols.indices) cols[idx].trim() else "" } ?: ""

        val rawDate = col("date").ifBlank { cols.getOrNull(0) ?: "" }
        val dateResult = parseDateAndSeconds(rawDate, dateOrder)
            ?: return RowMapResult(warning = "Row $lineNum: Invalid date '$rawDate'")

        val account = col("wallet").ifBlank { col("account") }.ifBlank { "Money Lover Wallet" }
        val category = col("category")
        val rawAmount = col("amount")
        val currency = col("currency").ifBlank { defaultCurrency }.uppercase(Locale.US)
        val note = col("note")
        val event = col("event")
        val tags = if (event.isNotBlank()) listOf(event.trim()) else emptyList()

        val amountMinor = parseAmountToMinor(rawAmount) ?: 0L
        if (amountMinor == 0L) return RowMapResult(warning = "Row $lineNum: Skipped zero amount")
        val absAmount = abs(amountMinor)
        val isOutflow = amountMinor < 0L

        val isTransferOut = category.equals("Outgoing transfer", ignoreCase = true)
        val isTransferIn = category.equals("Incoming transfer", ignoreCase = true)

        if (isTransferOut || isTransferIn) {
            return RowMapResult(
                transaction = NormalizedCsvTransaction(
                    lineNum = lineNum,
                    epochDay = dateResult.first,
                    rawDate = rawDate,
                    type = if (isTransferOut) TransactionType.EXPENSE else TransactionType.INCOME,
                    amountMinor = absAmount,
                    currencyCode = currency,
                    accountName = account,
                    categoryName = null,
                    payeeOrDescription = note,
                    tags = tags,
                    rawAmountForDisplay = rawAmount,
                    localDateTimeEpochSeconds = dateResult.second,
                    isTransferLeg = true,
                    isOutflowLeg = isTransferOut
                )
            )
        }

        return RowMapResult(
            transaction = NormalizedCsvTransaction(
                lineNum = lineNum,
                epochDay = dateResult.first,
                rawDate = rawDate,
                type = if (isOutflow) TransactionType.EXPENSE else TransactionType.INCOME,
                amountMinor = absAmount,
                currencyCode = currency,
                accountName = account,
                categoryName = category.ifBlank { null },
                payeeOrDescription = note,
                tags = tags,
                rawAmountForDisplay = rawAmount,
                localDateTimeEpochSeconds = dateResult.second
            )
        )
    }

    // --- YNAB ---
    private fun mapYnabRow(
        cols: List<String>,
        h: Map<String, Int>,
        lineNum: Int,
        dateOrder: DateOrder,
        defaultCurrency: String
    ): RowMapResult {
        fun col(key: String): String = h[key]?.let { idx -> if (idx in cols.indices) cols[idx].trim() else "" } ?: ""

        val rawDate = col("date").ifBlank { cols.getOrNull(1) ?: "" }
        val dateResult = parseDateAndSeconds(rawDate, dateOrder)
            ?: return RowMapResult(warning = "Row $lineNum: Invalid date '$rawDate'")

        val account = col("account").ifBlank { "YNAB Account" }
        val payee = col("payee")
        val categoryGroup = col("categorygroup").ifBlank { col("categorygroupcategory") }
        val category = col("category")
        val memo = col("memo")
        val outflowStr = col("outflow")
        val inflowStr = col("inflow")

        val outflowMinor = parseAmountToMinor(outflowStr) ?: 0L
        val inflowMinor = parseAmountToMinor(inflowStr) ?: 0L

        if (outflowMinor == 0L && inflowMinor == 0L) {
            return RowMapResult(warning = "Row $lineNum: Skipped zero amount")
        }

        val isOutflow = outflowMinor > 0L
        val amountMinor = if (isOutflow) outflowMinor else inflowMinor

        // Reconciliation
        if (payee.equals("Starting Balance", ignoreCase = true) ||
            payee.equals("Reconciliation Balance Adjustment", ignoreCase = true)) {
            return RowMapResult(
                transaction = NormalizedCsvTransaction(
                    lineNum = lineNum,
                    epochDay = dateResult.first,
                    rawDate = rawDate,
                    type = TransactionType.RECONCILIATION,
                    amountMinor = amountMinor,
                    isOutflowReconciliation = isOutflow,
                    currencyCode = defaultCurrency,
                    accountName = account,
                    categoryName = null,
                    payeeOrDescription = payee,
                    note = memo,
                    rawAmountForDisplay = if (isOutflow) outflowStr else inflowStr,
                    localDateTimeEpochSeconds = dateResult.second
                )
            )
        }

        // Transfer: Payee starts with "Transfer : "
        if (payee.startsWith("Transfer : ", ignoreCase = true) || payee.startsWith("Transfer:", ignoreCase = true)) {
            val targetAccount = payee.substringAfter(":").trim()
            return RowMapResult(
                transaction = NormalizedCsvTransaction(
                    lineNum = lineNum,
                    epochDay = dateResult.first,
                    rawDate = rawDate,
                    type = if (isOutflow) TransactionType.EXPENSE else TransactionType.INCOME,
                    amountMinor = amountMinor,
                    currencyCode = defaultCurrency,
                    accountName = account,
                    transferToAccountName = targetAccount.ifBlank { null },
                    categoryName = null,
                    payeeOrDescription = payee,
                    note = memo,
                    rawAmountForDisplay = if (isOutflow) outflowStr else inflowStr,
                    localDateTimeEpochSeconds = dateResult.second,
                    isTransferLeg = true,
                    isOutflowLeg = isOutflow
                )
            )
        }

        val catName = if (categoryGroup.contains("Inflow", ignoreCase = true) || category.contains("Ready to Assign", ignoreCase = true)) {
            null
        } else {
            categoryGroup.ifBlank { category }.ifBlank { null }
        }

        val subCatName = if (categoryGroup.isNotBlank() && category.isNotBlank() && catName != null) category else null

        return RowMapResult(
            transaction = NormalizedCsvTransaction(
                lineNum = lineNum,
                epochDay = dateResult.first,
                rawDate = rawDate,
                type = if (isOutflow) TransactionType.EXPENSE else TransactionType.INCOME,
                amountMinor = amountMinor,
                currencyCode = defaultCurrency,
                accountName = account,
                categoryName = catName,
                subCategoryName = subCatName,
                payeeOrDescription = payee,
                note = memo,
                rawAmountForDisplay = if (isOutflow) outflowStr else inflowStr,
                localDateTimeEpochSeconds = dateResult.second
            )
        )
    }

    // --- Intuit Mint ---
    private fun mapMintRow(
        cols: List<String>,
        h: Map<String, Int>,
        lineNum: Int,
        dateOrder: DateOrder,
        defaultCurrency: String
    ): RowMapResult {
        fun col(key: String): String = h[key]?.let { idx -> if (idx in cols.indices) cols[idx].trim() else "" } ?: ""

        val rawDate = col("date").ifBlank { cols.getOrNull(0) ?: "" }
        val dateResult = parseDateAndSeconds(rawDate, dateOrder)
            ?: return RowMapResult(warning = "Row $lineNum: Invalid date '$rawDate'")

        val description = col("description").ifBlank { col("originaldescription") }
        val rawAmount = col("amount")
        val txType = col("transactiontype").lowercase(Locale.US)
        val category = col("category")
        val account = col("accountname").ifBlank { "Mint Account" }
        val labels = col("labels")
        val notes = col("notes")
        val tags = if (labels.isNotBlank()) labels.split(",").map { it.trim() }.filter { it.isNotEmpty() } else emptyList()

        val amountMinor = parseAmountToMinor(rawAmount) ?: 0L
        if (amountMinor == 0L) return RowMapResult(warning = "Row $lineNum: Skipped zero amount")
        val absAmount = abs(amountMinor)
        val isCredit = txType == "credit"

        return RowMapResult(
            transaction = NormalizedCsvTransaction(
                lineNum = lineNum,
                epochDay = dateResult.first,
                rawDate = rawDate,
                type = if (isCredit) TransactionType.INCOME else TransactionType.EXPENSE,
                amountMinor = absAmount,
                currencyCode = defaultCurrency,
                accountName = account,
                categoryName = category.ifBlank { null },
                payeeOrDescription = description,
                note = notes,
                tags = tags,
                rawAmountForDisplay = rawAmount,
                localDateTimeEpochSeconds = dateResult.second
            )
        )
    }

    // --- Spendee ---
    private fun mapSpendeeRow(
        cols: List<String>,
        h: Map<String, Int>,
        lineNum: Int,
        dateOrder: DateOrder,
        defaultCurrency: String
    ): RowMapResult {
        fun col(key: String): String = h[key]?.let { idx -> if (idx in cols.indices) cols[idx].trim() else "" } ?: ""

        val rawDate = col("date").ifBlank { cols.getOrNull(0) ?: "" }
        val dateResult = parseDateAndSeconds(rawDate, dateOrder)
            ?: return RowMapResult(warning = "Row $lineNum: Invalid date '$rawDate'")

        val account = col("wallet").ifBlank { col("account") }.ifBlank { "Spendee Wallet" }
        val rawType = col("type").lowercase(Locale.US)
        val category = col("categoryname").ifBlank { col("category") }
        val rawAmount = col("amount")
        val currency = col("currency").ifBlank { defaultCurrency }.uppercase(Locale.US)
        val note = col("note")

        val amountMinor = parseAmountToMinor(rawAmount) ?: 0L
        if (amountMinor == 0L) return RowMapResult(warning = "Row $lineNum: Skipped zero amount")
        val absAmount = abs(amountMinor)
        val isOutflow = rawType.contains("expense") || amountMinor < 0L

        if (rawType.contains("transfer")) {
            return RowMapResult(
                transaction = NormalizedCsvTransaction(
                    lineNum = lineNum,
                    epochDay = dateResult.first,
                    rawDate = rawDate,
                    type = if (isOutflow) TransactionType.EXPENSE else TransactionType.INCOME,
                    amountMinor = absAmount,
                    currencyCode = currency,
                    accountName = account,
                    categoryName = null,
                    payeeOrDescription = note,
                    rawAmountForDisplay = rawAmount,
                    localDateTimeEpochSeconds = dateResult.second,
                    isTransferLeg = true,
                    isOutflowLeg = isOutflow
                )
            )
        }

        return RowMapResult(
            transaction = NormalizedCsvTransaction(
                lineNum = lineNum,
                epochDay = dateResult.first,
                rawDate = rawDate,
                type = if (isOutflow) TransactionType.EXPENSE else TransactionType.INCOME,
                amountMinor = absAmount,
                currencyCode = currency,
                accountName = account,
                categoryName = category.ifBlank { null },
                payeeOrDescription = note,
                rawAmountForDisplay = rawAmount,
                localDateTimeEpochSeconds = dateResult.second
            )
        )
    }

    // --- Monefy ---
    private fun mapMonefyRow(
        cols: List<String>,
        h: Map<String, Int>,
        lineNum: Int,
        dateOrder: DateOrder,
        defaultCurrency: String
    ): RowMapResult {
        // Monefy: Date, Account, Category, Amount, Currency, Converted Amount, Converted Currency, Description
        val rawDate = h["date"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(0) ?: ""
        val dateResult = parseDateAndSeconds(rawDate, dateOrder)
            ?: return RowMapResult(warning = "Row $lineNum: Invalid date '$rawDate'")

        val account = (h["account"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(1) ?: "Monefy Account").trim()
        val category = (h["category"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(2) ?: "").trim()
        val rawAmount = (h["amount"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(3) ?: "").trim()
        val currency = (h["currency"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(4) ?: defaultCurrency).trim().uppercase(Locale.US)
        val description = (h["description"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(7) ?: "").trim()

        val amountMinor = parseAmountToMinor(rawAmount) ?: 0L
        if (amountMinor == 0L) return RowMapResult(warning = "Row $lineNum: Skipped zero amount")
        val absAmount = abs(amountMinor)
        val isIncome = amountMinor > 0L

        return RowMapResult(
            transaction = NormalizedCsvTransaction(
                lineNum = lineNum,
                epochDay = dateResult.first,
                rawDate = rawDate,
                type = if (isIncome) TransactionType.INCOME else TransactionType.EXPENSE,
                amountMinor = absAmount,
                currencyCode = currency,
                accountName = account,
                categoryName = category.ifBlank { null },
                payeeOrDescription = description,
                rawAmountForDisplay = rawAmount,
                localDateTimeEpochSeconds = dateResult.second
            )
        )
    }

    // --- 1Money ---
    private fun mapOneMoneyRow(
        cols: List<String>,
        h: Map<String, Int>,
        lineNum: Int,
        dateOrder: DateOrder,
        defaultCurrency: String
    ): RowMapResult {
        fun col(key: String): String = h[key]?.let { idx -> if (idx in cols.indices) cols[idx].trim() else "" } ?: ""

        val rawDate = col("date").ifBlank { cols.getOrNull(0) ?: "" }
        val dateResult = parseDateAndSeconds(rawDate, dateOrder)
            ?: return RowMapResult(warning = "Row $lineNum: Invalid date '$rawDate'")

        val rawType = col("type").uppercase(Locale.US)
        val fromAccount = col("fromaccount").ifBlank { "1Money Account" }
        val toAccountOrCat = col("toaccountcategory").ifBlank { col("toaccount") }.ifBlank { col("category") }
        val rawAmount = col("amount")
        val currency = col("currency").ifBlank { defaultCurrency }.uppercase(Locale.US)
        val note = col("note")

        val amountMinor = parseAmountToMinor(rawAmount) ?: 0L
        if (amountMinor == 0L) return RowMapResult(warning = "Row $lineNum: Skipped zero amount")
        val absAmount = abs(amountMinor)

        if (rawType == "TRANSFER") {
            return RowMapResult(
                transaction = NormalizedCsvTransaction(
                    lineNum = lineNum,
                    epochDay = dateResult.first,
                    rawDate = rawDate,
                    type = TransactionType.TRANSFER,
                    amountMinor = absAmount,
                    currencyCode = currency,
                    accountName = fromAccount,
                    transferToAccountName = toAccountOrCat.ifBlank { "Destination Account" },
                    categoryName = null,
                    payeeOrDescription = note,
                    rawAmountForDisplay = rawAmount,
                    localDateTimeEpochSeconds = dateResult.second
                )
            )
        }

        val isIncome = if (rawType.isNotBlank()) {
            rawType == "INCOME"
        } else {
            amountMinor > 0L
        }

        return RowMapResult(
            transaction = NormalizedCsvTransaction(
                lineNum = lineNum,
                epochDay = dateResult.first,
                rawDate = rawDate,
                type = if (isIncome) TransactionType.INCOME else TransactionType.EXPENSE,
                amountMinor = absAmount,
                currencyCode = currency,
                accountName = fromAccount,
                categoryName = toAccountOrCat.ifBlank { null },
                payeeOrDescription = note,
                rawAmountForDisplay = rawAmount,
                localDateTimeEpochSeconds = dateResult.second
            )
        )
    }

    // --- Bluecoins ---
    private fun mapBluecoinsRow(
        cols: List<String>,
        h: Map<String, Int>,
        lineNum: Int,
        dateOrder: DateOrder,
        defaultCurrency: String
    ): RowMapResult {
        fun col(key: String): String = h[key]?.let { idx -> if (idx in cols.indices) cols[idx].trim() else "" } ?: ""

        val rawDate = col("date").ifBlank { cols.getOrNull(0) ?: "" }
        val dateResult = parseDateAndSeconds(rawDate, dateOrder)
            ?: return RowMapResult(warning = "Row $lineNum: Invalid date '$rawDate'")

        val rawType = col("type").lowercase(Locale.US)
        val rawAmount = col("amount")
        val categoryGroup = col("categorygroup")
        val category = col("category")
        val account = col("account").ifBlank { "Bluecoins Account" }
        val notes = col("notes")
        val tagsStr = col("tags")
        val tags = if (tagsStr.isNotBlank()) tagsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() } else emptyList()

        val amountMinor = parseAmountToMinor(rawAmount) ?: 0L
        if (amountMinor == 0L) return RowMapResult(warning = "Row $lineNum: Skipped zero amount")
        val absAmount = abs(amountMinor)
        val isOutflow = rawType.contains("expense") || amountMinor < 0L

        if (rawType.contains("transfer")) {
            return RowMapResult(
                transaction = NormalizedCsvTransaction(
                    lineNum = lineNum,
                    epochDay = dateResult.first,
                    rawDate = rawDate,
                    type = if (isOutflow) TransactionType.EXPENSE else TransactionType.INCOME,
                    amountMinor = absAmount,
                    currencyCode = defaultCurrency,
                    accountName = account,
                    categoryName = null,
                    payeeOrDescription = notes,
                    tags = tags,
                    rawAmountForDisplay = rawAmount,
                    localDateTimeEpochSeconds = dateResult.second,
                    isTransferLeg = true,
                    isOutflowLeg = isOutflow
                )
            )
        }

        return RowMapResult(
            transaction = NormalizedCsvTransaction(
                lineNum = lineNum,
                epochDay = dateResult.first,
                rawDate = rawDate,
                type = if (isOutflow) TransactionType.EXPENSE else TransactionType.INCOME,
                amountMinor = absAmount,
                currencyCode = defaultCurrency,
                accountName = account,
                categoryName = category.ifBlank { categoryGroup }.ifBlank { null },
                subCategoryName = if (categoryGroup.isNotBlank() && category.isNotBlank()) category else null,
                payeeOrDescription = notes,
                tags = tags,
                rawAmountForDisplay = rawAmount,
                localDateTimeEpochSeconds = dateResult.second
            )
        )
    }

    // --- Financisto ---
    private fun mapFinancistoRow(
        cols: List<String>,
        h: Map<String, Int>,
        lineNum: Int,
        dateOrder: DateOrder,
        defaultCurrency: String
    ): RowMapResult {
        // Financisto: date, time, account, amount, currency, category, payee, note, ...
        val rawDatePart = h["date"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(0) ?: ""
        val rawTimePart = h["time"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(1) ?: ""
        val combinedDate = if (rawTimePart.isNotBlank()) "$rawDatePart $rawTimePart" else rawDatePart

        val dateResult = parseDateAndSeconds(combinedDate, dateOrder)
            ?: return RowMapResult(warning = "Row $lineNum: Invalid date '$combinedDate'")

        val account = (h["account"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(2) ?: "Financisto Account").trim()
        val rawAmount = (h["originalamount"]?.let { cols.getOrNull(it) } ?: h["amount"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(3) ?: "").trim()
        val currency = (h["currency"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(4) ?: defaultCurrency).trim().uppercase(Locale.US)
        val category = (h["category"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(5) ?: "").trim()
        val payee = (h["payee"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(6) ?: "").trim()
        val note = (h["note"]?.let { cols.getOrNull(it) } ?: cols.getOrNull(7) ?: "").trim()
        val isTransfer = category.contains("transfer", ignoreCase = true) || payee.contains("transfer", ignoreCase = true)

        val amountMinor = parseAmountToMinor(rawAmount) ?: 0L
        if (amountMinor == 0L) return RowMapResult(warning = "Row $lineNum: Skipped zero amount")
        val absAmount = abs(amountMinor)
        val isOutflow = amountMinor < 0L

        if (isTransfer) {
            return RowMapResult(
                transaction = NormalizedCsvTransaction(
                    lineNum = lineNum,
                    epochDay = dateResult.first,
                    rawDate = combinedDate,
                    type = if (isOutflow) TransactionType.EXPENSE else TransactionType.INCOME,
                    amountMinor = absAmount,
                    currencyCode = currency,
                    accountName = account,
                    categoryName = null,
                    payeeOrDescription = payee,
                    note = note,
                    rawAmountForDisplay = rawAmount,
                    localDateTimeEpochSeconds = dateResult.second,
                    isTransferLeg = true,
                    isOutflowLeg = isOutflow
                )
            )
        }

        return RowMapResult(
            transaction = NormalizedCsvTransaction(
                lineNum = lineNum,
                epochDay = dateResult.first,
                rawDate = combinedDate,
                type = if (isOutflow) TransactionType.EXPENSE else TransactionType.INCOME,
                amountMinor = absAmount,
                currencyCode = currency,
                accountName = account,
                categoryName = category.ifBlank { null },
                payeeOrDescription = payee,
                note = note,
                rawAmountForDisplay = rawAmount,
                localDateTimeEpochSeconds = dateResult.second
            )
        )
    }

    // --- Money Manager (KTW) ---
    private fun mapKtwRow(
        cols: List<String>,
        h: Map<String, Int>,
        lineNum: Int,
        dateOrder: DateOrder,
        defaultCurrency: String
    ): RowMapResult {
        fun col(key: String): String = h[key]?.let { idx -> if (idx in cols.indices) cols[idx].trim() else "" } ?: ""

        val rawDate = col("date").ifBlank { cols.getOrNull(0) ?: "" }
        val dateResult = parseDateAndSeconds(rawDate, dateOrder)
            ?: return RowMapResult(warning = "Row $lineNum: Invalid date '$rawDate'")

        val account = col("account").ifBlank { "KTW Account" }
        val category = col("category")
        val rawAmount = col("amount")
        val rawType = col("type").lowercase(Locale.US)
        val note = col("note").ifBlank { col("description") }

        val amountMinor = parseAmountToMinor(rawAmount) ?: 0L
        if (amountMinor == 0L) return RowMapResult(warning = "Row $lineNum: Skipped zero amount")
        val absAmount = abs(amountMinor)
        val isOutflow = rawType.contains("expense") || amountMinor < 0L

        return RowMapResult(
            transaction = NormalizedCsvTransaction(
                lineNum = lineNum,
                epochDay = dateResult.first,
                rawDate = rawDate,
                type = if (isOutflow) TransactionType.EXPENSE else TransactionType.INCOME,
                amountMinor = absAmount,
                currencyCode = defaultCurrency,
                accountName = account,
                categoryName = category.ifBlank { null },
                payeeOrDescription = note,
                rawAmountForDisplay = rawAmount,
                localDateTimeEpochSeconds = dateResult.second
            )
        )
    }

    // --- Fortune City ---
    private fun mapFortuneCityRow(
        cols: List<String>,
        h: Map<String, Int>,
        lineNum: Int,
        dateOrder: DateOrder,
        defaultCurrency: String
    ): RowMapResult {
        fun col(key: String): String = h[key]?.let { idx -> if (idx in cols.indices) cols[idx].trim() else "" } ?: ""

        val rawDate = col("date").ifBlank { cols.getOrNull(0) ?: "" }
        val dateResult = parseDateAndSeconds(rawDate, dateOrder)
            ?: return RowMapResult(warning = "Row $lineNum: Invalid date '$rawDate'")

        val category = col("category")
        val rawAmount = col("amount")
        val note = col("note").ifBlank { col("description") }
        val account = col("account").ifBlank { "Fortune City Account" }

        val amountMinor = parseAmountToMinor(rawAmount) ?: 0L
        if (amountMinor == 0L) return RowMapResult(warning = "Row $lineNum: Skipped zero amount")
        val absAmount = abs(amountMinor)

        val isIncome = category.equals("income", ignoreCase = true) || amountMinor > 0L

        return RowMapResult(
            transaction = NormalizedCsvTransaction(
                lineNum = lineNum,
                epochDay = dateResult.first,
                rawDate = rawDate,
                type = if (isIncome) TransactionType.INCOME else TransactionType.EXPENSE,
                amountMinor = absAmount,
                currencyCode = defaultCurrency,
                accountName = account,
                categoryName = if (isIncome) null else category.ifBlank { null },
                payeeOrDescription = note,
                rawAmountForDisplay = rawAmount,
                localDateTimeEpochSeconds = dateResult.second
            )
        )
    }

    // ------------------------------------------------------------------------
    // 8. Shared Pure Transfer Pair Consolidator
    // ------------------------------------------------------------------------

    data class ConsolidationResult(
        val transactions: List<NormalizedCsvTransaction>,
        val consolidatedCount: Int
    )

    fun consolidateTransferPairs(
        rows: List<NormalizedCsvTransaction>,
        warnings: MutableList<String>
    ): ConsolidationResult {
        val result = mutableListOf<NormalizedCsvTransaction>()
        val transferCandidates = mutableListOf<NormalizedCsvTransaction>()

        for (tx in rows) {
            if (tx.isTransferLeg) {
                transferCandidates.add(tx)
            } else {
                result.add(tx)
            }
        }

        if (transferCandidates.isEmpty()) {
            return ConsolidationResult(result, 0)
        }

        val pairedIndices = mutableSetOf<Int>()
        var consolidatedCount = 0

        for (i in 0 until transferCandidates.size) {
            if (i in pairedIndices) continue
            val legA = transferCandidates[i]

            var matchIdx = -1
            for (j in (i + 1) until transferCandidates.size) {
                if (j in pairedIndices) continue
                val legB = transferCandidates[j]

                // Must have opposite flow
                if (legA.isOutflowLeg == legB.isOutflowLeg) continue
                // Must have different accounts
                if (legA.accountName.equals(legB.accountName, ignoreCase = true)) continue

                // Cashew matching: same-day or within 65 seconds
                val timeMatch = if (legA.localDateTimeEpochSeconds != null && legB.localDateTimeEpochSeconds != null) {
                    abs(legA.localDateTimeEpochSeconds - legB.localDateTimeEpochSeconds) <= 65L
                } else {
                    legA.epochDay == legB.epochDay
                }

                if (!timeMatch) continue

                // Check note matching if present
                val noteMatch = if (legA.note.isNotBlank() && legB.note.isNotBlank() &&
                    (legA.note.contains("→") || legA.note.contains("←"))) {
                    legA.note == legB.note
                } else true

                if (!noteMatch) continue

                // Amount match (equal abs amount) OR cross-currency transfer match
                val amountMatch = legA.amountMinor == legB.amountMinor
                val hasArrowNote = legA.note.isNotBlank() && legB.note.isNotBlank() &&
                    (legA.note.contains("→") || legA.note.contains("←")) &&
                    legA.note == legB.note
                val isCrossCurrencyPair = hasArrowNote && legA.currencyCode != legB.currencyCode

                if (amountMatch || isCrossCurrencyPair) {
                    matchIdx = j
                    break
                }
            }

            if (matchIdx != -1) {
                val legB = transferCandidates[matchIdx]
                pairedIndices.add(i)
                pairedIndices.add(matchIdx)

                val outflowLeg = if (legA.isOutflowLeg) legA else legB
                val inflowLeg = if (legA.isOutflowLeg) legB else legA

                // Cross-currency transfer check
                if (outflowLeg.currencyCode != null && inflowLeg.currencyCode != null &&
                    !outflowLeg.currencyCode.equals(inflowLeg.currencyCode, ignoreCase = true)) {
                    warnings.add("Rows ${outflowLeg.lineNum} & ${inflowLeg.lineNum}: Skipped cross-currency transfer (${outflowLeg.currencyCode} -> ${inflowLeg.currencyCode})")
                } else {
                    val cleanPayee = outflowLeg.payeeOrDescription.ifBlank { inflowLeg.payeeOrDescription }
                    val cleanNote = outflowLeg.note.ifBlank { inflowLeg.note }
                    result.add(
                        NormalizedCsvTransaction(
                            lineNum = outflowLeg.lineNum,
                            epochDay = outflowLeg.epochDay,
                            rawDate = outflowLeg.rawDate,
                            type = TransactionType.TRANSFER,
                            amountMinor = outflowLeg.amountMinor,
                            currencyCode = outflowLeg.currencyCode,
                            accountName = outflowLeg.accountName,
                            transferToAccountName = inflowLeg.accountName,
                            categoryName = null,
                            payeeOrDescription = cleanPayee,
                            note = cleanNote,
                            tags = outflowLeg.tags.ifEmpty { inflowLeg.tags },
                            rawAmountForDisplay = outflowLeg.rawAmountForDisplay,
                            localDateTimeEpochSeconds = outflowLeg.localDateTimeEpochSeconds
                        )
                    )
                    consolidatedCount++
                }
            }
        }

        // Handle unpaired legs
        for (i in 0 until transferCandidates.size) {
            if (i in pairedIndices) continue
            val leg = transferCandidates[i]

            if (leg.isCashewBalanceCorrection) {
                // Cashew unpaired balance correction -> RECONCILIATION
                result.add(
                    NormalizedCsvTransaction(
                        lineNum = leg.lineNum,
                        epochDay = leg.epochDay,
                        rawDate = leg.rawDate,
                        type = TransactionType.RECONCILIATION,
                        amountMinor = leg.amountMinor,
                        isOutflowReconciliation = leg.isOutflowLeg,
                        currencyCode = leg.currencyCode,
                        accountName = leg.accountName,
                        categoryName = null,
                        payeeOrDescription = leg.payeeOrDescription,
                        note = leg.note,
                        tags = leg.tags,
                        rawAmountForDisplay = leg.rawAmountForDisplay,
                        localDateTimeEpochSeconds = leg.localDateTimeEpochSeconds
                    )
                )
            } else {
                // Informational fallback to standard expense/income
                val fallbackType = if (leg.isOutflowLeg) TransactionType.EXPENSE else TransactionType.INCOME
                warnings.add("Row ${leg.lineNum}: Unpaired transfer leg imported as standard ${fallbackType.name.lowercase(Locale.US)}")
                result.add(
                    leg.copy(
                        type = fallbackType,
                        categoryName = leg.categoryName,
                        isTransferLeg = false
                    )
                )
            }
        }

        return ConsolidationResult(result, consolidatedCount)
    }
}
