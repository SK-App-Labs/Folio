/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.folioledger.app.MainActivity
import com.folioledger.app.R
import com.folioledger.app.domain.util.WidgetSnapshotBuilder

private data class SafeToSpendWidgetUiData(
    val safeAmount: String,
    val isPrivacyLocked: Boolean,
    val isMasked: Boolean,
    val statusLabel: String,
    val statusTone: String,
    val secondarySummary: String,
    val nextCardDue: String?,
    val nextCardDueMasked: String?,
    val isDemoMode: Boolean,
    val themeMode: String,
)

class SafeToSpendGlanceWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    companion object {
        val SMALL_SQUARE = DpSize(140.dp, 90.dp)
        val STANDARD = DpSize(240.dp, 100.dp)
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL_SQUARE, STANDARD))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        FolioWidgetUpdater.populateStateIfMissing(context, id)
        provideContent {
            val prefs = currentState<Preferences>()
            val themeMode = prefs[FolioWidgetStateKeys.KEY_THEME_MODE] ?: "SYSTEM"
            val accentPalette = prefs[FolioWidgetStateKeys.KEY_ACCENT_PALETTE] ?: "EMERALD"
            val dynamicColor = prefs[FolioWidgetStateKeys.KEY_DYNAMIC_COLOR] ?: false

            val customColors = com.folioledger.app.ui.theme.resolveFolioGlanceColors(
                themeMode = themeMode,
                accentPalette = accentPalette,
                dynamicColorEnabled = dynamicColor,
                context = context,
            )
            val isPrivacyLocked = prefs[FolioWidgetStateKeys.KEY_IS_PRIVACY_LOCKED] ?: false
            val uiData = SafeToSpendWidgetUiData(
                safeAmount = prefs[FolioWidgetStateKeys.KEY_SAFE_AMOUNT] ?: "\u2014",
                isPrivacyLocked = isPrivacyLocked,
                isMasked = (prefs[FolioWidgetStateKeys.KEY_IS_MASKED] ?: false) || isPrivacyLocked,
                statusLabel = prefs[FolioWidgetStateKeys.KEY_STATUS_LABEL] ?: "Safe to Spend",
                statusTone = prefs[FolioWidgetStateKeys.KEY_STATUS_TONE] ?: "SAFE",
                secondarySummary = prefs[FolioWidgetStateKeys.KEY_SECONDARY_SUMMARY] ?: "",
                nextCardDue = prefs[FolioWidgetStateKeys.KEY_NEXT_CARD_DUE],
                nextCardDueMasked = prefs[FolioWidgetStateKeys.KEY_NEXT_CARD_DUE_MASKED],
                isDemoMode = prefs[FolioWidgetStateKeys.KEY_IS_DEMO_MODE] ?: false,
                themeMode = themeMode,
            )
            if (customColors != null) {
                GlanceTheme(colors = customColors) {
                    SafeToSpendWidgetBody(context = context, data = uiData)
                }
            } else {
                GlanceTheme {
                    SafeToSpendWidgetBody(context = context, data = uiData)
                }
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val themeConfig = FolioWidgetUpdater.readThemeSnapshotForPreview(context)
        val previewData = SafeToSpendWidgetUiData(
            safeAmount = "$1,250.00",
            isPrivacyLocked = themeConfig.privacyEnforced,
            isMasked = themeConfig.privacyEnforced,
            statusLabel = "Safe to Spend",
            statusTone = if (themeConfig.privacyEnforced) "NEUTRAL" else "SAFE",
            secondarySummary = "Liquid \$2,400 \u00B7 Bills \$1,150",
            nextCardDue = if (themeConfig.privacyEnforced) null else "Visa due in 5d",
            nextCardDueMasked = if (themeConfig.privacyEnforced) null else "Card due in 5d",
            isDemoMode = false,
            themeMode = themeConfig.themeMode,
        )
        provideContent {
            val customColors = com.folioledger.app.ui.theme.resolveFolioGlanceColors(
                themeMode = themeConfig.themeMode,
                accentPalette = themeConfig.accentPalette,
                dynamicColorEnabled = themeConfig.dynamicColorEnabled,
                context = context,
            )
            if (customColors != null) {
                GlanceTheme(colors = customColors) {
                    SafeToSpendWidgetBody(context = context, data = previewData)
                }
            } else {
                GlanceTheme {
                    SafeToSpendWidgetBody(context = context, data = previewData)
                }
            }
        }
    }
}

@Composable
private fun SafeToSpendWidgetBody(
    context: Context,
    data: SafeToSpendWidgetUiData,
) {
    val safeAmount = data.safeAmount
    val isPrivacyLocked = data.isPrivacyLocked
    val isMasked = data.isMasked
    val rawStatusLabel = data.statusLabel
    val rawStatusTone = data.statusTone
    val statusLabel = if (isMasked) "Safe to Spend" else rawStatusLabel
    val statusTone = if (isMasked) "NEUTRAL" else rawStatusTone
    val secondarySummary = data.secondarySummary
    val nextCardDue = data.nextCardDue
    val nextCardDueMasked = data.nextCardDueMasked
    val isDemoMode = data.isDemoMode
    val size = LocalSize.current

    val themeMode = data.themeMode
    val isExpanded = size.width.value >= 240f && size.height.value >= 100f

    val (dayColor, nightColor) = when (statusTone) {
        "SAFE" -> Color(0xFF059669) to Color(0xFF34D399)
        "WARNING" -> Color(0xFFD97706) to Color(0xFFFBBF24)
        "CRITICAL" -> Color(0xFFE11D48) to Color(0xFFF87171)
        else -> Color(0xFF64748B) to Color(0xFF94A3B8)
    }

    val amountColor = when (com.folioledger.app.ui.theme.FolioThemeMode.fromKey(themeMode)) {
        com.folioledger.app.ui.theme.FolioThemeMode.LIGHT,
        com.folioledger.app.ui.theme.FolioThemeMode.SEPIA,
        ->
            androidx.glance.unit.ColorProvider(dayColor)

        com.folioledger.app.ui.theme.FolioThemeMode.DARK,
        com.folioledger.app.ui.theme.FolioThemeMode.SLATE_GRAY,
        com.folioledger.app.ui.theme.FolioThemeMode.AMOLED_BLACK,
        ->
            androidx.glance.unit.ColorProvider(nightColor)

        com.folioledger.app.ui.theme.FolioThemeMode.SYSTEM ->
            ColorProvider(day = dayColor, night = nightColor)
    }

    val openAppIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        addCategory(Intent.CATEGORY_LAUNCHER)
        identifier = "folio_widget_open_app"
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    val openAddExpenseIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        addCategory(Intent.CATEGORY_LAUNCHER)
        identifier = "folio_widget_safe_add"
        putExtra("shortcut_action", "EXPENSE")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity(openAppIntent)),
    ) {
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.Top,
            horizontalAlignment = Alignment.Start,
        ) {
            // Header Row: Status Label + Demo Tag + Action Icons (Eye Mask + Quick Add)
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isDemoMode) "$statusLabel · DEMO" else statusLabel,
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = GlanceTheme.colors.onSurfaceVariant,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )

                // Privacy Eye Mask Toggle Button (opens app to authenticate when privacy-locked)
                Box(
                    modifier = GlanceModifier
                        .size(32.dp)
                        .cornerRadius(8.dp)
                        .clickable(
                            if (isPrivacyLocked) {
                                actionStartActivity(openAppIntent)
                            } else {
                                actionRunCallback<ToggleWidgetPrivacyAction>()
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(if (isMasked) R.drawable.ic_widget_eye_off else R.drawable.ic_widget_eye),
                        contentDescription = if (isPrivacyLocked) {
                            "Unlock in Folio"
                        } else if (isMasked) {
                            "Reveal amounts"
                        } else {
                            "Mask amounts"
                        },
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                        modifier = GlanceModifier.size(18.dp),
                    )
                }

                Spacer(modifier = GlanceModifier.width(4.dp))

                // + Quick Add Expense (32.dp touch target + tinted)
                Box(
                    modifier = GlanceModifier
                        .size(32.dp)
                        .cornerRadius(8.dp)
                        .clickable(actionStartActivity(openAddExpenseIntent)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_tile_folio_add),
                        contentDescription = "New Expense",
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                        modifier = GlanceModifier.size(18.dp),
                    )
                }
            }

            Spacer(modifier = GlanceModifier.height(4.dp))

            // Hero Amount
            Text(
                text = if (isMasked) WidgetSnapshotBuilder.MASKED_AMOUNT_TEXT else safeAmount,
                style = TextStyle(
                    fontSize = if (isExpanded) 24.sp else 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = amountColor,
                ),
                maxLines = 1,
            )

            // Secondary Details (on standard/expanded 4x2)
            if (isExpanded) {
                Spacer(modifier = GlanceModifier.defaultWeight())

                val breakdownText = if (isMasked) {
                    WidgetSnapshotBuilder.MASKED_SUMMARY_TEXT
                } else {
                    secondarySummary
                }
                val dueText = if (isMasked) nextCardDueMasked else nextCardDue

                if (breakdownText.isNotBlank() || !dueText.isNullOrBlank()) {
                    Column(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .background(GlanceTheme.colors.surfaceVariant)
                            .cornerRadius(10.dp)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        if (breakdownText.isNotBlank()) {
                            Text(
                                text = breakdownText,
                                style = TextStyle(
                                    fontSize = 11.sp,
                                    color = GlanceTheme.colors.onSurfaceVariant,
                                ),
                                maxLines = 1,
                            )
                        }
                        if (!dueText.isNullOrBlank()) {
                            if (breakdownText.isNotBlank()) {
                                Spacer(modifier = GlanceModifier.height(2.dp))
                            }
                            Text(
                                text = dueText,
                                style = TextStyle(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = GlanceTheme.colors.primary,
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
