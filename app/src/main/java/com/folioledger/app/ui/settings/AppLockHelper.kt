/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.settings

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Returns the BiometricManager authenticator bitmask supported on the current Android API level.
 * Android 10 (API 29) rejects combining BIOMETRIC_STRONG with DEVICE_CREDENTIAL.
 */
fun supportedAppLockAuthenticators(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
} else {
    BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
}

/**
 * Checks device lock capability and displays the system Biometric/Device Credential prompt.
 */
fun launchAppLockPrompt(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    onSuccess: () -> Unit,
    onNoDeviceLock: () -> Unit,
    onError: (String) -> Unit,
) {
    if (activity.isFinishing || activity.isDestroyed) return
    val biometricManager = BiometricManager.from(activity)
    val authenticators = supportedAppLockAuthenticators()

    when (val canAuth = biometricManager.canAuthenticate(authenticators)) {
        BiometricManager.BIOMETRIC_SUCCESS -> { /* proceed to prompt */ }

        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
        BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
        -> {
            onNoDeviceLock()
            return
        }

        else -> {
            val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (keyguard != null && !keyguard.isDeviceSecure) {
                onNoDeviceLock()
            } else {
                onError("Authentication is temporarily unavailable (code $canAuth). Please try again.")
            }
            return
        }
    }

    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                when (errorCode) {
                    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> {
                        onNoDeviceLock()
                    }

                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    -> {
                        onError("Authentication canceled.")
                    }

                    else -> {
                        onError(errString.toString().ifBlank { "Authentication failed (code $errorCode)." })
                    }
                }
            }
        },
    )

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(authenticators)
        .build()

    prompt.authenticate(promptInfo)
}
