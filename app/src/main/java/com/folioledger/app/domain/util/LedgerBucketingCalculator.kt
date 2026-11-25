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

import com.folioledger.app.domain.model.AccountPeriodFlowSummary
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.CalendarDayCell
import com.folioledger.app.domain.model.CategoryNode
import com.folioledger.app.domain.model.CategorySpendStat
import com.folioledger.app.domain.model.CategoryType
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.DailyLedgerGroup
import com.folioledger.app.domain.model.FinancialPeriodRange
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.model.WeeklyLedgerBucket
import com.folioledger.app.domain.usecase.AccountingRulesEngine
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeAbs
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeAdd
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeSubtract
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object LedgerBucketingCalculator {

    private val SHORT_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMM", Locale.US)
    private val MONTH_YEAR_FORMATTER = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)
    private val fullDayMonthYearFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)

    fun resolveClampedDate(yearMonth: YearMonth, desiredDayOfMonth: Int): LocalDate {
        val safeDay = desiredDayOfMonth.coerceIn(1, 31)
        val clampedDay = minOf(safeDay, yearMonth.lengthOfMonth())
        return yearMonth.atDay(clampedDay)
    }

    fun computeFinancialMonthRange(
        referenceDateEpochDay: Long,
        monthStartDay: Int
    ): FinancialPeriodRange {
        val safeStartDay = monthStartDay.coerceIn(1, 31)
        val (startEpoch, endEpoch) = AccountingRulesEngine.computeFinancialMonthRange(
            referenceDateEpochDay = referenceDateEpochDay,
            monthStartDay = safeStartDay
        )
        val startDate = LocalDate.ofEpochDay(startEpoch)
        val endDate = LocalDate.ofEpochDay(endEpoch)

        val anchorDate = if (safeStartDay > 15) endDate else startDate
        val title = anchorDate.format(MONTH_YEAR_FORMATTER)
        val subtitle = "${startDate.format(SHORT_DATE_FORMATTER)} – ${endDate.format(fullDayMonthYearFormatter)}"

        return FinancialPeriodRange(
            startEpochDay = startEpoch,
            endEpochDay = endEpoch,
            title = title,
            subtitle = subtitle,
            anchorYear = anchorDate.year,
            anchorMonthValue = anchorDate.monthValue
        )
    }

    fun stepFinancialMonth(
        currentReferenceEpochDay: Long,
        monthStartDay: Int,
        deltaMonths: Long
    ): Long {
        val safeStartDay = monthStartDay.coerceIn(1, 31)
        val currentRange = computeFinancialMonthRange(currentReferenceEpochDay, safeStartDay)
        val startLocalDate = LocalDate.ofEpochDay(currentRange.startEpochDay)
        val targetYearMonth = YearMonth.from(startLocalDate).plusMonths(deltaMonths)
        return resolveClampedDate(targetYearMonth, safeStartDay).toEpochDay()
    }

    fun computeFinancialYearMonthRanges(
        anchorYear: Int,
        monthStartDay: Int
    ): List<FinancialPeriodRange> {
        val safeStartDay = monthStartDay.coerceIn(1, 31)
        return (1..12).map { monthIndex ->
            val ym = YearMonth.of(anchorYear, monthIndex)
            val monthAnchorDate = if (safeStartDay > 15) {
                resolveClampedDate(ym.minusMonths(1), safeStartDay)
            } else {
                resolveClampedDate(ym, safeStartDay)
            }
            computeFinancialMonthRange(monthAnchorDate.toEpochDay(), safeStartDay)
        }
    }

    fun computeIncomeAndExpenseTotals(
        transactions: List<TransactionItem>,
        scopedAccountId: Long? = null,
        primaryCurrencySpec: CurrencySpec? = null,
        secondaryCurrencies: List<SecondaryCurrency> = emptyList()
    ): Pair<MinorMoney, MinorMoney> {
        val secondaryMap = if (scopedAccountId == null && primaryCurrencySpec != null && secondaryCurrencies.isNotEmpty()) {
            secondaryCurrencies.associateBy { it.code.trim().uppercase(Locale.US) }
        } else {
            emptyMap()
        }

        fun toTargetMinor(amountMinor: Long, txCurrencyCode: String): Long {
            if (scopedAccountId != null || primaryCurrencySpec == null) {
                return amountMinor
            }
            val sec = secondaryMap[txCurrencyCode.trim().uppercase(Locale.US)]
            return MoneyFormatterAndCalculator.convertToPrimaryMinor(
                sourceMinor = amountMinor,
                sourceCurrencyCode = txCurrencyCode,
                primaryCurrencyCode = primaryCurrencySpec.code,
                exchangeRate = sec?.exchangeRateToPrimary
            )
        }

        var incomeMinor = 0L
        var expenseMinor = 0L
        transactions.forEach { tx ->
            val effectiveAmountMinor = toTargetMinor(tx.amount.amountMinor, tx.currencyCode)
            when (tx.type) {
                TransactionType.INCOME -> {
                    incomeMinor = safeAdd(incomeMinor, effectiveAmountMinor)
                }
                TransactionType.EXPENSE -> {
                    val feeMinor = toTargetMinor(tx.transferFee.amountMinor, tx.currencyCode)
                    expenseMinor = safeAdd(expenseMinor, safeAdd(effectiveAmountMinor, feeMinor))
                }
                TransactionType.REFUND -> {
                    if (scopedAccountId != null) {
                        incomeMinor = safeAdd(incomeMinor, effectiveAmountMinor)
                    } else {
                        expenseMinor = safeSubtract(expenseMinor, effectiveAmountMinor)
                    }
                }
                TransactionType.TRANSFER, TransactionType.CC_SETTLEMENT -> {
                    if (scopedAccountId != null) {
                        if (tx.type == TransactionType.CC_SETTLEMENT && tx.transferToAccountId == null && tx.accountId == scopedAccountId) {
                            incomeMinor = safeAdd(incomeMinor, tx.amount.amountMinor)
                        } else if (tx.accountId == scopedAccountId && tx.transferToAccountId == scopedAccountId) {
                            expenseMinor = safeAdd(expenseMinor, tx.transferFee.amountMinor)
                        } else if (tx.transferToAccountId == scopedAccountId) {
                            incomeMinor = safeAdd(incomeMinor, tx.amount.amountMinor)
                        } else if (tx.accountId == scopedAccountId) {
                            expenseMinor = safeAdd(
                                expenseMinor,
                                safeAdd(tx.amount.amountMinor, tx.transferFee.amountMinor)
                            )
                        }
                    } else if (tx.transferFee.isPositive) {
                        val feeMinor = toTargetMinor(tx.transferFee.amountMinor, tx.currencyCode)
                        expenseMinor = safeAdd(expenseMinor, feeMinor)
                    }
                }
                TransactionType.OPENING_BALANCE,
                TransactionType.RECONCILIATION -> {
                    // Capital events do not count toward operating income or expense totals
                }
            }
        }
        if (scopedAccountId == null && expenseMinor < 0L) {
            val excessRefund = safeAbs(expenseMinor)
            incomeMinor = safeAdd(incomeMinor, excessRefund)
            expenseMinor = 0L
        }
        return MinorMoney(incomeMinor) to MinorMoney(expenseMinor)
    }

    fun computeTransferTotal(
        transactions: List<TransactionItem>,
        primaryCurrencySpec: CurrencySpec? = null,
        secondaryCurrencies: List<SecondaryCurrency> = emptyList()
    ): MinorMoney {
        val secondaryMap = if (primaryCurrencySpec != null && secondaryCurrencies.isNotEmpty()) {
            secondaryCurrencies.associateBy { it.code.trim().uppercase(Locale.US) }
        } else {
            emptyMap()
        }
        val transferTotalMinor = transactions.fold(0L) { acc, tx ->
            if (tx.type != TransactionType.TRANSFER && tx.type != TransactionType.CC_SETTLEMENT) {
                acc
            } else {
                val absMinor = safeAbs(tx.amount.amountMinor)
                val convertedMinor = if (primaryCurrencySpec != null) {
                    val sec = secondaryMap[tx.currencyCode.trim().uppercase(Locale.US)]
                    MoneyFormatterAndCalculator.convertToPrimaryMinor(
                        sourceMinor = absMinor,
                        sourceCurrencyCode = tx.currencyCode,
                        primaryCurrencyCode = primaryCurrencySpec.code,
                        exchangeRate = sec?.exchangeRateToPrimary
                    )
                } else {
                    absMinor
                }
                safeAdd(acc, convertedMinor)
            }
        }
        return MinorMoney(transferTotalMinor)
    }

    fun buildDailyGroups(
        transactions: List<TransactionItem>,
        todayEpochDay: Long,
        scopedAccountId: Long? = null,
        primaryCurrencySpec: CurrencySpec? = null,
        secondaryCurrencies: List<SecondaryCurrency> = emptyList()
    ): List<DailyLedgerGroup> {
        if (transactions.isEmpty()) return emptyList()

        return transactions
            .groupBy { it.ledgerDateEpochDay }
            .entries
            .sortedByDescending { it.key }
            .map { (epochDay, dayTxns) ->
                val date = LocalDate.ofEpochDay(epochDay)
                val sortedTxns = dayTxns.sortedByDescending { it.timestampEpochMillis }
                val (dayInc, dayExp) = computeIncomeAndExpenseTotals(
                    transactions = sortedTxns,
                    scopedAccountId = scopedAccountId,
                    primaryCurrencySpec = primaryCurrencySpec,
                    secondaryCurrencies = secondaryCurrencies
                )
                val transferTxns = sortedTxns.filter { it.type == TransactionType.TRANSFER || it.type == TransactionType.CC_SETTLEMENT }
                val dailyTransferTotal = computeTransferTotal(
                    transactions = transferTxns,
                    primaryCurrencySpec = primaryCurrencySpec,
                    secondaryCurrencies = secondaryCurrencies
                )
                DailyLedgerGroup(
                    epochDay = epochDay,
                    dayOfMonthText = String.format(Locale.US, "%02d", date.dayOfMonth),
                    dayOfWeekText = date.dayOfWeek
                        .getDisplayName(JavaTextStyle.SHORT, Locale.US)
                        .uppercase(Locale.US),
                    monthYearText = date.format(MONTH_YEAR_FORMATTER),
                    isToday = epochDay == todayEpochDay,
                    dailyIncome = dayInc,
                    dailyExpense = dayExp,
                    dailyNet = dayInc - dayExp,
                    dailyTransferTotal = dailyTransferTotal,
                    transferCount = transferTxns.size,
                    transactions = sortedTxns
                )
            }
    }

    fun buildWeekdayHeaders(weekStartDayIso: Int): List<String> {
        val startIso = weekStartDayIso.coerceIn(1, 7)
        return (0 until 7).map { offset ->
            val dowNumber = ((startIso - 1 + offset) % 7) + 1
            DayOfWeek.of(dowNumber).getDisplayName(JavaTextStyle.SHORT, Locale.US)
        }
    }

    fun buildCalendarGridCells(
        periodRange: FinancialPeriodRange,
        weekStartDayIso: Int,
        transactions: List<TransactionItem>,
        todayEpochDay: Long,
        scopedAccountId: Long? = null,
        primaryCurrencySpec: CurrencySpec? = null,
        secondaryCurrencies: List<SecondaryCurrency> = emptyList(),
        numberGrouping: NumberGroupingSystem = NumberGroupingSystem.INDIAN
    ): List<CalendarDayCell> {
        val startDate = LocalDate.ofEpochDay(periodRange.startEpochDay)
        val endDate = LocalDate.ofEpochDay(periodRange.endEpochDay)
        val startDow = DayOfWeek.of(weekStartDayIso.coerceIn(1, 7))

        val firstGridDate = startDate.with(TemporalAdjusters.previousOrSame(startDow))
        val endDow = startDow.minus(1)
        val lastGridDate = endDate.with(TemporalAdjusters.nextOrSame(endDow))

        val txByDay = transactions.groupBy { it.ledgerDateEpochDay }
        val currency = primaryCurrencySpec ?: SupportedCurrencies.INR

        val cells = mutableListOf<CalendarDayCell>()
        var cursor = firstGridDate
        while (!cursor.isAfter(lastGridDate)) {
            val epoch = cursor.toEpochDay()
            val inPeriod = epoch in periodRange.startEpochDay..periodRange.endEpochDay
            if (!inPeriod) {
                cells.add(
                    CalendarDayCell(
                        epochDay = null,
                        dayNumber = null,
                        isInActivePeriod = false
                    )
                )
            } else {
                val dayTxns = txByDay[epoch].orEmpty()
                val (inc, exp) = computeIncomeAndExpenseTotals(
                    transactions = dayTxns,
                    scopedAccountId = scopedAccountId,
                    primaryCurrencySpec = primaryCurrencySpec,
                    secondaryCurrencies = secondaryCurrencies
                )
                val transferTxns = dayTxns.filter { it.type == TransactionType.TRANSFER || it.type == TransactionType.CC_SETTLEMENT }
                val transferTotal = computeTransferTotal(
                    transactions = transferTxns,
                    primaryCurrencySpec = primaryCurrencySpec,
                    secondaryCurrencies = secondaryCurrencies
                )

                val formattedInc = formatCalendarCompactPositive(inc, currency, numberGrouping)
                val formattedExp = formatCalendarCompactPositive(exp, currency, numberGrouping)
                val formattedTransfer = formatCalendarCompactPositive(transferTotal, currency, numberGrouping)

                cells.add(
                    CalendarDayCell(
                        epochDay = epoch,
                        dayNumber = cursor.dayOfMonth,
                        dateLabel = cursor.format(SHORT_DATE_FORMATTER),
                        isInActivePeriod = true,
                        isToday = epoch == todayEpochDay,
                        incomeTotal = inc,
                        expenseTotal = exp,
                        netTotal = inc - exp,
                        transferTotal = transferTotal,
                        transferCount = transferTxns.size,
                        transactionCount = dayTxns.size,
                        formattedCompactIncome = formattedInc,
                        formattedCompactExpense = formattedExp,
                        formattedCompactTransfer = formattedTransfer
                    )
                )
            }
            cursor = cursor.plusDays(1)
        }
        return cells
    }

    fun buildWeeklyBucketsForPeriod(
        periodRange: FinancialPeriodRange,
        weekStartDayIso: Int,
        transactions: List<TransactionItem>,
        scopedAccountId: Long? = null,
        primaryCurrencySpec: CurrencySpec? = null,
        secondaryCurrencies: List<SecondaryCurrency> = emptyList()
    ): List<WeeklyLedgerBucket> {
        val periodStart = LocalDate.ofEpochDay(periodRange.startEpochDay)
        val periodEnd = LocalDate.ofEpochDay(periodRange.endEpochDay)
        val startDow = DayOfWeek.of(weekStartDayIso.coerceIn(1, 7))
        val endDow = startDow.minus(1)

        val buckets = mutableListOf<WeeklyLedgerBucket>()
        var currentStart = periodStart
        var weekNum = 1

        while (!currentStart.isAfter(periodEnd)) {
            val candidateEnd = currentStart.with(TemporalAdjusters.nextOrSame(endDow))
            val currentEnd = if (candidateEnd.isAfter(periodEnd)) periodEnd else candidateEnd

            val startEpoch = currentStart.toEpochDay()
            val endEpoch = currentEnd.toEpochDay()
            val weekTxns = transactions
                .filter { it.ledgerDateEpochDay in startEpoch..endEpoch }
                .sortedWith(
                    compareByDescending<TransactionItem> { it.ledgerDateEpochDay }
                        .thenByDescending { it.timestampEpochMillis }
                )
            val (inc, exp) = computeIncomeAndExpenseTotals(
                transactions = weekTxns,
                scopedAccountId = scopedAccountId,
                primaryCurrencySpec = primaryCurrencySpec,
                secondaryCurrencies = secondaryCurrencies
            )

            buckets.add(
                WeeklyLedgerBucket(
                    weekIndex = weekNum,
                    label = "Week $weekNum",
                    dateRangeSubtitle = "${currentStart.format(SHORT_DATE_FORMATTER)} – ${currentEnd.format(SHORT_DATE_FORMATTER)}",
                    startEpochDay = startEpoch,
                    endEpochDay = endEpoch,
                    incomeTotal = inc,
                    expenseTotal = exp,
                    netTotal = inc - exp,
                    transactions = weekTxns
                )
            )

            currentStart = currentEnd.plusDays(1)
            weekNum++
        }
        return buckets
    }

    private fun formatCalendarCompactPositive(
        amount: MinorMoney,
        currencySpec: CurrencySpec,
        numberGrouping: NumberGroupingSystem
    ): String {
        if (!amount.isPositive) return ""
        val formatted = MoneyFormatterAndCalculator.formatCompactMinorMoney(amount, currencySpec, numberGrouping)
        return if (formatted == "0") "<1" else formatted
    }

    fun buildAccountPeriodFlows(
        transactions: List<TransactionItem>,
        accounts: List<AccountSummary>,
        scopedAccountId: Long? = null
    ): List<AccountPeriodFlowSummary> {
        if (transactions.isEmpty()) return emptyList()

        val accountsById = accounts.associateBy { it.id }
        val accountNamesById = mutableMapOf<Long, String>()
        val txnsByAccountId = mutableMapOf<Long, MutableList<TransactionItem>>()

        for (tx in transactions) {
            if (scopedAccountId == null || tx.accountId == scopedAccountId) {
                accountNamesById.putIfAbsent(tx.accountId, tx.accountName)
                txnsByAccountId.getOrPut(tx.accountId) { mutableListOf() }.add(tx)
            }

            val destId = tx.transferToAccountId
            val isTransferOrSettlement = tx.type == TransactionType.TRANSFER || tx.type == TransactionType.CC_SETTLEMENT
            if (isTransferOrSettlement && destId != null && destId != tx.accountId && (scopedAccountId == null || destId == scopedAccountId)) {
                val resolvedDestName = accountsById[destId]?.name ?: tx.transferToAccountName
                if (resolvedDestName != null) {
                    accountNamesById.putIfAbsent(destId, resolvedDestName)
                }
                txnsByAccountId.getOrPut(destId) { mutableListOf() }.add(tx)
            }
        }

        return txnsByAccountId.map { (accId, txns) ->
            val account = accountsById[accId]
            val accName = account?.name ?: accountNamesById[accId] ?: "Account #$accId"
            val currencyCode = account?.currencyCode ?: txns.firstOrNull()?.currencyCode ?: "INR"
            val (inc, exp) = computeIncomeAndExpenseTotals(txns, accId)
            AccountPeriodFlowSummary(
                accountId = accId,
                accountName = accName,
                currencyCode = currencyCode,
                incomeTotal = inc,
                expenseTotal = exp,
                netChange = inc - exp,
                transactionCount = txns.size
            )
        }.sortedByDescending { it.transactionCount }
    }

    fun buildScopedCategorySpendStats(
        transactions: List<TransactionItem>,
        categories: List<CategoryNode>,
        maxCategories: Int = 6,
        primaryCurrencySpec: CurrencySpec? = null,
        secondaryCurrencies: List<SecondaryCurrency> = emptyList(),
        scopedAccountId: Long? = null
    ): List<CategorySpendStat> {
        val secondaryMap = if (primaryCurrencySpec != null && secondaryCurrencies.isNotEmpty()) {
            secondaryCurrencies.associateBy { it.code.trim().uppercase(Locale.US) }
        } else {
            emptyMap()
        }

        fun toTargetMinor(amountMinor: Long, txCurrencyCode: String): Long {
            if (primaryCurrencySpec == null) {
                return amountMinor
            }
            val sec = secondaryMap[txCurrencyCode.trim().uppercase(Locale.US)]
            return MoneyFormatterAndCalculator.convertToPrimaryMinor(
                sourceMinor = amountMinor,
                sourceCurrencyCode = txCurrencyCode,
                primaryCurrencyCode = primaryCurrencySpec.code,
                exchangeRate = sec?.exchangeRateToPrimary
            )
        }

        val netMinorByCategoryId = mutableMapOf<Long, Long>()
        val txCountByCategoryId = mutableMapOf<Long, Int>()
        val categoryDetails = mutableMapOf<Long, Triple<String, String, String>>()
        val parentIdByCategoryId = mutableMapOf<Long, Long>()

        fun indexCategory(node: CategoryNode) {
            parentIdByCategoryId[node.id] = node.id
            categoryDetails[node.id] = Triple(node.name, node.colorHex, node.iconName)
            node.subcategories.forEach { sub ->
                parentIdByCategoryId[sub.id] = node.id
                categoryDetails[sub.id] = Triple(node.name, node.colorHex, node.iconName)
            }
        }
        categories.forEach { indexCategory(it) }

        fun addDelta(catId: Long?, amountMinor: Long, countIncrement: Int) {
            val rawId = catId ?: -1L
            val resolvedId = parentIdByCategoryId[rawId] ?: rawId
            val current = netMinorByCategoryId[resolvedId] ?: 0L
            netMinorByCategoryId[resolvedId] = MoneyFormatterAndCalculator.safeAdd(current, amountMinor)
            if (countIncrement > 0) {
                txCountByCategoryId[resolvedId] = (txCountByCategoryId[resolvedId] ?: 0) + countIncrement
            }
        }

        for (tx in transactions) {
            if (scopedAccountId != null && tx.accountId != scopedAccountId) continue
            when (tx.type) {
                TransactionType.EXPENSE -> {
                    val feeConverted = toTargetMinor(tx.transferFee.amountMinor, tx.currencyCode)
                    if (tx.isSplit && tx.splits.isNotEmpty()) {
                        for (split in tx.splits) {
                            val converted = toTargetMinor(split.amount.amountMinor, tx.currencyCode)
                            addDelta(split.categoryId, converted, 1)
                        }
                        if (feeConverted > 0L) {
                            addDelta(tx.categoryId, feeConverted, 0)
                        }
                    } else {
                        val converted = toTargetMinor(tx.amount.amountMinor, tx.currencyCode)
                        val total = MoneyFormatterAndCalculator.safeAdd(converted, feeConverted)
                        addDelta(tx.categoryId, total, 1)
                    }
                }
                TransactionType.REFUND -> {
                    if (tx.isSplit && tx.splits.isNotEmpty()) {
                        for (split in tx.splits) {
                            val converted = toTargetMinor(MoneyFormatterAndCalculator.safeAbs(split.amount.amountMinor), tx.currencyCode)
                            addDelta(split.categoryId, -converted, 0)
                        }
                    } else {
                        val converted = toTargetMinor(MoneyFormatterAndCalculator.safeAbs(tx.amount.amountMinor), tx.currencyCode)
                        addDelta(tx.categoryId, -converted, 0)
                    }
                }
                TransactionType.TRANSFER, TransactionType.CC_SETTLEMENT -> {
                    if (tx.transferFee.isPositive && (scopedAccountId == null || tx.accountId == scopedAccountId)) {
                        val feeConverted = toTargetMinor(tx.transferFee.amountMinor, tx.currencyCode)
                        addDelta(tx.categoryId, feeConverted, 1)
                    }
                }
                else -> Unit
            }
        }

        val positive = netMinorByCategoryId.filter { it.value > 0L }
        val grandTotal = positive.values.fold(0L) { acc, v -> MoneyFormatterAndCalculator.safeAdd(acc, v) }
        if (grandTotal <= 0L) return emptyList()

        return positive.map { (catId, minor) ->
            val details = categoryDetails[catId]
            val name = details?.first ?: if (catId == -1L) "Uncategorized" else "Category #$catId"
            val color = details?.second ?: "#64748B"
            val icon = details?.third ?: "category"
            val pct = MoneyFormatterAndCalculator.calculatePercentage(minor, grandTotal)
            CategorySpendStat(
                categoryId = catId,
                categoryName = name,
                colorHex = color,
                iconName = icon,
                netAmount = MinorMoney(minor),
                percentageOfTotal = pct,
                transactionCount = txCountByCategoryId[catId] ?: 1
            )
        }.sortedByDescending { it.netAmount.amountMinor }.take(maxCategories)
    }
}
