/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class FolioThemeContrastTest {

    private fun relativeLuminance(color: Color): Double {
        fun linearize(c: Float): Double {
            val d = c.toDouble()
            return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linearize(color.red) +
            0.7152 * linearize(color.green) +
            0.0722 * linearize(color.blue)
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val l1 = relativeLuminance(foreground)
        val l2 = relativeLuminance(background)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun allLightAndSepiaAccentPalettesMeetWcagAaPrimaryButtonContrast() {
        for (palette in FolioAccentPalette.entries) {
            val light = buildLightSchemeForAccent(palette)
            val lightRatio = contrastRatio(light.onPrimary, light.primary)
            assertTrue(
                "Light palette ${palette.name} primary/onPrimary contrast $lightRatio must be >= 4.5:1",
                lightRatio >= 4.5,
            )

            val sepia = buildSepiaSchemeForAccent(palette)
            val sepiaRatio = contrastRatio(sepia.onPrimary, sepia.primary)
            assertTrue(
                "Sepia palette ${palette.name} primary/onPrimary contrast $sepiaRatio must be >= 4.5:1",
                sepiaRatio >= 4.5,
            )
        }
    }
}
