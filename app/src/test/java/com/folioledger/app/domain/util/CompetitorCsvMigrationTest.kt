package com.folioledger.app.domain.util

import com.folioledger.app.domain.model.CsvImportPreset
import com.folioledger.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class CompetitorCsvMigrationTest {

    // ------------------------------------------------------------------------
    // 1. Ivy Wallet: UTF-16BE BOM, Transfer Amount, Adjust Balance, Dust & Scheduled
    // ------------------------------------------------------------------------
    @Test
    fun testIvyWallet_fullMigration() {
        // Verbatim fixture:
        // Header: id,date,type,account,currency,title,description,amount,category,transferamount,toaccount,receiveamount,receivecurrency,duedate
        val csvText = buildString {
            append("id,date,type,account,currency,title,description,amount,category,transferamount,toaccount,receiveamount,receivecurrency,duedate\n")
            // Row 1: Expense
            append("1,2025-01-15,EXPENSE,Main Bank,USD,Coffee,Morning latte,4.50,Food & Drinks,,,,,\n")
            // Row 2: Quoted Income
            append("2,2025-01-16,INCOME,Main Bank,USD,Client Payout,\"Freelance dev, mobile app\",3250.00,Salary,,,,,\n")
            // Row 3: Same-currency Transfer (amount is 0.00, read transfer amount 200.00)
            append("3,2025-01-17,TRANSFER,Main Bank,USD,ATM Cash,,0.00,,200.00,Wallet,200.00,USD,\n")
            // Row 4: Cross-currency Transfer (USD -> EUR)
            append("4,2025-01-18,TRANSFER,Main Bank,USD,Euro Travel,,0.00,,200.00,Euro Account,184.50,EUR,\n")
            // Row 5: Adjust balance -> RECONCILIATION
            append("5,2025-01-19,EXPENSE,Main Bank,USD,Adjust balance,,500.00,,,,,\n")
            // Row 6: Multiline description & escaped quotes
            append("6,2025-01-20,EXPENSE,Main Bank,USD,\"Dinner with \"\"Friends\"\"\",\"Great pasta\nSplit bill with Bob\",45.00,Food & Drinks,,,,,\n")
            // Row 7: Crypto sub-cent dust (rounded 0.00) -> skipped
            append("7,2025-01-21,EXPENSE,Main Bank,USD,Dust fee,,0.00,Fees,,,,,\n")
            // Row 8: Blank Date + Populated Due Date -> skipped scheduled
            append("8,,EXPENSE,Main Bank,USD,Future Rent,,1200.00,Housing,,,,,2025-02-01\n")
        }

        // Encode as UTF-16BE with 0xFE, 0xFF BOM
        val baos = ByteArrayOutputStream()
        baos.write(0xFE)
        baos.write(0xFF)
        baos.write(csvText.toByteArray(StandardCharsets.UTF_16BE))
        val inputStream = ByteArrayInputStream(baos.toByteArray())

        val reader = CompetitorCsvParser.openBomAwareReader(inputStream)
        val headerLine = reader.readLine()
        assertNotNull(headerLine)

        val delimiter = CompetitorCsvParser.detectCsvDelimiter(headerLine)
        val headers = CompetitorCsvParser.parseCsvLineWithDelimiter(headerLine, delimiter)
        val detected = CompetitorCsvParser.detectPresetFromHeader(headerLine, headers)
        assertEquals(CsvImportPreset.IVY_WALLET, detected)

        val result = CompetitorCsvParser.parseCompetitorCsv(reader, headerLine, detected, "USD")

        assertEquals(1, result.skippedScheduledCount)
        assertEquals(5, result.transactions.size) // Rows 1, 2, 3, 5, 6 (Row 4 cross-currency flagged/skipped or handled, Row 7 dust skipped, Row 8 scheduled skipped)

        // Row 1: Expense 4.50 -> 450L
        val tx1 = result.transactions[0]
        assertEquals(TransactionType.EXPENSE, tx1.type)
        assertEquals(450L, tx1.amountMinor)
        assertEquals("Coffee", tx1.payeeOrDescription)
        assertEquals("Food & Drinks", tx1.categoryName)

        // Row 2: Income 3250.00 -> 325000L
        val tx2 = result.transactions[1]
        assertEquals(TransactionType.INCOME, tx2.type)
        assertEquals(325000L, tx2.amountMinor)
        assertEquals("Client Payout", tx2.payeeOrDescription)
        assertEquals("Freelance dev, mobile app", tx2.note)

        // Row 3: Transfer 200.00 -> 20000L, Bank -> Wallet
        val tx3 = result.transactions[2]
        assertEquals(TransactionType.TRANSFER, tx3.type)
        assertEquals(20000L, tx3.amountMinor)
        assertEquals("Main Bank", tx3.accountName)
        assertEquals("Wallet", tx3.transferToAccountName)
        assertNull(tx3.categoryName)

        // Row 5: Adjust balance -> RECONCILIATION
        val tx5 = result.transactions[3]
        assertEquals(TransactionType.RECONCILIATION, tx5.type)
        assertEquals(50000L, tx5.amountMinor)
        assertTrue(tx5.isOutflowReconciliation)
        assertNull(tx5.categoryName)

        // Row 6: Multiline
        val tx6 = result.transactions[4]
        assertEquals(TransactionType.EXPENSE, tx6.type)
        assertEquals(4500L, tx6.amountMinor)
        assertEquals("Dinner with \"Friends\"", tx6.payeeOrDescription)
        assertEquals("Great pasta\nSplit bill with Bob", tx6.note)
    }

    // ------------------------------------------------------------------------
    // 2. Cashew: Pair Consolidation, Rollover, Localized Balances, Subcategories
    // ------------------------------------------------------------------------
    @Test
    fun testCashew_fullMigration() {
        val csvText = buildString {
            append("account,category name,subcategory name,amount,title,note,date,currency,income,icon name,color\n")
            // Row 1: Expense with subcategory
            append("Checking,Groceries,Organic,25.50,Whole Foods,Healthy snacks,2025-01-10T12:00:00.000Z,USD,false,shopping,0xff10b981\n")
            // Row 2: Income
            append("Checking,Salary,,2500.00,Employer,Monthly Pay,2025-01-10T13:00:00.000Z,USD,true,salary,0xff059669\n")
            // Rows 3 & 4: Same-currency transfer pair (:30 outflow, :31 inflow)
            append("Checking,Balance Correction,,-500.00,Transfer,Checking → Savings,2025-01-11T14:00:30.000Z,USD,false,charts.png,0xff607d8b\n")
            append("Savings,Balance Correction,,500.00,Transfer,Checking → Savings,2025-01-11T14:00:31.000Z,USD,true,charts.png,0xff607d8b\n")
            // Rows 5 & 6: Cross-currency transfer pair (USD vs EUR) -> skipped
            append("Checking,Balance Correction,,-200.00,Transfer,Checking → Euro,2025-01-12T15:00:30.000Z,USD,false,charts.png,0xff607d8b\n")
            append("Euro Travel,Balance Correction,,184.50,Transfer,Checking → Euro,2025-01-12T15:00:31.000Z,EUR,true,charts.png,0xff607d8b\n")
            // Row 7: Unpaired Balance Correction -> RECONCILIATION
            append("Checking,Balance Correction,,1500.00,Updated Total Balance,Manual reset,2025-01-13T16:00:00.000Z,USD,true,charts.png,0xff607d8b\n")
        }

        val reader = CompetitorCsvParser.openBomAwareReader(ByteArrayInputStream(csvText.toByteArray()))
        val headerLine = reader.readLine()
        assertNotNull(headerLine)

        val delimiter = CompetitorCsvParser.detectCsvDelimiter(headerLine)
        val headers = CompetitorCsvParser.parseCsvLineWithDelimiter(headerLine, delimiter)
        val detected = CompetitorCsvParser.detectPresetFromHeader(headerLine, headers)
        assertEquals(CsvImportPreset.CASHEW, detected)

        val result = CompetitorCsvParser.parseCompetitorCsv(reader, headerLine, detected, "USD")

        assertEquals(1, result.consolidatedTransferCount)
        assertEquals(4, result.transactions.size) // Expense, Income, Consolidated Transfer, Reconciliation

        val tx1 = result.transactions[0]
        assertEquals("Groceries", tx1.categoryName)
        assertEquals("Organic", tx1.subCategoryName)
        assertEquals(2550L, tx1.amountMinor)

        val transferTx = result.transactions.first { it.type == TransactionType.TRANSFER }
        assertEquals("Checking", transferTx.accountName)
        assertEquals("Savings", transferTx.transferToAccountName)
        assertEquals(50000L, transferTx.amountMinor)
        assertNull(transferTx.categoryName)

        val reconTx = result.transactions.first { it.type == TransactionType.RECONCILIATION }
        assertEquals(150000L, reconTx.amountMinor)
        assertEquals(false, reconTx.isOutflowReconciliation)
    }

    @Test
    fun testCashew_secondRolloverAndLocalizedCategory() {
        val csvText = buildString {
            append("account,category name,subcategory name,amount,title,note,date,currency,income,icon name,color\n")
            // 61-second minute rollover (:59 -> :00) with non-English category name
            append("Account A,Ajuste de saldo,,-300.00,Transfer,Note A,2025-02-01T10:14:59.000Z,USD,false,charts.png,0xff607d8b\n")
            append("Account B,Ajuste de saldo,,300.00,Transfer,Note A,2025-02-01T10:15:00.000Z,USD,true,charts.png,0xff607d8b\n")
        }

        val reader = CompetitorCsvParser.openBomAwareReader(ByteArrayInputStream(csvText.toByteArray()))
        val headerLine = reader.readLine()!!
        val result = CompetitorCsvParser.parseCompetitorCsv(reader, headerLine, CsvImportPreset.CASHEW, "USD")

        assertEquals(1, result.consolidatedTransferCount)
        assertEquals(1, result.transactions.size)
        val tx = result.transactions[0]
        assertEquals(TransactionType.TRANSFER, tx.type)
        assertEquals("Account A", tx.accountName)
        assertEquals("Account B", tx.transferToAccountName)
        assertEquals(30000L, tx.amountMinor)
    }

    // ------------------------------------------------------------------------
    // 3. Realbyte Money Manager: Tab-Delimited, MDY Order, Duplicate Account Header
    // ------------------------------------------------------------------------
    @Test
    fun testRealbyteMoneyManager() {
        val tsvText = buildString {
            append("Period\tAccount\tCategory\tSubcategory\tNote\tDescription\tIncome/Expense\tAmount\tAccount\tCurrency\n")
            // Transfer-out row: destination is Category
            append("03/15/2025\tChecking\tSavings\t\tATM Transfer\tCash out\tTransfer-Out\t150.00\tChecking\tUSD\n")
            // Companion transfer-in row (should be deduplicated or skipped)
            append("03/15/2025\tSavings\tChecking\t\tATM Transfer\tCash out\tTransfer-In\t150.00\tSavings\tUSD\n")
            // Standard expense with slash date 03/05/2025
            append("03/05/2025\tChecking\tFood\tLunch\tDiner\tBurger\tExpense\t18.50\tChecking\tUSD\n")
        }

        val reader = CompetitorCsvParser.openBomAwareReader(ByteArrayInputStream(tsvText.toByteArray()))
        val headerLine = reader.readLine()!!
        val delimiter = CompetitorCsvParser.detectCsvDelimiter(headerLine)
        assertEquals('\t', delimiter)

        val headers = CompetitorCsvParser.parseCsvLineWithDelimiter(headerLine, delimiter)
        val detected = CompetitorCsvParser.detectPresetFromHeader(headerLine, headers)
        assertEquals(CsvImportPreset.REALBYTE_MONEY_MANAGER, detected)

        val result = CompetitorCsvParser.parseCompetitorCsv(reader, headerLine, detected, "USD")
        assertEquals(2, result.transactions.size) // 1 Transfer, 1 Expense

        val transferTx = result.transactions.first { it.type == TransactionType.TRANSFER }
        assertEquals("Checking", transferTx.accountName)
        assertEquals("Savings", transferTx.transferToAccountName)
        assertEquals(15000L, transferTx.amountMinor)
    }

    // ------------------------------------------------------------------------
    // 4. Wallet by BudgetBakers: Semicolon Delimiter, Plural "Expenses", Tags
    // ------------------------------------------------------------------------
    @Test
    fun testWalletBudgetBakers() {
        val csvText = buildString {
            append("account;category;currency;amount;ref_currency_amount;type;note;date;transfer;payee;labels\n")
            append("Bank;Food;USD;12.50;12.50;Expenses;\"Lunch, coffee\";2025-01-20;false;Cafe;work|travel\n")
            // Transfer legs
            append("Bank;;USD;-100.00;-100.00;Expenses;;2025-01-21;true;;finance\n")
            append("Cash;;USD;100.00;100.00;Income;;2025-01-21;true;;finance\n")
        }

        val reader = CompetitorCsvParser.openBomAwareReader(ByteArrayInputStream(csvText.toByteArray()))
        val headerLine = reader.readLine()!!
        val delimiter = CompetitorCsvParser.detectCsvDelimiter(headerLine)
        assertEquals(';', delimiter)

        val headers = CompetitorCsvParser.parseCsvLineWithDelimiter(headerLine, delimiter)
        val detected = CompetitorCsvParser.detectPresetFromHeader(headerLine, headers)
        assertEquals(CsvImportPreset.WALLET_BUDGETBAKERS, detected)

        val result = CompetitorCsvParser.parseCompetitorCsv(reader, headerLine, detected, "USD")
        assertEquals(1, result.consolidatedTransferCount)
        assertEquals(2, result.transactions.size)

        val expTx = result.transactions.first { it.type == TransactionType.EXPENSE }
        assertEquals(listOf("work", "travel"), expTx.tags)
        assertEquals("Lunch, coffee", expTx.note)
    }

    // ------------------------------------------------------------------------
    // 5. Money Lover: Semicolon, Outgoing/Incoming Transfer Pair
    // ------------------------------------------------------------------------
    @Test
    fun testMoneyLover() {
        val csvText = buildString {
            append("Date;Wallet;Category;Amount;Currency;Note;Event;Id\n")
            append("15/01/2025;Personal;Outgoing transfer;-75.00;USD;Move funds;Trip;101\n")
            append("15/01/2025;Savings;Incoming transfer;75.00;USD;Move funds;Trip;102\n")
            append("16/01/2025;Personal;Dining;-22.00;USD;Sushi;;103\n")
        }

        val reader = CompetitorCsvParser.openBomAwareReader(ByteArrayInputStream(csvText.toByteArray()))
        val headerLine = reader.readLine()!!
        val headers = CompetitorCsvParser.parseCsvLineWithDelimiter(headerLine, ';')
        val detected = CompetitorCsvParser.detectPresetFromHeader(headerLine, headers)
        assertEquals(CsvImportPreset.MONEY_LOVER, detected)

        val result = CompetitorCsvParser.parseCompetitorCsv(reader, headerLine, detected, "USD")
        assertEquals(1, result.consolidatedTransferCount)
        assertEquals(2, result.transactions.size)

        val transferTx = result.transactions.first { it.type == TransactionType.TRANSFER }
        assertEquals("Personal", transferTx.accountName)
        assertEquals("Savings", transferTx.transferToAccountName)
        assertEquals(7500L, transferTx.amountMinor)
    }

    // ------------------------------------------------------------------------
    // 6. YNAB: Split Outflow / Inflow, Starting Balance Reconciliation
    // ------------------------------------------------------------------------
    @Test
    fun testYnab() {
        val csvText = buildString {
            append("Account,Flag,Date,Payee,Category Group/Category,Category Group,Category,Memo,Outflow,Inflow,Cleared\n")
            append("Checking,,01/15/2025,Starting Balance,,,,Initial funds,$0.00,$1500.00,Cleared\n")
            append("Checking,,01/16/2025,Transfer : Savings,,,,Savings deposit,$250.00,$0.00,Cleared\n")
            append("Savings,,01/16/2025,Transfer : Checking,,,,Savings deposit,$0.00,$250.00,Cleared\n")
            append("Checking,,01/17/2025,Grocery Store,Everyday Expenses: Groceries,Everyday Expenses,Groceries,Snacks,$45.50,$0.00,Cleared\n")
        }

        val reader = CompetitorCsvParser.openBomAwareReader(ByteArrayInputStream(csvText.toByteArray()))
        val headerLine = reader.readLine()!!
        val headers = CompetitorCsvParser.parseCsvLineWithDelimiter(headerLine, ',')
        val detected = CompetitorCsvParser.detectPresetFromHeader(headerLine, headers)
        assertEquals(CsvImportPreset.YNAB, detected)

        val result = CompetitorCsvParser.parseCompetitorCsv(reader, headerLine, detected, "USD")
        assertEquals(1, result.consolidatedTransferCount)
        assertEquals(3, result.transactions.size) // Recon, Transfer, Expense

        val reconTx = result.transactions.first { it.type == TransactionType.RECONCILIATION }
        assertEquals(150000L, reconTx.amountMinor)
        assertEquals(false, reconTx.isOutflowReconciliation)

        val transferTx = result.transactions.first { it.type == TransactionType.TRANSFER }
        assertEquals("Checking", transferTx.accountName)
        assertEquals("Savings", transferTx.transferToAccountName)
        assertEquals(25000L, transferTx.amountMinor)

        val expTx = result.transactions.first { it.type == TransactionType.EXPENSE }
        assertEquals("Everyday Expenses", expTx.categoryName)
        assertEquals("Groceries", expTx.subCategoryName)
        assertEquals(4550L, expTx.amountMinor)
    }

    // ------------------------------------------------------------------------
    // 7. Smoke Tests for the Remaining 8 Presets
    // ------------------------------------------------------------------------
    @Test
    fun testMint() {
        val csvText = buildString {
            append("\"Date\",\"Description\",\"Original Description\",\"Amount\",\"Transaction Type\",\"Category\",\"Account Name\",\"Labels\",\"Notes\"\n")
            append("\"1/15/2025\",\"Target\",\"TARGET 0123\",\"42.50\",\"debit\",\"Shopping\",\"Chase Credit\",\"clothing\",\"New jacket\"\n")
            append("\"1/16/2025\",\"Payroll\",\"DIRECT DEP\",\"2100.00\",\"credit\",\"Income\",\"Chase Checking\",\"\",\"\"\n")
        }

        val reader = CompetitorCsvParser.openBomAwareReader(ByteArrayInputStream(csvText.toByteArray()))
        val headerLine = reader.readLine()!!
        val headers = CompetitorCsvParser.parseCsvLineWithDelimiter(headerLine, ',')
        val detected = CompetitorCsvParser.detectPresetFromHeader(headerLine, headers)
        assertEquals(CsvImportPreset.INTUIT_MINT, detected)

        val result = CompetitorCsvParser.parseCompetitorCsv(reader, headerLine, detected, "USD")
        assertEquals(2, result.transactions.size)
        assertEquals(TransactionType.EXPENSE, result.transactions[0].type)
        assertEquals(TransactionType.INCOME, result.transactions[1].type)
        assertEquals(listOf("clothing"), result.transactions[0].tags)
    }

    @Test
    fun testMonefy() {
        val csvText = buildString {
            append("Date,Account,Category,Amount,Currency,Converted Amount,Converted Currency,Description\n")
            append("15/01/2025,Cash,Bills,-45.00,USD,-45.00,USD,Electric bill\n")
            append("16/01/2025,Cash,Salary,1200.00,USD,1200.00,USD,Bonus\n")
        }

        val reader = CompetitorCsvParser.openBomAwareReader(ByteArrayInputStream(csvText.toByteArray()))
        val headerLine = reader.readLine()!!
        val headers = CompetitorCsvParser.parseCsvLineWithDelimiter(headerLine, ',')
        val detected = CompetitorCsvParser.detectPresetFromHeader(headerLine, headers)
        assertEquals(CsvImportPreset.MONEFY, detected)

        val result = CompetitorCsvParser.parseCompetitorCsv(reader, headerLine, detected, "USD")
        assertEquals(2, result.transactions.size)
        assertEquals(TransactionType.EXPENSE, result.transactions[0].type)
        assertEquals(TransactionType.INCOME, result.transactions[1].type)
    }

    @Test
    fun testOneMoney() {
        val csvText = buildString {
            append("Date,Type,From Account,To Account / Category,Amount,Currency,Note\n")
            append("2025-01-15,EXPENSE,Wallet,Food,15.00,USD,Lunch\n")
            append("2025-01-16,TRANSFER,Bank,Wallet,100.00,USD,ATM\n")
        }

        val reader = CompetitorCsvParser.openBomAwareReader(ByteArrayInputStream(csvText.toByteArray()))
        val headerLine = reader.readLine()!!
        val headers = CompetitorCsvParser.parseCsvLineWithDelimiter(headerLine, ',')
        val detected = CompetitorCsvParser.detectPresetFromHeader(headerLine, headers)
        assertEquals(CsvImportPreset.ONE_MONEY, detected)

        val result = CompetitorCsvParser.parseCompetitorCsv(reader, headerLine, detected, "USD")
        assertEquals(2, result.transactions.size)
        assertEquals(TransactionType.EXPENSE, result.transactions[0].type)
        val transferTx = result.transactions[1]
        assertEquals(TransactionType.TRANSFER, transferTx.type)
        assertEquals("Bank", transferTx.accountName)
        assertEquals("Wallet", transferTx.transferToAccountName)
    }

    @Test
    fun testFinancisto() {
        val csvText = buildString {
            append("date,time,account,original amount,currency,category,payee,note,parent\n")
            append("2025-01-15,10:30,Bank,-50.00,USD,Transfer,Transfer,To Cash,\n")
            append("2025-01-15,10:30,Cash,50.00,USD,Transfer,Transfer,To Cash,\n")
        }

        val reader = CompetitorCsvParser.openBomAwareReader(ByteArrayInputStream(csvText.toByteArray()))
        val headerLine = reader.readLine()!!
        val headers = CompetitorCsvParser.parseCsvLineWithDelimiter(headerLine, ',')
        val detected = CompetitorCsvParser.detectPresetFromHeader(headerLine, headers)
        assertEquals(CsvImportPreset.FINANCISTO, detected)

        val result = CompetitorCsvParser.parseCompetitorCsv(reader, headerLine, detected, "USD")
        assertEquals(1, result.consolidatedTransferCount)
        assertEquals(1, result.transactions.size)
        assertEquals(TransactionType.TRANSFER, result.transactions[0].type)
    }

    @Test
    fun testBluecoins() {
        val csvText = buildString {
            append("Date,Type,Amount,Category Group,Category,Account,Notes,Tags,Exchange Rate\n")
            append("2025-01-15,Expense,18.00,Food,Dining,Card,Pizza,,1.0\n")
        }

        val reader = CompetitorCsvParser.openBomAwareReader(ByteArrayInputStream(csvText.toByteArray()))
        val headerLine = reader.readLine()!!
        val headers = CompetitorCsvParser.parseCsvLineWithDelimiter(headerLine, ',')
        val detected = CompetitorCsvParser.detectPresetFromHeader(headerLine, headers)
        assertEquals(CsvImportPreset.BLUECOINS, detected)

        val result = CompetitorCsvParser.parseCompetitorCsv(reader, headerLine, detected, "USD")
        assertEquals(1, result.transactions.size)
        assertEquals(1800L, result.transactions[0].amountMinor)
    }

    @Test
    fun testSpendee() {
        val csvText = buildString {
            append("Date,Wallet,Type,Category Name,Amount,Currency,Note\n")
            append("2025-01-15T08:00:00Z,Main,Expense,Coffee,4.00,USD,Morning brew\n")
        }

        val reader = CompetitorCsvParser.openBomAwareReader(ByteArrayInputStream(csvText.toByteArray()))
        val headerLine = reader.readLine()!!
        val headers = CompetitorCsvParser.parseCsvLineWithDelimiter(headerLine, ',')
        val detected = CompetitorCsvParser.detectPresetFromHeader(headerLine, headers)
        assertEquals(CsvImportPreset.SPENDEE, detected)

        val result = CompetitorCsvParser.parseCompetitorCsv(reader, headerLine, detected, "USD")
        assertEquals(1, result.transactions.size)
        assertEquals(400L, result.transactions[0].amountMinor)
    }

    @Test
    fun testFortuneCity() {
        val csvText = buildString {
            append("Date,Account,Category,Amount,Note\n")
            append("15-01-25 14:30,Default,Food,12.00,Fast food (FortuneCity export)\n")
        }

        val reader = CompetitorCsvParser.openBomAwareReader(ByteArrayInputStream(csvText.toByteArray()))
        val headerLine = reader.readLine()!!
        val result = CompetitorCsvParser.parseCompetitorCsv(reader, headerLine, CsvImportPreset.FORTUNE_CITY, "USD")
        assertEquals(1, result.transactions.size)
        assertEquals(1200L, result.transactions[0].amountMinor)
    }
}
