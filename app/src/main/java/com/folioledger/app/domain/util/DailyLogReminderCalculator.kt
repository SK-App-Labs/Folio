/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/domain/util/DailyLogReminderCalculator.kt
package com.folioledger.app.domain.util

import java.time.DayOfWeek
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Pure Kotlin scheduling and formatting utilities for the customizable Daily Log Reminder.
 * All date/time calculations use `java.time` zone-aware types so daylight-saving transitions
 * preserve wall-clock trigger times without 24-hour drift and remain 100% JVM unit-testable.
 */
object DailyLogReminderCalculator {

    const val ALL_DAYS_MASK: Int = 0b1111111
    const val WEEKDAYS_MASK: Int = 0b0011111
    const val WEEKENDS_MASK: Int = 0b1100000

    const val DEFAULT_HOUR: Int = 20
    const val DEFAULT_MINUTE: Int = 30
    const val DEFAULT_SNOOZE_MINUTES: Int = 60
    const val MAX_SNOOZES_PER_DAY: Int = 3
    const val MAX_CUSTOM_MESSAGE_LENGTH: Int = 80

    val ALLOWED_SNOOZE_MINUTES: Set<Int> = setOf(15, 30, 60, 120)

    /**
     * Masks [mask] to the 7 valid weekday bits (Mon = bit 0 .. Sun = bit 6) and falls back to
     * [ALL_DAYS_MASK] if empty so an unfireable schedule can never be persisted.
     */
    fun normalizeDaysMask(mask: Int): Int {
        val masked = mask and ALL_DAYS_MASK
        return if (masked == 0) ALL_DAYS_MASK else masked
    }

    /**
     * Ensures [minutes] matches one of [ALLOWED_SNOOZE_MINUTES], falling back to [DEFAULT_SNOOZE_MINUTES].
     */
    fun normalizeSnoozeMinutes(minutes: Int): Int = if (minutes in ALLOWED_SNOOZE_MINUTES) minutes else DEFAULT_SNOOZE_MINUTES

    /**
     * Returns true when [dayOfWeek] (ISO 1=Monday .. 7=Sunday) is enabled in [daysMask].
     */
    fun isDaySelected(daysMask: Int, dayOfWeek: DayOfWeek): Boolean {
        val bit = 1 shl (dayOfWeek.value - 1)
        return (daysMask and bit) != 0
    }

    /**
     * Returns a copy of [daysMask] with [dayOfWeek]'s bit set according to [selected].
     */
    fun withDaySelected(daysMask: Int, dayOfWeek: DayOfWeek, selected: Boolean): Int {
        val bit = 1 shl (dayOfWeek.value - 1)
        return if (selected) {
            (daysMask and ALL_DAYS_MASK) or bit
        } else {
            (daysMask and ALL_DAYS_MASK) and bit.inv()
        }
    }

    /**
     * Computes the next [ZonedDateTime] strictly after [now] matching [hour]:[minute] on a day
     * enabled by [daysMask]. Preserves wall-clock time across DST transitions via [now].zone.
     */
    fun computeNextTriggerDateTime(
        now: ZonedDateTime,
        hour: Int,
        minute: Int,
        daysMask: Int,
    ): ZonedDateTime {
        val safeHour = hour.coerceIn(0, 23)
        val safeMinute = minute.coerceIn(0, 59)
        val effectiveMask = normalizeDaysMask(daysMask)
        val today = now.toLocalDate()

        if (isDaySelected(effectiveMask, today.dayOfWeek)) {
            val todayCandidate = today.atTime(safeHour, safeMinute).atZone(now.zone)
            if (todayCandidate.isAfter(now)) {
                return todayCandidate
            }
        }

        for (offset in 1L..7L) {
            val candidateDate = today.plusDays(offset)
            if (isDaySelected(effectiveMask, candidateDate.dayOfWeek)) {
                return candidateDate.atTime(safeHour, safeMinute).atZone(now.zone)
            }
        }

        // Unreachable when effectiveMask != 0, kept for total fallback safety
        return today.plusDays(1L).atTime(safeHour, safeMinute).atZone(now.zone)
    }

    /**
     * Computes WorkManager initial delay in milliseconds, floored at 1,000 ms.
     */
    fun computeInitialDelayMillis(now: ZonedDateTime, target: ZonedDateTime): Long = Duration.between(now, target).toMillis().coerceAtLeast(1_000L)

    /**
     * Returns true when `0 <= gap <= minGapMinutes` between [snoozeFireTime] and [nextRegularTime],
     * suppressing duplicate back-to-back daily notifications right before a scheduled regular run.
     */
    fun shouldSkipSnoozeNearNextRegularRun(
        snoozeFireTime: ZonedDateTime,
        nextRegularTime: ZonedDateTime,
        minGapMinutes: Long = 60L,
    ): Boolean {
        val thresholdMillis = minGapMinutes.coerceAtLeast(0L) * 60_000L
        val gapMillis = Duration.between(snoozeFireTime, nextRegularTime).toMillis()
        return gapMillis in 0L..thresholdMillis
    }

    /**
     * Formats [daysMask] into a concise localized summary: "Every day", "Weekdays", "Weekends",
     * or comma-separated short day names ordered Mon..Sun.
     */
    fun formatDaysSummary(daysMask: Int): String {
        val normalized = normalizeDaysMask(daysMask)
        return when (normalized) {
            ALL_DAYS_MASK -> "Every day"

            WEEKDAYS_MASK -> "Weekdays"

            WEEKENDS_MASK -> "Weekends"

            else ->
                DayOfWeek.entries
                    .filter { isDaySelected(normalized, it) }
                    .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
        }
    }

    /**
     * Formats a snooze duration preset for UI chips/summaries or compact notification actions.
     */
    fun formatSnoozeLabel(minutes: Int, compact: Boolean = false): String {
        val normalized = normalizeSnoozeMinutes(minutes)
        return when (normalized) {
            15 -> if (compact) "15m" else "15 min"
            30 -> if (compact) "30m" else "30 min"
            60 -> "1 hr"
            120 -> "2 hr"
            else -> if (compact) "${normalized}m" else "$normalized min"
        }
    }
}
