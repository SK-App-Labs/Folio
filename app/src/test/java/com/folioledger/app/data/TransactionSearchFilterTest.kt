/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.data

import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionSearchFilter
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.model.UNCATEGORIZED_CATEGORY_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionSearchFilterTest {

    private fun createSampleTransaction(
        id: Long,
        amountMinor: Long,
        categoryId: Long?,
        categoryName: String? = null,
    ): TransactionItem = TransactionItem(
        id = id,
        type = TransactionType.EXPENSE,
        timestampEpochMillis = 1700000000000L,
        ledgerDateEpochDay = 20000L,
        amount = MinorMoney(amountMinor),
        currencyCode = "INR",
        accountId = 1L,
        accountName = "Cash",
        categoryId = categoryId,
        categoryName = categoryName,
        payeeOrDescription = "Test Entry $id",
    )

    private fun applyFilter(
        items: List<TransactionItem>,
        filter: TransactionSearchFilter,
        expandedCategoryIds: Set<Long> = filter.categoryIds - UNCATEGORIZED_CATEGORY_ID,
    ): List<TransactionItem> = items.filter { com.folioledger.app.data.repository.matchesSearchFilter(it, filter, expandedCategoryIds) }

    @Test
    fun `filtering by UNCATEGORIZED_CATEGORY_ID isolates transactions without category`() {
        val txUncategorized1 = createSampleTransaction(1L, 10000L, null)
        val txFood = createSampleTransaction(2L, 25000L, 10L, "Food")
        val txUncategorized2 = createSampleTransaction(3L, 5000L, null)
        val txTransit = createSampleTransaction(4L, 12000L, 20L, "Transit")

        val list = listOf(txUncategorized1, txFood, txUncategorized2, txTransit)
        val filter = TransactionSearchFilter(categoryIds = setOf(UNCATEGORIZED_CATEGORY_ID))

        val filtered = applyFilter(list, filter)
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.categoryId == null })
        assertEquals(listOf(1L, 3L), filtered.map { it.id })
    }

    @Test
    fun `combining UNCATEGORIZED_CATEGORY_ID with regular category matches both`() {
        val txUncategorized = createSampleTransaction(1L, 10000L, null)
        val txFood = createSampleTransaction(2L, 25000L, 10L, "Food")
        val txTransit = createSampleTransaction(3L, 12000L, 20L, "Transit")

        val list = listOf(txUncategorized, txFood, txTransit)
        val filter = TransactionSearchFilter(categoryIds = setOf(UNCATEGORIZED_CATEGORY_ID, 10L))

        val filtered = applyFilter(list, filter, expandedCategoryIds = setOf(10L))
        assertEquals(2, filtered.size)
        assertEquals(listOf(1L, 2L), filtered.map { it.id })
    }

    @Test
    fun `amount range filter correctly filters between min and max bounds`() {
        val txSmall = createSampleTransaction(1L, 5000L, 10L) // 50.00
        val txMid = createSampleTransaction(2L, 15000L, 10L) // 150.00
        val txLarge = createSampleTransaction(3L, 50000L, 10L) // 500.00

        val list = listOf(txSmall, txMid, txLarge)

        // Min bound only: >= 100.00
        val minOnlyFilter = TransactionSearchFilter(minAmount = MinorMoney(10000L))
        val minOnlyResult = applyFilter(list, minOnlyFilter)
        assertEquals(listOf(2L, 3L), minOnlyResult.map { it.id })

        // Max bound only: <= 200.00
        val maxOnlyFilter = TransactionSearchFilter(maxAmount = MinorMoney(20000L))
        val maxOnlyResult = applyFilter(list, maxOnlyFilter)
        assertEquals(listOf(1L, 2L), maxOnlyResult.map { it.id })

        // Range bound: 100.00 to 200.00
        val rangeFilter = TransactionSearchFilter(
            minAmount = MinorMoney(10000L),
            maxAmount = MinorMoney(20000L),
        )
        val rangeResult = applyFilter(list, rangeFilter)
        assertEquals(listOf(2L), rangeResult.map { it.id })
    }

    @Test
    fun `multi-currency search filter converts foreign amount using exchange rate and scales fraction digits`() {
        val txInr = TransactionItem(
            id = 1L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 1700000000000L,
            ledgerDateEpochDay = 20000L,
            amount = MinorMoney(50000L), // ₹500.00
            currencyCode = "INR",
            accountId = 1L,
            accountName = "Cash",
        )
        val txUsd = TransactionItem(
            id = 2L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 1700000000000L,
            ledgerDateEpochDay = 20000L,
            amount = MinorMoney(1000L), // $10.00 @ 83.0 = ₹830.00 (83000 minor)
            currencyCode = "USD",
            accountId = 2L,
            accountName = "US Account",
        )
        val txJpy = TransactionItem(
            id = 3L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 1700000000000L,
            ledgerDateEpochDay = 20000L,
            amount = MinorMoney(1000L), // 1000 JPY (0 fraction digits) @ 0.55 = ₹550.00 (55000 minor)
            currencyCode = "JPY",
            accountId = 3L,
            accountName = "Japan Account",
        )

        val list = listOf(txInr, txUsd, txJpy)

        // Filter: ₹600.00 to ₹900.00 (60000 to 90000 paise)
        val filter = TransactionSearchFilter(
            minAmount = MinorMoney(60000L),
            maxAmount = MinorMoney(90000L),
            targetCurrencyCode = "INR",
            secondaryExchangeRates = mapOf("USD" to 83.0, "JPY" to 0.55),
        )

        val result = applyFilter(list, filter)
        // Only txUsd (₹830.00) falls between ₹600.00 and ₹900.00
        assertEquals(listOf(2L), result.map { it.id })
    }

    @Test
    fun `safe math helpers prevent Long MIN_VALUE overflow and saturate on extreme addition`() {
        val safeAbsMin = com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeAbs(Long.MIN_VALUE)
        assertEquals(Long.MAX_VALUE, safeAbsMin)

        val saturatedAdd = com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeAdd(Long.MAX_VALUE, 100L)
        assertEquals(Long.MAX_VALUE, saturatedAdd)

        val saturatedSub = com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeSubtract(Long.MIN_VALUE, 100L)
        assertEquals(Long.MIN_VALUE, saturatedSub)
    }

    @Test
    fun `uncategorized filter ignores non-categorizable transactions like transfers and reconciliations`() {
        val transferTx = createSampleTransaction(1L, 1000L, null).copy(type = TransactionType.TRANSFER, transferToAccountId = 2L)
        val ccSettlementTx = createSampleTransaction(2L, 2000L, null).copy(type = TransactionType.CC_SETTLEMENT, transferToAccountId = 3L)
        val reconciliationTx = createSampleTransaction(3L, 500L, null).copy(type = TransactionType.RECONCILIATION)
        val openingTx = createSampleTransaction(4L, 10000L, null).copy(type = TransactionType.OPENING_BALANCE)
        val expenseTx = createSampleTransaction(5L, 300L, null).copy(type = TransactionType.EXPENSE)

        val list = listOf(transferTx, ccSettlementTx, reconciliationTx, openingTx, expenseTx)
        val filter = TransactionSearchFilter(categoryIds = setOf(UNCATEGORIZED_CATEGORY_ID))

        val result = applyFilter(list, filter)
        assertEquals("Only EXPENSE without category should match", listOf(5L), result.map { it.id })
    }

    @Test
    fun `uncategorized filter ignores split transactions where all splits are categorized`() {
        val fullyCategorizedSplit = createSampleTransaction(1L, 5000L, null).copy(
            isSplit = true,
            splits = listOf(
                com.folioledger.app.domain.model.SplitLineItem(id = 1L, categoryId = 10L, categoryName = "Food", amount = MinorMoney(2000L)),
                com.folioledger.app.domain.model.SplitLineItem(id = 2L, categoryId = 20L, categoryName = "Transport", amount = MinorMoney(3000L)),
            ),
        )
        val partiallyUncategorizedSplit = createSampleTransaction(2L, 5000L, null).copy(
            isSplit = true,
            splits = listOf(
                com.folioledger.app.domain.model.SplitLineItem(id = 3L, categoryId = 10L, categoryName = "Food", amount = MinorMoney(2000L)),
                com.folioledger.app.domain.model.SplitLineItem(id = 4L, categoryId = null, categoryName = "Uncategorized", amount = MinorMoney(3000L)),
            ),
        )

        val list = listOf(fullyCategorizedSplit, partiallyUncategorizedSplit)
        val filter = TransactionSearchFilter(categoryIds = setOf(UNCATEGORIZED_CATEGORY_ID))

        val result = applyFilter(list, filter)
        assertEquals("Only split with null category split line should match", listOf(2L), result.map { it.id })
    }

    @Test
    fun `matchesSearchFilter handles blank tags and Turkish locale case folding cleanly`() {
        val previousLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            val tx = createSampleTransaction(1L, 1000L, 10L).copy(
                payeeOrDescription = "IKEA Store",
                tags = listOf("Home"),
            )
            // Blank tag entries in filter must be ignored rather than rejecting everything
            val blankTagFilter = TransactionSearchFilter(tags = setOf("   "))
            assertEquals(listOf(1L), applyFilter(listOf(tx), blankTagFilter).map { it.id })

            // Case-insensitive query on 'I'/'i' under tr-TR must still match via Locale.ROOT
            val queryFilter = TransactionSearchFilter(queryText = "ikea")
            assertEquals(listOf(1L), applyFilter(listOf(tx), queryFilter).map { it.id })
        } finally {
            java.util.Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `multi-currency scoped account filter converts primary and cross-currency amounts via primaryCurrencyCode`() {
        val txInrMatch = TransactionItem(
            id = 1L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 1700000000000L,
            ledgerDateEpochDay = 20000L,
            amount = MinorMoney(83000L), // ₹830.00 @ 83 = $10.00 USD
            currencyCode = "INR",
            accountId = 1L,
            accountName = "INR Cash",
        )
        val txInrTooSmall = TransactionItem(
            id = 2L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 1700000000000L,
            ledgerDateEpochDay = 20000L,
            amount = MinorMoney(10000L), // ₹100.00 @ 83 = $1.20 USD
            currencyCode = "INR",
            accountId = 1L,
            accountName = "INR Cash",
        )
        val txEurMatch = TransactionItem(
            id = 3L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 1700000000000L,
            ledgerDateEpochDay = 20000L,
            amount = MinorMoney(1000L), // €10.00 * 90 / 83 = $10.84 USD
            currencyCode = "EUR",
            accountId = 2L,
            accountName = "Euro Card",
        )

        val filter = TransactionSearchFilter(
            minAmount = MinorMoney(500L), // $5.00 USD
            maxAmount = MinorMoney(1500L), // $15.00 USD
            targetCurrencyCode = "USD",
            primaryCurrencyCode = "INR",
            secondaryExchangeRates = mapOf("USD" to 83.0, "EUR" to 90.0),
        )

        val result = applyFilter(listOf(txInrMatch, txInrTooSmall, txEurMatch), filter)
        assertEquals(listOf(1L, 3L), result.map { it.id })
    }

    @Test
    fun `keyword search with leading hash matches stored tags and zero bounds match zero amounts`() {
        val zeroTx = createSampleTransaction(1L, 0L, 10L).copy(tags = listOf("Vacation"))
        val positiveTx = createSampleTransaction(2L, 5000L, 10L).copy(tags = listOf("Work"))

        val tagQueryFilter = TransactionSearchFilter(queryText = "#vac")
        assertEquals(listOf(1L), applyFilter(listOf(zeroTx, positiveTx), tagQueryFilter).map { it.id })

        val zeroBoundFilter = TransactionSearchFilter(
            minAmount = MinorMoney.ZERO,
            maxAmount = MinorMoney.ZERO,
        )
        assertEquals(listOf(1L), applyFilter(listOf(zeroTx, positiveTx), zeroBoundFilter).map { it.id })
    }
}
