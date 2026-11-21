package com.folioledger.app.ui.widget

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object FolioWidgetStateKeys {
    val KEY_SAFE_AMOUNT = stringPreferencesKey("safe_amount")
    val KEY_RAW_MINOR = longPreferencesKey("raw_minor")
    val KEY_STATUS_LABEL = stringPreferencesKey("status_label")
    val KEY_STATUS_TONE = stringPreferencesKey("status_tone")
    val KEY_SECONDARY_SUMMARY = stringPreferencesKey("secondary_summary")
    val KEY_NEXT_CARD_DUE = stringPreferencesKey("next_card_due")
    val KEY_NEXT_CARD_DUE_MASKED = stringPreferencesKey("next_card_due_masked")
    val KEY_IS_DEMO_MODE = booleanPreferencesKey("is_demo_mode")
    val KEY_IS_MASKED = booleanPreferencesKey("is_masked")
    val KEY_UPDATED_TIME = stringPreferencesKey("updated_time")
    val KEY_BOOKMARKS_JSON = stringPreferencesKey("bookmarks_json")
}
