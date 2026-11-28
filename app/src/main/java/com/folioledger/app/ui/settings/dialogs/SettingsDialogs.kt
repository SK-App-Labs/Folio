/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.settings.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.folioledger.app.ui.theme.LocalFolioSemanticColors
import com.folioledger.app.ui.theme.tabularMoneyStyle

@Composable
fun MonthStartDayPickerDialog(
    selectedDay: Int,
    onSelectDay: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Financial Month Start Day (1–31)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Choose the day of the month your financial cycle begins (1–31). Months with fewer days automatically clamp to the last day of that month.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (1..31).forEach { day ->
                        FilterChip(
                            selected = selectedDay == day,
                            onClick = { onSelectDay(day) },
                            label = {
                                Text(
                                    text = day.toString(),
                                    style = tabularMoneyStyle(MaterialTheme.typography.labelMedium),
                                    fontWeight = if (selectedDay == day) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun RestoreConfirmationDialog(
    sourceUriString: String?,
    isEncrypted: Boolean = false,
    passwordError: String? = null,
    isBusy: Boolean = false,
    onClearPasswordError: () -> Unit = {},
    onConfirmRestore: (CharArray?) -> Unit,
    onCancel: () -> Unit,
) {
    val isLocalLatest = sourceUriString == null || sourceUriString == "LATEST_BACKUP"
    val sourceDescription = if (isLocalLatest) {
        "the latest local backup"
    } else {
        "the selected backup file"
    }

    var password by remember(sourceUriString) { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val canConfirm = !isBusy && (!isEncrypted || password.isNotEmpty())

    fun submitRestore() {
        if (!canConfirm) return
        val chars = if (isEncrypted) password.toCharArray() else null
        onConfirmRestore(chars)
    }

    AlertDialog(
        onDismissRequest = { if (!isBusy) onCancel() },
        properties = DialogProperties(
            dismissOnBackPress = !isBusy,
            dismissOnClickOutside = !isBusy,
            securePolicy = if (isEncrypted) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
        ),
        icon = {
            Icon(
                imageVector = if (isEncrypted) Icons.Filled.Lock else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (isEncrypted) MaterialTheme.colorScheme.primary else LocalFolioSemanticColors.current.warningAmber,
            )
        },
        title = {
            Text(
                text = if (isEncrypted) {
                    "Unlock & Restore Encrypted Backup?"
                } else {
                    "Restore from Portable ZIP Backup?"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Restoring $sourceDescription will replace your current local accounts, transactions, categories, and budgets.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (isEncrypted) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            if (!passwordError.isNullOrBlank()) {
                                onClearPasswordError()
                            }
                        },
                        label = { Text("Backup Password") },
                        singleLine = true,
                        enabled = !isBusy,
                        isError = !passwordError.isNullOrBlank(),
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                            autoCorrectEnabled = false,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submitRestore() }),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = Icons.Default.Visibility,
                                    contentDescription = if (showPassword) "Hide password" else "Show password",
                                    tint = if (showPassword) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        supportingText = if (!passwordError.isNullOrBlank()) {
                            { Text(passwordError, color = MaterialTheme.colorScheme.error) }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = "A safety rollback snapshot will be automatically saved before restoring.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { submitRestore() },
                enabled = canConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = LocalFolioSemanticColors.current.expenseCoral,
                    contentColor = Color.White,
                ),
            ) {
                Text("Replace & Restore")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !isBusy) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun ClearAllDataConfirmationDialog(
    isBusy: Boolean = false,
    onConfirmClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var acknowledged by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        icon = {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = LocalFolioSemanticColors.current.expenseCoral,
            )
        },
        title = {
            Text(
                text = "Reset & Clear All Ledger Data?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "This action will permanently delete all your accounts, transactions, custom categories, tags, budgets, settings, and local auto-backup archives. This cannot be undone unless you have an external backup.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = acknowledged,
                            enabled = !isBusy,
                            role = Role.Checkbox,
                            onValueChange = { acknowledged = it },
                        ),
                ) {
                    Checkbox(
                        checked = acknowledged,
                        onCheckedChange = null,
                        enabled = !isBusy,
                    )
                    Text(
                        text = "I understand all local data and backups will be permanently wiped",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmClear,
                enabled = acknowledged && !isBusy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = LocalFolioSemanticColors.current.expenseCoral,
                    contentColor = Color.White,
                ),
            ) {
                Text("Clear All Data")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text("Cancel")
            }
        },
    )
}
