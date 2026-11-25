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
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

object BudgetWindowAndRolloverCalculator {

    private val shortDayMonthFormatter = DateTimeFormatter.ofPattern("dd MMM", Locale.US)
    private val shortDayMonthYearFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)

    data class ResolvedBudgetWindow(
        val startEpochDay: Long,
        val endEpochDay: Long,
        val label: String
    )

    data class RolloverComputationResult(
        val rolloverMinor: Long,
        val effectiveLimitMinor: Long,
        val spentMinor: Long,
        val remainingMinor: Long,
        val usagePercentage: Float
    )

    /**
     * Bounded maximum lookback periods by cadence to ensure computation is strictly O(1)
     * and prevents unbounded SQLite range queries for long-lived budgets.
     */
    fun maxLookbackPeriodsFor(periodType: BudgetPeriodType): Int = when (periodType) {
        BudgetPeriodType.WEEKLY -> 26       // ~6 months
        BudgetPeriodType.BI_WEEKLY -> 13    // ~6 months
        BudgetPeriodType.MONTHLY -> 24      // 2 years
        BudgetPeriodType.QUARTERLY -> 8     // 2 years
        BudgetPeriodType.ANNUAL -> 2        // 2 years
        BudgetPeriodType.CUSTOM -> 0        // Custom budgets do not support rollover
    }

    /**
     * Resolves the exact [startEpochDay, endEpochDay] window containing [referenceDateEpochDay]
     * for any [BudgetPeriodType], respecting [monthStartDay] and [weekStartDayIso].
     */
    fun resolveWindow(
        periodType: BudgetPeriodType,
        referenceDateEpochDay: Long,
        monthStartDay: Int,
        weekStartDayIso: Int,
        anchorStartEpochDay: Long? = null,
        customEndEpochDay: Long? = null
    ): ResolvedBudgetWindow {
        return when (periodType) {
            BudgetPeriodType.WEEKLY -> {
                val range = StatsPeriodCalculator.computeWeeklyRange(
                    referenceDateEpochDay = referenceDateEpochDay,
                    weekStartDayIso = weekStartDayIso
                )
                ResolvedBudgetWindow(
                    startEpochDay = range.startEpochDay,
                    endEpochDay = range.endEpochDay,
                    label = range.title
                )
            }

            BudgetPeriodType.BI_WEEKLY -> {
                val anchor = anchorStartEpochDay ?: StatsPeriodCalculator.computeWeeklyRange(
                    referenceDateEpochDay = 0L,
                    weekStartDayIso = weekStartDayIso
                ).startEpochDay
                val cycleIndex = Math.floorDiv(referenceDateEpochDay - anchor, 14L)
                val startDay = anchor + (cycleIndex * 14L)
                val endDay = startDay + 13L
                ResolvedBudgetWindow(
                    startEpochDay = startDay,
                    endEpochDay = endDay,
                    label = formatDateSpan(startDay, endDay)
                )
            }

            BudgetPeriodType.MONTHLY -> {
                val range = StatsPeriodCalculator.computeFinancialMonthRange(
                    referenceDateEpochDay = referenceDateEpochDay,
                    monthStartDay = monthStartDay
                )
                val label = if (monthStartDay.coerceIn(1, 31) == 1) {
                    range.title
                } else {
                    formatDateSpan(range.startEpochDay, range.endEpochDay)
                }
                ResolvedBudgetWindow(
                    startEpochDay = range.startEpochDay,
                    endEpochDay = range.endEpochDay,
                    label = label
                )
            }

            BudgetPeriodType.QUARTERLY -> resolveQuarterlyWindow(
                referenceDateEpochDay = referenceDateEpochDay,
                monthStartDay = monthStartDay
            )

            BudgetPeriodType.ANNUAL -> {
                val range = StatsPeriodCalculator.computeAnnualRange(
                    referenceDateEpochDay = referenceDateEpochDay,
                    monthStartDay = monthStartDay
                )
                val label = if (monthStartDay.coerceIn(1, 31) == 1) {
                    range.anchorYear.toString()
                } else {
                    formatDateSpan(range.startEpochDay, range.endEpochDay)
                }
                ResolvedBudgetWindow(
                    startEpochDay = range.startEpochDay,
                    endEpochDay = range.endEpochDay,
                    label = label
                )
            }

            BudgetPeriodType.CUSTOM -> {
                val rawStart = anchorStartEpochDay ?: referenceDateEpochDay
                val rawEnd = customEndEpochDay ?: rawStart
                val safeStart = minOf(rawStart, rawEnd)
                val safeEnd = maxOf(rawStart, rawEnd)
                ResolvedBudgetWindow(
                    startEpochDay = safeStart,
                    endEpochDay = safeEnd,
                    label = formatDateSpan(safeStart, safeEnd)
                )
            }
        }
    }

    /**
     * Resolves the 3-financial-month quarter containing [referenceDateEpochDay].
     * Aligned with [StatsPeriodCalculator.computeAnnualRange]'s `safeStartDay > 15` convention
     * so Q1.start == Annual.start and Q4.end == Annual.end with zero gaps or overlaps.
     */
    fun resolveQuarterlyWindow(
        referenceDateEpochDay: Long,
        monthStartDay: Int
    ): ResolvedBudgetWindow {
        val safeStartDay = monthStartDay.coerceIn(1, 31)
        val monthRange = StatsPeriodCalculator.computeFinancialMonthRange(
            referenceDateEpochDay = referenceDateEpochDay,
            monthStartDay = safeStartDay
        )
        val anchorDate = if (safeStartDay > 15) {
            LocalDate.ofEpochDay(monthRange.endEpochDay)
        } else {
            LocalDate.ofEpochDay(monthRange.startEpochDay)
        }
        val quarterIndex = (anchorDate.monthValue - 1) / 3 // 0..3 -> Q1..Q4
        val quarterStartMonth = (quarterIndex * 3) + 1 // 1, 4, 7, 10
        val anchorYear = anchorDate.year

        val startYearMonth = if (safeStartDay > 15) {
            YearMonth.of(anchorYear, quarterStartMonth).minusMonths(1)
        } else {
            YearMonth.of(anchorYear, quarterStartMonth)
        }
        val nextQuarterStartYearMonth = startYearMonth.plusMonths(3)

        val startDate = StatsPeriodCalculator.resolveClampedDate(startYearMonth, safeStartDay)
        val endDate = StatsPeriodCalculator.resolveClampedDate(nextQuarterStartYearMonth, safeStartDay).minusDays(1)
        val label = "Q${quarterIndex + 1} $anchorYear"

        return ResolvedBudgetWindow(
            startEpochDay = startDate.toEpochDay(),
            endEpochDay = endDate.toEpochDay(),
            label = label
        )
    }

    /**
     * Resolves the immediately preceding recurring window before [currentWindow].
     * Guaranteed contiguous (`prev.endEpochDay == currentWindow.startEpochDay - 1L`).
     */
    fun resolvePreviousWindow(
        periodType: BudgetPeriodType,
        currentWindow: ResolvedBudgetWindow,
        monthStartDay: Int,
        weekStartDayIso: Int,
        anchorStartEpochDay: Long? = null
    ): ResolvedBudgetWindow {
        require(periodType != BudgetPeriodType.CUSTOM) { "CUSTOM budgets do not have recurring previous windows." }
        return resolveWindow(
            periodType = periodType,
            referenceDateEpochDay = currentWindow.startEpochDay - 1L,
            monthStartDay = monthStartDay,
            weekStartDayIso = weekStartDayIso,
            anchorStartEpochDay = anchorStartEpochDay
        )
    }

    /**
     * Builds the ordered chronological list of COMPLETED historical windows strictly before [activeWindow]
     * that contribute to rollover, starting from the window containing [rolloverStartEpochDay]
     * and capped at [maxLookbackPeriodsFor] to keep computation strictly bounded.
     */
    fun buildRolloverHistoryWindows(
        periodType: BudgetPeriodType,
        activeWindow: ResolvedBudgetWindow,
        rolloverEnabled: Boolean,
        rolloverStartEpochDay: Long?,
        monthStartDay: Int,
        weekStartDayIso: Int,
        anchorStartEpochDay: Long? = null,
        maxPeriods: Int = maxLookbackPeriodsFor(periodType)
    ): List<ResolvedBudgetWindow> {
        if (!rolloverEnabled || !periodType.supportsRollover || rolloverStartEpochDay == null) {
            return emptyList()
        }
        val firstWindow = resolveWindow(
            periodType = periodType,
            referenceDateEpochDay = rolloverStartEpochDay,
            monthStartDay = monthStartDay,
            weekStartDayIso = weekStartDayIso,
            anchorStartEpochDay = anchorStartEpochDay
        )
        // If rollover starts in or after the active window, no prior completed windows exist.
        if (firstWindow.startEpochDay >= activeWindow.startEpochDay) {
            return emptyList()
        }

        // Walk backward from activeWindow so cap keeps the most recent `maxPeriods` completed windows.
        val reversed = mutableListOf<ResolvedBudgetWindow>()
        var cursor = activeWindow
        val safeCap = maxPeriods.coerceAtLeast(0)
        while (reversed.size < safeCap) {
            val prev = resolvePreviousWindow(
                periodType = periodType,
                currentWindow = cursor,
                monthStartDay = monthStartDay,
                weekStartDayIso = weekStartDayIso,
                anchorStartEpochDay = anchorStartEpochDay
            )
            if (prev.endEpochDay < firstWindow.startEpochDay) break
            reversed.add(prev)
            if (prev.startEpochDay <= firstWindow.startEpochDay) break
            cursor = prev
        }
        return reversed.asReversed()
    }

    /**
     * Pure deterministic rollover calculation from immutable spend buckets (100% Long minor units).
     * Never stores mutable virtual balances — zero drift when past transactions are edited or backdated.
     */
    fun computeRolloverAndProgress(
        baseLimitMinor: Long,
        rolloverEnabled: Boolean,
        rolloverIncludeDeficit: Boolean,
        historyWindows: List<ResolvedBudgetWindow>,
        activeWindow: ResolvedBudgetWindow,
        spendInWindowProvider: (startEpochDay: Long, endEpochDay: Long) -> Long
    ): RolloverComputationResult {
        var carryMinor = 0L
        if (rolloverEnabled && historyWindows.isNotEmpty()) {
            for (window in historyWindows) {
                val periodSpentMinor = spendInWindowProvider(window.startEpochDay, window.endEpochDay)
                val periodEffectiveLimit = MoneyFormatterAndCalculator.safeAdd(baseLimitMinor, carryMinor)
                val periodUnusedMinor = MoneyFormatterAndCalculator.safeSubtract(periodEffectiveLimit, periodSpentMinor)
                carryMinor = if (rolloverIncludeDeficit) {
                    periodUnusedMinor
                } else {
                    periodUnusedMinor.coerceAtLeast(0L)
                }
            }
        }

        val activeSpentMinor = spendInWindowProvider(activeWindow.startEpochDay, activeWindow.endEpochDay)
        val effectiveLimitMinor = MoneyFormatterAndCalculator.safeAdd(baseLimitMinor, carryMinor)
        val remainingMinor = MoneyFormatterAndCalculator.safeSubtract(effectiveLimitMinor, activeSpentMinor)

        val usagePercentage = when {
            effectiveLimitMinor <= 0L -> if (
                activeSpentMinor > 0L ||
                effectiveLimitMinor < 0L ||
                (effectiveLimitMinor == 0L && carryMinor < 0L)
            ) 100f else 0f
            else -> MoneyFormatterAndCalculator.calculatePercentage(activeSpentMinor, effectiveLimitMinor)
                .coerceAtLeast(0f)
        }

        return RolloverComputationResult(
            rolloverMinor = carryMinor,
            effectiveLimitMinor = effectiveLimitMinor,
            spentMinor = activeSpentMinor,
            remainingMinor = remainingMinor,
            usagePercentage = usagePercentage
        )
    }

    fun resolveWindowStatus(
        window: ResolvedBudgetWindow,
        todayEpochDay: Long
    ): BudgetWindowStatus = when {
        todayEpochDay < window.startEpochDay -> BudgetWindowStatus.UPCOMING
        todayEpochDay > window.endEpochDay -> BudgetWindowStatus.COMPLETED
        else -> BudgetWindowStatus.ACTIVE
    }

    fun formatDateSpan(startEpochDay: Long, endEpochDay: Long): String {
        val start = LocalDate.ofEpochDay(startEpochDay)
        val end = LocalDate.ofEpochDay(endEpochDay)
        return when {
            startEpochDay == endEpochDay -> start.format(shortDayMonthYearFormatter)
            start.year == end.year -> "${start.format(shortDayMonthFormatter)} – ${end.format(shortDayMonthFormatter)}"
            else -> "${start.format(shortDayMonthYearFormatter)} – ${end.format(shortDayMonthYearFormatter)}"
        }
    }
}
