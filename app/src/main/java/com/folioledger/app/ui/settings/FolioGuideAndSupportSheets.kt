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

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folioledger.app.ui.components.FolioBrandLogoBadge as ExtractedFolioBrandLogoBadge
import com.folioledger.app.ui.settings.sheets.TipJarBottomSheet as ExtractedTipJarBottomSheet
import com.folioledger.app.ui.settings.sheets.UserGuideBottomSheet as ExtractedUserGuideBottomSheet

typealias GuideCategoryTab = com.folioledger.app.ui.settings.guides.GuideCategoryTab

/**
 * Backward-compatible delegating wrapper for Folio's signature app icon badge.
 */
@Composable
fun FolioBrandLogoBadge(
    modifier: Modifier = Modifier,
    size: Dp = 60.dp,
) {
    ExtractedFolioBrandLogoBadge(modifier = modifier, size = size)
}

/**
 * Backward-compatible delegating wrapper for the Folio User Guide & FAQs bottom sheet.
 */
@Composable
fun UserGuideBottomSheet(
    onDismiss: () -> Unit,
) {
    ExtractedUserGuideBottomSheet(onDismiss = onDismiss)
}

/**
 * Backward-compatible delegating wrapper for the Folio Tip Jar & Supporter bottom sheet.
 */
@Composable
fun TipJarBottomSheet(
    isSupporter: Boolean = false,
    supporterTier: String? = null,
    billingState: com.folioledger.app.data.billing.TipJarBillingState = com.folioledger.app.data.billing.TipJarBillingState(),
    onTipSelected: (skuId: String, tierTitle: String) -> Unit,
    onRestorePurchases: () -> Unit = {},
    onRetryConnection: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    ExtractedTipJarBottomSheet(
        isSupporter = isSupporter,
        supporterTier = supporterTier,
        billingState = billingState,
        onTipSelected = onTipSelected,
        onRestorePurchases = onRestorePurchases,
        onRetryConnection = onRetryConnection,
        onDismiss = onDismiss,
    )
}
