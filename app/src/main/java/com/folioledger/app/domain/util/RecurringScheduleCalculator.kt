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

import com.folioledger.app.domain.model.RepeatFrequency
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object RecurringScheduleCalculator {

    private val previewDateFormatter = DateTimeFormatter.ofPattern("dd MMM", Locale.US)
    private val annualDateFormatter = DateTimeFormatter.ofPattern("dd MMM", Locale.US)

    private fun plusMonthsAnchored(currentDate: LocalDate, months: Long, anchorDay: Int?): LocalDate {
        val targetYearMonth = java.time.YearMonth.from(currentDate).plusMonths(months)
        val day = anchorDay ?: currentDate.dayOfMonth
        val clampedDay = day.coerceIn(1, targetYearMonth.lengthOfMonth())
        return targetYearMonth.atDay(clampedDay)
    }

    private fun plusYearsAnchored(currentDate: LocalDate, years: Long, anchorDay: Int?): LocalDate {
        val targetYear = currentDate.year + years.toInt()
        val targetYearMonth = java.time.YearMonth.of(targetYear, currentDate.month)
        val day = anchorDay ?: currentDate.dayOfMonth
        val clampedDay = day.coerceIn(1, targetYearMonth.lengthOfMonth())
        return targetYearMonth.atDay(clampedDay)
    }

    /**
     * Calculates the next calendar occurrence from [currentDate] given the [frequency].
     * An optional [anchorDayOfMonth] preserves the original calendar anchor day (e.g. 31st or 29th)
     * across months of varying lengths without compounding drift.
     */
    fun computeNextOccurrence(
        currentDate: LocalDate,
        frequency: RepeatFrequency,
        anchorDayOfMonth: Int? = null
    ): LocalDate {
        return when (frequency) {
            RepeatFrequency.EVERY_DAY -> currentDate.plusDays(1)

            RepeatFrequency.WEEKDAYS -> {
                var next = currentDate.plusDays(1)
                while (next.dayOfWeek == DayOfWeek.SATURDAY || next.dayOfWeek == DayOfWeek.SUNDAY) {
                    next = next.plusDays(1)
                }
                next
            }

            RepeatFrequency.WEEKENDS -> {
                var next = currentDate.plusDays(1)
                while (next.dayOfWeek != DayOfWeek.SATURDAY && next.dayOfWeek != DayOfWeek.SUNDAY) {
                    next = next.plusDays(1)
                }
                next
            }

            RepeatFrequency.EVERY_WEEK -> currentDate.plusWeeks(1)

            RepeatFrequency.EVERY_2_WEEKS -> currentDate.plusWeeks(2)

            RepeatFrequency.EVERY_4_WEEKS -> currentDate.plusDays(28)

            RepeatFrequency.EVERY_MONTH -> plusMonthsAnchored(currentDate, 1, anchorDayOfMonth)

            RepeatFrequency.END_OF_MONTH -> currentDate.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth())

            RepeatFrequency.EVERY_2_MONTHS -> plusMonthsAnchored(currentDate, 2, anchorDayOfMonth)

            RepeatFrequency.EVERY_3_MONTHS -> plusMonthsAnchored(currentDate, 3, anchorDayOfMonth)

            RepeatFrequency.EVERY_4_MONTHS -> plusMonthsAnchored(currentDate, 4, anchorDayOfMonth)

            RepeatFrequency.EVERY_6_MONTHS -> plusMonthsAnchored(currentDate, 6, anchorDayOfMonth)

            RepeatFrequency.ANNUALLY -> plusYearsAnchored(currentDate, 1, anchorDayOfMonth)
        }
    }

    private fun advanceCursorToOnOrAfter(
        startDay: Long,
        frequency: RepeatFrequency,
        targetEpochDay: Long,
        anchorDayOfMonth: Int? = null
    ): LocalDate? {
        val startDate = LocalDate.ofEpochDay(startDay)
        val anchorDay = anchorDayOfMonth ?: startDate.dayOfMonth
        var cursor = startDate

        // Fast-forward cursor mathematically if far behind targetEpochDay
        val targetDate = LocalDate.ofEpochDay(targetEpochDay)
        if (cursor.isBefore(targetDate)) {
            cursor = when (frequency) {
                RepeatFrequency.EVERY_DAY -> targetDate
                RepeatFrequency.WEEKDAYS, RepeatFrequency.WEEKENDS -> targetDate.minusDays(1)
                RepeatFrequency.EVERY_WEEK -> {
                    val weeks = java.time.temporal.ChronoUnit.WEEKS.between(cursor, targetDate)
                    if (weeks > 1L) cursor.plusWeeks(weeks - 1L) else cursor
                }
                RepeatFrequency.EVERY_2_WEEKS -> {
                    val fortnights = java.time.temporal.ChronoUnit.WEEKS.between(cursor, targetDate) / 2L
                    if (fortnights > 1L) cursor.plusWeeks((fortnights - 1L) * 2L) else cursor
                }
                RepeatFrequency.EVERY_4_WEEKS -> {
                    val periods = java.time.temporal.ChronoUnit.WEEKS.between(cursor, targetDate) / 4L
                    if (periods > 1L) cursor.plusWeeks((periods - 1L) * 4L) else cursor
                }
                RepeatFrequency.EVERY_MONTH -> {
                    val months = java.time.temporal.ChronoUnit.MONTHS.between(cursor, targetDate)
                    if (months > 1L) plusMonthsAnchored(cursor, months - 1L, anchorDay) else cursor
                }
                RepeatFrequency.END_OF_MONTH -> {
                    val months = java.time.temporal.ChronoUnit.MONTHS.between(cursor, targetDate)
                    if (months > 1L) cursor.plusMonths(months - 1L).with(TemporalAdjusters.lastDayOfMonth()) else cursor
                }
                RepeatFrequency.EVERY_2_MONTHS -> {
                    val bimonths = java.time.temporal.ChronoUnit.MONTHS.between(cursor, targetDate) / 2L
                    if (bimonths > 1L) plusMonthsAnchored(cursor, (bimonths - 1L) * 2L, anchorDay) else cursor
                }
                RepeatFrequency.EVERY_3_MONTHS -> {
                    val quarters = java.time.temporal.ChronoUnit.MONTHS.between(cursor, targetDate) / 3L
                    if (quarters > 1L) plusMonthsAnchored(cursor, (quarters - 1L) * 3L, anchorDay) else cursor
                }
                RepeatFrequency.EVERY_4_MONTHS -> {
                    val periods = java.time.temporal.ChronoUnit.MONTHS.between(cursor, targetDate) / 4L
                    if (periods > 1L) plusMonthsAnchored(cursor, (periods - 1L) * 4L, anchorDay) else cursor
                }
                RepeatFrequency.EVERY_6_MONTHS -> {
                    val halfYears = java.time.temporal.ChronoUnit.MONTHS.between(cursor, targetDate) / 6L
                    if (halfYears > 1L) plusMonthsAnchored(cursor, (halfYears - 1L) * 6L, anchorDay) else cursor
                }
                RepeatFrequency.ANNUALLY -> {
                    val years = java.time.temporal.ChronoUnit.YEARS.between(cursor, targetDate)
                    if (years > 1L) plusYearsAnchored(cursor, years - 1L, anchorDay) else cursor
                }
            }
        }

        var catchupGuard = 0
        // Advance cursor until it reaches or exceeds targetEpochDay
        while (cursor.toEpochDay() < targetEpochDay && catchupGuard++ < 1_000) {
            cursor = computeNextOccurrence(cursor, frequency, anchorDay)
        }
        if (cursor.toEpochDay() < targetEpochDay) return null

        // Align weekday/weekend schedules if startDay >= targetEpochDay fell on a non-matching day
        val isUnalignedWeekday = frequency == RepeatFrequency.WEEKDAYS &&
            (cursor.dayOfWeek == DayOfWeek.SATURDAY || cursor.dayOfWeek == DayOfWeek.SUNDAY)
        val isUnalignedWeekend = frequency == RepeatFrequency.WEEKENDS &&
            (cursor.dayOfWeek != DayOfWeek.SATURDAY && cursor.dayOfWeek != DayOfWeek.SUNDAY)
        if (isUnalignedWeekday || isUnalignedWeekend) {
            cursor = computeNextOccurrence(cursor.minusDays(1), frequency, anchorDay)
        }
        return cursor
    }

    /**
     * Returns the first occurrence epoch day >= [fromEpochDay] along the schedule anchored at
     * [startDay], or `null` if it falls past [endDateEpochDay].
     */
    fun computeFirstOccurrenceOnOrAfter(
        startDay: Long,
        frequency: RepeatFrequency,
        fromEpochDay: Long,
        anchorDayOfMonth: Int? = null,
        endDateEpochDay: Long? = null
    ): Long? {
        if (endDateEpochDay != null && fromEpochDay > endDateEpochDay) return null
        val cursor = advanceCursorToOnOrAfter(
            startDay = startDay,
            frequency = frequency,
            targetEpochDay = fromEpochDay,
            anchorDayOfMonth = anchorDayOfMonth
        ) ?: return null
        val firstDay = cursor.toEpochDay()
        return if (endDateEpochDay != null && firstDay > endDateEpochDay) null else firstDay
    }

    /**
     * Projects all recurring dates falling between [todayEpochDay] and [horizonEpochDay] inclusive,
     * optionally bounded by [endDateEpochDay].
     */
    fun projectOccurrencesInHorizon(
        startDay: Long,
        frequency: RepeatFrequency,
        todayEpochDay: Long,
        horizonEpochDay: Long,
        anchorDayOfMonth: Int? = null,
        endDateEpochDay: Long? = null
    ): List<Long> {
        val effectiveHorizon = endDateEpochDay?.let { minOf(horizonEpochDay, it) } ?: horizonEpochDay
        if (effectiveHorizon < todayEpochDay) return emptyList()

        val anchorDay = anchorDayOfMonth ?: LocalDate.ofEpochDay(startDay).dayOfMonth
        var cursor = advanceCursorToOnOrAfter(
            startDay = startDay,
            frequency = frequency,
            targetEpochDay = todayEpochDay,
            anchorDayOfMonth = anchorDay
        ) ?: return emptyList()

        val occurrences = mutableListOf<Long>()
        var horizonGuard = 0
        while (cursor.toEpochDay() <= effectiveHorizon && horizonGuard++ < 1_000) {
            occurrences.add(cursor.toEpochDay())
            cursor = computeNextOccurrence(cursor, frequency, anchorDay)
        }

        return occurrences
    }

    /**
     * Formats a friendly human-readable sentence describing the recurrence rule.
     */
    fun formatHumanReadableSummary(frequency: RepeatFrequency, baseDate: LocalDate): String {
        return when (frequency) {
            RepeatFrequency.EVERY_DAY -> "Repeats every day"
            RepeatFrequency.WEEKDAYS -> "Repeats every weekday (Mon–Fri)"
            RepeatFrequency.WEEKENDS -> "Repeats every weekend (Sat & Sun)"
            RepeatFrequency.EVERY_WEEK -> {
                val dayName = baseDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                "Repeats every week on $dayName"
            }
            RepeatFrequency.EVERY_2_WEEKS -> {
                val dayName = baseDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                "Repeats every 2 weeks on $dayName"
            }
            RepeatFrequency.EVERY_4_WEEKS -> "Repeats every 4 weeks (28 days)"
            RepeatFrequency.EVERY_MONTH -> "Repeats every month on the ${formatOrdinal(baseDate.dayOfMonth)}"
            RepeatFrequency.END_OF_MONTH -> "Repeats on the last day of each month"
            RepeatFrequency.EVERY_2_MONTHS -> "Repeats every 2 months on the ${formatOrdinal(baseDate.dayOfMonth)}"
            RepeatFrequency.EVERY_3_MONTHS -> "Repeats every 3 months (Quarterly)"
            RepeatFrequency.EVERY_4_MONTHS -> "Repeats every 4 months"
            RepeatFrequency.EVERY_6_MONTHS -> "Repeats every 6 months (Half-yearly)"
            RepeatFrequency.ANNUALLY -> "Repeats annually on ${baseDate.format(annualDateFormatter)}"
        }
    }

    /**
     * Generates a preview string of upcoming dates: e.g. "Next: 30 Nov, 31 Dec, 31 Jan".
     */
    fun formatNextOccurrencesPreview(frequency: RepeatFrequency, baseDate: LocalDate, count: Int = 3): String {
        val dates = mutableListOf<String>()
        val anchorDay = baseDate.dayOfMonth
        var cursor = baseDate
        repeat(count) {
            cursor = computeNextOccurrence(cursor, frequency, anchorDay)
            dates.add(cursor.format(previewDateFormatter))
        }
        return "Next: " + dates.joinToString(", ")
    }

    private fun formatOrdinal(day: Int): String {
        return when {
            day in 11..13 -> "${day}th"
            day % 10 == 1 -> "${day}st"
            day % 10 == 2 -> "${day}nd"
            day % 10 == 3 -> "${day}rd"
            else -> "${day}th"
        }
    }
}
