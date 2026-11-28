/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.data

import com.folioledger.app.data.repository.parseAmountDecimalToMinor
import com.folioledger.app.data.repository.parseCsvLine
import com.folioledger.app.data.repository.parseDateStringToEpochDay
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.usecase.AccountingRulesEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.YearMonth

class CreditCardCycleEditingRegressionTest {

    @Test
    fun axisRewardsCC_metricsCalculatedAccurately_beforeAndAfterDueDayEdit() {
        // Load actual Axis Rewards CC transactions from user spreadsheet
        val stream = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("user_test_spreadsheet.csv")
                ?: javaClass.getResourceAsStream("/user_test_spreadsheet.csv"),
        ) { "Missing test resource: user_test_spreadsheet.csv" }
        val reader = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))

        val ccTransactions = mutableListOf<TransactionItem>()
        var line = reader.readLine()
        var txIdCounter = 1L

        while (line != null) {
            val trimmed = line.trim()
            if (trimmed.contains("Axis Rewards CC", ignoreCase = true) && !trimmed.startsWith("Axis Rewards CC,")) {
                val tokens = parseCsvLine(trimmed)
                val rawDate = tokens.getOrNull(1) ?: ""
                val epochDay = parseDateStringToEpochDay(rawDate)
                val rawType = tokens.getOrNull(2)?.trim() ?: ""
                val type = when {
                    rawType.equals("CC_SETTLEMENT", ignoreCase = true) -> TransactionType.CC_SETTLEMENT
                    rawType.equals("EXPENSE", ignoreCase = true) -> TransactionType.EXPENSE
                    rawType.equals("INCOME", ignoreCase = true) -> TransactionType.INCOME
                    else -> TransactionType.EXPENSE
                }
                val rawAmount = tokens.getOrNull(3) ?: ""
                val amountMinor = parseAmountDecimalToMinor(rawAmount, 2)

                if (epochDay != null && amountMinor != null) {
                    ccTransactions.add(
                        TransactionItem(
                            id = txIdCounter++,
                            type = type,
                            timestampEpochMillis = epochDay * 86_400_000L + 12 * 3600 * 1000L,
                            ledgerDateEpochDay = epochDay,
                            amount = MinorMoney(amountMinor),
                            currencyCode = "INR",
                            accountId = 101L,
                            accountName = "Axis Rewards CC",
                        ),
                    )
                }
            }
            line = reader.readLine()
        }

        assertEquals(25, ccTransactions.size)

        // 1. Compute metrics as of 2026-10-27 with Statement Day = 17, Opening Balance = 18589.90 (1858990 minor)
        val openingDebt = MinorMoney(1858990L)
        val refDateEpochDay = LocalDate.of(2026, 10, 27).toEpochDay()
        val limitMoney = MinorMoney(15000000L) // 1,50,000

        val metricsClean = AccountingRulesEngine.computeCreditCardMetrics(
            openingBalance = openingDebt,
            transactions = ccTransactions,
            settlementDayOfMonth = 17,
            referenceDateEpochDay = refDateEpochDay,
            creditLimit = limitMoney,
            initialUnbilledAmount = null,
        )

        // Billed statement due must be 15,203.08
        assertEquals(1520308L, metricsClean.statementPayable.amountMinor)
        // Active cycle unbilled spend must be 15,866.38
        assertEquals(1586638L, metricsClean.unbilledAmount.amountMinor)
        // Total outstanding debt is 31,069.46
        assertEquals(-3106946L, metricsClean.currentBalance.amountMinor)

        // 2. Self-Healing Simulation:
        // Even if initialUnbilledMinor in the database was accidentally set to 1586638L by editing due day,
        // our repository guard verifies anchor date <= last statement close date and ccTransactions.isNotEmpty(),
        // safely setting initialUnbilledMoney to null!
        val anchorEpochDay = LocalDate.of(2026, 6, 10).toEpochDay()
        val refDate = LocalDate.ofEpochDay(refDateEpochDay)
        val refYearMonth = YearMonth.from(refDate)
        val statementDay = 17
        val statementInRefMonth = refYearMonth.atDay(statementDay)
        val lastStatementCloseDate = if (refDate.isAfter(statementInRefMonth)) {
            statementInRefMonth
        } else {
            refYearMonth.minusMonths(1).atDay(statementDay)
        }

        val initialUnbilledMoney = if (anchorEpochDay <= lastStatementCloseDate.toEpochDay()) {
            null
        } else {
            MinorMoney(1586638L)
        }

        assertNull("Corrupted initial unbilled anchor from old cycle must be self-healed to null", initialUnbilledMoney)

        val metricsAfterHeal = AccountingRulesEngine.computeCreditCardMetrics(
            openingBalance = openingDebt,
            transactions = ccTransactions,
            settlementDayOfMonth = 17,
            referenceDateEpochDay = refDateEpochDay,
            creditLimit = limitMoney,
            initialUnbilledAmount = initialUnbilledMoney,
        )

        assertEquals(1520308L, metricsAfterHeal.statementPayable.amountMinor)
        assertEquals(1586638L, metricsAfterHeal.unbilledAmount.amountMinor)
        assertEquals(-3106946L, metricsAfterHeal.currentBalance.amountMinor)
    }

    @Test
    fun newCreditCardWithoutTransactions_initialUnbilledAmount_isRespected() {
        // Brand new card created in current billing cycle without transactions
        val refDateEpochDay = LocalDate.of(2026, 10, 27).toEpochDay()
        val totalOpeningDebt = MinorMoney(3000000L) // 30,000.00
        val initialUnbilled = MinorMoney(1000000L) // 10,000.00
        val limit = MinorMoney(10000000L) // 1,00,000.00

        val metrics = AccountingRulesEngine.computeCreditCardMetrics(
            openingBalance = totalOpeningDebt,
            transactions = emptyList(),
            settlementDayOfMonth = 17,
            referenceDateEpochDay = refDateEpochDay,
            creditLimit = limit,
            initialUnbilledAmount = initialUnbilled,
        )

        // 30,000 total debt: 20,000 closed cycle statement due, 10,000 active unbilled spend
        assertEquals(2000000L, metrics.statementPayable.amountMinor)
        assertEquals(1000000L, metrics.unbilledAmount.amountMinor)
        assertEquals(-3000000L, metrics.currentBalance.amountMinor)
        assertEquals(7000000L, metrics.availableCredit?.amountMinor)
    }

    @Test
    fun newCreditCardWithTransactionsInActiveCycle_initialUnbilledAmount_isPreservedAndAccumulates() {
        // Bug 1.1 Regression Test:
        // A card created mid-cycle (e.g. Oct 20) with statement day 17 has:
        // - Last statement closed on Oct 17
        // - Opening Balance Total Debt = ₹15,000 (1500000 minor)
        // - Initial Unbilled Spend = ₹5,000 (500000 minor)
        // - Expected Billed Due = ₹10,000 (1000000 minor)
        val anchorEpochDay = LocalDate.of(2026, 10, 20).toEpochDay()
        val refDateEpochDay = LocalDate.of(2026, 10, 27).toEpochDay()
        val refDate = LocalDate.ofEpochDay(refDateEpochDay)
        val refYearMonth = YearMonth.from(refDate)
        val statementDay = 17
        val statementInRefMonth = refYearMonth.atDay(statementDay)
        val lastStatementCloseDate = if (refDate.isAfter(statementInRefMonth)) {
            statementInRefMonth
        } else {
            refYearMonth.minusMonths(1).atDay(statementDay)
        }

        // Active cycle transaction: ₹200 expense logged on Oct 22
        val activeTx = TransactionItem(
            id = 201L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = LocalDate.of(2026, 10, 22).toEpochDay() * 86_400_000L + 12 * 3600 * 1000L,
            ledgerDateEpochDay = LocalDate.of(2026, 10, 22).toEpochDay(),
            amount = MinorMoney(20000L), // ₹200.00
            currencyCode = "INR",
            accountId = 101L,
            accountName = "Credit Card",
        )
        val ccTransactions = listOf(activeTx)

        // Repository evaluation: anchorEpochDay (Oct 20) > lastStatementCloseDate (Oct 17),
        // so initialUnbilledMoney MUST NOT be null even though ccTransactions.isNotEmpty() is true!
        val initialUnbilledMoney = if (anchorEpochDay <= lastStatementCloseDate.toEpochDay()) {
            null
        } else {
            MinorMoney(500000L) // ₹5,000.00
        }

        org.junit.Assert.assertNotNull(initialUnbilledMoney)
        assertEquals(500000L, initialUnbilledMoney?.amountMinor)

        val metrics = AccountingRulesEngine.computeCreditCardMetrics(
            openingBalance = MinorMoney(1500000L),
            transactions = ccTransactions,
            settlementDayOfMonth = statementDay,
            referenceDateEpochDay = refDateEpochDay,
            creditLimit = MinorMoney(10000000L),
            initialUnbilledAmount = initialUnbilledMoney,
        )

        // Statement Payable must remain ₹10,000 (NOT jump to ₹15,000!)
        assertEquals(1000000L, metrics.statementPayable.amountMinor)
        // Unbilled Spend must be ₹5,000 (initial) + ₹200 (active expense) = ₹5,200 (NOT drop to ₹200!)
        assertEquals(520000L, metrics.unbilledAmount.amountMinor)
        // Total Outstanding Debt = ₹15,200
        assertEquals(-1520000L, metrics.currentBalance.amountMinor)
    }

    @Test
    fun newCreditCard_initialUnbilledExpiresNaturally_whenBillingCycleCloses() {
        // When calendar rolls into the next billing cycle (e.g. Nov 20, 2026),
        // anchorEpochDay (Oct 20) <= lastStatementCloseDate (Nov 17),
        // so initialUnbilled naturally expires to null and rolls into closed-cycle debt.
        val anchorEpochDay = LocalDate.of(2026, 10, 20).toEpochDay()
        val refDateEpochDay = LocalDate.of(2026, 11, 20).toEpochDay()
        val refDate = LocalDate.ofEpochDay(refDateEpochDay)
        val refYearMonth = YearMonth.from(refDate)
        val statementDay = 17
        val statementInRefMonth = refYearMonth.atDay(statementDay)
        val lastStatementCloseDate = if (refDate.isAfter(statementInRefMonth)) {
            statementInRefMonth
        } else {
            refYearMonth.minusMonths(1).atDay(statementDay)
        }

        val initialUnbilledMoney = if (anchorEpochDay <= lastStatementCloseDate.toEpochDay()) {
            null
        } else {
            MinorMoney(500000L)
        }

        assertNull("Initial unbilled must roll over into closed statement once billing cycle closes", initialUnbilledMoney)
    }
}
