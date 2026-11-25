/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.widget

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.folioledger.app.FolioApplication
import com.folioledger.app.MainActivity
import kotlinx.coroutines.flow.first

class ToggleWidgetPrivacyAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val app = context.applicationContext as? FolioApplication
        val prefs = try {
            app?.container?.settingsAndBackupRepository?.observeUserPreferences()?.first()
        } catch (_: Throwable) {
            null
        }

        // If App Lock + Hide Amounts is active, refuse launcher unmasking and require biometric unlock via MainActivity
        if (prefs != null && prefs.biometricLockEnabled && prefs.hideAmountsOnLockScreen) {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            return
        }

        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { curPrefs ->
            val currentMasked = curPrefs[FolioWidgetStateKeys.KEY_IS_MASKED] ?: false
            curPrefs.toMutablePreferences().apply {
                this[FolioWidgetStateKeys.KEY_IS_MASKED] = !currentMasked
            }
        }
        SafeToSpendGlanceWidget().update(context, glanceId)
    }
}
