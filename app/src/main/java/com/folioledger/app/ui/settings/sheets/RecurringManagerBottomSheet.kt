/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/ui/settings/sheets/RecurringManagerBottomSheet.kt
package com.folioledger.app.ui.settings.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folioledger.app.domain.model.RecurringCommitmentItem
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.util.RecurringScheduleCalculator
import com.folioledger.app.ui.settings.SettingsUiState
import com.folioledger.app.ui.settings.sheets.components.EditRecurringRuleDialog
import com.folioledger.app.ui.settings.sheets.components.InstallmentsTabContent
import com.folioledger.app.ui.settings.sheets.components.MonthlyBurdenHeroCard
import com.folioledger.app.ui.settings.sheets.components.SubscriptionsTabContent
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringManagerBottomSheet(
    uiState: SettingsUiState,
    onToggleRecurringActive: (Long, Boolean) -> Unit,
    onDeleteRecurringRule: (Long) -> Unit,
    onUpdateRecurringRule: (RecurringCommitmentItem) -> Unit,
    onDeleteInstallmentPlan: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    var ruleToDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var ruleToEditId by rememberSaveable { mutableStateOf<Long?>(null) }
    var planToDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var ruleToResumeId by rememberSaveable { mutableStateOf<Long?>(null) }

    val ruleToDelete = ruleToDeleteId?.let { id -> uiState.recurringRules.firstOrNull { it.id == id } }
    val ruleToEdit = ruleToEditId?.let { id -> uiState.recurringRules.firstOrNull { it.id == id } }
    val planToDelete = planToDeleteId?.let { id -> uiState.installmentPlans.firstOrNull { it.id == id } }
    val ruleToResume = ruleToResumeId?.let { id -> uiState.recurringRules.firstOrNull { it.id == id } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(bottom = 24.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Autorenew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(9.dp)
                                .size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Recurring & Subscriptions",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Manage repeat commitments & EMIs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Hero Summary Card: Monthly Fixed Burden
            MonthlyBurdenHeroCard(
                uiState = uiState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Primary Tabs
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            text = "Recurring Rules (${uiState.recurringRules.size})",
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            text = "EMI Plans (${uiState.installmentPlans.size})",
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Tab Content
            when (selectedTab) {
                0 -> {
                    SubscriptionsTabContent(
                        rules = uiState.recurringRules,
                        uiState = uiState,
                        onToggleActive = { id, isActive ->
                            val targetRule = uiState.recurringRules.firstOrNull { it.id == id }
                            if (isActive && targetRule != null &&
                                targetRule.nextOccurrenceEpochDay < uiState.referenceDateEpochDay
                            ) {
                                ruleToResumeId = id
                            } else {
                                onToggleRecurringActive(id, isActive)
                            }
                        },
                        onEditRule = { ruleToEditId = it.id },
                        onDeleteRule = { ruleToDeleteId = it.id },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 20.dp)
                    )
                }

                1 -> {
                    InstallmentsTabContent(
                        plans = uiState.installmentPlans,
                        uiState = uiState,
                        onDeletePlan = { planToDeleteId = it.id },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 20.dp)
                    )
                }
            }
        }
    }

    // Delete Recurring Rule Confirmation Dialog
    ruleToDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { ruleToDeleteId = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Delete Recurring Rule?") },
            text = {
                Text("Are you sure you want to delete \"${rule.title}\"? Future automated transactions will no longer be generated. Past transactions will remain unchanged.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteRecurringRule(rule.id)
                        ruleToDeleteId = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { ruleToDeleteId = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Resume Overdue Paused Rule Confirmation Dialog
    ruleToResume?.let { rule ->
        val nextScheduledDay = remember(rule, uiState.referenceDateEpochDay) {
            RecurringScheduleCalculator.computeFirstOccurrenceOnOrAfter(
                startDay = rule.nextOccurrenceEpochDay,
                frequency = rule.frequency,
                fromEpochDay = uiState.referenceDateEpochDay,
                anchorDayOfMonth = LocalDate.ofEpochDay(rule.startDateEpochDay).dayOfMonth,
                endDateEpochDay = rule.endDateEpochDay
            )
        }
        val nextScheduledText = remember(nextScheduledDay) {
            nextScheduledDay?.let {
                LocalDate.ofEpochDay(it)
                    .format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault()))
            }
        }
        AlertDialog(
            onDismissRequest = { ruleToResumeId = null },
            title = {
                Text(if (nextScheduledText != null) "Resume Overdue Rule?" else "Recurring Rule Ended")
            },
            text = {
                Text(
                    if (nextScheduledText != null) {
                        "\"${rule.title}\" missed due dates while paused. Resuming will skip past missed dates without backfilling old entries and schedule the next entry for $nextScheduledText."
                    } else {
                        "\"${rule.title}\" has no remaining occurrences before its end date. Edit its schedule or keep it paused."
                    }
                )
            },
            confirmButton = {
                if (nextScheduledText != null) {
                    TextButton(
                        onClick = {
                            onToggleRecurringActive(rule.id, true)
                            ruleToResumeId = null
                        }
                    ) {
                        Text("Resume", fontWeight = FontWeight.Bold)
                    }
                } else {
                    TextButton(onClick = { ruleToResumeId = null }) {
                        Text("OK", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = if (nextScheduledText != null) {
                {
                    TextButton(onClick = { ruleToResumeId = null }) {
                        Text("Keep Paused")
                    }
                }
            } else null
        )
    }

    // Delete Installment Plan Confirmation Dialog
    planToDelete?.let { plan ->
        val isCompletedOrOrphaned = plan.remainingInstallmentsCount == 0
        AlertDialog(
            onDismissRequest = { planToDeleteId = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(if (isCompletedOrOrphaned) "Remove Installment Plan?" else "Cancel Installment Plan?") },
            text = {
                Text(
                    if (isCompletedOrOrphaned) {
                        "Remove \"${plan.title}\" from your EMI plans list? Any recorded transactions remain untouched."
                    } else {
                        "Are you sure you want to remove the installment plan for \"${plan.title}\"? Any remaining future scheduled installments will be removed."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteInstallmentPlan(plan.id)
                        planToDeleteId = null
                    }
                ) {
                    Text(
                        text = if (isCompletedOrOrphaned) "Remove Plan" else "Cancel Plan",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { planToDeleteId = null }) {
                    Text("Keep Plan")
                }
            }
        )
    }

    // Edit Recurring Rule Dialog
    ruleToEdit?.let { rule ->
        val ruleCurrencySpec = remember(rule.currencyCode, uiState.currencySpec) {
            SupportedCurrencies.fromCode(rule.currencyCode)
        }
        EditRecurringRuleDialog(
            rule = rule,
            todayEpochDay = uiState.referenceDateEpochDay,
            currencySpec = ruleCurrencySpec,
            onDismiss = { ruleToEditId = null },
            onSave = { updatedRule ->
                onUpdateRecurringRule(updatedRule)
                ruleToEditId = null
            }
        )
    }
}
