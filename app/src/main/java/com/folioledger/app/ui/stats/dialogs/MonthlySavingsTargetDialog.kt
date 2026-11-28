/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.stats.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.ui.theme.LocalCurrencyDisplayPolicy

@Composable
internal fun MonthlySavingsTargetDialog(
    currentInput: String,
    currencySpec: CurrencySpec,
    isConfigured: Boolean,
    validationError: String? = null,
    onClearValidationError: () -> Unit = {},
    onSave: (targetInput: String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var localInput by rememberSaveable { mutableStateOf(currentInput) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Monthly Wealth Target", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Folio uses double-entry accounting to automatically calculate your actual monthly wealth savings as net transfers into Savings & Investment accounts, independent of expense tracking.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val effectiveSymbol = LocalCurrencyDisplayPolicy.current
                    .effectiveSymbol(currencySpec)
                OutlinedTextField(
                    value = localInput,
                    onValueChange = {
                        localInput = it
                        if (validationError != null) onClearValidationError()
                    },
                    label = {
                        Text(
                            if (effectiveSymbol.isNotEmpty()) {
                                "Target Amount ($effectiveSymbol)"
                            } else {
                                "Target Amount"
                            },
                        )
                    },
                    placeholder = { Text("e.g. 50000") },
                    isError = !validationError.isNullOrBlank(),
                    supportingText = validationError?.let { err ->
                        { Text(text = err) }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(localInput) },
                enabled = localInput.isNotBlank(),
            ) {
                Text("Save Target")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isConfigured) {
                    TextButton(
                        onClick = onRemove,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Remove")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}
