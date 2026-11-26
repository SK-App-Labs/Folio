/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/test/java/com/folioledger/app/data/demo/DemoModeAndSandboxTest.kt
package com.folioledger.app.data.demo

import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoModeAndSandboxTest {

    @Test
    fun testCurrencyScalingMagnitudes() {
        val baseMinor = 100_00L // 100 base units

        // Western currencies (1x)
        assertEquals(100_00L, DemoDataSeeder.scaleMinor(baseMinor, "USD"))
        assertEquals(100_00L, DemoDataSeeder.scaleMinor(baseMinor, "EUR"))
        assertEquals(100_00L, DemoDataSeeder.scaleMinor(baseMinor, "GBP"))
        assertEquals(100_00L, DemoDataSeeder.scaleMinor(baseMinor, "CAD"))
        assertEquals(100_00L, DemoDataSeeder.scaleMinor(baseMinor, "AUD"))

        // INR (20x scaling for realistic rupee amounts)
        assertEquals(2000_00L, DemoDataSeeder.scaleMinor(baseMinor, "INR"))

        // JPY and KRW (0-decimal currencies, 100x scaling from USD major units)
        assertEquals(10_000L, DemoDataSeeder.scaleMinor(baseMinor, "JPY"))
        assertEquals(10_000L, DemoDataSeeder.scaleMinor(baseMinor, "KRW"))

        // 3-decimal currencies (KWD, OMR, BHD -> 1000 minor units per major unit)
        assertEquals(100_000L, DemoDataSeeder.scaleMinor(baseMinor, "KWD"))
        assertEquals(100_000L, DemoDataSeeder.scaleMinor(baseMinor, "BHD"))

        // Emerging markets (15x scaling)
        assertEquals(1500_00L, DemoDataSeeder.scaleMinor(baseMinor, "PHP"))
        assertEquals(1500_00L, DemoDataSeeder.scaleMinor(baseMinor, "THB"))
        assertEquals(1500_00L, DemoDataSeeder.scaleMinor(baseMinor, "MXN"))
        assertEquals(1500_00L, DemoDataSeeder.scaleMinor(baseMinor, "ZAR"))
    }

    @Test
    fun testDemoStashDecisionHelpersGuardStaleAndDuplicateStashes() {
        assertTrue(DemoModeManager.shouldPurgeStaleStash(isDemoModeActive = false, hasStashedRealLedger = false, isStashAvailable = true))
        assertFalse(DemoModeManager.shouldPurgeStaleStash(isDemoModeActive = true, hasStashedRealLedger = false, isStashAvailable = true))
        assertFalse(DemoModeManager.shouldPurgeStaleStash(isDemoModeActive = false, hasStashedRealLedger = true, isStashAvailable = true))

        assertTrue(DemoModeManager.shouldCreateNewStash(isDemoModeActive = false, hasStashedRealLedger = false, isStashAvailable = false))
        assertFalse(DemoModeManager.shouldCreateNewStash(isDemoModeActive = true, hasStashedRealLedger = false, isStashAvailable = false))
        assertFalse(DemoModeManager.shouldCreateNewStash(isDemoModeActive = false, hasStashedRealLedger = true, isStashAvailable = true))
    }

    @Test
    fun testUserPreferencesDemoFlagsDefaultState() {
        val prefs = UserPreferences()
        assertFalse("New install must not have seen welcome sheet", prefs.hasSeenWelcomeSheet)
        assertFalse("Demo mode must not be active on clean state", prefs.isDemoModeActive)
        assertFalse("Stash flag must be false by default", prefs.hasStashedRealLedger)
    }

    @Test
    fun testUserPreferencesDemoStateTransitions() {
        val initial = UserPreferences()
        val inDemo = initial.copy(
            hasSeenWelcomeSheet = true,
            isDemoModeActive = true,
            hasStashedRealLedger = true
        )

        assertTrue(inDemo.hasSeenWelcomeSheet)
        assertTrue(inDemo.isDemoModeActive)
        assertTrue(inDemo.hasStashedRealLedger)

        val restored = inDemo.copy(
            isDemoModeActive = false,
            hasStashedRealLedger = false
        )

        assertTrue("Welcome sheet remains seen after restore", restored.hasSeenWelcomeSheet)
        assertFalse("Demo mode deactivated after restore", restored.isDemoModeActive)
        assertFalse("Stash cleared after restore", restored.hasStashedRealLedger)
    }

    @Test
    fun testCurrencyDefaultGroupingMatchesSupportedCurrencies() {
        val inrSpec = SupportedCurrencies.fromCode("INR")
        assertEquals(NumberGroupingSystem.INDIAN, inrSpec.defaultGrouping)

        val usdSpec = SupportedCurrencies.fromCode("USD")
        assertEquals(NumberGroupingSystem.INTERNATIONAL, usdSpec.defaultGrouping)

        val eurSpec = SupportedCurrencies.fromCode("EUR")
        assertEquals(NumberGroupingSystem.INTERNATIONAL, eurSpec.defaultGrouping)
    }
}
