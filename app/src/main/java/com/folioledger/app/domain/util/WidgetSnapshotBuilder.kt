package com.folioledger.app.domain.util

import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.CardDueReminderItem
import com.folioledger.app.domain.model.CategoryNode
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.SafeToSpendState
import com.folioledger.app.domain.model.SafeToSpendStatus
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionBookmark

enum class WidgetStatusTone {
    SAFE,
    WARNING,
    CRITICAL,
    NEUTRAL
}

data class SafeToSpendWidgetSnapshot(
    val formattedSafeToSpend: String,
    val rawAmountMinor: Long,
    val statusLabel: String,
    val statusTone: WidgetStatusTone,
    val secondarySummary: String,
    val nextCardDueSummary: String?,
    val isDemoMode: Boolean,
    val isMasked: Boolean,
    val updatedTimeLabel: String
)

data class QuickBookmarkWidgetItem(
    val id: Long,
    val title: String,
    val formattedAmount: String?,
    val iconName: String,
    val colorHex: String
)

data class QuickAddWidgetSnapshot(
    val bookmarks: List<QuickBookmarkWidgetItem>,
    val isDemoMode: Boolean
)

object WidgetSnapshotBuilder {

    const val MASKED_AMOUNT_TEXT = "••••••"
    const val MASKED_SUMMARY_TEXT = "Balances hidden"

    fun buildSafeToSpendSnapshot(
        safeToSpendState: SafeToSpendState,
        currencySpec: CurrencySpec,
        grouping: NumberGroupingSystem,
        nextCardDue: CardDueReminderItem? = null,
        isMasked: Boolean = false,
        isDemoMode: Boolean = false,
        updatedTimeLabel: String = ""
    ): SafeToSpendWidgetSnapshot {
        val rawMinor = safeToSpendState.trueSafeToSpend.amountMinor

        val formattedAmount = if (isMasked) {
            MASKED_AMOUNT_TEXT
        } else {
            MoneyFormatterAndCalculator.formatMinorMoney(
                money = safeToSpendState.trueSafeToSpend,
                currencySpec = currencySpec,
                grouping = grouping,
                showPlusSignForPositive = false
            )
        }

        val (statusLabel, statusTone) = when (safeToSpendState.status) {
            SafeToSpendStatus.SAFE -> "Safe Discretionary Cash" to WidgetStatusTone.SAFE
            SafeToSpendStatus.CARD_DEBT_EXCEEDS_CASH -> "Card Debt Exceeds Cash" to WidgetStatusTone.CRITICAL
            SafeToSpendStatus.BILLS_EXCEED_CASH -> "Bills Exceed Cash" to WidgetStatusTone.WARNING
            SafeToSpendStatus.BILLS_EXCEED_REMAINING_CASH -> "Bills Exceed Remaining Cash" to WidgetStatusTone.WARNING
            SafeToSpendStatus.COMBINED_OBLIGATIONS_EXCEED_CASH -> "Debt & Bills Exceed Cash" to WidgetStatusTone.CRITICAL
            SafeToSpendStatus.ZERO_RESERVES -> "Zero Liquid Cash" to WidgetStatusTone.NEUTRAL
        }

        val secondarySummary = if (isMasked) {
            MASKED_SUMMARY_TEXT
        } else {
            val liquidFormatted = "${currencySpec.symbol}${MoneyFormatterAndCalculator.formatCompactMinorMoney(safeToSpendState.totalLiquidBankAndCash, currencySpec, grouping)}"
            val ccFormatted = "${currencySpec.symbol}${MoneyFormatterAndCalculator.formatCompactMinorMoney(safeToSpendState.totalCreditCardDebt, currencySpec, grouping)}"
            val billsFormatted = "${currencySpec.symbol}${MoneyFormatterAndCalculator.formatCompactMinorMoney(safeToSpendState.upcoming14DayCommitments, currencySpec, grouping)}"
            "Cash $liquidFormatted · Cards $ccFormatted · Bills $billsFormatted"
        }

        val nextCardDueSummary = nextCardDue?.let { card ->
            val dueDays = card.daysRemaining
            val dueText = when {
                card.isOverdue -> "Overdue"
                dueDays <= 0L -> "Due today"
                dueDays == 1L -> "Due in 1d"
                else -> "Due in ${dueDays}d"
            }

            if (isMasked) {
                "${card.accountName}: $dueText"
            } else {
                val cardSpec = SupportedCurrencies.fromCode(card.currencyCode)
                val amtFormatted = "${cardSpec.symbol}${MoneyFormatterAndCalculator.formatCompactMinorMoney(card.amountDue, cardSpec, grouping)}"
                "${card.accountName}: $dueText ($amtFormatted)"
            }
        }

        return SafeToSpendWidgetSnapshot(
            formattedSafeToSpend = formattedAmount,
            rawAmountMinor = rawMinor,
            statusLabel = statusLabel,
            statusTone = statusTone,
            secondarySummary = secondarySummary,
            nextCardDueSummary = nextCardDueSummary,
            isDemoMode = isDemoMode,
            isMasked = isMasked,
            updatedTimeLabel = updatedTimeLabel
        )
    }

    fun buildQuickAddSnapshot(
        bookmarks: List<TransactionBookmark>,
        accounts: Map<Long, AccountSummary> = emptyMap(),
        categories: Map<Long, CategoryNode> = emptyMap(),
        primaryCurrencySpec: CurrencySpec,
        grouping: NumberGroupingSystem,
        isDemoMode: Boolean = false,
        limit: Int = 3
    ): QuickAddWidgetSnapshot {
        val items = bookmarks.take(limit).map { bm ->
            val account = accounts[bm.accountId]
            val category = bm.categoryId?.let { categories[it] }

            val itemSpec = account?.currencyCode?.let { SupportedCurrencies.fromCode(it) } ?: primaryCurrencySpec

            val formattedAmt = if (bm.amount.isPositive) {
                "${itemSpec.symbol}${MoneyFormatterAndCalculator.formatCompactMinorMoney(
                    money = bm.amount,
                    currencySpec = itemSpec,
                    grouping = grouping
                )}"
            } else null

            val icon = category?.iconName ?: account?.iconName ?: "star"
            val color = category?.colorHex ?: account?.colorHex ?: "#059669"

            QuickBookmarkWidgetItem(
                id = bm.id,
                title = bm.title,
                formattedAmount = formattedAmt,
                iconName = icon,
                colorHex = color
            )
        }

        return QuickAddWidgetSnapshot(
            bookmarks = items,
            isDemoMode = isDemoMode
        )
    }
}
