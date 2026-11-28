/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/di/AppContainer.kt
package com.folioledger.app.di

import android.content.Context
import com.folioledger.app.data.billing.FolioBillingManager
import com.folioledger.app.data.local.FolioDatabase
import com.folioledger.app.data.repository.AccountRepositoryImpl
import com.folioledger.app.data.repository.AnalyticsAndBudgetRepositoryImpl
import com.folioledger.app.data.repository.CategoryAndTagRepositoryImpl
import com.folioledger.app.data.repository.SettingsAndBackupRepositoryImpl
import com.folioledger.app.data.repository.TransactionRepositoryImpl
import com.folioledger.app.data.repository.UserPreferencesDataSource
import com.folioledger.app.domain.repository.AccountRepository
import com.folioledger.app.domain.repository.AnalyticsAndBudgetRepository
import com.folioledger.app.domain.repository.CategoryAndTagRepository
import com.folioledger.app.domain.repository.SettingsAndBackupRepository
import com.folioledger.app.domain.repository.TransactionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class AppContainer(
    context: Context,
    val clock: Clock = systemDynamicClock,
    private val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    companion object {
        private val systemDynamicClock: Clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneId.systemDefault()
            override fun withZone(zone: ZoneId): Clock = system(zone)
            override fun instant(): Instant = Instant.now()
        }
    }

    private val appContext: Context = context.applicationContext

    private val _isDatabaseWarmedUp = MutableStateFlow(false)
    val isDatabaseWarmedUp: StateFlow<Boolean> = _isDatabaseWarmedUp.asStateFlow()

    val userPreferencesDataSource: UserPreferencesDataSource by lazy {
        UserPreferencesDataSource(appContext)
    }

    val database: FolioDatabase by lazy {
        FolioDatabase.getInstance(appContext)
    }

    suspend fun warmUpDatabase() {
        if (_isDatabaseWarmedUp.value) return
        withContext(Dispatchers.IO) {
            try {
                database.openHelper.writableDatabase
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.e("AppContainer", "Database warmup failed", e)
            } finally {
                _isDatabaseWarmedUp.value = true
            }
        }
    }

    val settingsAndBackupRepository: SettingsAndBackupRepository by lazy {
        SettingsAndBackupRepositoryImpl(
            context = appContext,
            database = database,
            userPreferencesDataSource = userPreferencesDataSource,
        )
    }

    val accountRepository: AccountRepository by lazy {
        AccountRepositoryImpl(
            accountDao = database.accountDao(),
            transactionDao = database.transactionDao(),
            settingsRepository = settingsAndBackupRepository,
            database = database,
        )
    }

    val categoryAndTagRepository: CategoryAndTagRepository by lazy {
        CategoryAndTagRepositoryImpl(
            categoryAndTagDao = database.categoryAndTagDao(),
            database = database,
        )
    }

    val transactionRepository: TransactionRepository by lazy {
        TransactionRepositoryImpl(
            transactionDao = database.transactionDao(),
            accountDao = database.accountDao(),
            categoryAndTagDao = database.categoryAndTagDao(),
            budgetAndRecurringDao = database.budgetAndRecurringDao(),
            database = database,
            clock = clock,
        )
    }

    val analyticsAndBudgetRepository: AnalyticsAndBudgetRepository by lazy {
        AnalyticsAndBudgetRepositoryImpl(
            transactionDao = database.transactionDao(),
            categoryAndTagDao = database.categoryAndTagDao(),
            budgetAndRecurringDao = database.budgetAndRecurringDao(),
            settingsRepository = settingsAndBackupRepository,
            accountDao = database.accountDao(),
            database = database,
            clock = clock,
        )
    }

    private val billingManagerDelegate = lazy {
        FolioBillingManager(
            context = appContext,
            userPreferencesDataSource = userPreferencesDataSource,
            externalScope = applicationScope,
        )
    }

    val billingManager: FolioBillingManager by billingManagerDelegate

    fun releaseBillingIfInitialized() {
        if (billingManagerDelegate.isInitialized()) {
            billingManager.destroy()
        }
    }
}
