/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.accounts.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import com.folioledger.app.ui.accounts.ReconcileBalanceDialogState
import com.folioledger.app.ui.accounts.formatMinorPlainForInput
import com.folioledger.app.ui.accounts.parseInputToMinorOrZero
import com.folioledger.app.ui.theme.tabularMoneyStyle

@Composable
fun ReconcileBalanceDialog(
    state: ReconcileBalanceDialogState,
    isSaving: Boolean = false,
    onUpdateTargetBalance: (String) -> Unit,
    onUpdateNote: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var localTargetBalance by rememberSaveable(state.accountId) { mutableStateOf(state.targetBalanceInput) }
    var localNote by rememberSaveable(state.accountId) { mutableStateOf(state.note) }

    val isLiability = state.isLiability
    val isInvestment = state.isInvestment
    val recordedMinor = if (isLiability) {
        MoneyFormatterAndCalculator.safeNegate(state.recordedBalance.amountMinor)
    } else {
        state.recordedBalance.amountMinor
    }
    val spec = SupportedCurrencies.fromCode(state.currencyCode)
    val parsedTarget = parseInputToMinorOrZero(
        rawInput = localTargetBalance,
        fractionDigits = spec.fractionDigits,
        allowBlankAsZero = false
    )
    val diffMinor = parsedTarget?.let {
        MoneyFormatterAndCalculator.safeSubtract(it.amountMinor, recordedMinor)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isInvestment) "Update Value \u00B7 ${state.accountName}" else "Reconcile ${state.accountName}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = when {
                        isInvestment -> "Update Folio's portfolio valuation to match your current brokerage or market statement."
                        isLiability -> "Match Folio's outstanding balance against your official credit card or loan statement."
                        else -> "Match Folio's balance against your official bank statement or physical cash."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = when {
                                isInvestment -> "Current Portfolio Value"
                                isLiability -> "Current Recorded Outstanding Debt"
                                else -> "Current Recorded Balance"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val displayRecorded = MinorMoney(recordedMinor)
                        Text(
                            text = MoneyFormatterAndCalculator.formatMinorMoney(
                                money = displayRecorded,
                                currencySpec = spec
                            ),
                            style = tabularMoneyStyle(MaterialTheme.typography.titleMedium),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                OutlinedTextField(
                    value = localTargetBalance,
                    onValueChange = {
                        localTargetBalance = it
                        onUpdateTargetBalance(it)
                    },
                    label = {
                        Text(
                            when {
                                isInvestment -> "Actual Market Value"
                                isLiability -> "Actual Statement Outstanding Debt"
                                else -> "Actual Statement Balance"
                            }
                        )
                    },
                    placeholder = { Text("0.00") },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val trimmed = localTargetBalance.trim()
                                val toggled = when {
                                    trimmed.startsWith("-") -> trimmed.removePrefix("-")
                                    trimmed.isEmpty() || trimmed == "0" -> "-"
                                    else -> "-$trimmed"
                                }
                                localTargetBalance = toggled
                                onUpdateTargetBalance(toggled)
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "Toggle positive or negative sign"
                            }
                        ) {
                            Text(
                                text = "\u00B1",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    singleLine = true,
                    isError = state.validationError != null,
                    supportingText = state.validationError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    modifier = Modifier.fillMaxWidth()
                )

                if (diffMinor != null) {
                    val adjustmentColor = when {
                        diffMinor == 0L -> MaterialTheme.colorScheme.onSurfaceVariant
                        isLiability -> if (diffMinor > 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        else -> if (diffMinor > 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    }
                    val diffSign = if (diffMinor > 0L) "+" else "-"
                    val diffText = if (diffMinor == 0L) {
                        "✓ Balances match. No adjustment needed."
                    } else if (isLiability) {
                        val formattedDiff = formatMinorPlainForInput(MinorMoney(MoneyFormatterAndCalculator.safeAbs(diffMinor)), spec.fractionDigits)
                        if (diffMinor > 0L) {
                            "Adjustment: +${state.currencyCode} $formattedDiff (Increases debt)"
                        } else {
                            "Adjustment: -${state.currencyCode} $formattedDiff (Decreases debt)"
                        }
                    } else {
                        val formattedDiff = formatMinorPlainForInput(MinorMoney(MoneyFormatterAndCalculator.safeAbs(diffMinor)), spec.fractionDigits)
                        val actionDesc = when {
                            isInvestment && diffMinor > 0L -> "Valuation gain"
                            isInvestment -> "Valuation loss"
                            diffMinor > 0L -> "Inflow adjustment"
                            else -> "Outflow adjustment"
                        }
                        "Adjustment: $diffSign${state.currencyCode} $formattedDiff ($actionDesc)"
                    }

                    Text(
                        text = diffText,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = adjustmentColor
                    )
                }

                OutlinedTextField(
                    value = localNote,
                    onValueChange = {
                        localNote = it
                        onUpdateNote(it)
                    },
                    label = { Text("Note (Optional)") },
                    placeholder = {
                        Text(if (isInvestment) "e.g., Monthly portfolio valuation" else "e.g., Monthly bank reconciliation")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onConfirm()
                },
                enabled = parsedTarget != null && !isSaving
            ) {
                Text(if (diffMinor == 0L) "Confirm Match" else "Apply Adjustment")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
