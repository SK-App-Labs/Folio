/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.domain

import com.folioledger.app.domain.model.SupportedCurrencies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalCurrencySupportTest {

    @Test
    fun popularCurrenciesContainsCoreGlobalCurrencies() {
        val popular = SupportedCurrencies.POPULAR
        val popularCodes = popular.map { it.code }.toSet()

        assertTrue("INR should be in popular", popularCodes.contains("INR"))
        assertTrue("USD should be in popular", popularCodes.contains("USD"))
        assertTrue("EUR should be in popular", popularCodes.contains("EUR"))
        assertTrue("GBP should be in popular", popularCodes.contains("GBP"))
        assertTrue("AED should be in popular", popularCodes.contains("AED"))
        assertTrue("SAR should be in popular", popularCodes.contains("SAR"))
        assertTrue("SGD should be in popular", popularCodes.contains("SGD"))
        assertTrue("QAR should be in popular", popularCodes.contains("QAR"))
        assertTrue("MYR should be in popular", popularCodes.contains("MYR"))
        assertTrue("THB should be in popular", popularCodes.contains("THB"))
    }

    @Test
    fun allCurrenciesCatalogContainsWorldCurrencies() {
        val all = SupportedCurrencies.ALL
        assertTrue("Should contain comprehensive world currencies (>100)", all.size > 100)

        val allCodes = all.map { it.code }.toSet()
        // Latin America
        assertTrue("BRL (Brazilian Real) should be present", allCodes.contains("BRL"))
        assertTrue("MXN (Mexican Peso) should be present", allCodes.contains("MXN"))
        assertTrue("COP (Colombian Peso) should be present", allCodes.contains("COP"))

        // Africa
        assertTrue("ZAR (South African Rand) should be present", allCodes.contains("ZAR"))
        assertTrue("NGN (Nigerian Naira) should be present", allCodes.contains("NGN"))

        // Southeast Asia
        assertTrue("PHP (Philippine Peso) should be present", allCodes.contains("PHP"))
        assertTrue("VND (Vietnamese Dong) should be present", allCodes.contains("VND"))
        assertTrue("IDR (Indonesian Rupiah) should be present", allCodes.contains("IDR"))

        // Europe
        assertTrue("PLN (Polish Zloty) should be present", allCodes.contains("PLN"))
        assertTrue("SEK (Swedish Krona) should be present", allCodes.contains("SEK"))
    }

    @Test
    fun dynamicResolutionResolvesAnyISOCurrency() {
        val brl = SupportedCurrencies.fromCode("BRL")
        assertEquals("BRL", brl.code)
        assertEquals(2, brl.fractionDigits)

        val zar = SupportedCurrencies.fromCode("ZAR")
        assertEquals("ZAR", zar.code)
        assertEquals(2, zar.fractionDigits)

        val jpy = SupportedCurrencies.fromCode("JPY")
        assertEquals("JPY", jpy.code)
        assertEquals(0, jpy.fractionDigits)

        val bhd = SupportedCurrencies.fromCode("BHD")
        assertEquals("BHD", bhd.code)
        assertEquals(3, bhd.fractionDigits)
    }

    @Test
    fun invalidCurrencyGracefullyFallsBackToINR() {
        val fallback = SupportedCurrencies.fromCode("NONEXISTENT_XYZ")
        assertEquals("INR", fallback.code)
        assertEquals("₹", fallback.symbol)
    }

    @Test
    fun testConvertMinorMoney_SameDecimals() {
        // $100.00 USD (10,000 cents) @ 85.00 INR/USD -> ₹8,500.00 (850,000 paise)
        val converted = com.folioledger.app.domain.util.MoneyFormatterAndCalculator.convertMinorMoney(
            sourceMinor = 10_000L,
            sourceFractionDigits = 2,
            targetFractionDigits = 2,
            rateToTarget = 85.0,
        )
        assertEquals(850_000L, converted)
    }

    @Test
    fun testConvertMinorMoney_DifferentDecimals_ZeroDecimalsToTwo() {
        // 10,000 JPY (0 decimals) @ 0.55 INR/JPY -> ₹5,500.00 (550,000 paise)
        val converted = com.folioledger.app.domain.util.MoneyFormatterAndCalculator.convertMinorMoney(
            sourceMinor = 10_000L,
            sourceFractionDigits = 0,
            targetFractionDigits = 2,
            rateToTarget = 0.55,
        )
        assertEquals(550_000L, converted)
    }

    @Test
    fun testConvertMinorMoney_DifferentDecimals_ThreeDecimalsToTwo() {
        // 10.000 KWD (10,000 minor, 3 decimals) @ 3.25 USD/KWD -> $32.50 (3,250 cents, 2 decimals)
        val converted = com.folioledger.app.domain.util.MoneyFormatterAndCalculator.convertMinorMoney(
            sourceMinor = 10_000L,
            sourceFractionDigits = 3,
            targetFractionDigits = 2,
            rateToTarget = 3.25,
        )
        assertEquals(3_250L, converted)
    }

    @Test
    fun testConvertMinorMoney_NegativeAmountPreserved() {
        // -$50.00 USD (-5,000 cents) @ 85.00 INR/USD -> -₹4,250.00 (-425,000 paise)
        val converted = com.folioledger.app.domain.util.MoneyFormatterAndCalculator.convertMinorMoney(
            sourceMinor = -5_000L,
            sourceFractionDigits = 2,
            targetFractionDigits = 2,
            rateToTarget = 85.0,
        )
        assertEquals(-425_000L, converted)
    }

    @Test
    fun resolveDeviceCurrencyCode_respectsSystemLocale() {
        val originalLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.US)
            assertEquals("USD", com.folioledger.app.data.repository.resolveDeviceCurrencyCode())

            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("pt-BR"))
            assertEquals("BRL", com.folioledger.app.data.repository.resolveDeviceCurrencyCode())

            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("en-IN"))
            assertEquals("INR", com.folioledger.app.data.repository.resolveDeviceCurrencyCode())

            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("EUR", com.folioledger.app.data.repository.resolveDeviceCurrencyCode())

            java.util.Locale.setDefault(java.util.Locale.UK)
            assertEquals("GBP", com.folioledger.app.data.repository.resolveDeviceCurrencyCode())

            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("fil-PH"))
            assertEquals("PHP", com.folioledger.app.data.repository.resolveDeviceCurrencyCode())

            java.util.Locale.setDefault(java.util.Locale.ROOT)
            assertEquals("USD", com.folioledger.app.data.repository.resolveDeviceCurrencyCode())
        } finally {
            java.util.Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun currencyDefaultGroupingMatchesConventions() {
        assertEquals(
            com.folioledger.app.domain.model.NumberGroupingSystem.INDIAN,
            SupportedCurrencies.fromCode("INR").defaultGrouping,
        )
        assertEquals(
            com.folioledger.app.domain.model.NumberGroupingSystem.INTERNATIONAL,
            SupportedCurrencies.fromCode("USD").defaultGrouping,
        )
        assertEquals(
            com.folioledger.app.domain.model.NumberGroupingSystem.INTERNATIONAL,
            SupportedCurrencies.fromCode("BRL").defaultGrouping,
        )
        assertEquals(
            com.folioledger.app.domain.model.NumberGroupingSystem.INTERNATIONAL,
            SupportedCurrencies.fromCode("EUR").defaultGrouping,
        )
        assertEquals(
            com.folioledger.app.domain.model.NumberGroupingSystem.INTERNATIONAL,
            SupportedCurrencies.fromCode("PHP").defaultGrouping,
        )
    }

    @Test
    fun convertMinorMoney_appliesFractionDigitScalingOnInvalidRate() {
        // USD (2 decimals) -> JPY (0 decimals): $100.00 fallback scales to 100 JPY, not 10,000
        assertEquals(
            100L,
            com.folioledger.app.domain.util.MoneyFormatterAndCalculator.convertMinorMoney(
                sourceMinor = 10_000L,
                sourceFractionDigits = 2,
                targetFractionDigits = 0,
                rateToTarget = -1.0,
            ),
        )
        assertEquals(
            0L,
            com.folioledger.app.domain.util.MoneyFormatterAndCalculator.convertMinorMoney(
                sourceMinor = 0L,
                sourceFractionDigits = 2,
                targetFractionDigits = 3,
                rateToTarget = Double.NaN,
            ),
        )
    }

    @Test
    fun currencyLookupAndCrossConversion_areLocaleRootAndSingleHopBigDecimal() {
        val originalLocale = java.util.Locale.getDefault()
        try {
            // Turkish locale uppercases 'i' -> '\u0130'; currency lookup & conversion must use Locale.ROOT
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            val secondary = listOf(com.folioledger.app.domain.model.SecondaryCurrency("idr", 0.0055))
            val converted = com.folioledger.app.domain.util.MoneyFormatterAndCalculator.convertToPrimaryMinor(
                sourceMinor = 100_000_00L,
                sourceCurrencyCode = "idr",
                primaryCurrencyCode = "inr",
                secondaryCurrencies = secondary,
            )
            assertEquals(550_00L, converted)

            // Cross-currency conversion EUR (90.0 INR) -> USD (85.0 INR) avoids intermediate rounding hop
            val secMap = mapOf(
                "EUR" to com.folioledger.app.domain.model.SecondaryCurrency("EUR", 90.0),
                "USD" to com.folioledger.app.domain.model.SecondaryCurrency("USD", 85.0),
            )
            assertEquals(
                10_588L,
                com.folioledger.app.domain.util.MoneyFormatterAndCalculator.convertBetweenCurrenciesMinor(
                    sourceMinor = 10_000L,
                    fromCurrencyCode = "eur",
                    toCurrencyCode = "usd",
                    primaryCurrencyCode = "inr",
                    secondaryCurrenciesByCode = secMap,
                ),
            )

            // Midpoint exactness: 150 * 1.0 / 300.0 = 0.5 rounds HALF_UP to 1 (not 0 from truncated quotient)
            val midpointRates = mapOf(
                "AAA" to com.folioledger.app.domain.model.SecondaryCurrency("AAA", 1.0),
                "BBB" to com.folioledger.app.domain.model.SecondaryCurrency("BBB", 300.0),
            )
            assertEquals(
                1L,
                com.folioledger.app.domain.util.MoneyFormatterAndCalculator.convertBetweenCurrenciesMinor(
                    sourceMinor = 150L,
                    fromCurrencyCode = "aaa",
                    toCurrencyCode = "bbb",
                    primaryCurrencyCode = "usd",
                    secondaryCurrenciesByCode = midpointRates,
                ),
            )
        } finally {
            java.util.Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun supportedCurrencies_getByCodeRejectsNonCurrencyCodes() {
        assertNull(SupportedCurrencies.getByCode("XXX"))
        assertNull(SupportedCurrencies.getByCode("XTS"))
        assertNull(SupportedCurrencies.getByCode("XBA"))
    }
}
