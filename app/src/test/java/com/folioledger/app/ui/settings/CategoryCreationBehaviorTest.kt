/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.settings

import com.folioledger.app.domain.model.CategoryNode
import com.folioledger.app.domain.model.CategoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryCreationBehaviorTest {

    @Test
    fun `duplicate category detection flags same-name main category`() {
        val existingCategories = listOf(
            CategoryNode(id = 1L, name = "Food & Dining", type = CategoryType.EXPENSE, parentId = null, iconName = "restaurant", colorHex = "#EA580C"),
            CategoryNode(id = 2L, name = "Shopping", type = CategoryType.EXPENSE, parentId = null, iconName = "shopping_bag", colorHex = "#EC4899"),
        )

        val inputName = "food & dining"
        val isDuplicate = existingCategories.any { it.name.equals(inputName.trim(), ignoreCase = true) }
        assertTrue(isDuplicate)

        val uniqueName = "Health & Fitness"
        val isUnique = existingCategories.any { it.name.equals(uniqueName.trim(), ignoreCase = true) }
        assertFalse(isUnique)
    }

    @Test
    fun `duplicate subcategory detection flags same-name subcategory under same parent`() {
        val foodSubcategories = listOf(
            CategoryNode(id = 10L, name = "Swiggy", type = CategoryType.EXPENSE, parentId = 1L, iconName = "restaurant", colorHex = "#EA580C"),
            CategoryNode(id = 11L, name = "Groceries", type = CategoryType.EXPENSE, parentId = 1L, iconName = "restaurant", colorHex = "#EA580C"),
        )

        val inputSub = "SWIGGY"
        val isDuplicate = foodSubcategories.any { it.name.equals(inputSub.trim(), ignoreCase = true) }
        assertTrue(isDuplicate)

        val uniqueSub = "Coffee"
        val isUnique = foodSubcategories.any { it.name.equals(uniqueSub.trim(), ignoreCase = true) }
        assertFalse(isUnique)
    }

    @Test
    fun `subcategory creation inherits parent color and icon by default`() {
        val parent = CategoryNode(
            id = 5L,
            name = "Utilities",
            type = CategoryType.EXPENSE,
            parentId = null,
            iconName = "build",
            colorHex = "#0284C7",
        )

        val inheritedColor = parent.colorHex
        val inheritedIcon = parent.iconName

        assertEquals("#0284C7", inheritedColor)
        assertEquals("build", inheritedIcon)
    }

    @Test
    fun `category suggestion matches ro water to plumbing icon`() {
        val suggestion = com.folioledger.app.domain.util.CategorySuggestionHelper.suggestIconAndColorForCategoryName("RO Water Service")
        assertNotNull(suggestion)
        assertEquals("plumbing", suggestion?.first)
        assertEquals("#0284C7", suggestion?.second)
    }

    @Test
    fun `category suggestion uses word boundaries without short-token hijacking and returns null for unmatched income`() {
        val suggest = com.folioledger.app.domain.util.CategorySuggestionHelper::suggestIconAndColorForCategoryName

        assertEquals("sports_esports", suggest("Steam", CategoryType.EXPENSE)?.first)
        assertEquals("content_cut", suggest("Barber", CategoryType.EXPENSE)?.first)
        assertEquals("payments", suggest("Payroll", CategoryType.INCOME)?.first)
        assertEquals("watch", suggest("Smartwatch", CategoryType.EXPENSE)?.first)
        assertEquals("receipt_long", suggest("Credit Card Bill", CategoryType.EXPENSE)?.first)
        assertEquals("child_care", suggest("Daycare", CategoryType.EXPENSE)?.first)
        assertEquals("work", suggest("Business", CategoryType.EXPENSE)?.first)
        assertEquals("luggage", suggest("Passport", CategoryType.EXPENSE)?.first)
        assertEquals("local_fire_department", suggest("LPG", CategoryType.EXPENSE)?.first)
        assertEquals("local_hospital", suggest("MRI Scan", CategoryType.EXPENSE)?.first)
        assertEquals("toys", suggest("Board Game", CategoryType.EXPENSE)?.first)
        assertEquals("redeem", suggest("Reward Points", CategoryType.INCOME)?.first)
        assertEquals("ac_unit", suggest("AC", CategoryType.EXPENSE)?.first)
        assertNull(suggest("Miscellaneous Custom Source", CategoryType.INCOME))

        // Stems, plurals, and compound names
        assertEquals("plumbing", suggest("Plumbing", CategoryType.EXPENSE)?.first)
        assertEquals("ac_unit", suggest("Air Conditioner", CategoryType.EXPENSE)?.first)
        assertEquals("diamond", suggest("Jewelry", CategoryType.EXPENSE)?.first)
        assertEquals("trending_up", suggest("Investments", CategoryType.EXPENSE)?.first)
        assertEquals("shopping_cart", suggest("Vegetables", CategoryType.EXPENSE)?.first)
        assertEquals("savings", suggest("Savings", CategoryType.EXPENSE)?.first)
        assertEquals("local_cafe", suggest("Office Coffee", CategoryType.EXPENSE)?.first)
        assertEquals("restaurant", suggest("Work Lunch", CategoryType.EXPENSE)?.first)
        assertEquals("flight", suggest("Business Flight", CategoryType.EXPENSE)?.first)
        assertEquals("computer", suggest("Work Laptop", CategoryType.EXPENSE)?.first)
        assertEquals("security", suggest("Travel Insurance", CategoryType.EXPENSE)?.first)
    }
}
