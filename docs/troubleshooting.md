# Troubleshooting

> Grows as real-world issues are triaged. **Turn on `UQPayConfiguration(loggingEnabled = true)`
> before reproducing anything below** — the SDK's degraded paths (an unwritable idempotency
> pin store, an exhausted poll budget, a superseded confirm) are silent otherwise, and those
> lines are usually the answer. It never logs a request or response body.

> **Testing in sandbox?** Read [testing.md](testing.md) first. Several things that look like
> SDK faults are sandbox facts — most notably that **every Visa card returns `system_error`
> on the current sandbox merchant**, so Mastercard is the only working 3-D Secure route.

## Build / integration issues

**Dependency not resolving**
- Confirm the repository hosting the artifact is declared in `settings.gradle.kts`
  (`dependencyResolutionManagement.repositories`).
- Confirm the version exists (see CHANGELOG / release notes).

**Manifest merger conflicts**
- The SDK declares `INTERNET` permission and its internal payment Activity. If your app
  overrides merged attributes, check the merger report at
  `app/build/outputs/logs/manifest-merger-*-report.txt`.

**Minified (R8/ProGuard) build breaks payment flow**
- Consumer rules ship inside the AAR and should apply automatically. If you maintain
  aggressive custom rules, ensure `com.uqpay.sdk.**` model classes are not stripped.

## Runtime issues

**`IllegalStateException: UQPay is not initialized`**
- Call `UQPay.initialize(...)` in `Application.onCreate` before any payment API.

**`AUTHENTICATION_FAILED` in sandbox**
- Sandbox keys only work with `Environment.SANDBOX` (and vice-versa for production).

**No callback received**
- The callback fires exactly once per `launch` call. If you re-registered callbacks manually
  across rotation, remove that — the SDK handles configuration changes itself.

**A second `launch` was answered with the first payment's result**
- Fixed in 0.1.0. Each launch now gets its own payment sheet, its own launch parameters and
  its own result. Launching the same intent twice is still one payment — the two sheets share
  one engine and one idempotency key — but it is two launches, so expect one callback each.

**The card form looks complete but Pay does nothing**
- Fixed in 0.1.0, and worth knowing what it was: entering an Amex, typing a four-digit
  security code, then replacing the number with a three-digit-code brand left a code the new
  brand forbids. Nothing showed an error (errors appear after a submit attempt) and the Pay
  button was disabled by the very failure it would have explained. The code is now
  re-truncated when the brand changes, and Pay is always tappable — tapping an incomplete
  form reveals every field error instead of doing nothing.

**A payment came back `PENDING` but money moved**
- Expected, and correct. `PENDING` means the SDK stopped waiting, not that the payment
  failed — the customer may have completed it in their wallet or banking app moments
  later. Wait for the `acquiring.payment_intent.succeeded` webhook. Do not retry, refund,
  or release the order on `PENDING`.
- There is no `TIMEOUT` **status**; the timeout appears as `PENDING` carrying
  `UQPayErrorCode.TIMEOUT`.
- Your callback will **not** fire a second time to upgrade the result. The result channel
  delivers exactly once per launch; the webhook is the upgrade path.

**The customer scanned the QR, paid, and the screen kept spinning**
- The SDK re-reads the intent immediately when the payment screen returns to the
  foreground, so coming back from the wallet app should resolve it within a second.
  If it does not, check that the host app is not finishing the SDK's Activity from a
  lifecycle callback of its own.
- A wallet QR is polled for ten minutes (300 attempts, 2s apart). Budget is spent in
  *attempts*, not wall-clock, so time the app spends backgrounded costs nothing.

**3-D Secure finishes but the SDK stays on the WebView**
- The step ends when the WebView reaches a custom (non-`http`) scheme — recognised without
  any configuration — or a URL matching the intent's `return_url`. If your intent was
  created with an **https** `return_url`, make sure it is the URL the issuer actually
  redirects to, prefix included.

**3-D Secure ends the moment the customer taps a link on the issuer's page**
- Fixed in 0.1.0. Links that address the *device* rather than a page — `tel:` for the bank's
  support line, `mailto:`, and the `intent://` deep links used for app-to-app authentication
  — are no longer mistaken for the merchant's return URL. They are consumed and ignored, so
  the challenge stays exactly where it was.
- App-to-app authentication does not hand the customer over to their banking app: this SDK
  will not fire an arbitrary `intent://` from a page it did not write. Issuers fall back to
  an in-page challenge, and the payment settles either way, because the outcome is always
  re-read from the API rather than from the browser step.

**Every payment fails with `AUTHENTICATION_FAILED`**
- Check `clientId` first, not your UQPAY account. A blank one is now refused by
  `UQPayConfiguration` at construction, so a value that used to fail every payment at the
  gateway fails your first run instead — but a *wrong* (non-blank) one still looks exactly
  like an account problem.
- Sandbox credentials only work with `Environment.SANDBOX`, and vice-versa.

**`error.traceId` is always null**
- Expected. The UQPAY gateway does not currently return a correlation header. Quote
  `result.paymentIntentId` and `result.transactionId` in support tickets instead.

**The pay button submitted twice**
- It cannot create two payments: the SDK guards duplicate submission at its own boundary
  (same payload joins the in-flight attempt; a different payload while one is unresolved is
  refused; the idempotency key is persisted across process death). If you are seeing two
  *attempts* on the gateway, capture a `loggingEnabled` log and report it — that would be
  an SDK bug, not a host-app one.

## Appearance and copy

**The payment sheet is Material 3 purple and does not match our app**
- Set `UQPayConfiguration(appearance = UQPayAppearance(...))`. It is SDK-wide, so setting it
  once at init covers every screen the SDK draws. See
  [the integration guide](integration-guide.md#6-make-the-sheet-look-like-your-app).

**The sheet is dark even though our app forces light mode (or the reverse)**
- The SDK follows the device by default and cannot see an app-level override. Say so
  explicitly: `UQPayAppearance(colorMode = UQPayAppearance.ColorMode.LIGHT)`.

**We set a brand colour and now some text is unreadable**
- Contrast is the merchant's to get right and nothing in the SDK checks it. Every `on*`
  colour has to be readable against the surface it is named for — `onPrimary` on `primary`,
  `onSurface` on `surface`. WCAG AA is the bar: 4.5:1 for body text, 3:1 for large text and
  UI edges.

**A "TEST MODE" banner is on the sheet**
- The SDK is configured with `Environment.SANDBOX`. That is exactly what the banner is for.
  It disappears in `Environment.PRODUCTION`, and there is no way to hide it otherwise.

**The sheet is in English and our app is not**
- The SDK ships English only. Every string is a `uqpay_*` resource, and app resources beat
  library resources during merging — declare the ones you want in your own
  `res/values-<language>/strings.xml`. See
  [Localisation](integration-guide.md#localisation).

**Amounts look wrong for our currency**
- Amounts are rendered by the platform's currency formatter, per locale: JPY, KRW and VND
  show no decimals; European locales use their own separators and symbol placement. If you
  are seeing `"CODE 8.98"` instead, the currency code on the intent is one the platform's
  ISO 4217 table does not recognise — check what your backend set on the intent.

**The Pay button is hidden behind the keyboard**
- It should not be: the payment Activity declares `windowSoftInputMode="adjustResize"` and
  the card form scrolls. If you see this, capture the device, OS version and font-size
  setting and report it.

## Sheet lifecycle

**How do we close the sheet when the order is cancelled on our side?**
- `payments.cancel()`. The outcome still arrives through your callback exactly once. With
  nothing submitted that is `CANCELLED`; **with an attempt already in the air it is
  `PENDING`, never `CANCELLED`** — closing a sheet cannot un-send a confirm, so treat that
  `PENDING` as you would from any other path and wait for the webhook.

**`cancel()` did nothing**
- Either no payment was launched by that launcher, or the payment had already ended, or it
  was called in the moment between `launch` returning and the sheet appearing. All three are
  documented no-ops. Re-issue the cancel if your condition still holds.

**We restricted the methods and the sheet says none are available**
- `allowedPaymentMethods` only ever narrows, and an empty intersection with the intent's own
  methods is honoured rather than widened. Check that the methods you allowed are actually
  enabled on the intent.

**A `CardOnly` launch failed instantly with `INVALID_PAYMENT_METHOD`**
- The launch contradicted itself: `CardOnly` names `CARD`, and `allowedPaymentMethods` did
  not contain it. `UQPayError.developerMessage` names the contradiction.

## Getting help

Include: SDK version (`UQPay.version`), Android version, device model, `paymentIntentId`,
`transactionId`, the `UQPayErrorCode`, **`UQPayError.developerMessage`**, and a Logcat capture
of the `UQPay` tag taken with `loggingEnabled = true`.

`developerMessage` is the sentence written for you rather than for the shopper — it names the
failure, the HTTP status where there was one, and (in sandbox) the gateway's own text. It is
safe to paste into a ticket. `UQPayError.message` is the shopper's sentence and says much
less.

### Where to send it

| What you have | Where it goes |
|---|---|
| A bug in this SDK — wrong behaviour, a crash, a doc that is wrong | A GitHub issue on this repository. |
| A payment that behaved oddly at the gateway, a method or currency not enabled on your account, credentials | [it@uqpay.com](mailto:it@uqpay.com), quoting `paymentIntentId` and `transactionId`. |
| **A security vulnerability** | **Not a public issue.** Use GitHub's private vulnerability reporting (Security tab → Report a vulnerability), or email [it@uqpay.com](mailto:it@uqpay.com) with `SECURITY` in the subject. See [`SECURITY.md`](../SECURITY.md). |

Sandbox questions are usually answered by [testing.md](testing.md) faster than by us — the
Visa `system_error` limitation in particular is a known sandbox fact, not a bug to report.

**Never include card data, keys, or customer PII in reports** — not in a GitHub issue, not
in an email, not in a screenshot. The SDK will not have logged any; do not add any by hand.
Mask anything you must reference, for example a card as `•••• •••• •••• 1234`.
