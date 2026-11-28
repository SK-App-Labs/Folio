/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.folioledger.app.data.notification.FolioNotificationManager
import com.folioledger.app.di.FolioViewModelFactory
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.util.CurrencyDisplayPolicy
import com.folioledger.app.ui.ledger.LedgerHomeViewModel
import com.folioledger.app.ui.settings.sheets.FolioLocaleManager
import com.folioledger.app.ui.shell.FolioMainShell
import com.folioledger.app.ui.theme.FolioTheme
import com.folioledger.app.ui.theme.FolioThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

sealed interface FolioNotificationAction : java.io.Serializable {
    data class SettleCard(val accountId: Long) : FolioNotificationAction {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
    data class LogBill(val recurringRuleId: Long, val expectedDueEpochDay: Long? = null) : FolioNotificationAction {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
    data object LogDailyTransaction : FolioNotificationAction {
        private const val serialVersionUID = 1L
        private fun readResolve(): Any = LogDailyTransaction
    }
}

class MainLockViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
    val isSessionUnlocked = mutableStateOf(false)
    val hasUnlockedOnce = mutableStateOf(false)
    var lastBackgroundElapsedRealtimeMs: Long = 0L
    var hasAutoPromptedForCurrentLock: Boolean = false
    var isBiometricPromptInFlight: Boolean = false

    val pendingShortcutAction: MutableState<TransactionType?> = savedState(
        key = KEY_SHORTCUT_ACTION,
        initial = savedStateHandle.get<String>(KEY_SHORTCUT_ACTION)?.let { name ->
            runCatching { TransactionType.valueOf(name) }.getOrNull()
        },
        onSave = { type ->
            if (type != null) {
                savedStateHandle[KEY_SHORTCUT_ACTION] = type.name
            } else {
                savedStateHandle.remove<String>(KEY_SHORTCUT_ACTION)
            }
        },
    )

    val pendingNotificationAction: MutableState<FolioNotificationAction?> = savedState(
        key = KEY_NOTIFICATION_ACTION,
        initial = savedStateHandle.get<FolioNotificationAction>(KEY_NOTIFICATION_ACTION),
        onSave = { action ->
            if (action != null) {
                savedStateHandle[KEY_NOTIFICATION_ACTION] = action
            } else {
                savedStateHandle.remove<FolioNotificationAction>(KEY_NOTIFICATION_ACTION)
            }
        },
    )

    val pendingBookmarkId: MutableState<Long?> = savedState(
        key = KEY_BOOKMARK_ID,
        initial = savedStateHandle.get<Long>(KEY_BOOKMARK_ID),
        onSave = { id ->
            if (id != null) {
                savedStateHandle[KEY_BOOKMARK_ID] = id
            } else {
                savedStateHandle.remove<Long>(KEY_BOOKMARK_ID)
            }
        },
    )

    companion object {
        private const val KEY_SHORTCUT_ACTION = "pending_shortcut_action"
        private const val KEY_NOTIFICATION_ACTION = "pending_notification_action"
        private const val KEY_BOOKMARK_ID = "pending_bookmark_id"

        private fun <T> savedState(
            key: String,
            initial: T?,
            onSave: (T?) -> Unit,
        ): MutableState<T?> {
            val state = mutableStateOf(initial)
            return object : MutableState<T?> {
                override var value: T?
                    get() = state.value
                    set(v) {
                        state.value = v
                        onSave(v)
                    }
                override fun component1(): T? = state.value
                override fun component2(): (T?) -> Unit = { value = it }
            }
        }
    }
}

class MainActivity : FragmentActivity() {

    private val lockViewModel: MainLockViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(FolioLocaleManager.wrapContextIfNeeded(newBase))
    }

    override fun onStart() {
        super.onStart()
        val lastBg = lockViewModel.lastBackgroundElapsedRealtimeMs
        if (lastBg > 0L) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - lastBg
            // 15s grace period: if the app was backgrounded for more than 15s, lock session.
            // Quick backgroundings (such as system SAF file pickers, permission prompts, or quick switches) stay unlocked.
            if (elapsed > 15_000L) {
                lockViewModel.isSessionUnlocked.value = false
                lockViewModel.hasAutoPromptedForCurrentLock = false
            }
            // Consume the background timestamp so subsequent config changes do not re-trigger lock
            lockViewModel.lastBackgroundElapsedRealtimeMs = 0L
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            lockViewModel.lastBackgroundElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime()
            // Do NOT wipe pendingShortcutAction or pendingNotificationAction here;
            // FolioMainShell consumes and clears them only after unlock completes.
            val app = application as FolioApplication
            app.applicationScope.launch(Dispatchers.IO) {
                try {
                    app.container.settingsAndBackupRepository.triggerSessionAutoBackupIfDirty()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    android.util.Log.e("MainActivity", "Session auto-backup failed on stop", e)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIncomingIntent(intent)
    }

    private fun consumeIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val flags = runCatching { intent.flags }.getOrDefault(0)
        if ((flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return

        val notification = runCatching { extractNotificationAction(intent) }.getOrNull()
        val bookmarkId = if (notification == null) runCatching { extractBookmarkId(intent) }.getOrNull() else null
        val shortcut = if (notification == null && bookmarkId == null) {
            runCatching { extractShortcutAction(intent) }.getOrNull()
        } else {
            null
        }

        if (notification != null || bookmarkId != null || shortcut != null) {
            // Atomically replace all 3 pending targets so stale lock-screen deep links cannot race on unlock
            lockViewModel.pendingNotificationAction.value = notification
            lockViewModel.pendingBookmarkId.value = bookmarkId
            lockViewModel.pendingShortcutAction.value = shortcut
        }

        runCatching {
            intent.removeExtra("shortcut_action")
            intent.removeExtra("widget_bookmark_id")
            intent.removeExtra(FolioNotificationManager.EXTRA_NOTIFICATION_ACTION)
            intent.removeExtra(FolioNotificationManager.EXTRA_ACCOUNT_ID)
            intent.removeExtra(FolioNotificationManager.EXTRA_RECURRING_RULE_ID)
            intent.removeExtra(FolioNotificationManager.EXTRA_DUE_EPOCH_DAY)
        }
    }

    private fun extractNotificationAction(intent: Intent?): FolioNotificationAction? {
        if (intent == null) return null
        val action = intent.getStringExtra(FolioNotificationManager.EXTRA_NOTIFICATION_ACTION) ?: return null
        return when (action) {
            FolioNotificationManager.ACTION_SETTLE_CARD -> {
                val accountId = intent.getLongExtra(FolioNotificationManager.EXTRA_ACCOUNT_ID, -1L)
                if (accountId > 0L) FolioNotificationAction.SettleCard(accountId) else null
            }

            FolioNotificationManager.ACTION_LOG_BILL -> {
                val ruleId = intent.getLongExtra(FolioNotificationManager.EXTRA_RECURRING_RULE_ID, -1L)
                val expectedDueEpochDay = intent.getLongExtra(FolioNotificationManager.EXTRA_DUE_EPOCH_DAY, -1L)
                if (ruleId > 0L) FolioNotificationAction.LogBill(ruleId, expectedDueEpochDay.takeIf { it > 0L }) else null
            }

            FolioNotificationManager.ACTION_LOG_DAILY_TRANSACTION -> FolioNotificationAction.LogDailyTransaction

            else -> null
        }
    }

    private fun extractBookmarkId(intent: Intent?): Long? {
        val id = intent?.getLongExtra("widget_bookmark_id", -1L) ?: return null
        return if (id > 0L) id else null
    }

    private fun extractShortcutAction(intent: Intent?): TransactionType? {
        val action = intent?.getStringExtra("shortcut_action") ?: return null
        return when (action.uppercase(Locale.US)) {
            "EXPENSE" -> TransactionType.EXPENSE
            "INCOME" -> TransactionType.INCOME
            "TRANSFER" -> TransactionType.TRANSFER
            else -> null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            consumeIncomingIntent(intent)
        }

        // BiometricPrompt is Activity-scoped; clear stale in-flight flag after Activity recreation
        lockViewModel.isBiometricPromptInFlight = false

        val app = application as FolioApplication
        val appContainer = app.container
        val viewModelFactory = FolioViewModelFactory(appContainer)

        if (!appContainer.isDatabaseWarmedUp.value) {
            lifecycleScope.launch {
                // Warm up Room SQLite and trigger pre-migration backup on IO dispatcher
                appContainer.warmUpDatabase()
            }
        }

        var keepSplashScreen = true
        splashScreen.setKeepOnScreenCondition { keepSplashScreen }

        var lastResolvedThemeMode = "SYSTEM"

        splashScreen.setOnExitAnimationListener { splashViewProvider ->
            val splashView = splashViewProvider.view
            val iconView = runCatching { splashViewProvider.iconView }.getOrNull()

            val animators = buildList {
                add(ObjectAnimator.ofFloat(splashView, View.ALPHA, 1f, 0f))
                if (iconView != null) {
                    add(ObjectAnimator.ofFloat(iconView, View.SCALE_X, 1f, 1.05f))
                    add(ObjectAnimator.ofFloat(iconView, View.SCALE_Y, 1f, 1.05f))
                    add(ObjectAnimator.ofFloat(iconView, View.ALPHA, 1f, 0f))
                }
            }

            AnimatorSet().apply {
                playTogether(animators)
                duration = 240L
                interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        splashViewProvider.remove()
                        val systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                        val isDarkActive = when (FolioThemeMode.fromKey(lastResolvedThemeMode)) {
                            FolioThemeMode.LIGHT,
                            FolioThemeMode.SEPIA,
                            -> false

                            FolioThemeMode.DARK,
                            FolioThemeMode.AMOLED_BLACK,
                            FolioThemeMode.SLATE_GRAY,
                            -> true

                            FolioThemeMode.SYSTEM -> systemDark
                        }
                        WindowCompat.getInsetsController(window, window.decorView).apply {
                            isAppearanceLightStatusBars = !isDarkActive
                            isAppearanceLightNavigationBars = !isDarkActive
                        }
                    }
                })
                start()
            }
        }

        setContent {
            val prefs by appContainer.userPreferencesDataSource
                .observeUserPreferences()
                .collectAsStateWithLifecycle(initialValue = null)
            val isDatabaseReady by appContainer.isDatabaseWarmedUp.collectAsStateWithLifecycle()

            var isSessionUnlocked by lockViewModel.isSessionUnlocked
            val hasUnlockedOnce by lockViewModel.hasUnlockedOnce
            var authErrorText by rememberSaveable { mutableStateOf<String?>(null) }

            val activePrefs = prefs
            LaunchedEffect(activePrefs?.biometricLockEnabled) {
                if (activePrefs?.biometricLockEnabled == false) {
                    lockViewModel.isSessionUnlocked.value = true
                    lockViewModel.hasUnlockedOnce.value = true
                    lockViewModel.hasAutoPromptedForCurrentLock = false
                    lockViewModel.isBiometricPromptInFlight = false
                    authErrorText = null
                }
            }
            SideEffect {
                activePrefs?.let { lastResolvedThemeMode = it.themeMode }
            }

            LaunchedEffect(activePrefs?.biometricLockEnabled, activePrefs?.hideAmountsOnLockScreen) {
                if (activePrefs != null) {
                    val secure = activePrefs.biometricLockEnabled || activePrefs.hideAmountsOnLockScreen
                    if (secure) {
                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }

            val themeMode = activePrefs?.themeMode ?: "SYSTEM"
            val accentPalette = activePrefs?.accentPalette ?: "EMERALD"
            val dynamicColorEnabled = activePrefs?.dynamicColorEnabled ?: false
            val iconPackTheme = activePrefs?.iconPackTheme ?: "MATERIAL_FILLED"
            val currencyDisplayPolicy = remember(
                activePrefs?.primaryCurrencyCode,
                activePrefs?.hidePrimaryCurrencySymbol,
            ) {
                CurrencyDisplayPolicy(
                    primaryCurrencyCode = activePrefs?.primaryCurrencyCode ?: "INR",
                    hidePrimaryCurrencySymbol = activePrefs?.hidePrimaryCurrencySymbol ?: false,
                )
            }

            FolioTheme(
                themeMode = themeMode,
                accentPalette = accentPalette,
                dynamicColorEnabled = dynamicColorEnabled,
                iconPackTheme = iconPackTheme,
                currencyDisplayPolicy = currencyDisplayPolicy,
            ) {
                if (activePrefs == null) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {}
                } else {
                    val isLocked = activePrefs.biometricLockEnabled && !isSessionUnlocked
                    val canComposeShell = isDatabaseReady && (hasUnlockedOnce || !isLocked)

                    if (isLocked && !hasUnlockedOnce) {
                        // Dismiss splash right into LockedLedgerScreen on cold start without touching Room on the main thread
                        LaunchedEffect(Unit) {
                            keepSplashScreen = false
                        }
                    }

                    val pendingShortcut by lockViewModel.pendingShortcutAction
                    val pendingNotification by lockViewModel.pendingNotificationAction
                    val pendingBookmark by lockViewModel.pendingBookmarkId
                    val lifecycleOwner = LocalLifecycleOwner.current

                    fun attemptUnlock(isManualRetry: Boolean = false) {
                        if (lockViewModel.isSessionUnlocked.value) return
                        if (!isManualRetry && lockViewModel.isBiometricPromptInFlight) return
                        lockViewModel.isBiometricPromptInFlight = true
                        triggerBiometricOrCredentialPrompt(
                            onSuccess = {
                                lockViewModel.isBiometricPromptInFlight = false
                                lockViewModel.hasUnlockedOnce.value = true
                                isSessionUnlocked = true
                                lockViewModel.lastBackgroundElapsedRealtimeMs = 0L
                                authErrorText = null
                            },
                            onNoDeviceLock = {
                                lockViewModel.isBiometricPromptInFlight = false
                                lockViewModel.hasUnlockedOnce.value = true
                                isSessionUnlocked = true
                                lockViewModel.lastBackgroundElapsedRealtimeMs = 0L
                                authErrorText = null
                                app.applicationScope.launch(Dispatchers.IO) {
                                    appContainer.settingsAndBackupRepository.updateUserPreferences { current ->
                                        current.copy(biometricLockEnabled = false)
                                    }
                                }
                                Toast.makeText(
                                    this@MainActivity,
                                    "Device screen lock unavailable; Folio App Lock turned off.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            },
                            onError = { msg ->
                                lockViewModel.isBiometricPromptInFlight = false
                                authErrorText = msg
                            },
                        )
                    }

                    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                        if (canComposeShell) {
                            val ledgerViewModel: LedgerHomeViewModel = viewModel(factory = viewModelFactory)
                            val isInitialDataLoaded by remember(ledgerViewModel) {
                                ledgerViewModel.uiState
                                    .map { it.isInitialDataLoaded }
                                    .distinctUntilChanged()
                            }.collectAsStateWithLifecycle(initialValue = false)

                            LaunchedEffect(isInitialDataLoaded) {
                                if (isInitialDataLoaded) {
                                    keepSplashScreen = false
                                }
                            }

                            androidx.compose.foundation.layout.Box(
                                modifier = if (isLocked) {
                                    @OptIn(ExperimentalComposeUiApi::class)
                                    Modifier
                                        .fillMaxSize()
                                        .clearAndSetSemantics { hideFromAccessibility() }
                                } else {
                                    Modifier.fillMaxSize()
                                },
                            ) {
                                // Keep FolioMainShell alive in composition after first unlock so tab/dialog state survives lock & SAF pickers
                                FolioMainShell(
                                    viewModelFactory = viewModelFactory,
                                    isLocked = isLocked,
                                    initialShortcutAction = if (!isLocked) pendingShortcut else null,
                                    onShortcutActionConsumed = {
                                        lockViewModel.pendingShortcutAction.value = null
                                    },
                                    initialNotificationAction = if (!isLocked) pendingNotification else null,
                                    onNotificationActionConsumed = {
                                        lockViewModel.pendingNotificationAction.value = null
                                    },
                                    initialBookmarkId = if (!isLocked) pendingBookmark else null,
                                    onBookmarkActionConsumed = {
                                        lockViewModel.pendingBookmarkId.value = null
                                    },
                                )
                            }

                            if (isLocked) {
                                // Solid privacy curtain blocking Activity-window pixels & pointer input behind the Dialog overlay
                                Surface(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(Unit) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    val event = awaitPointerEvent()
                                                    event.changes.forEach { it.consume() }
                                                }
                                            }
                                        },
                                    color = MaterialTheme.colorScheme.background,
                                ) {}
                            }
                        } else {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.background,
                            ) {}
                        }

                        if (isLocked) {
                            LaunchedEffect(lifecycleOwner) {
                                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                                    if (!isSessionUnlocked &&
                                        !lockViewModel.hasAutoPromptedForCurrentLock &&
                                        !lockViewModel.isBiometricPromptInFlight
                                    ) {
                                        lockViewModel.hasAutoPromptedForCurrentLock = true
                                        attemptUnlock(isManualRetry = false)
                                    }
                                }
                            }

                            LockedLedgerScreen(
                                errorMessage = authErrorText,
                                onUnlockClick = { attemptUnlock(isManualRetry = true) },
                                onExitApp = {
                                    moveTaskToBack(true)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            (application as FolioApplication).container.releaseBillingIfInitialized()
        }
        super.onDestroy()
    }

    private fun triggerBiometricOrCredentialPrompt(
        onSuccess: () -> Unit,
        onNoDeviceLock: () -> Unit,
        onError: (String) -> Unit,
    ) {
        com.folioledger.app.ui.settings.launchAppLockPrompt(
            activity = this,
            title = "Unlock Folio",
            subtitle = "Verify your identity to access your financial ledger",
            onSuccess = onSuccess,
            onNoDeviceLock = onNoDeviceLock,
            onError = onError,
        )
    }
}

@Composable
private fun LockedLedgerScreen(
    errorMessage: String?,
    onUnlockClick: () -> Unit,
    onExitApp: () -> Unit = {},
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onExitApp,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn,
        ),
    ) {
        LockedLedgerContent(
            errorMessage = errorMessage,
            onUnlockClick = onUnlockClick,
        )
    }
}

@Composable
private fun LockedLedgerContent(
    errorMessage: String?,
    onUnlockClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Locked",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Folio Ledger is Locked",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage ?: "Use your fingerprint, face, or device PIN to continue.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(28.dp))
            Button(onClick = onUnlockClick) {
                Text(text = "Unlock Folio")
            }
        }
    }
}
