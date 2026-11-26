/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.entry

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import com.folioledger.app.data.notification.DailyLogReminderScheduler
import com.folioledger.app.data.notification.FolioNotificationManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.CategoryNode
import com.folioledger.app.domain.model.CategoryType
import com.folioledger.app.domain.model.PayeeSuggestion
import com.folioledger.app.domain.model.RepeatFrequency
import com.folioledger.app.domain.model.TransactionBookmark
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.ui.entry.components.PayeeSuggestionStrip
import com.folioledger.app.ui.entry.components.BookmarksTemplateDialog
import com.folioledger.app.ui.entry.components.CalculatorNumericKeypad
import com.folioledger.app.ui.entry.components.CategoryPickerBottomSheet
import com.folioledger.app.ui.entry.components.CreateTagDialog
import com.folioledger.app.ui.entry.components.DockedAccountPicker
import com.folioledger.app.ui.entry.components.DockedCategoryPicker
import com.folioledger.app.ui.entry.components.EntryAccountAndCategoryCards
import com.folioledger.app.ui.entry.components.EntryAmountDisplayCard
import com.folioledger.app.ui.entry.components.EntryDateAndTimeBar
import com.folioledger.app.ui.entry.components.EntryDatePickerDialog
import com.folioledger.app.ui.entry.components.EntryModeSelectorHeader
import com.folioledger.app.ui.entry.components.EntryRecurringBanner
import com.folioledger.app.ui.entry.components.EntryTimePickerLauncher
import com.folioledger.app.ui.entry.components.InstallmentPlanCard
import com.folioledger.app.ui.entry.components.RecurrenceFrequencyPickerBottomSheet
import com.folioledger.app.ui.entry.components.SplitTransactionEditorCard
import com.folioledger.app.ui.theme.LocalFolioSemanticColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ENTRY_DATE_PATTERN_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yy (EEE)", Locale.US)
private val ENTRY_SHORT_MONTH_FORMATTER = DateTimeFormatter.ofPattern("dd MMM", Locale.US)
private val ENTRY_TIME_FORMATTER = DateTimeFormatter.ofPattern("hh:mm a", Locale.US)

enum class LowerDockContent { KEYPAD, ACCOUNT_GRID, DEST_ACCOUNT_GRID, CATEGORY_TREE, HIDDEN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionEntrySheet(
    viewModel: TransactionEntryViewModel,
    onDismiss: () -> Unit,
    onTransactionSaved: () -> Unit = onDismiss,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentIsSaving by rememberUpdatedState(uiState.isSaving)
    val currentHasUnsavedChanges by rememberUpdatedState(uiState.hasUnsavedChanges)
    var showDiscardChangesDialog by rememberSaveable { mutableStateOf(false) }

    val requestDismiss: () -> Unit = {
        when {
            currentIsSaving -> Unit
            currentHasUnsavedChanges -> showDiscardChangesDialog = true
            else -> onDismiss()
        }
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { targetValue ->
            if (targetValue == SheetValue.Hidden) {
                when {
                    currentIsSaving -> false
                    currentHasUnsavedChanges -> {
                        showDiscardChangesDialog = true
                        false
                    }
                    else -> true
                }
            } else {
                true
            }
        }
    )
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = requestDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        val performSave: (() -> Unit) -> Unit = { afterSave ->
            val linkedRule = uiState.linkedRecurringRuleId
            val isLoggingDueRule = uiState.editingTransactionId == 0L && linkedRule != null
            val isNewManualTodayEntry = uiState.editingTransactionId == 0L &&
                linkedRule == null &&
                !uiState.isRecurring &&
                uiState.ledgerDateEpochDay == LocalDate.now().toEpochDay()
            viewModel.saveTransaction {
                if (isLoggingDueRule) {
                    FolioNotificationManager.cancelBillNotification(context, linkedRule)
                }
                if (isNewManualTodayEntry) {
                    FolioNotificationManager.cancelDailyLogNotification(context)
                    DailyLogReminderScheduler.cancelSnooze(context)
                }
                afterSave()
            }
        }
        TransactionEntryFormContent(
            uiState = uiState,
            payeeSuggestionsFlow = viewModel.payeeSuggestions,
            onSelectPayeeSuggestion = viewModel::applyPayeeSuggestion,
            onUndoAutofill = viewModel::undoPayeeAutofill,
            onDismissAutofillBanner = viewModel::dismissAutofillBanner,
            onModeSelected = viewModel::setMode,
            onStepDateByDays = viewModel::stepLedgerDateByDays,
            onDateSelected = viewModel::setLedgerDateEpochDay,
            onTimeSelected = viewModel::setLedgerTime,
            onSelectSourceAccount = viewModel::selectSourceAccount,
            onSelectDestinationAccount = viewModel::selectTransferDestinationAccount,
            onTransferFeeChange = viewModel::updateTransferFeeInput,
            onSelectCategory = viewModel::selectCategory,
            onCreateCategory = viewModel::createAndSelectCategory,
            onToggleSplit = viewModel::toggleSplitMode,
            onAddSplitLine = viewModel::addSplitLine,
            onUpdateSplitAmount = viewModel::updateSplitLineAmount,
            onUpdateSplitCategory = viewModel::updateSplitLineCategory,
            onAssignRemainingToSplit = viewModel::assignRemainingToSplitLine,
            onRemoveSplitLine = viewModel::removeSplitLine,
            onToggleRecurring = viewModel::toggleRecurringMode,
            onSelectRecurringFrequency = viewModel::setRecurringFrequency,
            onDisableRecurring = viewModel::disableRecurringMode,
            onToggleInstallment = viewModel::toggleInstallmentEnabled,
            onSelectInstallmentMonths = viewModel::setInstallmentMonthsCount,
            onPayeeChange = viewModel::updatePayeeOrDescription,
            onNoteChange = viewModel::updateNote,
            onToggleTag = viewModel::toggleTagSelection,
            onCreateNewTag = viewModel::createAndSelectTag,
            onApplyBookmark = viewModel::applyBookmark,
            onSaveBookmark = viewModel::saveCurrentAsBookmark,
            onDeleteBookmark = viewModel::deleteBookmark,
            onSetKeypadVisible = viewModel::setKeypadVisible,
            onAmountInputChange = viewModel::updateAmountInput,
            onKeypadKeyPress = viewModel::onKeypadKeyPress,
            onSaveClick = { performSave(onTransactionSaved) },
            onSaveAndContinueClick = {
                performSave {
                    // Save succeeds -> Keep sheet open and reset form for next rapid entry
                    viewModel.setKeypadVisible(true)
                }
            },
            onCloseClick = requestDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
        )
    }

    if (showDiscardChangesDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardChangesDialog = false },
            title = { Text("Discard Unsaved Changes?") },
            text = { Text("You have unsaved changes on this transaction. Discard them and close?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardChangesDialog = false
                        onDismiss()
                    }
                ) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardChangesDialog = false }) {
                    Text("Keep Editing")
                }
            }
        )
    }
}

@Composable
fun TransactionEntryFormContent(
    uiState: TransactionEntryUiState,
    onModeSelected: (TransactionType) -> Unit,
    onStepDateByDays: (Long) -> Unit,
    onDateSelected: (Long) -> Unit,
    onTimeSelected: (Int, Int) -> Unit = { _, _ -> },
    onSelectSourceAccount: (Long) -> Unit,
    onSelectDestinationAccount: (Long) -> Unit,
    onTransferFeeChange: (String) -> Unit,
    onSelectCategory: (CategoryNode, String?) -> Unit,
    onCreateCategory: (name: String, parentId: Long?, parentName: String?, iconName: String, colorHex: String, splitLineLocalId: Long?, onFailure: (() -> Unit)?, onSuccess: ((CategoryNode) -> Unit)?) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onToggleSplit: () -> Unit,
    onAddSplitLine: () -> Unit,
    onUpdateSplitAmount: (Long, String) -> Unit,
    onUpdateSplitCategory: (Long, CategoryNode) -> Unit,
    onAssignRemainingToSplit: (Long) -> Unit,
    onRemoveSplitLine: (Long) -> Unit,
    onToggleRecurring: () -> Unit = {},
    onSelectRecurringFrequency: (RepeatFrequency) -> Unit = {},
    onDisableRecurring: () -> Unit = {},
    onToggleInstallment: () -> Unit,
    onSelectInstallmentMonths: (Int) -> Unit,
    onPayeeChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onToggleTag: (String) -> Unit,
    onCreateNewTag: (String) -> Unit,
    onApplyBookmark: (TransactionBookmark) -> Unit,
    onSaveBookmark: (String) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    onSetKeypadVisible: (Boolean) -> Unit,
    onAmountInputChange: (String) -> Unit = {},
    onKeypadKeyPress: (String) -> Unit,
    onSaveClick: () -> Unit,
    onSaveAndContinueClick: () -> Unit = onSaveClick,
    onCloseClick: () -> Unit,
    payeeSuggestions: List<PayeeSuggestion> = emptyList(),
    payeeSuggestionsFlow: kotlinx.coroutines.flow.StateFlow<List<PayeeSuggestion>>? = null,
    onSelectPayeeSuggestion: (PayeeSuggestion) -> Unit = {},
    onUndoAutofill: () -> Unit = {},
    onDismissAutofillBanner: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val semanticColors = LocalFolioSemanticColors.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current
    val amountFocusRequester = remember { FocusRequester() }
    var isPayeeFocused by remember { mutableStateOf(false) }

    var lowerDockContent by rememberSaveable {
        mutableStateOf(if (uiState.isKeypadVisible && !uiState.isSplit) LowerDockContent.KEYPAD else LowerDockContent.HIDDEN)
    }
    var pendingAmountKeyboardFocus by remember { mutableStateOf(false) }

    LaunchedEffect(pendingAmountKeyboardFocus, lowerDockContent) {
        if (pendingAmountKeyboardFocus && lowerDockContent == LowerDockContent.HIDDEN) {
            pendingAmountKeyboardFocus = false
            amountFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    BackHandler(enabled = true) {
        if (uiState.isSaving) return@BackHandler
        if (lowerDockContent != LowerDockContent.HIDDEN) {
            lowerDockContent = LowerDockContent.HIDDEN
        } else {
            onCloseClick()
        }
    }

    LaunchedEffect(uiState.isSplit) {
        if (uiState.isSplit && lowerDockContent == LowerDockContent.KEYPAD) {
            lowerDockContent = LowerDockContent.HIDDEN
        }
    }

    var lastHandledSaveCounter by rememberSaveable { mutableIntStateOf(uiState.savedSuccessfullyCounter) }
    LaunchedEffect(uiState.savedSuccessfullyCounter) {
        if (uiState.savedSuccessfullyCounter > lastHandledSaveCounter) {
            lastHandledSaveCounter = uiState.savedSuccessfullyCounter
            pendingAmountKeyboardFocus = false
            focusManager.clearFocus()
            keyboardController?.hide()
            lowerDockContent = LowerDockContent.KEYPAD
        }
    }

    LaunchedEffect(uiState.linkedRecurringRuleId, uiState.isKeypadVisible) {
        if (uiState.linkedRecurringRuleId != null && !uiState.isKeypadVisible) {
            lowerDockContent = LowerDockContent.HIDDEN
        }
    }

    var showDatePickerDialog by rememberSaveable { mutableStateOf(false) }
    var showTimePickerDialog by rememberSaveable { mutableStateOf(false) }
    var showRecurrencePicker by rememberSaveable { mutableStateOf(false) }
    var showCategorySheet by rememberSaveable { mutableStateOf(false) }
    var splitLineIdForCategoryPicker by rememberSaveable { mutableStateOf<Long?>(null) }
    var categoryPickerPreselectedParent by remember { mutableStateOf<CategoryNode?>(null) }
    var showBookmarksDialog by rememberSaveable { mutableStateOf(false) }
    var showNewTagDialog by rememberSaveable { mutableStateOf(false) }

    var localAmountInput by remember(uiState.editingTransactionId) {
        mutableStateOf(if (uiState.expressionBuffer == "0") "" else uiState.expressionBuffer)
    }
    LaunchedEffect(uiState.formSyncVersion, uiState.editingTransactionId) {
        val expected = if (uiState.expressionBuffer == "0") "" else uiState.expressionBuffer
        if (localAmountInput != expected) {
            localAmountInput = expected
        }
    }

    var localTransferFee by remember(uiState.editingTransactionId) {
        mutableStateOf(uiState.transferFeeInput)
    }
    LaunchedEffect(uiState.formSyncVersion, uiState.editingTransactionId) {
        if (localTransferFee != uiState.transferFeeInput) {
            localTransferFee = uiState.transferFeeInput
        }
    }

    var localPayee by remember(uiState.editingTransactionId) {
        mutableStateOf(uiState.payeeOrDescription)
    }
    LaunchedEffect(uiState.formSyncVersion, uiState.editingTransactionId) {
        if (localPayee != uiState.payeeOrDescription) {
            localPayee = uiState.payeeOrDescription
        }
    }

    var localNote by remember(uiState.editingTransactionId) {
        mutableStateOf(uiState.note)
    }
    LaunchedEffect(uiState.formSyncVersion, uiState.editingTransactionId) {
        if (localNote != uiState.note) {
            localNote = uiState.note
        }
    }

    val formattedDate = remember(uiState.ledgerDateEpochDay) {
        val date = LocalDate.ofEpochDay(uiState.ledgerDateEpochDay)
        val today = LocalDate.now()
        when (date) {
            today -> "Today, ${date.format(ENTRY_SHORT_MONTH_FORMATTER)}"
            today.minusDays(1) -> "Yesterday, ${date.format(ENTRY_SHORT_MONTH_FORMATTER)}"
            else -> date.format(ENTRY_DATE_PATTERN_FORMATTER)
        }
    }

    val formattedTime = remember(uiState.timestampEpochMillis) {
        val time = Instant.ofEpochMilli(uiState.timestampEpochMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
        time.format(ENTRY_TIME_FORMATTER)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .statusBarsPadding()
            .imePadding()
    ) {
        // UPPER HALF: Form Display, Requirements & Anchored Save Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // Top Header: Segmented Mode Tabs + Bookmarks ★ + Close
            EntryModeSelectorHeader(
                currentMode = uiState.mode,
                isEditing = uiState.editingTransactionId > 0L,
                onModeSelected = onModeSelected,
                onOpenBookmarks = { showBookmarksDialog = true },
                onCloseClick = onCloseClick
            )

            // Unified Date, Time & Feature Actions Bar
            EntryDateAndTimeBar(
                formattedDate = formattedDate,
                formattedTime = formattedTime,
                mode = uiState.mode,
                isSplit = uiState.isSplit,
                isRecurring = uiState.isRecurring,
                isInstallmentEnabled = uiState.isInstallmentEnabled,
                isCreditCardExpense = uiState.isCreditCardExpense,
                isEditing = uiState.editingTransactionId > 0L,
                isLoggingDueRecurringRule = uiState.isLoggingDueRecurringRule,
                onStepDateByDays = onStepDateByDays,
                onOpenDatePicker = { showDatePickerDialog = true },
                onOpenTimePicker = { showTimePickerDialog = true },
                onToggleSplit = {
                    onToggleSplit()
                    if (!uiState.isSplit && lowerDockContent == LowerDockContent.KEYPAD) {
                        lowerDockContent = LowerDockContent.HIDDEN
                    }
                },
                onToggleRecurring = { showRecurrencePicker = true },
                onToggleInstallment = onToggleInstallment
            )

            if (uiState.isRecurring) {
                EntryRecurringBanner(
                    recurringFrequency = uiState.recurringFrequency,
                    ledgerDateEpochDay = uiState.ledgerDateEpochDay,
                    onEditRecurrence = { showRecurrencePicker = true },
                    onDisableRecurring = onDisableRecurring
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Amount & Live Calculator Expression Display Banner
            EntryAmountDisplayCard(
                isKeypadActive = lowerDockContent == LowerDockContent.KEYPAD,
                isEditingExisting = uiState.editingTransactionId > 0L,
                mode = uiState.mode,
                evaluatedAmount = uiState.evaluatedAmount,
                expressionBuffer = uiState.expressionBuffer,
                expressionHasOperator = uiState.expressionHasOperator,
                currencySpec = uiState.currencySpec,
                numberGrouping = uiState.numberGrouping,
                amountInput = localAmountInput,
                amountFocusRequester = amountFocusRequester,
                onAmountInputChange = { newInput ->
                    val normalized = EntryAmountInputSanitizer.sanitizeExpressionInput(
                        rawInput = newInput,
                        fractionDigits = uiState.currencySpec.fractionDigits
                    )
                    localAmountInput = normalized
                    onAmountInputChange(normalized)
                },
                onCardClick = {
                    if (lowerDockContent != LowerDockContent.HIDDEN) {
                        lowerDockContent = LowerDockContent.HIDDEN
                        pendingAmountKeyboardFocus = true
                    } else {
                        amountFocusRequester.requestFocus()
                        keyboardController?.show()
                    }
                },
                onToggleKeypadClick = {
                    if (lowerDockContent == LowerDockContent.KEYPAD) {
                        lowerDockContent = LowerDockContent.HIDDEN
                        pendingAmountKeyboardFocus = true
                    } else {
                        pendingAmountKeyboardFocus = false
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        lowerDockContent = LowerDockContent.KEYPAD
                    }
                },
                onNextImeAction = {
                    pendingAmountKeyboardFocus = false
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    lowerDockContent = LowerDockContent.ACCOUNT_GRID
                }
            )

            if (!uiState.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = semanticColors.expenseCoralContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = uiState.errorMessage,
                        style = MaterialTheme.typography.labelMedium,
                        color = semanticColors.onExpenseCoralContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            // Scrollable Middle Form Area
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                // 2-Column Account & Category Cards
                EntryAccountAndCategoryCards(
                    mode = uiState.mode,
                    isSplit = uiState.isSplit,
                    selectedAccount = uiState.selectedAccount,
                    transferToAccount = uiState.transferToAccount,
                    selectedCategoryName = uiState.selectedCategoryName,
                    selectedParentCategoryName = uiState.selectedParentCategoryName,
                    selectedCategoryIconName = uiState.selectedCategoryIconName,
                    selectedCategoryColorHex = uiState.selectedCategoryColorHex,
                    onPickSourceAccount = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        lowerDockContent = LowerDockContent.ACCOUNT_GRID
                    },
                    onPickDestinationAccount = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        lowerDockContent = LowerDockContent.DEST_ACCOUNT_GRID
                    },
                    onPickCategory = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        splitLineIdForCategoryPicker = null
                        lowerDockContent = LowerDockContent.CATEGORY_TREE
                    }
                )

                // Optional Transfer Fee Input
                AnimatedVisibility(visible = uiState.mode == TransactionType.TRANSFER && !uiState.isRecurring) {
                    OutlinedTextField(
                        value = localTransferFee,
                        onValueChange = { newInput ->
                            val sanitized = EntryAmountInputSanitizer.sanitizeDecimalInput(
                                rawInput = newInput,
                                fractionDigits = uiState.currencySpec.fractionDigits
                            )
                            localTransferFee = sanitized
                            onTransferFeeChange(sanitized)
                        },
                        label = { Text("Optional Transfer Fee (${uiState.currencySpec.symbol})") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (uiState.currencySpec.fractionDigits > 0) KeyboardType.Decimal else KeyboardType.Number
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (it.isFocused) lowerDockContent = LowerDockContent.HIDDEN }
                    )
                }

                // Dynamic Split Transaction Editor Section
                AnimatedVisibility(visible = uiState.isSplit) {
                    SplitTransactionEditorCard(
                        uiState = uiState,
                        onAddSplitLine = onAddSplitLine,
                        onPickSplitCategory = { localId ->
                            splitLineIdForCategoryPicker = localId
                            showCategorySheet = true
                        },
                        onUpdateSplitAmount = onUpdateSplitAmount,
                        onAssignRemainingToSplit = onAssignRemainingToSplit,
                        onRemoveSplitLine = onRemoveSplitLine,
                        onSplitFieldFocused = {
                            if (lowerDockContent == LowerDockContent.KEYPAD) {
                                lowerDockContent = LowerDockContent.HIDDEN
                            }
                        }
                    )
                }

                // Dynamic Credit Card Installment Plan Section
                AnimatedVisibility(visible = uiState.isInstallmentEnabled && uiState.isCreditCardExpense) {
                    InstallmentPlanCard(
                        uiState = uiState,
                        onSelectMonths = onSelectInstallmentMonths
                    )
                }

                // Payee / Description & Note Inputs
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = localPayee,
                        onValueChange = { newInput ->
                            localPayee = newInput
                            onPayeeChange(newInput)
                        },
                        label = { Text("Payee / Description") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged {
                                isPayeeFocused = it.isFocused
                                if (it.isFocused) lowerDockContent = LowerDockContent.HIDDEN
                            }
                    )

                    OutlinedTextField(
                        value = localNote,
                        onValueChange = { newInput ->
                            localNote = newInput
                            onNoteChange(newInput)
                        },
                        label = { Text("Note / Memo") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { if (it.isFocused) lowerDockContent = LowerDockContent.HIDDEN }
                    )
                }

                // Tags Row
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    uiState.availableTags.forEach { tag ->
                        val selected = tag in uiState.selectedTags
                        FilterChip(
                            selected = selected,
                            onClick = { onToggleTag(tag) },
                            label = { Text("#$tag", style = MaterialTheme.typography.labelSmall) }
                        )
                    }

                    FilterChip(
                        selected = false,
                        onClick = { showNewTagDialog = true },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Label,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        label = { Text("+ New Tag", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            val allCategories = remember(uiState.expenseCategories, uiState.incomeCategories) {
                uiState.expenseCategories + uiState.incomeCategories
            }
            if (payeeSuggestionsFlow != null) {
                PayeeSuggestionStrip(
                    suggestionsFlow = payeeSuggestionsFlow,
                    isPayeeFocused = isPayeeFocused,
                    categories = allCategories,
                    onSelectSuggestion = { suggestion ->
                        localPayee = suggestion.displayName
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelectPayeeSuggestion(suggestion)
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        if (uiState.evaluatedAmount.isZero && !uiState.isSplit) {
                            lowerDockContent = LowerDockContent.KEYPAD
                        }
                    },
                    autofillBannerText = uiState.autofillBannerText,
                    canUndoAutofill = uiState.canUndoAutofill,
                    onUndoAutofill = onUndoAutofill,
                    onDismissBanner = onDismissAutofillBanner
                )
            } else {
                PayeeSuggestionStrip(
                    suggestions = if (isPayeeFocused) payeeSuggestions else emptyList(),
                    categories = allCategories,
                    onSelectSuggestion = { suggestion ->
                        localPayee = suggestion.displayName
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelectPayeeSuggestion(suggestion)
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        if (uiState.evaluatedAmount.isZero && !uiState.isSplit) {
                            lowerDockContent = LowerDockContent.KEYPAD
                        }
                    },
                    autofillBannerText = uiState.autofillBannerText,
                    canUndoAutofill = uiState.canUndoAutofill,
                    onUndoAutofill = onUndoAutofill,
                    onDismissBanner = onDismissAutofillBanner
                )
            }

            // Always-Visible Save Action Bar Anchored directly at Bottom of Upper Half
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSaveClick()
                    },
                    enabled = !uiState.isSaving,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }

                if (uiState.editingTransactionId == 0L) {
                    FilledTonalButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSaveAndContinueClick()
                        },
                        enabled = !uiState.isSaving,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Continue", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        HorizontalDivider(color = semanticColors.cardStrokeSubtle)

        // LOWER HALF: Dynamic Dock Container (Keypad / 3-Col Accounts / Split Categories)
        AnimatedVisibility(visible = lowerDockContent != LowerDockContent.HIDDEN) {
            when (lowerDockContent) {
                LowerDockContent.KEYPAD -> {
                    CalculatorNumericKeypad(
                        isSaving = uiState.isSaving,
                        isEditingExisting = uiState.editingTransactionId > 0L,
                        expressionBuffer = uiState.expressionBuffer,
                        currencySymbol = uiState.currencySpec.symbol,
                        expressionHasOperator = uiState.expressionHasOperator,
                        onKeyPress = onKeypadKeyPress,
                        onHideKeypadClick = { lowerDockContent = LowerDockContent.HIDDEN },
                        onNextStepClick = {
                            focusManager.clearFocus()
                            if (uiState.expressionHasOperator) {
                                onKeypadKeyPress("=")
                            }
                            if (uiState.editingTransactionId > 0L) {
                                lowerDockContent = LowerDockContent.HIDDEN
                            } else {
                                lowerDockContent = LowerDockContent.ACCOUNT_GRID
                            }
                        },
                        fractionDigits = uiState.currencySpec.fractionDigits
                    )
                }

                LowerDockContent.ACCOUNT_GRID -> {
                    val isTransferLike = uiState.mode == TransactionType.TRANSFER || uiState.mode == TransactionType.CC_SETTLEMENT
                    DockedAccountPicker(
                        title = if (isTransferLike) "Select Source Account" else "Select Account",
                        accounts = uiState.activeAccounts,
                        selectedAccountId = uiState.selectedAccount?.id,
                        onAccountSelected = { acc ->
                            onSelectSourceAccount(acc.id)
                            if (isTransferLike) {
                                lowerDockContent = LowerDockContent.DEST_ACCOUNT_GRID
                            } else if (!uiState.isSplit) {
                                lowerDockContent = LowerDockContent.CATEGORY_TREE
                            } else {
                                lowerDockContent = LowerDockContent.HIDDEN
                            }
                        },
                        onCloseClick = { lowerDockContent = LowerDockContent.HIDDEN }
                    )
                }

                LowerDockContent.DEST_ACCOUNT_GRID -> {
                    val candidateAccounts = remember(uiState.activeAccounts, uiState.selectedAccount, uiState.mode) {
                        val sourceCurrency = uiState.selectedAccount?.currencyCode
                        uiState.activeAccounts.filter {
                            it.id != uiState.selectedAccount?.id &&
                                (sourceCurrency == null || it.currencyCode.equals(sourceCurrency, ignoreCase = true)) &&
                                (uiState.mode != TransactionType.CC_SETTLEMENT || it.groupType == com.folioledger.app.domain.model.AccountGroup.CREDIT_CARD)
                        }
                    }
                    DockedAccountPicker(
                        title = "Select Destination Account",
                        accounts = candidateAccounts,
                        selectedAccountId = uiState.transferToAccount?.id,
                        onAccountSelected = { acc ->
                            onSelectDestinationAccount(acc.id)
                            lowerDockContent = LowerDockContent.HIDDEN
                        },
                        onCloseClick = { lowerDockContent = LowerDockContent.HIDDEN }
                    )
                }

                LowerDockContent.CATEGORY_TREE -> {
                    DockedCategoryPicker(
                        categories = uiState.activeCategoryTree,
                        selectedCategoryId = uiState.selectedCategoryId,
                        subcategoriesEnabled = uiState.subcategoriesEnabled,
                        categoryType = if (uiState.mode == TransactionType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE,
                        onCategorySelected = { category, parentName ->
                            onSelectCategory(category, parentName)
                            lowerDockContent = LowerDockContent.HIDDEN
                        },
                        onCreateNewCategoryClick = {
                            categoryPickerPreselectedParent = null
                            showCategorySheet = true
                        },
                        onAddSubcategoryClick = { parent ->
                            categoryPickerPreselectedParent = parent
                            showCategorySheet = true
                        },
                        onCloseClick = { lowerDockContent = LowerDockContent.HIDDEN }
                    )
                }

                LowerDockContent.HIDDEN -> {}
            }
        }
    }

    // Dialogs & Sub-Sheets
    if (showDatePickerDialog) {
        EntryDatePickerDialog(
            initialEpochDay = uiState.ledgerDateEpochDay,
            onDateSelected = onDateSelected,
            onDismiss = { showDatePickerDialog = false }
        )
    }

    if (showTimePickerDialog) {
        EntryTimePickerLauncher(
            initialEpochMillis = uiState.timestampEpochMillis,
            onTimeSelected = onTimeSelected,
            onDismiss = { showTimePickerDialog = false }
        )
    }

    if (showCategorySheet) {
        val effectiveSelectedCategoryId = splitLineIdForCategoryPicker?.let { id ->
            uiState.splitDrafts.firstOrNull { it.localId == id }?.categoryId
        } ?: uiState.selectedCategoryId
        CategoryPickerBottomSheet(
            categories = uiState.activeCategoryTree,
            selectedCategoryId = effectiveSelectedCategoryId,
            subcategoriesEnabled = uiState.subcategoriesEnabled,
            categoryType = if (uiState.mode == TransactionType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE,
            initialCreateParent = categoryPickerPreselectedParent,
            onCategorySelected = { category, parentName ->
                val targetSplitId = splitLineIdForCategoryPicker
                if (targetSplitId != null) {
                    onUpdateSplitCategory(targetSplitId, category)
                } else {
                    onSelectCategory(category, parentName)
                }
                splitLineIdForCategoryPicker = null
                categoryPickerPreselectedParent = null
                showCategorySheet = false
                lowerDockContent = LowerDockContent.HIDDEN
            },
            onCreateNewCategory = { name, parentId, parentName, iconName, colorHex, onFailure, onCreated ->
                onCreateCategory(name, parentId, parentName, iconName, colorHex, splitLineIdForCategoryPicker, onFailure) { createdNode ->
                    onCreated(createdNode)
                    splitLineIdForCategoryPicker = null
                    categoryPickerPreselectedParent = null
                    showCategorySheet = false
                    lowerDockContent = LowerDockContent.HIDDEN
                }
            },
            onDismiss = {
                splitLineIdForCategoryPicker = null
                categoryPickerPreselectedParent = null
                showCategorySheet = false
            }
        )
    }

    if (showBookmarksDialog) {
        val isTransferMode = uiState.mode == TransactionType.TRANSFER || uiState.mode == TransactionType.CC_SETTLEMENT
        val src = uiState.selectedAccount
        val dst = uiState.transferToAccount
        val validTransferDest = !isTransferMode || (
            src != null && !src.isArchived && dst != null && !dst.isArchived && dst.id != src.id &&
                dst.currencyCode.equals(src.currencyCode, ignoreCase = true) &&
                (uiState.mode != TransactionType.CC_SETTLEMENT || dst.groupType == AccountGroup.CREDIT_CARD)
            )
        val saveBlockedReason = when {
            uiState.isSplit -> "Split transactions cannot be saved as templates."
            src == null || src.isArchived -> "Select an active account before saving a template."
            !validTransferDest -> "Select a valid same-currency destination account to save a transfer template."
            !uiState.evaluatedAmount.isPositive -> "Enter an amount greater than zero to save a template."
            else -> null
        }
        BookmarksTemplateDialog(
            bookmarks = uiState.bookmarks,
            currencySpec = uiState.currencySpec,
            accountCurrencySpecs = uiState.accountCurrencySpecs,
            canSaveCurrentAsBookmark = saveBlockedReason == null,
            saveBlockedReason = saveBlockedReason,
            errorMessage = uiState.errorMessage,
            grouping = uiState.numberGrouping,
            onSelectBookmark = { bm ->
                onApplyBookmark(bm)
                showBookmarksDialog = false
            },
            onSaveCurrentAsBookmark = { title ->
                onSaveBookmark(title)
            },
            onDeleteBookmark = onDeleteBookmark,
            onDismiss = { showBookmarksDialog = false }
        )
    }

    if (showNewTagDialog) {
        CreateTagDialog(
            onTagCreated = onCreateNewTag,
            onDismiss = { showNewTagDialog = false }
        )
    }

    if (showRecurrencePicker) {
        RecurrenceFrequencyPickerBottomSheet(
            selectedFrequency = uiState.recurringFrequency,
            isRecurringActive = uiState.isRecurring,
            baseDate = LocalDate.ofEpochDay(uiState.ledgerDateEpochDay),
            onSelectFrequency = { freq ->
                onSelectRecurringFrequency(freq)
            },
            onDisableRecurring = {
                onDisableRecurring()
            },
            onDismiss = { showRecurrencePicker = false }
        )
    }
}
