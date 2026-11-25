# Security Policy

## Zero-Network Architecture

Folio is built on a **100% offline, zero-network architecture**:

- Both `android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE` are explicitly stripped at manifest merge time via `tools:node="remove"` in [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml).
- No analytics, telemetry, crash-reporting SDKs, or remote network endpoints exist anywhere in the application.
- In-app Tip Jar billing interacts exclusively with Google Play Services via local Android IPC Binder (`com.android.vending.BILLING`) without app-level network access.
- All ledger records, categories, budgets, backups, and preferences stay strictly on-device in private application storage or user-selected Storage Access Framework (SAF) locations.

## Local Storage & Biometric Security

- **Database Protection:** Financial records are stored in local Room SQLite databases inside Android's private app sandbox.
- **Biometric Guard:** When biometric lock is enabled, `BiometricPrompt` authentication is enforced before accessing ledger balances, accounts, settlements, or transaction entry screens.
- **Privacy Masking:** Balances and widget summaries can be masked (`••••••`) across the app, lock screen notifications, and home screen widgets. When biometric lock is enabled, public backup mirroring is automatically disabled.
- **Authenticated Deep Links:** Home screen widgets and the Quick Settings tile route through `MainActivity` / `MainLockViewModel` so biometric authentication cannot be bypassed.

## Supported Versions

Only the latest release receives security fixes and updates:

| Version | Supported |
| :--- | :---: |
| `1.9.x` (Latest Play Store / GitHub Release) | ✅ |
| `< 1.9.0` | ❌ |

## Private Vulnerability Reporting

**Please do NOT open public GitHub Issues for security, backup, encryption, or biometric lock vulnerabilities.**

If you discover a security or privacy issue in Folio:
1. Report it privately using **[GitHub Security Advisories](https://github.com/SK-App-Labs/folio/security/advisories)** (*Repository → Security → Advisories → Report a vulnerability*) or contact the project maintainers directly via the support email listed on the Google Play Store listing.
2. Include clear reproduction steps, affected version, device model, and Android OS version.
3. Allow reasonable time for the maintainers to investigate, verify, and ship a fix before any public disclosure.
