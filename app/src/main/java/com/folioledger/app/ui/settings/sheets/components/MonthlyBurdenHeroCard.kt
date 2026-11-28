/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.settings.sheets.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.ui.components.MoneyColorMode
import com.folioledger.app.ui.components.MoneyText
import com.folioledger.app.ui.settings.SettingsUiState
import com.folioledger.app.ui.theme.LocalFolioSemanticColors
import com.folioledger.app.ui.theme.tabularMoneyStyle

@Composable
fun MonthlyBurdenHeroCard(
    uiState: SettingsUiState,
    modifier: Modifier = Modifier,
) {
    val semanticColors = LocalFolioSemanticColors.current

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "TOTAL MONTHLY FIXED COMMITMENT",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                MoneyText(
                    money = uiState.totalMonthlyFixedBurden,
                    currencySpec = uiState.currencySpec,
                    grouping = uiState.userPreferences.numberGrouping,
                    style = tabularMoneyStyle(MaterialTheme.typography.headlineMedium),
                    colorMode = MoneyColorMode.NEUTRAL,
                )
                Text(
                    text = "/ month",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            HorizontalDivider(
                color = semanticColors.cardStrokeSubtle,
                modifier = Modifier.padding(vertical = 4.dp),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Subscriptions Breakdown
                val activeSubscriptionsCount = uiState.recurringRules.count { it.isActive && it.type == TransactionType.EXPENSE }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Subscriptions ($activeSubscriptionsCount active)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    MoneyText(
                        money = uiState.activeMonthlyRecurringBurden,
                        currencySpec = uiState.currencySpec,
                        grouping = uiState.userPreferences.numberGrouping,
                        style = tabularMoneyStyle(MaterialTheme.typography.bodyMedium),
                        colorMode = MoneyColorMode.NEUTRAL,
                    )
                }

                // EMIs Breakdown
                val activePlansCount = uiState.installmentPlans.count { it.remainingInstallmentsCount > 0 }
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "Credit Card EMIs ($activePlansCount active)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    MoneyText(
                        money = uiState.activeMonthlyInstallmentsBurden,
                        currencySpec = uiState.currencySpec,
                        grouping = uiState.userPreferences.numberGrouping,
                        style = tabularMoneyStyle(MaterialTheme.typography.bodyMedium),
                        colorMode = MoneyColorMode.NEUTRAL,
                    )
                }
            }

            // Auto-Savings / Transfers Breakdown (shown separately since transfers are excluded from fixed expense total)
            val activeTransfersCount = uiState.recurringRules.count { it.isActive && it.type == TransactionType.TRANSFER }
            if (activeTransfersCount > 0) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                ) {
                    Text(
                        text = "Auto-Save Transfers ($activeTransfersCount active · not in expense total)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    MoneyText(
                        money = uiState.activeMonthlyTransfersBurden,
                        currencySpec = uiState.currencySpec,
                        grouping = uiState.userPreferences.numberGrouping,
                        style = tabularMoneyStyle(MaterialTheme.typography.bodySmall),
                        colorMode = MoneyColorMode.NEUTRAL,
                    )
                }
            }
        }
    }
}
