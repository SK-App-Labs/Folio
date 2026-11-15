package com.folioledger.app.data

import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionSearchFilter
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.model.UNCATEGORIZED_CATEGORY_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionSearchFilterTest {

    private fun createSampleTransaction(
        id: Long,
        amountMinor: Long,
        categoryId: Long?,
        categoryName: String? = null
    ): TransactionItem {
        return TransactionItem(
            id = id,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 1700000000000L,
            ledgerDateEpochDay = 20000L,
            amount = MinorMoney(amountMinor),
            currencyCode = "INR",
            accountId = 1L,
            accountName = "Cash",
            categoryId = categoryId,
            categoryName = categoryName,
            payeeOrDescription = "Test Entry $id"
        )
    }

    private fun applyFilter(
        items: List<TransactionItem>,
        filter: TransactionSearchFilter,
        expandedCategoryIds: Set<Long> = filter.categoryIds - UNCATEGORIZED_CATEGORY_ID
    ): List<TransactionItem> {
        return items.filter { com.folioledger.app.data.repository.matchesSearchFilter(it, filter, expandedCategoryIds) }
    }

    @Test
    fun `filtering by UNCATEGORIZED_CATEGORY_ID isolates transactions without category`() {
        val txUncategorized1 = createSampleTransaction(1L, 10000L, null)
        val txFood = createSampleTransaction(2L, 25000L, 10L, "Food")
        val txUncategorized2 = createSampleTransaction(3L, 5000L, null)
        val txTransit = createSampleTransaction(4L, 12000L, 20L, "Transit")

        val list = listOf(txUncategorized1, txFood, txUncategorized2, txTransit)
        val filter = TransactionSearchFilter(categoryIds = setOf(UNCATEGORIZED_CATEGORY_ID))

        val filtered = applyFilter(list, filter)
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.categoryId == null })
        assertEquals(listOf(1L, 3L), filtered.map { it.id })
    }

    @Test
    fun `combining UNCATEGORIZED_CATEGORY_ID with regular category matches both`() {
        val txUncategorized = createSampleTransaction(1L, 10000L, null)
        val txFood = createSampleTransaction(2L, 25000L, 10L, "Food")
        val txTransit = createSampleTransaction(3L, 12000L, 20L, "Transit")

        val list = listOf(txUncategorized, txFood, txTransit)
        val filter = TransactionSearchFilter(categoryIds = setOf(UNCATEGORIZED_CATEGORY_ID, 10L))

        val filtered = applyFilter(list, filter, expandedCategoryIds = setOf(10L))
        assertEquals(2, filtered.size)
        assertEquals(listOf(1L, 2L), filtered.map { it.id })
    }

    @Test
    fun `amount range filter correctly filters between min and max bounds`() {
        val txSmall = createSampleTransaction(1L, 5000L, 10L) // 50.00
        val txMid = createSampleTransaction(2L, 15000L, 10L)   // 150.00
        val txLarge = createSampleTransaction(3L, 50000L, 10L) // 500.00

        val list = listOf(txSmall, txMid, txLarge)

        // Min bound only: >= 100.00
        val minOnlyFilter = TransactionSearchFilter(minAmount = MinorMoney(10000L))
        val minOnlyResult = applyFilter(list, minOnlyFilter)
        assertEquals(listOf(2L, 3L), minOnlyResult.map { it.id })

        // Max bound only: <= 200.00
        val maxOnlyFilter = TransactionSearchFilter(maxAmount = MinorMoney(20000L))
        val maxOnlyResult = applyFilter(list, maxOnlyFilter)
        assertEquals(listOf(1L, 2L), maxOnlyResult.map { it.id })

        // Range bound: 100.00 to 200.00
        val rangeFilter = TransactionSearchFilter(
            minAmount = MinorMoney(10000L),
            maxAmount = MinorMoney(20000L)
        )
        val rangeResult = applyFilter(list, rangeFilter)
        assertEquals(listOf(2L), rangeResult.map { it.id })
    }
}
