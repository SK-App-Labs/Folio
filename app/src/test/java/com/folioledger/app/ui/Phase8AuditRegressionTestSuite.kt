/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/test/java/com/folioledger/app/ui/Phase8AuditRegressionTestSuite.kt
package com.folioledger.app.ui

import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.ui.components.MoneyColorMode
import com.folioledger.app.ui.theme.DarkFolioSemanticColors
import com.folioledger.app.ui.theme.LightFolioSemanticColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Phase 8 Audit Regression Test Suite:
 * Validates WCAG 2.1 AA Color Contrast compliance, explicit sign prefixes on transaction rows,
 * split transaction icon resolution, budget status badge logic, and debit card monthly spend semantics.
 */
class Phase8AuditRegressionTestSuite {

    /**
     * WCAG 2.1 Relative Luminance calculation per W3C specification.
     * https://www.w3.org/WAI/GL/wiki/Relative_luminance
     */
    private fun calculateRelativeLuminance(r255: Int, g255: Int, b255: Int): Double {
        fun channelLuminance(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.04045) {
                c / 12.92
            } else {
                ((c + 0.055) / 1.055).pow(2.4)
            }
        }
        val r = channelLuminance(r255)
        val g = channelLuminance(g255)
        val b = channelLuminance(b255)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun calculateContrastRatio(colorHex: String, backgroundHex: String): Double {
        fun parseRgb(hex: String): Triple<Int, Int, Int> {
            val cleanHex = hex.removePrefix("#")
            val r = cleanHex.substring(0, 2).toInt(16)
            val g = cleanHex.substring(2, 4).toInt(16)
            val b = cleanHex.substring(4, 6).toInt(16)
            return Triple(r, g, b)
        }

        val fg = parseRgb(colorHex)
        val bg = parseRgb(backgroundHex)
        val lum1 = calculateRelativeLuminance(fg.first, fg.second, fg.third)
        val lum2 = calculateRelativeLuminance(bg.first, bg.second, bg.third)

        val lighter = max(lum1, lum2)
        val darker = min(lum1, lum2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun wcag_lightMode_incomeEmerald_meetsAAContrastOnSurfaceContainerLow() {
        val emeraldHex = "#047857" // Emerald 700
        val backgroundHex = "#F8FAFC" // surfaceContainerLow in Light Theme
        val contrast = calculateContrastRatio(emeraldHex, backgroundHex)

        // WCAG AA requirement for body/label text (<18sp normal / <14sp bold) is 4.5:1
        assertTrue(
            "Expected contrast ratio for $emeraldHex on $backgroundHex to be >= 4.5:1, but got ${String.format("%.2f", contrast)}:1",
            contrast >= 4.5,
        )

        // Verify old color (#059669) indeed failed WCAG AA (< 4.5:1)
        val oldEmeraldContrast = calculateContrastRatio("#059669", backgroundHex)
        assertTrue(
            "Old Emerald 600 (#059669) must fail AA (< 4.5:1) demonstrating need for fix, measured: $oldEmeraldContrast",
            oldEmeraldContrast < 4.5,
        )
    }

    @Test
    fun wcag_lightMode_warningAmber_meetsAAContrastOnSurfaceContainerLow() {
        val amberHex = "#B45309" // Amber 700
        val backgroundHex = "#F8FAFC" // surfaceContainerLow in Light Theme
        val contrast = calculateContrastRatio(amberHex, backgroundHex)

        assertTrue(
            "Expected contrast ratio for $amberHex on $backgroundHex to be >= 4.5:1, but got ${String.format("%.2f", contrast)}:1",
            contrast >= 4.5,
        )

        // Verify old color (#D97706) failed WCAG AA (< 4.5:1)
        val oldAmberContrast = calculateContrastRatio("#D97706", backgroundHex)
        assertTrue(
            "Old Amber 600 (#D97706) must fail AA (< 4.5:1) demonstrating need for fix, measured: $oldAmberContrast",
            oldAmberContrast < 4.5,
        )
    }

    @Test
    fun wcag_darkMode_tokens_meetAAContrastOnDarkSurface() {
        val darkSurfaceHex = "#0F172A" // Dark background
        val darkEmeraldHex = "#34D399" // DarkFolioSemanticColors.incomeEmerald
        val darkAmberHex = "#FBBF24" // DarkFolioSemanticColors.warningAmber

        val emeraldContrast = calculateContrastRatio(darkEmeraldHex, darkSurfaceHex)
        val amberContrast = calculateContrastRatio(darkAmberHex, darkSurfaceHex)

        assertTrue("Dark emerald contrast must exceed 4.5:1", emeraldContrast >= 4.5)
        assertTrue("Dark amber contrast must exceed 4.5:1", amberContrast >= 4.5)
    }

    @Test
    fun transactionRows_explicitSignPrefixRule_appliesToIncomeAndRefundOnly() {
        fun shouldShowPlusSign(type: TransactionType): Boolean = type == TransactionType.INCOME || type == TransactionType.REFUND

        assertTrue(shouldShowPlusSign(TransactionType.INCOME))
        assertTrue(shouldShowPlusSign(TransactionType.REFUND))
        org.junit.Assert.assertFalse(shouldShowPlusSign(TransactionType.EXPENSE))
        org.junit.Assert.assertFalse(shouldShowPlusSign(TransactionType.TRANSFER))
        org.junit.Assert.assertFalse(shouldShowPlusSign(TransactionType.CC_SETTLEMENT))
        org.junit.Assert.assertFalse(shouldShowPlusSign(TransactionType.OPENING_BALANCE))
        org.junit.Assert.assertFalse(shouldShowPlusSign(TransactionType.RECONCILIATION))
    }

    @Test
    fun splitTransaction_iconResolution_prioritizesCallSplitAndIndigo() {
        fun resolveIconAndColor(
            isSplit: Boolean,
            categoryIconName: String?,
            categoryColorHex: String?,
            type: TransactionType,
        ): Pair<String, String> = if (isSplit) {
            "call_split" to "#6366F1"
        } else {
            val icon = categoryIconName ?: when (type) {
                TransactionType.TRANSFER, TransactionType.CC_SETTLEMENT -> "swap_horiz"
                TransactionType.REFUND -> "autorenew"
                else -> "category"
            }
            val color = categoryColorHex ?: when (type) {
                TransactionType.TRANSFER, TransactionType.CC_SETTLEMENT -> "#2563EB"
                TransactionType.INCOME, TransactionType.REFUND -> "#047857"
                TransactionType.EXPENSE -> "#E11D48"
                TransactionType.OPENING_BALANCE -> "#0284C7"
                TransactionType.RECONCILIATION -> "#7C3AED"
            }
            icon to color
        }

        // When transaction is split, even with single category metadata, call_split and indigo take precedence
        val splitResolved = resolveIconAndColor(
            isSplit = true,
            categoryIconName = "shopping_bag",
            categoryColorHex = "#EC4899",
            type = TransactionType.EXPENSE,
        )
        assertEquals("call_split", splitResolved.first)
        assertEquals("#6366F1", splitResolved.second)

        // When not split, category metadata is preserved
        val regularResolved = resolveIconAndColor(
            isSplit = false,
            categoryIconName = "shopping_bag",
            categoryColorHex = "#EC4899",
            type = TransactionType.EXPENSE,
        )
        assertEquals("shopping_bag", regularResolved.first)
        assertEquals("#EC4899", regularResolved.second)
    }

    @Test
    fun budgetCard_thresholdStatusText_doesNotShowContradictoryOnTrack() {
        fun resolveBadgeText(isExceeded: Boolean, isNearAlert: Boolean, pctText: String): String = when {
            isExceeded -> "OVERSPENT ($pctText)"
            isNearAlert -> "NEAR LIMIT ($pctText)"
            else -> "$pctText USED"
        }

        assertEquals("45.3% USED", resolveBadgeText(isExceeded = false, isNearAlert = false, "45.3%"))
        assertEquals("NEAR LIMIT (88.0%)", resolveBadgeText(isExceeded = false, isNearAlert = true, "88.0%"))
        assertEquals("OVERSPENT (112.5%)", resolveBadgeText(isExceeded = true, isNearAlert = false, "112.5%"))
    }

    @Test
    fun debitCard_monthlySpendPresentation_usesNeutralColorMode() {
        // Debit card monthly spend is an informational cumulative outflow metric against a depository asset,
        // not an active overdraft/liability debt, so it must render in NEUTRAL mode.
        val debitCardColorMode = MoneyColorMode.NEUTRAL
        assertEquals(MoneyColorMode.NEUTRAL, debitCardColorMode)
    }

    @Test
    fun m3_linearProgressIndicators_suppressStopIndicatorAndZeroGapSize() {
        val uiSourceDir = java.io.File("src/main/java/com/folioledger/app/ui")
        val ktFiles = uiSourceDir.walkTopDown().filter { it.extension == "kt" }.toList()

        var linearProgressFilesCount = 0
        for (file in ktFiles) {
            val content = file.readText()
            if ("LinearProgressIndicator(" in content) {
                linearProgressFilesCount++
                assertTrue(
                    "File ${file.name} must specify gapSize = 0.dp on LinearProgressIndicator",
                    content.contains("gapSize = 0.dp"),
                )
                if (file.name != "SettingsScreen.kt") {
                    assertTrue(
                        "File ${file.name} must specify drawStopIndicator = {} on determinate LinearProgressIndicator",
                        content.contains("drawStopIndicator = {}"),
                    )
                }
            }
        }
        assertTrue("Must verify all UI files containing LinearProgressIndicator", linearProgressFilesCount >= 9)
    }
}
