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

import com.folioledger.app.domain.model.BudgetPeriodType
import com.folioledger.app.domain.model.BudgetWindowStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BudgetWindowAndRolloverCalculatorTest {

    @Test
    fun testMaxLookbackPeriodsFor_matchesBoundedCadenceRules() {
        assertEquals(26, BudgetWindowAndRolloverCalculator.maxLookbackPeriodsFor(BudgetPeriodType.WEEKLY))
        assertEquals(13, BudgetWindowAndRolloverCalculator.maxLookbackPeriodsFor(BudgetPeriodType.BI_WEEKLY))
        assertEquals(24, BudgetWindowAndRolloverCalculator.maxLookbackPeriodsFor(BudgetPeriodType.MONTHLY))
        assertEquals(8, BudgetWindowAndRolloverCalculator.maxLookbackPeriodsFor(BudgetPeriodType.QUARTERLY))
        assertEquals(2, BudgetWindowAndRolloverCalculator.maxLookbackPeriodsFor(BudgetPeriodType.ANNUAL))
        assertEquals(0, BudgetWindowAndRolloverCalculator.maxLookbackPeriodsFor(BudgetPeriodType.CUSTOM))
    }

    @Test
    fun testResolveWindow_weekly_mondayAndSundayStarts() {
        // Wednesday Nov 19, 2025
        val wednesday = LocalDate.of(2025, 11, 19).toEpochDay()

        // Week starts Monday (1) -> Nov 17 (Mon) to Nov 23 (Sun)
        val monRange = BudgetWindowAndRolloverCalculator.resolveWindow(
            periodType = BudgetPeriodType.WEEKLY,
            referenceDateEpochDay = wednesday,
            monthStartDay = 1,
            weekStartDayIso = 1
        )
        assertEquals(LocalDate.of(2025, 11, 17).toEpochDay(), monRange.startEpochDay)
        assertEquals(LocalDate.of(2025, 11, 23).toEpochDay(), monRange.endEpochDay)

        // Week starts Sunday (7) -> Nov 16 (Sun) to Nov 22 (Sat)
        val sunRange = BudgetWindowAndRolloverCalculator.resolveWindow(
            periodType = BudgetPeriodType.WEEKLY,
            referenceDateEpochDay = wednesday,
            monthStartDay = 1,
            weekStartDayIso = 7
        )
        assertEquals(LocalDate.of(2025, 11, 16).toEpochDay(), sunRange.startEpochDay)
        assertEquals(LocalDate.of(2025, 11, 22).toEpochDay(), sunRange.endEpochDay)
    }

    @Test
    fun testResolveWindow_biWeekly_anchoredCycles() {
        // Anchor: Friday Jan 3, 2025
        val anchorFriday = LocalDate.of(2025, 1, 3).toEpochDay()

        // Test day inside cycle 0: Jan 10, 2025
        val jan10 = LocalDate.of(2025, 1, 10).toEpochDay()
        val cycle0 = BudgetWindowAndRolloverCalculator.resolveWindow(
            periodType = BudgetPeriodType.BI_WEEKLY,
            referenceDateEpochDay = jan10,
            monthStartDay = 1,
            weekStartDayIso = 1,
            anchorStartEpochDay = anchorFriday
        )
        assertEquals(anchorFriday, cycle0.startEpochDay)
        assertEquals(LocalDate.of(2025, 1, 16).toEpochDay(), cycle0.endEpochDay)

        // Test day inside cycle 1: Jan 17, 2025
        val jan17 = LocalDate.of(2025, 1, 17).toEpochDay()
        val cycle1 = BudgetWindowAndRolloverCalculator.resolveWindow(
            periodType = BudgetPeriodType.BI_WEEKLY,
            referenceDateEpochDay = jan17,
            monthStartDay = 1,
            weekStartDayIso = 1,
            anchorStartEpochDay = anchorFriday
        )
        assertEquals(LocalDate.of(2025, 1, 17).toEpochDay(), cycle1.startEpochDay)
        assertEquals(LocalDate.of(2025, 1, 30).toEpochDay(), cycle1.endEpochDay)

        // Test day before anchor (negative floorDiv): Dec 25, 2024
        val dec25 = LocalDate.of(2024, 12, 25).toEpochDay()
        val cycleNeg1 = BudgetWindowAndRolloverCalculator.resolveWindow(
            periodType = BudgetPeriodType.BI_WEEKLY,
            referenceDateEpochDay = dec25,
            monthStartDay = 1,
            weekStartDayIso = 1,
            anchorStartEpochDay = anchorFriday
        )
        assertEquals(LocalDate.of(2024, 12, 20).toEpochDay(), cycleNeg1.startEpochDay)
        assertEquals(LocalDate.of(2025, 1, 2).toEpochDay(), cycleNeg1.endEpochDay)
    }

    @Test
    fun testResolveWindow_monthly_standardAndCrossMonth() {
        // Standard monthStartDay = 1: Feb 15, 2024 (leap year)
        val feb15 = LocalDate.of(2024, 2, 15).toEpochDay()
        val stdFeb = BudgetWindowAndRolloverCalculator.resolveWindow(
            periodType = BudgetPeriodType.MONTHLY,
            referenceDateEpochDay = feb15,
            monthStartDay = 1,
            weekStartDayIso = 1
        )
        assertEquals(LocalDate.of(2024, 2, 1).toEpochDay(), stdFeb.startEpochDay)
        assertEquals(LocalDate.of(2024, 2, 29).toEpochDay(), stdFeb.endEpochDay)

        // Cross-month monthStartDay = 25: Feb 20, 2025
        // Cycle is Jan 25, 2025 to Feb 24, 2025
        val feb20 = LocalDate.of(2025, 2, 20).toEpochDay()
        val crossFeb = BudgetWindowAndRolloverCalculator.resolveWindow(
            periodType = BudgetPeriodType.MONTHLY,
            referenceDateEpochDay = feb20,
            monthStartDay = 25,
            weekStartDayIso = 1
        )
        assertEquals(LocalDate.of(2025, 1, 25).toEpochDay(), crossFeb.startEpochDay)
        assertEquals(LocalDate.of(2025, 2, 24).toEpochDay(), crossFeb.endEpochDay)
    }

    @Test
    fun testResolveQuarterlyWindow_contiguousAndAlignedWithAnnual() {
        val safeStartDay = 25
        // Q1: Jan 10, 2025 -> inside Dec 25, 2024 to Mar 24, 2025
        val jan10 = LocalDate.of(2025, 1, 10).toEpochDay()
        val q1 = BudgetWindowAndRolloverCalculator.resolveQuarterlyWindow(jan10, safeStartDay)
        assertEquals(LocalDate.of(2024, 12, 25).toEpochDay(), q1.startEpochDay)
        assertEquals(LocalDate.of(2025, 3, 24).toEpochDay(), q1.endEpochDay)
        assertEquals("Q1 2025", q1.label)

        // Q2: Apr 5, 2025
        val apr5 = LocalDate.of(2025, 4, 5).toEpochDay()
        val q2 = BudgetWindowAndRolloverCalculator.resolveQuarterlyWindow(apr5, safeStartDay)
        assertEquals(LocalDate.of(2025, 3, 25).toEpochDay(), q2.startEpochDay)
        assertEquals(LocalDate.of(2025, 6, 24).toEpochDay(), q2.endEpochDay)
        assertEquals("Q2 2025", q2.label)

        // Q1 end + 1 day == Q2 start (perfectly contiguous)
        assertEquals(q1.endEpochDay + 1L, q2.startEpochDay)
    }

    @Test
    fun testResolveWindow_customDateRange() {
        val start = LocalDate.of(2025, 10, 10).toEpochDay()
        val end = LocalDate.of(2025, 10, 24).toEpochDay()

        // Normal order
        val window = BudgetWindowAndRolloverCalculator.resolveWindow(
            periodType = BudgetPeriodType.CUSTOM,
            referenceDateEpochDay = start,
            monthStartDay = 1,
            weekStartDayIso = 1,
            anchorStartEpochDay = start,
            customEndEpochDay = end
        )
        assertEquals(start, window.startEpochDay)
        assertEquals(end, window.endEpochDay)

        // Inverted order auto-normalizes
        val invertedWindow = BudgetWindowAndRolloverCalculator.resolveWindow(
            periodType = BudgetPeriodType.CUSTOM,
            referenceDateEpochDay = start,
            monthStartDay = 1,
            weekStartDayIso = 1,
            anchorStartEpochDay = end,
            customEndEpochDay = start
        )
        assertEquals(start, invertedWindow.startEpochDay)
        assertEquals(end, invertedWindow.endEpochDay)

        // Window status
        val todayUpcoming = LocalDate.of(2025, 10, 1).toEpochDay()
        val todayActive = LocalDate.of(2025, 10, 15).toEpochDay()
        val todayCompleted = LocalDate.of(2025, 10, 25).toEpochDay()

        assertEquals(BudgetWindowStatus.UPCOMING, BudgetWindowAndRolloverCalculator.resolveWindowStatus(window, todayUpcoming))
        assertEquals(BudgetWindowStatus.ACTIVE, BudgetWindowAndRolloverCalculator.resolveWindowStatus(window, todayActive))
        assertEquals(BudgetWindowStatus.COMPLETED, BudgetWindowAndRolloverCalculator.resolveWindowStatus(window, todayCompleted))
    }

    @Test
    fun testComputeRolloverAndProgress_surplusOnly_noDebtCarried() {
        val w1 = BudgetWindowAndRolloverCalculator.ResolvedBudgetWindow(100L, 129L, "Jan")
        val w2 = BudgetWindowAndRolloverCalculator.ResolvedBudgetWindow(130L, 159L, "Feb")
        val activeWindow = BudgetWindowAndRolloverCalculator.ResolvedBudgetWindow(160L, 189L, "Mar")

        val spendMap = mapOf(
            100L to 300_00L, // W1: limit 500, spent 300 -> +200 carry
            130L to 800_00L, // W2: effective limit 500+200=700, spent 800 -> deficit -100 clamped to 0
            160L to 150_00L  // Active W3: limit 500+0=500, spent 150 -> remaining 350
        )

        val result = BudgetWindowAndRolloverCalculator.computeRolloverAndProgress(
            baseLimitMinor = 500_00L,
            rolloverEnabled = true,
            rolloverIncludeDeficit = false,
            historyWindows = listOf(w1, w2),
            activeWindow = activeWindow,
            spendInWindowProvider = { start, _ -> spendMap[start] ?: 0L }
        )

        assertEquals(0L, result.rolloverMinor) // -100 clamped to 0
        assertEquals(500_00L, result.effectiveLimitMinor)
        assertEquals(150_00L, result.spentMinor)
        assertEquals(350_00L, result.remainingMinor)
        assertEquals(30f, result.usagePercentage, 0.01f)
    }

    @Test
    fun testComputeRolloverAndProgress_surplusAndDeficitCarried() {
        val w1 = BudgetWindowAndRolloverCalculator.ResolvedBudgetWindow(100L, 129L, "Jan")
        val w2 = BudgetWindowAndRolloverCalculator.ResolvedBudgetWindow(130L, 159L, "Feb")
        val activeWindow = BudgetWindowAndRolloverCalculator.ResolvedBudgetWindow(160L, 189L, "Mar")

        val spendMap = mapOf(
            100L to 300_00L, // W1: limit 500, spent 300 -> +200 carry
            130L to 800_00L, // W2: effective limit 500+200=700, spent 800 -> net deficit -100 carried
            160L to 150_00L  // Active W3: limit 500+(-100)=400, spent 150 -> remaining 250
        )

        val result = BudgetWindowAndRolloverCalculator.computeRolloverAndProgress(
            baseLimitMinor = 500_00L,
            rolloverEnabled = true,
            rolloverIncludeDeficit = true,
            historyWindows = listOf(w1, w2),
            activeWindow = activeWindow,
            spendInWindowProvider = { start, _ -> spendMap[start] ?: 0L }
        )

        assertEquals(-100_00L, result.rolloverMinor)
        assertEquals(400_00L, result.effectiveLimitMinor)
        assertEquals(150_00L, result.spentMinor)
        assertEquals(250_00L, result.remainingMinor)
        assertEquals(37.5f, result.usagePercentage, 0.01f)
    }

    @Test
    fun testComputeRolloverAndProgress_extremeDeficitExceedingBaseLimit() {
        val w1 = BudgetWindowAndRolloverCalculator.ResolvedBudgetWindow(100L, 129L, "Jan")
        val activeWindow = BudgetWindowAndRolloverCalculator.ResolvedBudgetWindow(130L, 159L, "Feb")

        // Period 1 limit 500_00, spent 1100_00 -> deficit -600_00
        val spendMap = mapOf(
            100L to 1100_00L,
            130L to 50_00L
        )

        val result = BudgetWindowAndRolloverCalculator.computeRolloverAndProgress(
            baseLimitMinor = 500_00L,
            rolloverEnabled = true,
            rolloverIncludeDeficit = true,
            historyWindows = listOf(w1),
            activeWindow = activeWindow,
            spendInWindowProvider = { start, _ -> spendMap[start] ?: 0L }
        )

        // Effective limit: 500_00 + (-600_00) = -100_00
        assertEquals(-600_00L, result.rolloverMinor)
        assertEquals(-100_00L, result.effectiveLimitMinor)
        assertEquals(50_00L, result.spentMinor)
        assertEquals(-150_00L, result.remainingMinor)
        // Over budget immediately
        assertEquals(100f, result.usagePercentage, 0.01f)
    }

    @Test
    fun testBuildRolloverHistoryWindows_respectsCadenceCap() {
        // Active window in March 2026
        val active = BudgetWindowAndRolloverCalculator.resolveWindow(
            periodType = BudgetPeriodType.MONTHLY,
            referenceDateEpochDay = LocalDate.of(2026, 3, 15).toEpochDay(),
            monthStartDay = 1,
            weekStartDayIso = 1
        )

        // Start 40 months ago in Nov 2022
        val start40MonthsAgo = LocalDate.of(2022, 11, 1).toEpochDay()
        val history = BudgetWindowAndRolloverCalculator.buildRolloverHistoryWindows(
            periodType = BudgetPeriodType.MONTHLY,
            activeWindow = active,
            rolloverEnabled = true,
            rolloverStartEpochDay = start40MonthsAgo,
            monthStartDay = 1,
            weekStartDayIso = 1,
            maxPeriods = BudgetWindowAndRolloverCalculator.maxLookbackPeriodsFor(BudgetPeriodType.MONTHLY) // 24
        )

        // Bounded to 24 periods
        assertEquals(24, history.size)
        // Last history window is immediately prior month (Feb 2026)
        assertEquals(LocalDate.of(2026, 2, 28).toEpochDay(), history.last().endEpochDay)
        assertEquals(active.startEpochDay - 1L, history.last().endEpochDay)
    }

    @Test
    fun testResolveWindow_annual_alignsWithQuarterlyQ1StartAndQ4End() {
        val safeStartDay = 25
        val testDate = LocalDate.of(2025, 5, 15).toEpochDay()

        val annualWindow = BudgetWindowAndRolloverCalculator.resolveWindow(
            periodType = BudgetPeriodType.ANNUAL,
            referenceDateEpochDay = testDate,
            monthStartDay = safeStartDay,
            weekStartDayIso = 1
        )

        // For 2025 with startDay = 25, annual range starts Dec 25, 2024 and ends Dec 24, 2025
        val q1Window = BudgetWindowAndRolloverCalculator.resolveQuarterlyWindow(annualWindow.startEpochDay, safeStartDay)
        val q4Window = BudgetWindowAndRolloverCalculator.resolveQuarterlyWindow(annualWindow.endEpochDay, safeStartDay)

        assertEquals("Annual start must match Q1 start", q1Window.startEpochDay, annualWindow.startEpochDay)
        assertEquals("Annual end must match Q4 end", q4Window.endEpochDay, annualWindow.endEpochDay)
    }
}
