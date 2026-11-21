package com.folioledger.app.ui.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition

class ToggleWidgetPrivacyAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
            val currentMasked = prefs[FolioWidgetStateKeys.KEY_IS_MASKED] ?: false
            prefs.toMutablePreferences().apply {
                this[FolioWidgetStateKeys.KEY_IS_MASKED] = !currentMasked
            }
        }
        SafeToSpendGlanceWidget().update(context, glanceId)
    }
}
