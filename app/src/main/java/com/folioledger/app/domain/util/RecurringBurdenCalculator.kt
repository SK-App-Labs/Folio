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

import com.folioledger.app.domain.model.InstallmentPlanWithProgress
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.RecurringCommitmentItem
import com.folioledger.app.domain.model.RepeatFrequency
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

data class RecurringBurdenSummary(
    val activeRecurringCount: Int,
    val monthlyRecurringExpense: MinorMoney,
    val monthlyRecurringTransfer: MinorMoney,
    val monthlyInstallments: MinorMoney,
    val totalMonthlyFixedBurden: MinorMoney
)

object RecurringBurdenCalculator {

    private val LONG_MIN_BD = BigDecimal.valueOf(Long.MIN_VALUE)
    private val LONG_MAX_BD = BigDecimal.valueOf(Long.MAX_VALUE)

    private fun multiplyClamped(amountMinor: Long, multiplier: Long): Long {
        return BigDecimal.valueOf(amountMinor)
            .multiply(BigDecimal.valueOf(multiplier))
            .coerceIn(LONG_MIN_BD, LONG_MAX_BD)
            .toLong()
    }

    private fun divideHalfUp(amountMinor: Long, divisor: Long): Long {
        return BigDecimal.valueOf(amountMinor)
            .divide(BigDecimal.valueOf(divisor), 0, RoundingMode.HALF_UP)
            .toLong()
    }

    fun computeMonthlyEquivalent(amountMinor: Long, frequency: RepeatFrequency): Long {
        return when (frequency) {
            RepeatFrequency.EVERY_DAY -> multiplyClamped(amountMinor, 30L)
            RepeatFrequency.WEEKDAYS -> multiplyClamped(amountMinor, 22L)
            RepeatFrequency.WEEKENDS -> multiplyClamped(amountMinor, 8L)
            RepeatFrequency.EVERY_WEEK -> BigDecimal.valueOf(amountMinor)
                .multiply(BigDecimal.valueOf(52L))
                .divide(BigDecimal.valueOf(12L), 0, RoundingMode.HALF_UP)
                .coerceIn(LONG_MIN_BD, LONG_MAX_BD)
                .toLong()
            RepeatFrequency.EVERY_2_WEEKS -> BigDecimal.valueOf(amountMinor)
                .multiply(BigDecimal.valueOf(26L))
                .divide(BigDecimal.valueOf(12L), 0, RoundingMode.HALF_UP)
                .coerceIn(LONG_MIN_BD, LONG_MAX_BD)
                .toLong()
            RepeatFrequency.EVERY_4_WEEKS -> BigDecimal.valueOf(amountMinor)
                .multiply(BigDecimal.valueOf(13L))
                .divide(BigDecimal.valueOf(12L), 0, RoundingMode.HALF_UP)
                .coerceIn(LONG_MIN_BD, LONG_MAX_BD)
                .toLong()
            RepeatFrequency.EVERY_MONTH, RepeatFrequency.END_OF_MONTH -> amountMinor
            RepeatFrequency.EVERY_2_MONTHS -> divideHalfUp(amountMinor, 2L)
            RepeatFrequency.EVERY_3_MONTHS -> divideHalfUp(amountMinor, 3L)
            RepeatFrequency.EVERY_4_MONTHS -> divideHalfUp(amountMinor, 4L)
            RepeatFrequency.EVERY_6_MONTHS -> divideHalfUp(amountMinor, 6L)
            RepeatFrequency.ANNUALLY -> divideHalfUp(amountMinor, 12L)
        }
    }

    fun computeMonthlyBurden(
        recurringRules: List<RecurringCommitmentItem>,
        installmentPlans: List<InstallmentPlanWithProgress>,
        primaryCurrencyCode: String = "INR",
        secondaryCurrencies: List<SecondaryCurrency> = emptyList()
    ): RecurringBurdenSummary {
        val primarySpec = SupportedCurrencies.fromCode(primaryCurrencyCode)
        val secondaryMap = secondaryCurrencies.associateBy { it.code.trim().uppercase(Locale.ROOT) }

        fun toPrimaryMinor(amountMinor: Long, currencyCode: String): Long {
            val sec = secondaryMap[currencyCode.trim().uppercase(Locale.ROOT)]
            return MoneyFormatterAndCalculator.convertToPrimaryMinor(
                sourceMinor = amountMinor,
                sourceCurrencyCode = currencyCode,
                primaryCurrencyCode = primarySpec.code,
                exchangeRate = sec?.exchangeRateToPrimary
            )
        }

        val activeRules = recurringRules.filter { it.isActive }
        var monthlyExpenseMinor = 0L
        var monthlyTransferMinor = 0L

        for (rule in activeRules) {
            val convertedMinor = toPrimaryMinor(rule.amount.amountMinor, rule.currencyCode)
            val monthlyEquivalent = computeMonthlyEquivalent(convertedMinor, rule.frequency)
            when (rule.type) {
                TransactionType.EXPENSE ->
                    monthlyExpenseMinor = MoneyFormatterAndCalculator.safeAdd(monthlyExpenseMinor, monthlyEquivalent)
                TransactionType.TRANSFER ->
                    monthlyTransferMinor = MoneyFormatterAndCalculator.safeAdd(monthlyTransferMinor, monthlyEquivalent)
                else -> Unit
            }
        }

        val activeInstallments = installmentPlans.filter { it.remainingInstallmentsCount > 0 }
        var monthlyInstallmentsMinor = 0L
        for (plan in activeInstallments) {
            monthlyInstallmentsMinor = MoneyFormatterAndCalculator.safeAdd(
                monthlyInstallmentsMinor,
                toPrimaryMinor(plan.monthlyAmount.amountMinor, plan.currencyCode)
            )
        }
        val totalMonthlyFixedMinor = MoneyFormatterAndCalculator.safeAdd(monthlyExpenseMinor, monthlyInstallmentsMinor)

        return RecurringBurdenSummary(
            activeRecurringCount = activeRules.size,
            monthlyRecurringExpense = MinorMoney(monthlyExpenseMinor),
            monthlyRecurringTransfer = MinorMoney(monthlyTransferMinor),
            monthlyInstallments = MinorMoney(monthlyInstallmentsMinor),
            totalMonthlyFixedBurden = MinorMoney(totalMonthlyFixedMinor)
        )
    }
}
