# Architecture Overview

> **Status:** Proposed design, agreed 2026-08-12. Supersedes the skeleton described in
> `api-reference.md`, which is updated when the API lands in code.
>
> Derived from a full read of the shipped **UQPAY iOS SDK** (`develop` @ `8d3b4c3`,
> `1.0.0-rc.1`) and its `ACCEPTANCE_CRITERIA_GAPS.md` audit. Where iOS made a decision
> that works, we copy it so a merchant sees identical behaviour on both platforms.
> Where iOS has a known open gap, we build the fix in from the start rather than
> inherit the debt. Both cases are marked below.

## 1. Principles

1. **The engine is headless.** Every money-affecting decision lives in a plain Kotlin
   state machine with no Android UI types. iOS put confirm, 3DS, polling and reporting
   inside a 2,872-line view controller, which is why its lifecycle and integration-test
   gaps are still open. Ours is testable with a fake clock and MockWebServer.
2. **Exactly one terminal outcome per payment**, enforced structurally, not by
   discipline.
3. **The app never holds a merchant secret.** Auth is a short-lived token issued by the
   merchant's backend.
4. **The client result is advisory.** The webhook is the authority. Every doc says so.
5. **Nothing sensitive is ever logged, persisted, or put in an `Intent`.**
6. **Minimal dependency footprint.** Every transitive dependency is a tax on every
   merchant app.

## 2. Artifact and package layout

One published artifact, `com.uqpay:uqpay-sdk-android`. iOS split into three
CocoaPods and shipped them structurally broken (its audit item R3); we take the split
only if a headless-only consumer asks for it, and it can then be additive under the
same umbrella coordinate.

```
uqpay-sdk/src/main/kotlin/com/uqpay/sdk/
├── UQPay.kt                     public  — initialize / isInitialized / version
├── UQPayConfiguration.kt        public  — clientId, environment, tokenProvider, appearance
├── Environment.kt               public  — SANDBOX | PRODUCTION
├── auth/
│   ├── UQPayTokenProvider.kt    public  — fun interface, merchant-implemented
│   ├── UQPayAuthToken.kt        public  — token + expiry
│   └── BackendTokenProvider.kt  public  — convenience impl over an HTTPS route
├── payment/
│   ├── UQPayPaymentLauncher.kt  public  — ActivityResult-based entry point
│   ├── PaymentSessionParams.kt  public  — paymentIntentId + presentation options
│   ├── PaymentResult.kt         public  — terminal outcome
│   ├── PaymentStatus.kt         public  — SUCCEEDED | FAILED | CANCELLED | PENDING
│   ├── PaymentCallback.kt       public  — fun interface
│   └── PaymentMethodType.kt     public  — card + wallet identifiers
├── error/
│   ├── UQPayError.kt            public  — code, message, declineCode, cause
│   └── UQPayErrorCode.kt        public  — extensible value class
├── appearance/                  public  — colours, shapes, merchant display name
└── internal/
    ├── net/          HTTP client, auth interceptor, error mapping, retry policy
    ├── model/        wire DTOs (kotlinx.serialization), lenient unknown-value decoding
    ├── engine/       PaymentEngine, state machine, IntentPoller, Clock, idempotency
    ├── store/        persisted session + idempotency pins
    └── ui/           UQPayPaymentActivity, Compose screens, 3DS WebView
```

`internal/` is Kotlin-`internal` throughout and excluded from the public API dump.

## 3. Public API surface

Kotlin-first, fully Java-interoperable. This is a permanent compatibility commitment
once released — it is deliberately small.

```kotlin
// One-time init. No network, no disk, no work before this call.
UQPay.initialize(context, UQPayConfiguration(
    clientId      = "…",             // x-client-id, from your backend
    environment   = Environment.SANDBOX,
    tokenProvider = myTokenProvider, // supplies short-lived x-auth-token
    appearance    = UQPayAppearance(),
))

// Per payment. Created during Activity/Fragment initialization.
private val launcher = UQPayPaymentLauncher.create(this) { result: PaymentResult ->
    when (result.status) {
        PaymentStatus.SUCCEEDED -> …   // advisory; confirm from the webhook
        PaymentStatus.FAILED    -> showError(result.error!!.message)
        PaymentStatus.CANCELLED -> …
        PaymentStatus.PENDING   -> …   // still live; the webhook will settle it
    }
}

launcher.launch(PaymentSessionParams(paymentIntentId = "int_1a2b3c4d5e"))
```

### Why the launcher, not `startPayment(activity, request, callback)`

`registerForActivityResult` gives exactly-once delivery across rotation **and process
death** from the framework, which is acceptance §7 and §8 for free. A raw callback
would mean rebuilding that by hand — the hardest part of the criteria — and getting it
subtly wrong. `UQPay.initialize` remains the only other public entry point.

Contract: `create()` must be called before the host reaches `STARTED` (the standard
`registerForActivityResult` rule). Documented, and the SDK fails loudly with a
programmer-error exception if violated.

### `PaymentResult`

| Property | Type | Notes |
|---|---|---|
| `status` | `PaymentStatus` | terminal; exactly one delivery |
| `paymentIntentId` | `String` | |
| `paymentMethodType` | `PaymentMethodType?` | `CARD`, `WECHAT_PAY`, `GRABPAY`, … |
| `amount` | `BigDecimal?` | **major units.** iOS models this as `Double` — money in a binary float is a defect we do not copy |
| `currency` | `String?` | |
| `merchantOrderId` | `String?` | |
| `transactionId` | `String?` | the attempt id |
| `completedAt` | `Instant?` | |
| `error` | `UQPayError?` | non-null iff `FAILED`; may also be set on `PENDING` to carry `TIMEOUT` |

### `PaymentStatus` — four terminal values, and why `PENDING` replaces `TIMEOUT`

`SUCCEEDED`, `FAILED`, `CANCELLED`, `PENDING`.

The skeleton's `TIMEOUT` status is wrong for a payment SDK. When a QR wallet poll
window closes, the customer may have already paid in another app — the payment is
*live*, not failed and not timed out. Reporting anything but "unresolved" invites a
double payment. iOS learned this and added `paymentDidBecomePending` for exactly this
case. So a timeout becomes `PENDING` carrying `UQPayErrorCode.TIMEOUT`, and the merchant
is told plainly: stop your spinner, wait for the webhook.

`CANCELLED` is never delivered after any other outcome — leaving a pending screen is not
an abandonment.

## 4. Auth model

Replaces `publishableKey` entirely. There is no publishable key in the UQPAY API.

```kotlin
public fun interface UQPayTokenProvider {
    public suspend fun fetchToken(): UQPayAuthToken   // value + expiresAt
}
```

- The SDK caches the token and refreshes on a margin (~2 min) before expiry.
- A `401` invalidates the cache and retries the call exactly once.
- `BackendTokenProvider(endpoint, decorate)` ships as a convenience so most merchants
  write no code; `decorate` attaches their own user-session header.
- A Java-friendly `UQPayTokenProvider.Blocking` variant wraps the suspend function.

**Non-negotiable, and documented at every mention:** the merchant's `x-api-key` never
enters the app. It can issue refunds and payouts, and Global Acquiring permits only one
active token per merchant — a device minting its own tokens would invalidate the
merchant's backend.

Per-payment values (`paymentIntentId`) travel in `PaymentSessionParams`, **not** on the
config singleton. iOS put them on `UqpayConfiguration.shared` and had to document "do
not run two payments concurrently in one process"; that limitation does not exist here.

## 5. Payment engine

### State machine

```
Idle
 └─► LoadingIntent ──► (terminal intent?) ──► Terminal(FAILED: INTENT_ALREADY_TERMINAL)
        │
        └─► SelectingMethod ──► Confirming
                                   │
                     ┌─────────────┼──────────────┐
                     ▼             ▼              ▼
              RequiresAction   Polling      Terminal(…)
              (ThreeDS | Qr)      │
                     └────────────┘
```

Rules enforced by the machine, not by screens:

- `Terminal` is entered once, via an atomic compare-and-set. Every later attempt is
  dropped. (iOS's `hasReportedOutcome`, moved down a layer.)
- `Confirming` is single-flight per session — the duplicate-submission guard for
  double-tap, plus the idempotency pin for everything harder.
- Entering the flow re-reads the intent and refuses a non-payable one. iOS does not do
  this (its audit item U2): presenting a sheet for an already-`SUCCEEDED` intent gives
  the customer a working payment form.
- After any external step, the outcome is **re-read from the API**, never inferred from
  a return URL, WebView result, or query parameter.

### Idempotency — persisted from day one

iOS keeps its idempotency pins in a static in-memory map, so a process kill during an
unresolved confirm loses the pin and the retry mints a fresh key. That is its open
double-charge hole (audit item P1). We persist:

- `IdempotencyStore`, keyed by `paymentIntentId + payloadHash`, holding the key and the
  **frozen** device values (IP, screen metrics) — these change between sends and would
  otherwise break byte-equality.
- Canonical JSON encoding with sorted keys, so a replay is byte-identical. An idempotent
  server rejects a reused key whose payload changed.
- Outcome-unknown (`IOException`, `3xx`, `429`, `5xx`, un-parseable `2xx`) **keeps** the
  pin; a definitive answer clears it; cancellation leaves it untouched. `3xx` counts because
  redirects are not followed: a redirect proves nothing about what the origin did with the
  body it was sent.
- TTL matched to the server's idempotency window; restored on first use after launch.
- **No card data, ever.** The store holds a hash, a UUID, and device metrics.
- Stored as one file in `Context.getNoBackupFilesDir()`, never `SharedPreferences`. Every
  `shared_prefs` file is uploaded by Android Auto Backup, which defaults to on — that would
  put a live idempotency key, `ANDROID_ID` and the device IP in the cloud from every merchant
  app, and a restored backup restores a working replay. A library cannot fix that with backup
  rules (they are *application* attributes and would collide with the merchant's own), so the
  data lives where the platform already excludes it.

### Polling and lifecycle — the iOS gap we do not inherit

iOS has zero app-lifecycle handling (audit item U1, its largest functional gap): poll
deadlines are wall-clock, so a customer who leaves to complete 3DS in their banking app
returns to a payment that "timed out" while nothing was actually wrong.

- Poll scheduling uses `SystemClock.elapsedRealtime()`; the give-up condition is an
  **attempt budget**, not a wall-clock deadline.
- A `DefaultLifecycleObserver` re-reads the intent immediately on `onStart`, ahead of
  the next scheduled poll.
- Backoff `2s → 5s → 15s` instead of iOS's flat 2s/30s, so a backgrounded app is not
  burning battery.
- A confirm in flight is allowed to finish; if the process dies anyway, the persisted
  pin makes the replay safe.
- `Clock` is an injected interface, so every timing rule is unit-testable without
  waiting.

## 6. Networking

**OkHttp + kotlinx.serialization. No Retrofit, no Gson/Moshi.** A payment SDK that drags
a converter stack into every merchant app is a liability; OkHttp is already present in
practically every app.

- One client, one auth scheme: `x-auth-token`, `x-client-id`, `x-idempotency-key` on
  mutating calls. iOS carries two parallel clients with two different auth schemes, one
  of which sends a long-lived secret it is separately trying to delete (its audit item
  S1). We ship one.
- HTTPS only, enforced by `cleartextTrafficPermitted=false` in the library manifest.
- Wire enums decode unknown values to `Unknown(raw)` so a new server-side status cannot
  break a shipped app. Copied from iOS verbatim — it is one of its best decisions.
- Amounts are **decimal strings in major units** (`"8.98"`), parsed to `BigDecimal`.
  Never minor units, never `× 100`.
- No response caching for payment traffic (the iOS equivalent of its ephemeral
  `URLSession`): no disk cache, no cookie jar.
- Retry only on `IOException` / `429` / `5xx`, always replaying the same idempotency key.
  A `Retry-After` is honoured up to a bound (10s): unbounded, one header can park a live
  payment for days with nothing above the transport able to interrupt it.

### Endpoints used by the SDK

| Call | Purpose |
|---|---|
| `GET  /api/v2/payment_intents/{id}` | read status; the source of truth after every external step |
| `POST /api/v2/payment_intents/{id}/confirm` | submit the payment method |

Token issuance and intent creation belong to the merchant backend and are never called
from the app.

## 7. 3D Secure

Both 3DS steps run in an in-SDK `WebView`:

- `next_action.redirect_iframe` carries a self-submitting `method="POST"` form. It
  **must** be loaded as HTML (`loadDataWithBaseURL`) — rewriting it as a GET navigation
  drops the POST body and authentication fails.
- The challenge (`redirect_to_url`) runs in the same WebView, so there is one code path,
  no app-switch round trip, and no intent-filter registration for merchants.
- Cookies and DOM storage are cleared when the **payment** ends, never when the WebView is
  torn down. A teardown is a configuration change as often as it is the end of a payment,
  and the ACS sets a session cookie between the fingerprint step and the challenge that the
  challenge cannot be completed without — clearing on teardown ends the customer's
  authentication when they rotate the phone. The clear runs from
  `UQPayPaymentActivity.onDestroy`, under the same `isFinishing && !isChangingConfigurations`
  predicate that releases the engine's session, and is **scoped to the origins the 3DS step
  visited**: `CookieManager` is process-global, and `removeAllCookies` inside a payment SDK
  signs the merchant's own web views out on every card payment. iOS gets both properties
  from `WKWebsiteDataStore.nonPersistent()`; Android has no per-WebView store.
- A dead WebView renderer is handled (`onRenderProcessGone` returns true), so a renderer
  crash or an out-of-memory kill during a challenge does not take the merchant's process
  with it; the screen says verification was interrupted and the poller settles the payment.
- The return URL is detected in `shouldOverrideUrlLoading` purely as a *signal that the
  step ended*. Its query parameters are never trusted; the outcome comes from re-reading
  the intent.
- JavaScript is enabled (3DS requires it); no JS bridge is installed, file access is off.

## 8. Wallet QR flow

One screen for all wallets, parameterised by method — iOS collapsed six near-identical
copies into one and it was the right call.

- Exactly one confirm per (intent, method); re-downloading an already-issued QR is
  always allowed. A dropped image request must not strand the customer, and a second
  confirm must not open a second live attempt.
- Poll until terminal or the attempt budget is exhausted → then `PENDING`, never
  `FAILED`.
- QR expiry is surfaced from `display_qr_code.expires_at`.

## 9. UI

Jetpack Compose + Material3, inside `UQPayPaymentActivity` (a `ComponentActivity`). The
merchant needs no Compose in their own build; the AAR ships pre-compiled.

- Compose is declared `implementation` at a **conservative floor version**, and no
  Compose type appears in the public API — so we never force an upgrade on a merchant
  who pins their own Compose versions.
- Theming through `UQPayAppearance` (a light and a dark palette of ten Material 3 colour
  roles as plain ARGB ints, plus a corner radius), with a real light/dark scheme. iOS
  force-disables dark mode in seven view controllers while its public API advertises
  adaptive colours (audit item U3); ours follows the system by default and takes an explicit
  `colorMode` of `LIGHT` or `DARK` for a host app that forces its own — which the device
  setting alone cannot express, and which the old `Theme.AppCompat.DayNight` window theme
  got wrong for exactly that host.
- The payment Activity is `androidx.activity.ComponentActivity`, on the SDK's own window
  theme. It deliberately does **not** extend `AppCompatActivity`: appcompat was a runtime
  dependency imposed on every merchant for the sake of one window theme, and its DayNight
  behaviour was the device's decision rather than the merchant's.
- A **test-mode badge** on every screen while the SDK points at `Environment.SANDBOX`, drawn
  by the SDK and not themeable away. A sandbox sheet that is pixel-identical to a live one is
  how a QA pass ends up running against real money.
- **Accessibility and scaling are requirements, not polish**: `sp` typography that
  scales, semantics/`contentDescription` on every control and status icon, minimum touch
  targets, RTL-safe layout. iOS has 5 accessibility attributes and 0 scaling support
  across its whole sheet (audit item U5) — that is the debt being avoided.
- All customer-facing strings in `strings.xml` from the first commit (iOS has zero
  localization, audit item U4) — **including the sentences in `UQPayError.message`**, which
  were hardcoded in `ErrorMapper` until 0.1.0 and made the SDK untranslatable without an API
  change. English ships; more can be added without touching code, and a merchant can override
  any `uqpay_*` name from their own app's `values-<language>/` because app resources beat
  library resources during merging.
- Amounts are rendered with the platform's currency formatter rather than concatenated, so
  the minor unit is the currency's (JPY, KRW and VND have none) and the separators and symbol
  placement are the locale's.
- Back press during payment resolves to `CANCELLED` — defined, never silent.

## 10. Errors

```kotlin
@JvmInline
public value class UQPayErrorCode private constructor(public val raw: String) {
    public companion object {
        public val CARD_DECLINED: UQPayErrorCode = UQPayErrorCode("card_declined")
        public val THREE_DS_FAILED: UQPayErrorCode = UQPayErrorCode("3ds_failed")
        // …
    }
}
```

A public `enum class` is source-breaking to extend when merchants `when` exhaustively —
iOS flags exactly this on its own error enum (audit item A4). A raw-backed value class is
additive forever and still reads naturally with an `else` branch.

`UQPayError` carries `code`, `message`, `developerMessage: String?`, `declineCode: String?`
(the acquirer's raw code) and `traceId: String?`.

**The two messages are for two audiences and are not interchangeable.** `message` is written
for the shopper, comes from `strings.xml`, never quotes the gateway, and is identical in both
environments — which is what makes it safe for a merchant to put on screen from a build they
have not shipped yet. `developerMessage` is written for the integrator, is English and
unlocalised, and is the only one that ever carries the gateway's own text (in `SANDBOX`
only). Both are safe to log; neither ever contains a PAN, CVV, expiry, token or PII.

**One shared mapper** translates a server `failure_code` + intent status into a
`UQPayErrorCode`, and *every* payment method routes through it. iOS's card path maps
real codes while its wallet path hardcodes `unknown` for every failure, so the same
server response yields different codes depending on which screen the customer used
(audit items E1/E2). A table test asserts every published code is produced by at least
one real path.

## 11. Threading

- Engine work on `Dispatchers.IO`, scoped to the payment session.
- All merchant-facing callbacks on the main thread, exactly once.
- No blocking calls on the main thread — nothing in the SDK does main-thread network or
  disk I/O (acceptance §10).
- Coroutines are an internal implementation detail; the public API is callback-based so
  Java merchants are first-class.

## 12. Security invariants

Each one is a test, not a convention:

1. No PAN / CVV / expiry / token / key / PII in Logcat, exceptions, crash reports, saved
   state, or test fixtures. Card data exists only in memory for the life of a confirm.
2. Card data is never written to disk, `SharedPreferences`, a database, or
   `savedInstanceState`, and never placed in an `Intent` extra.
3. HTTPS only; no cleartext permitted by manifest or network-security-config.
4. Logging is off by default and redacts in release builds regardless.
5. Consumer R8 rules ship in the AAR, and CI builds the sample app minified.
6. No analytics, no tracking, no third-party SDK phoning home.

## 13. Testing strategy

| Layer | How |
|---|---|
| Engine / state machine | Plain JVM unit tests, fake `Clock`, fake transport |
| HTTP + error mapping | MockWebServer |
| Idempotency & persistence | JVM tests incl. simulated process death (store reload) |
| Full flow: confirm → 3DS → poll → report | **Stubbed integration suite** over MockWebServer |
| Compose screens | Compose UI tests + accessibility assertions |
| Lifecycle | Instrumented: rotation mid-payment, background/foreground, process death |

The stubbed flow suite is deliberately first-class: it is iOS's weakest area (audit item
T3), and its live E2E tests hit sandbox rails that move real money, so they cannot run in
CI. Ours never touch a live acquirer.

**CI from the first commit** — unit tests, lint, `apiCheck`, minified sample build.
iOS has no CI at all (audit item T2), and every other guarantee rests on it.

## 14. Build and tooling

- Explicit API mode (already on) + **binary-compatibility-validator** (`apiDump` /
  `apiCheck`), so the frozen public surface is enforced by CI rather than by review.
- All versions in `gradle/libs.versions.toml`. New entries needed: OkHttp,
  kotlinx-serialization, Compose BOM + Material3, activity-compose,
  lifecycle-runtime-compose, MockWebServer, Turbine, Robolectric.
- `consumer-rules.pro` in the library module.
- Sources + javadoc jars, correct POM metadata, semantic versioning per
  `docs/release-process.md`.

## 15. Mapping to `docs/acceptance-criteria.md`

| § | Criterion | How this architecture satisfies it |
|---|---|---|
| 1 | Integration | One artifact, one init call, one launcher; sample app mirrors it |
| 2 | API | Small surface, `apiCheck`-enforced, value-class error codes are additive |
| 3 | Payment flow | State machine with a single atomic terminal transition; `PENDING` models the unresolved case honestly |
| 4 | Security | §12 invariants, each backed by a test |
| 5 | Compatibility | minSdk 24; Compose is a library so behaviour is uniform; explicit dark-mode handling below API 29 |
| 6 | Error handling | One shared `failure_code` mapper for every method; stable raw codes |
| 7 | Callbacks | ActivityResult delivery — once, main thread, survives process death |
| 8 | UI/UX | Lifecycle observer, single-flight confirm, terminal-intent guard, back → `CANCELLED` |
| 9 | Testing | §13, incl. the stubbed full-flow suite and interrupted-payment cases |
| 10 | Performance | Zero work before `initialize`; no main-thread I/O; polling backs off |
| 11 | Release | Version catalog, semver, `apiCheck`, R8 rules, signed artifacts |
| 12 | Documentation | This doc + api-reference, error-codes, integration guide, troubleshooting |

## 16. Decisions taken, with rationale

| Decision | Chosen | Why |
|---|---|---|
| UI toolkit | Jetpack Compose | Dark mode, font scaling, TalkBack and RTL are near-free; these are four of iOS's open items |
| Entry point | ActivityResult launcher | Exactly-once delivery across process death comes from the framework |
| 3DS | In-SDK WebView, both steps | `redirect_iframe` requires a WebView anyway; one path, no merchant intent-filter setup |
| v1 scope | Card + all QR wallets (iOS v1 parity) | Merchants get the same methods on both platforms; shared error mapper + stubbed wallet tests are built in from the start to avoid iOS's E1/T3 |
| Artifact split | Single AAR | iOS's three-pod split shipped broken; a split stays possible additively |
| HTTP | OkHttp + kotlinx.serialization | No converter stack forced on merchants |
| Money type | `BigDecimal` | iOS uses `Double` for amounts; floats are not a money type |

## 17. Deferred to v2

Apple Pay's counterpart (Google Pay), native wallet app hand-off (WeChat/Alipay SDK
integration), certificate pinning (record the decision explicitly, as iOS did), and a
headless-only artifact split.
