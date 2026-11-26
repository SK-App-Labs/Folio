/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.domain.util

import com.folioledger.app.domain.model.CsvImportPreset
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.util.CompetitorCsvParser.DateOrder
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
    // 0. Amount Parsing Unit Tests (Critical Finding 1 verification)
    // ------------------------------------------------------------------------
    @Test
    fun testParseAmountToMinor_variousFormats() {
        // Standard decimal with currency symbols
        assertEquals(12050L, CompetitorCsvParser.parseAmountToMinor("$120.50", 2))
        assertEquals(-4550L, CompetitorCsvParser.parseAmountToMinor("-45.50", 2))

        // 1000x bug check: thousands-comma integer without decimal point
        assertEquals(100000L, CompetitorCsvParser.parseAmountToMinor("1,000", 2))
        assertEquals(2500000L, CompetitorCsvParser.parseAmountToMinor("25,000", 2))

        // Thousands comma with decimal point
        assertEquals(125050L, CompetitorCsvParser.parseAmountToMinor("1,250.50", 2))

        // European dot-thousands and comma-decimals
        assertEquals(125050L, CompetitorCsvParser.parseAmountToMinor("1.250,50", 2))
        assertEquals(4550L, CompetitorCsvParser.parseAmountToMinor("45,50", 2))

        // Zero-decimal currencies (JPY, KRW, VND)
        val jpyFraction = SupportedCurrencies.fromCode("JPY").fractionDigits
        assertEquals(0, jpyFraction)
        assertEquals(1500L, CompetitorCsvParser.parseAmountToMinor("1500", jpyFraction))
        assertEquals(25000L, CompetitorCsvParser.parseAmountToMinor("25,000", jpyFraction))

        // 3-decimal currencies (KWD, BHD, OMR)
        val kwdFraction = SupportedCurrencies.fromCode("KWD").fractionDigits
        assertEquals(3, kwdFraction)
        assertEquals(12345L, CompetitorCsvParser.parseAmountToMinor("12.345", kwdFraction))

        // Leading/trailing 3-letter ISO code stripping
        assertEquals(12050L, CompetitorCsvParser.parseAmountToMinor("USD 120.50", 2))
        assertEquals(12050L, CompetitorCsvParser.parseAmountToMinor("120.50 EUR", 2))
    }

    @Test
    fun testParseDateAndSeconds_12HourAmPm() {
        // DMY order: "15/01/2025 2:30 PM"
        val dmyResult = CompetitorCsvParser.parseDateAndSeconds("15/01/2025 2:30 PM", DateOrder.DMY)
        assertNotNull(dmyResult)
        val expectedDate = java.time.LocalDate.of(2025, 1, 15).toEpochDay()
        assertEquals(expectedDate, dmyResult!!.first)
        val expectedEpochSeconds = java.time.LocalDateTime.of(2025, 1, 15, 14, 30, 0)
            .toEpochSecond(java.time.ZoneOffset.UTC)
        assertEquals(expectedEpochSeconds, dmyResult.second)

        // MDY order: "01/15/2025 2:30:15 PM"
        val mdyResult = CompetitorCsvParser.parseDateAndSeconds("01/15/2025 2:30:15 PM", DateOrder.MDY)
        assertNotNull(mdyResult)
        assertEquals(expectedDate, mdyResult!!.first)
        val expectedEpochSecondsWithSecs = java.time.LocalDateTime.of(2025, 1, 15, 14, 30, 15)
            .toEpochSecond(java.time.ZoneOffset.UTC)
        assertEquals(expectedEpochSecondsWithSecs, mdyResult.second)
    }

    // ------------------------------------------------------------------------
    // 1. Ivy Wallet: Verbatim 15-col format, UTF-16BE BOM, Transfer Amount, Adjust Balance, Dust & Scheduled
    // ------------------------------------------------------------------------
    @Test
    fun testIvyWallet_fullMigration() {
        // Verbatim Ivy Wallet 15-column format:
        // Date,Title,Category,Account,Amount,Currency,Type,Transfer Amount,Transfer Currency,To Account,Receive Amount,Receive Currency,Description,Due Date,ID
        val csvText = buildString {
            append("Date,Title,Category,Account,Amount,Currency,Type,Transfer Amount,Transfer Currency,To Account,Receive Amount,Receive Currency,Description,Due Date,ID\n")
            // Row 1: Expense with ID = 1 (Verifies ID=1 is never mistaken for unpaid=1)
            append("2025-01-15,Coffee,Food & Drinks,Main Bank,4.50,USD,EXPENSE,,,,,,Morning latte,,1\n")
            // Row 2: Quoted Income with thousands comma "3,250.00"
            append("2025-01-16,Client Payout,Salary,Main Bank,\"3,250.00\",USD,INCOME,,,,,,\"Freelance dev, mobile app\",,2\n")
            // Row 3: Same-currency Transfer (amount is 0.00, read transfer amount 200.00)
            append("2025-01-17,ATM Cash,,Main Bank,0.00,USD,TRANSFER,200.00,USD,Wallet,200.00,USD,,,3\n")
            // Row 4: Cross-currency Transfer (USD -> EUR) -> skipped
            append("2025-01-18,Euro Travel,,Main Bank,0.00,USD,TRANSFER,200.00,USD,Euro Account,184.50,EUR,,,4\n")
            // Row 5: Adjust balance -> RECONCILIATION
            append("2025-01-19,Adjust balance,,Main Bank,500.00,USD,EXPENSE,,,,,,,,5\n")
            // Row 6: Multiline description & escaped quotes
            append("2025-01-20,\"Dinner with \"\"Friends\"\"\",Food & Drinks,Main Bank,45.00,USD,EXPENSE,,,,,,\"Great pasta\nSplit bill with Bob\",,6\n")
            // Row 7: Crypto sub-cent dust (rounded 0.00) -> skipped
            append("2025-01-21,Dust fee,Fees,Main Bank,0.00,USD,EXPENSE,,,,,,,,7\n")
            // Row 8: Blank Date + Populated Due Date -> skipped scheduled
            append(",Future Rent,Housing,Main Bank,1200.00,USD,EXPENSE,,,,,,,2025-02-01,8\n")
            // Row 9: V1/V2 Transfer without Transfer Currency and Receive Currency columns
            append("2025-01-22,Internal Move,,Main Bank,0.00,USD,TRANSFER,150.00,,Savings,150.00,,,,9\n")
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

        assertEquals(1, result.skippedScheduledCount) // Row 8 (due date with blank date)
        assertEquals(6, result.transactions.size) // Rows 1, 2, 3, 5, 6, 9

        // Row 1: Expense 4.50 -> 450L (ID=1 preserved and not dropped)
        val tx1 = result.transactions[0]
        assertEquals(TransactionType.EXPENSE, tx1.type)
        assertEquals(450L, tx1.amountMinor)
        assertEquals("Coffee", tx1.payeeOrDescription)
        assertEquals("Food & Drinks", tx1.categoryName)

        // Row 2: Income 3,250.00 -> 325000L
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

        // Row 9: V1/V2 Transfer (no transfer currency) -> falls back cleanly to account currency "USD"
        val tx9 = result.transactions[5]
        assertEquals(TransactionType.TRANSFER, tx9.type)
        assertEquals(15000L, tx9.amountMinor)
        assertEquals("Main Bank", tx9.accountName)
        assertEquals("Savings", tx9.transferToAccountName)
        assertEquals("USD", tx9.currencyCode)
    }

    // ------------------------------------------------------------------------
    // 2. Cashew: Verbatim 15-col format, Pair Consolidation, Explicit Income col, Subcategories
    // ------------------------------------------------------------------------
    @Test
    fun testCashew_fullMigration() {
        // Verbatim 15 columns:
        // account,category name,subcategory name,amount,title,note,date,currency,income,icon name,color,paired_transaction_id,reoccurring_id,reoccurring_length,reoccurring_frequency
        val csvText = buildString {
            append("account,category name,subcategory name,amount,title,note,date,currency,income,icon name,color,paired_transaction_id,reoccurring_id,reoccurring_length,reoccurring_frequency\n")
            // Row 1: Positive unsigned amount with income=false -> MUST be EXPENSE (Critical Finding 2)
            append("Checking,Groceries,Organic,25.50,Whole Foods,Healthy snacks,2025-01-10T12:00:00.000Z,USD,false,shopping,0xff10b981,,,,\n")
            // Row 2: Income
            append("Checking,Salary,,2500.00,Employer,Monthly Pay,2025-01-10T13:00:00.000Z,USD,true,salary,0xff059669,,,,\n")
            // Rows 3 & 4: Same-currency transfer pair (:30 outflow, :31 inflow)
            append("Checking,Balance Correction,,-500.00,Transfer,Checking → Savings,2025-01-11T14:00:30.000Z,USD,false,charts.png,0xff607d8b,,,,\n")
            append("Savings,Balance Correction,,500.00,Transfer,Checking → Savings,2025-01-11T14:00:31.000Z,USD,true,charts.png,0xff607d8b,,,,\n")
            // Rows 5 & 6: Cross-currency transfer pair (USD vs EUR) -> skipped
            append("Checking,Balance Correction,,-200.00,Transfer,Checking → Euro,2025-01-12T15:00:30.000Z,USD,false,charts.png,0xff607d8b,,,,\n")
            append("Euro Travel,Balance Correction,,184.50,Transfer,Checking → Euro,2025-01-12T15:00:31.000Z,EUR,true,charts.png,0xff607d8b,,,,\n")
            // Row 7: Unpaired Balance Correction -> RECONCILIATION
            append("Checking,Balance Correction,,1500.00,Updated Total Balance,Manual reset,2025-01-13T16:00:00.000Z,USD,true,charts.png,0xff607d8b,,,,\n")
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
        assertEquals(TransactionType.EXPENSE, tx1.type) // Explicitly verified
        assertEquals("Groceries", tx1.categoryName)
        assertEquals("Organic", tx1.subCategoryName)
        assertEquals(2550L, tx1.amountMinor)

        val tx2 = result.transactions[1]
        assertEquals(TransactionType.INCOME, tx2.type) // Explicitly verified
        assertEquals(250000L, tx2.amountMinor)

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
            append("account,category name,subcategory name,amount,title,note,date,currency,income,icon name,color,paired_transaction_id,reoccurring_id,reoccurring_length,reoccurring_frequency\n")
            // 1-second minute rollover (:59 -> :00) with arrow in note
            append("Account A,Ajuste de saldo,,-300.00,Transfer,Account A → Account B,2025-02-01T10:14:59.000Z,USD,false,charts.png,0xff607d8b,,,,\n")
            append("Account B,Ajuste de saldo,,300.00,Transfer,Account A → Account B,2025-02-01T10:15:00.000Z,USD,true,charts.png,0xff607d8b,,,,\n")
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
    // 3. Realbyte Money Manager: Tab-Delimited, MDY Order, Explicit Income/Expense Col
    // ------------------------------------------------------------------------
    @Test
    fun testRealbyteMoneyManager() {
        val tsvText = buildString {
            append("Period\tAccount\tCategory\tSubcategory\tNote\tDescription\tIncome/Expense\tAmount\tAccount\tCurrency\n")
            // Transfer-out row: destination is Category
            append("03/15/2025\tChecking\tSavings\t\tATM Transfer\tCash out\tTransfer-Out\t150.00\tChecking\tUSD\n")
            // Companion transfer-in row (should be deduplicated or skipped)
            append("03/15/2025\tSavings\tChecking\t\tATM Transfer\tCash out\tTransfer-In\t150.00\tSavings\tUSD\n")
            // Positive unsigned amount with Income/Expense="Expense" -> MUST be EXPENSE (Critical Finding 2)
            append("03/05/2025\tChecking\tFood\tLunch\tDiner\tBurger\tExpense\t18.50\tChecking\tUSD\n")
            // Positive unsigned amount with Income/Expense="Income" -> MUST be INCOME
            append("03/06/2025\tChecking\tSalary\tBonus\tBonus\tWork\tIncome\t500.00\tChecking\tUSD\n")
        }

        val reader = CompetitorCsvParser.openBomAwareReader(ByteArrayInputStream(tsvText.toByteArray()))
        val headerLine = reader.readLine()!!
        val delimiter = CompetitorCsvParser.detectCsvDelimiter(headerLine)
        assertEquals('\t', delimiter)

        val headers = CompetitorCsvParser.parseCsvLineWithDelimiter(headerLine, delimiter)
        val detected = CompetitorCsvParser.detectPresetFromHeader(headerLine, headers)
        assertEquals(CsvImportPreset.REALBYTE_MONEY_MANAGER, detected)

        val result = CompetitorCsvParser.parseCompetitorCsv(reader, headerLine, detected, "USD")
        assertEquals(3, result.transactions.size) // 1 Transfer, 1 Expense, 1 Income

        val transferTx = result.transactions.first { it.type == TransactionType.TRANSFER }
        assertEquals("Checking", transferTx.accountName)
        assertEquals("Savings", transferTx.transferToAccountName)
        assertEquals(15000L, transferTx.amountMinor)

        val expTx = result.transactions.first { it.type == TransactionType.EXPENSE }
        assertEquals(1850L, expTx.amountMinor)
        assertEquals("Food", expTx.categoryName)
        assertEquals("Lunch", expTx.subCategoryName)

        val incTx = result.transactions.first { it.type == TransactionType.INCOME }
        assertEquals(50000L, incTx.amountMinor)
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
        assertEquals(TransactionType.EXPENSE, expTx.type)
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

        val expTx = result.transactions.first { it.type == TransactionType.EXPENSE }
        assertEquals(2200L, expTx.amountMinor)
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
            // Split "Category Group/Category" colon parsing test
            append("Checking,,01/17/2025,Grocery Store,Everyday Expenses: Groceries,,,Snacks,$45.50,$0.00,Cleared\n")
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
    fun testFinancisto_domesticAndTransfer() {
        // High 5 verification: Domestic rows have "amount" populated, "original amount" blank
        val csvText = buildString {
            append("date,time,account,amount,original amount,currency,category,payee,note,parent\n")
            append("2025-01-15,10:30,Bank,-50.00,,USD,Transfer,Transfer,To Cash,\n")
            append("2025-01-15,10:30,Cash,50.00,,USD,Transfer,Transfer,To Cash,\n")
            append("2025-01-16,11:00,Bank,-15.50,,USD,Food,Diner,Lunch,\n")
        }

        val reader = CompetitorCsvParser.openBomAwareReader(ByteArrayInputStream(csvText.toByteArray()))
        val headerLine = reader.readLine()!!
        val headers = CompetitorCsvParser.parseCsvLineWithDelimiter(headerLine, ',')
        val detected = CompetitorCsvParser.detectPresetFromHeader(headerLine, headers)
        assertEquals(CsvImportPreset.FINANCISTO, detected)

        val result = CompetitorCsvParser.parseCompetitorCsv(reader, headerLine, detected, "USD")
        assertEquals(1, result.consolidatedTransferCount)
        assertEquals(2, result.transactions.size) // 1 Transfer, 1 Expense
        val transferTx = result.transactions.first { it.type == TransactionType.TRANSFER }
        val expTx = result.transactions.first { it.type == TransactionType.EXPENSE }
        assertEquals(TransactionType.TRANSFER, transferTx.type)
        assertEquals(TransactionType.EXPENSE, expTx.type)
        assertEquals(1550L, expTx.amountMinor)
    }

    @Test
    fun testBluecoins_groupSubcategoryAndCurrency() {
        // High 5 verification: Category Group as parent category, Category as subcategory
        val csvText = buildString {
            append("Date,Type,Amount,Category Group,Category,Account,Notes,Tags,Exchange Rate,Currency\n")
            append("2025-01-15,Expense,18.00,Food,Dining,Card,Pizza,,1.0,USD\n")
            append("2025-01-16,Income,1200.00,Earnings,Salary,Card,Payday,,1.0,USD\n")
        }

        val reader = CompetitorCsvParser.openBomAwareReader(ByteArrayInputStream(csvText.toByteArray()))
        val headerLine = reader.readLine()!!
        val headers = CompetitorCsvParser.parseCsvLineWithDelimiter(headerLine, ',')
        val detected = CompetitorCsvParser.detectPresetFromHeader(headerLine, headers)
        assertEquals(CsvImportPreset.BLUECOINS, detected)

        val result = CompetitorCsvParser.parseCompetitorCsv(reader, headerLine, detected, "USD")
        assertEquals(2, result.transactions.size)

        val tx1 = result.transactions[0]
        assertEquals(TransactionType.EXPENSE, tx1.type)
        assertEquals(1800L, tx1.amountMinor)
        assertEquals("Food", tx1.categoryName)
        assertEquals("Dining", tx1.subCategoryName)
        assertEquals("USD", tx1.currencyCode)

        val tx2 = result.transactions[1]
        assertEquals(TransactionType.INCOME, tx2.type)
        assertEquals(120000L, tx2.amountMinor)
        assertEquals("Earnings", tx2.categoryName)
        assertEquals("Salary", tx2.subCategoryName)
    }

    @Test
    fun testSpendee() {
        val csvText = buildString {
            append("Date,Wallet,Type,Category Name,Amount,Currency,Note\n")
            append("2025-01-15T08:00:00Z,Main,Expense,Coffee,4.00,USD,Morning brew\n")
            append("2025-01-16T08:00:00Z,Main,Income,Freelance,150.00,USD,Design gig\n")
        }

        val reader = CompetitorCsvParser.openBomAwareReader(ByteArrayInputStream(csvText.toByteArray()))
        val headerLine = reader.readLine()!!
        val headers = CompetitorCsvParser.parseCsvLineWithDelimiter(headerLine, ',')
        val detected = CompetitorCsvParser.detectPresetFromHeader(headerLine, headers)
        assertEquals(CsvImportPreset.SPENDEE, detected)

        val result = CompetitorCsvParser.parseCompetitorCsv(reader, headerLine, detected, "USD")
        assertEquals(2, result.transactions.size)
        assertEquals(TransactionType.EXPENSE, result.transactions[0].type)
        assertEquals(400L, result.transactions[0].amountMinor)
        assertEquals(TransactionType.INCOME, result.transactions[1].type)
        assertEquals(15000L, result.transactions[1].amountMinor)
    }

    @Test
    fun testFortuneCity() {
        // Critical Finding 2 verification: Category="Income" sets INCOME type
        val csvText = buildString {
            append("Date,Account,Category,Amount,Note\n")
            append("15-01-25 14:30,Default,Food,12.00,Fast food\n")
            append("16-01-25 10:00,Default,Income,500.00,Salary paycheck\n")
        }

        val reader = CompetitorCsvParser.openBomAwareReader(ByteArrayInputStream(csvText.toByteArray()))
        val headerLine = reader.readLine()!!
        val result = CompetitorCsvParser.parseCompetitorCsv(reader, headerLine, CsvImportPreset.FORTUNE_CITY, "USD")
        assertEquals(2, result.transactions.size)

        val tx1 = result.transactions[0]
        assertEquals(TransactionType.EXPENSE, tx1.type)
        assertEquals(1200L, tx1.amountMinor)

        val tx2 = result.transactions[1]
        assertEquals(TransactionType.INCOME, tx2.type)
        assertEquals(50000L, tx2.amountMinor)
    }

    @Test
    fun testYnab_negativeOutflowReversesToIncomeAndPositiveAmount() {
        val csvText = buildString {
            append("Account,Flag,Date,Payee,Category Group/Category,Category Group,Category,Memo,Outflow,Inflow,Cleared\n")
            append("Checking,,01/18/2025,Refund Merchant,Everyday Expenses: Groceries,,,Cashback,-$45.50,$0.00,Cleared\n")
            append("Checking,,01/19/2025,Reversal,Income,,,Fee reversal,$0.00,-$20.00,Cleared\n")
        }

        val reader = CompetitorCsvParser.openBomAwareReader(ByteArrayInputStream(csvText.toByteArray()))
        val headerLine = reader.readLine()!!
        val result = CompetitorCsvParser.parseCompetitorCsv(reader, headerLine, CsvImportPreset.YNAB, "USD")

        assertEquals(2, result.transactions.size)
        val first = result.transactions[0]
        assertEquals(TransactionType.INCOME, first.type)
        assertEquals(4550L, first.amountMinor)

        val second = result.transactions[1]
        assertEquals(TransactionType.EXPENSE, second.type)
        assertEquals(2000L, second.amountMinor)
    }

    @Test
    fun testParseAmountToMinor_internationalAndNegativeIsoFormats() {
        assertEquals(125050L, CompetitorCsvParser.parseAmountToMinor("Rs. 1,250.50", 2))
        assertEquals(-5000L, CompetitorCsvParser.parseAmountToMinor("-USD 50.00", 2))
        assertEquals(-5000L, CompetitorCsvParser.parseAmountToMinor("USD (50.00)", 2))
        assertEquals(-125050L, CompetitorCsvParser.parseAmountToMinor("Rs. (1,250.50)", 2))
        assertEquals(-5000L, CompetitorCsvParser.parseAmountToMinor("S$-50.00", 2))
        assertEquals(-5000L, CompetitorCsvParser.parseAmountToMinor("HK$-50.00", 2))
        assertEquals(-5000L, CompetitorCsvParser.parseAmountToMinor("Rs.-50.00", 2))
        assertEquals(-5000L, CompetitorCsvParser.parseAmountToMinor("USD-50.00", 2))
        assertNull(CompetitorCsvParser.parseAmountToMinor("-USD (50.00)", 2))
        assertEquals(125050L, CompetitorCsvParser.parseAmountToMinor("1'250.50", 2))
        assertEquals(125050L, CompetitorCsvParser.parseAmountToMinor("1\u202F250,50", 2))
        assertEquals(15000L, CompetitorCsvParser.parseAmountToMinor("RM 150.00", 2))
    }

    @Test
    fun testFortuneCityDetection_requiresCategoryOrItemWithAmount() {
        val unrelatedHeader = "City,Date,Description,Value"
        val headers = CompetitorCsvParser.parseCsvLineWithDelimiter(unrelatedHeader, ',')
        assertEquals(CsvImportPreset.FOLIO_NATIVE, CompetitorCsvParser.detectPresetFromHeader(unrelatedHeader, headers))
    }

    @Test
    fun testDateDisambiguation_scansBeyondFirst100Rows() {
        val csvText = buildString {
            append("Date,Wallet,Type,Category name,Amount,Currency,Note\n")
            // First 105 rows are ambiguous (day <= 12, month <= 12)
            for (i in 1..105) {
                append("05/06/2025,Wallet,Expense,Food,-10.00,USD,Item $i\n")
            }
            // Row 106 disambiguates MM/dd/yyyy (second token 25 > 12)
            append("01/25/2025,Wallet,Expense,Food,-25.00,USD,Late Unambiguous\n")
        }
        val reader = CompetitorCsvParser.openBomAwareReader(ByteArrayInputStream(csvText.toByteArray()))
        val headerLine = reader.readLine()!!
        val result = CompetitorCsvParser.parseCompetitorCsv(reader, headerLine, CsvImportPreset.SPENDEE, "USD")

        val expectedMay6 = java.time.LocalDate.of(2025, 5, 6).toEpochDay()
        assertEquals(106, result.transactions.size)
        assertEquals(expectedMay6, result.transactions[0].epochDay)
    }
}

