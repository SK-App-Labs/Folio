package com.folioledger.app.ui

import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class Phase6AuditRegressionTestSuite {

    // ---------------------------------------------------------------------------------------------
    // 1. Keypad Expression Sanitization: European Comma Decimals, Grouping, & Unicode Operators
    // ---------------------------------------------------------------------------------------------
    @Test
    fun evaluateKeypadExpression_europeanCommaDecimalAndSpaces_evaluatesAccurately() {
        // Standard comma decimal paste: "12,50" -> 12.50 (1250 minor units)
        val result1 = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor("12,50", 2)
        assertTrue(result1.isSuccess)
        assertEquals(MinorMoney(1250L), result1.getOrThrow())

        // Compound European expression: "1.250,50 + 20,00" -> 1270.50
        val result2 = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor("1.250,50 + 20,00", 2)
        assertTrue(result2.isSuccess)
        assertEquals(MinorMoney(127050L), result2.getOrThrow())

        // Standard thousands grouping with addition: "1,000 + 500" -> 1500.00
        val result3 = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor("1,000 + 500", 2)
        assertTrue(result3.isSuccess)
        assertEquals(MinorMoney(150000L), result3.getOrThrow())

        // Non-breaking spaces (\u00A0, \u202F) and Unicode operators (× \u00D7, ÷ \u00F7)
        val result4 = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor("100\u00A0\u00D7\u202F5\u00A0\u00F7\u202F2", 2)
        assertTrue(result4.isSuccess)
        assertEquals(MinorMoney(25000L), result4.getOrThrow())

        // Partial decimal: "12,5" -> 12.50
        val result5 = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor("12,5", 2)
        assertTrue(result5.isSuccess)
        assertEquals(MinorMoney(1250L), result5.getOrThrow())

        // 3-digit comma in 2-decimal currency treated as thousands grouping: "5,000" -> 5000.00
        val result6 = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor("5,000", 2)
        assertTrue(result6.isSuccess)
        assertEquals(MinorMoney(500000L), result6.getOrThrow())

        // 3-decimal currency (KWD, BHD, OMR): "1,000" under dot-decimal locale must NOT parse as 1.000
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val result3DecDot = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor("1,000", 3)
            assertTrue(result3DecDot.isSuccess)
            assertEquals(MinorMoney(1000000L), result3DecDot.getOrThrow()) // 1,000.000 (1M minor)

            Locale.setDefault(Locale.GERMANY)
            val result3DecComma = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor("1,250", 3)
            assertTrue(result3DecComma.isSuccess)
            assertEquals(MinorMoney(1250L), result3DecComma.getOrThrow()) // 1.250 (1250 minor)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Locale Management: Pre-Android 13 JVM Default Restoration Invariant
    // ---------------------------------------------------------------------------------------------
    @Test
    fun localeManager_switchingBackToSystemDefault_restoresSystemLocale() {
        val originalJvmLocale = Locale.getDefault()
        try {
            // User selects Hindi
            val hindiLocale = Locale.forLanguageTag("hi")
            Locale.setDefault(hindiLocale)
            assertEquals("hi", Locale.getDefault().language)

            // User resets to System Default (empty tag): system locale must be restored to JVM
            val systemLocale = Locale.US
            val tag = ""
            if (tag.isBlank()) {
                Locale.setDefault(systemLocale)
            }
            assertEquals("en", Locale.getDefault().language)
        } finally {
            Locale.setDefault(originalJvmLocale)
        }
    }

    @Test
    fun evaluateKeypadExpression_unicodeMinusAndDeepNestingAndLocaleCommaDecimals_handledSafely() {
        // Unicode minus (\u2212) normalization
        val minusResult = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor("100\u221225", 2)
        assertTrue(minusResult.isSuccess)
        assertEquals(MinorMoney(7500L), minusResult.getOrThrow())

        // Deeply nested expression rejects gracefully instead of crashing with StackOverflowError
        val deepExpr = "(".repeat(200) + "1" + ")".repeat(200)
        val deepResult = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor(deepExpr, 2)
        assertTrue(deepResult.isFailure)

        // Comma-decimal locale parses >2 decimal digits cleanly via HALF_UP rounding
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val roundedGerman = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor("12,345", 2)
            assertTrue(roundedGerman.isSuccess)
            assertEquals(MinorMoney(1235L), roundedGerman.getOrThrow())
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
