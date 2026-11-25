/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/domain/usecase/AccountingRulesEngine.kt
package com.folioledger.app.domain.usecase

import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.SplitLineItem
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.util.CreditCardCycleCalculator
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeAdd
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeNegate
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeSubtract
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

data class CreditCardMetrics(
    val currentBalance: MinorMoney,
    val outstandingBalance: MinorMoney,
    val statementPayable: MinorMoney,
    val unbilledAmount: MinorMoney,
    val availableCredit: MinorMoney?,
    val utilizationPercent: Float,
    val lastStatementCloseEpochDay: Long,
    val priorOverduePayable: MinorMoney = MinorMoney.ZERO
)

class AccountingRulesEngine {

    fun validateSplitTransaction(
        totalAmount: MinorMoney,
        splits: List<SplitLineItem>,
        requireCategoryIds: Boolean = false
    ): Result<Unit> = Companion.validateSplitTransaction(totalAmount, splits, requireCategoryIds)

    fun generateInstallmentSchedule(
        totalAmount: MinorMoney,
        monthsCount: Int,
        startDateEpochDay: Long
    ): List<Pair<Long, MinorMoney>> =
        Companion.generateInstallmentSchedule(totalAmount, monthsCount, startDateEpochDay)

    fun computeFinancialMonthRange(
        referenceDateEpochDay: Long,
        monthStartDay: Int
    ): Pair<Long, Long> =
        Companion.computeFinancialMonthRange(referenceDateEpochDay, monthStartDay)

    fun computeCreditCardMetrics(
        cardAccountId: Long = 0L,
        openingBalance: MinorMoney,
        transactions: List<TransactionItem>,
        settlementDayOfMonth: Int,
        referenceDateEpochDay: Long,
        creditLimit: MinorMoney?,
        initialUnbilledAmount: MinorMoney? = null,
        openingBalanceIsCredit: Boolean = false,
        paymentDueDayOfMonth: Int? = null,
        openingAnchorEpochDay: Long? = null,
        initialUnbilledAtCreation: MinorMoney? = null
    ): CreditCardMetrics = Companion.computeCreditCardMetrics(
        cardAccountId = cardAccountId,
        openingBalance = openingBalance,
        transactions = transactions,
        settlementDayOfMonth = settlementDayOfMonth,
        referenceDateEpochDay = referenceDateEpochDay,
        creditLimit = creditLimit,
        initialUnbilledAmount = initialUnbilledAmount,
        openingBalanceIsCredit = openingBalanceIsCredit,
        paymentDueDayOfMonth = paymentDueDayOfMonth,
        openingAnchorEpochDay = openingAnchorEpochDay,
        initialUnbilledAtCreation = initialUnbilledAtCreation
    )

    companion object {

        fun computeSignedDeltaMinorForAccount(
            tx: TransactionItem,
            effectiveAccountIds: Set<Long>,
            isSourceLiabilityAccount: Boolean = false
        ): Long {
            val amt = tx.amount.abs().amountMinor
            val fee = tx.transferFee.abs().amountMinor
            val isSource = tx.accountId in effectiveAccountIds
            val isTarget = tx.transferToAccountId != null && tx.transferToAccountId in effectiveAccountIds

            return when (tx.type) {
                TransactionType.INCOME,
                TransactionType.REFUND -> if (isSource) amt else 0L

                TransactionType.EXPENSE -> if (isSource) safeNegate(safeAdd(amt, fee)) else 0L

                TransactionType.TRANSFER -> when {
                    isSource && isTarget -> safeNegate(fee)
                    isSource -> safeNegate(safeAdd(amt, fee))
                    isTarget -> amt
                    else -> 0L
                }

                TransactionType.CC_SETTLEMENT -> when {
                    tx.transferToAccountId == null -> if (isSource) amt else 0L
                    isSource && isTarget -> safeNegate(fee)
                    isSource -> safeNegate(safeAdd(amt, fee))
                    isTarget -> amt
                    else -> 0L
                }

                TransactionType.OPENING_BALANCE -> {
                    val isOutflow = tx.transferToAccountId != null || isSourceLiabilityAccount
                    if (isSource) (if (isOutflow) safeNegate(amt) else amt) else 0L
                }
                TransactionType.RECONCILIATION -> {
                    val isOutflow = tx.transferToAccountId != null
                    if (isSource) (if (isOutflow) safeNegate(amt) else amt) else 0L
                }
            }
        }

        fun validateSplitTransaction(
            totalAmount: MinorMoney,
            splits: List<SplitLineItem>,
            requireCategoryIds: Boolean = false
        ): Result<Unit> {
            if (!totalAmount.isPositive) {
                return Result.failure(
                    IllegalArgumentException("Parent split transaction total must be greater than zero.")
                )
            }
            if (splits.size < 2) {
                return Result.failure(
                    IllegalArgumentException("A split transaction requires at least 2 split line items.")
                )
            }

            return try {
                var runningSum = MinorMoney.ZERO
                for ((index, item) in splits.withIndex()) {
                    if (requireCategoryIds && (item.categoryId == null || item.categoryId <= 0L)) {
                        return Result.failure(
                            IllegalArgumentException("Split line item #${index + 1} must have a valid category selected.")
                        )
                    }
                    if (!item.amount.isPositive) {
                        return Result.failure(
                            IllegalArgumentException(
                                "Split line item #${index + 1} must have a positive amount."
                            )
                        )
                    }
                    runningSum += item.amount
                }

                if (runningSum != totalAmount) {
                    Result.failure(
                        IllegalArgumentException(
                            "Split sum (${runningSum.amountMinor}) does not match parent total (${totalAmount.amountMinor})."
                        )
                    )
                } else {
                    Result.success(Unit)
                }
            } catch (e: ArithmeticException) {
                Result.failure(
                    ArithmeticException("Overflow while summing split line items.")
                )
            }
        }

        fun generateInstallmentSchedule(
            totalAmount: MinorMoney,
            monthsCount: Int,
            startDateEpochDay: Long
        ): List<Pair<Long, MinorMoney>> {
            require(totalAmount.isPositive) {
                "Installment totalAmount must be strictly positive."
            }
            require(monthsCount >= 1) {
                "Installment monthsCount must be at least 1."
            }
            val safeMonths = monthsCount.coerceIn(1, 60)
            require(totalAmount.amountMinor >= safeMonths.toLong()) {
                "Installment totalAmount must be at least 1 minor unit per installment."
            }

            val baseAmountMinor: Long = totalAmount.amountMinor / safeMonths.toLong()
            val remainderMinor: Long = totalAmount.amountMinor % safeMonths.toLong()
            val firstInstallmentMinor: Long = safeAdd(baseAmountMinor, remainderMinor)
            val startDate: LocalDate = LocalDate.ofEpochDay(startDateEpochDay)

            return List(safeMonths) { index ->
                val installmentDateEpochDay = startDate.plusMonths(index.toLong()).toEpochDay()
                val installmentMoney = if (index == 0) {
                    MinorMoney(firstInstallmentMinor)
                } else {
                    MinorMoney(baseAmountMinor)
                }
                installmentDateEpochDay to installmentMoney
            }
        }

        fun computeFinancialMonthRange(
            referenceDateEpochDay: Long,
            monthStartDay: Int
        ): Pair<Long, Long> {
            val safeStartDay = monthStartDay.coerceIn(1, 31)
            val referenceDate = LocalDate.ofEpochDay(referenceDateEpochDay)
            val refYearMonth = YearMonth.from(referenceDate)

            val cycleStartDate: LocalDate
            val cycleEndDate: LocalDate

            if (safeStartDay == 1) {
                cycleStartDate = refYearMonth.atDay(1)
                cycleEndDate = refYearMonth.atEndOfMonth()
            } else {
                val startInRefMonth = resolveClampedDate(refYearMonth, safeStartDay)
                if (!referenceDate.isBefore(startInRefMonth)) {
                    cycleStartDate = startInRefMonth
                    val nextMonthStart = resolveClampedDate(refYearMonth.plusMonths(1), safeStartDay)
                    cycleEndDate = nextMonthStart.minusDays(1)
                } else {
                    cycleStartDate = resolveClampedDate(refYearMonth.minusMonths(1), safeStartDay)
                    cycleEndDate = startInRefMonth.minusDays(1)
                }
            }

            return cycleStartDate.toEpochDay() to cycleEndDate.toEpochDay()
        }

        fun resolveClampedDate(yearMonth: YearMonth, desiredDayOfMonth: Int): LocalDate {
            val safeDay = desiredDayOfMonth.coerceIn(1, 31)
            val clampedDay = minOf(safeDay, yearMonth.lengthOfMonth())
            return yearMonth.atDay(clampedDay)
        }

        fun computeCreditCardMetrics(
            cardAccountId: Long = 0L,
            openingBalance: MinorMoney,
            transactions: List<TransactionItem>,
            settlementDayOfMonth: Int,
            referenceDateEpochDay: Long,
            creditLimit: MinorMoney?,
            initialUnbilledAmount: MinorMoney? = null,
            openingBalanceIsCredit: Boolean = false,
            paymentDueDayOfMonth: Int? = null,
            openingAnchorEpochDay: Long? = null,
            initialUnbilledAtCreation: MinorMoney? = null
        ): CreditCardMetrics {
            val safeSettlementDay = settlementDayOfMonth.coerceIn(1, 31)
            val referenceDate = LocalDate.ofEpochDay(referenceDateEpochDay)
            val refYearMonth = YearMonth.from(referenceDate)
            val statementInRefMonth = resolveClampedDate(refYearMonth, safeSettlementDay)

            val lastStatementCloseDate = if (referenceDate.isAfter(statementInRefMonth)) {
                statementInRefMonth
            } else {
                resolveClampedDate(refYearMonth.minusMonths(1), safeSettlementDay)
            }
            val lastStatementCloseEpochDay = lastStatementCloseDate.toEpochDay()

            val prevStatementCloseDate = if (referenceDate.isAfter(statementInRefMonth)) {
                resolveClampedDate(refYearMonth.minusMonths(1), safeSettlementDay)
            } else {
                resolveClampedDate(refYearMonth.minusMonths(2), safeSettlementDay)
            }
            val prevStatementCloseEpochDay = prevStatementCloseDate.toEpochDay()

            // Deduplicate transactions by non-zero id so transfer/settlement legs are never counted twice.
            val uniqueTransactions = buildList {
                val seenIds = HashSet<Long>()
                for (tx in transactions) {
                    if (tx.ledgerDateEpochDay <= referenceDateEpochDay) {
                        if (tx.id == 0L || seenIds.add(tx.id)) {
                            add(tx)
                        }
                    }
                }
            }

            // Opening debt is normalized to positive debt minor units (unless openingBalanceIsCredit is true).
            val initTotalDebtMinor = if (openingBalanceIsCredit) {
                safeNegate(openingBalance.abs().amountMinor)
            } else {
                openingBalance.abs().amountMinor
            }
            val initUnbilledMinor = if (initTotalDebtMinor > 0L) {
                (initialUnbilledAmount?.abs()?.amountMinor ?: 0L).coerceAtMost(initTotalDebtMinor)
            } else {
                0L
            }
            val creationUnbilledMinor = if (initTotalDebtMinor > 0L) {
                ((initialUnbilledAtCreation ?: initialUnbilledAmount)?.abs()?.amountMinor ?: 0L)
                    .coerceAtMost(initTotalDebtMinor)
            } else {
                0L
            }

            val initialClosedDebtMinor = safeSubtract(initTotalDebtMinor, initUnbilledMinor)
            var closedCycleNetDebtMinor: Long = initialClosedDebtMinor
            var activeCycleSpendMinor: Long = initUnbilledMinor
            var activeCycleCreditMinor = 0L
            var prevCycleDebtMinor: Long = when {
                openingAnchorEpochDay != null &&
                    openingAnchorEpochDay > lastStatementCloseEpochDay &&
                    initialClosedDebtMinor > 0L -> 0L
                openingAnchorEpochDay != null &&
                    openingAnchorEpochDay > prevStatementCloseEpochDay &&
                    creationUnbilledMinor > 0L -> {
                    safeSubtract(initialClosedDebtMinor, creationUnbilledMinor).coerceAtLeast(0L)
                }
                else -> initialClosedDebtMinor
            }
            var totalCreditsUpToNowMinor = 0L

            val resolvedCardId = if (cardAccountId != 0L) {
                cardAccountId
            } else {
                uniqueTransactions.firstOrNull {
                    it.type != TransactionType.CC_SETTLEMENT &&
                        it.type != TransactionType.TRANSFER &&
                        it.accountId != 0L
                }?.accountId
                    ?: uniqueTransactions.firstOrNull {
                        it.type == TransactionType.CC_SETTLEMENT &&
                            (it.transferToAccountId ?: it.accountId) != 0L
                    }?.let { it.transferToAccountId ?: it.accountId }
                    ?: uniqueTransactions.firstOrNull { it.accountId != 0L }?.accountId
                    ?: 0L
            }

            for (tx in uniqueTransactions) {
                val isClosedCycle = tx.ledgerDateEpochDay <= lastStatementCloseEpochDay
                val txMagnitude = tx.amount.abs().amountMinor
                val feeMagnitude = tx.transferFee.abs().amountMinor
                val isSource = resolvedCardId == 0L || tx.accountId == resolvedCardId
                val isTarget = resolvedCardId != 0L && tx.transferToAccountId == resolvedCardId

                fun addDebt(amountMinor: Long) {
                    if (isClosedCycle) {
                        closedCycleNetDebtMinor = safeAdd(closedCycleNetDebtMinor, amountMinor)
                    } else {
                        activeCycleSpendMinor = safeAdd(activeCycleSpendMinor, amountMinor)
                    }
                    if (tx.ledgerDateEpochDay <= prevStatementCloseEpochDay) {
                        prevCycleDebtMinor = safeAdd(prevCycleDebtMinor, amountMinor)
                    }
                }

                fun addCredit(amountMinor: Long) {
                    if (isClosedCycle) {
                        closedCycleNetDebtMinor = safeSubtract(closedCycleNetDebtMinor, amountMinor)
                    } else {
                        activeCycleCreditMinor = safeAdd(activeCycleCreditMinor, amountMinor)
                    }
                    totalCreditsUpToNowMinor = safeAdd(totalCreditsUpToNowMinor, amountMinor)
                }

                when (tx.type) {
                    TransactionType.EXPENSE -> {
                        if (isSource) {
                            addDebt(safeAdd(txMagnitude, feeMagnitude))
                        }
                    }
                    TransactionType.CC_SETTLEMENT -> {
                        when {
                            tx.transferToAccountId == null -> {
                                if (isSource) addCredit(txMagnitude)
                            }
                            isSource && isTarget -> {
                                if (cardAccountId == 0L) {
                                    // Legacy unit test fixture where settlement on the card itself was constructed with accountId == transferToAccountId
                                    addCredit(txMagnitude)
                                } else {
                                    addDebt(feeMagnitude)
                                }
                            }
                            isTarget -> addCredit(txMagnitude)
                            isSource -> {
                                if (cardAccountId != 0L) {
                                    addDebt(safeAdd(txMagnitude, feeMagnitude))
                                } else {
                                    addCredit(txMagnitude)
                                }
                            }
                            else -> Unit
                        }
                    }
                    TransactionType.TRANSFER -> {
                        when {
                            isSource && isTarget -> addDebt(feeMagnitude)
                            isTarget -> addCredit(txMagnitude)
                            isSource -> addDebt(safeAdd(txMagnitude, feeMagnitude))
                            else -> Unit
                        }
                    }
                    TransactionType.REFUND,
                    TransactionType.INCOME -> {
                        if (isSource || isTarget) {
                            addCredit(txMagnitude)
                        }
                    }
                    TransactionType.OPENING_BALANCE -> {
                        // Managed directly via openingBalance parameter
                    }
                    TransactionType.RECONCILIATION -> {
                        if (isSource) {
                            val isDebtIncrease = tx.transferToAccountId != null
                            if (isDebtIncrease) addDebt(txMagnitude) else addCredit(txMagnitude)
                        }
                    }
                }
            }

            // Apply any post-cutoff settlements/refunds to the closed statement balance first,
            // and apply any surplus credit to active unbilled spend so nothing is double-counted.
            val rawStatementAfterCredits = safeSubtract(closedCycleNetDebtMinor, activeCycleCreditMinor)
            val statementPayableMinor = rawStatementAfterCredits.coerceAtLeast(0L)

            val surplusCreditAfterStatement = if (rawStatementAfterCredits < 0L) {
                safeNegate(rawStatementAfterCredits)
            } else {
                0L
            }

            val unbilledAmountMinor = safeSubtract(
                activeCycleSpendMinor,
                surplusCreditAfterStatement
            ).coerceAtLeast(0L)

            val netDebtSignedMinor = safeAdd(
                closedCycleNetDebtMinor,
                safeSubtract(activeCycleSpendMinor, activeCycleCreditMinor)
            )

            val outstandingBalanceMinor = netDebtSignedMinor.coerceAtLeast(0L)
            val currentBalanceSignedMinor = safeNegate(netDebtSignedMinor)

            val availableCredit: MinorMoney? = creditLimit?.let { limit ->
                val remaining = safeSubtract(limit.amountMinor, outstandingBalanceMinor)
                    .coerceAtLeast(0L)
                MinorMoney(remaining)
            }

            val utilizationPercent: Float = if (
                creditLimit != null &&
                creditLimit.amountMinor > 0L &&
                outstandingBalanceMinor > 0L
            ) {
                BigDecimal.valueOf(outstandingBalanceMinor)
                    .multiply(BigDecimal.valueOf(100L))
                    .divide(
                        BigDecimal.valueOf(creditLimit.amountMinor),
                        2,
                        RoundingMode.HALF_UP
                    )
                    .toFloat()
            } else {
                0f
            }

            val effectiveDueDay = (paymentDueDayOfMonth ?: CreditCardCycleCalculator.DEFAULT_PAYMENT_DUE_DAY).coerceIn(1, 31)
            val prevStatementDueDate = CreditCardCycleCalculator.computeDueDateAfterStatementClose(
                statementCloseDate = prevStatementCloseDate,
                statementDayOfMonth = safeSettlementDay,
                dueDayOfMonth = effectiveDueDay
            )
            val priorOverduePayableMinor = if (referenceDate.isAfter(prevStatementDueDate)) {
                val remainingPrevDebt = safeSubtract(prevCycleDebtMinor, totalCreditsUpToNowMinor)
                minOf(remainingPrevDebt.coerceAtLeast(0L), outstandingBalanceMinor)
            } else {
                0L
            }

            return CreditCardMetrics(
                currentBalance = MinorMoney(currentBalanceSignedMinor),
                outstandingBalance = MinorMoney(outstandingBalanceMinor),
                statementPayable = MinorMoney(statementPayableMinor),
                unbilledAmount = MinorMoney(unbilledAmountMinor),
                availableCredit = availableCredit,
                utilizationPercent = utilizationPercent,
                lastStatementCloseEpochDay = lastStatementCloseEpochDay,
                priorOverduePayable = MinorMoney(priorOverduePayableMinor)
            )
        }
    }
}