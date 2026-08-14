# Error Codes

> **Status:** Skeleton — initial code set. Codes are stable once released: new codes may
> be added, existing codes are never renamed, renumbered, or repurposed.

All failures surface as `UQPayError(code, message)`. Messages are human-readable,
actionable, and never contain sensitive data.

| Code | Meaning | Typical cause | Recommended handling |
|---|---|---|---|
| `NOT_INITIALIZED` | SDK used before `UQPay.initialize` | Missing init call | Programmer error — initialize in `Application.onCreate`. |
| `INVALID_CONFIGURATION` | Bad merchant ID / key / environment | Wrong or empty config values | Verify dashboard credentials and environment. |
| `INVALID_REQUEST` | Malformed `PaymentRequest` | Empty/invalid intent id or client secret | Fix backend intent creation. |
| `NETWORK_ERROR` | Network unreachable or dropped | Offline, DNS, TLS failure | Offer retry; payment state must be confirmed server-side. |
| `TIMEOUT` | Payment did not complete in time | Slow network, abandoned flow | Confirm final state via backend before retrying. |
| `AUTHENTICATION_FAILED` | Key rejected by UQPAY | Revoked/wrong key, env mismatch | Check key vs environment (sandbox key on production, etc.). |
| `PAYMENT_DECLINED` | Payment attempted and declined | Issuer decline, insufficient funds | Show user-friendly decline message; allow another method. |
| `USER_CANCELLED` | User abandoned the flow | Back press / cancel button | Not an error state to alarm on; surface as CANCELLED. |
| `SERVER_ERROR` | UQPAY-side failure | 5xx from gateway | Retry later; confirm state server-side. |
| `UNKNOWN` | Unclassified failure | Anything unexpected | Report to UQPAY support with `paymentIntentId`. |

## Rules

1. Every failure path maps to exactly one code — no raw exceptions escape to the host app.
2. `PaymentResult.error` is non-null iff `status == FAILED`.
3. Messages must be safe for logging (no PAN/CVV/keys/PII, ever).
