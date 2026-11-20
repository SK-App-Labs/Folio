package com.folioledger.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.PathInterpolator
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.folioledger.app.data.notification.FolioNotificationManager
import com.folioledger.app.domain.model.TransactionType

import com.folioledger.app.ui.settings.sheets.FolioLocaleManager
import com.folioledger.app.ui.theme.FolioThemeMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.MutableState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.folioledger.app.di.FolioViewModelFactory
import com.folioledger.app.ui.ledger.LedgerHomeViewModel
import com.folioledger.app.ui.shell.FolioMainShell
import com.folioledger.app.ui.theme.FolioTheme
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

sealed interface FolioNotificationAction : java.io.Serializable {
    data class SettleCard(val accountId: Long) : FolioNotificationAction
    data class LogBill(val recurringRuleId: Long) : FolioNotificationAction
}

class MainLockViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
    val isSessionUnlocked = mutableStateOf(false)
    var lastBackgroundTimestampEpochMillis: Long = 0L

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
        }
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
        }
    )

    companion object {
        private const val KEY_SHORTCUT_ACTION = "pending_shortcut_action"
        private const val KEY_NOTIFICATION_ACTION = "pending_notification_action"

        private fun <T> savedState(
            key: String,
            initial: T?,
            onSave: (T?) -> Unit
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
        val elapsed = android.os.SystemClock.elapsedRealtime() - lockViewModel.lastBackgroundTimestampEpochMillis
        // 15s grace period: if the app was backgrounded for more than 15s, lock session.
        // Quick backgroundings (such as system SAF file pickers, permission prompts, or quick switches) stay unlocked.
        if (lockViewModel.lastBackgroundTimestampEpochMillis > 0L && elapsed > 15_000L) {
            lockViewModel.isSessionUnlocked.value = false
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            lockViewModel.lastBackgroundTimestampEpochMillis = android.os.SystemClock.elapsedRealtime()
            // Do NOT wipe pendingShortcutAction or pendingNotificationAction here;
            // FolioMainShell consumes and clears them only after unlock completes.
            val app = application as FolioApplication
            app.applicationScope.launch(Dispatchers.IO) {
                try {
                    app.container.settingsAndBackupRepository.triggerSessionAutoBackupIfDirty()
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Session auto-backup failed on stop", e)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractShortcutAction(intent)?.let { action ->
            lockViewModel.pendingShortcutAction.value = action
            intent.removeExtra("shortcut_action")
        }
        extractNotificationAction(intent)?.let { action ->
            lockViewModel.pendingNotificationAction.value = action
            intent.removeExtra(FolioNotificationManager.EXTRA_NOTIFICATION_ACTION)
        }
    }

    private fun extractNotificationAction(intent: Intent?): FolioNotificationAction? {
        if (intent == null) return null
        val action = intent.getStringExtra(FolioNotificationManager.EXTRA_NOTIFICATION_ACTION) ?: return null
        return when (action) {
            FolioNotificationManager.ACTION_SETTLE_CARD -> {
                val accountId = intent.getLongExtra(FolioNotificationManager.EXTRA_ACCOUNT_ID, -1L)
                if (accountId != -1L) FolioNotificationAction.SettleCard(accountId) else null
            }
            FolioNotificationManager.ACTION_LOG_BILL -> {
                val ruleId = intent.getLongExtra(FolioNotificationManager.EXTRA_RECURRING_RULE_ID, -1L)
                if (ruleId != -1L) FolioNotificationAction.LogBill(ruleId) else null
            }
            else -> null
        }
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
            extractShortcutAction(intent)?.let { action ->
                lockViewModel.pendingShortcutAction.value = action
                intent?.removeExtra("shortcut_action")
            }
            extractNotificationAction(intent)?.let { action ->
                lockViewModel.pendingNotificationAction.value = action
                intent?.removeExtra(FolioNotificationManager.EXTRA_NOTIFICATION_ACTION)
            }
        }

        val appContainer = (application as FolioApplication).container
        val viewModelFactory = FolioViewModelFactory(appContainer)

        lifecycleScope.launch(Dispatchers.IO) {
            // Warm up Room SQLite and trigger pre-migration backup on IO dispatcher
            appContainer.database.openHelper.writableDatabase
        }

        var keepSplashScreen = true
        splashScreen.setKeepOnScreenCondition { keepSplashScreen }

        var lastResolvedThemeMode = "SYSTEM"

        splashScreen.setOnExitAnimationListener { splashViewProvider ->
            val splashView = splashViewProvider.view
            val iconView = splashViewProvider.iconView

            // Seamless Dissolve: Keep icon centered (0 translation).
            // Gently scale (1.0 -> 1.05) and fade out softly with Material 3 ease-out curve.
            val iconScaleX = ObjectAnimator.ofFloat(iconView, View.SCALE_X, 1f, 1.05f)
            val iconScaleY = ObjectAnimator.ofFloat(iconView, View.SCALE_Y, 1f, 1.05f)
            val iconAlpha = ObjectAnimator.ofFloat(iconView, View.ALPHA, 1f, 0f)
            val splashAlpha = ObjectAnimator.ofFloat(splashView, View.ALPHA, 1f, 0f)

            AnimatorSet().apply {
                playTogether(iconScaleX, iconScaleY, iconAlpha, splashAlpha)
                duration = 240L
                interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        splashViewProvider.remove()
                        val systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                        val isDarkActive = when (FolioThemeMode.fromKey(lastResolvedThemeMode)) {
                            FolioThemeMode.LIGHT,
                            FolioThemeMode.SEPIA -> false
                            FolioThemeMode.DARK,
                            FolioThemeMode.AMOLED_BLACK,
                            FolioThemeMode.SLATE_GRAY -> true
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
            val prefs by appContainer.settingsAndBackupRepository
                .observeUserPreferences()
                .collectAsStateWithLifecycle(initialValue = null)

            val ledgerViewModel: LedgerHomeViewModel = viewModel(factory = viewModelFactory)
            val isInitialDataLoaded by androidx.compose.runtime.remember(ledgerViewModel) {
                ledgerViewModel.uiState
                    .map { it.isInitialDataLoaded }
                    .distinctUntilChanged()
            }.collectAsStateWithLifecycle(initialValue = false)

            var isSessionUnlocked by lockViewModel.isSessionUnlocked
            var authErrorText by rememberSaveable { mutableStateOf<String?>(null) }

            val activePrefs = prefs
            if (activePrefs != null) {
                lastResolvedThemeMode = activePrefs.themeMode
            }
            val isDataReady = activePrefs != null && isInitialDataLoaded

            LaunchedEffect(isDataReady) {
                if (isDataReady) {
                    keepSplashScreen = false
                }
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

            FolioTheme(
                themeMode = themeMode,
                accentPalette = accentPalette,
                dynamicColorEnabled = dynamicColorEnabled,
                iconPackTheme = iconPackTheme
            ) {
                if (activePrefs == null || !isInitialDataLoaded) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {}
                } else {
                    val isLocked = activePrefs.biometricLockEnabled && !isSessionUnlocked
                    val pendingShortcut by lockViewModel.pendingShortcutAction
                    val pendingNotification by lockViewModel.pendingNotificationAction
                    val lifecycleOwner = LocalLifecycleOwner.current

                    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                        androidx.compose.foundation.layout.Box(
                            modifier = if (isLocked) {
                                @OptIn(ExperimentalComposeUiApi::class)
                                Modifier
                                    .fillMaxSize()
                                    .clearAndSetSemantics { hideFromAccessibility() }
                            } else {
                                Modifier.fillMaxSize()
                            }
                        ) {
                            // Keep FolioMainShell alive in composition so tab/dialog state survives lock & SAF pickers
                            FolioMainShell(
                                viewModelFactory = viewModelFactory,
                                initialShortcutAction = if (!isLocked) pendingShortcut else null,
                                onShortcutActionConsumed = {
                                    lockViewModel.pendingShortcutAction.value = null
                                },
                                initialNotificationAction = if (!isLocked) pendingNotification else null,
                                onNotificationActionConsumed = {
                                    lockViewModel.pendingNotificationAction.value = null
                                }
                            )
                        }

                        if (isLocked) {
                            LaunchedEffect(lifecycleOwner) {
                                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                                    if (!isSessionUnlocked) {
                                        triggerBiometricOrCredentialPrompt(
                                            onSuccess = {
                                                isSessionUnlocked = true
                                                authErrorText = null
                                            },
                                            onError = { msg ->
                                                authErrorText = msg
                                            }
                                        )
                                    }
                                }
                            }

                            LockedLedgerScreen(
                                errorMessage = authErrorText,
                                onUnlockClick = {
                                    triggerBiometricOrCredentialPrompt(
                                        onSuccess = {
                                            isSessionUnlocked = true
                                            authErrorText = null
                                        },
                                        onError = { msg ->
                                            authErrorText = msg
                                        }
                                    )
                                },
                                onExitApp = {
                                    moveTaskToBack(true)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun triggerBiometricOrCredentialPrompt(
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val biometricManager = BiometricManager.from(this)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL

        when (val canAuth = biometricManager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> { /* proceed to prompt */ }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                // Device has no lock screen or biometrics configured; allow entry rather than locking user out
                onSuccess()
                return
            }
            else -> {
                onError("Authentication is temporarily unavailable (code $canAuth). Please try again.")
                return
            }
        }

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errString.toString())
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Folio")
            .setSubtitle("Verify your identity to access your financial ledger")
            .setAllowedAuthenticators(authenticators)
            .build()

        prompt.authenticate(promptInfo)
    }
}

@Composable
private fun LockedLedgerScreen(
    errorMessage: String?,
    onUnlockClick: () -> Unit,
    onExitApp: () -> Unit = {}
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onExitApp,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn
        )
    ) {
        LockedLedgerContent(
            errorMessage = errorMessage,
            onUnlockClick = onUnlockClick
        )
    }
}

@Composable
private fun LockedLedgerContent(
    errorMessage: String?,
    onUnlockClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Locked",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Folio Ledger is Locked",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage ?: "Use your fingerprint, face, or device PIN to continue.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(28.dp))
            Button(onClick = onUnlockClick) {
                Text(text = "Unlock Folio")
            }
        }
    }
}