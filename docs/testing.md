# Testing

How to exercise a UQPAY payment in the sandbox: which card numbers do what, what wallets
actually do in sandbox, and how to reproduce rotation, process death and network loss —
the scenarios that decide whether an integration double-charges a customer.

Every value on this page is a **documented sandbox test value**. No real card number, key
or customer detail appears here, and none may appear in your tests either.

---

> ## ⚠️ Read this before you test 3-D Secure
>
> **Visa does not work on the current sandbox merchant. Mastercard is the only working
> 3-D Secure route.**
>
> Every Visa card we have tried — `4176660000000027`, `4176660000000068`,
> `4176660000000092`, `4176660000000118`, `4000020000000000`, including the one in UQPAY's
> own CLI documentation — returns `system_error` at `POST /confirm`, with and without
> `enforce_3ds`. The same request body with a Mastercard number succeeds and returns a real
> `next_action`. Visa acquiring appears not to be enabled on this merchant account.
>
> This is an account-provisioning question for UQPAY, not an SDK bug. If you are seeing
> `SERVER_ERROR` on every Visa payment, you are seeing this and not something you caused.

---

## 1. Set up a sandbox run

Sandbox base URL is `https://api-sandbox.uqpaytech.com`. No real money moves.

Put your merchant credentials in `local.properties` (gitignored — never commit it):

```properties
uqpay.clientId=<your x-client-id>
uqpay.sandboxApiKey=<your x-api-key>
```

Then mint a token and create an intent:

```bash
./scripts/mint-sandbox-token.sh                    # writes uqpay.sandboxToken
./scripts/create-sandbox-intent.sh 8.98 SGD        # writes uqpay.intentId
./gradlew :sample-app:installDebug
```

Three things to know before you start:

- **UQPAY allows exactly one active access token per merchant.** Minting a new one
  invalidates the token your backend, your colleague's laptop and every test device are
  currently holding. If a payment suddenly starts returning `AUTHENTICATION_FAILED`,
  someone else minted.
- **Tokens last about 30 minutes**, and **intents auto-expire 30 minutes after creation**.
  A stale intent fails with `INTENT_NOT_PAYABLE`, not with a network error.
- **The `x-api-key` must never reach an app.** It can issue refunds and payouts. The sample
  app compiles it into **debug builds only**, purely so the demo runs standalone; the
  release build carries none and says so.

Turn on diagnostics while testing:

```kotlin
UQPayConfiguration(clientId, Environment.SANDBOX, tokenProvider, loggingEnabled = true)
```

That logs under the tag `UQPay`. It can never emit a request or response body, so it is
safe to capture into a ticket.

**Sandbox sheets are visibly sandbox.** While the SDK points at `Environment.SANDBOX` every
screen carries a test-mode badge the SDK draws itself; a merchant cannot theme it away or
switch it off. If a screenshot has no badge, it was taken against production.

---

## 2. Card test values

### The 3-D Secure card that works end to end

| Field | Value |
|---|---|
| Number | `5521970079998012` |
| Expiry | `10 / 2028` |
| CVC | `001` |
| Network | mastercard |

Verified on Android: confirm → `redirect_iframe` → Mastercard ID Check renders in the SDK's
WebView → frictionless pass → `SUCCEEDED` delivered to the host app.

### The lookalike trap — do not debug this for a day like we did

| Card | Reaches | Then |
|---|---|---|
| `5521970079998012` | `REQUIRES_CUSTOMER_ACTION` + `redirect_iframe` | ✅ ID Check renders, payment **succeeds** |
| `5413330057004047` | `REQUIRES_CUSTOMER_ACTION` + `redirect_iframe` | ❌ ACS declines → `THREE_DS_FAILED` |
| `5346930100108117` | `REQUIRES_CUSTOMER_ACTION` + `redirect_iframe` | ❌ ACS declines → `THREE_DS_FAILED` |

**The confirm response is identical in all three cases.** The last two are not enrolled in
the 3-D Secure test directory, so the fingerprint step reports `threeDSCompInd=U` ("3DS
Method unavailable") and the issuer's ACS refuses before showing anything.

The consequence for how you test: **a `confirm` probe is not evidence about 3-D Secure.**
Curling `/confirm` and seeing a `next_action` tells you the card is chargeable, which was
never in doubt. Only driving the flow through a real WebView to a final status tells you
whether 3-D Secure works. Comparing `skip_3ds` against `enforce_3ds` does not help either —
it tests the same thing twice.

### General cards (no 3-D Secure)

| Scheme | Number | Expiry | CVC |
|---|---|---|---|
| Mastercard | `5413330057004047` | `12/2030` | `989` |
| Mastercard | `5346930100108117` | `12/2026` | `811` |
| UnionPay | `6250947000000014` | `12/2033` | `123` |

**Use `12/2030` for `5413330057004047`.** UQPAY's published table gives its expiry as
`12/25`, which is now in the past and is rejected with `invalid expiry year: card is
expired`. The gateway does not validate a test card's expiry against a stored value — any
future date is accepted.

Sandbox OTP for any card that reaches a challenge screen: **`0101`**.

---

## 3. Wallets

**Wallet availability is per-merchant, and most are off.** An intent's
`available_payment_method_types` lists what *your merchant account* is enabled for, not
UQPAY's catalogue. Seeing three methods where the docs list fifteen is enablement, not a
bug — the SDK takes methods from the intent verbatim, carries types it does not recognise
rather than dropping them, and hides a type it cannot render rather than erroring.

- **GrabPay** is the wallet confirmed end to end on the current sandbox merchant.
- **AlipayCN / AlipayHK** need the "AWallet" sandbox app and a sandbox account — request
  shared credentials from UQPAY support. QR flow: scan the returned QR with AWallet.

One gateway quirk worth knowing if you are reading raw responses: **AlipayCN's `next_action`
details object comes back keyed `alipay`**, not `alipaycn`, even though the confirm request
used the `alipaycn` method type. Anything keying off the method type to find the details
object reads null. The SDK handles this; your own logging might not.

A wallet QR is polled for **10 minutes** (300 attempts, 2 seconds apart) while the sheet is
in the foreground. Time the customer spends in the wallet app costs nothing — the budget is
counted in attempts, not wall-clock, so suspended time spends none of it.

---

## 4. Forcing each of the four outcomes

| Outcome | How to get there |
|---|---|
| `SUCCEEDED` | The 3-D Secure card above, or any general card without `enforce_3ds`. |
| `FAILED` | `5413330057004047` with 3-D Secure enforced → `THREE_DS_FAILED`. Any Visa card → `SERVER_ERROR` (see the warning at the top). A stale intent → `INTENT_NOT_PAYABLE`. |
| `CANCELLED` | Press back on the method list before submitting anything, or call `launcher.cancel()` before a confirm is in flight. |
| `PENDING` | Start a wallet QR and leave it unscanned past the poll window, or kill the network mid-confirm (below). |

**`PENDING` is the one to get right, and the one integrations get wrong.** It means *the SDK
stopped waiting*, never *the payment failed*. Do not retry, do not refund, do not release the
order — wait for the webhook. See [webhooks.md](webhooks.md#when-the-sdk-and-the-webhook-disagree).

Poll windows, so you know how long to wait before the SDK gives up:

| Flow | Budget | Roughly |
|---|---|---|
| Card 3-D Secure | 150 attempts × 2s | 5 minutes |
| Wallet QR | 300 attempts × 2s | 10 minutes |
| Post-dismissal reconciliation | 12 attempts × 5s | 1 minute |

A single read is abandoned after 45 seconds and costs one attempt; the intent is re-read on
the next tick.

---

## 5. The scenarios that matter

These are the ones where a payment gets lost or charged twice. Run all of them before you
ship.

### Rotation mid-payment

```bash
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1     # landscape
adb shell settings put system user_rotation 0     # back to portrait
```

Rotate on the method list, on the card form, and — the important one — **on the 3-D Secure
challenge page**. A WebView is destroyed on every configuration change, and the ACS sets a
session cookie between the fingerprint step and the challenge that the challenge cannot be
completed without. Expect: the issuer session survives, the payment continues, exactly one
result arrives.

### Process death mid-payment

```bash
adb shell settings put global always_finish_activities 1   # don't keep activities
# — or, more realistically, kill the process while the sheet is in the background:
adb shell am kill com.uqpay.sample
```

Send the app to background at each stage (method list, card form, 3-D Secure, wallet QR),
kill it, and relaunch. Expect: the payment is picked up where it was, **not** restarted, and
the callback fires exactly once. An intent already in `REQUIRES_CUSTOMER_ACTION` is watched,
not dropped back onto the method list.

Turn `always_finish_activities` back off afterwards — it will make every other test strange.

### Network loss mid-confirm

```bash
adb shell svc wifi disable && adb shell svc data disable
# ... submit the payment, then:
adb shell svc wifi enable && adb shell svc data enable
```

Expect `NETWORK_ERROR` on a request that never left, and `PENDING` on one that may have
landed. The SDK never reports `FAILED` for a confirm whose fate it does not know.

### Duplicate submission

Double-tap Pay. Tap Pay, rotate, tap Pay again. Expect: the second tap joins the attempt
already in flight rather than starting a second one, back-press is blocked (visibly, and
boundedly) while a confirm is in the air, and the persisted idempotency key is replayed
rather than a new one minted.

### Returning from another app

Start a wallet payment, switch to the wallet app, come back. Expect an immediate re-read of
the intent rather than a wait for the next poll tick — exactly one, replacing the pending
wait rather than adding to the budget.

---

### Memory leaks (LeakCanary)

The sample app's **debug** build ships with [LeakCanary](https://square.github.io/leakcanary/)
(`debugImplementation` only — it is not in the SDK and not in any release build). Install
`:sample-app:installDebug`, then run each of these and wait for the LeakCanary notification
after every one:

1. Card + 3-D Secure to completion.
2. Wallet QR, then cancel while the QR is showing.
3. Start a payment, rotate twice during the card form and once during the 3-DS challenge.
4. Background the app mid-3-DS for a minute, return, finish.
5. Cancel from the card form, then press back on the sample app's checkout screen.

A retained `UQPayPaymentActivity`, `PaymentViewModel`, `WebView` or `PaymentSession` after
the sheet has closed is a bug. Leaks inside the sample app itself are worth a look but are
not SDK bugs.

## 6. Rules for your own tests

- **No real card numbers, no real API keys, no real customer data** — in test sources,
  fixtures, screenshots, or bug reports. Use the values on this page.
- **Never assert on `UQPayError.message`.** It is the shopper's sentence, it is localisable,
  and merchants are expected to override it. Assert on `UQPayErrorCode`.
- **Never assert on `UQPayError.developerMessage` text.** It is deliberately unstable.
- **`UQPayErrorCode` and `PaymentMethodType` are open sets**, not enums. A test that switches
  over them exhaustively will pass today and be wrong the day UQPAY adds a code.
- **`traceId` is always null today.** A test that expects a value will fail; a support flow
  that depends on one has nothing to quote. Use `paymentIntentId` and `transactionId`.

---

## Next steps

- [Webhooks & reconciliation](webhooks.md) — the authority on what actually happened
- [Error Codes](error-codes.md) — what each failure means and how to handle it
- [Troubleshooting](troubleshooting.md) — symptoms and their causes
- [Integration Guide](integration-guide.md)
