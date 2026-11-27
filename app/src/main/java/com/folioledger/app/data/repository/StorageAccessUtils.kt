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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal object StorageAccessUtils {
    fun resolveDisplayName(context: Context? = null, uriString: String, fallback: String): String {
        if (uriString.startsWith("content://") && context != null) {
            val uri = Uri.parse(uriString)
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        }
        val clean = uriString
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        val isOpaqueId = clean.all { it.isDigit() } || clean.contains("%3A") || clean.contains(":")
        return if (clean.isNotBlank() && !isOpaqueId) {
            clean
        } else {
            fallback
        }
    }

    internal fun isPathConfinedToRoots(targetFile: File, roots: List<File?>): Boolean {
        val canonicalPath = runCatching { targetFile.canonicalPath }.getOrNull() ?: return false
        return roots.mapNotNull { root ->
            runCatching { root?.canonicalPath }.getOrNull()?.trimEnd(File.separatorChar)?.plus(File.separator)
        }.any { rootPrefix ->
            canonicalPath.startsWith(rootPrefix)
        }
    }

    private fun folioDownloadsRelativePath(): String =
        "${android.os.Environment.DIRECTORY_DOWNLOADS}/Folio/"

    private fun folioDownloadsRelPathSqlPredicate(): String =
        "(${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} = ? " +
            "OR ${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} = ?)"

    private fun folioDownloadsRelPathSqlArgs(): Array<String> {
        val withSlash = folioDownloadsRelativePath()
        return arrayOf(withSlash, withSlash.trimEnd('/'))
    }

    internal fun splitBaseAndExtension(fileName: String): Pair<String, String> {
        val dotIdx = fileName.lastIndexOf('.')
        return if (dotIdx > 0) {
            fileName.substring(0, dotIdx) to fileName.substring(dotIdx)
        } else {
            fileName to ""
        }
    }

    private fun openPendingDownloadsMirrorStream(
        context: Context,
        cleanFileName: String,
        mimeType: String
    ): OutputStream? {
        if (cleanFileName.isBlank() || cleanFileName == "." || cleanFileName == ".." || ".." in cleanFileName) {
            return null
        }
        return try {
            val stagingName = "$cleanFileName.tmp"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, stagingName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    folioDownloadsRelativePath()
                )
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: return null
            val rawOut = context.contentResolver.openOutputStream(uri, "wt")
            if (rawOut == null) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                return null
            }
            object : OutputStream() {
                private var hasWriteError = false
                private var closed = false

                override fun write(b: Int) {
                    try {
                        rawOut.write(b)
                    } catch (e: Throwable) {
                        hasWriteError = true
                        throw e
                    }
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    try {
                        rawOut.write(b, off, len)
                    } catch (e: Throwable) {
                        hasWriteError = true
                        throw e
                    }
                }

                override fun flush() {
                    try {
                        rawOut.flush()
                    } catch (e: Throwable) {
                        hasWriteError = true
                        throw e
                    }
                }

                override fun close() {
                    if (closed) return
                    closed = true
                    try {
                        rawOut.close()
                    } catch (e: Throwable) {
                        hasWriteError = true
                        runCatching { context.contentResolver.delete(uri, null, null) }
                        throw e
                    }
                    if (hasWriteError) {
                        runCatching { context.contentResolver.delete(uri, null, null) }
                        return
                    }
                    // Delete prior canonical & auto-suffixed copies first, then rename staged item atomically
                    val newId = runCatching { android.content.ContentUris.parseId(uri) }.getOrDefault(-1L)
                    val (baseName, ext) = splitBaseAndExtension(cleanFileName)
                    val suffixedPattern = "$baseName (%)$ext"
                    runCatching {
                        val deleteSelection = "(${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ? " +
                            "OR ${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?) " +
                            "AND ${folioDownloadsRelPathSqlPredicate()} " +
                            "AND ${android.provider.MediaStore.MediaColumns._ID} != ?"
                        val deleteArgs = arrayOf(
                            cleanFileName,
                            suffixedPattern,
                            *folioDownloadsRelPathSqlArgs(),
                            newId.toString()
                        )
                        context.contentResolver.delete(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            deleteSelection,
                            deleteArgs
                        )
                    }
                    runCatching {
                        val finalizeValues = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, cleanFileName)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                            }
                        }
                        context.contentResolver.update(uri, finalizeValues, null, null)
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun openOutputStreamForLocation(
        context: Context,
        destinationUriString: String,
        mirrorToPublicDownloads: Boolean = true
    ): OutputStream {
        if (destinationUriString.startsWith("content://")) {
            val uri = Uri.parse(destinationUriString)
            return context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Unable to open OutputStream for URI: $destinationUriString")
        }

        if (destinationUriString.startsWith("file://")) {
            val rawPath = Uri.parse(destinationUriString).path
                ?: throw SecurityException("Invalid file URI: $destinationUriString")
            val targetFile = File(rawPath)
            val baseExt = context.getExternalFilesDir(null)
            val allowedExportRoots = listOf(
                File(baseExt ?: context.filesDir, "FolioBackups"),
                File(context.filesDir, "FolioAutoBackups"),
                File(context.noBackupFilesDir, "FolioAutoBackups"),
                File(baseExt ?: context.filesDir, "FolioStatements"),
                context.cacheDir
            )
            if (!isPathConfinedToRoots(targetFile, allowedExportRoots)) {
                throw SecurityException("Access denied to write outside Folio export directories.")
            }
            targetFile.parentFile?.mkdirs()
            return FileOutputStream(targetFile)
        }

        val cleanFileName = destinationUriString
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
            .takeIf { it != "." && it != ".." && ".." !in it && it.isNotBlank() }
            ?: "Folio_Export_${System.currentTimeMillis()}.dat"

        val mimeType = when {
            cleanFileName.endsWith(".zip", ignoreCase = true) -> "application/zip"
            cleanFileName.endsWith(".folio", ignoreCase = true) -> "application/octet-stream"
            cleanFileName.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
            else -> "text/csv"
        }

        // Always keep an internal copy in app-specific storage so 1-tap Restore always works
        val internalDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "FolioBackups").apply {
            mkdirs()
        }
        val internalFile = File(internalDir, cleanFileName)
        val isInsideDir = runCatching {
            internalFile.canonicalFile.parentFile == internalDir.canonicalFile
        }.getOrDefault(false)
        if (!isInsideDir) {
            throw SecurityException("Invalid export destination path: $cleanFileName")
        }

        val internalStream = FileOutputStream(internalFile)

        // Optionally mirror into public Downloads/Folio folder via MediaStore (API 29+)
        val downloadsStream: OutputStream? = if (mirrorToPublicDownloads) {
            openPendingDownloadsMirrorStream(context, cleanFileName, mimeType)
        } else {
            null
        }

        return if (downloadsStream != null) {
            object : OutputStream() {
                private var mirrorFailed = false

                override fun write(b: Int) {
                    internalStream.write(b)
                    if (!mirrorFailed) {
                        runCatching { downloadsStream.write(b) }.onFailure { mirrorFailed = true }
                    }
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    internalStream.write(b, off, len)
                    if (!mirrorFailed) {
                        runCatching { downloadsStream.write(b, off, len) }.onFailure { mirrorFailed = true }
                    }
                }

                override fun flush() {
                    internalStream.flush()
                    if (!mirrorFailed) {
                        runCatching { downloadsStream.flush() }.onFailure { mirrorFailed = true }
                    }
                }

                override fun close() {
                    try {
                        internalStream.close()
                    } finally {
                        runCatching { downloadsStream.close() }
                    }
                }
            }
        } else {
            internalStream
        }
    }

    fun openDownloadsMirrorStream(context: Context, fileName: String): OutputStream? {
        val cleanFileName = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        if (cleanFileName.isBlank() || cleanFileName == "." || cleanFileName == ".." || ".." in cleanFileName) {
            return null
        }
        val mimeType = when {
            cleanFileName.endsWith(".zip", ignoreCase = true) -> "application/zip"
            cleanFileName.endsWith(".folio", ignoreCase = true) -> "application/octet-stream"
            cleanFileName.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
            else -> "text/csv"
        }
        return openPendingDownloadsMirrorStream(context, cleanFileName, mimeType)
    }

    fun resolveTreeDisplayName(context: Context, treeUri: Uri): String {
        val docId = runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        }.getOrNull()
        if (!docId.isNullOrBlank()) {
            val docUri = runCatching {
                android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            }.getOrNull()
            if (docUri != null) {
                val queriedName = runCatching {
                    context.contentResolver.query(
                        docUri,
                        arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull()
                if (!queriedName.isNullOrBlank()) {
                    val colonSuffix = docId.substringAfter(':', missingDelimiterValue = "")
                    return if (colonSuffix.contains('/')) {
                        colonSuffix
                    } else {
                        queriedName
                    }
                }
            }
            val cleanDocSuffix = docId.substringAfter(':', missingDelimiterValue = docId)
            if (cleanDocSuffix.isNotBlank()) return cleanDocSuffix
        }
        return treeUri.lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: "Selected Folder"
    }

    fun releasePersistableTreePermission(context: Context, uriString: String?) {
        if (uriString.isNullOrBlank()) return
        runCatching {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.releasePersistableUriPermission(Uri.parse(uriString), flags)
        }
    }

    fun openInputStreamForLocation(context: Context, sourceUriString: String): InputStream {
        if (sourceUriString.startsWith("content://")) {
            val uri = Uri.parse(sourceUriString)
            return context.contentResolver.openInputStream(uri)
                ?: throw IOException("Unable to open InputStream for URI: $sourceUriString")
        }

        if (sourceUriString == "LATEST_BACKUP") {
            return openLatestBackupStream(context)
        }

        val rawPath = if (sourceUriString.startsWith("file://")) {
            Uri.parse(sourceUriString).path
                ?: throw SecurityException("Invalid file URI: $sourceUriString")
        } else {
            sourceUriString
        }

        val localFile = File(rawPath)
        val allowedRoots = listOf(
            context.cacheDir,
            context.filesDir,
            context.noBackupFilesDir,
            context.getExternalFilesDir(null)
        )
        if (!isPathConfinedToRoots(localFile, allowedRoots)) {
            throw SecurityException("Access denied to local path outside app storage.")
        }
        if (!localFile.exists() || !localFile.isFile) {
            throw java.io.FileNotFoundException("Local file not found: ${localFile.name}")
        }
        return FileInputStream(localFile)
    }

    private fun openLatestBackupStream(context: Context): InputStream {
        val extOrFilesDir = context.getExternalFilesDir(null) ?: context.filesDir
        val candidateDirs = listOf(
            File(context.filesDir, "FolioAutoBackups"),
            File(context.noBackupFilesDir, "FolioAutoBackups"),
            File(extOrFilesDir, "FolioAutoBackups"),
            File(extOrFilesDir, "FolioBackups")
        ).distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }

        val latestZip = candidateDirs
            .filter { it.exists() && it.isDirectory }
            .flatMap { dir ->
                dir.listFiles { file ->
                    file.isFile && (file.name.endsWith(".zip", ignoreCase = true) ||
                        file.name.endsWith(".folio", ignoreCase = true))
                }?.toList() ?: emptyList()
            }
            .maxByOrNull { file ->
                AutoBackupRetentionEngine.parseBackupFile(file)?.epochMillis ?: file.lastModified()
            }

        if (latestZip != null) {
            return FileInputStream(latestZip)
        }

        // Fallback: check public Downloads/Folio via MediaStore
        val projection = arrayOf(android.provider.MediaStore.MediaColumns._ID)
        val selection = "(${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? " +
            "OR ${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?) " +
            "AND ${folioDownloadsRelPathSqlPredicate()}"
        val selectionArgs = arrayOf(
            "Folio_%Backup%.zip",
            "Folio_%Backup%.folio",
            *folioDownloadsRelPathSqlArgs()
        )
        val sortOrder = "${android.provider.MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        context.contentResolver.query(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                val contentUri = android.content.ContentUris.withAppendedId(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    id
                )
                context.contentResolver.openInputStream(contentUri)?.let { return it }
            }
        }

        throw IOException("No Folio backup (.zip or .folio) found in Downloads/Folio or app storage. Create a backup first.")
    }

    fun deleteDownloadsMirrorIfExists(context: Context, fileName: String = "Folio_Latest_AutoBackup.zip"): Boolean {
        return runCatching {
            val (baseName, ext) = splitBaseAndExtension(fileName)
            val suffixedPattern = "$baseName (%)$ext"
            val projection = arrayOf(android.provider.MediaStore.MediaColumns._ID)
            val selection = "(${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ? " +
                "OR ${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?) " +
                "AND ${folioDownloadsRelPathSqlPredicate()}"
            val selectionArgs = arrayOf(
                fileName,
                suffixedPattern,
                *folioDownloadsRelPathSqlArgs()
            )
            context.contentResolver.query(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                var deleted = false
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val contentUri = android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        id
                    )
                    if (context.contentResolver.delete(contentUri, null, null) > 0) {
                        deleted = true
                    }
                }
                deleted
            } ?: false
        }.getOrDefault(false)
    }

}
