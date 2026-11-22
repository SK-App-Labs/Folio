package com.folioledger.app.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
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
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.folioledger.app.MainActivity
import com.folioledger.app.ui.theme.DarkFolioSemanticColors
import com.folioledger.app.ui.theme.LightFolioSemanticColors
import com.folioledger.app.ui.theme.resolveFolioGlanceSemanticColor
import org.json.JSONArray

class QuickAddGlanceWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    companion object {
        val COMPACT = DpSize(180.dp, 48.dp)
        val EXPANDED = DpSize(180.dp, 96.dp)
    }

    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, EXPANDED))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val current = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        if (current[FolioWidgetStateKeys.KEY_BOOKMARKS_JSON] == null ||
            current[FolioWidgetStateKeys.KEY_THEME_MODE] == null) {
            FolioWidgetUpdater.refreshAllNow(context)
        }
        provideContent {
            val prefs = currentState<Preferences>()
            val themeMode = prefs[FolioWidgetStateKeys.KEY_THEME_MODE] ?: "SYSTEM"
            val accentPalette = prefs[FolioWidgetStateKeys.KEY_ACCENT_PALETTE] ?: "EMERALD"
            val dynamicColor = prefs[FolioWidgetStateKeys.KEY_DYNAMIC_COLOR] ?: false

            val customColors = com.folioledger.app.ui.theme.resolveFolioGlanceColors(themeMode, accentPalette, dynamicColor)
            if (customColors != null) {
                GlanceTheme(colors = customColors) {
                    QuickAddWidgetContent(context)
                }
            } else {
                GlanceTheme {
                    QuickAddWidgetContent(context)
                }
            }
        }
    }
}

private data class QuickAddBookmark(
    val id: Long,
    val title: String,
    val formattedAmount: String?
)

@Composable
private fun QuickAddWidgetContent(context: Context) {
    val prefs = currentState<Preferences>()
    val size = LocalSize.current
    val isExpanded = size.height.value >= 80f

    val themeMode = prefs[FolioWidgetStateKeys.KEY_THEME_MODE] ?: "SYSTEM"
    val expenseBg = resolveFolioGlanceSemanticColor(
        themeMode,
        LightFolioSemanticColors.expenseCoralContainer,
        DarkFolioSemanticColors.expenseCoralContainer
    )
    val expenseFg = resolveFolioGlanceSemanticColor(
        themeMode,
        LightFolioSemanticColors.onExpenseCoralContainer,
        DarkFolioSemanticColors.onExpenseCoralContainer
    )
    val incomeBg = resolveFolioGlanceSemanticColor(
        themeMode,
        LightFolioSemanticColors.incomeEmeraldContainer,
        DarkFolioSemanticColors.incomeEmeraldContainer
    )
    val incomeFg = resolveFolioGlanceSemanticColor(
        themeMode,
        LightFolioSemanticColors.onIncomeEmeraldContainer,
        DarkFolioSemanticColors.onIncomeEmeraldContainer
    )
    val transferBg = resolveFolioGlanceSemanticColor(
        themeMode,
        LightFolioSemanticColors.transferBlueContainer,
        DarkFolioSemanticColors.transferBlueContainer
    )
    val transferFg = resolveFolioGlanceSemanticColor(
        themeMode,
        LightFolioSemanticColors.onTransferBlueContainer,
        DarkFolioSemanticColors.onTransferBlueContainer
    )

    val bookmarksJson = prefs[FolioWidgetStateKeys.KEY_BOOKMARKS_JSON]
    val bookmarks = parseBookmarks(bookmarksJson)

    val expenseIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        addCategory(Intent.CATEGORY_LAUNCHER)
        identifier = "folio_widget_expense"
        putExtra("shortcut_action", "EXPENSE")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    val incomeIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        addCategory(Intent.CATEGORY_LAUNCHER)
        identifier = "folio_widget_income"
        putExtra("shortcut_action", "INCOME")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    val transferIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        addCategory(Intent.CATEGORY_LAUNCHER)
        identifier = "folio_widget_transfer"
        putExtra("shortcut_action", "TRANSFER")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    val primaryButtonHeight = if (isExpanded) 42.dp else 38.dp
    val secondaryButtonHeight = if (isExpanded) 36.dp else 34.dp

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(16.dp)
            .padding(8.dp)
    ) {
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Primary Quick Add Buttons
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                QuickActionButton(
                    label = "− Expense",
                    intent = expenseIntent,
                    backgroundColor = expenseBg,
                    contentColor = expenseFg,
                    height = primaryButtonHeight,
                    modifier = GlanceModifier.defaultWeight()
                )

                Spacer(modifier = GlanceModifier.width(6.dp))

                QuickActionButton(
                    label = "+ Income",
                    intent = incomeIntent,
                    backgroundColor = incomeBg,
                    contentColor = incomeFg,
                    height = primaryButtonHeight,
                    modifier = GlanceModifier.defaultWeight()
                )

                Spacer(modifier = GlanceModifier.width(6.dp))

                QuickActionButton(
                    label = "⇄ Transfer",
                    intent = transferIntent,
                    backgroundColor = transferBg,
                    contentColor = transferFg,
                    height = primaryButtonHeight,
                    modifier = GlanceModifier.defaultWeight()
                )
            }

            // Expanded 4x2 view: Bookmarks row or 1-tap preset prompt
            if (isExpanded) {
                Spacer(modifier = GlanceModifier.height(6.dp))

                if (bookmarks.isNotEmpty()) {
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        bookmarks.take(3).forEachIndexed { index, bookmark ->
                            if (index > 0) {
                                Spacer(modifier = GlanceModifier.width(6.dp))
                            }
                            BookmarkActionButton(
                                context = context,
                                bookmark = bookmark,
                                height = secondaryButtonHeight,
                                modifier = GlanceModifier.defaultWeight()
                            )
                        }
                    }
                } else {
                    val promoIntent = Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_MAIN
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        identifier = "folio_widget_bookmark_promo"
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }

                    Box(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .height(secondaryButtonHeight)
                            .background(GlanceTheme.colors.surfaceVariant)
                            .cornerRadius(10.dp)
                            .clickable(actionStartActivity(promoIntent)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Save Bookmarks in Folio for 1-tap presets",
                            style = TextStyle(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal,
                                color = GlanceTheme.colors.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            ),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionButton(
    label: String,
    intent: Intent,
    backgroundColor: androidx.glance.unit.ColorProvider,
    contentColor: androidx.glance.unit.ColorProvider,
    height: Dp,
    modifier: GlanceModifier
) {
    Box(
        modifier = modifier
            .height(height)
            .background(backgroundColor)
            .cornerRadius(10.dp)
            .clickable(actionStartActivity(intent)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                textAlign = TextAlign.Center
            ),
            maxLines = 1
        )
    }
}

@Composable
private fun BookmarkActionButton(
    context: Context,
    bookmark: QuickAddBookmark,
    height: Dp,
    modifier: GlanceModifier
) {
    val bookmarkIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        addCategory(Intent.CATEGORY_LAUNCHER)
        identifier = "folio_widget_bookmark_${bookmark.id}"
        putExtra("widget_bookmark_id", bookmark.id)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    val displayLabel = if (bookmark.formattedAmount != null) {
        "${bookmark.title} ${bookmark.formattedAmount}"
    } else {
        bookmark.title
    }

    Box(
        modifier = modifier
            .height(height)
            .background(GlanceTheme.colors.primaryContainer)
            .cornerRadius(10.dp)
            .clickable(actionStartActivity(bookmarkIntent)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = displayLabel,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = GlanceTheme.colors.onPrimaryContainer,
                textAlign = TextAlign.Center
            ),
            maxLines = 1
        )
    }
}

private fun parseBookmarks(json: String?): List<QuickAddBookmark> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(json)
        val list = mutableListOf<QuickAddBookmark>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val id = obj.getLong("id")
            val title = obj.getString("title")
            val amount = if (obj.has("amount") && !obj.isNull("amount")) obj.getString("amount") else null
            list.add(QuickAddBookmark(id, title, amount))
        }
        list
    }.getOrDefault(emptyList())
}
