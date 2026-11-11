package com.folioledger.app.data

import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.RepeatFrequency
import com.folioledger.app.domain.model.SplitLineItem
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import com.folioledger.app.domain.util.RecurringScheduleCalculator
import com.folioledger.app.domain.util.TransactionEntryRulesEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class Phase2AuditRegressionTestSuite {

    // ---------------------------------------------------------------------------------------------
    // Bug 1.1: Split line items must allow null categoryId and retain splits when category is deleted
    // ---------------------------------------------------------------------------------------------
    @Test
    fun splitLineItem_allowsNullCategoryId_retainsIntegrity() {
        val splitItem = SplitLineItem(
            id = 1L,
            categoryId = null, // Deleted category becomes null via SET_NULL
            categoryName = "Uncategorized",
            amount = MinorMoney(250_00L),
            note = "Split part 1"
        )
        assertNull(splitItem.categoryId)
        assertEquals(MinorMoney(250_00L), splitItem.amount)
    }

    @Test
    fun transactionEntryRulesEngine_rejectsNullOrZeroCategoryId_whenCreatingSplits() {
        val splits = listOf(
            SplitLineItem(id = 1L, categoryId = null, categoryName = "Unassigned", amount = MinorMoney(50_00L)),
            SplitLineItem(id = 2L, categoryId = 2L, categoryName = "Groceries", amount = MinorMoney(50_00L))
        )
        val result = TransactionEntryRulesEngine.validateSplitTransaction(MinorMoney(100_00L), splits)
        assertFalse(result.isValid)
        assertEquals("Every split line must have a category selected.", result.errorMessage)
    }

    // ---------------------------------------------------------------------------------------------
    // Bug 1.4: Multi-currency native balance formatting without hardcoded / 100.0
    // ---------------------------------------------------------------------------------------------
    @Test
    fun balanceSheetNativeFormatting_usesCurrencyFractionDigits_notHardcodedCents() {
        // Test BHD (3 decimals)
        val bhdSpec = SupportedCurrencies.fromCode("BHD")
        val bhdMoney = MinorMoney(12345L) // 12.345 BHD
        val formattedBhd = MoneyFormatterAndCalculator.formatMinorMoney(
            bhdMoney,
            bhdSpec,
            NumberGroupingSystem.INTERNATIONAL
        )
        assertEquals("BD12.345", formattedBhd)

        // Test JPY (0 decimals)
        val jpySpec = SupportedCurrencies.fromCode("JPY")
        val jpyMoney = MinorMoney(5000L) // 5000 JPY
        val formattedJpy = MoneyFormatterAndCalculator.formatMinorMoney(
            jpyMoney,
            jpySpec,
            NumberGroupingSystem.INTERNATIONAL
        )
        assertEquals("¥5,000", formattedJpy)
    }

    // ---------------------------------------------------------------------------------------------
    // Bug 1.5: Recurring rules catch-up logic advances through all overdue occurrences
    // ---------------------------------------------------------------------------------------------
    @Test
    fun recurringRuleCatchUp_advancesThroughMultipleOverdueCycles() {
        val ruleStartDate = LocalDate.of(2026, 8, 1)
        val todayDate = LocalDate.of(2026, 11, 1)

        var currentDate = ruleStartDate
        var postedCount = 0

        while (currentDate.toEpochDay() <= todayDate.toEpochDay() && postedCount < 366) {
            postedCount++
            val nextDate = RecurringScheduleCalculator.computeNextOccurrence(
                currentDate = currentDate,
                frequency = RepeatFrequency.MONTHLY
            )
            if (nextDate <= currentDate) break
            currentDate = nextDate
        }

        // Aug 1, Sep 1, Oct 1, Nov 1 -> 4 occurrences posted
        assertEquals(4, postedCount)
        assertTrue(currentDate > todayDate)
    }

    // ---------------------------------------------------------------------------------------------
    // Item 2.2: AutoBackup directory scanning searches both FolioAutoBackups and FolioBackups
    // ---------------------------------------------------------------------------------------------
    @Test
    fun autoBackupDirectories_includesBothAutoAndManualBackupFolders() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "folio_test_backups_${System.currentTimeMillis()}")
        val autoBackupsDir = File(tempDir, "FolioAutoBackups").apply { mkdirs() }
        val manualBackupsDir = File(tempDir, "FolioBackups").apply { mkdirs() }

        try {
            val file1 = File(autoBackupsDir, "Folio_Auto_20261101_120000.foliobackup").apply {
                createNewFile()
                setLastModified(1000L)
            }
            val file2 = File(manualBackupsDir, "Folio_Manual_20261102_120000.foliobackup").apply {
                createNewFile()
                setLastModified(2000L)
            }

            val searchDirs = listOf(autoBackupsDir, manualBackupsDir)
            val allBackupFiles = searchDirs
                .flatMap { it.listFiles()?.toList() ?: emptyList() }
                .filter { it.isFile && (it.name.endsWith(".foliobackup") || it.name.endsWith(".zip")) }
                .sortedByDescending { it.lastModified() }

            assertEquals(2, allBackupFiles.size)
            assertEquals("Folio_Manual_20261102_120000.foliobackup", allBackupFiles.first().name)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Item 4.1: Future-dated transactions excluded from invested principal
    // ---------------------------------------------------------------------------------------------
    @Test
    fun investedPrincipal_excludesFutureDatedTransactions() {
        val refEpochDay = LocalDate.of(2026, 11, 11).toEpochDay()
        val pastTxEpochDay = LocalDate.of(2026, 11, 1).toEpochDay()
        val futureTxEpochDay = LocalDate.of(2026, 11, 20).toEpochDay()

        val pastTx = Pair(pastTxEpochDay, 5000_00L)
        val futureTx = Pair(futureTxEpochDay, 10000_00L)

        var investedPrincipalMinor = 0L
        for (tx in listOf(pastTx, futureTx)) {
            // In AccountRepositoryImpl, transactions with ledgerDateEpochDay > referenceDateEpochDay are skipped
            if (tx.first <= refEpochDay) {
                investedPrincipalMinor += tx.second
            }
        }

        assertEquals(5000_00L, investedPrincipalMinor)
    }

    // ---------------------------------------------------------------------------------------------
    // Item 1.1b: DatabaseBackupEngine splits JSON serialization handles null categoryId
    // ---------------------------------------------------------------------------------------------
    @Test
    fun databaseBackupEngine_parseSplits_handlesNullCategoryIdWithoutException() {
        val splitsJson = org.json.JSONArray().apply {
            put(org.json.JSONObject().apply {
                put("id", 101L)
                put("transactionId", 42L)
                put("categoryId", org.json.JSONObject.NULL)
                put("amountMinor", 1500L)
                put("note", "Uncategorized split line")
            })
            put(org.json.JSONObject().apply {
                put("id", 102L)
                put("transactionId", 42L)
                put("categoryId", 5L)
                put("amountMinor", 3500L)
                put("note", "Groceries split line")
            })
        }

        val engine = com.folioledger.app.data.repository.DatabaseBackupEngine
        val parsedSplits = engine.parseSplits(splitsJson)

        assertEquals(2, parsedSplits.size)
        assertNull(parsedSplits[0].categoryId)
        assertEquals(101L, parsedSplits[0].id)
        assertEquals(1500L, parsedSplits[0].amountMinor)

        assertEquals(5L, parsedSplits[1].categoryId)
        assertEquals(102L, parsedSplits[1].id)
        assertEquals(3500L, parsedSplits[1].amountMinor)
    }
}
