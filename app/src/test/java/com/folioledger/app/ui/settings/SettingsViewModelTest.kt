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

import com.folioledger.app.domain.model.CategoryNode
import com.folioledger.app.domain.model.CategoryType
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.domain.repository.AccountRepository
import com.folioledger.app.domain.repository.AnalyticsAndBudgetRepository
import com.folioledger.app.domain.repository.CategoryAndTagRepository
import com.folioledger.app.domain.repository.SettingsAndBackupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private inline fun <reified T : Any> createStub(): T {
        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java)
        ) { _, method, args ->
            val name = method.name
            when {
                method.returnType == kotlinx.coroutines.flow.Flow::class.java -> {
                    when {
                        name.startsWith("observeUserPreferences") -> flowOf(UserPreferences())
                        else -> flowOf(emptyList<Any>())
                    }
                }
                method.returnType == Result::class.java -> Result.success(1L)
                method.returnType == List::class.java -> emptyList<Any>()
                method.returnType == Boolean::class.javaPrimitiveType -> false
                method.returnType == Int::class.javaPrimitiveType -> 0
                method.returnType == Long::class.javaPrimitiveType -> 0L
                args != null && args.isNotEmpty() && args.last() is kotlin.coroutines.Continuation<*> -> Result.success(1L)
                else -> null
            }
        } as T
    }

    private fun createViewModel(
        expenseCategories: List<CategoryNode> = emptyList(),
        settingsRepoMock: SettingsAndBackupRepository? = null
    ): SettingsViewModel {
        val catRepo = Proxy.newProxyInstance(
            CategoryAndTagRepository::class.java.classLoader,
            arrayOf(CategoryAndTagRepository::class.java)
        ) { _, method, args ->
            when {
                method.name.startsWith("observeCategoryTree") -> flowOf(expenseCategories)
                else -> flowOf(emptyList<Any>())
            }
        } as CategoryAndTagRepository

        return SettingsViewModel(
            settingsAndBackupRepository = settingsRepoMock ?: createStub<SettingsAndBackupRepository>(),
            categoryAndTagRepository = catRepo,
            accountRepository = createStub<AccountRepository>(),
            analyticsAndBudgetRepository = createStub<AnalyticsAndBudgetRepository>(),
            computationDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun onCategoryNameInputChanged_withDuplicateName_setsValidationError() = runTest {
        val existingCat = CategoryNode(
            id = 1L,
            name = "Groceries",
            type = CategoryType.EXPENSE,
            parentId = null,
            iconName = "cart",
            colorHex = "#FF5722"
        )
        val vm = createViewModel(expenseCategories = listOf(existingCat))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        // Open create category dialog
        vm.openCreateMainCategoryDialog(CategoryType.EXPENSE)
        testScheduler.advanceUntilIdle()

        assertNotNull(vm.uiState.value.categoryEditorState)

        // Type existing category name "Groceries"
        vm.onCategoryNameInputChanged("Groceries")
        testScheduler.advanceUntilIdle()

        // Attempt save -> triggers duplicate validation
        vm.saveCategoryFromDialog()
        testScheduler.advanceUntilIdle()

        val editor = vm.uiState.value.categoryEditorState
        assertNotNull(editor)
        assertEquals("A category with this name already exists.", editor?.validationError)

        // Type unique name
        vm.onCategoryNameInputChanged("Pet Supplies")
        testScheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.categoryEditorState?.validationError)

        vm.dismissCategoryEditorDialog()
        testScheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.categoryEditorState)

        job.cancel()
    }

    @Test
    fun setPrimaryCurrency_persistsNewCurrencyCode() = runTest {
        var updatedCurrency: String? = null
        val settingsRepo = Proxy.newProxyInstance(
            SettingsAndBackupRepository::class.java.classLoader,
            arrayOf(SettingsAndBackupRepository::class.java)
        ) { _, method, args ->
            val name = method.name
            when {
                name.startsWith("observeUserPreferences") -> flowOf(UserPreferences(primaryCurrencyCode = "INR"))
                name.startsWith("updateUserPreferences") -> {
                    @Suppress("UNCHECKED_CAST")
                    val transform = args?.getOrNull(0) as? ((UserPreferences) -> UserPreferences)
                    val result = transform?.invoke(UserPreferences(primaryCurrencyCode = "INR"))
                    updatedCurrency = result?.primaryCurrencyCode
                    Result.success(Unit)
                }
                args != null && args.isNotEmpty() && args.last() is kotlin.coroutines.Continuation<*> -> Result.success(Unit)
                method.returnType == List::class.java -> emptyList<Any>()
                else -> flowOf(emptyList<Any>())
            }
        } as SettingsAndBackupRepository

        val vm = createViewModel(settingsRepoMock = settingsRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.setPrimaryCurrency("EUR")
        testScheduler.advanceUntilIdle()

        assertEquals("EUR", updatedCurrency)
        job.cancel()
    }

    @Test
    fun dismissWelcomeSheet_invokesRepositorySetting() = runTest {
        var sheetDismissed = false
        val settingsRepo = Proxy.newProxyInstance(
            SettingsAndBackupRepository::class.java.classLoader,
            arrayOf(SettingsAndBackupRepository::class.java)
        ) { _, method, args ->
            val name = method.name
            when {
                name.startsWith("observeUserPreferences") -> flowOf(UserPreferences(hasSeenWelcomeSheet = false))
                name.startsWith("updateUserPreferences") -> {
                    @Suppress("UNCHECKED_CAST")
                    val transform = args?.getOrNull(0) as? ((UserPreferences) -> UserPreferences)
                    val result = transform?.invoke(UserPreferences(hasSeenWelcomeSheet = false))
                    if (result?.hasSeenWelcomeSheet == true) {
                        sheetDismissed = true
                    }
                    Result.success(Unit)
                }
                args != null && args.isNotEmpty() && args.last() is kotlin.coroutines.Continuation<*> -> Result.success(Unit)
                method.returnType == List::class.java -> emptyList<Any>()
                else -> flowOf(emptyList<Any>())
            }
        } as SettingsAndBackupRepository

        val vm = createViewModel(settingsRepoMock = settingsRepo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.dismissWelcomeSheet()
        testScheduler.advanceUntilIdle()

        assertTrue(sheetDismissed)
        job.cancel()
    }
}
