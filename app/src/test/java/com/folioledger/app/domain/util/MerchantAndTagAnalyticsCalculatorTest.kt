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

import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.MonthlyTrendBar
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantAndTagAnalyticsCalculatorTest {

    private fun sampleBar(label: String, startDay: Long, endDay: Long) = MonthlyTrendBar(
        periodLabel = label,
        startEpochDay = startDay,
        endEpochDay = endDay,
        totalIncome = MinorMoney.ZERO,
        totalExpense = MinorMoney.ZERO,
        netSavings = MinorMoney.ZERO,
    )

    private fun tx(
        id: Long,
        type: TransactionType,
        amountMinor: Long,
        epochDay: Long = 100L,
        currency: String = "INR",
        payee: String = "Coffee Roasters",
        transferFeeMinor: Long = 0L,
        categoryName: String? = "Food & Dining",
        tags: List<String> = emptyList(),
    ) = TransactionItem(
        id = id,
        type = type,
        timestampEpochMillis = epochDay * 86_400_000L,
        ledgerDateEpochDay = epochDay,
        amount = MinorMoney(amountMinor),
        currencyCode = currency,
        accountId = 1L,
        accountName = "Main Bank",
        transferFee = MinorMoney(transferFeeMinor),
        categoryName = categoryName,
        tags = tags,
        payeeOrDescription = payee,
    )

    @Test
    fun `computePayeeDrillDownState handles empty list cleanly`() {
        val state = MerchantAndTagAnalyticsCalculator.computePayeeDrillDownState(
            payeeName = "Blue Bottle",
            allTxs = emptyList(),
            trendBars = listOf(sampleBar("Jan", 90L, 120L)),
            startEpochDay = 90L,
            endEpochDay = 120L,
            isAllTime = false,
            primaryCurrencyCode = "INR",
        )
        assertEquals(0L, state.totalPeriodSpend.amountMinor)
        assertEquals(0L, state.totalAllTimeSpend.amountMinor)
        assertEquals(0, state.periodVisitCount)
        assertEquals(0L, state.averageTicketSize.amountMinor)
        assertTrue(state.categoryDistribution.isEmpty())
    }

    @Test
    fun `computePayeeDrillDownState nets refunds converts secondary currencies and includes transfer fees only`() {
        val txs = listOf(
            // Period INR expense: 100.00 INR
            tx(1L, TransactionType.EXPENSE, 100_00L, epochDay = 100L, payee = "Amazon"),
            // Refund in period: -35.00 INR -> net 65.00 INR
            tx(2L, TransactionType.REFUND, 35_00L, epochDay = 105L, payee = "Amazon"),
            // Secondary currency expense: $10.00 USD * 83.5 -> 835.00 INR
            tx(3L, TransactionType.EXPENSE, 10_00L, epochDay = 106L, currency = "USD", payee = "Amazon"),
            // Transfer fee-only spend: transfer principal ignored, fee 2.50 INR included
            tx(4L, TransactionType.TRANSFER, 500_00L, epochDay = 107L, transferFeeMinor = 2_50L, payee = "Amazon"),
            // Outside period (All-Time only): 20.00 INR
            tx(5L, TransactionType.EXPENSE, 20_00L, epochDay = 50L, payee = "Amazon"),
        )

        val state = MerchantAndTagAnalyticsCalculator.computePayeeDrillDownState(
            payeeName = "amazon",
            allTxs = txs,
            trendBars = listOf(sampleBar("Cur", 90L, 120L)),
            startEpochDay = 90L,
            endEpochDay = 120L,
            isAllTime = false,
            primaryCurrencyCode = "INR",
            secondaryCurrencies = listOf(SecondaryCurrency("USD", 83.5)),
        )

        // 100_00 - 35_00 + 835_00 + 2_50 = 902_50
        assertEquals(902_50L, state.totalPeriodSpend.amountMinor)
        assertEquals(922_50L, state.totalAllTimeSpend.amountMinor)
        assertEquals(3, state.periodVisitCount) // 2 expenses + 1 fee-bearing transfer
        assertEquals(4, state.allTimeVisitCount)
        assertEquals(902_50L, state.monthlyTrendBars.first().totalExpense.amountMinor)
    }

    @Test
    fun `computeTagProjectDrillDownState tracks reimbursable spending and settlement accurately`() {
        val partialTxs = listOf(
            tx(1L, TransactionType.EXPENSE, 120_00L, epochDay = 100L, tags = listOf("reimbursable")),
            tx(2L, TransactionType.EXPENSE, 80_00L, epochDay = 103L, tags = listOf("#reimbursable")),
            tx(3L, TransactionType.INCOME, 150_00L, epochDay = 104L, tags = listOf("reimbursable")),
        )

        val partial = MerchantAndTagAnalyticsCalculator.computeTagProjectDrillDownState(
            tagName = "#reimbursable",
            allTxs = partialTxs,
            trendBars = emptyList(),
            startEpochDay = 90L,
            endEpochDay = 120L,
            isAllTime = false,
            primaryCurrencyCode = "INR",
        )

        assertTrue(partial.isReimbursableProject)
        assertEquals(200_00L, partial.totalSpent.amountMinor)
        assertEquals(150_00L, partial.totalReimbursed.amountMinor)
        assertEquals(50_00L, partial.netPendingOwed.amountMinor)
        assertFalse(partial.isFullySettled)
        assertEquals(5, partial.dateSpanDays) // days 100..104 inclusive
        assertEquals(40_00L, partial.dailyAverageSpend.amountMinor) // 200_00 / 5

        val settled = MerchantAndTagAnalyticsCalculator.computeTagProjectDrillDownState(
            tagName = "reimbursable",
            allTxs = partialTxs + tx(4L, TransactionType.REFUND, 50_00L, epochDay = 104L, tags = listOf("reimbursable")),
            trendBars = emptyList(),
            startEpochDay = 90L,
            endEpochDay = 120L,
            isAllTime = false,
            primaryCurrencyCode = "INR",
        )
        assertEquals(0L, settled.netPendingOwed.amountMinor)
        assertTrue(settled.isFullySettled)
    }
}
