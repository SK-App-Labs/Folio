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
import com.folioledger.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test

class RecurringBurdenCalculatorTest {

    @Test
    fun computeMonthlyEquivalent_multipliersAreCorrect() {
        val base = 1000L // 10.00
        assertEquals(30000L, RecurringBurdenCalculator.computeMonthlyEquivalent(base, RepeatFrequency.EVERY_DAY))
        assertEquals(22000L, RecurringBurdenCalculator.computeMonthlyEquivalent(base, RepeatFrequency.WEEKDAYS))
        assertEquals(8000L, RecurringBurdenCalculator.computeMonthlyEquivalent(base, RepeatFrequency.WEEKENDS))
        assertEquals(4333L, RecurringBurdenCalculator.computeMonthlyEquivalent(base, RepeatFrequency.EVERY_WEEK))
        assertEquals(2167L, RecurringBurdenCalculator.computeMonthlyEquivalent(base, RepeatFrequency.EVERY_2_WEEKS))
        assertEquals(1083L, RecurringBurdenCalculator.computeMonthlyEquivalent(base, RepeatFrequency.EVERY_4_WEEKS))
        assertEquals(1000L, RecurringBurdenCalculator.computeMonthlyEquivalent(base, RepeatFrequency.EVERY_MONTH))
        assertEquals(1000L, RecurringBurdenCalculator.computeMonthlyEquivalent(base, RepeatFrequency.END_OF_MONTH))
        assertEquals(500L, RecurringBurdenCalculator.computeMonthlyEquivalent(base, RepeatFrequency.EVERY_2_MONTHS))
        assertEquals(333L, RecurringBurdenCalculator.computeMonthlyEquivalent(base, RepeatFrequency.EVERY_3_MONTHS))
        assertEquals(250L, RecurringBurdenCalculator.computeMonthlyEquivalent(base, RepeatFrequency.EVERY_4_MONTHS))
        assertEquals(167L, RecurringBurdenCalculator.computeMonthlyEquivalent(base, RepeatFrequency.EVERY_6_MONTHS))
        assertEquals(83L, RecurringBurdenCalculator.computeMonthlyEquivalent(base, RepeatFrequency.ANNUALLY))
        assertEquals(100L, RecurringBurdenCalculator.computeMonthlyEquivalent(1199L, RepeatFrequency.ANNUALLY))
    }

    @Test
    fun computeMonthlyBurden_handlesTurkishLocaleSecondaryCurrencyAndOverflowSafeMath() {
        val previousLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            val rule = RecurringCommitmentItem(
                id = 1L,
                title = "Cloud Storage",
                type = TransactionType.EXPENSE,
                amount = MinorMoney(1000L), // $10.00
                currencyCode = "usd",
                accountId = 10L,
                frequency = RepeatFrequency.EVERY_MONTH,
                nextOccurrenceEpochDay = 1000L,
                isActive = true,
            )
            val secondary = com.folioledger.app.domain.model.SecondaryCurrency(
                code = "usd",
                exchangeRateToPrimary = 80.0,
            )

            val burden = RecurringBurdenCalculator.computeMonthlyBurden(
                recurringRules = listOf(rule),
                installmentPlans = emptyList(),
                primaryCurrencyCode = "INR",
                secondaryCurrencies = listOf(secondary),
            )
            assertEquals(80000L, burden.monthlyRecurringExpense.amountMinor)
            assertEquals(Long.MAX_VALUE, RecurringBurdenCalculator.computeMonthlyEquivalent(Long.MAX_VALUE / 2L, RepeatFrequency.EVERY_DAY))
        } finally {
            java.util.Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun computeMonthlyBurden_filtersInactiveRulesAndIncludesActiveInstallments() {
        val activeExpense = RecurringCommitmentItem(
            id = 1L,
            title = "Netflix",
            type = TransactionType.EXPENSE,
            amount = MinorMoney(50000L), // 500.00 / month
            currencyCode = "INR",
            accountId = 10L,
            frequency = RepeatFrequency.EVERY_MONTH,
            nextOccurrenceEpochDay = 1000L,
            isActive = true,
        )
        val inactiveExpense = RecurringCommitmentItem(
            id = 2L,
            title = "Gym",
            type = TransactionType.EXPENSE,
            amount = MinorMoney(100000L),
            currencyCode = "INR",
            accountId = 10L,
            frequency = RepeatFrequency.EVERY_MONTH,
            nextOccurrenceEpochDay = 1000L,
            isActive = false, // inactive -> should be ignored
        )
        val activeTransfer = RecurringCommitmentItem(
            id = 3L,
            title = "Savings Transfer",
            type = TransactionType.TRANSFER,
            amount = MinorMoney(200000L), // 2000.00 / month
            currencyCode = "INR",
            accountId = 10L,
            transferToAccountId = 20L,
            frequency = RepeatFrequency.EVERY_MONTH,
            nextOccurrenceEpochDay = 1000L,
            isActive = true,
        )

        val activeInstallment = InstallmentPlanWithProgress(
            id = 1L,
            title = "MacBook EMI",
            accountId = 30L,
            accountName = "Credit Card",
            categoryId = null,
            categoryName = "Electronics",
            categoryColorHex = null,
            categoryIconName = null,
            totalAmount = MinorMoney(30000L),
            monthsCount = 3,
            paidInstallmentsCount = 1,
            remainingInstallmentsCount = 2,
            paidAmount = MinorMoney(10000L),
            remainingAmount = MinorMoney(20000L),
            monthlyAmount = MinorMoney(10000L),
            startDateEpochDay = 1000L,
            nextDueDateEpochDay = 1050L,
        )
        val finishedInstallment = InstallmentPlanWithProgress(
            id = 2L,
            title = "Phone EMI",
            accountId = 30L,
            accountName = "Credit Card",
            categoryId = null,
            categoryName = "Phone",
            categoryColorHex = null,
            categoryIconName = null,
            totalAmount = MinorMoney(50000L),
            monthsCount = 2,
            paidInstallmentsCount = 2,
            remainingInstallmentsCount = 0, // finished -> should be ignored
            paidAmount = MinorMoney(50000L),
            remainingAmount = MinorMoney.ZERO,
            monthlyAmount = MinorMoney(25000L),
            startDateEpochDay = 950L,
            nextDueDateEpochDay = 1020L,
        )

        val burden = RecurringBurdenCalculator.computeMonthlyBurden(
            recurringRules = listOf(activeExpense, inactiveExpense, activeTransfer),
            installmentPlans = listOf(activeInstallment, finishedInstallment),
        )

        assertEquals(2, burden.activeRecurringCount)
        assertEquals(50000L, burden.monthlyRecurringExpense.amountMinor)
        assertEquals(200000L, burden.monthlyRecurringTransfer.amountMinor)
        assertEquals(10000L, burden.monthlyInstallments.amountMinor)
        assertEquals(60000L, burden.totalMonthlyFixedBurden.amountMinor) // 50000 + 10000
    }
}
