/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/ui/components/FolioCharts.kt
package com.folioledger.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.folioledger.app.domain.model.CategoryType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.folioledger.app.R
import com.folioledger.app.domain.model.CategorySpendStat
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.MonthlyTrendBar
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.ui.theme.FolioSemanticColors
import com.folioledger.app.ui.theme.LocalFolioSemanticColors
import com.folioledger.app.ui.theme.tabularMoneyStyle
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

private data class DonutSliceArc(
    val stat: CategorySpendStat,
    val startAngle: Float,
    val sweepAngle: Float,
    val color: Color,
    val midCos: Float,
    val midSin: Float
)

@Composable
fun InteractiveCategoryDonutChart(
    stats: List<CategorySpendStat>,
    modifier: Modifier = Modifier,
    currencySpec: CurrencySpec = SupportedCurrencies.INR,
    grouping: NumberGroupingSystem = currencySpec.defaultGrouping,
    categoryType: CategoryType = CategoryType.EXPENSE,
    selectedCategoryId: Long? = null,
    onCategorySelected: ((CategorySpendStat?) -> Unit)? = null,
    centerLabelWhenUnselected: String = "Total Spend",
    chartSize: Dp = 230.dp,
    strokeWidth: Dp = 32.dp,
    showLegendChips: Boolean = true
) {
    val semanticColors = LocalFolioSemanticColors.current
    var internalSelectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(selectedCategoryId, categoryType) {
        internalSelectedId = selectedCategoryId
    }
    val activeSelectedId = selectedCategoryId ?: internalSelectedId

    val entryAnim = remember { Animatable(0f) }
    LaunchedEffect(stats) {
        entryAnim.snapTo(0f)
        entryAnim.animateTo(1f, animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing))
    }

    val totalMinorSum = remember(stats) {
        stats.fold(0L) { acc, item -> acc + max(0L, item.netAmount.amountMinor) }
    }
    val totalMoney = remember(totalMinorSum) { MinorMoney(totalMinorSum) }

    val slices = remember(stats, totalMinorSum) {
        val positiveStats = stats.filter { it.netAmount.amountMinor > 0L }
        if (totalMinorSum <= 0L || positiveStats.isEmpty()) {
            emptyList()
        } else {
            val rawSweeps = positiveStats.map { stat ->
                val ratio = stat.netAmount.amountMinor.toFloat() / totalMinorSum.toFloat()
                (ratio * 360f).coerceAtLeast(2.5f)
            }
            val sweepScale = 360f / rawSweeps.sum().coerceAtLeast(360f)
            var runningAngle = -90f
            positiveStats.mapIndexed { index, stat ->
                val sweep = rawSweeps[index] * sweepScale
                val midAngleRad = Math.toRadians((runningAngle + sweep / 2f).toDouble())
                val slice = DonutSliceArc(
                    stat = stat,
                    startAngle = runningAngle,
                    sweepAngle = sweep,
                    color = parseHexColorSafely(stat.colorHex),
                    midCos = cos(midAngleRad).toFloat(),
                    midSin = sin(midAngleRad).toFloat()
                )
                runningAngle += sweep
                slice
            }
        }
    }

    val selectedStat = remember(slices, activeSelectedId) {
        slices.firstOrNull { it.stat.categoryId == activeSelectedId }?.stat
    }

    val handleSelect: (CategorySpendStat?) -> Unit = { tapped ->
        val next = if (tapped?.categoryId == activeSelectedId) null else tapped
        internalSelectedId = next?.categoryId
        onCategorySelected?.invoke(next)
    }
    val currentHandleSelect by androidx.compose.runtime.rememberUpdatedState(handleSelect)

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
        modifier = modifier.fillMaxWidth()
    ) {
        val isLargeFontScale = LocalDensity.current.fontScale >= 1.35f
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(chartSize)
            ) {
                val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                val density = LocalDensity.current
                val baseStrokePx = remember(density, strokeWidth) { with(density) { strokeWidth.toPx() } }
                val normalStroke = remember(baseStrokePx) { Stroke(width = baseStrokePx, cap = StrokeCap.Butt) }
                val selectedStroke = remember(baseStrokePx) { Stroke(width = baseStrokePx * 1.22f, cap = StrokeCap.Butt) }
                val trackStroke = remember(baseStrokePx) { Stroke(width = baseStrokePx, cap = StrokeCap.Round) }

                val donutChartDescription = if (selectedStat != null) {
                    "${selectedStat.categoryName}, ${String.format(java.util.Locale.getDefault(), "%.1f", selectedStat.percentageOfTotal)}%"
                } else {
                    "Category breakdown donut chart"
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = donutChartDescription }
                        .pointerInput(slices) {
                            detectTapGestures { tapOffset ->
                                if (slices.isEmpty()) return@detectTapGestures
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val dx = tapOffset.x - center.x
                                val dy = tapOffset.y - center.y
                                val distance = hypot(dx, dy)

                                val strokePx = strokeWidth.toPx()
                                val outerRadius = (kotlin.math.min(size.width, size.height).toFloat() / 2f) - (strokePx * 0.2f)
                                val innerRadius = outerRadius - (strokePx * 1.45f)

                                if (distance < innerRadius) {
                                    currentHandleSelect(null)
                                    return@detectTapGestures
                                }
                                if (distance > outerRadius + strokePx * 0.5f) {
                                    return@detectTapGestures
                                }

                                var touchDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                                if (touchDeg < -90f) {
                                    touchDeg += 360f
                                }

                                val hitSlice = slices.firstOrNull { slice ->
                                    val normalizedTouch = if (touchDeg < slice.startAngle) {
                                        touchDeg + 360f
                                    } else {
                                        touchDeg
                                    }
                                    normalizedTouch >= slice.startAngle &&
                                            normalizedTouch <= (slice.startAngle + slice.sweepAngle)
                                }
                                currentHandleSelect(hitSlice?.stat)
                            }
                        }
                ) {
                    val entryProgress = entryAnim.value
                    val diameter = size.minDimension - baseStrokePx * 1.35f
                    val topLeft = Offset(
                        x = (size.width - diameter) / 2f,
                        y = (size.height - diameter) / 2f
                    )
                    val arcSize = Size(diameter, diameter)

                    if (slices.isEmpty()) {
                        drawArc(
                            color = trackColor,
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = trackStroke
                        )
                    } else {
                        val gapAngle = if (slices.size > 1) 2.2f else 0f
                        slices.forEach { slice ->
                            val isSelected = slice.stat.categoryId == activeSelectedId
                            val animatedSweep = (slice.sweepAngle - gapAngle).coerceAtLeast(1f) * entryProgress
                            val alpha = if (activeSelectedId == null || isSelected) 1f else 0.42f

                            val shiftedTopLeft = if (isSelected) {
                                val shiftDistance = baseStrokePx * 0.14f
                                Offset(
                                    x = topLeft.x + slice.midCos * shiftDistance,
                                    y = topLeft.y + slice.midSin * shiftDistance
                                )
                            } else {
                                topLeft
                            }

                            drawArc(
                                color = slice.color.copy(alpha = alpha),
                                startAngle = slice.startAngle + gapAngle / 2f,
                                sweepAngle = animatedSweep,
                                useCenter = false,
                                topLeft = shiftedTopLeft,
                                size = arcSize,
                                style = if (isSelected) selectedStroke else normalStroke
                            )
                        }
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .padding(horizontal = strokeWidth + 14.dp)
                        .clickable(enabled = selectedStat != null) {
                            handleSelect(null)
                        }
                ) {
                    if (isLargeFontScale) {
                        MoneyText(
                            money = selectedStat?.netAmount ?: totalMoney,
                            currencySpec = currencySpec,
                            grouping = grouping,
                            colorMode = MoneyColorMode.NEUTRAL,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    } else if (selectedStat != null) {
                        Text(
                            text = selectedStat.categoryName,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        MoneyText(
                            money = selectedStat.netAmount,
                            currencySpec = currencySpec,
                            grouping = grouping,
                            colorMode = MoneyColorMode.NEUTRAL,
                            style = if (currencySpec.symbol.length > 2) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val formattedPct = remember(selectedStat.percentageOfTotal) {
                            String.format(java.util.Locale.getDefault(), "%.1f%%", selectedStat.percentageOfTotal)
                        }
                        val selectedColor = remember(selectedStat.colorHex) {
                            parseHexColorSafely(selectedStat.colorHex)
                        }
                        Surface(
                            color = selectedColor.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "$formattedPct \u00B7 ${selectedStat.transactionCount} trans",
                                style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                                color = selectedColor,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        selectedStat.changePercentage?.let { deltaPct ->
                            Spacer(modifier = Modifier.height(2.dp))
                            DonutMoMDeltaBadge(
                                deltaPct = deltaPct,
                                categoryType = categoryType,
                                semanticColors = semanticColors
                            )
                        }
                    } else {
                        Text(
                            text = centerLabelWhenUnselected,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        MoneyText(
                            money = totalMoney,
                            currencySpec = currencySpec,
                            grouping = grouping,
                            colorMode = MoneyColorMode.NEUTRAL,
                            style = if (currencySpec.symbol.length > 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        if (slices.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Tap slice to inspect",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (isLargeFontScale) {
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (selectedStat != null) {
                        Text(
                            text = selectedStat.categoryName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val formattedPct = remember(selectedStat.percentageOfTotal) {
                            String.format(java.util.Locale.getDefault(), "%.1f%%", selectedStat.percentageOfTotal)
                        }
                        Text(
                            text = "$formattedPct \u00B7 ${selectedStat.transactionCount} trans",
                            style = tabularMoneyStyle(MaterialTheme.typography.labelMedium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        selectedStat.changePercentage?.let { deltaPct ->
                            DonutMoMDeltaBadge(
                                deltaPct = deltaPct,
                                categoryType = categoryType,
                                semanticColors = semanticColors
                            )
                        }
                    } else {
                        Text(
                            text = centerLabelWhenUnselected,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (slices.isNotEmpty()) {
                            Text(
                                text = "Tap slice to inspect",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (showLegendChips && slices.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    slices.forEach { slice ->
                        val isSelected = slice.stat.categoryId == activeSelectedId
                        val pctText = remember(slice.stat.percentageOfTotal) {
                            String.format(java.util.Locale.getDefault(), "%.1f%%", slice.stat.percentageOfTotal)
                        }
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSelected) {
                                slice.color.copy(alpha = 0.2f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isSelected) slice.color else semanticColors.cardStrokeSubtle
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .heightIn(min = 48.dp)
                                .semantics(mergeDescendants = true) {
                                    selected = isSelected
                                    role = Role.Tab
                                }
                                .clickable(role = Role.Tab) { handleSelect(slice.stat) }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(slice.color)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = slice.stat.categoryName,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = pctText,
                                    style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DonutMoMDeltaBadge(
    deltaPct: Float,
    categoryType: CategoryType,
    semanticColors: FolioSemanticColors
) {
    val isIncrease = deltaPct > 0f
    val isZero = kotlin.math.abs(deltaPct) < 0.1f
    val deltaColor = if (isZero) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else if (categoryType == CategoryType.EXPENSE) {
        if (isIncrease) semanticColors.expenseCoral else semanticColors.incomeEmerald
    } else {
        if (isIncrease) semanticColors.incomeEmerald else semanticColors.expenseCoral
    }
    val arrow = if (isZero) "\u2022" else if (isIncrease) "\u25B2" else "\u25BC"
    val sign = if (isIncrease) "+" else ""
    val formattedDelta = remember(deltaPct) {
        String.format(java.util.Locale.getDefault(), "%.1f%%", deltaPct)
    }
    Surface(
        color = deltaColor.copy(alpha = 0.14f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = "$arrow $sign$formattedDelta",
            style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
            color = deltaColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun SixMonthTrendBarChart(
    bars: List<MonthlyTrendBar>,
    modifier: Modifier = Modifier,
    currencySpec: CurrencySpec = SupportedCurrencies.INR,
    grouping: NumberGroupingSystem = currencySpec.defaultGrouping,
    initialNetMode: Boolean = false,
    chartHeight: Dp = 200.dp
) {
    val semanticColors = LocalFolioSemanticColors.current
    var showNetMode by rememberSaveable { mutableStateOf(initialNetMode) }
    var selectedBarIndex by rememberSaveable(bars.size) {
        mutableIntStateOf(if (bars.isNotEmpty()) bars.lastIndex else -1)
    }

    val entryAnim = remember { Animatable(0f) }
    LaunchedEffect(bars, showNetMode) {
        entryAnim.snapTo(0f)
        entryAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing)
        )
    }

    val maxMagnitudeMinor = remember(bars, showNetMode) {
        if (bars.isEmpty()) {
            1L
        } else if (showNetMode) {
            bars.maxOf { it.netSavings.abs().amountMinor }.coerceAtLeast(1L)
        } else {
            bars.maxOf {
                max(it.totalIncome.amountMinor, it.totalExpense.amountMinor)
            }.coerceAtLeast(1L)
        }
    }

    val selectedBar = bars.getOrNull(selectedBarIndex)

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Text(
                        text = "6-Month Cashflow Trend",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Tap any month bar to inspect exact ledger totals",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = !showNetMode,
                        onClick = { showNetMode = false },
                        label = {
                            Text(
                                text = "Inc / Exp",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                    FilterChip(
                        selected = showNetMode,
                        onClick = { showNetMode = true },
                        label = {
                            Text(
                                text = "Net",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }

            if (selectedBar != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = selectedBar.periodLabel,
                            style = tabularMoneyStyle(MaterialTheme.typography.labelLarge),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        val isMultiCharCurrency = currencySpec.symbol.length > 2
                        Row(horizontalArrangement = Arrangement.spacedBy(if (isMultiCharCurrency) 6.dp else 12.dp)) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = stringResource(R.string.ledger_banner_income),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                MoneyText(
                                    money = selectedBar.totalIncome,
                                    currencySpec = currencySpec,
                                    grouping = grouping,
                                    colorMode = MoneyColorMode.INCOME,
                                    style = if (isMultiCharCurrency) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = stringResource(R.string.ledger_banner_expense),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                MoneyText(
                                    money = selectedBar.totalExpense,
                                    currencySpec = currencySpec,
                                    grouping = grouping,
                                    colorMode = MoneyColorMode.EXPENSE,
                                    style = if (isMultiCharCurrency) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = stringResource(R.string.ledger_banner_net),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                MoneyText(
                                    money = selectedBar.netSavings,
                                    currencySpec = currencySpec,
                                    grouping = grouping,
                                    colorMode = MoneyColorMode.AUTO_SIGN,
                                    style = if (isMultiCharCurrency) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    showPlusSignForPositive = true
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            val gridColor = semanticColors.chartGridLine
            val incomeColor = semanticColors.incomeEmerald
            val expenseColor = semanticColors.expenseCoral
            val highlightSurface = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f) }
            val density = LocalDensity.current
            val barCornerRadius = remember(density) {
                with(density) { CornerRadius(6.dp.toPx(), 6.dp.toPx()) }
            }
            val highlightCornerRadius = remember(density) {
                with(density) { CornerRadius(8.dp.toPx(), 8.dp.toPx()) }
            }

            val topPaddingPx = remember(density) { with(density) { 6.dp.toPx() } }
            val twelveDpPx = remember(density) { with(density) { 12.dp.toPx() } }
            val oneDpPx = remember(density) { with(density) { 1.dp.toPx() } }
            val oneAndHalfDpPx = remember(density) { with(density) { 1.5.dp.toPx() } }
            val threeDpPx = remember(density) { with(density) { 3.dp.toPx() } }
            val fourDpPx = remember(density) { with(density) { 4.dp.toPx() } }
            val eightDpPx = remember(density) { with(density) { 8.dp.toPx() } }
            val eighteenDpPx = remember(density) { with(density) { 18.dp.toPx() } }
            val twentyFourDpPx = remember(density) { with(density) { 24.dp.toPx() } }

            val barChartDescription = selectedBar?.let {
                "6-month trend, ${it.periodLabel} selected"
            } ?: "6-month trend bar chart"

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(chartHeight)
                            .semantics { contentDescription = barChartDescription }
                            .pointerInput(bars) {
                                detectTapGestures { offset ->
                                    if (bars.isEmpty()) return@detectTapGestures
                                    val slotWidth = size.width / bars.size.toFloat()
                                    val index = (offset.x / slotWidth).toInt().coerceIn(0, bars.lastIndex)
                                    selectedBarIndex = index
                                }
                            }
                    ) {
                        val entryProgress = entryAnim.value
                        val horizontalLines = 4
                        val usableHeight = size.height - twelveDpPx
                        val topPadding = topPaddingPx

                        for (i in 0..horizontalLines) {
                            val y = topPadding + (usableHeight / horizontalLines) * i
                            drawLine(
                                color = gridColor,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = oneDpPx,
                                pathEffect = if (i == horizontalLines) null else dashEffect
                            )
                        }

                        if (bars.isEmpty()) return@Canvas

                        val slotWidth = size.width / bars.size.toFloat()

                        if (showNetMode) {
                            val zeroY = topPadding + (usableHeight / 2f)
                            drawLine(
                                color = gridColor,
                                start = Offset(0f, zeroY),
                                end = Offset(size.width, zeroY),
                                strokeWidth = oneAndHalfDpPx
                            )
                        }

                        bars.forEachIndexed { index, bar ->
                            val slotLeft = index * slotWidth
                            val isSelected = index == selectedBarIndex

                            if (isSelected) {
                                drawRoundRect(
                                    color = highlightSurface,
                                    topLeft = Offset(slotLeft + fourDpPx, 0f),
                                    size = Size(slotWidth - eightDpPx, usableHeight + topPadding),
                                    cornerRadius = highlightCornerRadius
                                )
                            }

                            if (!showNetMode) {
                                val barWidth = (slotWidth * 0.28f).coerceAtMost(eighteenDpPx)
                                val gap = fourDpPx
                                val groupWidth = barWidth * 2f + gap
                                val startX = slotLeft + (slotWidth - groupWidth) / 2f
                                val baselineY = topPadding + usableHeight

                                val incomeRatio = (bar.totalIncome.amountMinor.toFloat() / maxMagnitudeMinor.toFloat())
                                    .coerceIn(0f, 1f) * entryProgress
                                val expenseRatio = (bar.totalExpense.amountMinor.toFloat() / maxMagnitudeMinor.toFloat())
                                    .coerceIn(0f, 1f) * entryProgress

                                val incomeHeight = if (bar.totalIncome.amountMinor > 0L) {
                                    (usableHeight * incomeRatio).coerceAtLeast(threeDpPx)
                                } else {
                                    0f
                                }
                                val expenseHeight = if (bar.totalExpense.amountMinor > 0L) {
                                    (usableHeight * expenseRatio).coerceAtLeast(threeDpPx)
                                } else {
                                    0f
                                }

                                if (incomeHeight > 0f) {
                                    drawRoundRect(
                                        color = if (isSelected) incomeColor else incomeColor.copy(alpha = 0.78f),
                                        topLeft = Offset(startX, baselineY - incomeHeight),
                                        size = Size(barWidth, incomeHeight),
                                        cornerRadius = barCornerRadius
                                    )
                                }

                                if (expenseHeight > 0f) {
                                    drawRoundRect(
                                        color = if (isSelected) expenseColor else expenseColor.copy(alpha = 0.78f),
                                        topLeft = Offset(startX + barWidth + gap, baselineY - expenseHeight),
                                        size = Size(barWidth, expenseHeight),
                                        cornerRadius = barCornerRadius
                                    )
                                }
                            } else {
                                val zeroY = topPadding + (usableHeight / 2f)
                                val barWidth = (slotWidth * 0.42f).coerceAtMost(twentyFourDpPx)
                                val startX = slotLeft + (slotWidth - barWidth) / 2f
                                val netMinor = bar.netSavings.amountMinor
                                if (netMinor != 0L) {
                                    val ratio = (kotlin.math.abs(netMinor).toFloat() / maxMagnitudeMinor.toFloat())
                                        .coerceIn(0f, 1f) * entryProgress
                                    val barHeight = (usableHeight * 0.46f * ratio).coerceAtLeast(threeDpPx)

                                    val color = if (netMinor > 0L) incomeColor else expenseColor
                                    val topY = if (netMinor > 0L) zeroY - barHeight else zeroY

                                    drawRoundRect(
                                        color = if (isSelected) color else color.copy(alpha = 0.8f),
                                        topLeft = Offset(startX, topY),
                                        size = Size(barWidth, barHeight),
                                        cornerRadius = barCornerRadius
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        bars.forEachIndexed { index, bar ->
                            val isSelected = index == selectedBarIndex
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .heightIn(min = 48.dp)
                                    .clickable(role = Role.Tab) { selectedBarIndex = index }
                                    .semantics {
                                        selected = isSelected
                                        role = Role.Tab
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    text = bar.periodLabel,
                                    style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SixMonthSmoothTrendAreaChart(
    bars: List<MonthlyTrendBar>,
    modifier: Modifier = Modifier,
    currencySpec: CurrencySpec = SupportedCurrencies.INR,
    grouping: NumberGroupingSystem = currencySpec.defaultGrouping,
    categoryType: CategoryType = CategoryType.EXPENSE,
    chartHeight: Dp = 136.dp,
    customColor: Color? = null
) {
    val semanticColors = LocalFolioSemanticColors.current
    var selectedIndex by rememberSaveable(bars.size) {
        mutableIntStateOf(if (bars.isNotEmpty()) bars.lastIndex else -1)
    }

    val entryAnim = remember { Animatable(0f) }
    LaunchedEffect(bars, categoryType) {
        entryAnim.snapTo(0f)
        entryAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing)
        )
    }

    val lineColor = customColor ?: if (categoryType == CategoryType.EXPENSE) {
        semanticColors.expenseCoral
    } else {
        semanticColors.incomeEmerald
    }

    val maxAmountMinor = remember(bars, categoryType) {
        if (bars.isEmpty()) {
            1L
        } else {
            bars.maxOf { bar ->
                if (categoryType == CategoryType.EXPENSE) {
                    bar.totalExpense.amountMinor
                } else {
                    bar.totalIncome.amountMinor
                }
            }.coerceAtLeast(1L)
        }
    }

    val selectedBar = bars.getOrNull(selectedIndex)

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Text(
                        text = "6-Month Trend Curve",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Tap data point to inspect month total",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = lineColor.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = if (categoryType == CategoryType.EXPENSE) "Expense Trend" else "Income Trend",
                        style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                        fontWeight = FontWeight.Bold,
                        color = lineColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (selectedBar != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = selectedBar.periodLabel,
                            style = tabularMoneyStyle(MaterialTheme.typography.labelLarge),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        val displayAmount = if (categoryType == CategoryType.EXPENSE) {
                            selectedBar.totalExpense
                        } else {
                            selectedBar.totalIncome
                        }

                        MoneyText(
                            money = displayAmount,
                            currencySpec = currencySpec,
                            grouping = grouping,
                            colorMode = if (categoryType == CategoryType.EXPENSE) {
                                MoneyColorMode.EXPENSE
                            } else {
                                MoneyColorMode.INCOME
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            val gridColor = semanticColors.chartGridLine
            val highlightBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f) }
            val strokePath = remember { Path() }
            val fillPath = remember { Path() }
            val density = LocalDensity.current
            val highlightCornerRadius = remember(density) {
                with(density) { CornerRadius(8.dp.toPx(), 8.dp.toPx()) }
            }

            val gradientColors = remember(lineColor) {
                listOf(
                    lineColor.copy(alpha = 0.35f),
                    lineColor.copy(alpha = 0.02f)
                )
            }
            val rawAmounts = remember(bars, categoryType) {
                bars.map { bar ->
                    if (categoryType == CategoryType.EXPENSE) {
                        bar.totalExpense.amountMinor
                    } else {
                        bar.totalIncome.amountMinor
                    }
                }
            }

            val topPaddingPx = remember(density) { with(density) { 12.dp.toPx() } }
            val bottomPaddingPx = remember(density) { with(density) { 10.dp.toPx() } }
            val oneDpPx = remember(density) { with(density) { 1.dp.toPx() } }
            val threeDpPx = remember(density) { with(density) { 3.dp.toPx() } }
            val fourDpPx = remember(density) { with(density) { 4.dp.toPx() } }
            val sixDpPx = remember(density) { with(density) { 6.dp.toPx() } }
            val eightDpPx = remember(density) { with(density) { 8.dp.toPx() } }
            val twelveDpPx = remember(density) { with(density) { 12.dp.toPx() } }

            val totalHeightPx = remember(density, chartHeight) { with(density) { chartHeight.toPx() } }
            val usableHeightPx = totalHeightPx - topPaddingPx - bottomPaddingPx
            val fillBrush = remember(gradientColors, topPaddingPx, usableHeightPx) {
                Brush.verticalGradient(
                    colors = gradientColors,
                    startY = topPaddingPx,
                    endY = topPaddingPx + usableHeightPx
                )
            }
            val pointsX = remember(bars.size) { FloatArray(bars.size) }
            val pointsY = remember(bars.size) { FloatArray(bars.size) }

            val curveStroke = remember(threeDpPx) {
                Stroke(
                    width = threeDpPx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            }

            val smoothTrendDescription = selectedBar?.let {
                "6-month trend curve, ${it.periodLabel} selected"
            } ?: "6-month trend curve"
            val surfaceColor = MaterialTheme.colorScheme.surface

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(chartHeight)
                            .semantics { contentDescription = smoothTrendDescription }
                            .pointerInput(bars) {
                                detectTapGestures { offset ->
                                    if (bars.isEmpty()) return@detectTapGestures
                                    val slotWidth = size.width / bars.size.toFloat()
                                    val index = (offset.x / slotWidth).toInt().coerceIn(0, bars.lastIndex)
                                    selectedIndex = index
                                }
                            }
                    ) {
                        val entryProgress = entryAnim.value
                        val horizontalLines = 3
                        val usableHeight = size.height - topPaddingPx - bottomPaddingPx

                        for (i in 0..horizontalLines) {
                            val y = topPaddingPx + (usableHeight / horizontalLines) * i
                            drawLine(
                                color = gridColor,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = oneDpPx,
                                pathEffect = if (i == horizontalLines) null else dashEffect
                            )
                        }

                        if (bars.isEmpty()) return@Canvas

                        val slotWidth = size.width / bars.size.toFloat()
                        for (idx in rawAmounts.indices) {
                            val rawVal = rawAmounts[idx]
                            val x = idx * slotWidth + slotWidth / 2f
                            val ratio = (rawVal.toFloat() / maxAmountMinor.toFloat()).coerceIn(0f, 1f) * entryProgress
                            val y = topPaddingPx + usableHeight * (1f - ratio)
                            pointsX[idx] = x
                            pointsY[idx] = y
                        }

                        if (selectedIndex in 0 until bars.size) {
                            val selX = pointsX[selectedIndex]
                            drawRoundRect(
                                color = highlightBg,
                                topLeft = Offset(selX - slotWidth / 2f + fourDpPx, topPaddingPx),
                                size = Size(slotWidth - eightDpPx, usableHeight),
                                cornerRadius = highlightCornerRadius
                            )
                        }

                        strokePath.reset()
                        fillPath.reset()

                        val firstX = pointsX[0]
                        val firstY = pointsY[0]
                        strokePath.moveTo(firstX, firstY)
                        fillPath.moveTo(firstX, topPaddingPx + usableHeight)
                        fillPath.lineTo(firstX, firstY)

                        for (i in 0 until bars.size - 1) {
                            val p1X = pointsX[i]
                            val p1Y = pointsY[i]
                            val p2X = pointsX[i + 1]
                            val p2Y = pointsY[i + 1]
                            val controlX1 = p1X + (p2X - p1X) * 0.35f
                            val controlY1 = p1Y
                            val controlX2 = p1X + (p2X - p1X) * 0.65f
                            val controlY2 = p2Y

                            strokePath.cubicTo(controlX1, controlY1, controlX2, controlY2, p2X, p2Y)
                            fillPath.cubicTo(controlX1, controlY1, controlX2, controlY2, p2X, p2Y)
                        }

                        val lastX = pointsX[bars.size - 1]
                        fillPath.lineTo(lastX, topPaddingPx + usableHeight)
                        fillPath.close()

                        drawPath(
                            path = fillPath,
                            brush = fillBrush
                        )

                        drawPath(
                            path = strokePath,
                            color = lineColor,
                            style = curveStroke
                        )

                        for (idx in 0 until bars.size) {
                            val ptX = pointsX[idx]
                            val ptY = pointsY[idx]
                            val isSelected = idx == selectedIndex
                            val center = Offset(ptX, ptY)
                            if (isSelected) {
                                drawCircle(
                                    color = lineColor.copy(alpha = 0.22f),
                                    radius = twelveDpPx,
                                    center = center
                                )
                                drawCircle(
                                    color = lineColor,
                                    radius = sixDpPx,
                                    center = center
                                )
                                drawCircle(
                                    color = surfaceColor,
                                    radius = threeDpPx,
                                    center = center
                                )
                            } else {
                                drawCircle(
                                    color = lineColor,
                                    radius = fourDpPx,
                                    center = center
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        bars.forEachIndexed { index, bar ->
                            val isSelected = index == selectedIndex
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .heightIn(min = 48.dp)
                                    .clickable(role = Role.Tab) { selectedIndex = index }
                                    .semantics {
                                        selected = isSelected
                                        role = Role.Tab
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    text = bar.periodLabel.take(3),
                                    style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}