/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/ui/settings/sheets/SecondaryCurrenciesBottomSheet.kt
package com.folioledger.app.ui.settings.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.ui.theme.LocalFolioSemanticColors
import com.folioledger.app.ui.theme.tabularMoneyStyle
import java.math.BigDecimal

internal fun formatExchangeRatePlain(rate: Double): String = BigDecimal.valueOf(rate).stripTrailingZeros().toPlainString()

internal fun parseExchangeRateInput(raw: String): Double? {
    val trimmed = raw.trim().replace(" ", "").replace("\u00A0", "")
    if (trimmed.isEmpty()) return null
    val lastDot = trimmed.lastIndexOf('.')
    val lastComma = trimmed.lastIndexOf(',')
    val normalized = when {
        lastDot >= 0 && lastComma >= 0 -> {
            if (lastComma > lastDot) {
                trimmed.replace(".", "").replace(',', '.')
            } else {
                trimmed.replace(",", "")
            }
        }

        lastComma >= 0 -> {
            if (trimmed.count { it == ',' } > 1) {
                trimmed.replace(",", "")
            } else {
                trimmed.replace(',', '.')
            }
        }

        else -> trimmed
    }
    val bd = normalized.toBigDecimalOrNull() ?: return null
    val d = bd.toDouble()
    return if (d.isFinite() && d in 1e-6..1_000_000_000.0) d else null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecondaryCurrenciesBottomSheet(
    prefs: UserPreferences,
    allAccounts: List<AccountSummary> = emptyList(),
    onAddOrUpdateSecondaryCurrency: (code: String, rate: Double) -> Unit,
    onRemoveSecondaryCurrency: (code: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val semanticColors = LocalFolioSemanticColors.current

    var editingCurrencyCode by rememberSaveable { mutableStateOf<String?>(null) }
    var currencyPendingDeleteCode by rememberSaveable { mutableStateOf<String?>(null) }
    var isAddingSecondaryCurrency by rememberSaveable { mutableStateOf(false) }

    val editingSecondaryCurrency = editingCurrencyCode?.let { code ->
        prefs.secondaryCurrencies.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp, top = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Column {
                    Text(
                        text = "Secondary Currencies",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Manual conversion rates relative to ${prefs.primaryCurrencyCode}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Informational explanation card
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "How Secondary Currencies Work",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "• When creating an account, you'll see only your Primary and configured Secondary currencies.\n" +
                            "• Account balances convert into ${prefs.primaryCurrencyCode} using your rate for unified Net Worth.\n" +
                            "• Past transactions are always preserved in their original native currency.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Existing Secondary Currencies List
            if (prefs.secondaryCurrencies.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            text = "No Secondary Currencies Added",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Add foreign currencies like USD, EUR, or GBP to manage international accounts with realistic offline rates.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "ACTIVE CURRENCIES (${prefs.secondaryCurrencies.size})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    prefs.secondaryCurrencies.forEach { sec ->
                        val secSpec = SupportedCurrencies.fromCode(sec.code)
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { editingCurrencyCode = sec.code },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(40.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = secSpec.symbol,
                                                style = tabularMoneyStyle(MaterialTheme.typography.titleMedium),
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = "${secSpec.displayName} (${sec.code})",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = "1 ${sec.code} = ${formatExchangeRatePlain(sec.exchangeRateToPrimary)} ${prefs.primaryCurrencyCode}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { editingCurrencyCode = sec.code }) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Edit Rate",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                    IconButton(onClick = { currencyPendingDeleteCode = sec.code }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Remove Currency",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Primary Add Button
            FilledTonalButton(
                onClick = { isAddingSecondaryCurrency = true },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Add Secondary Currency",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    if (isAddingSecondaryCurrency) {
        AddSecondaryCurrencyDialog(
            primaryCurrencyCode = prefs.primaryCurrencyCode,
            existingCodes = prefs.secondaryCurrencies.map { it.code.uppercase() }.toSet(),
            onSave = onAddOrUpdateSecondaryCurrency,
            onDismiss = { isAddingSecondaryCurrency = false },
        )
    }

    editingSecondaryCurrency?.let { sec ->
        EditSecondaryCurrencyRateDialog(
            primaryCurrencyCode = prefs.primaryCurrencyCode,
            secondaryCurrency = sec,
            onSave = { rate -> onAddOrUpdateSecondaryCurrency(sec.code, rate) },
            onDismiss = { editingCurrencyCode = null },
        )
    }

    currencyPendingDeleteCode?.let { deleteCode ->
        val affectedAccountsCount = remember(deleteCode, allAccounts) {
            allAccounts.count { it.currencyCode.equals(deleteCode, ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = { currencyPendingDeleteCode = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Remove $deleteCode?") },
            text = {
                Text(
                    if (affectedAccountsCount > 0) {
                        val acctWord = if (affectedAccountsCount == 1) "1 account uses" else "$affectedAccountsCount accounts use"
                        "$acctWord $deleteCode. Removing this rate will convert those balances 1:1 with ${prefs.primaryCurrencyCode} in Net Worth & Analytics until a rate is re-added."
                    } else {
                        "Remove $deleteCode from your configured secondary currencies?"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveSecondaryCurrency(deleteCode)
                        currencyPendingDeleteCode = null
                    },
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { currencyPendingDeleteCode = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * Dialog to pick any currency from the complete 170+ world currencies list
 * and define its manual conversion rate.
 */
@Composable
private fun AddSecondaryCurrencyDialog(
    primaryCurrencyCode: String,
    existingCodes: Set<String>,
    onSave: (code: String, rate: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCurrencyCode by rememberSaveable { mutableStateOf<String?>(null) }
    var rateInput by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var isBrowseAllExpanded by rememberSaveable { mutableStateOf(false) }

    val allCurrencies = remember { SupportedCurrencies.ALL }
    val availableCurrencies = remember(primaryCurrencyCode, existingCodes) {
        allCurrencies.filter {
            !it.code.equals(primaryCurrencyCode, ignoreCase = true) &&
                it.code.uppercase() !in existingCodes
        }
    }

    val popularCurrencies = remember(availableCurrencies) {
        val popularCodes = setOf("USD", "EUR", "GBP", "AED", "SGD", "CAD", "AUD", "JPY", "SAR", "CHF", "QAR")
        availableCurrencies.filter { it.code in popularCodes }
    }

    val filteredCurrencies = remember(availableCurrencies, popularCurrencies, searchQuery, isBrowseAllExpanded) {
        if (searchQuery.isNotBlank()) {
            availableCurrencies.filter {
                it.code.contains(searchQuery, ignoreCase = true) ||
                    it.displayName.contains(searchQuery, ignoreCase = true) ||
                    it.symbol.contains(searchQuery, ignoreCase = true)
            }
        } else if (isBrowseAllExpanded) {
            availableCurrencies
        } else {
            popularCurrencies
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Add Secondary Currency",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Select any currency and enter its manual exchange rate relative to $primaryCurrencyCode.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search 170+ world currencies") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear search",
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Currency List or Selector
                Text(
                    text = if (searchQuery.isNotBlank()) "SEARCH RESULTS" else "SELECT CURRENCY",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )

                if (filteredCurrencies.isEmpty()) {
                    Text(
                        text = "No currencies available matching '$searchQuery'",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    if (isBrowseAllExpanded || searchQuery.isNotBlank()) {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp),
                        ) {
                            items(filteredCurrencies, key = { it.code }) { spec ->
                                val isSelected = selectedCurrencyCode.equals(spec.code, ignoreCase = true)
                                Surface(
                                    onClick = {
                                        selectedCurrencyCode = spec.code
                                        errorMessage = null
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                    border = BorderStroke(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    ) {
                                        Text(
                                            text = "${spec.symbol}  ${spec.code} — ${spec.displayName}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            filteredCurrencies.forEach { spec ->
                                val isSelected = selectedCurrencyCode.equals(spec.code, ignoreCase = true)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        selectedCurrencyCode = spec.code
                                        errorMessage = null
                                    },
                                    label = { Text("${spec.symbol} ${spec.code}") },
                                    leadingIcon = if (isSelected) {
                                        { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp)) }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }

                    if (searchQuery.isBlank() && !isBrowseAllExpanded) {
                        TextButton(
                            onClick = { isBrowseAllExpanded = true },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) {
                            Text("Browse All ${availableCurrencies.size} Currencies ⌄")
                        }
                    }
                }

                // Exchange Rate Input (Shown once a currency is selected)
                selectedCurrencyCode?.let { code ->
                    val spec = SupportedCurrencies.fromCode(code)
                    HorizontalDivider()

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "EXCHANGE RATE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        OutlinedTextField(
                            value = rateInput,
                            onValueChange = {
                                rateInput = it
                                errorMessage = null
                            },
                            label = { Text("1 $code = ? $primaryCurrencyCode") },
                            placeholder = { Text("e.g. 85.00") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            isError = errorMessage != null,
                            supportingText = errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        val parsedRate = parseExchangeRateInput(rateInput)
                        if (parsedRate != null) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = "Preview: 1 ${spec.displayName} (${spec.symbol}) = ${formatExchangeRatePlain(parsedRate)} $primaryCurrencyCode",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val code = selectedCurrencyCode
                    if (code == null) {
                        errorMessage = "Please select a currency"
                        return@Button
                    }
                    val rate = parseExchangeRateInput(rateInput)
                    if (rate == null) {
                        errorMessage = "Enter a valid rate between 0.000001 and 1,000,000,000"
                        return@Button
                    }
                    onSave(code, rate)
                    onDismiss()
                },
                enabled = selectedCurrencyCode != null,
            ) {
                Text("Add Currency")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Dialog to edit the exchange rate of an already added secondary currency.
 */
@Composable
private fun EditSecondaryCurrencyRateDialog(
    primaryCurrencyCode: String,
    secondaryCurrency: SecondaryCurrency,
    onSave: (rate: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var rateInput by rememberSaveable(secondaryCurrency.code) {
        mutableStateOf(formatExchangeRatePlain(secondaryCurrency.exchangeRateToPrimary))
    }
    var errorMessage by rememberSaveable(secondaryCurrency.code) { mutableStateOf<String?>(null) }
    val spec = SupportedCurrencies.fromCode(secondaryCurrency.code)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Edit Rate: ${spec.code}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Update the conversion rate for ${spec.displayName} (${spec.symbol}) relative to $primaryCurrencyCode.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = rateInput,
                    onValueChange = {
                        rateInput = it
                        errorMessage = null
                    },
                    label = { Text("1 ${secondaryCurrency.code} = ? $primaryCurrencyCode") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    modifier = Modifier.fillMaxWidth(),
                )

                val parsedRate = parseExchangeRateInput(rateInput)
                if (parsedRate != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "New rate: 1 ${spec.code} = ${formatExchangeRatePlain(parsedRate)} $primaryCurrencyCode",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val rate = parseExchangeRateInput(rateInput)
                    if (rate == null) {
                        errorMessage = "Enter a valid rate between 0.000001 and 1,000,000,000"
                        return@Button
                    }
                    onSave(rate)
                    onDismiss()
                },
            ) {
                Text("Save Rate")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
