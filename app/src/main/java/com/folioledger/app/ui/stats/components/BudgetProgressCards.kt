package com.folioledger.app.ui.stats.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folioledger.app.domain.model.BudgetProgress
import com.folioledger.app.domain.model.BudgetWindowStatus
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import com.folioledger.app.ui.components.CategoryIconBadge
import com.folioledger.app.ui.components.MoneyColorMode
import com.folioledger.app.ui.components.MoneyText
import com.folioledger.app.ui.stats.StatsPeriodRange
import com.folioledger.app.ui.theme.LocalFolioSemanticColors
import com.folioledger.app.ui.theme.tabularMoneyStyle
import java.time.LocalDate
import java.util.Locale

@Composable
internal fun BudgetTrackWithPacingTick(
    progressFraction: () -> Float,
    elapsedFraction: Float?,
    alertThresholdPercent: Int,
    statusColor: Color,
    trackColor: Color,
    isHeroOverall: Boolean,
    modifier: Modifier = Modifier
) {
    val barHeight = if (isHeroOverall) 12.dp else 10.dp
    val tickOverflow = 4.dp
    val totalCanvasHeight = barHeight + tickOverflow * 2
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primaryColor = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(totalCanvasHeight)
    ) {
        val width = size.width
        val height = barHeight.toPx()
        val topOffset = tickOverflow.toPx()
        val cornerRadius = CornerRadius(height / 2f, height / 2f)

        // 1. Draw track background
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, topOffset),
            size = Size(width, height),
            cornerRadius = cornerRadius
        )

        // 2. Draw progress fill
        val fillWidth = (width * progressFraction()).coerceIn(0f, width)
        if (fillWidth > 0f) {
            drawRoundRect(
                color = statusColor,
                topLeft = Offset(0f, topOffset),
                size = Size(fillWidth, height),
                cornerRadius = cornerRadius
            )
        }

        // 3. Draw alert threshold marker (e.g. 80%)
        val thresholdFraction = (alertThresholdPercent / 100f).coerceIn(0f, 1f)
        val thresholdX = width * thresholdFraction
        drawLine(
            color = onSurface.copy(alpha = 0.28f),
            start = Offset(thresholdX, topOffset),
            end = Offset(thresholdX, topOffset + height),
            strokeWidth = 2.dp.toPx()
        )

        // 4. Draw month progress pacing tick (if current period)
        if (elapsedFraction != null) {
            val tickX = width * elapsedFraction
            val tickWidth = 3.dp.toPx()
            val tickHeight = height + (tickOverflow.toPx() * 1.5f)
            val tickTop = topOffset - (tickOverflow.toPx() * 0.75f)

            // High contrast needle shadow/border
            drawRoundRect(
                color = onSurface.copy(alpha = 0.35f),
                topLeft = Offset(tickX - (tickWidth / 2f) - 1.dp.toPx(), tickTop - 1.dp.toPx()),
                size = Size(tickWidth + 2.dp.toPx(), tickHeight + 2.dp.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
            )

            // Primary needle
            drawRoundRect(
                color = primaryColor,
                topLeft = Offset(tickX - (tickWidth / 2f), tickTop),
                size = Size(tickWidth, tickHeight),
                cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
            )
        }
    }
}

@Composable
internal fun BudgetProgressCard(
    budget: BudgetProgress,
    isHeroOverall: Boolean,
    currencySpec: CurrencySpec,
    grouping: NumberGroupingSystem,
    activePeriodRange: StatsPeriodRange,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    todayEpochDay: Long = LocalDate.now().toEpochDay()
) {
    val semanticColors = LocalFolioSemanticColors.current
    val isExceeded = budget.usagePercentage >= 100f || budget.remainingAmount.isNegative
    val isNearAlert = !isExceeded && budget.usagePercentage >= budget.alertThresholdPercent.toFloat()

    val statusColor = when {
        isExceeded -> semanticColors.expenseCoral
        isNearAlert -> semanticColors.warningAmber
        else -> semanticColors.incomeEmerald
    }

    val statusContainerColor = when {
        isExceeded -> semanticColors.expenseCoralContainer
        isNearAlert -> semanticColors.warningAmberContainer
        else -> semanticColors.incomeEmeraldContainer
    }

    val onStatusContainerColor = when {
        isExceeded -> semanticColors.onExpenseCoralContainer
        isNearAlert -> semanticColors.onWarningAmberContainer
        else -> semanticColors.onIncomeEmeraldContainer
    }

    val animatedFraction = animateFloatAsState(
        targetValue = (budget.usagePercentage / 100f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "BudgetUsageFraction"
    )

    val pctBadgeText = remember(budget.usagePercentage) {
        String.format(Locale.US, "%.1f%%", budget.usagePercentage)
    }

    val windowStart = if (budget.periodStartEpochDay > 0L) budget.periodStartEpochDay else activePeriodRange.startEpochDay
    val windowEnd = if (budget.periodEndEpochDay > 0L) budget.periodEndEpochDay else activePeriodRange.endEpochDay
    val isCurrentPeriod = todayEpochDay in windowStart..windowEnd
    val totalDays = remember(windowStart, windowEnd) {
        (windowEnd - windowStart + 1).coerceAtLeast(1L)
    }
    val elapsedDays = remember(todayEpochDay, windowStart, totalDays) {
        (todayEpochDay - windowStart + 1).coerceIn(1L, totalDays)
    }
    val elapsedMonthFraction = if (isCurrentPeriod) {
        (elapsedDays.toFloat() / totalDays.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }
    val elapsedPercent: Int? = elapsedMonthFraction?.let { (it * 100f).toInt() }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isHeroOverall) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        border = BorderStroke(
            width = if (isHeroOverall || isExceeded) 1.5.dp else 1.dp,
            color = when {
                isExceeded -> semanticColors.expenseCoral
                isHeroOverall -> MaterialTheme.colorScheme.primary
                else -> semanticColors.cardStrokeSubtle
            }
        ),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    CategoryIconBadge(
                        iconName = if (isHeroOverall) "savings" else "category",
                        colorHex = budget.colorHex,
                        size = if (isHeroOverall) 42.dp else 36.dp,
                        iconSize = if (isHeroOverall) 22.dp else 18.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = budget.displayTitle,
                            style = if (isHeroOverall) {
                                MaterialTheme.typography.titleMedium
                            } else {
                                MaterialTheme.typography.titleSmall
                            },
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val periodSubtitle = when {
                            budget.periodType == com.folioledger.app.domain.model.BudgetPeriodType.CUSTOM && budget.periodLabel.isNotBlank() -> {
                                "Custom · ${budget.periodLabel}"
                            }
                            budget.periodLabel.isNotBlank() -> "${budget.periodType.displayLabel} · ${budget.periodLabel}"
                            else -> "${budget.periodType.displayLabel} · Alert at ${budget.alertThresholdPercent}%"
                        }
                        Text(
                            text = periodSubtitle,
                            style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val isCustom = budget.periodType == com.folioledger.app.domain.model.BudgetPeriodType.CUSTOM
                val badgeContainerColor = when {
                    isCustom && budget.windowStatus == BudgetWindowStatus.UPCOMING -> MaterialTheme.colorScheme.secondaryContainer
                    isCustom && budget.windowStatus == BudgetWindowStatus.COMPLETED && !isExceeded -> MaterialTheme.colorScheme.surfaceVariant
                    else -> statusContainerColor
                }
                val onBadgeContainerColor = when {
                    isCustom && budget.windowStatus == BudgetWindowStatus.UPCOMING -> MaterialTheme.colorScheme.onSecondaryContainer
                    isCustom && budget.windowStatus == BudgetWindowStatus.COMPLETED && !isExceeded -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> onStatusContainerColor
                }
                val showBadgeWarning = when {
                    isCustom && budget.windowStatus == BudgetWindowStatus.UPCOMING -> false
                    isCustom && budget.windowStatus == BudgetWindowStatus.COMPLETED -> isExceeded
                    else -> isExceeded || isNearAlert
                }
                val badgeText = when {
                    isCustom && budget.windowStatus == BudgetWindowStatus.UPCOMING -> "Upcoming"
                    isCustom && budget.windowStatus == BudgetWindowStatus.COMPLETED -> if (isExceeded) "Completed · Over ($pctBadgeText)" else "Completed"
                    isCustom && budget.windowStatus == BudgetWindowStatus.ACTIVE -> {
                        val dayPrefix = if (isCurrentPeriod) "Active · Day $elapsedDays of $totalDays" else "Active"
                        when {
                            isExceeded -> "$dayPrefix (Over $pctBadgeText)"
                            isNearAlert -> "$dayPrefix (Near Limit $pctBadgeText)"
                            else -> "$dayPrefix ($pctBadgeText)"
                        }
                    }
                    isExceeded -> "OVERSPENT ($pctBadgeText)"
                    isNearAlert -> "NEAR LIMIT ($pctBadgeText)"
                    else -> "$pctBadgeText USED"
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = badgeContainerColor
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            if (showBadgeWarning) {
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = onBadgeContainerColor,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = badgeText,
                                style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                                fontWeight = FontWeight.Bold,
                                color = onBadgeContainerColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit Budget",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            BudgetTrackWithPacingTick(
                progressFraction = { animatedFraction.value },
                elapsedFraction = elapsedMonthFraction,
                alertThresholdPercent = budget.alertThresholdPercent,
                statusColor = statusColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                isHeroOverall = isHeroOverall
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = "Spent",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MoneyText(
                        money = budget.spentAmount,
                        currencySpec = currencySpec,
                        grouping = grouping,
                        colorMode = MoneyColorMode.EXPENSE,
                        style = if (currencySpec.symbol.length > 2) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Budget Limit",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MoneyText(
                        money = budget.limitAmount,
                        currencySpec = currencySpec,
                        grouping = grouping,
                        colorMode = MoneyColorMode.NEUTRAL,
                        style = if (currencySpec.symbol.length > 2) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (budget.rolloverEnabled && budget.rolloverAmount.amountMinor != 0L) {
                        val baseFormatted = MoneyFormatterAndCalculator.formatMinorMoney(budget.baseLimitAmount, currencySpec, grouping)
                        Text(
                            text = "Base: $baseFormatted",
                            style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (budget.remainingAmount.isNegative) "Over Budget" else "Remaining",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MoneyText(
                        money = budget.remainingAmount,
                        currencySpec = currencySpec,
                        grouping = grouping,
                        colorMode = MoneyColorMode.AUTO_SIGN,
                        style = if (currencySpec.symbol.length > 2) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (budget.rolloverEnabled && budget.rolloverAmount.amountMinor != 0L) {
                val isSurplus = budget.rolloverAmount.amountMinor > 0L
                val rollBg = if (isSurplus) semanticColors.incomeEmeraldContainer else semanticColors.expenseCoralContainer
                val rollFg = if (isSurplus) semanticColors.onIncomeEmeraldContainer else semanticColors.onExpenseCoralContainer
                val rolloverFormatted = MoneyFormatterAndCalculator.formatMinorMoney(
                    budget.rolloverAmount, currencySpec, grouping, showPlusSignForPositive = true
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = rollBg
                ) {
                    Text(
                        text = if (isSurplus) "$rolloverFormatted rolled over" else "$rolloverFormatted prior deficit",
                        style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                        fontWeight = FontWeight.SemiBold,
                        color = rollFg,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            if (isCurrentPeriod) {
                HorizontalDivider(color = semanticColors.cardStrokeSubtle)

                val remainingDays = remember(totalDays, elapsedDays) {
                    (totalDays - elapsedDays + 1).coerceAtLeast(1L)
                }
                val monthElapsedRatio = remember(totalDays, elapsedDays) {
                    (elapsedDays.toFloat() / totalDays.toFloat()).coerceAtLeast(0.01f)
                }
                val velocityRatio = (budget.usagePercentage / 100f) / monthElapsedRatio
                val dailySafeMinor = if (!isExceeded && budget.remainingAmount.isPositive) {
                    budget.remainingAmount.amountMinor / remainingDays
                } else null

                val (pacingText, pacingColor) = when {
                    isExceeded -> "Limit exceeded" to semanticColors.expenseCoral
                    velocityRatio > 1.2f -> "Pacing ${String.format(Locale.US, "%.1f", velocityRatio)}× faster" to semanticColors.warningAmber
                    velocityRatio < 0.85f -> "Pacing ${String.format(Locale.US, "%.0f", (1f - velocityRatio) * 100f)}% under" to semanticColors.incomeEmerald
                    else -> "On track" to MaterialTheme.colorScheme.onSurfaceVariant
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = pacingColor,
                            modifier = Modifier.size(6.dp)
                        ) {}
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Day $elapsedDays of $totalDays ($elapsedPercent%) · $pacingText",
                            style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                            fontWeight = FontWeight.SemiBold,
                            color = pacingColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (dailySafeMinor != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Safe daily: ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            MoneyText(
                                money = MinorMoney(dailySafeMinor),
                                currencySpec = currencySpec,
                                grouping = grouping,
                                colorMode = MoneyColorMode.INCOME,
                                style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
