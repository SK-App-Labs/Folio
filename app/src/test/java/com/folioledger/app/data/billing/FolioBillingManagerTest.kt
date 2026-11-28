/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.data.billing

import com.android.billingclient.api.BillingClient
import com.folioledger.app.domain.model.SupporterTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolioBillingManagerTest {

    @Test
    fun `supporterTier fromSkuOrTitle resolves exact Play Store product IDs`() {
        assertEquals(SupporterTier.COFFEE, SupporterTier.fromSkuOrTitle("folio_tip_coffee"))
        assertEquals(SupporterTier.GENEROUS_SUPPORTER, SupporterTier.fromSkuOrTitle("folio_tip_supporter"))
        assertEquals(SupporterTier.PATRON, SupporterTier.fromSkuOrTitle("folio_tip_patron"))
    }

    @Test
    fun `supporterTier fromSkuOrTitle is case insensitive and resolves titles`() {
        assertEquals(SupporterTier.COFFEE, SupporterTier.fromSkuOrTitle("FOLIO_TIP_COFFEE"))
        assertEquals(SupporterTier.COFFEE, SupporterTier.fromSkuOrTitle("Coffee / Chai Boost"))
        assertEquals(SupporterTier.GENEROUS_SUPPORTER, SupporterTier.fromSkuOrTitle("Generous Supporter"))
        assertEquals(SupporterTier.PATRON, SupporterTier.fromSkuOrTitle("Folio Patron"))
        assertNull(SupporterTier.fromSkuOrTitle(null))
        assertNull(SupporterTier.fromSkuOrTitle(""))
        assertNull(SupporterTier.fromSkuOrTitle("unknown_sku"))
    }

    @Test
    fun `tier hierarchy enforces strictly ascending ordinal values`() {
        assertTrue(SupporterTier.COFFEE.ordinal < SupporterTier.GENEROUS_SUPPORTER.ordinal)
        assertTrue(SupporterTier.GENEROUS_SUPPORTER.ordinal < SupporterTier.PATRON.ordinal)
    }

    @Test
    fun `downgrade guard prevents Patron from being overwritten by Coffee purchase`() {
        val currentTier = SupporterTier.PATRON
        val incomingPurchasedTier = SupporterTier.COFFEE

        val shouldUpgrade = FolioBillingManager.shouldPersistTierUpgrade(currentTier, incomingPurchasedTier)
        assertFalse("Patron should not be downgraded by Coffee purchase", shouldUpgrade)
    }

    @Test
    fun `upgrade guard allows Coffee supporter to be upgraded to Patron`() {
        val currentTier = SupporterTier.COFFEE
        val incomingPurchasedTier = SupporterTier.PATRON

        val shouldUpgrade = FolioBillingManager.shouldPersistTierUpgrade(currentTier, incomingPurchasedTier)
        assertTrue("Coffee should be upgraded to Patron", shouldUpgrade)
    }

    @Test
    fun `resolveHighestTier resolves highest tier among multiple purchases`() {
        val tiers = listOf("folio_tip_coffee", "folio_tip_patron", "folio_tip_supporter")
        assertEquals(SupporterTier.PATRON, FolioBillingManager.resolveHighestTier(tiers))

        val singleTier = listOf("folio_tip_coffee")
        assertEquals(SupporterTier.COFFEE, FolioBillingManager.resolveHighestTier(singleTier))

        val unknownTiers = listOf("unknown_sku_1", "unknown_sku_2")
        assertNull(FolioBillingManager.resolveHighestTier(unknownTiers))

        assertNull(FolioBillingManager.resolveHighestTier(emptyList()))
    }

    @Test
    fun `shouldPersistTierUpgrade correctly evaluates upgrade, equal, and downgrade cases`() {
        // Upgrade: Coffee -> Patron
        assertTrue(FolioBillingManager.shouldPersistTierUpgrade(SupporterTier.COFFEE, SupporterTier.PATRON))

        // Equal: Generous Supporter -> Generous Supporter
        assertTrue(FolioBillingManager.shouldPersistTierUpgrade(SupporterTier.GENEROUS_SUPPORTER, SupporterTier.GENEROUS_SUPPORTER))

        // Downgrade: Patron -> Coffee
        assertFalse(FolioBillingManager.shouldPersistTierUpgrade(SupporterTier.PATRON, SupporterTier.COFFEE))

        // Fresh user: null -> Coffee
        assertTrue(FolioBillingManager.shouldPersistTierUpgrade(null, SupporterTier.COFFEE))
    }

    @Test
    fun `TipJarBillingState defaults to not connecting with store available`() {
        val defaultState = TipJarBillingState()
        assertFalse(defaultState.isConnecting)
        assertFalse(defaultState.isLoadingProducts)
        assertFalse(defaultState.isRestoringPurchases)
        assertFalse(defaultState.hasVerifiedPlayPurchases)
        assertTrue(defaultState.isStoreAvailable)
        assertFalse(defaultState.isOfflineError)
        assertTrue(defaultState.products.isEmpty())
        assertFalse(defaultState.isPurchaseInProgress)
        assertNull(defaultState.pendingMessage)
        assertNull(defaultState.errorMessage)
        assertNull(defaultState.restoreMessage)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `isTransientOfflineCode classifies transient connectivity billing errors accurately`() {
        assertTrue(FolioBillingManager.isTransientOfflineCode(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE))
        assertTrue(FolioBillingManager.isTransientOfflineCode(BillingClient.BillingResponseCode.NETWORK_ERROR))
        assertTrue(FolioBillingManager.isTransientOfflineCode(BillingClient.BillingResponseCode.SERVICE_TIMEOUT))
        assertTrue(FolioBillingManager.isTransientOfflineCode(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED))

        assertFalse(FolioBillingManager.isTransientOfflineCode(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE))
        assertFalse(FolioBillingManager.isTransientOfflineCode(BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED))
        assertFalse(FolioBillingManager.isTransientOfflineCode(BillingClient.BillingResponseCode.OK))
    }

    @Test
    fun `buildTipProducts keeps all tiers purchasable when Play ownership is empty (legacy local Patron)`() {
        val prices = mapOf(
            "folio_tip_coffee" to "₹99.00",
            "folio_tip_supporter" to "₹299.00",
            "folio_tip_patron" to "₹999.00",
        )
        val products = FolioBillingManager.buildTipProducts(
            cachedPricesBySku = prices,
            ownedPlaySkus = emptySet(),
        )
        assertEquals(3, products.size)
        assertTrue(products.all { !it.isOwned })
        assertEquals("₹99.00", products.first { it.tier == SupporterTier.COFFEE }.formattedPrice)
    }

    @Test
    fun `buildTipProducts marks only exact Play-owned SKUs as owned so higher tier buyers can still tip other tiers`() {
        val prices = mapOf(
            "folio_tip_coffee" to "₹99.00",
            "folio_tip_supporter" to "₹299.00",
            "folio_tip_patron" to "₹999.00",
        )
        val products = FolioBillingManager.buildTipProducts(
            cachedPricesBySku = prices,
            ownedPlaySkus = setOf("folio_tip_patron"),
        )
        assertFalse(products.first { it.tier == SupporterTier.COFFEE }.isOwned)
        assertFalse(products.first { it.tier == SupporterTier.GENEROUS_SUPPORTER }.isOwned)
        assertTrue(products.first { it.tier == SupporterTier.PATRON }.isOwned)
    }

    @Test
    fun `buildTipProducts marks multiple Play-owned SKUs accurately`() {
        val products = FolioBillingManager.buildTipProducts(
            cachedPricesBySku = emptyMap(),
            ownedPlaySkus = setOf("folio_tip_coffee", "folio_tip_patron"),
        )
        assertTrue(products.first { it.tier == SupporterTier.COFFEE }.isOwned)
        assertFalse(products.first { it.tier == SupporterTier.GENEROUS_SUPPORTER }.isOwned)
        assertTrue(products.first { it.tier == SupporterTier.PATRON }.isOwned)
    }

    @Test
    fun `TipProductUi maps tier details and owned flag accurately`() {
        val item = TipProductUi(
            tier = SupporterTier.COFFEE,
            formattedPrice = "₹99.00",
            isOwned = true,
        )
        assertEquals("folio_tip_coffee", item.tier.skuId)
        assertEquals("₹99.00", item.formattedPrice)
        assertTrue(item.isOwned)
    }
}
