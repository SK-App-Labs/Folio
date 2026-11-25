# Security Policy

## Offline Privacy Architecture

Folio is designed with an **offline-first, zero-network architecture**. Unlike cloud-connected financial applications:
- Release builds do not request or declare `android.permission.INTERNET` or `android.permission.ACCESS_NETWORK_STATE`.
- No analytics, telemetry, crash-reporting SDKs, or remote tracking endpoints exist in the application.
- All ledger data, categories, budgets, and user preferences remain strictly on the local device inside private application storage.

## Local Storage & Biometric Security

- **Database Protection:** Folio stores financial records in local Room SQLite databases. Database files reside in private app storage protected by Android's application sandbox and Linux filesystem permissions.
- **Biometric Guard:** When biometric lock is enabled, `BiometricPrompt` authentication is required before accessing ledger balances, accounts, or transaction entry screens.
- **Privacy Masking:** Discretionary balances, net worth values, and widget summaries can be masked (`••••••`) across the app and lock-screen to protect sensitive balances from shoulder surfing.
- **Safe-to-Spend Deep Links:** Widget deep links and Quick Settings tile actions route through authentication checkpoints to prevent unauthorized ledger access when app lock is active.

## Reporting a Vulnerability

If you discover a security vulnerability or privacy concern in Folio:
1. Please report it privately by opening a [Security Advisory](https://github.com/folio-ledger/folio/security/advisories) or by emailing the project maintainers.
2. Please provide detailed reproduction steps, the device model, Android OS version, and app version.
3. Allow reasonable time for the maintainers to review, reproduce, and release a fix before publishing details publicly.
