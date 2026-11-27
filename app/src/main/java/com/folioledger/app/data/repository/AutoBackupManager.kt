/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.folioledger.app.data.backup.AutoBackupRetentionEngine
import com.folioledger.app.data.backup.SafBackupMirrorScheduler
import com.folioledger.app.data.backup.SessionChangeTracker
import com.folioledger.app.data.local.DefaultCategorySeeder
import com.folioledger.app.data.local.FolioDatabase
import com.folioledger.app.data.local.dao.CategoryAndTagDao
import com.folioledger.app.data.local.entity.AccountEntity
import com.folioledger.app.data.local.entity.BookmarkEntity
import com.folioledger.app.data.local.entity.BudgetEntity
import com.folioledger.app.data.local.entity.CategoryEntity
import com.folioledger.app.data.local.entity.InstallmentPlanEntity
import com.folioledger.app.data.local.entity.RecurringRuleEntity
import com.folioledger.app.data.local.entity.TagEntity
import com.folioledger.app.data.local.entity.TransactionEntity
import com.folioledger.app.data.local.entity.TransactionSplitEntity
import com.folioledger.app.data.local.entity.TransactionTagCrossRef
import com.folioledger.app.data.local.entity.TransactionWithDetails
import com.folioledger.app.data.pdf.AccountSnapshotPdfItem
import com.folioledger.app.data.pdf.AccountStatementPdfData
import com.folioledger.app.data.pdf.AnnualTaxPdfData
import com.folioledger.app.data.pdf.CategoryAggregateItem
import com.folioledger.app.data.pdf.PdfStatementEngine
import com.folioledger.app.data.pdf.TaxDeductionPdfItem
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AutoBackupRetention
import com.folioledger.app.domain.model.CategoryNode
import com.folioledger.app.domain.model.CategoryType
import com.folioledger.app.domain.model.DuplicateTransactionSample
import com.folioledger.app.domain.model.ImportCsvPreview
import com.folioledger.app.domain.model.ImportCsvResult
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.RepeatFrequency
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.SplitLineItem
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionBookmark
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.domain.repository.CategoryAndTagRepository
import com.folioledger.app.domain.repository.SettingsAndBackupRepository
import com.folioledger.app.domain.usecase.AccountingRulesEngine
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AutoBackupManager(
    private val context: Context,
    private val database: FolioDatabase,
    private val userPreferences: UserPreferencesDataSource,
    private val backupEngine: DatabaseBackupEngine,
    private val backupMutex: Mutex = Mutex()
) {

    fun getLatestBackupFile(): File? {
        val autoLatest = listAutoBackupFiles().firstOrNull()
        val manualDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "FolioBackups")
        val manualLatest = if (manualDir.exists()) {
            manualDir.listFiles { file ->
                file.isFile && (file.name.endsWith(".zip", ignoreCase = true) ||
                    file.name.endsWith(".folio", ignoreCase = true))
            }?.maxByOrNull { fileTimestampMillis(it) }
        } else null

        return when {
            autoLatest != null && manualLatest != null ->
                if (fileTimestampMillis(autoLatest) >= fileTimestampMillis(manualLatest)) autoLatest else manualLatest
            autoLatest != null -> autoLatest
            else -> manualLatest
        }
    }

    private fun fileTimestampMillis(file: File): Long =
        AutoBackupRetentionEngine.parseBackupFile(file)?.epochMillis ?: file.lastModified()

    suspend fun triggerSessionAutoBackupIfDirty(): Result<String?> =
        createAutoBackupSnapshotInternal(forceIfEmpty = false, enqueueSafMirror = true, waitForLock = false)

    internal suspend fun createSnapshotForMirrorIfNeeded(): File? {
        if (!SessionChangeTracker.isDirty()) {
            listAutoBackupFiles().firstOrNull()?.let { return it }
        }
        val path = createAutoBackupSnapshotInternal(
            forceIfEmpty = true,
            enqueueSafMirror = false,
            waitForLock = true
        ).getOrNull()
        return path?.let { File(it) }
            ?: if (!SessionChangeTracker.isDirty()) listAutoBackupFiles().firstOrNull() else null
    }

    private suspend fun createAutoBackupSnapshotInternal(
        forceIfEmpty: Boolean,
        enqueueSafMirror: Boolean,
        waitForLock: Boolean
    ): Result<String?> = withContext(Dispatchers.IO) {
        if (waitForLock) {
            backupMutex.lock()
        } else if (!backupMutex.tryLock()) {
            return@withContext Result.success(null)
        }
        try {
            runCatchingNonCancellation {
                val currentPrefs = userPreferences.observeUserPreferences().first()
                if (currentPrefs.isDemoModeActive || !currentPrefs.autoBackupEnabled) {
                    return@runCatchingNonCancellation null
                }
                val canCapture = if (forceIfEmpty) {
                    SessionChangeTracker.isDirty() || listAutoBackupFiles().isEmpty()
                } else {
                    SessionChangeTracker.canBackupNow()
                }
                if (!canCapture) {
                    return@runCatchingNonCancellation null
                }
                val snapshotVersion = SessionChangeTracker.currentVersion()

                val autoDir = File(context.filesDir, "FolioAutoBackups").apply {
                    mkdirs()
                }
                val timestampStr = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss", Locale.US)
                    .format(LocalDateTime.now())
                val fileName = "Folio_AutoBackup_${timestampStr}.zip"
                val targetFile = File(autoDir, fileName)
                val tempFile = File(autoDir, "$fileName.tmp")

                try {
                    FileOutputStream(tempFile).use { output ->
                        backupEngine.createBackupToStream(output)
                    }
                    if (!tempFile.renameTo(targetFile)) {
                        tempFile.copyTo(targetFile, overwrite = true)
                    }
                } finally {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }

                // Mirror latest auto-backup to public Downloads/Folio folder for uninstall protection
                // Gated strictly behind explicit opt-in preference, and automatically suppressed when App Lock is active
                val allowPublicMirror = currentPrefs.autoBackupPublicMirrorEnabled && !currentPrefs.biometricLockEnabled
                if (allowPublicMirror) {
                    runCatching {
                        StorageAccessUtils.openDownloadsMirrorStream(context, "Folio_Latest_AutoBackup.zip")?.use { mirrorOut ->
                            FileInputStream(targetFile).use { it.copyTo(mirrorOut) }
                        }
                    }
                }

                // Apply Tiered Retention Pruning
                AutoBackupRetentionEngine.pruneDirectory(autoDir, currentPrefs.autoBackupRetention)
                val noBackupDir = File(context.noBackupFilesDir, "FolioAutoBackups")
                if (noBackupDir.exists() && noBackupDir.isDirectory && noBackupDir != autoDir) {
                    AutoBackupRetentionEngine.pruneDirectory(noBackupDir, currentPrefs.autoBackupRetention)
                }
                val legacyDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "FolioAutoBackups")
                if (legacyDir.exists() && legacyDir.isDirectory && legacyDir != autoDir && legacyDir != noBackupDir) {
                    AutoBackupRetentionEngine.pruneDirectory(legacyDir, currentPrefs.autoBackupRetention)
                }

                // Update in-memory and DataStore recency indicator
                userPreferences.updateUserPreferences { it.copy(lastBackupEpochMillis = System.currentTimeMillis()) }
                SessionChangeTracker.recordBackupFinished(snapshotVersion)
                database.checkpointDatabase()

                if (enqueueSafMirror &&
                    currentPrefs.safMirrorEnabled &&
                    !currentPrefs.safMirrorTreeUri.isNullOrBlank() &&
                    (!currentPrefs.biometricLockEnabled || currentPrefs.safMirrorEncryptEnabled)
                ) {
                    SafBackupMirrorScheduler.enqueueImmediateMirror(context, targetFile.absolutePath)
                }

                targetFile.absolutePath
            }
        } finally {
            backupMutex.unlock()
        }
    }

    fun listAutoBackupFiles(): List<File> {
        val primaryDir = File(context.filesDir, "FolioAutoBackups")
        val noBackupDir = File(context.noBackupFilesDir, "FolioAutoBackups")
        val legacyDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "FolioAutoBackups")

        fun readDir(dir: File): List<File> =
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles { file ->
                    file.isFile &&
                        file.name.startsWith("Folio_AutoBackup_") &&
                        (file.name.endsWith(".zip", ignoreCase = true) ||
                            file.name.endsWith(".folio", ignoreCase = true))
                }?.toList() ?: emptyList()
            } else emptyList()

        val primaryFiles = readDir(primaryDir)
        val noBackupFiles = readDir(noBackupDir)
        val legacyFiles = if (legacyDir != primaryDir && legacyDir != noBackupDir) readDir(legacyDir) else emptyList()

        return (primaryFiles + noBackupFiles + legacyFiles)
            .distinctBy { it.name }
            .sortedByDescending { fileTimestampMillis(it) }
    }

    suspend fun setAutoBackupEnabled(enabled: Boolean): Result<Unit> {
        val result = userPreferences.updateUserPreferences { it.copy(autoBackupEnabled = enabled) }
        if (result.isSuccess) {
            val prefs = userPreferences.observeUserPreferences().first()
            SafBackupMirrorScheduler.syncPeriodicSchedule(context, prefs)
        }
        return result
    }

    suspend fun setAutoBackupRetention(retention: AutoBackupRetention): Result<Unit> =
        userPreferences.updateUserPreferences { it.copy(autoBackupRetention = retention) }

}
