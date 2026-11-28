/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.shell

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FabScrollVisibilityControllerTest {

    @Test
    fun downwardScrollHidesFabOnlyAfterCrossingHideThreshold() {
        val controller = FabScrollVisibilityController(hideThresholdPx = 24f, showThresholdPx = 12f)
        assertTrue(controller.isVisible)

        controller.onPostScroll(consumedX = 0f, consumedY = -10f, availableY = 0f)
        assertTrue(controller.isVisible)

        controller.onPostScroll(consumedX = 0f, consumedY = -13.9f, availableY = 0f)
        assertTrue(controller.isVisible)

        controller.onPostScroll(consumedX = 0f, consumedY = -0.1f, availableY = 0f)
        assertFalse(controller.isVisible)
    }

    @Test
    fun upwardScrollRevealsFabOnlyAfterCrossingShowThreshold() {
        val controller = FabScrollVisibilityController(hideThresholdPx = 24f, showThresholdPx = 12f)
        controller.onPostScroll(consumedX = 0f, consumedY = -30f, availableY = 0f)
        assertFalse(controller.isVisible)

        controller.onPostScroll(consumedX = 0f, consumedY = 6f, availableY = 0f)
        assertFalse(controller.isVisible)

        controller.onPostScroll(consumedX = 0f, consumedY = 6f, availableY = 0f)
        assertTrue(controller.isVisible)
    }

    @Test
    fun reversingScrollDirectionResetsAccumulatedDelta() {
        val controller = FabScrollVisibilityController(hideThresholdPx = 24f, showThresholdPx = 12f)

        // Down 18px (< 24px), then up 4px (direction flips -> reset), then down 20px (< 24px)
        controller.onPostScroll(consumedX = 0f, consumedY = -18f, availableY = 0f)
        controller.onPostScroll(consumedX = 0f, consumedY = 4f, availableY = 0f)
        controller.onPostScroll(consumedX = 0f, consumedY = -20f, availableY = 0f)
        assertTrue(controller.isVisible)

        // Additional 4px crosses 24px threshold from reset
        controller.onPostScroll(consumedX = 0f, consumedY = -4f, availableY = 0f)
        assertFalse(controller.isVisible)

        // Hidden: small upward nudge (8px < 12px), reversed down, then up 8px (< 12px) stays hidden
        controller.onPostScroll(consumedX = 0f, consumedY = 8f, availableY = 0f)
        controller.onPostScroll(consumedX = 0f, consumedY = -5f, availableY = 0f)
        controller.onPostScroll(consumedX = 0f, consumedY = 8f, availableY = 0f)
        assertFalse(controller.isVisible)

        // Additional 4px crosses 12px show threshold
        controller.onPostScroll(consumedX = 0f, consumedY = 4f, availableY = 0f)
        assertTrue(controller.isVisible)
    }

    @Test
    fun horizontalSwipesNeverToggleVisibility() {
        val controller = FabScrollVisibilityController(hideThresholdPx = 24f, showThresholdPx = 12f)

        controller.onPostScroll(consumedX = -80f, consumedY = -40f, availableY = 0f)
        assertTrue(controller.isVisible)

        // Hide via genuine vertical scroll, then verify horizontal drag does not reveal
        controller.onPostScroll(consumedX = 0f, consumedY = -30f, availableY = 0f)
        assertFalse(controller.isVisible)

        controller.onPostScroll(consumedX = 60f, consumedY = 25f, availableY = 0f)
        assertFalse(controller.isVisible)
    }

    @Test
    fun shortNonScrollableListDragNeverHidesFab() {
        val controller = FabScrollVisibilityController(hideThresholdPx = 24f, showThresholdPx = 12f)

        // Non-scrollable list leaves consumedY = 0 and all delta unconsumed in availableY < 0
        controller.onPostScroll(consumedX = 0f, consumedY = 0f, availableY = -120f)
        assertTrue(controller.isVisible)
    }

    @Test
    fun pullDownOverscrollAtTopWhileHiddenRevealsFab() {
        val controller = FabScrollVisibilityController(hideThresholdPx = 24f, showThresholdPx = 12f)
        controller.onPostScroll(consumedX = 0f, consumedY = -30f, availableY = 0f)
        assertFalse(controller.isVisible)

        // Even if additional downward scroll accumulated negative delta while hidden
        controller.onPostScroll(consumedX = 0f, consumedY = -10f, availableY = 0f)

        controller.onPostScroll(consumedX = 0f, consumedY = 0f, availableY = 6f)
        assertFalse(controller.isVisible)

        controller.onPostScroll(consumedX = 0f, consumedY = 0f, availableY = 6f)
        assertTrue(controller.isVisible)
    }

    @Test
    fun showUnconditionallyRestoresVisibilityAndResetsAccumulator() {
        val controller = FabScrollVisibilityController(hideThresholdPx = 24f, showThresholdPx = 12f)
        controller.onPostScroll(consumedX = 0f, consumedY = -30f, availableY = 0f)
        assertFalse(controller.isVisible)

        controller.show()
        assertTrue(controller.isVisible)

        // Verify accumulator was cleared by checking partial upward/downward state doesn't leak
        controller.onPostScroll(consumedX = 0f, consumedY = -20f, availableY = 0f)
        assertTrue(controller.isVisible)
    }

    @Test
    fun sideEffectUpwardFlingRevealsHiddenFabAfterCrossingShowThreshold() {
        val controller = FabScrollVisibilityController(hideThresholdPx = 24f, showThresholdPx = 12f)
        controller.nestedScrollConnection.onPostScroll(
            consumed = Offset(0f, -30f),
            available = Offset.Zero,
            source = NestedScrollSource.UserInput,
        )
        assertFalse(controller.isVisible)

        // Short touch flick (< 12px) followed by SideEffect fling momentum frames
        controller.nestedScrollConnection.onPostScroll(
            consumed = Offset(0f, 8f),
            available = Offset.Zero,
            source = NestedScrollSource.UserInput,
        )
        assertFalse(controller.isVisible)

        controller.nestedScrollConnection.onPostScroll(
            consumed = Offset(0f, 6f),
            available = Offset.Zero,
            source = NestedScrollSource.SideEffect,
        )
        assertTrue(controller.isVisible)
    }

    @Test
    fun sideEffectDownwardFlingNeverHidesVisibleFab() {
        val controller = FabScrollVisibilityController(hideThresholdPx = 24f, showThresholdPx = 12f)
        assertTrue(controller.isVisible)

        controller.nestedScrollConnection.onPostScroll(
            consumed = Offset(0f, -60f),
            available = Offset.Zero,
            source = NestedScrollSource.SideEffect,
        )
        assertTrue(controller.isVisible)
    }

    @Test
    fun horizontalEdgeOverscrollDoesNotTriggerTopReveal() {
        val controller = FabScrollVisibilityController(hideThresholdPx = 24f, showThresholdPx = 12f)
        controller.onPostScroll(consumedX = 0f, consumedY = -30f, availableY = 0f)
        assertFalse(controller.isVisible)

        controller.onPostScroll(
            consumedX = 0f,
            consumedY = 0f,
            availableY = 14f,
            availableX = 40f,
        )
        assertFalse(controller.isVisible)
    }

    @Test
    fun nonPositiveOrNonFiniteThresholdsThrow() {
        assertThrows(IllegalArgumentException::class.java) {
            FabScrollVisibilityController(hideThresholdPx = 0f, showThresholdPx = 12f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FabScrollVisibilityController(hideThresholdPx = 24f, showThresholdPx = -1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FabScrollVisibilityController(hideThresholdPx = Float.NaN, showThresholdPx = 12f)
        }
    }
}
