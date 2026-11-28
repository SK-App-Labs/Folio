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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folioledger.app.domain.model.CsvImportPreset
import com.folioledger.app.domain.model.ImportCsvPreview

/**
 * Dialog displaying CSV analysis preview prior to database ingestion.
 * Features total rows read, duplicate detection controls (Exclude Recommended vs Allow Duplicates),
 * opening balance anchor alerts, and collapsible sample inspection.
 */
@Composable
fun ImportCsvPreviewDialog(
    preview: ImportCsvPreview,
    isBusy: Boolean = false,
    onConfirmImport: (skipDuplicates: Boolean) -> Unit,
    onChangePreset: (CsvImportPreset) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var skipDuplicates by rememberSaveable(preview.sourceUriString) { mutableStateOf(true) }
    var showDuplicateDetails by rememberSaveable(preview.sourceUriString) { mutableStateOf(false) }
    var showPresetMenu by rememberSaveable { mutableStateOf(false) }

    val totalToImport = if (skipDuplicates) {
        preview.validTransactionCount
    } else {
        preview.validTransactionCount + preview.duplicateCount
    }

    val hasMetadataChanges = preview.accountsWithOpeningBalance.isNotEmpty() ||
        preview.newAccountsToCreate.isNotEmpty() ||
        preview.newCategoriesToCreate.isNotEmpty()

    val totalRecordsActionable = totalToImport + preview.updatesCount
    val canConfirm = totalRecordsActionable > 0 || hasMetadataChanges

    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = {
            Text(
                text = "Import CSV Preview",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Source Format Row with Change dropdown
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val autoTag = if (preview.selectedPreset == CsvImportPreset.AUTO_DETECT) " (Auto)" else ""
                    Text(
                        text = "Format: ${preview.detectedPreset.displayName}$autoTag",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box {
                        TextButton(
                            onClick = { showPresetMenu = true },
                            enabled = !isBusy,
                        ) {
                            Text("Change")
                        }
                        DropdownMenu(
                            expanded = showPresetMenu,
                            onDismissRequest = { showPresetMenu = false },
                        ) {
                            CsvImportPreset.entries.forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text(preset.displayName) },
                                    onClick = {
                                        showPresetMenu = false
                                        onChangePreset(preset)
                                    },
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "Folio analyzed your spreadsheet and found:",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "• Total Rows: ${preview.totalRowsRead}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "• Valid New Transactions: ${preview.validTransactionCount}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (preview.updatesCount > 0) {
                            Text(
                                text = "• Updates to Existing Records: ${preview.updatesCount}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (preview.duplicateCount > 0) {
                            Text(
                                text = "• Duplicates Detected: ${preview.duplicateCount}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (preview.consolidatedTransferCount > 0) {
                            Text(
                                text = "• Consolidated Transfers: ${preview.consolidatedTransferCount}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (preview.skippedScheduledCount > 0) {
                            Text(
                                text = "• Skipped Unpaid/Scheduled Rows: ${preview.skippedScheduledCount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (preview.accountsWithOpeningBalance.isNotEmpty()) {
                            Text(
                                text = "• Opening Balance Anchors (${preview.accountsWithOpeningBalance.size}): ${preview.accountsWithOpeningBalance.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        if (preview.newAccountsToCreate.isNotEmpty()) {
                            Text(
                                text = "• New Accounts to create (${preview.newAccountsToCreate.size}): ${preview.newAccountsToCreate.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (preview.newCategoriesToCreate.isNotEmpty()) {
                            Text(
                                text = "• New Categories to create (${preview.newCategoriesToCreate.size}): ${preview.newCategoriesToCreate.take(5).joinToString(", ")}${if (preview.newCategoriesToCreate.size > 5) "..." else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Duplicate Handling Section
                if (preview.duplicateCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "Duplicate Handling",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }

                            Text(
                                text = "${preview.duplicateCount} transaction${if (preview.duplicateCount > 1) "s" else ""} match existing records in your ledger.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            // Option 1: Exclude Duplicates (Recommended)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (skipDuplicates) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                },
                                border = BorderStroke(
                                    width = if (skipDuplicates) 1.5.dp else 1.dp,
                                    color = if (skipDuplicates) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics(mergeDescendants = true) {}
                                    .selectable(
                                        selected = skipDuplicates,
                                        enabled = !isBusy,
                                        role = Role.RadioButton,
                                        onClick = { skipDuplicates = true },
                                    ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    RadioButton(
                                        selected = skipDuplicates,
                                        onClick = null,
                                        enabled = !isBusy,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                text = "Exclude Duplicates",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                            ) {
                                                Text(
                                                    text = "Recommended",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                        Text(
                                            text = "Skips the ${preview.duplicateCount} duplicate${if (preview.duplicateCount > 1) "s" else ""} to avoid double-counting overlapping statements.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = "→ Will import ${preview.validTransactionCount} new transaction${if (preview.validTransactionCount != 1) "s" else ""}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }

                            // Option 2: Include All (Allow Duplicates)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (!skipDuplicates) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                },
                                border = BorderStroke(
                                    width = if (!skipDuplicates) 1.5.dp else 1.dp,
                                    color = if (!skipDuplicates) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics(mergeDescendants = true) {}
                                    .selectable(
                                        selected = !skipDuplicates,
                                        enabled = !isBusy,
                                        role = Role.RadioButton,
                                        onClick = { skipDuplicates = false },
                                    ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    RadioButton(
                                        selected = !skipDuplicates,
                                        onClick = null,
                                        enabled = !isBusy,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = "Include All (Allow Duplicates)",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = "Imports every row. Use this if your statement includes legitimate identical purchases on the same day.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = "→ Will import all ${preview.validTransactionCount + preview.duplicateCount} transactions",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.secondary,
                                        )
                                    }
                                }
                            }

                            // Expandable Duplicate Inspection
                            if (preview.duplicateSamples.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showDuplicateDetails = !showDuplicateDetails }
                                        .padding(top = 4.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = if (showDuplicateDetails) "Hide duplicate details" else "Inspect detected duplicates (${preview.duplicateCount})",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Icon(
                                        imageVector = if (showDuplicateDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }

                                if (showDuplicateDetails) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            preview.duplicateSamples.forEachIndexed { idx, sample ->
                                                if (idx > 0) {
                                                    HorizontalDivider(
                                                        thickness = 0.5.dp,
                                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                                    )
                                                }
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = sample.payee,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            fontWeight = FontWeight.SemiBold,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                        Text(
                                                            text = "${sample.date} • ${sample.accountName}",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                    Text(
                                                        text = sample.amount,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                    )
                                                }
                                            }
                                            if (preview.duplicateCount > preview.duplicateSamples.size) {
                                                Text(
                                                    text = "+ ${preview.duplicateCount - preview.duplicateSamples.size} more duplicates not shown",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontStyle = FontStyle.Italic,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (preview.validTransactionCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "No duplicates detected. All ${preview.validTransactionCount} transactions are new.",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                if (preview.warningMessages.isNotEmpty()) {
                    Text(
                        text = "Warnings (${preview.warningMessages.size}):",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                    preview.warningMessages.take(3).forEach { warning ->
                        Text(
                            text = "⚠ $warning",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (preview.warningMessages.size > 3) {
                        Text(
                            text = "+ ${preview.warningMessages.size - 3} more warnings",
                            style = MaterialTheme.typography.labelSmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmImport(skipDuplicates) },
                enabled = canConfirm && !isBusy,
            ) {
                val buttonLabel = when {
                    totalToImport > 0 && preview.updatesCount > 0 ->
                        "Import $totalToImport & Update ${preview.updatesCount}"

                    totalToImport > 0 ->
                        "Import $totalToImport Record${if (totalToImport == 1) "" else "s"}"

                    preview.updatesCount > 0 ->
                        "Update ${preview.updatesCount} Record${if (preview.updatesCount == 1) "" else "s"}"

                    hasMetadataChanges -> "Import Metadata"

                    else -> "Confirm & Import"
                }
                Text(buttonLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isBusy,
            ) {
                Text("Cancel")
            }
        },
    )
}
