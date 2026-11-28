/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.BudgetPeriodType
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.ui.components.FolioChromeIcons
import com.folioledger.app.ui.components.PeriodSelectorHeader
import com.folioledger.app.ui.stats.dialogs.CustomDateRangePickerDialog
import com.folioledger.app.ui.stats.dialogs.MonthlySavingsTargetDialog
import com.folioledger.app.ui.stats.sheets.BudgetEditorBottomSheet
import com.folioledger.app.ui.stats.sheets.CategoryDrillDownBottomSheet
import com.folioledger.app.ui.stats.sheets.PayeeDrillDownBottomSheet
import com.folioledger.app.ui.stats.sheets.SavingsGoalEditorBottomSheet
import com.folioledger.app.ui.stats.sheets.TagProjectDrillDownBottomSheet
import com.folioledger.app.ui.stats.subtabs.BudgetsManagerSubTabContent
import com.folioledger.app.ui.stats.subtabs.StatsBreakdownSubTabContent
import com.folioledger.app.ui.stats.subtabs.TagsAndNotesRankingsSubTabContent
import com.folioledger.app.ui.theme.LocalFolioSemanticColors
import kotlinx.coroutines.launch

@Composable
fun StatsAndBudgetsScreen(
    viewModel: StatsAndBudgetsViewModel,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onInspectTransaction: (TransactionItem) -> Unit = {},
    onAddTransactionForAccount: ((AccountSummary) -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = uiState.selectedSubTab.ordinal) { StatsSubTab.entries.size }

    // Sync ViewModel's selectedSubTab ONLY when user swipe gesture settles
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settledPage ->
            val targetTab = StatsSubTab.entries[settledPage]
            if (uiState.selectedSubTab != targetTab) {
                viewModel.selectSubTab(targetTab)
            }
        }
    }

    // Sync pagerState when ViewModel's selectedSubTab changes externally (e.g. Tab click)
    LaunchedEffect(uiState.selectedSubTab) {
        if (pagerState.currentPage != uiState.selectedSubTab.ordinal && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(uiState.selectedSubTab.ordinal)
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val message = uiState.snackbarMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearSnackbarMessage()
        }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = contentPadding.calculateTopPadding() + scaffoldPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding(),
                ),
        ) {
            val todayEpochDay = uiState.todayEpochDay
            val isPeriodLoaded = uiState.activePeriodRange.endEpochDay > 0L
            val isCurrentPeriod = remember(todayEpochDay, uiState.activePeriodRange) {
                todayEpochDay in uiState.activePeriodRange.startEpochDay..uiState.activePeriodRange.endEpochDay
            }
            val showTodayBadge = isPeriodLoaded && !isCurrentPeriod && uiState.periodGranularity != StatsPeriodGranularity.CUSTOM

            PeriodSelectorHeader(
                periodTitle = uiState.activePeriodRange.title,
                periodSubtitle = null,
                onPreviousPeriod = viewModel::navigateToPreviousPeriod,
                onNextPeriod = viewModel::navigateToNextPeriod,
                onPeriodTitleClick = {
                    if (uiState.periodGranularity == StatsPeriodGranularity.CUSTOM) {
                        viewModel.openCustomDateRangePicker()
                    } else {
                        viewModel.jumpToToday()
                    }
                },
                showTodayBadge = showTodayBadge,
                onTodayClick = viewModel::jumpToToday,
                trailingContent = {
                    if (uiState.selectedSubTab != StatsSubTab.BUDGETS) {
                        StatsPeriodGranularityDropdownChip(
                            selectedGranularity = uiState.periodGranularity,
                            onSelectGranularity = viewModel::selectPeriodGranularity,
                            onOpenCustomDatePicker = viewModel::openCustomDateRangePicker,
                        )
                    }
                },
            )

            PrimaryScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                edgePadding = 12.dp,
                containerColor = MaterialTheme.colorScheme.surface,
                divider = {},
            ) {
                StatsSubTab.entries.forEach { tab ->
                    val isSelected = pagerState.currentPage == tab.ordinal
                    Tab(
                        selected = isSelected,
                        onClick = {
                            viewModel.selectSubTab(tab)
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(tab.ordinal)
                            }
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

            HorizontalDivider(color = LocalFolioSemanticColors.current.cardStrokeSubtle)

            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 0,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (StatsSubTab.entries[page]) {
                    StatsSubTab.STATS -> {
                        StatsBreakdownSubTabContent(
                            uiState = uiState,
                            onSelectOverviewMode = viewModel::selectOverviewMode,
                            onSelectCategoryType = viewModel::selectCategoryType,
                            onDonutSliceSelected = viewModel::onDonutSliceSelected,
                            onOpenCategoryDrillDown = viewModel::openCategoryDrillDown,
                            onSelectNetWorthChartMetricFilter = viewModel::selectNetWorthChartMetricFilter,
                            onToggleRunwayIncludesInvestments = viewModel::toggleRunwayIncludesInvestments,
                        )
                    }

                    StatsSubTab.CREDIT_CARDS -> {
                        CreditCardsCockpitSubTabContent(
                            uiState = uiState,
                            onAddTransactionForAccount = onAddTransactionForAccount,
                            onSaveCardMilestone = viewModel::saveCardMilestoneConfig,
                            contentPadding = contentPadding,
                        )
                    }

                    StatsSubTab.BUDGETS -> {
                        BudgetsManagerSubTabContent(
                            uiState = uiState,
                            onOpenOverallBudgetEditor = {
                                val filter = uiState.selectedBudgetCadenceFilter
                                val targetType = filter ?: BudgetPeriodType.MONTHLY
                                val existing = uiState.overallBudgetProgressList.firstOrNull { it.periodType == targetType }
                                if (existing != null) {
                                    viewModel.openEditBudgetDialog(existing)
                                } else {
                                    viewModel.openCreateBudgetDialog(null, targetType)
                                }
                            },
                            onOpenAddCategoryBudget = viewModel::openCreateCategoryBudgetDialog,
                            onOpenCreateCustomBudget = { viewModel.openCreateBudgetDialog(null, BudgetPeriodType.CUSTOM) },
                            onOpenEditBudget = viewModel::openEditBudgetDialog,
                            onSelectBudgetCadenceFilter = viewModel::selectBudgetCadenceFilter,
                            onOpenMonthlySavingsTargetDialog = viewModel::openMonthlySavingsTargetDialog,
                            onOpenAddSavingsGoal = { viewModel.openAddSavingsGoalSheet(null) },
                            onOpenEditSavingsGoal = viewModel::openEditSavingsGoalSheet,
                        )
                    }

                    StatsSubTab.TAGS_AND_NOTES -> {
                        TagsAndNotesRankingsSubTabContent(
                            uiState = uiState,
                            onSelectRankingMode = viewModel::selectRankingMode,
                            onSelectPayeeSortMode = viewModel::selectPayeeSortMode,
                            onToggleRankingsAllTime = viewModel::toggleRankingsAllTime,
                            onPayeeClick = viewModel::openPayeeDrillDown,
                            onTagClick = viewModel::openTagDrillDown,
                        )
                    }
                }
            }
        }
    }

    if (!com.folioledger.app.ui.theme.LocalIsAppLocked.current) {
        if (uiState.isCustomDatePickerOpen) {
            CustomDateRangePickerDialog(
                initialStartEpochDay = uiState.customStartEpochDay,
                initialEndEpochDay = uiState.customEndEpochDay,
                onConfirmRange = viewModel::setCustomDateRange,
                onDismiss = { viewModel.setCustomDatePickerOpen(false) },
            )
        }

        uiState.drillDownState?.let { drillDown ->
            CategoryDrillDownBottomSheet(
                drillDownState = drillDown,
                periodTitle = uiState.activePeriodRange.title,
                currencySpec = uiState.currencySpec,
                grouping = uiState.numberGrouping,
                onConfigureBudgetForCategory = {
                    val existingBudget = drillDown.matchingBudget
                    if (existingBudget != null) {
                        viewModel.openEditBudgetDialog(existingBudget)
                    } else {
                        viewModel.openCreateBudgetDialog(drillDown.categoryStat.categoryId)
                    }
                },
                onTransactionClick = { tx ->
                    onInspectTransaction(tx)
                },
                onDismiss = viewModel::dismissCategoryDrillDown,
            )
        }

        uiState.payeeDrillDownState?.let { payeeDrillDown ->
            PayeeDrillDownBottomSheet(
                drillDownState = payeeDrillDown,
                periodTitle = uiState.activePeriodRange.title,
                currencySpec = uiState.currencySpec,
                grouping = uiState.numberGrouping,
                onTransactionClick = { tx ->
                    onInspectTransaction(tx)
                },
                onDismiss = viewModel::dismissPayeeDrillDown,
            )
        }

        uiState.tagDrillDownState?.let { tagDrillDown ->
            TagProjectDrillDownBottomSheet(
                drillDownState = tagDrillDown,
                periodTitle = uiState.activePeriodRange.title,
                isAllTime = uiState.isRankingsAllTime,
                currencySpec = uiState.currencySpec,
                grouping = uiState.numberGrouping,
                onTransactionClick = { tx ->
                    onInspectTransaction(tx)
                },
                onDismiss = viewModel::dismissTagDrillDown,
            )
        }

        val budgetEditor = uiState.budgetEditorDialogState
        if (budgetEditor != null) {
            BudgetEditorBottomSheet(
                state = budgetEditor,
                expenseCategories = uiState.expenseCategoryTree,
                currencySpec = uiState.currencySpec,
                isSaving = uiState.isSavingBudget,
                onUpdateState = viewModel::updateBudgetEditorState,
                onSave = viewModel::saveBudgetFromDialog,
                onDelete = {
                    if ((budgetEditor.isEditing) && (budgetEditor.budgetId > 0L)) {
                        viewModel.deleteBudget(budgetEditor.budgetId)
                    }
                },
                onDismiss = viewModel::dismissBudgetEditorDialog,
            )
        }

        if (uiState.isMonthlySavingsTargetDialogOpen) {
            MonthlySavingsTargetDialog(
                currentInput = uiState.monthlySavingsTargetInput,
                currencySpec = uiState.currencySpec,
                isConfigured = uiState.monthlySavingsProgress.isConfigured,
                validationError = uiState.monthlySavingsTargetValidationError,
                onClearValidationError = viewModel::clearMonthlySavingsTargetValidationError,
                onSave = viewModel::saveMonthlySavingsTarget,
                onRemove = viewModel::removeMonthlySavingsTarget,
                onDismiss = viewModel::closeMonthlySavingsTargetDialog,
            )
        }

        if (uiState.isSavingsGoalSheetOpen) {
            val existingGoalAccountIds = remember(uiState.accountSavingsGoals) {
                uiState.accountSavingsGoals.map { it.accountId }.toSet()
            }
            SavingsGoalEditorBottomSheet(
                isEditingExisting = uiState.isEditingSavingsGoal,
                selectedAccountId = uiState.editingSavingsGoalAccountId,
                title = uiState.editingSavingsGoalTitle,
                targetAmountInput = uiState.editingSavingsGoalTargetAmountInput,
                targetDateEpochDay = uiState.editingSavingsGoalTargetDateEpochDay,
                validationError = uiState.savingsGoalValidationError,
                existingGoalAccountIds = existingGoalAccountIds,
                availableAccounts = uiState.savingsGoalEligibleAccounts,
                currencySpec = uiState.currencySpec,
                onClearValidationError = viewModel::clearSavingsGoalValidationError,
                onUpdate = viewModel::updateEditingSavingsGoal,
                onSave = viewModel::saveSavingsGoal,
                onDelete = {
                    uiState.editingSavingsGoalAccountId?.let { viewModel.removeSavingsGoal(it) }
                },
                onDismiss = viewModel::closeSavingsGoalSheet,
            )
        }
    }
}

@Composable
private fun StatsPeriodGranularityDropdownChip(
    selectedGranularity: StatsPeriodGranularity,
    onSelectGranularity: (StatsPeriodGranularity) -> Unit,
    onOpenCustomDatePicker: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.padding(end = 4.dp)) {
        FilterChip(
            selected = true,
            onClick = { menuExpanded = true },
            label = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (selectedGranularity == StatsPeriodGranularity.CUSTOM) {
                        Icon(
                            imageVector = FolioChromeIcons.DateRange,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Text(
                        text = selectedGranularity.title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Icon(
                        imageVector = FolioChromeIcons.ArrowDropDown,
                        contentDescription = "Select Granularity",
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
            shape = RoundedCornerShape(10.dp),
        )

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            StatsPeriodGranularity.entries.forEach { granularity ->
                val isSelected = granularity == selectedGranularity
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (granularity == StatsPeriodGranularity.CUSTOM) {
                                Icon(
                                    imageVector = FolioChromeIcons.DateRange,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            Text(
                                text = granularity.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    },
                    onClick = {
                        menuExpanded = false
                        if (granularity == StatsPeriodGranularity.CUSTOM) {
                            onOpenCustomDatePicker()
                        } else {
                            onSelectGranularity(granularity)
                        }
                    },
                )
            }
        }
    }
}
