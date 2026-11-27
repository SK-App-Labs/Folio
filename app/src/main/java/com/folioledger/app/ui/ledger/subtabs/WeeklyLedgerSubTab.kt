/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.ledger.subtabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folioledger.app.R
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.ui.components.MoneyColorMode
import com.folioledger.app.ui.components.MoneyText
import com.folioledger.app.ui.components.ThreeColumnSummaryBanner
import com.folioledger.app.ui.ledger.*
import com.folioledger.app.ui.theme.LocalFolioSemanticColors
import com.folioledger.app.ui.theme.tabularMoneyStyle

@Composable
internal fun WeeklyLedgerSubTabContent(
    uiState: LedgerHomeUiState,
    onToggleWeek: (Int) -> Unit,
    onTransactionClick: (TransactionItem) -> Unit
) {
    val semanticColors = LocalFolioSemanticColors.current

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "weekly_summary_banner") {
            ThreeColumnSummaryBanner(
                periodSummary = uiState.periodSummary,
                currencySpec = uiState.currencySpec,
                grouping = uiState.numberGrouping,
                showCarryOver = uiState.userPreferences.carryOverEnabled && uiState.scopedAccountId == null
            )
        }

        items(
            items = uiState.weeklyBuckets,
            key = { "${it.startEpochDay}_${it.weekIndex}" },
            contentType = { "weekly_bucket_card" }
        ) { bucket ->
            val isExpanded = bucket.weekIndex in uiState.expandedWeekIndices

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleWeek(bucket.weekIndex) }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (isExpanded) {
                                    Icons.Filled.ExpandLess
                                } else {
                                    Icons.Filled.ExpandMore
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "${bucket.label} · ${bucket.dateRangeSubtitle}",
                                    style = tabularMoneyStyle(MaterialTheme.typography.titleSmall),
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${bucket.transactions.size} transactions",
                                    style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Column(horizontalAlignment = Alignment.End) {
                            Row(horizontalArrangement = Arrangement.spacedBy(if (uiState.currencySpec.symbol.length > 2) 6.dp else 10.dp)) {
                                MoneyText(
                                    money = bucket.incomeTotal,
                                    currencySpec = uiState.currencySpec,
                                    grouping = uiState.numberGrouping,
                                    colorMode = MoneyColorMode.INCOME,
                                    style = if (uiState.currencySpec.symbol.length > 2) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    showPlusSignForPositive = true
                                )
                                MoneyText(
                                    money = bucket.expenseTotal,
                                    currencySpec = uiState.currencySpec,
                                    grouping = uiState.numberGrouping,
                                    colorMode = MoneyColorMode.EXPENSE,
                                    style = if (uiState.currencySpec.symbol.length > 2) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            MoneyText(
                                money = bucket.netTotal,
                                currencySpec = uiState.currencySpec,
                                grouping = uiState.numberGrouping,
                                colorMode = MoneyColorMode.AUTO_SIGN,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                showPlusSignForPositive = true
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = isExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            HorizontalDivider(
                                color = semanticColors.cardStrokeSubtle,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            if (bucket.transactions.isNotEmpty()) {
                                bucket.transactions.forEachIndexed { idx, tx ->
                                    key(tx.id) {
                                        if (idx > 0) {
                                            HorizontalDivider(
                                                color = semanticColors.cardStrokeSubtle.copy(alpha = 0.5f),
                                                modifier = Modifier.padding(horizontal = 4.dp)
                                            )
                                        }
                                        LedgerTransactionRow(
                                            transaction = tx,
                                            scopedAccountId = uiState.scopedAccountId,
                                            currencySpec = uiState.currencySpec,
                                            grouping = uiState.numberGrouping,
                                            showDate = true,
                                            onTransactionClick = onTransactionClick
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = stringResource(R.string.ledger_no_tx_for_week),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

