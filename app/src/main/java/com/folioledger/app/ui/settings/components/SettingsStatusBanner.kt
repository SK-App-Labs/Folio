/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.settings.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.ui.components.FolioChromeIcons
import com.folioledger.app.ui.theme.LocalFolioSemanticColors

internal enum class SettingsBannerMode {
    NONE,
    DEMO_SANDBOX,
    SAF_PERMISSION_LOST,
    SAF_KEY_UNAVAILABLE,
    SAF_IO_ERROR,
}

internal fun hasActiveSafMirrorAlert(prefs: UserPreferences): Boolean {
    if (prefs.isDemoModeActive || !prefs.autoBackupEnabled) return false
    val hasSafTree = !prefs.safMirrorTreeUri.isNullOrBlank()
    val errorCode = prefs.safMirrorErrorCode
    if (!hasSafTree || errorCode.isNullOrBlank()) return false
    return prefs.safMirrorEnabled || (prefs.biometricLockEnabled && errorCode == "KEY_UNAVAILABLE")
}

internal fun resolveSettingsBannerMode(prefs: UserPreferences): SettingsBannerMode {
    if (prefs.isDemoModeActive) return SettingsBannerMode.DEMO_SANDBOX
    if (!hasActiveSafMirrorAlert(prefs)) return SettingsBannerMode.NONE
    return when (prefs.safMirrorErrorCode) {
        "PERMISSION_LOST" -> SettingsBannerMode.SAF_PERMISSION_LOST
        "KEY_UNAVAILABLE" -> SettingsBannerMode.SAF_KEY_UNAVAILABLE
        else -> SettingsBannerMode.SAF_IO_ERROR
    }
}

@Composable
fun SettingsStatusBanner(
    prefs: UserPreferences,
    isBusy: Boolean,
    onExitDemoClick: () -> Unit,
    onReconnectSafFolder: () -> Unit,
    onSetSafPassphrase: () -> Unit,
    onRetrySafMirror: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode = resolveSettingsBannerMode(prefs)
    if (mode == SettingsBannerMode.NONE) return

    val warningColor = LocalFolioSemanticColors.current.warningAmber
    val (title, description, ctaLabel, onAction) = when (mode) {
        SettingsBannerMode.DEMO_SANDBOX -> QuadBannerSpec(
            title = "Demo Sandbox Active",
            description = "Auto-backups, SAF folder mirroring, and data exports are paused.",
            ctaLabel = "Exit Demo",
            onClick = onExitDemoClick,
        )

        SettingsBannerMode.SAF_PERMISSION_LOST -> QuadBannerSpec(
            title = "External Folder Needs Attention",
            description = "Write permission for ${prefs.safMirrorFolderDisplayName ?: "your mirror folder"} expired.",
            ctaLabel = "Reconnect",
            onClick = onReconnectSafFolder,
        )

        SettingsBannerMode.SAF_KEY_UNAVAILABLE -> QuadBannerSpec(
            title = "Mirror Encryption Key Unavailable",
            description = "Re-enter your passphrase to resume encrypted .folio mirroring.",
            ctaLabel = "Set Passphrase",
            onClick = onSetSafPassphrase,
        )

        SettingsBannerMode.SAF_IO_ERROR -> QuadBannerSpec(
            title = "External Folder Mirror Failed",
            description = "Could not write the latest snapshot to ${prefs.safMirrorFolderDisplayName ?: "selected folder"}.",
            ctaLabel = "Retry",
            onClick = onRetrySafMirror,
        )

        SettingsBannerMode.NONE -> return
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = warningColor.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, warningColor.copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = FolioChromeIcons.Warning,
                contentDescription = null,
                tint = warningColor,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            FilledTonalButton(
                onClick = onAction,
                enabled = !isBusy,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = ctaLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private data class QuadBannerSpec(
    val title: String,
    val description: String,
    val ctaLabel: String,
    val onClick: () -> Unit,
)
