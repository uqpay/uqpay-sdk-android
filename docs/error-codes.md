# Error Codes

> Codes are stable once released: new codes may be added, existing codes are never
> renamed or repurposed.

All failures surface as `UQPayError(code, message, declineCode, traceId)` inside a
`PaymentResult` — the SDK never throws for a payment outcome. `message` is
human-readable and always safe to log.

## `UQPayErrorCode` is an open set, not an enum

Branch on it with an `else`:

```kotlin
when (error.code) {
    UQPayErrorCode.CARD_DECLINED -> promptForAnotherCard()
    UQPayErrorCode.NETWORK_ERROR -> offerRetry()
    else -> showGenericFailure(error.message)
}
```

UQPAY can introduce a failure code at any time. If this were an enum, an exhaustive
`when` in your app would stop compiling the day we added one. Codes we do not recognise
reach you verbatim via `UQPayErrorCode.of(raw)` rather than being flattened into
`UNKNOWN`, so you can still log and branch on them.

## Codes

| Code | Wire value | Meaning | Recommended handling |
|---|---|---|---|
| `NOT_INITIALIZED` | `not_initialized` | A payment API was used before `UQPay.initialize`. | Programmer error — initialize in `Application.onCreate`. |
| `INVALID_CONFIGURATION` | `invalid_configuration` | The request never left the device: missing environment, token, or intent id. | Check your configuration. Never pins an idempotency attempt. |
| `INVALID_REQUEST` | `invalid_request` | The gateway rejected the request as malformed. | Fix backend intent creation. |
| `INVALID_PAYMENT_METHOD` | `invalid_payment_method` | The method is not enabled for this account, currency, or country. | Offer a different method; contact UQPAY to enable it. |
| `NETWORK_ERROR` | `network_error` | No response was received. | Offer retry. Confirm final state server-side. |
| `TIMEOUT` | `timeout` | The SDK stopped waiting. **Only ever carried on a `PENDING` result.** | The payment may still be live. Wait for the webhook — do not retry or release the order. |
| `AUTHENTICATION_FAILED` | `authentication_failed` | The access token was missing, malformed, or expired. | Check your token provider. Remember UQPAY allows one active token per merchant. |
| `CARD_DECLINED` | `card_declined` | The issuer or acquirer declined. | Show a decline message; allow another method. |
| `INSUFFICIENT_FUNDS` | `insufficient_funds` | Declined for insufficient funds. | As above, with specific copy. |
| `THREE_DS_FAILED` | `3ds_failed` | 3-D Secure failed or was abandoned. | Allow a retry or another method. |
| `CANCELLED` | `cancelled` | The customer abandoned the flow. | Not an alarm condition. |
| `INTENT_NOT_PAYABLE` | `intent_not_payable` | The intent is already settled, cancelled, or expired. | Create a new intent. Do not re-present the old one. |
| `SERVER_ERROR` | `server_error` | UQPAY could not process an otherwise valid request. | Retry with backoff; confirm state server-side. |
| `UNKNOWN` | `unknown` | Unclassified. | Report to UQPAY support with `traceId` and `paymentIntentId`. |

## How gateway errors map

The gateway's `code` is checked **before** the HTTP status, because it is far more
specific. An unrecognised code falls through to the status and is **never** reported as
`CARD_DECLINED` — telling a merchant a card was refused when the request was actually
malformed or unauthenticated is a real bug that shipped on another platform.

| HTTP status (fallback only) | Code |
|---|---|
| 401, 403 | `AUTHENTICATION_FAILED` |
| 402 | `CARD_DECLINED` |
| 400, 404, 422 | `INVALID_REQUEST` |
| 429, 5xx | `SERVER_ERROR` |
| anything else | `UNKNOWN` |

For an intent that has settled badly, the attempt's `failure_code` decides: `3ds_failed`
→ `THREE_DS_FAILED`, `insufficient_funds` → `INSUFFICIENT_FUNDS`, anything else →
`CARD_DECLINED`. A **cancelled** intent is always `CANCELLED`, whatever the failure code
says — the customer's action outranks the last attempt's report.

## `declineCode` and `traceId`

- `declineCode` is the acquirer's raw reason (`do_not_honor`, …), when one was supplied.
  Use it for analytics, not for control flow — branch on `code`.
- `traceId` is the gateway's request id. Quote it in support tickets. It contains no
  sensitive data and is safe to log and display.

## Rules

1. Every failure path maps to exactly one code — no raw exception escapes to the host app.
2. All payment methods share one mapping function, so the same server response always
   yields the same code no matter which screen produced it. A table test asserts every
   declared code is reachable from at least one real path.
3. `PaymentResult.error` is non-null when `status == FAILED`, and is also populated on
   `PENDING` to carry why the SDK stopped waiting.
4. Messages are safe for logging — no PAN, CVV, expiry, token, or PII, ever. In
   `PRODUCTION` the message is fixed per code; in `SANDBOX` the gateway's detail is
   appended for debugging. The machine code never appears in the sentence a customer reads.
