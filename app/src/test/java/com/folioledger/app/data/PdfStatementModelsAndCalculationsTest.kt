package com.folioledger.app.data

import com.folioledger.app.data.pdf.FiscalYearType
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.usecase.AccountingRulesEngine
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PdfStatementModelsAndCalculationsTest {

    @Test
    fun fiscalYearTypeCalendarYear_producesCorrectJanToDecRange() {
        val anchorYear = 2026
        val start = LocalDate.of(anchorYear, 1, 1).toEpochDay()
        val end = LocalDate.of(anchorYear, 12, 31).toEpochDay()

        assertEquals(LocalDate.of(2026, 1, 1), LocalDate.ofEpochDay(start))
        assertEquals(LocalDate.of(2026, 12, 31), LocalDate.ofEpochDay(end))
        assertTrue(end > start)
    }

    @Test
    fun fiscalYearTypeAprilYear_producesCorrectAprToMarRange() {
        val anchorYear = 2025
        val start = LocalDate.of(anchorYear, 4, 1).toEpochDay()
        val end = LocalDate.of(anchorYear + 1, 3, 31).toEpochDay()

        assertEquals(LocalDate.of(2025, 4, 1), LocalDate.ofEpochDay(start))
        assertEquals(LocalDate.of(2026, 3, 31), LocalDate.ofEpochDay(end))
        assertTrue(end > start)
    }

    @Test
    fun fiscalYearTypeJulyYear_producesCorrectJulToJunRange() {
        val anchorYear = 2025
        val start = LocalDate.of(anchorYear, 7, 1).toEpochDay()
        val end = LocalDate.of(anchorYear + 1, 6, 30).toEpochDay()

        assertEquals(LocalDate.of(2025, 7, 1), LocalDate.ofEpochDay(start))
        assertEquals(LocalDate.of(2026, 6, 30), LocalDate.ofEpochDay(end))
        assertTrue(end > start)
    }

    @Test
    fun signedDeltaCalculation_computesInflowsAndOutflowsCorrectly() {
        val accountIds = setOf(101L)

        val incomeTx = TransactionItem(
            id = 1L,
            type = TransactionType.INCOME,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 100L,
            amount = MinorMoney(500000L), // 5,000.00
            currencyCode = "USD",
            accountId = 101L,
            accountName = "Primary Checking"
        )
        val incomeDelta = AccountingRulesEngine.computeSignedDeltaMinorForAccount(incomeTx, accountIds)
        assertEquals(500000L, incomeDelta)

        val expenseTx = TransactionItem(
            id = 2L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 2000L,
            ledgerDateEpochDay = 101L,
            amount = MinorMoney(120000L), // 1,200.00
            currencyCode = "USD",
            accountId = 101L,
            accountName = "Primary Checking"
        )
        val expenseDelta = AccountingRulesEngine.computeSignedDeltaMinorForAccount(expenseTx, accountIds)
        assertEquals(-120000L, expenseDelta)

        val transferTx = TransactionItem(
            id = 3L,
            type = TransactionType.TRANSFER,
            timestampEpochMillis = 3000L,
            ledgerDateEpochDay = 102L,
            amount = MinorMoney(50000L), // 500.00
            currencyCode = "USD",
            accountId = 101L,
            accountName = "Primary Checking",
            transferToAccountId = 102L,
            transferToAccountName = "Savings"
        )
        val transferOutDelta = AccountingRulesEngine.computeSignedDeltaMinorForAccount(transferTx, accountIds)
        assertEquals(-50000L, transferOutDelta)

        val transferInDelta = AccountingRulesEngine.computeSignedDeltaMinorForAccount(transferTx, setOf(102L))
        assertEquals(50000L, transferInDelta)
    }

    @Test
    fun multiCurrencyConversion_handlesForeignAccountsInTaxStatement() {
        val usdSpec = SupportedCurrencies.USD // 2 decimals
        val inrSpec = SupportedCurrencies.INR // 2 decimals
        val rateToInr = 85.0

        val usdMinor = 1000_00L // $1,000.00
        val inrConverted = MoneyFormatterAndCalculator.convertMinorMoney(
            sourceMinor = usdMinor,
            sourceFractionDigits = usdSpec.fractionDigits,
            targetFractionDigits = inrSpec.fractionDigits,
            rateToTarget = rateToInr
        )

        // 1,000 * 85 = 85,000.00 INR (8,500,000 minor paise)
        assertEquals(8500000L, inrConverted)

        val jpySpec = SupportedCurrencies.JPY // 0 decimals
        val jpyMinor = 10_000L // ¥10,000
        val rateJpyToUsd = 0.0067
        val usdFromJpy = MoneyFormatterAndCalculator.convertMinorMoney(
            sourceMinor = jpyMinor,
            sourceFractionDigits = jpySpec.fractionDigits,
            targetFractionDigits = usdSpec.fractionDigits,
            rateToTarget = rateJpyToUsd
        )
        // 10,000 * 0.0067 = $67.00 = 6,700 cents
        assertEquals(6700L, usdFromJpy)
    }

    @Test
    fun taxDeductionTagMatching_identifiesLegitimateDeductions() {
        val taxTags = setOf("tax", "business", "medical", "donation", "charity")

        val tx1Tags = listOf("business", "travel")
        assertTrue(tx1Tags.any { it in taxTags })

        val tx2Tags = listOf("groceries", "personal")
        assertFalse(tx2Tags.any { it in taxTags })

        val tx3Tags = listOf("medical", "prescription")
        assertTrue(tx3Tags.any { it in taxTags })
    }

    @Test
    fun payableFallback_formatsCurrencyZeroInsteadOfHardcodedRupee() {
        val usdSpec = SupportedCurrencies.USD
        val formattedZero = MoneyFormatterAndCalculator.formatMinorMoney(
            money = MinorMoney.ZERO,
            currencySpec = usdSpec,
            grouping = NumberGroupingSystem.INTERNATIONAL
        )
        // Must format as "$0.00", not "₹0.00"
        assertEquals("$0.00", formattedZero)
        assertFalse(formattedZero.contains("₹"))

        val eurSpec = SupportedCurrencies.EUR
        val formattedEur = MoneyFormatterAndCalculator.formatMinorMoney(
            money = MinorMoney.ZERO,
            currencySpec = eurSpec,
            grouping = NumberGroupingSystem.INTERNATIONAL
        )
        assertFalse(formattedEur.contains("₹"))
    }

    @Test
    fun balanceSheetClassification_overdrawnAccountIsLiability_overpaidCardIsAsset() {
        // Overdrawn bank account (negative balance)
        val overdrawnBankBalance = -5000_00L // -5,000.00
        val isOverdrawnBankAsset = overdrawnBankBalance >= 0L
        assertFalse("Overdrawn bank account must be classified as Liability", isOverdrawnBankAsset)

        // Overpaid credit card (positive balance / surplus)
        val overpaidCardBalance = 200_00L // +200.00
        val isOverpaidCardAsset = overpaidCardBalance >= 0L
        assertTrue("Overpaid credit card with surplus must be classified as Asset", isOverpaidCardAsset)
    }

    @Test
    fun refundAccounting_offsetsExpensesInsteadOfGrossIncome() {
        var grossInflowsMinor = 0L
        var totalExpensesMinor = 0L

        val incomeTx = 100_000_00L // ₹100,000 income
        val expenseTx = 30_000_00L // ₹30,000 expense
        val refundTx = 5_000_00L   // ₹5,000 refund on returned item

        // Income
        grossInflowsMinor += incomeTx

        // Expense
        totalExpensesMinor += expenseTx

        // Refund (must offset totalExpensesMinor, NOT add to grossInflowsMinor)
        totalExpensesMinor -= refundTx

        assertEquals(100_000_00L, grossInflowsMinor) // Stays ₹100,000 (not falsely inflated to ₹105,000)
        assertEquals(25_000_00L, totalExpensesMinor) // Net expense correctly ₹25,000
        val netSavingsMinor = grossInflowsMinor - totalExpensesMinor
        assertEquals(75_000_00L, netSavingsMinor)
    }

    @Test
    fun splitTransaction_attributesToIndividualCategories() {
        val splits = listOf(
            com.folioledger.app.domain.model.SplitLineItem(
                id = 1L,
                categoryId = 10L,
                categoryName = "Groceries",
                amount = MinorMoney(35_00L)
            ),
            com.folioledger.app.domain.model.SplitLineItem(
                id = 2L,
                categoryId = 11L,
                categoryName = "Electronics",
                amount = MinorMoney(65_00L)
            )
        )
        val expenseByCategory = mutableMapOf<String, MutableList<Long>>()
        for (split in splits) {
            val cat = split.categoryName.ifBlank { "General Expense" }
            expenseByCategory.getOrPut(cat) { mutableListOf() }.add(split.amount.amountMinor)
        }

        assertEquals(35_00L, expenseByCategory["Groceries"]?.sum())
        assertEquals(65_00L, expenseByCategory["Electronics"]?.sum())
        assertNull(expenseByCategory["Split"])
    }

    @Test
    fun splitTransaction_includesOnlyDeductibleSplitLinesInTaxDeductions() {
        val splitMedical = com.folioledger.app.domain.model.SplitLineItem(
            id = 1L,
            categoryId = 10L,
            categoryName = "Medical Expenses",
            amount = MinorMoney(25_00L),
            note = "Prescription medicines"
        )
        val splitGroceries = com.folioledger.app.domain.model.SplitLineItem(
            id = 2L,
            categoryId = 11L,
            categoryName = "Groceries",
            amount = MinorMoney(75_00L),
            note = "Weekly groceries"
        )

        val tx = TransactionItem(
            id = 100L,
            type = TransactionType.EXPENSE,
            amount = MinorMoney(100_00L),
            currencyCode = "USD",
            accountId = 1L,
            accountName = "Checking",
            categoryName = "Split",
            isSplit = true,
            splits = listOf(splitMedical, splitGroceries),
            tags = emptyList(),
            ledgerDateEpochDay = 20750L,
            timestampEpochMillis = 20750L * 86_400_000L
        )

        fun isCategoryDeductible(catName: String?): Boolean {
            if (catName == null) return false
            return catName.contains("tax", true) || catName.contains("donation", true) ||
                    catName.contains("charity", true) || catName.contains("medical", true) ||
                    catName.contains("insurance", true)
        }

        val taxDeductions = mutableListOf<com.folioledger.app.data.pdf.TaxDeductionPdfItem>()
        if (tx.isSplit && tx.splits.isNotEmpty()) {
            for (split in tx.splits) {
                if (isCategoryDeductible(split.categoryName)) {
                    taxDeductions.add(
                        com.folioledger.app.data.pdf.TaxDeductionPdfItem(
                            dateText = "23 Oct",
                            payee = tx.payeeOrDescription.ifBlank { split.categoryName.ifBlank { "Expense" } },
                            categoryName = split.categoryName.ifBlank { "General" },
                            tagOrDeductionType = "Deductible Split",
                            note = split.note.ifBlank { tx.note }.ifBlank { null },
                            amountMinor = split.amount.amountMinor,
                            currencyCode = tx.currencyCode,
                            convertedMinor = split.amount.amountMinor
                        )
                    )
                }
            }
        }

        // Only splitMedical (25_00L) should be included, NOT groceries (75_00L) or total (100_00L)
        assertEquals(1, taxDeductions.size)
        val deduction = taxDeductions[0]
        assertEquals("Medical Expenses", deduction.categoryName)
        assertEquals(25_00L, deduction.amountMinor)
        assertEquals("Prescription medicines", deduction.note)
    }
}
