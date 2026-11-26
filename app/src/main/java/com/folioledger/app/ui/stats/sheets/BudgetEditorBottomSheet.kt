/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.stats.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.folioledger.app.domain.model.BudgetPeriodType
import com.folioledger.app.domain.model.CategoryNode
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.util.BudgetWindowAndRolloverCalculator
import com.folioledger.app.ui.components.CategoryIconBadge
import com.folioledger.app.ui.stats.BudgetEditorDialogState
import com.folioledger.app.ui.stats.dialogs.CustomDateRangePickerDialog
import com.folioledger.app.ui.theme.LocalFolioSemanticColors
import com.folioledger.app.ui.theme.tabularMoneyStyle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BudgetEditorBottomSheet(
    state: BudgetEditorDialogState,
    expenseCategories: List<CategoryNode>,
    currencySpec: CurrencySpec,
    isSaving: Boolean,
    onUpdateState: ((BudgetEditorDialogState) -> BudgetEditorDialogState) -> Unit,
    onSave: (limitInput: String, customName: String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val semanticColors = LocalFolioSemanticColors.current

    var localLimitInput by rememberSaveable(state.budgetId) { mutableStateOf(state.limitAmountInput) }
    var localCustomName by rememberSaveable(state.budgetId) { mutableStateOf(state.customName) }

    var isCustomDateRangePickerOpen by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
        ) {
            // Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryIconBadge(
                        iconName = if (state.isOverallMonthlyBudget) "savings" else "category",
                        colorHex = state.selectedCategoryColorHex,
                        size = 40.dp,
                        iconSize = 20.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (state.isEditing) "Edit ${state.periodType.displayLabel} Budget" else "New ${state.periodType.displayLabel} Budget",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = "Close")
                }
            }

            if (!state.validationError.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = semanticColors.expenseCoralContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = state.validationError,
                        style = MaterialTheme.typography.labelMedium,
                        color = semanticColors.onExpenseCoralContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // Budget Cadence Selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Budget Cadence",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(BudgetPeriodType.entries, key = { it.name }) { type ->
                        FilterChip(
                            selected = state.periodType == type,
                            onClick = {
                                onUpdateState {
                                    val today = LocalDate.now().toEpochDay()
                                    val switchingFromCustom = it.periodType == BudgetPeriodType.CUSTOM && type != BudgetPeriodType.CUSTOM
                                    val start = when {
                                        type == BudgetPeriodType.CUSTOM -> it.startDateEpochDay ?: today
                                        switchingFromCustom -> null
                                        else -> it.startDateEpochDay
                                    }
                                    val end = if (type == BudgetPeriodType.CUSTOM) it.endDateEpochDay ?: (today + 13L) else null
                                    it.copy(
                                        periodType = type,
                                        startDateEpochDay = start,
                                        endDateEpochDay = end,
                                        rolloverEnabled = if (type.supportsRollover) it.rolloverEnabled else false,
                                        rolloverIncludeDeficit = if (type.supportsRollover) it.rolloverIncludeDeficit else false
                                    )
                                }
                            },
                            label = { Text(type.displayLabel) }
                        )
                    }
                }
            }

            // Custom Date Range Card (When CUSTOM)
            if (state.periodType == BudgetPeriodType.CUSTOM) {
                val startDay = state.startDateEpochDay ?: LocalDate.now().toEpochDay()
                val endDay = state.endDateEpochDay ?: (startDay + 13L)
                val dateLabel = remember(startDay, endDay) {
                    BudgetWindowAndRolloverCalculator.formatDateSpan(startDay, endDay)
                }

                OutlinedCard(
                    onClick = { isCustomDateRangePickerOpen = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Column {
                            Text(
                                text = "Budget Duration",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = dateLabel,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.DateRange,
                            contentDescription = "Pick Date Range",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Budget Name Field (Required for CUSTOM, Optional for recurring)
            OutlinedTextField(
                value = localCustomName,
                onValueChange = {
                    localCustomName = it
                    if (state.validationError != null) {
                        onUpdateState { s -> s }
                    }
                },
                label = {
                    Text(
                        if (state.periodType == BudgetPeriodType.CUSTOM) "Budget Name (e.g. Europe Trip)" else "Optional Title (e.g. 2026 Annual Spend Cap)"
                    )
                },
                placeholder = {
                    Text(
                        if (state.periodType == BudgetPeriodType.CUSTOM) "e.g. Diwali Shopping, Tokyo Trip" else "Leave blank to use category name"
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Scope Switcher: Overall Budget vs Specific Category
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Budget Scope",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilterChip(
                        selected = state.isOverallMonthlyBudget,
                        onClick = {
                            onUpdateState {
                                it.copy(
                                    isOverallMonthlyBudget = true,
                                    selectedCategoryId = null,
                                    selectedCategoryName = "${it.periodType.displayLabel} Overall Budget",
                                    selectedCategoryColorHex = "#059669"
                                )
                            }
                        },
                        label = { Text("Overall Spend Cap") }
                    )

                    FilterChip(
                        selected = !state.isOverallMonthlyBudget,
                        onClick = {
                            val firstCat = expenseCategories.firstOrNull()
                            onUpdateState {
                                it.copy(
                                    isOverallMonthlyBudget = false,
                                    selectedCategoryId = it.selectedCategoryId ?: firstCat?.id,
                                    selectedCategoryName = if (it.selectedCategoryId != null) {
                                        it.selectedCategoryName
                                    } else {
                                        firstCat?.name ?: "Select Category"
                                    },
                                    selectedCategoryColorHex = if (it.selectedCategoryId != null) {
                                        it.selectedCategoryColorHex
                                    } else {
                                        firstCat?.colorHex ?: "#059669"
                                    }
                                )
                            }
                        },
                        label = { Text("Specific Category") }
                    )
                }
            }

            if (!state.isOverallMonthlyBudget) {
                Text(
                    text = "Select Expense Category",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    expenseCategories.forEach { cat ->
                        FilterChip(
                            selected = state.selectedCategoryId == cat.id,
                            onClick = {
                                onUpdateState {
                                    it.copy(
                                        selectedCategoryId = cat.id,
                                        selectedCategoryName = cat.name,
                                        selectedCategoryColorHex = cat.colorHex
                                    )
                                }
                            },
                            label = { Text(cat.name) }
                        )
                    }
                }
            }

            // Limit Amount Input
            OutlinedTextField(
                value = localLimitInput,
                onValueChange = {
                    localLimitInput = it
                    if (state.validationError != null) {
                        onUpdateState { s -> s }
                    }
                },
                label = { Text("${state.periodType.displayLabel} Limit (${currencySpec.symbol})") },
                placeholder = { Text("e.g. 50000 or 15000+5000") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Rollover Section (Animated for recurring cadences)
            AnimatedVisibility(
                visible = state.periodType.supportsRollover,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Roll over unused balance",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Carries unspent budget into next ${state.periodType.shortLabel.lowercase()} from transaction history.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = state.rolloverEnabled,
                                onCheckedChange = { checked ->
                                    val today = LocalDate.now().toEpochDay()
                                    onUpdateState {
                                        it.copy(
                                            rolloverEnabled = checked,
                                            rolloverIncludeDeficit = if (checked) it.rolloverIncludeDeficit else false,
                                            startDateEpochDay = when {
                                                checked -> it.startDateEpochDay ?: today
                                                it.periodType == BudgetPeriodType.BI_WEEKLY -> it.startDateEpochDay // preserve bi-weekly cycle anchor
                                                else -> null
                                            }
                                        )
                                    }
                                }
                            )
                        }

                        AnimatedVisibility(
                            visible = state.rolloverEnabled,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Also carry over overspending",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "Reduces next period's limit if you exceed budget.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = state.rolloverIncludeDeficit,
                                        onCheckedChange = { checked ->
                                            onUpdateState { it.copy(rolloverIncludeDeficit = checked) }
                                        }
                                    )
                                }

                                if (state.isEditing && state.startDateEpochDay != null) {
                                    val startFormatted = remember(state.startDateEpochDay) {
                                        LocalDate.ofEpochDay(state.startDateEpochDay)
                                            .format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US))
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "Rollover active since $startFormatted",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        TextButton(
                                            onClick = {
                                                onUpdateState { it.copy(startDateEpochDay = LocalDate.now().toEpochDay()) }
                                            }
                                        ) {
                                            Text("Reset anchor", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Warning Alert Threshold Slider
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Warning Alert Threshold",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${state.alertThresholdPercent}%",
                        style = tabularMoneyStyle(MaterialTheme.typography.labelLarge),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = state.alertThresholdPercent.toFloat(),
                    onValueChange = { value ->
                        onUpdateState { it.copy(alertThresholdPercent = value.toInt().coerceIn(50, 100)) }
                    },
                    valueRange = 50f..100f,
                    steps = 9
                )
            }

            // Action Buttons (Delete / Save)
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isEditing && state.budgetId > 0L) {
                    OutlinedButton(
                        onClick = onDelete,
                        enabled = !isSaving,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = semanticColors.expenseCoral
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Delete")
                    }
                }

                Button(
                    onClick = { onSave(localLimitInput, localCustomName) },
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (state.isEditing) "Save Budget" else "Create Budget")
                }
            }
        }
    }

    if (isCustomDateRangePickerOpen) {
        val initialStart = state.startDateEpochDay ?: LocalDate.now().toEpochDay()
        val initialEnd = state.endDateEpochDay ?: (initialStart + 13L)
        CustomDateRangePickerDialog(
            initialStartEpochDay = initialStart,
            initialEndEpochDay = initialEnd,
            onConfirmRange = { s, e ->
                onUpdateState { it.copy(startDateEpochDay = s, endDateEpochDay = e) }
                isCustomDateRangePickerOpen = false
            },
            onDismiss = { isCustomDateRangePickerOpen = false }
        )
    }
}
