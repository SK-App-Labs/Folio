/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.settings.sheets.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.RecurringCommitmentItem
import com.folioledger.app.domain.model.RepeatFrequency
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import com.folioledger.app.domain.util.RecurringScheduleCalculator
import com.folioledger.app.ui.theme.LocalCurrencyDisplayPolicy
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun formatMinorPlainForInput(money: MinorMoney, fractionDigits: Int): String {
    val divisor = BigDecimal.TEN.pow(fractionDigits)
    return BigDecimal.valueOf(money.amountMinor)
        .divide(divisor, fractionDigits, RoundingMode.HALF_UP)
        .toPlainString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditRecurringRuleDialog(
    rule: RecurringCommitmentItem,
    todayEpochDay: Long = LocalDate.now().toEpochDay(),
    currencySpec: CurrencySpec,
    onDismiss: () -> Unit,
    onSave: (RecurringCommitmentItem) -> Unit,
) {
    val initialAmount = remember(rule.amount, currencySpec.fractionDigits) {
        formatMinorPlainForInput(rule.amount, currencySpec.fractionDigits)
    }
    var title by rememberSaveable(rule.id) { mutableStateOf(rule.title) }
    var amountInput by rememberSaveable(rule.id, initialAmount) { mutableStateOf(initialAmount) }
    var selectedFrequency by rememberSaveable(rule.id) { mutableStateOf(rule.frequency) }
    var nextDueEpochDay by rememberSaveable(rule.id) { mutableLongStateOf(rule.nextOccurrenceEpochDay) }
    var startEpochDay by rememberSaveable(rule.id) { mutableLongStateOf(rule.startDateEpochDay) }
    var frequencyExpanded by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    var titleError by rememberSaveable(rule.id) { mutableStateOf<String?>(null) }
    var amountError by rememberSaveable(rule.id) { mutableStateOf<String?>(null) }
    var dateError by rememberSaveable(rule.id) { mutableStateOf<String?>(null) }

    val dateFormatter = remember { DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault()) }
    val nextDueLabel = remember(nextDueEpochDay, dateFormatter) {
        LocalDate.ofEpochDay(nextDueEpochDay).format(dateFormatter)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Edit Recurring Rule",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (rule.type == TransactionType.TRANSFER && rule.transferToAccountName != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Transfer: ${rule.accountName} → ${rule.transferToAccountName}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        if (titleError != null) titleError = null
                    },
                    label = { Text("Title / Payee") },
                    isError = titleError != null,
                    supportingText = titleError?.let { err -> { Text(err) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                val effectiveSymbol = LocalCurrencyDisplayPolicy.current
                    .effectiveSymbol(currencySpec)
                OutlinedTextField(
                    value = amountInput,
                    onValueChange = {
                        amountInput = it
                        if (amountError != null) amountError = null
                    },
                    label = {
                        Text(if (effectiveSymbol.isNotEmpty()) "Amount ($effectiveSymbol)" else "Amount")
                    },
                    isError = amountError != null,
                    supportingText = amountError?.let { err -> { Text(err) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                ExposedDropdownMenuBox(
                    expanded = frequencyExpanded,
                    onExpandedChange = { frequencyExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedFrequency.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Repeat Frequency") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = frequencyExpanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )

                    ExposedDropdownMenu(
                        expanded = frequencyExpanded,
                        onDismissRequest = { frequencyExpanded = false },
                    ) {
                        RepeatFrequency.entries.forEach { freq ->
                            DropdownMenuItem(
                                text = { Text(freq.displayName) },
                                onClick = {
                                    selectedFrequency = freq
                                    if (nextDueEpochDay < todayEpochDay) {
                                        val anchorDay = LocalDate.ofEpochDay(startEpochDay).dayOfMonth
                                        nextDueEpochDay = RecurringScheduleCalculator.computeFirstOccurrenceOnOrAfter(
                                            startDay = nextDueEpochDay,
                                            frequency = freq,
                                            fromEpochDay = todayEpochDay,
                                            anchorDayOfMonth = anchorDay,
                                        ) ?: todayEpochDay
                                    }
                                    dateError = null
                                    frequencyExpanded = false
                                },
                            )
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = nextDueLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Next Due Date") },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.DateRange,
                                contentDescription = "Pick next due date",
                            )
                        },
                        isError = dateError != null,
                        supportingText = dateError?.let { err -> { Text(err) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { showDatePicker = true },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedTitle = title.trim()
                    val evaluatedResult = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor(
                        expression = amountInput,
                        fractionDigits = currencySpec.fractionDigits,
                    )
                    val evaluatedMinor = evaluatedResult.getOrNull()?.amountMinor ?: 0L
                    val endEpoch = rule.endDateEpochDay

                    titleError = if (trimmedTitle.isEmpty()) "Title cannot be empty." else null
                    amountError = if (evaluatedMinor <= 0L) "Enter a valid positive amount." else null
                    dateError = if (endEpoch != null && nextDueEpochDay > endEpoch) {
                        val endText = LocalDate.ofEpochDay(endEpoch).format(dateFormatter)
                        "Next due date cannot be after end date ($endText)."
                    } else {
                        null
                    }

                    if (titleError == null && amountError == null && dateError == null) {
                        onSave(
                            rule.copy(
                                title = trimmedTitle,
                                payeeOrDescription = if (rule.payeeOrDescription == rule.title || rule.payeeOrDescription.isBlank()) {
                                    trimmedTitle
                                } else {
                                    rule.payeeOrDescription
                                },
                                amount = MinorMoney(evaluatedMinor),
                                frequency = selectedFrequency,
                                startDateEpochDay = startEpochDay,
                                nextOccurrenceEpochDay = nextDueEpochDay,
                            ),
                        )
                    }
                },
            ) {
                Text("Save Changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )

    if (showDatePicker) {
        val initialMillis = remember(nextDueEpochDay) {
            LocalDate.ofEpochDay(nextDueEpochDay)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedMillis = datePickerState.selectedDateMillis
                        if (selectedMillis != null) {
                            val pickedEpochDay = Instant.ofEpochMilli(selectedMillis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                                .toEpochDay()
                            nextDueEpochDay = pickedEpochDay
                            startEpochDay = pickedEpochDay
                            dateError = null
                        }
                        showDatePicker = false
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
