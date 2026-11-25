## Summary

Describe the problem solved or feature added, and link any related issue.

## Folio Invariants & Verification Checklist

- [ ] **Exact Integer Money (`MinorMoney`):** All financial math uses `Long` minor units (zero `Float`/`Double` currency math; `BigDecimal` with `RoundingMode.HALF_UP` only for division/FX/percentages).
- [ ] **Zero Network Permissions:** No `INTERNET` or `ACCESS_NETWORK_STATE` permissions, analytics, telemetry, or network SDKs introduced.
- [ ] **Immutable Opening Anchors:** Post-creation balance adjustments use `OPENING_BALANCE` / `RECONCILIATION` entries rather than mutating historical opening anchors.
- [ ] **Room Schema Export:** If database entities/schema changed, an explicit non-destructive `Migration` and exported JSON schema under `app/schemas/` are included.
- [ ] **Formatting & Tests:** `./gradlew spotlessCheck` and `./gradlew testDebugUnitTest` pass locally.
