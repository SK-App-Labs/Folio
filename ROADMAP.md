# Folio Ledger — Production Release Roadmap

Folio is an offline-first personal finance, budgeting, and double-entry ledger application for Android built with Jetpack Compose, Material 3, Room SQLite, and DataStore.

---

## ✅ Completed Feature Milestones

### M10: Secondary Currencies, PDF Statements & Auto-Backup Engine (v1.3.0)
- `[x]` **Multi-Currency Engine**: Custom secondary currencies with user-defined exchange rates and dynamic net worth valuation.
- `[x]` **Vector PDF Generator**: Zero-dependency Android Skia vector PDF generator for branded bank-style passbooks and universal annual tax statements.
- `[x]` **Tiered Rolling Auto-Backup**: Automated rolling session backups with 30-day tiered retention (hourly, daily, weekly) and dual local/SAF restore.
- `[x]` **Modular Data Sheets**: Bottom sheets for spreadsheet export/import, transfer & manual backup, and automated backup management.

### M11: Credit Cards Cockpit & Predictive Financial Intelligence Suite (v1.4.0)
- `[x]` **Credit Cards Cockpit (`StatsSubTab.CREDIT_CARDS`)**: Statement cycle timeline, bureau utilization threshold calibration (30% benchmark), smart swipe headroom, and debt-free milestone banner.
- `[x]` **Net Worth Velocity & Emergency Runway**: Monthly trajectory delta, burn rate, and runway calculation in months (`computeEmergencyRunway`).
- `[x]` **Merchant & Payee Intelligence**: Dual sort by outlay vs. frequency, 6-month historical trends, cadence detection, and drill-down inspection sheet.
- `[x]` **Events, Trips & Reimbursements Hub**: Smart mode detection in Tags (reimbursable claim netting vs. trip burn rate, 1-tap receipt drill-down).
- `[x]` **Discretionary Safe-to-Spend & 14-Day Overdraft Radar**: Real-time unencumbered cash protecting card debt and recurring commitments with proactive shortfall alerts.

### M12: Interactive Demo Mode, Auto-Stash Sandbox & Codebase Modularization (v1.5.0)
- `[x]` **Express Onboarding**: Welcome sheet for currency discovery, number grouping selection, and 1-tap interactive demo seeding.
- `[x]` **Headless Demo Sandbox**: `DemoModeManager` & `DemoDataSeeder` with atomic auto-stash to `noBackupFilesDir/demo_stash/pre_demo_real_ledger.zip` and reverse-topological table resets.
- `[x]` **Architectural Decomposition**: 26-phase modularization of ViewModels, bottom sheets, dialogs, and domain calculators.
- `[x]` **Zero-Allocation Canvas Charts**: Zero-allocation Compose Canvas rendering for buttery 60/120 FPS UI responsiveness.
- `[x]` **Comprehensive QA Test Suite**: 240+ passing JVM unit tests covering persistence, demo mode, backup retention, PDF preset resolution, domain calculators, and ViewModels.

### M13: Local Bill & Credit Card Due Reminders (v1.6.0)
- `[x]` **Zero-Network Push Engine**: Native Android `WorkManager` daily background worker (`DueRemindersWorker` & `DueRemindersScheduler`) scheduled at user's preferred time (default 09:00 AM).
- `[x]` **Isolated Domain Evaluator**: `DueRemindersEvaluator` unit-tested across overdue alerts, notice windows, and zero-balance skip rules.
- `[x]` **Dedicated Notification Channels**: `folio_channel_credit_cards` and `folio_channel_recurring_bills` with Android 13+ `POST_NOTIFICATIONS` runtime permission flow.
- `[x]` **1-Tap Deep Linking**: Actionable notification buttons ("Settle Now" pre-filling `CreditCardSettlementSheet` and "Log Payment" opening `TransactionEntrySheet`).
- `[x]` **Biometric Guard Protection**: Strict authentication check preventing unauthorized ledger access from notification taps when app lock is active.

### M14: Payee Autocomplete & Predictive Smart Fill Engine (v1.8.0)
- `[x]` **Search-as-You-Type Autocomplete**: High-speed ranking over historical payees using Tier 1 prefix, Tier 2 word-boundary, and Tier 3 substring matching.
- `[x]` **Self-Healing Canonical Casing**: SQLite CTE and aggregate query grouping preserving canonical casing with zero database migrations.
- `[x]` **Predictive Metadata Autofill**: Non-destructive auto-fill of Category, Subcategory, Account, and Tags based on the payee's latest entry, strictly preserving any entered amount.
- `[x]` **Multi-Currency Safety**: Currency mismatch guards preventing inappropriate account substitution across different currency accounts.
- `[x]` **IME-Docked Suggestion Strip**: Smooth 120 FPS suggestion strip above the software keypad with 1-tap Undo banner.

### M15: Multi-Cadence & Rollover Budgets Engine (v1.9.0)
- `[x]` **Multi-Cadence Cadence Support**: Native budgeting across Weekly, Bi-Weekly, Monthly, Quarterly, Annual, and Custom Date Range cycles (`BudgetPeriodType`).
- `[x]` **Zero-Drift Rollover Engine**: Pure Kotlin `BudgetWindowAndRolloverCalculator` replaying completed historical periods dynamically using Long minor units exclusively.
- `[x]` **Room v6 Schema Migration (`MIGRATION_5_6`)**: Rebuilt `budgets` table to drop single-category uniqueness, enabling concurrent cadences and multiple custom date-range budgets.
- `[x]` **Pacing Progress Indicators**: Visual pacing indicators showing target vs. actual spend rates over time.
- `[x]` **Trips & Custom Budgets Section**: Pinned UI section with dynamic status badges (`Upcoming`, `Active · Day X of Y`, `Completed`).

### M16: 14-App Competitor CSV Migration Presets & Universal Parser (v1.9.0)
- `[x]` **100% Offline Pre-Normalization Bridge**: Universal migration tool supporting 14 top personal finance applications:
  - *Primary Featured*: Ivy Wallet, Cashew, Money Manager (Realbyte), Wallet by BudgetBakers, Money Lover, and YNAB.
  - *Secondary*: Intuit Mint, Spendee, Monefy, 1Money, Bluecoins Finance, Financisto, Money Manager (KTW), and Fortune City.
- `[x]` **Robust Delimiter & BOM Detection**: Pure JVM parser (`CompetitorCsvParser`) with automatic UTF-8 / UTF-16 BOM detection, RFC 4180 quote-aware delimiter resolution (comma, semicolon, tab), and regex-based DMY vs. MDY date order disambiguation.
- `[x]` **Shared Transfer Consolidator**: Intelligent pair matching uniting separate 2-row transfer entries into unified `TRANSFER` entities with cross-currency support.
- `[x]` **Decoupled Identity Guarantee**: External CSV identifiers are never mapped to SQLite auto-increment IDs, protecting existing ledger data from overwrites.

### M17: Jetpack Glance Home Screen Widgets & Quick Settings Tile (v1.9.0)
- `[x]` **Event-Driven Architecture**: Glance AppWidgets running with zero background polling wakeups (`updatePeriodMillis="0"`), updated exclusively via Room database invalidation and preference observers.
- `[x]` **Quick-Add Strip Widget (4×1 & 4×2)**: Instant 1-tap launchers for Expense, Income, and Transfer, with expanded mode displaying top bookmarked transaction presets.
- `[x]` **Safe-to-Spend & Daily Pulse Widget (2×2 & 4×2)**: Live discretionary spending cash, financial status tone (`SAFE`, `WARNING`, `CRITICAL`), liquidity breakdown, and upcoming card due date alerts.
- `[x]` **Privacy Eye Mask Toggle**: 1-tap interactive balance masking (`ToggleWidgetPrivacyAction`) with instance-level persistence and automatic sync with biometric lock preferences.
- `[x]` **Android Quick Settings Tile**: System pull-down shade tile (`FolioQuickAddTileService`) for instant transaction logging.
- `[x]` **Dynamic Theme & Picker Previews**: Day/Night RemoteViews preview layouts for Android 12+ widget picker and runtime theme synchronization with Folio's 6 theme modes and 12 accent palettes.

### M18: Google Play Billing Patron Tip Jar & In-Sheet Purchase Restoration (v1.9.0)
- `[x]` **Zero-Network In-App Purchases**: Integrates `billing-ktx` client via Android IPC Binder to Google Play Services, preserving release builds with zero internet permissions.
- `[x]` **Localized Pricing**: Dynamic regional pricing fetched from Google Play cache across 170+ countries.
- `[x]` **Patron SKUs**: In-app supporter products (`folio_tip_coffee`, `folio_tip_supporter`, `folio_tip_patron`).
- `[x]` **In-Sheet Purchase Restoration**: "Restore Purchases" action querying Google Play to automatically re-unlock supporter badges on new devices.

---

## 🔮 Upcoming Feature Milestones

### 1. Loan EMI Auto-Balancing Split & Principal/Interest Tracking
- `[ ]` **1-Tap Auto-Balancing Loan Transfer Split**:
  - When transferring from a bank account to an account in `AccountGroup.LOAN`, auto-calculate Principal paydown ($P = E - I$) from entered Interest ($I$), recording both Transfer and Expense atomically.
- `[ ]` **Amortization Schedule Preview**:
  - Embedded loan amortization schedule visualizer showing projected payoff dates and cumulative interest savings from extra principal payments.

### 2. Two-Tier Category Emoji & Custom Icon Resolver ("Infinite Icons")
- `[ ]` **Tier 1 — Reverse Emoji to Icon Pack Vector Mapping**:
  - When a user enters or pastes an emoji (e.g. 🍔, ☕, 🚗, ✈️, 🎮) in the Category Editor dialog, perform a reverse lookup to the matching semantic key (`fastfood`, `local_cafe`, `directions_car`, etc.).
  - Renders the corresponding vector icon in whatever active icon pack is selected (Material Filled, Phosphor Rounded, Lucide Outline).
- `[ ]` **Tier 2 — Direct Unicode Emoji Fallback**:
  - If the emoji has no direct vector equivalent (e.g. 🦄, 🛹, 🎸, 🪴, 🍣), store the emoji directly as the category's `iconName`.
  - Render the full-color Unicode emoji inside the category's tinted badge container using existing `CategoryIconBadge.kt` rendering.
- `[ ]` **Category Editor Quick-Picker**:
  - Provide an "Or Enter Any Emoji" input field and quick-picker in `CategoryEditorDialog.kt` alongside the icon grid with real-time preview.

### 3. FOSS Product Flavor & F-Droid Reproducible Build Support
- `[ ]` **`foss` vs. `play` Gradle Product Flavors**:
  - Decouple proprietary Google Play dependencies (`com.android.billingclient:billing-ktx`) into a `play` flavor while providing a clean `foss` flavor with a mock billing provider.
- `[ ]` **F-Droid & IzzyOnDroid Compatibility**:
  - Verify 100% reproducible builds adhering strictly to F-Droid Inclusion Policy (zero proprietary blobs, 100% open source dependencies).
- `[ ]` **Alternative Supporter Mechanisms**:
  - Provide offline donation links (e.g. GitHub Sponsors, LiberaPay) in the FOSS build in place of Google Play Billing.
