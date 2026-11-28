/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.ledger

import androidx.compose.runtime.Composable
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.TransactionBookmark
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.ui.ledger.dialogs.QuickBookmarksLauncherDialog as ExtractedQuickBookmarksLauncherDialog
import com.folioledger.app.ui.ledger.sheets.ReinforcedSearchFilterBottomSheet as ExtractedReinforcedSearchFilterBottomSheet
import com.folioledger.app.ui.ledger.sheets.TransactionDetailActionSheet as ExtractedTransactionDetailActionSheet

typealias LedgerFilterSheetTab = com.folioledger.app.ui.ledger.sheets.LedgerFilterSheetTab

@Composable
internal fun TransactionDetailActionSheet(
    transaction: TransactionItem,
    currencySpec: CurrencySpec,
    grouping: NumberGroupingSystem,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ExtractedTransactionDetailActionSheet(
        transaction = transaction,
        currencySpec = currencySpec,
        grouping = grouping,
        onEdit = onEdit,
        onDelete = onDelete,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun ReinforcedSearchFilterBottomSheet(
    uiState: LedgerHomeUiState,
    onQueryChange: (String) -> Unit,
    onToggleType: (TransactionType) -> Unit,
    onToggleAccount: (Long) -> Unit,
    onToggleCategory: (Long) -> Unit,
    onToggleTag: (String) -> Unit,
    onToggleTransfers: () -> Unit,
    onUpdateAmountBounds: (String, String) -> Unit,
    onFilterAllPeriodsChange: (Boolean) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    ExtractedReinforcedSearchFilterBottomSheet(
        uiState = uiState,
        onQueryChange = onQueryChange,
        onToggleType = onToggleType,
        onToggleAccount = onToggleAccount,
        onToggleCategory = onToggleCategory,
        onToggleTag = onToggleTag,
        onToggleTransfers = onToggleTransfers,
        onUpdateAmountBounds = onUpdateAmountBounds,
        onFilterAllPeriodsChange = onFilterAllPeriodsChange,
        onClearAll = onClearAll,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun QuickBookmarksLauncherDialog(
    bookmarks: List<TransactionBookmark>,
    currencySpec: CurrencySpec,
    grouping: NumberGroupingSystem,
    onSelectBookmark: (TransactionBookmark) -> Unit,
    onDismiss: () -> Unit,
    accounts: List<AccountSummary> = emptyList(),
) {
    ExtractedQuickBookmarksLauncherDialog(
        bookmarks = bookmarks,
        currencySpec = currencySpec,
        grouping = grouping,
        onSelectBookmark = onSelectBookmark,
        onDismiss = onDismiss,
        accounts = accounts,
    )
}
