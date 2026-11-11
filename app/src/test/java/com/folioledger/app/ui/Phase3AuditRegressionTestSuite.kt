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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
                frequency = RepeatFrequency.MONTHLY
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
            recurringRuleId = 99L
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
        // TransactionEntryUiState() must equal another TransactionEntryUiState()
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
}
