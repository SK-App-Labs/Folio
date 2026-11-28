/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/data/repository/CategoryAndTagRepositoryImpl.kt
package com.folioledger.app.data.repository

import androidx.room.withTransaction
import com.folioledger.app.data.local.FolioDatabase
import com.folioledger.app.data.local.dao.CategoryAndTagDao
import com.folioledger.app.data.local.entity.BookmarkEntity
import com.folioledger.app.data.local.entity.CategoryEntity
import com.folioledger.app.data.local.entity.TagEntity
import com.folioledger.app.domain.model.BudgetPeriodType
import com.folioledger.app.domain.model.CategoryNode
import com.folioledger.app.domain.model.CategoryType
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.TransactionBookmark
import com.folioledger.app.domain.repository.CategoryAndTagRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale

class CategoryAndTagRepositoryImpl(
    private val categoryAndTagDao: CategoryAndTagDao,
    private val database: FolioDatabase,
) : CategoryAndTagRepository {

    override fun observeCategoryTree(type: CategoryType): Flow<List<CategoryNode>> = categoryAndTagDao.observeCategoriesByType(type)
        .map { entities -> buildCategoryTree(entities) }
        .flowOn(Dispatchers.Default)

    override fun observeAllCategoryTrees(): Flow<List<CategoryNode>> = categoryAndTagDao.observeActiveCategories()
        .map { entities -> buildCategoryTree(entities) }
        .flowOn(Dispatchers.Default)

    override fun observeAllTags(): Flow<List<String>> = categoryAndTagDao.observeAllTags()
        .map { tagEntities -> tagEntities.map { it.name } }

    override fun observeBookmarks(): Flow<List<TransactionBookmark>> = categoryAndTagDao.observeAllBookmarks()
        .map { bookmarkEntities ->
            bookmarkEntities.map { entity ->
                TransactionBookmark(
                    id = entity.id,
                    title = entity.title,
                    type = entity.type,
                    amount = MinorMoney(entity.amountMinor),
                    accountId = entity.accountId,
                    transferToAccountId = entity.transferToAccountId,
                    categoryId = entity.categoryId,
                    payeeOrDescription = entity.payeeOrDescription,
                    note = entity.note,
                    tags = if (entity.tagsCsv.isBlank()) {
                        emptyList()
                    } else {
                        entity.tagsCsv.split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                    },
                )
            }
        }
        .flowOn(Dispatchers.Default)

    override suspend fun upsertCategory(category: CategoryNode): Result<Long> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            val trimmedName = category.name.trim()
            require(trimmedName.isNotEmpty()) { "Category name cannot be blank." }

            database.withTransaction {
                val existing = if (category.id != 0L) {
                    categoryAndTagDao.getCategoryByIdOnce(category.id)
                } else {
                    null
                }
                if (category.parentId != null) {
                    require(category.parentId != category.id) { "Category cannot be its own parent." }
                    val parent = categoryAndTagDao.getCategoryByIdOnce(category.parentId)
                        ?: throw IllegalArgumentException("Parent category does not exist.")
                    require(!parent.isArchived) { "Cannot add a subcategory under an archived category." }
                    require(parent.parentId == null) { "Subcategories cannot be nested more than 2 levels deep." }
                    require(parent.type == category.type) { "Subcategory type must match its parent category." }
                }

                val allCategories = categoryAndTagDao.getAllCategoriesOnce()
                if (existing != null) {
                    val hasChildren = allCategories.any { it.parentId == existing.id }
                    require(!hasChildren || (category.parentId == null && category.type == existing.type)) {
                        "Cannot move or change type of a category that has subcategories."
                    }
                }
                val duplicate = allCategories.any {
                    !it.isArchived &&
                        it.id != category.id &&
                        it.type == category.type &&
                        it.parentId == category.parentId &&
                        it.name.trim().equals(trimmedName, ignoreCase = true)
                }
                require(!duplicate) { "A category with this name already exists." }

                val entity = CategoryEntity(
                    id = category.id,
                    name = trimmedName,
                    type = category.type,
                    parentId = category.parentId,
                    iconName = category.iconName.ifBlank { "category" },
                    colorHex = category.colorHex.ifBlank { "#10B981" },
                    sortOrder = existing?.sortOrder ?: 0,
                    isArchived = existing?.isArchived ?: false,
                )
                categoryAndTagDao.insertCategory(entity)
            }
        }
    }

    override suspend fun deleteCategory(
        categoryId: Long,
        reassignToCategoryId: Long?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            require(reassignToCategoryId == null || reassignToCategoryId != categoryId) {
                "Cannot reassign category to itself."
            }
            database.withTransaction {
                val sourceCat = categoryAndTagDao.getCategoryByIdOnce(categoryId)
                    ?: return@withTransaction
                if (reassignToCategoryId != null) {
                    val targetCat = categoryAndTagDao.getCategoryByIdOnce(reassignToCategoryId)
                        ?: throw IllegalArgumentException("Target reassign category does not exist.")
                    require(!targetCat.isArchived) { "Cannot reassign to an archived category." }
                    require(targetCat.type == sourceCat.type) {
                        "Target category type must match the deleted category."
                    }
                    require(targetCat.parentId != categoryId) {
                        "Cannot reassign category to its own child subcategory."
                    }
                    categoryAndTagDao.reassignTransactionsCategoryTree(categoryId, reassignToCategoryId)
                    categoryAndTagDao.reassignSplitsCategoryTree(categoryId, reassignToCategoryId)
                    categoryAndTagDao.reassignRecurringRulesCategoryTree(categoryId, reassignToCategoryId)
                    categoryAndTagDao.reassignBookmarksCategoryTree(categoryId, reassignToCategoryId)
                    categoryAndTagDao.reassignInstallmentPlansCategoryTree(categoryId, reassignToCategoryId)

                    val occupiedRecurringPeriodTypes = categoryAndTagDao
                        .getBudgetsByCategoryIdOnce(reassignToCategoryId)
                        .filter { it.periodType != BudgetPeriodType.CUSTOM }
                        .map { it.periodType }
                        .toMutableSet()
                    for (budget in categoryAndTagDao.getBudgetsForCategoryTreeOnce(categoryId)) {
                        if (budget.periodType == BudgetPeriodType.CUSTOM ||
                            budget.periodType !in occupiedRecurringPeriodTypes
                        ) {
                            if (budget.periodType != BudgetPeriodType.CUSTOM) {
                                occupiedRecurringPeriodTypes.add(budget.periodType)
                            }
                            categoryAndTagDao.updateBudgetCategoryId(budget.id, reassignToCategoryId)
                        } else {
                            categoryAndTagDao.deleteCategoryBudgetById(budget.id)
                        }
                    }
                } else {
                    for (budget in categoryAndTagDao.getBudgetsForCategoryTreeOnce(categoryId)) {
                        categoryAndTagDao.deleteCategoryBudgetById(budget.id)
                    }
                }
                categoryAndTagDao.deleteCategoryById(categoryId)
            }
        }
    }

    private fun sanitizeTagName(raw: String): String {
        val cleaned = raw.trim().trimStart('#').trim()
        require(cleaned.isNotEmpty()) { "Tag name cannot be blank." }
        require(',' !in cleaned) { "Tag names cannot contain commas." }
        return cleaned
    }

    override suspend fun createTag(tagName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            val cleaned = sanitizeTagName(tagName)
            database.withTransaction {
                if (categoryAndTagDao.getTagByNameOnce(cleaned) == null) {
                    categoryAndTagDao.insertTag(TagEntity(name = cleaned))
                }
            }
            Unit
        }
    }

    override suspend fun renameTag(oldTagName: String, newTagName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            val oldTrimmed = oldTagName.trim()
            require(oldTrimmed.isNotEmpty()) { "Tag names cannot be blank." }
            val newCleaned = sanitizeTagName(newTagName)
            categoryAndTagDao.renameOrMergeTag(oldTrimmed, newCleaned)
        }
    }

    override suspend fun deleteTag(tagName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            val trimmed = tagName.trim()
            if (trimmed.isNotEmpty()) {
                database.withTransaction {
                    val bookmarks = categoryAndTagDao.getAllBookmarksOnce()
                    val targetLower = trimmed.lowercase(Locale.ROOT)
                    for (bm in bookmarks) {
                        if (bm.tagsCsv.isNotBlank()) {
                            val tags = bm.tagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            val filtered = tags.filter { it.lowercase(Locale.ROOT) != targetLower }
                            if (filtered.size != tags.size) {
                                categoryAndTagDao.updateBookmark(bm.copy(tagsCsv = filtered.joinToString(",")))
                            }
                        }
                    }
                    categoryAndTagDao.deleteTagByName(trimmed)
                }
            }
        }
    }

    override suspend fun upsertBookmark(bookmark: TransactionBookmark): Result<Long> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            val trimmedTitle = bookmark.title.trim()
            require(trimmedTitle.isNotEmpty()) { "Bookmark title cannot be blank." }
            require(bookmark.amount.isPositive) { "Bookmark amount must be greater than zero." }

            val tagsCsv = bookmark.tags
                .mapNotNull { raw ->
                    val cleaned = raw.trim().trimStart('#').trim()
                    if (cleaned.isEmpty()) {
                        null
                    } else {
                        require(',' !in cleaned) { "Tag names cannot contain commas." }
                        cleaned
                    }
                }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .joinToString(",")

            val entity = BookmarkEntity(
                id = bookmark.id,
                title = trimmedTitle,
                type = bookmark.type,
                amountMinor = bookmark.amount.amountMinor,
                accountId = bookmark.accountId,
                transferToAccountId = bookmark.transferToAccountId,
                categoryId = bookmark.categoryId,
                payeeOrDescription = bookmark.payeeOrDescription.trim(),
                note = bookmark.note.trim(),
                tagsCsv = tagsCsv,
            )
            categoryAndTagDao.upsertBookmark(entity)
        }
    }

    override suspend fun deleteBookmark(bookmarkId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            categoryAndTagDao.deleteBookmarkById(bookmarkId)
        }
    }

    private fun buildCategoryTree(entities: List<CategoryEntity>): List<CategoryNode> {
        val childrenByParentId = entities
            .filter { it.parentId != null }
            .groupBy { it.parentId }

        return entities
            .filter { it.parentId == null }
            .sortedWith(compareBy<CategoryEntity> { it.sortOrder }.thenBy { it.id })
            .map { mainCat ->
                val subcategories = childrenByParentId[mainCat.id]
                    .orEmpty()
                    .sortedWith(compareBy<CategoryEntity> { it.sortOrder }.thenBy { it.id })
                    .map { subCat ->
                        CategoryNode(
                            id = subCat.id,
                            name = subCat.name,
                            type = subCat.type,
                            parentId = subCat.parentId,
                            iconName = subCat.iconName,
                            colorHex = subCat.colorHex,
                            subcategories = emptyList(),
                        )
                    }

                CategoryNode(
                    id = mainCat.id,
                    name = mainCat.name,
                    type = mainCat.type,
                    parentId = null,
                    iconName = mainCat.iconName,
                    colorHex = mainCat.colorHex,
                    subcategories = subcategories,
                )
            }
    }
}
