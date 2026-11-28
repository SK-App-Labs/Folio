/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/domain/util/SafeToSpendCalculator.kt
package com.folioledger.app.domain.util

import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountShortfallAlert
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.RecurringCommitmentItem
import com.folioledger.app.domain.model.RepeatFrequency
import com.folioledger.app.domain.model.SafeToSpendState
import com.folioledger.app.domain.model.SafeToSpendStatus
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import java.time.LocalDate
import java.util.Locale

object SafeToSpendCalculator {

    fun projectRecurringOccurrencesInHorizon(
        startDay: Long,
        frequency: RepeatFrequency,
        todayEpochDay: Long,
        horizonEpochDay: Long,
        anchorDayOfMonth: Int? = null,
        endDateEpochDay: Long? = null,
    ): List<Long> = RecurringScheduleCalculator.projectOccurrencesInHorizon(
        startDay = startDay,
        frequency = frequency,
        todayEpochDay = todayEpochDay,
        horizonEpochDay = horizonEpochDay,
        anchorDayOfMonth = anchorDayOfMonth,
        endDateEpochDay = endDateEpochDay,
    )

    fun computeSafeToSpend(
        allAccounts: List<AccountSummary>,
        recurringRules: List<RecurringCommitmentItem>,
        futureTransactions: List<TransactionItem>,
        todayEpochDay: Long,
        periodStartEpochDay: Long,
        periodEndEpochDay: Long,
        primaryCurrencyCode: String,
        secondaryCurrencies: List<SecondaryCurrency> = emptyList(),
    ): SafeToSpendState {
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

        // 1. Total Liquid Cash & Bank Balances
        var totalLiquidMinor = 0L
        for (acc in allAccounts) {
            if (acc.isArchived || acc.currentBalance.amountMinor == 0L) continue
            val isLiquid = acc.groupType == AccountGroup.BANK_ACCOUNT ||
                acc.groupType == AccountGroup.CASH ||
                acc.groupType == AccountGroup.SAVINGS ||
                acc.groupType == AccountGroup.PREPAID_WALLET
            if (isLiquid) {
                totalLiquidMinor = MoneyFormatterAndCalculator.safeAdd(
                    totalLiquidMinor,
                    convertToPrimary(acc.currentBalance.amountMinor, acc.currencyCode),
                )
            }
        }

        // 2. Total Credit Card Debt (Billed + Unbilled)
        var totalCcDebtMinor = 0L
        for (acc in allAccounts) {
            if (acc.isArchived || acc.groupType != AccountGroup.CREDIT_CARD) continue
            // Only negative balance indicates outstanding debt owed (negative in Folio double-entry ledger).
            // A positive balance indicates an overpayment or store credit, which is not debt.
            if (acc.currentBalance.amountMinor < 0L) {
                val debtMinor = acc.currentBalance.abs().amountMinor
                totalCcDebtMinor = MoneyFormatterAndCalculator.safeAdd(
                    totalCcDebtMinor,
                    convertToPrimary(debtMinor, acc.currencyCode),
                )
            }
        }

        val accountById = allAccounts.associateBy { it.id }

        fun resolveEffectiveAccountId(accountId: Long): Long {
            val acc = accountById[accountId]
            return if (acc?.groupType == AccountGroup.DEBIT_CARD && acc.parentAccountId != null) {
                acc.parentAccountId
            } else {
                accountId
            }
        }

        fun isLiquidAccount(accountId: Long?): Boolean {
            if (accountId == null) return false
            val effectiveAcc = accountById[resolveEffectiveAccountId(accountId)] ?: return false
            return !effectiveAcc.isArchived && (
                effectiveAcc.groupType == AccountGroup.BANK_ACCOUNT ||
                    effectiveAcc.groupType == AccountGroup.CASH ||
                    effectiveAcc.groupType == AccountGroup.SAVINGS ||
                    effectiveAcc.groupType == AccountGroup.PREPAID_WALLET
                )
        }

        // 3. Upcoming 14-Day Commitments
        val horizonEpochDay = todayEpochDay + 14L
        var upcomingCommitmentsMinor = 0L

        // a. Recurring Rules
        for (rule in recurringRules) {
            if (!rule.isActive) continue
            val isOutflowFromLiquid = when (rule.type) {
                TransactionType.EXPENSE -> isLiquidAccount(rule.accountId)

                TransactionType.TRANSFER -> {
                    val targetAcc = rule.transferToAccountId?.let { accountById[resolveEffectiveAccountId(it)] }
                    // Only deduct if leaving the liquid pool AND not settling CC debt (CC debt is already deducted in full in Step 2)
                    isLiquidAccount(rule.accountId) &&
                        !isLiquidAccount(rule.transferToAccountId) &&
                        targetAcc?.groupType != AccountGroup.CREDIT_CARD
                }

                else -> false
            }
            if (!isOutflowFromLiquid) continue

            val occurrences = projectRecurringOccurrencesInHorizon(
                startDay = rule.nextOccurrenceEpochDay,
                frequency = rule.frequency,
                todayEpochDay = todayEpochDay,
                horizonEpochDay = horizonEpochDay,
                anchorDayOfMonth = if (rule.startDateEpochDay > 0L) LocalDate.ofEpochDay(rule.startDateEpochDay).dayOfMonth else null,
                endDateEpochDay = rule.endDateEpochDay,
            )

            val singleAmt = convertToPrimary(rule.amount.abs().amountMinor, rule.currencyCode)
            val ruleCommitmentMinor = occurrences.fold(0L) { acc, _ ->
                MoneyFormatterAndCalculator.safeAdd(acc, singleAmt)
            }
            upcomingCommitmentsMinor = MoneyFormatterAndCalculator.safeAdd(
                upcomingCommitmentsMinor,
                ruleCommitmentMinor,
            )
        }

        // b. Future Scheduled Transactions
        for (tx in futureTransactions) {
            if (tx.ledgerDateEpochDay in (todayEpochDay + 1L)..horizonEpochDay) {
                when (tx.type) {
                    TransactionType.EXPENSE -> {
                        if (isLiquidAccount(tx.accountId)) {
                            val combinedMinor = MoneyFormatterAndCalculator.safeAdd(
                                tx.amount.abs().amountMinor,
                                tx.transferFee.abs().amountMinor,
                            )
                            val amt = convertToPrimary(combinedMinor, tx.currencyCode)
                            upcomingCommitmentsMinor = MoneyFormatterAndCalculator.safeAdd(upcomingCommitmentsMinor, amt)
                        }
                    }

                    TransactionType.TRANSFER,
                    TransactionType.CC_SETTLEMENT,
                    -> {
                        if (isLiquidAccount(tx.accountId)) {
                            val targetAcc = tx.transferToAccountId?.let { accountById[resolveEffectiveAccountId(it)] }
                            val includePrincipal = tx.type == TransactionType.TRANSFER &&
                                !isLiquidAccount(tx.transferToAccountId) &&
                                targetAcc?.groupType != AccountGroup.CREDIT_CARD
                            val principalMinor = if (includePrincipal) tx.amount.abs().amountMinor else 0L
                            val feeMinor = tx.transferFee.abs().amountMinor
                            val combinedMinor = MoneyFormatterAndCalculator.safeAdd(principalMinor, feeMinor)
                            if (combinedMinor > 0L) {
                                val amt = convertToPrimary(combinedMinor, tx.currencyCode)
                                upcomingCommitmentsMinor = MoneyFormatterAndCalculator.safeAdd(upcomingCommitmentsMinor, amt)
                            }
                        }
                    }

                    else -> Unit
                }
            }
        }

        val netCashAfterDebtMinor = MoneyFormatterAndCalculator.safeSubtract(totalLiquidMinor, totalCcDebtMinor)
        val trueSafeToSpendMinor = MoneyFormatterAndCalculator.safeSubtract(
            netCashAfterDebtMinor,
            upcomingCommitmentsMinor,
        )
        val isPositive = trueSafeToSpendMinor >= 0L
        val isCurrentPeriod = todayEpochDay in periodStartEpochDay..periodEndEpochDay

        val status = when {
            trueSafeToSpendMinor > 0L -> SafeToSpendStatus.SAFE
            totalLiquidMinor <= 0L && totalCcDebtMinor == 0L && upcomingCommitmentsMinor == 0L -> SafeToSpendStatus.ZERO_RESERVES
            totalCcDebtMinor > 0L && upcomingCommitmentsMinor == 0L && totalCcDebtMinor > totalLiquidMinor -> SafeToSpendStatus.CARD_DEBT_EXCEEDS_CASH
            totalCcDebtMinor == 0L && upcomingCommitmentsMinor > 0L && upcomingCommitmentsMinor > totalLiquidMinor -> SafeToSpendStatus.BILLS_EXCEED_CASH
            totalLiquidMinor >= totalCcDebtMinor && upcomingCommitmentsMinor > netCashAfterDebtMinor -> SafeToSpendStatus.BILLS_EXCEED_REMAINING_CASH
            totalCcDebtMinor > 0L && totalCcDebtMinor > totalLiquidMinor && upcomingCommitmentsMinor > 0L -> SafeToSpendStatus.COMBINED_OBLIGATIONS_EXCEED_CASH
            trueSafeToSpendMinor == 0L -> SafeToSpendStatus.SAFE
            else -> if (totalCcDebtMinor > 0L) SafeToSpendStatus.CARD_DEBT_EXCEEDS_CASH else SafeToSpendStatus.BILLS_EXCEED_CASH
        }

        return SafeToSpendState(
            totalLiquidBankAndCash = MinorMoney(totalLiquidMinor),
            totalCreditCardDebt = MinorMoney(totalCcDebtMinor),
            upcoming14DayCommitments = MinorMoney(upcomingCommitmentsMinor),
            trueSafeToSpend = MinorMoney(trueSafeToSpendMinor),
            isPositive = isPositive,
            isCurrentPeriod = isCurrentPeriod,
            status = status,
        )
    }

    fun computeShortfallAlerts(
        allAccounts: List<AccountSummary>,
        recurringRules: List<RecurringCommitmentItem>,
        futureTransactions: List<TransactionItem>,
        todayEpochDay: Long,
        primaryCurrencyCode: String,
        secondaryCurrencies: List<SecondaryCurrency> = emptyList(),
    ): List<AccountShortfallAlert> {
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

        val horizonEpochDay = todayEpochDay + 14L
        val accountById = allAccounts.associateBy { it.id }

        fun resolveEffectiveAccountId(accountId: Long): Long {
            val acc = accountById[accountId]
            return if (acc?.groupType == AccountGroup.DEBIT_CARD && acc.parentAccountId != null) {
                acc.parentAccountId
            } else {
                accountId
            }
        }

        fun isLiquidAccount(accountId: Long?): Boolean {
            if (accountId == null) return false
            val effectiveAcc = accountById[resolveEffectiveAccountId(accountId)] ?: return false
            return !effectiveAcc.isArchived && (
                effectiveAcc.groupType == AccountGroup.BANK_ACCOUNT ||
                    effectiveAcc.groupType == AccountGroup.CASH ||
                    effectiveAcc.groupType == AccountGroup.SAVINGS ||
                    effectiveAcc.groupType == AccountGroup.PREPAID_WALLET
                )
        }

        // Account ID -> Map<EpochDay, DayDeltas(inflowsMinor, outflowsMinor)>
        class DayDeltas(var inflows: Long = 0L, var outflows: Long = 0L)
        val dailyDeltasByAccount = mutableMapOf<Long, MutableMap<Long, DayDeltas>>()
        val totalDebitsByAccount = mutableMapOf<Long, Long>()

        fun addInflow(effectiveAccId: Long, epochDay: Long, amountMinor: Long) {
            if (amountMinor <= 0L) return
            val accountMap = dailyDeltasByAccount.getOrPut(effectiveAccId) { mutableMapOf() }
            val day = accountMap.getOrPut(epochDay) { DayDeltas() }
            day.inflows = MoneyFormatterAndCalculator.safeAdd(day.inflows, amountMinor)
        }

        fun addOutflow(effectiveAccId: Long, epochDay: Long, amountMinor: Long) {
            if (amountMinor <= 0L) return
            val accountMap = dailyDeltasByAccount.getOrPut(effectiveAccId) { mutableMapOf() }
            val day = accountMap.getOrPut(epochDay) { DayDeltas() }
            day.outflows = MoneyFormatterAndCalculator.safeAdd(day.outflows, amountMinor)
            totalDebitsByAccount[effectiveAccId] = MoneyFormatterAndCalculator.safeAdd(
                totalDebitsByAccount[effectiveAccId] ?: 0L,
                amountMinor,
            )
        }

        // 1. Recurring Rules
        for (rule in recurringRules) {
            if (!rule.isActive) continue
            val occurrences = projectRecurringOccurrencesInHorizon(
                startDay = rule.nextOccurrenceEpochDay,
                frequency = rule.frequency,
                todayEpochDay = todayEpochDay,
                horizonEpochDay = horizonEpochDay,
                anchorDayOfMonth = if (rule.startDateEpochDay > 0L) LocalDate.ofEpochDay(rule.startDateEpochDay).dayOfMonth else null,
                endDateEpochDay = rule.endDateEpochDay,
            )
            if (occurrences.isEmpty()) continue

            val singleAmt = convertToPrimary(rule.amount.abs().amountMinor, rule.currencyCode)
            if (singleAmt <= 0L) continue

            when (rule.type) {
                TransactionType.INCOME -> {
                    if (isLiquidAccount(rule.accountId)) {
                        val sourceId = resolveEffectiveAccountId(rule.accountId)
                        for (occ in occurrences) {
                            addInflow(sourceId, occ, singleAmt)
                        }
                    }
                }

                TransactionType.EXPENSE -> {
                    if (isLiquidAccount(rule.accountId)) {
                        val sourceId = resolveEffectiveAccountId(rule.accountId)
                        for (occ in occurrences) {
                            addOutflow(sourceId, occ, singleAmt)
                        }
                    }
                }

                TransactionType.TRANSFER,
                TransactionType.CC_SETTLEMENT,
                -> {
                    val isSourceLiquid = isLiquidAccount(rule.accountId)
                    val sourceId = if (isSourceLiquid) resolveEffectiveAccountId(rule.accountId) else null
                    val targetId = rule.transferToAccountId?.takeIf { isLiquidAccount(it) }?.let { resolveEffectiveAccountId(it) }

                    if (sourceId != null && targetId != null && sourceId == targetId) {
                        // Intra-account transfer (e.g. debit card to parent account) -> zero net balance change
                    } else {
                        if (sourceId != null) {
                            for (occ in occurrences) {
                                addOutflow(sourceId, occ, singleAmt)
                            }
                        }
                        if (targetId != null) {
                            for (occ in occurrences) {
                                addInflow(targetId, occ, singleAmt)
                            }
                        }
                    }
                }

                else -> Unit
            }
        }

        // 2. Future Scheduled Transactions
        for (tx in futureTransactions) {
            val txDay = tx.ledgerDateEpochDay
            if (txDay !in (todayEpochDay + 1L)..horizonEpochDay) continue

            val feeMinor = convertToPrimary(tx.transferFee.abs().amountMinor, tx.currencyCode)
            val amtMinor = convertToPrimary(tx.amount.abs().amountMinor, tx.currencyCode)

            when (tx.type) {
                TransactionType.INCOME,
                TransactionType.REFUND,
                -> {
                    if (isLiquidAccount(tx.accountId)) {
                        val sourceId = resolveEffectiveAccountId(tx.accountId)
                        addInflow(sourceId, txDay, amtMinor)
                    }
                }

                TransactionType.EXPENSE -> {
                    if (isLiquidAccount(tx.accountId)) {
                        val sourceId = resolveEffectiveAccountId(tx.accountId)
                        val totalCharge = MoneyFormatterAndCalculator.safeAdd(amtMinor, feeMinor)
                        addOutflow(sourceId, txDay, totalCharge)
                    }
                }

                TransactionType.TRANSFER,
                TransactionType.CC_SETTLEMENT,
                -> {
                    val isSourceLiquid = isLiquidAccount(tx.accountId)
                    val sourceId = if (isSourceLiquid) resolveEffectiveAccountId(tx.accountId) else null
                    val targetId = tx.transferToAccountId?.takeIf { isLiquidAccount(it) }?.let { resolveEffectiveAccountId(it) }

                    if (sourceId != null && targetId != null && sourceId == targetId) {
                        // Intra-account transfer
                        if (feeMinor > 0L) {
                            addOutflow(sourceId, txDay, feeMinor)
                        }
                    } else {
                        if (sourceId != null) {
                            val totalOutflow = MoneyFormatterAndCalculator.safeAdd(amtMinor, feeMinor)
                            addOutflow(sourceId, txDay, totalOutflow)
                        }
                        if (targetId != null) {
                            addInflow(targetId, txDay, amtMinor)
                        }
                    }
                }

                else -> Unit
            }
        }

        // 3. Match against live liquid account balances
        val alerts = mutableListOf<AccountShortfallAlert>()
        for (acc in allAccounts) {
            if (acc.isArchived) continue
            if (!isLiquidAccount(acc.id)) continue

            val scheduledDebitsMinor = totalDebitsByAccount[acc.id] ?: 0L
            if (scheduledDebitsMinor <= 0L) continue

            val liveBalanceMinor = convertToPrimary(acc.currentBalance.amountMinor, acc.currencyCode)
            val dayMap = dailyDeltasByAccount[acc.id] ?: emptyMap()

            val daysToSimulate = if (liveBalanceMinor < 0L && todayEpochDay !in dayMap) {
                (dayMap.keys + todayEpochDay).sorted()
            } else {
                dayMap.keys.sorted()
            }
            var runningBalance = liveBalanceMinor
            var minRunningBalance = Long.MAX_VALUE
            var firstShortfallEpochDay: Long? = null

            for (day in daysToSimulate) {
                val deltas = dayMap[day]
                if (deltas != null) {
                    // Apply same-day inflows before outflows to avoid false intraday drops
                    runningBalance = MoneyFormatterAndCalculator.safeAdd(runningBalance, deltas.inflows)
                    runningBalance = MoneyFormatterAndCalculator.safeSubtract(runningBalance, deltas.outflows)
                }
                if (runningBalance < 0L) {
                    if (firstShortfallEpochDay == null) {
                        firstShortfallEpochDay = day
                    }
                    if (runningBalance < minRunningBalance) {
                        minRunningBalance = runningBalance
                    }
                }
            }

            if (firstShortfallEpochDay != null && minRunningBalance < 0L) {
                val shortfallMinor = MoneyFormatterAndCalculator.safeAbs(minRunningBalance)
                val earliestDay = firstShortfallEpochDay
                val daysUntil = maxOf(0L, earliestDay - todayEpochDay)

                alerts.add(
                    AccountShortfallAlert(
                        accountId = acc.id,
                        accountName = acc.name,
                        currentBalance = MinorMoney(liveBalanceMinor),
                        upcomingDebits = MinorMoney(scheduledDebitsMinor),
                        shortfallAmount = MinorMoney(shortfallMinor),
                        daysUntilShortfall = daysUntil,
                    ),
                )
            }
        }

        return alerts.sortedBy { it.daysUntilShortfall }
    }
}
