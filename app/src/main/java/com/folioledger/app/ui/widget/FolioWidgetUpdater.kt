package com.folioledger.app.ui.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.folioledger.app.FolioApplication
import com.folioledger.app.domain.model.CategoryNode
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.util.DueRemindersEvaluator
import com.folioledger.app.domain.util.SafeToSpendCalculator
import com.folioledger.app.domain.util.WidgetSnapshotBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

object FolioWidgetUpdater {

    private var debounceJob: Job? = null
    private val debounceLock = Any()

    fun scheduleDebouncedRefresh(
        context: Context,
        scope: CoroutineScope,
        delayMs: Long = 300L
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

    private suspend fun refreshAllInternal(context: Context) {
        val app = context.applicationContext as? FolioApplication ?: return
        val manager = GlanceAppWidgetManager(context)

        val safeWidget = SafeToSpendGlanceWidget()
        val quickWidget = QuickAddGlanceWidget()

        val safeGlanceIds = try {
            manager.getGlanceIds(SafeToSpendGlanceWidget::class.java)
        } catch (e: Exception) {
            emptyList()
        }

        val quickGlanceIds = try {
            manager.getGlanceIds(QuickAddGlanceWidget::class.java)
        } catch (e: Exception) {
            emptyList()
        }

        // Fast-path: If user has placed neither widget on home screen, avoid all DB / calculation work!
        if (safeGlanceIds.isEmpty() && quickGlanceIds.isEmpty()) {
            return
        }

        val container = app.container
        val prefs = try {
            container.settingsAndBackupRepository.observeUserPreferences().first()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return
        }

        val today = LocalDate.now(container.clock)
        val todayEpochDay = today.toEpochDay()
        val primarySpec = SupportedCurrencies.fromCode(prefs.primaryCurrencyCode)
        val grouping = prefs.numberGrouping

        // 1. Update Safe-to-Spend Widget Instances
        if (safeGlanceIds.isNotEmpty()) {
            try {
                val allAccounts = container.accountRepository.observeActiveAccounts(todayEpochDay).first()
                val recurringRules = container.analyticsAndBudgetRepository.observeActiveRecurringCommitments().first()
                val futureTxs = container.transactionRepository.observeTransactionsInRange(
                    startEpochDay = todayEpochDay + 1L,
                    endEpochDay = todayEpochDay + 14L
                ).first()

                val safeToSpendState = SafeToSpendCalculator.computeSafeToSpend(
                    allAccounts = allAccounts,
                    recurringRules = recurringRules,
                    futureTransactions = futureTxs,
                    todayEpochDay = todayEpochDay,
                    periodStartEpochDay = today.withDayOfMonth(1).toEpochDay(),
                    periodEndEpochDay = today.withDayOfMonth(today.lengthOfMonth()).toEpochDay(),
                    primaryCurrencyCode = prefs.primaryCurrencyCode,
                    secondaryCurrencies = prefs.secondaryCurrencies
                )

                val cardReminders = DueRemindersEvaluator.evaluateCreditCardReminders(
                    accounts = allAccounts,
                    todayEpochDay = todayEpochDay,
                    leadDaysNotice = prefs.ccReminderLeadDays
                )
                val nextCardDue = cardReminders.firstOrNull()

                val defaultMasked = prefs.biometricLockEnabled && prefs.hideAmountsOnLockScreen

                val snapshot = WidgetSnapshotBuilder.buildSafeToSpendSnapshot(
                    safeToSpendState = safeToSpendState,
                    currencySpec = primarySpec,
                    grouping = grouping,
                    nextCardDue = nextCardDue,
                    isMasked = false,
                    isDemoMode = prefs.isDemoModeActive
                )

                val snapshotMasked = WidgetSnapshotBuilder.buildSafeToSpendSnapshot(
                    safeToSpendState = safeToSpendState,
                    currencySpec = primarySpec,
                    grouping = grouping,
                    nextCardDue = nextCardDue,
                    isMasked = true,
                    isDemoMode = prefs.isDemoModeActive
                )

                for (glanceId in safeGlanceIds) {
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { curPrefs ->
                        val isMaskedUserOverride = curPrefs[FolioWidgetStateKeys.KEY_IS_MASKED] ?: defaultMasked
                        curPrefs.toMutablePreferences().apply {
                            this[FolioWidgetStateKeys.KEY_SAFE_AMOUNT] = snapshot.formattedSafeToSpend
                            this[FolioWidgetStateKeys.KEY_RAW_MINOR] = snapshot.rawAmountMinor
                            this[FolioWidgetStateKeys.KEY_STATUS_LABEL] = snapshot.statusLabel
                            this[FolioWidgetStateKeys.KEY_STATUS_TONE] = snapshot.statusTone.name
                            this[FolioWidgetStateKeys.KEY_SECONDARY_SUMMARY] = snapshot.secondarySummary
                            if (snapshot.nextCardDueSummary != null) {
                                this[FolioWidgetStateKeys.KEY_NEXT_CARD_DUE] = snapshot.nextCardDueSummary
                            } else {
                                remove(FolioWidgetStateKeys.KEY_NEXT_CARD_DUE)
                            }
                            if (snapshotMasked.nextCardDueSummary != null) {
                                this[FolioWidgetStateKeys.KEY_NEXT_CARD_DUE_MASKED] = snapshotMasked.nextCardDueSummary
                            } else {
                                remove(FolioWidgetStateKeys.KEY_NEXT_CARD_DUE_MASKED)
                            }
                            this[FolioWidgetStateKeys.KEY_IS_DEMO_MODE] = snapshot.isDemoMode
                            this[FolioWidgetStateKeys.KEY_IS_MASKED] = isMaskedUserOverride
                            this[FolioWidgetStateKeys.KEY_THEME_MODE] = prefs.themeMode
                            this[FolioWidgetStateKeys.KEY_ACCENT_PALETTE] = prefs.accentPalette
                            this[FolioWidgetStateKeys.KEY_DYNAMIC_COLOR] = prefs.dynamicColorEnabled
                        }
                    }
                    safeWidget.update(context, glanceId)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.e("FolioWidgetUpdater", "Failed to update SafeToSpend widgets", e)
            }
        }

        // 2. Update Quick-Add Widget Instances
        if (quickGlanceIds.isNotEmpty()) {
            try {
                val bookmarks = container.categoryAndTagRepository.observeBookmarks().first()
                val accounts = container.accountRepository.observeActiveAccounts(todayEpochDay).first().associateBy { it.id }
                val categories = flattenCategories(container.categoryAndTagRepository.observeAllCategoryTrees().first())

                val snapshot = WidgetSnapshotBuilder.buildQuickAddSnapshot(
                    bookmarks = bookmarks,
                    accounts = accounts,
                    categories = categories,
                    primaryCurrencySpec = primarySpec,
                    grouping = grouping,
                    isDemoMode = prefs.isDemoModeActive,
                    limit = 3
                )

                val jsonArray = JSONArray()
                snapshot.bookmarks.forEach { bm ->
                    val obj = JSONObject()
                    obj.put("id", bm.id)
                    obj.put("title", bm.title)
                    if (bm.formattedAmount != null) {
                        obj.put("amount", bm.formattedAmount)
                    }
                    jsonArray.put(obj)
                }
                val bookmarksJson = jsonArray.toString()

                for (glanceId in quickGlanceIds) {
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { curPrefs ->
                        curPrefs.toMutablePreferences().apply {
                            this[FolioWidgetStateKeys.KEY_BOOKMARKS_JSON] = bookmarksJson
                            this[FolioWidgetStateKeys.KEY_IS_DEMO_MODE] = snapshot.isDemoMode
                            this[FolioWidgetStateKeys.KEY_THEME_MODE] = prefs.themeMode
                            this[FolioWidgetStateKeys.KEY_ACCENT_PALETTE] = prefs.accentPalette
                            this[FolioWidgetStateKeys.KEY_DYNAMIC_COLOR] = prefs.dynamicColorEnabled
                        }
                    }
                    quickWidget.update(context, glanceId)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.e("FolioWidgetUpdater", "Failed to update QuickAdd widgets", e)
            }
        }
    }

    private fun flattenCategories(trees: List<CategoryNode>): Map<Long, CategoryNode> {
        val map = mutableMapOf<Long, CategoryNode>()
        fun traverse(node: CategoryNode) {
            map[node.id] = node
            node.subcategories.forEach(::traverse)
        }
        trees.forEach(::traverse)
        return map
    }
}
