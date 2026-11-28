/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.entry.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryDatePickerDialog(
    initialEpochDay: Long,
    onDateSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialMillis = remember(initialEpochDay) {
        LocalDate.ofEpochDay(initialEpochDay)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val selectedMillis = datePickerState.selectedDateMillis
                    if (selectedMillis != null) {
                        val selectedEpochDay = Instant.ofEpochMilli(selectedMillis)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                            .toEpochDay()
                        onDateSelected(selectedEpochDay)
                    }
                    onDismiss()
                },
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    ) {
        DatePicker(state = datePickerState)
    }
}

@Composable
internal fun EntryTimePickerLauncher(
    initialEpochMillis: Long,
    onTimeSelected: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialZone = remember { ZoneId.systemDefault() }
    val initialLocalTime = remember(initialEpochMillis) {
        Instant.ofEpochMilli(initialEpochMillis)
            .atZone(initialZone)
            .toLocalTime()
    }
    val androidContext = LocalContext.current
    val currentOnTimeSelected by rememberUpdatedState(onTimeSelected)
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    DisposableEffect(Unit) {
        val dialog = android.app.TimePickerDialog(
            androidContext,
            { _, hourOfDay, minute ->
                currentOnTimeSelected(hourOfDay, minute)
            },
            initialLocalTime.hour,
            initialLocalTime.minute,
            android.text.format.DateFormat.is24HourFormat(androidContext),
        )
        dialog.setOnDismissListener { currentOnDismiss() }
        dialog.show()

        onDispose {
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        }
    }
}

@Composable
internal fun CreateTagDialog(
    onTagCreated: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newTagText by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Tag") },
        text = {
            OutlinedTextField(
                value = newTagText,
                onValueChange = { newTagText = it },
                label = { Text("Tag name (e.g. vacation, tax)") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = newTagText.trim()
                    if (trimmed.isNotEmpty()) {
                        onTagCreated(trimmed)
                    }
                    onDismiss()
                },
            ) {
                Text("Add Tag")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
