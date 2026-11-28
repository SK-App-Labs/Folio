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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.ui.components.EmptyStateCard
import com.folioledger.app.ui.components.MoneyColorMode
import com.folioledger.app.ui.components.ThreeColumnSummaryBanner
import com.folioledger.app.ui.ledger.*
import com.folioledger.app.ui.theme.tabularMoneyStyle

@Composable
internal fun FilteredSearchResultsContent(
    uiState: LedgerHomeUiState,
    onTransactionClick: (TransactionItem) -> Unit,
    onToggleFilterAllPeriods: (Boolean) -> Unit,
    onClearFilters: () -> Unit,
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
            bottom = 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (uiState.filteredTransactions.isNotEmpty()) {
            val hasIncome = uiState.filteredIncomeTotal.isPositive
            val hasExpense = uiState.filteredExpenseTotal.isPositive
            val hasTransfer = uiState.filteredTransferTotal.isPositive

            if (hasIncome || hasExpense || hasTransfer) {
                item(key = "filtered_summary_banner") {
                    when {
                        uiState.isFilteredRefundOnly && hasIncome -> {
                            SingleTotalSummaryBanner(
                                label = "Total Filtered Refunds",
                                amount = uiState.filteredIncomeTotal,
                                colorMode = MoneyColorMode.INCOME,
                                currencySpec = uiState.currencySpec,
                                grouping = uiState.numberGrouping,
                            )
                        }

                        hasIncome && hasExpense -> {
                            androidx.compose.foundation.layout.Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ThreeColumnSummaryBanner(
                                    firstLabel = "Filtered Income",
                                    firstAmount = uiState.filteredIncomeTotal,
                                    secondLabel = "Filtered Expenses",
                                    secondAmount = uiState.filteredExpenseTotal,
                                    thirdLabel = "Filtered Net",
                                    thirdAmount = uiState.filteredNetTotal,
                                    currencySpec = uiState.currencySpec,
                                    grouping = uiState.numberGrouping,
                                )
                                if (hasTransfer) {
                                    SingleTotalSummaryBanner(
                                        label = "Filtered Transfers",
                                        amount = uiState.filteredTransferTotal,
                                        colorMode = MoneyColorMode.NEUTRAL,
                                        currencySpec = uiState.currencySpec,
                                        grouping = uiState.numberGrouping,
                                        prefix = "⇄ ",
                                    )
                                }
                            }
                        }

                        hasExpense && !hasIncome && !hasTransfer -> {
                            SingleTotalSummaryBanner(
                                label = "Total Filtered Expenses",
                                amount = uiState.filteredExpenseTotal,
                                colorMode = MoneyColorMode.EXPENSE,
                                currencySpec = uiState.currencySpec,
                                grouping = uiState.numberGrouping,
                            )
                        }

                        hasIncome && !hasExpense && !hasTransfer -> {
                            SingleTotalSummaryBanner(
                                label = "Total Filtered Income",
                                amount = uiState.filteredIncomeTotal,
                                colorMode = MoneyColorMode.INCOME,
                                currencySpec = uiState.currencySpec,
                                grouping = uiState.numberGrouping,
                            )
                        }

                        hasTransfer && !hasIncome && !hasExpense -> {
                            SingleTotalSummaryBanner(
                                label = "Total Filtered Transfers",
                                amount = uiState.filteredTransferTotal,
                                colorMode = MoneyColorMode.NEUTRAL,
                                currencySpec = uiState.currencySpec,
                                grouping = uiState.numberGrouping,
                                prefix = "⇄ ",
                            )
                        }

                        else -> {
                            val showTransferFirst = hasTransfer && !hasIncome
                            ThreeColumnSummaryBanner(
                                firstLabel = if (showTransferFirst) "Transfers" else "Filtered Income",
                                firstAmount = if (showTransferFirst) uiState.filteredTransferTotal else uiState.filteredIncomeTotal,
                                secondLabel = "Filtered Expenses",
                                secondAmount = uiState.filteredExpenseTotal,
                                thirdLabel = "Filtered Net",
                                thirdAmount = uiState.filteredNetTotal,
                                currencySpec = uiState.currencySpec,
                                grouping = uiState.numberGrouping,
                                firstColorMode = if (showTransferFirst) MoneyColorMode.TRANSFER else MoneyColorMode.INCOME,
                            )
                        }
                    }
                }
            }
        }

        item(key = "filtered_matches_count_row") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "${uiState.filteredTransactions.size} matches",
                    style = tabularMoneyStyle(MaterialTheme.typography.labelLarge),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                FilterChip(
                    selected = !uiState.filterAllPeriods,
                    onClick = { onToggleFilterAllPeriods(!uiState.filterAllPeriods) },
                    label = {
                        Text(
                            text = if (uiState.filterAllPeriods) {
                                "All Dates"
                            } else {
                                "In ${uiState.activePeriodRange.title}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }
        }

        if (uiState.filteredDailyGroups.isEmpty()) {
            item(key = "filtered_empty_state") {
                EmptyStateCard(
                    title = if (uiState.filterAllPeriods) {
                        "No Matching Transactions"
                    } else {
                        "No Matches in ${uiState.activePeriodRange.title}"
                    },
                    subtitle = if (uiState.filterAllPeriods) {
                        "No ledger entries matched your search query and multi-filter criteria."
                    } else {
                        "Use < Prev / Next > above to check other months, or tap 'In ${uiState.activePeriodRange.title}' to search across all dates."
                    },
                    icon = Icons.Filled.Search,
                    actionLabel = "Clear All Filters",
                    onActionClick = onClearFilters,
                )
            }
        } else {
            uiState.filteredDailyGroups.forEach { group ->
                val headerKey = "filtered_day_${group.epochDay}"
                stickyHeader(key = headerKey, contentType = "day_group_header") {
                    DailyDateGroupHeaderRow(
                        group = group,
                        currencySpec = uiState.currencySpec,
                        grouping = uiState.numberGrouping,
                    )
                }
                item(key = "filtered_day_card_${group.epochDay}", contentType = "day_group_card") {
                    DailyGroupedTransactionsCard(
                        group = group,
                        scopedAccountId = uiState.scopedAccountId,
                        currencySpec = uiState.currencySpec,
                        grouping = uiState.numberGrouping,
                        onTransactionClick = onTransactionClick,
                    )
                }
            }
        }
    }
}
