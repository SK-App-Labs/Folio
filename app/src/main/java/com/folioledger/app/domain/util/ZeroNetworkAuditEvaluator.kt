/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.domain.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Immutable

enum class ZeroNetworkAuditStatus {
    VERIFIED,
    NETWORK_PERMISSION_DETECTED,
    UNAVAILABLE,
}

@Immutable
data class ZeroNetworkAuditReport(
    val status: ZeroNetworkAuditStatus,
    val hasInternetPermission: Boolean,
    val hasNetworkStatePermission: Boolean,
) {
    val isZeroNetworkVerified: Boolean
        get() = status == ZeroNetworkAuditStatus.VERIFIED
}

object ZeroNetworkAuditEvaluator {

    private const val PERMISSION_INTERNET = Manifest.permission.INTERNET
    private const val PERMISSION_ACCESS_NETWORK_STATE = Manifest.permission.ACCESS_NETWORK_STATE

    /**
     * Pure JVM-testable evaluator over a package's declared manifest permissions.
     */
    fun evaluate(requestedPermissions: Collection<String>?): ZeroNetworkAuditReport {
        val normalized = requestedPermissions.orEmpty()
            .asSequence()
            .map { it.trim() }
            .toSet()

        val hasInternet = normalized.any { it.equals(PERMISSION_INTERNET, ignoreCase = true) }
        val hasNetworkState = normalized.any { it.equals(PERMISSION_ACCESS_NETWORK_STATE, ignoreCase = true) }

        val status = if (!hasInternet && !hasNetworkState) {
            ZeroNetworkAuditStatus.VERIFIED
        } else {
            ZeroNetworkAuditStatus.NETWORK_PERMISSION_DETECTED
        }

        return ZeroNetworkAuditReport(
            status = status,
            hasInternetPermission = hasInternet,
            hasNetworkStatePermission = hasNetworkState,
        )
    }

    /**
     * Reads Folio's own installed APK manifest via PackageManager (100% offline, zero extra permissions).
     */
    fun inspectInstalledPackage(context: Context): ZeroNetworkAuditReport = runCatching {
        val pm = context.packageManager
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
        evaluate(packageInfo.requestedPermissions?.asList())
    }.getOrElse {
        ZeroNetworkAuditReport(
            status = ZeroNetworkAuditStatus.UNAVAILABLE,
            hasInternetPermission = false,
            hasNetworkStatePermission = false,
        )
    }
}
