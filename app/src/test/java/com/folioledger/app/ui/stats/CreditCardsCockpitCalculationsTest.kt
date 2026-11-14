// File: app/src/test/java/com/folioledger/app/ui/stats/CreditCardsCockpitCalculationsTest.kt
package com.folioledger.app.ui.stats

import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.MonthlyTrendBar
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.ui.accounts.CreditCardCycleCalculator
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreditCardsCockpitCalculationsTest {

    private val refDate = LocalDate.of(2026, 10, 31)
    private val refEpochDay = refDate.toEpochDay()

    private fun createTestAccount(
        id: Long,
        name: String,
        group: AccountGroup,
        balanceMinor: Long,
        creditLimitMinor: Long? = null,
        statementDay: Int? = null,
        dueDay: Int? = null,
        statementPayableMinor: Long? = null,
        unbilledMinor: Long? = null
    ) = AccountSummary(
        id = id,
        name = name,
        groupType = group,
        currencyCode = "INR",
        currentBalance = MinorMoney(balanceMinor),
        creditLimit = creditLimitMinor?.let { MinorMoney(it) },
        settlementDayOfMonth = statementDay,
        paymentDueDayOfMonth = dueDay,
        creditCardStatementPayable = statementPayableMinor?.let { MinorMoney(it) },
        creditCardUnbilledAmount = unbilledMinor?.let { MinorMoney(it) },
        colorHex = "#10B981",
        iconName = "credit_card",
        includeInNetWorth = true,
        isArchived = false
    )

    private fun createTestTransaction(
        id: Long,
        accountId: Long,
        amountMinor: Long,
        type: TransactionType,
        epochDay: Long = refEpochDay,
        categoryName: String = "Shopping"
    ) = TransactionItem(
        id = id,
        accountId = accountId,
        accountName = "Account $accountId",
        amount = MinorMoney(amountMinor),
        currencyCode = "INR",
        type = type,
        ledgerDateEpochDay = epochDay,
        timestampEpochMillis = epochDay * 86_400_000L,
        categoryId = 100L,
        categoryName = categoryName,
        note = "Test Tx",
        payeeOrDescription = "Merchant",
        tags = emptyList(),
        splits = emptyList()
    )

    @Test
    fun testLiquidCashCoverage_100PercentCashBacked() {
        val bank = createTestAccount(1, "HDFC Bank", AccountGroup.BANK_ACCOUNT, balanceMinor = 150_000_00) // ₹1,50,000
        val cash = createTestAccount(2, "Cash in Hand", AccountGroup.CASH, balanceMinor = 15_000_00)     // ₹15,000
        val card = createTestAccount(3, "Axis Card", AccountGroup.CREDIT_CARD, balanceMinor = -48_200_00) // ₹48,200 debt

        val liquidMinor = bank.currentBalance.amountMinor + cash.currentBalance.amountMinor // 165,000
        val debtMinor = card.currentBalance.abs().amountMinor // 48,200
        val netLiquidMinor = liquidMinor - debtMinor

        val isCashBacked = liquidMinor >= debtMinor
        val coverageRatio = liquidMinor.toFloat() / debtMinor.toFloat()

        assertTrue("Should be 100% cash-backed", isCashBacked)
        assertEquals(116_800_00L, netLiquidMinor)
        assertTrue("Coverage ratio should be > 3.4x", coverageRatio > 3.4f)
    }

    @Test
    fun testLiquidCashCoverage_RidingCreditFloat() {
        val bank = createTestAccount(1, "Salary Bank", AccountGroup.BANK_ACCOUNT, balanceMinor = 20_000_00) // ₹20,000
        val card = createTestAccount(2, "ICICI Card", AccountGroup.CREDIT_CARD, balanceMinor = -60_000_00) // ₹60,000 debt

        val liquidMinor = bank.currentBalance.amountMinor
        val debtMinor = card.currentBalance.abs().amountMinor
        val isCashBacked = liquidMinor >= debtMinor

        assertFalse("Should be riding credit float (deficit)", isCashBacked)
        val deficit = debtMinor - liquidMinor
        assertEquals(40_000_00L, deficit)
    }

    @Test
    fun testSmartSwipeAdvisor_SelectsCardWithMaxFloatDays() {
        // Card A: Statement day 28th, due day 16th
        // On 31 Oct: Statement just closed on 28 Oct. Today's purchase will close on 28 Nov and be due around 16 Dec.
        val cardA = createTestAccount(
            id = 1,
            name = "Axis Rewards",
            group = AccountGroup.CREDIT_CARD,
            balanceMinor = 0L,
            creditLimitMinor = 100_000_00L,
            statementDay = 28,
            dueDay = 16
        )

        // Card B: Statement day 1st, due day 20th
        // On 31 Oct: Statement closes tomorrow (1 Nov)! Due around 20 Nov.
        val cardB = createTestAccount(
            id = 2,
            name = "HDFC Regalia",
            group = AccountGroup.CREDIT_CARD,
            balanceMinor = 0L,
            creditLimitMinor = 100_000_00L,
            statementDay = 1,
            dueDay = 20
        )

        val cycleA = CreditCardCycleCalculator.computeCycleWindow(refEpochDay, 28, 16)
        val cycleB = CreditCardCycleCalculator.computeCycleWindow(refEpochDay, 1, 20)

        val floatDaysA = ChronoUnit.DAYS.between(refDate, cycleA.activeCycleNextDueDate)
        val floatDaysB = ChronoUnit.DAYS.between(refDate, cycleB.activeCycleNextDueDate)

        // Card A should give far more float days (~46 days) than Card B (~20 days)
        assertTrue("Card A float ($floatDaysA) should be greater than Card B float ($floatDaysB)", floatDaysA > floatDaysB)
        assertTrue("Card A should give over 40 interest-free days", floatDaysA >= 40)
    }

    @Test
    fun testSmartSwipeAdvisor_PrioritizesCardsWithAvailableCredit() {
        // Card A has longer float (~46 days) but is 100% MAXED OUT (limit 50k, debt 50k -> available 0)
        val cardA = createTestAccount(
            id = 1,
            name = "Maxed Out Card",
            group = AccountGroup.CREDIT_CARD,
            balanceMinor = -50_000_00L,
            creditLimitMinor = 50_000_00L,
            statementDay = 28,
            dueDay = 16
        )

        // Card B has shorter float (~20 days) but HAS AVAILABLE LIMIT (limit 100k, debt 10k -> available 90k)
        val cardB = createTestAccount(
            id = 2,
            name = "Available Card",
            group = AccountGroup.CREDIT_CARD,
            balanceMinor = -10_000_00L,
            creditLimitMinor = 100_000_00L,
            statementDay = 1,
            dueDay = 20
        )

        val cards = listOf(cardA, cardB)
        val candidateWithHeadroom = cards.filter {
            val limit = it.creditLimit?.amountMinor ?: 0L
            val debt = it.currentBalance.abs().amountMinor
            limit > debt
        }.maxByOrNull { card ->
            val cycle = CreditCardCycleCalculator.computeCycleWindow(refEpochDay, card.settlementDayOfMonth!!, card.paymentDueDayOfMonth!!)
            ChronoUnit.DAYS.between(refDate, cycle.activeCycleNextDueDate)
        }

        assertNotNull(candidateWithHeadroom)
        assertEquals("Available Card", candidateWithHeadroom?.name)
    }

    @Test
    fun testSpendModeRatio_StrictlyExcludesTransfersAndSettlements() {
        val ccId = 10L
        val bankId = 20L

        val txns = listOf(
            createTestTransaction(1, ccId, 50_000_00L, TransactionType.EXPENSE),
            createTestTransaction(2, bankId, 15_000_00L, TransactionType.EXPENSE),
            // Settlement transfer from Bank -> CC should NOT count towards expense ratio!
            createTestTransaction(3, bankId, 30_000_00L, TransactionType.CC_SETTLEMENT),
            createTestTransaction(4, bankId, 20_000_00L, TransactionType.TRANSFER)
        )

        var ccExpenseMinor = 0L
        var liquidExpenseMinor = 0L

        for (tx in txns) {
            if (tx.type == TransactionType.EXPENSE) {
                if (tx.accountId == ccId) {
                    ccExpenseMinor += tx.amount.amountMinor
                } else {
                    liquidExpenseMinor += tx.amount.amountMinor
                }
            }
        }

        assertEquals("CC Expense should be 50k", 50_000_00L, ccExpenseMinor)
        assertEquals("Liquid Expense should be 15k (transfers and settlements excluded)", 15_000_00L, liquidExpenseMinor)

        val totalExpense = ccExpenseMinor + liquidExpenseMinor // 65,000
        val ccPct = (ccExpenseMinor.toFloat() / totalExpense.toFloat() * 100f)
        val liquidPct = (liquidExpenseMinor.toFloat() / totalExpense.toFloat() * 100f)

        assertEquals(76.92f, ccPct, 0.1f)
        assertEquals(23.08f, liquidPct, 0.1f)
    }

    @Test
    fun testSingleCardSpikeAlert_FlagsSingleCardOver30Percent() {
        // Aggregate limit = 2,00,000. Total debt = 35,000. Aggregate util = 17.5% (Safe <30%)
        // Card 1: Limit 1,50,000. Debt 10,000 (6.6% Safe)
        // Card 2: Limit 50,000. Debt 25,000 (50.0% Spike!)
        val card1 = createTestAccount(1, "Large Card", AccountGroup.CREDIT_CARD, balanceMinor = -10_000_00L, creditLimitMinor = 150_000_00L)
        val card2 = createTestAccount(2, "Small Card", AccountGroup.CREDIT_CARD, balanceMinor = -25_000_00L, creditLimitMinor = 50_000_00L)

        val cards = listOf(card1, card2)
        val totalLimit = cards.sumOf { it.creditLimit?.amountMinor ?: 0L }
        val totalDebt = cards.sumOf { it.currentBalance.abs().amountMinor }
        val aggregateUtil = (totalDebt.toFloat() / totalLimit.toFloat()) * 100f

        assertEquals("Aggregate utilization should be 17.5%", 17.5f, aggregateUtil, 0.1f)

        // Find single card spike
        val spikeCard = cards.firstOrNull { card ->
            val limit = card.creditLimit?.amountMinor ?: 0L
            val debt = card.currentBalance.abs().amountMinor
            val util = if (limit > 0) (debt.toFloat() / limit.toFloat() * 100f) else 0f
            util > 30f
        }

        assertNotNull("Should detect single card spike", spikeCard)
        assertEquals("Small Card", spikeCard?.name)
    }

    @Test
    fun computeCreditCockpitState_multiCurrency_convertsToPrimaryAccurately() {
        val inrCard = createTestAccount(
            id = 1L,
            name = "HDFC Regalia",
            group = AccountGroup.CREDIT_CARD,
            balanceMinor = -20_000_00L, // -₹20,000
            creditLimitMinor = 200_000_00L, // ₹2,00,000
            statementDay = 15,
            dueDay = 5
        ).copy(currencyCode = "INR")

        val usdCard = createTestAccount(
            id = 2L,
            name = "Chase Sapphire",
            group = AccountGroup.CREDIT_CARD,
            balanceMinor = -1000_00L, // -$1,000
            creditLimitMinor = 5000_00L, // $5,000
            statementDay = 15,
            dueDay = 5
        ).copy(currencyCode = "USD")

        val inrBank = createTestAccount(
            id = 3L,
            name = "SBI Savings",
            group = AccountGroup.BANK_ACCOUNT,
            balanceMinor = 100_000_00L // ₹1,00,000
        ).copy(currencyCode = "INR")

        val usdBank = createTestAccount(
            id = 4L,
            name = "BoA Checking",
            group = AccountGroup.BANK_ACCOUNT,
            balanceMinor = 500_00L // $500
        ).copy(currencyCode = "USD")

        val secondaryCurrencies = listOf(
            com.folioledger.app.domain.model.SecondaryCurrency(
                code = "USD",
                exchangeRateToPrimary = 80.0
            )
        )

        val txInrCard = createTestTransaction(
            id = 101L,
            accountId = 1L,
            amountMinor = 10_000_00L, // ₹10,000
            type = TransactionType.EXPENSE
        ).copy(currencyCode = "INR")

        val txUsdCard = createTestTransaction(
            id = 102L,
            accountId = 2L,
            amountMinor = 200_00L, // $200 = ₹16,000
            type = TransactionType.EXPENSE
        ).copy(currencyCode = "USD")

        val txInrBank = createTestTransaction(
            id = 103L,
            accountId = 3L,
            amountMinor = 10_000_00L, // ₹10,000
            type = TransactionType.EXPENSE
        ).copy(currencyCode = "INR")

        val cockpit = com.folioledger.app.domain.util.CreditCardCockpitCalculator.computeCreditCockpitState(
            allAccounts = listOf(inrCard, usdCard, inrBank, usdBank),
            allTxs = listOf(txInrCard, txUsdCard, txInrBank),
            periodTransactions = listOf(txInrCard, txUsdCard, txInrBank),
            trendBars = emptyList(),
            referenceDateEpochDay = refEpochDay,
            primaryCurrencyCode = "INR",
            secondaryCurrencies = secondaryCurrencies
        )

        // Total Limit = ₹2,00,000 + ($5,000 * 80 = ₹4,00,000) = ₹6,00,000 (60,000,000 paise)
        assertEquals(60_000_000L, cockpit.totalCreditLimit.amountMinor)

        // Total CC Debt = ₹20,000 + ($1,000 * 80 = ₹80,000) = ₹1,00,000 (10,000,000 paise)
        assertEquals(10_000_000L, cockpit.totalCcDebt.amountMinor)

        // Total Available = ₹6,00,000 - ₹1,00,000 = ₹5,00,000 (50,000,000 paise)
        assertEquals(50_000_000L, cockpit.totalAvailableCredit.amountMinor)

        // Total Liquid Cash = ₹1,00,000 + ($500 * 80 = ₹40,000) = ₹1,40,000 (14,000,000 paise)
        assertEquals(14_000_000L, cockpit.liquidCoverage.totalLiquidCash.amountMinor)

        // Net Liquid Cash = ₹1,40,000 - ₹1,00,000 = ₹40,000
        assertEquals(4_000_000L, cockpit.liquidCoverage.netLiquidCash.amountMinor)
        assertTrue(cockpit.liquidCoverage.isCashBacked)

        // Spend Ratio:
        // Credit spend = ₹10,000 + ($200 * 80 = ₹16,000) = ₹26,000 (2,600,000 paise)
        assertEquals(2_600_000L, cockpit.spendRatio.creditSpend.amountMinor)
        // Liquid spend = ₹10,000 (1,000,000 paise)
        assertEquals(1_000_000L, cockpit.spendRatio.liquidSpend.amountMinor)
    }

    @Test
    fun computeCreditCockpitState_anchoredToToday_preventsFalseOverdueInHistoricalNavigation() {
        val today = LocalDate.of(2026, 11, 13)
        val todayEpochDay = today.toEpochDay()
        val twoMonthsAgoEpochDay = today.minusMonths(2).toEpochDay()

        // Card with statement day 1, due day 20.
        // Today is Nov 13: active cycle ends Dec 1, previous statement closed Nov 1, due Nov 20.
        // If evaluated today: daysRemaining to Nov 20 is +7 days (not overdue).
        // If evaluated two months ago (Sep 13): cycle looks at Sep 20, falsely calculating negative daysRemaining if compared to current reality.
        val activeCard = createTestAccount(
            id = 1L,
            name = "Live Card",
            group = AccountGroup.CREDIT_CARD,
            balanceMinor = 0L,
            creditLimitMinor = 100_000_00L,
            statementDay = 1,
            dueDay = 20,
            statementPayableMinor = 15_000_00L,
            unbilledMinor = 5_000_00L
        )

        // Real-time anchoring (passing todayEpochDay as in StatsAndBudgetsViewModel)
        val liveCockpit = com.folioledger.app.domain.util.CreditCardCockpitCalculator.computeCreditCockpitState(
            allAccounts = listOf(activeCard),
            allTxs = emptyList(),
            periodTransactions = emptyList(),
            trendBars = emptyList(),
            referenceDateEpochDay = todayEpochDay
        )
        val liveItem = liveCockpit.timelineItems.first()
        assertEquals(LocalDate.of(2026, 11, 20), liveItem.targetDate)
        assertEquals(7L, liveItem.daysRemaining)
        assertFalse("Card due in 7 days must NOT be marked overdue", liveItem.isOverdue)
        assertFalse("Card due in 7 days must NOT be marked urgent (urgent is <= 3 days)", liveItem.isUrgent)
        assertNotNull(liveCockpit.smartSwipeRecommendation)
        assertEquals(LocalDate.of(2026, 12, 1), liveCockpit.smartSwipeRecommendation?.statementCloseDate)
    }

    @Test
    fun computeCreditCockpitState_descendingTransactions_preservesRefundNetting() {
        val today = LocalDate.of(2026, 11, 13)
        val todayEpoch = today.toEpochDay()

        val card = createTestAccount(
            id = 50L,
            name = "Rewards Visa",
            group = AccountGroup.CREDIT_CARD,
            balanceMinor = 0L,
            creditLimitMinor = 200_000_00L
        )

        val milestonesJson = """
            {
                "50": {
                    "milestoneTitle": "Fee Waiver",
                    "cycleStartDateEpochDay": ${LocalDate.of(2026, 1, 1).toEpochDay()},
                    "targetAmountMinor": 5000000,
                    "annualFeeSavedMinor": 100000,
                    "isAutoRenewing": true
                }
            }
        """.trimIndent()

        // Descending order (newest first, as returned by Room)
        // Tx 1: Nov 10, Refund ₹4,000
        val txRefund = TransactionItem(
            id = 202L,
            type = TransactionType.REFUND,
            timestampEpochMillis = LocalDate.of(2026, 11, 10).toEpochDay() * 86_400_000L,
            ledgerDateEpochDay = LocalDate.of(2026, 11, 10).toEpochDay(),
            amount = MinorMoney(4_000_00L),
            currencyCode = "INR",
            accountId = 50L,
            accountName = "Rewards Visa",
            categoryName = "Electronics"
        )
        // Tx 2: Nov 5, Expense ₹10,000
        val txExpense = TransactionItem(
            id = 201L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = LocalDate.of(2026, 11, 5).toEpochDay() * 86_400_000L,
            ledgerDateEpochDay = LocalDate.of(2026, 11, 5).toEpochDay(),
            amount = MinorMoney(10_000_00L),
            currencyCode = "INR",
            accountId = 50L,
            accountName = "Rewards Visa",
            categoryName = "Electronics"
        )

        val trendBars = listOf(
            MonthlyTrendBar(
                periodLabel = "Nov 2026",
                startEpochDay = LocalDate.of(2026, 11, 1).toEpochDay(),
                endEpochDay = LocalDate.of(2026, 11, 30).toEpochDay(),
                totalIncome = MinorMoney.ZERO,
                totalExpense = MinorMoney.ZERO,
                netSavings = MinorMoney.ZERO
            )
        )

        val cockpit = com.folioledger.app.domain.util.CreditCardCockpitCalculator.computeCreditCockpitState(
            allAccounts = listOf(card),
            allTxs = listOf(txRefund, txExpense), // Descending
            periodTransactions = listOf(txRefund, txExpense),
            trendBars = trendBars,
            referenceDateEpochDay = todayEpoch,
            milestonesJson = milestonesJson
        )

        val cardAnalytics = cockpit.cardAnalyticsItems.first { it.account.id == 50L }
        // Net qualifying spend: 10,000 - 4,000 = 6,000
        assertEquals(6_000_00L, cardAnalytics.milestoneInfo?.qualifyingSpend?.amountMinor)
        assertEquals(6_000_00L, cardAnalytics.ytdSpend.amountMinor)
        assertEquals("Electronics", cardAnalytics.topCategoryName)

        // Trend bars: Net spend in Nov 2026 is 6,000
        assertEquals(6_000_00L, cockpit.sixMonthCreditTrendBars.first().totalExpense.amountMinor)
    }

    @Test
    fun c1_futureMultiYearInstallments_excludedFromYtdSpend() {
        val today = LocalDate.of(2026, 10, 31)
        val todayEpoch = today.toEpochDay()
        val card = createTestAccount(
            id = 60L,
            name = "Infinia",
            group = AccountGroup.CREDIT_CARD,
            balanceMinor = -50_000_00L
        )

        val txPast = TransactionItem(
            id = 1L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(10_000_00L),
            currencyCode = "INR",
            accountId = 60L,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = LocalDate.of(2026, 10, 10).toEpochDay(),
            accountName = "Infinia",
            categoryName = "Shopping"
        )

        // Future installment in Nov 2026 (this year)
        val txFutureNov = TransactionItem(
            id = 2L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(5_000_00L),
            currencyCode = "INR",
            accountId = 60L,
            timestampEpochMillis = 2000L,
            ledgerDateEpochDay = LocalDate.of(2026, 11, 10).toEpochDay(),
            accountName = "Infinia",
            categoryName = "Shopping"
        )

        // Future installment in 2027 (next year)
        val txFuture2027 = TransactionItem(
            id = 3L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(20_000_00L),
            currencyCode = "INR",
            accountId = 60L,
            timestampEpochMillis = 3000L,
            ledgerDateEpochDay = LocalDate.of(2027, 2, 10).toEpochDay(),
            accountName = "Infinia",
            categoryName = "Shopping"
        )

        val cockpit = com.folioledger.app.domain.util.CreditCardCockpitCalculator.computeCreditCockpitState(
            allAccounts = listOf(card),
            allTxs = listOf(txPast, txFutureNov, txFuture2027),
            periodTransactions = emptyList(),
            trendBars = emptyList(),
            referenceDateEpochDay = todayEpoch
        )

        val cardAnalytics = cockpit.cardAnalyticsItems.first { it.account.id == 60L }
        // Only txPast (10,000) should be in YTD spend; future 5,000 and 20,000 must be excluded!
        assertEquals(10_000_00L, cardAnalytics.ytdSpend.amountMinor)
    }

    @Test
    fun c2_multiCurrencyForexAndFees_calculatedInMilestoneAndYtdSpend() {
        val today = LocalDate.of(2026, 10, 31)
        val todayEpoch = today.toEpochDay()
        val card = createTestAccount(
            id = 70L,
            name = "Forex Card",
            group = AccountGroup.CREDIT_CARD,
            balanceMinor = -10_000_00L
        )

        val milestonesJson = """{"70": {"milestoneTitle": "Forex Spend", "cycleStartDateEpochDay": ${LocalDate.of(2026, 1, 1).toEpochDay()}, "targetAmountMinor": 50000000, "isAutoRenewing": true}}"""

        // USD 100 expense + USD 2 transfer fee at exchange rate 80.0 INR per USD
        // Converted: (100 + 2) * 80 = 8,160 INR
        val txUsd = TransactionItem(
            id = 1L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(100_00L),
            transferFee = MinorMoney(2_00L),
            currencyCode = "USD",
            accountId = 70L,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = LocalDate.of(2026, 8, 15).toEpochDay(),
            accountName = "Forex Card",
            categoryName = "Travel"
        )

        val cockpit = com.folioledger.app.domain.util.CreditCardCockpitCalculator.computeCreditCockpitState(
            allAccounts = listOf(card),
            allTxs = listOf(txUsd),
            periodTransactions = emptyList(),
            trendBars = emptyList(),
            referenceDateEpochDay = todayEpoch,
            milestonesJson = milestonesJson,
            primaryCurrencyCode = "INR",
            secondaryCurrencies = listOf(com.folioledger.app.domain.model.SecondaryCurrency("USD", 80.0))
        )

        val cardAnalytics = cockpit.cardAnalyticsItems.first { it.account.id == 70L }
        assertEquals(8_160_00L, cardAnalytics.ytdSpend.amountMinor)
        assertEquals(8_160_00L, cardAnalytics.milestoneInfo?.qualifyingSpend?.amountMinor)
    }

    @Test
    fun c3_creditSpendRatio_includesRefundsAndExcludesOverdraftFromLiquidSpend() {
        val today = LocalDate.of(2026, 10, 31)
        val card = createTestAccount(1L, "Credit Card", AccountGroup.CREDIT_CARD, -15_000_00L)
        val checking = createTestAccount(2L, "Checking Bank", AccountGroup.BANK_ACCOUNT, 50_000_00L)
        val overdraft = createTestAccount(3L, "Overdraft Line", AccountGroup.OVERDRAFT, -20_000_00L)

        // Card: 20k expense - 5k refund = 15k credit spend
        val txCardExp = TransactionItem(
            id = 1L, type = TransactionType.EXPENSE, amount = MinorMoney(20_000_00L),
            currencyCode = "INR", accountId = 1L, timestampEpochMillis = 1000L,
            ledgerDateEpochDay = today.toEpochDay(), accountName = "Credit Card"
        )
        val txCardRef = TransactionItem(
            id = 2L, type = TransactionType.REFUND, amount = MinorMoney(5_000_00L),
            currencyCode = "INR", accountId = 1L, timestampEpochMillis = 2000L,
            ledgerDateEpochDay = today.toEpochDay(), accountName = "Credit Card"
        )

        // Checking: 10k expense = 10k liquid spend
        val txBankExp = TransactionItem(
            id = 3L, type = TransactionType.EXPENSE, amount = MinorMoney(10_000_00L),
            currencyCode = "INR", accountId = 2L, timestampEpochMillis = 3000L,
            ledgerDateEpochDay = today.toEpochDay(), accountName = "Checking Bank"
        )

        // Overdraft: 10k expense (Debt, NOT liquid cash spend!)
        val txOdExp = TransactionItem(
            id = 4L, type = TransactionType.EXPENSE, amount = MinorMoney(10_000_00L),
            currencyCode = "INR", accountId = 3L, timestampEpochMillis = 4000L,
            ledgerDateEpochDay = today.toEpochDay(), accountName = "Overdraft Line"
        )

        val cockpit = com.folioledger.app.domain.util.CreditCardCockpitCalculator.computeCreditCockpitState(
            allAccounts = listOf(card, checking, overdraft),
            allTxs = listOf(txCardExp, txCardRef, txBankExp, txOdExp),
            periodTransactions = listOf(txCardExp, txCardRef, txBankExp, txOdExp),
            trendBars = emptyList(),
            referenceDateEpochDay = today.toEpochDay()
        )

        assertEquals(15_000_00L, cockpit.spendRatio.creditSpend.amountMinor)
        assertEquals(10_000_00L, cockpit.spendRatio.liquidSpend.amountMinor)
        assertEquals(60.0f, cockpit.spendRatio.creditPercentage, 0.01f)
        assertEquals(40.0f, cockpit.spendRatio.liquidPercentage, 0.01f)
    }

    @Test
    fun c4_utilizationStatus_consistentAt50Percent() {
        val card = createTestAccount(
            id = 80L,
            name = "Card 60%",
            group = AccountGroup.CREDIT_CARD,
            balanceMinor = -60_000_00L, // 60,000 debt
            creditLimitMinor = 100_000_00L // 100,000 limit -> 60% utilization
        )

        val healthList = com.folioledger.app.domain.util.CreditCardCockpitCalculator.computeCreditCardHealthList(listOf(card))
        val cockpit = com.folioledger.app.domain.util.CreditCardCockpitCalculator.computeCreditCockpitState(
            allAccounts = listOf(card),
            allTxs = emptyList(),
            periodTransactions = emptyList(),
            trendBars = emptyList(),
            referenceDateEpochDay = LocalDate.of(2026, 10, 31).toEpochDay()
        )

        assertEquals(com.folioledger.app.domain.model.CreditUtilizationStatus.WARNING, healthList.first().status)
        assertEquals(com.folioledger.app.domain.model.CreditUtilizationStatus.WARNING, cockpit.cardAnalyticsItems.first().status)
        assertEquals(com.folioledger.app.domain.model.CreditUtilizationStatus.WARNING, cockpit.aggregateStatus)
    }
}
