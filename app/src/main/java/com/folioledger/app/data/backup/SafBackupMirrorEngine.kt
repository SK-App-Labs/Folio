/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.folioledger.app.domain.model.AutoBackupRetention
import com.folioledger.app.domain.model.SafMirrorOutcome
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Sync-safe Storage Access Framework (SAF) auto-mirror engine using direct [DocumentsContract]
 * cursors (zero androidx.documentfile dependency, single-query child enumeration).
 */
internal object SafBackupMirrorEngine {

    private val CHILD_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE
    )

    internal data class SafChildDocument(
        val docId: String,
        val displayName: String,
        val mimeType: String,
        val lastModifiedMillis: Long,
        val sizeBytes: Long
    )

    internal fun isManagedBackupChild(displayName: String, mimeType: String): Boolean {
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) return false
        return displayName.startsWith("Folio_AutoBackup_") &&
            AutoBackupRetentionEngine.isSupportedBackupExtension(displayName)
    }

    internal fun computePrunableChildIds(
        children: List<SafChildDocument>,
        retention: AutoBackupRetention,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): List<String> {
        val managed = children.filter { isManagedBackupChild(it.displayName, it.mimeType) }
        if (managed.isEmpty()) return emptyList()

        val entries = managed.map { child ->
            AutoBackupRetentionEngine.BackupEntry(
                id = child.docId,
                fileName = child.displayName,
                lastModifiedMillis = child.lastModifiedMillis
            )
        }
        val toRetain = AutoBackupRetentionEngine.computeEntriesToRetain(
            entries = entries,
            policy = retention,
            nowEpochMillis = nowEpochMillis
        )
        return managed.mapNotNull { child ->
            child.docId.takeIf { it !in toRetain }
        }
    }

    fun mirrorSnapshotToSaf(
        context: Context,
        snapshotFile: File,
        treeUriString: String,
        retention: AutoBackupRetention,
        encryptEnabled: Boolean,
        keyVault: AutoMirrorKeyVault,
        forceOverwrite: Boolean = false
    ): SafMirrorOutcome {
        if (!snapshotFile.exists() || !snapshotFile.isFile || snapshotFile.length() <= 0L) {
            return SafMirrorOutcome.Skipped
        }

        val treeUri = runCatching { Uri.parse(treeUriString) }.getOrNull()
            ?: return SafMirrorOutcome.PermissionLost
        val contentResolver = context.contentResolver

        val hasPersistedGrant = runCatching {
            contentResolver.persistedUriPermissions.any { perm ->
                perm.uri == treeUri && perm.isReadPermission && perm.isWritePermission
            }
        }.getOrDefault(false)
        if (!hasPersistedGrant) {
            return SafMirrorOutcome.PermissionLost
        }

        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return SafMirrorOutcome.PermissionLost
        val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)

        val dirAccessible = try {
            contentResolver.query(
                treeDocUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_FLAGS
                ),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                val flags = cursor.getInt(1)
                (flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0
            } ?: false
        } catch (_: SecurityException) {
            return SafMirrorOutcome.PermissionLost
        } catch (_: IllegalArgumentException) {
            return SafMirrorOutcome.PermissionLost
        } catch (t: Throwable) {
            return SafMirrorOutcome.IoFailure(t.message ?: "Unable to access selected folder.")
        }

        if (!dirAccessible) {
            return SafMirrorOutcome.PermissionLost
        }

        val baseName = snapshotFile.nameWithoutExtension
            .ifBlank { "Folio_AutoBackup_${System.currentTimeMillis()}" }
        val finalName = if (encryptEnabled) "$baseName.folio" else "$baseName.zip"
        val mimeType = if (encryptEnabled) "application/octet-stream" else "application/zip"

        val encryptedBytes: ByteArray? = if (encryptEnabled) {
            if (snapshotFile.length() > BackupCryptoEngine.MAX_ENCRYPTED_BYTES) {
                return SafMirrorOutcome.IoFailure("Snapshot exceeds maximum encrypted backup size.")
            }
            val unwrapped = keyVault.unwrapMasterKey() ?: return SafMirrorOutcome.KeyUnavailable
            var plainBytes: ByteArray? = null
            try {
                plainBytes = snapshotFile.readBytes()
                val encryptedOut = ByteArrayOutputStream(plainBytes.size + 128)
                BackupCryptoEngine.encryptWithMasterKey(
                    plaintextZipBytes = plainBytes,
                    masterKeyBytes = unwrapped.masterKeyBytes,
                    masterSalt = unwrapped.masterSalt,
                    iterations = unwrapped.iterations,
                    output = encryptedOut
                )
                encryptedOut.toByteArray()
            } catch (t: Throwable) {
                return SafMirrorOutcome.IoFailure(t.message ?: "Encryption failed during SAF mirror.")
            } finally {
                plainBytes?.fill(0)
                unwrapped.zeroize()
            }
        } else {
            null
        }

        val children = try {
            queryManagedChildren(contentResolver, treeUri, treeDocId)
        } catch (_: SecurityException) {
            return SafMirrorOutcome.PermissionLost
        } catch (t: Throwable) {
            return SafMirrorOutcome.IoFailure(t.message ?: "Failed to read SAF directory.")
        }

        val existingDuplicate = children.firstOrNull {
            it.displayName.equals(finalName, ignoreCase = true)
        }

        var replacedExisting = false
        val uploadedDoc: Pair<String, String>? = if (
            existingDuplicate != null &&
            existingDuplicate.sizeBytes > 0L &&
            !forceOverwrite
        ) {
            // Identical filename snapshot already mirrored & non-empty; skip duplicate upload
            existingDuplicate.docId to existingDuplicate.displayName
        } else {
            try {
                writeSafDocumentAtomically(
                    context = context,
                    treeUri = treeUri,
                    treeDocUri = treeDocUri,
                    finalName = finalName,
                    mimeType = mimeType,
                    snapshotFile = snapshotFile,
                    encryptedBytes = encryptedBytes,
                    existingDuplicateDocId = existingDuplicate?.docId
                ).also {
                    replacedExisting = existingDuplicate != null
                }
            } catch (_: SecurityException) {
                return SafMirrorOutcome.PermissionLost
            } catch (t: Throwable) {
                return SafMirrorOutcome.IoFailure(t.message ?: "Failed to write mirrored backup.")
            }
        }

        val updatedChildren = if (existingDuplicate != null && existingDuplicate.sizeBytes > 0L && !forceOverwrite) {
            children
        } else {
            val baseList = if (replacedExisting) {
                children.filterNot { it.displayName.equals(finalName, ignoreCase = true) }
            } else {
                children
            }
            if (uploadedDoc != null) {
                val (newDocId, actualName) = uploadedDoc
                baseList + SafChildDocument(
                    docId = newDocId,
                    displayName = actualName,
                    mimeType = mimeType,
                    lastModifiedMillis = System.currentTimeMillis(),
                    sizeBytes = encryptedBytes?.size?.toLong() ?: snapshotFile.length()
                )
            } else {
                baseList
            }
        }

        val prunableIds = computePrunableChildIds(
            children = updatedChildren,
            retention = retention
        )
        var prunedCount = 0
        prunableIds.forEach { docId ->
            runCatching {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                if (DocumentsContract.deleteDocument(contentResolver, docUri)) {
                    prunedCount++
                }
            }
        }

        return SafMirrorOutcome.Success(
            fileName = uploadedDoc?.second ?: finalName,
            encrypted = encryptEnabled,
            prunedCount = prunedCount
        )
    }

    private fun queryManagedChildren(
        contentResolver: android.content.ContentResolver,
        treeUri: Uri,
        treeDocId: String
    ): List<SafChildDocument> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        val result = mutableListOf<SafChildDocument>()
        contentResolver.query(childrenUri, CHILD_PROJECTION, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                val docId = cursor.getString(idIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: continue
                val mime = cursor.getString(mimeIdx) ?: ""
                val lastMod = if (modIdx != -1) cursor.getLong(modIdx) else 0L
                val size = if (sizeIdx != -1) cursor.getLong(sizeIdx) else 0L
                result.add(
                    SafChildDocument(
                        docId = docId,
                        displayName = name,
                        mimeType = mime,
                        lastModifiedMillis = lastMod,
                        sizeBytes = size
                    )
                )
            }
        }
        return result
    }

    private fun writeSafDocumentAtomically(
        context: Context,
        treeUri: Uri,
        treeDocUri: Uri,
        finalName: String,
        mimeType: String,
        snapshotFile: File,
        encryptedBytes: ByteArray?,
        existingDuplicateDocId: String? = null
    ): Pair<String, String>? {
        val contentResolver = context.contentResolver
        // Use application/octet-stream + non-dot prefix so ExternalStorageProvider does not append .zip to .tmp
        val tempName = "Folio_Staging_${System.currentTimeMillis()}.tmp"
        val tempUri = DocumentsContract.createDocument(
            contentResolver,
            treeDocUri,
            "application/octet-stream",
            tempName
        )

        if (tempUri != null) {
            val supportsRename = runCatching {
                contentResolver.query(
                    tempUri,
                    arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    cursor.moveToFirst() &&
                        (cursor.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0
                } ?: false
            }.getOrDefault(false)

            if (supportsRename) {
                try {
                    val out = contentResolver.openOutputStream(tempUri, "wt")
                        ?: throw IOException("Unable to open SAF temp output stream.")
                    out.use { stream ->
                        writePayload(stream, snapshotFile, encryptedBytes)
                        stream.flush()
                    }
                    if (!existingDuplicateDocId.isNullOrBlank()) {
                        runCatching {
                            DocumentsContract.deleteDocument(
                                contentResolver,
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, existingDuplicateDocId)
                            )
                        }
                    }
                    val renamedUri = DocumentsContract.renameDocument(contentResolver, tempUri, finalName)
                        ?: throw IOException("SAF provider failed to rename temporary backup file.")
                    val docId = runCatching { DocumentsContract.getDocumentId(renamedUri) }.getOrNull()
                    val actualName = queryDisplayName(contentResolver, renamedUri) ?: finalName
                    return docId?.let { it to actualName }
                } catch (t: Throwable) {
                    runCatching { DocumentsContract.deleteDocument(contentResolver, tempUri) }
                    throw t
                }
            } else {
                // Provider does not support rename; remove temp placeholder and stream directly
                runCatching { DocumentsContract.deleteDocument(contentResolver, tempUri) }
            }
        }

        if (!existingDuplicateDocId.isNullOrBlank()) {
            runCatching {
                DocumentsContract.deleteDocument(
                    contentResolver,
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, existingDuplicateDocId)
                )
            }
        }
        val directUri = DocumentsContract.createDocument(contentResolver, treeDocUri, mimeType, finalName)
            ?: throw IOException("Unable to create backup document in selected SAF folder.")
        try {
            val out = contentResolver.openOutputStream(directUri, "wt")
                ?: throw IOException("Unable to open SAF output stream.")
            out.use { stream ->
                writePayload(stream, snapshotFile, encryptedBytes)
                stream.flush()
            }
            val docId = runCatching { DocumentsContract.getDocumentId(directUri) }.getOrNull()
            val actualName = queryDisplayName(contentResolver, directUri) ?: finalName
            return docId?.let { it to actualName }
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(contentResolver, directUri) }
            throw t
        }
    }

    private fun queryDisplayName(
        contentResolver: android.content.ContentResolver,
        docUri: Uri
    ): String? = runCatching {
        contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun writePayload(
        output: java.io.OutputStream,
        snapshotFile: File,
        encryptedBytes: ByteArray?
    ) {
        if (encryptedBytes != null) {
            output.write(encryptedBytes)
        } else {
            FileInputStream(snapshotFile).use { input ->
                input.copyTo(output)
            }
        }
    }
}
