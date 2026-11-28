/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

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
            isCreditCard = false,
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
            isCreditCard = false,
        )

        assertEquals(LocalDate.of(2026, 4, 10).toEpochDay(), rangeBefore.startEpochDay)
        assertEquals(LocalDate.of(2026, 5, 9).toEpochDay(), rangeBefore.endEpochDay)
        assertEquals("Apr 2026", rangeBefore.periodTitle)

        // Date after start day (e.g. May 15th -> should be in May 10 - Jun 9 cycle)
        val dateAfter = LocalDate.of(2026, 5, 15).toEpochDay()
        val rangeAfter = PassbookPeriodCalculator.computeFinancialMonthRange(
            referenceDateEpochDay = dateAfter,
            monthStartDay = 10,
            isCreditCard = false,
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
            isCreditCard = false,
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
            deltaMonths = 1,
        )
        val forwardDate = LocalDate.ofEpochDay(steppedForward)
        assertEquals(2026, forwardDate.year)
        assertEquals(6, forwardDate.monthValue)

        val steppedBackward = PassbookPeriodCalculator.stepFinancialMonth(
            currentReferenceEpochDay = initialEpoch,
            monthStartDay = 1,
            deltaMonths = -2,
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
            colorHex = "#059669",
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
                categoryName = "Salary",
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
                categoryName = "Groceries",
            ),
        )

        val passbook = AccountPassbookCalculator.buildAccountPassbookUiModel(
            inspectedAccount = bankAccount,
            allAccounts = listOf(bankAccount),
            allTransactions = transactions,
            monthStartDay = 1,
            referenceDateEpochDay = may1,
            passbookMonthOffset = 0,
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
            colorHex = "#059669",
        )
        val debitCard = AccountSummary(
            id = 202L,
            name = "Debit Card",
            groupType = AccountGroup.DEBIT_CARD,
            parentAccountId = 201L,
            currencyCode = "INR",
            currentBalance = MinorMoney.ZERO,
            iconName = "credit_card",
            colorHex = "#2563EB",
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
            categoryName = "Dining",
        )

        val passbook = AccountPassbookCalculator.buildAccountPassbookUiModel(
            inspectedAccount = parentBank,
            allAccounts = listOf(parentBank, debitCard),
            allTransactions = listOf(tx),
            monthStartDay = 1,
            referenceDateEpochDay = refDate,
            passbookMonthOffset = 0,
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
            todayEpochDay = LocalDate.of(2026, 5, 10).toEpochDay(),
        )
        assertEquals("May 2026 Cycle (Active)", rangeActive.periodTitle)

        // When today is AFTER end date -> "Statement"
        val rangeStatement = PassbookPeriodCalculator.computeFinancialMonthRange(
            referenceDateEpochDay = cycleRef,
            monthStartDay = 21,
            isCreditCard = true,
            todayEpochDay = LocalDate.of(2026, 6, 1).toEpochDay(),
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
            currentBalance = MinorMoney.ZERO,
        )
        val passbook = AccountPassbookCalculator.buildAccountPassbookUiModel(
            inspectedAccount = emptyAccount,
            allAccounts = listOf(emptyAccount),
            allTransactions = emptyList(),
            monthStartDay = 1,
            referenceDateEpochDay = LocalDate.of(2026, 6, 15).toEpochDay(),
            passbookMonthOffset = 0,
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
            currentBalance = MinorMoney(-50_000_00L),
        )
        val openingTx = TransactionItem(
            id = 1L,
            type = TransactionType.OPENING_BALANCE,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = LocalDate.of(2026, 6, 1).toEpochDay(),
            amount = MinorMoney(50_000_00L),
            currencyCode = "INR",
            accountId = 601L,
            accountName = "Auto Loan",
        )
        val passbook = AccountPassbookCalculator.buildAccountPassbookUiModel(
            inspectedAccount = loanAccount,
            allAccounts = listOf(loanAccount),
            allTransactions = listOf(openingTx),
            monthStartDay = 1,
            referenceDateEpochDay = LocalDate.of(2026, 6, 15).toEpochDay(),
            passbookMonthOffset = 0,
        )

        assertEquals(MinorMoney.ZERO, passbook.periodOpeningBalance)
        assertEquals(MinorMoney(-50_000_00L), passbook.periodClosingBalance)
        assertEquals(MinorMoney(-50_000_00L), passbook.entries.single().signedDelta)
    }

    @Test
    fun accountPassbook_openingBalanceOrdersFirstOnSameDayEvenWithLaterTimestamp() {
        val bank = AccountSummary(
            id = 701L,
            name = "Primary Bank",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(9_800_00L),
        )
        val sameDay = LocalDate.of(2026, 6, 15).toEpochDay()
        val morningExpense = TransactionItem(
            id = 2L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 10_000L, // earlier UTC timestamp than opening balance
            ledgerDateEpochDay = sameDay,
            amount = MinorMoney(200_00L),
            currencyCode = "INR",
            accountId = 701L,
            accountName = "Primary Bank",
            categoryName = "Food",
        )
        val openingTx = TransactionItem(
            id = 1L,
            type = TransactionType.OPENING_BALANCE,
            timestampEpochMillis = 50_000L,
            ledgerDateEpochDay = sameDay,
            amount = MinorMoney(10_000_00L),
            currencyCode = "INR",
            accountId = 701L,
            accountName = "Primary Bank",
        )

        val passbook = AccountPassbookCalculator.buildAccountPassbookUiModel(
            inspectedAccount = bank,
            allAccounts = listOf(bank),
            allTransactions = listOf(morningExpense, openingTx),
            monthStartDay = 1,
            referenceDateEpochDay = sameDay,
            passbookMonthOffset = 0,
        )

        // Newest-first UI display: Coffee Expense first (balance 9,800), then Opening Balance (balance 10,000)
        assertEquals(2, passbook.entries.size)
        assertEquals(TransactionType.EXPENSE, passbook.entries[0].transaction.type)
        assertEquals(MinorMoney(9_800_00L), passbook.entries[0].runningBalanceAfter)
        assertEquals(TransactionType.OPENING_BALANCE, passbook.entries[1].transaction.type)
        assertEquals(MinorMoney(10_000_00L), passbook.entries[1].runningBalanceAfter)
    }

    @Test
    fun accountPassbook_debitCardOnOverdraftUsesParentLiabilityFlagAndAttributesSiblingCard() {
        val overdraft = AccountSummary(
            id = 801L,
            name = "Overdraft Line",
            groupType = AccountGroup.OVERDRAFT,
            currencyCode = "INR",
            currentBalance = MinorMoney(-15_000_00L),
        )
        val cardA = AccountSummary(
            id = 802L,
            name = "Card A",
            groupType = AccountGroup.DEBIT_CARD,
            currencyCode = "INR",
            currentBalance = MinorMoney(-15_000_00L),
            parentAccountId = 801L,
        )
        val cardB = AccountSummary(
            id = 803L,
            name = "Card B",
            groupType = AccountGroup.DEBIT_CARD,
            currencyCode = "INR",
            currentBalance = MinorMoney(-15_000_00L),
            parentAccountId = 801L,
        )
        val day = LocalDate.of(2026, 6, 15).toEpochDay()
        val openingTx = TransactionItem(
            id = 1L,
            type = TransactionType.OPENING_BALANCE,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = day,
            amount = MinorMoney(10_000_00L),
            currencyCode = "INR",
            accountId = 801L,
            accountName = "Overdraft Line",
        )
        val siblingSpend = TransactionItem(
            id = 2L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 2000L,
            ledgerDateEpochDay = day,
            amount = MinorMoney(5_000_00L),
            currencyCode = "INR",
            accountId = 803L,
            accountName = "Card B",
        )

        val passbookA = AccountPassbookCalculator.buildAccountPassbookUiModel(
            inspectedAccount = cardA,
            allAccounts = listOf(overdraft, cardA, cardB),
            allTransactions = listOf(openingTx, siblingSpend),
            monthStartDay = 1,
            referenceDateEpochDay = day,
            passbookMonthOffset = 0,
        )

        assertEquals(MinorMoney.ZERO, passbookA.periodOpeningBalance)
        assertEquals(MinorMoney(-15_000_00L), passbookA.periodClosingBalance)
        val siblingEntry = passbookA.entries.first { it.transaction.id == 2L }
        assertEquals("Card B", siblingEntry.viaLinkedDebitCardName)
    }

    @Test
    fun computeCreditCardStatementRange_futureCycleShowsUpcomingTitle() {
        val today = LocalDate.of(2026, 5, 10).toEpochDay()
        val futureRange = PassbookPeriodCalculator.computeCreditCardStatementRange(
            referenceDateEpochDay = today,
            settlementDayOfMonth = 20,
            monthOffset = 1,
            todayEpochDay = today,
        )
        assertEquals("Jun 2026 Cycle (Upcoming)", futureRange.periodTitle)
    }
}
