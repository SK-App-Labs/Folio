package com.folioledger.app.domain.util

import com.folioledger.app.domain.model.CardDueReminderItem
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.SafeToSpendState
import com.folioledger.app.domain.model.SafeToSpendStatus
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionBookmark
import com.folioledger.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WidgetSnapshotBuilderTest {

    private val inrSpec = SupportedCurrencies.fromCode("INR")
    private val usdSpec = SupportedCurrencies.fromCode("USD")

    @Test
    fun safeToSpendSnapshot_formatsPositiveIndianGrouping() {
        val state = SafeToSpendState(
            totalLiquidBankAndCash = MinorMoney(20000000L), // ₹2,00,000
            totalCreditCardDebt = MinorMoney(3500000L),     // ₹35,000
            upcoming14DayCommitments = MinorMoney(1500000L), // ₹15,000
            trueSafeToSpend = MinorMoney(15000000L),        // ₹1,50,000
            isPositive = true,
            status = SafeToSpendStatus.SAFE
        )

        val snapshot = WidgetSnapshotBuilder.buildSafeToSpendSnapshot(
            safeToSpendState = state,
            currencySpec = inrSpec,
            grouping = NumberGroupingSystem.INDIAN,
            isMasked = false,
            isDemoMode = false,
            updatedTimeLabel = "12:00 PM"
        )

        assertEquals("₹1,50,000.00", snapshot.formattedSafeToSpend)
        assertEquals(WidgetStatusTone.SAFE, snapshot.statusTone)
        assertEquals("Safe Discretionary Cash", snapshot.statusLabel)
        assertTrue(snapshot.secondarySummary.contains("Cash"))
        assertTrue(snapshot.secondarySummary.contains("Cards"))
        assertTrue(snapshot.secondarySummary.contains("Bills"))
    }

    @Test
    fun safeToSpendSnapshot_handlesDebtExceedsCashCriticalTone() {
        val state = SafeToSpendState(
            totalLiquidBankAndCash = MinorMoney(100000L),
            totalCreditCardDebt = MinorMoney(500000L),
            upcoming14DayCommitments = MinorMoney.ZERO,
            trueSafeToSpend = MinorMoney(-400000L),
            isPositive = false,
            status = SafeToSpendStatus.CARD_DEBT_EXCEEDS_CASH
        )

        val snapshot = WidgetSnapshotBuilder.buildSafeToSpendSnapshot(
            safeToSpendState = state,
            currencySpec = usdSpec,
            grouping = NumberGroupingSystem.INTERNATIONAL,
            isMasked = false
        )

        assertEquals(WidgetStatusTone.CRITICAL, snapshot.statusTone)
        assertEquals("Card Debt Exceeds Cash", snapshot.statusLabel)
        assertEquals("-$4,000.00", snapshot.formattedSafeToSpend)
    }

    @Test
    fun safeToSpendSnapshot_maskedModeHidesFigures() {
        val nextDue = CardDueReminderItem(
            accountId = 101L,
            accountName = "Regalia Gold",
            currencyCode = "INR",
            amountDue = MinorMoney(2450000L),
            dueDate = LocalDate.now().plusDays(4),
            daysRemaining = 4,
            isOverdue = false
        )

        val state = SafeToSpendState(
            totalLiquidBankAndCash = MinorMoney(20000000L),
            totalCreditCardDebt = MinorMoney(2450000L),
            upcoming14DayCommitments = MinorMoney.ZERO,
            trueSafeToSpend = MinorMoney(17550000L),
            isPositive = true,
            status = SafeToSpendStatus.SAFE
        )

        val maskedSnapshot = WidgetSnapshotBuilder.buildSafeToSpendSnapshot(
            safeToSpendState = state,
            currencySpec = inrSpec,
            grouping = NumberGroupingSystem.INDIAN,
            nextCardDue = nextDue,
            isMasked = true
        )

        assertEquals(WidgetSnapshotBuilder.MASKED_AMOUNT_TEXT, maskedSnapshot.formattedSafeToSpend)
        assertEquals(WidgetSnapshotBuilder.MASKED_SUMMARY_TEXT, maskedSnapshot.secondarySummary)
        assertNotNull(maskedSnapshot.nextCardDueSummary)
        assertEquals("Regalia Gold: Due in 4d", maskedSnapshot.nextCardDueSummary)
    }

    @Test
    fun safeToSpendSnapshot_unmaskedNextCardDueIncludesAmount() {
        val nextDue = CardDueReminderItem(
            accountId = 101L,
            accountName = "Sapphire Reserve",
            currencyCode = "USD",
            amountDue = MinorMoney(150000L),
            dueDate = LocalDate.now().plusDays(2),
            daysRemaining = 2,
            isOverdue = false
        )

        val state = SafeToSpendState(
            totalLiquidBankAndCash = MinorMoney(500000L),
            totalCreditCardDebt = MinorMoney(150000L),
            upcoming14DayCommitments = MinorMoney.ZERO,
            trueSafeToSpend = MinorMoney(350000L),
            isPositive = true,
            status = SafeToSpendStatus.SAFE
        )

        val unmasked = WidgetSnapshotBuilder.buildSafeToSpendSnapshot(
            safeToSpendState = state,
            currencySpec = usdSpec,
            grouping = NumberGroupingSystem.INTERNATIONAL,
            nextCardDue = nextDue,
            isMasked = false
        )

        assertTrue(unmasked.nextCardDueSummary?.contains("Sapphire Reserve: Due in 2d ($1.5k)") == true)
    }

    @Test
    fun quickAddSnapshot_mapsTopBookmarks() {
        val bookmarks = listOf(
            TransactionBookmark(
                id = 1L,
                title = "Morning Filter Coffee",
                type = TransactionType.EXPENSE,
                amount = MinorMoney(15000L),
                accountId = 1L
            ),
            TransactionBookmark(
                id = 2L,
                title = "Metro Smart Card",
                type = TransactionType.EXPENSE,
                amount = MinorMoney(6000L),
                accountId = 1L
            ),
            TransactionBookmark(
                id = 3L,
                title = "Daily Groceries",
                type = TransactionType.EXPENSE,
                amount = MinorMoney(45000L),
                accountId = 1L
            ),
            TransactionBookmark(
                id = 4L,
                title = "Fourth Item Should Be Excluded By Limit",
                type = TransactionType.EXPENSE,
                amount = MinorMoney(9900L),
                accountId = 1L
            )
        )

        val snapshot = WidgetSnapshotBuilder.buildQuickAddSnapshot(
            bookmarks = bookmarks,
            primaryCurrencySpec = inrSpec,
            grouping = NumberGroupingSystem.INDIAN,
            limit = 3
        )

        assertEquals(3, snapshot.bookmarks.size)
        assertEquals("Morning Filter Coffee", snapshot.bookmarks[0].title)
        assertEquals("₹150", snapshot.bookmarks[0].formattedAmount)
        assertEquals("Metro Smart Card", snapshot.bookmarks[1].title)
        assertEquals("₹60", snapshot.bookmarks[1].formattedAmount)
    }

    @Test
    fun safeToSpendSnapshot_formatsNegativeLiquidCashCorrectly() {
        val state = SafeToSpendState(
            totalLiquidBankAndCash = MinorMoney(-5000000L), // -₹50,000 (Overdrawn)
            totalCreditCardDebt = MinorMoney(1000000L),
            upcoming14DayCommitments = MinorMoney.ZERO,
            trueSafeToSpend = MinorMoney(-6000000L),
            isPositive = false,
            status = SafeToSpendStatus.ZERO_RESERVES
        )

        val snapshot = WidgetSnapshotBuilder.buildSafeToSpendSnapshot(
            safeToSpendState = state,
            currencySpec = inrSpec,
            grouping = NumberGroupingSystem.INDIAN,
            isMasked = false
        )

        assertTrue(snapshot.secondarySummary.contains("Cash -₹50k"))
    }

    @Test
    fun safeToSpendSnapshot_masked_hidesAmountsAndBalances() {
        val state = SafeToSpendState(
            totalLiquidBankAndCash = MinorMoney(20000000L),
            totalCreditCardDebt = MinorMoney(3500000L),
            upcoming14DayCommitments = MinorMoney(1500000L),
            trueSafeToSpend = MinorMoney(15000000L),
            isPositive = true,
            status = SafeToSpendStatus.SAFE
        )
        val cardReminder = CardDueReminderItem(
            accountId = 2L,
            accountName = "HDFC Regalia",
            currencyCode = "INR",
            amountDue = MinorMoney(500000L),
            dueDate = LocalDate.now().plusDays(2),
            daysRemaining = 2L,
            isOverdue = false
        )

        val snapshot = WidgetSnapshotBuilder.buildSafeToSpendSnapshot(
            safeToSpendState = state,
            currencySpec = inrSpec,
            grouping = NumberGroupingSystem.INDIAN,
            nextCardDue = cardReminder,
            isMasked = true
        )

        assertEquals(WidgetSnapshotBuilder.MASKED_AMOUNT_TEXT, snapshot.formattedSafeToSpend)
        assertEquals(WidgetSnapshotBuilder.MASKED_SUMMARY_TEXT, snapshot.secondarySummary)
        assertEquals("HDFC Regalia: Due in 2d", snapshot.nextCardDueSummary)
        assertTrue(snapshot.isMasked)
    }
}
