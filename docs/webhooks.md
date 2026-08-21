# Webhooks & Reconciliation

The integration guide, the API reference and the architecture notes all say the same thing:
**the SDK's result is advisory, and the webhook is the authority.** This page is the other
half of that sentence — what the webhook is, how to verify it, how it lines up with the
SDK's callback, and what to do when the two disagree.

Webhooks are delivered to **your backend**. The Android app never receives one, and cannot:
a device that is asleep, uninstalled or out of signal is not a delivery target for the
outcome of a payment that already happened.

> **Scope.** This page covers reconciling a webhook against an SDK result. For the
> field-level payload schema, the endpoint configuration UI and the full event catalogue,
> UQPAY's own webhook reference is the source of truth — see "What this page does not
> cover" at the bottom.

---

## Why the client result cannot be the authority

The SDK reports what it observed from a phone. Four things it cannot observe:

- **The customer paid and the phone died** — in the wallet app, on the bank's OTP page, in
  the second between the issuer approving and the poller reading. The SDK reports `PENDING`.
  The money moved.
- **The poll window closed.** Ten minutes of QR polling is generous; a customer who wandered
  off and came back at minute twelve still paid.
- **The customer force-quit the app** after the confirm left the device.
- **The intent expired 30 minutes after creation** with no completion — a state change that
  happens entirely server-side, with no client anywhere.

In every one of those, the webhook is the only thing that knows. That is why `PENDING`
exists as a status and why there is no `TIMEOUT`.

---

## Verifying a delivery

Deliveries are HTTPS POSTs with a JSON body and two signature headers:

| Header | Contents |
|---|---|
| `x-wk-timestamp` | 13-digit Unix timestamp in **milliseconds** |
| `x-wk-signature` | HMAC-SHA512 hex digest over the **raw body concatenated with the timestamp**, keyed with your endpoint's signing secret |

**Verify against the raw bytes, before any parsing or reformatting.** A JSON round-trip
reorders keys, changes number formatting and normalises whitespace — any one of which
breaks the digest. Read the body as a string once, verify, then parse that same string.

```
signature == hex(hmac_sha512(secret, raw_body + x_wk_timestamp))
```

Reject a delivery whose signature does not match, and reject one whose timestamp is far
outside your clock skew tolerance — a valid signature on a replayed old body is still a
replay.

**Compare digests in constant time** (`MessageDigest.isEqual`, `hmac.compare_digest`,
`crypto.timingSafeEqual`). A plain string comparison leaks the correct prefix.

### Source IP allowlist

| Environment | Addresses |
|---|---|
| Sandbox | `52.76.137.90`, `52.221.8.28` |
| Production | `18.143.59.64`, `54.179.248.205`, `13.250.234.88`, `18.136.58.213`, `56.10.39.6` |

An allowlist is a firewall control, not an authentication one. **It does not replace
signature verification** — verify every delivery regardless of where it came from.

### Retries

An unreachable or slow endpoint is retried up to **5 times** with exponential backoff of
`2^(attempts+3)` seconds — 16s, 32s, 64s, … capped at 900s (15 minutes).

Two consequences for how you write the handler:

- **Acknowledge fast.** Return `200` as soon as the delivery is stored, then do the work.
  A handler that fulfils an order inline before responding will be retried while it is
  still running, and you will process the same event twice.
- **Dedupe on `event_id`.** Retries mean at-least-once delivery, never exactly-once.
- **Tolerate out-of-order arrival.** A retried `requires_action` can land after the
  `succeeded` it precedes. Order your state machine on the intent's status, not on
  arrival order.

---

## The events that matter

| Event | Meaning |
|---|---|
| `acquiring.payment_intent.created` | Intent created. |
| `acquiring.payment_intent.requires_action` | Needs customer action — 3-D Secure or a QR scan. |
| `acquiring.payment_intent.succeeded` | **Terminal success. This is the one you fulfil on.** |
| `acquiring.payment_intent.failed` | Terminal failure — mainly the 30-minute auto-expiry. |
| `acquiring.payment_attempt.created` | A new attempt started on the intent. |
| `acquiring.payment_attempt.capture_requested` | Capture submitted — **treat as a success signal.** |
| `acquiring.payment_attempt.cancelled` | This attempt was cancelled or expired. |
| `acquiring.payment_attempt.failed` | **This attempt** failed. The intent may still be retryable — this is not an order failure. |
| `acquiring.cancel.succeeded` / `.failed` | Outcome of a cancellation you requested. |
| `acquiring.refund.created` / `.succeeded` / `.failed` | Refund lifecycle. |

**Intent events decide an order. Attempt events do not.** A customer whose first card is
declined and whose second succeeds generates `payment_attempt.failed` followed by
`payment_intent.succeeded`. A backend that fails the order on the attempt event cancels a
paid order.

---

## Matching a webhook to an SDK result

The SDK hands you two identifiers on every `PaymentResult`:

| SDK field | Webhook field | Notes |
|---|---|---|
| `paymentIntentId` | `payment_intent_id` | **The join key.** Stable for the whole payment, present on every event. |
| `transactionId` | `payment_attempt_id` | The attempt id. Null until an attempt exists. |

Two naming traps that have cost time before:

- **The attempt id has two names.** It is `attempt_id` when nested inside an intent
  response, and `payment_attempt_id` in webhook payloads. The SDK decodes both, so
  `transactionId` matches whichever the gateway sent — but if you are parsing raw gateway
  JSON yourself, handle both spellings or you will match nothing.
- **There is no `id` key on an intent.** `GET /payment_intents/{id}` returns
  `payment_intent_id`. Code that reads `id` gets null, quietly, forever.

**Join on `paymentIntentId`, and make fulfilment idempotent on it.** You will legitimately
see the same `paymentIntentId` more than once: two `launch` calls for the same intent — a
customer double-tapping Pay — are *one* payment but *two* callbacks, and the webhook arrives
independently of both. "Already fulfilled" has to be a no-op, and only your system knows
what fulfilled means for an order.

Do not use `traceId` for this. It is always null today — see
[error-codes.md](error-codes.md#declinecode-and-traceid).

---

## When the SDK and the webhook disagree

Both signals are normal, they arrive in either order, and they are not equally trustworthy.

| SDK callback says | Webhook says | What to do |
|---|---|---|
| `SUCCEEDED` | nothing yet | **Hold.** Do not fulfil on the callback alone. The callback routinely arrives first; give the webhook its delivery window. |
| `SUCCEEDED` | `payment_intent.succeeded` | Fulfil. |
| `PENDING` | nothing yet | **Wait.** Keep the order open. Do not retry, do not refund, do not release. |
| `PENDING` | `payment_intent.succeeded` | Fulfil. This is the designed path, not an anomaly. |
| `PENDING` | `payment_intent.failed` | Fail the order. |
| `SUCCEEDED` | `payment_intent.failed` | **Webhook wins — do not fulfil.** |
| `FAILED` / `CANCELLED` | `payment_intent.succeeded` | **Webhook wins — money moved.** Fulfil, or refund deliberately. Never silently ignore it. |
| `CANCELLED` | nothing, ever | Nothing happened. Not an error, not an alarm. |

The rule underneath the table: **the webhook can only be contradicted by a later webhook.**
The SDK result is a hint that lets you show the customer something immediately; the webhook
is what your ledger is allowed to believe.

### The reconciliation sweep you still need

Webhooks are delivered, and delivery can fail — five retries then silence, an endpoint that
was down for an hour, a signature that failed verification because a secret was rotated
mid-flight.

Run a periodic sweep over intents your system created and has not resolved: for anything
older than the 30-minute expiry window with no terminal event, ask your backend to retrieve
the intent and settle it from the gateway's own answer. Without that sweep, one missed
delivery is one order that stays open forever.

---

## A minimal handler

```
POST /uqpay/webhook

1. Read the raw body as bytes. Do not parse yet.
2. Verify x-wk-signature over (raw_body + x_wk_timestamp), constant-time.
   Mismatch → 401, log, stop.
3. Reject a timestamp outside your skew window → 401.
4. Parse. If event_id has been seen → 200, stop.       # dedupe
5. Persist the raw event.                              # audit trail
6. Return 200.                                         # ack before the work
7. Asynchronously: look up the order by payment_intent_id,
   apply the state change if it is not already applied. # idempotent
```

Steps 6 and 7 in that order are what keeps a retry from double-fulfilling.

---

## What this page does not cover

The **field-level schema of the `data` object** in `paymentintent-result` and
`paymentattempt-result` payloads. What we have confirmed is the envelope —
`event_id`, `event_name`, `event_type`, `source_id`, `version`, `data` — and the identifier
names in the table above. The rest of `data` has not been verified against a live delivery
by us, so this page does not describe it rather than guessing. Use UQPAY's webhook reference
for the payload, and treat any field this page does not name as something to confirm before
you depend on it.

---

## Next steps

- [Testing](testing.md) — how to produce each of these events in the sandbox
- [Integration Guide](integration-guide.md#pending-is-the-one-to-get-right)
- [Error Codes](error-codes.md)
