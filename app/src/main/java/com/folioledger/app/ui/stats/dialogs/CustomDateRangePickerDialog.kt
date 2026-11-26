/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.stats.dialogs

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomDateRangePickerDialog(
    initialStartEpochDay: Long,
    initialEndEpochDay: Long,
    onConfirmRange: (startEpochDay: Long, endEpochDay: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val minPickerDay = remember { LocalDate.of(DatePickerDefaults.YearRange.first, 1, 1).toEpochDay() }
    val maxPickerDay = remember { LocalDate.of(DatePickerDefaults.YearRange.last, 12, 31).toEpochDay() }
    val safeStartDay = minOf(initialStartEpochDay, initialEndEpochDay).coerceIn(minPickerDay, maxPickerDay)
    val safeEndDay = maxOf(initialStartEpochDay, initialEndEpochDay).coerceIn(safeStartDay, maxPickerDay)

    val startMillis = remember(safeStartDay) {
        LocalDate.ofEpochDay(safeStartDay)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }
    val endMillis = remember(safeEndDay) {
        LocalDate.ofEpochDay(safeEndDay)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }

    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = startMillis,
        initialSelectedEndDateMillis = endMillis
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val selectedStart = pickerState.selectedStartDateMillis
                    val selectedEnd = pickerState.selectedEndDateMillis ?: selectedStart
                    if (selectedStart != null && selectedEnd != null) {
                        val sEpoch = Instant.ofEpochMilli(selectedStart)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                            .toEpochDay()
                        val eEpoch = Instant.ofEpochMilli(selectedEnd)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                            .toEpochDay()
                        onConfirmRange(minOf(sEpoch, eEpoch), maxOf(sEpoch, eEpoch))
                    } else {
                        onDismiss()
                    }
                }
            ) {
                Text("Apply Range")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    ) {
        DateRangePicker(
            state = pickerState,
            title = {
                Text(
                    text = "Select Custom Analytics Date Range",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp)
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(460.dp)
        )
    }
}
