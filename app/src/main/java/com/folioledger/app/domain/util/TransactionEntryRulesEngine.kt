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

import com.folioledger.app.domain.model.InstallmentPreviewItem
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.SplitLineItem
import com.folioledger.app.domain.model.SplitValidationSummary
import java.time.LocalDate

object TransactionEntryRulesEngine {

    fun validateSplitTransaction(
        parentTotal: MinorMoney,
        splits: List<SplitLineItem>,
    ): SplitValidationSummary = try {
        if (!parentTotal.isPositive) {
            SplitValidationSummary(
                isValid = false,
                allocatedTotal = MinorMoney.ZERO,
                remainingUnallocated = parentTotal,
                errorMessage = "Enter a positive transaction total before splitting.",
            )
        } else if (splits.size < 2) {
            val allocated = splits.fold(MinorMoney.ZERO) { acc, item -> acc + item.amount }
            SplitValidationSummary(
                isValid = false,
                allocatedTotal = allocated,
                remainingUnallocated = parentTotal - allocated,
                errorMessage = "A split transaction requires at least 2 split rows.",
            )
        } else {
            var allocatedMinor = 0L
            var firstErrorMessage: String? = null
            for (line in splits) {
                if (firstErrorMessage == null && (line.categoryId == null || line.categoryId <= 0L)) {
                    firstErrorMessage = "Every split line must have a category selected."
                } else if (firstErrorMessage == null && !line.amount.isPositive) {
                    firstErrorMessage = "Every split line amount must be strictly greater than zero."
                }
                if (line.amount.isPositive) {
                    allocatedMinor = Math.addExact(allocatedMinor, line.amount.amountMinor)
                }
            }

            val allocated = MinorMoney(allocatedMinor)
            val remaining = parentTotal - allocated
            if (firstErrorMessage != null) {
                SplitValidationSummary(
                    isValid = false,
                    allocatedTotal = allocated,
                    remainingUnallocated = remaining,
                    errorMessage = firstErrorMessage,
                )
            } else if (remaining.isZero) {
                SplitValidationSummary(
                    isValid = true,
                    allocatedTotal = allocated,
                    remainingUnallocated = MinorMoney.ZERO,
                    errorMessage = null,
                )
            } else {
                SplitValidationSummary(
                    isValid = false,
                    allocatedTotal = allocated,
                    remainingUnallocated = remaining,
                    errorMessage = if (remaining.isPositive) {
                        "Split lines are under-allocated."
                    } else {
                        "Split lines exceed the transaction total."
                    },
                )
            }
        }
    } catch (e: ArithmeticException) {
        SplitValidationSummary(
            isValid = false,
            allocatedTotal = MinorMoney.ZERO,
            remainingUnallocated = parentTotal,
            errorMessage = "Split amounts exceed maximum supported limit.",
        )
    }

    fun generateInstallmentSchedule(
        totalAmount: MinorMoney,
        monthsCount: Int,
        startDateEpochDay: Long,
    ): List<InstallmentPreviewItem> {
        val safeMonths = monthsCount.coerceIn(2, 60)
        if (!totalAmount.isPositive || totalAmount.amountMinor < safeMonths) return emptyList()

        val baseMinor = totalAmount.amountMinor / safeMonths
        val remainderMinor = totalAmount.amountMinor % safeMonths
        val startLocalDate = LocalDate.ofEpochDay(startDateEpochDay)

        return (1..safeMonths).map { index ->
            val installmentMinor = if (index == 1) {
                baseMinor + remainderMinor
            } else {
                baseMinor
            }
            val installmentDate = startLocalDate.plusMonths((index - 1).toLong())
            InstallmentPreviewItem(
                installmentIndex = index,
                totalInstallments = safeMonths,
                dueDateEpochDay = installmentDate.toEpochDay(),
                amount = MinorMoney(installmentMinor),
            )
        }
    }
}
