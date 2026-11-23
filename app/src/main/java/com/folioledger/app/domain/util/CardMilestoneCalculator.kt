package com.folioledger.app.domain.util

import com.folioledger.app.domain.model.CardMilestoneConfig
import com.folioledger.app.domain.model.CardMilestoneInfo
import com.folioledger.app.domain.model.MinorMoney
import org.json.JSONObject
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
        isAutoRenewing: Boolean = true
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
                val obj = root.getJSONObject(key)
                val config = CardMilestoneConfig(
                    accountId = accountId,
                    milestoneTitle = obj.optString("milestoneTitle", "Annual Fee Waiver"),
                    cycleStartDateEpochDay = obj.optLong("cycleStartDateEpochDay", LocalDate.now().toEpochDay()),
                    targetAmountMinor = obj.optLong("targetAmountMinor", 100_000_00L),
                    annualFeeSavedMinor = if (obj.has("annualFeeSavedMinor") && !obj.isNull("annualFeeSavedMinor")) {
                        obj.getLong("annualFeeSavedMinor")
                    } else null,
                    isAutoRenewing = obj.optBoolean("isAutoRenewing", true)
                )
                map[accountId] = config
            }
            map
        }.getOrNull()

        if (androidParsed != null && androidParsed.isNotEmpty()) return androidParsed

        // Pure Kotlin fallback for JVM unit test environments where org.json.JSONObject is stubbed
        return runCatching {
            val map = mutableMapOf<Long, CardMilestoneConfig>()
            val entryRegex = Regex("\"(\\d+)\"\\s*:\\s*\\{([^}]+)\\}")
            for (match in entryRegex.findAll(json)) {
                val accountId = match.groupValues[1].toLongOrNull() ?: continue
                val body = match.groupValues[2]

                fun extractString(key: String, default: String): String {
                    val r = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
                    return r.find(body)?.groupValues?.get(1) ?: default
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
                    isAutoRenewing = extractBoolean("isAutoRenewing", true)
                )
            }
            map
        }.getOrDefault(emptyMap())
    }

    /**
     * Serializes an upserted milestone configuration into the JSON map.
     */
    fun upsertMilestone(currentJson: String?, config: CardMilestoneConfig): String {
        return runCatching {
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
    }

    /**
     * Evaluates a card's milestone progress and pacing metrics against qualified spend in the active cycle.
     */
    fun evaluateMilestone(
        config: CardMilestoneConfig,
        qualifyingSpendMinor: Long,
        referenceDate: LocalDate = LocalDate.now()
    ): CardMilestoneInfo {
        val (cycleStart, cycleEnd) = computeCurrentAnniversaryWindow(
            startDateEpochDay = config.cycleStartDateEpochDay,
            referenceDateEpochDay = referenceDate.toEpochDay(),
            isAutoRenewing = config.isAutoRenewing
        )

        val targetMinor = config.targetAmountMinor.coerceAtLeast(1L)
        val remainingMinor = (targetMinor - qualifyingSpendMinor).coerceAtLeast(0L)
        val progressPct = (qualifyingSpendMinor.toFloat() / targetMinor.toFloat() * 100f).coerceIn(0f, 100f)

        val daysRemaining = if (referenceDate.isAfter(cycleEnd)) {
            0L
        } else {
            ChronoUnit.DAYS.between(referenceDate, cycleEnd).coerceAtLeast(1L)
        }
        val requiredDailyMinor = if (remainingMinor > 0L && daysRemaining > 0L) {
            remainingMinor / daysRemaining
        } else {
            0L
        }

        val totalCycleDays = ChronoUnit.DAYS.between(cycleStart, cycleEnd).coerceAtLeast(1L)
        val elapsedDays = ChronoUnit.DAYS.between(cycleStart, referenceDate).coerceIn(0L, totalCycleDays)
        val expectedPacePct = (elapsedDays.toFloat() / totalCycleDays.toFloat() * 100f)
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
            isAutoRenewing = config.isAutoRenewing
        )
    }
}
