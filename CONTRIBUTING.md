# Contributing to Folio

Thanks for your interest in contributing to **Folio**! Pull requests, bug reports, and documentation improvements are always welcome.

---

## 5 Non-Negotiable Folio Invariants

To keep Folio accurate, private, and maintainable, every pull request must uphold these five invariants:

1. **`Long` Minor Currency Units (`MinorMoney`) Only:** Zero `Float` or `Double` financial math. Use `MinorMoney` (`Long` minor units) everywhere, reserving `BigDecimal` (`RoundingMode.HALF_UP`) strictly for division, FX rates, and percentages.
2. **Zero Network Permissions:** No `INTERNET` or `ACCESS_NETWORK_STATE` permissions, and no telemetry, analytics, or network SDKs.
3. **Immutable Opening Anchors:** Never rewrite an account's opening anchor after creation—use `OPENING_BALANCE` or `RECONCILIATION` transactions for balance adjustments.
4. **Jetpack Compose + Material 3:** Follow Material 3 design tokens and use `WhileSubscribed(5_000)` for `StateFlow` lifecycles in ViewModels.
5. **Explicit, Non-Destructive Room Migrations:** Every schema change must provide an explicit `Migration` path with exported JSON schemas in `app/schemas/` (no destructive fallbacks).

---

## Local Verification Commands

Run these checks locally before opening a pull request:

```bash
./gradlew spotlessCheck
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

> **Note:** `keystore.properties` is not required for local builds—Gradle automatically falls back to debug signing when it is absent locally.

---

## Submitting a Pull Request

1. Fork the repository and create a branch from `main` (e.g., `fix/...` or `feature/...`).
2. Add unit tests for any new domain logic, rules, or edge cases.
3. Ensure all verification commands above pass cleanly, then open a PR with a clear summary of your changes.
