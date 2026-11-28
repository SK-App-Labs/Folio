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
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.folioledger.app.FolioApplication
import com.folioledger.app.data.repository.SettingsAndBackupRepositoryImpl
import com.folioledger.app.domain.model.SafMirrorOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * WorkManager worker that encrypts (optional) and mirrors the latest local auto-backup
 * snapshot to the user's configured SAF external folder outside Activity/freezer lifecycle.
 */
class SafBackupMirrorWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? FolioApplication ?: return@withContext Result.failure()
        val repo = app.container.settingsAndBackupRepository

        try {
            val prefs = repo.observeUserPreferences().first()
            if (prefs.isDemoModeActive ||
                !prefs.autoBackupEnabled ||
                !prefs.safMirrorEnabled ||
                prefs.safMirrorTreeUri.isNullOrBlank()
            ) {
                return@withContext Result.success()
            }

            // Never mirror plaintext backups when App Lock is active
            if (prefs.biometricLockEnabled && !prefs.safMirrorEncryptEnabled) {
                repo.updateUserPreferences {
                    it.copy(safMirrorEnabled = false, safMirrorErrorCode = null)
                }
                return@withContext Result.success()
            }

            val forceOverwrite = inputData.getBoolean(SafBackupMirrorScheduler.KEY_FORCE_OVERWRITE, false)
            val outcomeResult = (repo as? SettingsAndBackupRepositoryImpl)
                ?.triggerSafMirrorNowInternal(forceOverwrite = forceOverwrite)
                ?: repo.triggerSafMirrorNow()

            return@withContext when (outcomeResult.getOrNull()) {
                is SafMirrorOutcome.Success,
                SafMirrorOutcome.Skipped,
                SafMirrorOutcome.PermissionLost,
                SafMirrorOutcome.KeyUnavailable,
                -> Result.success()

                is SafMirrorOutcome.IoFailure,
                null,
                -> if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            android.util.Log.e("SafBackupMirrorWorker", "SAF backup mirror worker failed", t)
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
        }
    }
}
