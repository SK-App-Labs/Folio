/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.domain.util

import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.CategoryNode
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.PayeeAutofillTemplate
import com.folioledger.app.domain.model.TransactionType

data class PayeeAutofillResolution(
    val updatedPayee: String,
    val updatedMode: TransactionType,
    val updatedAccountId: Long?,
    val updatedTransferToAccountId: Long?,
    val updatedCategoryId: Long?,
    val updatedCategoryName: String?,
    val updatedParentCategoryName: String?,
    val updatedCategoryColorHex: String?,
    val updatedCategoryIconName: String?,
    val updatedTags: List<String>,
    val bannerText: String?,
    val didApplyAnyMetadataChange: Boolean
)

object PayeeAutofillResolver {

    fun resolve(
        template: PayeeAutofillTemplate,
        isEditMode: Boolean,
        currentMode: TransactionType,
        currentAccountId: Long?,
        currentTransferToAccountId: Long?,
        currentCategoryId: Long?,
        currentCategoryName: String?,
        currentParentCategoryName: String?,
        currentCategoryColorHex: String?,
        currentCategoryIconName: String?,
        currentTags: List<String>,
        isSplit: Boolean,
        isAccountManuallySet: Boolean,
        isDestinationManuallySet: Boolean,
        isCategoryManuallySet: Boolean,
        isTagsManuallySet: Boolean,
        isModeManuallySet: Boolean,
        activeAccounts: List<AccountSummary>,
        activeExpenseCategories: List<CategoryNode>,
        activeIncomeCategories: List<CategoryNode>,
        activeTags: List<String>,
        subcategoriesEnabled: Boolean,
        evaluatedAmount: MinorMoney
    ): PayeeAutofillResolution {
        // Edit mode (editing an existing transaction): Only update payee name, never touch existing metadata
        if (isEditMode) {
            return PayeeAutofillResolution(
                updatedPayee = template.payeeName,
                updatedMode = currentMode,
                updatedAccountId = currentAccountId,
                updatedTransferToAccountId = currentTransferToAccountId,
                updatedCategoryId = currentCategoryId,
                updatedCategoryName = currentCategoryName,
                updatedParentCategoryName = currentParentCategoryName,
                updatedCategoryColorHex = currentCategoryColorHex,
                updatedCategoryIconName = currentCategoryIconName,
                updatedTags = currentTags,
                bannerText = null,
                didApplyAnyMetadataChange = false
            )
        }

        // 1. Transaction Mode resolution
        val resolvedMode = if (!isModeManuallySet && !isCategoryManuallySet && !isSplit) {
            val isCurrentSimple = currentMode == TransactionType.EXPENSE || currentMode == TransactionType.INCOME
            val isTemplateSimple = template.type == TransactionType.EXPENSE || template.type == TransactionType.INCOME
            if (isCurrentSimple && isTemplateSimple) template.type else currentMode
        } else {
            currentMode
        }
        val modeChanged = resolvedMode != currentMode

        // 2. Account resolution with Cross-Currency safety guard
        var resolvedAccountId = currentAccountId
        var accountChanged = false
        val effectiveCurrentAccount = currentAccountId?.let { id ->
            activeAccounts.firstOrNull { it.id == id }
        } ?: activeAccounts.firstOrNull()

        if (!isAccountManuallySet) {
            val targetAccount = activeAccounts.firstOrNull { it.id == template.accountId }
            if (targetAccount != null) {
                val lockedTransferDest = if (resolvedMode == TransactionType.TRANSFER && isDestinationManuallySet) {
                    currentTransferToAccountId?.let { id -> activeAccounts.firstOrNull { it.id == id } }
                } else null

                val isCurrencySafe = evaluatedAmount.isZero ||
                        effectiveCurrentAccount == null ||
                        targetAccount.currencyCode.equals(effectiveCurrentAccount.currencyCode, ignoreCase = true)

                val respectsLockedDest = lockedTransferDest == null || (
                    targetAccount.id != lockedTransferDest.id &&
                    targetAccount.currencyCode.equals(lockedTransferDest.currencyCode, ignoreCase = true)
                )

                if (isCurrencySafe && respectsLockedDest && targetAccount.id != effectiveCurrentAccount?.id) {
                    resolvedAccountId = targetAccount.id
                    accountChanged = true
                }
            }
        }

        // 3. Transfer destination resolution
        var resolvedTransferToAccountId = currentTransferToAccountId
        var destinationChanged = false
        if (resolvedMode == TransactionType.TRANSFER) {
            val srcId = resolvedAccountId ?: effectiveCurrentAccount?.id
            val srcAccount = srcId?.let { id -> activeAccounts.firstOrNull { it.id == id } }

            if (!isDestinationManuallySet && template.transferToAccountId != null) {
                val targetDestId = if (template.transferToAccountId != srcId) {
                    template.transferToAccountId
                } else {
                    activeAccounts.firstOrNull { candidate ->
                        candidate.id != srcId && (srcAccount == null || candidate.currencyCode.equals(srcAccount.currencyCode, ignoreCase = true))
                    }?.id
                }

                if (targetDestId != null) {
                    val destAccount = activeAccounts.firstOrNull { it.id == targetDestId }
                    if (destAccount != null &&
                        destAccount.id != srcId &&
                        (srcAccount == null || destAccount.currencyCode.equals(srcAccount.currencyCode, ignoreCase = true)) &&
                        destAccount.id != currentTransferToAccountId
                    ) {
                        resolvedTransferToAccountId = destAccount.id
                        destinationChanged = true
                    }
                }
            }

            // Self-collision and currency mismatch guard: source and destination cannot be the same account, and must use matching currency
            val currentDest = resolvedTransferToAccountId?.let { id -> activeAccounts.firstOrNull { it.id == id } }
            val hasCollisionOrCurrencyMismatch = currentDest != null && (
                currentDest.id == srcId ||
                (srcAccount != null && !currentDest.currencyCode.equals(srcAccount.currencyCode, ignoreCase = true))
            )
            if (hasCollisionOrCurrencyMismatch && !isDestinationManuallySet) {
                val alternateAccount = activeAccounts.firstOrNull { candidate ->
                    candidate.id != srcId && (srcAccount == null || candidate.currencyCode.equals(srcAccount.currencyCode, ignoreCase = true))
                }
                resolvedTransferToAccountId = alternateAccount?.id
                destinationChanged = resolvedTransferToAccountId != currentTransferToAccountId
            }
        }

        // 4. Category resolution
        // If mode auto-switched across EXPENSE ↔ INCOME, invalidate prior mode's category
        var resolvedCategoryId = if (modeChanged) null else currentCategoryId
        var resolvedCategoryName = if (modeChanged) null else currentCategoryName
        var resolvedParentCategoryName = if (modeChanged) null else currentParentCategoryName
        var resolvedCategoryColorHex = if (modeChanged) null else currentCategoryColorHex
        var resolvedCategoryIconName = if (modeChanged) null else currentCategoryIconName
        var categoryChanged = modeChanged && currentCategoryId != null

        val isTransferType = resolvedMode == TransactionType.TRANSFER || resolvedMode == TransactionType.CC_SETTLEMENT
        if (isTransferType) {
            if (resolvedCategoryId != null) {
                resolvedCategoryId = null
                resolvedCategoryName = null
                resolvedParentCategoryName = null
                resolvedCategoryColorHex = null
                resolvedCategoryIconName = null
                categoryChanged = true
            }
        } else if (!isCategoryManuallySet && !isSplit) {
            val tree = if (resolvedMode == TransactionType.INCOME) activeIncomeCategories else activeExpenseCategories
            val foundNode = if (!template.isSplit && template.categoryId != null) {
                findCategoryNodeById(tree, template.categoryId)
            } else null
            if (foundNode != null) {
                if (!subcategoriesEnabled && foundNode.parentId != null) {
                    // Subcategories disabled: promote to parent category
                    val parentNode = tree.firstOrNull { it.id == foundNode.parentId }
                    if (parentNode != null) {
                        if (parentNode.id != resolvedCategoryId) {
                            resolvedCategoryId = parentNode.id
                            resolvedCategoryName = parentNode.name
                            resolvedParentCategoryName = null
                            resolvedCategoryColorHex = parentNode.colorHex
                            resolvedCategoryIconName = parentNode.iconName
                            categoryChanged = true
                        }
                    } else if (foundNode.id != resolvedCategoryId) {
                        resolvedCategoryId = foundNode.id
                        resolvedCategoryName = foundNode.name
                        resolvedParentCategoryName = null
                        resolvedCategoryColorHex = foundNode.colorHex
                        resolvedCategoryIconName = foundNode.iconName
                        categoryChanged = true
                    }
                } else if (foundNode.id != resolvedCategoryId) {
                    resolvedCategoryId = foundNode.id
                    resolvedCategoryName = foundNode.name
                    resolvedParentCategoryName = findParentCategoryName(tree, foundNode.id)
                    resolvedCategoryColorHex = foundNode.colorHex
                    resolvedCategoryIconName = foundNode.iconName
                    categoryChanged = true
                }
            } else {
                // Template has no category, or category not found in active tree (e.g. deleted or split)
                // Clear any prior autofilled category so it does not leak into this payee
                if (resolvedCategoryId != null) {
                    resolvedCategoryId = null
                    resolvedCategoryName = null
                    resolvedParentCategoryName = null
                    resolvedCategoryColorHex = null
                    resolvedCategoryIconName = null
                    categoryChanged = true
                }
            }
        }

        // 5. Tags resolution
        var resolvedTags = currentTags
        var tagsChanged = false
        if (!isTagsManuallySet) {
            val activeTagSet = activeTags.toHashSet()
            val validTags = template.tags.filter { it in activeTagSet }
            if (validTags != currentTags) {
                resolvedTags = validTags
                tagsChanged = true
            }
        }

        val didApplyAnyMetadataChange = modeChanged || accountChanged || destinationChanged || categoryChanged || tagsChanged

        val bannerText = if (didApplyAnyMetadataChange) {
            val changedParts = buildList {
                if (categoryChanged && resolvedCategoryName != null) {
                    add(resolvedCategoryName)
                }
                if (accountChanged) {
                    val accountName = activeAccounts.firstOrNull { it.id == resolvedAccountId }?.name
                    if (accountName != null) add(accountName)
                }
                if (destinationChanged) {
                    val destAccountName = activeAccounts.firstOrNull { it.id == resolvedTransferToAccountId }?.name
                    if (destAccountName != null) add("To $destAccountName")
                }
                if (modeChanged && !categoryChanged) {
                    val modeLabel = when (resolvedMode) {
                        TransactionType.INCOME -> "Income"
                        TransactionType.EXPENSE -> "Expense"
                        TransactionType.TRANSFER -> "Transfer"
                        TransactionType.CC_SETTLEMENT -> "Card Payment"
                        TransactionType.REFUND -> "Refund"
                        else -> resolvedMode.name
                    }
                    add(modeLabel)
                }
                if (tagsChanged && resolvedTags.isNotEmpty()) {
                    add("${resolvedTags.size} tag(s)")
                }
            }
            if (changedParts.isNotEmpty()) {
                "Auto-filled: ${changedParts.joinToString(" \u2022 ")}"
            } else {
                null
            }
        } else null

        return PayeeAutofillResolution(
            updatedPayee = template.payeeName.trim { it.isWhitespace() },
            updatedMode = resolvedMode,
            updatedAccountId = resolvedAccountId,
            updatedTransferToAccountId = resolvedTransferToAccountId,
            updatedCategoryId = resolvedCategoryId,
            updatedCategoryName = resolvedCategoryName,
            updatedParentCategoryName = resolvedParentCategoryName,
            updatedCategoryColorHex = resolvedCategoryColorHex,
            updatedCategoryIconName = resolvedCategoryIconName,
            updatedTags = resolvedTags,
            bannerText = bannerText,
            didApplyAnyMetadataChange = didApplyAnyMetadataChange
        )
    }

    private fun findCategoryNodeById(tree: List<CategoryNode>, id: Long?): CategoryNode? {
        if (id == null) return null
        return tree.firstNotNullOfOrNull { parent ->
            if (parent.id == id) parent else parent.subcategories.firstOrNull { it.id == id }
        }
    }

    private fun findParentCategoryName(tree: List<CategoryNode>, id: Long?): String? {
        if (id == null) return null
        return tree.firstOrNull { parent -> parent.subcategories.any { it.id == id } }?.name
    }
}
