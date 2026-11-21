package com.folioledger.app.ui.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
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

class SafeToSpendGlanceWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    companion object {
        val SMALL_SQUARE = DpSize(140.dp, 90.dp)
        val STANDARD = DpSize(240.dp, 100.dp)
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL_SQUARE, STANDARD))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                SafeToSpendWidgetContent(context)
            }
        }
    }
}

@Composable
private fun SafeToSpendWidgetContent(context: Context) {
    val prefs = currentState<Preferences>()
    val safeAmount = prefs[FolioWidgetStateKeys.KEY_SAFE_AMOUNT] ?: "₹0.00"
    val statusLabel = prefs[FolioWidgetStateKeys.KEY_STATUS_LABEL] ?: "Safe to Spend"
    val statusTone = prefs[FolioWidgetStateKeys.KEY_STATUS_TONE] ?: "SAFE"
    val secondarySummary = prefs[FolioWidgetStateKeys.KEY_SECONDARY_SUMMARY] ?: ""
    val nextCardDue = prefs[FolioWidgetStateKeys.KEY_NEXT_CARD_DUE]
    val isDemoMode = prefs[FolioWidgetStateKeys.KEY_IS_DEMO_MODE] ?: false
    val isMasked = prefs[FolioWidgetStateKeys.KEY_IS_MASKED] ?: false
    val size = LocalSize.current

    val isExpanded = size.width.value >= 240f && size.height.value >= 100f

    val amountColor = when (statusTone) {
        "SAFE" -> ColorProvider(Color(0xFF059669), Color(0xFF34D399))
        "WARNING" -> ColorProvider(Color(0xFFD97706), Color(0xFFFBBF24))
        "CRITICAL" -> ColorProvider(Color(0xFFE11D48), Color(0xFFF87171))
        else -> ColorProvider(Color(0xFF64748B), Color(0xFF94A3B8))
    }

    val openAppIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    val openAddExpenseIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        data = Uri.parse("folio://widget/safe-to-spend/add")
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
            .clickable(actionStartActivity(openAppIntent))
    ) {
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.Top,
            horizontalAlignment = Alignment.Start
        ) {
            // Header Row: Status Label + Demo Tag + Action Icons (Eye Mask + Quick Add)
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isDemoMode) "$statusLabel · DEMO" else statusLabel,
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = GlanceTheme.colors.onSurfaceVariant
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight()
                )

                // Privacy Eye Mask Toggle Button
                Image(
                    provider = ImageProvider(if (isMasked) R.drawable.ic_widget_eye_off else R.drawable.ic_widget_eye),
                    contentDescription = if (isMasked) "Reveal amounts" else "Mask amounts",
                    modifier = GlanceModifier
                        .size(20.dp)
                        .clickable(actionRunCallback<ToggleWidgetPrivacyAction>())
                )

                Spacer(modifier = GlanceModifier.width(10.dp))

                // + Quick Add Expense
                Image(
                    provider = ImageProvider(R.drawable.ic_tile_folio_add),
                    contentDescription = "New Expense",
                    modifier = GlanceModifier
                        .size(20.dp)
                        .clickable(actionStartActivity(openAddExpenseIntent))
                )
            }

            Spacer(modifier = GlanceModifier.height(4.dp))

            // Hero Amount
            Text(
                text = if (isMasked) WidgetSnapshotBuilder.MASKED_AMOUNT_TEXT else safeAmount,
                style = TextStyle(
                    fontSize = if (isExpanded) 24.sp else 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = amountColor
                ),
                maxLines = 1
            )

            // Secondary Details (on standard/expanded 4x2)
            if (isExpanded) {
                Spacer(modifier = GlanceModifier.height(4.dp))

                val subText = if (isMasked) {
                    WidgetSnapshotBuilder.MASKED_SUMMARY_TEXT
                } else if (!nextCardDue.isNullOrBlank()) {
                    nextCardDue
                } else {
                    secondarySummary
                }

                if (subText.isNotBlank()) {
                    Text(
                        text = subText,
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = GlanceTheme.colors.onSurfaceVariant
                        ),
                        maxLines = 1
                    )
                }
            }
        }
    }
}
