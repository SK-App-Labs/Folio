/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.settings.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folioledger.app.domain.model.AutoBackupRetention
import com.folioledger.app.domain.util.BackupTimeFormatter
import com.folioledger.app.ui.settings.BackupSnapshotDisplayItem
import com.folioledger.app.ui.theme.LocalFolioSemanticColors
import java.io.File

/**
 * Bottom sheet dedicated strictly to Automated Rolling Backups & Snapshot History.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoBackupBottomSheet(
    autoBackupEnabled: Boolean,
    autoBackupPublicMirrorEnabled: Boolean = false,
    isAppLockActive: Boolean = false,
    autoBackupRetention: AutoBackupRetention,
    autoBackupSnapshots: List<BackupSnapshotDisplayItem>,
    safMirrorEnabled: Boolean = false,
    safMirrorTreeUri: String? = null,
    safMirrorFolderDisplayName: String? = null,
    safMirrorEncryptEnabled: Boolean = false,
    lastSafMirrorEpochMillis: Long? = null,
    safMirrorErrorCode: String? = null,
    isBusy: Boolean = false,
    onToggleAutoBackup: (Boolean) -> Unit,
    onTogglePublicMirror: (Boolean) -> Unit = {},
    onSelectRetention: (AutoBackupRetention) -> Unit,
    onPickSafFolder: () -> Unit = {},
    onToggleSafMirror: (Boolean) -> Unit = {},
    onDisconnectSafFolder: () -> Unit = {},
    onToggleSafEncrypt: (Boolean) -> Unit = {},
    onMirrorNowClick: () -> Unit = {},
    onRestoreAutoBackupFile: (File) -> Unit,
    onShareBackupFile: ((File) -> Unit)?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp, top = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column {
                Text(
                    text = "Automated Rolling Backups",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Zero-cloud, hands-off local protection on this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Main Auto-Backup Configuration Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .toggleable(
                                value = autoBackupEnabled,
                                enabled = !isBusy,
                                role = Role.Switch,
                                onValueChange = onToggleAutoBackup
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Auto-Backup on Session Exit",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Automatically saves whenever data is added or modified",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = autoBackupEnabled,
                            onCheckedChange = null,
                            enabled = !isBusy
                        )
                    }

                    if (autoBackupEnabled) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        Text(
                            text = "History Retention",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AutoBackupRetention.entries.forEach { policy ->
                                val isSelected = autoBackupRetention == policy
                                val label = when (policy) {
                                    AutoBackupRetention.TEN_SESSIONS -> "10 Sessions"
                                    AutoBackupRetention.THIRTY_DAYS -> "30 Days (Tiered)"
                                    AutoBackupRetention.NINETY_DAYS -> "90 Days"
                                }
                                val containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLowest
                                }
                                val contentColor = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                val borderColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = containerColor,
                                    border = BorderStroke(1.dp, borderColor),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .selectable(
                                            selected = isSelected,
                                            enabled = !isBusy,
                                            role = Role.RadioButton,
                                            onClick = { onSelectRetention(policy) }
                                        )
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = contentColor,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }

                        val policyDesc = when (autoBackupRetention) {
                            AutoBackupRetention.TEN_SESSIONS -> "Keeps rolling history of your last 10 session snapshots (~1.5 MB). Each snapshot contains your complete lifetime ledger."
                            AutoBackupRetention.THIRTY_DAYS -> "Keeps 3 intra-day + 30 daily snapshots + 3 monthly anchors (~5 MB). Each snapshot contains your complete lifetime ledger."
                            AutoBackupRetention.NINETY_DAYS -> "Keeps 3 intra-day + 90 daily snapshots (~12 MB). Each snapshot contains your complete lifetime ledger."
                        }
                        Text(
                            text = policyDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .toggleable(
                                    value = autoBackupPublicMirrorEnabled && !isAppLockActive,
                                    enabled = autoBackupEnabled && !isAppLockActive && !isBusy,
                                    role = Role.Switch,
                                    onValueChange = onTogglePublicMirror
                                )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column {
                                    Text(
                                        text = "Mirror to Public Downloads",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (isAppLockActive) {
                                            "Disabled while App Lock is active to protect ledger privacy."
                                        } else {
                                            "Copies latest backup to Downloads/Folio for recovery across uninstalls."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isAppLockActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                                    )
                                }
                            }
                            Switch(
                                checked = autoBackupPublicMirrorEnabled && !isAppLockActive,
                                onCheckedChange = null,
                                enabled = autoBackupEnabled && !isAppLockActive && !isBusy
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // External Folder Auto-Mirror (SAF)
                        val hasSafFolder = !safMirrorTreeUri.isNullOrBlank()
                        val needsEncryptionSetup = isAppLockActive &&
                            (!safMirrorEncryptEnabled || safMirrorErrorCode == "KEY_UNAVAILABLE")
                        val effectiveSafMirrorOn = safMirrorEnabled &&
                            hasSafFolder &&
                            !needsEncryptionSetup

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .toggleable(
                                    value = effectiveSafMirrorOn,
                                    enabled = autoBackupEnabled && !isBusy,
                                    role = Role.Switch,
                                    onValueChange = { enable ->
                                        if (!enable) {
                                            onToggleSafMirror(false)
                                        } else if (!hasSafFolder) {
                                            onPickSafFolder()
                                        } else if (needsEncryptionSetup) {
                                            onToggleSafEncrypt(true)
                                        } else {
                                            onToggleSafMirror(true)
                                        }
                                    }
                                )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column {
                                    Text(
                                        text = "External Folder Auto-Mirror (SAF)",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Point to a Syncthing, Nextcloud, FolderSync, Drive sync folder, or SD card. Zero internet permission.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                                    )
                                }
                            }
                            Switch(
                                checked = effectiveSafMirrorOn,
                                onCheckedChange = null,
                                enabled = autoBackupEnabled && !isBusy
                            )
                        }

                        if (hasSafFolder) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.FolderOpen,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Column {
                                                Text(
                                                    text = safMirrorFolderDisplayName ?: "Selected Folder",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                val formatLabel = if (safMirrorEncryptEnabled) {
                                                    "Encrypted (.folio)"
                                                } else {
                                                    "Standard (.zip)"
                                                }
                                                val timeLabel = BackupTimeFormatter.formatBackupRelativeTime(lastSafMirrorEpochMillis)
                                                Text(
                                                    text = "Last mirrored: $timeLabel \u2022 $formatLabel",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Row {
                                            TextButton(
                                                onClick = onPickSafFolder,
                                                enabled = !isBusy,
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text("Change", style = MaterialTheme.typography.labelSmall)
                                            }
                                            TextButton(
                                                onClick = onDisconnectSafFolder,
                                                enabled = !isBusy,
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    "Disconnect",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }

                                    if (safMirrorErrorCode != null) {
                                        val warningColor = LocalFolioSemanticColors.current.warningAmber
                                        val errorText = when (safMirrorErrorCode) {
                                            "PERMISSION_LOST" -> "Folder access permission expired or folder moved."
                                            "KEY_UNAVAILABLE" -> "Encryption key unavailable. Re-enter mirror passphrase."
                                            else -> "Last mirror failed due to storage error."
                                        }
                                        val ctaLabel = when (safMirrorErrorCode) {
                                            "PERMISSION_LOST" -> "Reconnect Folder"
                                            "KEY_UNAVAILABLE" -> "Set Passphrase"
                                            else -> "Retry"
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = warningColor.copy(alpha = 0.12f),
                                            border = BorderStroke(1.dp, warningColor.copy(alpha = 0.4f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Warning,
                                                        contentDescription = null,
                                                        tint = warningColor,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Text(
                                                        text = errorText,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                                TextButton(
                                                    onClick = {
                                                        when (safMirrorErrorCode) {
                                                            "PERMISSION_LOST" -> onPickSafFolder()
                                                            "KEY_UNAVAILABLE" -> onToggleSafEncrypt(true)
                                                            else -> onMirrorNowClick()
                                                        }
                                                    },
                                                    enabled = !isBusy,
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                                ) {
                                                    Text(ctaLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }

                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                                    val effectiveEncryptOn = safMirrorEncryptEnabled && safMirrorErrorCode != "KEY_UNAVAILABLE"
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .toggleable(
                                                value = effectiveEncryptOn,
                                                enabled = !isBusy,
                                                role = Role.Switch,
                                                onValueChange = { enable ->
                                                    if (safMirrorErrorCode == "KEY_UNAVAILABLE") {
                                                        onToggleSafEncrypt(true)
                                                    } else {
                                                        onToggleSafEncrypt(enable)
                                                    }
                                                }
                                            )
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Lock,
                                                contentDescription = null,
                                                tint = if (effectiveEncryptOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Column {
                                                Text(
                                                    text = "Encrypt Mirrored Backups (.folio, AES-256)",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    text = if (isAppLockActive && !effectiveEncryptOn) {
                                                        "Required to mirror while App Lock is enabled"
                                                    } else {
                                                        "Encrypts with AES-256-GCM before copying to external folder"
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isAppLockActive && !effectiveEncryptOn) {
                                                        LocalFolioSemanticColors.current.warningAmber
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    }
                                                )
                                            }
                                        }
                                        Switch(
                                            checked = safMirrorEncryptEnabled,
                                            onCheckedChange = null,
                                            enabled = !isBusy
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = onMirrorNowClick,
                                        enabled = !isBusy && effectiveSafMirrorOn,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Sync,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Mirror Now", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Recent Automated Snapshots Card (kept accessible even when paused)
            if (autoBackupSnapshots.isNotEmpty()) {
                val visibleSnapshots = autoBackupSnapshots.take(5)
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Recent Snapshots (${autoBackupSnapshots.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (!autoBackupEnabled) {
                                Text(
                                    text = "Auto-backup is paused • existing snapshots remain available to restore or share",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            visibleSnapshots.forEach { snapshot ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = snapshot.relativeTimeText,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "${snapshot.fileName} • ${snapshot.sizeKb} KB",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    onRestoreAutoBackupFile(snapshot.file)
                                                    onDismiss()
                                                },
                                                enabled = !isBusy,
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(32.dp)
                                            ) {
                                                Text("Restore", style = MaterialTheme.typography.labelSmall)
                                            }
                                            if (onShareBackupFile != null) {
                                                IconButton(
                                                    onClick = { onShareBackupFile(snapshot.file) },
                                                    enabled = !isBusy,
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Share,
                                                        contentDescription = "Share",
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (autoBackupSnapshots.size > 5) {
                                Text(
                                    text = "+ ${autoBackupSnapshots.size - 5} more snapshots managed automatically",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
