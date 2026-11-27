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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Pure state machine governing Google Drive–style FAB hide-on-scroll-down /
 * reveal-on-scroll-up transitions with asymmetric directional hysteresis.
 */
@Stable
class FabScrollVisibilityController(
    private val hideThresholdPx: Float,
    private val showThresholdPx: Float
) {
    init {
        require(hideThresholdPx > 0f && hideThresholdPx.isFinite()) {
            "hideThresholdPx must be finite and > 0"
        }
        require(showThresholdPx > 0f && showThresholdPx.isFinite()) {
            "showThresholdPx must be finite and > 0"
        }
    }

    var isVisible: Boolean by mutableStateOf(true)
        private set

    private var accumulatedDeltaY: Float = 0f

    fun show() {
        accumulatedDeltaY = 0f
        if (!isVisible) isVisible = true
    }

    /**
     * Processes a post-scroll step from Compose's NestedScrollConnection.
     * In Compose, scrolling down a list produces negative Y deltas (`consumedY < 0f`),
     * and scrolling up produces positive Y deltas (`consumedY > 0f`).
     */
    fun onPostScroll(
        consumedX: Float,
        consumedY: Float,
        availableY: Float,
        availableX: Float = 0f
    ) {
        // Ignore predominantly horizontal swipes (e.g., HorizontalPager tab drags)
        if (abs(consumedY) < abs(consumedX)) return

        // If hidden and the user pulls downward at the top edge (overscroll), reveal FAB
        if (
            !isVisible &&
            consumedY == 0f &&
            availableY > 0f &&
            abs(availableY) >= abs(availableX)
        ) {
            accumulatedDeltaY = if (accumulatedDeltaY < 0f) availableY else accumulatedDeltaY + availableY
            if (accumulatedDeltaY >= showThresholdPx) {
                isVisible = true
                accumulatedDeltaY = 0f
            }
            return
        }

        if (consumedY == 0f) return

        // Reset accumulator whenever scroll direction reverses
        if ((consumedY < 0f && accumulatedDeltaY > 0f) || (consumedY > 0f && accumulatedDeltaY < 0f)) {
            accumulatedDeltaY = 0f
        }

        accumulatedDeltaY += consumedY

        if (isVisible && accumulatedDeltaY <= -hideThresholdPx) {
            isVisible = false
            accumulatedDeltaY = 0f
        } else if (!isVisible && accumulatedDeltaY >= showThresholdPx) {
            isVisible = true
            accumulatedDeltaY = 0f
        }
    }

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource
        ): Offset {
            val isUserInput = source == NestedScrollSource.UserInput
            val isUpwardRevealFling =
                source == NestedScrollSource.SideEffect && !isVisible && consumed.y > 0f
            if (isUserInput || isUpwardRevealFling) {
                this@FabScrollVisibilityController.onPostScroll(
                    consumedX = consumed.x,
                    consumedY = consumed.y,
                    availableY = available.y,
                    availableX = available.x
                )
            }
            return Offset.Zero
        }
    }
}

@Composable
fun rememberFabScrollVisibilityController(
    hideThreshold: Dp = 24.dp,
    showThreshold: Dp = 12.dp
): FabScrollVisibilityController {
    val density = LocalDensity.current
    val hidePx = with(density) { hideThreshold.toPx() }
    val showPx = with(density) { showThreshold.toPx() }
    return remember(hidePx, showPx) {
        FabScrollVisibilityController(
            hideThresholdPx = hidePx,
            showThresholdPx = showPx
        )
    }
}
