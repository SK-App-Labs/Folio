/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.accounts.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
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
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.ui.accounts.AccountEditorDialogState
import com.folioledger.app.ui.accounts.parseInputToMinorOrZero
import com.folioledger.app.ui.components.MoneyColorMode
import com.folioledger.app.ui.components.MoneyText
import com.folioledger.app.ui.theme.LocalFolioSemanticColors
import com.folioledger.app.ui.theme.tabularMoneyStyle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountBalanceAndDetailsSection(
    state: AccountEditorDialogState,
    referenceDateEpochDay: Long,
    parentBankCandidates: List<AccountSummary>,
    grouping: NumberGroupingSystem = NumberGroupingSystem.INDIAN,
    onUpdateState: ((AccountEditorDialogState) -> AccountEditorDialogState) -> Unit,
    modifier: Modifier = Modifier
) {
    val semanticColors = LocalFolioSemanticColors.current

    var localCreditLimit by rememberSaveable(state.accountId, state.groupType) {
        mutableStateOf(state.creditLimitInput)
    }
    var localOpeningBalance by rememberSaveable(state.accountId) { mutableStateOf(state.openingBalanceInput) }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        when (state.groupType) {
            AccountGroup.CREDIT_CARD -> {
                OutlinedTextField(
                    value = localCreditLimit,
                    onValueChange = { v ->
                        localCreditLimit = v
                        onUpdateState { it.copy(creditLimitInput = v) }
                    },
                    label = { Text("Credit Limit") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                CreditCardBillingCycleSection(
                    state = state,
                    referenceDateEpochDay = referenceDateEpochDay,
                    grouping = grouping,
                    onUpdateState = onUpdateState
                )
            }

            AccountGroup.DEBIT_CARD -> {
                Text(
                    text = "Linked Parent Bank Account",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                if (state.isEditing && state.parentAccountId != null) {
                    val lockedParentName = state.lockedParentAccountName
                        ?: parentBankCandidates.firstOrNull { it.id == state.parentAccountId }?.name
                        ?: "Linked Bank Account"
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = lockedParentName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Linked parent bank account is locked after creation to protect historical balances.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    val eligibleBankCandidates = if (state.isEditing) {
                        parentBankCandidates.filter { it.currencyCode.equals(state.currencyCode, ignoreCase = true) }
                    } else {
                        parentBankCandidates
                    }
                    if (eligibleBankCandidates.isEmpty()) {
                        Text(
                            text = if (state.isEditing) {
                                "No parent Bank or Savings account found with matching currency ${state.currencyCode}."
                            } else {
                                "No Bank or Savings account found. Create a Bank Account first to link a Debit Card."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = semanticColors.expenseCoral
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            eligibleBankCandidates.forEach { candidate ->
                                FilterChip(
                                    selected = state.parentAccountId == candidate.id,
                                    onClick = {
                                        onUpdateState {
                                            it.copy(
                                                parentAccountId = candidate.id,
                                                currencyCode = candidate.currencyCode
                                            )
                                        }
                                    },
                                    label = { Text(candidate.name) }
                                )
                            }
                        }
                    }
                }
            }

            else -> {
                if (state.isEditing) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val labelText = when {
                                state.isLiabilityInCredit -> "Current Credit Balance (Surplus)"
                                state.groupType.isLiabilityByDefault -> "Current Outstanding Balance"
                                else -> "Current Live Balance"
                            }
                            val prefix = if (state.isLiabilityInCredit) "+" else ""
                            Text(
                                text = labelText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val spec = remember(state.currencyCode) {
                                SupportedCurrencies.fromCode(state.currencyCode)
                            }
                            val parsedBalance = parseInputToMinorOrZero(
                                rawInput = state.openingBalanceInput,
                                fractionDigits = spec.fractionDigits,
                                allowBlankAsZero = true
                            )
                            if (parsedBalance != null) {
                                MoneyText(
                                    money = parsedBalance,
                                    currencySpec = spec,
                                    grouping = grouping,
                                    colorMode = if (state.isLiabilityInCredit) MoneyColorMode.INCOME else MoneyColorMode.NEUTRAL,
                                    showPlusSignForPositive = state.isLiabilityInCredit,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Text(
                                    text = "$prefix${state.currencyCode} ${state.openingBalanceInput}",
                                    style = tabularMoneyStyle(MaterialTheme.typography.titleLarge),
                                    fontWeight = FontWeight.Bold,
                                    color = if (state.isLiabilityInCredit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "To adjust or reconcile this balance against your bank statement, use the Reconcile tool in the Passbook.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = localOpeningBalance,
                        onValueChange = { v ->
                            localOpeningBalance = v
                            onUpdateState { it.copy(openingBalanceInput = v) }
                        },
                        label = {
                            Text(
                                if (state.groupType.isLiabilityByDefault) {
                                    "Starting Outstanding Balance"
                                } else {
                                    "Starting Balance"
                                }
                            )
                        },
                        supportingText = {
                            Text(
                                if (state.groupType.isLiabilityByDefault) {
                                    "Initial debt as of the starting date"
                                } else {
                                    "Initial starting balance as of the starting date"
                                }
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    var showDatePickerDialog by rememberSaveable { mutableStateOf(false) }
                    val formattedAnchorDate = remember(state.openingDateEpochDay) {
                        LocalDate.ofEpochDay(state.openingDateEpochDay)
                            .format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US))
                    }

                    OutlinedCard(
                        onClick = { showDatePickerDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Starting Balance Date",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = formattedAnchorDate,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Icon(
                                imageVector = Icons.Filled.CalendarToday,
                                contentDescription = "Select starting date",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    if (showDatePickerDialog) {
                        val initialMillis = remember(state.openingDateEpochDay) {
                            LocalDate.ofEpochDay(state.openingDateEpochDay)
                                .atStartOfDay(ZoneOffset.UTC)
                                .toInstant()
                                .toEpochMilli()
                        }
                        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
                        DatePickerDialog(
                            onDismissRequest = { showDatePickerDialog = false },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        val selectedMillis = datePickerState.selectedDateMillis
                                        if (selectedMillis != null) {
                                            val selectedEpochDay = Instant.ofEpochMilli(selectedMillis)
                                                .atZone(ZoneOffset.UTC)
                                                .toLocalDate()
                                                .toEpochDay()
                                            onUpdateState { it.copy(openingDateEpochDay = selectedEpochDay) }
                                        }
                                        showDatePickerDialog = false
                                    }
                                ) {
                                    Text("Confirm")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDatePickerDialog = false }) {
                                    Text("Cancel")
                                }
                            }
                        ) {
                            DatePicker(state = datePickerState)
                        }
                    }
                }
            }
        }
    }
}
