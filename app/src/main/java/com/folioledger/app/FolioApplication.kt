// File: app/src/main/java/com/folioledger/app/FolioApplication.kt
package com.folioledger.app

import android.app.Application
import androidx.room.InvalidationTracker
import androidx.work.ExistingPeriodicWorkPolicy
import com.folioledger.app.data.backup.SessionChangeTracker
import com.folioledger.app.data.notification.DueRemindersScheduler
import com.folioledger.app.data.notification.FolioNotificationManager
import com.folioledger.app.di.AppContainer
import com.folioledger.app.ui.widget.FolioWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class FolioApplication : Application() {
    lateinit var container: AppContainer
        private set

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Initialize notification channels
        FolioNotificationManager.createNotificationChannels(this)

        // Attach Room InvalidationTracker off the main thread to avoid blocking cold start
        applicationScope.launch(Dispatchers.IO) {
            container.database.invalidationTracker.addObserver(
                object : InvalidationTracker.Observer(
                    "accounts",
                    "categories",
                    "transactions",
                    "transaction_splits",
                    "tags",
                    "budgets",
                    "recurring_rules",
                    "bookmarks",
                    "installment_plans",
                    "transaction_tags"
                ) {
                    override fun onInvalidated(tables: Set<String>) {
                        SessionChangeTracker.markDirty()
                        FolioWidgetUpdater.scheduleDebouncedRefresh(this@FolioApplication, applicationScope)
                    }
                }
            )
        }

        // Keep widgets in sync with preferences changes (currency, number grouping, privacy mask)
        applicationScope.launch(Dispatchers.IO) {
            container.settingsAndBackupRepository.observeUserPreferences()
                .map { p ->
                    listOf(
                        p.primaryCurrencyCode,
                        p.secondaryCurrencies,
                        p.numberGrouping,
                        p.monthStartDay,
                        p.isDemoModeActive,
                        p.biometricLockEnabled,
                        p.hideAmountsOnLockScreen,
                        p.ccReminderLeadDays,
                        p.themeMode,
                        p.accentPalette,
                        p.dynamicColorEnabled
                    )
                }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    FolioWidgetUpdater.scheduleDebouncedRefresh(this@FolioApplication, applicationScope)
                }
        }

        // Automatically process due recurring commitments on launch & ensure worker is scheduled if enabled
        applicationScope.launch {
            try {
                val prefs = container.settingsAndBackupRepository.observeUserPreferences().first()
                if (!prefs.isDemoModeActive) {
                    container.analyticsAndBudgetRepository.processDueRecurringRulesOnce(
                        todayEpochDay = java.time.LocalDate.now(container.clock).toEpochDay()
                    )
                }

                val hour = if (prefs.remindersEnabled) prefs.reminderHourOfDay else 9
                val minute = if (prefs.remindersEnabled) prefs.reminderMinute else 0
                DueRemindersScheduler.scheduleDailyReminders(
                    context = this@FolioApplication,
                    hourOfDay = hour,
                    minute = minute,
                    existingWorkPolicy = ExistingPeriodicWorkPolicy.KEEP
                )
            } catch (e: Exception) {
                android.util.Log.e("FolioApplication", "Failed to initialize recurring maintenance on startup", e)
            }
        }
    }
}