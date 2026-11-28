/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.data

import com.folioledger.app.data.local.entity.AccountEntity
import com.folioledger.app.domain.model.AccountGroup
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
            lastBackupEpochMillis = 1730000000000L,
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
            lastBackupEpochMillis = 1729000000000L,
        )

        // Incoming legacy backup without supporter keys:
        val incomingHasSupporterKey = false
        val incomingSupporterTierValue: String? = null

        val restored = UserPreferences(
            primaryCurrencyCode = "USD",
            isSupporter = if (incomingHasSupporterKey) false else current.isSupporter,
            supporterTier = incomingSupporterTierValue ?: current.supporterTier,
            lastBackupEpochMillis = current.lastBackupEpochMillis,
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
            iconPackTheme = "PHOSPHOR",
        )
        assertEquals("PHOSPHOR", originalPrefs.iconPackTheme)

        val restored = UserPreferences(
            primaryCurrencyCode = "USD",
            iconPackTheme = "EMOJI",
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
            isPinned = true,
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
            file = null,
        )
        assertEquals("application/zip", model.mimeType)
        assertEquals("Downloads/Folio/Folio_Backup_2026-11-09.zip", model.publicDisplayPath)
        assertEquals("Backup Created!", model.title)
    }

    @Test
    fun fileExportSuccessUiModel_folioMimeType_resolvedCorrectly() {
        val model = com.folioledger.app.ui.settings.FileExportSuccessUiModel(
            title = "Encrypted Backup Created!",
            subtitle = "AES-256-GCM encrypted .folio archive saved",
            fileName = "Folio_Backup_2026-11-27.folio",
            fileSizeBytes = 2048L,
            file = null,
        )
        assertEquals("application/octet-stream", model.mimeType)
        assertEquals("Downloads/Folio/Folio_Backup_2026-11-27.folio", model.publicDisplayPath)
    }

    @Test
    fun fileExportSuccessUiModel_csvMimeType_resolvedCorrectly() {
        val model = com.folioledger.app.ui.settings.FileExportSuccessUiModel(
            title = "Template Downloaded!",
            subtitle = "Side-by-side CSV template ready for entry",
            fileName = "Folio_Import_Template.csv",
            fileSizeBytes = 512L,
            file = null,
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
            file = null,
        )
        val stateWithSuccess = defaultState.copy(lastExportedFileSuccess = successModel)
        assertEquals(successModel, stateWithSuccess.lastExportedFileSuccess)
    }

    @Test
    fun backupRestore_foreignKeySanitization_danglingAndCircularIdsNulled() {
        val account1 = com.folioledger.app.data.local.entity.AccountEntity(
            id = 1L,
            name = "Bank",
            groupType = com.folioledger.app.domain.model.AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
        )
        val account2Dangling = com.folioledger.app.data.local.entity.AccountEntity(
            id = 2L,
            name = "Card",
            groupType = com.folioledger.app.domain.model.AccountGroup.DEBIT_CARD,
            currencyCode = "INR",
            parentAccountId = 999L, // Non-existent parent
        )
        val account3Circular = com.folioledger.app.data.local.entity.AccountEntity(
            id = 3L,
            name = "Circular",
            groupType = com.folioledger.app.domain.model.AccountGroup.DEBIT_CARD,
            currencyCode = "INR",
            parentAccountId = 3L, // Self parent
        )
        val accounts = listOf(account1, account2Dangling, account3Circular)
        val sanitizedAccounts = com.folioledger.app.data.repository.BackupSanitizer.sanitizeAccounts(accounts)

        assertNull("Dangling parentAccountId should be nulled out", sanitizedAccounts[1].parentAccountId)
        assertNull("Circular parentAccountId should be nulled out", sanitizedAccounts[2].parentAccountId)
    }

    @Test
    fun backupRestore_multiLevelParentSanitization_resetsToRoot() {
        val rootAccount = com.folioledger.app.data.local.entity.AccountEntity(
            id = 1L,
            name = "Root Bank",
            groupType = com.folioledger.app.domain.model.AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
            parentAccountId = null,
        )
        val childAccount = com.folioledger.app.data.local.entity.AccountEntity(
            id = 2L,
            name = "Child Debit Card",
            groupType = com.folioledger.app.domain.model.AccountGroup.DEBIT_CARD,
            currencyCode = "INR",
            parentAccountId = 1L,
        )
        val grandChildAccount = com.folioledger.app.data.local.entity.AccountEntity(
            id = 3L,
            name = "Grandchild Debit Card",
            groupType = com.folioledger.app.domain.model.AccountGroup.DEBIT_CARD,
            currencyCode = "INR",
            parentAccountId = 2L,
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
            id = 1L,
            title = "Valid",
            type = com.folioledger.app.domain.model.TransactionType.TRANSFER,
            amountMinor = 100L,
            accountId = 1L,
            transferToAccountId = 2L,
            categoryId = 10L,
        )
        val bmDanglingSource = com.folioledger.app.data.local.entity.BookmarkEntity(
            id = 2L,
            title = "Dangling Source",
            type = com.folioledger.app.domain.model.TransactionType.EXPENSE,
            amountMinor = 50L,
            accountId = 999L,
        )
        val bmDanglingTargetAndCat = com.folioledger.app.data.local.entity.BookmarkEntity(
            id = 3L,
            title = "Dangling Target",
            type = com.folioledger.app.domain.model.TransactionType.TRANSFER,
            amountMinor = 50L,
            accountId = 1L,
            transferToAccountId = 888L,
            categoryId = 777L,
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
            id = 1L,
            categoryId = null,
            limitAmountMinor = 50000L,
            periodType = com.folioledger.app.domain.model.BudgetPeriodType.MONTHLY,
        )
        val duplicateMonthlyOverall = com.folioledger.app.data.local.entity.BudgetEntity(
            id = 2L,
            categoryId = null,
            limitAmountMinor = 55000L,
            periodType = com.folioledger.app.domain.model.BudgetPeriodType.MONTHLY,
        )
        val annualOverall = com.folioledger.app.data.local.entity.BudgetEntity(
            id = 3L,
            categoryId = null,
            limitAmountMinor = 600000L,
            periodType = com.folioledger.app.domain.model.BudgetPeriodType.ANNUAL,
        )
        val weeklyCategory10 = com.folioledger.app.data.local.entity.BudgetEntity(
            id = 4L,
            categoryId = 10L,
            limitAmountMinor = 2500L,
            periodType = com.folioledger.app.domain.model.BudgetPeriodType.WEEKLY,
        )
        val tripEurope = com.folioledger.app.data.local.entity.BudgetEntity(
            id = 5L,
            categoryId = null,
            name = "Europe Trip",
            limitAmountMinor = 200000L,
            periodType = com.folioledger.app.domain.model.BudgetPeriodType.CUSTOM,
            startDateEpochDay = 20000L,
            endDateEpochDay = 20014L,
        )
        val tripJapan = com.folioledger.app.data.local.entity.BudgetEntity(
            id = 6L,
            categoryId = null,
            name = "Japan Trip",
            limitAmountMinor = 300000L,
            periodType = com.folioledger.app.domain.model.BudgetPeriodType.CUSTOM,
            startDateEpochDay = 20100L,
            endDateEpochDay = 20110L,
        )
        val invalidCustom = com.folioledger.app.data.local.entity.BudgetEntity(
            id = 7L,
            categoryId = null,
            name = "Invalid",
            limitAmountMinor = 10000L,
            periodType = com.folioledger.app.domain.model.BudgetPeriodType.CUSTOM,
            startDateEpochDay = null, // missing start date
        )

        val list = listOf(
            monthlyOverall,
            duplicateMonthlyOverall,
            annualOverall,
            weeklyCategory10,
            tripEurope,
            tripJapan,
            invalidCustom,
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
                },
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
            246_913_578_000L,
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
            transferToAccountId = 1L, // Outflow encoded via transferToAccountId == accountId
        )
        val inflowReconciliation = com.folioledger.app.data.local.entity.TransactionEntity(
            id = 11L,
            type = com.folioledger.app.domain.model.TransactionType.RECONCILIATION,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 20000L,
            amountMinor = 5000L,
            currencyCode = "USD",
            accountId = 1L,
            transferToAccountId = null, // Inflow
        )

        val sanitized = com.folioledger.app.data.repository.BackupSanitizer.sanitizeTransactions(
            transactions = listOf(outflowReconciliation, inflowReconciliation),
            accountIds = accountIds,
            categoryIds = emptySet(),
            planIds = emptySet(),
            ruleIds = emptySet(),
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
            transferToAccountId = null, // External/legacy payment not drawn from tracked Folio bank account
        )

        val sanitized = com.folioledger.app.data.repository.BackupSanitizer.sanitizeTransactions(
            transactions = listOf(externalSettlement),
            accountIds = accountIds,
            categoryIds = emptySet(),
            planIds = emptySet(),
            ruleIds = emptySet(),
        )

        assertEquals(1, sanitized.size)
        // Must remain CC_SETTLEMENT, NOT converted to EXPENSE
        assertEquals(com.folioledger.app.domain.model.TransactionType.CC_SETTLEMENT, sanitized[0].type)
        assertNull(sanitized[0].transferToAccountId)
    }

    @Test
    fun backupSanitizer_sanitizeTransactions_restoresOutflowMarkerForLiabilityAccountsWhenNull() {
        val loanAccountId = 5L
        val bankAccountId = 1L
        val accountIds = setOf(bankAccountId, loanAccountId)
        val liabilityAccountIds = setOf(loanAccountId)

        val loanOpeningBalanceWithNullTarget = com.folioledger.app.data.local.entity.TransactionEntity(
            id = 30L,
            type = com.folioledger.app.domain.model.TransactionType.OPENING_BALANCE,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 20000L,
            amountMinor = 50000_00L, // $50,000 debt
            currencyCode = "USD",
            accountId = loanAccountId,
            transferToAccountId = null, // Missing or nulled out in legacy backup
        )
        val bankOpeningBalanceWithNullTarget = com.folioledger.app.data.local.entity.TransactionEntity(
            id = 31L,
            type = com.folioledger.app.domain.model.TransactionType.OPENING_BALANCE,
            timestampEpochMillis = 1000L,
            ledgerDateEpochDay = 20000L,
            amountMinor = 10000_00L, // $10,000 asset
            currencyCode = "USD",
            accountId = bankAccountId,
            transferToAccountId = null, // Normal asset inflow
        )

        val sanitized = com.folioledger.app.data.repository.BackupSanitizer.sanitizeTransactions(
            transactions = listOf(loanOpeningBalanceWithNullTarget, bankOpeningBalanceWithNullTarget),
            accountIds = accountIds,
            categoryIds = emptySet(),
            planIds = emptySet(),
            ruleIds = emptySet(),
            liabilityAccountIds = liabilityAccountIds,
        )

        assertEquals(2, sanitized.size)
        // Loan opening balance must have self-outflow marker restored to prevent sign flip
        assertEquals(com.folioledger.app.domain.model.TransactionType.OPENING_BALANCE, sanitized[0].type)
        assertEquals(loanAccountId, sanitized[0].transferToAccountId)

        // Bank opening balance must remain null (inflow / asset)
        assertEquals(com.folioledger.app.domain.model.TransactionType.OPENING_BALANCE, sanitized[1].type)
        assertNull(sanitized[1].transferToAccountId)
    }

    @Test
    fun backupSanitizer_sanitizeBudgets_filtersInvalidIdsAndDeduplicates() {
        val invalidBudget = com.folioledger.app.data.local.entity.BudgetEntity(
            id = 0L,
            categoryId = 10L,
            limitAmountMinor = 5000L,
            periodType = com.folioledger.app.domain.model.BudgetPeriodType.MONTHLY,
        )
        val validBudget1 = com.folioledger.app.data.local.entity.BudgetEntity(
            id = 1L,
            categoryId = 10L,
            limitAmountMinor = 5000L,
            periodType = com.folioledger.app.domain.model.BudgetPeriodType.MONTHLY,
        )
        val duplicateBudget1 = com.folioledger.app.data.local.entity.BudgetEntity(
            id = 1L,
            categoryId = 10L,
            limitAmountMinor = 6000L,
            periodType = com.folioledger.app.domain.model.BudgetPeriodType.MONTHLY,
        )

        val sanitized = com.folioledger.app.data.repository.BackupSanitizer.sanitizeBudgets(
            budgets = listOf(invalidBudget, validBudget1, duplicateBudget1),
            categoryIds = setOf(10L),
        )

        assertEquals(1, sanitized.size)
        assertEquals(1L, sanitized[0].id)
    }

    @Test
    fun folioBackupAgent_inheritsFromBackupAgent() {
        val agent = com.folioledger.app.data.backup.FolioBackupAgent()
        assertTrue("FolioBackupAgent must extend android.app.backup.BackupAgent for D2D Full Backup support", agent is android.app.backup.BackupAgent)
    }

    @Test
    fun secondaryCurrencies_parseAndSerialize_handlesNonFiniteLocaleAndPartialCorruption() {
        val prevLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))

            // Partial corruption (string entry + Infinity rate) must not wipe valid USD/INR entries
            val mixedJson = """[
                "corrupt_element",
                {"code": "usd", "rate": 83.5},
                {"code": "eur", "rate": "Infinity"},
                {"code": "inr", "rate": 1.0}
            ]"""
            val parsed = com.folioledger.app.data.repository.parseSecondaryCurrencies(mixedJson)
            assertEquals(2, parsed.size)
            assertEquals("USD", parsed[0].code)
            assertEquals(83.5, parsed[0].exchangeRateToPrimary, 0.0001)
            assertEquals("INR", parsed[1].code) // Must NOT become "İNR" under tr-TR

            // Serialization must filter non-finite rates instead of throwing JSONException
            val serialized = com.folioledger.app.data.repository.serializeSecondaryCurrencies(
                listOf(
                    com.folioledger.app.domain.model.SecondaryCurrency("usd", 84.0),
                    com.folioledger.app.domain.model.SecondaryCurrency("eur", Double.POSITIVE_INFINITY),
                    com.folioledger.app.domain.model.SecondaryCurrency("gbp", Double.NaN),
                ),
            )
            val reparsed = com.folioledger.app.data.repository.parseSecondaryCurrencies(serialized)
            assertEquals(1, reparsed.size)
            assertEquals("USD", reparsed[0].code)
            assertEquals(84.0, reparsed[0].exchangeRateToPrimary, 0.0001)
        } finally {
            java.util.Locale.setDefault(prevLocale)
        }
    }

    @Test
    fun validateJsonObjectString_recoversCorruptJsonToEmptyObject() {
        assertEquals("{}", com.folioledger.app.data.repository.validateJsonObjectString(null))
        assertEquals("{}", com.folioledger.app.data.repository.validateJsonObjectString(""))
        assertEquals("{}", com.folioledger.app.data.repository.validateJsonObjectString("[1, 2, 3]"))
        assertEquals("{}", com.folioledger.app.data.repository.validateJsonObjectString("{bad_json"))
        val valid = """{"1":{"targetAmountMinor":1000}}"""
        assertEquals(valid, com.folioledger.app.data.repository.validateJsonObjectString(valid))
    }

    @Test
    fun parseBackupEnum_rejectsUnsupportedFutureEnumsWithUpdateMessage() {
        val parsed = com.folioledger.app.data.repository.DatabaseBackupEngine.parseBackupEnum<
            com.folioledger.app.domain.model.TransactionType,
            >("EXPENSE", "transaction type")
        assertEquals(com.folioledger.app.domain.model.TransactionType.EXPENSE, parsed)

        val err = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            com.folioledger.app.data.repository.DatabaseBackupEngine.parseBackupEnum<
                com.folioledger.app.domain.model.TransactionType,
                >("FUTURE_CRYPTO_SWAP", "transaction type")
        }
        assertTrue(err.message!!.contains("Please update Folio to restore this backup"))
    }

    @Test
    fun updateBookmarkTagsCsv_preservesCanonicalTargetTagCasing() {
        val updated = com.folioledger.app.data.local.dao.updateBookmarkTagsCsv(
            tagsCsv = "Food, travel, Work",
            oldName = "travel",
            newName = "Travel",
        )
        assertEquals("Food,Travel,Work", updated)
    }

    @Test
    fun userPreferences_dailyLogReminderFields_roundTripAndLegacyFallback() {
        val original = UserPreferences(
            primaryCurrencyCode = "USD",
            dailyLogReminderEnabled = true,
            dailyLogReminderHour = 21,
            dailyLogReminderMinute = 45,
            dailyLogReminderDaysMask = com.folioledger.app.domain.util.DailyLogReminderCalculator.WEEKDAYS_MASK,
            dailyLogReminderSkipIfLoggedToday = false,
            dailyLogReminderSnoozeMinutes = 30,
            dailyLogReminderMessage = "Check evening receipts",
        )

        val json = com.folioledger.app.data.repository.DatabaseBackupEngine.serializeUserPreferences(original)
        val restored = com.folioledger.app.data.repository.DatabaseBackupEngine.deserializeUserPreferences(
            prefsObj = json,
            current = UserPreferences(),
        )

        assertTrue(restored.dailyLogReminderEnabled)
        assertEquals(21, restored.dailyLogReminderHour)
        assertEquals(45, restored.dailyLogReminderMinute)
        assertEquals(
            com.folioledger.app.domain.util.DailyLogReminderCalculator.WEEKDAYS_MASK,
            restored.dailyLogReminderDaysMask,
        )
        assertFalse(restored.dailyLogReminderSkipIfLoggedToday)
        assertEquals(30, restored.dailyLogReminderSnoozeMinutes)
        assertEquals("Check evening receipts", restored.dailyLogReminderMessage)

        // Legacy backup without dailyLogReminder* keys falls back cleanly to safe defaults
        val legacyJson = org.json.JSONObject().apply {
            put("primaryCurrencyCode", "EUR")
            put("remindersEnabled", true)
        }
        val legacyRestored = com.folioledger.app.data.repository.DatabaseBackupEngine.deserializeUserPreferences(
            prefsObj = legacyJson,
            current = UserPreferences(),
        )
        assertEquals("EUR", legacyRestored.primaryCurrencyCode)
        assertFalse(legacyRestored.dailyLogReminderEnabled)
        assertEquals(20, legacyRestored.dailyLogReminderHour)
        assertEquals(30, legacyRestored.dailyLogReminderMinute)
        assertEquals(
            com.folioledger.app.domain.util.DailyLogReminderCalculator.ALL_DAYS_MASK,
            legacyRestored.dailyLogReminderDaysMask,
        )
        assertTrue(legacyRestored.dailyLogReminderSkipIfLoggedToday)
        assertEquals(60, legacyRestored.dailyLogReminderSnoozeMinutes)
        assertEquals("", legacyRestored.dailyLogReminderMessage)

        // Explicit JSON null on dailyLogReminderMessage falls back to current rather than literal "null"
        val explicitNullJson = org.json.JSONObject().apply {
            put("dailyLogReminderMessage", org.json.JSONObject.NULL)
        }
        val explicitNullRestored = com.folioledger.app.data.repository.DatabaseBackupEngine.deserializeUserPreferences(
            prefsObj = explicitNullJson,
            current = UserPreferences(dailyLogReminderMessage = "Keep this message"),
        )
        assertEquals("Keep this message", explicitNullRestored.dailyLogReminderMessage)
    }

    @Test
    fun userPreferences_hidePrimaryCurrencySymbol_roundTripAndLegacyFallback() {
        val original = UserPreferences(
            primaryCurrencyCode = "INR",
            hidePrimaryCurrencySymbol = true,
        )
        val json = com.folioledger.app.data.repository.DatabaseBackupEngine.serializeUserPreferences(original)
        assertTrue(json.getBoolean("hidePrimaryCurrencySymbol"))

        val restored = com.folioledger.app.data.repository.DatabaseBackupEngine.deserializeUserPreferences(
            prefsObj = json,
            current = UserPreferences(hidePrimaryCurrencySymbol = false),
        )
        assertTrue(restored.hidePrimaryCurrencySymbol)

        val legacyRestored = com.folioledger.app.data.repository.DatabaseBackupEngine.deserializeUserPreferences(
            prefsObj = org.json.JSONObject().apply { put("primaryCurrencyCode", "USD") },
            current = UserPreferences(hidePrimaryCurrencySymbol = true),
        )
        assertTrue(legacyRestored.hidePrimaryCurrencySymbol)
    }

    @Test
    fun readBackupJsonFromZip_skipsMacOsResourceForksAndPrefersFolioEntry() {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("__MACOSX/._folio_backup_v1.json"))
            zip.write(byteArrayOf(0x00, 0x05, 0x16, 0x07)) // AppleDouble binary bytes
            zip.closeEntry()

            zip.putNextEntry(java.util.zip.ZipEntry("package.json"))
            zip.write("""{"name":"unrelated"}""".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(java.util.zip.ZipEntry("folio_backup_v1.json"))
            zip.write("""{"backupFormatVersion":1,"accounts":[],"categories":[],"transactions":[]}""".toByteArray())
            zip.closeEntry()
        }

        val extracted = com.folioledger.app.data.repository.DatabaseBackupEngine.readBackupJsonFromZip(
            java.io.ByteArrayInputStream(out.toByteArray()),
        )
        assertTrue(extracted.contains("backupFormatVersion"))

        val unsafeOnlyOut = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(unsafeOnlyOut).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("../evil.json"))
            zip.write("""{"backupFormatVersion":1}""".toByteArray())
            zip.closeEntry()
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            com.folioledger.app.data.repository.DatabaseBackupEngine.readBackupJsonFromZip(
                java.io.ByteArrayInputStream(unsafeOnlyOut.toByteArray()),
            )
        }
    }

    @Test
    fun backupSanitizer_enforcesFinancialAndRelationalConstraints() {
        val accounts = com.folioledger.app.data.repository.BackupSanitizer.sanitizeAccounts(
            listOf(
                com.folioledger.app.data.local.entity.AccountEntity(
                    id = 1L,
                    name = "Bank",
                    groupType = com.folioledger.app.domain.model.AccountGroup.BANK_ACCOUNT,
                    currencyCode = "BAD_CODE",
                    settlementDayOfMonth = 99,
                    paymentDueDayOfMonth = 0,
                ),
                com.folioledger.app.data.local.entity.AccountEntity(
                    id = 2L,
                    name = "Card",
                    groupType = com.folioledger.app.domain.model.AccountGroup.CREDIT_CARD,
                    currencyCode = "USD",
                    parentAccountId = 1L,
                ),
            ),
        )
        assertEquals("INR", accounts[0].currencyCode)
        assertEquals(31, accounts[0].settlementDayOfMonth)
        assertEquals(1, accounts[0].paymentDueDayOfMonth)
        assertNull("Cross-group parentAccountId must be nulled", accounts[1].parentAccountId)

        val cats = com.folioledger.app.data.repository.BackupSanitizer.sanitizeCategories(
            listOf(
                com.folioledger.app.data.local.entity.CategoryEntity(
                    id = 1L,
                    name = "Salary",
                    type = com.folioledger.app.domain.model.CategoryType.INCOME,
                    iconName = "payments",
                    colorHex = "#10B981",
                ),
                com.folioledger.app.data.local.entity.CategoryEntity(
                    id = 2L,
                    name = "Dining",
                    type = com.folioledger.app.domain.model.CategoryType.EXPENSE,
                    parentId = 1L,
                    iconName = "restaurant",
                    colorHex = "#F97316",
                ),
            ),
        )
        assertNull("Cross-type parentId must be nulled", cats[1].parentId)

        val plans = com.folioledger.app.data.repository.BackupSanitizer.sanitizeInstallmentPlans(
            installmentPlans = listOf(
                com.folioledger.app.data.local.entity.InstallmentPlanEntity(
                    id = 1L,
                    accountId = 1L,
                    title = "Zero Months",
                    totalAmountMinor = 1000L,
                    monthsCount = 0,
                    startDateEpochDay = 20000L,
                ),
                com.folioledger.app.data.local.entity.InstallmentPlanEntity(
                    id = 2L,
                    accountId = 1L,
                    title = "Valid Plan",
                    totalAmountMinor = 12000L,
                    monthsCount = 6,
                    startDateEpochDay = 20000L,
                ),
            ),
            accountIds = setOf(1L),
            categoryIds = emptySet(),
        )
        assertEquals(1, plans.size)
        assertEquals(2L, plans[0].id)

        val rules = com.folioledger.app.data.repository.BackupSanitizer.sanitizeRecurringRules(
            recurringRules = listOf(
                com.folioledger.app.data.local.entity.RecurringRuleEntity(
                    id = 1L,
                    title = "Zero Rule",
                    type = com.folioledger.app.domain.model.TransactionType.EXPENSE,
                    amountMinor = 0L,
                    currencyCode = "INR",
                    accountId = 1L,
                    frequency = com.folioledger.app.domain.model.RepeatFrequency.EVERY_MONTH,
                    startDateEpochDay = 20000L,
                    nextOccurrenceEpochDay = 20000L,
                ),
                com.folioledger.app.data.local.entity.RecurringRuleEntity(
                    id = 2L,
                    title = "Broken Transfer",
                    type = com.folioledger.app.domain.model.TransactionType.TRANSFER,
                    amountMinor = 500L,
                    currencyCode = "INR",
                    accountId = 1L,
                    transferToAccountId = 999L,
                    frequency = com.folioledger.app.domain.model.RepeatFrequency.EVERY_MONTH,
                    startDateEpochDay = 20000L,
                    nextOccurrenceEpochDay = 20000L,
                ),
            ),
            accountIds = setOf(1L),
            categoryIds = emptySet(),
        )
        assertEquals(1, rules.size)
        assertEquals(com.folioledger.app.domain.model.TransactionType.EXPENSE, rules[0].type)
        assertNull(rules[0].transferToAccountId)

        val reconciledTx = com.folioledger.app.data.repository.BackupSanitizer.reconcileTransactionsAfterSplits(
            transactions = listOf(
                com.folioledger.app.data.local.entity.TransactionEntity(
                    id = 10L,
                    type = com.folioledger.app.domain.model.TransactionType.EXPENSE,
                    timestampEpochMillis = 1700000000000L,
                    ledgerDateEpochDay = 20000L,
                    amountMinor = 1000L,
                    currencyCode = "INR",
                    accountId = 1L,
                    isSplit = true,
                    originalTransactionId = 10L,
                ),
                com.folioledger.app.data.local.entity.TransactionEntity(
                    id = 11L,
                    type = com.folioledger.app.domain.model.TransactionType.REFUND,
                    timestampEpochMillis = 1700000001000L,
                    ledgerDateEpochDay = 20001L,
                    amountMinor = 500L,
                    currencyCode = "INR",
                    accountId = 1L,
                    isSplit = false,
                    originalTransactionId = 999L,
                ),
            ),
            validSplits = emptyList(),
        )
        assertFalse("isSplit must clear when child splits were dropped", reconciledTx[0].isSplit)
        assertNull("Self originalTransactionId must be cleared", reconciledTx[0].originalTransactionId)
        assertNull("Dangling originalTransactionId must be cleared", reconciledTx[1].originalTransactionId)
    }

    @Test
    fun formatBackupRelativeTime_respectsExplicitLocaleParameter() {
        val oldDate = LocalDate.of(2026, 5, 15).atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val refToday = LocalDate.of(2026, 10, 29)
        val enLabel = com.folioledger.app.domain.util.BackupTimeFormatter.formatBackupRelativeTime(
            oldDate,
            ZoneId.of("UTC"),
            refToday,
            java.util.Locale.US,
        )
        val frLabel = com.folioledger.app.domain.util.BackupTimeFormatter.formatBackupRelativeTime(
            oldDate,
            ZoneId.of("UTC"),
            refToday,
            java.util.Locale.FRENCH,
        )
        assertEquals("15 May 2026", enLabel)
        assertTrue(frLabel.contains("mai", ignoreCase = true))
    }

    @Test
    fun parseSecondaryCurrencies_andDeserializeUserPreferences_enforceBoundsAndPrimaryCollision() {
        val parsed = com.folioledger.app.data.repository.parseSecondaryCurrencies(
            """
            [
              {"code":"eur","rate":1.08},
              {"code":"FAKE","rate":2.0},
              {"code":"GBP","rate":1e-9},
              {"code":"JPY","rate":1e12},
              {"code":"CHF","rate":1.15}
            ]
            """.trimIndent(),
        )
        assertEquals(2, parsed.size)
        assertEquals("EUR", parsed[0].code)
        assertEquals("CHF", parsed[1].code)

        val restored = com.folioledger.app.data.repository.DatabaseBackupEngine.deserializeUserPreferences(
            prefsObj = org.json.JSONObject().apply {
                put("primaryCurrencyCode", "eur")
                put("secondaryCurrencies", """[{"code":"EUR","rate":1.0},{"code":"USD","rate":0.92}]""")
            },
            current = UserPreferences(primaryCurrencyCode = "INR"),
        )
        assertEquals("EUR", restored.primaryCurrencyCode)
        assertEquals(1, restored.secondaryCurrencies.size)
        assertEquals("USD", restored.secondaryCurrencies[0].code)
    }

    @Test
    fun storageAccessUtils_rejectsRootDirectoryEqualityAndComputesSuffixPatternParts() {
        val rootDir = java.io.File(System.getProperty("java.io.tmpdir"), "folio_root_guard_test").apply { mkdirs() }
        try {
            assertFalse(
                "Root directory itself must not match confinement",
                com.folioledger.app.data.repository.StorageAccessUtils.isPathConfinedToRoots(rootDir, listOf(rootDir)),
            )
            val child = java.io.File(rootDir, "backup.zip")
            assertTrue(
                com.folioledger.app.data.repository.StorageAccessUtils.isPathConfinedToRoots(child, listOf(rootDir)),
            )
            val escaped = java.io.File(rootDir, "../outside.zip")
            assertFalse(
                com.folioledger.app.data.repository.StorageAccessUtils.isPathConfinedToRoots(escaped, listOf(rootDir)),
            )

            val (base, ext) = com.folioledger.app.data.repository.StorageAccessUtils.splitBaseAndExtension(
                "Folio_Latest_AutoBackup.zip",
            )
            assertEquals("Folio_Latest_AutoBackup", base)
            assertEquals(".zip", ext)
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun sanitizeAccounts_preservesDebitCardParentLinksAndRejectsInvalidParents() {
        val bankParent = AccountEntity(
            id = 1L,
            name = "Primary Bank",
            groupType = AccountGroup.BANK_ACCOUNT,
            currencyCode = "INR",
        )
        val savingsParent = AccountEntity(
            id = 2L,
            name = "High Yield Savings",
            groupType = AccountGroup.SAVINGS,
            currencyCode = "USD",
        )
        val overdraftParent = AccountEntity(
            id = 3L,
            name = "Overdraft Line",
            groupType = AccountGroup.OVERDRAFT,
            currencyCode = "EUR",
        )
        val creditParent = AccountEntity(
            id = 4L,
            name = "Sapphire Credit",
            groupType = AccountGroup.CREDIT_CARD,
            currencyCode = "USD",
        )
        val debitCardBank = AccountEntity(
            id = 10L,
            name = "Primary Debit",
            groupType = AccountGroup.DEBIT_CARD,
            currencyCode = "inr",
            parentAccountId = 1L,
        )
        val debitCardSavings = AccountEntity(
            id = 11L,
            name = "Savings Debit",
            groupType = AccountGroup.DEBIT_CARD,
            currencyCode = "USD",
            parentAccountId = 2L,
        )
        val debitCardOverdraft = AccountEntity(
            id = 12L,
            name = "Overdraft Debit",
            groupType = AccountGroup.DEBIT_CARD,
            currencyCode = "EUR",
            parentAccountId = 3L,
        )
        val debitCardWrongCurrency = AccountEntity(
            id = 13L,
            name = "FX Mismatch Debit",
            groupType = AccountGroup.DEBIT_CARD,
            currencyCode = "USD",
            parentAccountId = 1L,
        )
        val debitCardCreditParent = AccountEntity(
            id = 14L,
            name = "Invalid Credit Parent Debit",
            groupType = AccountGroup.DEBIT_CARD,
            currencyCode = "USD",
            parentAccountId = 4L,
        )
        val debitCardDebitParent = AccountEntity(
            id = 15L,
            name = "Invalid Debit Parent",
            groupType = AccountGroup.DEBIT_CARD,
            currencyCode = "INR",
            parentAccountId = 10L,
        )

        val sanitized = com.folioledger.app.data.repository.BackupSanitizer.sanitizeAccounts(
            listOf(
                bankParent,
                savingsParent,
                overdraftParent,
                creditParent,
                debitCardBank,
                debitCardSavings,
                debitCardOverdraft,
                debitCardWrongCurrency,
                debitCardCreditParent,
                debitCardDebitParent,
            ),
        ).associateBy { it.id }

        assertEquals(1L, sanitized[10L]?.parentAccountId)
        assertEquals("INR", sanitized[10L]?.currencyCode)
        assertEquals(2L, sanitized[11L]?.parentAccountId)
        assertEquals(3L, sanitized[12L]?.parentAccountId)
        assertNull("Mismatched currency parent link must be cleared", sanitized[13L]?.parentAccountId)
        assertNull("Credit card parent for debit card must be cleared", sanitized[14L]?.parentAccountId)
        assertNull("Debit card parent for debit card must be cleared", sanitized[15L]?.parentAccountId)
    }
}
