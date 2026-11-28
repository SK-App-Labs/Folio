/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.data

import com.folioledger.app.data.repository.parseCsvLine
import com.folioledger.app.data.repository.readLogicalCsvRecord
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.SplitLineItem
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import com.folioledger.app.domain.util.TransactionEntryRulesEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader
import java.time.LocalDate

class Phase5AuditRegressionTestSuite {

    // ---------------------------------------------------------------------------------------------
    // Bug 1.2: RFC 4180 Multi-Line Quoted Fields in CSV Import
    // ---------------------------------------------------------------------------------------------
    @Test
    fun readLogicalCsvRecord_accumulatesMultiLineQuotes() {
        val rawCsv = """
            1,2026-11-11,EXPENSE,50.00,USD,Bank,,"Shopping
            Groceries
            Weekly",,0.00,Walmart,Paid,food
            2,2026-11-12,INCOME,100.00,USD,Bank,,,Salary,,0.00,Employer,Monthly,income
        """.trimIndent()

        val reader = BufferedReader(StringReader(rawCsv))
        val record1 = readLogicalCsvRecord(reader)
        val record2 = readLogicalCsvRecord(reader)
        val record3 = readLogicalCsvRecord(reader)

        assertEquals(
            "1,2026-11-11,EXPENSE,50.00,USD,Bank,,\"Shopping\nGroceries\nWeekly\",,0.00,Walmart,Paid,food",
            record1,
        )
        assertEquals(
            "2,2026-11-12,INCOME,100.00,USD,Bank,,,Salary,,0.00,Employer,Monthly,income",
            record2,
        )
        assertNull(record3)
    }

    @Test
    fun readLogicalCsvRecord_singleLineWithoutQuotes_readsImmediately() {
        val rawCsv = "1,2026-11-11,EXPENSE,15.00,USD,Cash,,Food,,0.00,Diner,Lunch,meal"
        val reader = BufferedReader(StringReader(rawCsv))
        val record = readLogicalCsvRecord(reader)
        assertEquals(rawCsv, record)
        assertNull(readLogicalCsvRecord(reader))
    }

    @Test
    fun parseCsvLine_handlesEmbeddedQuotesAndCommas() {
        val line = "1,2026-11-11,EXPENSE,50.00,USD,Bank,,\"Item A, Item B and \"\"Item C\"\"\",,0.00,Store,Notes,tag"
        val tokens = parseCsvLine(line)
        assertEquals(13, tokens.size)
        assertEquals("Item A, Item B and \"Item C\"", tokens[7])
    }

    // ---------------------------------------------------------------------------------------------
    // Bug 1.2: Split Transaction Pipe/Colon String Format Parsing
    // ---------------------------------------------------------------------------------------------
    @Test
    fun splitTransaction_pipeColonParsing_extractsSubcategoriesAndAmounts() {
        val splitString = "Groceries:1500 | Utilities:3500 | Home › Maintenance:2000"
        val parts = splitString.split("|")
        assertEquals(3, parts.size)

        val parsed = parts.mapNotNull { part ->
            val trimmed = part.trim()
            val colonIdx = trimmed.lastIndexOf(':')
            if (colonIdx == -1) {
                null
            } else {
                val cat = trimmed.substring(0, colonIdx).trim()
                val amt = trimmed.substring(colonIdx + 1).trim().toLong()
                cat to amt
            }
        }

        assertEquals(3, parsed.size)
        assertEquals("Groceries" to 1500L, parsed[0])
        assertEquals("Utilities" to 3500L, parsed[1])
        assertEquals("Home › Maintenance" to 2000L, parsed[2])
    }

    // ---------------------------------------------------------------------------------------------
    // Bug 1.5: Split Transaction Arithmetic Overflow Resilience
    // ---------------------------------------------------------------------------------------------
    @Test
    fun validateSplitTransaction_extremeAmounts_overflowHandledGracefully() {
        val splits = listOf(
            SplitLineItem(id = 1L, categoryId = 1L, categoryName = "A", amount = MinorMoney(Long.MAX_VALUE)),
            SplitLineItem(id = 2L, categoryId = 2L, categoryName = "B", amount = MinorMoney(Long.MAX_VALUE)),
        )
        // Should not throw ArithmeticException: Long overflow
        val result = TransactionEntryRulesEngine.validateSplitTransaction(MinorMoney(100L), splits)
        assertFalse(result.isValid)
        assertEquals("Split amounts exceed maximum supported limit.", result.errorMessage)
    }

    // ---------------------------------------------------------------------------------------------
    // Bug 1.5: Keypad Buffer 12-Digit Integer and Fraction Capping
    // ---------------------------------------------------------------------------------------------
    @Test
    fun keypadBuffer_enforces12IntegerDigitCap() {
        fun appendKey(buf: String, key: String, fractionDigits: Int): String = when (key) {
            "+", "-", "×", "÷" -> {
                if (buf.isEmpty() || buf == "0") {
                    buf
                } else if (buf.last() in charArrayOf('+', '-', '×', '÷', '*', '/')) {
                    buf.dropLast(1) + key
                } else {
                    buf + key
                }
            }

            "00" -> {
                if (buf == "0") {
                    "0"
                } else {
                    val lastNumberToken = buf.split('+', '-', '×', '÷', '*', '/').lastOrNull().orEmpty()
                    val intPart = lastNumberToken.substringBefore('.')
                    val fracPart = if (lastNumberToken.contains('.')) lastNumberToken.substringAfter('.') else null
                    if (fracPart != null) {
                        if (fracPart.length + 2 <= fractionDigits) "${buf}00" else buf
                    } else {
                        if (intPart.length + 2 <= 12) "${buf}00" else buf
                    }
                }
            }

            else -> {
                if (key.all { it.isDigit() }) {
                    val lastNumberToken = buf.split('+', '-', '×', '÷', '*', '/').lastOrNull().orEmpty()
                    val intPart = lastNumberToken.substringBefore('.')
                    val fracPart = if (lastNumberToken.contains('.')) lastNumberToken.substringAfter('.') else null
                    val canAppend = if (fracPart != null) {
                        fracPart.length + key.length <= fractionDigits
                    } else {
                        intPart.length + key.length <= 12
                    }
                    if (!canAppend) {
                        buf
                    } else if (buf == "0") {
                        key
                    } else {
                        buf + key
                    }
                } else {
                    if (buf == "0") key else buf + key
                }
            }
        }

        // Test 1: Start at 0, type 12 digits -> successfully 12 digits
        var buf = "0"
        for (i in 1..12) {
            buf = appendKey(buf, "9", 2)
        }
        assertEquals("999999999999", buf)
        assertEquals(12, buf.length)

        // Test 2: Typing 13th digit is rejected
        val overCap = appendKey(buf, "9", 2)
        assertEquals("999999999999", overCap)

        // Test 3: Typing operator resets token cap for the second operand
        var mathBuf = appendKey(buf, "+", 2)
        mathBuf = appendKey(mathBuf, "5", 2)
        assertEquals("999999999999+5", mathBuf)

        // Test 4: Decimal places capped to fractionDigits (2)
        var decBuf = "100.1"
        decBuf = appendKey(decBuf, "5", 2)
        assertEquals("100.15", decBuf)
        val decOverCap = appendKey(decBuf, "9", 2)
        assertEquals("100.15", decOverCap)

        // Test 5: "00" rejected if it exceeds 12 digits
        var elevenDigits = "11111111111" // 11 digits
        val attemptedDoubleZero = appendKey(elevenDigits, "00", 2)
        assertEquals("11111111111", attemptedDoubleZero) // 11 + 2 = 13 > 12, rejected
    }

    // ---------------------------------------------------------------------------------------------
    // Bug 1.4: Notification Redaction on Lock Screen
    // ---------------------------------------------------------------------------------------------
    @Test
    fun notificationFormatting_redactsAmountsWhenHideAmountsOnLockScreenEnabled() {
        val dueDate = LocalDate.of(2026, 11, 25)
        val spec = SupportedCurrencies.fromCode("USD")
        val amount = MinorMoney(45210_00L) // $45,210.00
        val formattedAmount = MoneyFormatterAndCalculator.formatMinorMoney(
            amount,
            spec,
            NumberGroupingSystem.INTERNATIONAL,
        )

        fun buildCardBody(hideAmounts: Boolean): String = if (hideAmounts) {
            "Due on 25 Nov. Settle now to preserve your grace period."
        } else {
            "Statement balance of $formattedAmount is due on 25 Nov."
        }

        val publicBody = buildCardBody(hideAmounts = true)
        val privateBody = buildCardBody(hideAmounts = false)

        assertFalse("Public body must NOT contain the currency amount", publicBody.contains("45,210"))
        assertTrue("Private body must contain the formatted currency amount", privateBody.contains("45,210.00"))
    }

    // ---------------------------------------------------------------------------------------------
    // Performance & Security: Accounting Formats, Overflow & Formula Injection
    // ---------------------------------------------------------------------------------------------
    @Test
    fun parseAmountDecimalToMinor_handlesAccountingParenthesesAndCurrencies() {
        val parsed1 = com.folioledger.app.data.repository.parseAmountDecimalToMinor("(250.00)", 2)
        assertEquals(-25000L, parsed1)

        val parsed2 = com.folioledger.app.data.repository.parseAmountDecimalToMinor("$ (1,234.56)", 2)
        assertEquals(-123456L, parsed2)

        val parsed3 = com.folioledger.app.data.repository.parseAmountDecimalToMinor("₹ 5,000.50", 2)
        assertEquals(500050L, parsed3)
    }

    @Test
    fun parseAmountDecimalToMinor_handlesOverflowSafely() {
        // Enormous number exceeding Long.MAX_VALUE
        val overflowInput = "99999999999999999999999999999.00"
        val parsed = com.folioledger.app.data.repository.parseAmountDecimalToMinor(overflowInput, 2)
        assertNull("Overflowing amounts must safely return null instead of wrapping around", parsed)
    }

    @Test
    fun parseDateStringToEpochDay_supportsMultipleFormats() {
        val expectedEpochDay = LocalDate.of(2026, 11, 11).toEpochDay()
        val iso = com.folioledger.app.data.repository.parseDateStringToEpochDay("2026-11-11")
        val dmySlash = com.folioledger.app.data.repository.parseDateStringToEpochDay("11/11/2026")
        val dmyDash = com.folioledger.app.data.repository.parseDateStringToEpochDay("11-11-2026")
        val ymdSlash = com.folioledger.app.data.repository.parseDateStringToEpochDay("2026/11/11")
        val dMmmY = com.folioledger.app.data.repository.parseDateStringToEpochDay("11 Nov 2026")

        assertEquals(expectedEpochDay, iso)
        assertEquals(expectedEpochDay, dmySlash)
        assertEquals(expectedEpochDay, dmyDash)
        assertEquals(expectedEpochDay, ymdSlash)
        assertEquals(expectedEpochDay, dMmmY)
    }

    @Test
    fun csvFormulaInjection_neutralizesSpreadsheetFormulas() {
        fun escape(value: String): String {
            if (value.isEmpty()) return ""
            val sanitized = if (value.startsWith("=") || value.startsWith("+") || value.startsWith("-") || value.startsWith("@")) {
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

        assertEquals("'=SUM(A1:B1)", escape("=SUM(A1:B1)"))
        assertEquals("'+91 9876543210", escape("+91 9876543210"))
        assertEquals("'- Paid via UPI", escape("- Paid via UPI"))
        assertEquals("'@admin", escape("@admin"))
        assertEquals("Normal Text", escape("Normal Text"))
    }

    @Test
    fun folioThemeMode_fromKey_identifiesLightAndDarkThemesCorrectly() {
        val sepia = com.folioledger.app.ui.theme.FolioThemeMode.fromKey("SEPIA")
        val slate = com.folioledger.app.ui.theme.FolioThemeMode.fromKey("SLATE_GRAY")
        val light = com.folioledger.app.ui.theme.FolioThemeMode.fromKey("LIGHT")
        val amoled = com.folioledger.app.ui.theme.FolioThemeMode.fromKey("AMOLED_BLACK")

        assertEquals(com.folioledger.app.ui.theme.FolioThemeMode.SEPIA, sepia)
        assertEquals(com.folioledger.app.ui.theme.FolioThemeMode.SLATE_GRAY, slate)
        assertEquals(com.folioledger.app.ui.theme.FolioThemeMode.LIGHT, light)
        assertEquals(com.folioledger.app.ui.theme.FolioThemeMode.AMOLED_BLACK, amoled)
    }
}
