# Folio — System Architecture & Database Schema

## 1. Package Ownership & Architecture Boundaries
Root package: `com.folioledger.app`

- `com.folioledger.app.domain.*` — Pure Kotlin domain models, value classes (`MinorMoney`), enums, use cases (`AccountingRulesEngine`), calculation engines (`domain/util/`), and repository interfaces. Free of Android framework dependencies.
- `com.folioledger.app.data.*` — Room database (`FolioDatabase`), entities, DAOs, TypeConverters, repository implementations, DataStore preferences (`UserPreferencesDataSource`), Skia PDF exporter (`PdfStatementEngine`), CSV import/export (`CsvExportImportEngine`), and backup engines (`DatabaseBackupEngine`, `AutoBackupManager`).
- `com.folioledger.app.ui.*` — Jetpack Compose UI, Material 3 design system, ViewModels, and modular bottom sheets/widgets.
- `com.folioledger.app.di.*` & `com.folioledger.app.navigation.*` — Manual dependency injection container (`AppContainer`), ViewModel factory (`FolioViewModelFactory`), and shell navigation (`FolioMainShell`).

## 2. Dependency Injection Strategy
Folio uses **Manual Constructor DI (`AppContainer` + `FolioViewModelFactory`)** for full compatibility with AGP 9.4+ Built-in Kotlin and fast incremental builds—no Dagger/Hilt or reflection-based DI frameworks are used.

## 3. Room SQLite Database Schema (`FolioDatabase`, Version = 6)
`FolioDatabase` is at **Version 6**, using explicit non-destructive migrations (`MIGRATION_1_2` through `MIGRATION_5_6`) paired with a pre-migration WAL-aware backup guard (`DatabaseBackupManager.createPreMigrationBackup`). Destructive fallback is strictly prohibited.

### Migration Summary
- **v1 -> v2 (`MIGRATION_1_2`)**: Added `accounts.initialUnbilledMinor` for credit card mid-cycle unbilled debt tracking.
- **v2 -> v3 (`MIGRATION_2_3`)**: Added composite indices on `transactions(accountId, ledgerDateEpochDay)` and `transactions(transferToAccountId, ledgerDateEpochDay)`.
- **v3 -> v4 (`MIGRATION_3_4`)**: Added `accounts.isPinned` and index `index_accounts_isPinned`.
- **v4 -> v5 (`MIGRATION_4_5`)**: Rebuilt `transaction_splits` to allow nullable `categoryId` (`ON DELETE SET NULL`), and replaced single-column `transactions` indices with composite `index_transactions_ledgerDateEpochDay_timestampEpochMillis_id`.
- **v5 -> v6 (`MIGRATION_5_6`)**: Rebuilt `budgets` to drop `UNIQUE(categoryId)` and add multi-cadence, custom date-range, and rollover fields (`name`, `periodType`, `startDateEpochDay`, `endDateEpochDay`, `rolloverEnabled`, `rolloverIncludeDeficit`).

### Table 1: `accounts`
- `id`: `Long` (PK, `autoGenerate = true`)
- `name`: `String`
- `groupType`: `String` (`AccountGroup`)
- `currencyCode`: `String` (ISO 4217, e.g. `"INR"`, `"USD"`)
- `openingBalanceMinor`: `Long` (Signed minor units)
- `initialUnbilledMinor`: `Long?` (Added in v2 for credit card unbilled allocation)
- `creditLimitMinor`: `Long?`
- `settlementDayOfMonth`: `Int?`
- `paymentDueDayOfMonth`: `Int?`
- `parentAccountId`: `Long?` (FK -> `accounts.id` `ON DELETE SET NULL`; indexed; used by `DEBIT_CARD`)
- `includeInNetWorth`: `Boolean` (default `true`)
- `isArchived`: `Boolean` (default `false`)
- `colorHex`: `String`
- `iconName`: `String`
- `sortOrder`: `Int`
- `isPinned`: `Boolean` (default `false`; added in v4)
- `createdAtEpochMillis`: `Long`
- **Indices:** `groupType`, `parentAccountId`, `isArchived`, `isPinned`

### Table 2: `categories`
- `id`: `Long` (PK, `autoGenerate = true`)
- `name`: `String`
- `type`: `String` (`CategoryType`: `INCOME`, `EXPENSE`)
- `parentId`: `Long?` (FK -> `categories.id` `ON DELETE CASCADE`; indexed; `null` = Main Category, non-null = Subcategory)
- `iconName`: `String`
- `colorHex`: `String`
- `sortOrder`: `Int`
- `isArchived`: `Boolean` (default `false`)
- **Indices:** `type`, `parentId`, `isArchived`

### Table 3: `transactions`
- `id`: `Long` (PK, `autoGenerate = true`)
- `type`: `String` (`TransactionType`: `INCOME`, `EXPENSE`, `TRANSFER`, `CC_SETTLEMENT`, `REFUND`, `OPENING_BALANCE`, `RECONCILIATION`)
- `timestampEpochMillis`: `Long`
- `ledgerDateEpochDay`: `Long`
- `amountMinor`: `Long`
- `currencyCode`: `String`
- `accountId`: `Long` (FK -> `accounts.id` `ON DELETE CASCADE`)
- `transferToAccountId`: `Long?` (FK -> `accounts.id` `ON DELETE SET NULL`)
- `transferFeeMinor`: `Long` (default `0L`)
- `categoryId`: `Long?` (FK -> `categories.id` `ON DELETE SET NULL`; indexed)
- `isSplit`: `Boolean` (default `false`)
- `payeeOrDescription`: `String`
- `note`: `String`
- `originalTransactionId`: `Long?`
- `installmentPlanId`: `Long?` (FK -> `installment_plans.id` `ON DELETE CASCADE`; indexed)
- `installmentIndex`: `Int?`
- `installmentTotalCount`: `Int?`
- `recurringRuleId`: `Long?` (FK -> `recurring_rules.id` `ON DELETE SET NULL`; indexed)
- **Indices:** `(ledgerDateEpochDay, timestampEpochMillis, id)`, `timestampEpochMillis`, `categoryId`, `type`, `installmentPlanId`, `recurringRuleId`, `(accountId, ledgerDateEpochDay)`, `(transferToAccountId, ledgerDateEpochDay)`

### Table 4: `transaction_splits`
- `id`: `Long` (PK, `autoGenerate = true`)
- `transactionId`: `Long` (FK -> `transactions.id` `ON DELETE CASCADE`; indexed)
- `categoryId`: `Long?` (FK -> `categories.id` `ON DELETE SET NULL`; indexed; nullable since v5)
- `amountMinor`: `Long`
- `note`: `String`

### Table 5: `tags` & Table 6: `transaction_tags`
- `tags`: `id: Long` (PK), `name: String` (Unique Index), `colorHex: String`
- `transaction_tags`: Composite PK (`transactionId: Long`, `tagId: Long`), both FK-indexed with `ON DELETE CASCADE`.

### Table 7: `bookmarks` (Quick-Entry Templates)
- `id`: `Long` (PK, `autoGenerate = true`)
- `title`: `String`
- `type`: `String` (`TransactionType`)
- `amountMinor`: `Long`
- `accountId`: `Long` (FK -> `accounts.id` `ON DELETE CASCADE`; indexed)
- `transferToAccountId`: `Long?` (FK -> `accounts.id` `ON DELETE SET NULL`; indexed)
- `categoryId`: `Long?` (FK -> `categories.id` `ON DELETE SET NULL`; indexed)
- `payeeOrDescription`: `String`
- `note`: `String`
- `tagsCsv`: `String`
- `sortOrder`: `Int` (indexed)

### Table 8: `budgets` (Updated in v6)
- `id`: `Long` (PK, `autoGenerate = true`)
- `categoryId`: `Long?` (FK -> `categories.id` `ON DELETE CASCADE`; indexed; `null` = Overall Budget)
- `name`: `String` (Optional custom title, e.g. `"Europe Trip"`)
- `limitAmountMinor`: `Long`
- `alertThresholdPercent`: `Int` (default `80`)
- `periodType`: `String` (`BudgetPeriodType`: `WEEKLY`, `BI_WEEKLY`, `MONTHLY`, `QUARTERLY`, `ANNUAL`, `CUSTOM`; indexed)
- `startDateEpochDay`: `Long?` (Anchor for `BI_WEEKLY`, `CUSTOM`, or rollover replay start)
- `endDateEpochDay`: `Long?` (Required for `CUSTOM`; `null` for recurring cadences)
- `rolloverEnabled`: `Boolean` (default `false`)
- `rolloverIncludeDeficit`: `Boolean` (default `false`)
- `updatedAtEpochMillis`: `Long`

### Table 9: `recurring_rules`
- `id`: `Long` (PK, `autoGenerate = true`)
- `title`: `String`
- `type`: `String` (`TransactionType`)
- `amountMinor`: `Long`
- `currencyCode`: `String`
- `accountId`: `Long` (FK -> `accounts.id` `ON DELETE CASCADE`; indexed)
- `transferToAccountId`: `Long?` (FK -> `accounts.id` `ON DELETE SET NULL`; indexed)
- `categoryId`: `Long?` (FK -> `categories.id` `ON DELETE SET NULL`; indexed)
- `frequency`: `String` (`RepeatFrequency`, 13 cadences from `EVERY_DAY` to `ANNUALLY`)
- `startDateEpochDay`: `Long`
- `nextOccurrenceEpochDay`: `Long` (Composite index `(isActive, nextOccurrenceEpochDay)`)
- `endDateEpochDay`: `Long?`
- `payeeOrDescription`: `String`
- `note`: `String`
- `isActive`: `Boolean` (default `true`)

### Table 10: `installment_plans`
- `id`: `Long` (PK, `autoGenerate = true`)
- `title`: `String`
- `accountId`: `Long` (FK -> `accounts.id` `ON DELETE CASCADE`; indexed)
- `categoryId`: `Long?` (FK -> `categories.id` `ON DELETE SET NULL`; indexed)
- `totalAmountMinor`: `Long`
- `monthsCount`: `Int`
- `startDateEpochDay`: `Long`
- `createdAtEpochMillis`: `Long`

## 4. Pure Domain Calculators (`domain/util/`)
To guarantee testability and keep the Android Main Thread unblocked, financial computation is isolated into pure Kotlin calculators under `app/src/main/java/com/folioledger/app/domain/util/`:

1. `AccountPassbookCalculator.kt` — Running balances, day grouping, debit card rollups
2. `BackupTimeFormatter.kt` — Relative timestamp labels for backup management
3. `BudgetWindowAndRolloverCalculator.kt` — Multi-cadence window math and immutable ledger rollover replay
4. `CardMilestoneCalculator.kt` — Credit card anniversary milestones and spend tracking
5. `CategoryDrillDownCalculator.kt` — Category tree filtering and subcategory breakdowns
6. `CategorySuggestionHelper.kt` — Deterministic category icon/color suggestion matching
7. `CompetitorCsvParser.kt` — Offline BOM-aware CSV migration across 14 competitor apps
8. `CreditCardCockpitCalculator.kt` — Single-pass credit card cockpit spend and status pipeline
9. `CreditCardCycleCalculator.kt` — Billed statement closing vs unbilled cycle window calculation
10. `DueRemindersEvaluator.kt` — Card bill and recurring commitment due/overdue notification evaluation
11. `LedgerBucketingCalculator.kt` — Daily/Weekly/Monthly/Calendar ledger grouping
12. `MerchantAndTagAnalyticsCalculator.kt` — Payee frequency/outlay and tag project netting
13. `MoneyFormatterAndCalculator.kt` — Minor-unit `BigDecimal` arithmetic, keypad expression parser, and currency formatting
14. `NetWorthPredictiveCalculator.kt` — Pre-grouped O(1) net worth trajectory trend and emergency runway calculation
15. `PayeeAutofillResolver.kt` — Deterministic metadata autofill from historical transactions
16. `PayeeSuggestionEngine.kt` — Prefix/word-boundary/substring payee ranking
17. `RecurringBurdenCalculator.kt` — Monthly fixed commitment burden calculation
18. `RecurringScheduleCalculator.kt` — Recurrence frequency next-occurrence projection
19. `SafeToSpendCalculator.kt` — Discretionary cash and 14-day overdraft radar
20. `SavingsGoalCalculator.kt` — Wealth vault and surplus progress calculations
21. `StatsPeriodCalculator.kt` — Financial period stepping with clamped month boundaries
22. `TagDateSpanFormatter.kt` — Trip & event active date span formatting
23. `TransactionEntryRulesEngine.kt` — Split transaction validation and installment preview schedules
24. `WidgetSnapshotBuilder.kt` — Glance home-screen widget snapshot projection

All domain, data, and UI ViewModel logic is backed by **57 unit test suites (596 tests)** under `app/src/test/java/com/folioledger/app/`.

## 5. Interactive Demo Mode & Auto-Stash Sandbox Architecture
- **Express Onboarding (`WelcomeExpressBottomSheet`)**: Allows first-time users to select primary currency, number grouping system (`International` vs `Indian`), and immediately preview Folio with sample data or start fresh.
- **Headless `DemoModeManager`**:
  - Controls demo state (`isDemoModeActive`) and pauses `AutoBackupManager` while demo mode is active to prevent sample data from contaminating user backups.
  - Atomically stashes active user data to `filesDir/demo_stash/pre_demo_real_ledger.zip` (with legacy fallback to `noBackupFilesDir/demo_stash`) via a `.tmp` swap before seeding sample data, and restores it cleanly on exit.
  - Table clearing runs in strict reverse-topological order (`transaction_tags` -> `transaction_splits` -> `transactions` -> `bookmarks` -> `budgets` -> `recurring_rules` -> `installment_plans` -> `accounts` -> `categories`).

## 6. Zero-Allocation Canvas Chart Performance Architecture
- **Zero-Allocation Canvas Chart Drawing (`FolioCharts.kt` & `NetWorthAnalyticsComponents.kt`)**:
  - In `SixMonthSmoothTrendAreaChart`: `Brush.verticalGradient` allocations are hoisted outside `Canvas` with `remember`, and per-frame `ArrayList<Offset>` allocations are replaced with reusable `FloatArray` buffers (`pointsX` and `pointsY`), eliminating GC churn during 60/120fps animations and touch scrubbing.
  - In `NetWorthGrowthCanvasChart`: pre-computed usable canvas dimensions and cached `Brush.verticalGradient` avoid per-frame closure and shader allocations inside `Canvas`.
