# Integration Guide

> **Status:** the public API below is final and compiles. The payment flow itself is
> still being built — a launched payment currently finishes as `CANCELLED` without
> submitting anything. Integrate against this API now; it will not change under you.

## Requirements

- Android `minSdk 24` (Android 7.0) or higher
- Kotlin or Java host app
- Internet permission (declared by the SDK's manifest, merged automatically)

## How a UQPAY payment works

Three parties, and the split matters:

| | Holds | Does |
|---|---|---|
| **Your backend** | `x-api-key`, `x-client-id` | Mints access tokens, creates payment intents, receives webhooks |
| **Your app + this SDK** | `clientId`, a short-lived access token, a `paymentIntentId` | Collects payment details, drives 3-D Secure and wallet flows |
| **UQPAY** | — | Authorises, captures, and tells your backend what happened |

The SDK result is **advisory**. Your `acquiring.payment_intent.succeeded` webhook is the
only authority on whether money moved. Confirm server-side before you fulfil an order.

## 1. Add the dependency

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.uqpay:uqpay-sdk-android:<latest-version>")
}
```

## 2. Serve access tokens from your backend

Your backend mints a token from `x-client-id` + `x-api-key` and exposes it to your app
over your own authenticated channel.

> ### ⚠️ Cache the token. Do not mint one per payment.
>
> **UQPAY permits exactly one active access token per merchant — minting a new one
> invalidates the previous one.** A backend that mints a token per checkout will
> invalidate the token every other customer's device is holding, and its own. Mint once,
> cache it, share it, and refresh only when it is close to expiry (tokens last about 30
> minutes).
>
> The `x-api-key` must never reach the app. It can issue refunds and payouts.

## 3. Initialize the SDK

Once, in your `Application`:

```kotlin
UQPay.initialize(
    context = this,
    configuration = UQPayConfiguration(
        clientId = "your-client-id",
        environment = Environment.SANDBOX,   // Environment.PRODUCTION for live
        tokenProvider = {
            // Called off the main thread; may block. Fetch from YOUR backend.
            val response = myBackend.fetchUqpayToken()
            UQPayAuthToken(
                value = response.token,
                expiresAtEpochMillis = response.expiresAtEpochMillis,
            )
        },
    ),
)
```

`initialize` stores the configuration and does nothing else — no network, no disk, no
background work — so it cannot affect your cold start.

## 4. Create the launcher in `onCreate`

```kotlin
class CheckoutActivity : AppCompatActivity() {

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
}
```

> ### ⚠️ Create the launcher **unconditionally**, on every Activity creation
>
> Not on a button tap. Not inside `if (savedInstanceState == null)`.
>
> If Android kills your process while the customer is completing 3-D Secure, your
> Activity is recreated in a **new process** and the result is redelivered to whatever
> launcher has re-registered by then. Registering conditionally means there is nothing to
> deliver to, and the payment result is lost silently — the worst failure mode a payment
> SDK has. Registration is cheap; do it every time.

## 5. Start a payment

Your backend creates the intent and gives your app the id:

```kotlin
payments.launch(PaymentSessionParams(paymentIntentId = "PI_xxx"))
```

Skip the method list if you want a specific screen:

```kotlin
PaymentSessionParams("PI_xxx", PaymentSessionParams.Presentation.CardOnly)
PaymentSessionParams("PI_xxx", PaymentSessionParams.Presentation.SingleWallet(PaymentMethodType.GRABPAY))
```

Payment intents auto-expire **30 minutes** after creation. You do not need to disable
your pay button — the SDK guards against double submission itself.

## 6. Handle the four outcomes

| Status | What it means | What to do |
|---|---|---|
| `SUCCEEDED` | The payment succeeded. | Confirm server-side, then fulfil. |
| `FAILED` | Definitively failed. `error` is non-null. | Show `error.message`; offer another method. |
| `CANCELLED` | The customer left with **nothing submitted**. | Nothing. Not an error. |
| `PENDING` | **The payment may still be live.** | Wait for the webhook. |

### `PENDING` is the one to get right

There is no `TIMEOUT` status, deliberately. If a QR or 3-D Secure step runs out of time,
the customer may have completed the payment in their banking or wallet app moments
earlier. Reporting that as a failure invites you to charge them a second time.

`PENDING` also covers the case where the customer dismissed the sheet while a
confirmation was in flight — cancelling a request does not un-charge a card.

**On `PENDING`: stop your spinner, do not retry, do not refund, do not release the
order.** Wait for the webhook. The SDK keeps reconciling briefly and may still deliver a
`SUCCEEDED` or `FAILED` result afterwards.

## Callback contract

- Invoked **exactly once** per payment, on the **main thread**.
- Delivered across configuration changes and process death.
- Never proof of payment on its own — always confirm via your backend.

## Java

The API is Java-friendly: `UQPay.initialize(...)`, `UQPay.createPaymentLauncher(...)`,
and `PaymentSessionParams` all work without Kotlin. `PaymentCallback` is a single-method
interface, so a lambda works in Java 8+.

## Sample app

See [`sample-app/`](../sample-app). Put sandbox credentials in `local.properties`
(gitignored) and read them through `BuildConfig` — never commit them.

## Next steps

- [API Reference](api-reference.md)
- [Error Codes](error-codes.md)
- [Troubleshooting](troubleshooting.md)
