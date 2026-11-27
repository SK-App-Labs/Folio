/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.settings.components

import com.folioledger.app.domain.model.AutoBackupRetention
import com.folioledger.app.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsPresentationHelpersTest {

    @Test
    fun formatAutoBackupSummary_reflectsDemoDisabledSafEncryptedMirrorAndErrors() {
        assertEquals(
            "Paused (Demo Sandbox)",
            formatAutoBackupSummary(UserPreferences(isDemoModeActive = true, safMirrorEnabled = true))
        )
        assertEquals(
            "Disabled",
            formatAutoBackupSummary(UserPreferences(autoBackupEnabled = false))
        )
        assertEquals(
            "30 Days (Tiered)",
            formatAutoBackupSummary(UserPreferences(autoBackupRetention = AutoBackupRetention.THIRTY_DAYS))
        )
        assertEquals(
            "30 Days \u2022 Mirror On",
            formatAutoBackupSummary(
                UserPreferences(
                    autoBackupRetention = AutoBackupRetention.THIRTY_DAYS,
                    safMirrorEnabled = true,
                    safMirrorTreeUri = "content://tree/1",
                    safMirrorEncryptEnabled = false
                )
            )
        )
        assertEquals(
            "90 Days \u2022 SAF Encrypted",
            formatAutoBackupSummary(
                UserPreferences(
                    autoBackupRetention = AutoBackupRetention.NINETY_DAYS,
                    safMirrorEnabled = true,
                    safMirrorTreeUri = "content://tree/1",
                    safMirrorEncryptEnabled = true,
                    biometricLockEnabled = true
                )
            )
        )
        assertEquals(
            "10 Sessions \u2022 Mirror Alert",
            formatAutoBackupSummary(
                UserPreferences(
                    autoBackupRetention = AutoBackupRetention.TEN_SESSIONS,
                    safMirrorEnabled = true,
                    safMirrorTreeUri = "content://tree/1",
                    safMirrorErrorCode = "PERMISSION_LOST"
                )
            )
        )
        // App lock blocks plaintext SAF mirror summary even if stale flag true
        assertEquals(
            "30 Days (Tiered)",
            formatAutoBackupSummary(
                UserPreferences(
                    autoBackupRetention = AutoBackupRetention.THIRTY_DAYS,
                    safMirrorEnabled = true,
                    safMirrorTreeUri = "content://tree/1",
                    safMirrorEncryptEnabled = false,
                    biometricLockEnabled = true
                )
            )
        )
        // Intentionally paused SAF mirror with stale PERMISSION_LOST does NOT show Mirror Alert
        assertEquals(
            "30 Days (Tiered)",
            formatAutoBackupSummary(
                UserPreferences(
                    autoBackupRetention = AutoBackupRetention.THIRTY_DAYS,
                    safMirrorEnabled = false,
                    safMirrorTreeUri = "content://tree/1",
                    safMirrorErrorCode = "PERMISSION_LOST"
                )
            )
        )
        // App Lock + KEY_UNAVAILABLE DOES surface Mirror Alert even though App Lock paused mirroring
        assertEquals(
            "30 Days \u2022 Mirror Alert",
            formatAutoBackupSummary(
                UserPreferences(
                    autoBackupRetention = AutoBackupRetention.THIRTY_DAYS,
                    safMirrorEnabled = false,
                    safMirrorTreeUri = "content://tree/1",
                    biometricLockEnabled = true,
                    safMirrorErrorCode = "KEY_UNAVAILABLE"
                )
            )
        )
    }

    @Test
    fun formatDueRemindersSummary_formatsLeadDaysAndTime() {
        val prefsDefault = UserPreferences(
            ccReminderLeadDays = 3,
            billReminderLeadDays = 1,
            reminderHourOfDay = 9,
            reminderMinute = 0
        )
        assertEquals(
            "CC: 3d before \u2022 Bills: 1d before \u2022 9:00 AM",
            formatDueRemindersSummary(prefsDefault, is24Hour = false)
        )

        val prefsSameDay = UserPreferences(
            ccReminderLeadDays = 0,
            billReminderLeadDays = 0,
            reminderHourOfDay = 20,
            reminderMinute = 5
        )
        assertEquals(
            "CC: On due date \u2022 Bills: On due date \u2022 20:05",
            formatDueRemindersSummary(prefsSameDay, is24Hour = true)
        )
    }

    @Test
    fun resolveSettingsBannerMode_prioritizesDemoOverSafErrors() {
        assertEquals(
            SettingsBannerMode.DEMO_SANDBOX,
            resolveSettingsBannerMode(
                UserPreferences(
                    isDemoModeActive = true,
                    safMirrorTreeUri = "content://tree/1",
                    safMirrorErrorCode = "PERMISSION_LOST"
                )
            )
        )
        assertEquals(
            SettingsBannerMode.SAF_PERMISSION_LOST,
            resolveSettingsBannerMode(
                UserPreferences(
                    safMirrorEnabled = true,
                    safMirrorTreeUri = "content://tree/1",
                    safMirrorErrorCode = "PERMISSION_LOST"
                )
            )
        )
        assertEquals(
            SettingsBannerMode.SAF_KEY_UNAVAILABLE,
            resolveSettingsBannerMode(
                UserPreferences(
                    safMirrorEnabled = true,
                    safMirrorTreeUri = "content://tree/1",
                    safMirrorErrorCode = "KEY_UNAVAILABLE"
                )
            )
        )
        assertEquals(
            SettingsBannerMode.SAF_KEY_UNAVAILABLE,
            resolveSettingsBannerMode(
                UserPreferences(
                    safMirrorEnabled = false,
                    biometricLockEnabled = true,
                    safMirrorTreeUri = "content://tree/1",
                    safMirrorErrorCode = "KEY_UNAVAILABLE"
                )
            )
        )
        assertEquals(
            SettingsBannerMode.SAF_IO_ERROR,
            resolveSettingsBannerMode(
                UserPreferences(
                    safMirrorEnabled = true,
                    safMirrorTreeUri = "content://tree/1",
                    safMirrorErrorCode = "IO_ERROR"
                )
            )
        )
        assertEquals(
            SettingsBannerMode.NONE,
            resolveSettingsBannerMode(
                UserPreferences(
                    safMirrorEnabled = false,
                    safMirrorTreeUri = "content://tree/1",
                    safMirrorErrorCode = "PERMISSION_LOST"
                )
            )
        )
        assertEquals(
            SettingsBannerMode.NONE,
            resolveSettingsBannerMode(
                UserPreferences(
                    safMirrorEnabled = true,
                    safMirrorTreeUri = "content://tree/1",
                    safMirrorErrorCode = null
                )
            )
        )
    }
}
