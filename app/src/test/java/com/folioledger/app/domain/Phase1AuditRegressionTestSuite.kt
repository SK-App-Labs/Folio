package com.folioledger.app.domain

import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.RepeatFrequency
import com.folioledger.app.domain.model.RecurringCommitmentItem
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.usecase.AccountingRulesEngine
import com.folioledger.app.domain.util.AccountPassbookCalculator
import com.folioledger.app.domain.util.CreditCardCockpitCalculator
import com.folioledger.app.domain.util.CreditCardCycleCalculator
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import com.folioledger.app.domain.util.PassbookPeriodCalculator
import com.folioledger.app.domain.util.SafeToSpendCalculator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class Phase1AuditRegressionTestSuite {

    private val engine = AccountingRulesEngine()

    // ---------------------------------------------------------------------------------------------
    // Bug 1.1: Incoming TRANSFERs to credit card must reduce debt, not add charges
    // ---------------------------------------------------------------------------------------------
    @Test
    fun computeCreditCardMetrics_incomingTransferToCreditCard_reducesDebtLikePayment() {
        val cardId = 100L
        val bankId = 200L
        val refDate = LocalDate.of(2026, 11, 20).toEpochDay()

        // Card had 10,000 INR opening debt
        val openingDebt = MinorMoney(10000_00L)

        // User transfers 6,000 INR from Bank to Credit Card (generic TRANSFER used as bill pay)
        val incomingPaymentTransfer = TransactionItem(
            id = 1L,
            type = TransactionType.TRANSFER,
            timestampEpochMillis = refDate * 86_400_000L,
            ledgerDateEpochDay = refDate,
            amount = MinorMoney(6000_00L),
            currencyCode = "INR",
            accountId = bankId,
            accountName = "Salary Bank",
            transferToAccountId = cardId,
            transferToAccountName = "ICICI Credit Card",
            transferFee = MinorMoney.ZERO
        )

        // User also performs a 2,000 INR cash advance / transfer OUT from card
        val cashAdvanceTransfer = TransactionItem(
            id = 2L,
            type = TransactionType.TRANSFER,
            timestampEpochMillis = refDate * 86_400_000L,
            ledgerDateEpochDay = refDate,
            amount = MinorMoney(2000_00L),
            currencyCode = "INR",
            accountId = cardId,
            accountName = "ICICI Credit Card",
            transferToAccountId = bankId,
            transferToAccountName = "Salary Bank",
            transferFee = MinorMoney(50_00L) // 50 fee
        )

        val metrics = engine.computeCreditCardMetrics(
            cardAccountId = cardId,
            openingBalance = openingDebt,
            transactions = listOf(incomingPaymentTransfer, cashAdvanceTransfer),
            settlementDayOfMonth = 15,
            referenceDateEpochDay = refDate,
            creditLimit = MinorMoney(50000_00L)
        )

        // Initial debt: 10,000.
        // Incoming transfer: -6,000 credit.
        // Outgoing cash advance: +2,050 debt.
        // Expected net debt = 10,000 - 6,000 + 2,050 = 6,050.
        assertEquals(MinorMoney(6050_00L), metrics.outstandingBalance)
        assertEquals(MinorMoney(-6050_00L), metrics.currentBalance)
    }

    // ---------------------------------------------------------------------------------------------
    // Bug 1.2: Credit card surplus balance (+5,000) must not be treated as debt
    // ---------------------------------------------------------------------------------------------
    @Test
    fun computeCreditCardHealthList_surplusCreditBalance_notTreatedAsDebt() {
        val surplusCard = AccountSummary(
            id = 1L,
            name = "Overpaid Credit Card",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "INR",
            currentBalance = MinorMoney(5000_00L), // +5,000 INR credit surplus
            creditLimit = MinorMoney(100000_00L)
        )

        val healthList = CreditCardCockpitCalculator.computeCreditCardHealthList(listOf(surplusCard))
        assertEquals(1, healthList.size)
        val item = healthList.first()

        // Surplus balance must yield 0 debt, full available limit, and 0% utilization
        assertEquals(MinorMoney.ZERO, item.totalDebt)
        assertEquals(MinorMoney(100000_00L), item.availableCredit)
        assertEquals(0f, item.utilizationPercent, 0.001f)

        val cockpit = CreditCardCockpitCalculator.computeCreditCockpitState(
            allAccounts = listOf(surplusCard),
            allTxs = emptyList(),
            periodTransactions = emptyList(),
            trendBars = emptyList(),
            referenceDateEpochDay = LocalDate.of(2026, 11, 11).toEpochDay()
        )
        assertEquals(MinorMoney.ZERO, cockpit.totalCcDebt)
        assertEquals(MinorMoney(100000_00L), cockpit.totalAvailableCredit)
        assertEquals(0f, cockpit.aggregateUtilizationPercent, 0.001f)
    }

    // ---------------------------------------------------------------------------------------------
    // Bug 1.3: Statement cycle ranges in short months (Feb 28, Apr 30) do not drift by 1 day
    // ---------------------------------------------------------------------------------------------
    @Test
    fun computeCreditCardStatementRange_shortMonths_doesNotDrift() {
        // Card with settlement day 28. In Feb 2025 (non-leap, 28 days):
        val refFeb2025 = LocalDate.of(2025, 2, 15).toEpochDay()
        val rangeFeb2025 = PassbookPeriodCalculator.computeCreditCardStatementRange(
            referenceDateEpochDay = refFeb2025,
            settlementDayOfMonth = 28,
            monthOffset = 0
        )
        // Previous close: Jan 28. Start date: Jan 29. End date: Feb 28.
        assertEquals(LocalDate.of(2025, 1, 29).toEpochDay(), rangeFeb2025.startEpochDay)
        assertEquals(LocalDate.of(2025, 2, 28).toEpochDay(), rangeFeb2025.endEpochDay)

        // Card with settlement day 30 in April (30 days):
        val refApr2026 = LocalDate.of(2026, 4, 15).toEpochDay()
        val rangeApr2026 = PassbookPeriodCalculator.computeCreditCardStatementRange(
            referenceDateEpochDay = refApr2026,
            settlementDayOfMonth = 30,
            monthOffset = 0
        )
        // Previous close: March 30. Start date: March 31. End date: April 30.
        assertEquals(LocalDate.of(2026, 3, 31).toEpochDay(), rangeApr2026.startEpochDay)
        assertEquals(LocalDate.of(2026, 4, 30).toEpochDay(), rangeApr2026.endEpochDay)
    }

    // ---------------------------------------------------------------------------------------------
    // Bug 1.4: Leap year February clamping does not collapse grace period to 1 day
    // ---------------------------------------------------------------------------------------------
    @Test
    fun computeDueDateAfterStatementClose_leapYearClamping_doesNotCollapseGracePeriod() {
        // Statement day = 28, Due day = 29.
        // In leap year Feb 2024 (29 days), statement closes Feb 28.
        // Due date must be March 29 (~30 days float), NOT Feb 29 (1 day float!).
        val leapFebCycle = CreditCardCycleCalculator.computeCycleWindow(
            referenceDateEpochDay = LocalDate.of(2024, 2, 10).toEpochDay(),
            settlementDayOfMonth = 28,
            paymentDueDayOfMonth = 29
        )
        assertEquals(LocalDate.of(2024, 2, 28), leapFebCycle.activeCycleEnd)
        assertEquals(LocalDate.of(2024, 3, 29), leapFebCycle.activeCycleNextDueDate)
    }

    // ---------------------------------------------------------------------------------------------
    // Bug 1.5: Sibling debit card spend reconciles with parent bank balance in passbook
    // ---------------------------------------------------------------------------------------------
    @Test
    fun buildAccountPassbookUiModel_siblingDebitCards_openingBalanceReconciles() {
        val parentBank = AccountSummary(
            id = 10L,
            name = "HDFC Savings",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(40000_00L) // Reflects 50,000 - 10,000 spend from Card 2
        )
        val debitCard1 = AccountSummary(
            id = 11L,
            name = "Debit Card 1",
            groupType = AccountGroup.DEBIT_CARD,
            parentAccountId = 10L,
            currencyCode = "INR",
            currentBalance = MinorMoney(40000_00L)
        )
        val debitCard2 = AccountSummary(
            id = 12L,
            name = "Debit Card 2",
            groupType = AccountGroup.DEBIT_CARD,
            parentAccountId = 10L,
            currencyCode = "INR",
            currentBalance = MinorMoney(40000_00L)
        )

        val txSpendCard2 = TransactionItem(
            id = 101L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = LocalDate.of(2026, 11, 5).toEpochDay(),
            amount = MinorMoney(10000_00L),
            currencyCode = "INR",
            accountId = 12L,
            accountName = "Debit Card 2"
        )

        // When inspecting Debit Card 1:
        val passbookCard1 = AccountPassbookCalculator.buildAccountPassbookUiModel(
            inspectedAccount = debitCard1,
            allAccounts = listOf(parentBank, debitCard1, debitCard2),
            allTransactions = listOf(txSpendCard2, txSpendCard2), // Also verifies tx.id dedup
            monthStartDay = 1,
            referenceDateEpochDay = LocalDate.of(2026, 11, 11).toEpochDay(),
            passbookMonthOffset = 0
        )

        // Opening balance for the month (prior to the Nov 5 spend) must reconcile back to 50,000
        assertEquals(50000_00L, passbookCard1.periodOpeningBalance.amountMinor)
        assertEquals(40000_00L, passbookCard1.currentLiveBalance.amountMinor)
    }

    // ---------------------------------------------------------------------------------------------
    // Bug 1.6: Internal liquid transfers not subtracted, CC debt not double-counted, debit shortfalls caught
    // ---------------------------------------------------------------------------------------------
    @Test
    fun computeSafeToSpend_internalLiquidTransfers_notDeductedAsCommitments() {
        val checking = AccountSummary(id = 1L, name = "Checking", groupType = AccountGroup.BANK_ACCOUNT, currencyCode = "INR", currentBalance = MinorMoney(50000_00L))
        val savings = AccountSummary(id = 2L, name = "Savings", groupType = AccountGroup.SAVINGS, currencyCode = "INR", currentBalance = MinorMoney(30000_00L))
        val creditCard = AccountSummary(id = 3L, name = "Credit Card", groupType = AccountGroup.CREDIT_CARD, currencyCode = "INR", currentBalance = MinorMoney(-10000_00L)) // 10,000 debt
        val debitCard = AccountSummary(id = 4L, name = "Debit Card", groupType = AccountGroup.DEBIT_CARD, parentAccountId = 1L, currencyCode = "INR", currentBalance = MinorMoney(50000_00L))

        val today = LocalDate.of(2026, 11, 11).toEpochDay()

        // 1. Internal transfer from Checking to Savings: must NOT reduce safe to spend!
        val internalTransferRule = RecurringCommitmentItem(
            id = 1L,
            title = "Save money",
            amount = MinorMoney(15000_00L),
            currencyCode = "INR",
            type = TransactionType.TRANSFER,
            frequency = RepeatFrequency.MONTHLY,
            accountId = 1L,
            accountName = "Checking",
            transferToAccountId = 2L,
            transferToAccountName = "Savings",
            nextOccurrenceEpochDay = today + 3L,
            isActive = true
        )

        // 2. Transfer from Checking to Credit Card: CC debt is already deducted in step 2; must NOT be double-counted!
        val ccPayTransferRule = RecurringCommitmentItem(
            id = 2L,
            title = "Pay CC",
            amount = MinorMoney(10000_00L),
            currencyCode = "INR",
            type = TransactionType.TRANSFER,
            frequency = RepeatFrequency.MONTHLY,
            accountId = 1L,
            accountName = "Checking",
            transferToAccountId = 3L,
            transferToAccountName = "Credit Card",
            nextOccurrenceEpochDay = today + 5L,
            isActive = true
        )

        // 3. Genuine external expense on Debit Card (should be attributed to parent Checking account):
        val externalDebitCardRule = RecurringCommitmentItem(
            id = 3L,
            title = "Gym Membership",
            amount = MinorMoney(60000_00L), // Exceeds 50,000 checking balance -> triggers shortfall!
            currencyCode = "INR",
            type = TransactionType.EXPENSE,
            frequency = RepeatFrequency.MONTHLY,
            accountId = 4L,
            accountName = "Debit Card",
            nextOccurrenceEpochDay = today + 2L,
            isActive = true
        )

        val state = SafeToSpendCalculator.computeSafeToSpend(
            allAccounts = listOf(checking, savings, creditCard, debitCard),
            recurringRules = listOf(internalTransferRule, ccPayTransferRule, externalDebitCardRule),
            futureTransactions = emptyList(),
            todayEpochDay = today,
            primaryCurrencyCode = "INR",
            periodStartEpochDay = today,
            periodEndEpochDay = today + 30L
        )

        // Liquid = 80,000 (Checking 50k + Savings 30k).
        // CC Debt = 10,000.
        // Upcoming external commitment = 60,000 (Gym). Internal transfers are ignored.
        // Safe to spend = 80,000 - 10,000 - 60,000 = 10,000!
        assertEquals(MinorMoney(80000_00L), state.totalLiquidBankAndCash)
        assertEquals(MinorMoney(10000_00L), state.totalCreditCardDebt)
        assertEquals(MinorMoney(60000_00L), state.upcoming14DayCommitments)
        assertEquals(MinorMoney(10000_00L), state.trueSafeToSpend)

        // Shortfall alert must identify the parent checking account for the debit card bill:
        val shortfalls = SafeToSpendCalculator.computeShortfallAlerts(
            allAccounts = listOf(checking, savings, creditCard, debitCard),
            recurringRules = listOf(externalDebitCardRule),
            futureTransactions = emptyList(),
            todayEpochDay = today,
            primaryCurrencyCode = "INR"
        )
        assertEquals(1, shortfalls.size)
        val alert = shortfalls.first()
        assertEquals(1L, alert.accountId) // Rolled up to Checking
        assertEquals(MinorMoney(50000_00L), alert.currentBalance)
        assertEquals(MinorMoney(60000_00L), alert.upcomingDebits)
        assertEquals(MinorMoney(10000_00L), alert.shortfallAmount)
    }

    // ---------------------------------------------------------------------------------------------
    // Bug 1.7: Deterministic BigDecimal Rounding in MoneyFormatterAndCalculator.convertMinorMoney
    // ---------------------------------------------------------------------------------------------
    @Test
    fun convertMinorMoney_highPrecisionCurrencyConversions_deterministicHalfUp() {
        val source = 10050L // 100.50
        val rate = 1.055
        val converted = MoneyFormatterAndCalculator.convertMinorMoney(
            sourceMinor = source,
            sourceFractionDigits = 2,
            targetFractionDigits = 2,
            rateToTarget = rate
        )
        // 10050 * 1.055 = 10602.75 -> half-up rounds to 10603
        assertEquals(10603L, converted)
    }
}
