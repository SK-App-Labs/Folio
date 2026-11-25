/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folioledger.app.R

/**
 * Renders Folio's signature "Architectural Ledger 'F'" app icon
 * using the official res/drawable app icon assets (ic_launcher_background & ic_launcher_foreground).
 */
@Composable
fun FolioBrandLogoBadge(
    modifier: Modifier = Modifier,
    size: Dp = 60.dp
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.26f))
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_background),
            contentDescription = null,
            modifier = Modifier.matchParentSize()
        )
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "Folio App Icon",
            modifier = Modifier.matchParentSize()
        )
    }
}
