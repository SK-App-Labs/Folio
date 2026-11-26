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
import com.folioledger.app.domain.model.CsvImportPreset
import com.folioledger.app.domain.model.ImportCsvPreview
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.domain.repository.AccountRepository
import com.folioledger.app.domain.repository.AnalyticsAndBudgetRepository
import com.folioledger.app.domain.repository.CategoryAndTagRepository
import com.folioledger.app.domain.repository.SettingsAndBackupRepository
import com.folioledger.app.ui.settings.sheets.formatExchangeRatePlain
import com.folioledger.app.ui.settings.sheets.parseExchangeRateInput
import com.folioledger.app.ui.settings.sheets.resolveMatchedLanguageTag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                method.returnType == String::class.java -> ""
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

    @Test
    fun onCategoryNameInputChanged_preservesManualIconSelectionOnNewCategory() = runTest {
        val vm = createViewModel()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.openCreateMainCategoryDialog(CategoryType.EXPENSE)
        testScheduler.advanceUntilIdle()

        vm.updateCategoryEditorState {
            it.copy(iconName = "pets", colorHex = "#7C3AED")
        }
        testScheduler.advanceUntilIdle()

        // Typing a matched keyword ("Coffee") must NOT overwrite user's manual icon/color choice
        vm.onCategoryNameInputChanged("Coffee")
        testScheduler.advanceUntilIdle()

        val editor = vm.uiState.value.categoryEditorState
        assertNotNull(editor)
        assertEquals("Coffee", editor?.name)
        assertEquals("pets", editor?.iconName)
        assertEquals("#7C3AED", editor?.colorHex)
        assertEquals(true, editor?.isIconManuallyOverridden)

        job.cancel()
    }

    @Test
    fun tagActions_rejectCommas_stripRepeatedHashes_andReportMergeTarget() = runTest {
        var createdTag: String? = null
        var renamedPair: Pair<String, String>? = null
        val catRepo = object : CategoryAndTagRepository by createStub() {
            override fun observeAllTags() = flowOf(listOf("Existing", "old"))
            override suspend fun createTag(tagName: String): Result<Unit> {
                createdTag = tagName
                return Result.success(Unit)
            }
            override suspend fun renameTag(oldTagName: String, newTagName: String): Result<Unit> {
                renamedPair = oldTagName to newTagName
                return Result.success(Unit)
            }
        }

        val vm = SettingsViewModel(
            settingsAndBackupRepository = createStub(),
            categoryAndTagRepository = catRepo,
            accountRepository = createStub(),
            analyticsAndBudgetRepository = createStub(),
            computationDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.createTag("tax, 2026")
        testScheduler.advanceUntilIdle()
        assertNull(createdTag)
        assertEquals("Tag names cannot contain commas.", vm.uiState.value.snackbarMessage)

        vm.createTag("###tax")
        testScheduler.advanceUntilIdle()
        assertEquals("tax", createdTag)

        vm.renameTag("old", "###existing")
        testScheduler.advanceUntilIdle()
        assertEquals("old" to "existing", renamedPair)
        assertEquals("Merged #old into #Existing", vm.uiState.value.snackbarMessage)

        job.cancel()
    }

    @Test
    fun requestDeleteCategory_stagesConfirmation_andRecurringErrorsSurfaceSnackbar() = runTest {
        val cat = CategoryNode(
            id = 12L,
            name = "Dining",
            type = CategoryType.EXPENSE,
            parentId = null,
            iconName = "restaurant",
            colorHex = "#F97316"
        )
        val analyticsRepo = object : AnalyticsAndBudgetRepository by createStub() {
            override suspend fun updateRecurringRuleActive(ruleId: Long, isActive: Boolean): Result<Unit> =
                Result.failure(IllegalStateException("Recurring rule has already passed its end date."))
        }

        val catRepo = object : CategoryAndTagRepository by createStub() {
            override fun observeCategoryTree(type: CategoryType) = flowOf(listOf(cat))
        }

        val vm = SettingsViewModel(
            settingsAndBackupRepository = createStub(),
            categoryAndTagRepository = catRepo,
            accountRepository = createStub(),
            analyticsAndBudgetRepository = analyticsRepo,
            computationDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.requestDeleteCategory(12L)
        testScheduler.advanceUntilIdle()
        assertEquals(12L, vm.uiState.value.categoryPendingDelete?.id)

        vm.cancelDeleteCategory()
        testScheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.categoryPendingDelete)

        vm.toggleRecurringRuleActive(5L, true)
        testScheduler.advanceUntilIdle()
        assertEquals("Recurring rule has already passed its end date.", vm.uiState.value.snackbarMessage)

        job.cancel()
    }

    @Test
    fun demoMode_blocksBackupRestoreCsvAndClearAllDataActions() = runTest {
        val prefsFlow = MutableStateFlow(UserPreferences(isDemoModeActive = true))
        var repoCalls = 0
        val settingsRepo = object : SettingsAndBackupRepository by createStub() {
            override fun observeUserPreferences() = prefsFlow
            override suspend fun createPortableZipBackup(destinationUriString: String): Result<String> {
                repoCalls++
                return Result.success("backup.zip")
            }
            override suspend fun clearAllData(): Result<Unit> {
                repoCalls++
                return Result.success(Unit)
            }
            override suspend fun exportTransactionsToCsv(
                destinationUriString: String,
                startEpochDay: Long?,
                endEpochDay: Long?,
                accountId: Long?
            ): Result<Int> {
                repoCalls++
                return Result.success(10)
            }
            override suspend fun previewTransactionsCsv(
                sourceUriString: String,
                preset: CsvImportPreset
            ): Result<ImportCsvPreview> {
                repoCalls++
                return Result.failure(IllegalStateException("should not run"))
            }
        }

        val vm = SettingsViewModel(
            settingsAndBackupRepository = settingsRepo,
            categoryAndTagRepository = createStub(),
            accountRepository = createStub(),
            analyticsAndBudgetRepository = createStub(),
            computationDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.createPortableZipBackup("backup.zip")
        vm.requestRestoreConfirmation("content://backup.zip")
        vm.clearAllData()
        vm.exportAllTransactionsToCsv("export.csv")
        vm.loadCsvPreview("content://import.csv")
        testScheduler.advanceUntilIdle()

        assertEquals(0, repoCalls)
        assertNull(vm.uiState.value.pendingRestoreUriString)
        assertNotNull(vm.uiState.value.snackbarMessage)
        assertTrue(vm.uiState.value.snackbarMessage!!.contains("Demo Sandbox", ignoreCase = true))

        job.cancel()
    }

    @Test
    fun setPrimaryCurrency_rebasesSecondaryCurrenciesAndPreservesCustomGrouping() = runTest {
        val prefsFlow = MutableStateFlow(
            UserPreferences(
                primaryCurrencyCode = "USD",
                numberGrouping = NumberGroupingSystem.INDIAN, // Custom override on USD
                secondaryCurrencies = listOf(
                    SecondaryCurrency("EUR", 1.10),
                    SecondaryCurrency("INR", 0.012)
                )
            )
        )
        val settingsRepo = object : SettingsAndBackupRepository by createStub() {
            override fun observeUserPreferences() = prefsFlow
            override suspend fun updateUserPreferences(
                transform: (UserPreferences) -> UserPreferences
            ): Result<Unit> {
                prefsFlow.update(transform)
                return Result.success(Unit)
            }
        }

        val vm = SettingsViewModel(
            settingsAndBackupRepository = settingsRepo,
            categoryAndTagRepository = createStub(),
            accountRepository = createStub(),
            analyticsAndBudgetRepository = createStub(),
            computationDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.setPrimaryCurrency("INR")
        testScheduler.advanceUntilIdle()

        val afterSwitch = prefsFlow.value
        assertEquals("INR", afterSwitch.primaryCurrencyCode)
        assertEquals(NumberGroupingSystem.INDIAN, afterSwitch.numberGrouping)
        val rebasedUsd = afterSwitch.secondaryCurrencies.first { it.code == "USD" }
        val rebasedEur = afterSwitch.secondaryCurrencies.first { it.code == "EUR" }
        assertEquals(83.333333, rebasedUsd.exchangeRateToPrimary, 0.0001)
        assertEquals(91.666667, rebasedEur.exchangeRateToPrimary, 0.0001)

        // Switching from a default grouping adopts the new currency's default
        prefsFlow.value = UserPreferences(
            primaryCurrencyCode = "USD",
            numberGrouping = NumberGroupingSystem.INTERNATIONAL
        )
        testScheduler.advanceUntilIdle()
        vm.setPrimaryCurrency("INR")
        testScheduler.advanceUntilIdle()
        assertEquals(NumberGroupingSystem.INDIAN, prefsFlow.value.numberGrouping)

        // Unsupported currency code is rejected
        vm.setPrimaryCurrency("NOT_A_CODE")
        testScheduler.advanceUntilIdle()
        assertEquals("INR", prefsFlow.value.primaryCurrencyCode)
        assertTrue(vm.uiState.value.snackbarMessage!!.contains("Unsupported currency"))

        // Non-pivot switch clears stale old-primary-denominated secondary rates and warns the user
        prefsFlow.value = UserPreferences(
            primaryCurrencyCode = "INR",
            numberGrouping = NumberGroupingSystem.INDIAN,
            secondaryCurrencies = listOf(
                SecondaryCurrency("USD", 85.0),
                SecondaryCurrency("EUR", 92.0)
            )
        )
        testScheduler.advanceUntilIdle()
        vm.setPrimaryCurrency("GBP")
        testScheduler.advanceUntilIdle()
        assertEquals("GBP", prefsFlow.value.primaryCurrencyCode)
        assertTrue(prefsFlow.value.secondaryCurrencies.isEmpty())
        assertTrue(vm.uiState.value.snackbarMessage!!.contains("Previous secondary rates were cleared"))

        job.cancel()
    }

    @Test
    fun addOrUpdateSecondaryCurrency_validatesRateBoundsAndPrimaryCollision() = runTest {
        val prefsFlow = MutableStateFlow(UserPreferences(primaryCurrencyCode = "USD"))
        val settingsRepo = object : SettingsAndBackupRepository by createStub() {
            override fun observeUserPreferences() = prefsFlow
            override suspend fun updateUserPreferences(
                transform: (UserPreferences) -> UserPreferences
            ): Result<Unit> {
                prefsFlow.update(transform)
                return Result.success(Unit)
            }
        }

        val vm = SettingsViewModel(
            settingsAndBackupRepository = settingsRepo,
            categoryAndTagRepository = createStub(),
            accountRepository = createStub(),
            analyticsAndBudgetRepository = createStub(),
            computationDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.addOrUpdateSecondaryCurrency("EUR", Double.NaN)
        vm.addOrUpdateSecondaryCurrency("EUR", Double.POSITIVE_INFINITY)
        vm.addOrUpdateSecondaryCurrency("EUR", 0.0)
        vm.addOrUpdateSecondaryCurrency("EUR", 1e-9)
        vm.addOrUpdateSecondaryCurrency("EUR", 2_000_000_000.0)
        testScheduler.advanceUntilIdle()
        assertTrue(prefsFlow.value.secondaryCurrencies.isEmpty())

        vm.addOrUpdateSecondaryCurrency("usd", 1.0)
        testScheduler.advanceUntilIdle()
        assertTrue(prefsFlow.value.secondaryCurrencies.isEmpty())
        assertTrue(vm.uiState.value.snackbarMessage!!.contains("already your primary currency"))

        vm.addOrUpdateSecondaryCurrency("eur", 1.08)
        testScheduler.advanceUntilIdle()
        assertEquals(1, prefsFlow.value.secondaryCurrencies.size)
        assertEquals("EUR", prefsFlow.value.secondaryCurrencies[0].code)
        assertEquals(1.08, prefsFlow.value.secondaryCurrencies[0].exchangeRateToPrimary, 0.0001)

        job.cancel()
    }

    @Test
    fun fileOpMutex_preventsConcurrentFileOperations() = runTest {
        val firstOpGate = CompletableDeferred<Result<String>>()
        var callCount = 0
        val settingsRepo = object : SettingsAndBackupRepository by createStub() {
            override suspend fun createPortableZipBackup(destinationUriString: String): Result<String> {
                callCount++
                return firstOpGate.await()
            }
            override fun resolveDisplayName(uriString: String, fallback: String): String =
                uriString.ifBlank { fallback }
        }

        val vm = SettingsViewModel(
            settingsAndBackupRepository = settingsRepo,
            categoryAndTagRepository = createStub(),
            accountRepository = createStub(),
            analyticsAndBudgetRepository = createStub(),
            computationDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.createPortableZipBackup("one.zip")
        testScheduler.runCurrent()
        assertTrue(vm.uiState.value.isBusyWithFileOperation)
        assertEquals(1, callCount)

        vm.createPortableZipBackup("two.zip")
        testScheduler.runCurrent()
        assertEquals(1, callCount)
        assertEquals("Another operation is already in progress.", vm.uiState.value.snackbarMessage)

        firstOpGate.complete(Result.success("one.zip"))
        testScheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.isBusyWithFileOperation)

        job.cancel()
    }

    @Test
    fun languageMatching_andSecondaryRateHelpers_handleVariantsSafely() {
        assertEquals("", resolveMatchedLanguageTag(""))
        assertEquals("zh-CN", resolveMatchedLanguageTag("zh"))
        assertEquals("zh-CN", resolveMatchedLanguageTag("zh-CN"))
        assertEquals("zh-CN", resolveMatchedLanguageTag("zh-Hans-CN"))
        assertEquals("zh-TW", resolveMatchedLanguageTag("zh-TW"))
        assertEquals("zh-TW", resolveMatchedLanguageTag("zh-HK"))
        assertEquals("zh-TW", resolveMatchedLanguageTag("zh-Hant"))
        assertEquals("zh-TW", resolveMatchedLanguageTag("zh-Hant-TW"))
        assertEquals("id", resolveMatchedLanguageTag("in"))
        assertEquals("id", resolveMatchedLanguageTag("in-ID"))
        assertEquals("en", resolveMatchedLanguageTag("en-US"))
        assertEquals("pt-BR", resolveMatchedLanguageTag("pt-BR"))

        assertEquals(84.5, parseExchangeRateInput("84.5")!!, 0.0001)
        assertEquals(84.5, parseExchangeRateInput("84,5")!!, 0.0001)
        assertEquals(1234.56, parseExchangeRateInput("1,234.56")!!, 0.0001)
        assertEquals(1234.56, parseExchangeRateInput("1.234,56")!!, 0.0001)
        assertEquals(1234567.0, parseExchangeRateInput("1,234,567")!!, 0.0001)
        assertEquals(1234.5, parseExchangeRateInput("1\u00A0234,50")!!, 0.0001)
        assertNull(parseExchangeRateInput(""))
        assertNull(parseExchangeRateInput("0"))
        assertNull(parseExchangeRateInput("1000000001"))

        assertEquals("0.000001", formatExchangeRatePlain(0.000001))
        assertEquals("83.5", formatExchangeRatePlain(83.50))
    }
}
