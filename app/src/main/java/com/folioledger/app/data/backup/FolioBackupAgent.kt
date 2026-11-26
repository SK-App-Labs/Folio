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

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor
import com.folioledger.app.FolioApplication
import com.folioledger.app.data.local.FolioDatabase

class FolioBackupAgent : BackupAgent() {
    override fun onFullBackup(data: FullBackupDataOutput?) {
        runCatching {
            val app = applicationContext as? FolioApplication
            if (app != null) {
                app.container.database.checkpointDatabase()
            } else {
                val dbFile = getDatabasePath(FolioDatabase.DATABASE_NAME)
                if (dbFile.exists()) {
                    SQLiteDatabase.openDatabase(
                        dbFile.path,
                        null,
                        SQLiteDatabase.OPEN_READWRITE
                    ).use { db ->
                        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)?.use { cursor ->
                            cursor.moveToFirst()
                        }
                    }
                }
            }
        }
        super.onFullBackup(data)
    }

    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?
    ) = Unit

    override fun onRestore(
        data: BackupDataInput?,
        appVersionCode: Int,
        newState: ParcelFileDescriptor?
    ) = Unit
}
