// File: app/src/main/java/com/folioledger/app/data/notification/DueRemindersWorker.kt
package com.folioledger.app.data.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.folioledger.app.FolioApplication
import com.folioledger.app.domain.util.DueRemindersEvaluator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate

class DueRemindersWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? FolioApplication ?: return@withContext Result.failure()
        val container = app.container

        try {
            val prefs = container.settingsAndBackupRepository.observeUserPreferences().first()
            if (prefs.isDemoModeActive) {
                return@withContext Result.success()
            }

            val todayEpochDay = LocalDate.now().toEpochDay()

            // 1. Snapshot recurring commitments before auto-processing,
            // ensuring Day 0 ("On Due Date") reminders can be evaluated before nextOccurrence is advanced.
            val billReminders = if (prefs.remindersEnabled) {
                val recurringRules = container.analyticsAndBudgetRepository.observeActiveRecurringCommitments().first()
                DueRemindersEvaluator.evaluateBillReminders(
                    recurringRules = recurringRules,
                    todayEpochDay = todayEpochDay,
                    leadDaysNotice = prefs.billReminderLeadDays
                )
            } else emptyList()

            // 2. Always process due recurring transactions in the background regardless of notification toggle
            container.analyticsAndBudgetRepository.processDueRecurringRulesOnce(todayEpochDay)
            com.folioledger.app.ui.widget.FolioWidgetUpdater.refreshAllNow(applicationContext)

            // Notifications are optional: if disabled, recurring maintenance has completed successfully
            if (!prefs.remindersEnabled) {
                return@withContext Result.success()
            }

            // 3. Evaluate credit card balances AFTER recurring rules have auto-posted,
            // so balances accurately reflect any recurring card settlements or charges processed today.
            val accounts = container.accountRepository.observeActiveAccounts(todayEpochDay).first()
            val cardReminders = DueRemindersEvaluator.evaluateCreditCardReminders(
                accounts = accounts,
                todayEpochDay = todayEpochDay,
                leadDaysNotice = prefs.ccReminderLeadDays
            )

            if (cardReminders.isNotEmpty() || billReminders.isNotEmpty()) {
                val notificationManager = FolioNotificationManager(applicationContext)
                notificationManager.postDueReminders(
                    cardReminders = cardReminders,
                    billReminders = billReminders,
                    hideAmounts = prefs.hideAmountsOnLockScreen,
                    primaryCurrencyCode = prefs.primaryCurrencyCode,
                    grouping = prefs.numberGrouping
                )
            }

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
