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

data class RecurringBurdenSummary(
    val activeRecurringCount: Int,
    val monthlyRecurringExpense: MinorMoney,
    val monthlyRecurringTransfer: MinorMoney,
    val monthlyInstallments: MinorMoney,
    val totalMonthlyFixedBurden: MinorMoney
)

object RecurringBurdenCalculator {

    fun computeMonthlyEquivalent(amountMinor: Long, frequency: RepeatFrequency): Long {
        return when (frequency) {
            RepeatFrequency.EVERY_DAY -> amountMinor * 30L
            RepeatFrequency.WEEKDAYS -> amountMinor * 22L
            RepeatFrequency.WEEKENDS -> amountMinor * 8L
            RepeatFrequency.EVERY_WEEK -> BigDecimal.valueOf(amountMinor)
                .multiply(BigDecimal.valueOf(52L))
                .divide(BigDecimal.valueOf(12L), 0, RoundingMode.HALF_UP)
                .toLong()
            RepeatFrequency.EVERY_2_WEEKS -> BigDecimal.valueOf(amountMinor)
                .multiply(BigDecimal.valueOf(26L))
                .divide(BigDecimal.valueOf(12L), 0, RoundingMode.HALF_UP)
                .toLong()
            RepeatFrequency.EVERY_4_WEEKS -> BigDecimal.valueOf(amountMinor)
                .multiply(BigDecimal.valueOf(13L))
                .divide(BigDecimal.valueOf(12L), 0, RoundingMode.HALF_UP)
                .toLong()
            RepeatFrequency.EVERY_MONTH, RepeatFrequency.END_OF_MONTH -> amountMinor
            RepeatFrequency.EVERY_2_MONTHS -> amountMinor / 2L
            RepeatFrequency.EVERY_3_MONTHS -> amountMinor / 3L
            RepeatFrequency.EVERY_4_MONTHS -> amountMinor / 4L
            RepeatFrequency.EVERY_6_MONTHS -> amountMinor / 6L
            RepeatFrequency.ANNUALLY -> amountMinor / 12L
        }
    }

    fun computeMonthlyBurden(
        recurringRules: List<RecurringCommitmentItem>,
        installmentPlans: List<InstallmentPlanWithProgress>,
        primaryCurrencyCode: String = "INR",
        secondaryCurrencies: List<SecondaryCurrency> = emptyList()
    ): RecurringBurdenSummary {
        val primarySpec = SupportedCurrencies.fromCode(primaryCurrencyCode)
        val secondaryMap = secondaryCurrencies.associateBy { it.code.trim().uppercase() }

        fun toPrimaryMinor(amountMinor: Long, currencyCode: String): Long {
            return MoneyFormatterAndCalculator.convertToPrimaryMinor(
                sourceMinor = amountMinor,
                sourceCurrencyCode = currencyCode,
                primaryCurrencyCode = primarySpec.code,
                secondaryCurrencies = secondaryCurrencies
            )
        }

        val activeRules = recurringRules.filter { it.isActive }
        var monthlyExpenseMinor = 0L
        var monthlyTransferMinor = 0L

        for (rule in activeRules) {
            val convertedMinor = toPrimaryMinor(rule.amount.amountMinor, rule.currencyCode)
            val monthlyEquivalent = computeMonthlyEquivalent(convertedMinor, rule.frequency)
            when (rule.type) {
                TransactionType.EXPENSE -> monthlyExpenseMinor += monthlyEquivalent
                TransactionType.TRANSFER -> monthlyTransferMinor += monthlyEquivalent
                else -> Unit
            }
        }

        val activeInstallments = installmentPlans.filter { it.remainingInstallmentsCount > 0 }
        val monthlyInstallmentsMinor = activeInstallments.sumOf {
            toPrimaryMinor(it.monthlyAmount.amountMinor, it.currencyCode)
        }
        val totalMonthlyFixedMinor = monthlyExpenseMinor + monthlyInstallmentsMinor

        return RecurringBurdenSummary(
            activeRecurringCount = activeRules.size,
            monthlyRecurringExpense = MinorMoney(monthlyExpenseMinor),
            monthlyRecurringTransfer = MinorMoney(monthlyTransferMinor),
            monthlyInstallments = MinorMoney(monthlyInstallmentsMinor),
            totalMonthlyFixedBurden = MinorMoney(totalMonthlyFixedMinor)
        )
    }
}
