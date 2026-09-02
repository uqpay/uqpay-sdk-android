# Changelog

All notable changes to the UQPAY SDK for Android are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/); versioning follows
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed
- **`SingleWallet(PaymentMethodType.CARD)` is refused before any network call.** It
  type-checks — `SingleWallet` takes any `PaymentMethodType` — and used to reach the sheet's
  auto-confirm, which sent a *wallet* confirm typed `card` (a body the gateway has no reading
  of) and latched the wallet registry for that intent under `card`, so a later, correct launch
  found a wallet attempt "in flight" that never existed. The payment now ends with `FAILED` /
  `INVALID_PAYMENT_METHOD` and a `developerMessage` naming `CardOnly` as the fix, exactly like
  a presentation that contradicts `allowedPaymentMethods`; the ViewModel holds the same rule
  as a backstop.
- **One access-token manager per configuration, not per payment.** UQPAY allows one active
  token per merchant, so a manager per session — every launch calling your `fetchToken()`,
  and two intents alive at once taking turns invalidating each other's token on every `401` —
  could fail a payment that had every right to succeed. Every session under a configuration
  now shares one manager, one token and one refresh mutex; `fetchToken()` is called when the
  held token nears expiry, and after a `401`. A second `UQPay.initialize` rebuilds it.

### Changed
- **A relaunch of a running intent keeps the first launch's `presentation`.** This was
  already the behaviour — the second launch re-attaches to the running payment — but it was
  undocumented and silent. It is now stated in `UQPayPaymentLauncher.launch` and the
  integration guide, and the SDK logs the ignored presentation at debug under
  `loggingEnabled`. `billingDetails` and `allowedPaymentMethods` from the second call are
  honoured, as before.
- **Docs and comments corrected, no behaviour change.** The card form's KDoc claimed rotation
  empties the fields; the manifest handles rotation, so they survive it and are lost only to
  configuration changes the system performs (dark mode, font scale, locale) and process
  death. The sample app's `RETURN_URL` comment claimed the scheme must be declared in the
  manifest; the SDK consumes the return URL inside its own WebView and nothing is declared or
  needed. The integration guide now states what `return_url` does on Android (a 3-D Secure
  end-of-step signal, never an outcome; an `https` one may render inside the sheet on a
  POST), that app-to-app 3-D Secure links are consumed rather than launched and run out to
  `PENDING`, every path that delivers `PENDING`, and that `completedAtEpochMillis` is device
  observation time and `traceId` is always `null` today.

## [0.1.0] — 2026-09-02

**The first release.** Everything below is new to merchants: there is no earlier version to
upgrade from, so nothing here is a breaking change. Where an entry contrasts with an older
shape, that shape existed only in this repository's pre-release skeleton and was never
published.

The payment flow is implemented end to end — card with 3-D Secure, wallet QR, bank-transfer
instructions, persisted idempotency, rotation and process-death recovery — and is covered by
879 unit tests and 8 instrumented tests, all green in CI. Published to Maven Central on
2026-09-02. Not yet verified on physical hardware: the device matrix, a LeakCanary run on the
sample app, and the manual release-candidate pass — see *Known limitations* below.

### Added
- **Maven Central publishing.** The SDK publishes as `com.uqpay.sdk:uqpay-sdk-android`
  through the Central Portal, with a sources jar and GPG-signed artifacts, so merchants add
  one `implementation(...)` line and no repository. Credentials come only from the
  environment; `publishToMavenLocal` and `-PuqpaySdkFromMavenLocal=true` on the sample app
  verify the artifact end to end before any remote publish (`docs/release-process.md`).
- **The merchant-facing docs are in the repository.** Integration guide, API reference,
  error codes, testing, webhooks, troubleshooting, architecture, acceptance criteria and the
  release process are tracked; only internal working material under `docs/` is ignored.
- **`UQPayAppearance`** — the payment sheet in your app's colours. Set once on
  `UQPayConfiguration`; applies to every screen the SDK draws. Ten Material 3 colour roles per
  mode as plain ARGB ints (no Compose type reaches the public API), an explicit
  `colorMode` of `SYSTEM` / `LIGHT` / `DARK` for hosts that force their own, and a corner
  radius clamped rather than rejected — this is built in `Application.onCreate` and must not
  be able to take an app down over a rounded corner. What is deliberately **not** themeable:
  the amount, the cancel affordance, the test-mode badge, the blocked-back-press copy, and
  text sizes (Material 3 typography in `sp`, so the sheet honours the customer's font-size
  setting).
- **A test-mode badge on the sheet, drawn by the SDK.** While the SDK points at
  `Environment.SANDBOX` every screen carries one; in `PRODUCTION` nothing is drawn, and an
  uninitialised SDK claims neither. It cannot be themed away or switched off. A sandbox sheet
  and a live one used to be pixel-identical, which is how a screenshot in a bug report — or a
  QA pass on a build someone had already flipped to production — says nothing about which
  environment the money was in. The sample app had a badge of its own, which proved only that
  a *merchant* could draw one.
- **`UQPay.createPaymentLauncher(caller, callback)`** — an `ActivityResultCaller` overload, so
  **a `Fragment` can host a payment**. Both `ComponentActivity` and `Fragment` implement that
  interface, so this adds no dependency: the SDK still does not depend on `androidx.fragment`.
  The existing `ComponentActivity` overload is unchanged. The Compose recipe (register in
  `onCreate`, pass the launcher into the composition) is documented, and there is deliberately
  no public `@Composable` — one would put Compose types in the public API and force every
  merchant, including those on Views, into a compatible Compose version.
- **`UQPayPaymentLauncher.cancel()`** — closes the sheet this launcher opened, for the
  merchant-side events that make one wrong: an expired basket reservation, an order cancelled
  from the back office, a push saying the customer paid elsewhere. The outcome still arrives
  through the callback exactly once. With nothing submitted that is `CANCELLED`; **with an
  attempt in the air it is `PENDING`, never `CANCELLED`** — closing a sheet does not reach into
  the gateway and stop a payment, and reporting a cancel there is how an order gets released
  for money that did move.
- **`PaymentSessionParams.allowedPaymentMethods`** — a per-region or per-risk-tier restriction
  on which of the intent's methods a payment may use, which previously could only be expressed
  as "all methods" or "exactly one wallet". It only ever narrows; an empty set is honoured
  rather than widened back to everything; and a `CardOnly` or `SingleWallet` presentation that
  contradicts it fails immediately with `INVALID_PAYMENT_METHOD`, before any network call,
  rather than showing a method the merchant's own rules forbade.
- **`PaymentSessionParams.BillingDetails.Builder`**, plus builders for `UQPayAppearance` and
  `UQPayAppearance.Colors`. Java has no named arguments, and these three types have long runs
  of same-typed parameters — ten `String?`, two `Colors`, ten `int` — where
  `new BillingDetails(…, "Singapore", "Singapore", …)` with `city` and `state` transposed
  compiles exactly as cleanly as the correct call and sends wrong AVS data forever.
  `UQPayAppearance` and `Colors` carry no `@JvmOverloads` for the same reason: a family of
  positional constructors is the hazard, not the cure.
- **`UQPayError.developerMessage`** — see *Changed*.
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
- **`docs/testing.md`** — the sandbox testing guide: the 3-D Secure card
  that works end to end, the two Mastercards that reach `REQUIRES_CUSTOMER_ACTION` and then
  fail at the ACS (`threeDSCompInd=U`) and so look like working 3-DS cards at confirm, the
  Visa `system_error` limitation stated at the top of the page rather than buried in a
  changelog, wallet enablement, poll windows, and adb recipes for rotation, process death,
  network loss and duplicate submission.
- **A support channel, and a security disclosure path that names an address.**
  "Getting help" listed exactly what to put in a report and no way to send it. There is now
  a routing table in `docs/troubleshooting.md` and a
  summary in the README: SDK bugs to GitHub issues, account and payment questions to
  `it@uqpay.com`, and vulnerabilities to GitHub's private reporting or the same address with
  `SECURITY` in the subject — never a public issue. `SECURITY.md` no longer carries a
  pre-push TODO in place of a mailbox, and warns that plain email is unencrypted, so the
  first message should be short and a secure channel arranged before any proof of concept.
- **`docs/webhooks.md`** — the reconciliation guide the rest of the docs
  were assuming: signature verification over the raw body (`x-wk-signature` /
  `x-wk-timestamp`, HMAC-SHA512), retry and dedupe rules, which events decide an order
  (intent events, not attempt events), how `paymentIntentId` / `transactionId` join to
  `payment_intent_id` / `payment_attempt_id`, and a table for every way the SDK result and
  the webhook can disagree. Every doc said "the webhook is the authority" and none of them
  said what the webhook was.
- **A Java host in the sample app** (`JavaCheckoutActivity.java`), reachable from the cart
  screen: the same payment driven from Java — launcher registered in `onCreate`, sheet
  opened, callback handled, `switch` over `PaymentStatus`, `equals` against
  `UQPayErrorCode`, `BillingDetails.Builder` for the ten-`String?` prefill. The Java test
  source set catches a lost `@JvmStatic`, but a unit test never registers an
  `ActivityResultContract` and never receives a result, which is the part a Java merchant
  actually depends on. Writing it surfaced one rough edge now documented in the integration
  guide: `@JvmOverloads` yields no `(intentId, billingDetails)` constructor, so a Java caller
  wanting a prefill with the default sheet must name
  `Presentation.MethodList.INSTANCE` — passing `null` throws.
- **A Java consumer test source set** (`uqpay-sdk/src/test/java/`) that exercises the public
  API exactly as a Java merchant would — the only thing that can catch a lost `@JvmStatic`
  or `@JvmOverloads`.
- **AAR size ceiling in CI** (AC §10.4): `./gradlew :uqpay-sdk:checkAarSize` prints the
  actual size and fails past a recorded ceiling. Current: 725,386 B.
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
- **`UQPayError` now carries two messages, and they are not interchangeable.** `message` is
  written for the **shopper** — a complete sentence, in the app's language, that never quotes
  the gateway and is identical in sandbox and production. `developerMessage` is written for
  the **integrator**: what the SDK was doing, the HTTP status, the gateway's own text (in
  sandbox only), and where to look. The SDK's own KDoc sample and the integration guide both
  told merchants to `showError(result.error?.message)`, which would eventually have shown a
  shopper "The UQPAY SDK was used before it was initialized". *Migration:* the gateway's
  sandbox detail used to be appended to `message` and now lives in `developerMessage`.
- **Every customer-facing error sentence moved from Kotlin into `res/values/strings.xml`.**
  Fourteen of them were hardcoded in `ErrorMapper`, in breach of the rule written at the top of
  `strings.xml` — nothing in Kotlin may hardcode text a customer can read — which made the SDK
  untranslatable without an API change. They are now `uqpay_error_*` resources, so a merchant
  can override any of them from their own app's `values-<language>/` without waiting for an SDK
  release. Documented under "Localisation" in the integration guide, along with the fact that
  the SDK ships English only — which was true before and stated nowhere a merchant reads.
- **`AUTHENTICATION_FAILED` no longer invites a retry loop.** It read "The payment could not be
  authorised. Please try again" for what is a *merchant backend token* problem: nothing the
  shopper did, and nothing retrying will fix. It now asks them to contact the store, and the
  technical explanation — check `UQPayTokenProvider`, and remember that minting a token
  invalidates the previous one — went to `developerMessage`. The same reasoning applies to
  `NOT_INITIALIZED`, `INVALID_CONFIGURATION` and `INVALID_REQUEST`, which now read as "this
  payment couldn't be started" rather than naming a fault the customer has no part in.
- **Amounts are formatted by the platform's currency formatter, not concatenated.** The sheet
  drew `"$currency $amount"`, which is wrong in three ordinary cases for a gateway whose method
  list is SEA and East Asian: JPY, KRW and VND have no minor unit, so `"1000.00 JPY"` showed a
  price a hundred times smaller than it was; European locales use different separators; and the
  symbol's side is a property of the locale, not the currency. A currency code the platform
  cannot resolve, or an amount that will not parse, falls back to the old code-beside-number
  rendering rather than dropping or rounding a digit.
- **The payment Activity no longer needs `androidx.appcompat`, and it is gone from the SDK's
  dependencies.** It was there for one thing — `Theme.AppCompat.DayNight.NoActionBar` — whose
  DayNight behaviour was decided by the device alone, so a host app that forces light mode still
  got a dark payment sheet on a phone set to dark. The Activity now extends
  `androidx.activity.ComponentActivity` with the SDK's own theme, and light/dark is
  `UQPayAppearance.colorMode`'s decision. The window background is painted from the configured
  palette in `onCreate`, so an explicit `LIGHT` or `DARK` is honoured from the first frame
  rather than flashing the device's preference first.
- **Manifest polish on the payment Activity**: `android:windowSoftInputMode="adjustResize"`, so
  the IME cannot leave the Pay button unreachable on a short screen; and `android:label`, so the
  Recents switcher names the payment screen instead of falling back to the host app's own name.
- **The consumer R8 rule is scoped to the SDK's own contract.** It read
  `-keep class * extends ActivityResultContract`, which kept *every* contract in the merchant's
  app — androidx's own, and every one the merchant or any other library ever wrote — disabling
  optimisation across code that is none of this SDK's business. It now names
  `com.uqpay.sdk.launcher.UQPayPaymentContract` explicitly. CI's check was strengthened at the
  same time: it asserted only that the name appeared somewhere in `mapping.txt`, which passes
  even when the class has been renamed, and now asserts the identity mapping and that the
  over-broad rule has not come back.
- **Dependency versions are declared as floors, not preferences — the SDK no longer forces
  `compileSdk 35` on the merchant.** Every androidx artifact carries a hard `minCompileSdk`
  that becomes the *merchant's* build error, naming androidx rather than us, and
  `androidx.core:core-ktx:1.15.0` — pinned to "latest" out of habit, and **not imported
  anywhere in the SDK** — required `compileSdk 35`. An app on `compileSdk 34` could not build
  at all. Everything else we depend on (activity 1.9.3, lifecycle 2.8.7, Compose 1.7.6) was
  already fine at 34, so lowering that one pin to 1.13.1 moves the floor to **`compileSdk 34`**
  at no cost: Gradle resolves the highest version across the app, so a merchant already on
  newer androidx keeps theirs. Verified in both directions — a `compileSdk 34` consumer builds,
  and a consumer declaring `core-ktx:1.15.0` resolves up to it. The SDK's own AAR declares
  `minCompileSdk=1`; it imposes nothing of its own.
- **The dependency footprint is published.** Every runtime-scope dependency, its version and why
  it is there, plus the supported Compose floor (1.7 / Material3 1.3) — in the integration
  guide, next to a note that the AAR size figure excludes all of it.
- **Dead and leaky strings removed.** `uqpay_card_placeholder` ("Card payments are coming
  soon.") had been superseded by the real card form but still shipped in the AAR, and the
  comments in `strings.xml` carried internal jargon — "Slice 4", "Slice 5", "§2c", "M-3 / M-4" —
  that means nothing outside this repository.
- **The sample app is now a store checkout** rather than a box to paste an intent id into:
  a cart with a price breakdown (money as `BigDecimal` throughout), a hard-coded shipping
  address, and a Checkout button that creates the intent and launches the payment. Still
  plain Views and XML, deliberately — it is the proof that a Compose-internal SDK drops
  into a non-Compose host app. It passes `DemoCustomer.billingDetails`, so only the card
  fields need typing. `DemoMerchantBackend` mints its own access tokens in **debug builds
  only**, so the demo runs standalone without re-running a script every half hour; a
  release build carries no API key and says so.
- **Payments launch through an `ActivityResultContract`**, not through a
  `startPayment(activity, request, callback)` call. A callback handed over at launch cannot
  survive process death; the result registry can. The skeleton's signature never shipped.
- **A closed poll window is `PaymentStatus.PENDING`.** The skeleton declared a `TIMEOUT`
  status and it never shipped: running out of patience is not an outcome. The customer may
  have paid moments earlier, and reporting failure there is how a duplicate charge happens.
- **There is no publishable key**, because UQPAY does not issue one. The app holds a
  short-lived access token minted by the merchant's backend, and never the `x-api-key` —
  which can issue refunds and payouts.
- `UQPayErrorCode` and `PaymentMethodType` are open sets rather than enums, so adding a
  code or a wallet is not source-breaking for merchants matching on them exhaustively.
- Documentation corrected where it had drifted: duplicate-submission protection is
  implemented (the integration guide said it was not); a `PENDING` result is **never**
  upgraded by a second callback (the guide implied it might be); `UQPayError.traceId` is
  documented as currently always null rather than as something to quote in support tickets.

### Fixed
- **A `redirect_to_url` or `display_qr_code` `next_action` whose URL is not `https` is now
  treated as an unrenderable action instead of being loaded.** The 3-D Secure redirect URL
  is handed to `WebView.loadUrl` with JavaScript enabled, and `shouldOverrideUrlLoading`
  filters only *later* navigations, never the initial load — so a `javascript:`, `file:`,
  `data:` or cleartext `http:` URL from a compromised or misconfigured gateway went straight
  into the WebView. The QR image loader already refused non-`https`; the redirect path did
  not. The check now lives once, in the engine's `next_action` decoder, so neither screen
  can receive a URL it would have to distrust. Defence in depth: exploiting it needed the
  gateway itself to serve a hostile action.

Pre-release code audit, second pass. Nothing below has shipped, so none of it is a
regression for a merchant — but each was reachable in the code as it stood.

- **A second `launch` could be answered with the first payment's result.** The payment
  Activity was `singleTop`, so a re-launch reused the running instance — which skips
  `onCreate` entirely and delivers the new Intent to `onNewIntent`, which did not exist. The
  second launch's parameters were discarded: the customer carried on paying the *first*
  intent while the merchant's second `launch` waited for a result that could only describe the
  first one. The launch mode is now the default, so every launch gets its own sheet, its own
  parameters and its own result; `onNewIntent` is implemented as a backstop and refuses to
  adopt a different payment.
- **Two Activities on one payment could cut each other off.** `PaymentSession` had no notion
  of how many hosts it had, so the first one destroyed — split-screen, two tasks, or the
  overlap while one instance replaces another — retired the shared scope. The survivor was
  left holding an engine whose coroutines could not run: its confirm launched on a cancelled
  scope, back-press stayed blocked for the full ten seconds waiting on it, and the payment
  settled `PENDING` for a request that never left the device. `PENDING` tells the merchant to
  wait for a webhook, and none was coming — a permanently stuck order. Sessions now count
  their hosts and end only when the last one leaves.
- **An unbounded `Retry-After` could park a live payment for days.** A `429` or `5xx` carrying
  `Retry-After: 999999` was obeyed literally, up to three times over, and nothing above the
  transport could interrupt it. Honoured now up to 10 seconds, clamped in seconds so a
  pathological value cannot overflow into an immediate retry storm.
- **A wallet failure could be reported as `card_declined`.** An attempt that failed with no
  `failure_code` — a QR that expired unscanned, most often — was reported with the card
  decline code, whose message reads "The card was declined. Please try a different payment
  method." to a customer who never entered a card, and files the failure under card declines
  in the merchant's analytics. The fallback is now chosen by payment method: `CARD_DECLINED`
  only for a card, `UNKNOWN` otherwise. A code the gateway actually sent is unaffected.
- **The card form could reach a state with no way out.** Entering an Amex, typing a four-digit
  security code, then replacing the number with a three-digit-code brand left a code the new
  brand forbids: every field looked filled, no error was drawn (errors appear only after a
  submit attempt), and Pay was disabled by the very validation failure it would have
  explained. The security code is now re-truncated by the card number's own setter, so the
  state cannot hold a value the brand forbids however the number is changed. Pay is no longer
  disabled by validation either — tapping an incomplete form reveals every field error, which
  is also the first thing a screen reader can announce about it.
- **Idempotency pins were eligible for Android Auto Backup in every merchant app.** They lived
  in `SharedPreferences`, and every `shared_prefs` file is uploaded when `allowBackup` is left
  at its default — taking a live idempotency key, `ANDROID_ID` and the device IP off the
  device, and restoring a working replay onto a new phone inside the gateway's 24h window. A
  library cannot fix that with backup rules (they are *application* attributes and would
  collide with the merchant's own), so the store moved to a file in
  `Context.getNoBackupFilesDir()`, written through a temp file, `fsync` and an atomic rename.
  The old preferences file is deleted on first read rather than migrated.
- **One flaky read could stall a poll for minutes.** The transport's three retries and their
  backoff sit *inside* a single poll attempt, so one read could occupy well over a minute
  while spending one attempt of budget — a ten-minute wallet QR poll could span hours. A read
  is now abandoned after 45 seconds and held as a failed attempt; the poll itself is untouched
  and still counted in attempts, never in wall-clock.
- **A wallet re-entry could dead-end.** Tapping a wallet whose confirm was already on the wire
  did *literally nothing* — no progress, no error, no state change. Tapping one whose QR had
  already been issued drew that QR with no poller watching it, so a customer could scan it,
  pay, and have the SDK report `CANCELLED` on the next back-press. Both now hand the attempt
  to the engine, which watches it without confirming: the QR is rendered from engine state,
  the wait shows progress with a way out, and leaving reports `PENDING`.
- **A `3xx` on the confirm released the idempotency pin.** Redirects are deliberately not
  followed, so one surfaced as a definitive failure — but a redirect proves nothing about what
  the origin did with the body it was sent. It is now classified as an unknown outcome: the
  pin is kept, the same key is replayed, and the merchant hears `PENDING` rather than a
  `FAILED` that invites the customer to pay again under a fresh key.
- **A confirm could be sent microseconds after the payment was reported.** `settle` did not
  hold the lock that `confirm` reads its state under, so a confirm already past its guard
  could put a request on the wire for a payment the merchant had just been told was finished —
  invisibly, because a `Terminal` state cannot be repainted. Both now serialise on the engine's
  own monitor.
- **The blocked back-press was silent to a screen reader.** §2c requires that a customer who
  cannot leave is told why; changing the sentence on screen tells nobody, because a back-press
  moves no focus and this is the one press in the flow with no other feedback. The blocked
  message is now an assertive live region.
- **3-D Secure ended on any non-`http` URL, in any frame.** A `tel:` link to the bank's support
  line, or the `intent://` deep link an issuer uses for app-to-app authentication, was treated
  as the merchant's return URL and tore down the WebView mid-challenge; so did a sub-frame that
  happened to reach the return URL. Device-handler schemes are now recognised and consumed
  without ending the step, and only a **main-frame** navigation can end it.
- **`initialize` validated nothing.** A blank `clientId` was accepted silently and surfaced
  much later as `AUTHENTICATION_FAILED` on a customer's checkout, which reads like a problem
  with the merchant's UQPAY account. `UQPayConfiguration` now rejects a blank id, and one
  containing a line break (header injection), at construction.

### Known limitations
- `UQPayError.traceId` is **always null**: the gateway returns no correlation header
  (`x-request-id` / `request-id` / `x-b3-traceid`). The field is retained pending sign-off
  with the UQPAY platform team; identify payments by `paymentIntentId` and `transactionId`.
- `DisplayQrCodeDto` does not model the raw EMVCo `qr_code` payload. Rendering it would
  need a QR encoder, which is a dependency decision rather than a patch; the SDK uses the
  gateway's `qr_code_url` image instead.
- Visa 3-D Secure test cards return `system_error` on the current sandbox merchant — Visa
  acquiring appears not to be enabled. Mastercard is the only working 3-D Secure route.
- **The payment sheet is English only.** Every string is in `res/values/strings.xml` under a
  `uqpay_*` name and can be overridden from a merchant's own app (see "Localisation" in the
  integration guide), but no translations ship with the SDK. Given the method list — TrueMoney,
  Touch 'n Go, GCash, DANA, KakaoPay, Toss Pay, Naver Pay, Alipay CN/HK, GrabPay, PayNow —
  that is a real limitation in most of the markets this SDK serves.
- `UQPayPaymentLauncher.cancel()` cannot cancel a payment whose sheet has not appeared yet: in
  the moment between `launch` returning and the Activity creating the session there is nothing
  to cancel, and the call is a no-op. Re-issue it if the condition still holds.
- Not yet verified by a human: physical-device matrix (AC §5.1/§5.2), LeakCanary
  (§10.2), and a manual release-candidate pass (§9.3). Instrumented coverage ran on an API 34
  emulator in CI only.
