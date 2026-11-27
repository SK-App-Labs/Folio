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
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.folioledger.app.domain.model.UserPreferences
import java.util.concurrent.TimeUnit

object SafBackupMirrorScheduler {
    const val ONE_TIME_WORK_NAME = "folio_saf_mirror_now"
    const val PERIODIC_WORK_NAME = "folio_saf_mirror_periodic_v1"
    const val KEY_SNAPSHOT_PATH = "snapshot_path"
    const val KEY_FORCE_OVERWRITE = "force_overwrite"

    fun enqueueImmediateMirror(
        context: Context,
        snapshotPath: String? = null,
        forceOverwrite: Boolean = false
    ) {
        val builder = OneTimeWorkRequestBuilder<SafBackupMirrorWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .addTag("folio_saf_mirror")

        builder.setInputData(
            workDataOf(
                KEY_SNAPSHOT_PATH to snapshotPath,
                KEY_FORCE_OVERWRITE to forceOverwrite
            )
        )

        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            builder.build()
        )
    }

    fun syncPeriodicSchedule(context: Context, prefs: UserPreferences) {
        val wm = WorkManager.getInstance(context)
        val shouldSchedule = !prefs.isDemoModeActive &&
            prefs.autoBackupEnabled &&
            prefs.safMirrorEnabled &&
            !prefs.safMirrorTreeUri.isNullOrBlank() &&
            (!prefs.biometricLockEnabled || prefs.safMirrorEncryptEnabled)

        if (!shouldSchedule) {
            wm.cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiresStorageNotLow(true)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<SafBackupMirrorWorker>(24L, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .addTag("folio_saf_mirror")
            .build()

        wm.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(ONE_TIME_WORK_NAME)
        wm.cancelUniqueWork(PERIODIC_WORK_NAME)
    }
}
