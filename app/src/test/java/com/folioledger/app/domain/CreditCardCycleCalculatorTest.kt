/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/test/java/com/folioledger/app/domain/CreditCardCycleCalculatorTest.kt
package com.folioledger.app.domain

import com.folioledger.app.domain.util.CreditCardCycleCalculator
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreditCardCycleCalculatorTest {

    @Test
    fun testCycleWindow_beforeStatementClose() {
        // Statement closes on 15th, payment due on 5th of following month.
        // Today is 2026-10-10 (before statement close).
        val refDate = LocalDate.of(2026, 10, 10)
        val cycle = CreditCardCycleCalculator.computeCycleWindow(
            referenceDateEpochDay = refDate.toEpochDay(),
            settlementDayOfMonth = 15,
            paymentDueDayOfMonth = 5
        )

        // Last closed statement was September 15th
        assertEquals(LocalDate.of(2026, 9, 15), cycle.lastStatementEnd)
        // Active cycle ends October 15th
        assertEquals(LocalDate.of(2026, 10, 15), cycle.activeCycleEnd)
        // Next due date for active cycle is November 5th
        assertEquals(LocalDate.of(2026, 11, 5), cycle.activeCycleNextDueDate)
    }

    @Test
    fun testCycleWindow_afterStatementClose() {
        // Statement closes on 15th, payment due on 5th of following month.
        // Today is 2026-10-20 (after statement close).
        val refDate = LocalDate.of(2026, 10, 20)
        val cycle = CreditCardCycleCalculator.computeCycleWindow(
            referenceDateEpochDay = refDate.toEpochDay(),
            settlementDayOfMonth = 15,
            paymentDueDayOfMonth = 5
        )

        // Last closed statement was October 15th
        assertEquals(LocalDate.of(2026, 10, 15), cycle.lastStatementEnd)
        // Due date for the last statement is November 5th
        assertEquals(LocalDate.of(2026, 11, 5), cycle.lastStatementDueDate)
        // Active cycle ends November 15th
        assertEquals(LocalDate.of(2026, 11, 15), cycle.activeCycleEnd)
    }

    @Test
    fun testCycleWindow_monthEndClamping() {
        // Statement day set to 31.
        // In April (30 days), it should clamp to 30.
        val refDate = LocalDate.of(2026, 4, 10)
        val cycle = CreditCardCycleCalculator.computeCycleWindow(
            referenceDateEpochDay = refDate.toEpochDay(),
            settlementDayOfMonth = 31,
            paymentDueDayOfMonth = 20
        )

        // March has 31 days
        assertEquals(LocalDate.of(2026, 3, 31), cycle.lastStatementEnd)
        // April has 30 days -> clamped to 30
        assertEquals(LocalDate.of(2026, 4, 30), cycle.activeCycleEnd)
    }

    @Test
    fun computeDueDateAfterStatementClose_februaryClamping_preservesMinimum7DaysGracePeriod() {
        // Scenario 1: Statement closes Feb 22, due on 29th.
        // In 2026 (non-leap), Feb 29 clamps to Feb 28 (only 6 days).
        // Grace period rule must push due date to March 29 (>= 7 days).
        val closeDateFeb22 = LocalDate.of(2026, 2, 22)
        val dueDate1 = CreditCardCycleCalculator.computeDueDateAfterStatementClose(
            statementCloseDate = closeDateFeb22,
            statementDayOfMonth = 22,
            dueDayOfMonth = 29
        )
        assertEquals(LocalDate.of(2026, 3, 29), dueDate1)
        assertTrue(java.time.temporal.ChronoUnit.DAYS.between(closeDateFeb22, dueDate1) >= 7L)

        // Scenario 2: Statement closes Feb 28, due on 2nd of next month.
        // March 2 is only 2 days after Feb 28.
        // Grace period rule must push due date to April 2 (>= 7 days).
        val closeDateFeb28 = LocalDate.of(2026, 2, 28)
        val dueDate2 = CreditCardCycleCalculator.computeDueDateAfterStatementClose(
            statementCloseDate = closeDateFeb28,
            statementDayOfMonth = 28,
            dueDayOfMonth = 2
        )
        assertEquals(LocalDate.of(2026, 4, 2), dueDate2)
        assertTrue(java.time.temporal.ChronoUnit.DAYS.between(closeDateFeb28, dueDate2) >= 7L)
    }

    @Test
    fun computeDueDateAfterStatementClose_coercesOutOfRangeDueDayBeforeGraceCheck() {
        val closeJan28 = LocalDate.of(2026, 1, 28)
        // Without clamping 35 -> 31 before subtraction, (35 - 28) == 7 would try Jan 31 (only 3 days).
        // With clamping first, (31 - 28) == 3 < 7 -> February 28 (31 days).
        val dueDate = CreditCardCycleCalculator.computeDueDateAfterStatementClose(
            statementCloseDate = closeJan28,
            statementDayOfMonth = 28,
            dueDayOfMonth = 35
        )
        assertEquals(LocalDate.of(2026, 2, 28), dueDate)
        assertEquals(
            LocalDate.of(2026, 1, 28),
            CreditCardCycleCalculator.resolveLastStatementCloseDate(
                referenceDateEpochDay = LocalDate.of(2026, 2, 10).toEpochDay(),
                settlementDayOfMonth = 28
            )
        )
    }
}
