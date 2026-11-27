/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/test/java/com/folioledger/app/ui/iconpack/FolioIconPackResolutionTest.kt
package com.folioledger.app.ui.iconpack

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import com.folioledger.app.ui.components.ALL_CATEGORY_ICON_KEYS
import com.folioledger.app.ui.components.resolveFolioIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolioIconPackResolutionTest {

    @Test
    fun resolvingTransferFirstDoesNotPolluteSubsequentCategoryIcons() {
        // 1. Resolve transfer first (mirroring the app launch scenario with a transfer tx)
        val transferIcon = resolveFolioIcon("swap_horiz", FolioIconPackTheme.MATERIAL_FILLED)
        assertNotNull(transferIcon)

        // 2. Resolve other distinct categories
        val restaurantIcon = resolveFolioIcon("restaurant", FolioIconPackTheme.MATERIAL_FILLED)
        val shoppingIcon = resolveFolioIcon("shopping_cart", FolioIconPackTheme.MATERIAL_FILLED)
        val homeIcon = resolveFolioIcon("home", FolioIconPackTheme.MATERIAL_FILLED)

        // 3. Verify each category resolves to its own distinct ImageVector, NOT the transfer icon
        assertNotEquals("Restaurant icon must not be transfer icon", transferIcon, restaurantIcon)
        assertNotEquals("Shopping icon must not be transfer icon", transferIcon, shoppingIcon)
        assertNotEquals("Home icon must not be transfer icon", transferIcon, homeIcon)
        assertNotEquals("Restaurant icon must not be shopping icon", restaurantIcon, shoppingIcon)
    }

    @Test
    fun themeSwitchingYieldsDifferentVectorsForSameCategory() {
        val materialRestaurant = resolveFolioIcon("restaurant", FolioIconPackTheme.MATERIAL_FILLED)
        val phosphorRestaurant = resolveFolioIcon("restaurant", FolioIconPackTheme.PHOSPHOR_ROUNDED)
        val lucideRestaurant = resolveFolioIcon("restaurant", FolioIconPackTheme.LUCIDE_OUTLINE)

        assertNotNull(materialRestaurant)
        assertNotNull(phosphorRestaurant)
        assertNotNull(lucideRestaurant)

        // Each pack has its own bespoke representation
        assertNotEquals(materialRestaurant, phosphorRestaurant)
        assertNotEquals(materialRestaurant, lucideRestaurant)
        assertNotEquals(phosphorRestaurant, lucideRestaurant)
    }

    @Test
    fun nullOrBlankIconNameResolvesGracefully() {
        val nullIcon = resolveFolioIcon(null, FolioIconPackTheme.MATERIAL_FILLED)
        val blankIcon = resolveFolioIcon("   ", FolioIconPackTheme.MATERIAL_FILLED)
        val categoryIcon = resolveFolioIcon("category", FolioIconPackTheme.MATERIAL_FILLED)

        assertNotNull(nullIcon)
        assertNotNull(blankIcon)
        assertEquals(nullIcon, categoryIcon)
        assertEquals(blankIcon, categoryIcon)
    }

    @Test
    fun emojiExpressiveThemePropertiesAndResolver() {
        // 1. Verify Enum definition and properties
        val emojiTheme = FolioIconPackTheme.fromKey("EMOJI_EXPRESSIVE")
        assertEquals(FolioIconPackTheme.EMOJI_EXPRESSIVE, emojiTheme)
        assertTrue(emojiTheme.isEmoji)
        assertTrue(emojiTheme.isSquircle)
        assertFalse(emojiTheme.isOutlined)

        // 2. Verify Semantic mapping to vibrant emojis
        assertEquals("🍔", EmojiIconResolver.resolve("restaurant"))
        assertEquals("🛒", EmojiIconResolver.resolve("shopping_cart"))
        assertEquals("🚗", EmojiIconResolver.resolve("directions_car"))
        assertEquals("💰", EmojiIconResolver.resolve("payments"))
        assertEquals("🏠", EmojiIconResolver.resolve("home"))
        assertEquals("⚡", EmojiIconResolver.resolve("bolt"))
        assertEquals("☕", EmojiIconResolver.resolve("local_cafe"))
        assertEquals("💳", EmojiIconResolver.resolve("credit_card"))
        assertEquals("🔄", EmojiIconResolver.resolve("swap_horiz"))

        // 3. Verify Direct Emoji detection & pass-through
        assertTrue(EmojiIconResolver.isDirectEmoji("🍕"))
        assertTrue(EmojiIconResolver.isDirectEmoji("🍣"))
        assertTrue(EmojiIconResolver.isDirectEmoji("🐱"))
        assertFalse(EmojiIconResolver.isDirectEmoji("restaurant"))
        assertFalse(EmojiIconResolver.isDirectEmoji("shopping_cart"))
        assertFalse(EmojiIconResolver.isDirectEmoji(null))

        // Direct emoji should pass through unchanged
        assertEquals("🍣", EmojiIconResolver.resolve("🍣"))
        assertEquals("🍕", EmojiIconResolver.resolve("🍕"))
    }

    @Test
    fun allCategoryAndDefaultSeederKeysResolveWithoutFallbackInMaterialAndEmoji() {
        val defaultSeederExtraKeys = listOf(
            "delivery_dining",
            "local_grocery_store",
            "eco",
            "set_meal",
            "devices",
            "local_pharmacy",
            "biotech",
            "live_tv",
            "verified_user",
            "currency_exchange",
            "laptop_mac",
            "monitoring",
            "real_estate_agent",
            "stars"
        )
        val keysToVerify = (ALL_CATEGORY_ICON_KEYS + defaultSeederExtraKeys)
            .distinct()
            .filterNot { it == "category" }

        for (key in keysToVerify) {
            val matVector = resolveFolioIcon(key, FolioIconPackTheme.MATERIAL_FILLED)
            assertNotEquals(
                "Material icon key '$key' must not fall back to generic Category icon",
                Icons.Filled.Category,
                matVector
            )
            val emoji = EmojiIconResolver.resolve(key)
            assertNotEquals(
                "Emoji key '$key' must not fall back to generic label emoji",
                "🏷️",
                emoji
            )
        }
    }

    @Test
    fun isDirectEmojiRejectsCurrencyPunctuationAndCjkCharacters() {
        assertFalse(EmojiIconResolver.isDirectEmoji("₹"))
        assertFalse(EmojiIconResolver.isDirectEmoji("€"))
        assertFalse(EmojiIconResolver.isDirectEmoji("₩"))
        assertFalse(EmojiIconResolver.isDirectEmoji("—"))
        assertFalse(EmojiIconResolver.isDirectEmoji("–"))
        assertFalse(EmojiIconResolver.isDirectEmoji("あ"))
        assertFalse(EmojiIconResolver.isDirectEmoji("ア"))
        assertFalse(EmojiIconResolver.isDirectEmoji("한"))
        assertFalse(EmojiIconResolver.isDirectEmoji("food🍕"))
        assertFalse(EmojiIconResolver.isDirectEmoji("$50⭐"))
        assertFalse(EmojiIconResolver.isDirectEmoji("1234567890"))

        assertTrue(EmojiIconResolver.isDirectEmoji("🍔"))
        assertTrue(EmojiIconResolver.isDirectEmoji("☕"))
        assertTrue(EmojiIconResolver.isDirectEmoji("⭐"))
        assertTrue(EmojiIconResolver.isDirectEmoji("🏠"))
        assertTrue(EmojiIconResolver.isDirectEmoji("🏷️"))
        assertTrue(EmojiIconResolver.isDirectEmoji("👨‍👩‍👧‍👦"))
        assertTrue(EmojiIconResolver.isDirectEmoji("1️⃣"))
    }

    @Test
    fun directionalIconsSetAutoMirrorAndPhosphorMapsGroceries() {
        val lucideDirectionalKeys = listOf("trending_up", "trending_down", "receipt_long", "swap_horiz", "autorenew")
        for (key in lucideDirectionalKeys) {
            assertTrue(
                "Lucide icon '$key' must set autoMirror=true",
                resolveFolioIcon(key, FolioIconPackTheme.LUCIDE_OUTLINE).autoMirror
            )
        }

        val phosphorDirectionalKeys = listOf("trending_up", "trending_down", "receipt_long")
        for (key in phosphorDirectionalKeys) {
            assertTrue(
                "Phosphor icon '$key' must set autoMirror=true",
                resolveFolioIcon(key, FolioIconPackTheme.PHOSPHOR_ROUNDED).autoMirror
            )
        }

        assertEquals(
            resolveFolioIcon("shopping_cart", FolioIconPackTheme.PHOSPHOR_ROUNDED),
            resolveFolioIcon("groceries", FolioIconPackTheme.PHOSPHOR_ROUNDED)
        )
    }
}
