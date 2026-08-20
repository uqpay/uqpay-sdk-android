# UQPAY SDK for Android

Android Payment Gateway SDK for [UQPAY](https://uqpay.com).

> **Status: pre-release.** The payment flow is implemented end to end — card with 3-D
> Secure, wallet QR, bank-transfer instructions, persisted idempotency, rotation and
> process-death recovery. What remains before 0.1.0 is human-gated; see
> [`CHANGELOG.md`](CHANGELOG.md).

## At a glance

- **One entry point.** `UQPay.initialize(...)`, then `UQPay.createPaymentLauncher(...)` in an
  Activity or a Fragment, then `launch(PaymentSessionParams(intentId))`. Four outcomes.
- **Themeable.** `UQPayAppearance` puts the sheet in your colours, as plain ARGB ints — no
  Compose type appears in the public API, so a Views app configures it the same way.
- **Sandbox sheets say so.** A test-mode badge the SDK draws itself and a merchant cannot
  switch off.
- **Small dependency graph.** No OkHttp, no Retrofit, no Gson, no analytics, no appcompat.
  The full list is in the [integration guide](docs/integration-guide.md#what-this-sdk-depends-on).
- **English only for now**, with every string overridable from your own app — see
  [Localisation](docs/integration-guide.md#localisation).

## Modules

- **`uqpay-sdk/`** — the SDK library (published as `com.uqpay:uqpay-sdk-android`)
- **`sample-app/`** — reference integration app
- **`docs/`** — [integration guide](docs/integration-guide.md) ·
  [API reference](docs/api-reference.md) · [error codes](docs/error-codes.md) ·
  [testing](docs/testing.md) · [webhooks](docs/webhooks.md) ·
  [troubleshooting](docs/troubleshooting.md) ·
  [acceptance criteria](docs/acceptance-criteria.md) ·
  [release process](docs/release-process.md) · [architecture](docs/architecture.md)

## Building

```bash
./gradlew :uqpay-sdk:assembleRelease   # build the SDK AAR
./gradlew :uqpay-sdk:test              # unit tests
./gradlew :sample-app:installDebug     # run the sample app
```

## Requirements

- Android `minSdk 24`, `compileSdk 35`
- JDK 17+

## Contributing

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) for how to raise a change, and
[`CLAUDE.md`](CLAUDE.md) for project conventions, security rules, and the definition of
done. Every release must satisfy
[`docs/acceptance-criteria.md`](docs/acceptance-criteria.md).

## Getting help

- **A bug in this SDK** — open a GitHub issue. What to include:
  [troubleshooting.md](docs/troubleshooting.md#getting-help).
- **Your UQPAY account, a payment, or credentials** — [it@uqpay.com](mailto:it@uqpay.com).
- **A security vulnerability** — please do not open a public issue. See
  [`SECURITY.md`](SECURITY.md).
