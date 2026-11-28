/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.entry

import androidx.compose.runtime.Immutable
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.CategoryNode
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.RepeatFrequency
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionBookmark
import com.folioledger.app.domain.model.TransactionType
import java.text.DecimalFormatSymbols
import java.time.LocalDate

typealias InstallmentPreviewItem = com.folioledger.app.domain.model.InstallmentPreviewItem
typealias SplitValidationSummary = com.folioledger.app.domain.model.SplitValidationSummary

@Immutable
data class SplitLineDraft(
    val localId: Long,
    val categoryId: Long = 0L,
    val categoryName: String = "Select Category",
    val categoryColorHex: String = "#059669",
    val categoryIconName: String = "category",
    val amountInput: String = "",
    val evaluatedAmount: MinorMoney = MinorMoney.ZERO,
    val note: String = "",
)

@Immutable
data class TransactionEntryUiState(
    val editingTransactionId: Long = 0L,
    val mode: TransactionType = TransactionType.EXPENSE,
    val ledgerDateEpochDay: Long = 0L,
    val timestampEpochMillis: Long = 0L,
    val expressionBuffer: String = "0",
    val evaluatedAmount: MinorMoney = MinorMoney.ZERO,
    val expressionHasOperator: Boolean = false,
    val activeAccounts: List<AccountSummary> = emptyList(),
    val selectedAccount: AccountSummary? = null,
    val transferToAccount: AccountSummary? = null,
    val transferFeeInput: String = "",
    val expenseCategories: List<CategoryNode> = emptyList(),
    val incomeCategories: List<CategoryNode> = emptyList(),
    val selectedCategoryId: Long? = null,
    val selectedCategoryName: String? = null,
    val selectedParentCategoryName: String? = null,
    val selectedCategoryColorHex: String? = null,
    val selectedCategoryIconName: String? = null,
    val isSplit: Boolean = false,
    val splitDrafts: List<SplitLineDraft> = emptyList(),
    val splitValidation: SplitValidationSummary = SplitValidationSummary(
        isValid = false,
        allocatedTotal = MinorMoney.ZERO,
        remainingUnallocated = MinorMoney.ZERO,
    ),
    val isInstallmentEnabled: Boolean = false,
    val installmentMonthsCount: Int = 3,
    val installmentSchedulePreview: List<InstallmentPreviewItem> = emptyList(),
    val isRecurring: Boolean = false,
    val recurringFrequency: RepeatFrequency = RepeatFrequency.EVERY_MONTH,
    val payeeOrDescription: String = "",
    val note: String = "",
    val installmentIndex: Int? = null,
    val installmentTotalCount: Int? = null,
    val selectedTags: List<String> = emptyList(),
    val availableTags: List<String> = emptyList(),
    val bookmarks: List<TransactionBookmark> = emptyList(),
    val currencySpec: CurrencySpec = SupportedCurrencies.INR,
    val numberGrouping: NumberGroupingSystem = NumberGroupingSystem.INDIAN,
    val subcategoriesEnabled: Boolean = true,
    val linkedRecurringRuleId: Long? = null,
    val isKeypadVisible: Boolean = true,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val savedSuccessfullyCounter: Int = 0,
    val formSyncVersion: Int = 0,
    val hasUserEdits: Boolean = false,
    val accountCurrencySpecs: Map<Long, CurrencySpec> = emptyMap(),
    val autofillBannerText: String? = null,
    val canUndoAutofill: Boolean = false,
) {
    val activeCategoryTree: List<CategoryNode>
        get() = when (mode) {
            TransactionType.INCOME -> incomeCategories

            TransactionType.EXPENSE, TransactionType.REFUND,
            TransactionType.TRANSFER, TransactionType.CC_SETTLEMENT,
            TransactionType.OPENING_BALANCE, TransactionType.RECONCILIATION,
            -> expenseCategories
        }

    val isCreditCardExpense: Boolean
        get() = mode == TransactionType.EXPENSE &&
            selectedAccount?.groupType == AccountGroup.CREDIT_CARD &&
            installmentIndex == null

    val isLoggingDueRecurringRule: Boolean
        get() = editingTransactionId == 0L && linkedRecurringRuleId != null

    val hasUnsavedChanges: Boolean
        get() = if (editingTransactionId > 0L) {
            hasUserEdits
        } else {
            hasUserEdits ||
                (expressionBuffer.isNotBlank() && expressionBuffer != "0") ||
                payeeOrDescription.isNotBlank() ||
                note.isNotBlank() ||
                transferFeeInput.isNotBlank() ||
                selectedTags.isNotEmpty() ||
                isSplit
        }
}

internal object EntryAmountInputSanitizer {
    private const val MAX_INTEGER_DIGITS = 12
    private const val MAX_BUFFER_LENGTH = 64
    private const val OPERATORS = "+-×÷*/"

    fun sanitizeExpressionInput(rawInput: String, fractionDigits: Int): String = sanitize(rawInput = rawInput, fractionDigits = fractionDigits, allowOperators = true)

    fun sanitizeDecimalInput(rawInput: String, fractionDigits: Int): String = sanitize(rawInput = rawInput, fractionDigits = fractionDigits, allowOperators = false)

    private fun sanitize(rawInput: String, fractionDigits: Int, allowOperators: Boolean): String {
        val isLocaleCommaDecimal = DecimalFormatSymbols.getInstance().decimalSeparator == ','
        val out = StringBuilder(rawInput.length.coerceAtMost(MAX_BUFFER_LENGTH))
        val tokenBuffer = StringBuilder()

        fun flushToken() {
            if (tokenBuffer.isEmpty() || out.length >= MAX_BUFFER_LENGTH) {
                tokenBuffer.setLength(0)
                return
            }
            val normalized = normalizeNumericToken(
                token = tokenBuffer.toString(),
                fractionDigits = fractionDigits,
                isLocaleCommaDecimal = isLocaleCommaDecimal,
            )
            tokenBuffer.setLength(0)
            appendCappedToken(out, normalized, fractionDigits)
        }

        for (ch in rawInput) {
            if (out.length >= MAX_BUFFER_LENGTH) break
            if (allowOperators && ch in OPERATORS) {
                val hadToken = tokenBuffer.isNotEmpty()
                val prevOutLength = out.length
                flushToken()
                if (hadToken && out.length > prevOutLength && out.length < MAX_BUFFER_LENGTH) {
                    out.append(ch)
                }
            } else {
                tokenBuffer.append(ch)
            }
        }
        flushToken()
        return out.toString()
    }

    private fun normalizeNumericToken(
        token: String,
        fractionDigits: Int,
        isLocaleCommaDecimal: Boolean,
    ): String {
        val cleaned = token.filter { it.isDigit() || it == '.' || it == ',' }
        if (cleaned.isEmpty()) return ""

        if (fractionDigits == 0) {
            // Strip grouping commas; truncate any pasted decimal tail after '.'.
            return cleaned.replace(",", "").substringBefore('.')
        }

        val lastComma = cleaned.lastIndexOf(',')
        val lastDot = cleaned.lastIndexOf('.')
        return when {
            lastComma != -1 && lastDot != -1 -> {
                if (lastComma > lastDot) {
                    cleaned.replace(".", "").replace(',', '.')
                } else {
                    cleaned.replace(",", "")
                }
            }

            lastComma != -1 -> {
                val commaCount = cleaned.count { it == ',' }
                val digitsAfterComma = cleaned.length - lastComma - 1
                val isDecimalComma = commaCount == 1 && (
                    digitsAfterComma == 0 ||
                        (digitsAfterComma in 1..fractionDigits && (digitsAfterComma != 3 || isLocaleCommaDecimal)) ||
                        (isLocaleCommaDecimal && digitsAfterComma >= 1)
                    )
                if (isDecimalComma) cleaned else cleaned.replace(",", "")
            }

            else -> cleaned
        }
    }

    private fun appendCappedToken(out: StringBuilder, normalized: String, fractionDigits: Int) {
        var intDigits = 0
        var fracDigits = 0
        var hasSeparator = false
        var tokenLength = 0
        var leadingZeroPending = false

        for (ch in normalized) {
            if (out.length >= MAX_BUFFER_LENGTH) break
            when {
                fractionDigits > 0 && (ch == '.' || ch == ',') -> {
                    if (!hasSeparator) {
                        if (tokenLength == 0) {
                            if (out.length + 2 > MAX_BUFFER_LENGTH) break
                            out.append('0')
                            intDigits = 1
                            tokenLength = 1
                        }
                        out.append(ch)
                        hasSeparator = true
                        leadingZeroPending = false
                        tokenLength++
                    }
                }

                ch.isDigit() -> {
                    if (hasSeparator) {
                        if (fracDigits < fractionDigits) {
                            out.append(ch)
                            fracDigits++
                            tokenLength++
                        }
                    } else if (leadingZeroPending) {
                        if (ch != '0') {
                            out.setCharAt(out.length - 1, ch)
                            leadingZeroPending = false
                        }
                    } else if (intDigits < MAX_INTEGER_DIGITS) {
                        if (tokenLength == 0 && ch == '0') {
                            leadingZeroPending = true
                        }
                        out.append(ch)
                        intDigits++
                        tokenLength++
                    }
                }
            }
        }
    }
}
