/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/domain/util/MerchantAndTagAnalyticsCalculator.kt
package com.folioledger.app.domain.util

import com.folioledger.app.domain.model.CategorySpendStat
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.MonthlyTrendBar
import com.folioledger.app.domain.model.PayeeDrillDownState
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TagProjectDrillDownState
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeAbs
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeAdd
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeNegate
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeSubtract
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

object MerchantAndTagAnalyticsCalculator {

    fun computePayeeDrillDownState(
        payeeName: String,
        allTxs: List<TransactionItem>,
        trendBars: List<MonthlyTrendBar>,
        startEpochDay: Long,
        endEpochDay: Long,
        isAllTime: Boolean,
        primaryCurrencyCode: String,
        secondaryCurrencies: List<SecondaryCurrency> = emptyList(),
    ): PayeeDrillDownState {
        val trimmedTarget = payeeName.trim().lowercase(Locale.ROOT)

        val allMatchingTxs = allTxs.filter { tx ->
            val raw = tx.payeeOrDescription.trim().ifEmpty { tx.note.trim() }.lowercase(Locale.ROOT)
            val isSpendType = tx.type == TransactionType.EXPENSE ||
                tx.type == TransactionType.REFUND ||
                (
                    (tx.type == TransactionType.TRANSFER || tx.type == TransactionType.CC_SETTLEMENT) &&
                        safeAbs(tx.transferFee.amountMinor) > 0L
                    )
            raw == trimmedTarget && isSpendType
        }

        val periodMatchingTxs = if (isAllTime) {
            allMatchingTxs
        } else {
            allMatchingTxs.filter { it.ledgerDateEpochDay in startEpochDay..endEpochDay }
        }

        val primarySpec = SupportedCurrencies.fromCode(primaryCurrencyCode)
        val secondaryMap = secondaryCurrencies.associateBy { it.code.trim().uppercase(Locale.ROOT) }

        fun convertToPrimary(rawAmountMinor: Long, currencyCode: String): Long {
            val sec = secondaryMap[currencyCode.trim().uppercase(Locale.ROOT)]
            return MoneyFormatterAndCalculator.convertToPrimaryMinor(
                sourceMinor = rawAmountMinor,
                sourceCurrencyCode = currencyCode,
                primaryCurrencyCode = primarySpec.code,
                exchangeRate = sec?.exchangeRateToPrimary,
            )
        }

        var totalPeriodMinor = 0L
        var periodVisitCount = 0
        for (tx in periodMatchingTxs) {
            val convertedAmount = convertToPrimary(safeAbs(tx.amount.amountMinor), tx.currencyCode)
            val convertedFee = convertToPrimary(safeAbs(tx.transferFee.amountMinor), tx.currencyCode)
            when (tx.type) {
                TransactionType.EXPENSE -> {
                    totalPeriodMinor = safeAdd(totalPeriodMinor, safeAdd(convertedAmount, convertedFee))
                    periodVisitCount++
                }

                TransactionType.REFUND -> {
                    totalPeriodMinor = safeSubtract(totalPeriodMinor, convertedAmount)
                }

                TransactionType.TRANSFER, TransactionType.CC_SETTLEMENT -> {
                    if (convertedFee > 0L) {
                        totalPeriodMinor = safeAdd(totalPeriodMinor, convertedFee)
                        periodVisitCount++
                    }
                }

                else -> Unit
            }
        }
        totalPeriodMinor = maxOf(0L, totalPeriodMinor)

        var totalAllTimeMinor = 0L
        var allTimeVisitCount = 0
        for (tx in allMatchingTxs) {
            val convertedAmount = convertToPrimary(safeAbs(tx.amount.amountMinor), tx.currencyCode)
            val convertedFee = convertToPrimary(safeAbs(tx.transferFee.amountMinor), tx.currencyCode)
            when (tx.type) {
                TransactionType.EXPENSE -> {
                    totalAllTimeMinor = safeAdd(totalAllTimeMinor, safeAdd(convertedAmount, convertedFee))
                    allTimeVisitCount++
                }

                TransactionType.REFUND -> {
                    totalAllTimeMinor = safeSubtract(totalAllTimeMinor, convertedAmount)
                }

                TransactionType.TRANSFER, TransactionType.CC_SETTLEMENT -> {
                    if (convertedFee > 0L) {
                        totalAllTimeMinor = safeAdd(totalAllTimeMinor, convertedFee)
                        allTimeVisitCount++
                    }
                }

                else -> Unit
            }
        }
        totalAllTimeMinor = maxOf(0L, totalAllTimeMinor)

        val averageTicketMinor = when {
            periodVisitCount > 0 -> BigDecimal.valueOf(totalPeriodMinor)
                .divide(BigDecimal.valueOf(periodVisitCount.toLong()), 0, RoundingMode.HALF_UP)
                .toLong()

            allTimeVisitCount > 0 -> BigDecimal.valueOf(totalAllTimeMinor)
                .divide(BigDecimal.valueOf(allTimeVisitCount.toLong()), 0, RoundingMode.HALF_UP)
                .toLong()

            else -> 0L
        }

        // Category distribution for this merchant (scoped cleanly to the active period/all-time slice)
        val catAmountMap = mutableMapOf<String, Long>()
        val catCountMap = mutableMapOf<String, Int>()
        val catColorMap = mutableMapOf<String, String>()
        val catIconMap = mutableMapOf<String, String>()

        for (tx in periodMatchingTxs) {
            val fee = convertToPrimary(safeAbs(tx.transferFee.amountMinor), tx.currencyCode)
            if (tx.type == TransactionType.TRANSFER || tx.type == TransactionType.CC_SETTLEMENT) {
                if (fee > 0L) {
                    val catName = tx.categoryName ?: "General"
                    catAmountMap[catName] = safeAdd(catAmountMap[catName] ?: 0L, fee)
                    catCountMap[catName] = (catCountMap[catName] ?: 0) + 1
                    if (tx.categoryColorHex != null) catColorMap[catName] = tx.categoryColorHex
                    if (tx.categoryIconName != null) catIconMap[catName] = tx.categoryIconName
                }
                continue
            }

            if (tx.isSplit && tx.splits.isNotEmpty()) {
                for (split in tx.splits) {
                    val splitCatName = split.categoryName.ifBlank { tx.categoryName ?: "General" }
                    val splitAmt = convertToPrimary(safeAbs(split.amount.amountMinor), tx.currencyCode)
                    if (tx.type == TransactionType.REFUND) {
                        catAmountMap[splitCatName] = safeSubtract(catAmountMap[splitCatName] ?: 0L, splitAmt)
                    } else if (tx.type == TransactionType.EXPENSE) {
                        catAmountMap[splitCatName] = safeAdd(catAmountMap[splitCatName] ?: 0L, splitAmt)
                        catCountMap[splitCatName] = (catCountMap[splitCatName] ?: 0) + 1
                        if (tx.categoryColorHex != null) catColorMap[splitCatName] = tx.categoryColorHex
                        if (tx.categoryIconName != null) catIconMap[splitCatName] = tx.categoryIconName
                    }
                }
                if (tx.type == TransactionType.EXPENSE && fee > 0L) {
                    val catName = tx.categoryName ?: "General"
                    catAmountMap[catName] = safeAdd(catAmountMap[catName] ?: 0L, fee)
                }
            } else {
                val catName = tx.categoryName ?: "General"
                val amt = convertToPrimary(safeAbs(tx.amount.amountMinor), tx.currencyCode)
                if (tx.type == TransactionType.REFUND) {
                    catAmountMap[catName] = safeSubtract(catAmountMap[catName] ?: 0L, amt)
                } else if (tx.type == TransactionType.EXPENSE) {
                    catAmountMap[catName] = safeAdd(catAmountMap[catName] ?: 0L, safeAdd(amt, fee))
                    catCountMap[catName] = (catCountMap[catName] ?: 0) + 1
                    if (tx.categoryColorHex != null) catColorMap[catName] = tx.categoryColorHex
                    if (tx.categoryIconName != null) catIconMap[catName] = tx.categoryIconName
                }
            }
        }

        val positiveCatAmounts = catAmountMap.filterValues { it > 0L }
        val grandCategoryMinor = positiveCatAmounts.values.fold(0L) { acc, v -> safeAdd(acc, v) }.coerceAtLeast(1L)
        val catDistribution = positiveCatAmounts.map { (catName, minorSum) ->
            CategorySpendStat(
                categoryId = 0L,
                categoryName = catName,
                colorHex = catColorMap[catName] ?: "#8B5CF6",
                iconName = catIconMap[catName] ?: "category",
                netAmount = MinorMoney(minorSum),
                percentageOfTotal = MoneyFormatterAndCalculator.calculatePercentage(minorSum, grandCategoryMinor)
                    .coerceIn(0f, 100f),
                transactionCount = catCountMap[catName] ?: 1,
            )
        }.sortedByDescending { it.netAmount }

        // 6-month monthly spend bars for this merchant
        val payeeMonthlyBars = trendBars.map { bar ->
            var barExpenseMinor = 0L
            for (tx in allMatchingTxs) {
                if (tx.ledgerDateEpochDay in bar.startEpochDay..bar.endEpochDay) {
                    val convertedAmount = convertToPrimary(safeAbs(tx.amount.amountMinor), tx.currencyCode)
                    val convertedFee = convertToPrimary(safeAbs(tx.transferFee.amountMinor), tx.currencyCode)
                    when (tx.type) {
                        TransactionType.EXPENSE ->
                            barExpenseMinor = safeAdd(barExpenseMinor, safeAdd(convertedAmount, convertedFee))

                        TransactionType.REFUND ->
                            barExpenseMinor = safeSubtract(barExpenseMinor, convertedAmount)

                        TransactionType.TRANSFER, TransactionType.CC_SETTLEMENT ->
                            if (convertedFee > 0L) {
                                barExpenseMinor = safeAdd(barExpenseMinor, convertedFee)
                            }

                        else -> Unit
                    }
                }
            }
            val safeMinor = maxOf(0L, barExpenseMinor)
            MonthlyTrendBar(
                periodLabel = bar.periodLabel,
                startEpochDay = bar.startEpochDay,
                endEpochDay = bar.endEpochDay,
                totalIncome = MinorMoney.ZERO,
                totalExpense = MinorMoney(safeMinor),
                netSavings = MinorMoney(safeNegate(safeMinor)),
            )
        }

        val sortedTransactions = periodMatchingTxs.sortedWith(
            compareByDescending<TransactionItem> { it.ledgerDateEpochDay }
                .thenByDescending { it.timestampEpochMillis },
        )

        return PayeeDrillDownState(
            payeeName = payeeName,
            totalPeriodSpend = MinorMoney(totalPeriodMinor),
            totalAllTimeSpend = MinorMoney(totalAllTimeMinor),
            periodVisitCount = periodVisitCount,
            allTimeVisitCount = allTimeVisitCount,
            averageTicketSize = MinorMoney(averageTicketMinor),
            categoryDistribution = catDistribution,
            monthlyTrendBars = payeeMonthlyBars,
            transactions = sortedTransactions,
        )
    }

    fun computeTagProjectDrillDownState(
        tagName: String,
        allTxs: List<TransactionItem>,
        trendBars: List<MonthlyTrendBar>,
        startEpochDay: Long,
        endEpochDay: Long,
        isAllTime: Boolean,
        primaryCurrencyCode: String,
        secondaryCurrencies: List<SecondaryCurrency> = emptyList(),
    ): TagProjectDrillDownState {
        val cleanTargetTag = tagName.removePrefix("#").trim().lowercase(Locale.ROOT)

        val allMatchingTxs = allTxs.filter { tx ->
            tx.tags.any { it.removePrefix("#").trim().lowercase(Locale.ROOT) == cleanTargetTag }
        }

        val periodMatchingTxs = if (isAllTime) {
            allMatchingTxs
        } else {
            allMatchingTxs.filter { it.ledgerDateEpochDay in startEpochDay..endEpochDay }
        }

        val primarySpec = SupportedCurrencies.fromCode(primaryCurrencyCode)
        val secondaryMap = secondaryCurrencies.associateBy { it.code.trim().uppercase(Locale.ROOT) }

        fun convertToPrimary(rawAmountMinor: Long, currencyCode: String): Long {
            val sec = secondaryMap[currencyCode.trim().uppercase(Locale.ROOT)]
            return MoneyFormatterAndCalculator.convertToPrimaryMinor(
                sourceMinor = rawAmountMinor,
                sourceCurrencyCode = currencyCode,
                primaryCurrencyCode = primarySpec.code,
                exchangeRate = sec?.exchangeRateToPrimary,
            )
        }

        val isReimbursableKeyword = cleanTargetTag.contains("reimburs") ||
            cleanTargetTag.contains("claim") ||
            cleanTargetTag.contains("split") ||
            cleanTargetTag.contains("office") ||
            cleanTargetTag.contains("work") ||
            cleanTargetTag.contains("bill") ||
            cleanTargetTag.contains("owed") ||
            cleanTargetTag.contains("due")

        val hasInflowTransactions = allMatchingTxs.any {
            it.type == TransactionType.REFUND || it.type == TransactionType.INCOME
        }

        val isReimbursableProject = isReimbursableKeyword || hasInflowTransactions

        var totalSpentMinor = 0L
        var totalReimbursedMinor = 0L
        var minEpochDay: Long? = null
        var maxEpochDay: Long? = null

        val activeTxs = periodMatchingTxs

        for (tx in activeTxs) {
            val convertedAmount = convertToPrimary(safeAbs(tx.amount.amountMinor), tx.currencyCode)
            val convertedFee = convertToPrimary(safeAbs(tx.transferFee.amountMinor), tx.currencyCode)

            when (tx.type) {
                TransactionType.EXPENSE -> {
                    totalSpentMinor = safeAdd(totalSpentMinor, safeAdd(convertedAmount, convertedFee))
                    val d = tx.ledgerDateEpochDay
                    minEpochDay = if (minEpochDay == null) d else minOf(minEpochDay, d)
                    maxEpochDay = if (maxEpochDay == null) d else maxOf(maxEpochDay, d)
                }

                TransactionType.REFUND, TransactionType.INCOME -> {
                    totalReimbursedMinor = safeAdd(totalReimbursedMinor, convertedAmount)
                    val d = tx.ledgerDateEpochDay
                    minEpochDay = if (minEpochDay == null) d else minOf(minEpochDay, d)
                    maxEpochDay = if (maxEpochDay == null) d else maxOf(maxEpochDay, d)
                }

                TransactionType.TRANSFER, TransactionType.CC_SETTLEMENT -> {
                    if (convertedFee > 0L) {
                        totalSpentMinor = safeAdd(totalSpentMinor, convertedFee)
                        val d = tx.ledgerDateEpochDay
                        minEpochDay = if (minEpochDay == null) d else minOf(minEpochDay, d)
                        maxEpochDay = if (maxEpochDay == null) d else maxOf(maxEpochDay, d)
                    }
                }

                TransactionType.OPENING_BALANCE, TransactionType.RECONCILIATION -> {}
            }
        }

        val netPendingOwedMinor = maxOf(0L, safeSubtract(totalSpentMinor, totalReimbursedMinor))
        val isFullySettled = totalSpentMinor > 0L && netPendingOwedMinor == 0L

        val dateSpanDays = if (minEpochDay != null && maxEpochDay != null) {
            safeAdd(safeSubtract(maxEpochDay, minEpochDay), 1L)
                .coerceIn(1L, Int.MAX_VALUE.toLong())
                .toInt()
        } else {
            1
        }

        val dailyAverageSpendMinor = if (dateSpanDays > 0) {
            BigDecimal.valueOf(totalSpentMinor)
                .divide(BigDecimal.valueOf(dateSpanDays.toLong()), 0, RoundingMode.HALF_UP)
                .toLong()
        } else {
            totalSpentMinor
        }

        // Category distribution for this project
        val catAmountMap = mutableMapOf<String, Long>()
        val catCountMap = mutableMapOf<String, Int>()
        val catColorMap = mutableMapOf<String, String>()
        val catIconMap = mutableMapOf<String, String>()

        for (tx in activeTxs) {
            val fee = convertToPrimary(safeAbs(tx.transferFee.amountMinor), tx.currencyCode)
            if (tx.type == TransactionType.TRANSFER || tx.type == TransactionType.CC_SETTLEMENT) {
                if (fee > 0L) {
                    val catName = tx.categoryName ?: "General"
                    catAmountMap[catName] = safeAdd(catAmountMap[catName] ?: 0L, fee)
                    catCountMap[catName] = (catCountMap[catName] ?: 0) + 1
                    if (tx.categoryColorHex != null) catColorMap[catName] = tx.categoryColorHex
                    if (tx.categoryIconName != null) catIconMap[catName] = tx.categoryIconName
                }
                continue
            }

            if (tx.isSplit && tx.splits.isNotEmpty()) {
                for (split in tx.splits) {
                    val splitCatName = split.categoryName.ifBlank { tx.categoryName ?: "General" }
                    val splitAmt = convertToPrimary(safeAbs(split.amount.amountMinor), tx.currencyCode)
                    if (tx.type == TransactionType.REFUND) {
                        catAmountMap[splitCatName] = safeSubtract(catAmountMap[splitCatName] ?: 0L, splitAmt)
                    } else if (tx.type == TransactionType.EXPENSE) {
                        catAmountMap[splitCatName] = safeAdd(catAmountMap[splitCatName] ?: 0L, splitAmt)
                        catCountMap[splitCatName] = (catCountMap[splitCatName] ?: 0) + 1
                        if (tx.categoryColorHex != null) catColorMap[splitCatName] = tx.categoryColorHex
                        if (tx.categoryIconName != null) catIconMap[splitCatName] = tx.categoryIconName
                    }
                }
                if (tx.type == TransactionType.EXPENSE && fee > 0L) {
                    val catName = tx.categoryName ?: "General"
                    catAmountMap[catName] = safeAdd(catAmountMap[catName] ?: 0L, fee)
                }
            } else {
                val catName = tx.categoryName ?: "General"
                val amt = convertToPrimary(safeAbs(tx.amount.amountMinor), tx.currencyCode)
                if (tx.type == TransactionType.REFUND) {
                    catAmountMap[catName] = safeSubtract(catAmountMap[catName] ?: 0L, amt)
                } else if (tx.type == TransactionType.EXPENSE) {
                    catAmountMap[catName] = safeAdd(catAmountMap[catName] ?: 0L, safeAdd(amt, fee))
                    catCountMap[catName] = (catCountMap[catName] ?: 0) + 1
                    if (tx.categoryColorHex != null) catColorMap[catName] = tx.categoryColorHex
                    if (tx.categoryIconName != null) catIconMap[catName] = tx.categoryIconName
                }
            }
        }

        val positiveCatAmounts = catAmountMap.filterValues { it > 0L }
        val grandCategoryMinor = positiveCatAmounts.values.fold(0L) { acc, v -> safeAdd(acc, v) }.coerceAtLeast(1L)
        val catDistribution = positiveCatAmounts.map { (catName, minorSum) ->
            CategorySpendStat(
                categoryId = 0L,
                categoryName = catName,
                colorHex = catColorMap[catName] ?: "#8B5CF6",
                iconName = catIconMap[catName] ?: "category",
                netAmount = MinorMoney(minorSum),
                percentageOfTotal = MoneyFormatterAndCalculator.calculatePercentage(minorSum, grandCategoryMinor)
                    .coerceIn(0f, 100f),
                transactionCount = catCountMap[catName] ?: 1,
            )
        }.sortedByDescending { it.netAmount }

        // 6-month monthly spend bars for this tag
        val tagMonthlyBars = trendBars.map { bar ->
            var barExpenseMinor = 0L
            for (tx in allMatchingTxs) {
                if (tx.ledgerDateEpochDay in bar.startEpochDay..bar.endEpochDay) {
                    val amt = convertToPrimary(safeAbs(tx.amount.amountMinor), tx.currencyCode)
                    val fee = convertToPrimary(safeAbs(tx.transferFee.amountMinor), tx.currencyCode)
                    when (tx.type) {
                        TransactionType.EXPENSE ->
                            barExpenseMinor = safeAdd(barExpenseMinor, safeAdd(amt, fee))

                        TransactionType.REFUND ->
                            barExpenseMinor = safeSubtract(barExpenseMinor, amt)

                        TransactionType.TRANSFER, TransactionType.CC_SETTLEMENT ->
                            if (fee > 0L) {
                                barExpenseMinor = safeAdd(barExpenseMinor, fee)
                            }

                        else -> Unit
                    }
                }
            }
            val safeMinor = maxOf(0L, barExpenseMinor)
            MonthlyTrendBar(
                periodLabel = bar.periodLabel,
                startEpochDay = bar.startEpochDay,
                endEpochDay = bar.endEpochDay,
                totalIncome = MinorMoney.ZERO,
                totalExpense = MinorMoney(safeMinor),
                netSavings = MinorMoney(safeNegate(safeMinor)),
            )
        }

        val sortedTransactions = activeTxs.sortedWith(
            compareByDescending<TransactionItem> { it.ledgerDateEpochDay }
                .thenByDescending { it.timestampEpochMillis },
        )

        return TagProjectDrillDownState(
            tagName = tagName,
            isReimbursableProject = isReimbursableProject,
            totalSpent = MinorMoney(totalSpentMinor),
            totalReimbursed = MinorMoney(totalReimbursedMinor),
            netPendingOwed = MinorMoney(netPendingOwedMinor),
            isFullySettled = isFullySettled,
            dateSpanDays = dateSpanDays,
            minEpochDay = minEpochDay,
            maxEpochDay = maxEpochDay,
            dailyAverageSpend = MinorMoney(dailyAverageSpendMinor),
            categoryDistribution = catDistribution,
            monthlyTrendBars = tagMonthlyBars,
            transactions = sortedTransactions,
        )
    }
}
