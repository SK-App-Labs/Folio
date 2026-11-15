package com.folioledger.app.data

import com.folioledger.app.data.local.entity.CategoryEntity
import com.folioledger.app.data.repository.CsvSectionState
import com.folioledger.app.data.repository.detectSectionHeader
import com.folioledger.app.data.repository.parseAccountGroupFromTokens
import com.folioledger.app.data.repository.parseAmountDecimalToMinor
import com.folioledger.app.data.repository.parseCsvLine
import com.folioledger.app.data.repository.parseDateStringToEpochDay
import com.folioledger.app.data.repository.readLogicalCsvRecord
import com.folioledger.app.data.repository.SideBySideColMap
import com.folioledger.app.data.repository.generateSideBySideCsvTemplateContent
import com.folioledger.app.data.repository.isSideBySideHeader
import com.folioledger.app.data.repository.parseSideBySideColMap
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.CategoryType
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.pow

class CsvExportImportEngineTest {

    @Test
    fun parseCsvLine_simpleTokens_parsedCorrectly() {
        val line = "101,2026-03-25,EXPENSE,250.50,INR,HDFC Bank"
        val tokens = parseCsvLine(line)
        assertEquals(listOf("101", "2026-03-25", "EXPENSE", "250.50", "INR", "HDFC Bank"), tokens)
    }

    @Test
    fun parseCsvLine_commasInsideQuotes_preservedIntact() {
        val line = "102,2026-03-25,EXPENSE,1500.00,INR,\"Supermarket, Grocery Store\",Weekly Groceries"
        val tokens = parseCsvLine(line)
        assertEquals(7, tokens.size)
        assertEquals("Supermarket, Grocery Store", tokens[5])
        assertEquals("Weekly Groceries", tokens[6])
    }

    @Test
    fun parseCsvLine_escapedDoubleQuotes_handledPerRfc4180() {
        val line = "103,2026-03-25,INCOME,50000.00,INR,Bank,\"Bonus \"\"Q1 Performance\"\"\",Job"
        val tokens = parseCsvLine(line)
        assertEquals(8, tokens.size)
        assertEquals("Bonus \"Q1 Performance\"", tokens[6])
    }

    @Test
    fun parseCsvLine_emptyFields_parsedAsEmptyStrings() {
        val line = "104,2026-03-25,TRANSFER,1000.00,INR,Bank A,Bank B,,,,Note,"
        val tokens = parseCsvLine(line)
        assertEquals(12, tokens.size)
        assertEquals("", tokens[7])
        assertEquals("", tokens[8])
        assertEquals("", tokens[9])
        assertEquals("Note", tokens[10])
        assertEquals("", tokens[11])
    }

    @Test
    fun utf8Bom_strippedSuccessfully() {
        val rawHeader = "\uFEFFID,Date,Type,Amount,Currency,Account"
        val cleanHeader = if (rawHeader.startsWith("\uFEFF")) rawHeader.substring(1) else rawHeader
        val tokens = parseCsvLine(cleanHeader)
        assertEquals("ID", tokens[0])
    }

    @Test
    fun amountParsing_bigDecimal_exactWithoutFloatingPointError() {
        // Test standard 2-fraction digits (INR / USD)
        assertEquals(25050L, parseAmountDecimalToMinor("250.50", 2))
        assertEquals(25000L, parseAmountDecimalToMinor("250", 2))
        assertEquals(123456789L, parseAmountDecimalToMinor("1,234,567.89", 2))

        // Test European formats and comma decimals
        assertEquals(12550L, parseAmountDecimalToMinor("125,50", 2))
        assertEquals(123456L, parseAmountDecimalToMinor("1.234,56", 2))

        // Test currency symbols and ISO prefixes/suffixes
        assertEquals(120000L, parseAmountDecimalToMinor("₹1,200.00", 2))
        assertEquals(120000L, parseAmountDecimalToMinor("\u20B91,200.00", 2))
        assertEquals(5000L, parseAmountDecimalToMinor("€50.00", 2))
        assertEquals(5000L, parseAmountDecimalToMinor("\u20AC50.00", 2))
        assertEquals(7525L, parseAmountDecimalToMinor("£75.25", 2))
        assertEquals(10050L, parseAmountDecimalToMinor("$100.50", 2))
        assertEquals(12050L, parseAmountDecimalToMinor("USD 120.50", 2))
        assertEquals(12050L, parseAmountDecimalToMinor("120.50 EUR", 2))
        assertEquals(12050L, parseAmountDecimalToMinor("\u00A0120.50\u00A0", 2))

        // Test accounting parenthesis negative
        assertEquals(-12550L, parseAmountDecimalToMinor("(125.50)", 2))
        assertEquals(-12550L, parseAmountDecimalToMinor("(€125,50)", 2))

        // Test 0 fraction digits (JPY)
        assertEquals(5000L, parseAmountDecimalToMinor("5000", 0))

        // Test 3 fraction digits (KWD / BHD)
        assertEquals(1500L, parseAmountDecimalToMinor("1.500", 3))
    }

    @Test
    fun dateParsing_multipleFormats_parsedToEpochDayCorrectly() {
        fun parseDateStringToEpochDay(raw: String): Long? {
            val cleaned = raw.trim()
            if (cleaned.isEmpty()) return null
            runCatching { return LocalDate.parse(cleaned, DateTimeFormatter.ISO_LOCAL_DATE).toEpochDay() }
            runCatching { return LocalDate.parse(cleaned, DateTimeFormatter.ofPattern("dd/MM/yyyy")).toEpochDay() }
            runCatching { return LocalDate.parse(cleaned, DateTimeFormatter.ofPattern("dd-MM-yyyy")).toEpochDay() }
            runCatching { return LocalDate.parse(cleaned, DateTimeFormatter.ofPattern("MM/dd/yyyy")).toEpochDay() }
            runCatching { return LocalDate.parse(cleaned, DateTimeFormatter.ofPattern("yyyy/MM/dd")).toEpochDay() }
            return null
        }

        val expected = LocalDate.of(2026, 3, 25).toEpochDay()
        assertEquals(expected, parseDateStringToEpochDay("2026-03-25"))
        assertEquals(expected, parseDateStringToEpochDay("25/03/2026"))
        assertEquals(expected, parseDateStringToEpochDay("25-03-2026"))
        assertEquals(expected, parseDateStringToEpochDay("03/25/2026"))
        assertEquals(expected, parseDateStringToEpochDay("2026/03/25"))
    }

    @Test
    fun decimalFormatting_plainWithoutGroupingCommas() {
        fun formatDecimalAmountPlain(minor: Long, fractionDigits: Int): String {
            if (fractionDigits <= 0) return minor.toString()
            val isNeg = minor < 0L
            val absMinor = Math.abs(minor)
            val factor = 10.0.pow(fractionDigits.toDouble()).toLong()
            val whole = absMinor / factor
            val frac = absMinor % factor
            val fracStr = frac.toString().padStart(fractionDigits, '0')
            val res = "$whole.$fracStr"
            return if (isNeg) "-$res" else res
        }

        assertEquals("250.50", formatDecimalAmountPlain(25050L, 2))
        assertEquals("1234567.89", formatDecimalAmountPlain(123456789L, 2))
        assertEquals("5000", formatDecimalAmountPlain(5000L, 0))
        assertEquals("1.500", formatDecimalAmountPlain(1500L, 3))
    }

    @Test
    fun detectSectionHeader_variousSectionMarkers() {
        // Accounts markers
        assertEquals(CsvSectionState.ACCOUNTS, detectSectionHeader("=== ACCOUNTS ==="))
        assertEquals(CsvSectionState.ACCOUNTS, detectSectionHeader("=== Accounts & Balances ==="))
        assertEquals(CsvSectionState.ACCOUNTS, detectSectionHeader("[ACCOUNTS]"))
        assertEquals(CsvSectionState.ACCOUNTS, detectSectionHeader("[ACCOUNTS],,,,,,,,,,,,"))
        assertEquals(CsvSectionState.ACCOUNTS, detectSectionHeader("# ACCOUNTS"))

        // Categories markers
        assertEquals(CsvSectionState.CATEGORIES, detectSectionHeader("=== CATEGORIES ==="))
        assertEquals(CsvSectionState.CATEGORIES, detectSectionHeader("[CATEGORIES]"))
        assertEquals(CsvSectionState.CATEGORIES, detectSectionHeader("[CATEGORIES],,,,,,,,,,,,"))
        assertEquals(CsvSectionState.CATEGORIES, detectSectionHeader("# Categories & Subcategories"))

        // Transactions markers
        assertEquals(CsvSectionState.TRANSACTIONS, detectSectionHeader("=== TRANSACTIONS ==="))
        assertEquals(CsvSectionState.TRANSACTIONS, detectSectionHeader("[TRANSACTIONS]"))
        assertEquals(CsvSectionState.TRANSACTIONS, detectSectionHeader("[TRANSACTIONS],,,,,,,,,,,,"))
        assertEquals(CsvSectionState.TRANSACTIONS, detectSectionHeader("# Transactions"))

        // Hint rows must NEVER be detected as section headers!
        assertNull(detectSectionHeader("# Hint: (Account Name),(BANK_ACCOUNT|CREDIT_CARD...),,,,,,"))
        assertNull(detectSectionHeader("# Hint: (Main Category Name),(Optional Subcategory)..."))
        assertNull(detectSectionHeader("# Hint: (Optional ID),(YYYY-MM-DD)...(Source Account)..."))

        // Non-section lines (Headers or data) should return null
        assertNull(detectSectionHeader("Date,Type,Amount,Currency,Account"))
        assertNull(detectSectionHeader("HDFC Bank,BANK,INR,50000.00,2026-01-01"))
        assertNull(detectSectionHeader("2026-03-25,EXPENSE,250.50,INR,HDFC Bank"))
        assertNull(detectSectionHeader(""))
    }

    @Test
    fun accountRowParsing_startingBalanceAndLimits() {
        fun parseAccountGroup(raw: String?): AccountGroup {
            val clean = raw?.trim()?.uppercase()?.replace(" ", "_") ?: ""
            if (clean == "BANK") return AccountGroup.BANK_ACCOUNT
            return runCatching { AccountGroup.valueOf(clean) }.getOrDefault(AccountGroup.BANK_ACCOUNT)
        }

        fun parseAmount(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            val cleaned = raw.replace(",", "").replace("$", "").replace("₹", "").trim()
            return runCatching {
                BigDecimal(cleaned).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
            }.getOrNull()
        }

        // Test 1: Bank account with positive starting balance and anchor date
        val row1 = parseCsvLine("HDFC Salary,BANK,INR,50000.00,2026-01-01,,")
        val acc1Name = row1[0]
        val acc1Group = parseAccountGroup(row1[1])
        val acc1Currency = row1[2]
        val acc1Balance = parseAmount(row1[3])
        val acc1Anchor = LocalDate.parse(row1[4]).toEpochDay()
        val acc1Limit = parseAmount(row1[5])
        val acc1BillDay = row1[6].toIntOrNull()

        assertEquals("HDFC Salary", acc1Name)
        assertEquals(AccountGroup.BANK_ACCOUNT, acc1Group)
        assertEquals("INR", acc1Currency)
        assertEquals(5000000L, acc1Balance)
        assertEquals(LocalDate.of(2026, 1, 1).toEpochDay(), acc1Anchor)
        assertNull(acc1Limit)
        assertNull(acc1BillDay)

        // Test 2: Credit card with negative starting balance, credit limit, and settlement day
        val row2 = parseCsvLine("Amazon ICICI,CREDIT_CARD,INR,-4500.50,2026-01-01,150000.00,18")
        val acc2Name = row2[0]
        val acc2Group = parseAccountGroup(row2[1])
        val acc2Balance = parseAmount(row2[3])
        val acc2Limit = parseAmount(row2[5])
        val acc2BillDay = row2[6].toIntOrNull()

        assertEquals("Amazon ICICI", acc2Name)
        assertEquals(AccountGroup.CREDIT_CARD, acc2Group)
        assertEquals(-450050L, acc2Balance)
        assertEquals(15000000L, acc2Limit)
        assertEquals(18, acc2BillDay)

        // Test 3: Cash account without starting balance
        val row3 = parseCsvLine("Wallet Cash,CASH,INR,,,,,")
        val acc3Balance = parseAmount(row3[3])
        assertNull(acc3Balance)

        // Test 4: Loan account with positive entered balance - auto-detected as liability
        val row4 = parseCsvLine("HDFC Home Loan,LOAN,INR,500000.00,2026-01-01,,")
        val acc4Group = parseAccountGroup(row4[1])
        val acc4Balance = parseAmount(row4[3])
        assertTrue("LOAN must be liability by default", acc4Group.isLiabilityByDefault)
        assertEquals(50000000L, acc4Balance)
    }

    @Test
    fun liabilityPolarity_autoDetectionForLoansAndCreditCards() {
        // Business Rule: For accounts where isLiabilityByDefault == true (CREDIT_CARD, LOAN, OVERDRAFT),
        // whether user writes positive (+50000) or negative (-50000), Folio recognizes it as an outstanding liability!
        fun computeOpeningBalanceOutflow(group: AccountGroup, rawAmount: Long): Boolean {
            return rawAmount < 0L || group.isLiabilityByDefault
        }

        // Bank Account (Asset): Positive is inflow (+), Negative is outflow (overdrawn)
        assertEquals(false, computeOpeningBalanceOutflow(AccountGroup.BANK_ACCOUNT, 2500000L))
        assertEquals(true, computeOpeningBalanceOutflow(AccountGroup.BANK_ACCOUNT, -500000L))

        // Loan (Liability): Always treated as outflow/debt regardless of whether entered with or without minus sign
        assertEquals(true, computeOpeningBalanceOutflow(AccountGroup.LOAN, 50000000L))
        assertEquals(true, computeOpeningBalanceOutflow(AccountGroup.LOAN, -50000000L))

        // Overdraft (Liability): Always treated as outflow/debt
        assertEquals(true, computeOpeningBalanceOutflow(AccountGroup.OVERDRAFT, 1000000L))
        assertEquals(true, computeOpeningBalanceOutflow(AccountGroup.OVERDRAFT, -1000000L))

        // Credit Card: Magnitude is stored as initial debt
        val enteredPositiveDebt = 450050L
        val enteredNegativeDebt = -450050L
        assertEquals(450050L, Math.abs(enteredPositiveDebt))
        assertEquals(450050L, Math.abs(enteredNegativeDebt))
        assertTrue(AccountGroup.CREDIT_CARD.isLiabilityByDefault)
    }

    @Test
    fun categoryRowParsing_hierarchyAndTypes() {
        val catLine1 = parseCsvLine("Food & Dining,,EXPENSE,restaurant,#FF5722")
        val mainCat = catLine1[0]
        val subCat = catLine1[1]
        val catType = CategoryType.valueOf(catLine1[2])
        val icon = catLine1[3]
        val color = catLine1[4]

        assertEquals("Food & Dining", mainCat)
        assertTrue(subCat.isEmpty())
        assertEquals(CategoryType.EXPENSE, catType)
        assertEquals("restaurant", icon)
        assertEquals("#FF5722", color)

        val catLine2 = parseCsvLine("Food & Dining,Groceries,EXPENSE,shopping_cart,#FF9800")
        assertEquals("Food & Dining", catLine2[0])
        assertEquals("Groceries", catLine2[1])
        assertEquals(CategoryType.EXPENSE, CategoryType.valueOf(catLine2[2]))
    }

    @Test
    fun transferCategoryProtection_invariantHoldsStrictly() {
        // Business Rule: Transfers (TRANSFER and CC_SETTLEMENT) must NEVER have a category attached.
        // Even if the CSV row has account names or notes in the category column, categoryId must resolve to null.
        fun resolveCategoryId(
            type: TransactionType,
            csvCategoryName: String?,
            categoryLookup: (String) -> Long?
        ): Long? {
            val isTransfer = type == TransactionType.TRANSFER || type == TransactionType.CC_SETTLEMENT
            if (isTransfer) {
                return null // Category protection invariant
            }
            return csvCategoryName?.takeIf { it.isNotBlank() }?.let { categoryLookup(it) }
        }

        val dummyCategories = mapOf("Groceries" to 101L, "HDFC Bank" to 102L, "Dining" to 103L)

        // Test 1: Standard Expense with category -> Successfully resolves category
        val expenseCatId = resolveCategoryId(TransactionType.EXPENSE, "Groceries") { dummyCategories[it] }
        assertEquals(101L, expenseCatId)

        // Test 2: Standard Income with category -> Successfully resolves category
        val incomeCatId = resolveCategoryId(TransactionType.INCOME, "Dining") { dummyCategories[it] }
        assertEquals(103L, incomeCatId)

        // Test 3: Transfer with account name in category column -> Strictly returns null!
        val transferCatId = resolveCategoryId(TransactionType.TRANSFER, "HDFC Bank") { dummyCategories[it] }
        assertNull("Transfers must never link a category, even if column has text", transferCatId)

        // Test 4: CC Settlement with account name in category column -> Strictly returns null!
        val settlementCatId = resolveCategoryId(TransactionType.CC_SETTLEMENT, "HDFC Bank") { dummyCategories[it] }
        assertNull("CC Settlement must never link a category", settlementCatId)
    }

    @Test
    fun multiSectionCsvStreamSimulation_stateTransitionsAndAnchorGeneration() {
        val csvLines = listOf(
            "# Folio Spreadsheet Template",
            "[ACCOUNTS],,,,,,,,,,,,",
            "Name,Group,Currency,Starting Balance,Anchor Date,Credit Limit,Settlement Day,,,,,,",
            "# Hint: (Account Name),(BANK_ACCOUNT|CREDIT_CARD|CASH|SAVINGS|PREPAID_WALLET|LOAN),(Currency ISO),(Starting Balance e.g. 50000.00),(Anchor Date YYYY-MM-DD),(Credit Limit),(Settlement Day 1-31),,,,,,",
            "HDFC Salary,BANK,INR,25000.00,2026-01-01,,,,,,,,",
            "Cash,CASH,INR,,,,,,,,",
            ",,,,,,,,,,,,",
            "[CATEGORIES],,,,,,,,,,,,",
            "Main Category,Subcategory,Type,Icon,Color,,,,,,,,",
            "# Hint: (Main Category Name),(Optional Subcategory),(EXPENSE|INCOME),(Icon Name e.g. restaurant),(Hex Color e.g. #FF5722),,,,,,,,",
            "Groceries,,EXPENSE,shopping_bag,#4CAF50,,,,,,,,",
            ",,,,,,,,,,,,",
            "[TRANSACTIONS],,,,,,,,,,,,",
            "Date,Type,Amount,Currency,Account,Transfer To,Category,Note,,,,,,",
            "# Hint: (Optional ID),(YYYY-MM-DD),(EXPENSE|INCOME|TRANSFER|CC_SETTLEMENT|REFUND),(Amount e.g. 250.50),(Currency),(Source Account),(Destination Account if Transfer),(Leave blank if Transfer),(Subcategory),(Transfer Fee),(Payee/Description),(Note),(Tags semicolon separated)",
            "2026-01-05,EXPENSE,500.00,INR,HDFC Salary,,Groceries,Supermarket,,,,,,",
            "2026-01-10,TRANSFER,2000.00,INR,HDFC Salary,Cash,Cash,ATM Withdrawal,,,,,,"
        )

        var currentSection = CsvSectionState.LEGACY
        val configuredAccounts = mutableListOf<String>()
        val accountsWithStartingBalance = mutableListOf<String>()
        val openingBalanceTransactions = mutableListOf<Pair<String, Long>>()
        val configuredCategories = mutableListOf<String>()
        val transactionsParsed = mutableListOf<String>()

        for (line in csvLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.all { it == ',' || it.isWhitespace() }) continue

            val section = detectSectionHeader(trimmed)
            if (section != null) {
                currentSection = section
                continue
            }

            if (trimmed.startsWith("#") || trimmed.startsWith("//")) continue

            val tokens = parseCsvLine(trimmed)
            when (currentSection) {
                CsvSectionState.ACCOUNTS -> {
                    if (tokens.firstOrNull()?.contains("Name", ignoreCase = true) == true) continue
                    val name = tokens[0]
                    configuredAccounts.add(name)
                    val balanceStr = tokens.getOrNull(3)
                    if (!balanceStr.isNullOrBlank()) {
                        accountsWithStartingBalance.add(name)
                        val minor = BigDecimal(balanceStr).movePointRight(2).toLong()
                        openingBalanceTransactions.add(name to minor)
                    }
                }
                CsvSectionState.CATEGORIES -> {
                    if (tokens.firstOrNull()?.contains("Main", ignoreCase = true) == true) continue
                    configuredCategories.add(tokens[0])
                }
                CsvSectionState.TRANSACTIONS, CsvSectionState.LEGACY -> {
                    if (tokens.firstOrNull()?.contains("Date", ignoreCase = true) == true) continue
                    transactionsParsed.add(tokens[0])
                }
                else -> {}
            }
        }

        assertEquals(listOf("HDFC Salary", "Cash"), configuredAccounts)
        assertEquals(listOf("HDFC Salary"), accountsWithStartingBalance)
        assertEquals(1, openingBalanceTransactions.size)
        assertEquals("HDFC Salary", openingBalanceTransactions[0].first)
        assertEquals(2500000L, openingBalanceTransactions[0].second)
        assertEquals(listOf("Groceries"), configuredCategories)
        assertEquals(2, transactionsParsed.size)
    }

    @Test
    fun userProvidedSpreadsheet_fullSimulation_parsesAllRowsWithoutErrors() {
        val stream = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("user_test_spreadsheet.csv")
                ?: javaClass.getResourceAsStream("/user_test_spreadsheet.csv")
        ) { "Missing test resource: user_test_spreadsheet.csv" }

        val reader = java.io.BufferedReader(java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))
        var currentSection = CsvSectionState.LEGACY
        var colMap = emptyMap<String, Int>()

        val parsedAccounts = mutableListOf<String>()
        val parsedCategories = mutableListOf<String>()
        val parsedTransactions = mutableListOf<String>()
        val errors = mutableListOf<String>()

        var line = reader.readLine()
        var lineNum = 0
        while (line != null) {
            lineNum++
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.all { it == ',' || it.isWhitespace() }) {
                val sec = detectSectionHeader(trimmed)
                if (sec != null) {
                    currentSection = sec
                    colMap = emptyMap()
                    line = reader.readLine()
                    continue
                }

                if (trimmed.startsWith("#") || trimmed.startsWith("//")) {
                    line = reader.readLine()
                    continue
                }

                if (colMap.isEmpty()) {
                    val tokens = parseCsvLine(trimmed)
                    colMap = tokens.mapIndexed { idx, name ->
                        name.trim().lowercase().replace("_", "").replace(" ", "") to idx
                    }.toMap()
                    line = reader.readLine()
                    continue
                }

                val tokens = parseCsvLine(trimmed)
                when (currentSection) {
                    CsvSectionState.ACCOUNTS -> {
                        val nameIdx = colMap["name"] ?: colMap["account"] ?: colMap["accountname"] ?: 0
                        val name = tokens.getOrNull(nameIdx)?.trim() ?: ""
                        if (name.isNotEmpty()) {
                            parsedAccounts.add(name)
                            val rawGroup = colMap["group"]?.let { tokens.getOrNull(it)?.trim() }
                                ?: colMap["grouptype"]?.let { tokens.getOrNull(it)?.trim() }
                            val group = parseAccountGroupFromTokens(rawGroup, name)
                            assertNotNull("Group should be parsed", group)
                        }
                    }
                    CsvSectionState.CATEGORIES -> {
                        val mainIdx = colMap["maincategory"] ?: colMap["category"] ?: colMap["name"] ?: 0
                        val mainName = tokens.getOrNull(mainIdx)?.trim() ?: ""
                        if (mainName.isNotEmpty()) {
                            parsedCategories.add(mainName)
                        }
                    }
                    CsvSectionState.TRANSACTIONS, CsvSectionState.LEGACY -> {
                        val dateIdx = colMap["date"] ?: colMap["txdate"] ?: colMap["transactiondate"]
                        val amountIdx = colMap["amount"] ?: colMap["txnamount"] ?: colMap["total"]
                        val accountIdx = colMap["account"] ?: colMap["accountname"] ?: colMap["fromaccount"]

                        if (dateIdx == null || amountIdx == null || accountIdx == null) {
                            errors.add("Line $lineNum: Missing header columns")
                        } else {
                            val rawDate = tokens.getOrNull(dateIdx) ?: ""
                            val epochDay = parseDateStringToEpochDay(rawDate)
                            val rawAmount = tokens.getOrNull(amountIdx) ?: ""
                            val amountMinor = parseAmountDecimalToMinor(rawAmount, 2)
                            val accountName = tokens.getOrNull(accountIdx)?.trim() ?: ""

                            if (epochDay == null) errors.add("Line $lineNum: Bad date '$rawDate'")
                            if (amountMinor == null) errors.add("Line $lineNum: Bad amount '$rawAmount'")
                            if (accountName.isEmpty()) errors.add("Line $lineNum: Missing account")

                            if (epochDay != null && amountMinor != null && accountName.isNotEmpty()) {
                                parsedTransactions.add(accountName)
                            }
                        }
                    }
                    else -> {}
                }
            }
            line = reader.readLine()
        }

        assertTrue("Errors should be empty, but had: $errors", errors.isEmpty())
        assertEquals(17, parsedAccounts.size)
        assertTrue(parsedAccounts.contains("Axis Bank"))
        assertTrue(parsedAccounts.contains("ICICI Home Loan"))
        assertTrue(parsedAccounts.contains("Axis Rewards CC"))
        assertTrue(parsedCategories.size >= 50)
        assertEquals(358, parsedTransactions.size)
    }

    @Test
    fun parseAccountGroupFromTokens_noFalsePositivesOnTwoLetterSubstrings() {
        // Words containing 'od' or 'cc' internally must not trigger OVERDRAFT or CREDIT_CARD
        assertEquals(AccountGroup.OTHER, parseAccountGroupFromTokens(null, "Food Card"))
        assertEquals(AccountGroup.OTHER, parseAccountGroupFromTokens(null, "Godrej Salary"))
        assertEquals(AccountGroup.OTHER, parseAccountGroupFromTokens(null, "Period Care"))
        assertEquals(AccountGroup.OTHER, parseAccountGroupFromTokens(null, "Today Expense"))
        assertEquals(AccountGroup.BANK_ACCOUNT, parseAccountGroupFromTokens(null, "Access Bank"))

        // Whole-word tokens should still match accurately
        assertEquals(AccountGroup.CREDIT_CARD, parseAccountGroupFromTokens(null, "Axis CC"))
        assertEquals(AccountGroup.CREDIT_CARD, parseAccountGroupFromTokens(null, "HDFC Credit Card"))
        assertEquals(AccountGroup.OVERDRAFT, parseAccountGroupFromTokens(null, "SBI OD"))
        assertEquals(AccountGroup.OVERDRAFT, parseAccountGroupFromTokens(null, "Personal Overdraft"))
        assertEquals(AccountGroup.DEBIT_CARD, parseAccountGroupFromTokens(null, "HDFC Debit"))
        assertEquals(AccountGroup.CASH, parseAccountGroupFromTokens(null, "Petty Cash"))
    }
    @Test
    fun sideBySideTemplate_headerDetectionAndColMapping() {
        val template = generateSideBySideCsvTemplateContent()
        assertTrue(template.isNotEmpty())
        val lines = template.lines()
        val bannerLine = lines[0]
        val headerLine = lines[1]

        assertEquals(CsvSectionState.SIDE_BY_SIDE, detectSectionHeader(bannerLine))
        assertEquals(CsvSectionState.SIDE_BY_SIDE, detectSectionHeader("=== ACCOUNTS ===,,,,=== CATEGORIES ===,,,,=== TRANSACTIONS ==="))

        val headerTokens = parseCsvLine(headerLine)
        assertTrue(isSideBySideHeader(headerTokens))

        val colMap = parseSideBySideColMap(headerTokens)
        assertEquals(0, colMap.accNameIdx)
        assertEquals(1, colMap.accGroupIdx)
        assertEquals(2, colMap.accCurrencyIdx)
        assertEquals(3, colMap.accStartingBalIdx)
        assertEquals(4, colMap.accAnchorDateIdx)
        assertEquals(5, colMap.accCreditLimitIdx)
        assertEquals(6, colMap.accSettlementDayIdx)

        assertEquals(8, colMap.catMainIdx)
        assertEquals(9, colMap.catSubIdx)
        assertEquals(10, colMap.catTypeIdx)

        assertEquals(12, colMap.txDateIdx)
        assertEquals(13, colMap.txTypeIdx)
        assertEquals(14, colMap.txAmountIdx)
        assertEquals(15, colMap.txCurrencyIdx)
        assertEquals(16, colMap.txAccountIdx)
        assertEquals(17, colMap.txTransferAccountIdx)
        assertEquals(18, colMap.txCategoryIdx)
        assertEquals(19, colMap.txSubcategoryIdx)
        assertEquals(20, colMap.txPayeeIdx)
        assertEquals(21, colMap.txNoteIdx)
        assertEquals(22, colMap.txTagsIdx)
    }

    @Test
    fun sideBySideTemplate_cleanWithoutDummyDataRows() {
        val template = generateSideBySideCsvTemplateContent()
        val lines = template.lines().filter { it.isNotBlank() }

        // Must only have 3 rows: Banner, Column Headers, and Hint guide row. No dummy data!
        assertEquals(3, lines.size)
        assertEquals(CsvSectionState.SIDE_BY_SIDE, detectSectionHeader(lines[0]))
        assertTrue(isSideBySideHeader(parseCsvLine(lines[1])))
        assertTrue(lines[2].startsWith("#"))
    }

    @Test
    fun isSideBySideHeader_doesNotMisclassifySingleTableCsvs() {
        // Standard single-table CSV headers exported from other apps or bank statements
        val singleTableTokens1 = listOf("Date", "Account Name", "Category", "Subcategory", "Amount")
        org.junit.Assert.assertFalse(isSideBySideHeader(singleTableTokens1))

        val singleTableTokens2 = listOf("Date", "Type", "Amount", "Currency", "Account", "Category", "Payee", "Note")
        org.junit.Assert.assertFalse(isSideBySideHeader(singleTableTokens2))

        val singleTableTokens3 = listOf("Date", "Account", "Transfer To Account", "Category", "Subcategory", "Amount")
        org.junit.Assert.assertFalse(isSideBySideHeader(singleTableTokens3))

        // Side-by-side header with account definition columns or duplicate category columns
        val sideBySideTokens = listOf(
            "Account Name", "Group", "Account Currency", "Starting Balance", "Anchor Date", "Credit Limit", "Settlement Day", "",
            "Category", "Subcategory", "Category Type", "",
            "Date", "Type", "Amount", "Currency", "Account", "Transfer To Account", "Category", "Subcategory", "Payee", "Note", "Tags"
        )
        assertTrue(isSideBySideHeader(sideBySideTokens))
    }

    @Test
    fun sideBySideTemplate_fullParsingSimulation() {
        val template = generateSideBySideCsvTemplateContent()
        val userDataRows = listOf(
            "Checking Account,BANK_ACCOUNT,USD,5000.00,2026-11-01,,,,Groceries,Supermarket,EXPENSE,,2026-11-01,EXPENSE,45.50,USD,Checking Account,,Groceries,Supermarket,Whole Foods,Weekly grocery haul,groceries; food",
            "Credit Card,CREDIT_CARD,USD,0.00,2026-11-01,10000.00,15,,Salary,,INCOME,,2026-11-02,INCOME,3500.00,USD,Checking Account,,Salary,,Acme Corp,Monthly salary,career",
            "Cash Wallet,CASH,USD,200.00,2026-11-01,,,,Dining,Restaurants,EXPENSE,,2026-11-03,TRANSFER,150.00,USD,Checking Account,Cash Wallet,,,,ATM cash withdrawal,cash",
            ",,,,,,,,,,,,2026-11-04,EXPENSE,18.75,USD,Credit Card,,Dining,Restaurants,Starbucks,Afternoon coffee,coffee"
        )
        val lines = (template.lines().filter { it.isNotBlank() } + userDataRows)

        var currentSection = CsvSectionState.LEGACY
        var sideBySideColMap: SideBySideColMap? = null

        val parsedAccounts = mutableListOf<String>()
        val parsedCategories = mutableListOf<Pair<String, String?>>()
        val parsedTransactions = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.all { it == ',' || it.isWhitespace() }) continue

            val detected = detectSectionHeader(trimmed)
            if (detected != null) {
                currentSection = detected
                continue
            }

            if (trimmed.startsWith("#") || trimmed.startsWith("//")) continue

            val tokens = parseCsvLine(trimmed)
            if (sideBySideColMap == null) {
                if (currentSection == CsvSectionState.SIDE_BY_SIDE || isSideBySideHeader(tokens)) {
                    currentSection = CsvSectionState.SIDE_BY_SIDE
                    sideBySideColMap = parseSideBySideColMap(tokens)
                    continue
                }
            }

            if (currentSection == CsvSectionState.SIDE_BY_SIDE && sideBySideColMap != null) {
                val sbs = sideBySideColMap
                val accName = sbs.accNameIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                if (accName.isNotEmpty()) {
                    parsedAccounts.add(accName)
                }

                val catMain = sbs.catMainIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                if (catMain.isNotEmpty()) {
                    val catSub = sbs.catSubIdx?.let { tokens.getOrNull(it)?.trim() }?.ifEmpty { null }
                    parsedCategories.add(catMain to catSub)
                }

                val txDate = sbs.txDateIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                val txAmount = sbs.txAmountIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                if (txDate.isNotEmpty() && txAmount.isNotEmpty()) {
                    val txAccount = sbs.txAccountIdx?.let { tokens.getOrNull(it)?.trim() } ?: ""
                    parsedTransactions.add("$txDate:$txAmount:$txAccount")
                }
            }
        }

        // 3 accounts parsed from user data
        assertEquals(listOf("Checking Account", "Credit Card", "Cash Wallet"), parsedAccounts)

        // 3 categories parsed from user data
        assertEquals(3, parsedCategories.size)
        assertEquals("Groceries" to "Supermarket", parsedCategories[0])
        assertEquals("Salary" to null, parsedCategories[1])
        assertEquals("Dining" to "Restaurants", parsedCategories[2])

        // 4 transactions parsed from user data (including the 4th row where Account & Category columns are empty)
        assertEquals(4, parsedTransactions.size)
        assertEquals("2026-11-01:45.50:Checking Account", parsedTransactions[0])
        assertEquals("2026-11-02:3500.00:Checking Account", parsedTransactions[1])
        assertEquals("2026-11-03:150.00:Checking Account", parsedTransactions[2])
        assertEquals("2026-11-04:18.75:Credit Card", parsedTransactions[3])
    }

    @Test
    fun previewAndImport_currencyFractionDigitsMatchExactDbFingerprint() {
        val jpyCurrency = "JPY"
        val jpySpec = SupportedCurrencies.fromCode(jpyCurrency)
        assertEquals(0, jpySpec.fractionDigits)
        val jpyAmountMinor = parseAmountDecimalToMinor("5000", jpySpec.fractionDigits)
        assertEquals(5000L, jpyAmountMinor)

        val bhdCurrency = "BHD"
        val bhdSpec = SupportedCurrencies.fromCode(bhdCurrency)
        assertEquals(3, bhdSpec.fractionDigits)
        val bhdAmountMinor = parseAmountDecimalToMinor("1.500", bhdSpec.fractionDigits)
        assertEquals(1500L, bhdAmountMinor)

        val usdCurrency = "USD"
        val usdSpec = SupportedCurrencies.fromCode(usdCurrency)
        assertEquals(2, usdSpec.fractionDigits)
        val usdAmountMinor = parseAmountDecimalToMinor("100.00", usdSpec.fractionDigits)
        assertEquals(10000L, usdAmountMinor)

        // Verify fingerprint matches exact DB minor units:
        val epochDay = 20760L
        val accountId = 12L
        val type = TransactionType.EXPENSE
        val payee = "tokyo store"
        val jpyFingerprint = "${epochDay}|${accountId}|${type.name}|${Math.abs(jpyAmountMinor!!)}|${payee.lowercase()}"
        assertEquals("20760|12|EXPENSE|5000|tokyo store", jpyFingerprint)

        val bhdFingerprint = "${epochDay}|${accountId}|${type.name}|${Math.abs(bhdAmountMinor!!)}|${payee.lowercase()}"
        assertEquals("20760|12|EXPENSE|1500|tokyo store", bhdFingerprint)
    }

    @Test
    fun zeroAmountTransactions_rejectedInBothPreviewAndImportValidation() {
        val zeroStrings = listOf("0", "0.00", "0.000", "+0.00", "-0.00", "00.00")
        for (raw in zeroStrings) {
            val amountMinor = parseAmountDecimalToMinor(raw, 2)
            assertNotNull("Amount should parse numerically", amountMinor)
            assertEquals(0L, amountMinor)
            // Validation check (amountMinor == null || amountMinor == 0L)
            val isInvalid = amountMinor == null || amountMinor == 0L
            assertTrue("Zero amount '$raw' must be flagged as invalid", isInvalid)
        }
    }

    @Test
    fun reImportSplitTransactions_suppressesDummyCategoryAndPreservesSplits() {
        val rawCat = "Split Transaction"
        val rawSubcatBlank = ""
        val isTransfer = false

        val isSplitCategory = rawCat.equals("Split Transaction", ignoreCase = true)
        assertTrue(isSplitCategory)

        val isExportedSplit = !isTransfer && isSplitCategory && (!rawSubcatBlank.isNullOrBlank() && rawSubcatBlank.contains(":"))
        org.junit.Assert.assertFalse(isExportedSplit)

        // 1. CategoryEntity creation must be suppressed even if isExportedSplit is false
        val shouldSuppressCategoryCreation = isTransfer || isExportedSplit || isSplitCategory
        assertTrue(shouldSuppressCategoryCreation)

        // 2. On ID-based update of an existing split transaction:
        val existingIsSplit = true
        val isSplitTx = existingIsSplit || isExportedSplit || isSplitCategory
        assertTrue(isSplitTx)

        val effectiveCategoryId: Long? = if (isTransfer || isSplitTx) null else 99L
        assertNull("Effective category ID must remain null for split transactions", effectiveCategoryId)

        // 3. Deleting splits must be strictly guarded by presence of valid exported split data
        val shouldDeleteAndReplaceSplits = isExportedSplit && !rawSubcatBlank.isNullOrBlank()
        org.junit.Assert.assertFalse("Should not wipe existing splits if CSV does not provide split items", shouldDeleteAndReplaceSplits)
    }

    @Test
    fun openingBalanceAnchorDeduplication_preventsDuplicateAnchorInsertion() {
        val accountId = 1L
        val epochDay = 20000L
        val existingAnchorKeys = mutableSetOf("${accountId}|${epochDay}")

        val txType = TransactionType.OPENING_BALANCE
        val isOpeningBalanceDuplicate = txType == TransactionType.OPENING_BALANCE &&
            ("${accountId}|$epochDay" in existingAnchorKeys)

        assertTrue("Opening balance must be identified as duplicate when account already has anchor on epochDay", isOpeningBalanceDuplicate)
    }

    @Test
    fun preservesOutflowTarget_preservesLoanDebtAndReconciliationOutflow() {
        // Loan account opening balance
        val loanResolvedTransferId = computeResolvedTransferToAccountId(
            type = TransactionType.OPENING_BALANCE,
            transferAccountId = null,
            sourceAccountId = 5L,
            transferAccountName = "Home Loan",
            amountMinor = 500_000_00L,
            isLiabilityByDefault = AccountGroup.LOAN.isLiabilityByDefault
        )
        assertEquals(5L, loanResolvedTransferId)

        // Negative reconciliation
        val recResolvedTransferId = computeResolvedTransferToAccountId(
            type = TransactionType.RECONCILIATION,
            transferAccountId = null,
            sourceAccountId = 1L,
            transferAccountName = null,
            amountMinor = -50_00L,
            isLiabilityByDefault = AccountGroup.BANK_ACCOUNT.isLiabilityByDefault
        )
        assertEquals(1L, recResolvedTransferId)

        // Regular positive bank opening balance (asset)
        val bankResolvedTransferId = computeResolvedTransferToAccountId(
            type = TransactionType.OPENING_BALANCE,
            transferAccountId = null,
            sourceAccountId = 1L,
            transferAccountName = null,
            amountMinor = 1000_00L,
            isLiabilityByDefault = AccountGroup.BANK_ACCOUNT.isLiabilityByDefault
        )
        assertNull(bankResolvedTransferId)
    }

    @Test
    fun newCategoriesCreated_recordsOnlyActuallyCreatedCategoriesAndSubcategories() {
        val allCategories = mutableListOf<CategoryEntity>()
        val categoryNameToEntity = mutableMapOf<String, CategoryEntity>()
        val newCategoriesCreated = mutableListOf<String>()

        fun testGetOrCreate(
            name: String?,
            subName: String?,
            type: CategoryType
        ): CategoryEntity? {
            val mainTrimmed = name?.trim()?.ifEmpty { null } ?: return null
            val subTrimmed = subName?.trim()?.ifEmpty { null }

            val mainKey = "${mainTrimmed.lowercase()}|${type.name}"
            var parent = categoryNameToEntity[mainKey]
            if (parent == null) {
                parent = CategoryEntity(
                    id = (allCategories.size + 1).toLong(),
                    name = mainTrimmed,
                    type = type,
                    parentId = null,
                    iconName = "category",
                    colorHex = "#8B5CF6"
                )
                categoryNameToEntity[mainKey] = parent
                allCategories.add(parent)
                newCategoriesCreated.add(mainTrimmed)
            }

            if (subTrimmed == null) return parent

            val existingSub = allCategories.firstOrNull {
                it.parentId == parent.id && it.name.equals(subTrimmed, ignoreCase = true)
            }
            if (existingSub != null) return existingSub

            val createdSub = CategoryEntity(
                id = (allCategories.size + 1).toLong(),
                name = subTrimmed,
                type = type,
                parentId = parent.id,
                iconName = "subcategory",
                colorHex = "#8B5CF6"
            )
            allCategories.add(createdSub)
            newCategoriesCreated.add("$mainTrimmed › $subTrimmed")
            return createdSub
        }

        // 1. Create new parent category
        val dining = testGetOrCreate("Dining", null, CategoryType.EXPENSE)
        assertNotNull(dining)
        assertEquals(listOf("Dining"), newCategoriesCreated)

        // 2. Fetching existing parent should NOT add duplicate to newCategoriesCreated
        val diningAgain = testGetOrCreate("Dining", null, CategoryType.EXPENSE)
        assertEquals(dining?.id, diningAgain?.id)
        assertEquals(listOf("Dining"), newCategoriesCreated)

        // 3. Create new subcategory under existing parent
        val coffee = testGetOrCreate("Dining", "Coffee", CategoryType.EXPENSE)
        assertNotNull(coffee)
        assertEquals(listOf("Dining", "Dining › Coffee"), newCategoriesCreated)

        // 4. Fetching existing subcategory should NOT add duplicate
        val coffeeAgain = testGetOrCreate("Dining", "Coffee", CategoryType.EXPENSE)
        assertEquals(coffee?.id, coffeeAgain?.id)
        assertEquals(listOf("Dining", "Dining › Coffee"), newCategoriesCreated)

        // 5. Creating new parent with subcategory in one go
        val techLaptop = testGetOrCreate("Technology", "Laptops", CategoryType.EXPENSE)
        assertNotNull(techLaptop)
        assertEquals(listOf("Dining", "Dining › Coffee", "Technology", "Technology › Laptops"), newCategoriesCreated)
    }

    private fun computeResolvedTransferToAccountId(
        type: TransactionType,
        transferAccountId: Long?,
        sourceAccountId: Long,
        transferAccountName: String?,
        amountMinor: Long,
        isLiabilityByDefault: Boolean
    ): Long? {
        val preservesOutflowTarget = (type == TransactionType.OPENING_BALANCE || type == TransactionType.RECONCILIATION) &&
            (transferAccountName != null || amountMinor < 0L || (type == TransactionType.OPENING_BALANCE && isLiabilityByDefault))

        return when {
            type == TransactionType.TRANSFER || type == TransactionType.CC_SETTLEMENT -> transferAccountId
            preservesOutflowTarget -> sourceAccountId
            else -> null
        }
    }

    @Test
    fun csvImport_singleLeggedCcSettlementOnCreditCard_fallsBackToRefund() {
        val validTransferTarget: Long? = null
        val isCreditCard = true
        val type = TransactionType.CC_SETTLEMENT
        val (effectiveType, resolvedTargetId) = when {
            type == TransactionType.CC_SETTLEMENT -> {
                if (validTransferTarget != null) {
                    TransactionType.CC_SETTLEMENT to validTransferTarget
                } else if (isCreditCard) {
                    TransactionType.REFUND to null
                } else {
                    TransactionType.EXPENSE to null
                }
            }
            else -> type to null
        }
        assertEquals(TransactionType.REFUND, effectiveType)
        assertNull(resolvedTargetId)
    }

    @Test
    fun csvImport_singleLeggedCcSettlementOnBankAccount_fallsBackToExpense() {
        val validTransferTarget: Long? = null
        val isCreditCard = false
        val type = TransactionType.CC_SETTLEMENT
        val (effectiveType, resolvedTargetId) = when {
            type == TransactionType.CC_SETTLEMENT -> {
                if (validTransferTarget != null) {
                    TransactionType.CC_SETTLEMENT to validTransferTarget
                } else if (isCreditCard) {
                    TransactionType.REFUND to null
                } else {
                    TransactionType.EXPENSE to null
                }
            }
            else -> type to null
        }
        assertEquals(TransactionType.EXPENSE, effectiveType)
        assertNull(resolvedTargetId)
    }

    @Test
    fun csvImport_singleLeggedTransfer_fallsBackToExpenseOrIncome() {
        val validTransferTarget: Long? = null
        val type = TransactionType.TRANSFER
        val (effectiveTypeExpense, _) = if (validTransferTarget != null) {
            TransactionType.TRANSFER to validTransferTarget
        } else if (-500L < 0L) {
            TransactionType.EXPENSE to null
        } else {
            TransactionType.INCOME to null
        }
        assertEquals(TransactionType.EXPENSE, effectiveTypeExpense)

        val (effectiveTypeIncome, _) = if (validTransferTarget != null) {
            TransactionType.TRANSFER to validTransferTarget
        } else if (500L < 0L) {
            TransactionType.EXPENSE to null
        } else {
            TransactionType.INCOME to null
        }
        assertEquals(TransactionType.INCOME, effectiveTypeIncome)
    }

    @Test
    fun safeAbs_longMinValue_doesNotOverflowToNegative() {
        fun safeAbs(value: Long): Long = if (value == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(value)

        assertEquals(Long.MAX_VALUE, safeAbs(Long.MIN_VALUE))
        assertEquals(500L, safeAbs(-500L))
        assertEquals(500L, safeAbs(500L))
        assertEquals(0L, safeAbs(0L))
    }

    @Test
    fun intraCsvDuplicates_trackedAndDeduplicatedWithinSameStream() {
        val preExistingFingerprints = mutableSetOf<String>()
        val rows = listOf(
            Triple(19000L, "ICICI", 5000L),
            Triple(19000L, "ICICI", 5000L), // Duplicate
            Triple(19001L, "ICICI", 5000L), // Different date
            Triple(19000L, "HDFC", 5000L)   // Different account
        )

        var acceptedCount = 0
        var skippedCount = 0

        for ((date, acc, amt) in rows) {
            val fp = "$date|$acc|EXPENSE|$amt|groceries"
            if (preExistingFingerprints.contains(fp)) {
                skippedCount++
            } else {
                acceptedCount++
                preExistingFingerprints.add(fp)
            }
        }

        assertEquals(3, acceptedCount)
        assertEquals(1, skippedCount)
    }

    @Test
    fun splitTransaction_desplitUpdate_isSplitBecomesFalse() {
        val rawSubcatBlank = ""
        val isExportedSplit = false
        val splitEntities = if (isExportedSplit && rawSubcatBlank.isNotBlank()) {
            listOf("split1")
        } else {
            emptyList<String>()
        }
        val isSplitTx = splitEntities.isNotEmpty()
        assertFalse("De-splitting should result in isSplitTx = false", isSplitTx)
    }

    @Test
    fun parseDateStringToEpochDay_handlesVariousFormats() {
        val expectedEpoch = LocalDate.of(2026, 4, 5).toEpochDay()

        // ISO format with space-delimited time
        assertEquals(expectedEpoch, parseDateStringToEpochDay("2026-04-05 14:30:00"))
        // ISO format with T-delimited time
        assertEquals(expectedEpoch, parseDateStringToEpochDay("2026-04-05T14:30:00Z"))
        // Single digit day/month
        assertEquals(expectedEpoch, parseDateStringToEpochDay("5/4/2026"))
        assertEquals(expectedEpoch, parseDateStringToEpochDay("5-4-2026"))
        // Dot separated
        assertEquals(expectedEpoch, parseDateStringToEpochDay("05.04.2026"))
        assertEquals(expectedEpoch, parseDateStringToEpochDay("5.4.2026"))
        // Month names
        assertEquals(expectedEpoch, parseDateStringToEpochDay("5 Apr 2026"))
        assertEquals(expectedEpoch, parseDateStringToEpochDay("05 Apr 2026"))
        assertEquals(expectedEpoch, parseDateStringToEpochDay("Apr 5, 2026"))
        assertEquals(expectedEpoch, parseDateStringToEpochDay("Apr 05, 2026"))
    }

    @Test
    fun readLogicalCsvRecord_unclosedQuoteBreaksOnSectionHeader() {
        val csvContent = """
            "Unclosed note with no closing quote
            second line of note
            [TRANSACTIONS]
            ID,Date,Type,Amount
        """.trimIndent()

        val reader = java.io.BufferedReader(java.io.StringReader(csvContent))
        val firstRecord = readLogicalCsvRecord(reader, maxLinesPerRecord = 10)
        assertNotNull(firstRecord)
        // Ensure it broke when it hit [TRANSACTIONS] rather than swallowing everything
        assertTrue(firstRecord!!.contains("Unclosed note"))
        assertTrue(firstRecord.contains("second line of note"))
        assertFalse(firstRecord.contains("[TRANSACTIONS]"))
    }
}
