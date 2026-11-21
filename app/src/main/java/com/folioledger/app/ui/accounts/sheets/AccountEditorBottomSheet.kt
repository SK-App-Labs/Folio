package com.folioledger.app.ui.accounts.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.ui.accounts.AccountEditorDialogState
import com.folioledger.app.ui.accounts.AccountsViewModel
import com.folioledger.app.ui.accounts.CreditCardCycleCalculator
import com.folioledger.app.ui.accounts.components.AccountAppearanceCustomizerCard
import com.folioledger.app.ui.accounts.components.AccountBalanceAndDetailsSection
import com.folioledger.app.ui.accounts.components.AccountCurrencyPickerCard
import com.folioledger.app.ui.accounts.components.AccountEditorActionButtons
import com.folioledger.app.ui.accounts.components.AccountGroupPickerSection
import com.folioledger.app.ui.accounts.components.formatAccountTypeSingularTitle
import com.folioledger.app.ui.components.CategoryIconBadge
import com.folioledger.app.ui.theme.LocalFolioSemanticColors
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountEditorBottomSheet(
    state: AccountEditorDialogState,
    referenceDateEpochDay: Long,
    parentBankCandidates: List<AccountSummary>,
    primaryCurrencyCode: String = "INR",
    secondaryCurrencies: List<SecondaryCurrency> = emptyList(),
    isSaving: Boolean,
    onUpdateState: ((AccountEditorDialogState) -> AccountEditorDialogState) -> Unit,
    onSave: () -> Unit,
    onToggleArchive: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val semanticColors = LocalFolioSemanticColors.current

    var isForeignCurrencyExpanded by rememberSaveable { mutableStateOf(state.currencyCode != primaryCurrencyCode) }
    var isAppearanceCustomizerExpanded by rememberSaveable { mutableStateOf(false) }

    var localName by rememberSaveable(state.accountId) { mutableStateOf(state.name) }
    LaunchedEffect(state.name) {
        if (state.name != localName) {
            localName = state.name
        }
    }

    val handleGroupSelection: (AccountGroup) -> Unit = { group ->
        onUpdateState { current ->
            val updatedAnchorEpochDay = if (group == AccountGroup.CREDIT_CARD) {
                CreditCardCycleCalculator.computeCycleWindow(
                    referenceDateEpochDay = referenceDateEpochDay,
                    settlementDayOfMonth = current.settlementDayOfMonth,
                    paymentDueDayOfMonth = current.paymentDueDayOfMonth
                ).lastStatementEnd.toEpochDay()
            } else if (current.groupType == AccountGroup.CREDIT_CARD) {
                LocalDate.now().toEpochDay()
            } else {
                current.openingDateEpochDay
            }

            val initialParent = if (group == AccountGroup.DEBIT_CARD) {
                current.parentAccountId ?: parentBankCandidates.firstOrNull()?.id
            } else {
                null
            }
            val initialCurrency = if (group == AccountGroup.DEBIT_CARD && initialParent != null) {
                parentBankCandidates.firstOrNull { it.id == initialParent }?.currencyCode ?: current.currencyCode
            } else {
                current.currencyCode
            }
            current.copy(
                groupType = group,
                openingDateEpochDay = updatedAnchorEpochDay,
                iconName = AccountsViewModel.defaultIconForGroup(group),
                colorHex = AccountsViewModel.defaultColorForGroup(group),
                parentAccountId = initialParent,
                currencyCode = initialCurrency
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
        ) {
            // Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryIconBadge(
                        iconName = state.iconName,
                        colorHex = state.colorHex,
                        size = 40.dp,
                        iconSize = 20.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (state.isEditing) "Edit Account" else "New Account",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close"
                    )
                }
            }

            if (!state.validationError.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = semanticColors.expenseCoralContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = state.validationError,
                        style = MaterialTheme.typography.labelMedium,
                        color = semanticColors.onExpenseCoralContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // 1. Account Name
            OutlinedTextField(
                value = localName,
                onValueChange = { newName ->
                    localName = newName
                    onUpdateState { it.copy(name = newName) }
                },
                label = { Text("Account Name") },
                placeholder = { Text("e.g., Travel Rewards Card, Primary Checking") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 2. Account Group Selector (Locked when editing to preserve ledger structure)
            if (!state.isEditing) {
                AccountGroupPickerSection(
                    selectedGroup = state.groupType,
                    onGroupSelected = handleGroupSelection
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Account Type",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = formatAccountTypeSingularTitle(state.groupType),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = if (state.groupType.isLiabilityByDefault) "Liability" else "Asset",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 3. Adaptive Account Details (Credit Card, Debit Card, or Starting/Live Balance)
            AccountBalanceAndDetailsSection(
                state = state,
                referenceDateEpochDay = referenceDateEpochDay,
                parentBankCandidates = parentBankCandidates,
                onUpdateState = onUpdateState
            )

            HorizontalDivider(color = semanticColors.cardStrokeSubtle)

            // 4. Active Currency Selector (Locked when editing or when linked to a parent bank account)
            if (!state.isEditing && state.groupType != AccountGroup.DEBIT_CARD) {
                AccountCurrencyPickerCard(
                    currencyCode = state.currencyCode,
                    primaryCurrencyCode = primaryCurrencyCode,
                    secondaryCurrencies = secondaryCurrencies,
                    isExpanded = isForeignCurrencyExpanded,
                    onToggleExpanded = { isForeignCurrencyExpanded = !isForeignCurrencyExpanded },
                    onSelectCurrency = { code -> onUpdateState { it.copy(currencyCode = code) } }
                )
            } else {
                val spec = SupportedCurrencies.fromCode(state.currencyCode)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, semanticColors.cardStrokeSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Account Currency",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${spec.code} · ${spec.displayName}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = spec.symbol,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 5. Collapsible Appearance Customizer (Badge Color & Icon)
            AccountAppearanceCustomizerCard(
                colorHex = state.colorHex,
                iconName = state.iconName,
                isExpanded = isAppearanceCustomizerExpanded,
                onToggleExpanded = { isAppearanceCustomizerExpanded = !isAppearanceCustomizerExpanded },
                onSelectColor = { hex -> onUpdateState { it.copy(colorHex = hex) } },
                onSelectIcon = { iconKey -> onUpdateState { it.copy(iconName = iconKey) } }
            )

            // 6. Net Worth Switch
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Include in Net Worth Overview",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Counts this account towards total Assets or Liabilities",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.includeInNetWorth,
                    onCheckedChange = { checked ->
                        onUpdateState { it.copy(includeInNetWorth = checked) }
                    }
                )
            }

            // 7. Pin to Top (Primary Account)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Pin Account to Top",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Prioritize at the top of your accounts list and the quick transaction bar",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.isPinned,
                    onCheckedChange = { checked ->
                        onUpdateState { it.copy(isPinned = checked) }
                    }
                )
            }

            // 8. Action Buttons
            AccountEditorActionButtons(
                isEditing = state.isEditing,
                isArchived = state.isArchived,
                isSaving = isSaving,
                onDelete = onDelete,
                onToggleArchive = onToggleArchive,
                onSave = onSave
            )
        }
    }
}
