# Troubleshooting

> **Status:** Skeleton — will grow as real-world issues are triaged.

## Build / integration issues

**Dependency not resolving**
- Confirm the repository hosting the artifact is declared in `settings.gradle.kts`
  (`dependencyResolutionManagement.repositories`).
- Confirm the version exists (see CHANGELOG / release notes).

**Manifest merger conflicts**
- The SDK declares `INTERNET` permission and its internal payment Activity. If your app
  overrides merged attributes, check the merger report at
  `app/build/outputs/logs/manifest-merger-*-report.txt`.

**Minified (R8/ProGuard) build breaks payment flow**
- Consumer rules ship inside the AAR and should apply automatically. If you maintain
  aggressive custom rules, ensure `com.uqpay.sdk.**` model classes are not stripped.

## Runtime issues

**`IllegalStateException: UQPay is not initialized`**
- Call `UQPay.initialize(...)` in `Application.onCreate` before any payment API.

**`AUTHENTICATION_FAILED` in sandbox**
- Sandbox keys only work with `Environment.SANDBOX` (and vice-versa for production).

**No callback received**
- The callback fires exactly once per payment. If you re-registered callbacks manually
  across rotation, remove that — the SDK handles configuration changes itself.

**Payment shows TIMEOUT but money moved**
- The client result is not the source of truth. Always confirm the final payment state
  from your backend (UQPAY server API / webhooks) before fulfilling or refunding.

## Getting help

Include: SDK version (`UQPay.version`), Android version, device model, `paymentIntentId`,
and the `UQPayErrorCode`. **Never include card data, keys, or customer PII in reports.**
