/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/test/java/com/folioledger/app/ui/stats/SafeToSpendCalculationsTest.kt
package com.folioledger.app.ui.stats

import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.RecurringCommitmentItem
import com.folioledger.app.domain.model.RepeatFrequency
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeToSpendCalculationsTest {

    private val refEpochDay = 20757L // Reference "today"

    private fun createTestAccount(
        id: Long,
        name: String,
        group: AccountGroup,
        balanceMinor: Long,
        currencyCode: String = "INR"
    ) = AccountSummary(
        id = id,
        name = name,
        groupType = group,
        currencyCode = currencyCode,
        currentBalance = MinorMoney(balanceMinor)
    )

    private fun createTestRecurringRule(
        id: Long,
        title: String,
        amountMinor: Long,
        accountId: Long,
        nextDueEpochDay: Long,
        frequency: RepeatFrequency = RepeatFrequency.MONTHLY,
        type: TransactionType = TransactionType.EXPENSE
    ) = RecurringCommitmentItem(
        id = id,
        title = title,
        type = type,
        amount = MinorMoney(amountMinor),
        currencyCode = "INR",
        accountId = accountId,
        frequency = frequency,
        nextOccurrenceEpochDay = nextDueEpochDay,
        isActive = true
    )

    @Test
    fun testSafeToSpend_positiveDiscretionaryCash() {
        val accounts = listOf(
            createTestAccount(1, "HDFC Salary", AccountGroup.BANK_ACCOUNT, 70_000_00L),
            createTestAccount(2, "Cash in Wallet", AccountGroup.CASH, 15_000_00L),
            createTestAccount(3, "Axis Credit Card", AccountGroup.CREDIT_CARD, -30_000_00L) // CC balance negative when owing
        )

        val recurringRules = listOf(
            createTestRecurringRule(101, "House Rent", 10_000_00L, accountId = 1, nextDueEpochDay = refEpochDay + 5),
            createTestRecurringRule(102, "Internet Fiber", 2_500_00L, accountId = 1, nextDueEpochDay = refEpochDay + 10),
            createTestRecurringRule(103, "Annual Insurance", 50_000_00L, accountId = 1, nextDueEpochDay = refEpochDay + 45) // Outside 14 days
        )

        val safeToSpend = StatsPredictiveCalculations.computeSafeToSpend(
            allAccounts = accounts,
            recurringRules = recurringRules,
            futureTransactions = emptyList(),
            todayEpochDay = refEpochDay,
            periodStartEpochDay = refEpochDay - 10,
            periodEndEpochDay = refEpochDay + 20,
            primaryCurrencyCode = "INR"
        )

        assertEquals("Liquid cash should sum bank + cash", MinorMoney(85_000_00L), safeToSpend.totalLiquidBankAndCash)
        assertEquals("Credit card debt should be 30k", MinorMoney(30_000_00L), safeToSpend.totalCreditCardDebt)
        assertEquals("Upcoming 14d commitments should sum rent + internet (12.5k)", MinorMoney(12_500_00L), safeToSpend.upcoming14DayCommitments)
        // 85,000 - 30,000 - 12,500 = 42,500
        assertEquals(MinorMoney(42_500_00L), safeToSpend.trueSafeToSpend)
        assertTrue(safeToSpend.isPositive)
        assertTrue(safeToSpend.isCurrentPeriod)
        assertEquals(SafeToSpendStatus.SAFE, safeToSpend.status)
    }

    @Test
    fun testSafeToSpend_negativeEncumberedCash() {
        val accounts = listOf(
            createTestAccount(1, "Bank Account", AccountGroup.BANK_ACCOUNT, 15_000_00L),
            createTestAccount(2, "Credit Card", AccountGroup.CREDIT_CARD, -20_000_00L)
        )

        val recurringRules = listOf(
            createTestRecurringRule(101, "Bill", 10_000_00L, accountId = 1, nextDueEpochDay = refEpochDay + 3)
        )

        val safeToSpend = StatsPredictiveCalculations.computeSafeToSpend(
            allAccounts = accounts,
            recurringRules = recurringRules,
            futureTransactions = emptyList(),
            todayEpochDay = refEpochDay,
            periodStartEpochDay = refEpochDay - 10,
            periodEndEpochDay = refEpochDay + 20,
            primaryCurrencyCode = "INR"
        )

        // 15,000 - 20,000 - 10,000 = -15,000
        assertEquals(MinorMoney(-15_000_00L), safeToSpend.trueSafeToSpend)
        assertFalse("Should be marked non-positive when commitments exceed cash", safeToSpend.isPositive)
        assertEquals(SafeToSpendStatus.COMBINED_OBLIGATIONS_EXCEED_CASH, safeToSpend.status)
    }

    @Test
    fun testSafeToSpend_cardDebtExceedsCash_zeroBills() {
        val accounts = listOf(
            createTestAccount(1, "Bank Account", AccountGroup.BANK_ACCOUNT, 10_000_00L),
            createTestAccount(2, "Credit Card", AccountGroup.CREDIT_CARD, -25_000_00L)
        )

        val safeToSpend = StatsPredictiveCalculations.computeSafeToSpend(
            allAccounts = accounts,
            recurringRules = emptyList(),
            futureTransactions = emptyList(),
            todayEpochDay = refEpochDay,
            periodStartEpochDay = refEpochDay - 10,
            periodEndEpochDay = refEpochDay + 20,
            primaryCurrencyCode = "INR"
        )

        // 10,000 - 25,000 - 0 = -15,000
        assertEquals(MinorMoney(-15_000_00L), safeToSpend.trueSafeToSpend)
        assertEquals(MinorMoney.ZERO, safeToSpend.upcoming14DayCommitments)
        assertFalse(safeToSpend.isPositive)
        assertEquals(SafeToSpendStatus.CARD_DEBT_EXCEEDS_CASH, safeToSpend.status)
    }

    @Test
    fun testSafeToSpend_billsExceedCash_zeroCardDebt() {
        val accounts = listOf(
            createTestAccount(1, "Bank Account", AccountGroup.BANK_ACCOUNT, 10_000_00L)
        )

        val recurringRules = listOf(
            createTestRecurringRule(101, "Rent", 15_000_00L, accountId = 1, nextDueEpochDay = refEpochDay + 5)
        )

        val safeToSpend = StatsPredictiveCalculations.computeSafeToSpend(
            allAccounts = accounts,
            recurringRules = recurringRules,
            futureTransactions = emptyList(),
            todayEpochDay = refEpochDay,
            periodStartEpochDay = refEpochDay - 10,
            periodEndEpochDay = refEpochDay + 20,
            primaryCurrencyCode = "INR"
        )

        // 10,000 - 0 - 15,000 = -5,000
        assertEquals(MinorMoney(-5_000_00L), safeToSpend.trueSafeToSpend)
        assertFalse(safeToSpend.isPositive)
        assertEquals(SafeToSpendStatus.BILLS_EXCEED_CASH, safeToSpend.status)
    }

    @Test
    fun testSafeToSpend_creditCardOverpayment_notCountedAsDebt() {
        val accounts = listOf(
            createTestAccount(1, "Bank Account", AccountGroup.BANK_ACCOUNT, 20_000_00L),
            createTestAccount(2, "Overpaid Card", AccountGroup.CREDIT_CARD, 5_000_00L) // Positive balance = store credit
        )

        val safeToSpend = StatsPredictiveCalculations.computeSafeToSpend(
            allAccounts = accounts,
            recurringRules = emptyList(),
            futureTransactions = emptyList(),
            todayEpochDay = refEpochDay,
            periodStartEpochDay = refEpochDay - 10,
            periodEndEpochDay = refEpochDay + 20,
            primaryCurrencyCode = "INR"
        )

        assertEquals("Card debt should be zero for overpaid card", MinorMoney.ZERO, safeToSpend.totalCreditCardDebt)
        assertEquals(MinorMoney(20_000_00L), safeToSpend.trueSafeToSpend)
        assertTrue(safeToSpend.isPositive)
        assertEquals(SafeToSpendStatus.SAFE, safeToSpend.status)
    }

    @Test
    fun testShortfallAlert_detectionAndUrgencySort() {
        val accounts = listOf(
            createTestAccount(1, "HDFC Salary", AccountGroup.BANK_ACCOUNT, 12_000_00L),
            createTestAccount(2, "ICICI Emergency", AccountGroup.SAVINGS, 50_000_00L),
            createTestAccount(3, "SBI Petty", AccountGroup.BANK_ACCOUNT, 1_000_00L)
        )

        val recurringRules = listOf(
            createTestRecurringRule(101, "Rent", 18_000_00L, accountId = 1, nextDueEpochDay = refEpochDay + 4), // Shortfall of 6k on day 4
            createTestRecurringRule(102, "Gym", 5_000_00L, accountId = 2, nextDueEpochDay = refEpochDay + 2),   // Covered (50k > 5k)
            createTestRecurringRule(103, "Loan EMI", 5_000_00L, accountId = 3, nextDueEpochDay = refEpochDay + 1) // Shortfall of 4k on day 1
        )

        val alerts = StatsPredictiveCalculations.computeShortfallAlerts(
            allAccounts = accounts,
            recurringRules = recurringRules,
            futureTransactions = emptyList(),
            todayEpochDay = refEpochDay,
            primaryCurrencyCode = "INR"
        )

        assertEquals("Should detect 2 shortfalls", 2, alerts.size)
        // First should be SBI due to daysUntilShortfall = 1
        assertEquals("SBI Petty", alerts[0].accountName)
        assertEquals(MinorMoney(4_000_00L), alerts[0].shortfallAmount)
        assertEquals(1L, alerts[0].daysUntilShortfall)

        // Second should be HDFC due to daysUntilShortfall = 4
        assertEquals("HDFC Salary", alerts[1].accountName)
        assertEquals(MinorMoney(6_000_00L), alerts[1].shortfallAmount)
        assertEquals(4L, alerts[1].daysUntilShortfall)
    }

    @Test
    fun testRecurringOccurrencesProjection() {
        // Weekly rule starting today
        val occurrences = StatsPredictiveCalculations.projectRecurringOccurrencesInHorizon(
            startDay = refEpochDay,
            frequency = RepeatFrequency.WEEKLY,
            todayEpochDay = refEpochDay,
            horizonEpochDay = refEpochDay + 14L
        )

        // Should hit on Day 0, Day 7, Day 14 (3 occurrences in 14-day window)
        assertEquals(3, occurrences.size)
        assertEquals(refEpochDay, occurrences[0])
        assertEquals(refEpochDay + 7L, occurrences[1])
        assertEquals(refEpochDay + 14L, occurrences[2])
    }

    @Test
    fun testSafeToSpend_overdrawnBankAccount_reducesTotalLiquidFunds() {
        val accounts = listOf(
            createTestAccount(1, "Savings", AccountGroup.SAVINGS, 50_000_00L),
            createTestAccount(2, "Overdrawn Checking", AccountGroup.BANK_ACCOUNT, -40_000_00L),
            createTestAccount(3, "Credit Card", AccountGroup.CREDIT_CARD, 0L)
        )

        val result = StatsPredictiveCalculations.computeSafeToSpend(
            allAccounts = accounts,
            recurringRules = emptyList(),
            futureTransactions = emptyList(),
            todayEpochDay = refEpochDay,
            periodStartEpochDay = refEpochDay - 10,
            periodEndEpochDay = refEpochDay + 20,
            primaryCurrencyCode = "INR",
            secondaryCurrencies = emptyList()
        )

        // Net liquid funds should be 50k - 40k = 10,000, not 50,000!
        assertEquals(MinorMoney(10_000_00L), result.totalLiquidBankAndCash)
        assertEquals(MinorMoney(10_000_00L), result.trueSafeToSpend)
    }

    @Test
    fun testShortfallAlerts_includesCreditCardSettlementFromBankAccount() {
        val accounts = listOf(
            createTestAccount(1, "Checking", AccountGroup.BANK_ACCOUNT, 15_000_00L),
            createTestAccount(2, "Credit Card", AccountGroup.CREDIT_CARD, -40_000_00L)
        )

        // CC_SETTLEMENT rule from Checking (1) to Credit Card (2) for 40,000
        val settlementRule = RecurringCommitmentItem(
            id = 10L,
            title = "Credit Card Bill Auto-Pay",
            type = TransactionType.CC_SETTLEMENT,
            amount = MinorMoney(40_000_00L),
            currencyCode = "INR",
            accountId = 1L,
            transferToAccountId = 2L,
            frequency = RepeatFrequency.MONTHLY,
            nextOccurrenceEpochDay = refEpochDay + 3L,
            isActive = true
        )

        val alerts = StatsPredictiveCalculations.computeShortfallAlerts(
            allAccounts = accounts,
            recurringRules = listOf(settlementRule),
            futureTransactions = emptyList(),
            todayEpochDay = refEpochDay,
            primaryCurrencyCode = "INR",
            secondaryCurrencies = emptyList()
        )

        assertEquals(1, alerts.size)
        val alert = alerts.first()
        assertEquals(1L, alert.accountId)
        assertEquals(MinorMoney(15_000_00L), alert.currentBalance)
        assertEquals(MinorMoney(40_000_00L), alert.upcomingDebits)
        assertEquals(MinorMoney(25_000_00L), alert.shortfallAmount)
        assertEquals(3L, alert.daysUntilShortfall)
    }

    @Test
    fun testSafeToSpend_preservesAnchorDayOfMonthFromStartDate() {
        // Start date is Jan 31st (anchor day 31)
        val jan31 = java.time.LocalDate.of(2026, 1, 31).toEpochDay()
        // Next occurrence is clamped to Feb 28th
        val feb28 = java.time.LocalDate.of(2026, 2, 28).toEpochDay()
        val mar31 = java.time.LocalDate.of(2026, 3, 31).toEpochDay()

        val rule = RecurringCommitmentItem(
            id = 20L,
            title = "End of Month Bill",
            type = TransactionType.EXPENSE,
            amount = MinorMoney(5_000_00L),
            currencyCode = "INR",
            accountId = 1L,
            frequency = RepeatFrequency.EVERY_MONTH,
            startDateEpochDay = jan31,
            nextOccurrenceEpochDay = feb28,
            isActive = true
        )

        // Today is Feb 1st, Horizon is Apr 5th
        val occurrences = StatsPredictiveCalculations.projectRecurringOccurrencesInHorizon(
            startDay = rule.nextOccurrenceEpochDay,
            frequency = rule.frequency,
            todayEpochDay = java.time.LocalDate.of(2026, 2, 1).toEpochDay(),
            horizonEpochDay = java.time.LocalDate.of(2026, 4, 5).toEpochDay(),
            anchorDayOfMonth = 31
        )

        // Occurrences should be Feb 28 and Mar 31 (re-anchored to 31st, not 28th)
        assertEquals(2, occurrences.size)
        assertEquals(feb28, occurrences[0])
        assertEquals(mar31, occurrences[1])
    }

    @Test
    fun testSafeToSpend_overdrawnBankWithZeroCardDebt_doesNotReportCardDebtExceedsCash() {
        val overdrawnAccount = createTestAccount(1, "Overdrawn Bank", AccountGroup.BANK_ACCOUNT, -5_000_00L)

        val safeToSpend = StatsPredictiveCalculations.computeSafeToSpend(
            allAccounts = listOf(overdrawnAccount),
            recurringRules = emptyList(),
            futureTransactions = emptyList(),
            todayEpochDay = refEpochDay,
            periodStartEpochDay = refEpochDay - 10,
            periodEndEpochDay = refEpochDay + 20,
            primaryCurrencyCode = "INR"
        )

        assertEquals(MinorMoney(-5_000_00L), safeToSpend.trueSafeToSpend)
        assertEquals(MinorMoney.ZERO, safeToSpend.totalCreditCardDebt)
        assertFalse(safeToSpend.status == SafeToSpendStatus.CARD_DEBT_EXCEEDS_CASH)
        assertEquals(SafeToSpendStatus.ZERO_RESERVES, safeToSpend.status)
    }

    @Test
    fun testShortfallAlerts_includesCashAccounts() {
        val cashAccount = createTestAccount(1, "Cash Wallet", AccountGroup.CASH, 2_000_00L)
        val recurringRule = createTestRecurringRule(
            id = 101L,
            title = "Cash Groceries",
            amountMinor = 3_000_00L,
            accountId = 1L,
            nextDueEpochDay = refEpochDay + 3L
        )
        val alerts = StatsPredictiveCalculations.computeShortfallAlerts(
            allAccounts = listOf(cashAccount),
            recurringRules = listOf(recurringRule),
            futureTransactions = emptyList(),
            todayEpochDay = refEpochDay,
            primaryCurrencyCode = "INR"
        )
        assertEquals(1, alerts.size)
        assertEquals("Cash Wallet", alerts[0].accountName)
        assertEquals(MinorMoney(1_000_00L), alerts[0].shortfallAmount)
    }

    @Test
    fun testShortfallAlerts_chronologicalSimulation_detectsOverdraftBeforeLaterInflow() {
        val checking = createTestAccount(1, "Checking", AccountGroup.BANK_ACCOUNT, 1_000_00L)
        val rentRule = createTestRecurringRule(
            id = 101L,
            title = "Rent",
            amountMinor = 10_000_00L,
            accountId = 1L,
            nextDueEpochDay = refEpochDay + 1L, // Due tomorrow (+1 day)
            type = TransactionType.EXPENSE
        )
        val salaryRule = createTestRecurringRule(
            id = 102L,
            title = "Salary",
            amountMinor = 50_000_00L,
            accountId = 1L,
            nextDueEpochDay = refEpochDay + 10L, // Arrives in 10 days
            type = TransactionType.INCOME
        )

        val alerts = StatsPredictiveCalculations.computeShortfallAlerts(
            allAccounts = listOf(checking),
            recurringRules = listOf(rentRule, salaryRule),
            futureTransactions = emptyList(),
            todayEpochDay = refEpochDay,
            primaryCurrencyCode = "INR"
        )

        assertEquals("Must trigger shortfall alert for dip before salary arrives", 1, alerts.size)
        assertEquals(MinorMoney(9_000_00L), alerts[0].shortfallAmount)
        assertEquals(1L, alerts[0].daysUntilShortfall)
        assertEquals(MinorMoney(10_000_00L), alerts[0].upcomingDebits)
    }

    @Test
    fun testShortfallAlerts_sameDayInflowBeforeOutflow_noFalseShortfall() {
        val checking = createTestAccount(1, "Checking", AccountGroup.BANK_ACCOUNT, 1_000_00L)
        val salaryRule = createTestRecurringRule(
            id = 102L,
            title = "Salary",
            amountMinor = 50_000_00L,
            accountId = 1L,
            nextDueEpochDay = refEpochDay + 1L, // Arrives tomorrow morning
            type = TransactionType.INCOME
        )
        val rentRule = createTestRecurringRule(
            id = 101L,
            title = "Rent",
            amountMinor = 10_000_00L,
            accountId = 1L,
            nextDueEpochDay = refEpochDay + 1L, // Due tomorrow
            type = TransactionType.EXPENSE
        )

        val alerts = StatsPredictiveCalculations.computeShortfallAlerts(
            allAccounts = listOf(checking),
            recurringRules = listOf(salaryRule, rentRule),
            futureTransactions = emptyList(),
            todayEpochDay = refEpochDay,
            primaryCurrencyCode = "INR"
        )

        assertTrue("Same-day inflow credited before outflow should avoid false shortfall alert", alerts.isEmpty())
    }

    @Test
    fun testShortfallAlerts_overdrawnCoveredByTodayInflow_noFalseShortfall() {
        val overdrawnChecking = createTestAccount(1, "Checking", AccountGroup.BANK_ACCOUNT, -1_000_00L)
        val salaryToday = createTestRecurringRule(
            id = 201L,
            title = "Salary",
            amountMinor = 50_000_00L,
            accountId = 1L,
            nextDueEpochDay = refEpochDay,
            type = TransactionType.INCOME
        )
        val billDay3 = createTestRecurringRule(
            id = 202L,
            title = "Internet",
            amountMinor = 2_000_00L,
            accountId = 1L,
            nextDueEpochDay = refEpochDay + 3L,
            type = TransactionType.EXPENSE
        )

        val alerts = StatsPredictiveCalculations.computeShortfallAlerts(
            allAccounts = listOf(overdrawnChecking),
            recurringRules = listOf(salaryToday, billDay3),
            futureTransactions = emptyList(),
            todayEpochDay = refEpochDay,
            primaryCurrencyCode = "INR"
        )

        assertTrue("Today's inflow recovering an overdrawn balance should avoid false shortfall alert", alerts.isEmpty())
    }

    @Test
    fun testSafeToSpend_overdrawnZeroBillsStatusAndTransferFees() {
        val overdrawnBank = createTestAccount(1, "Checking", AccountGroup.BANK_ACCOUNT, -5_000_00L)
        val savings = createTestAccount(2, "Savings", AccountGroup.SAVINGS, 10_000_00L)

        // With 0 bills and overdrawn net liquid? Keep totalLiquid < 0 -> overdrawn, bills == 0
        val statusState = StatsPredictiveCalculations.computeSafeToSpend(
            allAccounts = listOf(overdrawnBank),
            recurringRules = emptyList(),
            futureTransactions = emptyList(),
            todayEpochDay = refEpochDay,
            periodStartEpochDay = refEpochDay - 5L,
            periodEndEpochDay = refEpochDay + 25L,
            primaryCurrencyCode = "INR"
        )
        assertEquals(SafeToSpendStatus.ZERO_RESERVES, statusState.status)

        // Future liquid-to-liquid transfer carrying a transfer fee counts only the fee in commitments
        val futureTransferWithFee = TransactionItem(
            id = 301L,
            type = TransactionType.TRANSFER,
            timestampEpochMillis = (refEpochDay + 2L) * 86_400_000L,
            ledgerDateEpochDay = refEpochDay + 2L,
            amount = MinorMoney(3_000_00L),
            transferFee = MinorMoney(50_00L),
            currencyCode = "INR",
            accountId = 2L,
            accountName = "Savings",
            transferToAccountId = 1L,
            transferToAccountName = "Checking"
        )
        val feeState = StatsPredictiveCalculations.computeSafeToSpend(
            allAccounts = listOf(savings, createTestAccount(1, "Checking", AccountGroup.BANK_ACCOUNT, 5_000_00L)),
            recurringRules = emptyList(),
            futureTransactions = listOf(futureTransferWithFee),
            todayEpochDay = refEpochDay,
            periodStartEpochDay = refEpochDay - 5L,
            periodEndEpochDay = refEpochDay + 25L,
            primaryCurrencyCode = "INR"
        )
        assertEquals(MinorMoney(50_00L), feeState.upcoming14DayCommitments)
    }
}
