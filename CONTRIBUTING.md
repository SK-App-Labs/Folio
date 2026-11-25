# Contributing to Folio

Thank you for your interest in contributing to **Folio**! Folio is an offline-first, double-entry personal finance ledger for Android built with Jetpack Compose, Material 3, Room SQLite, and DataStore.

To maintain computational precision, absolute offline privacy, and 120 FPS UI responsiveness, all contributions must strictly adhere to the engineering standards outlined below.

---

## Non-Negotiable Invariants

Before writing or modifying any code, verify your changes uphold Folio's core architectural invariants:

### 1. Zero Floating-Point Money
- All currency amounts are stored and processed as integer minor units (`Long`) via `MinorMoney` (e.g. `$10.50` = `1050L`).
- **Never** use `Double` or `Float` for money representation or intermediate calculations.
- `BigDecimal` with explicit `RoundingMode.HALF_UP` is strictly reserved for division, multi-currency exchange rates, percentages, and scaling.

### 2. 100% Offline Privacy
- Folio operates completely offline. Release builds must not request `android.permission.INTERNET` or `android.permission.ACCESS_NETWORK_STATE`.
- Any third-party libraries introducing network dependencies must have their transitive network permissions explicitly stripped in `AndroidManifest.xml` via `tools:node="remove"`.
- In-app purchases (Tip Jar) interact exclusively with Google Play Services via local Android IPC Binder.

### 3. Non-Destructive Schema Evolution
- Destructive Room database migrations (`fallbackToDestructiveMigration`) are strictly prohibited in production.
- Every schema change must include an explicit, forward-compatible `Migration` script accompanied by a pre-migration database snapshot in `DatabaseBackupManager`.

### 4. Zero-Allocation Canvas Rendering
- Custom financial charts, timeline visualizers, and sparklines drawn via Compose `Canvas` must avoid object allocations inside draw passes. Pre-allocate drawing objects (`Path`, `Paint`, rects) outside the draw phase.

---

## Development Workflow

### Building the Project
- **Prerequisites:** Android Studio Ladybug (2024.2.1+) or newer, JDK 17/21, Android SDK 35.
- Run tests:
  ```bash
  ./gradlew testDebugUnitTest
  ```
- Build debug APK:
  ```bash
  ./gradlew assembleDebug
  ```

### Code Style & Structure
- **Domain Independence:** Business logic, accounting rules, and financial calculators live in `domain` and must not depend on Android framework classes (like `Context`, `View`, or `Activity`).
- **Manual Dependency Injection:** Dependencies are wired through `AppContainer` and `FolioViewModelFactory`. Do not introduce reflection-based DI frameworks.
- **Tabular Numbers:** Monetary amounts displayed in UI components must use `fontFeatureSettings = "tnum"` to ensure digits align vertically and do not jitter during animations.

### Submitting a Pull Request
1. Fork the repository and create your branch from `main`:
   ```bash
   git checkout -b feature/your-feature-name
   ```
2. Verify all unit tests pass:
   ```bash
   ./gradlew testDebugUnitTest
   ```
3. Add unit test coverage for any new domain calculations, rules, or parsers.
4. Open a Pull Request with a clear description of the problem solved, testing performed, and verification that invariants are preserved.
