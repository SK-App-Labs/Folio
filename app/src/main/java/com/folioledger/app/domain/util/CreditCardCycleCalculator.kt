/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/domain/util/CreditCardCycleCalculator.kt
package com.folioledger.app.domain.util

import androidx.compose.runtime.Immutable
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Immutable
data class CreditCardCycleWindow(
    val statementDayOfMonth: Int,
    val paymentDueDayOfMonth: Int,
    val lastStatementStart: LocalDate,
    val lastStatementEnd: LocalDate,
    val lastStatementDueDate: LocalDate,
    val activeCycleStart: LocalDate,
    val activeCycleEnd: LocalDate,
    val activeCycleNextDueDate: LocalDate,
    val prevStatementEnd: LocalDate = lastStatementStart.minusDays(1),
    val prevStatementDueDate: LocalDate
) {
    val lastStatementRangeShortText: String
        get() = "${lastStatementStart.format(shortFormatter)} – ${lastStatementEnd.format(shortFormatter)}"

    val lastStatementRangeFullText: String
        get() = "${lastStatementStart.format(fullFormatter)} – ${lastStatementEnd.format(fullFormatter)}"

    val lastStatementDueShortText: String
        get() = lastStatementDueDate.format(shortFormatter)

    val lastStatementDueFullText: String
        get() = lastStatementDueDate.format(fullFormatter)

    val activeCycleRangeShortText: String
        get() = "${activeCycleStart.format(shortFormatter)} – ${activeCycleEnd.format(shortFormatter)}"

    val activeCycleRangeFullText: String
        get() = "${activeCycleStart.format(fullFormatter)} – ${activeCycleEnd.format(fullFormatter)}"

    @Suppress("unused")
    val activeCycleNextDueFullText: String
        get() = activeCycleNextDueDate.format(fullFormatter)

    companion object {
        private val shortFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM", Locale.US)
        private val fullFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)
    }
}

object CreditCardCycleCalculator {

    const val DEFAULT_STATEMENT_DAY: Int = 18
    const val DEFAULT_PAYMENT_DUE_DAY: Int = 5

    fun resolveClampedDate(yearMonth: YearMonth, desiredDayOfMonth: Int): LocalDate {
        val safeDay = desiredDayOfMonth.coerceIn(1, 31)
        val clampedDay = minOf(safeDay, yearMonth.lengthOfMonth())
        return yearMonth.atDay(clampedDay)
    }

    fun resolveLastStatementCloseDate(
        referenceDateEpochDay: Long,
        settlementDayOfMonth: Int
    ): LocalDate {
        val safeStatementDay = settlementDayOfMonth.coerceIn(1, 31)
        val refDate = LocalDate.ofEpochDay(referenceDateEpochDay)
        val refYearMonth = YearMonth.from(refDate)
        val statementDateInRefMonth = resolveClampedDate(refYearMonth, safeStatementDay)
        return if (refDate.isAfter(statementDateInRefMonth)) {
            statementDateInRefMonth
        } else {
            resolveClampedDate(refYearMonth.minusMonths(1), safeStatementDay)
        }
    }

    fun computeCycleWindow(
        referenceDateEpochDay: Long,
        settlementDayOfMonth: Int,
        paymentDueDayOfMonth: Int
    ): CreditCardCycleWindow {
        val safeStatementDay = settlementDayOfMonth.coerceIn(1, 31)
        val safeDueDay = paymentDueDayOfMonth.coerceIn(1, 31)
        val lastStatementEnd = resolveLastStatementCloseDate(
            referenceDateEpochDay = referenceDateEpochDay,
            settlementDayOfMonth = safeStatementDay
        )

        val prevStatementEnd = resolveClampedDate(
            YearMonth.from(lastStatementEnd).minusMonths(1),
            safeStatementDay
        )
        val lastStatementStart = prevStatementEnd.plusDays(1)

        val activeCycleStart = lastStatementEnd.plusDays(1)
        val activeCycleEnd = resolveClampedDate(
            YearMonth.from(lastStatementEnd).plusMonths(1),
            safeStatementDay
        )

        val lastStatementDueDate = computeDueDateAfterStatementClose(
            statementCloseDate = lastStatementEnd,
            statementDayOfMonth = safeStatementDay,
            dueDayOfMonth = safeDueDay
        )
        val prevStatementDueDate = computeDueDateAfterStatementClose(
            statementCloseDate = prevStatementEnd,
            statementDayOfMonth = safeStatementDay,
            dueDayOfMonth = safeDueDay
        )
        val activeCycleNextDueDate = computeDueDateAfterStatementClose(
            statementCloseDate = activeCycleEnd,
            statementDayOfMonth = safeStatementDay,
            dueDayOfMonth = safeDueDay
        )

        return CreditCardCycleWindow(
            statementDayOfMonth = safeStatementDay,
            paymentDueDayOfMonth = safeDueDay,
            lastStatementStart = lastStatementStart,
            lastStatementEnd = lastStatementEnd,
            lastStatementDueDate = lastStatementDueDate,
            activeCycleStart = activeCycleStart,
            activeCycleEnd = activeCycleEnd,
            activeCycleNextDueDate = activeCycleNextDueDate,
            prevStatementEnd = prevStatementEnd,
            prevStatementDueDate = prevStatementDueDate
        )
    }

    fun computeDueDateAfterStatementClose(
        statementCloseDate: LocalDate,
        statementDayOfMonth: Int,
        dueDayOfMonth: Int
    ): LocalDate {
        val safeStatementDay = statementDayOfMonth.coerceIn(1, 31)
        val safeDueDay = dueDayOfMonth.coerceIn(1, 31)
        val closeYearMonth = YearMonth.from(statementCloseDate)
        // Standard credit card grace periods are at least 10-15 days.
        // A due date in the same calendar month requires dueDayOfMonth > statementDayOfMonth
        // with at least a 7-day grace window; otherwise the payment due date falls in the following calendar month.
        val canBeSameMonth = (safeDueDay - safeStatementDay) >= 7
        val initialCandidate = if (canBeSameMonth) {
            val candidate = resolveClampedDate(closeYearMonth, safeDueDay)
            if (candidate.isAfter(statementCloseDate)) {
                candidate
            } else {
                resolveClampedDate(closeYearMonth.plusMonths(1), safeDueDay)
            }
        } else {
            resolveClampedDate(closeYearMonth.plusMonths(1), safeDueDay)
        }

        return if (java.time.temporal.ChronoUnit.DAYS.between(statementCloseDate, initialCandidate) >= 7L) {
            initialCandidate
        } else {
            val candidateNextMonth = YearMonth.from(initialCandidate).plusMonths(1)
            resolveClampedDate(candidateNextMonth, safeDueDay)
        }
    }
}
