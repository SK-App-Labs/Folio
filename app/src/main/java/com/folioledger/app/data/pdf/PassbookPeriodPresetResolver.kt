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

import java.time.LocalDate

object PassbookPeriodPresetResolver {

    const val ALL_TIME_SENTINEL_EPOCH_DAY = Long.MIN_VALUE

    /**
     * Resolves the start and end epoch day for a given [PassbookPeriodPreset].
     */
    fun resolvePresetRange(
        preset: PassbookPeriodPreset,
        today: LocalDate = LocalDate.now(),
        earliestEpochDay: Long? = null,
        latestEpochDay: Long? = null
    ): Pair<Long, Long> {
        return when (preset) {
            PassbookPeriodPreset.CURRENT_MONTH -> {
                val start = today.withDayOfMonth(1).toEpochDay()
                val end = today.toEpochDay()
                start to end
            }
            PassbookPeriodPreset.LAST_MONTH -> {
                val lastMonth = today.minusMonths(1)
                val start = lastMonth.withDayOfMonth(1).toEpochDay()
                val end = lastMonth.withDayOfMonth(lastMonth.lengthOfMonth()).toEpochDay()
                start to end
            }
            PassbookPeriodPreset.LAST_THREE_MONTHS -> {
                val start = today.minusMonths(3).withDayOfMonth(1).toEpochDay()
                val end = today.toEpochDay()
                start to end
            }
            PassbookPeriodPreset.YEAR_TO_DATE -> {
                val start = today.withDayOfYear(1).toEpochDay()
                val end = today.toEpochDay()
                start to end
            }
            PassbookPeriodPreset.ALL_TIME -> {
                val defaultStart = LocalDate.of(2000, 1, 1).toEpochDay()
                val start = earliestEpochDay ?: defaultStart
                val end = maxOf(latestEpochDay ?: today.toEpochDay(), today.toEpochDay())
                start to end
            }
            PassbookPeriodPreset.CUSTOM_RANGE -> {
                today.minusMonths(1).toEpochDay() to today.toEpochDay()
            }
        }
    }
}
