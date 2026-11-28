/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/domain/util/StatsPeriodCalculator.kt
package com.folioledger.app.domain.util

import com.folioledger.app.domain.model.StatsPeriodGranularity
import com.folioledger.app.domain.model.StatsPeriodRange
import com.folioledger.app.domain.usecase.AccountingRulesEngine
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object StatsPeriodCalculator {

    private val shortMonthYearFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)
    private val shortDayMonthFormatter = DateTimeFormatter.ofPattern("dd MMM", Locale.US)
    private val fullDayMonthYearFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)

    fun resolveClampedDate(yearMonth: YearMonth, desiredDay: Int): LocalDate {
        val safeDay = desiredDay.coerceIn(1, 31)
        val clampedDay = minOf(safeDay, yearMonth.lengthOfMonth())
        return yearMonth.atDay(clampedDay)
    }

    fun computePeriodRange(
        granularity: StatsPeriodGranularity,
        referenceDateEpochDay: Long,
        monthStartDay: Int,
        weekStartDayIso: Int,
        customStartEpochDay: Long,
        customEndEpochDay: Long,
    ): StatsPeriodRange = when (granularity) {
        StatsPeriodGranularity.WEEKLY -> computeWeeklyRange(
            referenceDateEpochDay = referenceDateEpochDay,
            weekStartDayIso = weekStartDayIso,
        )

        StatsPeriodGranularity.MONTHLY -> computeFinancialMonthRange(
            referenceDateEpochDay = referenceDateEpochDay,
            monthStartDay = monthStartDay,
        )

        StatsPeriodGranularity.ANNUALLY -> computeAnnualRange(
            referenceDateEpochDay = referenceDateEpochDay,
            monthStartDay = monthStartDay,
        )

        StatsPeriodGranularity.CUSTOM -> computeCustomRange(
            customStartEpochDay = customStartEpochDay,
            customEndEpochDay = customEndEpochDay,
        )
    }

    fun computeWeeklyRange(
        referenceDateEpochDay: Long,
        weekStartDayIso: Int,
    ): StatsPeriodRange {
        val refDate = LocalDate.ofEpochDay(referenceDateEpochDay)
        val startDow = DayOfWeek.of(weekStartDayIso.coerceIn(1, 7))
        val startDate = refDate.with(TemporalAdjusters.previousOrSame(startDow))
        val endDate = startDate.plusDays(6)

        val title = "${startDate.format(shortDayMonthFormatter)} – ${endDate.format(shortDayMonthFormatter)}"
        val subtitle = "Weekly View • ${endDate.year}"

        return StatsPeriodRange(
            startEpochDay = startDate.toEpochDay(),
            endEpochDay = endDate.toEpochDay(),
            title = title,
            subtitle = subtitle,
            anchorYear = endDate.year,
        )
    }

    fun computeFinancialMonthRange(
        referenceDateEpochDay: Long,
        monthStartDay: Int,
    ): StatsPeriodRange {
        val safeStartDay = monthStartDay.coerceIn(1, 31)
        val (startEpoch, endEpoch) = AccountingRulesEngine.computeFinancialMonthRange(
            referenceDateEpochDay = referenceDateEpochDay,
            monthStartDay = safeStartDay,
        )
        val startDate = LocalDate.ofEpochDay(startEpoch)
        val endDate = LocalDate.ofEpochDay(endEpoch)

        val anchorDate = if (safeStartDay > 15) endDate else startDate
        val title = anchorDate.format(shortMonthYearFormatter)
        val subtitle = "${startDate.format(shortDayMonthFormatter)} – ${endDate.format(fullDayMonthYearFormatter)}"

        return StatsPeriodRange(
            startEpochDay = startEpoch,
            endEpochDay = endEpoch,
            title = title,
            subtitle = subtitle,
            anchorYear = anchorDate.year,
        )
    }

    fun computeAnnualRange(
        referenceDateEpochDay: Long,
        monthStartDay: Int,
    ): StatsPeriodRange {
        val currentMonthRange = computeFinancialMonthRange(referenceDateEpochDay, monthStartDay)
        val anchorYear = currentMonthRange.anchorYear
        val safeStartDay = monthStartDay.coerceIn(1, 31)

        val startDate = if (safeStartDay > 15) {
            resolveClampedDate(YearMonth.of(anchorYear - 1, 12), safeStartDay)
        } else {
            resolveClampedDate(YearMonth.of(anchorYear, 1), safeStartDay)
        }
        val nextYearStart = if (safeStartDay > 15) {
            resolveClampedDate(YearMonth.of(anchorYear, 12), safeStartDay)
        } else {
            resolveClampedDate(YearMonth.of(anchorYear + 1, 1), safeStartDay)
        }
        val endDate = nextYearStart.minusDays(1)

        val title = "Year $anchorYear"
        val subtitle = "${startDate.format(fullDayMonthYearFormatter)} – ${endDate.format(fullDayMonthYearFormatter)}"

        return StatsPeriodRange(
            startEpochDay = startDate.toEpochDay(),
            endEpochDay = endDate.toEpochDay(),
            title = title,
            subtitle = subtitle,
            anchorYear = anchorYear,
        )
    }

    fun computeCustomRange(
        customStartEpochDay: Long,
        customEndEpochDay: Long,
    ): StatsPeriodRange {
        val safeStart = minOf(customStartEpochDay, customEndEpochDay)
        val safeEnd = maxOf(customStartEpochDay, customEndEpochDay)
        val startDate = LocalDate.ofEpochDay(safeStart)
        val endDate = LocalDate.ofEpochDay(safeEnd)
        val daysCount = (safeEnd - safeStart) + 1L

        val title = if (startDate.year == endDate.year) {
            "${startDate.format(shortDayMonthFormatter)} – ${endDate.format(fullDayMonthYearFormatter)}"
        } else {
            "${startDate.format(fullDayMonthYearFormatter)} – ${endDate.format(fullDayMonthYearFormatter)}"
        }
        val subtitle = "Custom Period ($daysCount ${if (daysCount == 1L) "day" else "days"})"

        return StatsPeriodRange(
            startEpochDay = safeStart,
            endEpochDay = safeEnd,
            title = title,
            subtitle = subtitle,
            anchorYear = endDate.year,
        )
    }

    fun stepFinancialMonth(
        currentReferenceEpochDay: Long,
        monthStartDay: Int,
        deltaMonths: Long,
    ): Long {
        val currentRange = computeFinancialMonthRange(currentReferenceEpochDay, monthStartDay)
        val startLocalDate = LocalDate.ofEpochDay(currentRange.startEpochDay)
        val targetYearMonth = YearMonth.from(startLocalDate).plusMonths(deltaMonths)
        return resolveClampedDate(targetYearMonth, monthStartDay).toEpochDay()
    }

    fun stepFinancialYear(
        currentReferenceEpochDay: Long,
        monthStartDay: Int,
        deltaYears: Long,
    ): Long {
        val currentRange = computeFinancialMonthRange(currentReferenceEpochDay, monthStartDay)
        val startLocalDate = LocalDate.ofEpochDay(currentRange.startEpochDay)
        val targetYearMonth = YearMonth.from(startLocalDate).plusYears(deltaYears)
        return resolveClampedDate(targetYearMonth, monthStartDay).toEpochDay()
    }

    data class SteppedPeriod(
        val referenceDateEpochDay: Long,
        val customStartEpochDay: Long,
        val customEndEpochDay: Long,
    )

    fun stepPeriod(
        granularity: StatsPeriodGranularity,
        referenceDateEpochDay: Long,
        monthStartDay: Int,
        customStartEpochDay: Long,
        customEndEpochDay: Long,
        delta: Long,
    ): SteppedPeriod = when (granularity) {
        StatsPeriodGranularity.WEEKLY -> {
            val newRef = referenceDateEpochDay + (delta * 7L)
            SteppedPeriod(newRef, customStartEpochDay, customEndEpochDay)
        }

        StatsPeriodGranularity.MONTHLY -> {
            val newRef = stepFinancialMonth(referenceDateEpochDay, monthStartDay, delta)
            SteppedPeriod(newRef, customStartEpochDay, customEndEpochDay)
        }

        StatsPeriodGranularity.ANNUALLY -> {
            val newRef = stepFinancialYear(referenceDateEpochDay, monthStartDay, delta)
            SteppedPeriod(newRef, customStartEpochDay, customEndEpochDay)
        }

        StatsPeriodGranularity.CUSTOM -> {
            val safeStart = minOf(customStartEpochDay, customEndEpochDay)
            val safeEnd = maxOf(customStartEpochDay, customEndEpochDay)
            val spanDays = (safeEnd - safeStart + 1L).coerceAtLeast(1L)
            val newStart = safeStart + (delta * spanDays)
            val newEnd = safeEnd + (delta * spanDays)
            SteppedPeriod(newEnd, newStart, newEnd)
        }
    }
}
