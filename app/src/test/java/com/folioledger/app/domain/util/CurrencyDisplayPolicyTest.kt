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

import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.SupportedCurrencies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrencyDisplayPolicyTest {

    @Test
    fun primaryCurrencyHiddenAndForeignCurrenciesVisibleWhenEnabled() {
        val policy = CurrencyDisplayPolicy(
            primaryCurrencyCode = "INR",
            hidePrimaryCurrencySymbol = true,
        )
        val inr = SupportedCurrencies.fromCode("INR")
        val usd = SupportedCurrencies.fromCode("USD")
        val aed = SupportedCurrencies.fromCode("AED")

        assertTrue(policy.isPrimaryCurrency("inr"))
        assertTrue(policy.isPrimaryCurrency(" inr "))
        assertFalse(policy.isForeignCurrency("INR"))
        assertTrue(policy.isForeignCurrency("USD"))

        assertFalse(policy.shouldShowSymbol(inr))
        assertFalse(policy.shouldShowSymbol("inr"))
        assertTrue(policy.shouldShowSymbol(usd))
        assertTrue(policy.shouldShowSymbol(aed))

        assertEquals("", policy.effectiveSymbol(inr))
        assertEquals("$", policy.effectiveSymbol(usd))
        assertEquals("AED", policy.effectiveSymbol(aed))
    }

    @Test
    fun allSymbolsVisibleWhenDisabled() {
        val policy = CurrencyDisplayPolicy(
            primaryCurrencyCode = "AED",
            hidePrimaryCurrencySymbol = false,
        )
        val aed = SupportedCurrencies.fromCode("AED")
        val eur = SupportedCurrencies.fromCode("EUR")

        assertTrue(policy.shouldShowSymbol(aed))
        assertTrue(policy.shouldShowSymbol(eur))
        assertEquals("AED", policy.effectiveSymbol(aed))
        assertTrue(policy.isMultiCharVisibleSymbol(aed))
    }

    @Test
    fun multiCharVisibleSymbolBecomesFalseWhenPrimaryMultiCharSymbolIsHidden() {
        val aed = SupportedCurrencies.fromCode("AED")
        val usd = SupportedCurrencies.fromCode("USD")

        val hiddenAedPrimary = CurrencyDisplayPolicy(
            primaryCurrencyCode = "AED",
            hidePrimaryCurrencySymbol = true,
        )
        assertFalse(hiddenAedPrimary.isMultiCharVisibleSymbol(aed))
        assertFalse(hiddenAedPrimary.isMultiCharVisibleSymbol(usd))

        val switchedToUsdPrimary = hiddenAedPrimary.copy(primaryCurrencyCode = "USD")
        assertTrue(switchedToUsdPrimary.shouldShowSymbol(aed))
        assertTrue(switchedToUsdPrimary.isMultiCharVisibleSymbol(aed))
        assertFalse(switchedToUsdPrimary.shouldShowSymbol(usd))
    }

    @Test
    fun formatMinorMoneyWithoutSymbolFormatsPositiveNegativeZeroAndZeroDecimalCleanly() {
        val inr = SupportedCurrencies.fromCode("INR")
        val usd = SupportedCurrencies.fromCode("USD")
        val jpy = SupportedCurrencies.fromCode("JPY")

        assertEquals(
            "12,34,567.89",
            MoneyFormatterAndCalculator.formatMinorMoney(
                money = MinorMoney(123_456_789L),
                currencySpec = inr,
                grouping = NumberGroupingSystem.INDIAN,
                includeSymbol = false,
            ),
        )
        assertEquals(
            "-1,234.56",
            MoneyFormatterAndCalculator.formatMinorMoney(
                money = MinorMoney(-123_456L),
                currencySpec = usd,
                grouping = NumberGroupingSystem.INTERNATIONAL,
                includeSymbol = false,
            ),
        )
        assertEquals(
            "+500.00",
            MoneyFormatterAndCalculator.formatMinorMoney(
                money = MinorMoney(50_000L),
                currencySpec = inr,
                grouping = NumberGroupingSystem.INDIAN,
                includeSymbol = false,
                showPlusSignForPositive = true,
            ),
        )
        assertEquals(
            "0.00",
            MoneyFormatterAndCalculator.formatMinorMoney(
                money = MinorMoney.ZERO,
                currencySpec = usd,
                grouping = NumberGroupingSystem.INTERNATIONAL,
                includeSymbol = false,
            ),
        )
        assertEquals(
            "12,500",
            MoneyFormatterAndCalculator.formatMinorMoney(
                money = MinorMoney(12_500L),
                currencySpec = jpy,
                grouping = NumberGroupingSystem.INTERNATIONAL,
                includeSymbol = false,
            ),
        )
        assertEquals(
            "-12,500",
            MoneyFormatterAndCalculator.formatMinorMoney(
                money = MinorMoney(-12_500L),
                currencySpec = jpy,
                grouping = NumberGroupingSystem.INTERNATIONAL,
                includeSymbol = false,
            ),
        )
    }
}
