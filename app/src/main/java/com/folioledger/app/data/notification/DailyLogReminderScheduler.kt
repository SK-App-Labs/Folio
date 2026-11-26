/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/data/notification/DailyLogReminderScheduler.kt
package com.folioledger.app.data.notification

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.domain.util.DailyLogReminderCalculator
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object DailyLogReminderScheduler {
    const val UNIQUE_DAILY_WORK_NAME = "folio_daily_log_reminder"
    const val UNIQUE_SNOOZE_WORK_NAME = "folio_daily_log_reminder_snooze"

    fun scheduleNextDaily(
        context: Context,
        hour: Int,
        minute: Int,
        daysMask: Int,
        existingPolicy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
        referenceTime: ZonedDateTime = ZonedDateTime.now(),
        minTriggerAfter: ZonedDateTime = referenceTime
    ) {
        val target = DailyLogReminderCalculator.computeNextTriggerDateTime(
            now = minTriggerAfter,
            hour = hour,
            minute = minute,
            daysMask = daysMask
        )
        val delayMs = DailyLogReminderCalculator.computeInitialDelayMillis(
            now = referenceTime,
            target = target
        )
        val workRequest = OneTimeWorkRequestBuilder<DailyLogReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(DailyLogReminderWorker.KEY_IS_SNOOZE to false))
            .addTag(UNIQUE_DAILY_WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_DAILY_WORK_NAME,
            existingPolicy,
            workRequest
        )
    }

    fun scheduleSnooze(
        context: Context,
        snoozeMinutes: Int,
        originEpochDay: Long,
        snoozeCount: Int
    ) {
        val safeMinutes = DailyLogReminderCalculator.normalizeSnoozeMinutes(snoozeMinutes)
        val workRequest = OneTimeWorkRequestBuilder<DailyLogReminderWorker>()
            .setInitialDelay(safeMinutes.toLong(), TimeUnit.MINUTES)
            .setInputData(
                workDataOf(
                    DailyLogReminderWorker.KEY_IS_SNOOZE to true,
                    DailyLogReminderWorker.KEY_ORIGIN_EPOCH_DAY to originEpochDay,
                    DailyLogReminderWorker.KEY_SNOOZE_COUNT to snoozeCount
                )
            )
            .addTag(UNIQUE_SNOOZE_WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_SNOOZE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    fun cancelSnooze(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_SNOOZE_WORK_NAME)
    }

    fun cancelAll(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(UNIQUE_DAILY_WORK_NAME)
        workManager.cancelUniqueWork(UNIQUE_SNOOZE_WORK_NAME)
        FolioNotificationManager.cancelDailyLogNotification(context)
    }

    fun syncWithPreferences(
        context: Context,
        prefs: UserPreferences,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE
    ) {
        if (prefs.dailyLogReminderEnabled) {
            if (policy == ExistingWorkPolicy.REPLACE) {
                cancelSnooze(context)
            }
            scheduleNextDaily(
                context = context,
                hour = prefs.dailyLogReminderHour,
                minute = prefs.dailyLogReminderMinute,
                daysMask = prefs.dailyLogReminderDaysMask,
                existingPolicy = policy
            )
        } else {
            cancelAll(context)
        }
    }
}
