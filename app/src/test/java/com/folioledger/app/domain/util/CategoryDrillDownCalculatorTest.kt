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

import com.folioledger.app.domain.model.CategoryNode
import com.folioledger.app.domain.model.CategorySpendStat
import com.folioledger.app.domain.model.CategoryType
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.MonthlyTrendBar
import com.folioledger.app.domain.model.SplitLineItem
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.model.UNCATEGORIZED_CATEGORY_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryDrillDownCalculatorTest {

    @Test
    fun `attachComparativeDeltas calculates correct percentage changes`() {
        val current = listOf(
            CategorySpendStat(categoryId = 1L, categoryName = "Groceries", colorHex = "#000", iconName = "cart", netAmount = MinorMoney(15000L), percentageOfTotal = 50f, transactionCount = 5),
            CategorySpendStat(categoryId = 2L, categoryName = "Dining", colorHex = "#000", iconName = "food", netAmount = MinorMoney(8000L), percentageOfTotal = 30f, transactionCount = 3),
            CategorySpendStat(categoryId = 3L, categoryName = "New Sub", colorHex = "#000", iconName = "sub", netAmount = MinorMoney(2000L), percentageOfTotal = 10f, transactionCount = 1),
        )
        val prior = listOf(
            CategorySpendStat(categoryId = 1L, categoryName = "Groceries", colorHex = "#000", iconName = "cart", netAmount = MinorMoney(10000L), percentageOfTotal = 40f, transactionCount = 4),
            CategorySpendStat(categoryId = 2L, categoryName = "Dining", colorHex = "#000", iconName = "food", netAmount = MinorMoney(10000L), percentageOfTotal = 40f, transactionCount = 4),
            CategorySpendStat(categoryId = 3L, categoryName = "New Sub", colorHex = "#000", iconName = "sub", netAmount = MinorMoney.ZERO, percentageOfTotal = 0f, transactionCount = 0),
        )

        val result = CategoryDrillDownCalculator.attachComparativeDeltas(current, prior)

        // Groceries: 15000 vs 10000 -> +50%
        val groceries = result.first { it.categoryId == 1L }
        assertEquals(50f, groceries.changePercentage!!, 0.01f)
        assertEquals(MinorMoney(10000L), groceries.priorPeriodAmount)

        // Dining: 8000 vs 10000 -> -20%
        val dining = result.first { it.categoryId == 2L }
        assertEquals(-20f, dining.changePercentage!!, 0.01f)
        assertEquals(MinorMoney(10000L), dining.priorPeriodAmount)

        // New Sub: 2000 vs 0 -> 100%
        val newSub = result.first { it.categoryId == 3L }
        assertEquals(100f, newSub.changePercentage!!, 0.01f)
    }

    @Test
    fun `collectCategoryAndSubcategoryIds returns parent and all children`() {
        val sub1 = CategoryNode(id = 11L, name = "Organic", type = CategoryType.EXPENSE, parentId = 1L, iconName = "eco", colorHex = "#000")
        val sub2 = CategoryNode(id = 12L, name = "Snacks", type = CategoryType.EXPENSE, parentId = 1L, iconName = "fastfood", colorHex = "#000")
        val parent = CategoryNode(id = 1L, name = "Groceries", type = CategoryType.EXPENSE, parentId = null, iconName = "shopping_cart", colorHex = "#000", subcategories = listOf(sub1, sub2))

        val tree = listOf(parent)

        // Target parent: should return {1, 11, 12}
        val parentIds = CategoryDrillDownCalculator.collectCategoryAndSubcategoryIds(tree, targetCategoryId = 1L)
        assertEquals(setOf(1L, 11L, 12L), parentIds)

        // Target child: should return {11}
        val childIds = CategoryDrillDownCalculator.collectCategoryAndSubcategoryIds(tree, targetCategoryId = 11L)
        assertEquals(setOf(11L), childIds)

        // Unknown: fallback to {999}
        val unknownIds = CategoryDrillDownCalculator.collectCategoryAndSubcategoryIds(tree, targetCategoryId = 999L)
        assertEquals(setOf(999L), unknownIds)
    }

    @Test
    fun `findCategoryNode locates parent and child nodes`() {
        val sub1 = CategoryNode(id = 21L, name = "Sub", type = CategoryType.EXPENSE, parentId = 2L, iconName = "icon", colorHex = "#000")
        val parent = CategoryNode(id = 2L, name = "Parent", type = CategoryType.EXPENSE, parentId = null, iconName = "icon", colorHex = "#000", subcategories = listOf(sub1))
        val tree = listOf(parent)

        assertNull(CategoryDrillDownCalculator.findCategoryNode(tree, null))
        assertNull(CategoryDrillDownCalculator.findCategoryNode(tree, 999L))

        val foundParent = CategoryDrillDownCalculator.findCategoryNode(tree, 2L)
        assertNotNull(foundParent)
        assertEquals("Parent", foundParent!!.name)

        val foundChild = CategoryDrillDownCalculator.findCategoryNode(tree, 21L)
        assertNotNull(foundChild)
        assertEquals("Sub", foundChild!!.name)
    }

    @Test
    fun `filterMatchingTransactions matches category, splits, and avoids ID-name collisions`() {
        val tx1 = createDummyTransaction(id = 1L, categoryId = 10L, type = TransactionType.EXPENSE, amount = MinorMoney(500L), ledgerDateEpochDay = 100L)
        val tx2SameNameOtherId = createDummyTransaction(id = 2L, categoryId = 99L, type = TransactionType.EXPENSE, amount = MinorMoney(300L), categoryName = "Shopping", ledgerDateEpochDay = 101L)
        val tx3 = createDummyTransaction(id = 3L, categoryId = 10L, type = TransactionType.INCOME, amount = MinorMoney(1000L), ledgerDateEpochDay = 102L)
        val tx4FeeTransfer = createDummyTransaction(id = 4L, categoryId = 10L, type = TransactionType.TRANSFER, amount = MinorMoney(5000L), ledgerDateEpochDay = 103L)
            .copy(transferFee = MinorMoney(250L))

        val matchingIds = setOf(10L)

        val expenseMatches = CategoryDrillDownCalculator.filterMatchingTransactions(
            transactions = listOf(tx1, tx2SameNameOtherId, tx3, tx4FeeTransfer),
            categoryType = CategoryType.EXPENSE,
            categoryName = "Shopping",
            matchingCategoryIds = matchingIds,
        )

        // tx1 matches categoryId 10L (expense); tx4FeeTransfer matches fee-bearing transfer on 10L;
        // tx2SameNameOtherId has categoryId 99L != 10L (no cross-category collision); tx3 is INCOME
        assertEquals(2, expenseMatches.size)
        assertEquals(4L, expenseMatches[0].id)
        assertEquals(1L, expenseMatches[1].id)

        // Name fallback applies when matchingCategoryIds is empty
        val nameFallbackMatches = CategoryDrillDownCalculator.filterMatchingTransactions(
            transactions = listOf(tx1, tx2SameNameOtherId),
            categoryType = CategoryType.EXPENSE,
            categoryName = "Shopping",
            matchingCategoryIds = emptySet(),
        )
        assertEquals(listOf(2L), nameFallbackMatches.map { it.id })
    }

    @Test
    fun `computeCategoryHistoricalTrend calculates period expense and refund netting`() {
        val bars = listOf(
            MonthlyTrendBar(periodLabel = "Oct", startEpochDay = 100L, endEpochDay = 130L, totalIncome = MinorMoney.ZERO, totalExpense = MinorMoney.ZERO, netSavings = MinorMoney.ZERO),
            MonthlyTrendBar(periodLabel = "Nov", startEpochDay = 131L, endEpochDay = 160L, totalIncome = MinorMoney.ZERO, totalExpense = MinorMoney.ZERO, netSavings = MinorMoney.ZERO),
        )

        val txs = listOf(
            createDummyTransaction(id = 1L, categoryId = 10L, type = TransactionType.EXPENSE, amount = MinorMoney(5000L), ledgerDateEpochDay = 110L),
            createDummyTransaction(id = 2L, categoryId = 10L, type = TransactionType.REFUND, amount = MinorMoney(1000L), ledgerDateEpochDay = 120L),
            createDummyTransaction(id = 3L, categoryId = 10L, type = TransactionType.EXPENSE, amount = MinorMoney(3000L), ledgerDateEpochDay = 140L),
        )

        val result = CategoryDrillDownCalculator.computeCategoryHistoricalTrend(
            trendBars = bars,
            allTransactions = txs,
            categoryType = CategoryType.EXPENSE,
            categoryName = "Tech",
            matchingCategoryIds = setOf(10L),
        )

        assertEquals(2, result.size)
        // Oct: 5000 expense - 1000 refund = 4000 net expense
        assertEquals(MinorMoney(4000L), result[0].totalExpense)
        // Nov: 3000 expense
        assertEquals(MinorMoney(3000L), result[1].totalExpense)
    }

    @Test
    fun `filterCategoryTree with blank query returns all categories unchanged`() {
        val sub = CategoryNode(id = 11L, name = "Organic", type = CategoryType.EXPENSE, parentId = 1L, iconName = "eco", colorHex = "#000")
        val parent = CategoryNode(id = 1L, name = "Groceries", type = CategoryType.EXPENSE, parentId = null, iconName = "cart", colorHex = "#000", subcategories = listOf(sub))
        val tree = listOf(parent)

        assertEquals(tree, CategoryDrillDownCalculator.filterCategoryTree(tree, ""))
        assertEquals(tree, CategoryDrillDownCalculator.filterCategoryTree(tree, "   "))
    }

    @Test
    fun `filterCategoryTree matching parent name includes parent and all subcategories`() {
        val sub1 = CategoryNode(id = 11L, name = "Organic", type = CategoryType.EXPENSE, parentId = 1L, iconName = "eco", colorHex = "#000")
        val sub2 = CategoryNode(id = 12L, name = "Snacks", type = CategoryType.EXPENSE, parentId = 1L, iconName = "fastfood", colorHex = "#000")
        val parent1 = CategoryNode(id = 1L, name = "Groceries", type = CategoryType.EXPENSE, parentId = null, iconName = "cart", colorHex = "#000", subcategories = listOf(sub1, sub2))
        val parent2 = CategoryNode(id = 2L, name = "Utilities", type = CategoryType.EXPENSE, parentId = null, iconName = "bolt", colorHex = "#000")
        val tree = listOf(parent1, parent2)

        val filtered = CategoryDrillDownCalculator.filterCategoryTree(tree, "grocer")
        assertEquals(1, filtered.size)
        assertEquals("Groceries", filtered[0].name)
        assertEquals(2, filtered[0].subcategories.size)
    }

    @Test
    fun `filterCategoryTree matching subcategory only includes parent with matching subcategory`() {
        val sub1 = CategoryNode(id = 11L, name = "Organic Veggies", type = CategoryType.EXPENSE, parentId = 1L, iconName = "eco", colorHex = "#000")
        val sub2 = CategoryNode(id = 12L, name = "Snacks", type = CategoryType.EXPENSE, parentId = 1L, iconName = "fastfood", colorHex = "#000")
        val parent = CategoryNode(id = 1L, name = "Groceries", type = CategoryType.EXPENSE, parentId = null, iconName = "cart", colorHex = "#000", subcategories = listOf(sub1, sub2))
        val tree = listOf(parent)

        val filtered = CategoryDrillDownCalculator.filterCategoryTree(tree, "veggie")
        assertEquals(1, filtered.size)
        assertEquals(1, filtered[0].subcategories.size)
        assertEquals("Organic Veggies", filtered[0].subcategories[0].name)
    }

    @Test
    fun `filterCategoryTree with no match returns empty list`() {
        val sub = CategoryNode(id = 11L, name = "Organic", type = CategoryType.EXPENSE, parentId = 1L, iconName = "eco", colorHex = "#000")
        val parent = CategoryNode(id = 1L, name = "Groceries", type = CategoryType.EXPENSE, parentId = null, iconName = "cart", colorHex = "#000", subcategories = listOf(sub))
        val tree = listOf(parent)

        val filtered = CategoryDrillDownCalculator.filterCategoryTree(tree, "NonExistentXYZ")
        assertEquals(0, filtered.size)
    }

    @Test
    fun `computeSubcategoryBreakdown aggregates subcategories and calculates distribution correctly`() {
        val tx1 = createDummyTransaction(id = 1L, categoryId = 10L, type = TransactionType.EXPENSE, amount = MinorMoney(6000L), categoryName = "Fruits", ledgerDateEpochDay = 100L)
        val tx2 = createDummyTransaction(id = 2L, categoryId = 10L, type = TransactionType.EXPENSE, amount = MinorMoney(4000L), categoryName = "Vegetables", ledgerDateEpochDay = 101L)

        val breakdown = CategoryDrillDownCalculator.computeSubcategoryBreakdown(
            transactions = listOf(tx1, tx2),
            parentNetAmount = MinorMoney(10000L),
            parentCategoryName = "Groceries",
        )

        assertEquals(2, breakdown.size)
        assertEquals("Fruits", breakdown[0].name)
        assertEquals(MinorMoney(6000L), breakdown[0].totalAmount)
        assertEquals(60f, breakdown[0].percentageOfParent, 0.01f)
        assertEquals(1, breakdown[0].transactionCount)

        assertEquals("Vegetables", breakdown[1].name)
        assertEquals(MinorMoney(4000L), breakdown[1].totalAmount)
        assertEquals(40f, breakdown[1].percentageOfParent, 0.01f)
        assertEquals(1, breakdown[1].transactionCount)
    }

    @Test
    fun `computeCategoryHistoricalTrend with split transactions sums only matching splits`() {
        val bars = listOf(
            MonthlyTrendBar(periodLabel = "Oct", startEpochDay = 100L, endEpochDay = 130L, totalIncome = MinorMoney.ZERO, totalExpense = MinorMoney.ZERO, netSavings = MinorMoney.ZERO),
        )

        val tx1 = createDummyTransaction(
            id = 1L,
            categoryId = 10L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(2000L),
            ledgerDateEpochDay = 110L,
        )
        val split1 = SplitLineItem(categoryId = 10L, categoryName = "Groceries", amount = MinorMoney(3000L))
        val split2 = SplitLineItem(categoryId = 99L, categoryName = "Gadgets", amount = MinorMoney(7000L))
        val tx2 = createDummyTransaction(
            id = 2L,
            categoryId = null,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(10000L),
            ledgerDateEpochDay = 115L,
            isSplit = true,
            splits = listOf(split1, split2),
        )

        val result = CategoryDrillDownCalculator.computeCategoryHistoricalTrend(
            trendBars = bars,
            allTransactions = listOf(tx1, tx2),
            categoryType = CategoryType.EXPENSE,
            categoryName = "Groceries",
            matchingCategoryIds = setOf(10L),
        )

        assertEquals(1, result.size)
        // tx1 (2000) + tx2 split1 (3000) = 5000 (NOT 2000 + 10000 = 12000)
        assertEquals(MinorMoney(5000L), result[0].totalExpense)
    }

    @Test
    fun `computeSubcategoryBreakdown isolates matching category splits and excludes unrelated categories`() {
        val split1 = SplitLineItem(categoryId = 11L, categoryName = "Organic Fruits", amount = MinorMoney(3000L))
        val split2 = SplitLineItem(categoryId = 99L, categoryName = "Electronics", amount = MinorMoney(7000L))
        val tx = createDummyTransaction(
            id = 1L,
            categoryId = null,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(10000L),
            ledgerDateEpochDay = 100L,
            isSplit = true,
            splits = listOf(split1, split2),
        )

        val breakdown = CategoryDrillDownCalculator.computeSubcategoryBreakdown(
            transactions = listOf(tx),
            parentNetAmount = MinorMoney(3000L),
            parentCategoryName = "Groceries",
            matchingCategoryIds = setOf(10L, 11L),
        )

        assertEquals(1, breakdown.size)
        assertEquals("Organic Fruits", breakdown[0].name)
        assertEquals(MinorMoney(3000L), breakdown[0].totalAmount)
        assertEquals(100f, breakdown[0].percentageOfParent, 0.01f)
        assertEquals(1, breakdown[0].transactionCount)
    }

    @Test
    fun `computeSubcategoryBreakdown nets refunds and filters out fully refunded subcategories`() {
        val txExpense1 = createDummyTransaction(id = 1L, categoryId = 11L, type = TransactionType.EXPENSE, amount = MinorMoney(5000L), categoryName = "Clothing", ledgerDateEpochDay = 100L)
        val txRefund1 = createDummyTransaction(id = 2L, categoryId = 11L, type = TransactionType.REFUND, amount = MinorMoney(2000L), categoryName = "Clothing", ledgerDateEpochDay = 101L)
        val txExpense2 = createDummyTransaction(id = 3L, categoryId = 12L, type = TransactionType.EXPENSE, amount = MinorMoney(1500L), categoryName = "Shoes", ledgerDateEpochDay = 102L)
        val txRefund2 = createDummyTransaction(id = 4L, categoryId = 12L, type = TransactionType.REFUND, amount = MinorMoney(1500L), categoryName = "Shoes", ledgerDateEpochDay = 103L)

        val breakdown = CategoryDrillDownCalculator.computeSubcategoryBreakdown(
            transactions = listOf(txExpense1, txRefund1, txExpense2, txRefund2),
            parentNetAmount = MinorMoney(3000L),
            parentCategoryName = "Apparel",
            matchingCategoryIds = setOf(10L, 11L, 12L),
        )

        // Shoes (1500 - 1500 = 0) is filtered out
        // Clothing (5000 - 2000 = 3000) remains, transactionCount = 1 (refund does not inflate purchase count)
        assertEquals(1, breakdown.size)
        assertEquals("Clothing", breakdown[0].name)
        assertEquals(MinorMoney(3000L), breakdown[0].totalAmount)
        assertEquals(100f, breakdown[0].percentageOfParent, 0.01f)
        assertEquals(1, breakdown[0].transactionCount)
    }

    @Test
    fun `computeCategoryHistoricalTrend with multi-currency converts secondary currency to primary`() {
        val bars = listOf(
            MonthlyTrendBar(periodLabel = "Oct", startEpochDay = 100L, endEpochDay = 130L, totalIncome = MinorMoney.ZERO, totalExpense = MinorMoney.ZERO, netSavings = MinorMoney.ZERO),
        )

        // 10.00 USD @ 80.0 rate to INR = 800.00 INR (80000 minor)
        val tx = createDummyTransaction(
            id = 1L,
            categoryId = 10L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(1000L), // $10.00
            ledgerDateEpochDay = 110L,
            currencyCode = "USD",
        )

        val secondaryCurrencies = listOf(
            com.folioledger.app.domain.model.SecondaryCurrency(code = "USD", exchangeRateToPrimary = 80.0),
        )

        val result = CategoryDrillDownCalculator.computeCategoryHistoricalTrend(
            trendBars = bars,
            allTransactions = listOf(tx),
            categoryType = CategoryType.EXPENSE,
            categoryName = "Cloud",
            matchingCategoryIds = setOf(10L),
            primaryCurrencyCode = "INR",
            secondaryCurrencies = secondaryCurrencies,
        )

        assertEquals(1, result.size)
        assertEquals(MinorMoney(80000L), result[0].totalExpense)
    }

    @Test
    fun `computeSubcategoryBreakdown with multi-currency converts secondary currency to primary`() {
        // 20.00 USD @ 80.0 rate = 1600.00 INR (160000 minor)
        val tx = createDummyTransaction(
            id = 1L,
            categoryId = 10L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(2000L), // $20.00
            categoryName = "SaaS Subscriptions",
            ledgerDateEpochDay = 100L,
            currencyCode = "USD",
        )

        val secondaryCurrencies = listOf(
            com.folioledger.app.domain.model.SecondaryCurrency(code = "USD", exchangeRateToPrimary = 80.0),
        )

        val breakdown = CategoryDrillDownCalculator.computeSubcategoryBreakdown(
            transactions = listOf(tx),
            parentNetAmount = MinorMoney(160000L),
            parentCategoryName = "Software",
            matchingCategoryIds = setOf(10L),
            primaryCurrencyCode = "INR",
            secondaryCurrencies = secondaryCurrencies,
        )

        assertEquals(1, breakdown.size)
        assertEquals("SaaS Subscriptions", breakdown[0].name)
        assertEquals(MinorMoney(160000L), breakdown[0].totalAmount)
        assertEquals(100f, breakdown[0].percentageOfParent, 0.01f)
        assertEquals(1, breakdown[0].transactionCount)
    }

    @Test
    fun `computeSubcategoryBreakdown includes transferFee on non-split expenses`() {
        val tx = createDummyTransaction(
            id = 1L,
            categoryId = 10L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(10000L),
            categoryName = "Online Services",
            ledgerDateEpochDay = 100L,
        ).copy(transferFee = MinorMoney(500L))

        val breakdown = CategoryDrillDownCalculator.computeSubcategoryBreakdown(
            transactions = listOf(tx),
            parentNetAmount = MinorMoney(10500L),
            parentCategoryName = "Online Services",
            matchingCategoryIds = setOf(10L),
        )

        assertEquals(1, breakdown.size)
        assertEquals(MinorMoney(10500L), breakdown[0].totalAmount)
        assertEquals(100f, breakdown[0].percentageOfParent, 0.01f)
    }

    @Test
    fun `computeSubcategoryBreakdown matches split with null categoryId via uncategorized id or name fallback`() {
        val splitItem = SplitLineItem(
            categoryId = null,
            categoryName = "Dining",
            amount = MinorMoney(4000L),
        )
        val tx = createDummyTransaction(
            id = 1L,
            categoryId = 10L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(4000L),
            categoryName = "Food",
            ledgerDateEpochDay = 100L,
            isSplit = true,
            splits = listOf(splitItem),
        )

        val breakdownByUncat = CategoryDrillDownCalculator.computeSubcategoryBreakdown(
            transactions = listOf(tx),
            parentNetAmount = MinorMoney(4000L),
            parentCategoryName = "Uncategorized",
            matchingCategoryIds = setOf(UNCATEGORIZED_CATEGORY_ID),
        )
        assertEquals(1, breakdownByUncat.size)
        assertEquals("Dining", breakdownByUncat[0].name)
        assertEquals(MinorMoney(4000L), breakdownByUncat[0].totalAmount)

        val breakdownByNameFallback = CategoryDrillDownCalculator.computeSubcategoryBreakdown(
            transactions = listOf(tx),
            parentNetAmount = MinorMoney(4000L),
            parentCategoryName = "Dining",
            matchingCategoryIds = emptySet(),
        )
        assertEquals(1, breakdownByNameFallback.size)
        assertEquals("Dining", breakdownByNameFallback[0].name)
    }

    @Test
    fun `computeSubcategoryBreakdown retains splits when matchingCategoryIds is empty`() {
        val split1 = SplitLineItem(categoryId = 11L, categoryName = "Groceries", amount = MinorMoney(3000L))
        val split2 = SplitLineItem(categoryId = 12L, categoryName = "Produce", amount = MinorMoney(2000L))
        val tx = createDummyTransaction(
            id = 1L,
            categoryId = 10L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(5000L),
            categoryName = "Food",
            ledgerDateEpochDay = 100L,
            isSplit = true,
            splits = listOf(split1, split2),
        )

        // Calling with default matchingCategoryIds = emptySet()
        val breakdown = CategoryDrillDownCalculator.computeSubcategoryBreakdown(
            transactions = listOf(tx),
            parentNetAmount = MinorMoney(5000L),
            parentCategoryName = "Food",
            matchingCategoryIds = emptySet(),
        )

        assertEquals(2, breakdown.size)
        val names = breakdown.map { it.name }.toSet()
        assertTrue(names.contains("Groceries"))
        assertTrue(names.contains("Produce"))
    }

    @Test
    fun computeSubcategoryBreakdown_zeroParentNetAmount_returnsFinitePercentage() {
        val tx = createDummyTransaction(
            id = 1L,
            categoryId = 10L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(1000L),
            categoryName = "Coffee",
            ledgerDateEpochDay = 100L,
        )
        val breakdown = CategoryDrillDownCalculator.computeSubcategoryBreakdown(
            transactions = listOf(tx),
            parentNetAmount = MinorMoney.ZERO,
            parentCategoryName = "Food",
        )
        assertEquals(1, breakdown.size)
        assertTrue(breakdown[0].percentageOfParent.isFinite())
    }

    @Test
    fun `uncategorized drilldown matches null categoryId txns and splits and trend`() {
        val uncatExpense = createDummyTransaction(
            id = 1L,
            categoryId = null,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(2500L),
            categoryName = null,
            ledgerDateEpochDay = 110L,
        )
        val categorizedExpense = createDummyTransaction(
            id = 2L,
            categoryId = 55L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(9000L),
            categoryName = "Bills",
            ledgerDateEpochDay = 111L,
        )
        val uncatFeeTransfer = createDummyTransaction(
            id = 3L,
            categoryId = null,
            type = TransactionType.CC_SETTLEMENT,
            amount = MinorMoney(50000L),
            categoryName = null,
            ledgerDateEpochDay = 112L,
        ).copy(transferFee = MinorMoney(500L))

        val matching = CategoryDrillDownCalculator.filterMatchingTransactions(
            transactions = listOf(uncatExpense, categorizedExpense, uncatFeeTransfer),
            categoryType = CategoryType.EXPENSE,
            categoryName = "Uncategorized",
            matchingCategoryIds = setOf(UNCATEGORIZED_CATEGORY_ID),
        )
        assertEquals(listOf(3L, 1L), matching.map { it.id })

        val bars = listOf(
            MonthlyTrendBar("Period", 100L, 120L, MinorMoney.ZERO, MinorMoney.ZERO, MinorMoney.ZERO),
        )
        val trend = CategoryDrillDownCalculator.computeCategoryHistoricalTrend(
            trendBars = bars,
            allTransactions = listOf(uncatExpense, categorizedExpense, uncatFeeTransfer),
            categoryType = CategoryType.EXPENSE,
            categoryName = "Uncategorized",
            matchingCategoryIds = setOf(UNCATEGORIZED_CATEGORY_ID),
        )
        assertEquals(MinorMoney(3000L), trend.first().totalExpense)
    }

    @Test
    fun `categorized split with null parent categoryId does not leak into uncategorized and split fee lands in first split subcategory`() {
        val categorizedSplit = createDummyTransaction(
            id = 10L,
            categoryId = null,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(1500L),
            categoryName = null,
            ledgerDateEpochDay = 115L,
            isSplit = true,
            splits = listOf(
                SplitLineItem(categoryId = 10L, categoryName = "Groceries", amount = MinorMoney(1000L)),
                SplitLineItem(categoryId = 20L, categoryName = "Dining", amount = MinorMoney(500L)),
            ),
        ).copy(transferFee = MinorMoney(200L))

        val uncategorizedMatches = CategoryDrillDownCalculator.filterMatchingTransactions(
            transactions = listOf(categorizedSplit),
            categoryType = CategoryType.EXPENSE,
            categoryName = "Uncategorized",
            matchingCategoryIds = setOf(UNCATEGORIZED_CATEGORY_ID),
        )
        assertEquals(emptyList<TransactionItem>(), uncategorizedMatches)

        val groceriesMatches = CategoryDrillDownCalculator.filterMatchingTransactions(
            transactions = listOf(categorizedSplit),
            categoryType = CategoryType.EXPENSE,
            categoryName = "Food",
            matchingCategoryIds = setOf(10L),
        )
        assertEquals(listOf(10L), groceriesMatches.map { it.id })

        val breakdown = CategoryDrillDownCalculator.computeSubcategoryBreakdown(
            transactions = groceriesMatches,
            parentNetAmount = MinorMoney(1200L),
            parentCategoryName = "Food",
            matchingCategoryIds = setOf(10L),
        )
        assertEquals(1, breakdown.size)
        assertEquals("Groceries", breakdown.first().name)
        assertEquals(MinorMoney(1200L), breakdown.first().totalAmount)
    }

    private fun createDummyTransaction(
        id: Long,
        categoryId: Long?,
        type: TransactionType,
        amount: MinorMoney,
        categoryName: String? = null,
        ledgerDateEpochDay: Long,
        isSplit: Boolean = false,
        splits: List<SplitLineItem> = emptyList(),
        currencyCode: String = "INR",
    ): TransactionItem = TransactionItem(
        id = id,
        accountId = 1L,
        accountName = "Checking",
        currencyCode = currencyCode,
        categoryId = categoryId,
        categoryName = categoryName,
        categoryIconName = "icon",
        categoryColorHex = "#000",
        parentCategoryName = null,
        amount = amount,
        type = type,
        isSplit = isSplit,
        splits = splits,
        payeeOrDescription = "Store",
        note = "",
        ledgerDateEpochDay = ledgerDateEpochDay,
        timestampEpochMillis = ledgerDateEpochDay * 86_400_000L,
    )
}
