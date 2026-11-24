package com.folioledger.app.data.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import com.folioledger.app.FolioApplication

class FolioBackupAgent : BackupAgent() {
    override fun onFullBackup(data: FullBackupDataOutput?) {
        runCatching {
            (applicationContext as? FolioApplication)?.container?.database?.checkpointDatabase()
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
