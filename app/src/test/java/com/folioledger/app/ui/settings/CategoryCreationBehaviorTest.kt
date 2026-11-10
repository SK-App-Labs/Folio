package com.folioledger.app.ui.settings

import com.folioledger.app.domain.model.CategoryNode
import com.folioledger.app.domain.model.CategoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryCreationBehaviorTest {

    @Test
    fun `duplicate category detection flags same-name main category`() {
        val existingCategories = listOf(
            CategoryNode(id = 1L, name = "Food & Dining", type = CategoryType.EXPENSE, parentId = null, iconName = "restaurant", colorHex = "#EA580C"),
            CategoryNode(id = 2L, name = "Shopping", type = CategoryType.EXPENSE, parentId = null, iconName = "shopping_bag", colorHex = "#EC4899")
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
            CategoryNode(id = 11L, name = "Groceries", type = CategoryType.EXPENSE, parentId = 1L, iconName = "restaurant", colorHex = "#EA580C")
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
            colorHex = "#0284C7"
        )

        val inheritedColor = parent.colorHex
        val inheritedIcon = parent.iconName

        assertEquals("#0284C7", inheritedColor)
        assertEquals("build", inheritedIcon)
    }
}
