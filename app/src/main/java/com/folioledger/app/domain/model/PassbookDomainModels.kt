// File: app/src/main/java/com/folioledger/app/domain/model/PassbookDomainModels.kt
package com.folioledger.app.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class PassbookPeriodRange(
    val startEpochDay: Long,
    val endEpochDay: Long,
    val periodTitle: String,
    val periodSubtitle: String
)

@Immutable
data class AccountPassbookEntryUiModel(
    val transaction: TransactionItem,
    val signedDelta: MinorMoney,
    val isInflow: Boolean,
    val runningBalanceAfter: MinorMoney,
    val viaLinkedDebitCardName: String? = null
)

@Immutable
data class AccountPassbookDayGroup(
    val epochDay: Long,
    val dateHeaderTitle: String,
    val dayOfMonthText: String,
    val dayOfWeekText: String,
    val monthYearText: String,
    val isToday: Boolean,
    val dayInflowTotal: MinorMoney,
    val dayOutflowTotal: MinorMoney,
    val dayEndRunningBalance: MinorMoney,
    val entries: List<AccountPassbookEntryUiModel>
)

@Immutable
data class AccountPassbookUiModel(
    val account: AccountSummary,
    val parentBankAccount: AccountSummary? = null,
    val linkedDebitCards: List<AccountSummary> = emptyList(),
    val monthOffset: Int = 0,
    val startEpochDay: Long = 0L,
    val endEpochDay: Long = 0L,
    val periodTitle: String = "",
    val periodSubtitle: String = "",
    val currentLiveBalance: MinorMoney = MinorMoney.ZERO,
    val periodOpeningBalance: MinorMoney = MinorMoney.ZERO,
    val periodInflows: MinorMoney = MinorMoney.ZERO,
    val periodOutflows: MinorMoney = MinorMoney.ZERO,
    val periodNetFlow: MinorMoney = MinorMoney.ZERO,
    val periodClosingBalance: MinorMoney = MinorMoney.ZERO,
    val totalPeriodTransactionCount: Int = 0,
    val dayGroups: List<AccountPassbookDayGroup> = emptyList(),
    val entries: List<AccountPassbookEntryUiModel> = emptyList(),
    val investedPrincipal: MinorMoney? = null,
    val unrealizedPnl: MinorMoney? = null,
    val returnPercentage: Float? = null
)
