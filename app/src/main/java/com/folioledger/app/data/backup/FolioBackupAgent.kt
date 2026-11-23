package com.folioledger.app.data.backup

import android.app.backup.BackupAgentHelper
import android.app.backup.BackupDataOutput
import android.os.ParcelFileDescriptor
import com.folioledger.app.FolioApplication

class FolioBackupAgent : BackupAgentHelper() {
    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?
    ) {
        runCatching {
            (applicationContext as? FolioApplication)?.container?.database?.checkpointDatabase()
        }
        super.onBackup(oldState, data, newState)
    }
}
