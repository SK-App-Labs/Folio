/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.stats.subtabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.folioledger.app.R
import com.folioledger.app.domain.model.CategorySpendStat
import com.folioledger.app.domain.model.CategoryType
import com.folioledger.app.ui.components.EmptyStateCard
import com.folioledger.app.ui.components.InteractiveCategoryDonutChart
import com.folioledger.app.ui.components.MoneyColorMode
import com.folioledger.app.ui.components.NetWorthGrowthCanvasChart
import com.folioledger.app.ui.components.SixMonthTrendBarChart
import com.folioledger.app.ui.components.ThreeColumnSummaryBanner
import com.folioledger.app.ui.stats.NetWorthChartMetricFilter
import com.folioledger.app.ui.stats.OverviewMode
import com.folioledger.app.ui.stats.StatsAndBudgetsUiState
import com.folioledger.app.ui.stats.StatsPeriodGranularity
import com.folioledger.app.ui.stats.components.CashflowPerformanceInsightCard
import com.folioledger.app.ui.stats.components.EmergencyRunwayCard
import com.folioledger.app.ui.stats.components.NetWorthVelocityCard
import com.folioledger.app.ui.stats.components.RankedCategoryBreakdownGroupedCard
import com.folioledger.app.ui.stats.components.TopDriversSummaryCard
import com.folioledger.app.ui.theme.LocalFolioSemanticColors
import com.folioledger.app.ui.theme.tabularMoneyStyle

@Composable
internal fun StatsBreakdownSubTabContent(
    uiState: StatsAndBudgetsUiState,
    onSelectOverviewMode: (OverviewMode) -> Unit,
    onSelectCategoryType: (CategoryType) -> Unit,
    onDonutSliceSelected: (CategorySpendStat?) -> Unit,
    onOpenCategoryDrillDown: (CategorySpendStat, CategoryType) -> Unit,
    onSelectNetWorthChartMetricFilter: (NetWorthChartMetricFilter) -> Unit,
    onToggleRunwayIncludesInvestments: () -> Unit
) {
    val semanticColors = LocalFolioSemanticColors.current
    val activeStats = uiState.activeCategoryStats

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = 32.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // 1. Period Headline Summary Banner
        item(key = "stats_summary_banner", contentType = "stats_summary_banner") {
            ThreeColumnSummaryBanner(
                firstLabel = stringResource(R.string.ledger_banner_income),
                firstAmount = uiState.periodSummary.totalIncome,
                secondLabel = stringResource(R.string.ledger_banner_expense),
                secondAmount = uiState.periodSummary.totalExpense,
                thirdLabel = stringResource(R.string.ledger_banner_net),
                thirdAmount = uiState.periodSummary.netCashflow,
                currencySpec = uiState.currencySpec,
                grouping = uiState.numberGrouping,
                carryOverAmount = if (
                    uiState.userPreferences.carryOverEnabled &&
                    uiState.periodGranularity == StatsPeriodGranularity.MONTHLY
                ) {
                    uiState.periodSummary.carryOverFromPreviousPeriod
                } else {
                    null
                }
            )
        }

        // 2. 3-Mode Segmented Hero Switcher: [ Categories ] | [ Cashflow ] | [ Net Worth ]
        item(key = "stats_overview_mode_switcher", contentType = "stats_overview_mode_switcher") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
            ) {
                OverviewMode.entries.forEach { mode ->
                    val isSelected = uiState.selectedOverviewMode == mode
                    val icon = when (mode) {
                        OverviewMode.CATEGORIES -> Icons.Filled.PieChart
                        OverviewMode.CASHFLOW -> Icons.AutoMirrored.Filled.ShowChart
                        OverviewMode.NET_WORTH -> Icons.AutoMirrored.Filled.TrendingUp
                    }
                    val containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    }
                    val contentColor = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val borderColor = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        semanticColors.cardStrokeSubtle
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = containerColor,
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(
                                selected = isSelected,
                                role = Role.Tab,
                                onClick = { onSelectOverviewMode(mode) }
                            )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp)
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = contentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = when (mode) {
                                    OverviewMode.CATEGORIES -> stringResource(R.string.stats_overview_mode_categories)
                                    OverviewMode.CASHFLOW -> stringResource(R.string.stats_overview_mode_cashflow)
                                    OverviewMode.NET_WORTH -> stringResource(R.string.stats_overview_mode_net_worth)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = contentColor,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // 3. Dynamic Mode-Matched Content
        when (uiState.selectedOverviewMode) {
            OverviewMode.CATEGORIES -> {
                // Segmented Expenses vs Income Toggle
                item(key = "stats_category_type_toggle", contentType = "stats_category_type_toggle") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup()
                    ) {
                        val isExpense = uiState.selectedCategoryType == CategoryType.EXPENSE
                        val isIncome = uiState.selectedCategoryType == CategoryType.INCOME

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isExpense) {
                                semanticColors.expenseCoralContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isExpense) {
                                    semanticColors.expenseCoral
                                } else {
                                    semanticColors.cardStrokeSubtle
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .selectable(
                                    selected = isExpense,
                                    role = Role.Tab,
                                    onClick = { onSelectCategoryType(CategoryType.EXPENSE) }
                                )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.TrendingDown,
                                    contentDescription = null,
                                    tint = if (isExpense) {
                                        semanticColors.onExpenseCoralContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.ledger_banner_expense),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (isExpense) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isExpense) {
                                        semanticColors.onExpenseCoralContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isIncome) {
                                semanticColors.incomeEmeraldContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isIncome) {
                                    semanticColors.incomeEmerald
                                } else {
                                    semanticColors.cardStrokeSubtle
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .selectable(
                                    selected = isIncome,
                                    role = Role.Tab,
                                    onClick = { onSelectCategoryType(CategoryType.INCOME) }
                                )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                                    contentDescription = null,
                                    tint = if (isIncome) {
                                        semanticColors.onIncomeEmeraldContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.ledger_banner_income),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (isIncome) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isIncome) {
                                        semanticColors.onIncomeEmeraldContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }

                // Interactive Category Donut Chart
                item(key = "stats_donut_chart", contentType = "donut_chart") {
                    InteractiveCategoryDonutChart(
                        stats = activeStats,
                        currencySpec = uiState.currencySpec,
                        grouping = uiState.numberGrouping,
                        categoryType = uiState.selectedCategoryType,
                        selectedCategoryId = uiState.highlightedDonutCategoryId,
                        onCategorySelected = { tappedStat ->
                            onDonutSliceSelected(tappedStat)
                        },
                        centerLabelWhenUnselected = if (uiState.selectedCategoryType == CategoryType.EXPENSE) {
                            "Total Expenses"
                        } else {
                            "Total Income"
                        }
                    )
                }

                // Ranked Category Breakdown List Header
                item(key = "stats_category_list_header", contentType = "category_list_header") {
                    Text(
                        text = if (uiState.selectedCategoryType == CategoryType.EXPENSE) {
                            "Expense Categories (${activeStats.size})"
                        } else {
                            "Income Categories (${activeStats.size})"
                        },
                        style = tabularMoneyStyle(MaterialTheme.typography.titleMedium),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (activeStats.isEmpty()) {
                    item(key = "stats_empty_active_stats", contentType = "empty_state") {
                        EmptyStateCard(
                            title = "No ${uiState.selectedCategoryType.name.lowercase().replaceFirstChar { it.uppercase() }} Data",
                            subtitle = "No transactions recorded for ${uiState.activePeriodRange.title} in this category type.",
                            icon = Icons.Filled.PieChart
                        )
                    }
                } else {
                    item(key = "ranked_categories_grouped_card_${uiState.selectedCategoryType}", contentType = "ranked_categories_card") {
                        RankedCategoryBreakdownGroupedCard(
                            stats = activeStats,
                            categoryType = uiState.selectedCategoryType,
                            highlightedCategoryId = uiState.highlightedDonutCategoryId,
                            currencySpec = uiState.currencySpec,
                            grouping = uiState.numberGrouping,
                            onCategoryClick = { stat -> onOpenCategoryDrillDown(stat, uiState.selectedCategoryType) }
                        )
                    }
                }
            }

            OverviewMode.CASHFLOW -> {
                // Hero: 6-Month Income vs. Expense Trend Bar Chart
                item(key = "cashflow_trend_chart", contentType = "cashflow_trend_chart") {
                    SixMonthTrendBarChart(
                        bars = uiState.sixMonthTrendBars,
                        currencySpec = uiState.currencySpec,
                        grouping = uiState.numberGrouping
                    )
                }

                // Period Cashflow Performance Summary
                item(key = "cashflow_performance_card", contentType = "cashflow_performance_card") {
                    CashflowPerformanceInsightCard(
                        periodSummary = uiState.periodSummary,
                        priorPeriodSummary = uiState.priorPeriodSummary,
                        currencySpec = uiState.currencySpec,
                        grouping = uiState.numberGrouping
                    )
                }

                // Top Spending Drivers
                if (uiState.expenseCategoryStats.isNotEmpty()) {
                    item(key = "top_expense_drivers", contentType = "top_expense_drivers") {
                        TopDriversSummaryCard(
                            title = stringResource(R.string.stats_top_expense_drivers),
                            stats = uiState.expenseCategoryStats.take(3),
                            currencySpec = uiState.currencySpec,
                            grouping = uiState.numberGrouping,
                            colorMode = MoneyColorMode.EXPENSE,
                            onCategoryClick = { stat -> onOpenCategoryDrillDown(stat, CategoryType.EXPENSE) }
                        )
                    }
                }

                // Top Income Sources
                if (uiState.incomeCategoryStats.isNotEmpty()) {
                    item(key = "top_income_sources", contentType = "top_income_sources") {
                        TopDriversSummaryCard(
                            title = stringResource(R.string.stats_top_income_sources),
                            stats = uiState.incomeCategoryStats.take(3),
                            currencySpec = uiState.currencySpec,
                            grouping = uiState.numberGrouping,
                            colorMode = MoneyColorMode.INCOME,
                            onCategoryClick = { stat -> onOpenCategoryDrillDown(stat, CategoryType.INCOME) }
                        )
                    }
                }
            }

            OverviewMode.NET_WORTH -> {
                // Hero: Cubic Bezier Net Worth Growth Trend Canvas Chart
                item(key = "net_worth_trend_chart", contentType = "net_worth_trend_chart") {
                    NetWorthGrowthCanvasChart(
                        trendItems = uiState.sixMonthNetWorthTrend,
                        selectedFilter = uiState.selectedNetWorthMetricFilter,
                        currencySpec = uiState.currencySpec,
                        grouping = uiState.numberGrouping,
                        onSelectFilter = onSelectNetWorthChartMetricFilter
                    )
                }

                // Net Worth Velocity Card
                item(key = "net_worth_velocity", contentType = "net_worth_velocity") {
                    NetWorthVelocityCard(
                        velocity = uiState.netWorthVelocity,
                        currencySpec = uiState.currencySpec,
                        grouping = uiState.numberGrouping
                    )
                }

                // Emergency Runway Card
                item(key = "emergency_runway", contentType = "emergency_runway") {
                    EmergencyRunwayCard(
                        runway = uiState.emergencyRunway,
                        currencySpec = uiState.currencySpec,
                        grouping = uiState.numberGrouping,
                        onToggleIncludeInvestments = onToggleRunwayIncludesInvestments
                    )
                }
            }
        }
    }
}

