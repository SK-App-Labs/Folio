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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.ui.components.MoneyColorMode
import com.folioledger.app.ui.components.MoneyText
import com.folioledger.app.ui.components.fromTransactionType
import com.folioledger.app.ui.settings.SettingsUiState
import com.folioledger.app.ui.theme.LocalFolioSemanticColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsAndBookmarksBottomSheet(
    uiState: SettingsUiState,
    onCreateTag: (String) -> Unit,
    onRenameTag: (String, String) -> Unit,
    onDeleteTag: (String) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val semanticColors = LocalFolioSemanticColors.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    var newTagInput by rememberSaveable { mutableStateOf("") }
    var tagBeingRenamed by rememberSaveable { mutableStateOf<String?>(null) }
    var renameInput by rememberSaveable { mutableStateOf("") }
    var tagPendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var bookmarkPendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingMergeTarget by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.tags, tagBeingRenamed) {
        val current = tagBeingRenamed ?: return@LaunchedEffect
        if (uiState.tags.none { it.equals(current, ignoreCase = true) }) {
            tagBeingRenamed = null
            renameInput = ""
            pendingMergeTarget = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(bottom = 24.dp)
        ) {
            // Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Column {
                    Text(
                        text = "Tags & Favorite Bookmarks",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Manage cross-category #tags & saved transaction templates",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Tab Row
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            text = "#Tags (${uiState.tags.size})",
                            fontWeight = FontWeight.Bold
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            text = "Bookmarks ★ (${uiState.bookmarks.size})",
                            fontWeight = FontWeight.Bold
                        )
                    }
                )
            }

            when (selectedTab) {
                0 -> {
                    // Tag Manager Content
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Create New Tag Input Row
                        val sanitizedNewTag = newTagInput.trim().trimStart('#').trim()
                        val newTagHasComma = ',' in sanitizedNewTag
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = newTagInput,
                                onValueChange = { newTagInput = it },
                                label = { Text("New #tag name") },
                                placeholder = { Text("e.g. vacation, tax, project") },
                                isError = newTagHasComma,
                                supportingText = if (newTagHasComma) {
                                    { Text("Tag names cannot contain commas.") }
                                } else null,
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (sanitizedNewTag.isNotEmpty() && !newTagHasComma) {
                                        onCreateTag(sanitizedNewTag)
                                        newTagInput = ""
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                enabled = sanitizedNewTag.isNotEmpty() && !newTagHasComma
                            ) {
                                Text("Add")
                            }
                        }

                        // Rename Tag Section
                        val currentRenamingTag = tagBeingRenamed
                        if (currentRenamingTag != null) {
                            val cleanedRename = renameInput.trim().trimStart('#').trim()
                            val renameHasComma = ',' in cleanedRename
                            val mergeMatch = remember(cleanedRename, currentRenamingTag, uiState.tags) {
                                if (cleanedRename.isEmpty() || renameHasComma) null
                                else uiState.tags.firstOrNull {
                                    !it.equals(currentRenamingTag, ignoreCase = true) &&
                                        it.equals(cleanedRename, ignoreCase = true)
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = renameInput,
                                            onValueChange = { renameInput = it },
                                            label = { Text("Rename #$currentRenamingTag") },
                                            isError = renameHasComma,
                                            supportingText = if (renameHasComma) {
                                                { Text("Tag names cannot contain commas.") }
                                            } else null,
                                            singleLine = true,
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            enabled = cleanedRename.isNotEmpty() &&
                                                cleanedRename != currentRenamingTag &&
                                                !renameHasComma,
                                            onClick = {
                                                if (mergeMatch != null) {
                                                    pendingMergeTarget = mergeMatch
                                                } else {
                                                    onRenameTag(currentRenamingTag, cleanedRename)
                                                    tagBeingRenamed = null
                                                    renameInput = ""
                                                }
                                            }
                                        ) {
                                            Text(
                                                text = if (mergeMatch != null) "Merge" else "Save",
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                tagBeingRenamed = null
                                                renameInput = ""
                                                pendingMergeTarget = null
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Cancel rename",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    if (mergeMatch != null) {
                                        Text(
                                            text = "Matches existing tag #$mergeMatch — saving will merge all entries into #$mergeMatch.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = semanticColors.cardStrokeSubtle)

                        if (uiState.tags.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No tags created yet. Type a name above to create your first #tag.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(uiState.tags, key = { it }) { tag ->
                                    Card(
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                        ),
                                        border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                        ) {
                                            Text(
                                                text = "#$tag",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.weight(1f)
                                            )
                                            IconButton(
                                                onClick = {
                                                    tagBeingRenamed = tag
                                                    renameInput = tag
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Edit,
                                                    contentDescription = "Rename #$tag",
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            IconButton(
                                                onClick = { tagPendingDelete = tag }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete #$tag",
                                                    tint = semanticColors.expenseCoral,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    // Bookmark Templates Content
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        if (uiState.bookmarks.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No favorite bookmarks saved yet.\nTap ★ in the + New Transaction sheet to save any form as a reusable template.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(uiState.bookmarks, key = { it.id }) { bm ->
                                    val lookupAccounts = uiState.allAccounts.ifEmpty { uiState.accounts }
                                    val bmAccount = remember(bm.accountId, lookupAccounts) {
                                        lookupAccounts.firstOrNull { it.id == bm.accountId }
                                    }
                                    val bmCurrencySpec = remember(bmAccount?.currencyCode, uiState.currencySpec) {
                                        bmAccount?.currencyCode?.let { SupportedCurrencies.fromCode(it) }
                                            ?: uiState.currencySpec
                                    }
                                    Card(
                                        shape = RoundedCornerShape(14.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                        ),
                                        border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = bm.title,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                val subtitleParts = buildList {
                                                    if (bmAccount != null) add(bmAccount.name)
                                                    if (bm.payeeOrDescription.isNotBlank()) add(bm.payeeOrDescription)
                                                    if (bm.tags.isNotEmpty()) {
                                                        add(bm.tags.joinToString(" ") { "#$it" })
                                                    }
                                                }
                                                if (subtitleParts.isNotEmpty()) {
                                                    Text(
                                                        text = subtitleParts.joinToString(" · "),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.width(12.dp))

                                            MoneyText(
                                                money = bm.amount,
                                                currencySpec = bmCurrencySpec,
                                                grouping = uiState.userPreferences.numberGrouping,
                                                colorMode = MoneyColorMode.fromTransactionType(bm.type),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )

                                            Spacer(modifier = Modifier.width(4.dp))

                                            IconButton(
                                                onClick = { bookmarkPendingDeleteId = bm.id }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete Bookmark",
                                                    tint = semanticColors.expenseCoral,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val mergeTarget = pendingMergeTarget
    val mergingSource = tagBeingRenamed
    if (mergeTarget != null && mergingSource != null) {
        AlertDialog(
            onDismissRequest = { pendingMergeTarget = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("Merge #$mergingSource into #$mergeTarget?") },
            text = {
                Text("A tag named #$mergeTarget already exists. Merging will move all transactions and bookmarks tagged with #$mergingSource onto #$mergeTarget and remove #$mergingSource.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameTag(mergingSource, mergeTarget)
                        pendingMergeTarget = null
                        tagBeingRenamed = null
                        renameInput = ""
                    }
                ) {
                    Text("Merge Tags", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingMergeTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    tagPendingDelete?.let { tagToDelete ->
        AlertDialog(
            onDismissRequest = { tagPendingDelete = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Delete #$tagToDelete?") },
            text = {
                Text("This will detach #$tagToDelete from all transactions and bookmarks. The transactions themselves will not be deleted.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteTag(tagToDelete)
                        if (tagBeingRenamed.equals(tagToDelete, ignoreCase = true)) {
                            tagBeingRenamed = null
                            renameInput = ""
                        }
                        tagPendingDelete = null
                    }
                ) {
                    Text("Delete Tag", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { tagPendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    val bookmarkToDelete = bookmarkPendingDeleteId?.let { id ->
        uiState.bookmarks.firstOrNull { it.id == id }
    }
    if (bookmarkToDelete != null) {
        AlertDialog(
            onDismissRequest = { bookmarkPendingDeleteId = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Delete \"${bookmarkToDelete.title}\"?") },
            text = {
                Text("Remove this saved bookmark template? Existing transactions created from it will remain untouched.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteBookmark(bookmarkToDelete.id)
                        bookmarkPendingDeleteId = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { bookmarkPendingDeleteId = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}