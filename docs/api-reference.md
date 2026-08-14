# API Reference

> **Status:** Skeleton — declarations exist as stubs; behavior notes describe the
> intended contract. Keep this file in sync with every public API change.

Package root: `com.uqpay.sdk`

## `UQPay` (object)

Single public entry point.

| Member | Description |
|---|---|
| `initialize(context, configuration)` | One-time SDK initialization. Cheap; safe to call from `Application.onCreate`. Calling payment APIs before this throws `IllegalStateException`. |
| `startPayment(activity, request, callback)` | Launches the payment flow for a payment intent created by the merchant backend. |
| `isInitialized` | Whether the SDK has been initialized. |
| `version` | SDK version string. |

## `UQPayConfiguration`

Immutable configuration passed to `initialize`.

| Property | Type | Notes |
|---|---|---|
| `merchantId` | `String` | UQPAY merchant identifier. |
| `publishableKey` | `String` | Client-safe key. Secret keys must never be embedded in an app. |
| `environment` | `Environment` | `SANDBOX` or `PRODUCTION`. |

## `Environment` (enum)

`SANDBOX`, `PRODUCTION`.

## `payment` package

### `PaymentRequest`
Immutable description of the payment to run: `paymentIntentId`, `clientSecret`.
Intents are created server-side by the merchant backend; the app never holds amounts as
the source of truth.

### `PaymentCallback` (fun interface)
`onResult(result: PaymentResult)` — invoked exactly once per payment, main thread.

### `PaymentResult`
`status: PaymentStatus`, `paymentIntentId: String`, `error: UQPayError?` (non-null only
for `FAILED`).

### `PaymentStatus` (enum)
`SUCCESS`, `FAILED`, `CANCELLED`, `TIMEOUT`.

## `error` package

### `UQPayError`
`code: UQPayErrorCode`, `message: String`. Messages are safe to show in logs — they never
contain sensitive data.

### `UQPayErrorCode` (enum)
Stable, documented codes — see [error-codes.md](error-codes.md). Codes are never
renamed or renumbered after release.

## Internal packages (not part of the public API)

- `com.uqpay.sdk.network` — HTTP layer. Internal; may change without notice.
- `com.uqpay.sdk.ui` — payment Activity/UI. Internal; launched only via `UQPay.startPayment`.
