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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folioledger.app.domain.model.TransactionBookmark
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.ui.components.PeriodSelectorHeader
import com.folioledger.app.ui.ledger.subtabs.*
import com.folioledger.app.ui.theme.LocalFolioSemanticColors
import kotlinx.coroutines.flow.first

@Composable
fun LedgerHomeScreen(
    viewModel: LedgerHomeViewModel,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    fabScrollConnection: NestedScrollConnection? = null,
    onResetFabVisibility: () -> Unit = {},
    onOpenNewTransactionForDate: (epochDay: Long) -> Unit = {},
    onEditTransaction: (TransactionItem) -> Unit = {},
    onSelectBookmarkTemplate: (TransactionBookmark) -> Unit = {},
    onExploreDemoClick: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showBookmarksQuickDialog by rememberSaveable { mutableStateOf(false) }

    val pagerState = rememberPagerState(initialPage = uiState.selectedSubTab.ordinal) { LedgerSubTab.entries.size }

    // Sync ViewModel's selectedSubTab ONLY when user swipe gesture settles
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settledPage ->
            val targetTab = LedgerSubTab.entries[settledPage]
            if (uiState.selectedSubTab != targetTab) {
                viewModel.selectSubTab(targetTab)
            }
        }
    }

    // Sync pagerState when ViewModel's selectedSubTab changes (Tab click or external drill-down)
    LaunchedEffect(uiState.selectedSubTab) {
        if (pagerState.isScrollInProgress) {
            snapshotFlow { pagerState.isScrollInProgress }.first { !it }
        }
        if (pagerState.currentPage != uiState.selectedSubTab.ordinal) {
            pagerState.animateScrollToPage(uiState.selectedSubTab.ordinal)
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbarMessage()
        }
    }

    LaunchedEffect(
        uiState.selectedSubTab,
        uiState.activePeriodRange,
        uiState.isFilterActive,
        uiState.scopedAccountId,
        uiState.scrollToTopSignal,
    ) {
        onResetFabVisibility()
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = contentPadding.calculateTopPadding() + scaffoldPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding(),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (fabScrollConnection != null) {
                            Modifier.nestedScroll(fabScrollConnection)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                val isMonthlyTab = !uiState.isFilterActive && uiState.selectedSubTab == LedgerSubTab.MONTHLY
                val headerTitle = if (uiState.isFilterActive && uiState.filterAllPeriods) {
                    "All Dates (Lifetime)"
                } else if (isMonthlyTab) {
                    "Year ${uiState.activePeriodRange.anchorYear}"
                } else {
                    uiState.activePeriodRange.title
                }

                val todayInDisplayedRange = if (isMonthlyTab) {
                    val yearStart = uiState.yearlyMonthItems.firstOrNull()?.periodRange?.startEpochDay
                        ?: uiState.activePeriodRange.startEpochDay
                    val yearEnd = uiState.yearlyMonthItems.lastOrNull()?.periodRange?.endEpochDay
                        ?: uiState.activePeriodRange.endEpochDay
                    uiState.todayEpochDay in yearStart..yearEnd
                } else {
                    uiState.todayEpochDay in uiState.activePeriodRange.startEpochDay..uiState.activePeriodRange.endEpochDay
                }
                val isNotToday = uiState.isInitialDataLoaded && !todayInDisplayedRange

                PeriodSelectorHeader(
                    periodTitle = headerTitle,
                    periodSubtitle = null,
                    onPreviousPeriod = viewModel::navigateToPreviousPeriod,
                    onNextPeriod = viewModel::navigateToNextPeriod,
                    onPeriodTitleClick = viewModel::jumpToToday,
                    showTodayBadge = isNotToday && !(uiState.isFilterActive && uiState.filterAllPeriods),
                    onTodayClick = viewModel::jumpToToday,
                    navigationEnabled = !(uiState.isFilterActive && uiState.filterAllPeriods),
                    onBookmarksClick = { showBookmarksQuickDialog = true },
                    onFilterClick = { viewModel.setFilterSheetOpen(true) },
                    activeFilterCount = uiState.activeFilterCount,
                    onSearchClick = viewModel::toggleSearchBar,
                )

                // Prominent Scoped Account Filter Banner when an account is selected
                val scopedAccount = uiState.scopedAccount
                AnimatedVisibility(
                    visible = scopedAccount != null,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    if (scopedAccount != null) {
                        SelectedAccountScopeBanner(
                            account = scopedAccount,
                            grouping = uiState.numberGrouping,
                            onClearScopedAccount = { viewModel.setScopedAccount(null) },
                        )
                    }
                }

                AnimatedVisibility(
                    visible = uiState.isSearchBarVisible || uiState.isFilterActive,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    SearchAndActiveFilterBanner(
                        queryText = uiState.searchFilter.queryText,
                        showSearchInput = uiState.isSearchBarVisible || uiState.searchFilter.queryText.isNotBlank(),
                        activeFilterCount = uiState.activeFilterCount,
                        isFilterActive = uiState.isFilterActive,
                        filter = uiState.searchFilter,
                        accounts = uiState.allAccounts,
                        categories = uiState.availableCategories,
                        currencySpec = uiState.amountFilterCurrencySpec,
                        grouping = uiState.numberGrouping,
                        onQueryChange = viewModel::updateSearchQuery,
                        onOpenFilterSheet = { viewModel.setFilterSheetOpen(true) },
                        onClearAllFilters = viewModel::clearAllFilters,
                        onRemoveCategoryFilter = viewModel::toggleFilterCategoryId,
                        onRemoveAccountFilter = viewModel::toggleFilterAccountId,
                        onRemoveTypeFilter = viewModel::toggleFilterTransactionType,
                        onRemoveTagFilter = viewModel::toggleFilterTag,
                        onRemoveTransfersFilter = viewModel::toggleFilterTransfers,
                        onRemoveAmountBounds = { viewModel.updateFilterAmountBounds("", "") },
                    )
                }

                if (!uiState.isFilterActive) {
                    PrimaryScrollableTabRow(
                        selectedTabIndex = pagerState.currentPage,
                        edgePadding = 12.dp,
                        containerColor = MaterialTheme.colorScheme.surface,
                        divider = {},
                    ) {
                        LedgerSubTab.entries.forEach { tab ->
                            val isSelected = pagerState.currentPage == tab.ordinal
                            Tab(
                                selected = isSelected,
                                onClick = {
                                    viewModel.selectSubTab(tab)
                                },
                                text = {
                                    Text(
                                        text = stringResource(tab.titleRes),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    )
                                },
                            )
                        }
                    }
                }

                HorizontalDivider(color = LocalFolioSemanticColors.current.cardStrokeSubtle)

                if (uiState.isFilterActive) {
                    FilteredSearchResultsContent(
                        uiState = uiState,
                        onTransactionClick = viewModel::inspectTransaction,
                        onToggleFilterAllPeriods = viewModel::setFilterAllPeriods,
                        onClearFilters = viewModel::clearAllFilters,
                    )
                } else {
                    HorizontalPager(
                        state = pagerState,
                        beyondViewportPageCount = 0,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        when (LedgerSubTab.entries[page]) {
                            LedgerSubTab.DAILY -> {
                                DailyLedgerSubTabContent(
                                    uiState = uiState,
                                    onTransactionClick = viewModel::inspectTransaction,
                                    onAddTransactionClick = {
                                        onOpenNewTransactionForDate(uiState.referenceDateEpochDay)
                                    },
                                    onExploreDemoClick = onExploreDemoClick,
                                )
                            }

                            LedgerSubTab.CALENDAR -> {
                                CalendarLedgerSubTabContent(
                                    uiState = uiState,
                                    onSelectDay = viewModel::selectCalendarDay,
                                    onAddOnDate = onOpenNewTransactionForDate,
                                    onTransactionClick = viewModel::inspectTransaction,
                                )
                            }

                            LedgerSubTab.WEEKLY -> {
                                WeeklyLedgerSubTabContent(
                                    uiState = uiState,
                                    onToggleWeek = viewModel::toggleWeekExpanded,
                                    onTransactionClick = viewModel::inspectTransaction,
                                )
                            }

                            LedgerSubTab.MONTHLY -> {
                                MonthlyLedgerSubTabContent(
                                    uiState = uiState,
                                    onToggleMonth = viewModel::toggleYearlyMonthExpanded,
                                    onDrillDownToMonth = viewModel::drillDownToMonth,
                                    onDrillDownToWeek = viewModel::drillDownToWeek,
                                )
                            }

                            LedgerSubTab.SUMMARY -> {
                                SummaryLedgerSubTabContent(
                                    uiState = uiState,
                                    onExportCsvClick = {
                                        val sanitizeSegment: (String) -> String = { raw ->
                                            raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
                                                .replace(Regex("_+"), "_")
                                                .trim('_', '.')
                                                .take(40)
                                                .ifEmpty { "Export" }
                                        }
                                        val sanitizedTitle = sanitizeSegment(uiState.activePeriodRange.title)
                                        val scoped = uiState.scopedAccount
                                        val fileName = if (scoped != null) {
                                            val sanitizedAccount = sanitizeSegment(scoped.name)
                                            "Folio_${sanitizedAccount}_$sanitizedTitle.csv"
                                        } else {
                                            "Folio_$sanitizedTitle.csv"
                                        }
                                        viewModel.exportActivePeriodToCsv(fileName)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (!com.folioledger.app.ui.theme.LocalIsAppLocked.current) {
        val inspected = uiState.inspectedTransaction
        if (inspected != null) {
            TransactionDetailActionSheet(
                transaction = inspected,
                currencySpec = uiState.currencySpec,
                grouping = uiState.numberGrouping,
                onEdit = {
                    viewModel.inspectTransaction(null)
                    onEditTransaction(inspected)
                },
                onDelete = {
                    viewModel.deleteTransaction(inspected.id)
                },
                onDismiss = { viewModel.inspectTransaction(null) },
            )
        }

        if (uiState.isFilterSheetOpen) {
            ReinforcedSearchFilterBottomSheet(
                uiState = uiState,
                onQueryChange = viewModel::updateSearchQuery,
                onToggleType = viewModel::toggleFilterTransactionType,
                onToggleAccount = viewModel::toggleFilterAccountId,
                onToggleCategory = viewModel::toggleFilterCategoryId,
                onToggleTag = viewModel::toggleFilterTag,
                onToggleTransfers = viewModel::toggleFilterTransfers,
                onUpdateAmountBounds = viewModel::updateFilterAmountBounds,
                onFilterAllPeriodsChange = viewModel::setFilterAllPeriods,
                onClearAll = viewModel::clearAllFilters,
                onDismiss = { viewModel.setFilterSheetOpen(false) },
            )
        }

        if (showBookmarksQuickDialog) {
            QuickBookmarksLauncherDialog(
                bookmarks = uiState.bookmarks,
                accounts = uiState.allAccounts,
                currencySpec = uiState.currencySpec,
                grouping = uiState.numberGrouping,
                onSelectBookmark = { bm ->
                    showBookmarksQuickDialog = false
                    onSelectBookmarkTemplate(bm)
                },
                onDismiss = { showBookmarksQuickDialog = false },
            )
        }
    }
}
