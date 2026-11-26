/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/ui/settings/sheets/FolioLocaleManager.kt
package com.folioledger.app.ui.settings.sheets

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Pure Android platform-level Locale Manager for Folio.
 * Leverages native Android 13+ (API 33+) LocaleManager for OS per-app language settings,
 * with configuration-context wrapping fallback for pre-Android 13 devices,
 * avoiding any AppCompatActivity or AppCompat theme requirements.
 */
object FolioLocaleManager {
    private const val PREFS_NAME = "folio_locale_prefs"
    private const val KEY_LANGUAGE_TAG = "selected_language_tag"

    fun setAppLanguage(context: Context, languageTag: String) {
        // Persist to local preferences
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE_TAG, languageTag)
            .apply()

        // On Android 13+ (API 33+), notify the Android OS LocaleManager, which automatically triggers
        // a configuration change and Activity recreation. Manual recreate() is only needed for pre-Android 13.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            if (localeManager != null) {
                localeManager.applicationLocales = if (languageTag.isBlank()) {
                    LocaleList.getEmptyLocaleList()
                } else {
                    LocaleList.forLanguageTags(languageTag)
                }
            } else {
                findActivity(context)?.recreate()
            }
        } else {
            findActivity(context)?.recreate()
        }
    }

    fun getCurrentLanguageTag(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            if (localeManager != null) {
                val locales = localeManager.applicationLocales
                return if (!locales.isEmpty) locales[0]?.toLanguageTag() ?: "" else ""
            }
        }
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE_TAG, "") ?: ""
    }

    fun clearSavedLanguage(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LANGUAGE_TAG)
            .apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            localeManager?.applicationLocales = LocaleList.getEmptyLocaleList()
        }
    }

    fun wrapContextIfNeeded(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context
        }
        val tag = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE_TAG, "") ?: ""
        val systemLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.content.res.Resources.getSystem().configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            android.content.res.Resources.getSystem().configuration.locale
        }
        if (tag.isBlank()) {
            Locale.setDefault(systemLocale)
            return context
        }

        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    fun findActivity(context: Context): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
