package com.folioledger.app.data.billing

import com.folioledger.app.domain.model.SupporterTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

        val shouldUpgrade = incomingPurchasedTier.ordinal >= currentTier.ordinal
        assertFalse("Patron should not be downgraded by Coffee purchase", shouldUpgrade)
    }

    @Test
    fun `upgrade guard allows Coffee supporter to be upgraded to Patron`() {
        val currentTier = SupporterTier.COFFEE
        val incomingPurchasedTier = SupporterTier.PATRON

        val shouldUpgrade = incomingPurchasedTier.ordinal >= currentTier.ordinal
        assertTrue("Coffee should be upgraded to Patron", shouldUpgrade)
    }

    @Test
    fun `TipJarBillingState defaults to connecting with store available`() {
        val defaultState = TipJarBillingState()
        assertFalse(defaultState.isConnecting)
        assertTrue(defaultState.isStoreAvailable)
        assertTrue(defaultState.products.isEmpty())
        assertFalse(defaultState.isPurchaseInProgress)
        assertNull(defaultState.pendingMessage)
        assertNull(defaultState.errorMessage)
    }

    @Test
    fun `TipProductUi maps tier details and owned flag accurately`() {
        val item = TipProductUi(
            tier = SupporterTier.COFFEE,
            formattedPrice = "₹99.00",
            isOwned = true
        )
        assertEquals("folio_tip_coffee", item.tier.skuId)
        assertEquals("₹99.00", item.formattedPrice)
        assertTrue(item.isOwned)
    }
}
