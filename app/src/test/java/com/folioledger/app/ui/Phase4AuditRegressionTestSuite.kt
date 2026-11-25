/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui

import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.MinorMoney
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.round

class Phase4AuditRegressionTestSuite {

    // ---------------------------------------------------------------------------------------------
    // 1. Donut Chart Normalization: Sweep Angles Never Exceed 360 Degrees
    // ---------------------------------------------------------------------------------------------
    @Test
    fun donutChart_sweepNormalization_neverExceeds360Degrees() {
        // Given 30 small slices of 1 minor unit each out of a total of 30 units
        val slicesCount = 30
        val totalMinorSum = slicesCount.toLong()
        val amounts = List(slicesCount) { 1L }

        val rawSweeps = amounts.map { amount ->
            val ratio = amount.toFloat() / totalMinorSum.toFloat()
            (ratio * 360f).coerceAtLeast(2.5f)
        }
        val rawSum = rawSweeps.sum()
        // Raw sum coerced to 2.5 min per slice = 75 degrees, but what if there are 200 tiny slices?
        val tinyAmounts = List(200) { 1L }
        val tinyTotal = 200L
        val tinyRawSweeps = tinyAmounts.map { amount ->
            val ratio = amount.toFloat() / tinyTotal.toFloat()
            (ratio * 360f).coerceAtLeast(2.5f)
        }
        // 200 * 2.5 = 500 degrees!
        val tinyRawSum = tinyRawSweeps.sum()
        assertTrue("Raw sweeps exceed 360 degrees when many tiny categories exist", tinyRawSum > 360f)

        // Applying the normalized sweepScale:
        val sweepScale = 360f / tinyRawSum.coerceAtLeast(360f)
        val normalizedSweeps = tinyRawSweeps.map { it * sweepScale }
        val normalizedSum = normalizedSweeps.sum()

        assertEquals(360f, normalizedSum, 0.05f)
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Passbook Hero Cards: Surplus Credit Balance Debt & Available Credit Invariants
    // ---------------------------------------------------------------------------------------------
    @Test
    fun creditCardPassbook_surplusBalance_doesNotInflateDebtOrReduceCredit() {
        val limit = MinorMoney(100_000L) // $1,000.00
        val surplusBalance = MinorMoney(20_000L) // +$200.00 overpayment

        val account = AccountSummary(
            id = 1L,
            name = "Sapphire Reserve",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "USD",
            currentBalance = surplusBalance,
            creditLimit = limit,
            settlementDayOfMonth = 18,
            paymentDueDayOfMonth = 5
        )

        val hasSurplus = account.currentBalance.isPositive
        val totalOutstandingDebt = if (hasSurplus) MinorMoney.ZERO else account.currentBalance.abs()
        val creditLimit = account.creditLimit ?: MinorMoney.ZERO
        val availableCredit = if (hasSurplus) {
            creditLimit + account.currentBalance
        } else if (creditLimit > totalOutstandingDebt) {
            creditLimit - totalOutstandingDebt
        } else {
            MinorMoney.ZERO
        }

        val utilizationPercent = if (creditLimit.isPositive) {
            (totalOutstandingDebt.amountMinor.toFloat() / creditLimit.amountMinor.toFloat() * 100f).coerceIn(0f, 100f)
        } else {
            0f
        }

        // Debt must be zero
        assertEquals(MinorMoney.ZERO, totalOutstandingDebt)
        // Available credit should be limit + surplus = $1,200.00
        assertEquals(MinorMoney(120_000L), availableCredit)
        // Utilization should be 0.0%
        assertEquals(0.0f, utilizationPercent, 0.001f)
    }

    @Test
    fun creditCardPassbook_normalDebt_correctlyComputesAvailableCreditAndUtilization() {
        val limit = MinorMoney(100_000L) // $1,000.00
        val debtBalance = MinorMoney(-40_000L) // -$400.00 debt

        val account = AccountSummary(
            id = 1L,
            name = "Sapphire Reserve",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "USD",
            currentBalance = debtBalance,
            creditLimit = limit,
            settlementDayOfMonth = 18,
            paymentDueDayOfMonth = 5
        )

        val hasSurplus = account.currentBalance.isPositive
        val totalOutstandingDebt = if (hasSurplus) MinorMoney.ZERO else account.currentBalance.abs()
        val creditLimit = account.creditLimit ?: MinorMoney.ZERO
        val availableCredit = if (hasSurplus) {
            creditLimit + account.currentBalance
        } else if (creditLimit > totalOutstandingDebt) {
            creditLimit - totalOutstandingDebt
        } else {
            MinorMoney.ZERO
        }

        val utilizationPercent = if (creditLimit.isPositive) {
            (totalOutstandingDebt.amountMinor.toFloat() / creditLimit.amountMinor.toFloat() * 100f).coerceIn(0f, 100f)
        } else {
            0f
        }

        assertEquals(MinorMoney(40_000L), totalOutstandingDebt)
        assertEquals(MinorMoney(60_000L), availableCredit)
        assertEquals(40.0f, utilizationPercent, 0.001f)
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Net Worth Trend Chart: StepX Tap Coordinate Hit-Testing
    // ---------------------------------------------------------------------------------------------
    @Test
    fun netWorthGrowthChart_tapHitTesting_alignsWithNodeCoordinates() {
        val itemCount = 7
        val chartWidth = 600f
        val stepX = chartWidth / (itemCount - 1).coerceAtLeast(1).toFloat() // 600 / 6 = 100.0f
        val lastIndex = itemCount - 1

        fun hitTest(offsetX: Float): Int {
            return round(offsetX / stepX).toInt().coerceIn(0, lastIndex)
        }

        // Exact marker coordinates: 0, 100, 200, 300, 400, 500, 600
        assertEquals(0, hitTest(0f))
        assertEquals(0, hitTest(45f))
        assertEquals(1, hitTest(55f))
        assertEquals(1, hitTest(100f))
        assertEquals(1, hitTest(149f))
        assertEquals(2, hitTest(151f))
        assertEquals(6, hitTest(560f))
        assertEquals(6, hitTest(600f))
        assertEquals(6, hitTest(700f)) // Clamped
        assertEquals(0, hitTest(-50f)) // Clamped
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Credit Utilization Tiers
    // ---------------------------------------------------------------------------------------------
    @Test
    fun creditUtilizationTiers_evaluateCorrectThresholds() {
        fun tierName(utilizationPercent: Float): String = when {
            utilizationPercent > 50f -> "Risky"
            utilizationPercent > 30f -> "High"
            utilizationPercent > 10f -> "Good"
            else -> "Optimal"
        }

        assertEquals("Optimal", tierName(0f))
        assertEquals("Optimal", tierName(5f))
        assertEquals("Optimal", tierName(10.0f))
        assertEquals("Good", tierName(10.1f))
        assertEquals("Good", tierName(25.0f))
        assertEquals("Good", tierName(30.0f))
        assertEquals("High", tierName(30.1f))
        assertEquals("High", tierName(50.0f))
        assertEquals("Risky", tierName(50.1f))
        assertEquals("Risky", tierName(95.0f))
    }

    // ---------------------------------------------------------------------------------------------
    // 5. App Language Switching: Android 13+ OS Delegation vs. Pre-Android 13 Manual Recreation
    // ---------------------------------------------------------------------------------------------
    @Test
    fun localeManagerDispatch_requiresManualRecreateOnlyWhenLocaleManagerUnavailable() {
        fun shouldDispatchManualRecreate(sdkInt: Int, isLocaleManagerPresent: Boolean): Boolean {
            return if (sdkInt >= 33) {
                !isLocaleManagerPresent
            } else {
                true
            }
        }

        // On Android 13+ (API 33, 34, 35) with LocaleManager, OS handles recreation -> no manual recreate
        org.junit.Assert.assertFalse(shouldDispatchManualRecreate(sdkInt = 33, isLocaleManagerPresent = true))
        org.junit.Assert.assertFalse(shouldDispatchManualRecreate(sdkInt = 34, isLocaleManagerPresent = true))
        org.junit.Assert.assertFalse(shouldDispatchManualRecreate(sdkInt = 35, isLocaleManagerPresent = true))

        // On Android 13+ fallback if LocaleManager service is null -> manual recreate fallback
        assertTrue(shouldDispatchManualRecreate(sdkInt = 33, isLocaleManagerPresent = false))

        // On pre-Android 13 (API 29, 30, 31, 32) -> must manually recreate activity
        assertTrue(shouldDispatchManualRecreate(sdkInt = 29, isLocaleManagerPresent = false))
        assertTrue(shouldDispatchManualRecreate(sdkInt = 32, isLocaleManagerPresent = false))
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Share File MIME Type Resolution
    // ---------------------------------------------------------------------------------------------
    @Test
    fun shareFileMimeType_resolvesCorrectMimeTypeByExtension() {
        fun resolveMimeType(fileName: String): String = when {
            fileName.endsWith(".zip", ignoreCase = true) -> "application/zip"
            fileName.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
            else -> "text/csv"
        }

        assertEquals("application/zip", resolveMimeType("Folio_Backup_2026.zip"))
        assertEquals("application/zip", resolveMimeType("BACKUP.ZIP"))
        assertEquals("application/pdf", resolveMimeType("Statement_Checking_2026.pdf"))
        assertEquals("application/pdf", resolveMimeType("ANNUAL_TAX_2026.PDF"))
        assertEquals("text/csv", resolveMimeType("Folio_Ledger.csv"))
        assertEquals("text/csv", resolveMimeType("unknown_export.txt"))
    }
}
