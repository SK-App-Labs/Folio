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

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object FolioWidgetStateKeys {
    val KEY_SAFE_AMOUNT = stringPreferencesKey("safe_amount")
    val KEY_STATUS_LABEL = stringPreferencesKey("status_label")
    val KEY_STATUS_TONE = stringPreferencesKey("status_tone")
    val KEY_SECONDARY_SUMMARY = stringPreferencesKey("secondary_summary")
    val KEY_NEXT_CARD_DUE = stringPreferencesKey("next_card_due")
    val KEY_NEXT_CARD_DUE_MASKED = stringPreferencesKey("next_card_due_masked")
    val KEY_IS_DEMO_MODE = booleanPreferencesKey("is_demo_mode")
    val KEY_IS_MASKED = booleanPreferencesKey("is_masked")
    val KEY_IS_PRIVACY_LOCKED = booleanPreferencesKey("is_privacy_locked")
    val KEY_BOOKMARKS_JSON = stringPreferencesKey("bookmarks_json")
    val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    val KEY_ACCENT_PALETTE = stringPreferencesKey("accent_palette")
    val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
}
