/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/domain/util/DueRemindersEvaluator.kt
package com.folioledger.app.domain.util

import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.BillDueReminderItem
import com.folioledger.app.domain.model.CardDueReminderItem
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.RecurringCommitmentItem
import com.folioledger.app.domain.model.TransactionType
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object DueRemindersEvaluator {

    fun evaluateCreditCardReminders(
        accounts: List<AccountSummary>,
        todayEpochDay: Long,
        leadDaysNotice: Int = 3,
        enforceOverdueRecencyWindow: Boolean = false,
    ): List<CardDueReminderItem> {
        val today = LocalDate.ofEpochDay(todayEpochDay)
        val validLeadDays = leadDaysNotice.coerceIn(0, 14).toLong()
        val results = mutableListOf<CardDueReminderItem>()

        val creditCards = accounts.filter { !it.isArchived && it.groupType == AccountGroup.CREDIT_CARD }
        for (card in creditCards) {
            val stmtDay = (card.settlementDayOfMonth ?: CreditCardCycleCalculator.DEFAULT_STATEMENT_DAY).coerceIn(1, 31)
            val dueDay = (card.paymentDueDayOfMonth ?: CreditCardCycleCalculator.DEFAULT_PAYMENT_DUE_DAY).coerceIn(1, 31)

            val cycle = CreditCardCycleCalculator.computeCycleWindow(
                referenceDateEpochDay = todayEpochDay,
                settlementDayOfMonth = stmtDay,
                paymentDueDayOfMonth = dueDay,
            )

            // Calculate amount due now:
            // When creditCardStatementPayable is available, it reflects the closed cycle statement due on lastStatementDueDate.
            // If statementPayable is zero or non-positive, that statement has been settled; do not alarm the user for active cycle spend.
            val statementPayable = card.creditCardStatementPayable
            val priorOverdue = card.creditCardPriorOverduePayable
            val hasPriorOverdue = priorOverdue?.isPositive == true

            if (enforceOverdueRecencyWindow) {
                val priorDaysRemaining = ChronoUnit.DAYS.between(today, cycle.prevStatementDueDate)
                val usePriorWindow = hasPriorOverdue && priorDaysRemaining >= -3L
                val targetDueDate = if (usePriorWindow) cycle.prevStatementDueDate else cycle.lastStatementDueDate
                val effectiveStatement = when {
                    statementPayable != null -> if (statementPayable.isPositive) statementPayable else MinorMoney.ZERO
                    !hasPriorOverdue && card.currentBalance.isNegative -> card.currentBalance.abs()
                    else -> MinorMoney.ZERO
                }
                val amountDueNow = if (usePriorWindow) {
                    maxOf(effectiveStatement, priorOverdue!!)
                } else {
                    effectiveStatement
                }
                if (!amountDueNow.isPositive) continue

                val daysRemaining = ChronoUnit.DAYS.between(today, targetDueDate)
                if (daysRemaining in -3L..validLeadDays) {
                    results.add(
                        CardDueReminderItem(
                            accountId = card.id,
                            accountName = card.name,
                            currencyCode = card.currencyCode,
                            amountDue = amountDueNow,
                            dueDate = targetDueDate,
                            daysRemaining = daysRemaining,
                            isOverdue = daysRemaining < 0L,
                        ),
                    )
                }
                continue
            }

            val amountDueNow = when {
                statementPayable != null -> {
                    val effectiveStatement = if (statementPayable.isPositive) statementPayable else MinorMoney.ZERO
                    if (hasPriorOverdue) {
                        maxOf(effectiveStatement, priorOverdue!!)
                    } else {
                        effectiveStatement
                    }
                }

                hasPriorOverdue -> priorOverdue ?: MinorMoney.ZERO

                card.currentBalance.isNegative -> card.currentBalance.abs()

                else -> MinorMoney.ZERO
            }

            // If nothing is due, skip
            if (!amountDueNow.isPositive) continue

            // Evaluate target due date
            val targetDueDate = if (hasPriorOverdue && ChronoUnit.DAYS.between(today, cycle.lastStatementDueDate) >= 0) {
                cycle.prevStatementDueDate
            } else {
                cycle.lastStatementDueDate
            }
            val daysRemaining = ChronoUnit.DAYS.between(today, targetDueDate)

            val isDueSoon = daysRemaining in 0L..validLeadDays
            val isRecentlyOverdue = (daysRemaining < 0L && daysRemaining >= -3L) || hasPriorOverdue

            if (isDueSoon || isRecentlyOverdue) {
                results.add(
                    CardDueReminderItem(
                        accountId = card.id,
                        accountName = card.name,
                        currencyCode = card.currencyCode,
                        amountDue = amountDueNow,
                        dueDate = targetDueDate,
                        daysRemaining = daysRemaining,
                        isOverdue = daysRemaining < 0L || hasPriorOverdue,
                    ),
                )
            }
        }

        return results.sortedWith(
            compareBy<CardDueReminderItem> { !it.isOverdue }
                .thenBy { it.daysRemaining },
        )
    }

    fun evaluateBillReminders(
        recurringRules: List<RecurringCommitmentItem>,
        todayEpochDay: Long,
        leadDaysNotice: Int = 1,
    ): List<BillDueReminderItem> {
        val today = LocalDate.ofEpochDay(todayEpochDay)
        val validLeadDays = leadDaysNotice.coerceIn(0, 7).toLong()
        val results = mutableListOf<BillDueReminderItem>()

        val activeRules = recurringRules.filter { rule ->
            rule.isActive &&
                rule.amount.isPositive &&
                (
                    rule.type == TransactionType.EXPENSE ||
                        rule.type == TransactionType.TRANSFER ||
                        rule.type == TransactionType.CC_SETTLEMENT
                    ) &&
                (rule.endDateEpochDay == null || rule.nextOccurrenceEpochDay <= rule.endDateEpochDay)
        }
        for (rule in activeRules) {
            val dueDate = LocalDate.ofEpochDay(rule.nextOccurrenceEpochDay)
            val daysRemaining = ChronoUnit.DAYS.between(today, dueDate)

            if (daysRemaining in 0L..validLeadDays) {
                results.add(
                    BillDueReminderItem(
                        ruleId = rule.id,
                        title = rule.title,
                        payeeOrDescription = rule.payeeOrDescription.ifEmpty { rule.title },
                        currencyCode = rule.currencyCode,
                        amount = rule.amount,
                        dueDate = dueDate,
                        daysRemaining = daysRemaining,
                        accountName = rule.accountName,
                    ),
                )
            }
        }

        return results.sortedBy { it.daysRemaining }
    }
}
