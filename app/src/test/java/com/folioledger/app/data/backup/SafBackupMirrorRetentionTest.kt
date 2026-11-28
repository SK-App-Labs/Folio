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

import android.provider.DocumentsContract
import com.folioledger.app.data.repository.DatabaseBackupEngine
import com.folioledger.app.domain.model.AutoBackupRetention
import com.folioledger.app.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SafBackupMirrorRetentionTest {

    private val zoneId = ZoneId.of("UTC")
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

    @Test
    fun computeEntriesToRetain_mixedZipAndFolio_prunesCleanlyUnderAllPolicies() {
        val now = LocalDateTime.of(2026, 10, 29, 18, 0, 0)
        val nowMillis = now.atZone(zoneId).toInstant().toEpochMilli()

        val entries = (1..14).map { idx ->
            val ext = if (idx % 2 == 0) ".folio" else ".zip"
            val dt = now.minusHours(idx.toLong() * 3)
            AutoBackupRetentionEngine.BackupEntry(
                id = "doc_$idx",
                fileName = "Folio_AutoBackup_${dt.format(formatter)}$ext",
                lastModifiedMillis = dt.atZone(zoneId).toInstant().toEpochMilli(),
            )
        }

        val tenSessions = AutoBackupRetentionEngine.computeEntriesToRetain(
            entries = entries,
            policy = AutoBackupRetention.TEN_SESSIONS,
            nowEpochMillis = nowMillis,
            zoneId = zoneId,
        )
        assertEquals(10, tenSessions.size)
        assertTrue(tenSessions.containsAll((1..10).map { "doc_$it" }))
    }

    @Test
    fun safPrunableChildren_neverTouchesNonFolioFilesOrDirectories() {
        val base = LocalDateTime.of(2026, 10, 29, 12, 0, 0)
        val nowMillis = base.atZone(zoneId).toInstant().toEpochMilli()

        val oldFolioBackups = (1..12).map { i ->
            val dt = base.minusMinutes(i.toLong() * 15)
            SafBackupMirrorEngine.SafChildDocument(
                docId = "folio_$i",
                displayName = "Folio_AutoBackup_${dt.format(formatter)}.folio",
                mimeType = "application/octet-stream",
                lastModifiedMillis = dt.atZone(zoneId).toInstant().toEpochMilli(),
                sizeBytes = 4096L,
            )
        }

        val nonFolioUserFiles = listOf(
            SafBackupMirrorEngine.SafChildDocument(
                docId = "user_notes",
                displayName = "notes.txt",
                mimeType = "text/plain",
                lastModifiedMillis = 1000L,
                sizeBytes = 256L,
            ),
            SafBackupMirrorEngine.SafChildDocument(
                docId = "user_other_zip",
                displayName = "other_backup.zip",
                mimeType = "application/zip",
                lastModifiedMillis = 1000L,
                sizeBytes = 1024L,
            ),
            SafBackupMirrorEngine.SafChildDocument(
                docId = "user_subdir",
                displayName = "Folio_AutoBackup_2026-01-01_000000.folio",
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                lastModifiedMillis = 1000L,
                sizeBytes = 0L,
            ),
            SafBackupMirrorEngine.SafChildDocument(
                docId = "user_manual",
                displayName = "Folio_Backup_2026-10-29.folio",
                mimeType = "application/octet-stream",
                lastModifiedMillis = 1000L,
                sizeBytes = 4096L,
            ),
        )

        val prunable = SafBackupMirrorEngine.computePrunableChildIds(
            children = oldFolioBackups + nonFolioUserFiles,
            retention = AutoBackupRetention.TEN_SESSIONS,
            nowEpochMillis = nowMillis,
        )

        assertEquals(2, prunable.size)
        assertTrue("folio_11" in prunable && "folio_12" in prunable)
        assertFalse("user_notes" in prunable)
        assertFalse("user_other_zip" in prunable)
        assertFalse("user_subdir" in prunable)
        assertFalse("user_manual" in prunable)
    }

    @Test
    fun serializeAndDeserializeUserPreferences_neverLeaksOrOverwritesSafMirrorConfig() {
        val sourcePrefs = UserPreferences(
            primaryCurrencyCode = "USD",
            safMirrorEnabled = true,
            safMirrorTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ASyncthing%2FFolio",
            safMirrorFolderDisplayName = "Syncthing/Folio",
            safMirrorEncryptEnabled = true,
            lastSafMirrorEpochMillis = 1790000000000L,
            safMirrorErrorCode = "PERMISSION_LOST",
        )

        val serialized = DatabaseBackupEngine.serializeUserPreferences(sourcePrefs)
        assertFalse(serialized.has("safMirrorEnabled"))
        assertFalse(serialized.has("safMirrorTreeUri"))
        assertFalse(serialized.has("safMirrorFolderDisplayName"))
        assertFalse(serialized.has("safMirrorEncryptEnabled"))
        assertFalse(serialized.has("lastSafMirrorEpochMillis"))
        assertFalse(serialized.has("safMirrorErrorCode"))

        val targetDeviceCurrent = UserPreferences(
            primaryCurrencyCode = "INR",
            safMirrorEnabled = false,
            safMirrorTreeUri = "content://local-device-tree",
            safMirrorFolderDisplayName = "LocalSD/Folio",
            safMirrorEncryptEnabled = false,
            lastSafMirrorEpochMillis = 1700000000000L,
            safMirrorErrorCode = null,
        )

        val restored = DatabaseBackupEngine.deserializeUserPreferences(serialized, targetDeviceCurrent)
        assertEquals("USD", restored.primaryCurrencyCode)
        assertEquals(targetDeviceCurrent.safMirrorEnabled, restored.safMirrorEnabled)
        assertEquals(targetDeviceCurrent.safMirrorTreeUri, restored.safMirrorTreeUri)
        assertEquals(targetDeviceCurrent.safMirrorFolderDisplayName, restored.safMirrorFolderDisplayName)
        assertEquals(targetDeviceCurrent.safMirrorEncryptEnabled, restored.safMirrorEncryptEnabled)
        assertEquals(targetDeviceCurrent.lastSafMirrorEpochMillis, restored.lastSafMirrorEpochMillis)
        assertEquals(targetDeviceCurrent.safMirrorErrorCode, restored.safMirrorErrorCode)
    }
}
