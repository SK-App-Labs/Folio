/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/test/java/com/folioledger/app/domain/DueRemindersEvaluatorTest.kt
package com.folioledger.app.domain

import com.folioledger.app.data.notification.DueRemindersScheduler
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.RecurringCommitmentItem
import com.folioledger.app.domain.model.RepeatFrequency
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.util.DueRemindersEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DueRemindersEvaluatorTest {

    @Test
    fun testCreditCardReminder_triggeredWithinNoticeWindow() {
        // Statement closes on 15th, due on 5th of next month.
        // Today is 2026-11-02 (3 days before due date 2026-11-05).
        val today = LocalDate.of(2026, 11, 2)
        val card = AccountSummary(
            id = 101L,
            name = "ICICI Amazon Pay",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "INR",
            currentBalance = MinorMoney(-15_000_00L),
            creditLimit = MinorMoney(100_000_00L),
            creditCardStatementPayable = MinorMoney(12_500_00L),
            creditCardUnbilledAmount = MinorMoney(2_500_00L),
            settlementDayOfMonth = 15,
            paymentDueDayOfMonth = 5,
        )

        val reminders = DueRemindersEvaluator.evaluateCreditCardReminders(
            accounts = listOf(card),
            todayEpochDay = today.toEpochDay(),
            leadDaysNotice = 3,
        )

        assertEquals(1, reminders.size)
        val alert = reminders.first()
        assertEquals(101L, alert.accountId)
        assertEquals("ICICI Amazon Pay", alert.accountName)
        assertEquals(MinorMoney(12_500_00L), alert.amountDue)
        assertEquals(LocalDate.of(2026, 11, 5), alert.dueDate)
        assertEquals(3L, alert.daysRemaining)
        assertFalse(alert.isOverdue)
    }

    @Test
    fun testCreditCardReminder_skippedOutsideNoticeWindow() {
        // Today is 2026-10-25 (11 days before due date 2026-11-05).
        val today = LocalDate.of(2026, 10, 25)
        val card = AccountSummary(
            id = 101L,
            name = "ICICI Amazon Pay",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "INR",
            currentBalance = MinorMoney(-15_000_00L),
            creditLimit = MinorMoney(100_000_00L),
            creditCardStatementPayable = MinorMoney(12_500_00L),
            settlementDayOfMonth = 15,
            paymentDueDayOfMonth = 5,
        )

        val reminders = DueRemindersEvaluator.evaluateCreditCardReminders(
            accounts = listOf(card),
            todayEpochDay = today.toEpochDay(),
            leadDaysNotice = 3,
        )

        assertTrue(reminders.isEmpty())
    }

    @Test
    fun testCreditCardReminder_skippedWhenZeroBalance() {
        // Today is 2026-11-04 (1 day before due), but card was fully paid off
        val today = LocalDate.of(2026, 11, 4)
        val card = AccountSummary(
            id = 101L,
            name = "HDFC Regalia",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "INR",
            currentBalance = MinorMoney.ZERO,
            creditLimit = MinorMoney(200_000_00L),
            creditCardStatementPayable = MinorMoney.ZERO,
            settlementDayOfMonth = 15,
            paymentDueDayOfMonth = 5,
        )

        val reminders = DueRemindersEvaluator.evaluateCreditCardReminders(
            accounts = listOf(card),
            todayEpochDay = today.toEpochDay(),
            leadDaysNotice = 3,
        )

        assertTrue(reminders.isEmpty())
    }

    @Test
    fun testCreditCardReminder_skippedWhenStatementPaidEvenIfActiveCycleHasSpend() {
        // Today is 2026-11-04 (1 day before statement due date 2026-11-05).
        // Statement was paid in full (creditCardStatementPayable = ZERO),
        // but user spent money in the new active cycle (currentBalance = -3,500).
        val today = LocalDate.of(2026, 11, 4)
        val card = AccountSummary(
            id = 101L,
            name = "HDFC Regalia",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "INR",
            currentBalance = MinorMoney(-3_500_00L),
            creditLimit = MinorMoney(200_000_00L),
            creditCardStatementPayable = MinorMoney.ZERO,
            settlementDayOfMonth = 15,
            paymentDueDayOfMonth = 5,
        )

        val reminders = DueRemindersEvaluator.evaluateCreditCardReminders(
            accounts = listOf(card),
            todayEpochDay = today.toEpochDay(),
            leadDaysNotice = 3,
        )

        assertTrue("Should not alert for upcoming statement when statement is already settled", reminders.isEmpty())
    }

    @Test
    fun testCreditCardReminder_skippedWhenPositiveOverpaymentBalance() {
        // User overpaid card and has positive balance credit of +5,000 INR
        val today = LocalDate.of(2026, 11, 4)
        val card = AccountSummary(
            id = 101L,
            name = "ICICI Amazon Pay",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "INR",
            currentBalance = MinorMoney(5_000_00L),
            creditLimit = MinorMoney(100_000_00L),
            creditCardStatementPayable = null,
            settlementDayOfMonth = 15,
            paymentDueDayOfMonth = 5,
        )

        val reminders = DueRemindersEvaluator.evaluateCreditCardReminders(
            accounts = listOf(card),
            todayEpochDay = today.toEpochDay(),
            leadDaysNotice = 3,
        )

        assertTrue("Should not treat positive overpayment balance as debt due", reminders.isEmpty())
    }

    @Test
    fun testCreditCardReminder_overdueTriggersAlert() {
        // Today is 2026-11-06 (1 day after due date 2026-11-05)
        val today = LocalDate.of(2026, 11, 6)
        val card = AccountSummary(
            id = 101L,
            name = "Axis Bank Magnus",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "INR",
            currentBalance = MinorMoney(-25_000_00L),
            creditLimit = MinorMoney(300_000_00L),
            creditCardStatementPayable = MinorMoney(25_000_00L),
            settlementDayOfMonth = 15,
            paymentDueDayOfMonth = 5,
        )

        val reminders = DueRemindersEvaluator.evaluateCreditCardReminders(
            accounts = listOf(card),
            todayEpochDay = today.toEpochDay(),
            leadDaysNotice = 3,
        )

        assertEquals(1, reminders.size)
        val alert = reminders.first()
        assertTrue(alert.isOverdue)
        assertEquals(-1L, alert.daysRemaining)
    }

    @Test
    fun testBillReminder_triggeredWithinNoticeWindow() {
        val today = LocalDate.of(2026, 11, 10)
        val rule = RecurringCommitmentItem(
            id = 1L,
            title = "Netflix Subscription",
            type = TransactionType.EXPENSE,
            amount = MinorMoney(649_00L),
            currencyCode = "INR",
            accountId = 5L,
            frequency = RepeatFrequency.MONTHLY,
            nextOccurrenceEpochDay = LocalDate.of(2026, 11, 11).toEpochDay(),
            accountName = "HDFC Salary Account",
            isActive = true,
        )

        val reminders = DueRemindersEvaluator.evaluateBillReminders(
            recurringRules = listOf(rule),
            todayEpochDay = today.toEpochDay(),
            leadDaysNotice = 1,
        )

        assertEquals(1, reminders.size)
        val alert = reminders.first()
        assertEquals(1L, alert.ruleId)
        assertEquals("Netflix Subscription", alert.title)
        assertEquals(MinorMoney(649_00L), alert.amount)
        assertEquals(1L, alert.daysRemaining)
    }

    @Test
    fun testBillReminder_inactiveRuleSkipped() {
        val today = LocalDate.of(2026, 11, 10)
        val rule = RecurringCommitmentItem(
            id = 2L,
            title = "Gym Membership",
            type = TransactionType.EXPENSE,
            amount = MinorMoney(2_000_00L),
            currencyCode = "INR",
            accountId = 5L,
            frequency = RepeatFrequency.MONTHLY,
            nextOccurrenceEpochDay = LocalDate.of(2026, 11, 11).toEpochDay(),
            accountName = "HDFC Salary Account",
            isActive = false,
        )

        val reminders = DueRemindersEvaluator.evaluateBillReminders(
            recurringRules = listOf(rule),
            todayEpochDay = today.toEpochDay(),
            leadDaysNotice = 1,
        )

        assertTrue(reminders.isEmpty())
    }

    @Test
    fun testCreditCardReminder_priorCycleOverdueRemainsVisibleAfterStatementRollover() {
        // Statement closes on 18th, due on 5th of next month.
        // Sep 18 statement of 25,000 was due on Oct 5.
        // Today is Oct 19: the October statement has closed on Oct 18, so lastStatementDueDate advanced to Nov 5.
        // Even though Nov 5 is +17 days in the future, the user owes 25,000 from the prior statement.
        val today = LocalDate.of(2026, 10, 19)
        val card = AccountSummary(
            id = 501L,
            name = "Overdue Card",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "INR",
            currentBalance = MinorMoney(-25_000_00L),
            creditLimit = MinorMoney(100_000_00L),
            creditCardStatementPayable = MinorMoney(25_000_00L),
            creditCardPriorOverduePayable = MinorMoney(25_000_00L),
            settlementDayOfMonth = 18,
            paymentDueDayOfMonth = 5,
        )

        val reminders = DueRemindersEvaluator.evaluateCreditCardReminders(
            accounts = listOf(card),
            todayEpochDay = today.toEpochDay(),
            leadDaysNotice = 3,
        )

        assertEquals(1, reminders.size)
        val alert = reminders.first()
        assertTrue("Must be marked overdue because prior cycle debt is past its due date", alert.isOverdue)
        assertEquals(MinorMoney(25_000_00L), alert.amountDue)
        assertEquals(LocalDate.of(2026, 10, 5), alert.dueDate)
        assertEquals(-14L, alert.daysRemaining)
    }

    @Test
    fun testAmountDueNow_usesLargerStatementPayableWhenPriorOverdueExists() {
        val today = LocalDate.of(2026, 10, 19)
        val card = AccountSummary(
            id = 5L,
            name = "Card With Recent Statement",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "INR",
            currentBalance = MinorMoney(-30_000_00L),
            creditLimit = MinorMoney(100_000_00L),
            creditCardStatementPayable = MinorMoney(25_000_00L), // full statement due is 25,000
            creditCardPriorOverduePayable = MinorMoney(5_000_00L), // prior overdue was 5,000
            settlementDayOfMonth = 18,
            paymentDueDayOfMonth = 5,
        )

        val reminders = DueRemindersEvaluator.evaluateCreditCardReminders(
            accounts = listOf(card),
            todayEpochDay = today.toEpochDay(),
            leadDaysNotice = 3,
        )

        assertEquals(1, reminders.size)
        // Should report the full statement payable of 25,000 rather than under-reporting 5,000
        assertEquals(MinorMoney(25_000_00L), reminders.first().amountDue)
    }

    @Test
    fun testBillReminder_excludesIncomeAndRefundAndPastEndDateRules() {
        val today = LocalDate.of(2026, 11, 10)
        val tomorrowEpoch = today.plusDays(1).toEpochDay()
        val salaryIncome = RecurringCommitmentItem(
            id = 10L,
            title = "Monthly Salary",
            type = TransactionType.INCOME,
            amount = MinorMoney(500_000_00L),
            currencyCode = "INR",
            accountId = 1L,
            frequency = RepeatFrequency.MONTHLY,
            nextOccurrenceEpochDay = tomorrowEpoch,
            accountName = "Bank",
            isActive = true,
        )
        val recurringRefund = RecurringCommitmentItem(
            id = 11L,
            title = "Cashback Rebate",
            type = TransactionType.REFUND,
            amount = MinorMoney(500_00L),
            currencyCode = "INR",
            accountId = 1L,
            frequency = RepeatFrequency.MONTHLY,
            nextOccurrenceEpochDay = tomorrowEpoch,
            accountName = "Bank",
            isActive = true,
        )
        val expiredExpense = RecurringCommitmentItem(
            id = 12L,
            title = "Expired EMI",
            type = TransactionType.EXPENSE,
            amount = MinorMoney(1_000_00L),
            currencyCode = "INR",
            accountId = 1L,
            frequency = RepeatFrequency.MONTHLY,
            nextOccurrenceEpochDay = tomorrowEpoch,
            endDateEpochDay = today.toEpochDay(), // already past endDate
            accountName = "Bank",
            isActive = true,
        )
        val validDayZeroTransfer = RecurringCommitmentItem(
            id = 13L,
            title = "SIP Transfer",
            type = TransactionType.TRANSFER,
            amount = MinorMoney(10_000_00L),
            currencyCode = "INR",
            accountId = 1L,
            frequency = RepeatFrequency.MONTHLY,
            nextOccurrenceEpochDay = today.toEpochDay(),
            endDateEpochDay = today.toEpochDay(),
            accountName = "Bank",
            isActive = true,
        )

        val reminders = DueRemindersEvaluator.evaluateBillReminders(
            recurringRules = listOf(salaryIncome, recurringRefund, expiredExpense, validDayZeroTransfer),
            todayEpochDay = today.toEpochDay(),
            leadDaysNotice = 0,
        )

        assertEquals(1, reminders.size)
        assertEquals(13L, reminders.first().ruleId)
        assertEquals(0L, reminders.first().daysRemaining)
    }

    @Test
    fun testCreditCardNotificationRecencyWindow_stopsOverdueSpamAfterThreeDays() {
        // Sep 18 statement due Oct 5; today is Oct 19 (-14 days overdue, next statement due Nov 5 in +17 days)
        val today = LocalDate.of(2026, 10, 19)
        val card = AccountSummary(
            id = 502L,
            name = "Revolving Card",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "INR",
            currentBalance = MinorMoney(-25_000_00L),
            creditLimit = MinorMoney(100_000_00L),
            creditCardStatementPayable = MinorMoney(25_000_00L),
            creditCardPriorOverduePayable = MinorMoney(25_000_00L),
            settlementDayOfMonth = 18,
            paymentDueDayOfMonth = 5,
        )

        val notificationAlerts = DueRemindersEvaluator.evaluateCreditCardReminders(
            accounts = listOf(card),
            todayEpochDay = today.toEpochDay(),
            leadDaysNotice = 3,
            enforceOverdueRecencyWindow = true,
        )
        assertTrue("Push notifications must not spam daily beyond -3 days overdue", notificationAlerts.isEmpty())

        // Widget view (enforceOverdueRecencyWindow = false) continues surfacing prior-cycle overdue debt
        val widgetAlerts = DueRemindersEvaluator.evaluateCreditCardReminders(
            accounts = listOf(card),
            todayEpochDay = today.toEpochDay(),
            leadDaysNotice = 3,
            enforceOverdueRecencyWindow = false,
        )
        assertEquals(1, widgetAlerts.size)
    }

    @Test
    fun testDueRemindersScheduler_preservesWallClockAcrossDstTransition() {
        val zone = ZoneId.of("America/New_York")
        // Spring forward eve: 2026-03-07 20:00 EST -> next target should be 2026-03-08 09:00 EDT
        val now = ZonedDateTime.of(2026, 3, 7, 20, 0, 0, 0, zone)
        val next = DueRemindersScheduler.computeNextTriggerDateTime(now, hourOfDay = 9, minute = 0)
        assertEquals(LocalDate.of(2026, 3, 8), next.toLocalDate())
        assertEquals(9, next.hour)
        assertEquals(0, next.minute)
    }

    @Test
    fun testDueRemindersScheduler_midnightTargetDoesNotSkipUpcomingDayAt235930() {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 11, 26, 23, 59, 30, 0, zone)
        val next = DueRemindersScheduler.computeNextTriggerDateTime(
            now = now,
            hourOfDay = 0,
            minute = 0,
            minTriggerAfter = now.plusMinutes(1L),
        )
        assertEquals(LocalDate.of(2026, 11, 27), next.toLocalDate())
        assertEquals(0, next.hour)
        assertEquals(0, next.minute)
    }
}
