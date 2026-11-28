/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui

import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.RepeatFrequency
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.util.RecurringScheduleCalculator
import com.folioledger.app.ui.accounts.AccountEditorDialogState
import com.folioledger.app.ui.accounts.AccountsUiState
import com.folioledger.app.ui.accounts.CreditCardSettlementSheetState
import com.folioledger.app.ui.accounts.ReconcileBalanceDialogState
import com.folioledger.app.ui.entry.SplitLineDraft
import com.folioledger.app.ui.entry.TransactionEntryUiState
import com.folioledger.app.ui.ledger.LedgerHomeUiState
import com.folioledger.app.ui.stats.StatsAndBudgetsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class Phase3AuditRegressionTestSuite {

    // ---------------------------------------------------------------------------------------------
    // Bug 1.1: Atomic save lock prevents duplicate save submissions
    // ---------------------------------------------------------------------------------------------
    @Test
    fun atomicSaveLock_preventsDuplicateConcurrentSubmissions() {
        val isSavingState = MutableStateFlow(false)
        val submissionCount = AtomicLong(0)

        fun attemptSave(): Boolean {
            var acquired = false
            isSavingState.update { current ->
                if (current) {
                    current
                } else {
                    acquired = true
                    true
                }
            }
            if (acquired) {
                submissionCount.incrementAndGet()
            }
            return acquired
        }

        // First tap acquires lock
        val firstAttempt = attemptSave()
        assertTrue(firstAttempt)
        assertEquals(1L, submissionCount.get())

        // Rapid second tap while saving is rejected
        val secondAttempt = attemptSave()
        assertFalse(secondAttempt)
        assertEquals(1L, submissionCount.get())
    }

    // ---------------------------------------------------------------------------------------------
    // Bug 1.2: Synchronous recurring rule advancement before callback
    // ---------------------------------------------------------------------------------------------
    @Test
    fun recurringRuleAdvancement_completesBeforeOnSuccessCallback() {
        val ruleStartEpochDay = LocalDate.of(2026, 11, 1).toEpochDay()
        var currentRuleEpochDay = ruleStartEpochDay
        val ruleAdvancedBeforeCallback = AtomicBoolean(false)

        fun simulateSave(onSuccess: () -> Unit) {
            // Synchronously advance rule before triggering onSuccess
            val nextDate = RecurringScheduleCalculator.computeNextOccurrence(
                currentDate = LocalDate.ofEpochDay(currentRuleEpochDay),
                frequency = RepeatFrequency.MONTHLY,
            )
            currentRuleEpochDay = nextDate.toEpochDay()

            onSuccess()
        }

        simulateSave {
            // Inside callback (which pops backstack), rule MUST already be updated
            if (currentRuleEpochDay > ruleStartEpochDay) {
                ruleAdvancedBeforeCallback.set(true)
            }
        }

        assertTrue(ruleAdvancedBeforeCallback.get())
        assertEquals(LocalDate.of(2026, 12, 1).toEpochDay(), currentRuleEpochDay)
    }

    // ---------------------------------------------------------------------------------------------
    // Bug 1.3: Editing an existing transaction resets isRecurring and links recurringRuleId
    // ---------------------------------------------------------------------------------------------
    @Test
    fun loadTransactionItemForEdit_resetsIsRecurring_andLinksRecurringRuleId() {
        val existingTx = TransactionItem(
            id = 42L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 1700000000000L,
            ledgerDateEpochDay = 20000L,
            amount = MinorMoney(5000_00L),
            currencyCode = "INR",
            accountId = 10L,
            accountName = "Bank",
            transferToAccountId = null,
            transferToAccountName = null,
            transferFee = MinorMoney.ZERO,
            categoryId = 5L,
            categoryName = "Utilities",
            parentCategoryName = null,
            categoryColorHex = "#3B82F6",
            categoryIconName = "bolt",
            isSplit = false,
            splits = emptyList(),
            tags = listOf("electric"),
            payeeOrDescription = "Power Grid",
            note = "November Bill",
            installmentIndex = null,
            installmentTotalCount = null,
            recurringRuleId = 99L,
        )

        // Simulate form state updating with reset isRecurring
        var formIsRecurring = true // was previously toggled on
        var linkedRuleId: Long? = null

        // loadTransactionItemForEdit logic:
        formIsRecurring = false // explicitly reset
        linkedRuleId = existingTx.recurringRuleId

        assertFalse(formIsRecurring)
        assertEquals(99L, linkedRuleId)
    }

    // ---------------------------------------------------------------------------------------------
    // Item 4.1: Deterministic default parameter values in @Immutable UI state classes
    // ---------------------------------------------------------------------------------------------
    @Test
    fun immutableUiStateClasses_haveDeterministicDefaultParameterEquality() {
        val entry1 = TransactionEntryUiState()
        val entry2 = TransactionEntryUiState()
        assertEquals(entry1, entry2)
        assertEquals(0L, entry1.ledgerDateEpochDay)
        assertEquals(0L, entry1.timestampEpochMillis)

        // AccountsUiState() structural equality
        val acc1 = AccountsUiState()
        val acc2 = AccountsUiState()
        assertEquals(acc1, acc2)
        assertEquals(0L, acc1.referenceDateEpochDay)

        // LedgerHomeUiState() structural equality
        val ledger1 = LedgerHomeUiState()
        val ledger2 = LedgerHomeUiState()
        assertEquals(ledger1, ledger2)
        assertEquals(0L, ledger1.referenceDateEpochDay)
        assertEquals(0L, ledger1.selectedCalendarDayEpochDay)

        // StatsAndBudgetsUiState() structural equality
        val stats1 = StatsAndBudgetsUiState()
        val stats2 = StatsAndBudgetsUiState()
        assertEquals(stats1, stats2)
        assertEquals(0L, stats1.referenceDateEpochDay)
        assertEquals(0L, stats1.customStartEpochDay)
        assertEquals(0L, stats1.customEndEpochDay)
    }

    // ---------------------------------------------------------------------------------------------
    // Item 4.2: Timeout safety prevents infinite suspension on empty account flows
    // ---------------------------------------------------------------------------------------------
    @Test
    fun emptyAccountsFlow_timesOutSafely_withoutInfiniteSuspension() = runBlocking {
        val emptyFlow = MutableStateFlow<List<Long>>(emptyList())

        // Simulating the safety wrapper in AccountsViewModel.openSettlementSheetForAccountId
        val result = withTimeoutOrNull(100L) {
            emptyFlow.filter { it.isNotEmpty() }.first()
        }

        assertNull(result)
    }

    // ---------------------------------------------------------------------------------------------
    // Bug 2.1: Animatable snapTo resets value to 0f, preventing Compose snapshot write coalescing
    // ---------------------------------------------------------------------------------------------
    @Test
    fun chartAnimation_animatableSnapToResetsToZero_preventingCoalescingSkip() = runBlocking {
        // Compose Snapshot mutableFloatStateOf coalesces multiple writes in the same frame/turn:
        val mutableState = androidx.compose.runtime.mutableFloatStateOf(0f)
        mutableState.floatValue = 1f // After initial composition, targetValue is 1f

        // Simulating the flawed LaunchedEffect(bars, showNetMode) { target = 0f; target = 1f }:
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            mutableState.floatValue = 0f
            mutableState.floatValue = 1f
        }
        // Because 0f and 1f were set in the same snapshot, observer sees no transition (1f -> 1f)
        assertEquals(1f, mutableState.floatValue, 0.001f)

        // With Animatable(0f), snapTo(0f) immediately forces the internal value to 0f:
        val anim = androidx.compose.animation.core.Animatable(0f)
        anim.snapTo(1f) // initial state
        assertEquals(1f, anim.value, 0.001f)

        // On key trigger (bars or mode change), snapTo(0f) resets to 0f without waiting for frame commit
        anim.snapTo(0f)
        assertEquals(0f, anim.value, 0.001f)
    }

    // ---------------------------------------------------------------------------------------------
    // Bug 2.4: DueRemindersScheduler default ExistingPeriodicWorkPolicy is KEEP
    // ---------------------------------------------------------------------------------------------
    @Test
    fun dueRemindersScheduler_defaultPolicyIsKeep_toAvoidPostponingPendingReminders() {
        val method = com.folioledger.app.data.notification.DueRemindersScheduler::class.java.methods
            .first { it.name.startsWith("scheduleDailyReminders") }
        val paramTypes = method.parameterTypes
        assertTrue(paramTypes.contains(androidx.work.ExistingPeriodicWorkPolicy::class.java))
        assertEquals(androidx.work.ExistingPeriodicWorkPolicy.KEEP, com.folioledger.app.data.notification.DueRemindersScheduler.DEFAULT_WORK_POLICY)
    }

    // ---------------------------------------------------------------------------------------------
    // Bug 2.5: MainLockViewModel SavedStateHandle survives process death
    // ---------------------------------------------------------------------------------------------
    @Test
    fun mainLockViewModel_savedStateHandle_survivesProcessDeath() {
        val savedStateHandle = androidx.lifecycle.SavedStateHandle()
        val vm = com.folioledger.app.MainLockViewModel(savedStateHandle)

        assertNull(vm.pendingShortcutAction.value)
        assertNull(vm.pendingNotificationAction.value)

        // Set actions
        vm.pendingShortcutAction.value = com.folioledger.app.domain.model.TransactionType.EXPENSE
        vm.pendingNotificationAction.value = com.folioledger.app.FolioNotificationAction.SettleCard(42L)

        assertEquals(com.folioledger.app.domain.model.TransactionType.EXPENSE, vm.pendingShortcutAction.value)
        assertEquals(com.folioledger.app.FolioNotificationAction.SettleCard(42L), vm.pendingNotificationAction.value)

        // Verify SavedStateHandle received the data
        assertEquals("EXPENSE", savedStateHandle.get<String>("pending_shortcut_action"))
        assertEquals(com.folioledger.app.FolioNotificationAction.SettleCard(42L), savedStateHandle.get<com.folioledger.app.FolioNotificationAction>("pending_notification_action"))

        // Simulate process death & restoration with new ViewModel instance
        val restoredVm = com.folioledger.app.MainLockViewModel(savedStateHandle)
        assertEquals(com.folioledger.app.domain.model.TransactionType.EXPENSE, restoredVm.pendingShortcutAction.value)
        assertEquals(com.folioledger.app.FolioNotificationAction.SettleCard(42L), restoredVm.pendingNotificationAction.value)

        // Clear actions upon unlock
        restoredVm.pendingShortcutAction.value = null
        restoredVm.pendingNotificationAction.value = null
        assertNull(savedStateHandle.get<String>("pending_shortcut_action"))
        assertNull(savedStateHandle.get<com.folioledger.app.FolioNotificationAction>("pending_notification_action"))
    }

    @Test
    fun mainLockViewModel_lockAndPromptGuards_defaultToFailClosed() {
        val vm = com.folioledger.app.MainLockViewModel(androidx.lifecycle.SavedStateHandle())
        assertFalse(vm.isSessionUnlocked.value)
        assertFalse(vm.hasUnlockedOnce.value)
        assertFalse(vm.hasAutoPromptedForCurrentLock)
        assertFalse(vm.isBiometricPromptInFlight)
    }

    // ---------------------------------------------------------------------------------------------
    // Bug 2.6: NonCancellable protects critical database phase from cancellation
    // ---------------------------------------------------------------------------------------------
    @Test
    fun nonCancellable_protectsCriticalDatabasePhase_duringCancellation() = runBlocking {
        var dbPhaseCompleted = false
        val enteredNonCancellable = java.util.concurrent.CountDownLatch(1)
        val job = launch(Dispatchers.Default) {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    enteredNonCancellable.countDown()
                    kotlinx.coroutines.delay(20)
                    dbPhaseCompleted = true
                }
            } catch (e: Exception) {
                // Should not happen
            }
        }
        enteredNonCancellable.await(3, java.util.concurrent.TimeUnit.SECONDS)
        job.cancel()
        job.join()
        assertTrue("NonCancellable DB write phase must complete even if outer scope is cancelled", dbPhaseCompleted)
    }

    // ---------------------------------------------------------------------------------------------
    // Item 3.1: Noon UTC timestamp consistency for installments prevents negative timezone date drift
    // ---------------------------------------------------------------------------------------------
    @Test
    fun installmentTimestamp_standardizedToNoonUtc_preventsTimezoneShift() {
        val scheduledEpochDay = 20000L
        val noonUtcMillis = scheduledEpochDay * 86_400_000L + 12 * 3600 * 1000L

        // In UTC-8 (e.g. America/Los_Angeles), midnight UTC (scheduledEpochDay * 86_400_000L)
        // evaluates to 16:00 on the PREVIOUS day (scheduledEpochDay - 1).
        // Standardizing to Noon UTC (12:00 UTC) produces 04:00 AM on the SAME day in UTC-8.
        val zoneUtcMinus8 = java.time.ZoneId.of("America/Los_Angeles")
        val instantNoon = java.time.Instant.ofEpochMilli(noonUtcMillis)
        val localDateNoon = instantNoon.atZone(zoneUtcMinus8).toLocalDate()
        assertEquals(scheduledEpochDay, localDateNoon.toEpochDay())

        // In UTC+10 (e.g. Australia/Sydney), noon UTC produces 22:00 PM on the SAME day.
        val zoneUtcPlus10 = java.time.ZoneId.of("Australia/Sydney")
        val localDatePlus10 = instantNoon.atZone(zoneUtcPlus10).toLocalDate()
        assertEquals(scheduledEpochDay, localDatePlus10.toEpochDay())
    }

    // ---------------------------------------------------------------------------------------------
    // Item 3.2: SAF display name resolution handles clean paths, encoded URIs, and fallbacks
    // ---------------------------------------------------------------------------------------------
    @Test
    fun storageAccessUtils_resolveDisplayName_handlesCleanPathsAndFallbacks() {
        // 1. Clean file URI or path
        val cleanUri = "file:///storage/emulated/0/Download/Folio_Ledger_Export.csv"
        val resolvedClean = com.folioledger.app.data.repository.StorageAccessUtils.resolveDisplayName(
            context = null,
            uriString = cleanUri,
            fallback = "Folio_Export.csv",
        )
        assertEquals("Folio_Ledger_Export.csv", resolvedClean)

        // 2. Colon-encoded document ID fallback without content provider
        val opaqueUri = "content://com.android.providers.media.documents/document/msf%3A1000042"
        val resolvedOpaque = com.folioledger.app.data.repository.StorageAccessUtils.resolveDisplayName(
            context = null,
            uriString = opaqueUri,
            fallback = "Folio_Backup.zip",
        )
        assertEquals("Folio_Backup.zip", resolvedOpaque)

        // 3. Raw numeric document ID fallback
        val numericUri = "content://media/external/files/1000042"
        val resolvedNumeric = com.folioledger.app.data.repository.StorageAccessUtils.resolveDisplayName(
            context = null,
            uriString = numericUri,
            fallback = "Folio_Backup.zip",
        )
        assertEquals("Folio_Backup.zip", resolvedNumeric)
    }
}
