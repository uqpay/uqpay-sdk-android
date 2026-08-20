# Changelog

All notable changes to the UQPAY SDK for Android are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/); versioning follows
[Semantic Versioning](https://semver.org/).

## [Unreleased]

Nothing here has been released. The payment flow is implemented end to end — card with
3-D Secure, wallet QR, bank-transfer instructions, persisted idempotency, rotation and
process-death recovery — and is covered by 765 unit tests. What remains before 0.1.0 is
human-gated: a manual pass on physical devices, a LeakCanary run on the sample app, CI
actually executing, and Maven publishing credentials.

### Added
- **`PaymentSessionParams.BillingDetails`** — optional prefill for the card form's billing
  section (name, email, phone, address, ISO country). Additive and `@JvmOverloads`-
  compatible: the existing one- and two-argument `PaymentSessionParams` constructions are
  unchanged. Card number, expiry and security code are **not** prefillable and never will
  be — passing a PAN into the SDK would put card data in an `Intent` extra. Prefilled
  values stay fully editable, are never persisted or logged, and `toString()` redacts the
  email and phone. An unrecognised `countryCode` falls back to the device region rather
  than erroring.
- **The payment engine.** A state machine with a once-only terminal latch: exactly one
  result per payment, ever. `SUCCEEDED` / `FAILED` / `CANCELLED` / `PENDING`, with
  `PENDING` reserved for "the SDK stopped waiting", never for "it failed".
- **Persisted idempotency.** An attempt's key is pinned under a SHA-256 digest of its
  identity fields (never the PAN, never the CVC) and survives process death, so a customer
  paying again with the same details replays the original key instead of minting a new one.
  A pre-confirm re-read of the intent guards the rest.
- **The payment UI** (Compose, internal): method list, card form with brand detection and
  validation, 3-D Secure WebView (iframe and redirect), wallet QR with expiry, and
  bank-transfer instructions.
- **Duplicate-submission protection at the SDK boundary** (AC §8.2): a repeated identical
  confirm joins the attempt in flight; a *different* payload while one is unresolved is
  refused; back-press is blocked, visibly and boundedly, while a confirm is in flight;
  rotation re-attaches to the running payment instead of starting a second one.
- **Relaunch onto an attempt already in the air.** An intent that is already
  `REQUIRES_CUSTOMER_ACTION` is now watched rather than dropped onto the method list —
  previously the wallet latch would re-serve a live, correct QR that nobody was polling, so
  a customer could pay and the SDK would never learn.
- **Foreground re-read** (AC §8.1): returning from a banking or wallet app re-reads the
  intent immediately instead of waiting out the poll interval. Exactly one re-read; it
  replaces the pending wait rather than adding a poll, so it cannot inflate the budget.
- `UQPayConfiguration.loggingEnabled` (default `false`): opt-in Logcat diagnostics under
  the tag `UQPay`. Additive and `@JvmOverloads`-compatible — existing three-argument
  construction is unchanged. It can never emit a request or response body.
- `return_url` is now read from the payment intent and used as the 3-D Secure
  end-of-step prefix, so merchants using an `https` return (rather than a custom scheme)
  are detected too.
- **Rotating the phone during 3-D Secure keeps the issuer session.** A WebView is destroyed
  on every configuration change, and the ACS sets a session cookie between the fingerprint
  step and the challenge that the challenge cannot be completed without — so the 3-D Secure
  session's lifetime is the *payment's*, not the view's. It is cleared when the payment ends,
  under the same predicate that releases the payment session, and never on a configuration
  change or a system-initiated destroy, which is the relaunch-recovery case.
- **The SDK never clears cookies it did not create.** `CookieManager` and `WebStorage` are
  process-global, and a payment SDK calling `removeAllCookies` would sign the host app's own
  users out of the host app's own web views on every card payment. The 3-D Secure clear is
  scoped to the origins that step actually visited.
- **A WebView renderer crash never takes the host app with it.** `onRenderProcessGone`
  returns true, so a renderer killed by memory pressure mid-challenge cannot become a crash
  in the merchant's app; the sheet reports that verification was interrupted and the poller
  settles the payment.
- **A Java consumer test source set** (`uqpay-sdk/src/test/java/`) that exercises the public
  API exactly as a Java merchant would — the only thing that can catch a lost `@JvmStatic`
  or `@JvmOverloads`.
- **AAR size ceiling in CI** (AC §10.4): `./gradlew :uqpay-sdk:checkAarSize` prints the
  actual size and fails past a recorded ceiling. Current: 710,650 B.
- **A main-thread I/O test** (AC §10.3) that runs a whole payment on real threads and
  asserts the pin store, the socket and the device-info read were each exercised and none
  of them on the main thread.
- UI tests now run at **API 24 and API 34** with a modern `targetSdk`, so the back-press
  rules are exercised under predictive back rather than only on the legacy path.
- Public API: `UQPay.createPaymentLauncher`, `UQPayConfiguration`, `PaymentSessionParams`,
  `PaymentResult`, `PaymentCallback`, `UQPayError`, `UQPayErrorCode`, `PaymentMethodType`
  (card + 13 wallets), and the `auth` package (`UQPayTokenProvider`, `UQPayAuthToken`).
- Internal gateway client over raw `HttpsURLConnection` — no OkHttp, so no transitive
  version conflict is forced on the host app. Retries only where provably safe: a
  mutating call without an idempotency key is never resent.
- Single `ErrorMapper` chokepoint converting every internal failure into a public
  `UQPayError`, with a table test asserting every declared code is reachable.
- Access-token caching and refresh-on-401, serialised so concurrent requests trigger one
  fetch rather than a stampede.
- Consumer R8 rules shipped with the AAR, including the `ActivityResultContract` keep
  that stops payment results being silently lost in minified builds.
- CI: `apiCheck`, unit tests, lint, and a minified-consumer job that verifies the
  contract survives R8. **Not yet executed — the repository has no remote.**
- Documentation set: integration guide, API reference, error codes, troubleshooting,
  architecture, acceptance criteria, release process, and the research specs under
  `docs/spec/`.

### Changed
- **The sample app is now a store checkout** rather than a box to paste an intent id into:
  a cart with a price breakdown (money as `BigDecimal` throughout), a hard-coded shipping
  address, and a Checkout button that creates the intent and launches the payment. Still
  plain Views and XML, deliberately — it is the proof that a Compose-internal SDK drops
  into a non-Compose host app. It passes `DemoCustomer.billingDetails`, so only the card
  fields need typing. `DemoMerchantBackend` mints its own access tokens in **debug builds
  only**, so the demo runs standalone without re-running a script every half hour; a
  release build carries no API key and says so.
- **Breaking:** `startPayment(activity, request, callback)` is replaced by an
  `ActivityResultContract` launcher. A callback passed at launch cannot survive process
  death; the registry can.
- **Breaking:** `PaymentStatus.TIMEOUT` is replaced by `PENDING`. A closed poll window is
  not an outcome — the customer may have paid moments earlier, and reporting failure
  invites a duplicate charge.
- **Breaking:** the auth model drops the publishable key, which UQPAY does not have. The
  app holds a short-lived token from the merchant backend, never the `x-api-key`.
- `UQPayErrorCode` and `PaymentMethodType` are open sets rather than enums, so adding a
  code or a wallet is not source-breaking for merchants matching on them exhaustively.
- Documentation corrected where it had drifted: duplicate-submission protection is
  implemented (the integration guide said it was not); a `PENDING` result is **never**
  upgraded by a second callback (the guide implied it might be); `UQPayError.traceId` is
  documented as currently always null rather than as something to quote in support tickets.

### Known limitations
- `UQPayError.traceId` is **always null**: the gateway returns no correlation header
  (`x-request-id` / `request-id` / `x-b3-traceid`). The field is retained pending sign-off
  with the UQPAY platform team; identify payments by `paymentIntentId` and `transactionId`.
- `DisplayQrCodeDto` does not model the raw EMVCo `qr_code` payload. Rendering it would
  need a QR encoder, which is a dependency decision rather than a patch; the SDK uses the
  gateway's `qr_code_url` image instead.
- Visa 3-D Secure test cards return `system_error` on the current sandbox merchant — Visa
  acquiring appears not to be enabled. Mastercard is the only working 3-D Secure route.
- Not yet verified by a human: physical-device matrix (AC §5.1/§5.2), LeakCanary
  (§10.2), a manual release-candidate pass (§9.3), Maven publishing (§11.3/§11.4), and
  CI having actually run (§9.1).
