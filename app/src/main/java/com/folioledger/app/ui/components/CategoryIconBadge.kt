// File: app/src/main/java/com/folioledger/app/ui/components/CategoryIconBadge.kt
package com.folioledger.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import com.folioledger.app.domain.model.CategoryType
import com.folioledger.app.ui.iconpack.EmojiIconResolver
import com.folioledger.app.ui.iconpack.FolioIconPackTheme
import com.folioledger.app.ui.iconpack.FolioIconResolver
import com.folioledger.app.ui.iconpack.LocalFolioIconPackTheme
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

val ALL_CATEGORY_ICON_KEYS: List<String> = listOf(
    // Food & Dining (8)
    "restaurant",
    "fastfood",
    "local_cafe",
    "local_bar",
    "bakery_dining",
    "lunch_dining",
    "icecream",
    "kitchen",
    // Groceries & Home (13)
    "shopping_cart",
    "shopping_bag",
    "storefront",
    "local_mall",
    "cleaning_services",
    "chair",
    "weekend",
    "yard",
    "plumbing",
    "handyman",
    "build",
    "home",
    "apartment",
    // Utilities & Bills (9)
    "bolt",
    "water_drop",
    "local_fire_department",
    "wifi",
    "phone_android",
    "router",
    "tv",
    "ac_unit",
    "security",
    // Transport & Travel (13)
    "directions_car",
    "two_wheeler",
    "local_gas_station",
    "ev_station",
    "directions_bus",
    "train",
    "subway",
    "local_taxi",
    "flight",
    "hotel",
    "luggage",
    "beach_access",
    "commute",
    // Health & Care (7)
    "local_hospital",
    "medical_services",
    "medication",
    "fitness_center",
    "self_improvement",
    "spa",
    "content_cut",
    // Lifestyle & Family (16)
    "movie",
    "theaters",
    "music_note",
    "headphones",
    "sports_esports",
    "sports_soccer",
    "celebration",
    "cake",
    "school",
    "menu_book",
    "child_care",
    "toys",
    "pets",
    "checkroom",
    "watch",
    "diamond",
    // Finance & Work (20)
    "payments",
    "account_balance",
    "account_balance_wallet",
    "credit_card",
    "savings",
    "trending_up",
    "show_chart",
    "work",
    "business_center",
    "computer",
    "receipt_long",
    "request_quote",
    "gavel",
    "volunteer_activism",
    "card_giftcard",
    "redeem",
    "currency_rupee",
    "attach_money",
    "percent",
    "category",
)

fun suggestIconAndColorForCategoryName(
    name: String,
    type: CategoryType = CategoryType.EXPENSE,
): Pair<String, String>? = com.folioledger.app.domain.util.CategorySuggestionHelper.suggestIconAndColorForCategoryName(name, type)

private val hexColorCache = ConcurrentHashMap<String, Color>()
private val folioIconCache = ConcurrentHashMap<String, ImageVector>()

fun parseHexColorSafely(colorHex: String?, fallback: Color = Color(0xFF059669)): Color {
    if (colorHex.isNullOrBlank()) return fallback
    hexColorCache[colorHex]?.let { return it }
    val trimmed = colorHex.trim()
    val normalized = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
    return try {
        val parsed = Color(normalized.toColorInt())
        hexColorCache.putIfAbsent(colorHex, parsed)
        parsed
    } catch (_: IllegalArgumentException) {
        fallback
    }
}

fun resolveFolioIcon(
    iconName: String?,
    packTheme: FolioIconPackTheme = FolioIconPackTheme.MATERIAL_FILLED
): ImageVector {
    val normalizedKey = iconName?.trim()?.lowercase(Locale.US).takeUnless { it.isNullOrBlank() } ?: "category"
    val cacheKey = "${packTheme.key}_$normalizedKey"
    return folioIconCache.getOrPut(cacheKey) {
        FolioIconResolver.resolve(normalizedKey, packTheme)
    }
}

@Composable
fun CategoryIconBadge(
    iconName: String?,
    colorHex: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    contentDescription: String? = null
) {
    val density = LocalDensity.current
    val primaryFallback = MaterialTheme.colorScheme.primary
    val baseColor = remember(colorHex, primaryFallback) {
        parseHexColorSafely(colorHex, fallback = primaryFallback)
    }
    val iconPack = LocalFolioIconPackTheme.current
    val isDirectEmoji = remember(iconName) {
        EmojiIconResolver.isDirectEmoji(iconName)
    }
    val renderAsEmoji = iconPack.isEmoji || isDirectEmoji

    val shape = remember(iconPack.isSquircle || isDirectEmoji, size) {
        if (iconPack.isSquircle || isDirectEmoji) {
            RoundedCornerShape(size * 0.28f)
        } else {
            CircleShape
        }
    }

    if (renderAsEmoji) {
        val emoji = remember(iconName) {
            EmojiIconResolver.resolve(iconName)
        }
        val emojiFontSize = with(density) { (iconSize * 1.05f).toSp() }

        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(baseColor.copy(alpha = 0.18f))
                .border(BorderStroke(1.dp, baseColor.copy(alpha = 0.25f)), shape)
                .semantics {
                    if (contentDescription != null) {
                        this.contentDescription = contentDescription
                    } else {
                        hideFromAccessibility()
                    }
                }
        ) {
            Text(
                text = emoji,
                fontSize = emojiFontSize,
                textAlign = TextAlign.Center
            )
        }
    } else {
        val iconVector = remember(iconName, iconPack) {
            resolveFolioIcon(iconName, iconPack)
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(baseColor.copy(alpha = if (iconPack.isOutlined) 0.12f else 0.16f))
                .then(
                    if (iconPack.isOutlined) {
                        Modifier.border(BorderStroke(1.dp, baseColor.copy(alpha = 0.35f)), shape)
                    } else {
                        Modifier
                    }
                )
        ) {
            Icon(
                imageVector = iconVector,
                contentDescription = contentDescription,
                tint = baseColor,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}
