/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.stats.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folioledger.app.R
import com.folioledger.app.domain.model.CategorySpendStat
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.EmergencyRunwayState
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NetWorthVelocityState
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.PeriodLedgerSummary
import com.folioledger.app.domain.model.RunwayHealthTier
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import com.folioledger.app.ui.components.CategoryIconBadge
import com.folioledger.app.ui.components.MoneyColorMode
import com.folioledger.app.ui.components.MoneyText
import com.folioledger.app.ui.theme.LocalCurrencyDisplayPolicy
import com.folioledger.app.ui.theme.LocalFolioSemanticColors
import com.folioledger.app.ui.theme.tabularMoneyStyle
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs

@Composable
fun CashflowPerformanceInsightCard(
    periodSummary: PeriodLedgerSummary,
    priorPeriodSummary: PeriodLedgerSummary? = null,
    currencySpec: CurrencySpec,
    grouping: NumberGroupingSystem,
    modifier: Modifier = Modifier,
) {
    val semanticColors = LocalFolioSemanticColors.current
    val totalIncomeMinor = periodSummary.totalIncome.amountMinor
    val totalExpenseMinor = periodSummary.totalExpense.amountMinor
    val netCashflowMinor = periodSummary.netCashflow.amountMinor
    val isPositive = netCashflowMinor >= 0L

    val savingsRatePct = if (totalIncomeMinor > 0L) {
        BigDecimal.valueOf(netCashflowMinor)
            .multiply(BigDecimal.valueOf(100L))
            .divide(BigDecimal.valueOf(totalIncomeMinor), 1, RoundingMode.HALF_UP)
            .toFloat()
            .coerceIn(-100f, 100f)
    } else {
        null
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "CASHFLOW EFFICIENCY",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (savingsRatePct != null) {
                    val rateContainer = if (savingsRatePct >= 0) semanticColors.incomeEmeraldContainer else semanticColors.expenseCoralContainer
                    val rateContent = if (savingsRatePct >= 0) semanticColors.onIncomeEmeraldContainer else semanticColors.onExpenseCoralContainer
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = rateContainer,
                    ) {
                        Text(
                            text = String.format(Locale.US, "Savings Rate: %+.1f%%", savingsRatePct),
                            style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                            fontWeight = FontWeight.Bold,
                            color = rateContent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Text(
                        text = "Net Cashflow",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MoneyText(
                        money = periodSummary.netCashflow,
                        currencySpec = currencySpec,
                        grouping = grouping,
                        colorMode = if (isPositive) MoneyColorMode.INCOME else MoneyColorMode.EXPENSE,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Income vs. Expenses",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MoneyText(
                            money = periodSummary.totalIncome,
                            currencySpec = currencySpec,
                            grouping = grouping,
                            colorMode = MoneyColorMode.INCOME,
                            style = tabularMoneyStyle(MaterialTheme.typography.bodyMedium),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = " / ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MoneyText(
                            money = periodSummary.totalExpense,
                            currencySpec = currencySpec,
                            grouping = grouping,
                            colorMode = MoneyColorMode.EXPENSE,
                            style = tabularMoneyStyle(MaterialTheme.typography.bodyMedium),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            if (priorPeriodSummary != null && priorPeriodSummary.totalExpense.amountMinor > 0L) {
                val priorExpenseMinor = priorPeriodSummary.totalExpense.amountMinor
                val expenseDelta = MoneyFormatterAndCalculator.safeSubtract(totalExpenseMinor, priorExpenseMinor)
                val expenseDeltaPct = BigDecimal.valueOf(expenseDelta)
                    .multiply(BigDecimal.valueOf(100L))
                    .divide(BigDecimal.valueOf(priorExpenseMinor), 1, RoundingMode.HALF_UP)
                    .toFloat()
                val isZero = abs(expenseDeltaPct) < 0.1f
                val isExpenseIncrease = !isZero && expenseDeltaPct > 0f
                val displayPct = if (isZero) 0f else expenseDeltaPct
                val arrow = if (isZero) {
                    "\u2022"
                } else if (isExpenseIncrease) {
                    "▲"
                } else {
                    "▼"
                }
                val sign = if (isExpenseIncrease) "+" else ""
                val deltaColor = if (isZero) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else if (isExpenseIncrease) {
                    semanticColors.expenseCoral
                } else {
                    semanticColors.incomeEmerald
                }

                HorizontalDivider(color = semanticColors.cardStrokeSubtle)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "vs. Prior Period Expense",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "$arrow $sign${String.format(Locale.US, "%.1f%%", displayPct)} (",
                            style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                            fontWeight = FontWeight.Bold,
                            color = deltaColor,
                        )
                        MoneyText(
                            money = MinorMoney(MoneyFormatterAndCalculator.safeAbs(expenseDelta)),
                            currencySpec = currencySpec,
                            grouping = grouping,
                            colorMode = MoneyColorMode.NEUTRAL,
                            style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = ")",
                            style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                            fontWeight = FontWeight.Bold,
                            color = deltaColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TopDriversSummaryCard(
    title: String,
    stats: List<CategorySpendStat>,
    currencySpec: CurrencySpec,
    grouping: NumberGroupingSystem,
    colorMode: MoneyColorMode = MoneyColorMode.EXPENSE,
    onCategoryClick: (CategorySpendStat) -> Unit,
    modifier: Modifier = Modifier,
) {
    val semanticColors = LocalFolioSemanticColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            stats.forEach { stat ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onCategoryClick(stat) }
                        .padding(vertical = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        CategoryIconBadge(
                            iconName = stat.iconName,
                            colorHex = stat.colorHex,
                            size = 32.dp,
                            iconSize = 16.dp,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stat.categoryName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = String.format(Locale.US, "%.1f%% of total", stat.percentageOfTotal),
                                style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    MoneyText(
                        money = stat.netAmount,
                        currencySpec = currencySpec,
                        grouping = grouping,
                        colorMode = colorMode,
                        style = tabularMoneyStyle(MaterialTheme.typography.bodyLarge),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
fun NetWorthVelocityCard(
    velocity: NetWorthVelocityState,
    currencySpec: CurrencySpec,
    grouping: NumberGroupingSystem,
    modifier: Modifier = Modifier,
) {
    val semanticColors = LocalFolioSemanticColors.current
    val isPositive = velocity.isPositive
    val accentColor = if (isPositive) semanticColors.incomeEmerald else semanticColors.expenseCoral
    val badgeBg = if (isPositive) semanticColors.incomeEmeraldContainer.copy(alpha = 0.5f) else semanticColors.expenseCoralContainer.copy(alpha = 0.5f)

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(badgeBg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isPositive) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = stringResource(R.string.nw_velocity_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "30-Day Growth Rate",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = badgeBg,
                ) {
                    Text(
                        text = "${if (isPositive) "+" else ""}${String.format(Locale.US, "%.1f", velocity.percentageChange)}% MoM",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "MONTH-OVER-MONTH CHANGE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = if (isPositive) "+" else "-",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                        )
                        MoneyText(
                            money = velocity.absoluteChange.abs(),
                            currencySpec = currencySpec,
                            grouping = grouping,
                            colorMode = if (isPositive) MoneyColorMode.INCOME else MoneyColorMode.EXPENSE,
                            style = if (LocalCurrencyDisplayPolicy.current.isMultiCharVisibleSymbol(currencySpec)) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "CURRENT NET WORTH",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MoneyText(
                        money = velocity.currentNetWorth,
                        currencySpec = currencySpec,
                        grouping = grouping,
                        colorMode = MoneyColorMode.NEUTRAL,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
fun EmergencyRunwayCard(
    runway: EmergencyRunwayState,
    currencySpec: CurrencySpec,
    grouping: NumberGroupingSystem,
    onToggleIncludeInvestments: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semanticColors = LocalFolioSemanticColors.current
    val tierColor = when (runway.healthTier) {
        RunwayHealthTier.RESILIENT -> semanticColors.incomeEmerald
        RunwayHealthTier.ADEQUATE -> semanticColors.warningAmber
        RunwayHealthTier.VULNERABLE -> semanticColors.expenseCoral
    }
    val tierBg = when (runway.healthTier) {
        RunwayHealthTier.RESILIENT -> semanticColors.incomeEmeraldContainer.copy(alpha = 0.5f)
        RunwayHealthTier.ADEQUATE -> semanticColors.warningAmberContainer.copy(alpha = 0.5f)
        RunwayHealthTier.VULNERABLE -> semanticColors.expenseCoralContainer.copy(alpha = 0.5f)
    }
    val tierLabel = when (runway.healthTier) {
        RunwayHealthTier.RESILIENT -> "Resilient (>6 mos)"
        RunwayHealthTier.ADEQUATE -> "Adequate (3–6 mos)"
        RunwayHealthTier.VULNERABLE -> "Vulnerable (<3 mos)"
    }

    val animatedFraction by animateFloatAsState(
        targetValue = if (runway.targetMonths > 0f) {
            (runway.runwayMonths / runway.targetMonths).coerceIn(0f, 1f)
        } else {
            0f
        },
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "RunwayProgress",
    )

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            // Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(tierBg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = null,
                            tint = tierColor,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = stringResource(R.string.nw_velocity_runway),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = tierLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = tierColor,
                        )
                    }
                }

                FilterChip(
                    selected = runway.includesInvestments,
                    onClick = onToggleIncludeInvestments,
                    label = {
                        Text(
                            text = if (runway.includesInvestments) "+ Investments" else "Liquid Only",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                        )
                    },
                )
            }

            // Big Metric + Progress Bar
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = String.format(Locale.US, "%.1f", runway.runwayMonths),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black,
                            color = tierColor,
                        )
                        Text(
                            text = "Months Covered",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }

                    Text(
                        text = "Target: ${String.format(Locale.US, "%.0f", runway.targetMonths)} mos",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

                LinearProgressIndicator(
                    progress = { animatedFraction },
                    color = tierColor,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                )
            }

            HorizontalDivider(
                color = semanticColors.cardStrokeSubtle,
                thickness = 1.dp,
            )

            // Sub-metrics: 2 columns
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (runway.includesInvestments) "TOTAL AVAILABLE FUNDS" else "LIQUID EMERGENCY FUNDS",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val funds = if (runway.includesInvestments) {
                        runway.totalLiquidFunds + runway.totalInvestmentFunds
                    } else {
                        runway.totalLiquidFunds
                    }
                    MoneyText(
                        money = funds,
                        currencySpec = currencySpec,
                        grouping = grouping,
                        colorMode = MoneyColorMode.NEUTRAL,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (runway.includesInvestments) "Bank + Cash + Savings + Invest" else "Bank + Cash + Savings",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "AVG MONTHLY LIVING EXPENSE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MoneyText(
                        money = MinorMoney(runway.averageMonthlyBurnMinor),
                        currencySpec = currencySpec,
                        grouping = grouping,
                        colorMode = MoneyColorMode.EXPENSE,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Past 6-Month Average",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
