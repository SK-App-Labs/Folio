/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/data/notification/DailyLogReminderWorker.kt
package com.folioledger.app.data.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import com.folioledger.app.FolioApplication
import com.folioledger.app.domain.util.DailyLogReminderCalculator
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class DailyLogReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_IS_SNOOZE = "is_snooze"
        const val KEY_ORIGIN_EPOCH_DAY = "origin_epoch_day"
        const val KEY_SNOOZE_COUNT = "snooze_count"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? FolioApplication ?: return@withContext Result.failure()
        val container = app.container

        try {
            val prefs = container.settingsAndBackupRepository.observeUserPreferences().first()
            val isSnooze = inputData.getBoolean(KEY_IS_SNOOZE, false)

            if (!prefs.dailyLogReminderEnabled) {
                DailyLogReminderScheduler.cancelAll(applicationContext)
                return@withContext Result.success()
            }

            val nowZoned = ZonedDateTime.now(container.clock)

            // Always schedule the next regular occurrence on non-snooze runs so demo mode,
            // smart-skip, or missing permission never breaks tomorrow's daily reminder chain.
            if (!isSnooze) {
                DailyLogReminderScheduler.scheduleNextDaily(
                    context = applicationContext,
                    hour = prefs.dailyLogReminderHour,
                    minute = prefs.dailyLogReminderMinute,
                    daysMask = prefs.dailyLogReminderDaysMask,
                    existingPolicy = ExistingWorkPolicy.REPLACE,
                    referenceTime = nowZoned.plusMinutes(1L)
                )
            }

            if (prefs.isDemoModeActive) {
                return@withContext Result.success()
            }

            val todayEpochDay = LocalDate.now(container.clock).toEpochDay()
            val originEpochDay = inputData.getLong(KEY_ORIGIN_EPOCH_DAY, todayEpochDay)
            val snoozeCount = inputData.getInt(KEY_SNOOZE_COUNT, 0).coerceAtLeast(0)

            if (prefs.dailyLogReminderSkipIfLoggedToday) {
                val loggedToday = container.transactionRepository.hasManualTransactionForDay(todayEpochDay)
                val loggedOriginDay = isSnooze &&
                    originEpochDay != todayEpochDay &&
                    container.transactionRepository.hasManualTransactionForDay(originEpochDay)
                if (loggedToday || loggedOriginDay) {
                    return@withContext Result.success()
                }
            }

            if (isSnooze) {
                val nextRegularTime = DailyLogReminderCalculator.computeNextTriggerDateTime(
                    now = nowZoned,
                    hour = prefs.dailyLogReminderHour,
                    minute = prefs.dailyLogReminderMinute,
                    daysMask = prefs.dailyLogReminderDaysMask
                )
                if (DailyLogReminderCalculator.shouldSkipSnoozeNearNextRegularRun(
                        snoozeFireTime = nowZoned,
                        nextRegularTime = nextRegularTime
                    )
                ) {
                    return@withContext Result.success()
                }
            }

            FolioNotificationManager(applicationContext).postDailyLogReminder(
                customMessage = prefs.dailyLogReminderMessage,
                snoozeMinutes = prefs.dailyLogReminderSnoozeMinutes,
                originEpochDay = originEpochDay,
                snoozeCount = snoozeCount,
                hideAmountsOnLockScreen = prefs.hideAmountsOnLockScreen
            )

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
