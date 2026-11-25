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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.ui.components.MoneyColorMode
import com.folioledger.app.ui.components.MoneyText
import com.folioledger.app.ui.components.ThreeColumnSummaryBanner
import com.folioledger.app.ui.ledger.*
import com.folioledger.app.ui.theme.LocalFolioSemanticColors
import com.folioledger.app.ui.theme.tabularMoneyStyle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val calendarSelectedDateFormatter = DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy", Locale.US)

@Composable
private fun CalendarDayCellView(
    cell: CalendarDayCell,
    isSelected: Boolean,
    onSelectDay: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val semanticColors = LocalFolioSemanticColors.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = when {
            !cell.isInActivePeriod -> Color.Transparent
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            cell.isToday -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        border = if (isSelected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        modifier = modifier
            .heightIn(min = 62.dp)
            .then(
                if (cell.epochDay != null) {
                    Modifier.clickable { onSelectDay(cell.epochDay) }
                } else {
                    Modifier
                }
            )
    ) {
        if (cell.isInActivePeriod && cell.dayNumber != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 4.dp, horizontal = 2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = cell.dayNumber.toString(),
                        style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                        fontWeight = if (isSelected || cell.isToday) {
                            FontWeight.Bold
                        } else {
                            FontWeight.Medium
                        },
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    if (cell.isToday) {
                        Spacer(modifier = Modifier.width(2.dp))
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        semanticColors.incomeEmerald
                                    }
                                )
                        )
                    }
                    if (cell.transferCount > 0 && cell.formattedCompactIncome.isNotEmpty() && cell.formattedCompactExpense.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "\u21C4",
                            style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                            fontWeight = FontWeight.Bold,
                            color = semanticColors.transferBlue
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    if (cell.formattedCompactIncome.isNotEmpty()) {
                        Text(
                            text = "+${cell.formattedCompactIncome}",
                            style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                            fontWeight = FontWeight.Bold,
                            color = semanticColors.incomeEmerald,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false
                        )
                    }
                    if (cell.formattedCompactExpense.isNotEmpty()) {
                        Text(
                            text = "-${cell.formattedCompactExpense}",
                            style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                            fontWeight = FontWeight.Bold,
                            color = semanticColors.expenseCoral,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false
                        )
                    }
                    val showTransferPill = cell.transferCount > 0 && cell.formattedCompactTransfer.isNotEmpty() &&
                        (cell.formattedCompactIncome.isEmpty() || cell.formattedCompactExpense.isEmpty())
                    if (showTransferPill) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = semanticColors.transferBlueContainer.copy(alpha = 0.85f),
                            modifier = Modifier.padding(top = 1.dp)
                        ) {
                            Text(
                                text = "\u21C4 ${cell.formattedCompactTransfer}",
                                style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                                fontWeight = FontWeight.Bold,
                                color = semanticColors.onTransferBlueContainer,
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 0.5.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CalendarLedgerSubTabContent(
    uiState: LedgerHomeUiState,
    onSelectDay: (Long) -> Unit,
    onAddOnDate: (Long) -> Unit,
    onTransactionClick: (TransactionItem) -> Unit
) {
    val semanticColors = LocalFolioSemanticColors.current
    val selectedDateFormatted = remember(uiState.selectedCalendarDayEpochDay) {
        LocalDate.ofEpochDay(uiState.selectedCalendarDayEpochDay)
            .format(calendarSelectedDateFormatter)
    }

    val weeksOfCells = remember(uiState.calendarCells) {
        uiState.calendarCells.chunked(7)
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = 32.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "calendar_summary_banner") {
            ThreeColumnSummaryBanner(
                periodSummary = uiState.periodSummary,
                currencySpec = uiState.currencySpec,
                grouping = uiState.numberGrouping,
                showCarryOver = uiState.userPreferences.carryOverEnabled && uiState.scopedAccountId == null
            )
        }

        item(key = "calendar_grid_card") {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        uiState.calendarWeekdayHeaders.forEach { header ->
                            Text(
                                text = header,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    weeksOfCells.forEach { weekRow ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            weekRow.forEachIndexed { colIdx, cell ->
                                key(cell.epochDay ?: -(colIdx + 1L)) {
                                    val isSelected = cell.epochDay != null &&
                                            cell.epochDay == uiState.selectedCalendarDayEpochDay
                                    CalendarDayCellView(
                                        cell = cell,
                                        isSelected = isSelected,
                                        onSelectDay = onSelectDay,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "calendar_selected_date_header") {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selectedDateFormatted,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "${uiState.selectedCalendarDayTransactions.size} trans",
                                style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (uiState.selectedCalendarDayIncome.isPositive) {
                                MoneyText(
                                    money = uiState.selectedCalendarDayIncome,
                                    currencySpec = uiState.currencySpec,
                                    grouping = uiState.numberGrouping,
                                    colorMode = MoneyColorMode.INCOME,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    showPlusSignForPositive = true
                                )
                            }
                            if (uiState.selectedCalendarDayExpense.isPositive) {
                                MoneyText(
                                    money = uiState.selectedCalendarDayExpense,
                                    currencySpec = uiState.currencySpec,
                                    grouping = uiState.numberGrouping,
                                    colorMode = MoneyColorMode.EXPENSE,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (uiState.selectedCalendarDayTransfer.isPositive) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "\u21C4",
                                        style = tabularMoneyStyle(MaterialTheme.typography.labelSmall),
                                        fontWeight = FontWeight.Bold,
                                        color = semanticColors.transferBlue
                                    )
                                    MoneyText(
                                        money = uiState.selectedCalendarDayTransfer,
                                        currencySpec = uiState.currencySpec,
                                        grouping = uiState.numberGrouping,
                                        colorMode = MoneyColorMode.TRANSFER,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    FilledTonalButton(
                        onClick = { onAddOnDate(uiState.selectedCalendarDayEpochDay) },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Add",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (uiState.selectedCalendarDayTransactions.isEmpty()) {
            item(key = "calendar_empty_day_tx") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No transactions recorded on this date.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }
        } else {
            item(key = "cal_day_tx_card_${uiState.selectedCalendarDayEpochDay}") {
                DailyGroupedTransactionsCard(
                    transactions = uiState.selectedCalendarDayTransactions,
                    scopedAccountId = uiState.scopedAccountId,
                    currencySpec = uiState.currencySpec,
                    grouping = uiState.numberGrouping,
                    onTransactionClick = onTransactionClick
                )
            }
        }
    }
}

