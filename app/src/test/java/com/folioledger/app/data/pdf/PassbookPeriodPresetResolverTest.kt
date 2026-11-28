/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.data.pdf

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PassbookPeriodPresetResolverTest {

    private val fixedToday = LocalDate.of(2026, 11, 7)

    @Test
    fun `CURRENT_MONTH resolves from start of current month to today`() {
        val (start, end) = PassbookPeriodPresetResolver.resolvePresetRange(
            PassbookPeriodPreset.CURRENT_MONTH,
            fixedToday,
        )
        assertEquals(LocalDate.of(2026, 11, 1).toEpochDay(), start)
        assertEquals(LocalDate.of(2026, 11, 7).toEpochDay(), end)
    }

    @Test
    fun `LAST_MONTH resolves full previous calendar month`() {
        val (start, end) = PassbookPeriodPresetResolver.resolvePresetRange(
            PassbookPeriodPreset.LAST_MONTH,
            fixedToday,
        )
        assertEquals(LocalDate.of(2026, 10, 1).toEpochDay(), start)
        assertEquals(LocalDate.of(2026, 10, 31).toEpochDay(), end)
    }

    @Test
    fun `LAST_THREE_MONTHS resolves three months ago first day to today`() {
        val (start, end) = PassbookPeriodPresetResolver.resolvePresetRange(
            PassbookPeriodPreset.LAST_THREE_MONTHS,
            fixedToday,
        )
        assertEquals(LocalDate.of(2026, 8, 1).toEpochDay(), start)
        assertEquals(LocalDate.of(2026, 11, 7).toEpochDay(), end)
    }

    @Test
    fun `YEAR_TO_DATE resolves Jan 1 of current year to today`() {
        val (start, end) = PassbookPeriodPresetResolver.resolvePresetRange(
            PassbookPeriodPreset.YEAR_TO_DATE,
            fixedToday,
        )
        assertEquals(LocalDate.of(2026, 1, 1).toEpochDay(), start)
        assertEquals(LocalDate.of(2026, 11, 7).toEpochDay(), end)
    }

    @Test
    fun `ALL_TIME resolves from year 2000 to today`() {
        val (start, end) = PassbookPeriodPresetResolver.resolvePresetRange(
            PassbookPeriodPreset.ALL_TIME,
            fixedToday,
        )
        assertEquals(LocalDate.of(2000, 1, 1).toEpochDay(), start)
        assertEquals(LocalDate.of(2026, 11, 7).toEpochDay(), end)
    }

    @Test
    fun `CUSTOM_RANGE defaults to one month ago to today`() {
        val (start, end) = PassbookPeriodPresetResolver.resolvePresetRange(
            PassbookPeriodPreset.CUSTOM_RANGE,
            fixedToday,
        )
        assertEquals(LocalDate.of(2026, 10, 7).toEpochDay(), start)
        assertEquals(LocalDate.of(2026, 11, 7).toEpochDay(), end)
    }

    @Test
    fun passbookPeriodPresetResolver_yearBoundaryAndLeapYear_resolveAccurately() {
        val janDate = LocalDate.of(2026, 1, 15)
        val (janLastMonthStart, janLastMonthEnd) = PassbookPeriodPresetResolver.resolvePresetRange(
            PassbookPeriodPreset.LAST_MONTH,
            janDate,
        )
        // Previous month is Dec 2025
        assertEquals(LocalDate.of(2025, 12, 1).toEpochDay(), janLastMonthStart)
        assertEquals(LocalDate.of(2025, 12, 31).toEpochDay(), janLastMonthEnd)

        val (jan3MonthsStart, jan3MonthsEnd) = PassbookPeriodPresetResolver.resolvePresetRange(
            PassbookPeriodPreset.LAST_THREE_MONTHS,
            janDate,
        )
        assertEquals(LocalDate.of(2025, 10, 1).toEpochDay(), jan3MonthsStart)
        assertEquals(LocalDate.of(2026, 1, 15).toEpochDay(), jan3MonthsEnd)

        // Leap year 2024 (March 15) -> previous month is Feb 2024 (29 days)
        val leapDate = LocalDate.of(2024, 3, 15)
        val (leapLastMonthStart, leapLastMonthEnd) = PassbookPeriodPresetResolver.resolvePresetRange(
            PassbookPeriodPreset.LAST_MONTH,
            leapDate,
        )
        assertEquals(LocalDate.of(2024, 2, 1).toEpochDay(), leapLastMonthStart)
        assertEquals(LocalDate.of(2024, 2, 29).toEpochDay(), leapLastMonthEnd)
    }

    @Test
    fun `ALL_TIME respects explicit earliest and latest bounds when provided`() {
        val customEarliest = LocalDate.of(1998, 5, 10).toEpochDay()
        val futureLatest = LocalDate.of(2027, 2, 1).toEpochDay()
        val (start, end) = PassbookPeriodPresetResolver.resolvePresetRange(
            preset = PassbookPeriodPreset.ALL_TIME,
            today = fixedToday,
            earliestEpochDay = customEarliest,
            latestEpochDay = futureLatest,
        )
        assertEquals(customEarliest, start)
        assertEquals(futureLatest, end)
    }
}
