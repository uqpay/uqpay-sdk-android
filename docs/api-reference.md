# API Reference

> **Status:** the public surface below is declared, frozen by `apiCheck`, and fully
> implemented — engine, card form, 3-D Secure, wallet QR and bank-transfer instructions
> all ship behind it.
>
> Keep this file in sync with every public API change, in the same change.

Package root: `com.uqpay.sdk`. Everything not listed here is `internal` and may change
without notice.

## `UQPay` (object)

Single public entry point.

| Member | Description |
|---|---|
| `initialize(context, configuration)` | One-time initialization. Stores the configuration and nothing else — no network, no disk, no background work — so it is safe in `Application.onCreate`. Calling again replaces the configuration. |
| `createPaymentLauncher(activity, callback): UQPayPaymentLauncher` | Registers result delivery for a `ComponentActivity`. **Must be called unconditionally in `onCreate`, before the Activity is STARTED.** Throws `IllegalStateException` if the SDK is not initialized. |
| `createPaymentLauncher(caller, callback): UQPayPaymentLauncher` | The same, for any `ActivityResultCaller` — which is to say a `ComponentActivity` **or a `Fragment`**. Added 0.1.0. |
| `isInitialized` | Whether `initialize` has been called. |
| `version` | SDK version string. |

### Hosts: Activity, Fragment, Compose

Both overloads register through `ActivityResultCaller`, which `ComponentActivity` and
`Fragment` each implement — so the SDK serves a Fragment host without adding
`androidx.fragment` to your dependency graph.

```kotlin
// Fragment
class CheckoutFragment : Fragment() {
    private lateinit var payments: UQPayPaymentLauncher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        payments = UQPay.createPaymentLauncher(this) { result -> … }
    }
}
```

The registration rule is the same in either host and the framework enforces it in the
Fragment case: register in `onCreate` (or `onAttach`), unconditionally, every time.
Registering later throws.

**From Compose, do not register inside a composable.** A composable runs when something
decides to compose it, so a registration that has not happened by the time a redelivered
result arrives is a payment outcome dropped on the floor. Create the launcher in the host's
`onCreate` and pass it into the composition — see "Compose hosts" in the
[integration guide](integration-guide.md).

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
| `loggingEnabled` | `Boolean` | Default `false`. Opt-in Logcat diagnostics under the tag `UQPay`. |
| `appearance` | `UQPayAppearance` | How the payment sheet looks. Defaults to stock Material 3 following the device's dark-mode setting. Added 0.1.0. |

The constructor is `@JvmOverloads`, so the three-argument form remains valid from both
Kotlin and Java.

**Throws `IllegalArgumentException`** if `clientId` is blank, or if it contains a line break.
Both are programmer errors, and both are caught where the value is supplied rather than at a
customer's checkout: every request carries `x-client-id`, so a blank one means the gateway
answers `401` and *every* payment fails with `AUTHENTICATION_FAILED` — which reads like a
problem with your UQPAY account rather than a configuration line that was never filled in. A
line break in a value that becomes an HTTP header is header injection, so it is refused rather
than silently trimmed.

Nothing is retained by a rejected configuration, so if you swallow the exception the SDK stays
uninitialized and reports the honest `NOT_INITIALIZED` when a payment is launched.

There is **no publishable key** — UQPAY has no such credential.

### `loggingEnabled` (added 0.1.0)

Off by default; leave it off in a shipped build. On, it emits the SDK's state transitions,
redacted request paths, status codes, trace ids and — the reason it exists — the degraded
paths that otherwise pass in silence: an unwritable idempotency pin store, a discarded pin
blob, a superseded confirm, an exhausted poll budget.

It can never emit a request or response **body**, in any environment. Card number, CVC,
cardholder name, access tokens and API keys are not passed to the logger at all, so no
setting exposes them. The lines do carry payment intent ids; treat those like order ids.

## `appearance` package (added 0.1.0)

### `UQPayAppearance`

How the payment sheet looks — every screen the SDK draws: the method list, the card form,
the wallet QR, the bank-transfer instructions and the 3-D Secure chrome. Set once on
`UQPayConfiguration`; omit it and the sheet renders in stock Material 3.

| Property | Type | Default |
|---|---|---|
| `colorMode` | `ColorMode` | `SYSTEM` |
| `lightColors` | `Colors` | `Colors.MATERIAL_LIGHT` |
| `darkColors` | `Colors` | `Colors.MATERIAL_DARK` |
| `cornerRadiusDp` | `Float` | `12f`, clamped to `0f..28f` |

`ColorMode` is `SYSTEM` (follow the device), `LIGHT`, or `DARK`. Choose an explicit one when
your app forces its own — a light-only checkout on a phone in dark mode would otherwise hand
the customer a dark payment sheet at the last step.

`Colors` is ten `@ColorInt` ARGB ints named after Material 3 roles: `primary`, `onPrimary`,
`background`, `onBackground`, `surface`, `onSurface`, `onSurfaceVariant`, `outline`, `error`,
`onError`. Plain ints, deliberately: the payment UI is Compose internally and that stays
invisible from here, so a host on Views, on Compose, or on a Compose version we have never
heard of configures the sheet the same way.

```kotlin
UQPayConfiguration(
    clientId = "…",
    environment = Environment.SANDBOX,
    tokenProvider = { … },
    appearance = UQPayAppearance(
        lightColors = UQPayAppearance.Colors.MATERIAL_LIGHT.copy(
            primary = 0xFF0B5FFF.toInt(),
            onPrimary = 0xFFFFFFFF.toInt(),
        ),
        cornerRadiusDp = 8f,
    ),
)
```

From Java, use `UQPayAppearance.Builder` and `UQPayAppearance.Colors.Builder(base)` — the
constructors take two adjacent `Colors` and ten adjacent ints respectively, and a positional
call that transposes any of them compiles just as cleanly as the correct one.

Three things to know:

- **Contrast is yours and nothing checks it.** Every `on*` colour has to be readable against
  the surface it is named for. WCAG AA — 4.5:1 for body text, 3:1 for large text and UI
  edges — is the bar. A Pay button nobody can read is a payment that does not happen.
- **`cornerRadiusDp` is clamped, not rejected.** This is built in `Application.onCreate`, and
  throwing there would take your whole app down over a rounded corner.
- **Some things are not themeable, on purpose**: the amount, the cancel affordance, the
  test-mode badge, the copy explaining a blocked back-press, and the text sizes (Material 3
  typography in `sp`, so the sheet honours the customer's font-size setting). Those are how
  a customer knows what they are agreeing to and how to get out of it.

### Test mode is drawn by the SDK, not by you

While `environment` is `Environment.SANDBOX`, every screen of the sheet carries a test-mode
banner. It cannot be themed away or switched off — a badge a merchant can hide is a badge
that is hidden in exactly the build where it mattered. In `PRODUCTION` nothing is drawn.

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

Each call is answered by **exactly one** result for **that call's** payment intent. Launching
a second, different intent while the first sheet is still on screen starts a second payment;
neither one is answered with the other's outcome. Launching the *same* intent twice is not a
second payment — both sheets share one engine, one confirm and one idempotency key — but it is
two launches, so your callback is invoked once for each.

`cancel()` — closes the sheet this launcher most recently opened. Added 0.1.0, for the
merchant-side events that make a sheet on screen wrong: the basket reservation expired, the
order was cancelled from your back office, a push arrived saying the customer paid on another
device.

It cancels the payment; it does not un-send one. The outcome still arrives through your
callback, exactly once, like every other ending:

| State when you call it | What your callback receives |
|---|---|
| Nothing submitted yet | `CANCELLED` |
| **An attempt in the air** — a confirm on the wire, a QR the customer may have scanned, a half-completed 3-D Secure | `PENDING`, never `CANCELLED` |
| Already ended | Nothing new; the real outcome has been or is about to be delivered |

That middle row is the one that carries money. Closing a sheet does not reach into the
gateway and stop a payment, so `PENDING` is the honest answer — treat it exactly as you would
from any other path and wait for the webhook.

Call it from the main thread. It affects only this launcher's most recent `launch`, so a host
holding two launchers cancels each independently. Before any `launch`, or after the sheet has
closed, it is a documented no-op. In the moment between `launch` returning and the sheet
appearing there is nothing to cancel yet, so the call does nothing; re-issue it if your
condition still holds.

`UQPayPaymentLauncher` is implemented by the SDK. Merchants use the instance they are handed
and do not implement the interface — it gains members as the SDK grows, and `cancel()` was
one of them.

### `PaymentSessionParams`

| Property | Type | Notes |
|---|---|---|
| `paymentIntentId` | `String` | Created by your backend (`PI…`). Intents auto-expire after 30 minutes. |
| `presentation` | `Presentation` | Defaults to `MethodList`. |
| `billingDetails` | `BillingDetails?` | Prefills the card form's billing section. Defaults to `null`. Added 0.1.0. |
| `allowedPaymentMethods` | `Set<PaymentMethodType>?` | Restricts which of the intent's methods this payment may use. `null` — the default — means no restriction. Added 0.1.0. |

`Presentation` is a sealed class: `MethodList` (every method the intent offers, gateway
order, card first), `CardOnly`, and `SingleWallet(method)`.

#### `allowedPaymentMethods` (added 0.1.0)

For a per-region or per-risk-tier rule — "cards and PayNow only for this customer" — decided
by your backend, with one intent shape behind it. Before this, the only ways to express that
were an all-methods sheet (which ignores the rule) or `SingleWallet` (which can express
exactly one method, and no card).

```kotlin
payments.launch(
    PaymentSessionParams(
        paymentIntentId = intentId,
        allowedPaymentMethods = setOf(PaymentMethodType.CARD, PaymentMethodType.PAYNOW),
    ),
)
```

- **It only ever narrows.** The sheet shows the intersection of your set, the intent's own
  `available_payment_method_types`, and what this SDK version can render — in the gateway's
  order, card first, unchanged. Naming a method the intent does not offer adds nothing and is
  not an error: the intent is the authority on what is payable.
- **An empty set is honoured, not widened.** The sheet says no methods are available. Widening
  a restriction because it came out empty is how a risk control becomes a decoration; if your
  rules can produce an empty list, do not launch the sheet.
- **With `CardOnly` or `SingleWallet`**, which each name one method: if that method is not in
  the set the request contradicts itself, and the payment ends immediately with `FAILED` and
  `INVALID_PAYMENT_METHOD` — before any network call, and without the customer being shown a
  method they were not allowed to use. `UQPayError.developerMessage` names the contradiction.
- A method this SDK version predates can sit in the set harmlessly; it restricts nothing that
  could have been shown anyway.

#### `PaymentSessionParams.BillingDetails` (added 0.1.0)

Values your app already knows, used to **prefill** the card form so the customer types
only the card itself. Every property is an optional `String?` and every one is left out
by default:

| Property | Notes |
|---|---|
| `firstName`, `lastName` | Cardholder name. |
| `email`, `phone` | Billing contact. |
| `addressLine1`, `addressLine2` | Street address; the SDK joins them into the single `street` the gateway takes. |
| `city`, `state`, `postalCode` | Billing address. |
| `countryCode` | ISO 3166-1 alpha-2, e.g. `"SG"`. Case-insensitive. |

```kotlin
launcher.launch(
    PaymentSessionParams(
        paymentIntentId = intentId,
        billingDetails = PaymentSessionParams.BillingDetails(
            firstName = "John",
            lastName = "Tan",
            email = "john.tan@example.com",
            phone = "+6591234567",
            addressLine1 = "123 Orchard Road",
            city = "Singapore",
            postalCode = "238888",
            countryCode = "SG",
        ),
    ),
)
```

Four rules this type keeps, none of them negotiable:

- **Card number, expiry and security code are not prefillable, and never will be.** There
  are no properties for them. Accepting them would mean a merchant app holding card data
  and a PAN travelling through an `Intent` extra, where the OS may write it to disk.
- **The customer can edit everything you send.** These are ordinary text fields with your
  values typed into them; what reaches the gateway is what the form holds when Pay is
  tapped.
- **Nothing is persisted or logged.** The values travel in the launch parcel and seed the
  form's in-memory state. `toString()` redacts `email` and `phone` — a crash reporter that
  stringifies your params must not capture a customer's contact details.
- **An unrecognised `countryCode` is ignored, not rejected.** The picker then opens on the
  device's own region. A merchant typo costs the customer one tap, never a payment that
  cannot be started.

Omitting `billingDetails` (or passing `null`) leaves every field empty — exactly the
behaviour of a launch that never mentions it.

##### `BillingDetails.Builder` (added 0.1.0)

The constructor takes ten `String?` in a row, six of them address lines. From Kotlin, name
them. **From Java there are no named arguments**, and

```java
new BillingDetails(f, l, e, p, a1, a2, "Singapore", "Singapore", "238888", "SG")
```

compiles exactly as cleanly with `city` and `state` transposed — which sends wrong AVS data
on every payment, forever, with nothing to notice it by. The builder puts the field name
beside the value:

```java
BillingDetails billing = new BillingDetails.Builder()
    .firstName("Jo").lastName("Tan")
    .city("Klang").state("Selangor")
    .postalCode("41000").countryCode("MY")
    .build();
```

Every setter returns `this`, every field is optional, and `build()` is safe to call more than
once.

### `PaymentCallback` (fun interface)

`onResult(result: PaymentResult)` — invoked **exactly once per `launch` call**, on the
**main thread**, across configuration changes and process death. The result always names the
intent that launch was given, in `PaymentResult.paymentIntentId`.

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

`code: UQPayErrorCode`, `message: String`, `declineCode: String?`, `traceId: String?`,
`developerMessage: String?`.

#### Two messages, and they are not interchangeable (changed 0.1.0)

| | Written for | Where it belongs |
|---|---|---|
| `message` | the **shopper** | on screen |
| `developerMessage` | **you** | a log line or a bug report — never on screen |

`message` is a complete sentence in the app's language (see
[Localisation](integration-guide.md#localisation)). It says what happened and, where the
customer can do something about it, what that is. Where they cannot — an uninitialised SDK, a
rejected merchant token, a malformed request — it says the payment could not be started
rather than naming a fault the customer has no part in. It **never** quotes the gateway's own
text, in either environment, and it is identical in `SANDBOX` and `PRODUCTION`, which is what
makes it safe to put in front of a customer from a build you have not shipped yet.

`developerMessage` is the technical description: what the SDK was doing, what came back, and
where to look. English, never localised, never stable enough to parse, `null` when there is
nothing to add. In `SANDBOX` it also carries the gateway's own message so you can debug; in
`PRODUCTION` it never does, because gateway text is documented as unsafe to surface and a
crash reporter is a surface.

Both are **always safe to log** — neither ever contains a PAN, CVV, expiry, token, or PII.

```kotlin
PaymentStatus.FAILED -> {
    showError(result.error?.message)                                  // the shopper's
    Log.w(TAG, result.error?.developerMessage.orEmpty())              // yours
}
```

> **Migration note.** Before 0.1.0 the gateway's sandbox detail was appended to `message`, so
> the same string meant two different things depending on which environment the build pointed
> at. That detail now lives in `developerMessage`. If you were parsing sandbox `message` text,
> read `developerMessage` instead — and if you were showing `message` to shoppers, you can now
> keep doing that in every environment.

> **`traceId` is very often `null`, and today it is always `null`.**
>
> It carries the gateway's correlation header when one is present — the reader accepts
> `x-request-id`, `request-id` and `x-b3-traceid` — but the UQPAY gateway does not
> currently return any of them. Three live sandbox captures found none, and the shipped
> iOS SDK reads none across 87 source files.
>
> So: **quote `paymentIntentId` in support tickets**, and `transactionId` (the payment
> attempt id) when you have one. Treat a non-null `traceId` as a bonus, never as something
> your support flow depends on. The field is kept rather than removed so that the day the
> gateway does emit a correlation header, merchants get it without an API change —
> pending sign-off with the UQPAY platform team (tracked as F8).

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
- `com.uqpay.sdk.engine` — the payment state machine, confirm runner and poller.
- `com.uqpay.sdk.store` — the persisted idempotency pins.

## Java interoperability

Verified by a Java test source set in the SDK's own build
(`uqpay-sdk/src/test/java/com/uqpay/sdk/javaconsumer/`), so a regression here fails to
compile rather than being discovered by a merchant:

- Every `UQPay` member is `static` from Java.
- `PaymentCallback` and `UQPayTokenProvider` are SAM interfaces — Java lambdas work.
- `PaymentSessionParams`, `PaymentSessionParams.BillingDetails` and `UQPayConfiguration`
  carry `@JvmOverloads` constructors, so their optional trailing arguments can be omitted
  from Java. `new PaymentSessionParams("PI_1")` and the two-argument form both still exist
  unchanged after `billingDetails` was added.
- `PaymentStatus` is an `enum` and switches. `UQPayErrorCode` and `PaymentMethodType` are
  open sets, not enums: compare with `equals`, widen with `of(String)`.
- `PaymentResult` is read-only in practice; its constructor takes all nine arguments from
  Java (no `@JvmOverloads`), which is deliberate — merchants receive results, never build
  them. `UQPayError` is the same: merchants read errors, they do not construct them.
- `createPaymentLauncher` has a `ComponentActivity` overload and an `ActivityResultCaller`
  overload; Java overload resolution picks the first for an Activity and the second for a
  `Fragment`, with no cast.
- **Builders, where a positional call would be dangerous**:
  `PaymentSessionParams.BillingDetails.Builder`, `UQPayAppearance.Builder` and
  `UQPayAppearance.Colors.Builder`. These types have ten adjacent `String?`, two adjacent
  `Colors`, and ten adjacent `int`s respectively — the three shapes where Java's lack of
  named arguments turns a transposition into a silent, permanent bug. `UQPayAppearance` and
  `Colors` deliberately carry **no** `@JvmOverloads`, so there is no family of positional
  constructors to miscount.
