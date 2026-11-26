/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/test/java/com/folioledger/app/domain/util/DailyLogReminderCalculatorTest.kt
package com.folioledger.app.domain.util

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyLogReminderCalculatorTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun `same-day future time triggers today whereas past time rolls to next selected day`() {
        // Wednesday 2026-11-25 at 19:00 UTC -> target 20:30 UTC is still ahead today
        val beforeCutoff = ZonedDateTime.of(2026, 11, 25, 19, 0, 0, 0, utc)
        val todayTarget = DailyLogReminderCalculator.computeNextTriggerDateTime(
            now = beforeCutoff,
            hour = 20,
            minute = 30,
            daysMask = DailyLogReminderCalculator.ALL_DAYS_MASK
        )
        assertEquals(ZonedDateTime.of(2026, 11, 25, 20, 30, 0, 0, utc), todayTarget)

        // Exact equal instant is NOT strictly after now -> rolls to Thursday 2026-11-26
        val exactCutoff = ZonedDateTime.of(2026, 11, 25, 20, 30, 0, 0, utc)
        val rolledTarget = DailyLogReminderCalculator.computeNextTriggerDateTime(
            now = exactCutoff,
            hour = 20,
            minute = 30,
            daysMask = DailyLogReminderCalculator.ALL_DAYS_MASK
        )
        assertEquals(ZonedDateTime.of(2026, 11, 26, 20, 30, 0, 0, utc), rolledTarget)
    }

    @Test
    fun `weekday mask on Friday night skips weekend and lands on Monday`() {
        // Friday 2026-11-27 at 21:00 UTC -> skips Sat 28 & Sun 29, lands on Mon 2026-11-30 20:30
        val fridayNight = ZonedDateTime.of(2026, 11, 27, 21, 0, 0, 0, utc)
        val next = DailyLogReminderCalculator.computeNextTriggerDateTime(
            now = fridayNight,
            hour = 20,
            minute = 30,
            daysMask = DailyLogReminderCalculator.WEEKDAYS_MASK
        )
        assertEquals(DayOfWeek.MONDAY, next.dayOfWeek)
        assertEquals(ZonedDateTime.of(2026, 11, 30, 20, 30, 0, 0, utc), next)
    }

    @Test
    fun `daylight saving spring forward and fall back preserve wall clock hour and minute`() {
        val nyZone = ZoneId.of("America/New_York")

        // US Spring Forward 2026-03-08 (23-hour day): reminder from Sat night stays at 20:30 EDT
        val preSpringNight = ZonedDateTime.of(2026, 3, 7, 21, 0, 0, 0, nyZone)
        val springTarget = DailyLogReminderCalculator.computeNextTriggerDateTime(
            now = preSpringNight,
            hour = 20,
            minute = 30,
            daysMask = DailyLogReminderCalculator.ALL_DAYS_MASK
        )
        assertEquals(2026, springTarget.year)
        assertEquals(3, springTarget.monthValue)
        assertEquals(8, springTarget.dayOfMonth)
        assertEquals(20, springTarget.hour)
        assertEquals(30, springTarget.minute)

        // US Fall Back 2026-11-01 (25-hour day): reminder from Sun morning stays at 20:30 EST
        val fallBackMorning = ZonedDateTime.of(2026, 11, 1, 0, 30, 0, 0, nyZone)
        val fallTarget = DailyLogReminderCalculator.computeNextTriggerDateTime(
            now = fallBackMorning,
            hour = 20,
            minute = 30,
            daysMask = DailyLogReminderCalculator.ALL_DAYS_MASK
        )
        assertEquals(2026, fallTarget.year)
        assertEquals(11, fallTarget.monthValue)
        assertEquals(1, fallTarget.dayOfMonth)
        assertEquals(20, fallTarget.hour)
        assertEquals(30, fallTarget.minute)
    }

    @Test
    fun `empty daysMask normalizes safely to ALL_DAYS_MASK and computes delay at least 1s`() {
        assertEquals(
            DailyLogReminderCalculator.ALL_DAYS_MASK,
            DailyLogReminderCalculator.normalizeDaysMask(0)
        )
        assertEquals(
            DailyLogReminderCalculator.ALL_DAYS_MASK,
            DailyLogReminderCalculator.normalizeDaysMask(0b1111111111)
        )

        val now = ZonedDateTime.of(2026, 11, 25, 20, 0, 0, 0, utc)
        val target = DailyLogReminderCalculator.computeNextTriggerDateTime(
            now = now,
            hour = 20,
            minute = 30,
            daysMask = 0
        )
        assertEquals(ZonedDateTime.of(2026, 11, 25, 20, 30, 0, 0, utc), target)
        assertEquals(1_800_000L, DailyLogReminderCalculator.computeInitialDelayMillis(now, target))
        assertEquals(1_000L, DailyLogReminderCalculator.computeInitialDelayMillis(now, now))
    }

    @Test
    fun `snooze near next regular run is skipped within 60 minutes and allowed above 60 minutes`() {
        val nextRegular = ZonedDateTime.of(2026, 11, 25, 20, 30, 0, 0, utc)

        assertTrue(
            DailyLogReminderCalculator.shouldSkipSnoozeNearNextRegularRun(
                snoozeFireTime = nextRegular.minusMinutes(45),
                nextRegularTime = nextRegular
            )
        )
        assertTrue(
            DailyLogReminderCalculator.shouldSkipSnoozeNearNextRegularRun(
                snoozeFireTime = nextRegular.minusMinutes(60),
                nextRegularTime = nextRegular
            )
        )
        assertFalse(
            DailyLogReminderCalculator.shouldSkipSnoozeNearNextRegularRun(
                snoozeFireTime = nextRegular.minusMinutes(61),
                nextRegularTime = nextRegular
            )
        )
    }

    @Test
    fun `formatDaysSummary and snooze normalization produce expected labels`() {
        assertEquals("Every day", DailyLogReminderCalculator.formatDaysSummary(DailyLogReminderCalculator.ALL_DAYS_MASK))
        assertEquals("Every day", DailyLogReminderCalculator.formatDaysSummary(0))
        assertEquals("Weekdays", DailyLogReminderCalculator.formatDaysSummary(DailyLogReminderCalculator.WEEKDAYS_MASK))
        assertEquals("Weekends", DailyLogReminderCalculator.formatDaysSummary(DailyLogReminderCalculator.WEEKENDS_MASK))

        var monWedFri = 0
        monWedFri = DailyLogReminderCalculator.withDaySelected(monWedFri, DayOfWeek.MONDAY, true)
        monWedFri = DailyLogReminderCalculator.withDaySelected(monWedFri, DayOfWeek.WEDNESDAY, true)
        monWedFri = DailyLogReminderCalculator.withDaySelected(monWedFri, DayOfWeek.FRIDAY, true)
        assertEquals("Mon, Wed, Fri", DailyLogReminderCalculator.formatDaysSummary(monWedFri))

        assertEquals(30, DailyLogReminderCalculator.normalizeSnoozeMinutes(30))
        assertEquals(60, DailyLogReminderCalculator.normalizeSnoozeMinutes(45))
        assertEquals("15m", DailyLogReminderCalculator.formatSnoozeLabel(15, compact = true))
        assertEquals("15 min", DailyLogReminderCalculator.formatSnoozeLabel(15, compact = false))
        assertEquals("1 hr", DailyLogReminderCalculator.formatSnoozeLabel(60, compact = true))
        assertEquals("2 hr", DailyLogReminderCalculator.formatSnoozeLabel(120, compact = false))
    }

    @Test
    fun `decoupled minTriggerAfter and referenceTime computes full delay to next day without 1m undercount`() {
        // Worker fires at 20:30:05; minTriggerAfter = 20:31:05 selects tomorrow 20:30:00,
        // while initialDelay uses real nowZoned (20:30:05) -> 23h 59m 55s (86,395,000 ms).
        val nowZoned = ZonedDateTime.of(2026, 11, 25, 20, 30, 5, 0, utc)
        val minTriggerAfter = nowZoned.plusMinutes(1L)
        val target = DailyLogReminderCalculator.computeNextTriggerDateTime(
            now = minTriggerAfter,
            hour = 20,
            minute = 30,
            daysMask = DailyLogReminderCalculator.ALL_DAYS_MASK
        )
        assertEquals(ZonedDateTime.of(2026, 11, 26, 20, 30, 0, 0, utc), target)
        assertEquals(
            86_395_000L,
            DailyLogReminderCalculator.computeInitialDelayMillis(now = nowZoned, target = target)
        )
    }
}
