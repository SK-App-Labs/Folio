/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/ui/settings/sheets/DailyLogReminderBottomSheet.kt
package com.folioledger.app.ui.settings.sheets

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.folioledger.app.R
import com.folioledger.app.data.notification.DailyLogReminderScheduler
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.domain.util.DailyLogReminderCalculator
import com.folioledger.app.ui.theme.LocalFolioSemanticColors
import java.time.DayOfWeek
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DailyLogReminderBottomSheet(
    prefs: UserPreferences,
    onUpdateTime: (hour: Int, minute: Int) -> Unit,
    onUpdateDaysMask: (Int) -> Unit,
    onUpdateSnoozeMinutes: (Int) -> Unit,
    onUpdateSkipIfLoggedToday: (Boolean) -> Unit,
    onUpdateMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val semanticColors = LocalFolioSemanticColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val is24Hour = remember(context) { DateFormat.is24HourFormat(context) }

    var showTimePickerDialog by rememberSaveable { mutableStateOf(false) }
    var messageDraft by rememberSaveable(prefs.dailyLogReminderMessage) {
        mutableStateOf(prefs.dailyLogReminderMessage)
    }

    fun persistDraftAndDismiss() {
        val trimmed = messageDraft.trim().take(DailyLogReminderCalculator.MAX_CUSTOM_MESSAGE_LENGTH)
        if (trimmed != prefs.dailyLogReminderMessage) {
            onUpdateMessage(trimmed)
        }
        onDismiss()
    }

    fun applyScheduleChange(newHour: Int, newMinute: Int, newDaysMask: Int) {
        val safeMask = DailyLogReminderCalculator.normalizeDaysMask(newDaysMask)
        if (prefs.dailyLogReminderEnabled) {
            DailyLogReminderScheduler.scheduleNextDaily(
                context = context,
                hour = newHour,
                minute = newMinute,
                daysMask = safeMask
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = { persistDraftAndDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Column {
                Text(
                    text = stringResource(R.string.setting_daily_log_customize),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Set your preferred check-in time, active weekdays, snooze interval, and prompt message.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 1. Custom Time + Quick-Pick Presets
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "REMINDER TIME",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showTimePickerDialog = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccessTime,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = formatClockTime(
                                    hour = prefs.dailyLogReminderHour,
                                    minute = prefs.dailyLogReminderMinute,
                                    is24Hour = is24Hour
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Tap to pick any hour and minute",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { showTimePickerDialog = true }) {
                            Text("Change Time")
                        }
                    }
                }

                val quickPresets = listOf(20 to 0, 20 to 30, 21 to 0, 22 to 0)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    quickPresets.forEach { (presetHour, presetMinute) ->
                        val isSelected =
                            prefs.dailyLogReminderHour == presetHour && prefs.dailyLogReminderMinute == presetMinute
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                onUpdateTime(presetHour, presetMinute)
                                applyScheduleChange(presetHour, presetMinute, prefs.dailyLogReminderDaysMask)
                            },
                            label = {
                                Text(formatClockTime(presetHour, presetMinute, is24Hour))
                            }
                        )
                    }
                }
            }

            // 2. Repeat Days
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "REPEAT DAYS",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = DailyLogReminderCalculator.formatDaysSummary(prefs.dailyLogReminderDaysMask),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val dayEntries = listOf(
                    DayOfWeek.MONDAY to "M",
                    DayOfWeek.TUESDAY to "T",
                    DayOfWeek.WEDNESDAY to "W",
                    DayOfWeek.THURSDAY to "T",
                    DayOfWeek.FRIDAY to "F",
                    DayOfWeek.SATURDAY to "S",
                    DayOfWeek.SUNDAY to "S"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    dayEntries.forEach { (day, shortLabel) ->
                        val isSelected = DailyLogReminderCalculator.isDaySelected(
                            daysMask = prefs.dailyLogReminderDaysMask,
                            dayOfWeek = day
                        )
                        val candidateMask = DailyLogReminderCalculator.withDaySelected(
                            daysMask = prefs.dailyLogReminderDaysMask,
                            dayOfWeek = day,
                            selected = !isSelected
                        )
                        val canToggleOff = candidateMask != 0
                        Surface(
                            shape = CircleShape,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    semanticColors.cardStrokeSubtle
                                }
                            ),
                            modifier = Modifier
                                .size(40.dp)
                                .clickable(enabled = !isSelected || canToggleOff) {
                                    onUpdateDaysMask(candidateMask)
                                    applyScheduleChange(
                                        prefs.dailyLogReminderHour,
                                        prefs.dailyLogReminderMinute,
                                        candidateMask
                                    )
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = shortLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 3. Snooze Duration
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "SNOOZE DURATION",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(15, 30, 60, 120).forEach { minutes ->
                        val isSelected = prefs.dailyLogReminderSnoozeMinutes == minutes
                        FilterChip(
                            selected = isSelected,
                            onClick = { onUpdateSnoozeMinutes(minutes) },
                            leadingIcon = if (isSelected) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else {
                                null
                            },
                            label = {
                                Text(DailyLogReminderCalculator.formatSnoozeLabel(minutes))
                            }
                        )
                    }
                }
            }

            // 4. Smart Skip
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onUpdateSkipIfLoggedToday(!prefs.dailyLogReminderSkipIfLoggedToday)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Skip if I already logged today",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Automatically skips the reminder when you've manually logged a transaction today.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = prefs.dailyLogReminderSkipIfLoggedToday,
                        onCheckedChange = onUpdateSkipIfLoggedToday
                    )
                }
            }

            // 5. Custom Message
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "CUSTOM MESSAGE (OPTIONAL)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedTextField(
                    value = messageDraft,
                    onValueChange = { incoming ->
                        if (incoming.length <= DailyLogReminderCalculator.MAX_CUSTOM_MESSAGE_LENGTH) {
                            messageDraft = incoming
                        }
                    },
                    singleLine = true,
                    placeholder = {
                        Text(stringResource(R.string.daily_log_default_message))
                    },
                    supportingText = {
                        Text(
                            text = "${messageDraft.length}/${DailyLogReminderCalculator.MAX_CUSTOM_MESSAGE_LENGTH}"
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val trimmed = messageDraft.trim().take(DailyLogReminderCalculator.MAX_CUSTOM_MESSAGE_LENGTH)
                            messageDraft = trimmed
                            onUpdateMessage(trimmed)
                            focusManager.clearFocus()
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Button(
                onClick = { persistDraftAndDismiss() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Done")
            }
        }
    }

    if (showTimePickerDialog) {
        val timePickerState = rememberTimePickerState(
            initialHour = prefs.dailyLogReminderHour,
            initialMinute = prefs.dailyLogReminderMinute,
            is24Hour = is24Hour
        )
        AlertDialog(
            onDismissRequest = { showTimePickerDialog = false },
            title = {
                Text(
                    text = "Select Reminder Time",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    TimePicker(state = timePickerState)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val h = timePickerState.hour
                        val m = timePickerState.minute
                        onUpdateTime(h, m)
                        applyScheduleChange(h, m, prefs.dailyLogReminderDaysMask)
                        showTimePickerDialog = false
                    }
                ) {
                    Text("Set Time")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePickerDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun formatClockTime(hour: Int, minute: Int, is24Hour: Boolean): String {
    val safeHour = hour.coerceIn(0, 23)
    val safeMinute = minute.coerceIn(0, 59)
    return if (is24Hour) {
        String.format(Locale.US, "%02d:%02d", safeHour, safeMinute)
    } else {
        val h12 = when {
            safeHour == 0 -> 12
            safeHour > 12 -> safeHour - 12
            else -> safeHour
        }
        val amPm = if (safeHour < 12) "AM" else "PM"
        String.format(Locale.US, "%d:%02d %s", h12, safeMinute, amPm)
    }
}
