package com.folioledger.app.domain.util

import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AccountPassbookCalculatorTest {

    @Test
    fun computeFinancialMonthRange_calendarMonthStartDay1() {
        val date = LocalDate.of(2026, 5, 15).toEpochDay()
        val range = PassbookPeriodCalculator.computeFinancialMonthRange(
            referenceDateEpochDay = date,
            monthStartDay = 1,
            isCreditCard = false
        )

        assertEquals(LocalDate.of(2026, 5, 1).toEpochDay(), range.startEpochDay)
        assertEquals(LocalDate.of(2026, 5, 31).toEpochDay(), range.endEpochDay)
        assertEquals("May 2026", range.periodTitle)
    }

    @Test
    fun computeFinancialMonthRange_customMonthStartDay10() {
        // Date before start day (e.g. May 5th -> should be in Apr 10 - May 9 cycle)
        val dateBefore = LocalDate.of(2026, 5, 5).toEpochDay()
        val rangeBefore = PassbookPeriodCalculator.computeFinancialMonthRange(
            referenceDateEpochDay = dateBefore,
            monthStartDay = 10,
            isCreditCard = false
        )

        assertEquals(LocalDate.of(2026, 4, 10).toEpochDay(), rangeBefore.startEpochDay)
        assertEquals(LocalDate.of(2026, 5, 9).toEpochDay(), rangeBefore.endEpochDay)
        assertEquals("Apr 2026", rangeBefore.periodTitle)

        // Date after start day (e.g. May 15th -> should be in May 10 - Jun 9 cycle)
        val dateAfter = LocalDate.of(2026, 5, 15).toEpochDay()
        val rangeAfter = PassbookPeriodCalculator.computeFinancialMonthRange(
            referenceDateEpochDay = dateAfter,
            monthStartDay = 10,
            isCreditCard = false
        )

        assertEquals(LocalDate.of(2026, 5, 10).toEpochDay(), rangeAfter.startEpochDay)
        assertEquals(LocalDate.of(2026, 6, 9).toEpochDay(), rangeAfter.endEpochDay)
        assertEquals("May 2026", rangeAfter.periodTitle)
    }

    @Test
    fun computeFinancialMonthRange_customMonthStartDay23_majorityMonth() {
        // Oct 23 - Nov 22 should be named Nov 2026 under majority month rule
        val dateInNov = LocalDate.of(2026, 11, 11).toEpochDay()
        val range = PassbookPeriodCalculator.computeFinancialMonthRange(
            referenceDateEpochDay = dateInNov,
            monthStartDay = 23,
            isCreditCard = false
        )

        assertEquals(LocalDate.of(2026, 10, 23).toEpochDay(), range.startEpochDay)
        assertEquals(LocalDate.of(2026, 11, 22).toEpochDay(), range.endEpochDay)
        assertEquals("Nov 2026", range.periodTitle)
    }

    @Test
    fun stepFinancialMonth_stepsForwardAndBackward() {
        val initialEpoch = LocalDate.of(2026, 5, 15).toEpochDay()

        val steppedForward = PassbookPeriodCalculator.stepFinancialMonth(
            currentReferenceEpochDay = initialEpoch,
            monthStartDay = 1,
            deltaMonths = 1
        )
        val forwardDate = LocalDate.ofEpochDay(steppedForward)
        assertEquals(2026, forwardDate.year)
        assertEquals(6, forwardDate.monthValue)

        val steppedBackward = PassbookPeriodCalculator.stepFinancialMonth(
            currentReferenceEpochDay = initialEpoch,
            monthStartDay = 1,
            deltaMonths = -2
        )
        val backwardDate = LocalDate.ofEpochDay(steppedBackward)
        assertEquals(2026, backwardDate.year)
        assertEquals(3, backwardDate.monthValue)
    }

    @Test
    fun buildAccountPassbookUiModel_calculatesInflowOutflowAndRunningBalance() {
        val bankAccount = AccountSummary(
            id = 101L,
            name = "Checking Account",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(500000L), // 5,000.00
            iconName = "account_balance",
            colorHex = "#059669"
        )

        val may1 = LocalDate.of(2026, 5, 1).toEpochDay()
        val may10 = LocalDate.of(2026, 5, 10).toEpochDay()
        val may20 = LocalDate.of(2026, 5, 20).toEpochDay()

        val transactions = listOf(
            TransactionItem(
                id = 1L,
                type = TransactionType.INCOME,
                timestampEpochMillis = 1000L,
                ledgerDateEpochDay = may10,
                amount = MinorMoney(300000L), // +3000
                currencyCode = "INR",
                accountId = 101L,
                accountName = "Checking Account",
                categoryId = 10L,
                categoryName = "Salary"
            ),
            TransactionItem(
                id = 2L,
                type = TransactionType.EXPENSE,
                timestampEpochMillis = 2000L,
                ledgerDateEpochDay = may20,
                amount = MinorMoney(50000L), // -500
                currencyCode = "INR",
                accountId = 101L,
                accountName = "Checking Account",
                categoryId = 20L,
                categoryName = "Groceries"
            )
        )

        val passbook = AccountPassbookCalculator.buildAccountPassbookUiModel(
            inspectedAccount = bankAccount,
            allAccounts = listOf(bankAccount),
            allTransactions = transactions,
            monthStartDay = 1,
            referenceDateEpochDay = may1,
            passbookMonthOffset = 0
        )

        assertEquals(2, passbook.totalPeriodTransactionCount)
        assertEquals(300000L, passbook.periodInflows.amountMinor)
        assertEquals(50000L, passbook.periodOutflows.amountMinor)
        assertEquals(250000L, passbook.periodNetFlow.amountMinor)
        assertEquals(2, passbook.dayGroups.size)
    }

    @Test
    fun buildAccountPassbookUiModel_rollsUpChildDebitCardTransactions() {
        val parentBank = AccountSummary(
            id = 201L,
            name = "Main Savings",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(1000000L),
            iconName = "account_balance",
            colorHex = "#059669"
        )
        val debitCard = AccountSummary(
            id = 202L,
            name = "Debit Card",
            groupType = AccountGroup.DEBIT_CARD,
            parentAccountId = 201L,
            currencyCode = "INR",
            currentBalance = MinorMoney.ZERO,
            iconName = "credit_card",
            colorHex = "#2563EB"
        )

        val refDate = LocalDate.of(2026, 5, 1).toEpochDay()
        val txDate = LocalDate.of(2026, 5, 12).toEpochDay()

        // Transaction done on child debit card
        val tx = TransactionItem(
            id = 10L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 5000L,
            ledgerDateEpochDay = txDate,
            amount = MinorMoney(120000L), // 1200
            currencyCode = "INR",
            accountId = 202L, // on debit card
            accountName = "Debit Card",
            categoryId = 30L,
            categoryName = "Dining"
        )

        val passbook = AccountPassbookCalculator.buildAccountPassbookUiModel(
            inspectedAccount = parentBank,
            allAccounts = listOf(parentBank, debitCard),
            allTransactions = listOf(tx),
            monthStartDay = 1,
            referenceDateEpochDay = refDate,
            passbookMonthOffset = 0
        )

        assertEquals(1, passbook.totalPeriodTransactionCount)
        assertEquals(120000L, passbook.periodOutflows.amountMinor)
        assertEquals("Debit Card", passbook.entries.first().viaLinkedDebitCardName)
    }

    @Test
    fun computeFinancialMonthRange_deterministicTodayEpochDayForCreditCards() {
        val cycleEnd = LocalDate.of(2026, 5, 20)
        val cycleRef = cycleEnd.toEpochDay()

        // When today is BEFORE end date -> "Cycle (Active)"
        val rangeActive = PassbookPeriodCalculator.computeFinancialMonthRange(
            referenceDateEpochDay = cycleRef,
            monthStartDay = 21,
            isCreditCard = true,
            todayEpochDay = LocalDate.of(2026, 5, 10).toEpochDay()
        )
        assertEquals("May 2026 Cycle (Active)", rangeActive.periodTitle)

        // When today is AFTER end date -> "Statement"
        val rangeStatement = PassbookPeriodCalculator.computeFinancialMonthRange(
            referenceDateEpochDay = cycleRef,
            monthStartDay = 21,
            isCreditCard = true,
            todayEpochDay = LocalDate.of(2026, 6, 1).toEpochDay()
        )
        assertEquals("May 2026 Statement", rangeStatement.periodTitle)
    }

    @Test
    fun accountPassbook_zeroBalanceAndEmptyLedger_producesZeroTotalsAndNoEntries() {
        val emptyAccount = AccountSummary(
            id = 501L,
            name = "Fresh Savings",
            groupType = AccountGroup.SAVINGS,
            currencyCode = "INR",
            currentBalance = MinorMoney.ZERO
        )
        val passbook = AccountPassbookCalculator.buildAccountPassbookUiModel(
            inspectedAccount = emptyAccount,
            allAccounts = listOf(emptyAccount),
            allTransactions = emptyList(),
            monthStartDay = 1,
            referenceDateEpochDay = LocalDate.of(2026, 6, 15).toEpochDay(),
            passbookMonthOffset = 0
        )
        assertEquals(0, passbook.totalPeriodTransactionCount)
        assertEquals(MinorMoney.ZERO, passbook.periodOpeningBalance)
        assertEquals(MinorMoney.ZERO, passbook.periodClosingBalance)
        assertEquals(MinorMoney.ZERO, passbook.periodInflows)
        assertEquals(MinorMoney.ZERO, passbook.periodOutflows)
        assertEquals(MinorMoney.ZERO, passbook.periodNetFlow)
        assertTrue(passbook.entries.isEmpty())
    }

    @Test
    fun accountPassbook_liabilityOpeningBalanceTransaction_signedAsNegativeDebtAndZerosInitialBalance() {
        val loanAccount = AccountSummary(
            id = 601L,
            name = "Auto Loan",
            groupType = AccountGroup.LOAN,
            currencyCode = "INR",
            currentBalance = MinorMoney(-50_000_00L)
        )
        val openingTx = TransactionItem(
            id = 1L,
            type = TransactionType.OPENING_BALANCE,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = LocalDate.of(2026, 6, 1).toEpochDay(),
            amount = MinorMoney(50_000_00L),
            currencyCode = "INR",
            accountId = 601L,
            accountName = "Auto Loan"
        )
        val passbook = AccountPassbookCalculator.buildAccountPassbookUiModel(
            inspectedAccount = loanAccount,
            allAccounts = listOf(loanAccount),
            allTransactions = listOf(openingTx),
            monthStartDay = 1,
            referenceDateEpochDay = LocalDate.of(2026, 6, 15).toEpochDay(),
            passbookMonthOffset = 0
        )

        assertEquals(MinorMoney.ZERO, passbook.periodOpeningBalance)
        assertEquals(MinorMoney(-50_000_00L), passbook.periodClosingBalance)
        assertEquals(MinorMoney(-50_000_00L), passbook.entries.single().signedDelta)
    }
}
