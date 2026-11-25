/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class FolioApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("FolioApplication", "Unhandled exception in applicationScope", throwable)
    }

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + appExceptionHandler)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, applicationScope = applicationScope)

        // Initialize notification channels
        FolioNotificationManager.createNotificationChannels(this)

        // Clean up stale safety backup or temporary share-sheet exports left in cache
        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                java.io.File(cacheDir, "pre_restore_safety_backup.zip").takeIf { it.exists() }?.delete()
                val staleThresholdMs = System.currentTimeMillis() - 3_600_000L
                java.io.File(cacheDir, "shared_exports").listFiles()?.forEach { file ->
                    if (file.isFile && file.lastModified() < staleThresholdMs) {
                        file.delete()
                    }
                }
            }
        }

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
                .catch { e ->
                    android.util.Log.e("FolioApplication", "Failed observing user preferences for widget sync", e)
                }
                .collect {
                    FolioWidgetUpdater.scheduleDebouncedRefresh(this@FolioApplication, applicationScope)
                }
        }

        // Automatically process due recurring commitments on launch & ensure worker is scheduled if enabled
        applicationScope.launch(Dispatchers.IO) {
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
                if (e is CancellationException) throw e
                android.util.Log.e("FolioApplication", "Failed to initialize recurring maintenance on startup", e)
            }
        }
    }
}