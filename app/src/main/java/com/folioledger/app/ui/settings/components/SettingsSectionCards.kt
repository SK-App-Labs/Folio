/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.settings.components

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.folioledger.app.BuildConfig
import com.folioledger.app.R
import com.folioledger.app.domain.model.AutoBackupRetention
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.SupporterTier
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.domain.util.DailyLogReminderCalculator
import com.folioledger.app.ui.components.parseHexColorSafely
import com.folioledger.app.ui.iconpack.FolioIconPackTheme
import com.folioledger.app.ui.settings.SettingsUiState
import com.folioledger.app.ui.settings.sheets.formatBackupRelativeTime
import com.folioledger.app.ui.settings.sheets.formatExchangeRatePlain
import com.folioledger.app.ui.theme.FolioAccentPalette
import com.folioledger.app.ui.theme.FolioThemeMode
import com.folioledger.app.ui.theme.LocalFolioSemanticColors
import java.util.Locale

internal fun formatAutoBackupSummary(prefs: UserPreferences): String {
    if (prefs.isDemoModeActive) return "Paused (Demo Sandbox)"
    if (!prefs.autoBackupEnabled) return "Disabled"

    val shortRetention = when (prefs.autoBackupRetention) {
        AutoBackupRetention.TEN_SESSIONS -> "10 Sessions"
        AutoBackupRetention.THIRTY_DAYS -> "30 Days"
        AutoBackupRetention.NINETY_DAYS -> "90 Days"
    }
    val hasSafTree = !prefs.safMirrorTreeUri.isNullOrBlank()
    if (hasSafTree && !prefs.safMirrorErrorCode.isNullOrBlank()) {
        return "$shortRetention \u2022 Mirror Alert"
    }
    val isSafMirrorActive = prefs.safMirrorEnabled &&
        hasSafTree &&
        (!prefs.biometricLockEnabled || prefs.safMirrorEncryptEnabled)
    if (isSafMirrorActive) {
        val safLabel = if (prefs.safMirrorEncryptEnabled) "SAF Encrypted" else "Mirror On"
        return "$shortRetention \u2022 $safLabel"
    }
    return when (prefs.autoBackupRetention) {
        AutoBackupRetention.TEN_SESSIONS -> "10 Sessions"
        AutoBackupRetention.THIRTY_DAYS -> "30 Days (Tiered)"
        AutoBackupRetention.NINETY_DAYS -> "90 Days"
    }
}

internal fun formatDueRemindersSummary(prefs: UserPreferences, is24Hour: Boolean = false): String {
    val ccPart = if (prefs.ccReminderLeadDays == 0) {
        "On due date"
    } else {
        "${prefs.ccReminderLeadDays}d before"
    }
    val billPart = if (prefs.billReminderLeadDays == 0) {
        "On due date"
    } else {
        "${prefs.billReminderLeadDays}d before"
    }
    val timePart = formatReminderTimeDisplay(prefs.reminderHourOfDay, prefs.reminderMinute, is24Hour)
    return "CC: $ccPart \u2022 Bills: $billPart \u2022 $timePart"
}

internal fun formatReminderTimeDisplay(
    hourOfDay: Int,
    minute: Int,
    is24Hour: Boolean = false
): String {
    if (is24Hour) {
        return String.format(Locale.getDefault(), "%02d:%02d", hourOfDay, minute)
    }
    val h = if (hourOfDay == 0) 12 else if (hourOfDay > 12) hourOfDay - 12 else hourOfDay
    val amPm = if (hourOfDay < 12) "AM" else "PM"
    val minStr = if (minute < 10) "0$minute" else "$minute"
    return "$h:$minStr $amPm"
}

@Composable
fun LedgerAccountingSettingsCard(
    prefs: UserPreferences,
    isNotBusy: Boolean,
    onOpenRegionalFormat: () -> Unit,
    onOpenSecondaryCurrencies: () -> Unit,
    onOpenMonthStartDayDialog: () -> Unit,
    onToggleCarryOver: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val semanticColors = LocalFolioSemanticColors.current

    SettingsGroupCard(
        title = stringResource(R.string.settings_group_accounting),
        modifier = modifier
    ) {
        SettingsNavigationRow(
            icon = Icons.Default.Payments,
            title = stringResource(R.string.setting_currency_format),
            valueText = "${prefs.primaryCurrencyCode} \u2022 ${if (prefs.numberGrouping == NumberGroupingSystem.INDIAN) "12,34,567" else "1,234,567"}",
            enabled = isNotBusy,
            onClick = onOpenRegionalFormat
        )
        HorizontalDivider(
            color = semanticColors.cardStrokeSubtle,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        SettingsNavigationRow(
            icon = Icons.Default.Public,
            title = stringResource(R.string.setting_secondary_currencies),
            valueText = when {
                prefs.secondaryCurrencies.isEmpty() -> "None configured"
                prefs.secondaryCurrencies.size == 1 -> {
                    val sec = prefs.secondaryCurrencies.first()
                    "${sec.code} (1 = ${formatExchangeRatePlain(sec.exchangeRateToPrimary)})"
                }
                else -> "${prefs.secondaryCurrencies.size} active (${prefs.secondaryCurrencies.joinToString(", ") { it.code }})"
            },
            enabled = isNotBusy,
            onClick = onOpenSecondaryCurrencies
        )
        HorizontalDivider(
            color = semanticColors.cardStrokeSubtle,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        SettingsNavigationRow(
            icon = Icons.Default.CalendarMonth,
            title = stringResource(R.string.setting_month_start_day),
            valueText = if (prefs.monthStartDay == 1) "1st of month" else "Day ${prefs.monthStartDay}",
            enabled = isNotBusy,
            onClick = onOpenMonthStartDayDialog
        )
        HorizontalDivider(
            color = semanticColors.cardStrokeSubtle,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        SettingsSwitchRow(
            icon = Icons.Default.SwapHoriz,
            title = stringResource(R.string.ledger_banner_carry_over),
            checked = prefs.carryOverEnabled,
            enabled = isNotBusy,
            onCheckedChange = onToggleCarryOver
        )
    }
}

@Composable
fun CategoriesCommitmentsSettingsCard(
    prefs: UserPreferences,
    uiState: SettingsUiState,
    isNotBusy: Boolean,
    onOpenCategories: () -> Unit,
    onOpenTagsAndBookmarks: () -> Unit,
    onOpenRecurringManager: () -> Unit,
    modifier: Modifier = Modifier
) {
    val semanticColors = LocalFolioSemanticColors.current

    SettingsGroupCard(
        title = stringResource(R.string.settings_group_taxonomy),
        modifier = modifier
    ) {
        val categoryCount = if (prefs.subcategoriesEnabled) {
            uiState.totalExpenseCategoryCount + uiState.totalIncomeCategoryCount
        } else {
            uiState.expenseCategories.size + uiState.incomeCategories.size
        }
        val categorySummary = if (prefs.subcategoriesEnabled) {
            "$categoryCount active \u2022 2-level"
        } else {
            "$categoryCount active"
        }
        SettingsNavigationRow(
            icon = Icons.Default.Category,
            title = stringResource(R.string.setting_categories),
            valueText = categorySummary,
            enabled = isNotBusy,
            onClick = onOpenCategories
        )
        HorizontalDivider(
            color = semanticColors.cardStrokeSubtle,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        SettingsNavigationRow(
            icon = Icons.AutoMirrored.Filled.Label,
            title = stringResource(R.string.setting_tags_bookmarks),
            valueText = "${uiState.tags.size} tags \u2022 ${uiState.bookmarks.size} saved",
            enabled = isNotBusy,
            onClick = onOpenTagsAndBookmarks
        )
        HorizontalDivider(
            color = semanticColors.cardStrokeSubtle,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        SettingsNavigationRow(
            icon = Icons.Default.Autorenew,
            title = stringResource(R.string.setting_recurring_subscriptions),
            valueText = "${uiState.activeRecurringCount} active \u2022 ${uiState.formattedMonthlyBurden}/mo",
            enabled = isNotBusy,
            onClick = onOpenRecurringManager
        )
    }
}

@Composable
fun AppearanceLanguageSettingsCard(
    prefs: UserPreferences,
    isNotBusy: Boolean,
    currentLanguageDisplayName: String,
    onOpenAppearanceSheet: () -> Unit,
    onOpenIconPackSheet: () -> Unit,
    onOpenLanguageSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val semanticColors = LocalFolioSemanticColors.current

    SettingsGroupCard(
        title = stringResource(R.string.settings_group_appearance),
        modifier = modifier
    ) {
        val themeModeObj = FolioThemeMode.fromKey(prefs.themeMode)
        val accentPaletteObj = FolioAccentPalette.fromKey(prefs.accentPalette)
        SettingsNavigationRow(
            icon = Icons.Default.Palette,
            title = stringResource(R.string.setting_appearance_theme),
            valueText = themeModeObj.displayName,
            valueColorDot = accentPaletteObj.previewColor,
            enabled = isNotBusy,
            onClick = onOpenAppearanceSheet
        )
        HorizontalDivider(
            color = semanticColors.cardStrokeSubtle,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        val iconPackThemeObj = FolioIconPackTheme.fromKey(prefs.iconPackTheme)
        SettingsNavigationRow(
            icon = Icons.Default.GridView,
            title = stringResource(R.string.setting_icon_style),
            valueText = iconPackThemeObj.displayName,
            enabled = isNotBusy,
            onClick = onOpenIconPackSheet
        )
        HorizontalDivider(
            color = semanticColors.cardStrokeSubtle,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        SettingsNavigationRow(
            icon = Icons.Default.Language,
            title = stringResource(R.string.setting_app_language),
            valueText = currentLanguageDisplayName,
            enabled = isNotBusy,
            onClick = onOpenLanguageSheet
        )
    }
}

@Composable
fun RemindersNotificationsSettingsCard(
    prefs: UserPreferences,
    isNotBusy: Boolean,
    onToggleReminders: (Boolean) -> Unit,
    onOpenDueRemindersCustomizer: () -> Unit,
    onToggleDailyLogReminder: (Boolean) -> Unit,
    onOpenDailyLogCustomizer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val semanticColors = LocalFolioSemanticColors.current
    val is24Hour = remember(context) { DateFormat.is24HourFormat(context) }

    SettingsGroupCard(
        title = stringResource(R.string.settings_group_notifications),
        modifier = modifier
    ) {
        SettingsSwitchRow(
            icon = Icons.Default.Notifications,
            title = stringResource(R.string.setting_due_reminders),
            subtitle = stringResource(R.string.setting_due_reminders_desc),
            checked = prefs.remindersEnabled,
            enabled = isNotBusy,
            onCheckedChange = onToggleReminders
        )

        AnimatedVisibility(
            visible = prefs.remindersEnabled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                HorizontalDivider(
                    color = semanticColors.cardStrokeSubtle,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                SettingsNavigationRow(
                    icon = Icons.Default.Tune,
                    title = stringResource(R.string.setting_due_reminders_customize),
                    subtitle = formatDueRemindersSummary(prefs, is24Hour),
                    valueText = null,
                    enabled = isNotBusy,
                    onClick = onOpenDueRemindersCustomizer
                )
            }
        }

        HorizontalDivider(
            color = semanticColors.cardStrokeSubtle,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        SettingsSwitchRow(
            icon = Icons.Default.AccessTime,
            title = stringResource(R.string.setting_daily_log_reminder),
            subtitle = stringResource(R.string.setting_daily_log_reminder_desc),
            checked = prefs.dailyLogReminderEnabled,
            enabled = isNotBusy,
            onCheckedChange = onToggleDailyLogReminder
        )

        AnimatedVisibility(
            visible = prefs.dailyLogReminderEnabled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                HorizontalDivider(
                    color = semanticColors.cardStrokeSubtle,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                val dailyTimeText = formatReminderTimeDisplay(
                    hourOfDay = prefs.dailyLogReminderHour,
                    minute = prefs.dailyLogReminderMinute,
                    is24Hour = is24Hour
                )
                val daysSummary = DailyLogReminderCalculator.formatDaysSummary(prefs.dailyLogReminderDaysMask)
                val snoozeSummary = DailyLogReminderCalculator.formatSnoozeLabel(prefs.dailyLogReminderSnoozeMinutes)
                SettingsNavigationRow(
                    icon = Icons.Default.Edit,
                    title = stringResource(R.string.setting_daily_log_customize),
                    subtitle = "$dailyTimeText \u2022 $daysSummary \u2022 Snooze $snoozeSummary",
                    valueText = null,
                    enabled = isNotBusy,
                    onClick = onOpenDailyLogCustomizer
                )
            }
        }
    }
}

@Composable
fun SecurityPrivacySettingsCard(
    prefs: UserPreferences,
    isNotBusy: Boolean,
    onToggleBiometricLock: (Boolean) -> Unit,
    onToggleHideAmounts: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val semanticColors = LocalFolioSemanticColors.current

    SettingsGroupCard(
        title = stringResource(R.string.settings_group_security),
        modifier = modifier
    ) {
        SettingsSwitchRow(
            icon = Icons.Default.Fingerprint,
            title = stringResource(R.string.setting_app_lock),
            checked = prefs.biometricLockEnabled,
            enabled = isNotBusy,
            onCheckedChange = onToggleBiometricLock
        )
        HorizontalDivider(
            color = semanticColors.cardStrokeSubtle,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        SettingsSwitchRow(
            icon = Icons.Default.Lock,
            title = stringResource(R.string.setting_mask_notification_amounts),
            checked = prefs.hideAmountsOnLockScreen,
            enabled = isNotBusy,
            onCheckedChange = onToggleHideAmounts
        )
    }
}

@Composable
fun BackupsDataExportSettingsCard(
    prefs: UserPreferences,
    isNotBusy: Boolean,
    onOpenAutoBackupSheet: () -> Unit,
    onOpenTransferBackupSheet: () -> Unit,
    onOpenPdfReportsSheet: () -> Unit,
    onOpenSpreadsheetsSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val semanticColors = LocalFolioSemanticColors.current
    val canMutateStorage = isNotBusy && !prefs.isDemoModeActive

    SettingsGroupCard(
        title = stringResource(R.string.settings_group_backups),
        modifier = modifier
    ) {
        val hasSafAlert = !prefs.safMirrorTreeUri.isNullOrBlank() && !prefs.safMirrorErrorCode.isNullOrBlank()
        SettingsNavigationRow(
            icon = Icons.Default.History,
            title = stringResource(R.string.setting_auto_backup),
            valueText = formatAutoBackupSummary(prefs),
            valueColorDot = if (!prefs.isDemoModeActive && prefs.autoBackupEnabled && !hasSafAlert) {
                semanticColors.incomeEmerald
            } else {
                semanticColors.warningAmber
            },
            enabled = canMutateStorage,
            onClick = onOpenAutoBackupSheet
        )
        HorizontalDivider(
            color = semanticColors.cardStrokeSubtle,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        val backupStatusText = if (prefs.isDemoModeActive) {
            "Disabled in Demo"
        } else if (prefs.lastBackupEpochMillis != null) {
            formatBackupRelativeTime(prefs.lastBackupEpochMillis)
        } else {
            "Downloads/Folio"
        }
        SettingsNavigationRow(
            icon = Icons.Default.Backup,
            title = stringResource(R.string.setting_phone_migration),
            valueText = backupStatusText,
            valueColorDot = if (!prefs.isDemoModeActive && prefs.lastBackupEpochMillis != null) {
                semanticColors.incomeEmerald
            } else {
                null
            },
            enabled = canMutateStorage,
            onClick = onOpenTransferBackupSheet
        )
        HorizontalDivider(
            color = semanticColors.cardStrokeSubtle,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        SettingsNavigationRow(
            icon = Icons.Default.PictureAsPdf,
            title = stringResource(R.string.setting_pdf_statements),
            valueText = "Account & Tax Reports",
            enabled = isNotBusy,
            onClick = onOpenPdfReportsSheet
        )
        HorizontalDivider(
            color = semanticColors.cardStrokeSubtle,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        SettingsNavigationRow(
            icon = Icons.Default.TableChart,
            title = stringResource(R.string.setting_spreadsheets),
            valueText = if (prefs.isDemoModeActive) "Disabled in Demo" else "Export / Import",
            enabled = canMutateStorage,
            onClick = onOpenSpreadsheetsSheet
        )
    }
}

@Composable
fun HelpSandboxAboutSettingsCard(
    prefs: UserPreferences,
    isNotBusy: Boolean,
    onOpenUserGuideSheet: () -> Unit,
    onOpenFeedbackSheet: () -> Unit,
    onRateApp: () -> Unit,
    onOpenTipJarSheet: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit = {},
    onToggleDemoModeClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val semanticColors = LocalFolioSemanticColors.current

    SettingsGroupCard(
        title = stringResource(R.string.settings_group_community),
        modifier = modifier
    ) {
        SettingsNavigationRow(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            title = stringResource(R.string.setting_user_guide),
            valueText = null,
            enabled = isNotBusy,
            onClick = onOpenUserGuideSheet
        )
        HorizontalDivider(
            color = semanticColors.cardStrokeSubtle,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        if (prefs.isDemoModeActive) {
            SettingsNavigationRow(
                icon = Icons.Default.PlayArrow,
                title = stringResource(
                    if (prefs.hasStashedRealLedger) {
                        R.string.setting_demo_exit_restore
                    } else {
                        R.string.setting_demo_exit_fresh
                    }
                ),
                valueText = "Active",
                valueColorDot = semanticColors.warningAmber,
                enabled = isNotBusy,
                onClick = onToggleDemoModeClick
            )
        } else {
            SettingsNavigationRow(
                icon = Icons.Default.PlayArrow,
                title = stringResource(R.string.setting_demo_sandbox),
                valueText = "Sandbox",
                valueColorDot = semanticColors.incomeEmerald,
                enabled = isNotBusy,
                onClick = onToggleDemoModeClick
            )
        }
        HorizontalDivider(
            color = semanticColors.cardStrokeSubtle,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        SettingsNavigationRow(
            icon = Icons.Default.Feedback,
            title = stringResource(R.string.setting_feedback),
            valueText = null,
            enabled = isNotBusy,
            onClick = onOpenFeedbackSheet
        )
        HorizontalDivider(
            color = semanticColors.cardStrokeSubtle,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        SettingsNavigationRow(
            icon = Icons.Filled.Star,
            title = stringResource(R.string.setting_rate_app),
            valueText = "\u2605 \u2605 \u2605 \u2605 \u2605",
            valueColorDot = semanticColors.warningAmber,
            enabled = isNotBusy,
            onClick = onRateApp
        )
        HorizontalDivider(
            color = semanticColors.cardStrokeSubtle,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        val activeSupporterTier = SupporterTier.fromSkuOrTitle(prefs.supporterTier)
            ?: if (prefs.isSupporter) SupporterTier.GENEROUS_SUPPORTER else null

        SettingsNavigationRow(
            icon = Icons.Default.LocalCafe,
            title = stringResource(R.string.setting_tip_jar),
            valueText = activeSupporterTier?.badgeLabel ?: (if (prefs.isSupporter) "\u2605 Unlocked" else null),
            valueColorDot = activeSupporterTier?.let { parseHexColorSafely(it.accentHex) }
                ?: (if (prefs.isSupporter) semanticColors.warningAmber else null),
            enabled = isNotBusy,
            onClick = onOpenTipJarSheet
        )
        HorizontalDivider(
            color = semanticColors.cardStrokeSubtle,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        SettingsNavigationRow(
            icon = Icons.Default.Shield,
            title = stringResource(R.string.setting_privacy_policy),
            subtitle = stringResource(R.string.setting_privacy_policy_subtitle),
            valueText = null,
            enabled = isNotBusy,
            onClick = onOpenPrivacyPolicy
        )
    }
}

@Composable
fun DangerZoneSettingsCard(
    prefs: UserPreferences,
    isNotBusy: Boolean,
    onOpenClearAllDataDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canMutateStorage = isNotBusy && !prefs.isDemoModeActive
    SettingsGroupCard(
        title = stringResource(R.string.settings_group_danger_zone),
        headerColor = MaterialTheme.colorScheme.error,
        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
        modifier = modifier
    ) {
        SettingsNavigationRow(
            icon = Icons.Default.DeleteForever,
            title = stringResource(R.string.setting_danger_reset),
            valueText = if (prefs.isDemoModeActive) "Disabled in Demo" else null,
            titleColor = MaterialTheme.colorScheme.error,
            iconTint = MaterialTheme.colorScheme.error,
            enabled = canMutateStorage,
            onClick = onOpenClearAllDataDialog
        )
    }
}

@Composable
fun SettingsFooter(
    prefs: UserPreferences,
    modifier: Modifier = Modifier
) {
    val semanticColors = LocalFolioSemanticColors.current
    val activeSupporterTier = SupporterTier.fromSkuOrTitle(prefs.supporterTier)
        ?: if (prefs.isSupporter) SupporterTier.GENEROUS_SUPPORTER else null

    val footerText = activeSupporterTier?.let {
        "Folio Ledger v${BuildConfig.VERSION_NAME} \u2022 ${it.footerEdition}"
    } ?: if (prefs.isSupporter) {
        "Folio Ledger v${BuildConfig.VERSION_NAME} \u2022 \u2605 Supporter Edition"
    } else {
        "Folio Ledger v${BuildConfig.VERSION_NAME} \u2022 100% Offline SQLite"
    }

    val footerColor = activeSupporterTier?.let { parseHexColorSafely(it.accentHex) }
        ?: if (prefs.isSupporter) semanticColors.warningAmber else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 24.dp)
    ) {
        Text(
            text = footerText,
            style = MaterialTheme.typography.labelSmall,
            color = footerColor,
            fontWeight = if (prefs.isSupporter) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.heightIn(min = 2.dp))
        Text(
            text = "Zero Cloud \u2022 Zero Ads \u2022 Local Device Privacy",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            textAlign = TextAlign.Center
        )
    }
}
