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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class DemoModeManager(
    private val context: Context,
    private val database: FolioDatabase,
    private val userPreferences: UserPreferencesDataSource,
    private val backupEngine: DatabaseBackupEngine
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
        runCatchingNonCancellation {
            val currentPrefs = userPreferences.observeUserPreferences().first()
            val effectiveCurrency = primaryCurrencyCode ?: currentPrefs.primaryCurrencyCode

            // If we are in normal mode and prefs say there is no active stash, any leftover file on disk is stale.
            if (!currentPrefs.isDemoModeActive && !currentPrefs.hasStashedRealLedger && isStashAvailable()) {
                stashFile.delete()
                legacyStashFile.delete()
            }

            // 1. Only create a new stash if entering from normal mode and no valid real ledger stash exists
            val shouldCreateNewStash = !currentPrefs.isDemoModeActive &&
                    !(currentPrefs.hasStashedRealLedger && isStashAvailable())

            if (shouldCreateNewStash) {
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

                // Immediately persist stash flag so that any crash/kill before Step 3 cannot overwrite this stash
                userPreferences.updateUserPreferences { it.copy(hasStashedRealLedger = true) }.getOrThrow()
            }

            // 2. Wipe SQLite tables and seed demo dataset atomically in a single transaction
            val demoCardMilestonesJson = database.withTransaction {
                wipeLedgerTables()
                DefaultCategorySeeder.seedIfEmpty(database.categoryAndTagDao())
                DemoDataSeeder.seed(database, effectiveCurrency)
            }

            // 3. Update preferences
            userPreferences.updateUserPreferences { prefs ->
                prefs.copy(
                    primaryCurrencyCode = effectiveCurrency,
                    hasSeenWelcomeSheet = true,
                    isDemoModeActive = true,
                    hasStashedRealLedger = isStashAvailable() || prefs.hasStashedRealLedger,
                    cardMilestonesJson = demoCardMilestonesJson
                )
            }.getOrThrow()

            SessionChangeTracker.resetDirty()
        }
    }

    suspend fun exitDemoMode(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            val currentPrefs = userPreferences.observeUserPreferences().first()
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
                        cardMilestonesJson = "{}"
                    )
                }.getOrThrow()
            }
        }
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
}
