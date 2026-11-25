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

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure domain utility for formatting date bounds of project, trip, or event passbooks.
 */
object TagDateSpanFormatter {

    private val shortDateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM", Locale.US)
    private val fullDateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)

    /**
     * Formats min and max epoch days into a concise range string:
     * - null inputs -> "Active"
     * - same day -> "15 Oct"
     * - distinct days in same year -> "15 Oct – 22 Oct"
     * - distinct years -> "15 Dec 2025 – 10 Jan 2026"
     */
    fun formatSpan(minEpochDay: Long?, maxEpochDay: Long?): String {
        if (minEpochDay == null || maxEpochDay == null) return "Active"
        val safeMin = minOf(minEpochDay, maxEpochDay)
        val safeMax = maxOf(minEpochDay, maxEpochDay)
        val startDate = LocalDate.ofEpochDay(safeMin)
        val endDate = LocalDate.ofEpochDay(safeMax)
        if (safeMin == safeMax) return startDate.format(shortDateFormatter)
        val formatter = if (startDate.year != endDate.year) fullDateFormatter else shortDateFormatter
        return "${startDate.format(formatter)} – ${endDate.format(formatter)}"
    }
}
