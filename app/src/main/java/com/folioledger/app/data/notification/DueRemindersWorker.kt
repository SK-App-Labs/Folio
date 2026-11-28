/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/data/notification/DueRemindersWorker.kt
package com.folioledger.app.data.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.folioledger.app.FolioApplication
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.domain.util.DueRemindersEvaluator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

class DueRemindersWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
    }

    private fun scheduleNextRun(prefs: UserPreferences, nowZoned: ZonedDateTime) {
        val hour = if (prefs.remindersEnabled) prefs.reminderHourOfDay else 9
        val minute = if (prefs.remindersEnabled) prefs.reminderMinute else 0
        DueRemindersScheduler.scheduleNextFromWorker(
            context = applicationContext,
            hourOfDay = hour,
            minute = minute,
            referenceTime = nowZoned,
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? FolioApplication ?: return@withContext Result.failure()
        val container = app.container
        var latestPrefs: UserPreferences? = null

        try {
            val prefs = container.settingsAndBackupRepository.observeUserPreferences().first()
            latestPrefs = prefs
            val nowZoned = ZonedDateTime.now(container.clock)

            if (prefs.isDemoModeActive) {
                scheduleNextRun(prefs, nowZoned)
                return@withContext Result.success()
            }

            val todayEpochDay = nowZoned.toLocalDate().toEpochDay()

            // Snapshot rules due today BEFORE auto-posting so Day-0 auto-logged notifications reflect real advances
            val preRules = container.analyticsAndBudgetRepository.observeActiveRecurringCommitments().first()
            val dueTodayBeforeRun = preRules.filter { rule ->
                rule.isActive &&
                    rule.amount.isPositive &&
                    (
                        rule.type == TransactionType.EXPENSE ||
                            rule.type == TransactionType.TRANSFER ||
                            rule.type == TransactionType.CC_SETTLEMENT
                        ) &&
                    (rule.endDateEpochDay == null || rule.nextOccurrenceEpochDay <= rule.endDateEpochDay) &&
                    rule.nextOccurrenceEpochDay == todayEpochDay
            }

            // Propagate failures so retry logic applies and never falsely notify that a bill was auto-logged
            container.analyticsAndBudgetRepository.processDueRecurringRulesOnce(todayEpochDay).getOrThrow()
            com.folioledger.app.ui.widget.FolioWidgetUpdater.refreshAllNow(applicationContext)

            // Notifications are optional: if disabled, recurring maintenance has completed successfully
            if (!prefs.remindersEnabled) {
                scheduleNextRun(prefs, nowZoned)
                return@withContext Result.success()
            }

            // Evaluate Day-0 auto-logged bills (verified to have advanced/completed AND inserted an occurrence today)
            val postRules = container.analyticsAndBudgetRepository.observeActiveRecurringCommitments().first()
            val postById = postRules.associateBy { it.id }
            val autoLoggedToday = dueTodayBeforeRun.filter { pre ->
                val post = postById[pre.id]
                val advancedOrCompleted = post == null || !post.isActive || post.nextOccurrenceEpochDay > todayEpochDay
                advancedOrCompleted &&
                    container.analyticsAndBudgetRepository.hasRecurringOccurrenceForDay(pre.id, todayEpochDay)
            }
            val upcomingRules = postRules.filter { it.nextOccurrenceEpochDay > todayEpochDay }
            val billReminders = DueRemindersEvaluator.evaluateBillReminders(
                recurringRules = autoLoggedToday + upcomingRules,
                todayEpochDay = todayEpochDay,
                leadDaysNotice = prefs.billReminderLeadDays,
            )

            // Evaluate credit card balances AFTER recurring rules have auto-posted
            val accounts = container.accountRepository.getActiveAccountsSnapshot(todayEpochDay)
            val cardReminders = DueRemindersEvaluator.evaluateCreditCardReminders(
                accounts = accounts,
                todayEpochDay = todayEpochDay,
                leadDaysNotice = prefs.ccReminderLeadDays,
                enforceOverdueRecencyWindow = true,
            )

            if (cardReminders.isNotEmpty() || billReminders.isNotEmpty()) {
                val notificationManager = FolioNotificationManager(applicationContext)
                notificationManager.postDueReminders(
                    cardReminders = cardReminders,
                    billReminders = billReminders,
                    hideAmounts = prefs.hideAmountsOnLockScreen || prefs.biometricLockEnabled,
                    grouping = prefs.numberGrouping,
                )
            }

            scheduleNextRun(prefs, nowZoned)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount >= MAX_RETRY_ATTEMPTS) {
                val nowZoned = ZonedDateTime.now(container.clock)
                val hour = if (latestPrefs?.remindersEnabled == true) latestPrefs.reminderHourOfDay else 9
                val minute = if (latestPrefs?.remindersEnabled == true) latestPrefs.reminderMinute else 0
                DueRemindersScheduler.scheduleNextFromWorker(
                    context = applicationContext,
                    hourOfDay = hour,
                    minute = minute,
                    referenceTime = nowZoned,
                )
                // Return success after enqueuing tomorrow so APPEND_OR_REPLACE never marks downstream work FAILED
                Result.success()
            } else {
                Result.retry()
            }
        }
    }
}
