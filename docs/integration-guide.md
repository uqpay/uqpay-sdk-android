# Integration Guide

> **Status:** Skeleton — the API shown here is the planned public surface. Implementation
> is not yet complete; snippets will be finalized as the SDK is built.

## Requirements

- Android `minSdk 24` (Android 7.0) or higher
- Kotlin or Java host app
- Internet permission (declared by the SDK's manifest, merged automatically)

## 1. Add the dependency

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.uqpay:uqpay-sdk-android:<latest-version>")
}
```

## 2. Initialize the SDK

Initialize once, typically in your `Application` class:

```kotlin
UQPay.initialize(
    context = this,
    configuration = UQPayConfiguration(
        merchantId = "your-merchant-id",
        publishableKey = "your-publishable-key",   // never ship secret keys in the app
        environment = Environment.SANDBOX,          // Environment.PRODUCTION for live
    )
)
```

## 3. Start a payment

```kotlin
val request = PaymentRequest(
    paymentIntentId = "pi_xxx",       // created by YOUR backend via UQPAY server API
    clientSecret = "secret_xxx",
)

UQPay.startPayment(activity, request, object : PaymentCallback {
    override fun onResult(result: PaymentResult) {
        when (result.status) {
            PaymentStatus.SUCCESS   -> { /* payment completed */ }
            PaymentStatus.FAILED    -> { /* show result.error */ }
            PaymentStatus.CANCELLED -> { /* user cancelled */ }
            PaymentStatus.TIMEOUT   -> { /* payment timed out */ }
        }
    }
})
```

### Callback contract

- `onResult` is invoked **exactly once** per payment, on the **main thread**.
- The callback survives configuration changes (rotation) and process death.
- Never treat the SDK result alone as proof of payment — always confirm the final
  payment state from your backend via UQPAY's server-side API/webhooks.

## 4. Sample app

See [`sample-app/`](../sample-app) for a complete integration example. Put your sandbox
keys in `local.properties` (gitignored) — never commit keys.

## Next steps

- [API Reference](api-reference.md)
- [Error Codes](error-codes.md)
- [Troubleshooting](troubleshooting.md)
