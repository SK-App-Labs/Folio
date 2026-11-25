/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/data/notification/DueRemindersScheduler.kt
package com.folioledger.app.data.notification

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object DueRemindersScheduler {
    const val UNIQUE_WORK_NAME = "folio_due_reminders_worker"
    val DEFAULT_WORK_POLICY: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP

    fun scheduleDailyReminders(
        context: Context,
        hourOfDay: Int = 9,
        minute: Int = 0,
        existingWorkPolicy: ExistingPeriodicWorkPolicy = DEFAULT_WORK_POLICY
    ) {
        val now = LocalDateTime.now()
        var target = now.withHour(hourOfDay.coerceIn(0, 23))
            .withMinute(minute.coerceIn(0, 59))
            .withSecond(0)
            .withNano(0)

        if (now.isAfter(target)) {
            target = target.plusDays(1)
        }

        val initialDelayMillis = Duration.between(now, target).toMillis().coerceAtLeast(0L)

        val workRequest = PeriodicWorkRequestBuilder<DueRemindersWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .addTag("folio_due_reminders")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            existingWorkPolicy,
            workRequest
        )
    }

    /**
     * Reschedules daily reminders with [ExistingPeriodicWorkPolicy.UPDATE] so user time
     * or frequency preferences take effect immediately without WorkManager ignoring the change.
     */
    fun rescheduleDailyReminders(
        context: Context,
        hourOfDay: Int = 9,
        minute: Int = 0
    ) {
        scheduleDailyReminders(
            context = context,
            hourOfDay = hourOfDay,
            minute = minute,
            existingWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE
        )
    }

    fun cancelDailyReminders(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
