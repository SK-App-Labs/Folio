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
}
