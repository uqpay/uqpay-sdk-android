# UQPAY SDK for Android

[![CI](https://github.com/uqpay/uqpay-sdk-android/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/uqpay/uqpay-sdk-android/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.uqpay.sdk/uqpay-sdk-android)](https://central.sonatype.com/artifact/com.uqpay.sdk/uqpay-sdk-android)
![minSdk](https://img.shields.io/badge/minSdk-24-brightgreen)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF)
![License](https://img.shields.io/badge/license-MIT-blue)

Android Payment Gateway SDK for [UQPAY](https://uqpay.com).

> **Status: 0.1.0 on Maven Central** (published 2026-09-02).
>
> ```kotlin
> implementation("com.uqpay.sdk:uqpay-sdk-android:0.1.0")
> ```
>
> The payment flow is implemented end to end — card with 3-D Secure, wallet QR,
> bank-transfer instructions, persisted idempotency, rotation and process-death recovery —
> and verified by the unit and emulator suites in CI. Physical-device verification is still
> in progress; see the known limitations in [`CHANGELOG.md`](CHANGELOG.md).

## At a glance

- **One entry point.** `UQPay.initialize(...)`, then `UQPay.createPaymentLauncher(...)` in an
  Activity or a Fragment, then `launch(PaymentSessionParams(intentId))`. Four outcomes.
- **Themeable.** `UQPayAppearance` puts the sheet in your colours, as plain ARGB ints — no
  Compose type appears in the public API, so a Views app configures it the same way.
- **Sandbox sheets say so.** A test-mode badge the SDK draws itself and a merchant cannot
  switch off.
- **Small dependency graph.** No OkHttp, no Retrofit, no Gson, no analytics, no appcompat.
  The full dependency list is documented in the integration guide.
- **English only for now**, with every string overridable from your own app.

## Quick start

Your backend holds the `x-api-key`, mints the access token and creates the payment intent.
The app only ever sees a client id, a short-lived token and an intent id.

**1. Initialize once**, in your `Application`:

```kotlin
UQPay.initialize(
    context = this,
    configuration = UQPayConfiguration(
        clientId = "your-client-id",
        environment = Environment.SANDBOX, // Environment.PRODUCTION for live
        tokenProvider = {
            // Called off the main thread. Fetch the token from YOUR backend —
            // the x-api-key must never be in the app.
            val response = myBackend.fetchUqpayToken()
            UQPayAuthToken(response.token, response.expiresAtEpochMillis)
        },
    ),
)
```

**2. Create the launcher in `onCreate`** — unconditionally, on every creation, so a result
can be redelivered after process death — and **3. launch** with an intent id from your
backend:

```kotlin
class CheckoutActivity : ComponentActivity() {

    private lateinit var payments: UQPayPaymentLauncher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        payments = UQPay.createPaymentLauncher(this) { result ->
            when (result.status) {
                PaymentStatus.SUCCEEDED -> confirmWithBackend(result.paymentIntentId)
                PaymentStatus.FAILED    -> showError(result.error?.message)
                PaymentStatus.CANCELLED -> Unit
                PaymentStatus.PENDING   -> awaitWebhook(result.paymentIntentId)
            }
        }
    }

    private fun pay(paymentIntentId: String) {
        payments.launch(PaymentSessionParams(paymentIntentId))
    }
}
```

**4. Handle the four outcomes.** The result is advisory: the
`acquiring.payment_intent.succeeded` webhook on your backend is the only proof that money
moved. `PENDING` means the SDK stopped waiting, not that the payment failed — do not retry,
refund or release the order; wait for the webhook.

A Fragment works the same way (`createPaymentLauncher` takes any `ActivityResultCaller`),
and [`sample-app/`](sample-app) is a complete Views-based integration. The full
integration guide, API reference and error-code list are distributed separately — ask your
UQPAY contact, or [it@uqpay.com](mailto:it@uqpay.com).

## Modules

- **`uqpay-sdk/`** — the SDK library (published as `com.uqpay.sdk:uqpay-sdk-android`)
- **`sample-app/`** — reference integration app
- Merchant documentation (integration guide, API reference, error codes, testing,
  webhooks, troubleshooting, architecture) is distributed separately and is not part of
  this repository.

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
`CLAUDE.md` (local, not tracked) for project conventions, security rules, and the definition of
done. Every release must satisfy the project's acceptance criteria.

## Getting help

- **A bug in this SDK** — open a GitHub issue with the SDK version, device and Android
  version, and steps to reproduce.
- **Your UQPAY account, a payment, or credentials** — [it@uqpay.com](mailto:it@uqpay.com).
- **A security vulnerability** — please do not open a public issue. See
  [`SECURITY.md`](SECURITY.md).
