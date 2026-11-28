/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.widget

import android.content.Context
import android.os.Build
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.folioledger.app.FolioApplication
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.domain.util.DueRemindersEvaluator
import com.folioledger.app.domain.util.QuickAddWidgetSnapshot
import com.folioledger.app.domain.util.SafeToSpendCalculator
import com.folioledger.app.domain.util.SafeToSpendWidgetSnapshot
import com.folioledger.app.domain.util.WidgetSnapshotBuilder
import com.folioledger.app.domain.util.WidgetStatusTone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal data class WidgetPreviewThemeSnapshot(
    val themeMode: String,
    val accentPalette: String,
    val dynamicColorEnabled: Boolean,
    val privacyEnforced: Boolean,
)

object FolioWidgetUpdater {

    private const val PREVIEW_PREFS_NAME = "folio_widget_preview_meta"
    private const val KEY_LAST_PREVIEW_SIGNATURE = "last_preview_signature"
    private const val KEY_LAST_PREVIEW_EPOCH_MS = "last_preview_epoch_ms"
    private const val PREVIEW_MIN_INTERVAL_MS = 35L * 60L * 1000L

    private var debounceJob: Job? = null
    private var previewDebounceJob: Job? = null
    private val debounceLock = Any()
    private val updateMutex = Mutex()
    private val previewSyncMutex = Mutex()

    internal suspend fun readThemeSnapshotForPreview(context: Context): WidgetPreviewThemeSnapshot {
        val app = context.applicationContext as? FolioApplication
        val prefs = try {
            app?.container?.settingsAndBackupRepository?.observeUserPreferences()?.first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return WidgetPreviewThemeSnapshot(
            themeMode = prefs?.themeMode ?: "SYSTEM",
            accentPalette = prefs?.accentPalette ?: "EMERALD",
            dynamicColorEnabled = prefs?.dynamicColorEnabled ?: false,
            privacyEnforced = (prefs?.biometricLockEnabled ?: false) || (prefs?.hideAmountsOnLockScreen ?: false),
        )
    }

    fun scheduleDebouncedPreviewSync(
        context: Context,
        scope: CoroutineScope,
        delayMs: Long = 1_500L,
    ) {
        synchronized(debounceLock) {
            previewDebounceJob?.cancel()
            previewDebounceJob = scope.launch(Dispatchers.IO) {
                delay(delayMs)
                syncGeneratedPreviewsIfNeeded(context.applicationContext)
            }
        }
    }

    suspend fun syncGeneratedPreviewsIfNeeded(
        context: Context,
        forceIfThemeChanged: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return@withContext
        previewSyncMutex.withLock {
            val appContext = context.applicationContext
            val snapshot = readThemeSnapshotForPreview(appContext)
            val signature = "${snapshot.themeMode}|${snapshot.accentPalette}|${snapshot.dynamicColorEnabled}|${snapshot.privacyEnforced}"
            val meta = appContext.getSharedPreferences(PREVIEW_PREFS_NAME, Context.MODE_PRIVATE)
            val lastSig = meta.getString(KEY_LAST_PREVIEW_SIGNATURE, null)
            val lastTime = meta.getLong(KEY_LAST_PREVIEW_EPOCH_MS, 0L)
            val now = System.currentTimeMillis()
            val isSameSignature = lastSig == signature
            val elapsed = now - lastTime
            val withinCooldown = lastTime > 0L && elapsed in 0L until PREVIEW_MIN_INTERVAL_MS
            val wasPrivacyEnforced = lastSig?.endsWith("|true") == true
            val isPrivacyEscalation = snapshot.privacyEnforced && !wasPrivacyEnforced
            if (isSameSignature && lastTime > 0L) return@withLock
            if (withinCooldown && !(forceIfThemeChanged && isPrivacyEscalation)) return@withLock

            try {
                val manager = GlanceAppWidgetManager(appContext)
                val safeResult = manager.setWidgetPreviews(SafeToSpendWidgetReceiver::class)
                val quickResult = manager.setWidgetPreviews(QuickAddWidgetReceiver::class)
                if (safeResult == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS &&
                    quickResult == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS
                ) {
                    meta.edit()
                        .putString(KEY_LAST_PREVIEW_SIGNATURE, signature)
                        .putLong(KEY_LAST_PREVIEW_EPOCH_MS, now)
                        .apply()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("FolioWidgetUpdater", "Failed to sync generated widget previews", e)
            }
        }
    }

    fun scheduleDebouncedRefresh(
        context: Context,
        scope: CoroutineScope,
        delayMs: Long = 300L,
    ) {
        synchronized(debounceLock) {
            debounceJob?.cancel()
            debounceJob = scope.launch(Dispatchers.IO) {
                delay(delayMs)
                refreshAllInternal(context.applicationContext)
            }
        }
    }

    suspend fun refreshAllNow(context: Context) = withContext(Dispatchers.IO) {
        refreshAllInternal(context.applicationContext)
    }

    suspend fun populateStateIfMissing(
        context: Context,
        targetGlanceId: GlanceId,
    ) = withContext(Dispatchers.IO) {
        updateMutex.withLock {
            val app = context.applicationContext as? FolioApplication ?: return@withContext
            val manager = GlanceAppWidgetManager(context.applicationContext)

            val safeIds = try {
                manager.getGlanceIds(SafeToSpendGlanceWidget::class.java)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }

            val quickIds = try {
                manager.getGlanceIds(QuickAddGlanceWidget::class.java)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }

            val container = app.container
            val prefs = try {
                container.settingsAndBackupRepository.observeUserPreferences().first()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withContext
            }

            val privacyEnforced = prefs.biometricLockEnabled || prefs.hideAmountsOnLockScreen
            val today = LocalDate.now(container.clock)
            val todayEpochDay = today.toEpochDay()

            if (targetGlanceId in safeIds) {
                val cur = getAppWidgetState(context.applicationContext, PreferencesGlanceStateDefinition, targetGlanceId)
                if (cur[FolioWidgetStateKeys.KEY_SAFE_AMOUNT] == null ||
                    cur[FolioWidgetStateKeys.KEY_THEME_MODE] == null ||
                    cur[FolioWidgetStateKeys.KEY_IS_PRIVACY_LOCKED] == null
                ) {
                    val safePair = computeSafeToSpendSnapshots(app, prefs, today, todayEpochDay)
                    if (safePair != null) {
                        writeSafeToSpendState(
                            context = context.applicationContext,
                            glanceId = targetGlanceId,
                            prefs = prefs,
                            privacyEnforced = privacyEnforced,
                            snapshot = safePair.first,
                            snapshotMasked = safePair.second,
                        )
                    }
                }
            } else if (targetGlanceId in quickIds) {
                val cur = getAppWidgetState(context.applicationContext, PreferencesGlanceStateDefinition, targetGlanceId)
                if (cur[FolioWidgetStateKeys.KEY_BOOKMARKS_JSON] == null ||
                    cur[FolioWidgetStateKeys.KEY_THEME_MODE] == null ||
                    cur[FolioWidgetStateKeys.KEY_IS_PRIVACY_LOCKED] == null
                ) {
                    val quickSnapshot = computeQuickAddSnapshot(app, prefs, todayEpochDay)
                    if (quickSnapshot != null) {
                        val bookmarksJson = serializeBookmarksJson(quickSnapshot, privacyEnforced)
                        writeQuickAddState(
                            context = context.applicationContext,
                            glanceId = targetGlanceId,
                            prefs = prefs,
                            privacyEnforced = privacyEnforced,
                            isDemoMode = quickSnapshot.isDemoMode,
                            bookmarksJson = bookmarksJson,
                        )
                    }
                }
            }
        }
    }

    suspend fun togglePrivacyMask(
        context: Context,
        clickedGlanceId: GlanceId,
    ) = withContext(Dispatchers.IO) {
        updateMutex.withLock {
            val app = context.applicationContext as? FolioApplication ?: return@withContext
            val prefs = try {
                app.container.settingsAndBackupRepository.observeUserPreferences().first()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withContext
            }

            // Refuse launcher-side unmasking whenever Biometric App Lock or Lock-Screen Privacy is active
            if (prefs.biometricLockEnabled || prefs.hideAmountsOnLockScreen) return@withContext

            val manager = GlanceAppWidgetManager(context.applicationContext)
            val safeIds = try {
                manager.getGlanceIds(SafeToSpendGlanceWidget::class.java)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (safeIds.isEmpty()) return@withContext

            val clickedState = getAppWidgetState(context.applicationContext, PreferencesGlanceStateDefinition, clickedGlanceId)
            val nextMasked = !(clickedState[FolioWidgetStateKeys.KEY_IS_MASKED] ?: false)
            val safeWidget = SafeToSpendGlanceWidget()

            for (id in safeIds) {
                updateAppWidgetState(context.applicationContext, PreferencesGlanceStateDefinition, id) { cur ->
                    cur.toMutablePreferences().apply {
                        this[FolioWidgetStateKeys.KEY_IS_MASKED] = nextMasked
                    }
                }
                safeWidget.update(context.applicationContext, id)
            }
        }
    }

    private suspend fun refreshAllInternal(context: Context) = updateMutex.withLock {
        val app = context.applicationContext as? FolioApplication ?: return@withLock
        val manager = GlanceAppWidgetManager(context)

        val safeWidget = SafeToSpendGlanceWidget()
        val quickWidget = QuickAddGlanceWidget()

        val safeGlanceIds = try {
            manager.getGlanceIds(SafeToSpendGlanceWidget::class.java)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

        val quickGlanceIds = try {
            manager.getGlanceIds(QuickAddGlanceWidget::class.java)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

        // Fast-path: If user has placed neither widget on home screen, avoid all DB / calculation work!
        if (safeGlanceIds.isEmpty() && quickGlanceIds.isEmpty()) {
            return@withLock
        }

        val prefs = try {
            app.container.settingsAndBackupRepository.observeUserPreferences().first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withLock
        }

        val today = LocalDate.now(app.container.clock)
        val todayEpochDay = today.toEpochDay()
        val privacyEnforced = prefs.biometricLockEnabled || prefs.hideAmountsOnLockScreen

        // 1. Update Safe-to-Spend Widget Instances
        if (safeGlanceIds.isNotEmpty()) {
            val safePair = computeSafeToSpendSnapshots(app, prefs, today, todayEpochDay)
            if (safePair != null) {
                for (glanceId in safeGlanceIds) {
                    writeSafeToSpendState(
                        context = context,
                        glanceId = glanceId,
                        prefs = prefs,
                        privacyEnforced = privacyEnforced,
                        snapshot = safePair.first,
                        snapshotMasked = safePair.second,
                    )
                    safeWidget.update(context, glanceId)
                }
            }
        }

        // 2. Update Quick-Add Widget Instances
        if (quickGlanceIds.isNotEmpty()) {
            val quickSnapshot = computeQuickAddSnapshot(app, prefs, todayEpochDay)
            if (quickSnapshot != null) {
                val bookmarksJson = serializeBookmarksJson(quickSnapshot, privacyEnforced)
                for (glanceId in quickGlanceIds) {
                    writeQuickAddState(
                        context = context,
                        glanceId = glanceId,
                        prefs = prefs,
                        privacyEnforced = privacyEnforced,
                        isDemoMode = quickSnapshot.isDemoMode,
                        bookmarksJson = bookmarksJson,
                    )
                    quickWidget.update(context, glanceId)
                }
            }
        }
    }

    private suspend fun computeSafeToSpendSnapshots(
        app: FolioApplication,
        prefs: UserPreferences,
        today: LocalDate,
        todayEpochDay: Long,
    ): Pair<SafeToSpendWidgetSnapshot, SafeToSpendWidgetSnapshot>? = try {
        val container = app.container
        val primarySpec = SupportedCurrencies.fromCode(prefs.primaryCurrencyCode)
        val grouping = prefs.numberGrouping
        val allAccounts = container.accountRepository.getActiveAccountsSnapshot(todayEpochDay)
        val recurringRules = container.analyticsAndBudgetRepository.observeActiveRecurringCommitments().first()
        val futureTxs = container.transactionRepository.observeTransactionsInRange(
            startEpochDay = todayEpochDay + 1L,
            endEpochDay = todayEpochDay + 14L,
        ).first()

        val safeToSpendState = SafeToSpendCalculator.computeSafeToSpend(
            allAccounts = allAccounts,
            recurringRules = recurringRules,
            futureTransactions = futureTxs,
            todayEpochDay = todayEpochDay,
            periodStartEpochDay = today.withDayOfMonth(1).toEpochDay(),
            periodEndEpochDay = today.withDayOfMonth(today.lengthOfMonth()).toEpochDay(),
            primaryCurrencyCode = prefs.primaryCurrencyCode,
            secondaryCurrencies = prefs.secondaryCurrencies,
        )

        val cardReminders = DueRemindersEvaluator.evaluateCreditCardReminders(
            accounts = allAccounts,
            todayEpochDay = todayEpochDay,
            leadDaysNotice = prefs.ccReminderLeadDays,
        )
        val nextCardDue = cardReminders.firstOrNull()

        val snapshot = WidgetSnapshotBuilder.buildSafeToSpendSnapshot(
            safeToSpendState = safeToSpendState,
            currencySpec = primarySpec,
            grouping = grouping,
            nextCardDue = nextCardDue,
            isMasked = false,
            isDemoMode = prefs.isDemoModeActive,
            hidePrimaryCurrencySymbol = prefs.hidePrimaryCurrencySymbol,
        )

        val snapshotMasked = WidgetSnapshotBuilder.buildSafeToSpendSnapshot(
            safeToSpendState = safeToSpendState,
            currencySpec = primarySpec,
            grouping = grouping,
            nextCardDue = nextCardDue,
            isMasked = true,
            isDemoMode = prefs.isDemoModeActive,
            hidePrimaryCurrencySymbol = prefs.hidePrimaryCurrencySymbol,
        )

        snapshot to snapshotMasked
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.e("FolioWidgetUpdater", "Failed to compute SafeToSpend snapshot", e)
        null
    }

    private suspend fun writeSafeToSpendState(
        context: Context,
        glanceId: GlanceId,
        prefs: UserPreferences,
        privacyEnforced: Boolean,
        snapshot: SafeToSpendWidgetSnapshot,
        snapshotMasked: SafeToSpendWidgetSnapshot,
    ) {
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { curPrefs ->
            val isMaskedUserOverride = if (privacyEnforced) {
                true
            } else {
                curPrefs[FolioWidgetStateKeys.KEY_IS_MASKED] ?: false
            }
            val persisted = if (privacyEnforced) snapshotMasked else snapshot
            curPrefs.toMutablePreferences().apply {
                this[FolioWidgetStateKeys.KEY_SAFE_AMOUNT] = persisted.formattedSafeToSpend
                this[FolioWidgetStateKeys.KEY_STATUS_LABEL] = if (privacyEnforced) "Safe to Spend" else persisted.statusLabel
                this[FolioWidgetStateKeys.KEY_STATUS_TONE] = if (privacyEnforced) WidgetStatusTone.NEUTRAL.name else persisted.statusTone.name
                this[FolioWidgetStateKeys.KEY_SECONDARY_SUMMARY] = persisted.secondarySummary
                if (!privacyEnforced && snapshot.nextCardDueSummary != null) {
                    this[FolioWidgetStateKeys.KEY_NEXT_CARD_DUE] = snapshot.nextCardDueSummary
                } else {
                    remove(FolioWidgetStateKeys.KEY_NEXT_CARD_DUE)
                }
                if (!privacyEnforced && snapshotMasked.nextCardDueSummary != null) {
                    this[FolioWidgetStateKeys.KEY_NEXT_CARD_DUE_MASKED] = snapshotMasked.nextCardDueSummary
                } else {
                    remove(FolioWidgetStateKeys.KEY_NEXT_CARD_DUE_MASKED)
                }
                this[FolioWidgetStateKeys.KEY_IS_DEMO_MODE] = persisted.isDemoMode
                this[FolioWidgetStateKeys.KEY_IS_MASKED] = isMaskedUserOverride
                this[FolioWidgetStateKeys.KEY_IS_PRIVACY_LOCKED] = privacyEnforced
                this[FolioWidgetStateKeys.KEY_THEME_MODE] = prefs.themeMode
                this[FolioWidgetStateKeys.KEY_ACCENT_PALETTE] = prefs.accentPalette
                this[FolioWidgetStateKeys.KEY_DYNAMIC_COLOR] = prefs.dynamicColorEnabled
            }
        }
    }

    private suspend fun computeQuickAddSnapshot(
        app: FolioApplication,
        prefs: UserPreferences,
        todayEpochDay: Long,
    ): QuickAddWidgetSnapshot? = try {
        val container = app.container
        val primarySpec = SupportedCurrencies.fromCode(prefs.primaryCurrencyCode)
        val bookmarks = container.categoryAndTagRepository.observeBookmarks().first()
        val accounts = container.accountRepository.observeActiveAccounts(todayEpochDay).first().associateBy { it.id }

        WidgetSnapshotBuilder.buildQuickAddSnapshot(
            bookmarks = bookmarks,
            accounts = accounts,
            primaryCurrencySpec = primarySpec,
            grouping = prefs.numberGrouping,
            isDemoMode = prefs.isDemoModeActive,
            hidePrimaryCurrencySymbol = prefs.hidePrimaryCurrencySymbol,
            limit = 3,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.e("FolioWidgetUpdater", "Failed to compute QuickAdd snapshot", e)
        null
    }

    internal fun serializeBookmarksJson(
        snapshot: QuickAddWidgetSnapshot,
        privacyEnforced: Boolean,
    ): String {
        if (privacyEnforced) return "[]"
        val jsonArray = JSONArray()
        snapshot.bookmarks.forEach { bm ->
            val obj = JSONObject()
            obj.put("id", bm.id)
            obj.put("title", bm.title.trim().take(40))
            if (bm.formattedAmount != null) {
                obj.put("amount", bm.formattedAmount)
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    private suspend fun writeQuickAddState(
        context: Context,
        glanceId: GlanceId,
        prefs: UserPreferences,
        privacyEnforced: Boolean,
        isDemoMode: Boolean,
        bookmarksJson: String,
    ) {
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { curPrefs ->
            curPrefs.toMutablePreferences().apply {
                this[FolioWidgetStateKeys.KEY_BOOKMARKS_JSON] = bookmarksJson
                this[FolioWidgetStateKeys.KEY_IS_DEMO_MODE] = isDemoMode
                this[FolioWidgetStateKeys.KEY_IS_MASKED] = privacyEnforced
                this[FolioWidgetStateKeys.KEY_IS_PRIVACY_LOCKED] = privacyEnforced
                this[FolioWidgetStateKeys.KEY_THEME_MODE] = prefs.themeMode
                this[FolioWidgetStateKeys.KEY_ACCENT_PALETTE] = prefs.accentPalette
                this[FolioWidgetStateKeys.KEY_DYNAMIC_COLOR] = prefs.dynamicColorEnabled
            }
        }
    }
}
