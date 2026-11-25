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

import com.folioledger.app.data.local.dao.PayeeSuggestionProjection
import com.folioledger.app.data.repository.TransactionRepositoryImpl
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.CategoryNode
import com.folioledger.app.domain.model.CategoryType
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.PayeeAutofillTemplate
import com.folioledger.app.domain.model.PayeeSuggestion
import com.folioledger.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PayeeSuggestionAndAutofillTestSuite {

    // ==========================================
    // 1. PayeeSuggestionEngine Tests
    // ==========================================

    private val sampleCatalog = listOf(
        PayeeSuggestion(
            latestTransactionId = 1L,
            displayName = "Starbucks",
            normalizedKey = "starbucks",
            usageCount = 20,
            lastUsedEpochMillis = 1000L,
            lastType = TransactionType.EXPENSE,
            lastAccountId = 1L,
            lastTransferToAccountId = null,
            lastCategoryId = 10L,
            lastIsSplit = false
        ),
        PayeeSuggestion(
            latestTransactionId = 2L,
            displayName = "Blue Tokai Coffee",
            normalizedKey = "blue tokai coffee",
            usageCount = 5,
            lastUsedEpochMillis = 2000L,
            lastType = TransactionType.EXPENSE,
            lastAccountId = 1L,
            lastTransferToAccountId = null,
            lastCategoryId = 10L,
            lastIsSplit = false
        ),
        PayeeSuggestion(
            latestTransactionId = 3L,
            displayName = "Steam Games",
            normalizedKey = "steam games",
            usageCount = 15,
            lastUsedEpochMillis = 1500L,
            lastType = TransactionType.EXPENSE,
            lastAccountId = 2L,
            lastTransferToAccountId = null,
            lastCategoryId = 20L,
            lastIsSplit = false
        ),
        PayeeSuggestion(
            latestTransactionId = 4L,
            displayName = "Acme Salary",
            normalizedKey = "acme salary",
            usageCount = 12,
            lastUsedEpochMillis = 1800L,
            lastType = TransactionType.INCOME,
            lastAccountId = 1L,
            lastTransferToAccountId = null,
            lastCategoryId = 30L,
            lastIsSplit = false
        ),
        PayeeSuggestion(
            latestTransactionId = 5L,
            displayName = "Checking \u2192 Savings",
            normalizedKey = "checking \u2192 savings",
            usageCount = 50,
            lastUsedEpochMillis = 3000L,
            lastType = TransactionType.TRANSFER,
            lastAccountId = 1L,
            lastTransferToAccountId = 2L,
            lastCategoryId = null,
            lastIsSplit = false
        )
    )

    @Test
    fun engine_emptyQuery_returnsRecentSuggestions_excludingSyntheticTransfers() {
        val results = PayeeSuggestionEngine.rankSuggestions(
            query = "",
            currentMode = TransactionType.EXPENSE,
            catalog = sampleCatalog,
            maxResults = 5
        )

        // Must exclude "Checking \u2192 Savings"
        assertTrue(results.none { it.displayName.contains(" \u2192 ") })

        // EXPENSE payees should be ranked first
        assertEquals("Blue Tokai Coffee", results[0].displayName)
        assertEquals("Steam Games", results[1].displayName)
        assertEquals("Starbucks", results[2].displayName)
        // Followed by other modes if space allows
        assertEquals("Acme Salary", results[3].displayName)
    }

    @Test
    fun engine_prefixMatch_prioritizedOverWordBoundaryAndSubstring() {
        val results = PayeeSuggestionEngine.rankSuggestions(
            query = "St",
            currentMode = TransactionType.EXPENSE,
            catalog = sampleCatalog,
            maxResults = 5
        )

        // "Starbucks" (lastUsed 1000, usage 20) and "Steam Games" (lastUsed 1500, usage 15)
        assertEquals(2, results.size)
        // Both are prefix matches, tie-broken by lastUsedEpochMillis DESC
        assertEquals("Steam Games", results[0].displayName)
        assertEquals("Starbucks", results[1].displayName)
    }

    @Test
    fun engine_wordBoundaryMatch_worksCorrectly() {
        val results = PayeeSuggestionEngine.rankSuggestions(
            query = "coffee",
            currentMode = TransactionType.EXPENSE,
            catalog = sampleCatalog,
            maxResults = 5
        )

        assertEquals(1, results.size)
        assertEquals("Blue Tokai Coffee", results[0].displayName)
    }

    @Test
    fun engine_caseInsensitive_matching() {
        val resultsUpper = PayeeSuggestionEngine.rankSuggestions(
            query = "STAR",
            currentMode = TransactionType.EXPENSE,
            catalog = sampleCatalog,
            maxResults = 5
        )
        assertEquals(1, resultsUpper.size)
        assertEquals("Starbucks", resultsUpper[0].displayName)
    }

    // ==========================================
    // 2. PayeeAutofillResolver Tests
    // ==========================================

    private val activeAccounts = listOf(
        AccountSummary(
            id = 1L,
            name = "Chase Checking",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "USD",
            currentBalance = MinorMoney(100000L)
        ),
        AccountSummary(
            id = 2L,
            name = "Amex Gold",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "USD",
            currentBalance = MinorMoney(-50000L)
        ),
        AccountSummary(
            id = 3L,
            name = "India HDFC",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            currentBalance = MinorMoney(500000L)
        )
    )

    private val diningCategory = CategoryNode(
        id = 10L,
        name = "Dining Out",
        type = CategoryType.EXPENSE,
        parentId = null,
        iconName = "restaurant",
        colorHex = "#E11D48",
        subcategories = listOf(
            CategoryNode(
                id = 11L,
                name = "Coffee & Cafes",
                type = CategoryType.EXPENSE,
                parentId = 10L,
                iconName = "local_cafe",
                colorHex = "#EA580C"
            )
        )
    )

    private val salaryCategory = CategoryNode(
        id = 30L,
        name = "Salary",
        type = CategoryType.INCOME,
        parentId = null,
        iconName = "payments",
        colorHex = "#059669"
    )

    @Test
    fun resolver_editMode_onlyUpdatesPayeeName() {
        val template = PayeeAutofillTemplate(
            payeeName = "Starbucks Reserve",
            type = TransactionType.EXPENSE,
            accountId = 2L,
            transferToAccountId = null,
            categoryId = 11L,
            isSplit = false,
            tags = listOf("coffee", "work")
        )

        val resolution = PayeeAutofillResolver.resolve(
            template = template,
            isEditMode = true,
            currentMode = TransactionType.EXPENSE,
            currentAccountId = 1L,
            currentTransferToAccountId = null,
            currentCategoryId = 99L,
            currentCategoryName = "Old Category",
            currentParentCategoryName = null,
            currentCategoryColorHex = "#FFFFFF",
            currentCategoryIconName = "help",
            currentTags = listOf("old_tag"),
            isSplit = false,
            isAccountManuallySet = false,
            isDestinationManuallySet = false,
            isCategoryManuallySet = false,
            isTagsManuallySet = false,
            isModeManuallySet = false,
            activeAccounts = activeAccounts,
            activeExpenseCategories = listOf(diningCategory),
            activeIncomeCategories = listOf(salaryCategory),
            activeTags = listOf("coffee", "work", "old_tag"),
            subcategoriesEnabled = true,
            evaluatedAmount = MinorMoney(500L)
        )

        assertEquals("Starbucks Reserve", resolution.updatedPayee)
        // All metadata must remain strictly unchanged in edit mode
        assertEquals(1L, resolution.updatedAccountId)
        assertEquals(99L, resolution.updatedCategoryId)
        assertEquals("Old Category", resolution.updatedCategoryName)
        assertEquals(listOf("old_tag"), resolution.updatedTags)
        assertFalse(resolution.didApplyAnyMetadataChange)
        assertNull(resolution.bannerText)
    }

    @Test
    fun resolver_newMode_autofillsMetadataAndRespectsManualTouchFlags() {
        val template = PayeeAutofillTemplate(
            payeeName = "Starbucks",
            type = TransactionType.EXPENSE,
            accountId = 2L, // Amex Gold
            transferToAccountId = null,
            categoryId = 11L, // Coffee & Cafes
            isSplit = false,
            tags = listOf("coffee")
        )

        // Case A: Nothing manually set -> All metadata auto-populates
        val resA = PayeeAutofillResolver.resolve(
            template = template,
            isEditMode = false,
            currentMode = TransactionType.EXPENSE,
            currentAccountId = 1L,
            currentTransferToAccountId = null,
            currentCategoryId = null,
            currentCategoryName = null,
            currentParentCategoryName = null,
            currentCategoryColorHex = null,
            currentCategoryIconName = null,
            currentTags = emptyList(),
            isSplit = false,
            isAccountManuallySet = false,
            isDestinationManuallySet = false,
            isCategoryManuallySet = false,
            isTagsManuallySet = false,
            isModeManuallySet = false,
            activeAccounts = activeAccounts,
            activeExpenseCategories = listOf(diningCategory),
            activeIncomeCategories = listOf(salaryCategory),
            activeTags = listOf("coffee"),
            subcategoriesEnabled = true,
            evaluatedAmount = MinorMoney.ZERO
        )

        assertEquals("Starbucks", resA.updatedPayee)
        assertEquals(2L, resA.updatedAccountId)
        assertEquals(11L, resA.updatedCategoryId)
        assertEquals("Coffee & Cafes", resA.updatedCategoryName)
        assertEquals("Dining Out", resA.updatedParentCategoryName)
        assertEquals(listOf("coffee"), resA.updatedTags)
        assertTrue(resA.didApplyAnyMetadataChange)
        assertNotNull(resA.bannerText)

        // Case B: User already manually set Category -> Category must NOT be overwritten
        val resB = PayeeAutofillResolver.resolve(
            template = template,
            isEditMode = false,
            currentMode = TransactionType.EXPENSE,
            currentAccountId = 1L,
            currentTransferToAccountId = null,
            currentCategoryId = 10L,
            currentCategoryName = "Dining Out",
            currentParentCategoryName = null,
            currentCategoryColorHex = "#E11D48",
            currentCategoryIconName = "restaurant",
            currentTags = emptyList(),
            isSplit = false,
            isAccountManuallySet = false,
            isDestinationManuallySet = false,
            isCategoryManuallySet = true, // User picked this manually!
            isTagsManuallySet = false,
            isModeManuallySet = false,
            activeAccounts = activeAccounts,
            activeExpenseCategories = listOf(diningCategory),
            activeIncomeCategories = listOf(salaryCategory),
            activeTags = listOf("coffee"),
            subcategoriesEnabled = true,
            evaluatedAmount = MinorMoney.ZERO
        )

        assertEquals("Dining Out", resB.updatedCategoryName)
        assertEquals(10L, resB.updatedCategoryId)
        // Account can still update since isAccountManuallySet was false
        assertEquals(2L, resB.updatedAccountId)
    }

    @Test
    fun resolver_crossCurrencyGuard_blocksAccountSwitchWhenAmountTyped() {
        val template = PayeeAutofillTemplate(
            payeeName = "Indian Groceries",
            type = TransactionType.EXPENSE,
            accountId = 3L, // HDFC Bank (INR)
            transferToAccountId = null,
            categoryId = 10L,
            isSplit = false,
            tags = emptyList()
        )

        // User currently has Account 1 (USD) and has already entered $50.00 (evaluatedAmount = 5000L)
        val resolution = PayeeAutofillResolver.resolve(
            template = template,
            isEditMode = false,
            currentMode = TransactionType.EXPENSE,
            currentAccountId = 1L, // USD
            currentTransferToAccountId = null,
            currentCategoryId = null,
            currentCategoryName = null,
            currentParentCategoryName = null,
            currentCategoryColorHex = null,
            currentCategoryIconName = null,
            currentTags = emptyList(),
            isSplit = false,
            isAccountManuallySet = false,
            isDestinationManuallySet = false,
            isCategoryManuallySet = false,
            isTagsManuallySet = false,
            isModeManuallySet = false,
            activeAccounts = activeAccounts,
            activeExpenseCategories = listOf(diningCategory),
            activeIncomeCategories = listOf(salaryCategory),
            activeTags = emptyList(),
            subcategoriesEnabled = true,
            evaluatedAmount = MinorMoney(5000L) // Amount > 0!
        )

        // Must NOT switch from USD (Account 1) to INR (Account 3)!
        assertEquals(1L, resolution.updatedAccountId)
    }

    @Test
    fun resolver_crossCurrencyGuard_blocksAccountSwitchWhenCurrentAccountNullAndAmountTyped() {
        val template = PayeeAutofillTemplate(
            payeeName = "Indian Groceries",
            type = TransactionType.EXPENSE,
            accountId = 3L, // HDFC Bank (INR)
            transferToAccountId = null,
            categoryId = 10L,
            isSplit = false,
            tags = emptyList()
        )

        // On fresh sheet open: currentAccountId == null (activeAccounts.firstOrNull() is Account 1 in USD)
        // User types $50.00 first (evaluatedAmount = 5000L)
        val resolution = PayeeAutofillResolver.resolve(
            template = template,
            isEditMode = false,
            currentMode = TransactionType.EXPENSE,
            currentAccountId = null, // null on fresh sheet!
            currentTransferToAccountId = null,
            currentCategoryId = null,
            currentCategoryName = null,
            currentParentCategoryName = null,
            currentCategoryColorHex = null,
            currentCategoryIconName = null,
            currentTags = emptyList(),
            isSplit = false,
            isAccountManuallySet = false,
            isDestinationManuallySet = false,
            isCategoryManuallySet = false,
            isTagsManuallySet = false,
            isModeManuallySet = false,
            activeAccounts = activeAccounts,
            activeExpenseCategories = listOf(diningCategory),
            activeIncomeCategories = listOf(salaryCategory),
            activeTags = emptyList(),
            subcategoriesEnabled = true,
            evaluatedAmount = MinorMoney(5000L) // Amount > 0!
        )

        // Must NOT switch to INR (Account 3L); updatedAccountId remains null (default account preserved)
        assertNull(resolution.updatedAccountId)
    }

    @Test
    fun resolver_currentAccountNull_matchingDefaultAccountDoesNotTriggerSpuriousBanner() {
        val template = PayeeAutofillTemplate(
            payeeName = "Starbucks",
            type = TransactionType.EXPENSE,
            accountId = 1L, // Chase Checking (Account 1, which is activeAccounts.firstOrNull())
            transferToAccountId = null,
            categoryId = 11L, // Coffee & Cafes
            isSplit = false,
            tags = emptyList()
        )

        val resolution = PayeeAutofillResolver.resolve(
            template = template,
            isEditMode = false,
            currentMode = TransactionType.EXPENSE,
            currentAccountId = null, // null on fresh sheet
            currentTransferToAccountId = null,
            currentCategoryId = null,
            currentCategoryName = null,
            currentParentCategoryName = null,
            currentCategoryColorHex = null,
            currentCategoryIconName = null,
            currentTags = emptyList(),
            isSplit = false,
            isAccountManuallySet = false,
            isDestinationManuallySet = false,
            isCategoryManuallySet = false,
            isTagsManuallySet = false,
            isModeManuallySet = false,
            activeAccounts = activeAccounts,
            activeExpenseCategories = listOf(diningCategory),
            activeIncomeCategories = listOf(salaryCategory),
            activeTags = emptyList(),
            subcategoriesEnabled = true,
            evaluatedAmount = MinorMoney.ZERO
        )

        // Because Account 1 is already the default displayed account, banner text should only mention Category
        // and NOT append " • Chase Checking"
        assertEquals("Auto-filled: Coffee & Cafes", resolution.bannerText)
    }

    @Test
    fun resolver_subcategoriesDisabled_promotesToParentCategory() {
        val template = PayeeAutofillTemplate(
            payeeName = "Starbucks",
            type = TransactionType.EXPENSE,
            accountId = 1L,
            transferToAccountId = null,
            categoryId = 11L, // Child category: Coffee & Cafes
            isSplit = false,
            tags = emptyList()
        )

        val resolution = PayeeAutofillResolver.resolve(
            template = template,
            isEditMode = false,
            currentMode = TransactionType.EXPENSE,
            currentAccountId = 1L,
            currentTransferToAccountId = null,
            currentCategoryId = null,
            currentCategoryName = null,
            currentParentCategoryName = null,
            currentCategoryColorHex = null,
            currentCategoryIconName = null,
            currentTags = emptyList(),
            isSplit = false,
            isAccountManuallySet = false,
            isDestinationManuallySet = false,
            isCategoryManuallySet = false,
            isTagsManuallySet = false,
            isModeManuallySet = false,
            activeAccounts = activeAccounts,
            activeExpenseCategories = listOf(diningCategory),
            activeIncomeCategories = listOf(salaryCategory),
            activeTags = emptyList(),
            subcategoriesEnabled = false, // Subcategories disabled!
            evaluatedAmount = MinorMoney.ZERO
        )

        // Must promote from child (11L, Coffee & Cafes) to parent (10L, Dining Out)
        assertEquals(10L, resolution.updatedCategoryId)
        assertEquals("Dining Out", resolution.updatedCategoryName)
        assertNull(resolution.updatedParentCategoryName)
    }

    @Test
    fun resolver_deletedCategory_gracefullyHandled() {
        val template = PayeeAutofillTemplate(
            payeeName = "Defunct Store",
            type = TransactionType.EXPENSE,
            accountId = 1L,
            transferToAccountId = null,
            categoryId = 9999L, // Does not exist in active categories
            isSplit = false,
            tags = emptyList()
        )

        // Case A: Category was NOT manually set -> Cleared to null (not leaked)
        val resA = PayeeAutofillResolver.resolve(
            template = template,
            isEditMode = false,
            currentMode = TransactionType.EXPENSE,
            currentAccountId = 1L,
            currentTransferToAccountId = null,
            currentCategoryId = 10L,
            currentCategoryName = "Dining Out",
            currentParentCategoryName = null,
            currentCategoryColorHex = "#E11D48",
            currentCategoryIconName = "restaurant",
            currentTags = emptyList(),
            isSplit = false,
            isAccountManuallySet = false,
            isDestinationManuallySet = false,
            isCategoryManuallySet = false,
            isTagsManuallySet = false,
            isModeManuallySet = false,
            activeAccounts = activeAccounts,
            activeExpenseCategories = listOf(diningCategory),
            activeIncomeCategories = listOf(salaryCategory),
            activeTags = emptyList(),
            subcategoriesEnabled = true,
            evaluatedAmount = MinorMoney.ZERO
        )

        assertNull(resA.updatedCategoryId)
        assertNull(resA.updatedCategoryName)

        // Case B: Category WAS manually set -> Preserved
        val resB = PayeeAutofillResolver.resolve(
            template = template,
            isEditMode = false,
            currentMode = TransactionType.EXPENSE,
            currentAccountId = 1L,
            currentTransferToAccountId = null,
            currentCategoryId = 10L,
            currentCategoryName = "Dining Out",
            currentParentCategoryName = null,
            currentCategoryColorHex = "#E11D48",
            currentCategoryIconName = "restaurant",
            currentTags = emptyList(),
            isSplit = false,
            isAccountManuallySet = false,
            isDestinationManuallySet = false,
            isCategoryManuallySet = true,
            isTagsManuallySet = false,
            isModeManuallySet = false,
            activeAccounts = activeAccounts,
            activeExpenseCategories = listOf(diningCategory),
            activeIncomeCategories = listOf(salaryCategory),
            activeTags = emptyList(),
            subcategoriesEnabled = true,
            evaluatedAmount = MinorMoney.ZERO
        )

        assertEquals(10L, resB.updatedCategoryId)
        assertEquals("Dining Out", resB.updatedCategoryName)
    }

    @Test
    fun resolver_consecutiveAutofill_clearsStaleCategoryAndTags() {
        // Step 1: User autofilled Payee A ("Starbucks")
        val templateA = PayeeAutofillTemplate(
            payeeName = "Starbucks",
            type = TransactionType.EXPENSE,
            accountId = 2L,
            transferToAccountId = null,
            categoryId = 11L, // Coffee & Cafes
            isSplit = false,
            tags = listOf("coffee")
        )
        val resA = PayeeAutofillResolver.resolve(
            template = templateA,
            isEditMode = false,
            currentMode = TransactionType.EXPENSE,
            currentAccountId = 1L,
            currentTransferToAccountId = null,
            currentCategoryId = null,
            currentCategoryName = null,
            currentParentCategoryName = null,
            currentCategoryColorHex = null,
            currentCategoryIconName = null,
            currentTags = emptyList(),
            isSplit = false,
            isAccountManuallySet = false,
            isDestinationManuallySet = false,
            isCategoryManuallySet = false,
            isTagsManuallySet = false,
            isModeManuallySet = false,
            activeAccounts = activeAccounts,
            activeExpenseCategories = listOf(diningCategory),
            activeIncomeCategories = listOf(salaryCategory),
            activeTags = listOf("coffee"),
            subcategoriesEnabled = true,
            evaluatedAmount = MinorMoney.ZERO
        )
        assertEquals(11L, resA.updatedCategoryId)
        assertEquals(listOf("coffee"), resA.updatedTags)

        // Step 2: User immediately taps Payee B ("Local Flea Market")
        // Payee B has no category (split/null) and no tags
        val templateB = PayeeAutofillTemplate(
            payeeName = "Local Flea Market",
            type = TransactionType.EXPENSE,
            accountId = 1L,
            transferToAccountId = null,
            categoryId = null,
            isSplit = false,
            tags = emptyList()
        )
        val resB = PayeeAutofillResolver.resolve(
            template = templateB,
            isEditMode = false,
            currentMode = resA.updatedMode,
            currentAccountId = resA.updatedAccountId,
            currentTransferToAccountId = resA.updatedTransferToAccountId,
            currentCategoryId = resA.updatedCategoryId,
            currentCategoryName = resA.updatedCategoryName,
            currentParentCategoryName = resA.updatedParentCategoryName,
            currentCategoryColorHex = resA.updatedCategoryColorHex,
            currentCategoryIconName = resA.updatedCategoryIconName,
            currentTags = resA.updatedTags,
            isSplit = false,
            isAccountManuallySet = false,
            isDestinationManuallySet = false,
            isCategoryManuallySet = false,
            isTagsManuallySet = false,
            isModeManuallySet = false,
            activeAccounts = activeAccounts,
            activeExpenseCategories = listOf(diningCategory),
            activeIncomeCategories = listOf(salaryCategory),
            activeTags = listOf("coffee"),
            subcategoriesEnabled = true,
            evaluatedAmount = MinorMoney.ZERO
        )

        // Stale category "Coffee & Cafes" and stale tag "coffee" must be cleared!
        assertNull(resB.updatedCategoryId)
        assertNull(resB.updatedCategoryName)
        assertTrue(resB.updatedTags.isEmpty())
    }

    @Test
    fun resolver_consecutiveAutofill_nonSplitToSplitPayee_clearsPriorCategory() {
        // Step 1: Autofill Payee A (non-split, has category Coffee & Cafes)
        val templateA = PayeeAutofillTemplate(
            payeeName = "Starbucks",
            type = TransactionType.EXPENSE,
            accountId = 1L,
            transferToAccountId = null,
            categoryId = 11L, // Coffee & Cafes
            isSplit = false,
            tags = emptyList()
        )
        val resA = PayeeAutofillResolver.resolve(
            template = templateA,
            isEditMode = false,
            currentMode = TransactionType.EXPENSE,
            currentAccountId = 1L,
            currentTransferToAccountId = null,
            currentCategoryId = null,
            currentCategoryName = null,
            currentParentCategoryName = null,
            currentCategoryColorHex = null,
            currentCategoryIconName = null,
            currentTags = emptyList(),
            isSplit = false,
            isAccountManuallySet = false,
            isDestinationManuallySet = false,
            isCategoryManuallySet = false,
            isTagsManuallySet = false,
            isModeManuallySet = false,
            activeAccounts = activeAccounts,
            activeExpenseCategories = listOf(diningCategory),
            activeIncomeCategories = listOf(salaryCategory),
            activeTags = emptyList(),
            subcategoriesEnabled = true,
            evaluatedAmount = MinorMoney.ZERO
        )
        assertEquals(11L, resA.updatedCategoryId)
        assertEquals("Coffee & Cafes", resA.updatedCategoryName)

        // Step 2: User now taps Payee B (whose latest transaction was a SPLIT)
        val templateB = PayeeAutofillTemplate(
            payeeName = "Walmart Split",
            type = TransactionType.EXPENSE,
            accountId = 1L,
            transferToAccountId = null,
            categoryId = null,
            isSplit = true, // SPLIT transaction!
            tags = emptyList()
        )
        val resB = PayeeAutofillResolver.resolve(
            template = templateB,
            isEditMode = false,
            currentMode = resA.updatedMode,
            currentAccountId = resA.updatedAccountId,
            currentTransferToAccountId = resA.updatedTransferToAccountId,
            currentCategoryId = resA.updatedCategoryId,
            currentCategoryName = resA.updatedCategoryName,
            currentParentCategoryName = resA.updatedParentCategoryName,
            currentCategoryColorHex = resA.updatedCategoryColorHex,
            currentCategoryIconName = resA.updatedCategoryIconName,
            currentTags = resA.updatedTags,
            isSplit = false,
            isAccountManuallySet = false,
            isDestinationManuallySet = false,
            isCategoryManuallySet = false,
            isTagsManuallySet = false,
            isModeManuallySet = false,
            activeAccounts = activeAccounts,
            activeExpenseCategories = listOf(diningCategory),
            activeIncomeCategories = listOf(salaryCategory),
            activeTags = emptyList(),
            subcategoriesEnabled = true,
            evaluatedAmount = MinorMoney.ZERO
        )

        // Must clear prior category "Coffee & Cafes"
        assertNull(resB.updatedCategoryId)
        assertNull(resB.updatedCategoryName)
    }

    @Test
    fun resolver_crossModeSwitch_expenseToIncome_clearsStaleExpenseCategory() {
        val incomeSalaryTemplate = PayeeAutofillTemplate(
            payeeName = "Acme Corp",
            type = TransactionType.INCOME,
            accountId = 1L,
            transferToAccountId = null,
            categoryId = null, // No category assigned on template
            isSplit = false,
            tags = emptyList()
        )

        val resolution = PayeeAutofillResolver.resolve(
            template = incomeSalaryTemplate,
            isEditMode = false,
            currentMode = TransactionType.EXPENSE, // Started as EXPENSE
            currentAccountId = 1L,
            currentTransferToAccountId = null,
            currentCategoryId = 11L, // Coffee & Cafes (EXPENSE category!)
            currentCategoryName = "Coffee & Cafes",
            currentParentCategoryName = "Dining Out",
            currentCategoryColorHex = "#E11D48",
            currentCategoryIconName = "local_cafe",
            currentTags = emptyList(),
            isSplit = false,
            isAccountManuallySet = false,
            isDestinationManuallySet = false,
            isCategoryManuallySet = false,
            isTagsManuallySet = false,
            isModeManuallySet = false,
            activeAccounts = activeAccounts,
            activeExpenseCategories = listOf(diningCategory),
            activeIncomeCategories = listOf(salaryCategory),
            activeTags = emptyList(),
            subcategoriesEnabled = true,
            evaluatedAmount = MinorMoney.ZERO
        )

        assertEquals(TransactionType.INCOME, resolution.updatedMode)
        // Must NOT keep 11L (Expense category) on an Income transaction!
        assertNull(resolution.updatedCategoryId)
        assertNull(resolution.updatedCategoryName)
    }

    @Test
    fun resolver_crossModeSwitch_expenseToIncome_appliesValidIncomeCategory() {
        val incomeSalaryTemplate = PayeeAutofillTemplate(
            payeeName = "Acme Corp",
            type = TransactionType.INCOME,
            accountId = 1L,
            transferToAccountId = null,
            categoryId = 30L, // Salary & Wages (valid INCOME category)
            isSplit = false,
            tags = listOf("salary")
        )

        val resolution = PayeeAutofillResolver.resolve(
            template = incomeSalaryTemplate,
            isEditMode = false,
            currentMode = TransactionType.EXPENSE,
            currentAccountId = 1L,
            currentTransferToAccountId = null,
            currentCategoryId = 11L, // Coffee & Cafes (EXPENSE category)
            currentCategoryName = "Coffee & Cafes",
            currentParentCategoryName = "Dining Out",
            currentCategoryColorHex = "#E11D48",
            currentCategoryIconName = "local_cafe",
            currentTags = listOf("coffee"),
            isSplit = false,
            isAccountManuallySet = false,
            isDestinationManuallySet = false,
            isCategoryManuallySet = false,
            isTagsManuallySet = false,
            isModeManuallySet = false,
            activeAccounts = activeAccounts,
            activeExpenseCategories = listOf(diningCategory),
            activeIncomeCategories = listOf(salaryCategory),
            activeTags = listOf("coffee", "salary"),
            subcategoriesEnabled = true,
            evaluatedAmount = MinorMoney.ZERO
        )

        assertEquals(TransactionType.INCOME, resolution.updatedMode)
        assertEquals(30L, resolution.updatedCategoryId)
        assertEquals("Salary", resolution.updatedCategoryName)
        assertEquals(listOf("salary"), resolution.updatedTags)
    }

    @Test
    fun resolver_transferSelfAccountCollision_repointsDestinationToAlternateAccount() {
        val transferTemplate = PayeeAutofillTemplate(
            payeeName = "Checking to Checking",
            type = TransactionType.TRANSFER,
            accountId = 1L, // Chase Checking (USD)
            transferToAccountId = 1L, // Self-collision: Same account!
            categoryId = null,
            isSplit = false,
            tags = emptyList()
        )

        val resolution = PayeeAutofillResolver.resolve(
            template = transferTemplate,
            isEditMode = false,
            currentMode = TransactionType.TRANSFER,
            currentAccountId = 1L, // Chase Checking (USD)
            currentTransferToAccountId = null,
            currentCategoryId = null,
            currentCategoryName = null,
            currentParentCategoryName = null,
            currentCategoryColorHex = null,
            currentCategoryIconName = null,
            currentTags = emptyList(),
            isSplit = false,
            isAccountManuallySet = false,
            isDestinationManuallySet = false,
            isCategoryManuallySet = false,
            isTagsManuallySet = false,
            isModeManuallySet = false,
            activeAccounts = activeAccounts, // Account 1 (USD), Account 2 (USD), Account 3 (INR)
            activeExpenseCategories = listOf(diningCategory),
            activeIncomeCategories = listOf(salaryCategory),
            activeTags = emptyList(),
            subcategoriesEnabled = true,
            evaluatedAmount = MinorMoney.ZERO
        )

        // Must re-point destination away from Account 1L to Account 2L (matching USD currency)
        assertEquals(1L, resolution.updatedAccountId)
        assertEquals(2L, resolution.updatedTransferToAccountId)
    }

    @Test
    fun resolver_transferCrossCurrencyMismatch_repointsDestinationToMatchingCurrencyAccount() {
        // activeAccounts: Account 1 (USD), Account 2 (USD), Account 3 (INR)
        // User is in TRANSFER mode. Source is Account 1 (USD), Destination is Account 2 (USD).
        // User taps an INR payee template with accountId = 3L, transferToAccountId = null.
        val inrTemplate = PayeeAutofillTemplate(
            payeeName = "Swiggy",
            type = TransactionType.EXPENSE,
            accountId = 3L, // INR
            transferToAccountId = null,
            categoryId = null,
            isSplit = false,
            tags = emptyList()
        )

        val resolution = PayeeAutofillResolver.resolve(
            template = inrTemplate,
            isEditMode = false,
            currentMode = TransactionType.TRANSFER,
            currentAccountId = 1L, // USD
            currentTransferToAccountId = 2L, // USD
            currentCategoryId = null,
            currentCategoryName = null,
            currentParentCategoryName = null,
            currentCategoryColorHex = null,
            currentCategoryIconName = null,
            currentTags = emptyList(),
            isSplit = false,
            isAccountManuallySet = false,
            isDestinationManuallySet = false,
            isCategoryManuallySet = false,
            isTagsManuallySet = false,
            isModeManuallySet = false,
            activeAccounts = activeAccounts, // Account 1 (USD), Account 2 (USD), Account 3 (INR)
            activeExpenseCategories = listOf(diningCategory),
            activeIncomeCategories = listOf(salaryCategory),
            activeTags = emptyList(),
            subcategoriesEnabled = true,
            evaluatedAmount = MinorMoney.ZERO
        )

        // Source switched to Account 3 (INR). Destination was Account 2 (USD).
        // Guard must detect currency mismatch (INR != USD) and repoint away from USD to null (since no other INR account exists)
        assertEquals(3L, resolution.updatedAccountId)
        assertNull(resolution.updatedTransferToAccountId)
    }

    @Test
    fun resolver_transferLockedDestination_refusesSourceSwitchToCollidingAccount() {
        // activeAccounts: Account 1 (USD), Account 2 (USD), Account 3 (INR)
        // User is in TRANSFER mode. User MANUALLY locked Destination = Account 2 (USD).
        // Source is Account 1 (USD) (not manually set).
        // User taps a template with accountId = 2L (colliding with locked destination).
        val collidingTemplate = PayeeAutofillTemplate(
            payeeName = "Internal Transfer",
            type = TransactionType.TRANSFER,
            accountId = 2L,
            transferToAccountId = null,
            categoryId = null,
            isSplit = false,
            tags = emptyList()
        )

        val resolution = PayeeAutofillResolver.resolve(
            template = collidingTemplate,
            isEditMode = false,
            currentMode = TransactionType.TRANSFER,
            currentAccountId = 1L, // USD
            currentTransferToAccountId = 2L, // USD (Manually Locked)
            currentCategoryId = null,
            currentCategoryName = null,
            currentParentCategoryName = null,
            currentCategoryColorHex = null,
            currentCategoryIconName = null,
            currentTags = emptyList(),
            isSplit = false,
            isAccountManuallySet = false,
            isDestinationManuallySet = true, // LOCKED DESTINATION
            isCategoryManuallySet = false,
            isTagsManuallySet = false,
            isModeManuallySet = false,
            activeAccounts = activeAccounts,
            activeExpenseCategories = listOf(diningCategory),
            activeIncomeCategories = listOf(salaryCategory),
            activeTags = emptyList(),
            subcategoriesEnabled = true,
            evaluatedAmount = MinorMoney.ZERO
        )

        // Must refuse to switch Source to Account 2L because it collides with locked destination Account 2L
        assertEquals(1L, resolution.updatedAccountId)
        assertEquals(2L, resolution.updatedTransferToAccountId)
    }

    @Test
    fun resolver_transferLockedDestination_refusesSourceSwitchToMismatchedCurrencyAccount() {
        // activeAccounts: Account 1 (USD), Account 2 (USD), Account 3 (INR)
        // User is in TRANSFER mode. User MANUALLY locked Destination = Account 2 (USD).
        // Source is Account 1 (USD) (not manually set).
        // Amount is zero, but template is in INR (accountId = 3L).
        val inrTemplate = PayeeAutofillTemplate(
            payeeName = "Swiggy",
            type = TransactionType.EXPENSE,
            accountId = 3L, // INR
            transferToAccountId = null,
            categoryId = null,
            isSplit = false,
            tags = emptyList()
        )

        val resolution = PayeeAutofillResolver.resolve(
            template = inrTemplate,
            isEditMode = false,
            currentMode = TransactionType.TRANSFER,
            currentAccountId = 1L, // USD
            currentTransferToAccountId = 2L, // USD (Manually Locked)
            currentCategoryId = null,
            currentCategoryName = null,
            currentParentCategoryName = null,
            currentCategoryColorHex = null,
            currentCategoryIconName = null,
            currentTags = emptyList(),
            isSplit = false,
            isAccountManuallySet = false,
            isDestinationManuallySet = true, // LOCKED DESTINATION
            isCategoryManuallySet = false,
            isTagsManuallySet = false,
            isModeManuallySet = false,
            activeAccounts = activeAccounts,
            activeExpenseCategories = listOf(diningCategory),
            activeIncomeCategories = listOf(salaryCategory),
            activeTags = emptyList(),
            subcategoriesEnabled = true,
            evaluatedAmount = MinorMoney.ZERO
        )

        // Must refuse to switch Source to Account 3L (INR) because it has a currency mismatch with locked destination Account 2L (USD)
        assertEquals(1L, resolution.updatedAccountId)
        assertEquals(2L, resolution.updatedTransferToAccountId)
    }

    @Test
    fun resolver_clearedCategory_doesNotReportUncategorizedInBanner() {
        val uncategorizedTemplate = PayeeAutofillTemplate(
            payeeName = "Corner Bodega",
            type = TransactionType.EXPENSE,
            accountId = 1L,
            transferToAccountId = null,
            categoryId = null, // No category in template
            isSplit = false,
            tags = emptyList()
        )

        val resolution = PayeeAutofillResolver.resolve(
            template = uncategorizedTemplate,
            isEditMode = false,
            currentMode = TransactionType.EXPENSE,
            currentAccountId = 1L,
            currentTransferToAccountId = null,
            currentCategoryId = 11L, // Was categorized as Coffee & Cafes
            currentCategoryName = "Coffee & Cafes",
            currentParentCategoryName = "Dining Out",
            currentCategoryColorHex = "#E11D48",
            currentCategoryIconName = "local_cafe",
            currentTags = emptyList(),
            isSplit = false,
            isAccountManuallySet = false,
            isDestinationManuallySet = false,
            isCategoryManuallySet = false,
            isTagsManuallySet = false,
            isModeManuallySet = false,
            activeAccounts = activeAccounts,
            activeExpenseCategories = listOf(diningCategory),
            activeIncomeCategories = listOf(salaryCategory),
            activeTags = emptyList(),
            subcategoriesEnabled = true,
            evaluatedAmount = MinorMoney.ZERO
        )

        // Category is cleared so stale category doesn't leak
        assertNull(resolution.updatedCategoryId)
        assertNull(resolution.updatedCategoryName)
        // Banner must not claim "Auto-filled" or "Uncategorized" when stale metadata was only cleared
        assertNull(resolution.bannerText)
    }

    // ==========================================
    // 4. Payee Unicode Deduplication Tests
    // ==========================================

    @Test
    fun repository_deduplicatePayeeProjections_mergesNonAsciiCasingAndSumsUsage() {
        // SQLite LOWER("Café") is "café", but LOWER("CAFÉ") is "cafÉ" (SQLite LOWER is ASCII-only).
        // SQLite therefore returns two distinct rows.
        val projections = listOf(
            PayeeSuggestionProjection(
                latestTransactionId = 10L,
                displayName = "Café",
                usageCount = 3,
                lastUsedEpochMillis = 2000L,
                lastType = TransactionType.EXPENSE,
                lastAccountId = 1L,
                lastTransferToAccountId = null,
                lastCategoryId = 11L,
                lastIsSplit = false
            ),
            PayeeSuggestionProjection(
                latestTransactionId = 5L,
                displayName = "CAFÉ",
                usageCount = 2,
                lastUsedEpochMillis = 1000L,
                lastType = TransactionType.EXPENSE,
                lastAccountId = 1L,
                lastTransferToAccountId = null,
                lastCategoryId = 11L,
                lastIsSplit = false
            ),
            PayeeSuggestionProjection(
                latestTransactionId = 1L,
                displayName = "Uber",
                usageCount = 7,
                lastUsedEpochMillis = 500L,
                lastType = TransactionType.EXPENSE,
                lastAccountId = 1L,
                lastTransferToAccountId = null,
                lastCategoryId = 12L,
                lastIsSplit = false
            )
        )

        val result = TransactionRepositoryImpl.deduplicatePayeeProjections(projections)

        // Must deduplicate "Café" and "CAFÉ" into a single suggestion with normalizedKey = "café"
        assertEquals(2, result.size)

        val cafe = result.first { it.normalizedKey == "café" }
        assertEquals("Café", cafe.displayName) // Latest casing preserved
        assertEquals(10L, cafe.latestTransactionId) // Latest transaction id
        assertEquals(2000L, cafe.lastUsedEpochMillis) // Latest timestamp
        assertEquals(5, cafe.usageCount) // Sum of 3 + 2

        val uber = result.first { it.normalizedKey == "uber" }
        assertEquals("Uber", uber.displayName)
        assertEquals(7, uber.usageCount)
    }

    @Test
    fun repository_deduplicatePayeeProjections_sortedByLastUsedThenUsageCount() {
        val projections = listOf(
            PayeeSuggestionProjection(
                latestTransactionId = 1L,
                displayName = "Old Payee",
                usageCount = 50,
                lastUsedEpochMillis = 1000L,
                lastType = TransactionType.EXPENSE,
                lastAccountId = 1L,
                lastTransferToAccountId = null,
                lastCategoryId = null,
                lastIsSplit = false
            ),
            PayeeSuggestionProjection(
                latestTransactionId = 2L,
                displayName = "Recent Payee",
                usageCount = 5,
                lastUsedEpochMillis = 5000L,
                lastType = TransactionType.EXPENSE,
                lastAccountId = 1L,
                lastTransferToAccountId = null,
                lastCategoryId = null,
                lastIsSplit = false
            )
        )

        val result = TransactionRepositoryImpl.deduplicatePayeeProjections(projections)
        assertEquals(2, result.size)
        assertEquals("Recent Payee", result[0].displayName)
        assertEquals("Old Payee", result[1].displayName)
    }

    @Test
    fun repository_deduplicatePayeeProjections_dropsUnicodeWhitespaceBlankNames() {
        val projections = listOf(
            PayeeSuggestionProjection(
                latestTransactionId = 1L,
                displayName = "\u00A0\u3000 ",
                usageCount = 1,
                lastUsedEpochMillis = 5000L,
                lastType = TransactionType.EXPENSE,
                lastAccountId = 1L,
                lastTransferToAccountId = null,
                lastCategoryId = null,
                lastIsSplit = false
            ),
            PayeeSuggestionProjection(
                latestTransactionId = 2L,
                displayName = "McDonald's",
                usageCount = 2,
                lastUsedEpochMillis = 4000L,
                lastType = TransactionType.EXPENSE,
                lastAccountId = 1L,
                lastTransferToAccountId = null,
                lastCategoryId = null,
                lastIsSplit = false
            )
        )

        val result = TransactionRepositoryImpl.deduplicatePayeeProjections(projections)
        assertEquals(1, result.size)
        assertEquals("McDonald's", result[0].displayName)
    }

    @Test
    fun suggestionEngine_handlesNegativeMaxResultsAndPunctuationWordBoundaries() {
        val catalog = listOf(
            PayeeSuggestion(
                latestTransactionId = 1L,
                displayName = "Cafe (Main)",
                normalizedKey = "cafe (main)",
                usageCount = 3,
                lastUsedEpochMillis = 1000L,
                lastType = TransactionType.EXPENSE,
                lastAccountId = 1L,
                lastTransferToAccountId = null,
                lastCategoryId = null,
                lastIsSplit = false
            )
        )

        assertTrue(PayeeSuggestionEngine.rankSuggestions("", TransactionType.EXPENSE, catalog, maxResults = -1).isEmpty())
        val matches = PayeeSuggestionEngine.rankSuggestions("main", TransactionType.EXPENSE, catalog, maxResults = 5)
        assertEquals(1, matches.size)
        assertEquals("Cafe (Main)", matches[0].displayName)
    }
}
