# UQPAY SDK for Android

Android Payment Gateway SDK for [UQPAY](https://uqpay.com).

> **Status: skeleton** — project structure and public API surface only; no payment
> logic implemented yet.

## Modules

- **`uqpay-sdk/`** — the SDK library (published as `com.uqpay:uqpay-sdk-android`)
- **`sample-app/`** — reference integration app
- **`docs/`** — [integration guide](docs/integration-guide.md) ·
  [API reference](docs/api-reference.md) · [error codes](docs/error-codes.md) ·
  [troubleshooting](docs/troubleshooting.md) ·
  [acceptance criteria](docs/acceptance-criteria.md) ·
  [release process](docs/release-process.md) · [architecture](docs/architecture.md)

## Building

The Gradle wrapper is not committed yet. Generate it once (requires a local Gradle
install, e.g. `brew install gradle`):

```bash
gradle wrapper --gradle-version 8.12
```

Then:

```bash
./gradlew :uqpay-sdk:assembleRelease   # build the SDK AAR
./gradlew :uqpay-sdk:test              # unit tests
./gradlew :sample-app:installDebug     # run the sample app
```

## Requirements

- Android `minSdk 24`, `compileSdk 35`
- JDK 17+

## Contributing

Read [`CLAUDE.md`](CLAUDE.md) for project conventions, security rules, and the
definition of done. Every release must satisfy
[`docs/acceptance-criteria.md`](docs/acceptance-criteria.md).
