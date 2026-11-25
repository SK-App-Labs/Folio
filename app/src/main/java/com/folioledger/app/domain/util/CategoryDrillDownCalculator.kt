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
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.SubcategoryBreakdownItem
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.model.UNCATEGORIZED_CATEGORY_ID
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeAbs
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeAdd
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeNegate
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeSubtract
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

object CategoryDrillDownCalculator {

    fun attachComparativeDeltas(
        currentList: List<CategorySpendStat>,
        priorList: List<CategorySpendStat>
    ): List<CategorySpendStat> {
        val priorMap = priorList.associateBy { it.categoryId }
        return currentList.map { current ->
            val prior = priorMap[current.categoryId]
            val priorAmount = prior?.netAmount
            val changePct = if (priorAmount != null && priorAmount.amountMinor > 0L) {
                BigDecimal.valueOf(safeSubtract(current.netAmount.amountMinor, priorAmount.amountMinor))
                    .multiply(BigDecimal.valueOf(100L))
                    .divide(BigDecimal.valueOf(priorAmount.amountMinor), 2, RoundingMode.HALF_UP)
                    .toFloat()
            } else if (priorAmount != null && priorAmount.amountMinor == 0L && current.netAmount.amountMinor > 0L) {
                100f
            } else {
                null
            }
            current.copy(
                priorPeriodAmount = priorAmount,
                changePercentage = changePct
            )
        }
    }

    fun collectCategoryAndSubcategoryIds(
        tree: List<CategoryNode>,
        targetCategoryId: Long
    ): Set<Long> {
        for ((id, _, _, _, _, _, subcategories) in tree) {
            if (id == targetCategoryId) {
                return buildSet {
                    add(id)
                    subcategories.forEach { add(it.id) }
                }
            }
            if (subcategories.any { it.id == targetCategoryId }) {
                return setOf(targetCategoryId)
            }
        }
        return setOf(targetCategoryId)
    }

    fun findCategoryNode(tree: List<CategoryNode>, categoryId: Long?): CategoryNode? {
        if (categoryId == null) return null
        for (parent in tree) {
            if (parent.id == categoryId) return parent
            val matchingSub = parent.subcategories.firstOrNull { it.id == categoryId }
            if (matchingSub != null) return matchingSub
        }
        return null
    }

    /**
     * Filters a category tree by search query string:
     * - Returns full tree untouched when query is blank.
     * - Retains full parent and all subcategories if parent name matches.
     * - Retains parent with matching subcategories if only subcategories match.
     * - Filters out nodes where neither parent nor any subcategory matches.
     */
    fun filterCategoryTree(
        categories: List<CategoryNode>,
        searchQuery: String
    ): List<CategoryNode> {
        val trimmed = searchQuery.trim()
        if (trimmed.isEmpty()) return categories

        return categories.mapNotNull { parent ->
            val parentMatches = parent.name.contains(trimmed, ignoreCase = true)
            val matchingSubs = parent.subcategories.filter { sub ->
                sub.name.contains(trimmed, ignoreCase = true)
            }
            if (parentMatches || matchingSubs.isNotEmpty()) {
                parent.copy(
                    subcategories = if (parentMatches) parent.subcategories else matchingSubs
                )
            } else null
        }
    }

    private fun matchesCategorySelection(
        tx: TransactionItem,
        categoryName: String,
        matchingCategoryIds: Set<Long>
    ): Boolean {
        if (matchingCategoryIds.isNotEmpty()) {
            val includeUncategorized = UNCATEGORIZED_CATEGORY_ID in matchingCategoryIds
            if (tx.isSplit && tx.splits.isNotEmpty()) {
                val splitMatch = tx.splits.any { split ->
                    if (split.categoryId == null) includeUncategorized else split.categoryId in matchingCategoryIds
                }
                val splitFeeMatch = tx.type == TransactionType.EXPENSE &&
                    safeAbs(tx.transferFee.amountMinor) > 0L &&
                    ((tx.categoryId ?: tx.splits.firstOrNull()?.categoryId ?: UNCATEGORIZED_CATEGORY_ID) in matchingCategoryIds)
                return splitMatch || splitFeeMatch
            }
            return if (tx.categoryId == null) includeUncategorized else tx.categoryId in matchingCategoryIds
        }
        val nameMatch = tx.categoryName != null && tx.categoryName.equals(categoryName, ignoreCase = true)
        val parentNameMatch = tx.parentCategoryName != null && tx.parentCategoryName.equals(categoryName, ignoreCase = true)
        val splitNameMatch = tx.isSplit && tx.splits.any { it.categoryName.equals(categoryName, ignoreCase = true) }
        return nameMatch || parentNameMatch || splitNameMatch
    }

    private fun matchesTransactionTypeForCategory(
        tx: TransactionItem,
        categoryType: CategoryType
    ): Boolean = when (categoryType) {
        CategoryType.EXPENSE ->
            tx.type == TransactionType.EXPENSE ||
                tx.type == TransactionType.REFUND ||
                ((tx.type == TransactionType.TRANSFER || tx.type == TransactionType.CC_SETTLEMENT) &&
                    safeAbs(tx.transferFee.amountMinor) > 0L)
        CategoryType.INCOME -> tx.type == TransactionType.INCOME
    }

    fun filterMatchingTransactions(
        transactions: List<TransactionItem>,
        categoryType: CategoryType,
        categoryName: String,
        matchingCategoryIds: Set<Long>
    ): List<TransactionItem> {
        return transactions.filter { tx ->
            matchesTransactionTypeForCategory(tx, categoryType) &&
                matchesCategorySelection(tx, categoryName, matchingCategoryIds)
        }.sortedWith(
            compareByDescending<TransactionItem> { it.ledgerDateEpochDay }
                .thenByDescending { it.timestampEpochMillis }
        )
    }

    fun computeCategoryHistoricalTrend(
        trendBars: List<MonthlyTrendBar>,
        allTransactions: List<TransactionItem>,
        categoryType: CategoryType,
        categoryName: String,
        matchingCategoryIds: Set<Long>,
        primaryCurrencyCode: String = "INR",
        secondaryCurrencies: List<SecondaryCurrency> = emptyList()
    ): List<MonthlyTrendBar> {
        val primarySpec = SupportedCurrencies.fromCode(primaryCurrencyCode)
        val secondaryMap = secondaryCurrencies.associateBy { it.code.trim().uppercase(Locale.ROOT) }

        fun toPrimaryMinor(rawMinor: Long, currencyCode: String): Long {
            val sec = secondaryMap[currencyCode.trim().uppercase(Locale.ROOT)]
            return MoneyFormatterAndCalculator.convertToPrimaryMinor(
                sourceMinor = rawMinor,
                sourceCurrencyCode = currencyCode,
                primaryCurrencyCode = primarySpec.code,
                exchangeRate = sec?.exchangeRateToPrimary
            )
        }

        // Performance Optimization: Filter matching category transactions once instead of scanning allTransactions across every trend bar
        val matchingAllTxs = allTransactions.filter { tx ->
            matchesTransactionTypeForCategory(tx, categoryType) &&
                matchesCategorySelection(tx, categoryName, matchingCategoryIds)
        }

        return trendBars.map { bar ->
            var cycleExpenseMinor = 0L
            var cycleIncomeMinor = 0L

            for (tx in matchingAllTxs) {
                if (tx.ledgerDateEpochDay !in bar.startEpochDay..bar.endEpochDay) continue

                if (categoryType == CategoryType.EXPENSE &&
                    (tx.type == TransactionType.TRANSFER || tx.type == TransactionType.CC_SETTLEMENT)
                ) {
                    val feeMinor = toPrimaryMinor(safeAbs(tx.transferFee.amountMinor), tx.currencyCode)
                    cycleExpenseMinor = safeAdd(cycleExpenseMinor, feeMinor)
                    continue
                }

                val rawAmount = if (tx.isSplit && tx.splits.isNotEmpty()) {
                    val includeUncategorized = UNCATEGORIZED_CATEGORY_ID in matchingCategoryIds
                    val matchingSplits = tx.splits.filter { split ->
                        if (matchingCategoryIds.isNotEmpty()) {
                            if (split.categoryId == null) includeUncategorized else split.categoryId in matchingCategoryIds
                        } else {
                            split.categoryName.equals(categoryName, ignoreCase = true)
                        }
                    }
                    val splitSumMinor = matchingSplits.fold(0L) { acc, split ->
                        safeAdd(acc, toPrimaryMinor(safeAbs(split.amount.amountMinor), tx.currencyCode))
                    }
                    val feeTargetCatId = tx.categoryId ?: tx.splits.firstOrNull()?.categoryId
                    val includeSplitFee = tx.type == TransactionType.EXPENSE &&
                        safeAbs(tx.transferFee.amountMinor) > 0L &&
                        if (matchingCategoryIds.isNotEmpty()) {
                            (feeTargetCatId ?: UNCATEGORIZED_CATEGORY_ID) in matchingCategoryIds
                        } else {
                            tx.categoryName.equals(categoryName, ignoreCase = true) ||
                                tx.splits.firstOrNull()?.categoryName.equals(categoryName, ignoreCase = true)
                        }
                    if (includeSplitFee) {
                        val feeMinor = toPrimaryMinor(safeAbs(tx.transferFee.amountMinor), tx.currencyCode)
                        safeAdd(splitSumMinor, feeMinor)
                    } else {
                        splitSumMinor
                    }
                } else {
                    val fee = if (tx.type == TransactionType.EXPENSE) safeAbs(tx.transferFee.amountMinor) else 0L
                    toPrimaryMinor(safeAdd(safeAbs(tx.amount.amountMinor), fee), tx.currencyCode)
                }

                if (categoryType == CategoryType.EXPENSE) {
                    when (tx.type) {
                        TransactionType.EXPENSE -> cycleExpenseMinor = safeAdd(cycleExpenseMinor, rawAmount)
                        TransactionType.REFUND -> cycleExpenseMinor = safeSubtract(cycleExpenseMinor, rawAmount)
                        else -> Unit
                    }
                } else {
                    if (tx.type == TransactionType.INCOME) {
                        cycleIncomeMinor = safeAdd(cycleIncomeMinor, rawAmount)
                    }
                }
            }

            val safeIncome = cycleIncomeMinor.coerceAtLeast(0L)
            val safeExpense = cycleExpenseMinor.coerceAtLeast(0L)
            MonthlyTrendBar(
                periodLabel = bar.periodLabel,
                startEpochDay = bar.startEpochDay,
                endEpochDay = bar.endEpochDay,
                totalIncome = MinorMoney(safeIncome),
                totalExpense = MinorMoney(safeExpense),
                netSavings = MinorMoney(safeSubtract(safeIncome, safeExpense))
            )
        }
    }

    /**
     * Precomputes subcategory totals and distribution percentages on background dispatchers,
     * removing expensive grouping and sorting calculations from the UI thread when opening the bottom sheet.
     */
    fun computeSubcategoryBreakdown(
        transactions: List<TransactionItem>,
        parentNetAmount: MinorMoney,
        parentCategoryName: String,
        matchingCategoryIds: Set<Long> = emptySet(),
        primaryCurrencyCode: String = "INR",
        secondaryCurrencies: List<SecondaryCurrency> = emptyList()
    ): List<SubcategoryBreakdownItem> {
        val primarySpec = SupportedCurrencies.fromCode(primaryCurrencyCode)
        val secondaryMap = secondaryCurrencies.associateBy { it.code.trim().uppercase(Locale.ROOT) }

        fun toPrimaryMinor(rawMinor: Long, currencyCode: String): Long {
            val sec = secondaryMap[currencyCode.trim().uppercase(Locale.ROOT)]
            return MoneyFormatterAndCalculator.convertToPrimaryMinor(
                sourceMinor = rawMinor,
                sourceCurrencyCode = currencyCode,
                primaryCurrencyCode = primarySpec.code,
                exchangeRate = sec?.exchangeRateToPrimary
            )
        }

        val amountBySubcategory = mutableMapOf<String, Long>()
        val countBySubcategory = mutableMapOf<String, Int>()
        val includeUncategorized = UNCATEGORIZED_CATEGORY_ID in matchingCategoryIds

        for (tx in transactions) {
            if (tx.type == TransactionType.TRANSFER || tx.type == TransactionType.CC_SETTLEMENT) {
                val rawFee = safeAbs(tx.transferFee.amountMinor)
                if (rawFee <= 0L) continue
                val subName = tx.categoryName?.ifBlank { null } ?: parentCategoryName
                val feeMinor = toPrimaryMinor(rawFee, tx.currencyCode)
                amountBySubcategory[subName] = safeAdd(amountBySubcategory[subName] ?: 0L, feeMinor)
                countBySubcategory[subName] = (countBySubcategory[subName] ?: 0) + 1
                continue
            }

            val isRefund = tx.type == TransactionType.REFUND
            val countInc = if (isRefund) 0 else 1
            if (tx.isSplit && tx.splits.isNotEmpty()) {
                for (split in tx.splits) {
                    val matches = if (matchingCategoryIds.isNotEmpty()) {
                        if (split.categoryId == null) includeUncategorized else split.categoryId in matchingCategoryIds
                    } else {
                        true
                    }
                    if (!matches) continue
                    val subName = split.categoryName.ifBlank { "Uncategorized Split" }
                    val converted = toPrimaryMinor(safeAbs(split.amount.amountMinor), tx.currencyCode)
                    val signedAmt = if (isRefund) safeNegate(converted) else converted
                    amountBySubcategory[subName] = safeAdd(amountBySubcategory[subName] ?: 0L, signedAmt)
                    countBySubcategory[subName] = (countBySubcategory[subName] ?: 0) + countInc
                }
                val rawFee = if (tx.type == TransactionType.EXPENSE) safeAbs(tx.transferFee.amountMinor) else 0L
                if (rawFee > 0L) {
                    val feeTargetCatId = tx.categoryId ?: tx.splits.firstOrNull()?.categoryId
                    val feeMatches = if (matchingCategoryIds.isNotEmpty()) {
                        (feeTargetCatId ?: UNCATEGORIZED_CATEGORY_ID) in matchingCategoryIds
                    } else {
                        true
                    }
                    if (feeMatches) {
                        val subName = tx.categoryName?.ifBlank { null }
                            ?: tx.splits.firstOrNull()?.categoryName?.ifBlank { null }
                            ?: parentCategoryName
                        val feeMinor = toPrimaryMinor(rawFee, tx.currencyCode)
                        amountBySubcategory[subName] = safeAdd(amountBySubcategory[subName] ?: 0L, feeMinor)
                    }
                }
            } else {
                val subName = tx.categoryName?.ifBlank { null } ?: parentCategoryName
                val fee = if (tx.type == TransactionType.EXPENSE) safeAbs(tx.transferFee.amountMinor) else 0L
                val totalMinor = safeAdd(safeAbs(tx.amount.amountMinor), fee)
                val converted = toPrimaryMinor(totalMinor, tx.currencyCode)
                val signedAmt = if (isRefund) safeNegate(converted) else converted
                amountBySubcategory[subName] = safeAdd(amountBySubcategory[subName] ?: 0L, signedAmt)
                countBySubcategory[subName] = (countBySubcategory[subName] ?: 0) + countInc
            }
        }

        val parentTotalMinor = parentNetAmount.amountMinor.coerceAtLeast(1L)
        return amountBySubcategory
            .filter { it.value > 0L }
            .map { (subName, minorSum) ->
                val pct = MoneyFormatterAndCalculator.calculatePercentage(minorSum, parentTotalMinor)
                    .coerceIn(0f, 100f)
                SubcategoryBreakdownItem(
                    name = subName,
                    totalAmount = MinorMoney(minorSum),
                    transactionCount = (countBySubcategory[subName] ?: 1).coerceAtLeast(1),
                    percentageOfParent = pct
                )
            }.sortedByDescending { it.totalAmount.amountMinor }
    }
}
