/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.ledger.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.model.UNCATEGORIZED_CATEGORY_ID
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import com.folioledger.app.ui.components.CategoryIconBadge
import com.folioledger.app.ui.ledger.LedgerHomeUiState
import com.folioledger.app.ui.ledger.rememberAsyncEchoTextGuard
import com.folioledger.app.ui.theme.LocalFolioSemanticColors

enum class LedgerFilterSheetTab {
    EXPENSE,
    INCOME,
    ACCOUNTS
}

@Composable
private fun FilterTabPill(
    title: String,
    badgeCount: Int,
    selected: Boolean,
    selectedColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val badgeTextColor = if (selectedColor.luminance() > 0.45f) Color(0xFF111827) else Color.White
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) selectedColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            if (selected) selectedColor.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        ),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (badgeCount > 0) {
                Spacer(modifier = Modifier.width(5.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = selectedColor
                ) {
                    Text(
                        text = badgeCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = badgeTextColor,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

private data class QuickAmountPreset(
    val label: String,
    val min: String,
    val max: String
)

private fun resolveAmountPresets(currencyCode: String, symbol: String): List<QuickAmountPreset> {
    return when (currencyCode.uppercase()) {
        "INR" -> listOf(
            QuickAmountPreset("< ₹500", "", "500"),
            QuickAmountPreset("₹500 - ₹2K", "500", "2000"),
            QuickAmountPreset("₹2K - ₹10K", "2000", "10000"),
            QuickAmountPreset("> ₹10K", "10000", "")
        )
        "JPY", "KRW" -> listOf(
            QuickAmountPreset("< $symbol 1K", "", "1000"),
            QuickAmountPreset("$symbol 1K - 5K", "1000", "5000"),
            QuickAmountPreset("$symbol 5K - 25K", "5000", "25000"),
            QuickAmountPreset("> $symbol 25K", "25000", "")
        )
        else -> listOf(
            QuickAmountPreset("< $symbol 10", "", "10"),
            QuickAmountPreset("$symbol 10 - $symbol 50", "10", "50"),
            QuickAmountPreset("$symbol 50 - $symbol 200", "50", "200"),
            QuickAmountPreset("> $symbol 200", "200", "")
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReinforcedSearchFilterBottomSheet(
    uiState: LedgerHomeUiState,
    onQueryChange: (String) -> Unit,
    onToggleType: (TransactionType) -> Unit,
    onToggleAccount: (Long) -> Unit,
    onToggleCategory: (Long) -> Unit,
    onToggleTag: (String) -> Unit,
    onToggleTransfers: () -> Unit,
    onUpdateAmountBounds: (String, String) -> Unit,
    onFilterAllPeriodsChange: (Boolean) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val semanticColors = LocalFolioSemanticColors.current
    val filter = uiState.searchFilter
    val amountSpec = uiState.amountFilterCurrencySpec
    var selectedFilterTab by rememberSaveable { mutableStateOf(LedgerFilterSheetTab.EXPENSE) }

    val queryGuard = rememberAsyncEchoTextGuard(filter.queryText)
    val minAmountGuard = rememberAsyncEchoTextGuard(uiState.minAmountFilterInput)
    val maxAmountGuard = rememberAsyncEchoTextGuard(uiState.maxAmountFilterInput)

    var lastValidMin by rememberSaveable { mutableStateOf(uiState.minAmountFilterInput) }
    var lastValidMax by rememberSaveable { mutableStateOf(uiState.maxAmountFilterInput) }

    val minVal = MoneyFormatterAndCalculator.evaluateNonNegativeExpressionToMinor(
        expression = minAmountGuard.text.trim(),
        fractionDigits = amountSpec.fractionDigits
    ).getOrNull()
    val maxVal = MoneyFormatterAndCalculator.evaluateNonNegativeExpressionToMinor(
        expression = maxAmountGuard.text.trim(),
        fractionDigits = amountSpec.fractionDigits
    ).getOrNull()
    val isMinMalformed = minAmountGuard.text.isNotBlank() && minVal == null
    val isMaxMalformed = maxAmountGuard.text.isNotBlank() && maxVal == null
    val hasInvalidAmountRange = minVal != null && maxVal != null && minVal > maxVal
    val hasAmountInputError = isMinMalformed || isMaxMalformed || hasInvalidAmountRange

    LaunchedEffect(minAmountGuard.text, maxAmountGuard.text, hasAmountInputError) {
        if (!hasAmountInputError) {
            lastValidMin = minAmountGuard.text
            lastValidMax = maxAmountGuard.text
        }
    }

    val handleDismiss = {
        if (hasAmountInputError) {
            onUpdateAmountBounds(lastValidMin, lastValidMax)
        }
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = handleDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
        ) {
            // Fixed Title Row (always available for quick reset/close/drag-to-dismiss)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)
            ) {
                Text(
                    text = "Search & Multi-Filter",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        lastValidMin = ""
                        lastValidMax = ""
                        onClearAll()
                    }) {
                        Text("Reset All")
                    }
                    IconButton(onClick = handleDismiss) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = "Close")
                    }
                }
            }

            HorizontalDivider(color = semanticColors.cardStrokeSubtle)

            // Unified Scrollable Body so keyboard / small viewports never crush filter controls
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                OutlinedTextField(
                    value = queryGuard.text,
                    onValueChange = {
                        queryGuard.onUserEdit(it)
                        onQueryChange(it)
                    },
                    label = { Text("Keyword (Payee, Description, Note)") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (queryGuard.text.isNotEmpty()) {
                            IconButton(onClick = {
                                queryGuard.onUserEdit("")
                                onQueryChange("")
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = "Clear search query"
                                )
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = { handleDismiss() }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Only in ${uiState.activePeriodRange.title}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Turn off to search across all dates",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = !uiState.filterAllPeriods,
                        onCheckedChange = { onlyThisMonth -> onFilterAllPeriodsChange(!onlyThisMonth) }
                    )
                }

                // 3-Tab Filter Switcher
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilterTabPill(
                        title = "Expense",
                        badgeCount = uiState.expenseFilterCount,
                        selected = selectedFilterTab == LedgerFilterSheetTab.EXPENSE,
                        selectedColor = semanticColors.expenseCoral,
                        onClick = { selectedFilterTab = LedgerFilterSheetTab.EXPENSE },
                        modifier = Modifier.weight(1f)
                    )
                    FilterTabPill(
                        title = "Income",
                        badgeCount = uiState.incomeFilterCount,
                        selected = selectedFilterTab == LedgerFilterSheetTab.INCOME,
                        selectedColor = semanticColors.incomeEmerald,
                        onClick = { selectedFilterTab = LedgerFilterSheetTab.INCOME },
                        modifier = Modifier.weight(1f)
                    )
                    FilterTabPill(
                        title = "Accounts & Amount",
                        badgeCount = uiState.accountFilterCount,
                        selected = selectedFilterTab == LedgerFilterSheetTab.ACCOUNTS,
                        selectedColor = semanticColors.transferBlue,
                        onClick = { selectedFilterTab = LedgerFilterSheetTab.ACCOUNTS },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (filter.categoryIds.isNotEmpty() &&
                    (TransactionType.TRANSFER in filter.types || TransactionType.CC_SETTLEMENT in filter.types)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = semanticColors.warningAmberContainer.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Note: Transfers have no categories, so combining Category + Transfers yields 0 matches.",
                            style = MaterialTheme.typography.labelSmall,
                            color = semanticColors.onWarningAmberContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                HorizontalDivider(color = semanticColors.cardStrokeSubtle)

                when (selectedFilterTab) {
                    LedgerFilterSheetTab.EXPENSE -> {
                        Text(
                            text = "Transaction Type",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = TransactionType.EXPENSE in filter.types,
                                onClick = { onToggleType(TransactionType.EXPENSE) },
                                label = { Text("Expense") }
                            )
                            FilterChip(
                                selected = TransactionType.REFUND in filter.types,
                                onClick = { onToggleType(TransactionType.REFUND) },
                                label = { Text("Refund") }
                            )
                        }

                        val expenseCategoriesHeading = if (uiState.expenseCategories.isNotEmpty()) {
                            "Expense Categories (${uiState.expenseCategories.size})"
                        } else {
                            "Expense Categories"
                        }
                        Text(
                            text = expenseCategoriesHeading,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val isUncategorizedSelected = UNCATEGORIZED_CATEGORY_ID in filter.categoryIds
                            FilterChip(
                                selected = isUncategorizedSelected,
                                onClick = { onToggleCategory(UNCATEGORIZED_CATEGORY_ID) },
                                leadingIcon = {
                                    CategoryIconBadge(
                                        iconName = "help_outline",
                                        colorHex = "#F59E0B",
                                        size = 18.dp,
                                        iconSize = 12.dp
                                    )
                                },
                                label = { Text("Uncategorized") }
                            )
                            uiState.expenseCategories.forEach { cat ->
                                FilterChip(
                                    selected = cat.id in filter.categoryIds,
                                    onClick = { onToggleCategory(cat.id) },
                                    leadingIcon = {
                                        CategoryIconBadge(
                                            iconName = cat.iconName,
                                            colorHex = cat.colorHex,
                                            size = 18.dp,
                                            iconSize = 12.dp
                                        )
                                    },
                                    label = { Text(cat.name) }
                                )
                                cat.subcategories.forEach { sub ->
                                    FilterChip(
                                        selected = sub.id in filter.categoryIds,
                                        onClick = { onToggleCategory(sub.id) },
                                        leadingIcon = {
                                            CategoryIconBadge(
                                                iconName = sub.iconName,
                                                colorHex = sub.colorHex,
                                                size = 18.dp,
                                                iconSize = 12.dp
                                            )
                                        },
                                        label = { Text("${cat.name} › ${sub.name}") }
                                    )
                                }
                            }
                        }

                        if (uiState.availableTags.isNotEmpty()) {
                            Text(
                                text = "Spending Tags",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                uiState.availableTags.forEach { tag ->
                                    FilterChip(
                                        selected = tag in filter.tags,
                                        onClick = { onToggleTag(tag) },
                                        label = { Text("#$tag") }
                                    )
                                }
                            }
                        }
                    }

                    LedgerFilterSheetTab.INCOME -> {
                        Text(
                            text = "Transaction Type",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = TransactionType.INCOME in filter.types,
                                onClick = { onToggleType(TransactionType.INCOME) },
                                label = { Text("Income") }
                            )
                        }

                        val incomeCategoriesHeading = if (uiState.incomeCategories.isNotEmpty()) {
                            "Income Categories (${uiState.incomeCategories.size})"
                        } else {
                            "Income Categories"
                        }
                        Text(
                            text = incomeCategoriesHeading,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val isUncategorizedSelected = UNCATEGORIZED_CATEGORY_ID in filter.categoryIds
                            FilterChip(
                                selected = isUncategorizedSelected,
                                onClick = { onToggleCategory(UNCATEGORIZED_CATEGORY_ID) },
                                leadingIcon = {
                                    CategoryIconBadge(
                                        iconName = "help_outline",
                                        colorHex = "#F59E0B",
                                        size = 18.dp,
                                        iconSize = 12.dp
                                    )
                                },
                                label = { Text("Uncategorized (All)") }
                            )
                            uiState.incomeCategories.forEach { cat ->
                                FilterChip(
                                    selected = cat.id in filter.categoryIds,
                                    onClick = { onToggleCategory(cat.id) },
                                    leadingIcon = {
                                        CategoryIconBadge(
                                            iconName = cat.iconName,
                                            colorHex = cat.colorHex,
                                            size = 18.dp,
                                            iconSize = 12.dp
                                        )
                                    },
                                    label = { Text(cat.name) }
                                )
                                cat.subcategories.forEach { sub ->
                                    FilterChip(
                                        selected = sub.id in filter.categoryIds,
                                        onClick = { onToggleCategory(sub.id) },
                                        leadingIcon = {
                                            CategoryIconBadge(
                                                iconName = sub.iconName,
                                                colorHex = sub.colorHex,
                                                size = 18.dp,
                                                iconSize = 12.dp
                                            )
                                        },
                                        label = { Text("${cat.name} › ${sub.name}") }
                                    )
                                }
                            }
                        }
                    }

                    LedgerFilterSheetTab.ACCOUNTS -> {
                        if (uiState.availableAccounts.isNotEmpty()) {
                            Text(
                                text = "Accounts (${uiState.availableAccounts.size})",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                uiState.availableAccounts.forEach { account ->
                                    val isSelected = account.id in filter.accountIds ||
                                        (filter.accountIds.isEmpty() && uiState.scopedAccountId == account.id)
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { onToggleAccount(account.id) },
                                        leadingIcon = {
                                            CategoryIconBadge(
                                                iconName = account.iconName,
                                                colorHex = account.colorHex,
                                                size = 18.dp,
                                                iconSize = 12.dp
                                            )
                                        },
                                        label = { Text(account.name) }
                                    )
                                }
                            }
                        }

                        Text(
                            text = "Transfers & Settlements",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        val isTransferSelected = TransactionType.TRANSFER in filter.types || TransactionType.CC_SETTLEMENT in filter.types
                        FilterChip(
                            selected = isTransferSelected,
                            onClick = onToggleTransfers,
                            label = { Text("⇄ Internal Transfers & Settlements") }
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Amount Range (${amountSpec.symbol})",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            if (minAmountGuard.text.isNotBlank() || maxAmountGuard.text.isNotBlank()) {
                                TextButton(
                                    onClick = {
                                        minAmountGuard.onUserEdit("")
                                        maxAmountGuard.onUserEdit("")
                                        lastValidMin = ""
                                        lastValidMax = ""
                                        onUpdateAmountBounds("", "")
                                    }
                                ) {
                                    Text("Clear", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }

                        // Quick Presets
                        val presets = remember(amountSpec) {
                            resolveAmountPresets(amountSpec.code, amountSpec.symbol)
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            presets.forEach { preset ->
                                val isSelected = minAmountGuard.text.trim() == preset.min && maxAmountGuard.text.trim() == preset.max
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        if (isSelected) {
                                            minAmountGuard.onUserEdit("")
                                            maxAmountGuard.onUserEdit("")
                                            onUpdateAmountBounds("", "")
                                        } else {
                                            minAmountGuard.onUserEdit(preset.min)
                                            maxAmountGuard.onUserEdit(preset.max)
                                            onUpdateAmountBounds(preset.min, preset.max)
                                        }
                                    },
                                    label = { Text(preset.label) }
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val tryCommitAmountBounds: (String, String) -> Unit = { nextMin, nextMax ->
                                val parsedNextMin = if (nextMin.isBlank()) null else {
                                    MoneyFormatterAndCalculator.evaluateNonNegativeExpressionToMinor(
                                        expression = nextMin.trim(),
                                        fractionDigits = amountSpec.fractionDigits
                                    ).getOrNull()
                                }
                                val parsedNextMax = if (nextMax.isBlank()) null else {
                                    MoneyFormatterAndCalculator.evaluateNonNegativeExpressionToMinor(
                                        expression = nextMax.trim(),
                                        fractionDigits = amountSpec.fractionDigits
                                    ).getOrNull()
                                }
                                val nextMinBad = nextMin.isNotBlank() && parsedNextMin == null
                                val nextMaxBad = nextMax.isNotBlank() && parsedNextMax == null
                                val nextInverted = parsedNextMin != null && parsedNextMax != null && parsedNextMin > parsedNextMax
                                if (!nextMinBad && !nextMaxBad && !nextInverted) {
                                    onUpdateAmountBounds(nextMin, nextMax)
                                }
                            }
                            OutlinedTextField(
                                value = minAmountGuard.text,
                                onValueChange = { nextMin ->
                                    minAmountGuard.onUserEdit(nextMin)
                                    tryCommitAmountBounds(nextMin, maxAmountGuard.text)
                                },
                                label = { Text("Min Amount") },
                                prefix = { Text(amountSpec.symbol, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                isError = isMinMalformed || hasInvalidAmountRange,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = maxAmountGuard.text,
                                onValueChange = { nextMax ->
                                    maxAmountGuard.onUserEdit(nextMax)
                                    tryCommitAmountBounds(minAmountGuard.text, nextMax)
                                },
                                label = { Text("Max Amount") },
                                prefix = { Text(amountSpec.symbol, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                isError = isMaxMalformed || hasInvalidAmountRange,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (hasAmountInputError) {
                            Text(
                                text = when {
                                    hasInvalidAmountRange -> "Minimum amount cannot be greater than maximum amount."
                                    else -> "Enter a valid non-negative amount."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = semanticColors.cardStrokeSubtle)

            // Pinned Bottom Action Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Button(
                    onClick = handleDismiss,
                    enabled = !hasAmountInputError,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = when {
                            hasInvalidAmountRange -> "Invalid Amount Range"
                            hasAmountInputError -> "Invalid Amount"
                            uiState.filteredTransactions.isNotEmpty() ->
                                "Apply Filters (${uiState.filteredTransactions.size} Results)"
                            uiState.isFilterActive -> "Apply Filters (0 Results)"
                            else -> "Close Filters"
                        }
                    )
                }
            }
        }
    }
}
