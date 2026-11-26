/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/data/notification/DailyLogReminderActionReceiver.kt
package com.folioledger.app.data.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.folioledger.app.domain.util.DailyLogReminderCalculator
import java.time.LocalDate

/**
 * Handles background notification actions (Snooze) for the Daily Log Reminder without launching
 * MainActivity or triggering BiometricPrompt on lock-screen/background taps.
 */
class DailyLogReminderActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != FolioNotificationManager.ACTION_SNOOZE_DAILY_LOG) return

        FolioNotificationManager.cancelDailyLogNotification(context)

        val snoozeMinutes = DailyLogReminderCalculator.normalizeSnoozeMinutes(
            intent.getIntExtra(
                FolioNotificationManager.EXTRA_SNOOZE_MINUTES,
                DailyLogReminderCalculator.DEFAULT_SNOOZE_MINUTES
            )
        )
        val originEpochDay = intent.getLongExtra(
            FolioNotificationManager.EXTRA_ORIGIN_EPOCH_DAY,
            LocalDate.now().toEpochDay()
        )
        val snoozeCount = intent.getIntExtra(
            FolioNotificationManager.EXTRA_SNOOZE_COUNT,
            0
        ).coerceAtLeast(0)

        if (snoozeCount < DailyLogReminderCalculator.MAX_SNOOZES_PER_DAY) {
            DailyLogReminderScheduler.scheduleSnooze(
                context = context,
                snoozeMinutes = snoozeMinutes,
                originEpochDay = originEpochDay,
                snoozeCount = snoozeCount + 1
            )
        }
    }
}
