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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

private const val MIN_BACKUP_PASSWORD_LENGTH = 8

@Composable
fun CreateManualBackupDialog(
    isBusy: Boolean = false,
    onCreateStandardZip: () -> Unit,
    onCreateEncryptedFolio: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var encryptSelected by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    val passwordTooShort = encryptSelected && password.isNotEmpty() &&
        (password.length < MIN_BACKUP_PASSWORD_LENGTH || password.isBlank())
    val passwordMismatch = encryptSelected && confirmPassword.isNotEmpty() && password != confirmPassword
    val canConfirm = !isBusy && (
        !encryptSelected ||
            (password.isNotBlank() && password.length >= MIN_BACKUP_PASSWORD_LENGTH && password == confirmPassword)
        )

    fun submitConfirmed() {
        if (!canConfirm) return
        if (!encryptSelected) {
            onCreateStandardZip()
        } else {
            val chars = password.toCharArray()
            password = ""
            confirmPassword = ""
            showPassword = false
            onCreateEncryptedFolio(chars)
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isBusy) {
                password = ""
                confirmPassword = ""
                showPassword = false
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = !isBusy,
            dismissOnClickOutside = !isBusy,
            securePolicy = SecureFlagPolicy.SecureOn,
        ),
        icon = {
            Icon(
                imageVector = if (encryptSelected) Icons.Default.Lock else Icons.Default.Archive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = "Create Backup Archive",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BackupFormatChoiceCard(
                    selected = !encryptSelected,
                    enabled = !isBusy,
                    title = "Standard Portable ZIP (.zip)",
                    subtitle = "Fast 1-tap export for local archiving or trusted transfers",
                    onClick = {
                        encryptSelected = false
                        password = ""
                        confirmPassword = ""
                        showPassword = false
                    },
                )

                BackupFormatChoiceCard(
                    selected = encryptSelected,
                    enabled = !isBusy,
                    title = "Password-Encrypted (.folio)",
                    subtitle = "AES-256-GCM encrypted before leaving Folio. Safe for cloud drives.",
                    onClick = { encryptSelected = true },
                )

                if (encryptSelected) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Backup Password (min 8 chars)") },
                        singleLine = true,
                        enabled = !isBusy,
                        isError = passwordTooShort,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next,
                            autoCorrectEnabled = false,
                        ),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = Icons.Default.Visibility,
                                    contentDescription = if (showPassword) "Hide password" else "Show password",
                                    tint = if (showPassword) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        supportingText = if (passwordTooShort) {
                            { Text("Enter at least $MIN_BACKUP_PASSWORD_LENGTH non-space characters") }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("Confirm Password") },
                        singleLine = true,
                        enabled = !isBusy,
                        isError = passwordMismatch,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                            autoCorrectEnabled = false,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submitConfirmed() }),
                        supportingText = if (passwordMismatch) {
                            { Text("Passwords do not match") }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OfflineRecoveryWarningBanner()
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { submitConfirmed() },
                enabled = canConfirm,
            ) {
                Text(if (encryptSelected) "Encrypt & Export" else "Create Backup")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    password = ""
                    confirmPassword = ""
                    showPassword = false
                    onDismiss()
                },
                enabled = !isBusy,
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun SetSafMirrorPasswordDialog(
    isBusy: Boolean = false,
    onConfirmPassphrase: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    val passwordTooShort = password.isNotEmpty() &&
        (password.length < MIN_BACKUP_PASSWORD_LENGTH || password.isBlank())
    val passwordMismatch = confirmPassword.isNotEmpty() && password != confirmPassword
    val canSave = !isBusy &&
        password.isNotBlank() &&
        password.length >= MIN_BACKUP_PASSWORD_LENGTH &&
        password == confirmPassword

    fun submitPassphrase() {
        if (!canSave) return
        val chars = password.toCharArray()
        password = ""
        confirmPassword = ""
        showPassword = false
        onConfirmPassphrase(chars)
    }

    AlertDialog(
        onDismissRequest = {
            if (!isBusy) {
                password = ""
                confirmPassword = ""
                showPassword = false
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = !isBusy,
            dismissOnClickOutside = !isBusy,
            securePolicy = SecureFlagPolicy.SecureOn,
        ),
        icon = {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = "Encrypt External Mirror (.folio)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Every snapshot mirrored to your external folder will be encrypted with AES-256-GCM before leaving Folio. Your passphrase is never saved in plaintext.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Mirror Passphrase (min 8 chars)") },
                    singleLine = true,
                    enabled = !isBusy,
                    isError = passwordTooShort,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                        autoCorrectEnabled = false,
                    ),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                                tint = if (showPassword) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    supportingText = if (passwordTooShort) {
                        { Text("Enter at least $MIN_BACKUP_PASSWORD_LENGTH non-space characters") }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("Confirm Passphrase") },
                    singleLine = true,
                    enabled = !isBusy,
                    isError = passwordMismatch,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submitPassphrase() }),
                    supportingText = if (passwordMismatch) {
                        { Text("Passphrases do not match") }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                OfflineRecoveryWarningBanner()
            }
        },
        confirmButton = {
            Button(
                onClick = { submitPassphrase() },
                enabled = canSave,
            ) {
                Text("Enable Encryption")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    password = ""
                    confirmPassword = ""
                    showPassword = false
                    onDismiss()
                },
                enabled = !isBusy,
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun BackupFormatChoiceCard(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OfflineRecoveryWarningBanner() {
    val warningColor = LocalFolioSemanticColors.current.warningAmber
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = warningColor.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, warningColor.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = warningColor,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Folio works 100% offline. If you forget this password, encrypted backups cannot be recovered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
