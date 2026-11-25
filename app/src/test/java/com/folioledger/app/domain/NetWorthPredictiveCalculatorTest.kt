/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/test/java/com/folioledger/app/domain/NetWorthPredictiveCalculatorTest.kt
package com.folioledger.app.domain

import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.MonthlyTrendBar
import com.folioledger.app.domain.model.NetWorthGrowthTrendItem
import com.folioledger.app.domain.model.RunwayHealthTier
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.StatsPeriodGranularity
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.util.NetWorthPredictiveCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NetWorthPredictiveCalculatorTest {

    @Test
    fun testNetWorthVelocity_positiveGrowth() {
        val trend = listOf(
            NetWorthGrowthTrendItem(
                epochDay = 20700L,
                monthYearText = "Sep 2026",
                assets = MinorMoney(500_000_00L),
                liabilities = MinorMoney(100_000_00L),
                netWorth = MinorMoney(400_000_00L)
            ),
            NetWorthGrowthTrendItem(
                epochDay = 20730L,
                monthYearText = "Oct 2026",
                assets = MinorMoney(550_000_00L),
                liabilities = MinorMoney(90_000_00L),
                netWorth = MinorMoney(460_000_00L)
            )
        )

        val velocity = NetWorthPredictiveCalculator.computeNetWorthVelocity(trend)

        assertTrue(velocity.isPositive)
        assertEquals(MinorMoney(60_000_00L), velocity.absoluteChange)
        // 60,000 / 400,000 = +15%
        assertEquals(15.0f, velocity.percentageChange, 0.01f)
    }

    @Test
    fun testEmergencyRunway_tierClassification() {
        val accounts = listOf(
            AccountSummary(
                id = 1L,
                name = "Liquid Bank",
                groupType = AccountGroup.BANK_ACCOUNT,
                currencyCode = "INR",
                currentBalance = MinorMoney(300_000_00L) // 3,00,000
            ),
            AccountSummary(
                id = 2L,
                name = "Mutual Funds",
                groupType = AccountGroup.INVESTMENT,
                currencyCode = "INR",
                currentBalance = MinorMoney(500_000_00L) // 5,00,000
            )
        )

        val trendBars = listOf(
            MonthlyTrendBar("Jul", 0L, 0L, MinorMoney.ZERO, MinorMoney(50_000_00L), MinorMoney.ZERO),
            MonthlyTrendBar("Aug", 0L, 0L, MinorMoney.ZERO, MinorMoney(50_000_00L), MinorMoney.ZERO),
            MonthlyTrendBar("Sep", 0L, 0L, MinorMoney.ZERO, MinorMoney(50_000_00L), MinorMoney.ZERO)
        )

        // Average monthly burn = 50,000
        // Liquid runway = 300,000 / 50,000 = 6.0 months -> RESILIENT
        val runwayNoInvestments = NetWorthPredictiveCalculator.computeEmergencyRunway(
            allAccounts = accounts,
            trendBars = trendBars,
            primaryCurrencyCode = "INR",
            secondaryCurrencies = emptyList(),
            includesInvestments = false
        )

        assertEquals(6.0f, runwayNoInvestments.runwayMonths, 0.01f)
        assertEquals(RunwayHealthTier.RESILIENT, runwayNoInvestments.healthTier)

        // With investments: (300,000 + 500,000) / 50,000 = 16.0 months -> RESILIENT
        val runwayWithInvestments = NetWorthPredictiveCalculator.computeEmergencyRunway(
            allAccounts = accounts,
            trendBars = trendBars,
            primaryCurrencyCode = "INR",
            secondaryCurrencies = emptyList(),
            includesInvestments = true
        )

        assertEquals(16.0f, runwayWithInvestments.runwayMonths, 0.01f)
        assertEquals(RunwayHealthTier.RESILIENT, runwayWithInvestments.healthTier)
    }

    @Test
    fun testEmergencyRunway_vulnerableTier() {
        val accounts = listOf(
            AccountSummary(
                id = 1L,
                name = "Low Cash",
                groupType = AccountGroup.BANK_ACCOUNT,
                currencyCode = "INR",
                currentBalance = MinorMoney(80_000_00L) // 80,000
            )
        )

        val trendBars = listOf(
            MonthlyTrendBar("Sep", 0L, 0L, MinorMoney.ZERO, MinorMoney(50_000_00L), MinorMoney.ZERO)
        )

        // 80,000 / 50,000 = 1.6 months -> VULNERABLE (< 3 months)
        val runway = NetWorthPredictiveCalculator.computeEmergencyRunway(
            allAccounts = accounts,
            trendBars = trendBars,
            primaryCurrencyCode = "INR",
            secondaryCurrencies = emptyList(),
            includesInvestments = false
        )

        assertEquals(1.6f, runway.runwayMonths, 0.01f)
        assertEquals(RunwayHealthTier.VULNERABLE, runway.healthTier)
    }

    @Test
    fun testNetWorthTrend_multiCurrency_convertsSecondaryCurrenciesToPrimary() {
        val inrAccount = AccountSummary(
            id = 1L,
            name = "INR Bank",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(100_000_00L) // ₹100,000
        )
        val usdAccount = AccountSummary(
            id = 2L,
            name = "USD Bank",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "USD",
            currentBalance = MinorMoney(1000_00L) // $1,000
        )
        val secondaryUsd = SecondaryCurrency(
            code = "USD",
            exchangeRateToPrimary = 84.0
        )
        val today = LocalDate.of(2026, 5, 15).toEpochDay()

        val trend = NetWorthPredictiveCalculator.computeNetWorthTrend(
            allAccounts = listOf(inrAccount, usdAccount),
            allTransactions = emptyList(),
            referenceDateEpochDay = today,
            periodGranularity = StatsPeriodGranularity.MONTHLY,
            primaryCurrencyCode = "INR",
            secondaryCurrencies = listOf(secondaryUsd),
            todayEpochDay = today
        )

        // ₹100,000 + ($1,000 * 84) = ₹184,000
        val currentMonthItem = trend.last()
        assertEquals(MinorMoney(184_000_00L), currentMonthItem.assets)
        assertEquals(MinorMoney.ZERO, currentMonthItem.liabilities)
        assertEquals(MinorMoney(184_000_00L), currentMonthItem.netWorth)
    }

    @Test
    fun testNetWorthTrend_futureTransactions_excludedFromHistoricalRollback() {
        val today = LocalDate.of(2026, 5, 15).toEpochDay()
        val account = AccountSummary(
            id = 1L,
            name = "Main Checking",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(50_000_00L) // ₹50,000 today
        )
        // Future EMI scheduled for May 20 (> today May 15)
        val futureEmiTx = TransactionItem(
            id = 101L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 0L,
            ledgerDateEpochDay = LocalDate.of(2026, 5, 20).toEpochDay(),
            amount = MinorMoney(10_000_00L),
            currencyCode = "INR",
            accountId = 1L,
            accountName = "Main Checking"
        )

        val trend = NetWorthPredictiveCalculator.computeNetWorthTrend(
            allAccounts = listOf(account),
            allTransactions = listOf(futureEmiTx),
            referenceDateEpochDay = today,
            periodGranularity = StatsPeriodGranularity.MONTHLY,
            primaryCurrencyCode = "INR",
            secondaryCurrencies = emptyList(),
            todayEpochDay = today
        )

        // For April 2026 (index 4 out of 6 months: Dec, Jan, Feb, Mar, Apr, May):
        // Future EMI must NOT be added back to currentBalance
        val aprilItem = trend[trend.size - 2]
        assertEquals("Apr 2026", aprilItem.monthYearText)
        assertEquals(MinorMoney(50_000_00L), aprilItem.netWorth)
    }

    @Test
    fun testNetWorthTrend_debitCardSpend_rolledBackOnParentBankAccount() {
        val today = LocalDate.of(2026, 5, 15).toEpochDay()
        val bankAccount = AccountSummary(
            id = 1L,
            name = "Savings Bank",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(40_000_00L) // ₹40,000 today after a May 5 debit card spend
        )
        val debitCard = AccountSummary(
            id = 2L,
            name = "Visa Debit",
            groupType = AccountGroup.DEBIT_CARD,
            currencyCode = "INR",
            currentBalance = MinorMoney.ZERO,
            parentAccountId = 1L
        )
        // Debit card swipe on May 5 of ₹10,000
        val debitCardTx = TransactionItem(
            id = 201L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 0L,
            ledgerDateEpochDay = LocalDate.of(2026, 5, 5).toEpochDay(),
            amount = MinorMoney(10_000_00L),
            currencyCode = "INR",
            accountId = 2L, // logged under debit card
            accountName = "Visa Debit"
        )

        val trend = NetWorthPredictiveCalculator.computeNetWorthTrend(
            allAccounts = listOf(bankAccount, debitCard),
            allTransactions = listOf(debitCardTx),
            referenceDateEpochDay = today,
            periodGranularity = StatsPeriodGranularity.MONTHLY,
            primaryCurrencyCode = "INR",
            secondaryCurrencies = emptyList(),
            todayEpochDay = today
        )

        // For May 2026 (current): ₹40,000
        val mayItem = trend.last()
        assertEquals(MinorMoney(40_000_00L), mayItem.netWorth)

        // For April 2026: before the May 5 debit card expense, balance was ₹40,000 + ₹10,000 = ₹50,000
        val aprilItem = trend[trend.size - 2]
        assertEquals("Apr 2026", aprilItem.monthYearText)
        assertEquals(MinorMoney(50_000_00L), aprilItem.netWorth)
    }

    @Test
    fun d1_computeNetWorthTrend_deduplicatesTransactionsAndConvertsCrossCurrencyRollback() {
        val today = LocalDate.of(2026, 5, 20).toEpochDay()
        val usdAccount = AccountSummary(
            id = 1L,
            name = "USD Account",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "USD",
            currentBalance = MinorMoney(1_000_00L) // $1,000
        )
        val inrAccount = AccountSummary(
            id = 2L,
            name = "INR Account",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(50_000_00L) // ₹50,000
        )

        // Transfer of $100 USD on May 5 from USD account to INR account (exchange rate 80.0 INR/USD)
        val transferTx = TransactionItem(
            id = 301L,
            type = TransactionType.TRANSFER,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = LocalDate.of(2026, 5, 5).toEpochDay(),
            amount = MinorMoney(100_00L), // $100 USD
            currencyCode = "USD",
            accountId = 1L,
            accountName = "USD Account",
            transferToAccountId = 2L,
            transferToAccountName = "INR Account"
        )

        // Pass transferTx twice to test deduplication
        val trend = NetWorthPredictiveCalculator.computeNetWorthTrend(
            allAccounts = listOf(usdAccount, inrAccount),
            allTransactions = listOf(transferTx, transferTx.copy()),
            referenceDateEpochDay = today,
            periodGranularity = StatsPeriodGranularity.MONTHLY,
            primaryCurrencyCode = "INR",
            secondaryCurrencies = listOf(SecondaryCurrency("USD", 80.0)),
            todayEpochDay = today
        )

        // May 2026 Net Worth: $1,000 * 80 + ₹50,000 = ₹130,000
        val mayItem = trend.last()
        assertEquals(MinorMoney(130_000_00L), mayItem.netWorth)

        // April 2026 (prior to transfer):
        // USD Account: $1,000 + $100 = $1,100 (* 80 = ₹88,000)
        // INR Account: ₹50,000 - ($100 * 80 = ₹8,000) = ₹42,000
        // Total April Net Worth = ₹88,000 + ₹42,000 = ₹130,000 (Internal transfer preserves Net Worth)
        val aprilItem = trend[trend.size - 2]
        assertEquals(MinorMoney(130_000_00L), aprilItem.netWorth)
        assertEquals(MinorMoney(130_000_00L), aprilItem.assets)
        assertEquals(MinorMoney.ZERO, aprilItem.liabilities)
    }

    @Test
    fun d2_computeNetWorthVelocity_handlesExtremeValuesAndHighPrecision() {
        val trend = listOf(
            NetWorthGrowthTrendItem(
                epochDay = 1000L,
                monthYearText = "Apr 2026",
                assets = MinorMoney(10_000_000_00L), // ₹1 crore
                liabilities = MinorMoney.ZERO,
                netWorth = MinorMoney(10_000_000_00L)
            ),
            NetWorthGrowthTrendItem(
                epochDay = 1030L,
                monthYearText = "May 2026",
                assets = MinorMoney(10_500_000_00L), // ₹1.05 crore
                liabilities = MinorMoney.ZERO,
                netWorth = MinorMoney(10_500_000_00L)
            )
        )

        val velocity = NetWorthPredictiveCalculator.computeNetWorthVelocity(trend)
        assertEquals(5.0f, velocity.percentageChange, 0.01f)
        assertTrue(velocity.isPositive)
        assertEquals(MinorMoney(500_000_00L), velocity.absoluteChange)

        // Edge case: previousNW is Long.MIN_VALUE, must not invert or crash
        val extremeTrend = listOf(
            NetWorthGrowthTrendItem(
                epochDay = 1000L,
                monthYearText = "Apr 2026",
                assets = MinorMoney.ZERO,
                liabilities = MinorMoney.ZERO,
                netWorth = MinorMoney(Long.MIN_VALUE)
            ),
            NetWorthGrowthTrendItem(
                epochDay = 1030L,
                monthYearText = "May 2026",
                assets = MinorMoney.ZERO,
                liabilities = MinorMoney.ZERO,
                netWorth = MinorMoney(0L)
            )
        )
        val extremeVelocity = NetWorthPredictiveCalculator.computeNetWorthVelocity(extremeTrend)
        assertTrue("Delta from -infinity to 0 is an improvement", extremeVelocity.isPositive)
        assertEquals(100.0f, extremeVelocity.percentageChange, 0.01f)
    }

    @Test
    fun d3_computeEmergencyRunway_dropsLeadingEmptyMonthsWhileRetainingActiveZeroExpenseMonths() {
        val account = AccountSummary(
            id = 1L,
            name = "Checking",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(60_000_00L)
        )

        // Trend bars:
        // Month 0: Inactive pre-onboarding month (Income = 0, Expense = 0) -> dropped
        // Month 1: Active month (Income = 50k, Expense = 30k)
        // Month 2: Active month with zero expenses (Income = 50k, Expense = 0) -> retained!
        // Month 3: Active month (Income = 50k, Expense = 30k)
        val trendBars = listOf(
            MonthlyTrendBar("Jan", 0L, 0L, MinorMoney.ZERO, MinorMoney.ZERO, MinorMoney.ZERO),
            MonthlyTrendBar("Feb", 0L, 0L, MinorMoney(50_000_00L), MinorMoney(30_000_00L), MinorMoney(20_000_00L)),
            MonthlyTrendBar("Mar", 0L, 0L, MinorMoney(50_000_00L), MinorMoney.ZERO, MinorMoney(50_000_00L)),
            MonthlyTrendBar("Apr", 0L, 0L, MinorMoney(50_000_00L), MinorMoney(30_000_00L), MinorMoney(20_000_00L))
        )

        val runway = NetWorthPredictiveCalculator.computeEmergencyRunway(
            allAccounts = listOf(account),
            trendBars = trendBars,
            primaryCurrencyCode = "INR",
            secondaryCurrencies = emptyList(),
            includesInvestments = false
        )

        // Average burn across Feb, Mar, Apr: (30,000 + 0 + 30,000) / 3 = 20,000/mo
        assertEquals(20_000_00L, runway.averageMonthlyBurnMinor)
        // Runway: 60,000 / 20,000 = 3.0 months -> ADEQUATE
        assertEquals(3.0f, runway.runwayMonths, 0.01f)
        assertEquals(RunwayHealthTier.ADEQUATE, runway.healthTier)
    }

    @Test
    fun computeNetWorthVelocity_zeroPreviousMonthNetWorth_doesNotProduceNaNOrInfinity() {
        val trend = listOf(
            NetWorthGrowthTrendItem(1000L, "Apr 2026", MinorMoney.ZERO, MinorMoney.ZERO, MinorMoney.ZERO),
            NetWorthGrowthTrendItem(1030L, "May 2026", MinorMoney(50_000_00L), MinorMoney.ZERO, MinorMoney(50_000_00L))
        )
        val velocity = NetWorthPredictiveCalculator.computeNetWorthVelocity(trend)
        assertTrue(velocity.percentageChange.isFinite())
        assertEquals(MinorMoney(50_000_00L), velocity.absoluteChange)
        assertTrue(velocity.isPositive)
    }

    @Test
    fun computeEmergencyRunway_turkishLocale_usesLocaleRootForSecondaryCurrencyLookup() {
        val originalLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            val idrAccount = AccountSummary(
                id = 7L,
                name = "Jakarta Bank",
                groupType = AccountGroup.BANK_ACCOUNT,
                currencyCode = "idr",
                currentBalance = MinorMoney(100_000_00L)
            )
            val runway = NetWorthPredictiveCalculator.computeEmergencyRunway(
                allAccounts = listOf(idrAccount),
                trendBars = listOf(
                    MonthlyTrendBar("May", 0L, 0L, MinorMoney(1000_00L), MinorMoney(500_00L), MinorMoney(500_00L))
                ),
                primaryCurrencyCode = "INR",
                secondaryCurrencies = listOf(SecondaryCurrency("idr", 0.0055)),
                includesInvestments = false
            )
            assertEquals(MinorMoney(550_00L), runway.totalLiquidFunds)
        } finally {
            java.util.Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun computeNetWorthTrend_preservesArchivedAccountHistory_andCapsAnnualCurrentYearVelocity() {
        val oldBank = AccountSummary(
            id = 1L,
            name = "Old Bank",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney.ZERO,
            includeInNetWorth = true,
            isArchived = true
        )
        val newBank = AccountSummary(
            id = 2L,
            name = "New Bank",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(500_000_00L),
            includeInNetWorth = true,
            isArchived = false
        )
        // Month 1 (Jan 2026) already held 500k in Old Bank; Feb transferred 400k Old->New; Mar added 100k income in New + 100k Old->New
        val transfer = TransactionItem(
            id = 10L,
            type = TransactionType.TRANSFER,
            timestampEpochMillis = 0L,
            ledgerDateEpochDay = LocalDate.of(2026, 2, 10).toEpochDay(),
            amount = MinorMoney(400_000_00L),
            currencyCode = "INR",
            accountId = 1L,
            accountName = "Old Bank",
            transferToAccountId = 2L,
            transferToAccountName = "New Bank"
        )
        val finalSweep = TransactionItem(
            id = 11L,
            type = TransactionType.TRANSFER,
            timestampEpochMillis = 0L,
            ledgerDateEpochDay = LocalDate.of(2026, 4, 15).toEpochDay(),
            amount = MinorMoney(100_000_00L),
            currencyCode = "INR",
            accountId = 1L,
            accountName = "Old Bank",
            transferToAccountId = 2L,
            transferToAccountName = "New Bank"
        )
        val maySalary = TransactionItem(
            id = 12L,
            type = TransactionType.INCOME,
            timestampEpochMillis = 0L,
            ledgerDateEpochDay = LocalDate.of(2026, 5, 5).toEpochDay(),
            amount = MinorMoney(50_000_00L),
            currencyCode = "INR",
            accountId = 2L,
            accountName = "New Bank"
        )
        val updatedNewBank = newBank.copy(currentBalance = MinorMoney(550_000_00L))
        val today = LocalDate.of(2026, 5, 20).toEpochDay()

        val annualTrend = NetWorthPredictiveCalculator.computeNetWorthTrend(
            allAccounts = listOf(oldBank, updatedNewBank),
            allTransactions = listOf(transfer, finalSweep, maySalary),
            referenceDateEpochDay = today,
            periodGranularity = StatsPeriodGranularity.ANNUALLY,
            todayEpochDay = today
        )

        // Current year caps at May (5 months) instead of flat-lining through December
        assertEquals(5, annualTrend.size)
        // Jan reconstructed balance preserves 500k in archived Old Bank (before Feb transfer out)
        assertEquals(MinorMoney(500_000_00L), annualTrend[0].netWorth)
        assertEquals(MinorMoney(550_000_00L), annualTrend[4].netWorth)

        val velocity = NetWorthPredictiveCalculator.computeNetWorthVelocity(annualTrend)
        assertEquals(MinorMoney(50_000_00L), velocity.absoluteChange)
        assertEquals(10.0f, velocity.percentageChange, 0.01f)
    }
}
