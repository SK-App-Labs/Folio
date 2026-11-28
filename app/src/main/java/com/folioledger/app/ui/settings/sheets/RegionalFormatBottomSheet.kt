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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folioledger.app.R
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import com.folioledger.app.ui.theme.LocalFolioSemanticColors
import com.folioledger.app.ui.theme.tabularMoneyStyle
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

typealias NumberGroupingStyle = NumberGroupingSystem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionalFormatBottomSheet(
    prefs: UserPreferences,
    allAccounts: List<AccountSummary> = emptyList(),
    onSelectCurrency: (String) -> Unit,
    onSelectGroupingStyle: (NumberGroupingStyle) -> Unit,
    onToggleHidePrimaryCurrencySymbol: (Boolean) -> Unit,
    onSelectWeekStartDayIso: (Int) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val activeCurrencySpec = SupportedCurrencies.fromCode(prefs.primaryCurrencyCode)
    val semanticColors = LocalFolioSemanticColors.current

    var searchCurrencyQuery by rememberSaveable { mutableStateOf("") }
    var isBrowseAllExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingConfirmCurrencyCode by rememberSaveable { mutableStateOf<String?>(null) }

    val allCurrencies = remember { SupportedCurrencies.ALL }
    val popularCurrencies = remember { SupportedCurrencies.POPULAR }

    val previewMinorAmount = remember(activeCurrencySpec) {
        BigDecimal("1234567.89")
            .movePointRight(activeCurrencySpec.fractionDigits)
            .toLong()
    }

    val handleSelectCurrency = { code: String ->
        if (code.equals(prefs.primaryCurrencyCode, ignoreCase = true)) {
            onSelectCurrency(code)
        } else {
            val inSecondary = prefs.secondaryCurrencies.any {
                it.code.equals(code, ignoreCase = true)
            }
            val hasAccountsInCurrentPrimary = allAccounts.any {
                it.currencyCode.equals(prefs.primaryCurrencyCode, ignoreCase = true)
            }
            val needsConfirm = !inSecondary &&
                (prefs.secondaryCurrencies.isNotEmpty() || hasAccountsInCurrentPrimary)
            if (needsConfirm) {
                pendingConfirmCurrencyCode = code
            } else {
                onSelectCurrency(code)
            }
        }
    }

    val filteredCurrencies = remember(allCurrencies, popularCurrencies, searchCurrencyQuery, isBrowseAllExpanded) {
        if (searchCurrencyQuery.isNotBlank()) {
            allCurrencies.filter { currency ->
                currency.code.contains(searchCurrencyQuery, ignoreCase = true) ||
                    currency.displayName.contains(searchCurrencyQuery, ignoreCase = true) ||
                    currency.symbol.contains(searchCurrencyQuery, ignoreCase = true)
            }
        } else if (isBrowseAllExpanded) {
            allCurrencies
        } else {
            popularCurrencies
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "header_preview") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "Regional & Formatting",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )

                    // Live Formatted Preview Card
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "LIVE NUMBER FORMAT PREVIEW",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = MoneyFormatterAndCalculator.formatMinorMoney(
                                    money = MinorMoney(previewMinorAmount),
                                    currencySpec = activeCurrencySpec,
                                    grouping = prefs.numberGrouping,
                                    includeSymbol = !prefs.hidePrimaryCurrencySymbol,
                                ),
                                style = tabularMoneyStyle(MaterialTheme.typography.headlineMedium),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            val groupingLabel = if (prefs.numberGrouping == NumberGroupingSystem.INDIAN) "Indian" else "Standard"
                            val symbolCue = if (prefs.hidePrimaryCurrencySymbol) {
                                " \u2022 ${stringResource(R.string.setting_currency_symbol_hidden_cue)}"
                            } else {
                                ""
                            }
                            Text(
                                text = "${activeCurrencySpec.displayName} ($groupingLabel Grouping)$symbolCue",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }

            item(key = "grouping") {
                val indianSample = remember(activeCurrencySpec, previewMinorAmount) {
                    MoneyFormatterAndCalculator.formatMinorMoney(
                        money = MinorMoney(previewMinorAmount),
                        currencySpec = activeCurrencySpec,
                        grouping = NumberGroupingSystem.INDIAN,
                        includeSymbol = false,
                    )
                }
                val standardSample = remember(activeCurrencySpec, previewMinorAmount) {
                    MoneyFormatterAndCalculator.formatMinorMoney(
                        money = MinorMoney(previewMinorAmount),
                        currencySpec = activeCurrencySpec,
                        grouping = NumberGroupingSystem.INTERNATIONAL,
                        includeSymbol = false,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "NUMBER GROUPING STYLE",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                    ) {
                        val isIndian = prefs.numberGrouping == NumberGroupingSystem.INDIAN
                        val isStandard = prefs.numberGrouping == NumberGroupingSystem.INTERNATIONAL

                        ToggleButton(
                            checked = isIndian,
                            onCheckedChange = { checked ->
                                if (checked && !isIndian) onSelectGroupingStyle(NumberGroupingSystem.INDIAN)
                            },
                            shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                            colors = ToggleButtonDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (isIndian) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .semantics { role = Role.RadioButton },
                        ) {
                            if (isIndian) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.size(ToggleButtonDefaults.IconSpacing))
                            }
                            Text(
                                text = "Indian ($indianSample)",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        ToggleButton(
                            checked = isStandard,
                            onCheckedChange = { checked ->
                                if (checked && !isStandard) onSelectGroupingStyle(NumberGroupingSystem.INTERNATIONAL)
                            },
                            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                            colors = ToggleButtonDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (isStandard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .semantics { role = Role.RadioButton },
                        ) {
                            if (isStandard) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.size(ToggleButtonDefaults.IconSpacing))
                            }
                            Text(
                                text = "Standard ($standardSample)",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            item(key = "hide_currency_symbol") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .toggleable(
                            value = prefs.hidePrimaryCurrencySymbol,
                            role = Role.Switch,
                            onValueChange = onToggleHidePrimaryCurrencySymbol,
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.setting_hide_primary_currency_symbol),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(
                                    R.string.setting_hide_primary_currency_symbol_desc,
                                    activeCurrencySpec.code,
                                    activeCurrencySpec.symbol,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = prefs.hidePrimaryCurrencySymbol,
                            onCheckedChange = null,
                        )
                    }
                }
            }

            item(key = "week_start") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "FIRST DAY OF WEEK",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(DayOfWeek.entries, key = { it.value }) { day ->
                            val isSelected = prefs.weekStartDayIso == day.value
                            val label = day.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSelectWeekStartDayIso(day.value) },
                                label = { Text(label) },
                                leadingIcon = if (isSelected) {
                                    { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp)) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                }
            }

            item(key = "currency_search") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "PRIMARY LEDGER CURRENCY",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    // Search Currency Field
                    OutlinedTextField(
                        value = searchCurrencyQuery,
                        onValueChange = { searchCurrencyQuery = it },
                        placeholder = { Text("Search currency") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingIcon = {
                            if (searchCurrencyQuery.isNotBlank()) {
                                IconButton(onClick = { searchCurrencyQuery = "" }) {
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

                    if (popularCurrencies.isNotEmpty() && searchCurrencyQuery.isBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "POPULAR CURRENCIES",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(popularCurrencies, key = { it.code }) { currency ->
                                    val isSelected = prefs.primaryCurrencyCode.equals(currency.code, ignoreCase = true)
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { handleSelectCurrency(currency.code) },
                                        label = { Text("${currency.code} (${currency.symbol})") },
                                        leadingIcon = if (isSelected) {
                                            { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp)) }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (filteredCurrencies.isEmpty()) {
                item(key = "empty_currencies") {
                    Text(
                        text = "No currency found matching \"$searchCurrencyQuery\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            } else {
                items(filteredCurrencies, key = { it.code }) { currency ->
                    val isSelected = prefs.primaryCurrencyCode.equals(currency.code, ignoreCase = true)
                    Card(
                        onClick = { handleSelectCurrency(currency.code) },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        ),
                        border = BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else semanticColors.cardStrokeSubtle,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                role = Role.RadioButton
                                selected = isSelected
                            },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                                    modifier = Modifier.size(38.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = currency.symbol,
                                            style = tabularMoneyStyle(MaterialTheme.typography.titleMedium),
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "${currency.displayName} (${currency.code})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }

                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                }

                if (searchCurrencyQuery.isBlank()) {
                    item(key = "browse_all_toggle") {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { isBrowseAllExpanded = !isBrowseAllExpanded }
                                .padding(vertical = 12.dp, horizontal = 14.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = if (isBrowseAllExpanded) {
                                        "Show Top Currencies Only"
                                    } else {
                                        "Browse All ${allCurrencies.size} World Currencies ⌄"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingConfirmCurrencyCode?.let { targetCode ->
        val targetSpec = SupportedCurrencies.fromCode(targetCode)
        AlertDialog(
            onDismissRequest = { pendingConfirmCurrencyCode = null },
            title = { Text("Change Primary Currency?") },
            text = {
                val message = if (prefs.secondaryCurrencies.isNotEmpty()) {
                    "Switch primary reporting currency from ${prefs.primaryCurrencyCode} to ${targetSpec.code} (${targetSpec.displayName})? " +
                        "Existing secondary exchange rates (configured against ${prefs.primaryCurrencyCode}) will be cleared and must be re-entered relative to ${targetSpec.code}."
                } else {
                    "Switch primary reporting currency from ${prefs.primaryCurrencyCode} to ${targetSpec.code} (${targetSpec.displayName})? " +
                        "Existing account balances stay in their own currencies; configure an exchange rate in Secondary Currencies if needed."
                }
                Text(message)
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSelectCurrency(targetCode)
                        pendingConfirmCurrencyCode = null
                    },
                ) {
                    Text("Switch to ${targetSpec.code}")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmCurrencyCode = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}
