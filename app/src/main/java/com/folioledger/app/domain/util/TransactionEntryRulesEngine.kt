package com.folioledger.app.domain.util

import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.SplitLineItem
import com.folioledger.app.domain.model.InstallmentPreviewItem
import com.folioledger.app.domain.model.SplitValidationSummary
import java.time.LocalDate

object TransactionEntryRulesEngine {

    fun validateSplitTransaction(
        parentTotal: MinorMoney,
        splits: List<SplitLineItem>
    ): SplitValidationSummary = try {
        if (!parentTotal.isPositive) {
            SplitValidationSummary(
                isValid = false,
                allocatedTotal = MinorMoney.ZERO,
                remainingUnallocated = parentTotal,
                errorMessage = "Enter a positive transaction total before splitting."
            )
        } else if (splits.size < 2) {
            val allocated = splits.fold(MinorMoney.ZERO) { acc, item -> acc + item.amount }
            SplitValidationSummary(
                isValid = false,
                allocatedTotal = allocated,
                remainingUnallocated = parentTotal - allocated,
                errorMessage = "A split transaction requires at least 2 split rows."
            )
        } else {
            var allocatedMinor = 0L
            var earlyExitSummary: SplitValidationSummary? = null
            for (line in splits) {
                if (line.categoryId == null || line.categoryId <= 0L) {
                    earlyExitSummary = SplitValidationSummary(
                        isValid = false,
                        allocatedTotal = MinorMoney(allocatedMinor),
                        remainingUnallocated = MinorMoney(parentTotal.amountMinor - allocatedMinor),
                        errorMessage = "Every split line must have a category selected."
                    )
                    break
                }
                if (!line.amount.isPositive) {
                    earlyExitSummary = SplitValidationSummary(
                        isValid = false,
                        allocatedTotal = MinorMoney(allocatedMinor),
                        remainingUnallocated = MinorMoney(parentTotal.amountMinor - allocatedMinor),
                        errorMessage = "Every split line amount must be strictly greater than zero."
                    )
                    break
                }
                allocatedMinor = Math.addExact(allocatedMinor, line.amount.amountMinor)
            }

            if (earlyExitSummary != null) {
                earlyExitSummary
            } else {
                val allocated = MinorMoney(allocatedMinor)
                val remaining = parentTotal - allocated
                if (remaining.isZero) {
                    SplitValidationSummary(
                        isValid = true,
                        allocatedTotal = allocated,
                        remainingUnallocated = MinorMoney.ZERO,
                        errorMessage = null
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
                        }
                    )
                }
            }
        }
    } catch (e: ArithmeticException) {
        SplitValidationSummary(
            isValid = false,
            allocatedTotal = MinorMoney.ZERO,
            remainingUnallocated = parentTotal,
            errorMessage = "Split amounts exceed maximum supported limit."
        )
    }

    fun generateInstallmentSchedule(
        totalAmount: MinorMoney,
        monthsCount: Int,
        startDateEpochDay: Long
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
                amount = MinorMoney(installmentMinor)
            )
        }
    }
}
