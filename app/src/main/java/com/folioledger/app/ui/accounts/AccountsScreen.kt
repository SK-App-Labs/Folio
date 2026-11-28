/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folioledger.app.data.notification.FolioNotificationManager
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.ui.accounts.components.AccountGroupSectionCard
import com.folioledger.app.ui.accounts.dialogs.ReconcileBalanceDialog
import com.folioledger.app.ui.accounts.sheets.AccountEditorBottomSheet
import com.folioledger.app.ui.accounts.sheets.AccountPassbookBottomSheet
import com.folioledger.app.ui.accounts.sheets.CreditCardSettlementBottomSheet
import com.folioledger.app.ui.components.EmptyStateCard
import com.folioledger.app.ui.components.FolioChromeIcons
import com.folioledger.app.ui.components.ThreeColumnSummaryBanner
import com.folioledger.app.ui.theme.LocalFolioSemanticColors

@Composable
fun AccountsScreen(
    viewModel: AccountsViewModel,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onAccountClick: ((AccountSummary) -> Unit)? = null,
    onViewAccountInLedger: ((AccountSummary) -> Unit)? = null,
    onAddTransactionForAccount: (AccountSummary) -> Unit = {},
    onEditTransaction: (TransactionItem) -> Unit = {},
    onPassbookDismissed: (() -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.snackbarMessage) {
        val message = uiState.snackbarMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearSnackbarMessage()
        }
    }

    AccountsScreenContent(
        uiState = uiState,
        contentPadding = contentPadding,
        snackbarHostState = snackbarHostState,
        onToggleGroupCollapsed = viewModel::toggleGroupCollapsed,
        onToggleShowArchived = viewModel::toggleShowArchivedAccounts,
        onOpenCreateAccount = viewModel::openCreateAccountDialog,
        onOpenEditAccount = viewModel::openEditAccountDialog,
        onAccountClick = { account ->
            if (onAccountClick != null) {
                onAccountClick(account)
            } else {
                viewModel.selectAccountForInspection(account.id)
            }
        },
    )

    if (!com.folioledger.app.ui.theme.LocalIsAppLocked.current) {
        val passbook = uiState.accountPassbook
        if (passbook != null) {
            AccountPassbookBottomSheet(
                passbook = passbook,
                referenceDateEpochDay = uiState.referenceDateEpochDay,
                defaultCurrencySpec = uiState.currencySpec,
                grouping = uiState.numberGrouping,
                onPreviousPeriod = { viewModel.stepPassbookMonth(-1) },
                onNextPeriod = { viewModel.stepPassbookMonth(1) },
                onResetToCurrentMonth = viewModel::resetPassbookToCurrentMonth,
                onEditAccountClick = {
                    viewModel.openEditAccountDialog(passbook.account)
                },
                onAddTransactionClick = {
                    onAddTransactionForAccount(passbook.account)
                },
                onSettleBillClick = {
                    viewModel.openSettlementSheet(passbook.account)
                },
                onReconcileClick = {
                    viewModel.openReconcileDialog(passbook.account)
                },
                onTogglePin = {
                    viewModel.toggleAccountPin(passbook.account.id)
                },
                onViewInLedgerClick = {
                    viewModel.dismissInspectedAccountSheet()
                    onViewAccountInLedger?.invoke(passbook.account)
                },
                onTransactionRowClick = { tx ->
                    when (tx.type) {
                        TransactionType.OPENING_BALANCE -> {
                            if (passbook.account.groupType == AccountGroup.DEBIT_CARD) {
                                passbook.parentBankAccount?.let(viewModel::openEditAccountDialog)
                            } else {
                                viewModel.openEditAccountDialog(passbook.account)
                            }
                        }

                        TransactionType.RECONCILIATION -> {
                            if (passbook.account.groupType == AccountGroup.DEBIT_CARD) {
                                passbook.parentBankAccount?.let { viewModel.openReconcileDialog(it) }
                            } else {
                                viewModel.openReconcileDialog(passbook.account)
                            }
                        }

                        else -> onEditTransaction(tx)
                    }
                },
                onDismiss = {
                    viewModel.dismissInspectedAccountSheet()
                    onPassbookDismissed?.invoke()
                },
            )
        }

        val editorState = uiState.editorDialogState
        if (editorState != null) {
            AccountEditorBottomSheet(
                state = editorState,
                referenceDateEpochDay = uiState.referenceDateEpochDay,
                parentBankCandidates = uiState.parentBankCandidates,
                primaryCurrencyCode = uiState.primaryCurrencyCode,
                secondaryCurrencies = uiState.secondaryCurrencies,
                grouping = uiState.numberGrouping,
                isSaving = uiState.isSaving,
                onUpdateState = viewModel::updateEditorDialogState,
                onSave = viewModel::saveAccountFromDialog,
                onToggleArchive = {
                    if (editorState.isEditing && editorState.accountId > 0L) {
                        viewModel.setAccountArchived(editorState.accountId, !editorState.isArchived)
                    }
                },
                onDelete = {
                    if (editorState.isEditing && editorState.accountId > 0L) {
                        viewModel.requestAccountDeletion(editorState.accountId)
                    }
                },
                onDismiss = viewModel::dismissAccountEditorDialog,
            )
        }

        if (uiState.pendingDeletionAccountId != null) {
            val pendingId = uiState.pendingDeletionAccountId!!
            val pendingName = uiState.pendingDeletionAccountName ?: "Account"
            val txCount = uiState.pendingDeletionTxCount
            val linkedCardsCount = uiState.pendingDeletionLinkedCardsCount
            val hasTransactions = txCount > 0
            val hasLinkedCards = linkedCardsCount > 0
            val isDeletionBlocked = hasTransactions || hasLinkedCards

            AlertDialog(
                onDismissRequest = viewModel::cancelPendingDeletion,
                icon = {
                    Icon(
                        imageVector = FolioChromeIcons.Warning,
                        contentDescription = null,
                        tint = LocalFolioSemanticColors.current.warningAmber,
                    )
                },
                title = {
                    Text(
                        text = if (isDeletionBlocked) "Cannot Delete $pendingName" else "Delete $pendingName?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                },
                text = {
                    Text(
                        text = when {
                            hasTransactions -> {
                                "This account has $txCount linked transaction(s). To protect your historical reports and ledger logs, accounts with transaction history cannot be permanently deleted.\n\nWould you like to Archive this account instead? Archived accounts are hidden from active views while preserving all past transaction history."
                            }

                            hasLinkedCards -> {
                                "This account has $linkedCardsCount linked debit card(s). Delete those debit card(s) first, or Archive this account instead (archiving will also archive its linked debit card(s))."
                            }

                            else -> {
                                "Are you sure you want to permanently delete $pendingName? This action cannot be undone."
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    if (isDeletionBlocked) {
                        Button(
                            enabled = !uiState.isSaving,
                            onClick = {
                                viewModel.setAccountArchived(pendingId, isArchived = true)
                                viewModel.cancelPendingDeletion()
                            },
                        ) {
                            Text("Archive Account")
                        }
                    } else {
                        Button(
                            enabled = !uiState.isSaving,
                            onClick = { viewModel.confirmDeleteAccount(pendingId, deleteTransactions = false) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LocalFolioSemanticColors.current.expenseCoral,
                                contentColor = Color.White,
                            ),
                        ) {
                            Text("Delete Account")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::cancelPendingDeletion) {
                        Text("Cancel")
                    }
                },
            )
        }

        val reconcileState = uiState.reconcileDialogState
        if (reconcileState != null) {
            ReconcileBalanceDialog(
                state = reconcileState,
                numberGrouping = uiState.numberGrouping,
                isSaving = uiState.isSaving,
                onUpdateTargetBalance = { newTarget ->
                    viewModel.updateReconcileDialogState { it.copy(targetBalanceInput = newTarget) }
                },
                onUpdateNote = { newNote ->
                    viewModel.updateReconcileDialogState { it.copy(note = newNote) }
                },
                onConfirm = viewModel::submitReconcileDialog,
                onDismiss = viewModel::dismissReconcileDialog,
            )
        }

        val settlementState = uiState.settlementSheetState
        if (settlementState != null) {
            CreditCardSettlementBottomSheet(
                state = settlementState,
                assetAccounts = uiState.assetAccountsForSettlement,
                grouping = uiState.numberGrouping,
                isSaving = uiState.isSaving,
                onSelectPreset = viewModel::selectSettlementPreset,
                onUpdateState = viewModel::updateSettlementSheetState,
                onConfirmSettlement = {
                    val cardId = settlementState.creditCardAccount.id
                    viewModel.submitCreditCardSettlement(onSuccess = {
                        FolioNotificationManager.cancelCardNotification(context, cardId)
                    })
                },
                onDismiss = viewModel::dismissSettlementSheet,
            )
        }
    }
}

@Composable
fun AccountsScreenContent(
    uiState: AccountsUiState,
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    onToggleGroupCollapsed: (AccountGroup) -> Unit,
    onToggleShowArchived: () -> Unit,
    onOpenCreateAccount: (AccountGroup) -> Unit,
    onOpenEditAccount: (AccountSummary) -> Unit,
    onAccountClick: (AccountSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val allById = remember(uiState.allAccounts) {
        uiState.allAccounts.associateBy { it.id }
    }
    val orderedGroups = remember(uiState.netWorthOverview.accountsByGroup) {
        AccountGroup.entries.filter { group ->
            !uiState.netWorthOverview.accountsByGroup[group].isNullOrEmpty()
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = contentPadding.calculateTopPadding() + scaffoldPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding(),
                ),
        ) {
            // Standardized Top Header Bar (blends seamlessly into screen background)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Accounts & Net Worth",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                )

                FilledTonalButton(
                    onClick = { onOpenCreateAccount(AccountGroup.BANK_ACCOUNT) },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.height(40.dp),
                ) {
                    Icon(
                        imageVector = FolioChromeIcons.Add,
                        contentDescription = "Add Account",
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Add Account",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "accounts_summary_banner", contentType = "accounts_summary_banner") {
                    ThreeColumnSummaryBanner(
                        netWorthOverview = uiState.netWorthOverview,
                        currencySpec = uiState.currencySpec,
                        grouping = uiState.numberGrouping,
                    )
                }

                if (orderedGroups.isEmpty()) {
                    item(key = "accounts_empty_state", contentType = "accounts_empty_state") {
                        EmptyStateCard(
                            title = "No Active Accounts Yet",
                            subtitle = "Add your Bank Accounts, Cash Wallet, Credit Cards, and linked Debit Cards to track your live Net Worth.",
                            icon = FolioChromeIcons.AccountBalanceWalletOutlined,
                            actionLabel = "Create First Account",
                            onActionClick = { onOpenCreateAccount(AccountGroup.BANK_ACCOUNT) },
                        )
                    }
                } else {
                    orderedGroups.forEach { group ->
                        val groupAccounts = uiState.netWorthOverview.accountsByGroup[group].orEmpty()
                        val isCollapsed = group in uiState.collapsedGroups

                        item(key = "group_header_${group.name}", contentType = "account_group_card") {
                            val groupSubtotal = uiState.groupTotalsByGroup[group] ?: MinorMoney.ZERO
                            val onToggleCollapseGroup = remember(group, onToggleGroupCollapsed) {
                                { onToggleGroupCollapsed(group) }
                            }
                            AccountGroupSectionCard(
                                group = group,
                                accounts = groupAccounts,
                                allAccountsById = if (group == AccountGroup.DEBIT_CARD) allById else emptyMap(),
                                referenceDateEpochDay = uiState.referenceDateEpochDay,
                                groupSubtotal = groupSubtotal,
                                currencySpec = uiState.currencySpec,
                                grouping = uiState.numberGrouping,
                                isCollapsed = isCollapsed,
                                onToggleCollapse = onToggleCollapseGroup,
                                onEditAccount = onOpenEditAccount,
                                onAccountClick = onAccountClick,
                                creditCardGroupPayableTotal = if (group == AccountGroup.CREDIT_CARD) uiState.creditCardGroupPayableTotal else null,
                                creditCardGroupUnbilledTotal = if (group == AccountGroup.CREDIT_CARD) uiState.creditCardGroupUnbilledTotal else null,
                            )
                        }
                    }
                }

                item(key = "accounts_archived_toggle", contentType = "archived_toggle") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    ) {
                        Text(
                            text = "Include Archived Accounts",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Switch(
                            checked = uiState.showArchivedAccounts,
                            onCheckedChange = { onToggleShowArchived() },
                        )
                    }
                }
            }
        }
    }
}
