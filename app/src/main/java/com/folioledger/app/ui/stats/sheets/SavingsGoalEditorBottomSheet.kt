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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.ui.components.CategoryIconBadge
import com.folioledger.app.ui.theme.LocalCurrencyDisplayPolicy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SavingsGoalEditorBottomSheet(
    isEditingExisting: Boolean,
    selectedAccountId: Long?,
    title: String,
    targetAmountInput: String,
    targetDateEpochDay: Long?,
    validationError: String? = null,
    existingGoalAccountIds: Set<Long> = emptySet(),
    availableAccounts: List<AccountSummary>,
    currencySpec: CurrencySpec,
    onClearValidationError: () -> Unit = {},
    onUpdate: (accountId: Long?, title: String, targetAmountInput: String, targetDateEpochDay: Long?) -> Unit,
    onSave: (accountId: Long, title: String, targetAmountInput: String, targetDateEpochDay: Long?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val eligibleAccounts = remember(availableAccounts) {
        availableAccounts.sortedBy {
            if (it.groupType == AccountGroup.SAVINGS || it.groupType == AccountGroup.INVESTMENT) 0 else 1
        }
    }
    val currentAccountId = selectedAccountId ?: eligibleAccounts.firstOrNull()?.id ?: 0L
    val selectedAccountSpec = remember(currentAccountId, eligibleAccounts, currencySpec) {
        val account = eligibleAccounts.firstOrNull { it.id == currentAccountId }
        if (account != null) SupportedCurrencies.fromCode(account.currencyCode) else currencySpec
    }

    var localTitle by rememberSaveable(selectedAccountId) { mutableStateOf(title) }
    var localTargetAmount by rememberSaveable(selectedAccountId) { mutableStateOf(targetAmountInput) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = if (isEditingExisting) "Edit Savings Goal" else "New Savings Goal",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = "Link this goal directly to an account. Progress is verified in real-time from the live ledger balance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Target Account Selector Chips
            Text(
                text = "Target Account",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(
                    items = eligibleAccounts,
                    key = { it.id },
                ) { acc: AccountSummary ->
                    FilterChip(
                        selected = acc.id == currentAccountId,
                        enabled = if (isEditingExisting) {
                            acc.id == currentAccountId
                        } else {
                            acc.id !in existingGoalAccountIds
                        },
                        onClick = {
                            if (!isEditingExisting) {
                                onUpdate(acc.id, localTitle, localTargetAmount, targetDateEpochDay)
                            }
                        },
                        label = { Text(acc.name) },
                        leadingIcon = {
                            CategoryIconBadge(
                                iconName = acc.iconName,
                                colorHex = acc.colorHex,
                                size = 20.dp,
                                iconSize = 12.dp,
                            )
                        },
                    )
                }
            }

            // Goal Title
            OutlinedTextField(
                value = localTitle,
                onValueChange = {
                    localTitle = it
                },
                label = { Text("Goal Title") },
                placeholder = { Text("e.g. Emergency Fund, House Down Payment") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Target Amount
            val effectiveTargetSymbol = LocalCurrencyDisplayPolicy.current
                .effectiveSymbol(selectedAccountSpec)
            OutlinedTextField(
                value = localTargetAmount,
                onValueChange = {
                    localTargetAmount = it
                    if (validationError != null) onClearValidationError()
                },
                label = {
                    Text(
                        if (effectiveTargetSymbol.isNotEmpty()) {
                            "Target Amount ($effectiveTargetSymbol)"
                        } else {
                            "Target Amount"
                        },
                    )
                },
                placeholder = { Text("e.g. 500000") },
                isError = !validationError.isNullOrBlank(),
                supportingText = validationError?.let { err ->
                    { Text(text = err) }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Actions
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                if (isEditingExisting) {
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Remove")
                    }
                }
                Button(
                    onClick = {
                        onSave(currentAccountId, localTitle, localTargetAmount, targetDateEpochDay)
                    },
                    enabled = localTargetAmount.isNotBlank() && currentAccountId > 0L,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Save Goal")
                }
            }
        }
    }
}
