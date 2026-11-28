/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/domain/util/CreditCardCockpitCalculator.kt
package com.folioledger.app.domain.util

import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.CreditCardAnalyticsItem
import com.folioledger.app.domain.model.CreditCardCockpitUiState
import com.folioledger.app.domain.model.CreditCardHealthUiModel
import com.folioledger.app.domain.model.CreditCardTimelineItem
import com.folioledger.app.domain.model.CreditSpendRatio
import com.folioledger.app.domain.model.CreditUtilizationStatus
import com.folioledger.app.domain.model.LiquidCashCoverageState
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.MonthlyTrendBar
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.SmartSwipeRecommendation
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

object CreditCardCockpitCalculator {

    private fun AccountSummary.outstandingCardDebt(): MinorMoney = if (currentBalance.isNegative) -currentBalance else MinorMoney.ZERO

    fun evaluateUtilizationStatus(utilizationPercent: Float): CreditUtilizationStatus = when {
        utilizationPercent > 50f -> CreditUtilizationStatus.WARNING
        utilizationPercent > 30f -> CreditUtilizationStatus.MODERATE
        else -> CreditUtilizationStatus.SAFE
    }

    fun computeCreditCardHealthList(
        allAccounts: List<AccountSummary>,
    ): List<CreditCardHealthUiModel> {
        val creditCards = allAccounts.filter { !it.isArchived && it.groupType == AccountGroup.CREDIT_CARD }
        return creditCards.map { card ->
            val limit = card.creditLimit ?: MinorMoney.ZERO
            val debt = card.outstandingCardDebt()
            val payable = card.creditCardStatementPayable ?: MinorMoney.ZERO
            val unbilled = card.creditCardUnbilledAmount ?: MinorMoney.ZERO

            val available = if (limit > debt) limit - debt else MinorMoney.ZERO
            val utilization = if (limit.isPositive) {
                MoneyFormatterAndCalculator.calculatePercentage(debt.amountMinor, limit.amountMinor)
                    .coerceIn(0f, 100f)
            } else {
                0f
            }

            val status = evaluateUtilizationStatus(utilization)

            CreditCardHealthUiModel(
                account = card,
                statementPayable = payable,
                unbilledSpend = unbilled,
                totalDebt = debt,
                creditLimit = limit,
                availableCredit = available,
                utilizationPercent = utilization,
                status = status,
            )
        }
    }

    fun computeCreditCockpitState(
        allAccounts: List<AccountSummary>,
        allTxs: List<TransactionItem>,
        periodTransactions: List<TransactionItem>,
        trendBars: List<MonthlyTrendBar>,
        referenceDateEpochDay: Long,
        milestonesJson: String? = null,
        primaryCurrencyCode: String = "INR",
        secondaryCurrencies: List<SecondaryCurrency> = emptyList(),
    ): CreditCardCockpitUiState {
        val today = LocalDate.ofEpochDay(referenceDateEpochDay)
        val creditCards = allAccounts.filter { !it.isArchived && it.groupType == AccountGroup.CREDIT_CARD }
        val creditCardIds = creditCards.map { it.id }.toSet()
        val accountsById = allAccounts.associateBy { it.id }

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

        fun convertCurrency(rawAmountMinor: Long, fromCurrency: String, toCurrency: String): Long = MoneyFormatterAndCalculator.convertBetweenCurrenciesMinor(
            sourceMinor = rawAmountMinor,
            fromCurrencyCode = fromCurrency,
            toCurrencyCode = toCurrency,
            primaryCurrencyCode = primarySpec.code,
            secondaryCurrenciesByCode = secondaryMap,
        )

        if (creditCards.isEmpty()) {
            return CreditCardCockpitUiState(activeCards = emptyList())
        }

        // 1. Total Limits & Balances (Converted to Primary Currency)
        var totalLimitMinor = 0L
        var totalDebtMinor = 0L
        for (card in creditCards) {
            val cardLimit = card.creditLimit?.amountMinor ?: 0L
            val cardDebt = card.outstandingCardDebt().amountMinor
            totalLimitMinor = MoneyFormatterAndCalculator.safeAdd(
                totalLimitMinor,
                convertToPrimary(cardLimit, card.currencyCode),
            )
            totalDebtMinor = MoneyFormatterAndCalculator.safeAdd(
                totalDebtMinor,
                convertToPrimary(cardDebt, card.currencyCode),
            )
        }
        val totalLimit = MinorMoney(totalLimitMinor)
        val totalCcDebt = MinorMoney(totalDebtMinor)
        val totalAvailable = if (totalLimit > totalCcDebt) totalLimit - totalCcDebt else MinorMoney.ZERO
        val aggregateUtilization = if (totalLimit.isPositive) {
            MoneyFormatterAndCalculator.calculatePercentage(totalCcDebt.amountMinor, totalLimit.amountMinor)
                .coerceIn(0f, 100f)
        } else {
            0f
        }
        val aggregateStatus = evaluateUtilizationStatus(aggregateUtilization)

        // 2. Liquid Cash Coverage (Converted to Primary Currency)
        val liquidAccounts = allAccounts.filter {
            !it.isArchived && (
                it.groupType == AccountGroup.BANK_ACCOUNT ||
                    it.groupType == AccountGroup.CASH ||
                    it.groupType == AccountGroup.SAVINGS ||
                    it.groupType == AccountGroup.PREPAID_WALLET
                )
        }
        var totalLiquidMinor = 0L
        for (acc in liquidAccounts) {
            if (acc.currentBalance.amountMinor != 0L) {
                totalLiquidMinor = MoneyFormatterAndCalculator.safeAdd(
                    totalLiquidMinor,
                    convertToPrimary(acc.currentBalance.amountMinor, acc.currencyCode),
                )
            }
        }
        val totalLiquidCash = MinorMoney(totalLiquidMinor)
        val netLiquidMinor = MoneyFormatterAndCalculator.safeSubtract(totalLiquidMinor, totalDebtMinor)
        val netLiquidCash = MinorMoney(netLiquidMinor)
        val coverageRatio = if (totalDebtMinor > 0L) {
            BigDecimal.valueOf(totalLiquidMinor)
                .divide(BigDecimal.valueOf(totalDebtMinor), 2, RoundingMode.HALF_UP)
                .toFloat()
                .coerceAtLeast(0f)
        } else if (totalLiquidMinor > 0L) {
            999f
        } else {
            0f
        }
        val liquidCoverage = LiquidCashCoverageState(
            totalLiquidCash = totalLiquidCash,
            totalCcDebt = totalCcDebt,
            netLiquidCash = netLiquidCash,
            coverageRatio = coverageRatio,
            isCashBacked = totalLiquidMinor >= totalDebtMinor,
        )

        // 3. Cycle Window, Timeline & Smart Swipe
        val currentYearStartEpoch = LocalDate.of(today.year, 1, 1).toEpochDay()
        val timelineItems = mutableListOf<CreditCardTimelineItem>()
        val cardAnalyticsItems = mutableListOf<CreditCardAnalyticsItem>()
        var bestSmartSwipe: SmartSwipeRecommendation? = null
        var maxFloatDays = -1L
        var bestUtilization = Float.MAX_VALUE
        var singleCardExceeding30: CreditCardAnalyticsItem? = null
        val milestoneMap = CardMilestoneCalculator.parseMilestones(milestonesJson)

        // Performance Optimization: Filter and group credit expenses and refunds once to eliminate repeated full scans of allTxs
        val creditExpensesAndRefunds = allTxs.filter {
            (it.type == TransactionType.EXPENSE || it.type == TransactionType.REFUND) && it.accountId in creditCardIds
        }
        val creditExpensesByCard = creditExpensesAndRefunds.groupBy { it.accountId }

        for (card in creditCards) {
            val stmtDay = (card.settlementDayOfMonth ?: CreditCardCycleCalculator.DEFAULT_STATEMENT_DAY).coerceIn(1, 31)
            val dueDay = (card.paymentDueDayOfMonth ?: CreditCardCycleCalculator.DEFAULT_PAYMENT_DUE_DAY).coerceIn(1, 31)
            val cycle = CreditCardCycleCalculator.computeCycleWindow(
                referenceDateEpochDay = referenceDateEpochDay,
                settlementDayOfMonth = stmtDay,
                paymentDueDayOfMonth = dueDay,
            )

            val cardDebt = card.outstandingCardDebt()
            val cardLimit = card.creditLimit ?: MinorMoney.ZERO
            val cardAvailable = if (cardLimit > cardDebt) cardLimit - cardDebt else MinorMoney.ZERO
            val cardPayable = card.creditCardStatementPayable ?: MinorMoney.ZERO
            val cardUnbilled = card.creditCardUnbilledAmount ?: MinorMoney.ZERO
            val hasPriorOverdue = card.creditCardPriorOverduePayable?.isPositive == true

            val cardUtil = if (cardLimit.isPositive) {
                MoneyFormatterAndCalculator.calculatePercentage(cardDebt.amountMinor, cardLimit.amountMinor)
                    .coerceIn(0f, 100f)
            } else {
                0f
            }
            val cardStatus = evaluateUtilizationStatus(cardUtil)

            // Float days if swiped today (prioritize cards with available credit, break ties by lower utilization)
            val floatDays = ChronoUnit.DAYS.between(today, cycle.activeCycleNextDueDate).coerceAtLeast(0L)
            val hasAvailableHeadroom = cardAvailable.isPositive || (card.creditLimit == null && cardDebt.isZero)
            val isBetterCandidate = hasAvailableHeadroom && (
                floatDays > maxFloatDays ||
                    (floatDays == maxFloatDays && cardUtil < bestUtilization)
                )
            if (isBetterCandidate) {
                maxFloatDays = floatDays
                bestUtilization = cardUtil
                bestSmartSwipe = SmartSwipeRecommendation(
                    account = card,
                    floatDaysRemaining = floatDays,
                    statementCloseDate = cycle.activeCycleEnd,
                    nextDueDate = cycle.activeCycleNextDueDate,
                )
            }

            // Timeline item
            if (cardPayable.isPositive || hasPriorOverdue) {
                val effectiveDueDate = if (hasPriorOverdue && ChronoUnit.DAYS.between(today, cycle.lastStatementDueDate) >= 0) {
                    cycle.prevStatementDueDate
                } else {
                    cycle.lastStatementDueDate
                }
                val daysRem = ChronoUnit.DAYS.between(today, effectiveDueDate)
                val amountDue = if (hasPriorOverdue && cardPayable.amountMinor == 0L) {
                    card.creditCardPriorOverduePayable ?: cardPayable
                } else {
                    cardPayable
                }
                timelineItems.add(
                    CreditCardTimelineItem(
                        account = card,
                        isBilled = true,
                        amountDue = amountDue,
                        daysRemaining = daysRem,
                        targetDate = effectiveDueDate,
                        isUrgent = daysRem in 0..3,
                        isOverdue = daysRem < 0 || hasPriorOverdue,
                        floatDays = floatDays,
                    ),
                )
            } else {
                val daysRem = ChronoUnit.DAYS.between(today, cycle.activeCycleEnd)
                timelineItems.add(
                    CreditCardTimelineItem(
                        account = card,
                        isBilled = false,
                        amountDue = cardUnbilled,
                        daysRemaining = daysRem,
                        targetDate = cycle.activeCycleEnd,
                        isUrgent = false,
                        isOverdue = false,
                        floatDays = floatDays,
                    ),
                )
            }

            // Milestone calculation (supports recurring 1-year auto-renewal)
            val milestoneConfig = milestoneMap[card.id]
            val milestoneInfo = if (milestoneConfig != null) {
                val (cycleStart, cycleEnd) = CardMilestoneCalculator.computeCurrentAnniversaryWindow(
                    startDateEpochDay = milestoneConfig.cycleStartDateEpochDay,
                    referenceDateEpochDay = today.toEpochDay(),
                    isAutoRenewing = milestoneConfig.isAutoRenewing,
                )
                val cycleStartEpoch = cycleStart.toEpochDay()
                val cycleUpperEpoch = minOf(today.toEpochDay(), cycleEnd.toEpochDay())

                var qualifyingMinor = 0L
                val cardTxs = creditExpensesByCard[card.id]
                if (cardTxs != null) {
                    for (tx in cardTxs) {
                        if (tx.ledgerDateEpochDay in cycleStartEpoch..cycleUpperEpoch) {
                            val txCurrency = tx.currencyCode.ifBlank { card.currencyCode }
                            val combinedMinor = MoneyFormatterAndCalculator.safeAdd(
                                tx.amount.abs().amountMinor,
                                tx.transferFee.abs().amountMinor,
                            )
                            val signedSpend = if (tx.type == TransactionType.REFUND) {
                                MoneyFormatterAndCalculator.safeNegate(
                                    convertCurrency(tx.amount.abs().amountMinor, txCurrency, card.currencyCode),
                                )
                            } else {
                                convertCurrency(combinedMinor, txCurrency, card.currencyCode)
                            }
                            qualifyingMinor = MoneyFormatterAndCalculator.safeAdd(qualifyingMinor, signedSpend)
                        }
                    }
                }
                qualifyingMinor = qualifyingMinor.coerceAtLeast(0L)
                CardMilestoneCalculator.evaluateMilestone(
                    config = milestoneConfig,
                    qualifyingSpendMinor = qualifyingMinor,
                    referenceDate = today,
                )
            } else {
                null
            }

            // Card YTD Spend & Top Category
            var ytdMinor = 0L
            val catSpendMap = mutableMapOf<String, Long>()
            val cardTxs = creditExpensesByCard[card.id]
            if (cardTxs != null) {
                for (tx in cardTxs) {
                    if (tx.ledgerDateEpochDay in currentYearStartEpoch..referenceDateEpochDay) {
                        val txCurrency = tx.currencyCode.ifBlank { card.currencyCode }
                        val feeConverted = if (tx.type == TransactionType.EXPENSE) {
                            convertCurrency(tx.transferFee.abs().amountMinor, txCurrency, card.currencyCode)
                        } else {
                            0L
                        }
                        if (tx.isSplit && tx.splits.isNotEmpty()) {
                            for (split in tx.splits) {
                                val splitRaw = convertCurrency(split.amount.abs().amountMinor, txCurrency, card.currencyCode)
                                val signedSplit = if (tx.type == TransactionType.REFUND) {
                                    MoneyFormatterAndCalculator.safeNegate(splitRaw)
                                } else {
                                    splitRaw
                                }
                                ytdMinor = MoneyFormatterAndCalculator.safeAdd(ytdMinor, signedSplit)
                                val splitCatName = split.categoryName.ifBlank { tx.categoryName ?: "General" }
                                val cur = catSpendMap[splitCatName] ?: 0L
                                catSpendMap[splitCatName] = MoneyFormatterAndCalculator.safeAdd(cur, signedSplit)
                            }
                            if (feeConverted > 0L) {
                                ytdMinor = MoneyFormatterAndCalculator.safeAdd(ytdMinor, feeConverted)
                                val feeCatName = tx.categoryName ?: "General"
                                val cur = catSpendMap[feeCatName] ?: 0L
                                catSpendMap[feeCatName] = MoneyFormatterAndCalculator.safeAdd(cur, feeConverted)
                            }
                        } else {
                            val combinedMinor = MoneyFormatterAndCalculator.safeAdd(
                                tx.amount.abs().amountMinor,
                                tx.transferFee.abs().amountMinor,
                            )
                            val signedAmt = if (tx.type == TransactionType.REFUND) {
                                MoneyFormatterAndCalculator.safeNegate(
                                    convertCurrency(tx.amount.abs().amountMinor, txCurrency, card.currencyCode),
                                )
                            } else {
                                convertCurrency(combinedMinor, txCurrency, card.currencyCode)
                            }
                            ytdMinor = MoneyFormatterAndCalculator.safeAdd(ytdMinor, signedAmt)
                            val catName = tx.categoryName ?: "General"
                            val currentSpend = catSpendMap[catName] ?: 0L
                            catSpendMap[catName] = MoneyFormatterAndCalculator.safeAdd(currentSpend, signedAmt)
                        }
                    }
                }
            }
            ytdMinor = ytdMinor.coerceAtLeast(0L)
            val ytdSpend = MinorMoney(ytdMinor)
            val positiveCatSpend = catSpendMap.filterValues { it > 0L }
            val positiveCatSum = positiveCatSpend.values.fold(0L) { acc, v ->
                MoneyFormatterAndCalculator.safeAdd(acc, v)
            }
            val topCatEntry = positiveCatSpend.maxByOrNull { it.value }
            val topCategoryName = topCatEntry?.key
            val topCategoryPercent = if (positiveCatSum > 0L && topCatEntry != null) {
                MoneyFormatterAndCalculator.calculatePercentage(topCatEntry.value, positiveCatSum)
                    .coerceIn(0f, 100f)
            } else {
                0f
            }

            val isBilled = cardPayable.isPositive || hasPriorOverdue
            val targetDueDateOrCut = if (hasPriorOverdue && ChronoUnit.DAYS.between(today, cycle.lastStatementDueDate) >= 0) {
                cycle.prevStatementDueDate
            } else if (isBilled) {
                cycle.lastStatementDueDate
            } else {
                cycle.activeCycleEnd
            }
            val daysRem = ChronoUnit.DAYS.between(today, targetDueDateOrCut)
            val isOverdue = (isBilled && daysRem < 0) || hasPriorOverdue
            val isUrgent = isBilled && daysRem in 0..3

            val analyticsItem = CreditCardAnalyticsItem(
                account = card,
                currentDebt = cardDebt,
                creditLimit = cardLimit,
                availableCredit = cardAvailable,
                utilizationPercent = cardUtil,
                status = cardStatus,
                billedAmount = cardPayable,
                unbilledAmount = cardUnbilled,
                ytdSpend = ytdSpend,
                topCategoryName = topCategoryName,
                topCategoryPercent = topCategoryPercent,
                floatDaysRemaining = floatDays,
                isBilled = isBilled,
                daysRemaining = daysRem,
                targetDate = targetDueDateOrCut,
                isUrgent = isUrgent,
                isOverdue = isOverdue,
                milestoneInfo = milestoneInfo,
            )
            cardAnalyticsItems.add(analyticsItem)

            if (aggregateUtilization <= 30f && cardUtil > 30f && (singleCardExceeding30 == null || cardUtil > singleCardExceeding30.utilizationPercent)) {
                singleCardExceeding30 = analyticsItem
            }
        }

        // Fallback for smart swipe if all cards were maxed out (break ties by lower utilization)
        if (bestSmartSwipe == null && cardAnalyticsItems.isNotEmpty()) {
            val bestItem = cardAnalyticsItems.minWithOrNull(
                compareByDescending<CreditCardAnalyticsItem> { it.floatDaysRemaining }
                    .thenBy { it.utilizationPercent },
            )
            if (bestItem != null) {
                val stmtDay = (bestItem.account.settlementDayOfMonth ?: CreditCardCycleCalculator.DEFAULT_STATEMENT_DAY).coerceIn(1, 31)
                val dueDay = (bestItem.account.paymentDueDayOfMonth ?: CreditCardCycleCalculator.DEFAULT_PAYMENT_DUE_DAY).coerceIn(1, 31)
                val cycle = CreditCardCycleCalculator.computeCycleWindow(referenceDateEpochDay, stmtDay, dueDay)
                bestSmartSwipe = SmartSwipeRecommendation(
                    account = bestItem.account,
                    floatDaysRemaining = bestItem.floatDaysRemaining,
                    statementCloseDate = cycle.activeCycleEnd,
                    nextDueDate = cycle.activeCycleNextDueDate,
                )
            }
        }

        // Sort timeline: billed first (sorted by daysRemaining ascending), then unbilled (sorted by daysRemaining ascending)
        val sortedTimeline = timelineItems.sortedWith(
            compareBy<CreditCardTimelineItem> { !it.isBilled }
                .thenBy { it.daysRemaining },
        )

        // 4. Spend Mode Ratio (Pure EXPENSE and REFUND, excluding transfers and cc settlements, converted to Primary Currency)
        var creditSpendMinor = 0L
        var liquidSpendMinor = 0L
        for (tx in periodTransactions) {
            if (tx.type == TransactionType.EXPENSE || tx.type == TransactionType.REFUND) {
                val rawAcc = accountsById[tx.accountId]
                val effectiveAcc = if (rawAcc?.groupType == AccountGroup.DEBIT_CARD && rawAcc.parentAccountId != null) {
                    accountsById[rawAcc.parentAccountId] ?: rawAcc
                } else {
                    rawAcc
                }
                val txCurrency = tx.currencyCode.ifBlank { rawAcc?.currencyCode ?: primaryCurrencyCode }
                val fee = if (tx.type == TransactionType.EXPENSE) tx.transferFee.abs().amountMinor else 0L
                val combinedSpend = MoneyFormatterAndCalculator.safeAdd(tx.amount.abs().amountMinor, fee)
                val rawMinor = convertToPrimary(combinedSpend, txCurrency)
                val signedMinor = if (tx.type == TransactionType.REFUND) {
                    MoneyFormatterAndCalculator.safeNegate(rawMinor)
                } else {
                    rawMinor
                }

                if (tx.accountId in creditCardIds || effectiveAcc?.groupType == AccountGroup.CREDIT_CARD) {
                    creditSpendMinor = MoneyFormatterAndCalculator.safeAdd(creditSpendMinor, signedMinor)
                } else if (
                    effectiveAcc?.groupType == AccountGroup.BANK_ACCOUNT ||
                    effectiveAcc?.groupType == AccountGroup.CASH ||
                    effectiveAcc?.groupType == AccountGroup.SAVINGS ||
                    effectiveAcc?.groupType == AccountGroup.PREPAID_WALLET
                ) {
                    liquidSpendMinor = MoneyFormatterAndCalculator.safeAdd(liquidSpendMinor, signedMinor)
                }
            }
        }
        creditSpendMinor = creditSpendMinor.coerceAtLeast(0L)
        liquidSpendMinor = liquidSpendMinor.coerceAtLeast(0L)
        val totalExpenseMinor = MoneyFormatterAndCalculator.safeAdd(creditSpendMinor, liquidSpendMinor)
        val creditPct = if (totalExpenseMinor > 0L) {
            MoneyFormatterAndCalculator.calculatePercentage(creditSpendMinor, totalExpenseMinor)
        } else {
            0f
        }
        val liquidPct = if (totalExpenseMinor > 0L) {
            MoneyFormatterAndCalculator.calculatePercentage(liquidSpendMinor, totalExpenseMinor)
        } else {
            0f
        }
        val spendRatio = CreditSpendRatio(
            creditSpend = MinorMoney(creditSpendMinor),
            liquidSpend = MinorMoney(liquidSpendMinor),
            creditPercentage = creditPct,
            liquidPercentage = liquidPct,
        )

        // 5. 6-Month Historical Credit Spend Trend (converted to Primary Currency)
        val creditTrendBars = trendBars.map { bar ->
            var barSpendMinor = 0L
            for (tx in creditExpensesAndRefunds) {
                if (tx.ledgerDateEpochDay in bar.startEpochDay..bar.endEpochDay) {
                    val txCurrency = tx.currencyCode.ifBlank { accountsById[tx.accountId]?.currencyCode ?: primaryCurrencyCode }
                    val fee = if (tx.type == TransactionType.EXPENSE) tx.transferFee.abs().amountMinor else 0L
                    val combinedSpend = MoneyFormatterAndCalculator.safeAdd(tx.amount.abs().amountMinor, fee)
                    val signedAmt = if (tx.type == TransactionType.REFUND) {
                        MoneyFormatterAndCalculator.safeNegate(convertToPrimary(tx.amount.abs().amountMinor, txCurrency))
                    } else {
                        convertToPrimary(combinedSpend, txCurrency)
                    }
                    barSpendMinor = MoneyFormatterAndCalculator.safeAdd(barSpendMinor, signedAmt)
                }
            }
            val safeBarSpendMinor = barSpendMinor.coerceAtLeast(0L)
            MonthlyTrendBar(
                periodLabel = bar.periodLabel,
                startEpochDay = bar.startEpochDay,
                endEpochDay = bar.endEpochDay,
                totalIncome = MinorMoney.ZERO,
                totalExpense = MinorMoney(safeBarSpendMinor),
                netSavings = MinorMoney(MoneyFormatterAndCalculator.safeNegate(safeBarSpendMinor)),
            )
        }

        return CreditCardCockpitUiState(
            totalCreditLimit = totalLimit,
            totalCcDebt = totalCcDebt,
            totalAvailableCredit = totalAvailable,
            aggregateUtilizationPercent = aggregateUtilization,
            aggregateStatus = aggregateStatus,
            singleCardExceeding30 = singleCardExceeding30,
            liquidCoverage = liquidCoverage,
            smartSwipeRecommendation = bestSmartSwipe,
            timelineItems = sortedTimeline,
            cardAnalyticsItems = cardAnalyticsItems,
            spendRatio = spendRatio,
            sixMonthCreditTrendBars = creditTrendBars,
            activeCards = creditCards,
        )
    }
}
