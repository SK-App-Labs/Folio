/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolioChromeIconsTest {

    @Test
    fun allChromeIconsResolveValid960ViewportVectors() {
        val allIcons = listOf(
            FolioChromeIcons.ReceiptLongOutlined,
            FolioChromeIcons.ReceiptLongFilled,
            FolioChromeIcons.PieChartOutlined,
            FolioChromeIcons.PieChartFilled,
            FolioChromeIcons.AccountBalanceWalletOutlined,
            FolioChromeIcons.AccountBalanceWalletFilled,
            FolioChromeIcons.SettingsOutlined,
            FolioChromeIcons.SettingsFilled,
            FolioChromeIcons.Add,
            FolioChromeIcons.ChevronLeft,
            FolioChromeIcons.ChevronRight,
            FolioChromeIcons.CalendarMonth,
            FolioChromeIcons.Today,
            FolioChromeIcons.StarOutlined,
            FolioChromeIcons.StarFilled,
            FolioChromeIcons.FilterList,
            FolioChromeIcons.Search,
            FolioChromeIcons.ArrowDropDown,
            FolioChromeIcons.DateRange,
            FolioChromeIcons.Warning,
            FolioChromeIcons.Payments,
            FolioChromeIcons.Public,
            FolioChromeIcons.SwapHoriz,
            FolioChromeIcons.Category,
            FolioChromeIcons.Label,
            FolioChromeIcons.Autorenew,
            FolioChromeIcons.Palette,
            FolioChromeIcons.GridView,
            FolioChromeIcons.Language,
            FolioChromeIcons.Notifications,
            FolioChromeIcons.Tune,
            FolioChromeIcons.Schedule,
            FolioChromeIcons.Edit,
            FolioChromeIcons.Fingerprint,
            FolioChromeIcons.Lock,
            FolioChromeIcons.History,
            FolioChromeIcons.Backup,
            FolioChromeIcons.PictureAsPdf,
            FolioChromeIcons.TableChart,
            FolioChromeIcons.MenuBook,
            FolioChromeIcons.PlayArrow,
            FolioChromeIcons.Feedback,
            FolioChromeIcons.LocalCafe,
            FolioChromeIcons.Shield,
            FolioChromeIcons.DeleteForever,
            FolioChromeIcons.OpenInNew,
        )

        assertEquals(46, allIcons.size)
        for (icon in allIcons) {
            assertEquals(960f, icon.viewportWidth)
            assertEquals(960f, icon.viewportHeight)
            assertTrue("${icon.name} should have non-empty root nodes", icon.root.size > 0)
        }
    }

    @Test
    fun directionalChromeIconsEnableAutoMirror() {
        assertTrue(FolioChromeIcons.ReceiptLongOutlined.autoMirror)
        assertTrue(FolioChromeIcons.ReceiptLongFilled.autoMirror)
        assertTrue(FolioChromeIcons.ChevronLeft.autoMirror)
        assertTrue(FolioChromeIcons.ChevronRight.autoMirror)
        assertTrue(FolioChromeIcons.Label.autoMirror)
        assertTrue(FolioChromeIcons.MenuBook.autoMirror)
        assertTrue(FolioChromeIcons.Feedback.autoMirror)
        assertTrue(FolioChromeIcons.OpenInNew.autoMirror)
        assertFalse(FolioChromeIcons.SettingsOutlined.autoMirror)
    }

    @Test
    fun bottomNavAndStarUseDistinctOutlinedAndFilledVariants() {
        assertNotEquals(FolioChromeIcons.ReceiptLongOutlined, FolioChromeIcons.ReceiptLongFilled)
        assertNotEquals(FolioChromeIcons.PieChartOutlined, FolioChromeIcons.PieChartFilled)
        assertNotEquals(FolioChromeIcons.AccountBalanceWalletOutlined, FolioChromeIcons.AccountBalanceWalletFilled)
        assertNotEquals(FolioChromeIcons.SettingsOutlined, FolioChromeIcons.SettingsFilled)
        assertNotEquals(FolioChromeIcons.StarOutlined, FolioChromeIcons.StarFilled)
    }
}
