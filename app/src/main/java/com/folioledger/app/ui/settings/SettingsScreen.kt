/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/ui/settings/SettingsScreen.kt
package com.folioledger.app.ui.settings

import com.folioledger.app.BuildConfig

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import com.folioledger.app.R
import com.folioledger.app.domain.model.AutoBackupRetention
import com.folioledger.app.domain.model.ImportCsvPreview
import com.folioledger.app.domain.model.ImportCsvResult
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.SupporterTier
import com.folioledger.app.ui.components.FolioChromeIcons
import com.folioledger.app.ui.components.parseHexColorSafely
import androidx.appcompat.app.AppCompatDelegate
import com.folioledger.app.ui.settings.sheets.AppLanguageBottomSheet
import android.Manifest
import android.os.Build
import android.text.format.DateFormat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.folioledger.app.data.notification.DailyLogReminderScheduler
import com.folioledger.app.data.notification.DueRemindersScheduler
import com.folioledger.app.data.notification.FolioNotificationManager
import com.folioledger.app.domain.util.ZeroNetworkAuditEvaluator
import com.folioledger.app.ui.settings.components.AppearanceLanguageSettingsCard
import com.folioledger.app.ui.settings.components.BackupsDataExportSettingsCard
import com.folioledger.app.ui.settings.components.CategoriesCommitmentsSettingsCard
import com.folioledger.app.ui.settings.components.DangerZoneSettingsCard
import com.folioledger.app.ui.settings.components.HelpSandboxAboutSettingsCard
import com.folioledger.app.ui.settings.components.LedgerAccountingSettingsCard
import com.folioledger.app.ui.settings.components.RemindersNotificationsSettingsCard
import com.folioledger.app.ui.settings.components.SecurityPrivacySettingsCard
import com.folioledger.app.ui.settings.components.SettingsBannerMode
import com.folioledger.app.ui.settings.components.SettingsFooter
import com.folioledger.app.ui.settings.components.SettingsStatusBanner
import com.folioledger.app.ui.settings.components.resolveSettingsBannerMode
import com.folioledger.app.ui.settings.dialogs.CreateManualBackupDialog
import com.folioledger.app.ui.settings.dialogs.SetSafMirrorPasswordDialog
import com.folioledger.app.ui.settings.sheets.DueRemindersScheduleBottomSheet
import com.folioledger.app.ui.settings.components.openAppSystemPermissionsSettings
import com.folioledger.app.ui.settings.components.openFolioDownloadsFolder
import com.folioledger.app.ui.settings.components.shareBackupFile
import com.folioledger.app.ui.settings.dialogs.ClearAllDataConfirmationDialog
import com.folioledger.app.ui.settings.dialogs.FileExportSuccessDialog
import com.folioledger.app.ui.settings.dialogs.ImportCsvPreviewDialog
import com.folioledger.app.ui.settings.dialogs.ImportCsvResultDialog
import com.folioledger.app.ui.settings.dialogs.MonthStartDayPickerDialog
import com.folioledger.app.ui.settings.sheets.CategoryDeleteConfirmationDialog
import com.folioledger.app.ui.settings.dialogs.RestoreConfirmationDialog
import com.folioledger.app.ui.settings.sheets.FolioLocaleManager
import com.folioledger.app.ui.settings.sheets.AppearanceBottomSheet
import com.folioledger.app.ui.settings.sheets.DailyLogReminderBottomSheet
import com.folioledger.app.ui.settings.sheets.resolveCurrentLanguageDisplayName
import com.folioledger.app.ui.settings.sheets.AutoBackupBottomSheet
import com.folioledger.app.ui.settings.sheets.CategoryEditorDialog
import com.folioledger.app.ui.settings.sheets.CategoryManagerBottomSheet
import com.folioledger.app.ui.settings.sheets.FeedbackBottomSheet
import com.folioledger.app.ui.settings.sheets.PRIVACY_POLICY_URL
import com.folioledger.app.ui.settings.sheets.PrivacyPolicyBottomSheet
import com.folioledger.app.ui.settings.sheets.PdfReportsBottomSheet
import com.folioledger.app.ui.settings.sheets.RecurringManagerBottomSheet
import com.folioledger.app.ui.settings.sheets.RegionalFormatBottomSheet
import com.folioledger.app.ui.settings.sheets.SecondaryCurrenciesBottomSheet
import com.folioledger.app.domain.model.CsvImportPreset
import com.folioledger.app.ui.settings.sheets.MigrateFromAppBottomSheet
import com.folioledger.app.ui.theme.findActivity
import com.folioledger.app.ui.settings.sheets.SpreadsheetsBottomSheet
import com.folioledger.app.ui.settings.sheets.TagsAndBookmarksBottomSheet
import com.folioledger.app.ui.settings.sheets.TransferAndManualBackupBottomSheet
import com.folioledger.app.ui.theme.FolioAccentPalette
import com.folioledger.app.ui.theme.FolioThemeMode
import com.folioledger.app.ui.iconpack.FolioIconPackTheme
import com.folioledger.app.ui.settings.sheets.IconPackBottomSheet
import com.folioledger.app.ui.theme.tabularMoneyStyle
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.folioledger.app.ui.settings.sheets.formatBackupRelativeTime
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var isAppearanceSheetOpen by rememberSaveable { mutableStateOf(false) }
    var isIconPackSheetOpen by rememberSaveable { mutableStateOf(false) }
    var isLanguageSheetOpen by rememberSaveable { mutableStateOf(false) }
    var isRegionalFormatSheetOpen by rememberSaveable { mutableStateOf(false) }
    var isSecondaryCurrenciesSheetOpen by rememberSaveable { mutableStateOf(false) }
    var isAutoBackupSheetOpen by rememberSaveable { mutableStateOf(false) }
    var isTransferBackupSheetOpen by rememberSaveable { mutableStateOf(false) }
    var isSpreadsheetsSheetOpen by rememberSaveable { mutableStateOf(false) }
    var isTagsAndBookmarksSheetOpen by rememberSaveable { mutableStateOf(false) }
    var showDemoModeConfirmDialog by rememberSaveable { mutableStateOf(false) }

    var showMonthStartDayDialog by rememberSaveable { mutableStateOf(false) }
    var showDueRemindersScheduleSheet by rememberSaveable { mutableStateOf(false) }
    var showDailyLogReminderSheet by rememberSaveable { mutableStateOf(false) }
    var pendingPermissionTarget by rememberSaveable { mutableStateOf<String?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val target = pendingPermissionTarget
        pendingPermissionTarget = null
        if (isGranted) {
            when (target) {
                "DAILY_LOG" -> {
                    viewModel.setDailyLogReminderEnabled(true)
                    DailyLogReminderScheduler.scheduleNextDaily(
                        context = context,
                        hour = uiState.userPreferences.dailyLogReminderHour,
                        minute = uiState.userPreferences.dailyLogReminderMinute,
                        daysMask = uiState.userPreferences.dailyLogReminderDaysMask
                    )
                }
                "DUE_REMINDERS" -> {
                    viewModel.setRemindersEnabled(true)
                    DueRemindersScheduler.rescheduleDailyReminders(
                        context = context,
                        hourOfDay = uiState.userPreferences.reminderHourOfDay,
                        minute = uiState.userPreferences.reminderMinute
                    )
                }
            }
        } else {
            when (target) {
                "DAILY_LOG" -> viewModel.setDailyLogReminderEnabled(false)
                "DUE_REMINDERS" -> viewModel.setRemindersEnabled(false)
            }
            Toast.makeText(context, "Notification permission is required for reminders", Toast.LENGTH_SHORT).show()
        }
    }

    var isMigrateSheetOpen by rememberSaveable { mutableStateOf(false) }
    var pendingImportPreset by rememberSaveable { mutableStateOf(CsvImportPreset.AUTO_DETECT) }

    val csvDocumentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.loadCsvPreview(uri.toString(), pendingImportPreset)
            pendingImportPreset = CsvImportPreset.AUTO_DETECT
        }
    }

    val zipDocumentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.requestRestoreConfirmation(uri.toString())
        }
    }

    var showCreateManualBackupDialog by rememberSaveable { mutableStateOf(false) }
    var showSetSafMirrorPasswordDialog by rememberSaveable { mutableStateOf(false) }
    var showClearAllDataDialog by rememberSaveable { mutableStateOf(false) }

    val safTreePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val granted = runCatching {
                context.contentResolver.takePersistableUriPermission(treeUri, flags)
            }.isSuccess
            if (!granted) {
                viewModel.showCustomSnackbar(
                    "Selected folder does not allow persistent write access. Choose a local or synced folder."
                )
                return@rememberLauncherForActivityResult
            }
            viewModel.configureSafMirrorFolder(
                treeUriString = treeUri.toString(),
                onRequirePassphrase = { showSetSafMirrorPasswordDialog = true }
            )
        }
    }
    var showUserGuideSheet by rememberSaveable { mutableStateOf(false) }
    var showFeedbackSheet by rememberSaveable { mutableStateOf(false) }
    var showTipJarSheet by rememberSaveable { mutableStateOf(false) }
    var showPrivacyPolicySheet by rememberSaveable { mutableStateOf(false) }

    val prefs = uiState.userPreferences

    fun requestSafEncryptionChange(enable: Boolean) {
        val action: () -> Unit = {
            if (enable) {
                showSetSafMirrorPasswordDialog = true
            } else {
                viewModel.disableSafMirrorEncryption()
            }
        }
        if (!prefs.biometricLockEnabled) {
            action()
            return
        }
        val activity = context.findActivity() as? androidx.fragment.app.FragmentActivity
        if (activity == null) {
            viewModel.showCustomSnackbar("Unable to launch authentication prompt from this screen.")
            return
        }
        launchAppLockPrompt(
            activity = activity,
            title = if (enable) "Enable Mirror Encryption" else "Disable Mirror Encryption",
            subtitle = "Verify your identity to modify external backup encryption",
            onSuccess = action,
            onNoDeviceLock = action,
            onError = { message -> viewModel.showCustomSnackbar(message) }
        )
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val message = uiState.snackbarMessage
        if (!message.isNullOrBlank()) {
            val fileToShare = uiState.lastCompletedFileForShare
            if (fileToShare != null) {
                val result = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = "Share",
                    duration = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed) {
                    shareBackupFile(context, fileToShare)
                }
                viewModel.clearLastCompletedFileForShare()
            } else {
                snackbarHostState.showSnackbar(message)
            }
            viewModel.clearSnackbarMessage()
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding())
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = contentPadding.calculateTopPadding() + scaffoldPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding()
                )
        ) {
            // Header Bar (blends seamlessly into screen background)
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_screen_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (uiState.isBusyWithFileOperation) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        gapSize = 0.dp
                    )
                }
            }

            val isNotBusy = !uiState.isBusyWithFileOperation

            val configuration = LocalConfiguration.current
            val currentLanguageDisplayName = remember(context, isLanguageSheetOpen, configuration) {
                val currentTag = FolioLocaleManager.getCurrentLanguageTag(context)
                resolveCurrentLanguageDisplayName(currentTag)
            }
            var is24Hour by remember(context) { mutableStateOf(DateFormat.is24HourFormat(context)) }
            LifecycleResumeEffect(context) {
                is24Hour = DateFormat.is24HourFormat(context)
                onPauseOrDispose {}
            }
            val zeroNetworkAudit = remember(context) {
                ZeroNetworkAuditEvaluator.inspectInstalledPackage(context)
            }

            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 32.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (resolveSettingsBannerMode(prefs) != SettingsBannerMode.NONE) {
                    item(key = "status_banner") {
                        SettingsStatusBanner(
                            prefs = prefs,
                            isBusy = !isNotBusy,
                            onExitDemoClick = { showDemoModeConfirmDialog = true },
                            onReconnectSafFolder = { safTreePicker.launch(null) },
                            onSetSafPassphrase = { requestSafEncryptionChange(true) },
                            onRetrySafMirror = viewModel::triggerSafMirrorNow,
                            modifier = Modifier.animateItem()
                        )
                    }
                }

                item(key = "ledger_accounting") {
                    LedgerAccountingSettingsCard(
                        prefs = prefs,
                        isNotBusy = isNotBusy,
                        onOpenRegionalFormat = { isRegionalFormatSheetOpen = true },
                        onOpenSecondaryCurrencies = { isSecondaryCurrenciesSheetOpen = true },
                        onOpenMonthStartDayDialog = { showMonthStartDayDialog = true },
                        onToggleCarryOver = viewModel::setCarryOverEnabled
                    )
                }

                item(key = "categories_commitments") {
                    val categoryCount = if (prefs.subcategoriesEnabled) {
                        uiState.totalExpenseCategoryCount + uiState.totalIncomeCategoryCount
                    } else {
                        uiState.expenseCategories.size + uiState.incomeCategories.size
                    }
                    CategoriesCommitmentsSettingsCard(
                        categoryCount = categoryCount,
                        subcategoriesEnabled = prefs.subcategoriesEnabled,
                        tagsCount = uiState.tags.size,
                        bookmarksCount = uiState.bookmarks.size,
                        activeRecurringCount = uiState.activeRecurringCount,
                        formattedMonthlyBurden = uiState.formattedMonthlyBurden,
                        isNotBusy = isNotBusy,
                        onOpenCategories = { viewModel.setCategoryManagerOpen(true) },
                        onOpenTagsAndBookmarks = { isTagsAndBookmarksSheetOpen = true },
                        onOpenRecurringManager = { viewModel.setRecurringManagerOpen(true) }
                    )
                }

                item(key = "appearance_language") {
                    AppearanceLanguageSettingsCard(
                        prefs = prefs,
                        isNotBusy = isNotBusy,
                        currentLanguageDisplayName = currentLanguageDisplayName,
                        onOpenAppearanceSheet = { isAppearanceSheetOpen = true },
                        onOpenIconPackSheet = { isIconPackSheetOpen = true },
                        onOpenLanguageSheet = { isLanguageSheetOpen = true }
                    )
                }

                item(key = "reminders_notifications") {
                    RemindersNotificationsSettingsCard(
                        prefs = prefs,
                        is24Hour = is24Hour,
                        isNotBusy = isNotBusy,
                        onToggleReminders = { enabled ->
                            if (enabled) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    !FolioNotificationManager.hasNotificationPermission(context)
                                ) {
                                    pendingPermissionTarget = "DUE_REMINDERS"
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    viewModel.setRemindersEnabled(true)
                                    DueRemindersScheduler.rescheduleDailyReminders(
                                        context = context,
                                        hourOfDay = prefs.reminderHourOfDay,
                                        minute = prefs.reminderMinute
                                    )
                                }
                            } else {
                                viewModel.setRemindersEnabled(false)
                                // Keep the background worker scheduled for silent recurring rule processing
                            }
                        },
                        onOpenDueRemindersCustomizer = { showDueRemindersScheduleSheet = true },
                        onToggleDailyLogReminder = { enabled ->
                            if (enabled) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    !FolioNotificationManager.hasNotificationPermission(context)
                                ) {
                                    pendingPermissionTarget = "DAILY_LOG"
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    viewModel.setDailyLogReminderEnabled(true)
                                    DailyLogReminderScheduler.scheduleNextDaily(
                                        context = context,
                                        hour = prefs.dailyLogReminderHour,
                                        minute = prefs.dailyLogReminderMinute,
                                        daysMask = prefs.dailyLogReminderDaysMask
                                    )
                                }
                            } else {
                                viewModel.setDailyLogReminderEnabled(false)
                                DailyLogReminderScheduler.cancelAll(context)
                            }
                        },
                        onOpenDailyLogCustomizer = { showDailyLogReminderSheet = true }
                    )
                }

                item(key = "security_privacy") {
                    SecurityPrivacySettingsCard(
                        prefs = prefs,
                        zeroNetworkAudit = zeroNetworkAudit,
                        isNotBusy = isNotBusy,
                        onOpenAppSystemSettings = { openAppSystemPermissionsSettings(context) },
                        onToggleBiometricLock = { enabled ->
                            val activity = context.findActivity() as? androidx.fragment.app.FragmentActivity
                            if (activity == null) {
                                viewModel.showCustomSnackbar("Unable to launch authentication prompt from this screen.")
                            } else {
                                launchAppLockPrompt(
                                    activity = activity,
                                    title = if (enabled) "Enable Folio App Lock" else "Disable Folio App Lock",
                                    subtitle = if (enabled) {
                                        "Verify your identity to lock Folio on launch"
                                    } else {
                                        "Verify your identity to remove App Lock"
                                    },
                                    onSuccess = {
                                        viewModel.setBiometricLockEnabled(enabled)
                                    },
                                    onNoDeviceLock = {
                                        if (enabled) {
                                            viewModel.showCustomSnackbar("Please set up a device screen lock (PIN, pattern, or biometric) in device settings first.")
                                        } else {
                                            viewModel.setBiometricLockEnabled(false)
                                        }
                                    },
                                    onError = { message ->
                                        viewModel.showCustomSnackbar(message)
                                    }
                                )
                            }
                        },
                        onToggleHideAmounts = viewModel::setHideAmountsOnLockScreen
                    )
                }

                item(key = "backups_data_export") {
                    BackupsDataExportSettingsCard(
                        prefs = prefs,
                        isNotBusy = isNotBusy,
                        onOpenAutoBackupSheet = { isAutoBackupSheetOpen = true },
                        onOpenTransferBackupSheet = { isTransferBackupSheetOpen = true },
                        onOpenPdfReportsSheet = viewModel::openPdfReportsSheet,
                        onOpenSpreadsheetsSheet = { isSpreadsheetsSheetOpen = true }
                    )
                }

                item(key = "help_sandbox_about") {
                    HelpSandboxAboutSettingsCard(
                        prefs = prefs,
                        isNotBusy = isNotBusy,
                        onOpenUserGuideSheet = { showUserGuideSheet = true },
                        onOpenFeedbackSheet = { showFeedbackSheet = true },
                        onRateApp = {
                            val pkgName = context.packageName
                            val marketIntent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("market://details?id=$pkgName")
                            ).apply {
                                addFlags(
                                    android.content.Intent.FLAG_ACTIVITY_NO_HISTORY or
                                        android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                                        android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                                )
                            }
                            try {
                                context.startActivity(marketIntent)
                            } catch (e: android.content.ActivityNotFoundException) {
                                val webIntent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkgName")
                                )
                                try {
                                    context.startActivity(webIntent)
                                } catch (_: Exception) {
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.setting_rate_app_not_ready),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        onOpenTipJarSheet = { showTipJarSheet = true },
                        onOpenPrivacyPolicy = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(PRIVACY_POLICY_URL)
                            ).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching {
                                context.startActivity(intent)
                            }.onFailure {
                                showPrivacyPolicySheet = true
                            }
                        },
                        onToggleDemoModeClick = { showDemoModeConfirmDialog = true }
                    )
                }

                item(key = "danger_zone") {
                    DangerZoneSettingsCard(
                        prefs = prefs,
                        isNotBusy = isNotBusy,
                        onOpenClearAllDataDialog = { showClearAllDataDialog = true }
                    )
                }

                item(key = "footer") {
                    SettingsFooter(prefs = prefs)
                }
            }
        }
    }

    val isAppLocked = com.folioledger.app.ui.theme.LocalIsAppLocked.current
    if (!isAppLocked) {
    // 3 New Bottom Sheets
    if (isIconPackSheetOpen) {
        IconPackBottomSheet(
            currentThemeKey = prefs.iconPackTheme,
            onSelectTheme = { selected ->
                viewModel.setIconPackTheme(selected.key)
            },
            onDismiss = { isIconPackSheetOpen = false }
        )
    }

    if (isAppearanceSheetOpen) {
        AppearanceBottomSheet(
            prefs = prefs,
            onSelectThemeModeString = viewModel::setThemeMode,
            onSelectAccentPaletteString = viewModel::setAccentPalette,
            onToggleDynamicColor = viewModel::setDynamicColorEnabled,
            onDismiss = { isAppearanceSheetOpen = false }
        )
    }

    if (isLanguageSheetOpen) {
        AppLanguageBottomSheet(
            onDismissRequest = { isLanguageSheetOpen = false }
        )
    }

    if (isRegionalFormatSheetOpen) {
        RegionalFormatBottomSheet(
            prefs = prefs,
            allAccounts = uiState.allAccounts,
            onSelectCurrency = viewModel::setPrimaryCurrency,
            onSelectGroupingStyle = viewModel::setNumberGrouping,
            onToggleHidePrimaryCurrencySymbol = viewModel::setHidePrimaryCurrencySymbol,
            onSelectWeekStartDayIso = viewModel::setWeekStartDayIso,
            onDismiss = { isRegionalFormatSheetOpen = false }
        )
    }

    if (isSecondaryCurrenciesSheetOpen) {
        SecondaryCurrenciesBottomSheet(
            prefs = prefs,
            allAccounts = uiState.allAccounts,
            onAddOrUpdateSecondaryCurrency = viewModel::addOrUpdateSecondaryCurrency,
            onRemoveSecondaryCurrency = viewModel::removeSecondaryCurrency,
            onDismiss = { isSecondaryCurrenciesSheetOpen = false }
        )
    }

    LaunchedEffect(isAutoBackupSheetOpen, isTransferBackupSheetOpen) {
        if (isAutoBackupSheetOpen || isTransferBackupSheetOpen) {
            viewModel.refreshAutoBackupFiles()
        }
    }

    if (isAutoBackupSheetOpen) {
        AutoBackupBottomSheet(
            autoBackupEnabled = prefs.autoBackupEnabled,
            autoBackupPublicMirrorEnabled = prefs.autoBackupPublicMirrorEnabled,
            isAppLockActive = prefs.biometricLockEnabled,
            autoBackupRetention = prefs.autoBackupRetention,
            autoBackupSnapshots = uiState.autoBackupSnapshots,
            safMirrorEnabled = prefs.safMirrorEnabled,
            safMirrorTreeUri = prefs.safMirrorTreeUri,
            safMirrorFolderDisplayName = prefs.safMirrorFolderDisplayName,
            safMirrorEncryptEnabled = prefs.safMirrorEncryptEnabled,
            lastSafMirrorEpochMillis = prefs.lastSafMirrorEpochMillis,
            safMirrorErrorCode = prefs.safMirrorErrorCode,
            isBusy = uiState.isBusyWithFileOperation,
            onToggleAutoBackup = viewModel::setAutoBackupEnabled,
            onTogglePublicMirror = viewModel::setAutoBackupPublicMirrorEnabled,
            onSelectRetention = viewModel::setAutoBackupRetention,
            onPickSafFolder = { safTreePicker.launch(null) },
            onToggleSafMirror = viewModel::setSafMirrorEnabled,
            onDisconnectSafFolder = viewModel::disconnectSafMirrorFolder,
            onToggleSafEncrypt = ::requestSafEncryptionChange,
            onMirrorNowClick = viewModel::triggerSafMirrorNow,
            onRestoreAutoBackupFile = { file ->
                viewModel.requestRestoreConfirmation(file.absolutePath)
            },
            onShareBackupFile = { file ->
                shareBackupFile(context, file)
            },
            onDismiss = { isAutoBackupSheetOpen = false }
        )
    }

    if (isTransferBackupSheetOpen) {
        TransferAndManualBackupBottomSheet(
            lastBackupEpochMillis = prefs.lastBackupEpochMillis,
            isPublicMirrorActive = !prefs.biometricLockEnabled &&
                prefs.autoBackupPublicMirrorEnabled,
            isBusy = uiState.isBusyWithFileOperation,
            onCreateBackup = {
                showCreateManualBackupDialog = true
            },
            onRestoreLatestBackup = {
                viewModel.requestRestoreConfirmation("LATEST_BACKUP")
            },
            onSelectBackupFile = {
                zipDocumentPicker.launch(
                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")
                )
            },
            onShareLatestBackup = {
                viewModel.shareLatestBackup { file ->
                    shareBackupFile(context, file)
                }
            },
            onDismiss = { isTransferBackupSheetOpen = false }
        )
    }

    if (isSpreadsheetsSheetOpen) {
        SpreadsheetsBottomSheet(
            isBusy = uiState.isBusyWithFileOperation,
            onExportCsv = {
                viewModel.exportAllTransactionsToCsv(
                    "Folio_Ledger_Export_${LocalDate.now()}.csv"
                )
            },
            onImportCsv = {
                pendingImportPreset = CsvImportPreset.AUTO_DETECT
                csvDocumentPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/tab-separated-values", "text/plain", "application/vnd.ms-excel", "*/*"))
            },
            onMigrateFromApp = {
                isMigrateSheetOpen = true
            },
            onDownloadTemplate = {
                viewModel.downloadCsvTemplate("Folio_Import_Template.csv")
            },
            onDismiss = { isSpreadsheetsSheetOpen = false }
        )
    }

    if (isMigrateSheetOpen) {
        MigrateFromAppBottomSheet(
            isBusy = uiState.isBusyWithFileOperation,
            onSelectPresetAndPickFile = { preset ->
                pendingImportPreset = preset
                csvDocumentPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/tab-separated-values", "text/plain", "application/vnd.ms-excel", "*/*"))
            },
            onDismiss = { isMigrateSheetOpen = false }
        )
    }

    if (uiState.isPdfReportsSheetOpen) {
        PdfReportsBottomSheet(
            prefs = prefs,
            accounts = uiState.accounts,
            allLedgerTags = uiState.tags,
            isGeneratingPdf = uiState.isGeneratingPdf,
            lastGeneratedPdfResult = uiState.lastGeneratedPdfResult,
            onGenerateAccountStatement = viewModel::generateAccountStatementPdf,
            onGenerateAnnualTaxStatement = viewModel::generateAnnualTaxStatementPdf,
            onSaveCopyToUri = { file, uri ->
                viewModel.copyExportedFileToUri(file, uri.toString())
            },
            onClearLastResult = viewModel::clearLastGeneratedPdfResult,
            onDismiss = viewModel::closePdfReportsSheet
        )
    }

    // Dialogs & Secondary Sheets
    uiState.csvPreview?.let { preview ->
        androidx.compose.runtime.key(preview.sourceUriString) {
            ImportCsvPreviewDialog(
                preview = preview,
                isBusy = uiState.isBusyWithFileOperation,
                onConfirmImport = { skipDuplicates ->
                    viewModel.confirmCsvImport(skipDuplicates)
                },
                onChangePreset = viewModel::repreviewCsvWithPreset,
                onDismiss = viewModel::dismissCsvPreview
            )
        }
    }

    uiState.csvImportResult?.let { result ->
        ImportCsvResultDialog(
            result = result,
            onDismiss = viewModel::dismissCsvImportResult
        )
    }

    uiState.lastExportedFileSuccess?.let { exportSuccess ->
        FileExportSuccessDialog(
            model = exportSuccess,
            onShowInFiles = {
                openFolioDownloadsFolder(context)
                viewModel.clearLastExportedFileSuccess()
            },
            onShare = {
                exportSuccess.file?.let { file ->
                    shareBackupFile(context, file)
                    viewModel.clearLastExportedFileSuccess()
                } ?: Toast.makeText(context, "File sharing not available for this export", Toast.LENGTH_SHORT).show()
            },
            onSaveCopyToUri = { file, uri ->
                viewModel.copyExportedFileToUri(file, uri.toString())
            },
            onDismiss = viewModel::clearLastExportedFileSuccess
        )
    }

    if (showClearAllDataDialog) {
        ClearAllDataConfirmationDialog(
            isBusy = uiState.isBusyWithFileOperation,
            onConfirmClear = {
                showClearAllDataDialog = false
                viewModel.clearAllData()
            },
            onDismiss = {
                if (!uiState.isBusyWithFileOperation) {
                    showClearAllDataDialog = false
                }
            }
        )
    }

    if (showDemoModeConfirmDialog) {
        val isDemoActive = prefs.isDemoModeActive
        val hasStash = prefs.hasStashedRealLedger
        AlertDialog(
            onDismissRequest = {
                if (!uiState.isBusyWithFileOperation) {
                    showDemoModeConfirmDialog = false
                }
            },
            icon = {
                Icon(
                    imageVector = FolioChromeIcons.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = if (isDemoActive) {
                        if (hasStash) "Exit Demo Sandbox?" else "Reset Demo Data?"
                    } else {
                        "Launch Interactive Demo Mode?"
                    },
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (isDemoActive) {
                        if (hasStash) {
                            "Your real ledger will be restored from your private internal stash. Any transactions or edits created during this demo session will be discarded."
                        } else {
                            "This will wipe the sample demo data and reset your ledger so you can start tracking your personal finances."
                        }
                    } else {
                        "Folio will safely stash your current ledger in protected storage, pause auto-backups, and load a rich sample dataset with accounts, charts, credit card float tracking, and passbooks. You can restore your real ledger at any time."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDemoModeConfirmDialog = false
                        if (isDemoActive) {
                            viewModel.exitDemoMode()
                        } else {
                            viewModel.enterDemoMode()
                        }
                    },
                    enabled = !uiState.isBusyWithFileOperation
                ) {
                    Text(
                        if (isDemoActive) {
                            if (hasStash) "Restore Real Ledger" else "Wipe & Start Fresh"
                        } else {
                            "Enter Demo Sandbox"
                        }
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showDemoModeConfirmDialog = false },
                    enabled = !uiState.isBusyWithFileOperation
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showMonthStartDayDialog) {
        MonthStartDayPickerDialog(
            selectedDay = uiState.userPreferences.monthStartDay,
            onSelectDay = { day ->
                viewModel.setMonthStartDay(day)
                showMonthStartDayDialog = false
            },
            onDismiss = { showMonthStartDayDialog = false }
        )
    }

    if (showDueRemindersScheduleSheet) {
        DueRemindersScheduleBottomSheet(
            prefs = prefs,
            onSelectCcLeadDays = viewModel::setCcReminderLeadDays,
            onSelectBillLeadDays = viewModel::setBillReminderLeadDays,
            onSelectTime = { hour, min ->
                viewModel.setReminderTime(hour, min)
                if (prefs.remindersEnabled) {
                    DueRemindersScheduler.rescheduleDailyReminders(
                        context = context,
                        hourOfDay = hour,
                        minute = min
                    )
                }
            },
            onDismiss = { showDueRemindersScheduleSheet = false }
        )
    }

    if (showDailyLogReminderSheet) {
        DailyLogReminderBottomSheet(
            prefs = prefs,
            onUpdateSchedule = { hour, minute, daysMask ->
                viewModel.setDailyLogReminderSchedule(hour, minute, daysMask)
                if (prefs.dailyLogReminderEnabled) {
                    DailyLogReminderScheduler.cancelSnooze(context)
                    DailyLogReminderScheduler.scheduleNextDaily(
                        context = context,
                        hour = hour,
                        minute = minute,
                        daysMask = daysMask
                    )
                }
            },
            onUpdateSnoozeMinutes = viewModel::setDailyLogReminderSnoozeMinutes,
            onUpdateSkipIfLoggedToday = viewModel::setDailyLogReminderSkipIfLoggedToday,
            onUpdateMessage = viewModel::setDailyLogReminderMessage,
            onDismiss = { showDailyLogReminderSheet = false }
        )
    }

    if (uiState.isCategoryManagerOpen) {
        CategoryManagerBottomSheet(
            uiState = uiState,
            onSelectCategoryType = viewModel::selectCategoryManagerType,
            onToggleSubcategories = viewModel::setSubcategoriesEnabled,
            onAddMainCategory = {
                viewModel.openCreateMainCategoryDialog(uiState.selectedCategoryManagerType)
            },
            onAddSubcategory = viewModel::openCreateSubcategoryDialog,
            onEditCategory = viewModel::openEditCategoryDialog,
            onDeleteCategory = viewModel::requestDeleteCategory,
            onDismiss = { viewModel.setCategoryManagerOpen(false) }
        )
    }

    uiState.categoryEditorState?.let { categoryEditor ->
        CategoryEditorDialog(
            state = categoryEditor,
            isSaving = uiState.isSavingCategory,
            onNameChange = viewModel::onCategoryNameInputChanged,
            onUpdateState = viewModel::updateCategoryEditorState,
            onSave = viewModel::saveCategoryFromDialog,
            onDelete = {
                if ((categoryEditor.isEditing) && (categoryEditor.categoryId > 0L)) {
                    viewModel.requestDeleteCategory(categoryEditor.categoryId)
                }
            },
            onDismiss = viewModel::dismissCategoryEditorDialog
        )
    }

    uiState.categoryPendingDelete?.let { pendingDelete ->
        val sameTypeTree = if (pendingDelete.type == com.folioledger.app.domain.model.CategoryType.EXPENSE) {
            uiState.expenseCategories
        } else {
            uiState.incomeCategories
        }
        val excludedIds = remember(pendingDelete) {
            buildSet {
                add(pendingDelete.id)
                pendingDelete.subcategories.forEach { add(it.id) }
            }
        }
        val reassignOptions = remember(sameTypeTree, excludedIds) {
            sameTypeTree.flatMap { parent ->
                buildList {
                    if (parent.id !in excludedIds) {
                        add(parent.id to parent.name)
                    }
                    parent.subcategories.forEach { sub ->
                        if (sub.id !in excludedIds) {
                            add(sub.id to "${parent.name} › ${sub.name}")
                        }
                    }
                }
            }
        }
        CategoryDeleteConfirmationDialog(
            category = pendingDelete,
            reassignOptions = reassignOptions,
            onConfirmDelete = { reassignToId ->
                viewModel.deleteCategory(pendingDelete.id, reassignToId)
            },
            onDismiss = viewModel::cancelDeleteCategory
        )
    }

    if (isTagsAndBookmarksSheetOpen || uiState.isTagManagerOpen || uiState.isBookmarkManagerOpen) {
        TagsAndBookmarksBottomSheet(
            uiState = uiState,
            onCreateTag = viewModel::createTag,
            onRenameTag = viewModel::renameTag,
            onDeleteTag = viewModel::deleteTag,
            onDeleteBookmark = viewModel::deleteBookmark,
            onDismiss = {
                isTagsAndBookmarksSheetOpen = false
                viewModel.setTagManagerOpen(false)
                viewModel.setBookmarkManagerOpen(false)
            }
        )
    }

    if (uiState.isRecurringManagerOpen) {
        RecurringManagerBottomSheet(
            uiState = uiState,
            onToggleRecurringActive = viewModel::toggleRecurringRuleActive,
            onDeleteRecurringRule = viewModel::deleteRecurringRule,
            onUpdateRecurringRule = viewModel::upsertRecurringRule,
            onDeleteInstallmentPlan = viewModel::deleteInstallmentPlan,
            onDismiss = { viewModel.setRecurringManagerOpen(false) }
        )
    }

    if (uiState.pendingRestoreUriString != null) {
        RestoreConfirmationDialog(
            sourceUriString = uiState.pendingRestoreUriString,
            isEncrypted = uiState.pendingRestoreIsEncrypted,
            passwordError = uiState.restorePasswordError,
            isBusy = uiState.isBusyWithFileOperation,
            onClearPasswordError = viewModel::clearRestorePasswordError,
            onConfirmRestore = viewModel::confirmRestoreFromZipBackup,
            onCancel = viewModel::cancelPendingRestore
        )
    }

    if (showCreateManualBackupDialog) {
        CreateManualBackupDialog(
            isBusy = uiState.isBusyWithFileOperation,
            onCreateStandardZip = {
                showCreateManualBackupDialog = false
                viewModel.createPortableZipBackup(
                    destinationUriString = "Folio_Backup_${LocalDate.now()}_${System.currentTimeMillis() / 1000}.zip"
                )
            },
            onCreateEncryptedFolio = { password ->
                showCreateManualBackupDialog = false
                viewModel.createPortableZipBackup(
                    destinationUriString = "Folio_Backup_${LocalDate.now()}_${System.currentTimeMillis() / 1000}.folio",
                    password = password
                )
            },
            onDismiss = {
                if (!uiState.isBusyWithFileOperation) {
                    showCreateManualBackupDialog = false
                }
            }
        )
    }

    if (showSetSafMirrorPasswordDialog) {
        SetSafMirrorPasswordDialog(
            isBusy = uiState.isBusyWithFileOperation,
            onConfirmPassphrase = { passphrase ->
                showSetSafMirrorPasswordDialog = false
                viewModel.enableSafMirrorEncryption(passphrase)
            },
            onDismiss = {
                if (!uiState.isBusyWithFileOperation) {
                    showSetSafMirrorPasswordDialog = false
                }
            }
        )
    }

    if (showUserGuideSheet) {
        UserGuideBottomSheet(
            onDismiss = { showUserGuideSheet = false }
        )
    }

    if (showFeedbackSheet) {
        FeedbackBottomSheet(
            onDismiss = { showFeedbackSheet = false }
        )
    }

    LaunchedEffect(showTipJarSheet) {
        if (showTipJarSheet) {
            viewModel.refreshTipJarProducts()
        }
    }

    if (showTipJarSheet) {
        TipJarBottomSheet(
            isSupporter = prefs.isSupporter,
            supporterTier = prefs.supporterTier,
            billingState = uiState.tipJarBillingState,
            onTipSelected = { skuId, _ ->
                context.findActivity()?.let { activity ->
                    viewModel.launchTipPurchase(activity, skuId)
                }
            },
            onRestorePurchases = viewModel::restoreSupporterPurchases,
            onRetryConnection = viewModel::refreshTipJarProducts,
            onDismiss = { showTipJarSheet = false }
        )
    }

    if (showPrivacyPolicySheet) {
        PrivacyPolicyBottomSheet(
            onDismiss = { showPrivacyPolicySheet = false },
            onOpenWeb = {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(PRIVACY_POLICY_URL)
                ).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(intent) }
            }
        )
    }
    }
}

