/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/data/demo/DemoModeManager.kt
package com.folioledger.app.data.demo

import android.content.Context
import androidx.room.withTransaction
import com.folioledger.app.data.backup.SessionChangeTracker
import com.folioledger.app.data.local.DefaultCategorySeeder
import com.folioledger.app.data.local.FolioDatabase
import com.folioledger.app.data.repository.DatabaseBackupEngine
import com.folioledger.app.data.repository.UserPreferencesDataSource
import com.folioledger.app.data.repository.runCatchingNonCancellation
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DemoModeManager(
    private val context: Context,
    private val database: FolioDatabase,
    private val userPreferences: UserPreferencesDataSource,
    private val backupEngine: DatabaseBackupEngine,
    private val backupMutex: Mutex = Mutex()
) {

    private val stashDir: File
        get() = File(context.filesDir, "demo_stash")

    private val stashFile: File
        get() = File(stashDir, "pre_demo_real_ledger.zip")

    private val legacyStashFile: File
        get() = File(File(context.noBackupFilesDir, "demo_stash"), "pre_demo_real_ledger.zip")

    fun isStashAvailable(): Boolean =
        (stashFile.exists() && stashFile.length() > 0L) ||
        (legacyStashFile.exists() && legacyStashFile.length() > 0L)

    suspend fun enterDemoMode(primaryCurrencyCode: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        backupMutex.withLock {
            runCatchingNonCancellation {
                val currentPrefs = userPreferences.observeUserPreferences().first()
                val effectiveCurrency = primaryCurrencyCode ?: currentPrefs.primaryCurrencyCode

                // If we are in normal mode and prefs say there is no active stash, any leftover file on disk is stale.
                if (shouldPurgeStaleStash(currentPrefs.isDemoModeActive, currentPrefs.hasStashedRealLedger, isStashAvailable())) {
                    stashFile.delete()
                    legacyStashFile.delete()
                }

                // 1. Only create a new stash if entering from normal mode, no valid real ledger stash exists,
                //    and the user actually has real ledger data worth restoring later.
                val shouldCreateNewStash = shouldCreateNewStash(currentPrefs.isDemoModeActive, currentPrefs.hasStashedRealLedger, isStashAvailable())
                val hasRealData = if (shouldCreateNewStash) {
                    currentPrefs.hasSeenWelcomeSheet && (
                        database.accountDao().getAllAccountsOnce().isNotEmpty() ||
                            database.transactionDao().getTotalTransactionCountOnce() > 0 ||
                            database.budgetAndRecurringDao().getAllBudgetsOnce().isNotEmpty() ||
                            database.budgetAndRecurringDao().getAllRecurringRulesOnce().isNotEmpty() ||
                            database.categoryAndTagDao().getAllTagsOnce().isNotEmpty() ||
                            database.categoryAndTagDao().getAllBookmarksOnce().isNotEmpty() ||
                            currentPrefs.secondaryCurrencies.isNotEmpty() ||
                            currentPrefs.monthlySavingsTargetMinor != 0L
                    )
                } else {
                    false
                }

                if (shouldCreateNewStash && hasRealData) {
                    // Atomic stash to filesDir/demo_stash/pre_demo_real_ledger.zip.tmp -> rename
                    stashDir.mkdirs()
                    val tempFile = File(stashDir, "pre_demo_real_ledger.zip.tmp")
                    if (tempFile.exists()) tempFile.delete()

                    FileOutputStream(tempFile).use { outStream ->
                        backupEngine.createBackupToStream(outStream)
                    }

                    if (!tempFile.exists() || tempFile.length() < 256L) {
                        tempFile.delete()
                        throw IllegalStateException("Failed to securely stash current ledger. Demo mode aborted to protect real data.")
                    }

                    if (stashFile.exists()) stashFile.delete()
                    if (legacyStashFile.exists()) legacyStashFile.delete()
                    if (!tempFile.renameTo(stashFile)) {
                        throw IllegalStateException("Failed to finalize stash archive. Demo mode aborted.")
                    }
                }

                // 2. Persist active Demo Mode and clean sandbox preferences BEFORE wiping/seeding DB
                //    so any crash or background interruption treats the workspace as isolated Demo Mode.
                val stashExists = isStashAvailable()
                userPreferences.updateUserPreferences { prefs ->
                    prefs.copy(
                        primaryCurrencyCode = effectiveCurrency,
                        hasSeenWelcomeSheet = true,
                        isDemoModeActive = true,
                        hasStashedRealLedger = stashExists,
                        monthlySavingsTargetMinor = 0L,
                        savingsGoalsJson = "{}",
                        secondaryCurrencies = emptyList(),
                        cardMilestonesJson = "{}"
                    )
                }.getOrThrow()

                // 3. Wipe SQLite tables and seed demo dataset atomically in a single transaction,
                //    rolling back cleanly via exitDemoModeLocked() on failure.
                val demoCardMilestonesJson = try {
                    database.withTransaction {
                        wipeLedgerTables()
                        DefaultCategorySeeder.seedIfEmpty(database.categoryAndTagDao())
                        DemoDataSeeder.seed(database, effectiveCurrency)
                    }
                } catch (t: Throwable) {
                    if (t !is CancellationException) {
                        runCatchingNonCancellation { exitDemoModeLocked() }
                    }
                    throw t
                }

                // 4. Persist demo card milestones and mark session clean for Auto-Backup isolation
                userPreferences.updateUserPreferences { prefs ->
                    prefs.copy(cardMilestonesJson = demoCardMilestonesJson)
                }.getOrThrow()

                SessionChangeTracker.resetDirty()
            }
        }
    }

    suspend fun exitDemoMode(): Result<Unit> = withContext(Dispatchers.IO) {
        backupMutex.withLock {
            runCatchingNonCancellation {
                exitDemoModeLocked()
            }
        }
    }

    private suspend fun exitDemoModeLocked() {
        if (isStashAvailable()) {
            val fileToRestore = if (stashFile.exists() && stashFile.length() > 0L) {
                stashFile
            } else {
                legacyStashFile
            }
            // Restore real user data from private stash
            FileInputStream(fileToRestore).use { inStream ->
                backupEngine.restoreBackupFromStream(inStream, clearDemoStash = false)
            }
            if (stashFile.exists()) stashFile.delete()
            if (legacyStashFile.exists()) legacyStashFile.delete()
            userPreferences.updateUserPreferences { prefs ->
                prefs.copy(
                    hasSeenWelcomeSheet = true,
                    isDemoModeActive = false,
                    hasStashedRealLedger = false
                )
            }.getOrThrow()
        } else {
            // No stash available, start fresh with empty ledger and default categories in a single atomic transaction
            database.withTransaction {
                wipeLedgerTables()
                DefaultCategorySeeder.seedIfEmpty(database.categoryAndTagDao())
            }
            if (stashFile.exists()) stashFile.delete()
            if (legacyStashFile.exists()) legacyStashFile.delete()
            userPreferences.updateUserPreferences { prefs ->
                prefs.copy(
                    hasSeenWelcomeSheet = true,
                    isDemoModeActive = false,
                    hasStashedRealLedger = false,
                    monthlySavingsTargetMinor = 0L,
                    savingsGoalsJson = "{}",
                    cardMilestonesJson = "{}"
                )
            }.getOrThrow()
        }
        SessionChangeTracker.resetDirty()
    }

    private suspend fun wipeLedgerTables() {
        val txDao = database.transactionDao()
        val catTagDao = database.categoryAndTagDao()
        val budgetRecurringDao = database.budgetAndRecurringDao()
        val accountDao = database.accountDao()

        txDao.deleteAllTagCrossRefs()
        txDao.deleteAllSplits()
        txDao.deleteAllTransactions()
        txDao.deleteAllInstallmentPlans()
        budgetRecurringDao.deleteAllRecurringRules()
        budgetRecurringDao.deleteAllBudgets()
        catTagDao.deleteAllBookmarks()
        catTagDao.deleteAllTags()
        catTagDao.deleteAllCategories()
        accountDao.deleteAllAccounts()
    }

    companion object {
        internal fun shouldPurgeStaleStash(isDemoModeActive: Boolean, hasStashedRealLedger: Boolean, isStashAvailable: Boolean): Boolean =
            !isDemoModeActive && !hasStashedRealLedger && isStashAvailable

        internal fun shouldCreateNewStash(isDemoModeActive: Boolean, hasStashedRealLedger: Boolean, isStashAvailable: Boolean): Boolean =
            !isDemoModeActive && !(hasStashedRealLedger && isStashAvailable)
    }
}
