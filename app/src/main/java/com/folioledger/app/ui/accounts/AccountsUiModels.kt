package com.folioledger.app.ui.accounts

import androidx.compose.runtime.Immutable
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NetWorthOverview
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.AccountPassbookDayGroup
import com.folioledger.app.domain.model.AccountPassbookEntryUiModel
import com.folioledger.app.domain.model.AccountPassbookUiModel
import com.folioledger.app.domain.model.PassbookPeriodRange
import java.time.LocalDate

typealias PassbookPeriodRange = com.folioledger.app.domain.model.PassbookPeriodRange
typealias AccountPassbookEntryUiModel = com.folioledger.app.domain.model.AccountPassbookEntryUiModel
typealias AccountPassbookDayGroup = com.folioledger.app.domain.model.AccountPassbookDayGroup
typealias AccountPassbookUiModel = com.folioledger.app.domain.model.AccountPassbookUiModel

@Immutable
data class AccountEditorDialogState(
    val isEditing: Boolean = false,
    val accountId: Long = 0L,
    val name: String = "",
    val groupType: AccountGroup = AccountGroup.BANK_ACCOUNT,
    val currencyCode: String = "INR",
    val openingBalanceInput: String = "0",
    val openingDateEpochDay: Long = 0L,
    val creditLimitInput: String = "",
    val statementPayableInput: String = "",
    val unbilledAmountInput: String = "",
    val settlementDayOfMonth: Int = 18,
    val paymentDueDayOfMonth: Int = 5,
    val parentAccountId: Long? = null,
    val includeInNetWorth: Boolean = true,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val colorHex: String = "#059669",
    val iconName: String = "account_balance",
    val validationError: String? = null
)

enum class SettlementAmountPreset {
    STATEMENT_PAYABLE,
    FULL_OUTSTANDING,
    CUSTOM
}

@Immutable
data class CreditCardSettlementSheetState(
    val creditCardAccount: AccountSummary,
    val selectedSourceAccountId: Long?,
    val preset: SettlementAmountPreset = SettlementAmountPreset.STATEMENT_PAYABLE,
    val amountInput: String = "",
    val ledgerDateEpochDay: Long = 0L,
    val note: String = "Credit Card Statement Settlement",
    val validationError: String? = null
)

@Immutable
data class ReconcileBalanceDialogState(
    val accountId: Long,
    val accountName: String,
    val currencyCode: String,
    val recordedBalance: MinorMoney,
    val targetBalanceInput: String = "",
    val reconcileDateEpochDay: Long = 0L,
    val note: String = "",
    val validationError: String? = null,
    val isLiability: Boolean = false,
    val isInvestment: Boolean = false
)

@Immutable
data class AccountsUiState(
    val netWorthOverview: NetWorthOverview = NetWorthOverview(
        totalAssets = MinorMoney.ZERO,
        totalLiabilities = MinorMoney.ZERO,
        netWorth = MinorMoney.ZERO,
        accountsByGroup = emptyMap()
    ),
    val allAccounts: List<AccountSummary> = emptyList(),
    val assetAccountsForSettlement: List<AccountSummary> = emptyList(),
    val parentBankCandidates: List<AccountSummary> = emptyList(),
    val recentTransactionsForSelectedAccount: List<TransactionItem> = emptyList(),
    val inspectedAccountId: Long? = null,
    val inspectedAccount: AccountSummary? = null,
    val passbookMonthOffset: Int = 0,
    val accountPassbook: AccountPassbookUiModel? = null,
    val currencySpec: CurrencySpec = SupportedCurrencies.INR,
    val primaryCurrencyCode: String = "INR",
    val secondaryCurrencies: List<SecondaryCurrency> = emptyList(),
    val numberGrouping: NumberGroupingSystem = NumberGroupingSystem.INDIAN,
    val referenceDateEpochDay: Long = LocalDate.now().toEpochDay(),
    val showArchivedAccounts: Boolean = false,
    val collapsedGroups: Set<AccountGroup> = emptySet(),
    val editorDialogState: AccountEditorDialogState? = null,
    val settlementSheetState: CreditCardSettlementSheetState? = null,
    val reconcileDialogState: ReconcileBalanceDialogState? = null,
    val pendingDeletionAccountId: Long? = null,
    val pendingDeletionAccountName: String? = null,
    val pendingDeletionTxCount: Int = 0,
    val pendingDeletionLinkedCardsCount: Int = 0,
    val isSaving: Boolean = false,
    val snackbarMessage: String? = null
)
