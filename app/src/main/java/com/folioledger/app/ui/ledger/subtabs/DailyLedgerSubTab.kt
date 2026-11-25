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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.ui.components.EmptyStateCard
import com.folioledger.app.ui.components.ThreeColumnSummaryBanner
import com.folioledger.app.ui.ledger.*

@Composable
internal fun DailyLedgerSubTabContent(
    uiState: LedgerHomeUiState,
    onTransactionClick: (TransactionItem) -> Unit,
    onAddTransactionClick: () -> Unit,
    onExploreDemoClick: () -> Unit = {}
) {
    val lazyListState = rememberLazyListState()

    androidx.compose.runtime.LaunchedEffect(uiState.scrollToTopSignal) {
        if (uiState.scrollToTopSignal > 0) {
            lazyListState.animateScrollToItem(0)
        }
    }

    LazyColumn(
        state = lazyListState,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = 32.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "daily_summary_banner") {
            ThreeColumnSummaryBanner(
                periodSummary = uiState.periodSummary,
                currencySpec = uiState.currencySpec,
                grouping = uiState.numberGrouping,
                showCarryOver = uiState.userPreferences.carryOverEnabled && uiState.scopedAccountId == null
            )
        }

        if (uiState.dailyGroups.isEmpty()) {
            item(key = "daily_empty_state") {
                val scopedName = uiState.scopedAccount?.name
                EmptyStateCard(
                    title = if (scopedName != null) {
                        "No Transactions for $scopedName in ${uiState.activePeriodRange.title}"
                    } else {
                        "No Transactions in ${uiState.activePeriodRange.title}"
                    },
                    subtitle = "Record an expense, income, or transfer to populate your daily chronological ledger.",
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                    actionLabel = "+ Add Transaction",
                    onActionClick = onAddTransactionClick
                )

                if (!uiState.userPreferences.isDemoModeActive && uiState.scopedAccountId == null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onExploreDemoClick,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Preview App with Live Demo Data",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        } else {
            uiState.dailyGroups.forEach { group ->
                val headerKey = "day_header_${group.epochDay}"
                stickyHeader(key = headerKey, contentType = "day_group_header") {
                    DailyDateGroupHeaderRow(
                        group = group,
                        currencySpec = uiState.currencySpec,
                        grouping = uiState.numberGrouping
                    )
                }
                item(key = "day_card_${group.epochDay}", contentType = "day_group_card") {
                    DailyGroupedTransactionsCard(
                        group = group,
                        scopedAccountId = uiState.scopedAccountId,
                        currencySpec = uiState.currencySpec,
                        grouping = uiState.numberGrouping,
                        onTransactionClick = onTransactionClick
                    )
                }
            }
        }
    }
}
