/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/test/java/com/folioledger/app/domain/AccountingRulesEngineTest.kt
package com.folioledger.app.domain

import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.SplitLineItem
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.usecase.AccountingRulesEngine
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AccountingRulesEngineTest {

    private val engine = AccountingRulesEngine()

    @Test
    fun minorMoney_basicArithmeticAndComparisons_areExact() {
        val a = MinorMoney(125_50L)
        val b = MinorMoney(74_50L)

        val sum = a + b
        val diff = a - b
        val negated = -a

        assertEquals(MinorMoney(200_00L), sum)
        assertEquals(MinorMoney(51_00L), diff)
        assertEquals(MinorMoney(-125_50L), negated)
        assertTrue(a > b)
        assertTrue(sum.isPositive)
        assertTrue(negated.isNegative)
        assertTrue(MinorMoney.ZERO.isZero)
    }

    @Test(expected = ArithmeticException::class)
    fun minorMoney_additionOverflow_throwsArithmeticException() {
        val max = MinorMoney(Long.MAX_VALUE)
        val one = MinorMoney(1L)
        val unused = max + one
        assertEquals(0L, unused.amountMinor)
    }

    @Test(expected = ArithmeticException::class)
    fun minorMoney_subtractionOverflow_throwsArithmeticException() {
        val min = MinorMoney(Long.MIN_VALUE)
        val one = MinorMoney(1L)
        val unused = min - one
        assertEquals(0L, unused.amountMinor)
    }

    @Test
    fun formatMinorMoney_indianAndInternationalGrouping_formatsCorrectly() {
        val sample = MinorMoney(1234567_89L) // 12,34,567.89
        val oneLakh = MinorMoney(100000_00L) // 1,00,000.00
        val negative = MinorMoney(-500_50L) // -500.50

        val formattedIndian = MoneyFormatterAndCalculator.formatMinorMoney(
            money = sample,
            currencySpec = SupportedCurrencies.INR,
            grouping = NumberGroupingSystem.INDIAN,
        )
        assertEquals("₹12,34,567.89", formattedIndian)

        val formattedOneLakh = MoneyFormatterAndCalculator.formatMinorMoney(
            money = oneLakh,
            currencySpec = SupportedCurrencies.INR,
            grouping = NumberGroupingSystem.INDIAN,
        )
        assertEquals("₹1,00,000.00", formattedOneLakh)

        val formattedNegativeInr = MoneyFormatterAndCalculator.formatMinorMoney(
            money = negative,
            currencySpec = SupportedCurrencies.INR,
            grouping = NumberGroupingSystem.INDIAN,
        )
        assertEquals("-₹500.50", formattedNegativeInr)

        val formattedInternationalUsd = MoneyFormatterAndCalculator.formatMinorMoney(
            money = sample,
            currencySpec = SupportedCurrencies.USD,
            grouping = NumberGroupingSystem.INTERNATIONAL,
        )
        assertEquals("$1,234,567.89", formattedInternationalUsd)

        val formattedNoSymbolIndian = MoneyFormatterAndCalculator.formatMinorMoney(
            money = sample,
            currencySpec = SupportedCurrencies.INR,
            grouping = NumberGroupingSystem.INDIAN,
            includeSymbol = false,
        )
        assertEquals("12,34,567.89", formattedNoSymbolIndian)

        val formattedNoSymbolInternational = MoneyFormatterAndCalculator.formatMinorMoney(
            money = sample,
            currencySpec = SupportedCurrencies.INR,
            grouping = NumberGroupingSystem.INTERNATIONAL,
            includeSymbol = false,
        )
        assertEquals("1,234,567.89", formattedNoSymbolInternational)
    }

    @Test
    fun formatMinorMoney_zeroTwoThreeDecimalCurrencies_respectsFractionDigits() {
        // JPY: 0 fraction digits
        val jpyFormatted = MoneyFormatterAndCalculator.formatMinorMoney(
            money = MinorMoney(1234567L),
            currencySpec = SupportedCurrencies.JPY,
        )
        assertEquals("¥1,234,567", jpyFormatted)

        // INR: 2 fraction digits
        val inrFormatted = MoneyFormatterAndCalculator.formatMinorMoney(
            money = MinorMoney(2500_05L),
            currencySpec = SupportedCurrencies.INR,
        )
        assertEquals("₹2,500.05", inrFormatted)

        // KWD: 3 fraction digits
        val kwdFormatted = MoneyFormatterAndCalculator.formatMinorMoney(
            money = MinorMoney(1234_567L),
            currencySpec = SupportedCurrencies.KWD,
        )
        assertEquals("KD1,234.567", kwdFormatted)
    }

    @Test
    fun evaluateKeypadExpressionToMinor_precedenceAndRounding_evaluatesCorrectly() {
        // 250 + 49.95 * 2 = 250 + 99.90 = 349.90 -> 34990L minor units
        val result1 = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor(
            expression = "250+49.95*2",
            fractionDigits = 2,
        )
        assertTrue(result1.isSuccess)
        assertEquals(MinorMoney(349_90L), result1.getOrThrow())

        // 120 + 45.50 * 2 - 10 = 120 + 91.00 - 10 = 201.00 -> 20100L minor units
        val result2 = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor(
            expression = "120 + 45.50 * 2 - 10",
            fractionDigits = 2,
        )
        assertTrue(result2.isSuccess)
        assertEquals(MinorMoney(201_00L), result2.getOrThrow())

        // Division with HALF_UP rounding: 100 / 3 = 33.3333... -> 33.33 -> 3333L
        val result3 = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor(
            expression = "100 / 3",
            fractionDigits = 2,
        )
        assertTrue(result3.isSuccess)
        assertEquals(MinorMoney(33_33L), result3.getOrThrow())
    }

    @Test
    fun evaluateKeypadExpressionToMinor_rejectsZeroNegativeAndDivideByZero() {
        val zeroResult = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor(
            expression = "50 - 50",
            fractionDigits = 2,
        )
        assertTrue(zeroResult.isFailure)

        val negativeResult = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor(
            expression = "25 - 100",
            fractionDigits = 2,
        )
        assertTrue(negativeResult.isFailure)

        val divByZeroResult = MoneyFormatterAndCalculator.evaluateKeypadExpressionToMinor(
            expression = "100 / 0",
            fractionDigits = 2,
        )
        assertTrue(divByZeroResult.isFailure)
    }

    @Test
    fun validateSplitTransaction_enforcesExactSumAndMinimumTwoSplits() {
        val total = MinorMoney(1000_00L)
        val validSplits = listOf(
            SplitLineItem(categoryId = 1L, categoryName = "Groceries", amount = MinorMoney(650_00L)),
            SplitLineItem(categoryId = 2L, categoryName = "Household", amount = MinorMoney(350_00L)),
        )
        val validResult = engine.validateSplitTransaction(total, validSplits)
        assertTrue(validResult.isSuccess)

        val mismatchedSplits = listOf(
            SplitLineItem(categoryId = 1L, categoryName = "Groceries", amount = MinorMoney(600_00L)),
            SplitLineItem(categoryId = 2L, categoryName = "Household", amount = MinorMoney(350_00L)),
        )
        val mismatchResult = engine.validateSplitTransaction(total, mismatchedSplits)
        assertFalse(mismatchResult.isSuccess)

        val singleSplit = listOf(
            SplitLineItem(categoryId = 1L, categoryName = "Groceries", amount = MinorMoney(1000_00L)),
        )
        val singleSplitResult = engine.validateSplitTransaction(total, singleSplit)
        assertFalse(singleSplitResult.isSuccess)
    }

    @Test
    fun generateInstallmentSchedule_placesRemainderOnFirstInstallment() {
        val total = MinorMoney(100000L) // ₹1,000.00 in paise
        val startDate = LocalDate.of(2026, 10, 15)

        val schedule = engine.generateInstallmentSchedule(
            totalAmount = total,
            monthsCount = 3,
            startDateEpochDay = startDate.toEpochDay(),
        )

        assertEquals(3, schedule.size)
        assertEquals(MinorMoney(33334L), schedule[0].second)
        assertEquals(MinorMoney(33333L), schedule[1].second)
        assertEquals(MinorMoney(33333L), schedule[2].second)

        assertEquals(LocalDate.of(2026, 10, 15).toEpochDay(), schedule[0].first)
        assertEquals(LocalDate.of(2026, 11, 15).toEpochDay(), schedule[1].first)
        assertEquals(LocalDate.of(2026, 12, 15).toEpochDay(), schedule[2].first)

        val sum = schedule.fold(MinorMoney.ZERO) { acc, pair -> acc + pair.second }
        assertEquals(total, sum)
    }

    @Test
    fun computeFinancialMonthRange_customMonthStartDay25_computesExactBoundaries() {
        // Case 1: Reference date is 2026-10-17 (before the 25th) -> 2026-09-25 .. 2026-10-24
        val refBefore25 = LocalDate.of(2026, 10, 17).toEpochDay()
        val (start1, end1) = engine.computeFinancialMonthRange(
            referenceDateEpochDay = refBefore25,
            monthStartDay = 25,
        )
        assertEquals(LocalDate.of(2026, 9, 25).toEpochDay(), start1)
        assertEquals(LocalDate.of(2026, 10, 24).toEpochDay(), end1)

        // Case 2: Reference date is 2026-10-27 (on/after the 25th) -> 2026-10-25 .. 2026-11-24
        val refAfter25 = LocalDate.of(2026, 10, 27).toEpochDay()
        val (start2, end2) = engine.computeFinancialMonthRange(
            referenceDateEpochDay = refAfter25,
            monthStartDay = 25,
        )
        assertEquals(LocalDate.of(2026, 10, 25).toEpochDay(), start2)
        assertEquals(LocalDate.of(2026, 11, 24).toEpochDay(), end2)
    }

    @Test
    fun computeFinancialMonthRange_customMonthStartDay31_clampsInShortMonthsWithoutCrashing() {
        // Case 1: Reference date Feb 15, 2026 (non-leap year, Feb has 28 days) with monthStartDay = 31.
        // Feb start clamped to Feb 28. Ref date Feb 15 < Feb 28 -> cycle starts Jan 31, 2026, ends Feb 27, 2026.
        val feb15 = LocalDate.of(2026, 2, 15).toEpochDay()
        val (start1, end1) = engine.computeFinancialMonthRange(
            referenceDateEpochDay = feb15,
            monthStartDay = 31,
        )
        assertEquals(LocalDate.of(2026, 1, 31).toEpochDay(), start1)
        assertEquals(LocalDate.of(2026, 2, 27).toEpochDay(), end1)

        // Case 2: Reference date Feb 28, 2026 (on/after clamped start Feb 28) -> starts Feb 28, 2026, ends Mar 30, 2026.
        val feb28 = LocalDate.of(2026, 2, 28).toEpochDay()
        val (start2, end2) = engine.computeFinancialMonthRange(
            referenceDateEpochDay = feb28,
            monthStartDay = 31,
        )
        assertEquals(LocalDate.of(2026, 2, 28).toEpochDay(), start2)
        assertEquals(LocalDate.of(2026, 3, 30).toEpochDay(), end2)

        // Case 3: Reference date April 15, 2026 (30-day month) -> starts Mar 31, 2026, ends Apr 29, 2026.
        val apr15 = LocalDate.of(2026, 4, 15).toEpochDay()
        val (start3, end3) = engine.computeFinancialMonthRange(
            referenceDateEpochDay = apr15,
            monthStartDay = 31,
        )
        assertEquals(LocalDate.of(2026, 3, 31).toEpochDay(), start3)
        assertEquals(LocalDate.of(2026, 4, 29).toEpochDay(), end3)
    }

    @Test
    fun computeFinancialMonthRange_customMonthStartDay29_leapYearHandling() {
        // Feb 2028 is a leap year with 29 days.
        val feb152028 = LocalDate.of(2028, 2, 15).toEpochDay()
        val (start, end) = engine.computeFinancialMonthRange(
            referenceDateEpochDay = feb152028,
            monthStartDay = 29,
        )
        assertEquals(LocalDate.of(2028, 1, 29).toEpochDay(), start)
        assertEquals(LocalDate.of(2028, 2, 28).toEpochDay(), end)
    }

    @Test
    fun computeCreditCardMetrics_tracksStatementPayableUnbilledAndSettlementWithoutDoubleCounting() {
        // Statement closes on the 15th of each month.
        // Reference date: 2026-10-22 -> most recent statement closed on 2026-10-15.
        val oct10 = LocalDate.of(2026, 10, 10).toEpochDay()
        val oct15 = LocalDate.of(2026, 10, 15).toEpochDay()
        val oct18 = LocalDate.of(2026, 10, 18).toEpochDay()
        val oct20 = LocalDate.of(2026, 10, 20).toEpochDay()
        val oct22 = LocalDate.of(2026, 10, 22).toEpochDay()

        val transactions = listOf(
            // Billed in closed statement cycle (on or before Oct 15): ₹8,000 + ₹2,000 = ₹10,000
            createSampleTx(
                id = 1L,
                type = TransactionType.EXPENSE,
                epochDay = oct10,
                amount = MinorMoney(8000_00L),
            ),
            createSampleTx(
                id = 2L,
                type = TransactionType.EXPENSE,
                epochDay = oct15,
                amount = MinorMoney(2000_00L),
            ),
            // Active unbilled spend after Oct 15: ₹3,500
            createSampleTx(
                id = 3L,
                type = TransactionType.EXPENSE,
                epochDay = oct18,
                amount = MinorMoney(3500_00L),
            ),
            // Partial bill settlement on Oct 20: ₹6,000 (plus duplicate ID to verify deduplication)
            createSampleTx(
                id = 4L,
                type = TransactionType.CC_SETTLEMENT,
                epochDay = oct20,
                amount = MinorMoney(6000_00L),
            ),
            createSampleTx(
                id = 4L,
                type = TransactionType.CC_SETTLEMENT,
                epochDay = oct20,
                amount = MinorMoney(6000_00L),
            ),
            // Refund received on Oct 20: ₹500
            createSampleTx(
                id = 5L,
                type = TransactionType.REFUND,
                epochDay = oct20,
                amount = MinorMoney(500_00L),
            ),
        )

        val metrics = engine.computeCreditCardMetrics(
            openingBalance = MinorMoney.ZERO,
            transactions = transactions,
            settlementDayOfMonth = 15,
            referenceDateEpochDay = oct22,
            creditLimit = MinorMoney(50000_00L),
        )

        // Closed cycle debt = ₹10,000. Post-cutoff credits = ₹6,000 (settlement) + ₹500 (refund) = ₹6,500.
        // Remaining statementPayable = ₹10,000 - ₹6,500 = ₹3,500.
        // Unbilled spend in active cycle = ₹3,500.
        // Total outstandingBalance = ₹3,500 + ₹3,500 = ₹7,000 (signed currentBalance = -₹7,000).
        // Utilization = 7,000 / 50,000 = 14.00%.
        assertEquals(MinorMoney(3500_00L), metrics.statementPayable)
        assertEquals(MinorMoney(3500_00L), metrics.unbilledAmount)
        assertEquals(MinorMoney(7000_00L), metrics.outstandingBalance)
        assertEquals(MinorMoney(-7000_00L), metrics.currentBalance)
        assertEquals(43000_00L, metrics.availableCredit?.amountMinor)
        assertEquals(14.00f, metrics.utilizationPercent, 0.001f)
    }

    @Test
    fun computeCreditCardMetrics_reconciliationTransactions_adjustDebtCorrectly() {
        // Statement closes on 15th of each month.
        val statementDay = 15
        val referenceDay = LocalDate.of(2026, 10, 20).toEpochDay()
        val closeDay = LocalDate.of(2026, 10, 15).toEpochDay()

        // Closed cycle spend of 10,000 INR
        val closedSpend = createSampleTx(
            id = 1L,
            type = TransactionType.EXPENSE,
            epochDay = closeDay - 2,
            amount = MinorMoney(10000_00L),
        )

        // Reconciliation adjustment in active cycle (+500 debt adjustment, transferToAccountId != null)
        val reconDebtIncrease = createSampleTx(
            id = 2L,
            type = TransactionType.RECONCILIATION,
            epochDay = closeDay + 2,
            amount = MinorMoney(500_00L),
        ).copy(transferToAccountId = 10L)

        val metrics = engine.computeCreditCardMetrics(
            openingBalance = MinorMoney.ZERO,
            initialUnbilledAmount = null,
            creditLimit = MinorMoney(50000_00L),
            settlementDayOfMonth = statementDay,
            referenceDateEpochDay = referenceDay,
            transactions = listOf(closedSpend, reconDebtIncrease),
        )

        assertEquals(MinorMoney(10000_00L), metrics.statementPayable)
        assertEquals(MinorMoney(500_00L), metrics.unbilledAmount)
        assertEquals(MinorMoney(10500_00L), metrics.outstandingBalance)
        assertEquals(MinorMoney(-10500_00L), metrics.currentBalance)
    }

    @Test
    fun computeCreditCardMetrics_onStatementClosingDay_activeTransactionsRemainUnbilled() {
        // Settlement/closing day is 11th. Reference date is 11 Nov 2026 (exact statement day).
        val statementDay = 11
        val dueDay = 30
        val refDate = LocalDate.of(2026, 11, 11).toEpochDay()

        // Prior statement (closed Oct 11, due Oct 30) had 34,450.96 debt, settled on Oct 20
        val oct20Payment = createSampleTx(
            id = 101L,
            type = TransactionType.CC_SETTLEMENT,
            epochDay = LocalDate.of(2026, 10, 20).toEpochDay(),
            amount = MinorMoney(34450_96L),
        )

        // Transactions in active cycle (12 Oct – 11 Nov) totaling 8,042.00 INR
        val txOct27 = createSampleTx(id = 1L, type = TransactionType.EXPENSE, epochDay = LocalDate.of(2026, 10, 27).toEpochDay(), amount = MinorMoney(5314_00L))
        val txNov06 = createSampleTx(id = 2L, type = TransactionType.EXPENSE, epochDay = LocalDate.of(2026, 11, 6).toEpochDay(), amount = MinorMoney(1547_00L))
        val txNov10A = createSampleTx(id = 3L, type = TransactionType.EXPENSE, epochDay = LocalDate.of(2026, 11, 10).toEpochDay(), amount = MinorMoney(888_75L))
        val txNov10B = createSampleTx(id = 4L, type = TransactionType.EXPENSE, epochDay = LocalDate.of(2026, 11, 10).toEpochDay(), amount = MinorMoney(292_25L))

        val allTxs = listOf(oct20Payment, txOct27, txNov06, txNov10A, txNov10B)

        // On Statement Day (11 Nov):
        val metricsOnStatementDay = engine.computeCreditCardMetrics(
            openingBalance = MinorMoney(34450_96L), // Prior statement debt that was settled
            settlementDayOfMonth = statementDay,
            referenceDateEpochDay = refDate,
            creditLimit = MinorMoney(120000_00L),
            transactions = allTxs,
        )

        // Active cycle spend must be recognized as UNBILLED, NOT billed statement!
        assertEquals(MinorMoney.ZERO, metricsOnStatementDay.statementPayable)
        assertEquals(MinorMoney(8042_00L), metricsOnStatementDay.unbilledAmount)
        assertEquals(MinorMoney(8042_00L), metricsOnStatementDay.outstandingBalance)
        assertEquals(MinorMoney(-8042_00L), metricsOnStatementDay.currentBalance)

        // Verify CreditCardCycleCalculator window agreement on statement day
        val cycleOnStatementDay = com.folioledger.app.domain.util.CreditCardCycleCalculator.computeCycleWindow(
            referenceDateEpochDay = refDate,
            settlementDayOfMonth = statementDay,
            paymentDueDayOfMonth = dueDay,
        )
        assertEquals(LocalDate.of(2026, 10, 12), cycleOnStatementDay.activeCycleStart)
        assertEquals(LocalDate.of(2026, 11, 11), cycleOnStatementDay.activeCycleEnd)
        assertEquals(LocalDate.of(2026, 10, 30), cycleOnStatementDay.lastStatementDueDate)

        // On the day AFTER Statement Day (12 Nov):
        val dayAfterRefDate = LocalDate.of(2026, 11, 12).toEpochDay()
        val metricsDayAfter = engine.computeCreditCardMetrics(
            openingBalance = MinorMoney(34450_96L),
            settlementDayOfMonth = statementDay,
            referenceDateEpochDay = dayAfterRefDate,
            creditLimit = MinorMoney(120000_00L),
            transactions = allTxs,
        )

        // The closed cycle now bills 8,042.00 INR
        assertEquals(MinorMoney(8042_00L), metricsDayAfter.statementPayable)
        assertEquals(MinorMoney.ZERO, metricsDayAfter.unbilledAmount)
        assertEquals(MinorMoney(8042_00L), metricsDayAfter.outstandingBalance)

        // And cycleWindow now advances due date to 30 Nov (future due date, NEVER overdue!)
        val cycleDayAfter = com.folioledger.app.domain.util.CreditCardCycleCalculator.computeCycleWindow(
            referenceDateEpochDay = dayAfterRefDate,
            settlementDayOfMonth = statementDay,
            paymentDueDayOfMonth = dueDay,
        )
        assertEquals(LocalDate.of(2026, 11, 12), cycleDayAfter.activeCycleStart)
        assertEquals(LocalDate.of(2026, 12, 11), cycleDayAfter.activeCycleEnd)
        assertEquals(LocalDate.of(2026, 11, 30), cycleDayAfter.lastStatementDueDate)
    }

    private fun createSampleTx(
        id: Long,
        type: TransactionType,
        epochDay: Long,
        amount: MinorMoney,
    ): TransactionItem = TransactionItem(
        id = id,
        type = type,
        timestampEpochMillis = epochDay * 86_400_000L,
        ledgerDateEpochDay = epochDay,
        amount = amount,
        currencyCode = "INR",
        accountId = 10L,
        accountName = "HDFC Regalia Credit Card",
        transferToAccountId = if (type == TransactionType.CC_SETTLEMENT) 10L else null,
        transferToAccountName = if (type == TransactionType.CC_SETTLEMENT) "HDFC Regalia Credit Card" else null,
        transferFee = MinorMoney.ZERO,
        categoryId = 1L,
        categoryName = "Shopping",
        parentCategoryName = null,
        categoryColorHex = "#10B981",
        categoryIconName = "shopping_bag",
        isSplit = false,
        splits = emptyList(),
        tags = emptyList(),
        payeeOrDescription = "Merchant",
        note = "",
        installmentIndex = null,
        installmentTotalCount = null,
    )

    @Test
    fun minorMoney_abs_handlesLongMinValueWithoutOverflow() {
        val minVal = MinorMoney(Long.MIN_VALUE)
        val absVal = minVal.abs()
        assertEquals(MinorMoney(Long.MAX_VALUE), absVal)
    }

    @Test
    fun validateSplitTransaction_requireCategoryIds_enforcesValidCategory() {
        val total = MinorMoney(1000_00L)
        val splitsWithoutCategory = listOf(
            SplitLineItem(categoryId = null, categoryName = "", amount = MinorMoney(600_00L)),
            SplitLineItem(categoryId = 2L, categoryName = "Household", amount = MinorMoney(400_00L)),
        )
        // With requireCategoryIds = false (default), categoryId absence is allowed
        assertTrue(engine.validateSplitTransaction(total, splitsWithoutCategory, requireCategoryIds = false).isSuccess)
        // With requireCategoryIds = true, it fails
        assertFalse(engine.validateSplitTransaction(total, splitsWithoutCategory, requireCategoryIds = true).isSuccess)
    }

    @Test
    fun generateInstallmentSchedule_clampsMonthsCountToSixty() {
        val schedule = engine.generateInstallmentSchedule(MinorMoney(6000_00L), monthsCount = 120, startDateEpochDay = 20000L)
        assertEquals(60, schedule.size)
    }

    @Test
    fun computeCreditCardMetrics_unrelatedTransferAndSettlement_doNotAffectCard() {
        val cardId = 50L
        val bankX = 100L
        val bankY = 200L
        val cardB = 300L
        val today = LocalDate.of(2026, 11, 20).toEpochDay()

        // Unrelated TRANSFER between Bank X and Bank Y
        val unrelatedTransfer = TransactionItem(
            id = 1L,
            type = TransactionType.TRANSFER,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = today,
            amount = MinorMoney(5000_00L),
            currencyCode = "INR",
            accountId = bankX,
            accountName = "Bank X",
            transferToAccountId = bankY,
            transferToAccountName = "Bank Y",
            transferFee = MinorMoney(50_00L),
        )

        // Unrelated CC_SETTLEMENT between Bank X and Card B
        val unrelatedSettlement = TransactionItem(
            id = 2L,
            type = TransactionType.CC_SETTLEMENT,
            timestampEpochMillis = 2000L,
            ledgerDateEpochDay = today,
            amount = MinorMoney(8000_00L),
            currencyCode = "INR",
            accountId = bankX,
            accountName = "Bank X",
            transferToAccountId = cardB,
            transferToAccountName = "Card B",
        )

        // Unrelated RECONCILIATION where sentinel transferToAccountId matches cardId
        val unrelatedReconciliation = TransactionItem(
            id = 3L,
            type = TransactionType.RECONCILIATION,
            timestampEpochMillis = 3000L,
            ledgerDateEpochDay = today,
            amount = MinorMoney(1000_00L),
            currencyCode = "INR",
            accountId = bankX,
            accountName = "Bank X",
            transferToAccountId = cardId, // Sentinel flag on Bank X!
        )

        val metrics = engine.computeCreditCardMetrics(
            cardAccountId = cardId,
            openingBalance = MinorMoney(10000_00L), // ₹10,000 opening debt
            transactions = listOf(unrelatedTransfer, unrelatedSettlement, unrelatedReconciliation),
            settlementDayOfMonth = 15,
            referenceDateEpochDay = today,
            creditLimit = MinorMoney(50000_00L),
        )

        // Unrelated transactions must have zero impact on card debt
        assertEquals(MinorMoney(10000_00L), metrics.statementPayable)
        assertEquals(MinorMoney.ZERO, metrics.unbilledAmount)
        assertEquals(MinorMoney(-10000_00L), metrics.currentBalance)
    }

    @Test
    fun computeCreditCardMetrics_cardPayingAnotherAccount_increasesDebt() {
        val cardA = 50L
        val cardB = 60L
        val today = LocalDate.of(2026, 11, 20).toEpochDay()

        // Card A settles Card B's debt (Balance Transfer from Card A)
        val balanceTransfer = TransactionItem(
            id = 1L,
            type = TransactionType.CC_SETTLEMENT,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = today,
            amount = MinorMoney(10000_00L),
            currencyCode = "INR",
            accountId = cardA,
            accountName = "Card A",
            transferToAccountId = cardB,
            transferToAccountName = "Card B",
            transferFee = MinorMoney(200_00L),
        )

        // Card A metrics: Card A is source, so its debt should increase by ₹10,200 (amount + fee)
        val metricsA = engine.computeCreditCardMetrics(
            cardAccountId = cardA,
            openingBalance = MinorMoney.ZERO,
            transactions = listOf(balanceTransfer),
            settlementDayOfMonth = 15,
            referenceDateEpochDay = today,
            creditLimit = MinorMoney(50000_00L),
        )
        assertEquals(MinorMoney(10200_00L), metricsA.unbilledAmount)
        assertEquals(MinorMoney(-10200_00L), metricsA.currentBalance)

        // Card B metrics: Card B is target, so its debt should decrease by ₹10,000
        val metricsB = engine.computeCreditCardMetrics(
            cardAccountId = cardB,
            openingBalance = MinorMoney(15000_00L), // ₹15,000 opening debt
            transactions = listOf(balanceTransfer),
            settlementDayOfMonth = 15,
            referenceDateEpochDay = today,
            creditLimit = MinorMoney(50000_00L),
        )
        assertEquals(MinorMoney(5000_00L), metricsB.statementPayable)
    }

    @Test
    fun computeCreditCardMetrics_openingBalanceIsCredit_handlesOverpaymentCorrectly() {
        val cardId = 50L
        val today = LocalDate.of(2026, 11, 20).toEpochDay()

        // Opening credit overpayment of ₹1,500
        val metrics = engine.computeCreditCardMetrics(
            cardAccountId = cardId,
            openingBalance = MinorMoney(1500_00L),
            transactions = emptyList(),
            settlementDayOfMonth = 15,
            referenceDateEpochDay = today,
            creditLimit = MinorMoney(50000_00L),
            openingBalanceIsCredit = true,
        )

        assertEquals(MinorMoney.ZERO, metrics.statementPayable)
        assertEquals(MinorMoney.ZERO, metrics.unbilledAmount)
        assertEquals(MinorMoney(1500_00L), metrics.currentBalance) // Positive asset balance!
    }

    @Test
    fun computeCreditCardMetrics_priorOverduePayable_calculatedWhenReferenceDatePastPriorDueDate() {
        val cardId = 60L
        // Statement day: 18th, due day: 5th
        // Sep 18 statement of 25,000 due on Oct 5.
        val sep10Expense = TransactionItem(
            id = 1L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 1000L,
            amount = MinorMoney(25000_00L),
            currencyCode = "INR",
            accountId = cardId,
            ledgerDateEpochDay = LocalDate.of(2026, 9, 10).toEpochDay(),
            accountName = "Card",
        )

        // On Oct 19, statement has rolled over to Oct 18 (due Nov 5).
        // Since Oct 19 is past Oct 5 (prior statement due date) and no payment was made:
        val oct19 = LocalDate.of(2026, 10, 19).toEpochDay()
        val metricsUnpaid = engine.computeCreditCardMetrics(
            cardAccountId = cardId,
            openingBalance = MinorMoney.ZERO,
            transactions = listOf(sep10Expense),
            settlementDayOfMonth = 18,
            referenceDateEpochDay = oct19,
            creditLimit = MinorMoney(100000_00L),
            paymentDueDayOfMonth = 5,
        )

        assertEquals(MinorMoney(25000_00L), metricsUnpaid.priorOverduePayable)

        // If a payment of 25,000 was made on Oct 2:
        val paymentOnOct2 = TransactionItem(
            id = 2L,
            type = TransactionType.CC_SETTLEMENT,
            timestampEpochMillis = 2000L,
            amount = MinorMoney(25000_00L),
            currencyCode = "INR",
            accountId = 999L,
            transferToAccountId = cardId,
            ledgerDateEpochDay = LocalDate.of(2026, 10, 2).toEpochDay(),
            accountName = "Bank",
            transferToAccountName = "Card",
        )

        val metricsPaid = engine.computeCreditCardMetrics(
            cardAccountId = cardId,
            openingBalance = MinorMoney.ZERO,
            transactions = listOf(sep10Expense, paymentOnOct2),
            settlementDayOfMonth = 18,
            referenceDateEpochDay = oct19,
            creditLimit = MinorMoney(100000_00L),
            paymentDueDayOfMonth = 5,
        )

        assertEquals(MinorMoney.ZERO, metricsPaid.priorOverduePayable)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testGenerateInstallmentSchedule_throwsOnZeroMonths() {
        AccountingRulesEngine.generateInstallmentSchedule(
            totalAmount = MinorMoney(10000_00L),
            monthsCount = 0,
            startDateEpochDay = LocalDate.of(2026, 1, 1).toEpochDay(),
        )
    }

    @Test
    fun testComputeCreditCardMetrics_openingBalanceCreditOffsetsPriorDebt() {
        val cardId = 42L
        val sep10Expense = TransactionItem(
            id = 1L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 1000L,
            amount = MinorMoney(20000_00L), // ₹20,000 spend in previous cycle
            currencyCode = "INR",
            accountId = cardId,
            ledgerDateEpochDay = LocalDate.of(2026, 9, 10).toEpochDay(),
            accountName = "Card",
        )
        val oct19 = LocalDate.of(2026, 10, 19).toEpochDay()

        // Opening balance is a ₹5,000 credit (openingBalanceIsCredit = true)
        val metrics = engine.computeCreditCardMetrics(
            cardAccountId = cardId,
            openingBalance = MinorMoney(5000_00L),
            transactions = listOf(sep10Expense),
            settlementDayOfMonth = 18,
            referenceDateEpochDay = oct19,
            creditLimit = MinorMoney(100000_00L),
            openingBalanceIsCredit = true,
            paymentDueDayOfMonth = 5,
        )

        // Previous cycle debt was 20,000 - 5,000 credit = 15,000
        assertEquals(MinorMoney(15000_00L), metrics.priorOverduePayable)
    }

    @Test
    fun testComputeCreditCardMetrics_singleLeggedSettlement_reducesDebt() {
        val cardId = 55L
        val expense = TransactionItem(
            id = 1L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 1000L,
            amount = MinorMoney(30000_00L), // ₹30,000 spend
            currencyCode = "INR",
            accountId = cardId,
            ledgerDateEpochDay = LocalDate.of(2026, 10, 5).toEpochDay(),
            accountName = "Card",
        )
        // Single-legged settlement directly on the card (transferToAccountId == null)
        val singleLegPayment = TransactionItem(
            id = 2L,
            type = TransactionType.CC_SETTLEMENT,
            timestampEpochMillis = 2000L,
            amount = MinorMoney(10000_00L), // ₹10,000 payment
            currencyCode = "INR",
            accountId = cardId,
            transferToAccountId = null,
            ledgerDateEpochDay = LocalDate.of(2026, 10, 10).toEpochDay(),
            accountName = "Card",
        )

        val metrics = engine.computeCreditCardMetrics(
            cardAccountId = cardId,
            openingBalance = MinorMoney.ZERO,
            transactions = listOf(expense, singleLegPayment),
            settlementDayOfMonth = 18,
            referenceDateEpochDay = LocalDate.of(2026, 10, 15).toEpochDay(),
            creditLimit = MinorMoney(100000_00L),
        )

        // Outstanding debt should be 30,000 - 10,000 = 20,000 (NOT 40,000!)
        assertEquals(MinorMoney(20000_00L), metrics.outstandingBalance)
    }

    @Test
    fun computeCreditCardMetrics_nullAndZeroCreditLimit_returnsZeroUtilizationWithoutNaN() {
        val today = LocalDate.of(2026, 10, 20).toEpochDay()
        val nullLimit = engine.computeCreditCardMetrics(
            openingBalance = MinorMoney(5000_00L),
            transactions = emptyList(),
            settlementDayOfMonth = 15,
            referenceDateEpochDay = today,
            creditLimit = null,
        )
        assertEquals(0f, nullLimit.utilizationPercent, 0.001f)
        org.junit.Assert.assertNull(nullLimit.availableCredit)

        val zeroLimit = engine.computeCreditCardMetrics(
            openingBalance = MinorMoney(5000_00L),
            transactions = emptyList(),
            settlementDayOfMonth = 15,
            referenceDateEpochDay = today,
            creditLimit = MinorMoney.ZERO,
        )
        assertTrue(zeroLimit.utilizationPercent.isFinite())
        assertEquals(0f, zeroLimit.utilizationPercent, 0.001f)
    }

    @Test
    fun computeCreditCardMetrics_openingAnchorAfterStatementClose_suppressesDay1FalseOverdue() {
        // Statement Day: 18, Payment Due Day: 5
        // User creates card on Oct 20 (anchor = Oct 20).
        // Last statement close was Oct 18 (due Nov 5).
        // Without openingAnchorEpochDay, the initial statement balance was attributed to the Sep 18 cycle
        // which was due Oct 5, causing a false "OVERDUE" alert on Day 1.
        val oct20 = LocalDate.of(2026, 10, 20).toEpochDay()
        val metricsWithAnchor = engine.computeCreditCardMetrics(
            cardAccountId = 88L,
            openingBalance = MinorMoney(-50000_00L), // total debt 50,000
            transactions = emptyList(),
            settlementDayOfMonth = 18,
            paymentDueDayOfMonth = 5,
            referenceDateEpochDay = oct20,
            creditLimit = MinorMoney(100000_00L),
            initialUnbilledAmount = MinorMoney(20000_00L),
            openingAnchorEpochDay = oct20,
        )

        // Must NOT be marked overdue; prior overdue payable must be ZERO
        assertEquals(MinorMoney.ZERO, metricsWithAnchor.priorOverduePayable)
        assertEquals(MinorMoney(30000_00L), metricsWithAnchor.statementPayable)
        assertEquals(MinorMoney(20000_00L), metricsWithAnchor.unbilledAmount)
        assertEquals(MinorMoney(50000_00L), metricsWithAnchor.outstandingBalance)
    }

    @Test
    fun computeCreditCardMetrics_firstStatementCloseWithInitialUnbilled_doesNotTriggerFalsePriorOverdue() {
        // Card (statementDay = 15, dueDay = 5) created on Mar 10 with $1,000 unbilled spend.
        val mar10 = LocalDate.of(2026, 3, 10).toEpochDay()
        val initialUnbilled = MinorMoney(1000_00L)

        // Mar 16: the very day after the Mar 15 cycle closes (Apr 5 due date is still 20 days away).
        // Repository passes initialUnbilledAmount = null once cycle closes, alongside initialUnbilledAtCreation.
        val mar16Metrics = engine.computeCreditCardMetrics(
            cardAccountId = 91L,
            openingBalance = MinorMoney(-1000_00L),
            transactions = emptyList(),
            settlementDayOfMonth = 15,
            paymentDueDayOfMonth = 5,
            referenceDateEpochDay = LocalDate.of(2026, 3, 16).toEpochDay(),
            creditLimit = MinorMoney(10000_00L),
            initialUnbilledAmount = null,
            openingAnchorEpochDay = mar10,
            initialUnbilledAtCreation = initialUnbilled,
        )

        assertEquals(MinorMoney(1000_00L), mar16Metrics.statementPayable)
        assertEquals(MinorMoney.ZERO, mar16Metrics.unbilledAmount)
        assertEquals(MinorMoney.ZERO, mar16Metrics.priorOverduePayable)

        // Apr 16: second cycle closes after Apr 5 due date has passed without payment -> overdue now fires.
        val apr16Metrics = engine.computeCreditCardMetrics(
            cardAccountId = 91L,
            openingBalance = MinorMoney(-1000_00L),
            transactions = emptyList(),
            settlementDayOfMonth = 15,
            paymentDueDayOfMonth = 5,
            referenceDateEpochDay = LocalDate.of(2026, 4, 16).toEpochDay(),
            creditLimit = MinorMoney(10000_00L),
            initialUnbilledAmount = null,
            openingAnchorEpochDay = mar10,
            initialUnbilledAtCreation = initialUnbilled,
        )

        assertEquals(MinorMoney(1000_00L), apr16Metrics.priorOverduePayable)
    }

    @Test(expected = IllegalArgumentException::class)
    fun generateInstallmentSchedule_rejectsSubMinorPerInstallmentTotal() {
        engine.generateInstallmentSchedule(
            totalAmount = MinorMoney(5L),
            monthsCount = 12,
            startDateEpochDay = LocalDate.of(2026, 1, 1).toEpochDay(),
        )
    }

    @Test
    fun computeCreditCardMetrics_zeroCardIdWithLeadingSettlement_prefersCardOverBankAccountId() {
        val bankId = 10L
        val cardId = 99L
        val oct10 = LocalDate.of(2026, 10, 10).toEpochDay()
        val settlement = TransactionItem(
            id = 1L,
            type = TransactionType.CC_SETTLEMENT,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = oct10,
            amount = MinorMoney(200_00L),
            currencyCode = "INR",
            accountId = bankId,
            transferToAccountId = cardId,
            accountName = "Bank",
            transferToAccountName = "Card",
        )
        val expense = TransactionItem(
            id = 2L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 2000L,
            ledgerDateEpochDay = oct10,
            amount = MinorMoney(500_00L),
            currencyCode = "INR",
            accountId = cardId,
            accountName = "Card",
        )

        val metrics = engine.computeCreditCardMetrics(
            cardAccountId = 0L,
            openingBalance = MinorMoney(-1000_00L),
            transactions = listOf(settlement, expense),
            settlementDayOfMonth = 15,
            referenceDateEpochDay = LocalDate.of(2026, 10, 12).toEpochDay(),
            creditLimit = MinorMoney(10000_00L),
        )

        assertEquals(MinorMoney(1300_00L), metrics.outstandingBalance)
        assertEquals(MinorMoney(800_00L), metrics.statementPayable)
        assertEquals(MinorMoney(500_00L), metrics.unbilledAmount)
    }
}
