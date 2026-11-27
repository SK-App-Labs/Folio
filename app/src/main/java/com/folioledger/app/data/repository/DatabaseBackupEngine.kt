/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.folioledger.app.data.backup.AutoBackupRetentionEngine
import com.folioledger.app.data.backup.AutoMirrorKeyVault
import com.folioledger.app.data.backup.BackupCryptoEngine
import com.folioledger.app.data.backup.SafBackupMirrorScheduler
import com.folioledger.app.data.backup.SessionChangeTracker
import com.folioledger.app.data.local.DefaultCategorySeeder
import com.folioledger.app.data.local.FolioDatabase
import com.folioledger.app.data.local.dao.CategoryAndTagDao
import com.folioledger.app.data.local.entity.AccountEntity
import com.folioledger.app.data.local.entity.BookmarkEntity
import com.folioledger.app.data.local.entity.BudgetEntity
import com.folioledger.app.data.local.entity.CategoryEntity
import com.folioledger.app.data.local.entity.InstallmentPlanEntity
import com.folioledger.app.data.local.entity.RecurringRuleEntity
import com.folioledger.app.data.local.entity.TagEntity
import com.folioledger.app.data.local.entity.TransactionEntity
import com.folioledger.app.data.local.entity.TransactionSplitEntity
import com.folioledger.app.data.local.entity.TransactionTagCrossRef
import com.folioledger.app.data.local.entity.TransactionWithDetails
import com.folioledger.app.data.pdf.AccountSnapshotPdfItem
import com.folioledger.app.data.pdf.AccountStatementPdfData
import com.folioledger.app.data.pdf.AnnualTaxPdfData
import com.folioledger.app.data.pdf.CategoryAggregateItem
import com.folioledger.app.data.pdf.PdfStatementEngine
import com.folioledger.app.data.pdf.TaxDeductionPdfItem
import com.folioledger.app.domain.util.DailyLogReminderCalculator
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AutoBackupRetention
import com.folioledger.app.domain.model.BackupInspectionResult
import com.folioledger.app.domain.model.CategoryNode
import com.folioledger.app.domain.model.CategoryType
import com.folioledger.app.domain.model.DuplicateTransactionSample
import com.folioledger.app.domain.model.ImportCsvPreview
import com.folioledger.app.domain.model.ImportCsvResult
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.RepeatFrequency
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.SplitLineItem
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionBookmark
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.domain.repository.CategoryAndTagRepository
import com.folioledger.app.domain.repository.SettingsAndBackupRepository
import com.folioledger.app.domain.usecase.AccountingRulesEngine
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class DatabaseBackupEngine(
    private val context: Context,
    private val database: FolioDatabase,
    private val userPreferences: UserPreferencesDataSource,
    private val backupMutex: Mutex = Mutex()
) {

    companion object {
        const val BACKUP_SCHEMA_VERSION = 1
        const val BACKUP_JSON_ENTRY_NAME = "folio_backup_v1.json"
        const val MAX_BACKUP_JSON_BYTES = 32 * 1024 * 1024 // 32 MB

        internal fun isUnsafeOrMetadataZipEntry(entryName: String): Boolean {
            if (entryName.isBlank()) return true
            if (".." in entryName || entryName.startsWith("/") || entryName.startsWith("\\")) return true
            val normalized = entryName.replace('\\', '/')
            if (normalized.startsWith("__MACOSX/", ignoreCase = true) || "/__MACOSX/" in normalized) return true
            val fileName = normalized.substringAfterLast('/')
            if (fileName.startsWith("._")) return true
            return false
        }

        internal fun readBackupJsonFromZip(inputStream: InputStream): String {
            var exactPayload: String? = null
            val topLevelJsonCandidates = mutableListOf<String>()
            var entriesCount = 0

            fun readCappedEntryText(zipIn: ZipInputStream, alreadyBytes: Int): Pair<String, Int> {
                val buffer = ByteArrayOutputStream()
                val data = ByteArray(4096)
                var count = zipIn.read(data)
                var totalBytesRead = alreadyBytes
                while (count != -1) {
                    totalBytesRead += count
                    if (totalBytesRead > MAX_BACKUP_JSON_BYTES) {
                        throw IllegalArgumentException("Backup payload exceeds maximum allowed size (32 MB).")
                    }
                    buffer.write(data, 0, count)
                    count = zipIn.read(data)
                }
                return buffer.toString(StandardCharsets.UTF_8.name()) to totalBytesRead
            }

            ZipInputStream(inputStream).use { zipIn ->
                var totalJsonBytesRead = 0
                var entry = zipIn.nextEntry
                while (entry != null) {
                    if (++entriesCount > 64) {
                        throw IllegalArgumentException("Backup archive exceeds maximum allowed entry count (64).")
                    }
                    val entryName = entry.name
                    if (!entry.isDirectory && !isUnsafeOrMetadataZipEntry(entryName)) {
                        val normalized = entryName.replace('\\', '/')
                        if (normalized == BACKUP_JSON_ENTRY_NAME) {
                            val (text, nextTotal) = readCappedEntryText(zipIn, totalJsonBytesRead)
                            totalJsonBytesRead = nextTotal
                            exactPayload = text
                            zipIn.closeEntry()
                            break
                        } else if ('/' !in normalized && normalized.endsWith(".json", ignoreCase = true)) {
                            if (topLevelJsonCandidates.size < 2) {
                                val (text, nextTotal) = readCappedEntryText(zipIn, totalJsonBytesRead)
                                totalJsonBytesRead = nextTotal
                                topLevelJsonCandidates.add(text)
                            }
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }

            return exactPayload
                ?: topLevelJsonCandidates.singleOrNull()
                ?: throw IllegalArgumentException("Invalid backup archive: missing Folio ledger JSON payload.")
        }

        internal fun resolveRestoredSupporterTier(
            prefsObj: JSONObject,
            currentSupporterTier: String?
        ): String? {
            val restoredTier = if (prefsObj.has("supporterTier") && !prefsObj.isNull("supporterTier")) {
                prefsObj.optString("supporterTier").ifBlank { null }
            } else null

            val currentTierEnum = currentSupporterTier?.let { com.folioledger.app.domain.model.SupporterTier.fromSkuOrTitle(it) }
            val restoredTierEnum = restoredTier?.let { com.folioledger.app.domain.model.SupporterTier.fromSkuOrTitle(it) }

            return when {
                currentTierEnum != null && restoredTierEnum != null -> {
                    if (currentTierEnum >= restoredTierEnum) currentSupporterTier else restoredTier
                }
                currentTierEnum != null -> currentSupporterTier
                else -> restoredTier
            }
        }

        internal fun JSONObject.optNullableLong(key: String): Long? =
            if (isNull(key)) null else optLong(key)

        internal fun JSONObject.putNullableLong(key: String, value: Long?) {
            if (value == null) put(key, JSONObject.NULL) else put(key, value)
        }

        internal fun parseSplits(array: JSONArray?): List<TransactionSplitEntity> {
            if (array == null) return emptyList()
            return List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                TransactionSplitEntity(
                    id = obj.getLong("id"),
                    transactionId = obj.getLong("transactionId"),
                    categoryId = obj.optNullableLong("categoryId"),
                    amountMinor = obj.getLong("amountMinor"),
                    note = obj.optString("note", "")
                )
            }
        }

        internal inline fun <reified T : Enum<T>> parseBackupEnum(raw: String, fieldLabel: String): T {
            return runCatching { enumValueOf<T>(raw) }.getOrElse {
                throw IllegalArgumentException(
                    "Unsupported $fieldLabel ('$raw'). Please update Folio to restore this backup."
                )
            }
        }

        internal fun serializeUserPreferences(currentPrefs: UserPreferences): JSONObject {
            return JSONObject().apply {
                put("primaryCurrencyCode", currentPrefs.primaryCurrencyCode)
                put("secondaryCurrencies", serializeSecondaryCurrencies(currentPrefs.secondaryCurrencies))
                put("numberGrouping", currentPrefs.numberGrouping.name)
                put("monthStartDay", currentPrefs.monthStartDay)
                put("weekStartDayIso", currentPrefs.weekStartDayIso)
                put("subcategoriesEnabled", currentPrefs.subcategoriesEnabled)
                put("carryOverEnabled", currentPrefs.carryOverEnabled)
                put("themeMode", currentPrefs.themeMode)
                put("dynamicColorEnabled", currentPrefs.dynamicColorEnabled)
                put("accentPalette", currentPrefs.accentPalette)
                put("biometricLockEnabled", currentPrefs.biometricLockEnabled)
                put("isSupporter", currentPrefs.isSupporter)
                if (currentPrefs.supporterTier == null) {
                    put("supporterTier", JSONObject.NULL)
                } else {
                    put("supporterTier", currentPrefs.supporterTier)
                }
                put("cardMilestonesJson", currentPrefs.cardMilestonesJson)
                put("iconPackTheme", currentPrefs.iconPackTheme)
                put("monthlySavingsTargetMinor", currentPrefs.monthlySavingsTargetMinor)
                put("savingsGoalsJson", currentPrefs.savingsGoalsJson)
                put("remindersEnabled", currentPrefs.remindersEnabled)
                put("ccReminderLeadDays", currentPrefs.ccReminderLeadDays)
                put("billReminderLeadDays", currentPrefs.billReminderLeadDays)
                put("reminderHourOfDay", currentPrefs.reminderHourOfDay)
                put("reminderMinute", currentPrefs.reminderMinute)
                put("hideAmountsOnLockScreen", currentPrefs.hideAmountsOnLockScreen)
                put("dailyLogReminderEnabled", currentPrefs.dailyLogReminderEnabled)
                put("dailyLogReminderHour", currentPrefs.dailyLogReminderHour)
                put("dailyLogReminderMinute", currentPrefs.dailyLogReminderMinute)
                put("dailyLogReminderDaysMask", currentPrefs.dailyLogReminderDaysMask)
                put("dailyLogReminderSkipIfLoggedToday", currentPrefs.dailyLogReminderSkipIfLoggedToday)
                put("dailyLogReminderSnoozeMinutes", currentPrefs.dailyLogReminderSnoozeMinutes)
                put("dailyLogReminderMessage", currentPrefs.dailyLogReminderMessage)
                put("autoBackupEnabled", currentPrefs.autoBackupEnabled)
                put("autoBackupPublicMirrorEnabled", currentPrefs.autoBackupPublicMirrorEnabled)
                put("autoBackupRetention", currentPrefs.autoBackupRetention.name)
            }
        }

        internal fun deserializeUserPreferences(
            prefsObj: JSONObject,
            current: UserPreferences
        ): UserPreferences {
            val resolvedSupporterTier = resolveRestoredSupporterTier(prefsObj, current.supporterTier)
            val resolvedPrimary = normalizePrimaryCurrencyCode(
                prefsObj.optString("primaryCurrencyCode", current.primaryCurrencyCode)
            )
            val rawSecondary = if (prefsObj.has("secondaryCurrencies")) {
                parseSecondaryCurrencies(prefsObj.optString("secondaryCurrencies", "[]"))
            } else {
                current.secondaryCurrencies
            }
            val resolvedSecondary = rawSecondary.filterNot {
                it.code.equals(resolvedPrimary, ignoreCase = true)
            }
            return UserPreferences(
                primaryCurrencyCode = resolvedPrimary,
                secondaryCurrencies = resolvedSecondary,
                numberGrouping = runCatching {
                    NumberGroupingSystem.valueOf(
                        prefsObj.optString("numberGrouping", current.numberGrouping.name)
                    )
                }.getOrDefault(current.numberGrouping),
                monthStartDay = prefsObj.optInt("monthStartDay", current.monthStartDay)
                    .coerceIn(1, 31),
                weekStartDayIso = prefsObj.optInt("weekStartDayIso", current.weekStartDayIso)
                    .coerceIn(1, 7),
                subcategoriesEnabled = prefsObj.optBoolean(
                    "subcategoriesEnabled",
                    current.subcategoriesEnabled
                ),
                carryOverEnabled = prefsObj.optBoolean(
                    "carryOverEnabled",
                    current.carryOverEnabled
                ),
                themeMode = prefsObj.optString("themeMode", current.themeMode),
                dynamicColorEnabled = prefsObj.optBoolean(
                    "dynamicColorEnabled",
                    current.dynamicColorEnabled
                ),
                accentPalette = prefsObj.optString("accentPalette", current.accentPalette),
                biometricLockEnabled = prefsObj.optBoolean(
                    "biometricLockEnabled",
                    current.biometricLockEnabled
                ),
                isSupporter = current.isSupporter || (resolvedSupporterTier != null),
                supporterTier = resolvedSupporterTier,
                lastBackupEpochMillis = current.lastBackupEpochMillis,
                cardMilestonesJson = validateJsonObjectString(
                    prefsObj.optString("cardMilestonesJson", current.cardMilestonesJson)
                ),
                iconPackTheme = prefsObj.optString("iconPackTheme", current.iconPackTheme),
                monthlySavingsTargetMinor = prefsObj.optLong(
                    "monthlySavingsTargetMinor",
                    current.monthlySavingsTargetMinor
                ).coerceAtLeast(0L),
                savingsGoalsJson = validateJsonObjectString(
                    prefsObj.optString("savingsGoalsJson", current.savingsGoalsJson)
                ),
                hasSeenWelcomeSheet = true,
                isDemoModeActive = false,
                hasStashedRealLedger = false,
                remindersEnabled = prefsObj.optBoolean("remindersEnabled", current.remindersEnabled),
                ccReminderLeadDays = prefsObj.optInt("ccReminderLeadDays", current.ccReminderLeadDays).coerceIn(0, 14),
                billReminderLeadDays = prefsObj.optInt("billReminderLeadDays", current.billReminderLeadDays).coerceIn(0, 7),
                reminderHourOfDay = prefsObj.optInt("reminderHourOfDay", current.reminderHourOfDay).coerceIn(0, 23),
                reminderMinute = prefsObj.optInt("reminderMinute", current.reminderMinute).coerceIn(0, 59),
                hideAmountsOnLockScreen = prefsObj.optBoolean(
                    "hideAmountsOnLockScreen",
                    current.hideAmountsOnLockScreen
                ),
                dailyLogReminderEnabled = prefsObj.optBoolean(
                    "dailyLogReminderEnabled",
                    current.dailyLogReminderEnabled
                ),
                dailyLogReminderHour = prefsObj.optInt(
                    "dailyLogReminderHour",
                    current.dailyLogReminderHour
                ).coerceIn(0, 23),
                dailyLogReminderMinute = prefsObj.optInt(
                    "dailyLogReminderMinute",
                    current.dailyLogReminderMinute
                ).coerceIn(0, 59),
                dailyLogReminderDaysMask = DailyLogReminderCalculator.normalizeDaysMask(
                    prefsObj.optInt("dailyLogReminderDaysMask", current.dailyLogReminderDaysMask)
                ),
                dailyLogReminderSkipIfLoggedToday = prefsObj.optBoolean(
                    "dailyLogReminderSkipIfLoggedToday",
                    current.dailyLogReminderSkipIfLoggedToday
                ),
                dailyLogReminderSnoozeMinutes = DailyLogReminderCalculator.normalizeSnoozeMinutes(
                    prefsObj.optInt(
                        "dailyLogReminderSnoozeMinutes",
                        current.dailyLogReminderSnoozeMinutes
                    )
                ),
                dailyLogReminderMessage = (if (prefsObj.has("dailyLogReminderMessage") && !prefsObj.isNull("dailyLogReminderMessage")) {
                    prefsObj.optString("dailyLogReminderMessage", current.dailyLogReminderMessage)
                } else {
                    current.dailyLogReminderMessage
                }).trim().take(DailyLogReminderCalculator.MAX_CUSTOM_MESSAGE_LENGTH),
                autoBackupEnabled = prefsObj.optBoolean("autoBackupEnabled", current.autoBackupEnabled),
                autoBackupPublicMirrorEnabled = if (prefsObj.optBoolean("biometricLockEnabled", current.biometricLockEnabled)) {
                    false
                } else {
                    prefsObj.optBoolean("autoBackupPublicMirrorEnabled", current.autoBackupPublicMirrorEnabled)
                },
                autoBackupRetention = runCatching {
                    AutoBackupRetention.valueOf(
                        prefsObj.optString("autoBackupRetention", current.autoBackupRetention.name)
                    )
                }.getOrDefault(current.autoBackupRetention),
                // Preserve device-local SAF mirror configuration across restores
                safMirrorEnabled = current.safMirrorEnabled,
                safMirrorTreeUri = current.safMirrorTreeUri,
                safMirrorFolderDisplayName = current.safMirrorFolderDisplayName,
                safMirrorEncryptEnabled = current.safMirrorEncryptEnabled,
                lastSafMirrorEpochMillis = current.lastSafMirrorEpochMillis,
                safMirrorErrorCode = current.safMirrorErrorCode
            )
        }

        internal fun parseBudgets(array: JSONArray?): List<BudgetEntity> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val periodType = if (obj.has("periodType") && !obj.isNull("periodType")) {
                    parseBackupEnum<com.folioledger.app.domain.model.BudgetPeriodType>(
                        obj.getString("periodType"),
                        "budget period type"
                    )
                } else {
                    com.folioledger.app.domain.model.BudgetPeriodType.MONTHLY
                }
                val rolloverEnabled = if (periodType.supportsRollover) obj.optBoolean("rolloverEnabled", false) else false
                BudgetEntity(
                    id = obj.optLong("id", 0L),
                    categoryId = obj.optNullableLong("categoryId"),
                    name = obj.optString("name", "").trim(),
                    limitAmountMinor = obj.optLong("limitAmountMinor", 0L),
                    alertThresholdPercent = obj.optInt("alertThresholdPercent", 80).coerceIn(50, 100),
                    periodType = periodType,
                    startDateEpochDay = obj.optNullableLong("startDateEpochDay"),
                    endDateEpochDay = obj.optNullableLong("endDateEpochDay"),
                    rolloverEnabled = rolloverEnabled,
                    rolloverIncludeDeficit = if (rolloverEnabled) obj.optBoolean("rolloverIncludeDeficit", false) else false,
                    updatedAtEpochMillis = obj.optLong("updatedAtEpochMillis", System.currentTimeMillis())
                )
            }
        }
    }

    suspend fun inspectBackup(sourceUriString: String): Result<BackupInspectionResult> = withContext(Dispatchers.IO) {
        runCatchingNonCancellation {
            StorageAccessUtils.openInputStreamForLocation(context, sourceUriString).use { stream ->
                BackupCryptoEngine.inspectHeader(stream)
            }
        }
    }

    suspend fun createPortableZipBackup(
        destinationUriString: String,
        password: CharArray? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        backupMutex.withLock {
            try {
                runCatchingNonCancellation {
                    val currentPrefs = userPreferences.observeUserPreferences().first()
                    require(!currentPrefs.isDemoModeActive) {
                        "Exit Demo Sandbox before creating a backup."
                    }
                    val mirrorToPublicDownloads = !currentPrefs.biometricLockEnabled &&
                        currentPrefs.autoBackupPublicMirrorEnabled
                    if (password != null) {
                        val zipBuffer = ByteArrayOutputStream()
                        createBackupToStream(zipBuffer)
                        val plaintextZip = zipBuffer.toByteArray()
                        val encryptedBuffer = ByteArrayOutputStream(plaintextZip.size + 128)
                        try {
                            BackupCryptoEngine.encryptWithPassword(
                                plaintextZipBytes = plaintextZip,
                                password = password,
                                output = encryptedBuffer
                            )
                        } finally {
                            plaintextZip.fill(0)
                        }
                        StorageAccessUtils.openOutputStreamForLocation(
                            context = context,
                            destinationUriString = destinationUriString,
                            mirrorToPublicDownloads = mirrorToPublicDownloads
                        ).use { rawStream ->
                            encryptedBuffer.writeTo(rawStream)
                        }
                    } else {
                        StorageAccessUtils.openOutputStreamForLocation(
                            context = context,
                            destinationUriString = destinationUriString,
                            mirrorToPublicDownloads = mirrorToPublicDownloads
                        ).use { rawStream ->
                            createBackupToStream(rawStream)
                        }
                    }
                    userPreferences.updateUserPreferences { it.copy(lastBackupEpochMillis = System.currentTimeMillis()) }
                    destinationUriString
                }
            } finally {
                password?.fill('\u0000')
            }
        }
    }

    private data class LedgerDatabaseSnapshot(
        val accounts: List<AccountEntity>,
        val categories: List<CategoryEntity>,
        val tags: List<TagEntity>,
        val bookmarks: List<BookmarkEntity>,
        val installmentPlans: List<InstallmentPlanEntity>,
        val recurringRules: List<RecurringRuleEntity>,
        val transactions: List<TransactionEntity>,
        val splits: List<TransactionSplitEntity>,
        val tagCrossRefs: List<TransactionTagCrossRef>,
        val budgets: List<BudgetEntity>
    )

    suspend fun createBackupToStream(outputStream: OutputStream): Unit = withContext(Dispatchers.IO) {
        val currentPrefs = userPreferences.observeUserPreferences().first()
        val snapshot = database.withTransaction {
            LedgerDatabaseSnapshot(
                accounts = database.accountDao().getAllAccountsOnce(),
                categories = database.categoryAndTagDao().getAllCategoriesOnce(),
                tags = database.categoryAndTagDao().getAllTagsOnce(),
                bookmarks = database.categoryAndTagDao().getAllBookmarksOnce(),
                installmentPlans = database.transactionDao().getAllInstallmentPlansOnce(),
                recurringRules = database.budgetAndRecurringDao().getAllRecurringRulesOnce(),
                transactions = database.transactionDao().getAllTransactionsRawOnce(),
                splits = database.transactionDao().getAllSplitsOnce(),
                tagCrossRefs = database.transactionDao().getAllTagCrossRefsOnce(),
                budgets = database.budgetAndRecurringDao().getAllBudgetsOnce()
            )
        }
        val accounts = snapshot.accounts
        val categories = snapshot.categories
        val tags = snapshot.tags
        val bookmarks = snapshot.bookmarks
        val installmentPlans = snapshot.installmentPlans
        val recurringRules = snapshot.recurringRules
        val transactions = snapshot.transactions
        val splits = snapshot.splits
        val tagCrossRefs = snapshot.tagCrossRefs
        val budgets = snapshot.budgets

        val rootJson = JSONObject().apply {
            put("backupFormatVersion", BACKUP_SCHEMA_VERSION)
            put("createdAtEpochMillis", System.currentTimeMillis())

            put("userPreferences", serializeUserPreferences(currentPrefs))

            put(
                "accounts",
                JSONArray().apply {
                    for (acc in accounts) {
                        put(
                            JSONObject().apply {
                                put("id", acc.id)
                                put("name", acc.name)
                                put("groupType", acc.groupType.name)
                                put("currencyCode", acc.currencyCode)
                                put("openingBalanceMinor", acc.openingBalanceMinor)
                                putNullableLong("initialUnbilledMinor", acc.initialUnbilledMinor)
                                putNullableLong("creditLimitMinor", acc.creditLimitMinor)
                                putNullableInt("settlementDayOfMonth", acc.settlementDayOfMonth)
                                putNullableInt("paymentDueDayOfMonth", acc.paymentDueDayOfMonth)
                                putNullableLong("parentAccountId", acc.parentAccountId)
                                put("includeInNetWorth", acc.includeInNetWorth)
                                put("isArchived", acc.isArchived)
                                put("colorHex", acc.colorHex)
                                put("iconName", acc.iconName)
                                put("sortOrder", acc.sortOrder)
                                put("isPinned", acc.isPinned)
                                put("createdAtEpochMillis", acc.createdAtEpochMillis)
                            }
                        )
                    }
                }
            )

            put(
                "categories",
                JSONArray().apply {
                    for (cat in categories) {
                        put(
                            JSONObject().apply {
                                put("id", cat.id)
                                put("name", cat.name)
                                put("type", cat.type.name)
                                putNullableLong("parentId", cat.parentId)
                                put("iconName", cat.iconName)
                                put("colorHex", cat.colorHex)
                                put("sortOrder", cat.sortOrder)
                                put("isArchived", cat.isArchived)
                            }
                        )
                    }
                }
            )

            put(
                "tags",
                JSONArray().apply {
                    for (tag in tags) {
                        put(
                            JSONObject().apply {
                                put("id", tag.id)
                                put("name", tag.name)
                                put("colorHex", tag.colorHex)
                            }
                        )
                    }
                }
            )

            put(
                "bookmarks",
                JSONArray().apply {
                    for (bm in bookmarks) {
                        put(
                            JSONObject().apply {
                                put("id", bm.id)
                                put("title", bm.title)
                                put("type", bm.type.name)
                                put("amountMinor", bm.amountMinor)
                                put("accountId", bm.accountId)
                                putNullableLong("transferToAccountId", bm.transferToAccountId)
                                putNullableLong("categoryId", bm.categoryId)
                                put("payeeOrDescription", bm.payeeOrDescription)
                                put("note", bm.note)
                                put("tagsCsv", bm.tagsCsv)
                                put("sortOrder", bm.sortOrder)
                            }
                        )
                    }
                }
            )

            put(
                "installmentPlans",
                JSONArray().apply {
                    for (plan in installmentPlans) {
                        put(
                            JSONObject().apply {
                                put("id", plan.id)
                                put("title", plan.title)
                                put("accountId", plan.accountId)
                                putNullableLong("categoryId", plan.categoryId)
                                put("totalAmountMinor", plan.totalAmountMinor)
                                put("monthsCount", plan.monthsCount)
                                put("startDateEpochDay", plan.startDateEpochDay)
                                put("createdAtEpochMillis", plan.createdAtEpochMillis)
                            }
                        )
                    }
                }
            )

            put(
                "recurringRules",
                JSONArray().apply {
                    for (rule in recurringRules) {
                        put(
                            JSONObject().apply {
                                put("id", rule.id)
                                put("title", rule.title)
                                put("type", rule.type.name)
                                put("amountMinor", rule.amountMinor)
                                put("currencyCode", rule.currencyCode)
                                put("accountId", rule.accountId)
                                putNullableLong("transferToAccountId", rule.transferToAccountId)
                                putNullableLong("categoryId", rule.categoryId)
                                put("frequency", rule.frequency.name)
                                put("startDateEpochDay", rule.startDateEpochDay)
                                put("nextOccurrenceEpochDay", rule.nextOccurrenceEpochDay)
                                putNullableLong("endDateEpochDay", rule.endDateEpochDay)
                                put("payeeOrDescription", rule.payeeOrDescription)
                                put("note", rule.note)
                                put("isActive", rule.isActive)
                            }
                        )
                    }
                }
            )

            put(
                "transactions",
                JSONArray().apply {
                    for (tx in transactions) {
                        put(
                            JSONObject().apply {
                                put("id", tx.id)
                                put("type", tx.type.name)
                                put("timestampEpochMillis", tx.timestampEpochMillis)
                                put("ledgerDateEpochDay", tx.ledgerDateEpochDay)
                                put("amountMinor", tx.amountMinor)
                                put("currencyCode", tx.currencyCode)
                                put("accountId", tx.accountId)
                                putNullableLong("transferToAccountId", tx.transferToAccountId)
                                put("transferFeeMinor", tx.transferFeeMinor)
                                putNullableLong("categoryId", tx.categoryId)
                                put("isSplit", tx.isSplit)
                                put("payeeOrDescription", tx.payeeOrDescription)
                                put("note", tx.note)
                                putNullableLong("originalTransactionId", tx.originalTransactionId)
                                putNullableLong("installmentPlanId", tx.installmentPlanId)
                                putNullableInt("installmentIndex", tx.installmentIndex)
                                putNullableInt("installmentTotalCount", tx.installmentTotalCount)
                                putNullableLong("recurringRuleId", tx.recurringRuleId)
                            }
                        )
                    }
                }
            )

            put(
                "transactionSplits",
                JSONArray().apply {
                    for (split in splits) {
                        put(
                            JSONObject().apply {
                                put("id", split.id)
                                put("transactionId", split.transactionId)
                                putNullableLong("categoryId", split.categoryId)
                                put("amountMinor", split.amountMinor)
                                put("note", split.note)
                            }
                        )
                    }
                }
            )

            put(
                "transactionTags",
                JSONArray().apply {
                    for (crossRef in tagCrossRefs) {
                        put(
                            JSONObject().apply {
                                put("transactionId", crossRef.transactionId)
                                put("tagId", crossRef.tagId)
                            }
                        )
                    }
                }
            )

            put(
                "budgets",
                JSONArray().apply {
                    for (budget in budgets) {
                        put(
                            JSONObject().apply {
                                put("id", budget.id)
                                putNullableLong("categoryId", budget.categoryId)
                                put("name", budget.name)
                                put("limitAmountMinor", budget.limitAmountMinor)
                                put("alertThresholdPercent", budget.alertThresholdPercent)
                                put("periodType", budget.periodType.name)
                                putNullableLong("startDateEpochDay", budget.startDateEpochDay)
                                putNullableLong("endDateEpochDay", budget.endDateEpochDay)
                                put("rolloverEnabled", budget.rolloverEnabled)
                                put("rolloverIncludeDeficit", budget.rolloverIncludeDeficit)
                                put("updatedAtEpochMillis", budget.updatedAtEpochMillis)
                            }
                        )
                    }
                }
            )
        }

        ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
            val entry = ZipEntry(BACKUP_JSON_ENTRY_NAME)
            zipOut.putNextEntry(entry)
            val writer = OutputStreamWriter(zipOut, StandardCharsets.UTF_8)
            writer.write(rootJson.toString())
            writer.flush()
            zipOut.closeEntry()
            zipOut.finish()
        }
        Unit
    }

    suspend fun restoreFromZipBackup(
        sourceUriString: String,
        password: CharArray? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        backupMutex.withLock {
            try {
                runCatchingNonCancellation {
                    require(!userPreferences.observeUserPreferences().first().isDemoModeActive) {
                        "Exit Demo Sandbox before restoring a backup."
                    }

                    var decryptedZipBytes: ByteArray? = null
                    val safetyFile = File(context.cacheDir, "pre_restore_safety_backup.zip")
                    val safetyTempFile = File(context.cacheDir, "pre_restore_safety_backup.zip.tmp")
                    var dbWriteCommitted = false
                    try {
                        StorageAccessUtils.openInputStreamForLocation(
                            context = context,
                            sourceUriString = sourceUriString
                        ).use { rawStream ->
                            val buffered = java.io.BufferedInputStream(rawStream, 64)
                            buffered.mark(32)
                            val inspection = BackupCryptoEngine.inspectHeader(buffered)
                            buffered.reset()
                            when (inspection) {
                                BackupInspectionResult.PlaintextZip -> {
                                    createSafetyRollbackSnapshot(safetyFile, safetyTempFile)
                                    restoreBackupFromStream(buffered) {
                                        dbWriteCommitted = true
                                    }
                                }
                                is BackupInspectionResult.EncryptedFolio -> {
                                    val chars = password ?: throw IllegalArgumentException(
                                        "Backup password is required to decrypt this .folio archive."
                                    )
                                    val decrypted = BackupCryptoEngine.decryptToZipBytes(buffered, chars)
                                    decryptedZipBytes = decrypted
                                    createSafetyRollbackSnapshot(safetyFile, safetyTempFile)
                                    java.io.ByteArrayInputStream(decrypted).use { zipIn ->
                                        restoreBackupFromStream(zipIn) {
                                            dbWriteCommitted = true
                                        }
                                    }
                                }
                                is BackupInspectionResult.UnsupportedEncryptedVersion -> {
                                    throw IllegalArgumentException(
                                        "Unsupported encrypted backup version v${inspection.version}. Please update Folio."
                                    )
                                }
                                BackupInspectionResult.InvalidFormat -> {
                                    throw IllegalArgumentException("Invalid or unrecognized backup file format.")
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        if (dbWriteCommitted && safetyFile.exists() && safetyFile.length() > 0L) {
                            runCatchingNonCancellation {
                                safetyFile.inputStream().use { restoreBackupFromStream(it) }
                            }
                        }
                        throw e
                    } finally {
                        decryptedZipBytes?.fill(0)
                        safetyFile.delete()
                        safetyTempFile.delete()
                    }
                    Unit
                }
            } finally {
                password?.fill('\u0000')
            }
        }
    }

    private suspend fun createSafetyRollbackSnapshot(safetyFile: File, safetyTempFile: File) {
        safetyTempFile.delete()
        FileOutputStream(safetyTempFile).use { output ->
            createBackupToStream(output)
        }
        if (!safetyTempFile.renameTo(safetyFile)) {
            safetyTempFile.copyTo(safetyFile, overwrite = true)
            safetyTempFile.delete()
        }
    }

    suspend fun clearAllData(): Result<Unit> = withContext(Dispatchers.IO) {
        backupMutex.withLock {
            runCatchingNonCancellation {
                val currentPrefs = userPreferences.observeUserPreferences().first()
                require(!currentPrefs.isDemoModeActive) {
                    "Exit Demo Sandbox before clearing all local data."
                }
                database.withTransaction {
                    val txDao = database.transactionDao()
                    val catTagDao = database.categoryAndTagDao()
                    val budgetRecurringDao = database.budgetAndRecurringDao()
                    val accountDao = database.accountDao()

                    // Clear child tables first to respect foreign keys
                    txDao.deleteAllTagCrossRefs()
                    txDao.deleteAllSplits()
                    txDao.deleteAllTransactions()
                    txDao.deleteAllInstallmentPlans()
                    budgetRecurringDao.deleteAllRecurringRules()
                    budgetRecurringDao.deleteAllBudgets()
                    catTagDao.deleteAllBookmarks()
                    catTagDao.deleteAllTags()
                    catTagDao.deleteAllCategories()
                    accountDao.deleteAllAccounts()
                    DefaultCategorySeeder.seedIfEmpty(catTagDao)
                }
                SafBackupMirrorScheduler.cancelAll(context)
                StorageAccessUtils.releasePersistableTreePermission(context, currentPrefs.safMirrorTreeUri)
                AutoMirrorKeyVault(context).clear()
                File(File(context.filesDir, "demo_stash"), "pre_demo_real_ledger.zip").delete()
                File(File(context.noBackupFilesDir, "demo_stash"), "pre_demo_real_ledger.zip").delete()
                File(context.filesDir, "FolioAutoBackups").deleteRecursively()
                File(context.noBackupFilesDir, "FolioAutoBackups").deleteRecursively()
                listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
                    .distinct()
                    .forEach { root ->
                        File(root, "FolioAutoBackups").deleteRecursively()
                        File(root, "FolioBackups").deleteRecursively()
                        File(root, "FolioStatements").deleteRecursively()
                    }
                File(context.cacheDir, "shared_exports").deleteRecursively()
                runCatching { StorageAccessUtils.deleteDownloadsMirrorIfExists(context) }

                context.folioUserPreferencesDataStore.edit { mutablePrefs ->
                    mutablePrefs.clear()
                }
                com.folioledger.app.ui.settings.sheets.FolioLocaleManager.clearSavedLanguage(context)
                SessionChangeTracker.resetDirty()

                runCatchingNonCancellation {
                    androidx.core.app.NotificationManagerCompat.from(context).cancelAll()
                    // Keep daily recurring rule auto-posting and widget maintenance active with default 09:00 schedule
                    com.folioledger.app.data.notification.DueRemindersScheduler.rescheduleDailyReminders(context)
                    com.folioledger.app.data.notification.DailyLogReminderScheduler.cancelAll(context)
                    com.folioledger.app.ui.widget.FolioWidgetUpdater.refreshAllNow(context)
                }
                Unit
            }
        }
    }

    suspend fun restoreBackupFromStream(
        inputStream: InputStream,
        clearDemoStash: Boolean = true,
        onDbWriteStarted: (() -> Unit)? = null
    ): Unit = withContext(Dispatchers.IO) {
        val root = try {
            val jsonPayload = readBackupJsonFromZip(inputStream)
            JSONObject(jsonPayload)
        } catch (oom: OutOfMemoryError) {
            throw IllegalArgumentException("Backup file is too large to process on this device.", oom)
        } catch (so: StackOverflowError) {
            throw IllegalArgumentException("Invalid or deeply nested backup JSON payload.", so)
        }

        if (!root.has("backupFormatVersion") || root.isNull("backupFormatVersion")) {
            throw IllegalArgumentException("Invalid backup archive: not a Folio ledger backup.")
        }
        val formatVersion = root.optInt("backupFormatVersion", -1)
        if (formatVersion > BACKUP_SCHEMA_VERSION) {
            throw IllegalArgumentException(
                "This backup requires a newer version of Folio (format v$formatVersion). Please update Folio to restore this backup."
            )
        }
        if (formatVersion < 1 ||
            root.optJSONArray("accounts") == null ||
            root.optJSONArray("categories") == null ||
            root.optJSONArray("transactions") == null
        ) {
            throw IllegalArgumentException("Invalid backup archive: missing required Folio ledger tables.")
        }

        val accounts = parseAccounts(root.optJSONArray("accounts"))
        val categories = parseCategories(root.optJSONArray("categories"))
        val tags = parseTags(root.optJSONArray("tags"))
        val bookmarks = parseBookmarks(root.optJSONArray("bookmarks"))
        val installmentPlans = parseInstallmentPlans(root.optJSONArray("installmentPlans"))
        val recurringRules = parseRecurringRules(root.optJSONArray("recurringRules"))
        val transactions = parseTransactions(root.optJSONArray("transactions"))
        val splits = parseSplits(root.optJSONArray("transactionSplits"))
        val tagCrossRefs = parseTagCrossRefs(root.optJSONArray("transactionTags"))
        val budgets = parseBudgets(root.optJSONArray("budgets"))

        // Foreign-key sanitization before database transaction
        val sanitizedAccounts = BackupSanitizer.sanitizeAccounts(accounts)
        val accountIds = sanitizedAccounts.map { it.id }.toSet()
        val sanitizedCategories = BackupSanitizer.sanitizeCategories(categories)
        val categoryIds = sanitizedCategories.map { it.id }.toSet()
        val validBookmarks = BackupSanitizer.sanitizeBookmarks(bookmarks, accountIds, categoryIds)
        val sanitizedTagResult = BackupSanitizer.sanitizeTags(tags)
        val validTags = sanitizedTagResult.tags
        val validTagIds = validTags.map { it.id }.toSet()
        val sanitizedPlans = BackupSanitizer.sanitizeInstallmentPlans(installmentPlans, accountIds, categoryIds)
        val planIds = sanitizedPlans.map { it.id }.toSet()
        val sanitizedRules = BackupSanitizer.sanitizeRecurringRules(recurringRules, accountIds, categoryIds)
        val ruleIds = sanitizedRules.map { it.id }.toSet()
        val liabilityAccountIds = sanitizedAccounts.filter { it.groupType.isLiabilityByDefault }.map { it.id }.toSet()
        val preSplitTransactions = BackupSanitizer.sanitizeTransactions(
            transactions = transactions,
            accountIds = accountIds,
            categoryIds = categoryIds,
            planIds = planIds,
            ruleIds = ruleIds,
            liabilityAccountIds = liabilityAccountIds
        )
        val preSplitTxIds = preSplitTransactions.map { it.id }.toSet()
        val validSplits = BackupSanitizer.sanitizeSplits(splits, preSplitTxIds, categoryIds)
        val validTransactions = BackupSanitizer.reconcileTransactionsAfterSplits(preSplitTransactions, validSplits)
        val txIds = validTransactions.map { it.id }.toSet()
        val validCrossRefs = tagCrossRefs.mapNotNull { crossRef ->
            val mappedTagId = sanitizedTagResult.tagIdRemap[crossRef.tagId] ?: crossRef.tagId
            if (crossRef.transactionId in txIds && mappedTagId in validTagIds) {
                crossRef.copy(tagId = mappedTagId)
            } else null
        }.distinct()
        val validBudgets = BackupSanitizer.sanitizeBudgets(budgets, categoryIds)

        database.withTransaction {
            val txDao = database.transactionDao()
            val catTagDao = database.categoryAndTagDao()
            val budgetRecurringDao = database.budgetAndRecurringDao()
            val accountDao = database.accountDao()

            // Clear child tables first to respect foreign keys
            txDao.deleteAllTagCrossRefs()
            txDao.deleteAllSplits()
            txDao.deleteAllTransactions()
            txDao.deleteAllInstallmentPlans()
            budgetRecurringDao.deleteAllRecurringRules()
            budgetRecurringDao.deleteAllBudgets()
            catTagDao.deleteAllBookmarks()
            catTagDao.deleteAllTags()
            catTagDao.deleteAllCategories()
            accountDao.deleteAllAccounts()

            // Insert parent tables before child tables
            val parentAccounts = sanitizedAccounts.filter { it.parentAccountId == null }
            val childAccounts = sanitizedAccounts.filter { it.parentAccountId != null }
            accountDao.insertAccounts(parentAccounts)
            accountDao.insertAccounts(childAccounts)

            if (sanitizedCategories.isEmpty()) {
                DefaultCategorySeeder.seedIfEmpty(catTagDao)
            } else {
                val mainCategories = sanitizedCategories.filter { it.parentId == null }
                val subCategories = sanitizedCategories.filter { it.parentId != null }
                catTagDao.insertCategories(mainCategories)
                catTagDao.insertCategories(subCategories)
            }

            catTagDao.insertTags(validTags)
            catTagDao.insertBookmarks(validBookmarks)
            txDao.insertInstallmentPlansRaw(sanitizedPlans)
            budgetRecurringDao.insertRecurringRules(sanitizedRules)
            txDao.insertTransactionsRaw(validTransactions)
            txDao.insertSplits(validSplits)
            txDao.insertTagCrossRefs(validCrossRefs)
            budgetRecurringDao.insertBudgets(validBudgets)
        }
        onDbWriteStarted?.invoke()

        val prefsUpdateResult = root.optJSONObject("userPreferences")?.let { prefsObj ->
            userPreferences.updateUserPreferences { current ->
                deserializeUserPreferences(prefsObj, current)
            }
        } ?: userPreferences.updateUserPreferences { current ->
            current.copy(
                hasSeenWelcomeSheet = true,
                isDemoModeActive = false,
                hasStashedRealLedger = false
            )
        }
        prefsUpdateResult.getOrThrow()

        if (clearDemoStash) {
            File(File(context.filesDir, "demo_stash"), "pre_demo_real_ledger.zip").delete()
            File(File(context.noBackupFilesDir, "demo_stash"), "pre_demo_real_ledger.zip").delete()
        }
        database.checkpointDatabase()
        runCatchingNonCancellation {
            androidx.core.app.NotificationManagerCompat.from(context).cancelAll()
            val restoredPrefs = userPreferences.observeUserPreferences().first()
            // Always keep DueRemindersScheduler active (even when remindersEnabled == false) so daily
            // recurring rule auto-posting and midnight widget refreshes continue reliably.
            com.folioledger.app.data.notification.DueRemindersScheduler.rescheduleDailyReminders(
                context = context,
                hourOfDay = if (restoredPrefs.remindersEnabled) restoredPrefs.reminderHourOfDay else 9,
                minute = if (restoredPrefs.remindersEnabled) restoredPrefs.reminderMinute else 0
            )
            com.folioledger.app.data.notification.DailyLogReminderScheduler.syncWithPreferences(
                context = context,
                prefs = restoredPrefs
            )
            SafBackupMirrorScheduler.syncPeriodicSchedule(
                context = context,
                prefs = restoredPrefs
            )
            com.folioledger.app.ui.widget.FolioWidgetUpdater.refreshAllNow(context)
        }
        Unit
    }


    private fun JSONObject.putNullableString(key: String, value: String?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private fun JSONObject.putNullableInt(key: String, value: Int?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (isNull(key)) null else optInt(key)

    internal fun parseAccounts(array: JSONArray?): List<AccountEntity> {
        if (array == null) return emptyList()
        return List(array.length()) { i ->
            val obj = array.getJSONObject(i)
            AccountEntity(
                id = obj.getLong("id"),
                name = obj.getString("name"),
                groupType = parseBackupEnum<AccountGroup>(obj.getString("groupType"), "account group"),
                currencyCode = obj.optString("currencyCode", "INR"),
                openingBalanceMinor = obj.optLong("openingBalanceMinor", 0L),
                initialUnbilledMinor = obj.optNullableLong("initialUnbilledMinor"),
                creditLimitMinor = obj.optNullableLong("creditLimitMinor"),
                settlementDayOfMonth = obj.optNullableInt("settlementDayOfMonth"),
                paymentDueDayOfMonth = obj.optNullableInt("paymentDueDayOfMonth"),
                parentAccountId = obj.optNullableLong("parentAccountId"),
                includeInNetWorth = obj.optBoolean("includeInNetWorth", true),
                isArchived = obj.optBoolean("isArchived", false),
                colorHex = obj.optString("colorHex", "#10B981"),
                iconName = obj.optString("iconName", "account_balance_wallet"),
                sortOrder = obj.optInt("sortOrder", 0),
                isPinned = obj.optBoolean("isPinned", false),
                createdAtEpochMillis = obj.optLong("createdAtEpochMillis", System.currentTimeMillis())
            )
        }
    }

    internal fun parseCategories(array: JSONArray?): List<CategoryEntity> {
        if (array == null) return emptyList()
        return List(array.length()) { i ->
            val obj = array.getJSONObject(i)
            CategoryEntity(
                id = obj.getLong("id"),
                name = obj.getString("name"),
                type = parseBackupEnum<CategoryType>(obj.getString("type"), "category type"),
                parentId = obj.optNullableLong("parentId"),
                iconName = obj.optString("iconName", "category"),
                colorHex = obj.optString("colorHex", "#10B981"),
                sortOrder = obj.optInt("sortOrder", 0),
                isArchived = obj.optBoolean("isArchived", false)
            )
        }
    }

    internal fun parseTags(array: JSONArray?): List<TagEntity> {
        if (array == null) return emptyList()
        return List(array.length()) { i ->
            val obj = array.getJSONObject(i)
            TagEntity(
                id = obj.getLong("id"),
                name = obj.getString("name"),
                colorHex = obj.optString("colorHex", "#64748B")
            )
        }
    }

    internal fun parseBookmarks(array: JSONArray?): List<BookmarkEntity> {
        if (array == null) return emptyList()
        return List(array.length()) { i ->
            val obj = array.getJSONObject(i)
            BookmarkEntity(
                id = obj.getLong("id"),
                title = obj.getString("title"),
                type = parseBackupEnum<TransactionType>(obj.getString("type"), "bookmark transaction type"),
                amountMinor = obj.getLong("amountMinor"),
                accountId = obj.getLong("accountId"),
                transferToAccountId = obj.optNullableLong("transferToAccountId"),
                categoryId = obj.optNullableLong("categoryId"),
                payeeOrDescription = obj.optString("payeeOrDescription", ""),
                note = obj.optString("note", ""),
                tagsCsv = obj.optString("tagsCsv", ""),
                sortOrder = obj.optInt("sortOrder", 0)
            )
        }
    }

    internal fun parseInstallmentPlans(array: JSONArray?): List<InstallmentPlanEntity> {
        if (array == null) return emptyList()
        return List(array.length()) { i ->
            val obj = array.getJSONObject(i)
            InstallmentPlanEntity(
                id = obj.getLong("id"),
                title = obj.getString("title"),
                accountId = obj.getLong("accountId"),
                categoryId = obj.optNullableLong("categoryId"),
                totalAmountMinor = obj.getLong("totalAmountMinor"),
                monthsCount = obj.getInt("monthsCount"),
                startDateEpochDay = obj.getLong("startDateEpochDay"),
                createdAtEpochMillis = obj.optLong("createdAtEpochMillis", System.currentTimeMillis())
            )
        }
    }

    internal fun parseRecurringRules(array: JSONArray?): List<RecurringRuleEntity> {
        if (array == null) return emptyList()
        return List(array.length()) { i ->
            val obj = array.getJSONObject(i)
            RecurringRuleEntity(
                id = obj.getLong("id"),
                title = obj.getString("title"),
                type = parseBackupEnum<TransactionType>(obj.getString("type"), "recurring rule type"),
                amountMinor = obj.getLong("amountMinor"),
                currencyCode = obj.optString("currencyCode", "INR"),
                accountId = obj.getLong("accountId"),
                transferToAccountId = obj.optNullableLong("transferToAccountId"),
                categoryId = obj.optNullableLong("categoryId"),
                frequency = when (val raw = obj.getString("frequency")) {
                    "DAILY" -> RepeatFrequency.EVERY_DAY
                    "WEEKLY" -> RepeatFrequency.EVERY_WEEK
                    "BIWEEKLY" -> RepeatFrequency.EVERY_2_WEEKS
                    "MONTHLY" -> RepeatFrequency.EVERY_MONTH
                    "YEARLY" -> RepeatFrequency.ANNUALLY
                    else -> parseBackupEnum<RepeatFrequency>(raw, "repeat frequency")
                },
                startDateEpochDay = obj.getLong("startDateEpochDay"),
                nextOccurrenceEpochDay = obj.getLong("nextOccurrenceEpochDay"),
                endDateEpochDay = obj.optNullableLong("endDateEpochDay"),
                payeeOrDescription = obj.optString("payeeOrDescription", ""),
                note = obj.optString("note", ""),
                isActive = obj.optBoolean("isActive", true)
            )
        }
    }

    internal fun parseTransactions(array: JSONArray?): List<TransactionEntity> {
        if (array == null) return emptyList()
        return List(array.length()) { i ->
            val obj = array.getJSONObject(i)
            TransactionEntity(
                id = obj.getLong("id"),
                type = parseBackupEnum<TransactionType>(obj.getString("type"), "transaction type"),
                timestampEpochMillis = obj.getLong("timestampEpochMillis"),
                ledgerDateEpochDay = obj.getLong("ledgerDateEpochDay"),
                amountMinor = obj.getLong("amountMinor"),
                currencyCode = obj.optString("currencyCode", "INR"),
                accountId = obj.getLong("accountId"),
                transferToAccountId = obj.optNullableLong("transferToAccountId"),
                transferFeeMinor = obj.optLong("transferFeeMinor", 0L),
                categoryId = obj.optNullableLong("categoryId"),
                isSplit = obj.optBoolean("isSplit", false),
                payeeOrDescription = obj.optString("payeeOrDescription", ""),
                note = obj.optString("note", ""),
                originalTransactionId = obj.optNullableLong("originalTransactionId"),
                installmentPlanId = obj.optNullableLong("installmentPlanId"),
                installmentIndex = obj.optNullableInt("installmentIndex"),
                installmentTotalCount = obj.optNullableInt("installmentTotalCount"),
                recurringRuleId = obj.optNullableLong("recurringRuleId")
            )
        }
    }

    internal fun parseTagCrossRefs(array: JSONArray?): List<TransactionTagCrossRef> {
        if (array == null) return emptyList()
        return List(array.length()) { i ->
            val obj = array.getJSONObject(i)
            TransactionTagCrossRef(
                transactionId = obj.getLong("transactionId"),
                tagId = obj.getLong("tagId")
            )
        }
    }
}

object BackupSanitizer {
    fun sanitizeAccounts(accounts: List<AccountEntity>): List<AccountEntity> {
        val validAccounts = accounts.filter { it.id > 0L }.distinctBy { it.id }
        val rootAccountById = validAccounts.filter { it.parentAccountId == null }.associateBy { it.id }
        val allowedDebitParents = setOf(
            AccountGroup.BANK_ACCOUNT,
            AccountGroup.SAVINGS,
            AccountGroup.OVERDRAFT
        )
        return validAccounts.map { acc ->
            val validCurrency = SupportedCurrencies.getByCode(acc.currencyCode)?.code ?: "INR"
            val parent = acc.parentAccountId?.let { rootAccountById[it] }
            val parentValidCurrency = parent?.let { SupportedCurrencies.getByCode(it.currencyCode)?.code ?: "INR" }
            val safeParentId = if (
                acc.groupType == AccountGroup.DEBIT_CARD &&
                parent != null &&
                parent.id != acc.id &&
                parent.groupType in allowedDebitParents &&
                parentValidCurrency.equals(validCurrency, ignoreCase = true)
            ) {
                parent.id
            } else {
                null
            }
            acc.copy(
                currencyCode = validCurrency,
                settlementDayOfMonth = acc.settlementDayOfMonth?.coerceIn(1, 31),
                paymentDueDayOfMonth = acc.paymentDueDayOfMonth?.coerceIn(1, 31),
                parentAccountId = safeParentId
            )
        }
    }

    fun sanitizeCategories(categories: List<CategoryEntity>): List<CategoryEntity> {
        val validCategories = categories.filter { it.id > 0L }.distinctBy { it.id }
        val rootCategoryById = validCategories.filter { it.parentId == null }.associateBy { it.id }
        return validCategories.map { cat ->
            val parent = cat.parentId?.let { rootCategoryById[it] }
            val safeParentId = if (parent != null && parent.id != cat.id && parent.type == cat.type) {
                parent.id
            } else {
                null
            }
            cat.copy(parentId = safeParentId)
        }
    }

    fun sanitizeBookmarks(
        bookmarks: List<BookmarkEntity>,
        accountIds: Set<Long>,
        categoryIds: Set<Long>
    ): List<BookmarkEntity> {
        return bookmarks.filter { it.id > 0L && it.accountId in accountIds }.distinctBy { it.id }.map { bm ->
            val safeTransferTo = bm.transferToAccountId?.takeIf { it in accountIds && it != bm.accountId }
            val safeType = if (bm.type == TransactionType.TRANSFER && safeTransferTo == null) {
                TransactionType.EXPENSE
            } else {
                bm.type
            }
            bm.copy(
                type = safeType,
                amountMinor = maxOf(0L, bm.amountMinor),
                transferToAccountId = if (safeType == TransactionType.TRANSFER || safeType == TransactionType.CC_SETTLEMENT) {
                    safeTransferTo
                } else {
                    null
                },
                categoryId = bm.categoryId?.takeIf { it in categoryIds }
            )
        }
    }

    fun sanitizeInstallmentPlans(
        installmentPlans: List<InstallmentPlanEntity>,
        accountIds: Set<Long>,
        categoryIds: Set<Long>
    ): List<InstallmentPlanEntity> {
        return installmentPlans
            .filter { it.id > 0L && it.accountId in accountIds && it.monthsCount >= 1 && it.totalAmountMinor > 0L }
            .distinctBy { it.id }
            .map { pl ->
                pl.copy(categoryId = pl.categoryId?.takeIf { it in categoryIds })
            }
    }

    fun sanitizeRecurringRules(
        recurringRules: List<RecurringRuleEntity>,
        accountIds: Set<Long>,
        categoryIds: Set<Long>
    ): List<RecurringRuleEntity> {
        return recurringRules
            .filter { it.id > 0L && it.accountId in accountIds && it.amountMinor > 0L }
            .distinctBy { it.id }
            .map { rl ->
                val safeTransferTo = rl.transferToAccountId?.takeIf { it in accountIds && it != rl.accountId }
                val safeType = if (rl.type == TransactionType.TRANSFER && safeTransferTo == null) {
                    TransactionType.EXPENSE
                } else {
                    rl.type
                }
                rl.copy(
                    type = safeType,
                    transferToAccountId = if (safeType == TransactionType.TRANSFER || safeType == TransactionType.CC_SETTLEMENT) {
                        safeTransferTo
                    } else {
                        null
                    },
                    categoryId = rl.categoryId?.takeIf { it in categoryIds }
                )
            }
    }

    fun sanitizeTransactions(
        transactions: List<TransactionEntity>,
        accountIds: Set<Long>,
        categoryIds: Set<Long>,
        planIds: Set<Long>,
        ruleIds: Set<Long>,
        liabilityAccountIds: Set<Long> = emptySet()
    ): List<TransactionEntity> {
        return transactions
            .filter { it.id > 0L && it.accountId in accountIds }
            .distinctBy { it.id }
            .map { tx ->
                val isLiabilityAccount = tx.accountId in liabilityAccountIds
                val validDistinctTarget = tx.transferToAccountId?.takeIf { it in accountIds && it != tx.accountId }
                val (safeType, safeTransferTo) = when (tx.type) {
                    TransactionType.TRANSFER -> {
                        if (validDistinctTarget != null) {
                            TransactionType.TRANSFER to validDistinctTarget
                        } else {
                            TransactionType.EXPENSE to null
                        }
                    }
                    TransactionType.CC_SETTLEMENT -> {
                        // External/legacy card payments validly have transferToAccountId == null
                        TransactionType.CC_SETTLEMENT to validDistinctTarget
                    }
                    TransactionType.OPENING_BALANCE -> {
                        // Outflow/debt-increase direction is encoded via transferToAccountId == accountId.
                        // For liability accounts (LOAN, CREDIT_CARD, OVERDRAFT), opening balance represents
                        // starting debt (outflow) by default, healing backups where transferToAccountId was null.
                        val selfOutflowMarker = tx.transferToAccountId?.takeIf { it == tx.accountId }
                            ?: if (isLiabilityAccount) tx.accountId else null
                        tx.type to selfOutflowMarker
                    }
                    TransactionType.RECONCILIATION -> {
                        // Outflow/debt-increase direction is encoded via transferToAccountId == accountId
                        val selfOutflowMarker = tx.transferToAccountId?.takeIf { it == tx.accountId }
                        tx.type to selfOutflowMarker
                    }
                    else -> tx.type to null
                }

                tx.copy(
                    type = safeType,
                    amountMinor = maxOf(0L, tx.amountMinor),
                    transferFeeMinor = maxOf(0L, tx.transferFeeMinor),
                    transferToAccountId = safeTransferTo,
                    categoryId = tx.categoryId?.takeIf { it in categoryIds },
                    installmentPlanId = tx.installmentPlanId?.takeIf { it in planIds },
                    recurringRuleId = tx.recurringRuleId?.takeIf { it in ruleIds }
                )
            }
    }

    fun sanitizeSplits(
        splits: List<TransactionSplitEntity>,
        txIds: Set<Long>,
        categoryIds: Set<Long>
    ): List<TransactionSplitEntity> {
        return splits
            .filter { it.id > 0L && it.transactionId in txIds && it.amountMinor > 0L }
            .distinctBy { it.id }
            .map { sp ->
                sp.copy(categoryId = sp.categoryId?.takeIf { it in categoryIds })
            }
    }

    fun reconcileTransactionsAfterSplits(
        transactions: List<TransactionEntity>,
        validSplits: List<TransactionSplitEntity>
    ): List<TransactionEntity> {
        val txIds = transactions.map { it.id }.toSet()
        val splitTxIds = validSplits.map { it.transactionId }.toSet()
        return transactions.map { tx ->
            tx.copy(
                isSplit = tx.isSplit && tx.id in splitTxIds,
                originalTransactionId = tx.originalTransactionId?.takeIf { it in txIds && it != tx.id }
            )
        }
    }

    fun sanitizeBudgets(
        budgets: List<BudgetEntity>,
        categoryIds: Set<Long>
    ): List<BudgetEntity> {
        val seenRecurringKeys = mutableSetOf<Pair<Long?, com.folioledger.app.domain.model.BudgetPeriodType>>()
        val seenCustomIds = mutableSetOf<Long>()
        val result = mutableListOf<BudgetEntity>()

        for (budget in budgets.filter { it.id > 0L }.distinctBy { it.id }) {
            if (budget.categoryId != null && budget.categoryId !in categoryIds) continue
            if (budget.limitAmountMinor <= 0L) continue

            if (budget.periodType == com.folioledger.app.domain.model.BudgetPeriodType.CUSTOM) {
                val start = budget.startDateEpochDay ?: continue
                val end = budget.endDateEpochDay ?: start
                if (!seenCustomIds.add(budget.id)) continue
                result.add(
                    budget.copy(
                        startDateEpochDay = minOf(start, end),
                        endDateEpochDay = maxOf(start, end),
                        rolloverEnabled = false,
                        rolloverIncludeDeficit = false
                    )
                )
            } else {
                val key = budget.categoryId to budget.periodType
                if (seenRecurringKeys.add(key)) {
                    result.add(budget.copy(endDateEpochDay = null))
                }
            }
        }
        return result
    }

    data class SanitizedTagsResult(
        val tags: List<TagEntity>,
        val tagIdRemap: Map<Long, Long>
    )

    fun sanitizeTags(tags: List<TagEntity>): SanitizedTagsResult {
        val uniqueTags = mutableListOf<TagEntity>()
        val seenNames = mutableMapOf<String, Long>()
        val remap = mutableMapOf<Long, Long>()

        for (tag in tags.filter { it.id > 0L }.distinctBy { it.id }) {
            val key = tag.name.trim().lowercase(Locale.ROOT)
            if (key.isEmpty()) continue
            val existingId = seenNames[key]
            if (existingId != null) {
                remap[tag.id] = existingId
            } else {
                seenNames[key] = tag.id
                remap[tag.id] = tag.id
                uniqueTags.add(tag.copy(name = tag.name.trim()))
            }
        }
        return SanitizedTagsResult(uniqueTags, remap)
    }
}
