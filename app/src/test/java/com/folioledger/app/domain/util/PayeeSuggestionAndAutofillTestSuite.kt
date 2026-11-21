package com.folioledger.app.domain.util

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
}
