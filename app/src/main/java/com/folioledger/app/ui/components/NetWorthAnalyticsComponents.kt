/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.folioledger.app.R
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import com.folioledger.app.ui.stats.CreditCardHealthUiModel
import com.folioledger.app.ui.stats.CreditUtilizationStatus
import com.folioledger.app.ui.stats.NetWorthChartMetricFilter
import com.folioledger.app.ui.stats.NetWorthGrowthTrendItem
import com.folioledger.app.ui.theme.LocalCurrencyDisplayPolicy
import com.folioledger.app.ui.theme.LocalFolioSemanticColors
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun NetWorthGrowthCanvasChart(
    trendItems: List<NetWorthGrowthTrendItem>,
    selectedFilter: NetWorthChartMetricFilter,
    currencySpec: CurrencySpec,
    grouping: NumberGroupingSystem,
    onSelectFilter: (NetWorthChartMetricFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val semanticColors = LocalFolioSemanticColors.current
    var selectedIndex by rememberSaveable(trendItems.size) {
        mutableIntStateOf(if (trendItems.isNotEmpty()) trendItems.lastIndex else -1)
    }

    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(trendItems) {
        animProgress.snapTo(0f)
        animProgress.animateTo(1f, animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing))
    }

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
            modifier = Modifier.padding(16.dp),
        ) {
            // Header Title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ShowChart,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Net Worth & Wealth Trajectory",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // Filter Chips FlowRow
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                NetWorthChartMetricFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { onSelectFilter(filter) },
                        label = {
                            Text(
                                text = filter.title,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selectedFilter == filter) FontWeight.Bold else FontWeight.Medium,
                            )
                        },
                    )
                }
            }

            if (trendItems.isEmpty()) {
                Text(
                    text = "Insufficient historical ledger data to plot trend.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                val activeItem = trendItems.getOrNull(selectedIndex) ?: trendItems.last()
                val prevItem = if (selectedIndex > 0) trendItems.getOrNull(selectedIndex - 1) else null
                val momDeltaMinor = prevItem?.let {
                    com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeSubtract(
                        activeItem.netWorth.amountMinor,
                        it.netWorth.amountMinor,
                    )
                }
                val momDeltaPercent = prevItem?.let {
                    val prevAbsMinor = com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeAbs(it.netWorth.amountMinor)
                    if (prevAbsMinor > 0L && momDeltaMinor != null) {
                        com.folioledger.app.domain.util.MoneyFormatterAndCalculator.calculatePercentage(
                            momDeltaMinor,
                            prevAbsMinor,
                        ).toDouble()
                    } else {
                        null
                    }
                }

                // Live Active Month Inspection Card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Text(
                            text = "${activeItem.monthYearText} Snapshot",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        val currencyPolicy = LocalCurrencyDisplayPolicy.current
                        val netWorthMetricStyle = if (currencyPolicy.isMultiCharVisibleSymbol(currencySpec)) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            val assetsLabel = stringResource(R.string.accounts_banner_assets)
                            val liabilitiesLabel = stringResource(R.string.accounts_banner_liabilities)
                            val netWorthLabel = stringResource(R.string.accounts_banner_net_worth)

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics(mergeDescendants = true) {},
                            ) {
                                Text(
                                    text = assetsLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.semantics { contentDescription = assetsLabel },
                                )
                                MoneyText(
                                    money = activeItem.assets,
                                    currencySpec = currencySpec,
                                    grouping = grouping,
                                    colorMode = MoneyColorMode.INCOME,
                                    style = netWorthMetricStyle,
                                    fontWeight = FontWeight.Bold,
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics(mergeDescendants = true) {},
                            ) {
                                Text(
                                    text = liabilitiesLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.semantics { contentDescription = liabilitiesLabel },
                                )
                                MoneyText(
                                    money = activeItem.liabilities,
                                    currencySpec = currencySpec,
                                    grouping = grouping,
                                    colorMode = MoneyColorMode.EXPENSE,
                                    style = netWorthMetricStyle,
                                    fontWeight = FontWeight.Bold,
                                )
                            }

                            Column(
                                horizontalAlignment = Alignment.End,
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics(mergeDescendants = true) {},
                            ) {
                                Text(
                                    text = netWorthLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.semantics { contentDescription = netWorthLabel },
                                )
                                MoneyText(
                                    money = activeItem.netWorth,
                                    currencySpec = currencySpec,
                                    grouping = grouping,
                                    colorMode = MoneyColorMode.AUTO_SIGN,
                                    style = netWorthMetricStyle,
                                    fontWeight = FontWeight.Bold,
                                )
                                if (momDeltaMinor != null && momDeltaPercent != null) {
                                    val isGain = momDeltaMinor >= 0L
                                    val sign = if (isGain) "+" else ""
                                    val formattedDelta = MoneyFormatterAndCalculator.formatMinorMoney(
                                        MinorMoney(momDeltaMinor),
                                        currencySpec,
                                        grouping,
                                        includeSymbol = currencyPolicy.shouldShowSymbol(currencySpec),
                                        showPlusSignForPositive = true,
                                    )
                                    Text(
                                        text = "$formattedDelta (${sign}${String.format(Locale.getDefault(), "%.1f", momDeltaPercent)}%)",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isGain) semanticColors.incomeEmerald else semanticColors.expenseCoral,
                                    )
                                }
                            }
                        }
                    }
                }

                // Dynamic Scaled Canvas Area Chart with Negative Baseline Support
                val isOverallGrowth = remember(trendItems) {
                    if (trendItems.size >= 2) {
                        trendItems.last().netWorth.amountMinor >= trendItems.first().netWorth.amountMinor
                    } else {
                        true
                    }
                }

                val primaryColor = MaterialTheme.colorScheme.primary
                val netWorthLineColor = if (isOverallGrowth) semanticColors.incomeEmerald else semanticColors.expenseCoral
                val assetCurveColor = semanticColors.incomeEmerald
                val liabilityCurveColor = semanticColors.expenseCoral
                val surfaceColor = MaterialTheme.colorScheme.surface
                val gridColor = semanticColors.cardStrokeSubtle
                val zeroBaselineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)

                val maxAsset = remember(trendItems) { trendItems.maxOfOrNull { it.assets.amountMinor } ?: 0L }
                val maxLiability = remember(trendItems) { trendItems.maxOfOrNull { it.liabilities.amountMinor } ?: 0L }
                val maxNetWorth = remember(trendItems) { trendItems.maxOfOrNull { it.netWorth.amountMinor } ?: 0L }
                val minNetWorth = remember(trendItems) { trendItems.minOfOrNull { it.netWorth.amountMinor } ?: 0L }

                val (ceiling, floor) = remember(trendItems, selectedFilter, maxAsset, maxLiability, maxNetWorth, minNetWorth) {
                    when (selectedFilter) {
                        NetWorthChartMetricFilter.ALL_IN_ONE -> {
                            val c = maxOf(maxAsset, maxNetWorth, 1000L) * 1.08f
                            val minRaw = minOf(-maxLiability, minNetWorth, 0L)
                            val f = if (minRaw < 0L) minRaw * 1.08f else 0f
                            c to f
                        }

                        NetWorthChartMetricFilter.ASSETS -> {
                            val c = maxOf(maxAsset, 1000L) * 1.08f
                            c to 0f
                        }

                        NetWorthChartMetricFilter.LIABILITIES -> {
                            val c = maxOf(maxLiability, 1000L) * 1.08f
                            c to 0f
                        }

                        NetWorthChartMetricFilter.NET_WORTH -> {
                            val c = maxOf(maxNetWorth, 1000L) * 1.08f
                            val f = minOf(minNetWorth, 0L)
                            val padF = if (f < 0f) f * 1.08f else 0f
                            c to padF
                        }
                    }
                }

                val totalSpan = remember(ceiling, floor) {
                    (ceiling - floor).coerceAtLeast(1f)
                }

                val zeroDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) }
                val companionDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) }
                val activeDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(4f, 4f)) }

                val nwPath = remember { Path() }
                val fillPath = remember { Path() }
                val assetPath = remember { Path() }
                val liabilityPath = remember { Path() }

                val primaryMetricAmounts = remember(trendItems, selectedFilter) {
                    trendItems.map { item ->
                        when (selectedFilter) {
                            NetWorthChartMetricFilter.ALL_IN_ONE,
                            NetWorthChartMetricFilter.NET_WORTH,
                            -> item.netWorth.amountMinor

                            NetWorthChartMetricFilter.ASSETS -> item.assets.amountMinor

                            NetWorthChartMetricFilter.LIABILITIES -> item.liabilities.amountMinor
                        }
                    }
                }

                val primaryColorTone = remember(selectedFilter, assetCurveColor, liabilityCurveColor, netWorthLineColor) {
                    when (selectedFilter) {
                        NetWorthChartMetricFilter.ASSETS -> assetCurveColor

                        NetWorthChartMetricFilter.LIABILITIES -> liabilityCurveColor

                        NetWorthChartMetricFilter.ALL_IN_ONE,
                        NetWorthChartMetricFilter.NET_WORTH,
                        -> netWorthLineColor
                    }
                }

                val gradientColors = remember(primaryColorTone) {
                    listOf(
                        primaryColorTone.copy(alpha = 0.35f),
                        primaryColorTone.copy(alpha = 0.03f),
                    )
                }

                val density = LocalDensity.current
                val padTopPx = remember(density) { with(density) { 16.dp.toPx() } }
                val padBottomPx = remember(density) { with(density) { 20.dp.toPx() } }
                val chartHeightPx = remember(density) { with(density) { 190.dp.toPx() } }
                val usableHeightPx = chartHeightPx - padTopPx - padBottomPx
                val zeroYPx = remember(padTopPx, usableHeightPx, ceiling, totalSpan) {
                    (padTopPx + usableHeightPx * (ceiling / totalSpan)).coerceIn(padTopPx, padTopPx + usableHeightPx)
                }
                val fillBrush = remember(gradientColors, padTopPx, usableHeightPx) {
                    Brush.verticalGradient(
                        colors = gradientColors,
                        startY = padTopPx,
                        endY = (padTopPx + usableHeightPx).coerceAtLeast(padTopPx + 1f),
                    )
                }

                val gridStrokeWidthPx = remember(density) { with(density) { 1.dp.toPx() } }
                val zeroBaselineStrokeWidthPx = remember(density) { with(density) { 1.5.dp.toPx() } }
                val companionStroke = remember(density, companionDashEffect) {
                    with(density) {
                        Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            pathEffect = companionDashEffect,
                        )
                    }
                }
                val primaryCurveStroke = remember(density) {
                    with(density) {
                        Stroke(
                            width = 3.5.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }
                val radius11Px = remember(density) { with(density) { 11.dp.toPx() } }
                val radius6Px = remember(density) { with(density) { 6.dp.toPx() } }
                val radius4Px = remember(density) { with(density) { 4.dp.toPx() } }
                val radius2AndHalfPx = remember(density) { with(density) { 2.5.dp.toPx() } }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(190.dp)
                                .pointerInput(trendItems) {
                                    detectTapGestures { offset ->
                                        if (trendItems.isEmpty()) return@detectTapGestures
                                        if (trendItems.size == 1) {
                                            selectedIndex = 0
                                        } else {
                                            val stepX = size.width / (trendItems.size - 1).toFloat()
                                            val index = kotlin.math.round(offset.x / stepX).toInt().coerceIn(0, trendItems.lastIndex)
                                            selectedIndex = index
                                        }
                                    }
                                },
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val width = size.width
                                fun pointX(idx: Int): Float = if (trendItems.size <= 1) {
                                    width / 2f
                                } else {
                                    idx * (width / (trendItems.size - 1).toFloat())
                                }
                                val invTotalSpan = 1f / totalSpan

                                // Zero-allocation coordinate computation
                                val progress = animProgress.value
                                fun getY(amountMinor: Long): Float {
                                    val rawY = padTopPx + usableHeightPx * ((ceiling - amountMinor.toFloat()) * invTotalSpan)
                                    return zeroYPx + (rawY - zeroYPx) * progress
                                }

                                // Horizontal Subtle Grid lines
                                val horizontalLines = 3
                                for (i in 0..horizontalLines) {
                                    val gridY = padTopPx + (usableHeightPx / horizontalLines) * i
                                    drawLine(
                                        color = gridColor,
                                        start = Offset(0f, gridY),
                                        end = Offset(width, gridY),
                                        strokeWidth = gridStrokeWidthPx,
                                        pathEffect = zeroDashEffect,
                                    )
                                }

                                // Dashed Zero Baseline (if floor < 0f)
                                if (floor < 0f) {
                                    drawLine(
                                        color = zeroBaselineColor,
                                        start = Offset(0f, zeroYPx),
                                        end = Offset(width, zeroYPx),
                                        strokeWidth = zeroBaselineStrokeWidthPx,
                                        pathEffect = zeroDashEffect,
                                    )
                                }

                                // Companion Curves for ALL_IN_ONE mode
                                if (selectedFilter == NetWorthChartMetricFilter.ALL_IN_ONE && trendItems.size > 1) {
                                    // Assets Companion Curve
                                    assetPath.reset()
                                    trendItems.forEachIndexed { i, item ->
                                        val x = pointX(i)
                                        val y = getY(item.assets.amountMinor)
                                        if (i == 0) {
                                            assetPath.moveTo(x, y)
                                        } else {
                                            val prevX = pointX(i - 1)
                                            val prevY = getY(trendItems[i - 1].assets.amountMinor)
                                            val cX = (prevX + x) / 2f
                                            assetPath.cubicTo(cX, prevY, cX, y, x, y)
                                        }
                                    }
                                    drawPath(
                                        path = assetPath,
                                        color = assetCurveColor.copy(alpha = 0.70f),
                                        style = companionStroke,
                                    )

                                    // Liabilities Companion Curve
                                    liabilityPath.reset()
                                    trendItems.forEachIndexed { i, item ->
                                        val x = pointX(i)
                                        val y = getY(-item.liabilities.amountMinor)
                                        if (i == 0) {
                                            liabilityPath.moveTo(x, y)
                                        } else {
                                            val prevX = pointX(i - 1)
                                            val prevY = getY(-trendItems[i - 1].liabilities.amountMinor)
                                            val cX = (prevX + x) / 2f
                                            liabilityPath.cubicTo(cX, prevY, cX, y, x, y)
                                        }
                                    }
                                    drawPath(
                                        path = liabilityPath,
                                        color = liabilityCurveColor.copy(alpha = 0.70f),
                                        style = companionStroke,
                                    )
                                }

                                // Primary Trend Curve (Net Worth or isolated metric)
                                if (trendItems.size > 1) {
                                    nwPath.reset()
                                    fillPath.reset()

                                    val baselineY = zeroYPx

                                    primaryMetricAmounts.forEachIndexed { i, amount ->
                                        val x = pointX(i)
                                        val y = getY(amount)
                                        if (i == 0) {
                                            nwPath.moveTo(x, y)
                                            fillPath.moveTo(x, baselineY)
                                            fillPath.lineTo(x, y)
                                        } else {
                                            val prevX = pointX(i - 1)
                                            val prevY = getY(primaryMetricAmounts[i - 1])
                                            val cX = (prevX + x) / 2f
                                            nwPath.cubicTo(cX, prevY, cX, y, x, y)
                                            fillPath.cubicTo(cX, prevY, cX, y, x, y)
                                        }
                                    }

                                    val lastX = pointX(trendItems.lastIndex)
                                    fillPath.lineTo(lastX, baselineY)
                                    fillPath.close()

                                    // Gradient Area Fill (cached Brush, 0 allocations per frame)
                                    drawPath(
                                        path = fillPath,
                                        brush = fillBrush,
                                    )

                                    // Smooth Spline Stroke
                                    drawPath(
                                        path = nwPath,
                                        color = primaryColorTone,
                                        style = primaryCurveStroke,
                                    )
                                }

                                // Marker Nodes
                                primaryMetricAmounts.forEachIndexed { i, amount ->
                                    val x = pointX(i)
                                    val y = getY(amount)
                                    val isSelected = i == selectedIndex

                                    if (isSelected) {
                                        drawCircle(
                                            color = primaryColorTone.copy(alpha = 0.25f),
                                            radius = radius11Px,
                                            center = Offset(x, y),
                                        )
                                        drawCircle(
                                            color = surfaceColor,
                                            radius = radius6Px,
                                            center = Offset(x, y),
                                        )
                                        drawCircle(
                                            color = primaryColorTone,
                                            radius = radius4Px,
                                            center = Offset(x, y),
                                        )
                                    } else {
                                        drawCircle(
                                            color = surfaceColor,
                                            radius = radius4Px,
                                            center = Offset(x, y),
                                        )
                                        drawCircle(
                                            color = primaryColorTone,
                                            radius = radius2AndHalfPx,
                                            center = Offset(x, y),
                                        )
                                    }
                                }

                                // Selected Month Vertical Indicator Line
                                if (selectedIndex in trendItems.indices) {
                                    val activeX = pointX(selectedIndex)
                                    drawLine(
                                        color = primaryColorTone.copy(alpha = 0.5f),
                                        start = Offset(activeX, padTopPx),
                                        end = Offset(activeX, size.height - padBottomPx),
                                        strokeWidth = zeroBaselineStrokeWidthPx,
                                        pathEffect = activeDashEffect,
                                    )
                                }
                            }
                        }

                        // Month X-Axis Labels
                        Row(
                            horizontalArrangement = if (trendItems.size <= 1) Arrangement.Center else Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        ) {
                            trendItems.forEachIndexed { idx, item ->
                                val isSelected = idx == selectedIndex
                                val label = if (trendItems.size > 6) {
                                    if (isSelected) item.monthYearText.take(3) else item.monthYearText.take(1)
                                } else {
                                    item.monthYearText.take(3)
                                }
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(role = Role.Tab) { selectedIndex = idx }
                                        .semantics {
                                            contentDescription = item.monthYearText
                                            selected = isSelected
                                            role = Role.Tab
                                        }
                                        .padding(horizontal = 2.dp),
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                // Legend FlowRow
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(
                            modifier = Modifier
                                .size(width = 12.dp, height = 3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    when (selectedFilter) {
                                        NetWorthChartMetricFilter.ASSETS -> assetCurveColor
                                        NetWorthChartMetricFilter.LIABILITIES -> liabilityCurveColor
                                        else -> netWorthLineColor
                                    },
                                ),
                        )
                        Text(
                            text = when (selectedFilter) {
                                NetWorthChartMetricFilter.ASSETS -> "Total Assets"
                                NetWorthChartMetricFilter.LIABILITIES -> "Total Liabilities"
                                else -> "Net Worth Trajectory"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    if (selectedFilter == NetWorthChartMetricFilter.ALL_IN_ONE) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(width = 10.dp, height = 2.dp)
                                    .background(assetCurveColor.copy(alpha = 0.75f)),
                            )
                            Text(
                                text = "Assets",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(width = 10.dp, height = 2.dp)
                                    .background(liabilityCurveColor.copy(alpha = 0.75f)),
                            )
                            Text(
                                text = "Debt",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Text(
                        text = "Tap month to inspect",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

private data class CreditTierInfo(
    val tierText: String,
    val tierBg: Color,
    val tierFg: Color,
    val activeColor: Color,
)

@Composable
fun CreditUtilizationGauge(
    utilizationPercent: Float,
    modifier: Modifier = Modifier,
) {
    val semanticColors = LocalFolioSemanticColors.current
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val safePercent = if (utilizationPercent.isFinite()) utilizationPercent.coerceAtLeast(0f) else 0f
    val isLargeFontScale = LocalDensity.current.fontScale >= 1.35f

    val tierInfo = remember(safePercent, semanticColors) {
        when {
            safePercent > 50f -> CreditTierInfo(
                tierText = "Risky (>50%)",
                tierBg = semanticColors.expenseCoralContainer,
                tierFg = semanticColors.onExpenseCoralContainer,
                activeColor = semanticColors.expenseCoral,
            )

            safePercent > 30f -> CreditTierInfo(
                tierText = "High (30-50%)",
                tierBg = semanticColors.warningAmberContainer,
                tierFg = semanticColors.onWarningAmberContainer,
                activeColor = semanticColors.warningAmber,
            )

            safePercent > 10f -> CreditTierInfo(
                tierText = "Good (10-30%)",
                tierBg = semanticColors.incomeEmeraldContainer,
                tierFg = semanticColors.onIncomeEmeraldContainer,
                activeColor = semanticColors.incomeEmerald,
            )

            else -> CreditTierInfo(
                tierText = "Optimal (<10%)",
                tierBg = semanticColors.incomeEmeraldContainer,
                tierFg = semanticColors.onIncomeEmeraldContainer,
                activeColor = semanticColors.incomeEmerald,
            )
        }
    }

    val arcModifier = Modifier
        .fillMaxWidth(0.72f)
        .height(120.dp)
        .drawWithCache {
            val strokeWidth = 14.dp.toPx()
            val arcPadding = strokeWidth / 2f
            val arcWidth = size.width - strokeWidth
            val arcHeight = (size.height - arcPadding) * 2f

            val arcTopLeft = Offset(arcPadding, arcPadding)
            val arcSize = Size(arcWidth, arcHeight)

            val cx = size.width / 2f
            val cy = arcHeight / 2f + arcPadding
            val r = arcWidth / 2f
            val thresholds = floatArrayOf(0.10f, 0.30f, 0.50f)
            val tickColor = trackColor.copy(alpha = 0.9f)
            val tickStrokePx = 2.dp.toPx()
            val oneDpPx = 1.dp.toPx()

            val cachedTicks = thresholds.map { t ->
                val rad = Math.toRadians((180f + t * 180f).toDouble())
                val innerR = r - strokeWidth / 2f - oneDpPx
                val outerR = r + strokeWidth / 2f + oneDpPx
                val p1 = Offset(
                    (cx + innerR * cos(rad)).toFloat(),
                    (cy + innerR * sin(rad)).toFloat(),
                )
                val p2 = Offset(
                    (cx + outerR * cos(rad)).toFloat(),
                    (cy + outerR * sin(rad)).toFloat(),
                )
                p1 to p2
            }

            val arcStroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

            onDrawBehind {
                // Background Track Arc (180 to 360 degrees)
                drawArc(
                    color = trackColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = arcStroke,
                )

                // Active Progress Arc
                val sweep = (safePercent / 100f).coerceIn(0f, 1f) * 180f
                if (sweep > 0f) {
                    drawArc(
                        color = tierInfo.activeColor,
                        startAngle = 180f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = arcStroke,
                    )
                }

                // Cached tick marks
                for ((p1, p2) in cachedTicks) {
                    drawLine(
                        color = tickColor,
                        start = p1,
                        end = p2,
                        strokeWidth = tickStrokePx,
                    )
                }
            }
        }

    val gaugeSemanticsModifier = modifier
        .fillMaxWidth()
        .heightIn(min = 130.dp)
        .semantics(mergeDescendants = true) {
            contentDescription = "Credit utilized ${String.format(Locale.getDefault(), "%.1f", safePercent)}%, ${tierInfo.tierText}"
        }

    if (isLargeFontScale) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = gaugeSemanticsModifier,
        ) {
            Spacer(modifier = arcModifier)
            Text(
                text = "${String.format(Locale.getDefault(), "%.1f", safePercent)}%",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Credit Utilized",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = tierInfo.tierBg,
            ) {
                Text(
                    text = tierInfo.tierText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = tierInfo.tierFg,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = gaugeSemanticsModifier,
        ) {
            Spacer(modifier = arcModifier)

            // Center Content Overlay (Percentage & Tier Badge)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(top = 26.dp),
            ) {
                Text(
                    text = "${String.format(Locale.getDefault(), "%.1f", safePercent)}%",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Credit Utilized",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = tierInfo.tierBg,
                ) {
                    Text(
                        text = tierInfo.tierText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = tierInfo.tierFg,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun CreditHealthDashboardCard(
    totalAvailableCredit: MinorMoney,
    totalCreditLimit: MinorMoney,
    totalUsedCredit: MinorMoney,
    overallUtilizationPercent: Float,
    creditCardHealthList: List<CreditCardHealthUiModel>,
    currencySpec: CurrencySpec,
    grouping: NumberGroupingSystem,
    modifier: Modifier = Modifier,
) {
    val semanticColors = LocalFolioSemanticColors.current

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
            modifier = Modifier.padding(16.dp),
        ) {
            // Header with Live Snapshot Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CreditCard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Credit & Debt Health",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                ) {
                    Text(
                        text = "Live Snapshot",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .semantics { contentDescription = "Live Snapshot" },
                    )
                }
            }

            // Semi-Circular Utilization Gauge
            CreditUtilizationGauge(
                utilizationPercent = overallUtilizationPercent,
            )

            // Summary Numbers
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Used Debt: ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MoneyText(
                        money = totalUsedCredit,
                        currencySpec = currencySpec,
                        grouping = grouping,
                        colorMode = MoneyColorMode.EXPENSE,
                        style = if (LocalCurrencyDisplayPolicy.current.isMultiCharVisibleSymbol(currencySpec)) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Remaining Available: ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MoneyText(
                            money = totalAvailableCredit,
                            currencySpec = currencySpec,
                            grouping = grouping,
                            colorMode = MoneyColorMode.INCOME,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = " / ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MoneyText(
                            money = totalCreditLimit,
                            currencySpec = currencySpec,
                            grouping = grouping,
                            colorMode = MoneyColorMode.NEUTRAL,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            HorizontalDivider(color = semanticColors.cardStrokeSubtle)

            if (creditCardHealthList.isEmpty()) {
                Text(
                    text = "No active credit cards found.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Individual Credit Card Breakdown",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    creditCardHealthList.forEach { card ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            val safeCardPct = if (card.utilizationPercent.isFinite()) card.utilizationPercent.coerceAtLeast(0f) else 0f
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(12.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CategoryIconBadge(
                                            iconName = card.account.iconName,
                                            colorHex = card.account.colorHex,
                                            size = 32.dp,
                                            iconSize = 16.dp,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = card.account.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = when (card.status) {
                                            CreditUtilizationStatus.WARNING -> semanticColors.expenseCoralContainer
                                            CreditUtilizationStatus.MODERATE -> semanticColors.warningAmberContainer
                                            CreditUtilizationStatus.SAFE -> semanticColors.incomeEmeraldContainer
                                        },
                                    ) {
                                        Text(
                                            text = "${String.format(Locale.getDefault(), "%.1f", safeCardPct)}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = when (card.status) {
                                                CreditUtilizationStatus.WARNING -> semanticColors.onExpenseCoralContainer
                                                CreditUtilizationStatus.MODERATE -> semanticColors.onWarningAmberContainer
                                                CreditUtilizationStatus.SAFE -> semanticColors.onIncomeEmeraldContainer
                                            },
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = "Used: ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    MoneyText(
                                        money = card.totalDebt,
                                        currencySpec = currencySpec,
                                        grouping = grouping,
                                        colorMode = MoneyColorMode.EXPENSE,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }

                                LinearProgressIndicator(
                                    progress = { (safeCardPct / 100f).coerceIn(0f, 1f) },
                                    color = when (card.status) {
                                        CreditUtilizationStatus.WARNING -> semanticColors.expenseCoral
                                        CreditUtilizationStatus.MODERATE -> semanticColors.warningAmber
                                        CreditUtilizationStatus.SAFE -> semanticColors.incomeEmerald
                                    },
                                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    strokeCap = StrokeCap.Round,
                                    gapSize = 0.dp,
                                    drawStopIndicator = {},
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(CircleShape),
                                )

                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = "Available: ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        MoneyText(
                                            money = card.availableCredit,
                                            currencySpec = currencySpec,
                                            grouping = grouping,
                                            colorMode = MoneyColorMode.INCOME,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = " / ",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        MoneyText(
                                            money = card.creditLimit,
                                            currencySpec = currencySpec,
                                            grouping = grouping,
                                            colorMode = MoneyColorMode.NEUTRAL,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }

                                if (card.status == CreditUtilizationStatus.WARNING) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 2.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Warning,
                                            contentDescription = null,
                                            tint = semanticColors.expenseCoral,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "High utilization (>80%): Paying down this balance will help protect your credit score.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = semanticColors.expenseCoral,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
