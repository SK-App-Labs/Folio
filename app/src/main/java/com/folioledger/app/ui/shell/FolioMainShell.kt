/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.shell

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import com.folioledger.app.ui.shell.components.DemoModeActiveTopBanner
import com.folioledger.app.ui.shell.sheets.WelcomeExpressBottomSheet
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.folioledger.app.R
import com.folioledger.app.FolioNotificationAction
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.ui.accounts.AccountsScreen
import com.folioledger.app.ui.accounts.AccountsViewModel
import com.folioledger.app.ui.entry.TransactionEntrySheet
import com.folioledger.app.ui.entry.TransactionEntryViewModel
import com.folioledger.app.ui.ledger.LedgerHomeScreen
import com.folioledger.app.ui.ledger.LedgerHomeViewModel
import com.folioledger.app.ui.settings.SettingsScreen
import com.folioledger.app.ui.settings.SettingsViewModel
import com.folioledger.app.ui.stats.StatsAndBudgetsScreen
import com.folioledger.app.ui.stats.StatsAndBudgetsViewModel

enum class FolioMainTab(
    val label: String,
    val icon: ImageVector,
    @param:StringRes val labelRes: Int
) {
    TRANSACTIONS("Trans.", Icons.AutoMirrored.Filled.ReceiptLong, R.string.nav_tab_transactions),
    STATS("Stats", Icons.Default.PieChart, R.string.nav_tab_stats),
    ACCOUNTS("Accounts", Icons.Default.AccountBalanceWallet, R.string.nav_tab_accounts),
    SETTINGS("Settings", Icons.Default.Settings, R.string.nav_tab_settings)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FolioMainShell(
    viewModelFactory: ViewModelProvider.Factory,
    isLocked: Boolean = false,
    initialShortcutAction: TransactionType? = null,
    onShortcutActionConsumed: () -> Unit = {},
    initialNotificationAction: FolioNotificationAction? = null,
    onNotificationActionConsumed: () -> Unit = {},
    initialBookmarkId: Long? = null,
    onBookmarkActionConsumed: () -> Unit = {}
) {
    val ledgerViewModel: LedgerHomeViewModel = viewModel(factory = viewModelFactory)
    val accountsViewModel: AccountsViewModel = viewModel(factory = viewModelFactory)
    val entryViewModel: TransactionEntryViewModel = viewModel(factory = viewModelFactory)
    val statsViewModel: StatsAndBudgetsViewModel = viewModel(factory = viewModelFactory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = viewModelFactory)

    val userPreferences by settingsViewModel.userPreferences.collectAsStateWithLifecycle()
    val hasLoadedUserPreferences by settingsViewModel.hasLoadedUserPreferences.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableStateOf(FolioMainTab.TRANSACTIONS) }
    var isEntrySheetOpen by rememberSaveable { mutableStateOf(false) }
    var originTabBeforeAccountInspection by rememberSaveable { mutableStateOf<FolioMainTab?>(null) }

    // Keep primary ledger and account tabs warm and pre-computed while the app is in the foreground (STARTED).
    // Uses terminal .collect {} without reading Compose state, guaranteeing 0 root recompositions
    // and 0ms instant tab switching. When minimized (onStop), repeatOnLifecycle cancels these
    // collectors, allowing WhileSubscribed(5_000L) to pause background Room observers after 5 seconds.
    // Analytical Stats streams and Entry Sheet streams are only observed when their respective tab/sheet is active.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, ledgerViewModel, accountsViewModel, statsViewModel, settingsViewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            ledgerViewModel.refreshData()
            accountsViewModel.refreshData()
            statsViewModel.refreshData()
            settingsViewModel.refreshData()
            launch { ledgerViewModel.uiState.collect {} }
            launch { accountsViewModel.uiState.collect {} }
        }
    }

    val context = LocalContext.current

    LaunchedEffect(initialShortcutAction) {
        if (initialShortcutAction != null) {
            selectedTab = FolioMainTab.TRANSACTIONS
            entryViewModel.resetForNewTransaction(defaultMode = initialShortcutAction)
            isEntrySheetOpen = true
            onShortcutActionConsumed()
        }
    }

    LaunchedEffect(initialBookmarkId) {
        if (initialBookmarkId != null) {
            selectedTab = FolioMainTab.TRANSACTIONS
            entryViewModel.applyBookmarkById(initialBookmarkId)
            isEntrySheetOpen = true
            onBookmarkActionConsumed()
        }
    }

    LaunchedEffect(initialNotificationAction) {
        when (val action = initialNotificationAction) {
            is FolioNotificationAction.SettleCard -> {
                isEntrySheetOpen = false
                selectedTab = FolioMainTab.ACCOUNTS
                accountsViewModel.openSettlementSheetForAccountId(action.accountId)
                onNotificationActionConsumed()
            }
            is FolioNotificationAction.LogBill -> {
                selectedTab = FolioMainTab.TRANSACTIONS
                entryViewModel.loadRecurringRuleForEntry(
                    ruleId = action.recurringRuleId,
                    expectedDueEpochDay = action.expectedDueEpochDay,
                    onReady = { isEntrySheetOpen = true },
                    onAlreadyLogged = {
                        Toast.makeText(context, "This recurring bill was already logged.", Toast.LENGTH_SHORT).show()
                    }
                )
                onNotificationActionConsumed()
            }
            null -> Unit
        }
    }

    val haptic = LocalHapticFeedback.current
    var backPressedTime by remember { mutableLongStateOf(0L) }
    var lastTransTabTapTime by remember { mutableLongStateOf(0L) }
    var lastStatsTabTapTime by remember { mutableLongStateOf(0L) }

    // 1. Back Handler for secondary tabs (STATS, ACCOUNTS, SETTINGS)
    BackHandler(enabled = selectedTab != FolioMainTab.TRANSACTIONS && !isEntrySheetOpen) {
        if (selectedTab == FolioMainTab.ACCOUNTS && accountsViewModel.uiState.value.inspectedAccount != null) {
            // First collapse inspected account detail view
            accountsViewModel.selectAccountForInspection(null)
            originTabBeforeAccountInspection?.let { origin ->
                selectedTab = origin
                originTabBeforeAccountInspection = null
            }
        } else {
            // Return to primary TRANSACTIONS tab
            selectedTab = FolioMainTab.TRANSACTIONS
            originTabBeforeAccountInspection = null
        }
    }

    // 2. Double-Tap Back to Exit Protection on Primary TRANSACTIONS tab
    BackHandler(enabled = selectedTab == FolioMainTab.TRANSACTIONS && !isEntrySheetOpen) {
        val ledgerState = ledgerViewModel.uiState.value
        if (ledgerState.isSearchBarVisible || ledgerState.isFilterActive) {
            // Clear search/filter if active
            ledgerViewModel.clearAllFilters()
        } else {
            val now = System.currentTimeMillis()
            if (now - backPressedTime < 2000L) {
                context.findActivity()?.finish()
            } else {
                backPressedTime = now
                Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                NavigationBar {
                    FolioMainTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = {
                                val now = System.currentTimeMillis()
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                originTabBeforeAccountInspection = null
                                if (selectedTab == FolioMainTab.ACCOUNTS && tab == FolioMainTab.ACCOUNTS) {
                                    // Tapping Accounts tab icon again returns to main accounts list
                                    accountsViewModel.selectAccountForInspection(null)
                                }
                                if (tab == FolioMainTab.TRANSACTIONS) {
                                    if (selectedTab == FolioMainTab.TRANSACTIONS) {
                                        if (now - lastTransTabTapTime < 500L) {
                                            // Double-tap on active Transactions tab: switch to Daily subtab and jump to today
                                            ledgerViewModel.resetToDailySubTabAndJumpToToday()
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            lastTransTabTapTime = 0L
                                        } else {
                                            // Single tap on active Transactions tab: jump to today within current subtab
                                            lastTransTabTapTime = now
                                            ledgerViewModel.jumpToToday()
                                        }
                                    } else {
                                        // Switched from another main tab
                                        lastTransTabTapTime = now
                                    }
                                }
                                if (tab == FolioMainTab.STATS) {
                                    if (selectedTab == FolioMainTab.STATS) {
                                        if (now - lastStatsTabTapTime < 500L) {
                                            // Double-tap on active Stats tab: switch to Overview subtab and jump to today
                                            statsViewModel.resetToOverviewSubTabAndJumpToToday()
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            lastStatsTabTapTime = 0L
                                        } else {
                                            // Single tap on active Stats tab: jump to today within current subtab
                                            lastStatsTabTapTime = now
                                            statsViewModel.jumpToToday()
                                        }
                                    } else {
                                        // Switched from another main tab
                                        lastStatsTabTapTime = now
                                    }
                                }
                                selectedTab = tab
                            },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = stringResource(tab.labelRes)
                                )
                            },
                            label = { Text(text = stringResource(tab.labelRes)) }
                        )
                    }
                }
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = selectedTab == FolioMainTab.TRANSACTIONS,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut()
                ) {
                    FloatingActionButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            entryViewModel.resetForNewTransaction(
                                preselectedAccountId = ledgerViewModel.uiState.value.scopedAccountId,
                                initialEpochDay = null
                            )
                            isEntrySheetOpen = true
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.cd_add_transaction_fab)
                        )
                    }
                }
            }
        ) { innerPadding ->
            val screenContentPadding = PaddingValues(
                bottom = innerPadding.calculateBottomPadding()
            )
            val saveableStateHolder = rememberSaveableStateHolder()

            Column(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = userPreferences.isDemoModeActive,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    DemoModeActiveTopBanner(
                        hasStashedRealLedger = userPreferences.hasStashedRealLedger,
                        onRestoreRealLedger = { settingsViewModel.exitDemoMode() },
                        onStartFreshLedger = { settingsViewModel.exitDemoMode() }
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (userPreferences.isDemoModeActive) {
                                Modifier
                                    .consumeWindowInsets(WindowInsets.statusBars)
                                    .consumeWindowInsets(WindowInsets.systemBars.only(WindowInsetsSides.Top))
                            } else {
                                Modifier
                            }
                        )
                ) {
                    Crossfade(
                        targetState = selectedTab,
                        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                        label = "MainTabCrossfade"
                    ) { targetTab ->
                saveableStateHolder.SaveableStateProvider(key = targetTab) {
                    when (targetTab) {
                        FolioMainTab.TRANSACTIONS -> {
                            LedgerHomeScreen(
                                viewModel = ledgerViewModel,
                                contentPadding = screenContentPadding,
                                onOpenNewTransactionForDate = { epochDay ->
                                    entryViewModel.resetForNewTransaction(
                                        preselectedAccountId = ledgerViewModel.uiState.value.scopedAccountId,
                                        initialEpochDay = epochDay
                                    )
                                    isEntrySheetOpen = true
                                },
                                onEditTransaction = { transaction ->
                                    if (entryViewModel.loadTransactionItemForEdit(transaction)) {
                                        isEntrySheetOpen = true
                                    }
                                },
                                onSelectBookmarkTemplate = { bookmark ->
                                    entryViewModel.resetForNewTransaction(
                                        preselectedAccountId = ledgerViewModel.uiState.value.scopedAccountId,
                                        initialEpochDay = null
                                    )
                                    entryViewModel.applyBookmark(bookmark)
                                    isEntrySheetOpen = true
                                },
                                onExploreDemoClick = {
                                    settingsViewModel.enterDemoMode()
                                }
                            )
                        }

                        FolioMainTab.STATS -> {
                            StatsAndBudgetsScreen(
                                viewModel = statsViewModel,
                                contentPadding = screenContentPadding,
                                onInspectTransaction = { transaction ->
                                    if (entryViewModel.loadTransactionItemForEdit(transaction)) {
                                        isEntrySheetOpen = true
                                    }
                                },
                                onAddTransactionForAccount = { account ->
                                    entryViewModel.resetForNewTransaction(
                                        preselectedAccountId = account.id,
                                        initialEpochDay = null
                                    )
                                    isEntrySheetOpen = true
                                }
                            )
                        }

                        FolioMainTab.ACCOUNTS -> {
                            AccountsScreen(
                                viewModel = accountsViewModel,
                                contentPadding = screenContentPadding,
                                onAccountClick = { account ->
                                    originTabBeforeAccountInspection = null
                                    accountsViewModel.selectAccountForInspection(account.id)
                                },
                                onViewAccountInLedger = { account ->
                                    accountsViewModel.selectAccountForInspection(null)
                                    originTabBeforeAccountInspection = null
                                    ledgerViewModel.setScopedAccount(account.id)
                                    selectedTab = FolioMainTab.TRANSACTIONS
                                },
                                onAddTransactionForAccount = { account ->
                                    entryViewModel.resetForNewTransaction(
                                        preselectedAccountId = account.id,
                                        initialEpochDay = null
                                    )
                                    isEntrySheetOpen = true
                                },
                                onEditTransaction = { transaction ->
                                    if (entryViewModel.loadTransactionItemForEdit(transaction)) {
                                        isEntrySheetOpen = true
                                    }
                                },
                                onPassbookDismissed = {
                                    originTabBeforeAccountInspection?.let { origin ->
                                        selectedTab = origin
                                        originTabBeforeAccountInspection = null
                                    }
                                }
                            )
                        }

                        FolioMainTab.SETTINGS -> {
                            SettingsScreen(
                                viewModel = settingsViewModel,
                                contentPadding = screenContentPadding
                            )
                        }
                    }
                }
            }
        }
    }

    if (isEntrySheetOpen && !isLocked) {
        TransactionEntrySheet(
            viewModel = entryViewModel,
            onDismiss = { isEntrySheetOpen = false },
            onTransactionSaved = { isEntrySheetOpen = false }
        )
    }

    if (!isLocked && hasLoadedUserPreferences && !userPreferences.hasSeenWelcomeSheet) {
        WelcomeExpressBottomSheet(
            initialCurrencyCode = userPreferences.primaryCurrencyCode,
            onExploreWithDemo = { currency, grouping ->
                settingsViewModel.enterDemoMode(currency, grouping)
            },
            onStartFresh = { currency, grouping ->
                settingsViewModel.completeOnboardingFresh(currency, grouping)
            },
            onDismissRequest = {
                settingsViewModel.dismissWelcomeSheet()
            }
        )
    }
}
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
