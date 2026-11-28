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
import com.folioledger.app.data.backup.AutoMirrorKeyVault
import com.folioledger.app.data.backup.SafBackupMirrorEngine
import com.folioledger.app.data.backup.SafBackupMirrorScheduler
import com.folioledger.app.data.local.FolioDatabase
import com.folioledger.app.domain.model.AutoBackupRetention
import com.folioledger.app.domain.model.BackupInspectionResult
import com.folioledger.app.domain.model.CsvImportPreset
import com.folioledger.app.domain.model.FiscalYearType
import com.folioledger.app.domain.model.ImportCsvPreview
import com.folioledger.app.domain.model.ImportCsvResult
import com.folioledger.app.domain.model.PdfExportResult
import com.folioledger.app.domain.model.SafMirrorOutcome
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.domain.repository.SettingsAndBackupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class SettingsAndBackupRepositoryImpl(
    private val context: Context,
    private val database: FolioDatabase,
    private val userPreferencesDataSource: UserPreferencesDataSource = UserPreferencesDataSource(context),
) : SettingsAndBackupRepository {

    private val backupAndDemoMutex = Mutex()
    private val safMirrorMutex = Mutex()
    private val keyVault = AutoMirrorKeyVault(context)
    private val backupEngine = DatabaseBackupEngine(context, database, userPreferencesDataSource, backupAndDemoMutex)
    private val autoBackupManager = AutoBackupManager(context, database, userPreferencesDataSource, backupEngine, backupAndDemoMutex)
    private val csvEngine = CsvExportImportEngine(context, database, userPreferencesDataSource)
    private val pdfHelper = PdfStatementRepositoryHelper(context, database, userPreferencesDataSource)
    private val demoModeManager = com.folioledger.app.data.demo.DemoModeManager(context, database, userPreferencesDataSource, backupEngine, backupAndDemoMutex)

    // User Preferences
    override fun observeUserPreferences(): Flow<UserPreferences> = userPreferencesDataSource.observeUserPreferences()

    override suspend fun updateUserPreferences(
        transform: (UserPreferences) -> UserPreferences,
    ): Result<Unit> {
        var before: UserPreferences? = null
        val result = userPreferencesDataSource.updateUserPreferences { current ->
            before = current
            transform(current)
        }
        if (result.isSuccess) {
            val updated = userPreferencesDataSource.observeUserPreferences().first()
            val prev = before
            val safChanged = prev == null ||
                prev.autoBackupEnabled != updated.autoBackupEnabled ||
                prev.safMirrorEnabled != updated.safMirrorEnabled ||
                prev.safMirrorTreeUri != updated.safMirrorTreeUri ||
                prev.biometricLockEnabled != updated.biometricLockEnabled ||
                prev.safMirrorEncryptEnabled != updated.safMirrorEncryptEnabled ||
                prev.isDemoModeActive != updated.isDemoModeActive
            if (safChanged) {
                SafBackupMirrorScheduler.syncPeriodicSchedule(context, updated)
                if (!updated.safMirrorEnabled) {
                    SafBackupMirrorScheduler.cancelAll(context)
                }
            }
        }
        return result
    }

    // CSV Import / Export
    override suspend fun exportTransactionsToCsv(
        destinationUriString: String,
        startEpochDay: Long?,
        endEpochDay: Long?,
        accountId: Long?,
    ): Result<Int> = csvEngine.exportTransactionsToCsv(destinationUriString, startEpochDay, endEpochDay, accountId)

    override suspend fun exportCsvTemplate(destinationUriString: String): Result<File?> = csvEngine.exportCsvTemplate(destinationUriString)

    suspend fun exportTransactionsToCsvStream(
        outputStream: OutputStream,
        startEpochDay: Long? = null,
        endEpochDay: Long? = null,
        accountId: Long? = null,
    ): Int = csvEngine.exportTransactionsToCsvStream(outputStream, startEpochDay, endEpochDay, accountId)

    override suspend fun previewTransactionsCsv(
        sourceUriString: String,
        preset: CsvImportPreset,
    ): Result<ImportCsvPreview> = csvEngine.previewTransactionsCsv(sourceUriString, preset)

    override suspend fun importTransactionsFromCsv(
        sourceUriString: String,
        skipDuplicates: Boolean,
        preset: CsvImportPreset,
    ): Result<ImportCsvResult> = csvEngine.importTransactionsFromCsv(sourceUriString, skipDuplicates, preset)

    override fun cleanupCachedCsvPreview(sourceUriString: String?) {
        csvEngine.cleanupCachedCsvPreview(sourceUriString)
    }

    // Database Backup & Restore
    override suspend fun inspectBackup(sourceUriString: String): Result<BackupInspectionResult> = backupEngine.inspectBackup(sourceUriString)

    override suspend fun createPortableZipBackup(destinationUriString: String): Result<String> = backupEngine.createPortableZipBackup(destinationUriString)

    override suspend fun createPortableBackup(
        destinationUriString: String,
        password: CharArray?,
    ): Result<String> = backupEngine.createPortableZipBackup(destinationUriString, password)

    suspend fun createBackupToStream(outputStream: OutputStream): Unit = backupEngine.createBackupToStream(outputStream)

    override suspend fun restoreFromZipBackup(sourceUriString: String): Result<Unit> = backupEngine.restoreFromZipBackup(sourceUriString)

    override suspend fun restoreFromBackup(
        sourceUriString: String,
        password: CharArray?,
    ): Result<Unit> = backupEngine.restoreFromZipBackup(sourceUriString, password)

    suspend fun restoreBackupFromStream(inputStream: InputStream): Unit = backupEngine.restoreBackupFromStream(inputStream)

    override suspend fun clearAllData(): Result<Unit> = backupEngine.clearAllData()

    override suspend fun configureSafMirrorFolder(
        treeUriString: String,
        displayName: String?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        safMirrorMutex.withLock {
            runCatchingNonCancellation {
                val resolvedName = displayName?.takeIf { it.isNotBlank() }
                    ?: StorageAccessUtils.resolveTreeDisplayName(context, Uri.parse(treeUriString))
                var previousUri: String? = null
                userPreferencesDataSource.updateUserPreferences { current ->
                    previousUri = current.safMirrorTreeUri
                    val hasKey = keyVault.hasConfiguredKey()
                    val effectiveEncrypt = current.safMirrorEncryptEnabled && hasKey
                    val canEnableNow = !current.biometricLockEnabled || effectiveEncrypt
                    current.copy(
                        safMirrorTreeUri = treeUriString,
                        safMirrorFolderDisplayName = resolvedName,
                        safMirrorEncryptEnabled = effectiveEncrypt,
                        safMirrorEnabled = canEnableNow,
                        safMirrorErrorCode = if (current.safMirrorEncryptEnabled && !hasKey) "KEY_UNAVAILABLE" else null,
                    )
                }.getOrThrow()

                if (!previousUri.isNullOrBlank() && previousUri != treeUriString) {
                    StorageAccessUtils.releasePersistableTreePermission(context, previousUri)
                }
                val latestPrefs = userPreferencesDataSource.observeUserPreferences().first()
                SafBackupMirrorScheduler.syncPeriodicSchedule(context, latestPrefs)
                if (latestPrefs.safMirrorEnabled) {
                    SafBackupMirrorScheduler.enqueueImmediateMirror(context)
                }
                Unit
            }
        }
    }

    override suspend fun disconnectSafMirrorFolder(): Result<Unit> = withContext(Dispatchers.IO) {
        safMirrorMutex.withLock {
            runCatchingNonCancellation {
                SafBackupMirrorScheduler.cancelAll(context)
                var previousUri: String? = null
                userPreferencesDataSource.updateUserPreferences { current ->
                    previousUri = current.safMirrorTreeUri
                    current.copy(
                        safMirrorEnabled = false,
                        safMirrorTreeUri = null,
                        safMirrorFolderDisplayName = null,
                        safMirrorErrorCode = null,
                    )
                }.getOrThrow()
                StorageAccessUtils.releasePersistableTreePermission(context, previousUri)
                Unit
            }
        }
    }

    override suspend fun setSafMirrorEnabled(enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        safMirrorMutex.withLock {
            runCatchingNonCancellation {
                if (enabled) {
                    val current = userPreferencesDataSource.observeUserPreferences().first()
                    require(!current.safMirrorTreeUri.isNullOrBlank()) {
                        "Select an external folder before enabling mirroring."
                    }
                    if (current.biometricLockEnabled) {
                        require(current.safMirrorEncryptEnabled && keyVault.hasConfiguredKey()) {
                            "Set an encryption passphrase to enable external mirroring while App Lock is active."
                        }
                    }
                }
                userPreferencesDataSource.updateUserPreferences { current ->
                    current.copy(
                        safMirrorEnabled = enabled,
                        safMirrorErrorCode = if (enabled) null else current.safMirrorErrorCode,
                    )
                }.getOrThrow()
                val updated = userPreferencesDataSource.observeUserPreferences().first()
                SafBackupMirrorScheduler.syncPeriodicSchedule(context, updated)
                if (updated.safMirrorEnabled) {
                    SafBackupMirrorScheduler.enqueueImmediateMirror(context)
                } else {
                    SafBackupMirrorScheduler.cancelAll(context)
                }
                Unit
            }
        }
    }

    override suspend fun enableSafMirrorEncryption(passphrase: CharArray): Result<Unit> = withContext(Dispatchers.IO) {
        safMirrorMutex.withLock {
            runCatchingNonCancellation {
                try {
                    keyVault.configurePassphrase(passphrase)
                } finally {
                    passphrase.fill('\u0000')
                }
                runCatchingNonCancellation {
                    userPreferencesDataSource.updateUserPreferences { current ->
                        val autoActivate = current.biometricLockEnabled && !current.safMirrorTreeUri.isNullOrBlank()
                        current.copy(
                            safMirrorEncryptEnabled = true,
                            safMirrorEnabled = current.safMirrorEnabled || autoActivate,
                            safMirrorErrorCode = null,
                        )
                    }.getOrThrow()
                }.onFailure {
                    keyVault.clear()
                }.getOrThrow()

                val updated = userPreferencesDataSource.observeUserPreferences().first()
                SafBackupMirrorScheduler.syncPeriodicSchedule(context, updated)
                if (updated.safMirrorEnabled) {
                    SafBackupMirrorScheduler.enqueueImmediateMirror(context, forceOverwrite = true)
                }
                Unit
            }
        }
    }

    override suspend fun disableSafMirrorEncryption(): Result<Unit> = withContext(Dispatchers.IO) {
        safMirrorMutex.withLock {
            runCatchingNonCancellation {
                userPreferencesDataSource.updateUserPreferences { current ->
                    current.copy(
                        safMirrorEncryptEnabled = false,
                        safMirrorEnabled = if (current.biometricLockEnabled) false else current.safMirrorEnabled,
                        safMirrorErrorCode = null,
                    )
                }.getOrThrow()
                keyVault.clear()
                val updated = userPreferencesDataSource.observeUserPreferences().first()
                SafBackupMirrorScheduler.syncPeriodicSchedule(context, updated)
                if (!updated.safMirrorEnabled) {
                    SafBackupMirrorScheduler.cancelAll(context)
                }
                Unit
            }
        }
    }

    override suspend fun triggerSafMirrorNow(): Result<SafMirrorOutcome> = triggerSafMirrorNowInternal(forceOverwrite = true)

    internal suspend fun triggerSafMirrorNowInternal(
        forceOverwrite: Boolean = false,
    ): Result<SafMirrorOutcome> = withContext(Dispatchers.IO) {
        safMirrorMutex.withLock {
            runCatchingNonCancellation {
                val prefs = userPreferencesDataSource.observeUserPreferences().first()
                if (prefs.isDemoModeActive ||
                    !prefs.autoBackupEnabled ||
                    !prefs.safMirrorEnabled ||
                    prefs.safMirrorTreeUri.isNullOrBlank()
                ) {
                    return@runCatchingNonCancellation SafMirrorOutcome.Skipped
                }
                if (prefs.biometricLockEnabled && !prefs.safMirrorEncryptEnabled) {
                    return@runCatchingNonCancellation SafMirrorOutcome.Skipped
                }

                val snapshotFile = autoBackupManager.createSnapshotForMirrorIfNeeded()
                    ?: return@runCatchingNonCancellation SafMirrorOutcome.Skipped

                val outcome = SafBackupMirrorEngine.mirrorSnapshotToSaf(
                    context = context,
                    snapshotFile = snapshotFile,
                    treeUriString = prefs.safMirrorTreeUri,
                    retention = prefs.autoBackupRetention,
                    encryptEnabled = prefs.safMirrorEncryptEnabled,
                    keyVault = keyVault,
                    forceOverwrite = forceOverwrite,
                )

                when (outcome) {
                    is SafMirrorOutcome.Success -> {
                        userPreferencesDataSource.updateUserPreferences {
                            it.copy(
                                lastSafMirrorEpochMillis = System.currentTimeMillis(),
                                safMirrorErrorCode = null,
                            )
                        }
                    }

                    SafMirrorOutcome.PermissionLost -> {
                        userPreferencesDataSource.updateUserPreferences {
                            it.copy(safMirrorErrorCode = "PERMISSION_LOST")
                        }
                    }

                    SafMirrorOutcome.KeyUnavailable -> {
                        userPreferencesDataSource.updateUserPreferences {
                            it.copy(safMirrorErrorCode = "KEY_UNAVAILABLE")
                        }
                    }

                    is SafMirrorOutcome.IoFailure -> {
                        userPreferencesDataSource.updateUserPreferences {
                            it.copy(safMirrorErrorCode = "IO_ERROR")
                        }
                    }

                    SafMirrorOutcome.Skipped -> Unit
                }
                outcome
            }
        }
    }

    // Auto-Backup Retention
    override fun getLatestBackupFile(): File? = autoBackupManager.getLatestBackupFile()

    override fun getExportedFile(fileName: String): File? {
        val cleanFileName = fileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        if (cleanFileName.isEmpty() || cleanFileName == "." || cleanFileName == ".." || ".." in cleanFileName) {
            return null
        }
        val internalDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "FolioBackups")
        val file = File(internalDir, cleanFileName)
        val isInsideDir = runCatching {
            file.canonicalFile.parentFile == internalDir.canonicalFile
        }.getOrDefault(false)
        return if (isInsideDir && file.exists() && file.isFile) file else null
    }

    override suspend fun triggerSessionAutoBackupIfDirty(): Result<String?> = autoBackupManager.triggerSessionAutoBackupIfDirty()

    override fun listAutoBackupFiles(): List<File> = autoBackupManager.listAutoBackupFiles()

    override suspend fun setAutoBackupEnabled(enabled: Boolean): Result<Unit> = autoBackupManager.setAutoBackupEnabled(enabled)

    override suspend fun setAutoBackupRetention(retention: AutoBackupRetention): Result<Unit> = autoBackupManager.setAutoBackupRetention(retention)

    // PDF Statements
    override suspend fun generateAccountStatementPdf(
        accountId: Long,
        startEpochDay: Long,
        endEpochDay: Long,
        includeNotesAndTags: Boolean,
        includeRunningBalance: Boolean,
    ): Result<PdfExportResult> = pdfHelper.generateAccountStatementPdf(
        accountId,
        startEpochDay,
        endEpochDay,
        includeNotesAndTags,
        includeRunningBalance,
    )

    override suspend fun generateAnnualTaxStatementPdf(
        fiscalYearType: FiscalYearType,
        anchorYear: Int,
        customStartEpochDay: Long?,
        customEndEpochDay: Long?,
        selectedTaxTags: Set<String>,
    ): Result<PdfExportResult> = pdfHelper.generateAnnualTaxStatementPdf(
        fiscalYearType,
        anchorYear,
        customStartEpochDay,
        customEndEpochDay,
        selectedTaxTags,
    )

    // Demo Mode & Sandbox
    override suspend fun enterDemoMode(primaryCurrencyCode: String?): Result<Unit> = demoModeManager.enterDemoMode(primaryCurrencyCode)

    override suspend fun exitDemoMode(): Result<Unit> = demoModeManager.exitDemoMode()

    override fun isDemoStashAvailable(): Boolean = demoModeManager.isStashAvailable()

    override suspend fun copyExportedFileToUri(
        sourceFile: File,
        destinationUriString: String,
    ): Result<Unit> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatchingNonCancellation {
            require(sourceFile.exists() && sourceFile.isFile) {
                "Exported file no longer exists on device."
            }
            StorageAccessUtils.openOutputStreamForLocation(
                context = context,
                destinationUriString = destinationUriString,
                mirrorToPublicDownloads = false,
            ).use { out ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
            Unit
        }
    }

    override fun resolveDisplayName(uriString: String, fallback: String): String = StorageAccessUtils.resolveDisplayName(context, uriString, fallback)
}
