/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.folioledger.app.domain.model.AutoBackupRetention
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.domain.util.DailyLogReminderCalculator
import java.io.IOException
import java.util.Currency
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal val Context.folioUserPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "folio_user_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

class UserPreferencesDataSource(private val context: Context) {

    private object PreferenceKeys {
        val PRIMARY_CURRENCY_CODE = stringPreferencesKey("primary_currency_code")
        val SECONDARY_CURRENCIES_JSON = stringPreferencesKey("secondary_currencies_json")
        val NUMBER_GROUPING = stringPreferencesKey("number_grouping")
        val HIDE_PRIMARY_CURRENCY_SYMBOL = booleanPreferencesKey("hide_primary_currency_symbol")
        val MONTH_START_DAY = intPreferencesKey("month_start_day")
        val WEEK_START_DAY_ISO = intPreferencesKey("week_start_day_iso")
        val SUBCATEGORIES_ENABLED = booleanPreferencesKey("subcategories_enabled")
        val CARRY_OVER_ENABLED = booleanPreferencesKey("carry_over_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR_ENABLED = booleanPreferencesKey("dynamic_color_enabled")
        val ACCENT_PALETTE = stringPreferencesKey("accent_palette")
        val BIOMETRIC_LOCK_ENABLED = booleanPreferencesKey("biometric_lock_enabled")
        val IS_SUPPORTER = booleanPreferencesKey("is_supporter")
        val SUPPORTER_TIER = stringPreferencesKey("supporter_tier")
        val LAST_BACKUP_EPOCH_MILLIS = longPreferencesKey("last_backup_epoch_millis")
        val AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
        val AUTO_BACKUP_PUBLIC_MIRROR_ENABLED = booleanPreferencesKey("auto_backup_public_mirror_enabled")
        val AUTO_BACKUP_RETENTION = stringPreferencesKey("auto_backup_retention")
        val CARD_MILESTONES_JSON = stringPreferencesKey("card_milestones_json")
        val ICON_PACK_THEME = stringPreferencesKey("icon_pack_theme")
        val MONTHLY_SAVINGS_TARGET_MINOR = longPreferencesKey("monthly_savings_target_minor")
        val SAVINGS_GOALS_JSON = stringPreferencesKey("savings_goals_json")
        val HAS_SEEN_WELCOME_SHEET = booleanPreferencesKey("has_seen_welcome_sheet")
        val IS_DEMO_MODE_ACTIVE = booleanPreferencesKey("is_demo_mode_active")
        val HAS_STASHED_REAL_LEDGER = booleanPreferencesKey("has_stashed_real_ledger")
        val REMINDERS_ENABLED = booleanPreferencesKey("reminders_enabled")
        val CC_REMINDER_LEAD_DAYS = intPreferencesKey("cc_reminder_lead_days")
        val BILL_REMINDER_LEAD_DAYS = intPreferencesKey("bill_reminder_lead_days")
        val REMINDER_HOUR_OF_DAY = intPreferencesKey("reminder_hour_of_day")
        val REMINDER_MINUTE = intPreferencesKey("reminder_minute")
        val HIDE_AMOUNTS_ON_LOCK_SCREEN = booleanPreferencesKey("hide_amounts_on_lock_screen")
        val DAILY_LOG_REMINDER_ENABLED = booleanPreferencesKey("daily_log_reminder_enabled")
        val DAILY_LOG_REMINDER_HOUR = intPreferencesKey("daily_log_reminder_hour")
        val DAILY_LOG_REMINDER_MINUTE = intPreferencesKey("daily_log_reminder_minute")
        val DAILY_LOG_REMINDER_DAYS_MASK = intPreferencesKey("daily_log_reminder_days_mask")
        val DAILY_LOG_REMINDER_SKIP_IF_LOGGED = booleanPreferencesKey("daily_log_reminder_skip_if_logged")
        val DAILY_LOG_REMINDER_SNOOZE_MINUTES = intPreferencesKey("daily_log_reminder_snooze_minutes")
        val DAILY_LOG_REMINDER_MESSAGE = stringPreferencesKey("daily_log_reminder_message")
        val SAF_MIRROR_ENABLED = booleanPreferencesKey("saf_mirror_enabled")
        val SAF_MIRROR_TREE_URI = stringPreferencesKey("saf_mirror_tree_uri")
        val SAF_MIRROR_FOLDER_DISPLAY_NAME = stringPreferencesKey("saf_mirror_folder_display_name")
        val SAF_MIRROR_ENCRYPT_ENABLED = booleanPreferencesKey("saf_mirror_encrypt_enabled")
        val LAST_SAF_MIRROR_EPOCH_MILLIS = longPreferencesKey("last_saf_mirror_epoch_millis")
        val SAF_MIRROR_ERROR_CODE = stringPreferencesKey("saf_mirror_error_code")
    }

    fun observeUserPreferences(): Flow<UserPreferences> {
        return context.folioUserPreferencesDataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { prefs -> mapPreferencesToDomain(prefs) }
            .distinctUntilChanged()
    }

    suspend fun updateUserPreferences(
        transform: (UserPreferences) -> UserPreferences
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
        var shouldPurgePublicMirror = false
        context.folioUserPreferencesDataStore.edit { mutablePrefs ->
            val current = mapPreferencesToDomain(mutablePrefs)
            val updated = transform(current)
            val biometricJustEnabled = !current.biometricLockEnabled && updated.biometricLockEnabled
            val resolvedPublicMirror = if (updated.biometricLockEnabled) {
                false
            } else {
                updated.autoBackupPublicMirrorEnabled
            }
            val resolvedSafMirrorEnabled = if (updated.biometricLockEnabled && !updated.safMirrorEncryptEnabled) {
                false
            } else {
                updated.safMirrorEnabled
            }
            if ((current.autoBackupPublicMirrorEnabled && !resolvedPublicMirror) || biometricJustEnabled) {
                shouldPurgePublicMirror = true
            }
            val validatedCurrency = normalizePrimaryCurrencyCode(updated.primaryCurrencyCode)
            val validatedMonthStart = updated.monthStartDay.coerceIn(1, 31)
            val validatedWeekStart = updated.weekStartDayIso.coerceIn(1, 7)

            mutablePrefs[PreferenceKeys.PRIMARY_CURRENCY_CODE] = validatedCurrency
            mutablePrefs[PreferenceKeys.SECONDARY_CURRENCIES_JSON] = serializeSecondaryCurrencies(updated.secondaryCurrencies)
            mutablePrefs[PreferenceKeys.NUMBER_GROUPING] = updated.numberGrouping.name
            mutablePrefs[PreferenceKeys.HIDE_PRIMARY_CURRENCY_SYMBOL] = updated.hidePrimaryCurrencySymbol
            mutablePrefs[PreferenceKeys.MONTH_START_DAY] = validatedMonthStart
            mutablePrefs[PreferenceKeys.WEEK_START_DAY_ISO] = validatedWeekStart
            mutablePrefs[PreferenceKeys.SUBCATEGORIES_ENABLED] = updated.subcategoriesEnabled
            mutablePrefs[PreferenceKeys.CARRY_OVER_ENABLED] = updated.carryOverEnabled
            mutablePrefs[PreferenceKeys.THEME_MODE] = updated.themeMode
            mutablePrefs[PreferenceKeys.DYNAMIC_COLOR_ENABLED] = updated.dynamicColorEnabled
            mutablePrefs[PreferenceKeys.ACCENT_PALETTE] = updated.accentPalette
            mutablePrefs[PreferenceKeys.BIOMETRIC_LOCK_ENABLED] = updated.biometricLockEnabled
            mutablePrefs[PreferenceKeys.IS_SUPPORTER] = updated.isSupporter
            if (updated.supporterTier != null) {
                mutablePrefs[PreferenceKeys.SUPPORTER_TIER] = updated.supporterTier
            } else {
                mutablePrefs.remove(PreferenceKeys.SUPPORTER_TIER)
            }
            if (updated.lastBackupEpochMillis != null) {
                mutablePrefs[PreferenceKeys.LAST_BACKUP_EPOCH_MILLIS] = updated.lastBackupEpochMillis
            } else {
                mutablePrefs.remove(PreferenceKeys.LAST_BACKUP_EPOCH_MILLIS)
            }
            mutablePrefs[PreferenceKeys.AUTO_BACKUP_ENABLED] = updated.autoBackupEnabled
            mutablePrefs[PreferenceKeys.AUTO_BACKUP_PUBLIC_MIRROR_ENABLED] = resolvedPublicMirror
            mutablePrefs[PreferenceKeys.AUTO_BACKUP_RETENTION] = updated.autoBackupRetention.name
            mutablePrefs[PreferenceKeys.CARD_MILESTONES_JSON] = validateJsonObjectString(updated.cardMilestonesJson)
            mutablePrefs[PreferenceKeys.ICON_PACK_THEME] = updated.iconPackTheme
            mutablePrefs[PreferenceKeys.MONTHLY_SAVINGS_TARGET_MINOR] = updated.monthlySavingsTargetMinor.coerceAtLeast(0L)
            mutablePrefs[PreferenceKeys.SAVINGS_GOALS_JSON] = validateJsonObjectString(updated.savingsGoalsJson)
            mutablePrefs[PreferenceKeys.HAS_SEEN_WELCOME_SHEET] = updated.hasSeenWelcomeSheet
            mutablePrefs[PreferenceKeys.IS_DEMO_MODE_ACTIVE] = updated.isDemoModeActive
            mutablePrefs[PreferenceKeys.HAS_STASHED_REAL_LEDGER] = updated.hasStashedRealLedger
            mutablePrefs[PreferenceKeys.REMINDERS_ENABLED] = updated.remindersEnabled
            mutablePrefs[PreferenceKeys.CC_REMINDER_LEAD_DAYS] = updated.ccReminderLeadDays.coerceIn(0, 14)
            mutablePrefs[PreferenceKeys.BILL_REMINDER_LEAD_DAYS] = updated.billReminderLeadDays.coerceIn(0, 7)
            mutablePrefs[PreferenceKeys.REMINDER_HOUR_OF_DAY] = updated.reminderHourOfDay.coerceIn(0, 23)
            mutablePrefs[PreferenceKeys.REMINDER_MINUTE] = updated.reminderMinute.coerceIn(0, 59)
            mutablePrefs[PreferenceKeys.HIDE_AMOUNTS_ON_LOCK_SCREEN] = updated.hideAmountsOnLockScreen
            mutablePrefs[PreferenceKeys.DAILY_LOG_REMINDER_ENABLED] = updated.dailyLogReminderEnabled
            mutablePrefs[PreferenceKeys.DAILY_LOG_REMINDER_HOUR] = updated.dailyLogReminderHour.coerceIn(0, 23)
            mutablePrefs[PreferenceKeys.DAILY_LOG_REMINDER_MINUTE] = updated.dailyLogReminderMinute.coerceIn(0, 59)
            mutablePrefs[PreferenceKeys.DAILY_LOG_REMINDER_DAYS_MASK] =
                DailyLogReminderCalculator.normalizeDaysMask(updated.dailyLogReminderDaysMask)
            mutablePrefs[PreferenceKeys.DAILY_LOG_REMINDER_SKIP_IF_LOGGED] = updated.dailyLogReminderSkipIfLoggedToday
            mutablePrefs[PreferenceKeys.DAILY_LOG_REMINDER_SNOOZE_MINUTES] =
                DailyLogReminderCalculator.normalizeSnoozeMinutes(updated.dailyLogReminderSnoozeMinutes)
            mutablePrefs[PreferenceKeys.DAILY_LOG_REMINDER_MESSAGE] =
                updated.dailyLogReminderMessage.trim().take(DailyLogReminderCalculator.MAX_CUSTOM_MESSAGE_LENGTH)
            mutablePrefs[PreferenceKeys.SAF_MIRROR_ENABLED] = resolvedSafMirrorEnabled
            if (!updated.safMirrorTreeUri.isNullOrBlank()) {
                mutablePrefs[PreferenceKeys.SAF_MIRROR_TREE_URI] = updated.safMirrorTreeUri
            } else {
                mutablePrefs.remove(PreferenceKeys.SAF_MIRROR_TREE_URI)
            }
            if (!updated.safMirrorFolderDisplayName.isNullOrBlank()) {
                mutablePrefs[PreferenceKeys.SAF_MIRROR_FOLDER_DISPLAY_NAME] = updated.safMirrorFolderDisplayName
            } else {
                mutablePrefs.remove(PreferenceKeys.SAF_MIRROR_FOLDER_DISPLAY_NAME)
            }
            mutablePrefs[PreferenceKeys.SAF_MIRROR_ENCRYPT_ENABLED] = updated.safMirrorEncryptEnabled
            if (updated.lastSafMirrorEpochMillis != null) {
                mutablePrefs[PreferenceKeys.LAST_SAF_MIRROR_EPOCH_MILLIS] = updated.lastSafMirrorEpochMillis
            } else {
                mutablePrefs.remove(PreferenceKeys.LAST_SAF_MIRROR_EPOCH_MILLIS)
            }
            if (!updated.safMirrorErrorCode.isNullOrBlank()) {
                mutablePrefs[PreferenceKeys.SAF_MIRROR_ERROR_CODE] = updated.safMirrorErrorCode
            } else {
                mutablePrefs.remove(PreferenceKeys.SAF_MIRROR_ERROR_CODE)
            }
        }
        if (shouldPurgePublicMirror) {
            StorageAccessUtils.deleteDownloadsMirrorIfExists(context)
        }
        Unit
        }
    }

    fun mapPreferencesToDomain(prefs: Preferences): UserPreferences {
        val rawCurrency = prefs[PreferenceKeys.PRIMARY_CURRENCY_CODE]
        val currencyCode = if (rawCurrency != null) {
            normalizePrimaryCurrencyCode(rawCurrency)
        } else {
            resolveDeviceCurrencyCode()
        }
        val secondaryJson = prefs[PreferenceKeys.SECONDARY_CURRENCIES_JSON] ?: "[]"
        val secondaryCurrencies = parseSecondaryCurrencies(secondaryJson)
        val defaultGrouping = SupportedCurrencies.fromCode(currencyCode).defaultGrouping
        val groupingName = prefs[PreferenceKeys.NUMBER_GROUPING] ?: defaultGrouping.name
        val grouping = runCatching { NumberGroupingSystem.valueOf(groupingName) }
            .getOrDefault(defaultGrouping)

        return UserPreferences(
            primaryCurrencyCode = currencyCode,
            secondaryCurrencies = secondaryCurrencies,
            numberGrouping = grouping,
            hidePrimaryCurrencySymbol = prefs[PreferenceKeys.HIDE_PRIMARY_CURRENCY_SYMBOL] ?: false,
            monthStartDay = (prefs[PreferenceKeys.MONTH_START_DAY] ?: 1).coerceIn(1, 31),
            weekStartDayIso = (prefs[PreferenceKeys.WEEK_START_DAY_ISO] ?: 1).coerceIn(1, 7),
            subcategoriesEnabled = prefs[PreferenceKeys.SUBCATEGORIES_ENABLED] ?: true,
            carryOverEnabled = prefs[PreferenceKeys.CARRY_OVER_ENABLED] ?: false,
            themeMode = prefs[PreferenceKeys.THEME_MODE] ?: "SYSTEM",
            dynamicColorEnabled = prefs[PreferenceKeys.DYNAMIC_COLOR_ENABLED] ?: false,
            accentPalette = prefs[PreferenceKeys.ACCENT_PALETTE] ?: "EMERALD",
            biometricLockEnabled = prefs[PreferenceKeys.BIOMETRIC_LOCK_ENABLED] ?: false,
            isSupporter = prefs[PreferenceKeys.IS_SUPPORTER] ?: false,
            supporterTier = prefs[PreferenceKeys.SUPPORTER_TIER],
            lastBackupEpochMillis = prefs[PreferenceKeys.LAST_BACKUP_EPOCH_MILLIS],
            autoBackupEnabled = prefs[PreferenceKeys.AUTO_BACKUP_ENABLED] ?: true,
            autoBackupPublicMirrorEnabled = prefs[PreferenceKeys.AUTO_BACKUP_PUBLIC_MIRROR_ENABLED] ?: false,
            autoBackupRetention = runCatching {
                AutoBackupRetention.valueOf(prefs[PreferenceKeys.AUTO_BACKUP_RETENTION] ?: AutoBackupRetention.THIRTY_DAYS.name)
            }.getOrDefault(AutoBackupRetention.THIRTY_DAYS),
            cardMilestonesJson = validateJsonObjectString(prefs[PreferenceKeys.CARD_MILESTONES_JSON]),
            iconPackTheme = prefs[PreferenceKeys.ICON_PACK_THEME] ?: "MATERIAL_FILLED",
            monthlySavingsTargetMinor = (prefs[PreferenceKeys.MONTHLY_SAVINGS_TARGET_MINOR] ?: 0L).coerceAtLeast(0L),
            savingsGoalsJson = validateJsonObjectString(prefs[PreferenceKeys.SAVINGS_GOALS_JSON]),
            hasSeenWelcomeSheet = prefs[PreferenceKeys.HAS_SEEN_WELCOME_SHEET] ?: false,
            isDemoModeActive = prefs[PreferenceKeys.IS_DEMO_MODE_ACTIVE] ?: false,
            hasStashedRealLedger = prefs[PreferenceKeys.HAS_STASHED_REAL_LEDGER] ?: false,
            remindersEnabled = prefs[PreferenceKeys.REMINDERS_ENABLED] ?: false,
            ccReminderLeadDays = (prefs[PreferenceKeys.CC_REMINDER_LEAD_DAYS] ?: 3).coerceIn(0, 14),
            billReminderLeadDays = (prefs[PreferenceKeys.BILL_REMINDER_LEAD_DAYS] ?: 1).coerceIn(0, 7),
            reminderHourOfDay = (prefs[PreferenceKeys.REMINDER_HOUR_OF_DAY] ?: 9).coerceIn(0, 23),
            reminderMinute = (prefs[PreferenceKeys.REMINDER_MINUTE] ?: 0).coerceIn(0, 59),
            hideAmountsOnLockScreen = prefs[PreferenceKeys.HIDE_AMOUNTS_ON_LOCK_SCREEN] ?: false,
            dailyLogReminderEnabled = prefs[PreferenceKeys.DAILY_LOG_REMINDER_ENABLED] ?: false,
            dailyLogReminderHour = (prefs[PreferenceKeys.DAILY_LOG_REMINDER_HOUR] ?: DailyLogReminderCalculator.DEFAULT_HOUR).coerceIn(0, 23),
            dailyLogReminderMinute = (prefs[PreferenceKeys.DAILY_LOG_REMINDER_MINUTE] ?: DailyLogReminderCalculator.DEFAULT_MINUTE).coerceIn(0, 59),
            dailyLogReminderDaysMask = DailyLogReminderCalculator.normalizeDaysMask(
                prefs[PreferenceKeys.DAILY_LOG_REMINDER_DAYS_MASK] ?: DailyLogReminderCalculator.ALL_DAYS_MASK
            ),
            dailyLogReminderSkipIfLoggedToday = prefs[PreferenceKeys.DAILY_LOG_REMINDER_SKIP_IF_LOGGED] ?: true,
            dailyLogReminderSnoozeMinutes = DailyLogReminderCalculator.normalizeSnoozeMinutes(
                prefs[PreferenceKeys.DAILY_LOG_REMINDER_SNOOZE_MINUTES] ?: DailyLogReminderCalculator.DEFAULT_SNOOZE_MINUTES
            ),
            dailyLogReminderMessage = (prefs[PreferenceKeys.DAILY_LOG_REMINDER_MESSAGE] ?: "")
                .trim()
                .take(DailyLogReminderCalculator.MAX_CUSTOM_MESSAGE_LENGTH),
            safMirrorEnabled = prefs[PreferenceKeys.SAF_MIRROR_ENABLED] ?: false,
            safMirrorTreeUri = prefs[PreferenceKeys.SAF_MIRROR_TREE_URI],
            safMirrorFolderDisplayName = prefs[PreferenceKeys.SAF_MIRROR_FOLDER_DISPLAY_NAME],
            safMirrorEncryptEnabled = prefs[PreferenceKeys.SAF_MIRROR_ENCRYPT_ENABLED] ?: false,
            lastSafMirrorEpochMillis = prefs[PreferenceKeys.LAST_SAF_MIRROR_EPOCH_MILLIS],
            safMirrorErrorCode = prefs[PreferenceKeys.SAF_MIRROR_ERROR_CODE]
        )
    }
}

internal fun normalizePrimaryCurrencyCode(raw: String): String {
    val clean = raw.trim().uppercase(Locale.ROOT)
    return SupportedCurrencies.getByCode(clean)?.code ?: resolveDeviceCurrencyCode()
}

internal fun validateJsonObjectString(raw: String?): String {
    if (raw.isNullOrBlank()) return "{}"
    val trimmed = raw.trim()
    return runCatching {
        JSONObject(trimmed)
        trimmed
    }.getOrDefault("{}")
}

internal fun parseSecondaryCurrencies(json: String): List<SecondaryCurrency> {
    return runCatching {
        val array = JSONArray(json)
        val list = mutableListOf<SecondaryCurrency>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val code = obj.optString("code").trim().uppercase(Locale.ROOT)
            val rate = obj.optDouble("rate", Double.NaN)
            if (SupportedCurrencies.getByCode(code) != null &&
                rate.isFinite() &&
                rate in 1e-6..1_000_000_000.0
            ) {
                list.add(SecondaryCurrency(code = code, exchangeRateToPrimary = rate))
            }
        }
        list.distinctBy { it.code }
    }.getOrDefault(emptyList())
}

internal fun serializeSecondaryCurrencies(list: List<SecondaryCurrency>): String {
    val array = JSONArray()
    list.forEach { item ->
        val code = item.code.trim().uppercase(Locale.ROOT)
        if (code.isNotEmpty() && item.exchangeRateToPrimary.isFinite() && item.exchangeRateToPrimary > 0.0) {
            array.put(
                JSONObject().apply {
                    put("code", code)
                    put("rate", item.exchangeRateToPrimary)
                }
            )
        }
    }
    return array.toString()
}

internal fun resolveDeviceCurrencyCode(): String {
    return runCatching {
        val locale = Locale.getDefault()
        val curr = Currency.getInstance(locale)
        curr?.currencyCode?.takeIf { it.isNotBlank() && SupportedCurrencies.getByCode(it) != null }
    }.getOrNull() ?: "USD"
}
