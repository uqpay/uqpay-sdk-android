# API Reference

> **Status:** the public surface below is declared and compiles. The payment engine,
> card form, 3-D Secure, and wallet QR flows land in later slices — until then a launched
> payment finishes as `CANCELLED` with nothing submitted.
>
> Keep this file in sync with every public API change, in the same change.

Package root: `com.uqpay.sdk`. Everything not listed here is `internal` and may change
without notice.

## `UQPay` (object)

Single public entry point.

| Member | Description |
|---|---|
| `initialize(context, configuration)` | One-time initialization. Stores the configuration and nothing else — no network, no disk, no background work — so it is safe in `Application.onCreate`. Calling again replaces the configuration. |
| `createPaymentLauncher(activity, callback): UQPayPaymentLauncher` | Registers result delivery for `activity`. **Must be called unconditionally in `onCreate`, before the Activity is STARTED.** Throws `IllegalStateException` if the SDK is not initialized. |
| `isInitialized` | Whether `initialize` has been called. |
| `version` | SDK version string. |

### Why a launcher rather than `startPayment(activity, request, callback)`

A callback object passed at launch cannot survive process death — the heap dies with the
process, and stashing it in a static both leaks the Activity and still loses the result.
Registering through `ActivityResultContract` means the request and the result travel as
parcels through the OS, and `ActivityResultRegistry` redelivers a pending result to the
callback that the recreated Activity re-registers. The callback never needs to survive;
it is recreated.

This is why the registration must be unconditional. Creating the launcher lazily — on a
button tap, or only when `savedInstanceState == null` — breaks redelivery after process
death.

## `UQPayConfiguration`

Immutable configuration passed to `initialize`.

| Property | Type | Notes |
|---|---|---|
| `clientId` | `String` | The merchant's `x-client-id`, supplied by your backend. |
| `environment` | `Environment` | No default, no silent fallback. |
| `tokenProvider` | `UQPayTokenProvider` | Supplies short-lived access tokens. |

There is **no publishable key** — UQPAY has no such credential.

## `Environment` (enum)

`SANDBOX`, `PRODUCTION`. Tokens and intents are environment-specific and never
interchangeable.

## `auth` package

### `UQPayTokenProvider` (fun interface)

`fetchToken(): UQPayAuthToken` — called off the main thread; may block. The SDK calls it
when it has no cached token, when the cached one nears expiry, and once after a `401`.

The merchant's `x-api-key` must never enter the app: it can issue refunds and payouts.
UQPAY also permits only **one active access token per merchant** — minting a new one
invalidates the previous one, so a backend must cache and share a single token rather
than minting one per payment, and the app must never mint its own.

### `UQPayAuthToken`

`value: String`, `expiresAtEpochMillis: Long`. `toString()` redacts the value.

## `payment` package

### `UQPayPaymentLauncher` (interface)

`launch(params: PaymentSessionParams)` — starts a payment. Safe to call repeatedly over
the host Activity's life. A double-tap cannot start two payments; the SDK guards the
submission itself. If the host is no longer active the failure is delivered through the
callback rather than thrown.

### `PaymentSessionParams`

| Property | Type | Notes |
|---|---|---|
| `paymentIntentId` | `String` | Created by your backend (`PI…`). Intents auto-expire after 30 minutes. |
| `presentation` | `Presentation` | Defaults to `MethodList`. |

`Presentation` is a sealed class: `MethodList` (every method the intent offers, gateway
order, card first), `CardOnly`, and `SingleWallet(method)`.

### `PaymentCallback` (fun interface)

`onResult(result: PaymentResult)` — invoked **exactly once** per payment, on the **main
thread**, across configuration changes and process death.

### `PaymentResult`

| Property | Type | Notes |
|---|---|---|
| `status` | `PaymentStatus` | |
| `paymentIntentId` | `String` | |
| `paymentMethodType` | `PaymentMethodType?` | |
| `amount` | `BigDecimal?` | **Major units** (`8.98`). Binary floating point is not a money type. |
| `currency` | `String?` | ISO 4217. |
| `merchantOrderId` | `String?` | |
| `transactionId` | `String?` | The payment attempt id. |
| `completedAtEpochMillis` | `Long?` | Unix ms. A primitive, so the SDK needs no core library desugaring at `minSdk 24`. |
| `error` | `UQPayError?` | Non-null on `FAILED`; also set on `PENDING` to carry why the SDK stopped waiting. |

The result is **advisory**. The `acquiring.payment_intent.succeeded` webhook is the
authority; confirm server-side before fulfilling an order.

### `PaymentStatus` (enum)

`SUCCEEDED`, `FAILED`, `CANCELLED`, `PENDING`.

There is deliberately **no `TIMEOUT`**. A poll window closing is not an outcome — the
customer may have paid in their banking or wallet app moments earlier. Reporting such a
payment as failed invites a duplicate charge, so it is reported as `PENDING` carrying
`UQPayErrorCode.TIMEOUT`.

`CANCELLED` means the customer left with **no attempt in the air**. A dismissal while a
confirmation is in flight is `PENDING`, never `CANCELLED`: cancelling a request does not
un-charge a card.

### `PaymentMethodType`

An **open set**, not an enum. Constants: `CARD`, `WECHAT_PAY`, `ALIPAY_CN`, `ALIPAY_HK`,
`GRABPAY`, `PAYNOW`, `UNIONPAY`, `TRUEMONEY`, `TNG`, `GCASH`, `DANA`, `KAKAOPAY`,
`TOSSPAY`, `NAVERPAY`; plus `of(raw)`. Methods come from the intent's
`available_payment_method_types`, so UQPAY can enable new ones without an SDK release. A
method this SDK version cannot render is hidden from the customer, never an error.

## `error` package

### `UQPayError`

`code: UQPayErrorCode`, `message: String`, `declineCode: String?`, `traceId: String?`.

`message` is **always safe to log** — never a PAN, CVV, expiry, token, or PII. In
`PRODUCTION` it is a fixed message per code; in `SANDBOX` the gateway's detail is
appended. Quote `traceId` in support tickets.

### `UQPayErrorCode`

An **open set**, not an enum, so adding a code is never source-breaking for merchants
who `when` over it. Compare against the constants and always provide an `else` branch.

Constants: `NOT_INITIALIZED`, `INVALID_CONFIGURATION`, `INVALID_REQUEST`,
`INVALID_PAYMENT_METHOD`, `NETWORK_ERROR`, `TIMEOUT`, `AUTHENTICATION_FAILED`,
`CARD_DECLINED`, `INSUFFICIENT_FUNDS`, `THREE_DS_FAILED`, `CANCELLED`,
`INTENT_NOT_PAYABLE`, `SERVER_ERROR`, `UNKNOWN`; plus `of(raw)`, which preserves codes
this SDK version predates. See [error-codes.md](error-codes.md).

## Internal packages (not part of the public API)

- `com.uqpay.sdk.launcher` — the `ActivityResultContract` and launcher implementation.
- `com.uqpay.sdk.network` — HTTP layer.
- `com.uqpay.sdk.ui` — the payment Activity, launched only via `createPaymentLauncher`.
