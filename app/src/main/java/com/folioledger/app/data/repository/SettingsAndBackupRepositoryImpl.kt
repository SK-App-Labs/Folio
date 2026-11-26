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
import com.folioledger.app.data.local.FolioDatabase
import com.folioledger.app.domain.model.AutoBackupRetention
import com.folioledger.app.domain.model.CsvImportPreset
import com.folioledger.app.domain.model.FiscalYearType
import com.folioledger.app.domain.model.ImportCsvPreview
import com.folioledger.app.domain.model.ImportCsvResult
import com.folioledger.app.domain.model.PdfExportResult
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.domain.repository.SettingsAndBackupRepository
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex

class SettingsAndBackupRepositoryImpl(
    private val context: Context,
    private val database: FolioDatabase,
    private val userPreferencesDataSource: UserPreferencesDataSource = UserPreferencesDataSource(context)
) : SettingsAndBackupRepository {

    private val backupAndDemoMutex = Mutex()
    private val backupEngine = DatabaseBackupEngine(context, database, userPreferencesDataSource, backupAndDemoMutex)
    private val autoBackupManager = AutoBackupManager(context, database, userPreferencesDataSource, backupEngine, backupAndDemoMutex)
    private val csvEngine = CsvExportImportEngine(context, database, userPreferencesDataSource)
    private val pdfHelper = PdfStatementRepositoryHelper(context, database, userPreferencesDataSource)
    private val demoModeManager = com.folioledger.app.data.demo.DemoModeManager(context, database, userPreferencesDataSource, backupEngine, backupAndDemoMutex)

    // User Preferences
    override fun observeUserPreferences(): Flow<UserPreferences> =
        userPreferencesDataSource.observeUserPreferences()

    override suspend fun updateUserPreferences(
        transform: (UserPreferences) -> UserPreferences
    ): Result<Unit> = userPreferencesDataSource.updateUserPreferences(transform)

    // CSV Import / Export
    override suspend fun exportTransactionsToCsv(
        destinationUriString: String,
        startEpochDay: Long?,
        endEpochDay: Long?,
        accountId: Long?
    ): Result<Int> = csvEngine.exportTransactionsToCsv(destinationUriString, startEpochDay, endEpochDay, accountId)

    override suspend fun exportCsvTemplate(destinationUriString: String): Result<File?> =
        csvEngine.exportCsvTemplate(destinationUriString)

    suspend fun exportTransactionsToCsvStream(
        outputStream: OutputStream,
        startEpochDay: Long? = null,
        endEpochDay: Long? = null,
        accountId: Long? = null
    ): Int = csvEngine.exportTransactionsToCsvStream(outputStream, startEpochDay, endEpochDay, accountId)

    override suspend fun previewTransactionsCsv(
        sourceUriString: String,
        preset: CsvImportPreset
    ): Result<ImportCsvPreview> =
        csvEngine.previewTransactionsCsv(sourceUriString, preset)

    override suspend fun importTransactionsFromCsv(
        sourceUriString: String,
        skipDuplicates: Boolean,
        preset: CsvImportPreset
    ): Result<ImportCsvResult> = csvEngine.importTransactionsFromCsv(sourceUriString, skipDuplicates, preset)

    override fun cleanupCachedCsvPreview(sourceUriString: String?) {
        csvEngine.cleanupCachedCsvPreview(sourceUriString)
    }

    // Database Backup & Restore
    override suspend fun createPortableZipBackup(destinationUriString: String): Result<String> =
        backupEngine.createPortableZipBackup(destinationUriString)

    suspend fun createBackupToStream(outputStream: OutputStream): Unit =
        backupEngine.createBackupToStream(outputStream)

    override suspend fun restoreFromZipBackup(sourceUriString: String): Result<Unit> =
        backupEngine.restoreFromZipBackup(sourceUriString)

    suspend fun restoreBackupFromStream(inputStream: InputStream): Unit =
        backupEngine.restoreBackupFromStream(inputStream)

    override suspend fun clearAllData(): Result<Unit> =
        backupEngine.clearAllData()

    // Auto-Backup Retention
    override fun getLatestBackupFile(): File? =
        autoBackupManager.getLatestBackupFile()

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

    override suspend fun triggerSessionAutoBackupIfDirty(): Result<String?> =
        autoBackupManager.triggerSessionAutoBackupIfDirty()

    override fun listAutoBackupFiles(): List<File> =
        autoBackupManager.listAutoBackupFiles()

    override suspend fun setAutoBackupEnabled(enabled: Boolean): Result<Unit> =
        autoBackupManager.setAutoBackupEnabled(enabled)

    override suspend fun setAutoBackupRetention(retention: AutoBackupRetention): Result<Unit> =
        autoBackupManager.setAutoBackupRetention(retention)

    // PDF Statements
    override suspend fun generateAccountStatementPdf(
        accountId: Long,
        startEpochDay: Long,
        endEpochDay: Long,
        includeNotesAndTags: Boolean,
        includeRunningBalance: Boolean
    ): Result<PdfExportResult> = pdfHelper.generateAccountStatementPdf(
        accountId, startEpochDay, endEpochDay, includeNotesAndTags, includeRunningBalance
    )

    override suspend fun generateAnnualTaxStatementPdf(
        fiscalYearType: FiscalYearType,
        anchorYear: Int,
        customStartEpochDay: Long?,
        customEndEpochDay: Long?,
        selectedTaxTags: Set<String>
    ): Result<PdfExportResult> = pdfHelper.generateAnnualTaxStatementPdf(
        fiscalYearType, anchorYear, customStartEpochDay, customEndEpochDay, selectedTaxTags
    )

    // Demo Mode & Sandbox
    override suspend fun enterDemoMode(primaryCurrencyCode: String?): Result<Unit> =
        demoModeManager.enterDemoMode(primaryCurrencyCode)

    override suspend fun exitDemoMode(): Result<Unit> =
        demoModeManager.exitDemoMode()

    override fun isDemoStashAvailable(): Boolean =
        demoModeManager.isStashAvailable()

    override suspend fun copyExportedFileToUri(
        sourceFile: File,
        destinationUriString: String
    ): Result<Unit> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatchingNonCancellation {
            require(sourceFile.exists() && sourceFile.isFile) {
                "Exported file no longer exists on device."
            }
            StorageAccessUtils.openOutputStreamForLocation(
                context = context,
                destinationUriString = destinationUriString,
                mirrorToPublicDownloads = false
            ).use { out ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
            Unit
        }
    }

    override fun resolveDisplayName(uriString: String, fallback: String): String =
        StorageAccessUtils.resolveDisplayName(context, uriString, fallback)
}
