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
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object DueRemindersScheduler {
    private const val LEGACY_PERIODIC_WORK_NAME = "folio_due_reminders_worker"
    const val UNIQUE_WORK_NAME = "folio_due_reminders_daily_v2"
    val DEFAULT_WORK_POLICY: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP

    internal fun computeNextTriggerDateTime(
        now: ZonedDateTime,
        hourOfDay: Int,
        minute: Int,
        minTriggerAfter: ZonedDateTime = now,
    ): ZonedDateTime {
        val h = hourOfDay.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        var target = now.withHour(h)
            .withMinute(m)
            .withSecond(0)
            .withNano(0)

        if (!target.isAfter(now) || (target.toLocalDate() == now.toLocalDate() && target.isBefore(minTriggerAfter))) {
            target = target.plusDays(1)
        }
        return target
    }

    fun scheduleDailyReminders(
        context: Context,
        hourOfDay: Int = 9,
        minute: Int = 0,
        existingWorkPolicy: ExistingPeriodicWorkPolicy = DEFAULT_WORK_POLICY,
    ) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(LEGACY_PERIODIC_WORK_NAME)

        val now = ZonedDateTime.now()
        val target = computeNextTriggerDateTime(now, hourOfDay, minute)
        val initialDelayMillis = Duration.between(now, target).toMillis().coerceAtLeast(0L)

        val workRequest = OneTimeWorkRequestBuilder<DueRemindersWorker>()
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .addTag("folio_due_reminders")
            .build()

        val oneTimePolicy = when (existingWorkPolicy) {
            ExistingPeriodicWorkPolicy.KEEP -> ExistingWorkPolicy.KEEP
            else -> ExistingWorkPolicy.REPLACE
        }

        wm.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            oneTimePolicy,
            workRequest,
        )
    }

    internal fun scheduleNextFromWorker(
        context: Context,
        hourOfDay: Int,
        minute: Int,
        referenceTime: ZonedDateTime = ZonedDateTime.now(),
    ) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(LEGACY_PERIODIC_WORK_NAME)

        val target = computeNextTriggerDateTime(
            now = referenceTime,
            hourOfDay = hourOfDay,
            minute = minute,
            minTriggerAfter = referenceTime.plusMinutes(1L),
        )
        val initialDelayMillis = Duration.between(referenceTime, target).toMillis().coerceAtLeast(60_000L)
        val workRequest = OneTimeWorkRequestBuilder<DueRemindersWorker>()
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .addTag("folio_due_reminders")
            .build()

        wm.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            workRequest,
        )
    }

    /**
     * Reschedules daily reminders with replacement so user time
     * or preference updates take effect immediately and track DST transitions accurately.
     */
    fun rescheduleDailyReminders(
        context: Context,
        hourOfDay: Int = 9,
        minute: Int = 0,
    ) {
        scheduleDailyReminders(
            context = context,
            hourOfDay = hourOfDay,
            minute = minute,
            existingWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
        )
    }

    fun cancelDailyReminders(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(LEGACY_PERIODIC_WORK_NAME)
        wm.cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
