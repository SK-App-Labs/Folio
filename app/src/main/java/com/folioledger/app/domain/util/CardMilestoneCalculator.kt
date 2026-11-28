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

import com.folioledger.app.domain.model.CardMilestoneConfig
import com.folioledger.app.domain.model.CardMilestoneInfo
import com.folioledger.app.domain.model.MinorMoney
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object CardMilestoneCalculator {

    /**
     * Computes the active anniversary cycle window.
     * When [isAutoRenewing] is true, automatically rolls forward year-by-year from [startDateEpochDay]
     * so that the active window always encompasses [referenceDateEpochDay].
     */
    fun computeCurrentAnniversaryWindow(
        startDateEpochDay: Long,
        referenceDateEpochDay: Long = LocalDate.now().toEpochDay(),
        isAutoRenewing: Boolean = true,
    ): Pair<LocalDate, LocalDate> {
        val initialStart = LocalDate.ofEpochDay(startDateEpochDay)
        val refDate = LocalDate.ofEpochDay(referenceDateEpochDay)

        if (!isAutoRenewing || refDate.isBefore(initialStart)) {
            val initialEnd = initialStart.plusYears(1).minusDays(1)
            return initialStart to initialEnd
        }

        var yearsElapsed = java.time.temporal.ChronoUnit.YEARS.between(initialStart, refDate).coerceAtLeast(0L)
        while (!refDate.isBefore(initialStart.plusYears(yearsElapsed + 1L))) {
            yearsElapsed++
        }
        val currentStart = initialStart.plusYears(yearsElapsed)
        val currentEnd = initialStart.plusYears(yearsElapsed + 1L).minusDays(1)
        return currentStart to currentEnd
    }

    /**
     * Parses the persistent card milestones JSON map: `{ "accountId": { ...config } }`.
     */
    fun parseMilestones(json: String?): Map<Long, CardMilestoneConfig> {
        if (json.isNullOrBlank() || json.trim() == "{}") return emptyMap()
        val androidParsed = runCatching {
            val root = JSONObject(json)
            val map = mutableMapOf<Long, CardMilestoneConfig>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val accountId = key.toLongOrNull() ?: continue
                val obj = root.optJSONObject(key) ?: continue
                runCatching {
                    val config = CardMilestoneConfig(
                        accountId = accountId,
                        milestoneTitle = obj.optString("milestoneTitle", "Annual Fee Waiver"),
                        cycleStartDateEpochDay = obj.optLong("cycleStartDateEpochDay", LocalDate.now().toEpochDay()),
                        targetAmountMinor = obj.optLong("targetAmountMinor", 100_000_00L),
                        annualFeeSavedMinor = if (obj.has("annualFeeSavedMinor") && !obj.isNull("annualFeeSavedMinor")) {
                            obj.getLong("annualFeeSavedMinor")
                        } else {
                            null
                        },
                        isAutoRenewing = obj.optBoolean("isAutoRenewing", true),
                    )
                    map[accountId] = config
                }
            }
            map
        }.getOrNull()

        if (androidParsed != null && androidParsed.isNotEmpty()) return androidParsed

        // Pure Kotlin fallback for JVM unit test environments where org.json.JSONObject is stubbed
        return runCatching {
            val map = mutableMapOf<Long, CardMilestoneConfig>()
            val entryPattern = Regex("\"(\\d+)\"\\s*:\\s*\\{")
            for (match in entryPattern.findAll(json)) {
                val accountId = match.groupValues[1].toLongOrNull() ?: continue
                val bodyStart = match.range.last + 1
                var depth = 1
                var cursor = bodyStart
                var inString = false
                var escaped = false
                while (cursor < json.length && depth > 0) {
                    val ch = json[cursor]
                    when {
                        escaped -> escaped = false
                        ch == '\\' && inString -> escaped = true
                        ch == '"' -> inString = !inString
                        !inString && ch == '{' -> depth++
                        !inString && ch == '}' -> depth--
                    }
                    cursor++
                }
                if (depth != 0 || cursor <= bodyStart) continue
                val body = json.substring(bodyStart, cursor - 1)

                fun extractString(key: String, default: String): String {
                    val r = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                    val raw = r.find(body)?.groupValues?.get(1) ?: return default
                    return raw.replace("\\\"", "\"").replace("\\\\", "\\")
                }

                fun extractLong(key: String, default: Long): Long {
                    val r = Regex("\"$key\"\\s*:\\s*(-?\\d+)")
                    return r.find(body)?.groupValues?.get(1)?.toLongOrNull() ?: default
                }

                fun extractNullableLong(key: String): Long? {
                    val r = Regex("\"$key\"\\s*:\\s*(-?\\d+)")
                    return r.find(body)?.groupValues?.get(1)?.toLongOrNull()
                }

                fun extractBoolean(key: String, default: Boolean): Boolean {
                    val r = Regex("\"$key\"\\s*:\\s*(true|false)")
                    return r.find(body)?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: default
                }

                map[accountId] = CardMilestoneConfig(
                    accountId = accountId,
                    milestoneTitle = extractString("milestoneTitle", "Annual Fee Waiver"),
                    cycleStartDateEpochDay = extractLong("cycleStartDateEpochDay", LocalDate.now().toEpochDay()),
                    targetAmountMinor = extractLong("targetAmountMinor", 100_000_00L),
                    annualFeeSavedMinor = extractNullableLong("annualFeeSavedMinor"),
                    isAutoRenewing = extractBoolean("isAutoRenewing", true),
                )
            }
            map
        }.getOrDefault(emptyMap())
    }

    /**
     * Serializes an upserted milestone configuration into the JSON map.
     */
    fun upsertMilestone(currentJson: String?, config: CardMilestoneConfig): String = runCatching {
        val root = if (!currentJson.isNullOrBlank() && currentJson.trim() != "{}") {
            JSONObject(currentJson)
        } else {
            JSONObject()
        }
        val obj = JSONObject().apply {
            put("milestoneTitle", config.milestoneTitle)
            put("cycleStartDateEpochDay", config.cycleStartDateEpochDay)
            put("targetAmountMinor", config.targetAmountMinor)
            if (config.annualFeeSavedMinor != null) {
                put("annualFeeSavedMinor", config.annualFeeSavedMinor)
            } else {
                put("annualFeeSavedMinor", JSONObject.NULL)
            }
            put("isAutoRenewing", config.isAutoRenewing)
        }
        root.put(config.accountId.toString(), obj)
        root.toString()
    }.getOrDefault(currentJson ?: "{}")

    /**
     * Evaluates a card's milestone progress and pacing metrics against qualified spend in the active cycle.
     */
    fun evaluateMilestone(
        config: CardMilestoneConfig,
        qualifyingSpendMinor: Long,
        referenceDate: LocalDate = LocalDate.now(),
    ): CardMilestoneInfo {
        val (cycleStart, cycleEnd) = computeCurrentAnniversaryWindow(
            startDateEpochDay = config.cycleStartDateEpochDay,
            referenceDateEpochDay = referenceDate.toEpochDay(),
            isAutoRenewing = config.isAutoRenewing,
        )

        val targetMinor = config.targetAmountMinor.coerceAtLeast(1L)
        val remainingMinor = MoneyFormatterAndCalculator.safeSubtract(targetMinor, qualifyingSpendMinor).coerceAtLeast(0L)
        val progressPct = MoneyFormatterAndCalculator.calculatePercentage(qualifyingSpendMinor, targetMinor)
            .coerceIn(0f, 100f)

        val totalCycleDays = (ChronoUnit.DAYS.between(cycleStart, cycleEnd) + 1L).coerceAtLeast(1L)
        val effectiveRef = if (referenceDate.isBefore(cycleStart)) cycleStart else referenceDate
        val daysRemaining = if (referenceDate.isAfter(cycleEnd)) {
            0L
        } else {
            (ChronoUnit.DAYS.between(effectiveRef, cycleEnd) + 1L).coerceAtLeast(1L)
        }
        val requiredDailyMinor = if (remainingMinor > 0L && daysRemaining > 0L) {
            BigDecimal.valueOf(remainingMinor)
                .divide(BigDecimal.valueOf(daysRemaining), 0, RoundingMode.CEILING)
                .toLong()
        } else {
            0L
        }

        val elapsedDays = when {
            referenceDate.isBefore(cycleStart) -> 0L
            referenceDate.isAfter(cycleEnd) -> totalCycleDays
            else -> (ChronoUnit.DAYS.between(cycleStart, referenceDate) + 1L).coerceIn(0L, totalCycleDays)
        }
        val expectedPacePct = MoneyFormatterAndCalculator.calculatePercentage(elapsedDays, totalCycleDays)
            .coerceIn(0f, 100f)
        val isAchieved = qualifyingSpendMinor >= targetMinor
        val isAhead = if (referenceDate.isAfter(cycleEnd)) {
            isAchieved
        } else {
            progressPct >= expectedPacePct || remainingMinor == 0L
        }

        return CardMilestoneInfo(
            milestoneTitle = config.milestoneTitle,
            cycleStartDate = cycleStart,
            cycleEndDate = cycleEnd,
            qualifyingSpend = MinorMoney(qualifyingSpendMinor),
            targetAmount = MinorMoney(targetMinor),
            remainingSpend = MinorMoney(remainingMinor),
            progressPercentage = progressPct,
            daysRemaining = daysRemaining,
            requiredDailySpend = MinorMoney(requiredDailyMinor),
            isAheadOfPace = isAhead,
            isAchieved = isAchieved,
            annualFeeSaved = config.annualFeeSavedMinor?.let { MinorMoney(it) },
            isAutoRenewing = config.isAutoRenewing,
        )
    }
}
