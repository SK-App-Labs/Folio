package com.folioledger.app.data

import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.SupporterTier
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.ui.settings.sheets.formatBackupRelativeTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class BackupAndDataManagementTest {

    @Test
    fun formatBackupRelativeTime_nullOrZero_returnsNever() {
        assertEquals("Never", formatBackupRelativeTime(null))
        assertEquals("Never", formatBackupRelativeTime(0L))
        assertEquals("Never", formatBackupRelativeTime(-100L))
    }

    @Test
    fun formatBackupRelativeTime_today_returnsTodayWithTime() {
        val todayMiddayMillis = LocalDate.now()
            .atTime(12, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val result = formatBackupRelativeTime(todayMiddayMillis)
        assertTrue("Expected to start with 'Today, ', got '$result'", result.startsWith("Today, "))
    }

    @Test
    fun formatBackupRelativeTime_yesterday_returnsYesterdayWithTime() {
        val yesterdayMillis = LocalDate.now().minusDays(1)
            .atTime(14, 30)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val result = formatBackupRelativeTime(yesterdayMillis)
        assertTrue("Expected to start with 'Yesterday, ', got '$result'", result.startsWith("Yesterday, "))
    }

    @Test
    fun formatBackupRelativeTime_threeDaysAgo_returnsDaysAgo() {
        val threeDaysAgo = LocalDate.now().minusDays(3)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val result = formatBackupRelativeTime(threeDaysAgo)
        assertEquals("3d ago", result)
    }

    @Test
    fun userPreferences_supporterTierAndBackupTimestamp_preservedInModel() {
        val originalPrefs = UserPreferences(
            primaryCurrencyCode = "INR",
            numberGrouping = NumberGroupingSystem.INDIAN,
            monthStartDay = 25,
            weekStartDayIso = 1,
            isSupporter = true,
            supporterTier = SupporterTier.PATRON.skuId,
            lastBackupEpochMillis = 1730000000000L
        )

        assertTrue(originalPrefs.isSupporter)
        assertEquals(SupporterTier.PATRON.skuId, originalPrefs.supporterTier)
        assertEquals(1730000000000L, originalPrefs.lastBackupEpochMillis)
    }

    @Test
    fun userPreferences_legacyBackupFallback_preservesCurrentSupporterState() {
        val current = UserPreferences(
            isSupporter = true,
            supporterTier = SupporterTier.COFFEE.skuId,
            lastBackupEpochMillis = 1729000000000L
        )

        // Incoming legacy backup without supporter keys:
        val incomingHasSupporterKey = false
        val incomingSupporterTierValue: String? = null

        val restored = UserPreferences(
            primaryCurrencyCode = "USD",
            isSupporter = if (incomingHasSupporterKey) false else current.isSupporter,
            supporterTier = incomingSupporterTierValue ?: current.supporterTier,
            lastBackupEpochMillis = current.lastBackupEpochMillis
        )

        // Preserves current supporter status rather than clobbering to false/null
        assertTrue(restored.isSupporter)
        assertEquals(SupporterTier.COFFEE.skuId, restored.supporterTier)
        assertEquals(1729000000000L, restored.lastBackupEpochMillis)
    }

    @Test
    fun userPreferences_iconPackTheme_preservedInModel() {
        val originalPrefs = UserPreferences(
            primaryCurrencyCode = "USD",
            iconPackTheme = "PHOSPHOR"
        )
        assertEquals("PHOSPHOR", originalPrefs.iconPackTheme)

        val restored = UserPreferences(
            primaryCurrencyCode = "USD",
            iconPackTheme = "EMOJI"
        )
        assertEquals("EMOJI", restored.iconPackTheme)
    }

    @Test
    fun accountEntity_isPinned_preservedInModel() {
        val account = com.folioledger.app.data.local.entity.AccountEntity(
            id = 1L,
            name = "Salary Account",
            groupType = com.folioledger.app.domain.model.AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            isPinned = true
        )
        assertTrue(account.isPinned)
    }

    @Test
    fun fileExportSuccessUiModel_zipMimeType_resolvedCorrectly() {
        val model = com.folioledger.app.ui.settings.FileExportSuccessUiModel(
            title = "Backup Created!",
            subtitle = "Portable ZIP backup archive saved",
            fileName = "Folio_Backup_2026-11-09.zip",
            fileSizeBytes = 2048L,
            file = null
        )
        assertEquals("application/zip", model.mimeType)
        assertEquals("Downloads/Folio/Folio_Backup_2026-11-09.zip", model.publicDisplayPath)
        assertEquals("Backup Created!", model.title)
    }

    @Test
    fun fileExportSuccessUiModel_csvMimeType_resolvedCorrectly() {
        val model = com.folioledger.app.ui.settings.FileExportSuccessUiModel(
            title = "Template Downloaded!",
            subtitle = "Side-by-side CSV template ready for entry",
            fileName = "Folio_Import_Template.csv",
            fileSizeBytes = 512L,
            file = null
        )
        assertEquals("text/csv", model.mimeType)
        assertEquals("Downloads/Folio/Folio_Import_Template.csv", model.publicDisplayPath)
        assertEquals("Template Downloaded!", model.title)
    }

    @Test
    fun settingsUiState_lastExportedFileSuccess_defaultNullAndPreserved() {
        val defaultState = com.folioledger.app.ui.settings.SettingsUiState()
        assertNull(defaultState.lastExportedFileSuccess)

        val successModel = com.folioledger.app.ui.settings.FileExportSuccessUiModel(
            title = "Ledger Export Complete!",
            subtitle = "10 transactions exported to CSV",
            fileName = "Folio_Ledger_Export.csv",
            fileSizeBytes = 1024L,
            file = null
        )
        val stateWithSuccess = defaultState.copy(lastExportedFileSuccess = successModel)
        assertEquals(successModel, stateWithSuccess.lastExportedFileSuccess)
    }

    @Test
    fun backupRestore_foreignKeySanitization_danglingAndCircularIdsNulled() {
        val account1 = com.folioledger.app.data.local.entity.AccountEntity(
            id = 1L, name = "Bank", groupType = com.folioledger.app.domain.model.AccountGroup.BANK_ACCOUNT, currencyCode = "INR"
        )
        val account2Dangling = com.folioledger.app.data.local.entity.AccountEntity(
            id = 2L, name = "Card", groupType = com.folioledger.app.domain.model.AccountGroup.DEBIT_CARD, currencyCode = "INR", parentAccountId = 999L // Non-existent parent
        )
        val account3Circular = com.folioledger.app.data.local.entity.AccountEntity(
            id = 3L, name = "Circular", groupType = com.folioledger.app.domain.model.AccountGroup.DEBIT_CARD, currencyCode = "INR", parentAccountId = 3L // Self parent
        )
        val accounts = listOf(account1, account2Dangling, account3Circular)
        val sanitizedAccounts = com.folioledger.app.data.repository.BackupSanitizer.sanitizeAccounts(accounts)

        assertNull("Dangling parentAccountId should be nulled out", sanitizedAccounts[1].parentAccountId)
        assertNull("Circular parentAccountId should be nulled out", sanitizedAccounts[2].parentAccountId)
    }

    @Test
    fun backupRestore_multiLevelParentSanitization_resetsToRoot() {
        val rootAccount = com.folioledger.app.data.local.entity.AccountEntity(
            id = 1L, name = "Root Bank", groupType = com.folioledger.app.domain.model.AccountGroup.BANK_ACCOUNT, currencyCode = "INR", parentAccountId = null
        )
        val childAccount = com.folioledger.app.data.local.entity.AccountEntity(
            id = 2L, name = "Child", groupType = com.folioledger.app.domain.model.AccountGroup.BANK_ACCOUNT, currencyCode = "INR", parentAccountId = 1L
        )
        val grandChildAccount = com.folioledger.app.data.local.entity.AccountEntity(
            id = 3L, name = "Grandchild", groupType = com.folioledger.app.domain.model.AccountGroup.BANK_ACCOUNT, currencyCode = "INR", parentAccountId = 2L
        )
        val accounts = listOf(rootAccount, childAccount, grandChildAccount)
        val sanitizedAccounts = com.folioledger.app.data.repository.BackupSanitizer.sanitizeAccounts(accounts)

        assertNull(sanitizedAccounts[0].parentAccountId)
        assertEquals(1L, sanitizedAccounts[1].parentAccountId)
        assertNull("Grandchild parentAccountId pointing to non-root account should be nulled to prevent multi-level chain", sanitizedAccounts[2].parentAccountId)
    }

    @Test
    fun backupRestore_bookmarksSanitization_danglingAccountsAndCategoriesNulledOrFiltered() {
        val accountIds = setOf(1L, 2L)
        val categoryIds = setOf(10L, 20L)

        val bmValid = com.folioledger.app.data.local.entity.BookmarkEntity(
            id = 1L, title = "Valid", type = com.folioledger.app.domain.model.TransactionType.TRANSFER, amountMinor = 100L, accountId = 1L, transferToAccountId = 2L, categoryId = 10L
        )
        val bmDanglingSource = com.folioledger.app.data.local.entity.BookmarkEntity(
            id = 2L, title = "Dangling Source", type = com.folioledger.app.domain.model.TransactionType.EXPENSE, amountMinor = 50L, accountId = 999L
        )
        val bmDanglingTargetAndCat = com.folioledger.app.data.local.entity.BookmarkEntity(
            id = 3L, title = "Dangling Target", type = com.folioledger.app.domain.model.TransactionType.TRANSFER, amountMinor = 50L, accountId = 1L, transferToAccountId = 888L, categoryId = 777L
        )

        val bookmarks = listOf(bmValid, bmDanglingSource, bmDanglingTargetAndCat)
        val validBookmarks = com.folioledger.app.data.repository.BackupSanitizer.sanitizeBookmarks(bookmarks, accountIds, categoryIds)

        assertEquals(2, validBookmarks.size)
        assertEquals(1L, validBookmarks[0].accountId)
        assertEquals(2L, validBookmarks[0].transferToAccountId)
        assertEquals(10L, validBookmarks[0].categoryId)

        assertEquals(1L, validBookmarks[1].accountId)
        assertNull(validBookmarks[1].transferToAccountId)
        assertNull(validBookmarks[1].categoryId)
    }

    @Test
    fun backupRestore_budgetsSanitization_deduplicatesAndFiltersOrphans() {
        val categoryIds = setOf(10L, 20L)
        val bOverall1 = com.folioledger.app.data.local.entity.BudgetEntity(id = 1L, categoryId = null, limitAmountMinor = 50000L)
        val bOverall2 = com.folioledger.app.data.local.entity.BudgetEntity(id = 2L, categoryId = null, limitAmountMinor = 60000L)
        val bCategory10 = com.folioledger.app.data.local.entity.BudgetEntity(id = 3L, categoryId = 10L, limitAmountMinor = 10000L)
        val bOrphanCat = com.folioledger.app.data.local.entity.BudgetEntity(id = 4L, categoryId = 999L, limitAmountMinor = 5000L)

        val budgets = listOf(bOverall1, bOverall2, bCategory10, bOrphanCat)
        val validBudgets = com.folioledger.app.data.repository.BackupSanitizer.sanitizeBudgets(budgets, categoryIds)

        assertEquals(2, validBudgets.size)
        assertEquals(null, validBudgets[0].categoryId)
        assertEquals(10L, validBudgets[1].categoryId)
    }

    @Test
    fun backupRestore_budgetsSanitization_preservesMultiCadenceAndMultipleCustomBudgets() {
        val categoryIds = setOf(10L, 20L)
        val monthlyOverall = com.folioledger.app.data.local.entity.BudgetEntity(
            id = 1L, categoryId = null, limitAmountMinor = 50000L,
            periodType = com.folioledger.app.domain.model.BudgetPeriodType.MONTHLY
        )
        val duplicateMonthlyOverall = com.folioledger.app.data.local.entity.BudgetEntity(
            id = 2L, categoryId = null, limitAmountMinor = 55000L,
            periodType = com.folioledger.app.domain.model.BudgetPeriodType.MONTHLY
        )
        val annualOverall = com.folioledger.app.data.local.entity.BudgetEntity(
            id = 3L, categoryId = null, limitAmountMinor = 600000L,
            periodType = com.folioledger.app.domain.model.BudgetPeriodType.ANNUAL
        )
        val weeklyCategory10 = com.folioledger.app.data.local.entity.BudgetEntity(
            id = 4L, categoryId = 10L, limitAmountMinor = 2500L,
            periodType = com.folioledger.app.domain.model.BudgetPeriodType.WEEKLY
        )
        val tripEurope = com.folioledger.app.data.local.entity.BudgetEntity(
            id = 5L, categoryId = null, name = "Europe Trip", limitAmountMinor = 200000L,
            periodType = com.folioledger.app.domain.model.BudgetPeriodType.CUSTOM,
            startDateEpochDay = 20000L, endDateEpochDay = 20014L
        )
        val tripJapan = com.folioledger.app.data.local.entity.BudgetEntity(
            id = 6L, categoryId = null, name = "Japan Trip", limitAmountMinor = 300000L,
            periodType = com.folioledger.app.domain.model.BudgetPeriodType.CUSTOM,
            startDateEpochDay = 20100L, endDateEpochDay = 20110L
        )
        val invalidCustom = com.folioledger.app.data.local.entity.BudgetEntity(
            id = 7L, categoryId = null, name = "Invalid", limitAmountMinor = 10000L,
            periodType = com.folioledger.app.domain.model.BudgetPeriodType.CUSTOM,
            startDateEpochDay = null // missing start date
        )

        val list = listOf(
            monthlyOverall,
            duplicateMonthlyOverall,
            annualOverall,
            weeklyCategory10,
            tripEurope,
            tripJapan,
            invalidCustom
        )
        val sanitized = com.folioledger.app.data.repository.BackupSanitizer.sanitizeBudgets(list, categoryIds)

        // monthlyOverall, annualOverall, weeklyCategory10, tripEurope, tripJapan = 5 preserved
        assertEquals(5, sanitized.size)
        assertTrue(sanitized.any { it.periodType == com.folioledger.app.domain.model.BudgetPeriodType.MONTHLY && it.categoryId == null })
        assertTrue(sanitized.any { it.periodType == com.folioledger.app.domain.model.BudgetPeriodType.ANNUAL && it.categoryId == null })
        assertTrue(sanitized.any { it.periodType == com.folioledger.app.domain.model.BudgetPeriodType.WEEKLY && it.categoryId == 10L })
        assertEquals(2, sanitized.count { it.periodType == com.folioledger.app.domain.model.BudgetPeriodType.CUSTOM })
    }

    @Test
    fun parseBudgets_legacyV5Json_defaultsToMonthlyWithoutRollover() {
        val legacyJson = org.json.JSONArray().apply {
            put(
                org.json.JSONObject().apply {
                    put("id", 101L)
                    put("categoryId", 10L)
                    put("limitAmountMinor", 45000L)
                    put("alertThresholdPercent", 85)
                    put("updatedAtEpochMillis", 1700000000000L)
                }
            )
        }

        val parsed = com.folioledger.app.data.repository.DatabaseBackupEngine.parseBudgets(legacyJson)
        assertEquals(1, parsed.size)
        val b = parsed[0]
        assertEquals(101L, b.id)
        assertEquals(10L, b.categoryId)
        assertEquals(45000L, b.limitAmountMinor)
        assertEquals(85, b.alertThresholdPercent)
        assertEquals(com.folioledger.app.domain.model.BudgetPeriodType.MONTHLY, b.periodType)
        assertEquals("", b.name)
        assertFalse(b.rolloverEnabled)
        assertFalse(b.rolloverIncludeDeficit)
        assertEquals(null, b.startDateEpochDay)
        assertEquals(null, b.endDateEpochDay)
    }

    @Test
    fun backupRestore_tagsSanitization_deduplicatesCaseInsensitiveAndRemaps() {
        val tag1 = com.folioledger.app.data.local.entity.TagEntity(id = 1L, name = "Groceries")
        val tag2 = com.folioledger.app.data.local.entity.TagEntity(id = 2L, name = "groceries")
        val tag3 = com.folioledger.app.data.local.entity.TagEntity(id = 3L, name = "GROCERIES ")
        val tag4 = com.folioledger.app.data.local.entity.TagEntity(id = 4L, name = "Travel")

        val result = com.folioledger.app.data.repository.BackupSanitizer.sanitizeTags(listOf(tag1, tag2, tag3, tag4))

        assertEquals(2, result.tags.size)
        assertEquals("Groceries", result.tags[0].name)
        assertEquals("Travel", result.tags[1].name)

        assertEquals(1L, result.tagIdRemap[1L])
        assertEquals(1L, result.tagIdRemap[2L])
        assertEquals(1L, result.tagIdRemap[3L])
        assertEquals(4L, result.tagIdRemap[4L])
    }

    @Test
    fun moneyFormatterAndCalculator_calculatePercentage_handlesScaleAndZeroSafely() {
        val pctNormal = com.folioledger.app.domain.util.MoneyFormatterAndCalculator.calculatePercentage(25_00L, 100_00L)
        assertEquals(25.0f, pctNormal, 0.001f)

        val pctZeroTotal = com.folioledger.app.domain.util.MoneyFormatterAndCalculator.calculatePercentage(25_00L, 0L)
        assertEquals(0f, pctZeroTotal, 0.001f)

        val pctZeroPart = com.folioledger.app.domain.util.MoneyFormatterAndCalculator.calculatePercentage(0L, 100_00L)
        assertEquals(0f, pctZeroPart, 0.001f)

        val pctLarge = com.folioledger.app.domain.util.MoneyFormatterAndCalculator.calculatePercentage(
            123_456_789_000L,
            246_913_578_000L
        )
        assertEquals(50.0f, pctLarge, 0.001f)
    }

    @Test
    fun backupSanitizer_sanitizeTransactions_preservesReconciliationOutflowDirection() {
        val accountIds = setOf(1L, 2L)
        val outflowReconciliation = com.folioledger.app.data.local.entity.TransactionEntity(
            id = 10L,
            type = com.folioledger.app.domain.model.TransactionType.RECONCILIATION,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 20000L,
            amountMinor = 5000L,
            currencyCode = "USD",
            accountId = 1L,
            transferToAccountId = 1L // Outflow encoded via transferToAccountId == accountId
        )
        val inflowReconciliation = com.folioledger.app.data.local.entity.TransactionEntity(
            id = 11L,
            type = com.folioledger.app.domain.model.TransactionType.RECONCILIATION,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 20000L,
            amountMinor = 5000L,
            currencyCode = "USD",
            accountId = 1L,
            transferToAccountId = null // Inflow
        )

        val sanitized = com.folioledger.app.data.repository.BackupSanitizer.sanitizeTransactions(
            transactions = listOf(outflowReconciliation, inflowReconciliation),
            accountIds = accountIds,
            categoryIds = emptySet(),
            planIds = emptySet(),
            ruleIds = emptySet()
        )

        assertEquals(2, sanitized.size)
        assertEquals(com.folioledger.app.domain.model.TransactionType.RECONCILIATION, sanitized[0].type)
        assertEquals(1L, sanitized[0].transferToAccountId) // Preserved!
        assertEquals(com.folioledger.app.domain.model.TransactionType.RECONCILIATION, sanitized[1].type)
        assertNull(sanitized[1].transferToAccountId) // Preserved!
    }

    @Test
    fun backupSanitizer_sanitizeTransactions_preservesExternalCcSettlement() {
        val accountIds = setOf(1L)
        val externalSettlement = com.folioledger.app.data.local.entity.TransactionEntity(
            id = 20L,
            type = com.folioledger.app.domain.model.TransactionType.CC_SETTLEMENT,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 20000L,
            amountMinor = 15000L,
            currencyCode = "USD",
            accountId = 1L,
            transferToAccountId = null // External/legacy payment not drawn from tracked Folio bank account
        )

        val sanitized = com.folioledger.app.data.repository.BackupSanitizer.sanitizeTransactions(
            transactions = listOf(externalSettlement),
            accountIds = accountIds,
            categoryIds = emptySet(),
            planIds = emptySet(),
            ruleIds = emptySet()
        )

        assertEquals(1, sanitized.size)
        // Must remain CC_SETTLEMENT, NOT converted to EXPENSE
        assertEquals(com.folioledger.app.domain.model.TransactionType.CC_SETTLEMENT, sanitized[0].type)
        assertNull(sanitized[0].transferToAccountId)
    }

    @Test
    fun backupSanitizer_sanitizeBudgets_filtersInvalidIdsAndDeduplicates() {
        val invalidBudget = com.folioledger.app.data.local.entity.BudgetEntity(
            id = 0L,
            categoryId = 10L,
            limitAmountMinor = 5000L,
            periodType = com.folioledger.app.domain.model.BudgetPeriodType.MONTHLY
        )
        val validBudget1 = com.folioledger.app.data.local.entity.BudgetEntity(
            id = 1L,
            categoryId = 10L,
            limitAmountMinor = 5000L,
            periodType = com.folioledger.app.domain.model.BudgetPeriodType.MONTHLY
        )
        val duplicateBudget1 = com.folioledger.app.data.local.entity.BudgetEntity(
            id = 1L,
            categoryId = 10L,
            limitAmountMinor = 6000L,
            periodType = com.folioledger.app.domain.model.BudgetPeriodType.MONTHLY
        )

        val sanitized = com.folioledger.app.data.repository.BackupSanitizer.sanitizeBudgets(
            budgets = listOf(invalidBudget, validBudget1, duplicateBudget1),
            categoryIds = setOf(10L)
        )

        assertEquals(1, sanitized.size)
        assertEquals(1L, sanitized[0].id)
    }
}
