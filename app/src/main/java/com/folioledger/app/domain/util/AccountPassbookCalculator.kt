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

import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.usecase.AccountingRulesEngine
import com.folioledger.app.domain.model.AccountPassbookDayGroup
import com.folioledger.app.domain.model.AccountPassbookEntryUiModel
import com.folioledger.app.domain.model.AccountPassbookUiModel
import com.folioledger.app.domain.model.PassbookPeriodRange
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

object PassbookPeriodCalculator {

    private val shortMonthYearFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)
    private val shortDayMonthFormatter = DateTimeFormatter.ofPattern("dd MMM", Locale.US)
    private val fullDayMonthYearFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)
    val fullDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy (EEE)", Locale.US)
    val monthYearFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)

    fun resolveClampedDate(yearMonth: YearMonth, desiredDayOfMonth: Int): LocalDate {
        val safeDay = desiredDayOfMonth.coerceIn(1, 31)
        val clampedDay = minOf(safeDay, yearMonth.lengthOfMonth())
        return yearMonth.atDay(clampedDay)
    }

    fun computeFinancialMonthRange(
        referenceDateEpochDay: Long,
        monthStartDay: Int,
        isCreditCard: Boolean = false,
        todayEpochDay: Long = referenceDateEpochDay
    ): PassbookPeriodRange {
        val safeStartDay = monthStartDay.coerceIn(1, 31)
        val refDate = LocalDate.ofEpochDay(referenceDateEpochDay)
        val refYearMonth = YearMonth.from(refDate)

        val startDate: LocalDate
        val endDate: LocalDate

        if (safeStartDay == 1) {
            startDate = refYearMonth.atDay(1)
            endDate = refYearMonth.atEndOfMonth()
        } else {
            val startInRefMonth = resolveClampedDate(refYearMonth, safeStartDay)
            if (!refDate.isBefore(startInRefMonth)) {
                startDate = startInRefMonth
                val nextMonthStart = resolveClampedDate(refYearMonth.plusMonths(1), safeStartDay)
                endDate = nextMonthStart.minusDays(1)
            } else {
                startDate = resolveClampedDate(refYearMonth.minusMonths(1), safeStartDay)
                endDate = startInRefMonth.minusDays(1)
            }
        }

        val periodTitle = if (isCreditCard) {
            val today = LocalDate.ofEpochDay(todayEpochDay)
            if (!endDate.isBefore(today)) {
                "${endDate.format(shortMonthYearFormatter)} Cycle (Active)"
            } else {
                "${endDate.format(shortMonthYearFormatter)} Statement"
            }
        } else {
            val anchorDate = if (safeStartDay > 15) endDate else startDate
            anchorDate.format(shortMonthYearFormatter)
        }
        val periodSubtitle = "${startDate.format(shortDayMonthFormatter)} – ${endDate.format(fullDayMonthYearFormatter)}"

        return PassbookPeriodRange(
            startEpochDay = startDate.toEpochDay(),
            endEpochDay = endDate.toEpochDay(),
            periodTitle = periodTitle,
            periodSubtitle = periodSubtitle
        )
    }

    fun stepFinancialMonth(
        currentReferenceEpochDay: Long,
        monthStartDay: Int,
        deltaMonths: Int,
        isCreditCard: Boolean = false
    ): Long {
        if (deltaMonths == 0) return currentReferenceEpochDay
        val safeStartDay = monthStartDay.coerceIn(1, 31)
        val currentRange = computeFinancialMonthRange(currentReferenceEpochDay, safeStartDay, isCreditCard)
        val startLocalDate = LocalDate.ofEpochDay(currentRange.startEpochDay)
        val targetYearMonth = YearMonth.from(startLocalDate).plusMonths(deltaMonths.toLong())
        return resolveClampedDate(targetYearMonth, safeStartDay).toEpochDay()
    }

    fun computeCreditCardStatementRange(
        referenceDateEpochDay: Long,
        settlementDayOfMonth: Int,
        monthOffset: Int,
        todayEpochDay: Long = referenceDateEpochDay
    ): PassbookPeriodRange {
        val safeStatementDay = settlementDayOfMonth.coerceIn(1, 31)
        val baseCycle = CreditCardCycleCalculator.computeCycleWindow(
            referenceDateEpochDay = referenceDateEpochDay,
            settlementDayOfMonth = safeStatementDay,
            paymentDueDayOfMonth = 1
        )
        val targetCloseYearMonth = YearMonth.from(baseCycle.activeCycleEnd).plusMonths(monthOffset.toLong())
        val endDate = CreditCardCycleCalculator.resolveClampedDate(targetCloseYearMonth, safeStatementDay)
        val prevCloseDate = CreditCardCycleCalculator.resolveClampedDate(
            targetCloseYearMonth.minusMonths(1),
            safeStatementDay
        )
        val startDate = prevCloseDate.plusDays(1)

        val today = LocalDate.ofEpochDay(todayEpochDay)
        val periodTitle = when {
            startDate.isAfter(today) -> "${endDate.format(shortMonthYearFormatter)} Cycle (Upcoming)"
            !endDate.isBefore(today) -> "${endDate.format(shortMonthYearFormatter)} Cycle (Active)"
            else -> "${endDate.format(shortMonthYearFormatter)} Statement"
        }
        val periodSubtitle = "${startDate.format(shortDayMonthFormatter)} – ${endDate.format(fullDayMonthYearFormatter)}"

        return PassbookPeriodRange(
            startEpochDay = startDate.toEpochDay(),
            endEpochDay = endDate.toEpochDay(),
            periodTitle = periodTitle,
            periodSubtitle = periodSubtitle
        )
    }
}

private data class ChronologicalPassbookItem(
    val transaction: TransactionItem,
    val deltaMinor: Long,
    val runningBalanceAfterMinor: Long,
    val viaLinkedDebitCardName: String?
)

object AccountPassbookCalculator {

    fun computeSignedDeltaMinorForAccount(
        tx: TransactionItem,
        effectiveAccountIds: Set<Long>,
        isSourceLiabilityAccount: Boolean = false
    ): Long = AccountingRulesEngine.computeSignedDeltaMinorForAccount(
        tx = tx,
        effectiveAccountIds = effectiveAccountIds,
        isSourceLiabilityAccount = isSourceLiabilityAccount
    )

    fun buildAccountPassbookUiModel(
        inspectedAccount: AccountSummary,
        allAccounts: List<AccountSummary>,
        allTransactions: List<TransactionItem>,
        monthStartDay: Int,
        referenceDateEpochDay: Long,
        passbookMonthOffset: Int
    ): AccountPassbookUiModel {
        val isCreditCard = inspectedAccount.groupType == AccountGroup.CREDIT_CARD
        val periodRange = if (isCreditCard) {
            PassbookPeriodCalculator.computeCreditCardStatementRange(
                referenceDateEpochDay = referenceDateEpochDay,
                settlementDayOfMonth = inspectedAccount.settlementDayOfMonth ?: CreditCardCycleCalculator.DEFAULT_STATEMENT_DAY,
                monthOffset = passbookMonthOffset,
                todayEpochDay = referenceDateEpochDay
            )
        } else {
            val safeMonthStartDay = monthStartDay.coerceIn(1, 31)
            val targetRefEpochDay = PassbookPeriodCalculator.stepFinancialMonth(
                currentReferenceEpochDay = referenceDateEpochDay,
                monthStartDay = safeMonthStartDay,
                deltaMonths = passbookMonthOffset,
                isCreditCard = false
            )
            PassbookPeriodCalculator.computeFinancialMonthRange(
                referenceDateEpochDay = targetRefEpochDay,
                monthStartDay = safeMonthStartDay,
                isCreditCard = false
            )
        }

        val parentBankAccount = if (inspectedAccount.groupType == AccountGroup.DEBIT_CARD) {
            inspectedAccount.parentAccountId?.let { parentId ->
                allAccounts.firstOrNull { it.id == parentId }
            }
        } else {
            null
        }

        val childDebitCards = if (inspectedAccount.groupType != AccountGroup.DEBIT_CARD) {
            allAccounts.filter { candidate ->
                candidate.groupType == AccountGroup.DEBIT_CARD &&
                        candidate.parentAccountId == inspectedAccount.id
            }
        } else {
            emptyList()
        }
        val childDebitCardById = childDebitCards.associateBy { it.id }

        val effectiveAccountIds: Set<Long> = buildSet {
            add(inspectedAccount.id)
            if (inspectedAccount.groupType == AccountGroup.DEBIT_CARD) {
                val parentId = inspectedAccount.parentAccountId
                if (parentId != null) {
                    add(parentId)
                    // Include all sibling debit cards sharing the same parent bank account
                    // so initialOpeningBalanceMinor reconciles with parentBankAccount.currentBalance
                    allAccounts.forEach { candidate ->
                        if (candidate.groupType == AccountGroup.DEBIT_CARD && candidate.parentAccountId == parentId) {
                            add(candidate.id)
                        }
                    }
                }
            } else {
                childDebitCards.forEach { add(it.id) }
            }
        }

        val anchorGroupType = parentBankAccount?.groupType ?: inspectedAccount.groupType
        val familyDebitCardById = allAccounts
            .filter { it.groupType == AccountGroup.DEBIT_CARD && it.id in effectiveAccountIds }
            .associateBy { it.id }

        val seenTxIds = HashSet<Long>()
        val matchingChronological = allTransactions
            .asSequence()
            .filter { tx ->
                when (tx.type) {
                    TransactionType.EXPENSE,
                    TransactionType.INCOME,
                    TransactionType.REFUND,
                    TransactionType.OPENING_BALANCE,
                    TransactionType.RECONCILIATION -> tx.accountId in effectiveAccountIds
                    TransactionType.TRANSFER,
                    TransactionType.CC_SETTLEMENT -> tx.accountId in effectiveAccountIds ||
                            (tx.transferToAccountId != null && tx.transferToAccountId in effectiveAccountIds)
                }
            }
            .filter { tx -> tx.id == 0L || seenTxIds.add(tx.id) }
            .sortedWith(
                compareBy<TransactionItem> { it.ledgerDateEpochDay }
                    .thenBy { if (it.type == TransactionType.OPENING_BALANCE) 0 else 1 }
                    .thenBy { it.timestampEpochMillis }
                    .thenBy { it.id }
            )
            .toList()

        val deltasWithTx: List<Pair<TransactionItem, Long>> = matchingChronological.map { tx ->
            val deltaMinor = computeSignedDeltaMinorForAccount(
                tx = tx,
                effectiveAccountIds = effectiveAccountIds,
                isSourceLiabilityAccount = anchorGroupType.isLiabilityByDefault
            )
            tx to deltaMinor
        }

        val todayEpochDay = referenceDateEpochDay
        fun safeAdd(a: Long, b: Long): Long = MoneyFormatterAndCalculator.safeAdd(a, b)
        fun safeSubtract(a: Long, b: Long): Long = MoneyFormatterAndCalculator.safeSubtract(a, b)
        fun safeAbs(value: Long): Long = MoneyFormatterAndCalculator.safeAbs(value)

        val netDeltaUpToToday: Long = deltasWithTx
            .filter { (tx, _) -> tx.ledgerDateEpochDay <= todayEpochDay }
            .fold(0L) { sum, (_, delta) -> safeAdd(sum, delta) }

        val anchorLiveBalanceMinor: Long = if (inspectedAccount.groupType == AccountGroup.DEBIT_CARD) {
            parentBankAccount?.currentBalance?.amountMinor ?: 0L
        } else {
            inspectedAccount.currentBalance.amountMinor
        }

        val initialOpeningBalanceMinor: Long = safeSubtract(anchorLiveBalanceMinor, netDeltaUpToToday)

        var runningBalanceMinor = initialOpeningBalanceMinor
        var periodOpeningBalanceMinor = initialOpeningBalanceMinor
        var periodInflowsMinor = 0L
        var periodOutflowsMinor = 0L

        val periodChronologicalItems = mutableListOf<ChronologicalPassbookItem>()

        for ((tx, deltaMinor) in deltasWithTx) {
            if (tx.ledgerDateEpochDay < periodRange.startEpochDay) {
                runningBalanceMinor = safeAdd(runningBalanceMinor, deltaMinor)
                periodOpeningBalanceMinor = runningBalanceMinor
            } else if (tx.ledgerDateEpochDay in periodRange.startEpochDay..periodRange.endEpochDay) {
                runningBalanceMinor = safeAdd(runningBalanceMinor, deltaMinor)
                if (deltaMinor > 0L) {
                    periodInflowsMinor = safeAdd(periodInflowsMinor, deltaMinor)
                } else if (deltaMinor < 0L) {
                    val absDelta = if (deltaMinor == Long.MIN_VALUE) Long.MAX_VALUE else -deltaMinor
                    periodOutflowsMinor = safeAdd(periodOutflowsMinor, absDelta)
                }
                if (deltaMinor != 0L) {
                    val viaCardName = familyDebitCardById[tx.accountId]?.name
                    periodChronologicalItems.add(
                        ChronologicalPassbookItem(
                            transaction = tx,
                            deltaMinor = deltaMinor,
                            runningBalanceAfterMinor = runningBalanceMinor,
                            viaLinkedDebitCardName = viaCardName
                        )
                    )
                }
            } else {
                runningBalanceMinor = safeAdd(runningBalanceMinor, deltaMinor)
            }
        }

        val periodClosingBalanceMinor = safeSubtract(safeAdd(periodOpeningBalanceMinor, periodInflowsMinor), periodOutflowsMinor)

        val periodEntriesNewestFirst = periodChronologicalItems
            .asReversed()
            .map { item ->
                AccountPassbookEntryUiModel(
                    transaction = item.transaction,
                    signedDelta = MinorMoney(item.deltaMinor),
                    isInflow = item.deltaMinor >= 0L,
                    runningBalanceAfter = MinorMoney(item.runningBalanceAfterMinor),
                    viaLinkedDebitCardName = item.viaLinkedDebitCardName
                )
            }

        val fullDateFormatter = PassbookPeriodCalculator.fullDateFormatter
        val monthYearFormatter = PassbookPeriodCalculator.monthYearFormatter

        val dayGroups = periodEntriesNewestFirst
            .groupBy { it.transaction.ledgerDateEpochDay }
            .entries
            .sortedByDescending { it.key }
            .map { (epochDay, dayEntriesNewestFirst) ->
                val date = LocalDate.ofEpochDay(epochDay)
                var dayInflow = 0L
                var dayOutflow = 0L
                dayEntriesNewestFirst.forEach { entry ->
                    val d = entry.signedDelta.amountMinor
                    if (d > 0L) {
                        dayInflow = safeAdd(dayInflow, d)
                    } else if (d < 0L) {
                        dayOutflow = safeAdd(dayOutflow, safeAbs(d))
                    }
                }
                val dayEndBalance = dayEntriesNewestFirst.first().runningBalanceAfter
                val headerTitle = when (epochDay) {
                    todayEpochDay -> "Today, ${date.format(fullDateFormatter)}"
                    todayEpochDay - 1L -> "Yesterday, ${date.format(fullDateFormatter)}"
                    else -> date.format(fullDateFormatter)
                }

                AccountPassbookDayGroup(
                    epochDay = epochDay,
                    dateHeaderTitle = headerTitle,
                    dayOfMonthText = String.format(Locale.US, "%02d", date.dayOfMonth),
                    dayOfWeekText = date.dayOfWeek
                        .getDisplayName(JavaTextStyle.SHORT, Locale.US)
                        .uppercase(Locale.US),
                    monthYearText = date.format(monthYearFormatter),
                    isToday = epochDay == todayEpochDay,
                    dayInflowTotal = MinorMoney(dayInflow),
                    dayOutflowTotal = MinorMoney(dayOutflow),
                    dayEndRunningBalance = dayEndBalance,
                    entries = dayEntriesNewestFirst
                )
            }

        return AccountPassbookUiModel(
            account = inspectedAccount,
            parentBankAccount = parentBankAccount,
            linkedDebitCards = childDebitCards,
            monthOffset = passbookMonthOffset,
            startEpochDay = periodRange.startEpochDay,
            endEpochDay = periodRange.endEpochDay,
            periodTitle = periodRange.periodTitle,
            periodSubtitle = periodRange.periodSubtitle,
            currentLiveBalance = MinorMoney(anchorLiveBalanceMinor),
            periodOpeningBalance = MinorMoney(periodOpeningBalanceMinor),
            periodInflows = MinorMoney(periodInflowsMinor),
            periodOutflows = MinorMoney(periodOutflowsMinor),
            periodNetFlow = MinorMoney(safeSubtract(periodInflowsMinor, periodOutflowsMinor)),
            periodClosingBalance = MinorMoney(periodClosingBalanceMinor),
            totalPeriodTransactionCount = periodEntriesNewestFirst.size,
            dayGroups = dayGroups,
            entries = periodEntriesNewestFirst,
            investedPrincipal = inspectedAccount.investedPrincipal,
            unrealizedPnl = inspectedAccount.unrealizedPnl,
            returnPercentage = inspectedAccount.returnPercentage
        )
    }
}
