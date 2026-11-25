# Folio Engineering Documentation

Folio is an offline-first personal finance, budgeting, and double-entry ledger app for Android built with Jetpack Compose, Material 3, Room SQLite, and DataStore.

## Core Engineering Invariants

- **Zero Floating-Point Money:** All monetary amounts are stored and processed as `Long` minor units (`MinorMoney`) with `BigDecimal` (`RoundingMode.HALF_UP`) reserved for division, FX rates, and percentages.
- **100% Offline Privacy:** No `INTERNET` or `ACCESS_NETWORK_STATE` permissions in release builds (Play Billing uses Binder IPC only, with transitive network permissions stripped in `AndroidManifest.xml`).
- **Non-Destructive Schema Evolution:** `FolioDatabase` (v6) uses explicit `Migration` paths paired with pre-migration SQLite + WAL backups (`DatabaseBackupManager`).
- **Clean Layering & Manual DI:** Pure Kotlin `domain` layer (`domain.model`, `domain.usecase`, `domain.util`, `domain.repository`) wired via `AppContainer` and `FolioViewModelFactory`.

## Documentation Index

| Document | Scope |
| :--- | :--- |
| [Accounting & Domain Rules](01_ACCOUNTING_AND_DOMAIN_RULES.md) | `MinorMoney` representation, double-entry transaction semantics, credit card statement cycles, installments, multi-currency valuation, predictive intelligence, and demo mode sandbox rules. |
| [System Architecture & Database Schema](02_ARCHITECTURE_AND_SCHEMA.md) | Package boundaries, manual DI, Room v6 schema & migrations (`MIGRATION_1_2`–`MIGRATION_5_6`), domain calculators, and zero-allocation Compose Canvas charts. |
| [UI/UX & Material 3 Design System](03_UI_DESIGN_SYSTEM.md) | Theme modes, 12 accent palettes, semantic financial colors, typography/card standards, and 4-tab navigation hierarchy. |
| [Shared Domain Models & Repository Contracts](04_SHARED_CONTRACTS.md) | Core domain models, enums, repository interfaces, and UI state contracts. |

## Running Tests

```bash
./gradlew testDebugUnitTest
```
