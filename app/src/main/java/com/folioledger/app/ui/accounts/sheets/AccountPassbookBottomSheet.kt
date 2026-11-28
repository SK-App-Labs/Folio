/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.accounts.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.ui.accounts.AccountPassbookUiModel
import com.folioledger.app.ui.accounts.components.CreditCardPassbookHeroCard
import com.folioledger.app.ui.accounts.components.InvestmentPassbookHeroCard
import com.folioledger.app.ui.accounts.components.PassbookDayGroupHeaderRow
import com.folioledger.app.ui.accounts.components.PassbookQuickActionsRow
import com.folioledger.app.ui.accounts.components.PassbookTopAccountHeader
import com.folioledger.app.ui.accounts.components.PassbookTransactionEntryRowCard
import com.folioledger.app.ui.accounts.components.PassbookTwoTierSummaryCard
import com.folioledger.app.ui.components.EmptyStateCard
import com.folioledger.app.ui.components.PeriodSelectorHeader
import com.folioledger.app.ui.theme.LocalFolioSemanticColors
import com.folioledger.app.ui.theme.tabularMoneyStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountPassbookBottomSheet(
    passbook: AccountPassbookUiModel,
    referenceDateEpochDay: Long,
    defaultCurrencySpec: CurrencySpec,
    grouping: NumberGroupingSystem,
    onPreviousPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    onResetToCurrentMonth: () -> Unit,
    onEditAccountClick: () -> Unit,
    onAddTransactionClick: () -> Unit,
    onSettleBillClick: () -> Unit,
    onReconcileClick: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onViewInLedgerClick: () -> Unit,
    onTransactionRowClick: (TransactionItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val semanticColors = LocalFolioSemanticColors.current
    val account = passbook.account
    val accountCurrency = remember(account.currencyCode, defaultCurrencySpec) {
        SupportedCurrencies.getByCode(account.currencyCode) ?: defaultCurrencySpec
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
        ) {
            // Top Passbook Account Header
            PassbookTopAccountHeader(
                account = account,
                passbook = passbook,
                accountCurrency = accountCurrency,
                grouping = grouping,
                onTogglePin = onTogglePin,
                onReconcileClick = onReconcileClick,
                onEditAccountClick = onEditAccountClick,
                onDismiss = onDismiss,
            )

            // Monthly Statement Period Switcher (< Prev | Month Year | Next >)
            PeriodSelectorHeader(
                periodTitle = passbook.periodTitle,
                periodSubtitle = passbook.periodSubtitle,
                onPreviousPeriod = onPreviousPeriod,
                onNextPeriod = onNextPeriod,
                onPeriodTitleClick = onResetToCurrentMonth,
                trailingContent = {
                    AssistChip(
                        onClick = onViewInLedgerClick,
                        label = {
                            Text(
                                text = "View in Ledger",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.padding(end = 8.dp),
                    )
                },
            )

            HorizontalDivider(color = semanticColors.cardStrokeSubtle)

            LazyColumn(
                contentPadding = PaddingValues(
                    start = 18.dp,
                    end = 18.dp,
                    top = 12.dp,
                    bottom = 36.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (account.groupType == AccountGroup.CREDIT_CARD) {
                    // Unified Credit Card Statement & Debt Hub Card
                    item(key = "pb_cc_hero", contentType = "passbook_hero") {
                        CreditCardPassbookHeroCard(
                            account = account,
                            passbook = passbook,
                            referenceDateEpochDay = referenceDateEpochDay,
                            currencySpec = accountCurrency,
                            grouping = grouping,
                        )
                    }

                    // Dual Primary Actions Hub (Settle Bill + Add Expense)
                    item(key = "pb_cc_actions", contentType = "passbook_actions") {
                        PassbookQuickActionsRow(
                            account = account,
                            onSettleBillClick = onSettleBillClick,
                            onAddTransactionClick = onAddTransactionClick,
                            onReconcileClick = onReconcileClick,
                            onEditAccountClick = onEditAccountClick,
                        )
                    }
                } else if (account.groupType == AccountGroup.INVESTMENT) {
                    // Investment Portfolio Valuation Hero Card
                    item(key = "pb_inv_hero", contentType = "investment_hero") {
                        InvestmentPassbookHeroCard(
                            account = account,
                            passbook = passbook,
                            currencySpec = accountCurrency,
                            grouping = grouping,
                        )
                    }

                    // Quick Actions Row for Investment Accounts (Add Transaction, Update Value, Edit)
                    item(key = "pb_inv_actions", contentType = "passbook_actions") {
                        PassbookQuickActionsRow(
                            account = account,
                            onSettleBillClick = onSettleBillClick,
                            onAddTransactionClick = onAddTransactionClick,
                            onReconcileClick = onReconcileClick,
                            onEditAccountClick = onEditAccountClick,
                        )
                    }

                    // 2-Tier Passbook Statement Summary Card
                    item(key = "pb_inv_summary", contentType = "passbook_summary") {
                        PassbookTwoTierSummaryCard(
                            passbook = passbook,
                            currencySpec = accountCurrency,
                            grouping = grouping,
                        )
                    }
                } else {
                    // Quick Actions Row for Standard Accounts
                    item(key = "pb_std_actions", contentType = "passbook_actions") {
                        PassbookQuickActionsRow(
                            account = account,
                            onSettleBillClick = onSettleBillClick,
                            onAddTransactionClick = onAddTransactionClick,
                            onReconcileClick = onReconcileClick,
                            onEditAccountClick = onEditAccountClick,
                        )
                    }

                    // 2-Tier Passbook Statement Summary Card (Opening -> Inflows -> Outflows -> Closing)
                    item(key = "pb_std_summary", contentType = "passbook_summary") {
                        PassbookTwoTierSummaryCard(
                            passbook = passbook,
                            currencySpec = accountCurrency,
                            grouping = grouping,
                        )
                    }
                }

                // Passbook Statement Entries Header
                item(key = "pb_entries_header", contentType = "passbook_header") {
                    Text(
                        text = "${passbook.periodTitle} Statement · ${passbook.totalPeriodTransactionCount} entries",
                        style = tabularMoneyStyle(MaterialTheme.typography.titleSmall),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                if (passbook.dayGroups.isEmpty()) {
                    item(key = "pb_empty_state", contentType = "passbook_empty") {
                        EmptyStateCard(
                            title = "No Activity in ${passbook.periodTitle}",
                            subtitle = "Opening Balance (${passbook.periodSubtitle}) carries forward unchanged to Closing Balance.",
                            icon = Icons.AutoMirrored.Filled.ReceiptLong,
                            actionLabel = if (account.isArchived) null else "+ Add Transaction",
                            onActionClick = if (account.isArchived) null else onAddTransactionClick,
                        )
                    }
                } else {
                    passbook.dayGroups.forEach { dayGroup ->
                        item(key = "pb_day_${dayGroup.epochDay}", contentType = "passbook_day_header") {
                            PassbookDayGroupHeaderRow(
                                dayGroup = dayGroup,
                                currencySpec = accountCurrency,
                                grouping = grouping,
                            )
                        }
                        itemsIndexed(
                            items = dayGroup.entries,
                            key = { idx, entry ->
                                if (entry.transaction.id != 0L) {
                                    "pb_entry_${entry.transaction.id}"
                                } else {
                                    "pb_entry_syn_${dayGroup.epochDay}_$idx"
                                }
                            },
                            contentType = { _, _ -> "passbook_entry" },
                        ) { _, entry ->
                            PassbookTransactionEntryRowCard(
                                entry = entry,
                                currencySpec = accountCurrency,
                                grouping = grouping,
                                onClick = { onTransactionRowClick(entry.transaction) },
                            )
                        }
                    }
                }
            }
        }
    }
}
