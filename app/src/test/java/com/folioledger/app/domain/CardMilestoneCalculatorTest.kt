/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.domain

import com.folioledger.app.domain.model.CardMilestoneConfig
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.util.CardMilestoneCalculator
import com.folioledger.app.domain.util.SavingsGoalCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CardMilestoneCalculatorTest {

    @Test
    fun anniversaryWindow_firstYear_matchesExpectedDates() {
        val startEpochDay = LocalDate.of(2026, 1, 15).toEpochDay()
        val referenceEpochDay = LocalDate.of(2026, 6, 1).toEpochDay()

        val (cycleStart, cycleEnd) = CardMilestoneCalculator.computeCurrentAnniversaryWindow(
            startDateEpochDay = startEpochDay,
            referenceDateEpochDay = referenceEpochDay,
            isAutoRenewing = true
        )

        assertEquals(LocalDate.of(2026, 1, 15), cycleStart)
        assertEquals(LocalDate.of(2027, 1, 14), cycleEnd)
    }

    @Test
    fun anniversaryWindow_autoRenew_rollsForwardMultipleYears() {
        // Card was issued 3 years ago on Oct 10, 2023
        val startEpochDay = LocalDate.of(2023, 10, 10).toEpochDay()
        // Today is Nov 2, 2026 -> Active window is Oct 10, 2026 to Oct 9, 2027
        val referenceEpochDay = LocalDate.of(2026, 11, 2).toEpochDay()

        val (cycleStart, cycleEnd) = CardMilestoneCalculator.computeCurrentAnniversaryWindow(
            startDateEpochDay = startEpochDay,
            referenceDateEpochDay = referenceEpochDay,
            isAutoRenewing = true
        )

        assertEquals(LocalDate.of(2026, 10, 10), cycleStart)
        assertEquals(LocalDate.of(2027, 10, 9), cycleEnd)
    }

    @Test
    fun anniversaryWindow_noAutoRenew_retainsInitialWindow() {
        val startEpochDay = LocalDate.of(2023, 10, 10).toEpochDay()
        val referenceEpochDay = LocalDate.of(2026, 11, 2).toEpochDay()

        val (cycleStart, cycleEnd) = CardMilestoneCalculator.computeCurrentAnniversaryWindow(
            startDateEpochDay = startEpochDay,
            referenceDateEpochDay = referenceEpochDay,
            isAutoRenewing = false
        )

        // Without auto-renew, window remains the 1-time window from 2023
        assertEquals(LocalDate.of(2023, 10, 10), cycleStart)
        assertEquals(LocalDate.of(2024, 10, 9), cycleEnd)
    }

    @Test
    fun anniversaryWindow_leapYearHandling() {
        val startEpochDay = LocalDate.of(2024, 2, 29).toEpochDay()
        // In 2025 (non-leap year), rolls forward
        val ref2025EpochDay = LocalDate.of(2025, 3, 1).toEpochDay()

        val (cycleStart, cycleEnd) = CardMilestoneCalculator.computeCurrentAnniversaryWindow(
            startDateEpochDay = startEpochDay,
            referenceDateEpochDay = ref2025EpochDay,
            isAutoRenewing = true
        )

        assertEquals(LocalDate.of(2025, 2, 28), cycleStart)
        assertEquals(LocalDate.of(2026, 2, 27), cycleEnd)
    }

    @Test
    fun anniversaryWindow_leapYearHandling_fourYearsForward_restoresFeb29() {
        val startEpochDay = LocalDate.of(2024, 2, 29).toEpochDay()
        // 4 years later in 2028 (leap year)
        val ref2028EpochDay = LocalDate.of(2028, 3, 1).toEpochDay()

        val (cycleStart, cycleEnd) = CardMilestoneCalculator.computeCurrentAnniversaryWindow(
            startDateEpochDay = startEpochDay,
            referenceDateEpochDay = ref2028EpochDay,
            isAutoRenewing = true
        )

        // In 2028, cycleStart must land on Feb 29, not drift to Feb 28
        assertEquals(LocalDate.of(2028, 2, 29), cycleStart)
        assertEquals(LocalDate.of(2029, 2, 27), cycleEnd)
    }

    @Test
    fun evaluateMilestone_expiredNonRenewingMilestone_setsDaysRemainingAndDailySpendToZero() {
        val config = CardMilestoneConfig(
            accountId = 202L,
            milestoneTitle = "Welcome Bonus Spend",
            cycleStartDateEpochDay = LocalDate.of(2025, 1, 1).toEpochDay(),
            targetAmountMinor = 50_000_00L,
            annualFeeSavedMinor = null,
            isAutoRenewing = false
        )

        // Reference date is well after cycleEnd (2025-12-31)
        val referenceDate = LocalDate.of(2026, 3, 15)
        val result = CardMilestoneCalculator.evaluateMilestone(
            config = config,
            qualifyingSpendMinor = 30_000_00L, // Incomplete
            referenceDate = referenceDate
        )

        assertEquals(0L, result.daysRemaining)
        assertEquals(MinorMoney.ZERO, result.requiredDailySpend)
        assertFalse(result.isAheadOfPace)
        assertFalse(result.isAchieved)
    }

    @Test
    fun evaluateMilestone_inProgress_calculatesPacingAndDailySpend() {
        val config = CardMilestoneConfig(
            accountId = 101L,
            milestoneTitle = "Infinia Annual Fee Waiver",
            cycleStartDateEpochDay = LocalDate.of(2026, 1, 1).toEpochDay(),
            targetAmountMinor = 1_000_000_00L, // 10,00,000
            annualFeeSavedMinor = 12_500_00L,  // 12,500
            isAutoRenewing = true
        )

        // Halfway through the year with 6,00,000 spend (Ahead of pace)
        val today = LocalDate.of(2026, 7, 2)
        val qualifyingSpend = 600_000_00L

        val result = CardMilestoneCalculator.evaluateMilestone(
            config = config,
            qualifyingSpendMinor = qualifyingSpend,
            referenceDate = today
        )

        assertEquals("Infinia Annual Fee Waiver", result.milestoneTitle)
        assertEquals(MinorMoney(600_000_00L), result.qualifyingSpend)
        assertEquals(MinorMoney(400_000_00L), result.remainingSpend)
        assertEquals(60f, result.progressPercentage, 0.1f)
        assertTrue(result.isAheadOfPace)
        assertFalse(result.isAchieved)
        assertEquals(MinorMoney(12_500_00L), result.annualFeeSaved)
        assertTrue(result.daysRemaining in 180..185)
        assertTrue(result.requiredDailySpend.amountMinor > 0L)
    }

    @Test
    fun evaluateMilestone_targetReached_marksAchieved() {
        val config = CardMilestoneConfig(
            accountId = 102L,
            milestoneTitle = "Amex Platinum Travel 4L Milestone",
            cycleStartDateEpochDay = LocalDate.of(2026, 3, 15).toEpochDay(),
            targetAmountMinor = 400_000_00L,
            annualFeeSavedMinor = null,
            isAutoRenewing = true
        )

        val today = LocalDate.of(2026, 8, 1)
        val qualifyingSpend = 420_000_00L

        val result = CardMilestoneCalculator.evaluateMilestone(
            config = config,
            qualifyingSpendMinor = qualifyingSpend,
            referenceDate = today
        )

        assertTrue(result.isAchieved)
        assertTrue(result.isAheadOfPace)
        assertEquals(MinorMoney.ZERO, result.remainingSpend)
        assertEquals(MinorMoney.ZERO, result.requiredDailySpend)
        assertEquals(100f, result.progressPercentage, 0.01f)
    }

    @Test
    fun parseMilestones_demoSeederJson_parsesCorrectly() {
        val demoJson = """
            {
                "101": {
                    "milestoneTitle": "Annual Fee Waiver",
                    "cycleStartDateEpochDay": 20000,
                    "targetAmountMinor": 2500000,
                    "annualFeeSavedMinor": 9500,
                    "isAutoRenewing": true
                }
            }
        """.trimIndent()

        val parsed = CardMilestoneCalculator.parseMilestones(demoJson)
        assertEquals(1, parsed.size)
        val config = parsed[101L]
        assertNotNull(config)
        assertEquals(101L, config!!.accountId)
        assertEquals("Annual Fee Waiver", config.milestoneTitle)
        assertEquals(20000L, config.cycleStartDateEpochDay)
        assertEquals(2500000L, config.targetAmountMinor)
        assertEquals(9500L, config.annualFeeSavedMinor)
        assertTrue(config.isAutoRenewing)
    }

    @Test
    fun cardMilestoneScaleFactor_scalesCorrectlyAcrossCurrencies() {
        fun computeScaleFactor(fractionDigits: Int): Long =
            Math.pow(10.0, fractionDigits.toDouble()).toLong().coerceAtLeast(1L)

        // 0-decimal currency (JPY)
        val jpyScale = computeScaleFactor(0)
        assertEquals(1L, jpyScale)
        val jpyTargetInput = "100000"
        val jpyMinor = ((jpyTargetInput.toDoubleOrNull() ?: 0.0) * jpyScale.toDouble()).toLong()
        assertEquals(100_000L, jpyMinor)

        // 2-decimal currency (USD, INR, EUR)
        val usdScale = computeScaleFactor(2)
        assertEquals(100L, usdScale)
        val usdTargetInput = "5000"
        val usdMinor = ((usdTargetInput.toDoubleOrNull() ?: 0.0) * usdScale.toDouble()).toLong()
        assertEquals(500_000L, usdMinor)

        // 3-decimal currency (KWD, BHD, OMR)
        val kwdScale = computeScaleFactor(3)
        assertEquals(1000L, kwdScale)
        val kwdTargetInput = "1000"
        val kwdMinor = ((kwdTargetInput.toDoubleOrNull() ?: 0.0) * kwdScale.toDouble()).toLong()
        assertEquals(1_000_000L, kwdMinor)
    }

    @Test
    fun parseMilestones_emptyOrCorruptJson_returnsEmptyMapSafely() {
        assertTrue(CardMilestoneCalculator.parseMilestones("").isEmpty())
        assertTrue(CardMilestoneCalculator.parseMilestones("   ").isEmpty())
        assertTrue(CardMilestoneCalculator.parseMilestones("{not_valid_json").isEmpty())
    }

    @Test
    fun evaluateMilestone_zeroTargetAmount_handlesSafelyWithoutDivisionByZero() {
        val config = CardMilestoneConfig(
            accountId = 303L,
            milestoneTitle = "Zero Target",
            cycleStartDateEpochDay = LocalDate.of(2026, 1, 1).toEpochDay(),
            targetAmountMinor = 0L,
            annualFeeSavedMinor = null,
            isAutoRenewing = true
        )
        val result = CardMilestoneCalculator.evaluateMilestone(config, 0L, LocalDate.of(2026, 6, 1))
        assertTrue(result.progressPercentage.isFinite())
        assertFalse(result.isAchieved)
    }

    @Test
    fun parseMilestonesAndGoals_singleMalformedEntry_preservesHealthyEntries() {
        val mixedMilestones = """{
            "101": {"milestoneTitle": "Fee Waiver", "cycleStartDateEpochDay": 20454, "targetAmountMinor": 10000000, "isAutoRenewing": true},
            "102": "corrupt_non_object"
        }"""
        val parsedMilestones = CardMilestoneCalculator.parseMilestones(mixedMilestones)
        assertEquals(1, parsedMilestones.size)
        assertEquals("Fee Waiver", parsedMilestones[101L]?.milestoneTitle)

        val mixedGoals = """{
            "201": {"goalTitle": "Emergency", "targetAmountMinor": 50000000},
            "202": "corrupt_non_object"
        }"""
        val parsedGoals = SavingsGoalCalculator.parseGoals(mixedGoals)
        assertEquals(1, parsedGoals.size)
        assertEquals("Emergency", parsedGoals[201L]?.goalTitle)
    }

    @Test
    fun evaluateMilestone_inclusiveDaysRemaining_usesCeilingDailySpend() {
        val config = CardMilestoneConfig(
            accountId = 404L,
            milestoneTitle = "Waiver",
            cycleStartDateEpochDay = LocalDate.of(2026, 1, 1).toEpochDay(),
            targetAmountMinor = 100_00L, // ₹100.00
            isAutoRenewing = false
        )
        // Cycle ends Dec 31. On Dec 29: Dec 29, Dec 30, Dec 31 = 3 inclusive days left -> ₹100 / 3 = ceil(3333.33) = 3334 minor
        val result = CardMilestoneCalculator.evaluateMilestone(
            config = config,
            qualifyingSpendMinor = 0L,
            referenceDate = LocalDate.of(2026, 12, 29)
        )
        assertEquals(3L, result.daysRemaining)
        assertEquals(MinorMoney(3334L), result.requiredDailySpend)
    }

    @Test
    fun parseMilestones_handlesBracesInsideTitleOnJvmFallback() {
        val json = """{
            "105": {"milestoneTitle": "Bonus {VIP} Waiver", "cycleStartDateEpochDay": 20454, "targetAmountMinor": 5000000, "isAutoRenewing": true}
        }"""
        val parsed = CardMilestoneCalculator.parseMilestones(json)
        assertEquals(1, parsed.size)
        assertEquals("Bonus {VIP} Waiver", parsed[105L]?.milestoneTitle)
    }
}
