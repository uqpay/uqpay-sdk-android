# Integration Guide

> **Status:** the public API below is final, and the payment flow behind it is
> implemented — card with 3-D Secure, wallet QR, bank-transfer instructions, persisted
> idempotency, rotation and process-death recovery. What has *not* happened yet is a
> manual pass on physical devices and a published Maven artifact; see
> [`acceptance-criteria.md`](acceptance-criteria.md) for the release checklist.

## Requirements

- Android `minSdk 24` (Android 7.0) or higher
- **`compileSdk 34` or higher** in your app — see "Your app's build level" below
- Kotlin `2.0` or higher, if your app is written in Kotlin (Java-only apps are unaffected)
- JDK 17 to run the build (Android Gradle Plugin 8 requires this already)
- Kotlin or Java host app, Views or Compose — the SDK works the same either way
- Internet permission (declared by the SDK's manifest, merged automatically)

### Your app's build level

`compileSdk 34` is the floor, and it is a floor rather than a target: **every version this
SDK depends on is declared as the lowest one that does the job, not the newest available.**
Gradle resolves the highest version across your whole app, so if you are already on newer
androidx or Compose than we are, yours wins and nothing here changes for you.

That matters because androidx artifacts carry a hard `minCompileSdk` that becomes *your*
build error, naming androidx rather than us. The SDK's own AAR imposes nothing
(`minCompileSdk=1`); the floor comes entirely from what it depends on:

| | `minCompileSdk` |
|---|---|
| `androidx.core:core-ktx:1.13.1` | 34 |
| `androidx.activity:activity:1.9.3` | 34 |
| `androidx.lifecycle:*:2.8.7` | 34 |
| Compose 1.7.6 / Material3 1.3.1 | 34 |

If your app is on `compileSdk 33` or lower you will need to raise it — but note that Google
Play has required `targetSdk 35` for updates since 2025, and `compileSdk` must be at least
`targetSdk`, so any app still shipping updates is already well above this floor.

## What this SDK depends on

The published AAR is around 760 KB. **That number is the AAR alone and excludes everything
below**, all of which is runtime-scope and therefore lands in your app's dependency graph
whether or not you use it directly. A payment SDK's real cost is this list, so it is
published rather than left to be discovered from a POM:

Every version below is the **lowest** one that does the job, not the newest available.
Gradle resolves the highest version across your app, so if you are ahead of us, you stay
ahead of us. See "Your app's build level" above for why that is deliberate.

| Dependency | Version (floor) | Why |
|---|---|---|
| `androidx.core:core-ktx` | 1.13.1 | not used directly; declared so the floor is a choice rather than a side effect of `activity`'s own dependencies |
| `androidx.activity:activity-ktx` | 1.9.3 | `ActivityResultContract` — the result delivery that survives process death |
| `androidx.activity:activity-compose` | 1.9.3 | hosts the payment UI |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.8.7 | |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.8.7 | |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate` | 2.8.7 | screen state across process death |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.8.7 | |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.8.7 | |
| `androidx.compose.ui:ui` | via Compose BOM 2024.12.01 | the payment sheet |
| `androidx.compose.material3:material3` | via Compose BOM 2024.12.01 | |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.9.0 | |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.7.3 | wire decoding |

What is **not** here, deliberately:

- **No OkHttp, Retrofit, Gson or Moshi.** The HTTP layer is `HttpsURLConnection`, so the SDK
  cannot force a version conflict on the networking library you already use.
- **No analytics, crash reporting or tracking of any kind.**
- **No `androidx.appcompat`.** It used to be here for one thing — a window theme on the
  payment Activity — and it is gone as of 0.1.0.
- **No `androidx.fragment`.** The Fragment host path works through `androidx.activity`'s
  `ActivityResultCaller`, which `Fragment` implements.

### Compose versions

The SDK's payment UI is Compose internally and **no Compose type appears in its public API**,
so your app does not have to adopt Compose to use it — the sample app is plain Views and XML
for exactly that reason.

If your app *does* use Compose, the two graphs are resolved together by Gradle, so the usual
rules apply:

- **Supported floor: Compose 1.7 / Material3 1.3.** The SDK is built against Compose BOM
  2024.12.01 (Compose 1.7.6, Material3 1.3.1) and uses no API newer than that line. Below 1.7
  is not supported: it predates the K2 Compose compiler plugin.
- **Newer is fine.** Your BOM wins, and the SDK's calls are all in the stable surface.
- If you pin Compose artifacts individually rather than through a BOM, make sure
  `androidx.compose.runtime` and `androidx.compose.ui` come from the same release.

## How a UQPAY payment works

Three parties, and the split matters:

| | Holds | Does |
|---|---|---|
| **Your backend** | `x-api-key`, `x-client-id` | Mints access tokens, creates payment intents, receives webhooks |
| **Your app + this SDK** | `clientId`, a short-lived access token, a `paymentIntentId` | Collects payment details, drives 3-D Secure and wallet flows |
| **UQPAY** | — | Authorises, captures, and tells your backend what happened |

The SDK result is **advisory**. Your `acquiring.payment_intent.succeeded` webhook is the
only authority on whether money moved. Confirm server-side before you fulfil an order.

## 1. Add the dependency

The SDK is published to Maven Central, which every Android project already resolves from,
so no repository block is needed.

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.uqpay.sdk:uqpay-sdk-android:<latest-version>")
}
```

The latest version is in [`CHANGELOG.md`](../CHANGELOG.md). Each release ships the AAR with
a `-sources` jar, and every artifact is GPG-signed.

## 2. Serve access tokens from your backend

Your backend mints a token from `x-client-id` + `x-api-key` and exposes it to your app
over your own authenticated channel.

> ### ⚠️ Cache the token. Do not mint one per payment.
>
> **UQPAY permits exactly one active access token per merchant — minting a new one
> invalidates the previous one.** A backend that mints a token per checkout will
> invalidate the token every other customer's device is holding, and its own. Mint once,
> cache it, share it, and refresh only when it is close to expiry (tokens last about 30
> minutes).
>
> The `x-api-key` must never reach the app. It can issue refunds and payouts.

## 3. Initialize the SDK

Once, in your `Application`:

```kotlin
UQPay.initialize(
    context = this,
    configuration = UQPayConfiguration(
        clientId = "your-client-id",
        environment = Environment.SANDBOX,   // Environment.PRODUCTION for live
        tokenProvider = {
            // Called off the main thread; may block. Fetch from YOUR backend.
            val response = myBackend.fetchUqpayToken()
            UQPayAuthToken(
                value = response.token,
                expiresAtEpochMillis = response.expiresAtEpochMillis,
            )
        },
        // Optional, default false. See "Diagnostics" below.
        loggingEnabled = false,
        // Optional. Omit it and the sheet is stock Material 3 following the device's
        // dark-mode setting. See "Make the sheet look like your app" below.
        // appearance = UQPayAppearance(...),
    ),
)
```

`initialize` stores the configuration and does nothing else — no network, no disk, no
background work — so it cannot affect your cold start.

### Diagnostics

`loggingEnabled = true` writes the SDK's own diagnostics to Logcat under the tag `UQPay`:
state transitions, redacted request paths, status codes, trace ids, and the degraded paths
that otherwise pass silently — a pin store that could not be written, a poll that ran out
of budget, a confirm that was superseded. It is the difference between "it sometimes just
says pending" and a bug report we can act on.

It **cannot** emit a request or response body in any environment: card number, CVC,
cardholder name, access tokens and API keys are never passed to the logger at all. Leave it
off in a shipped build anyway — the lines include payment intent ids, which you should
treat like order ids.

## 4. Create the launcher in `onCreate`

```kotlin
class CheckoutActivity : ComponentActivity() {

    private lateinit var payments: UQPayPaymentLauncher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        payments = UQPay.createPaymentLauncher(this) { result ->
            when (result.status) {
                PaymentStatus.SUCCEEDED -> confirmWithBackend(result.paymentIntentId)
                PaymentStatus.CANCELLED -> Unit
                PaymentStatus.PENDING   -> awaitWebhook(result.paymentIntentId)
                PaymentStatus.FAILED    -> {
                    showError(result.error?.message)                        // for the shopper
                    Log.w(TAG, result.error?.developerMessage.orEmpty())    // for you
                }
            }
        }
    }
}
```

Any `ComponentActivity` works — `AppCompatActivity` is one, so an existing screen needs no
change.

> ### ⚠️ Create the launcher **unconditionally**, on every Activity creation
>
> Not on a button tap. Not inside `if (savedInstanceState == null)`.
>
> If Android kills your process while the customer is completing 3-D Secure, your
> Activity is recreated in a **new process** and the result is redelivered to whatever
> launcher has re-registered by then. Registering conditionally means there is nothing to
> deliver to, and the payment result is lost silently — the worst failure mode a payment
> SDK has. Registration is cheap; do it every time.

### Fragment hosts

`createPaymentLauncher` takes any `ActivityResultCaller`, and `Fragment` is one — so the same
call works, with no extra dependency on your side:

```kotlin
class CheckoutFragment : Fragment() {

    private lateinit var payments: UQPayPaymentLauncher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        payments = UQPay.createPaymentLauncher(this) { result -> handle(result) }
    }
}
```

The rule above, in the Fragment's vocabulary: register in `onCreate` (or `onAttach`),
unconditionally, every time. The framework enforces this one — registering any later throws
`IllegalStateException`.

### Compose hosts

**Do not register inside a composable.** A composable runs when something decides to compose
it, which makes the registration conditional by construction; a result redelivered after
process death then has nothing to arrive at. Create the launcher in the host's `onCreate` and
pass it down:

```kotlin
class CheckoutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Unconditional, before setContent, exactly like the Views case.
        val payments = UQPay.createPaymentLauncher(this) { result -> handle(result) }

        setContent {
            CheckoutScreen(onPay = { intentId -> payments.launch(PaymentSessionParams(intentId)) })
        }
    }
}
```

If the launcher has to reach deep into the tree, put it in a `CompositionLocal` or on a
ViewModel — anywhere except a `remember` inside a conditionally-composed subtree.

There is deliberately no `rememberUQPayPaymentLauncher()`. A public `@Composable` would put
Compose types in this SDK's public API, which would force every merchant — including the ones
on Views — into a compatible Compose version. The four lines above cost less than that.

## 5. Start a payment

Your backend creates the intent and gives your app the id:

```kotlin
payments.launch(PaymentSessionParams(paymentIntentId = "PI_xxx"))
```

Skip the method list if you want a specific screen:

```kotlin
PaymentSessionParams("PI_xxx", PaymentSessionParams.Presentation.CardOnly)
PaymentSessionParams("PI_xxx", PaymentSessionParams.Presentation.SingleWallet(PaymentMethodType.GRABPAY))
```

Payment intents auto-expire **30 minutes** after creation.

### Restrict which methods this payment may use

For a per-region or per-risk-tier rule — "cards and PayNow only for this customer" — decided
by your backend, with one intent shape behind it:

```kotlin
payments.launch(
    PaymentSessionParams(
        paymentIntentId = "PI_xxx",
        allowedPaymentMethods = setOf(PaymentMethodType.CARD, PaymentMethodType.PAYNOW),
    ),
)
```

It only ever **narrows**: the sheet shows the intersection of your set, the intent's own
methods, and what this SDK version can render, in the gateway's order. Naming a method the
intent does not offer adds nothing.

An **empty** set is honoured — the sheet says no methods are available — rather than quietly
falling back to showing everything. If your rules can produce an empty list, do not launch the
sheet at all.

Pairing it with `CardOnly` or `SingleWallet` and excluding that very method is a contradiction,
and the payment ends immediately with `FAILED` / `INVALID_PAYMENT_METHOD` before any network
call, rather than showing a method your own rules forbade.

### Close the sheet from your side

`payments.cancel()` closes the sheet this launcher opened. For the events that make a sheet on
screen wrong — the basket reservation expired, the order was cancelled from your back office, a
push says the customer paid on another device:

```kotlin
private fun onOrderCancelledByBackOffice() {
    payments.cancel()   // the outcome still arrives through your callback, exactly once
}
```

It cancels the payment; it does not un-send one. With nothing submitted your callback receives
`CANCELLED`. **With an attempt already in the air it receives `PENDING`, never `CANCELLED`** —
closing a sheet does not reach into the gateway and stop a payment. Before any launch, or after
the sheet has closed, it is a no-op. Call it from the main thread.

### Prefill the billing details you already know

If the customer is signed in, do not make them retype their address. Pass what you hold
and the card form opens with it filled in:

```kotlin
payments.launch(
    PaymentSessionParams(
        paymentIntentId = "PI_xxx",
        billingDetails = PaymentSessionParams.BillingDetails(
            firstName = "John",
            lastName = "Tan",
            email = "john.tan@example.com",
            phone = "+6591234567",
            addressLine1 = "123 Orchard Road",
            addressLine2 = "#12-01",
            city = "Singapore",
            state = "Singapore",
            postalCode = "238888",
            countryCode = "SG",   // ISO 3166-1 alpha-2
        ),
    ),
)
```

Only **card number, expiry and security code** are then left to type. Those three are not
prefillable from any app, deliberately: a PAN passed into the SDK would have to travel
through an `Intent` extra, where the OS may write it to disk.

Every field is optional — send only what you actually know. The customer sees your values
in ordinary editable fields and can change or clear any of them; what reaches the gateway
is what the form holds when they tap Pay. Nothing you pass is persisted or logged, and
`BillingDetails.toString()` redacts the email and the phone so a crash reporter cannot
capture them. A `countryCode` the SDK does not recognise is ignored and the picker opens
on the device's region instead, so a typo costs one tap rather than the whole payment.

Omit `billingDetails` entirely and every field starts empty, exactly as before.

> ### Duplicate submission is guarded at the SDK boundary
>
> You do not need to disable your pay button, and you do not need to de-duplicate
> launches. Four mechanisms stack:
>
> - **Double-tap**: a second confirm for the same payload joins the first attempt instead
>   of starting a second one; a confirm with a *different* payload while one is unresolved
>   is refused outright.
> - **Back-press mid-confirm** is blocked, visibly, for a bounded window — the customer
>   cannot leave in the gap where the outcome is unknown.
> - **Rotation** re-attaches the recreated screen to the running payment rather than
>   starting a new one.
> - **Relaunch and process death**: the idempotency key for an attempt is persisted, so a
>   customer paying again with the same details replays the original key rather than
>   minting a new one, and the SDK re-reads the intent before every confirm.
>
> Relaunching the same `paymentIntentId` after the customer has already been shown a QR
> re-serves *that* QR rather than requesting a second one.

## 6. Make the sheet look like your app

The payment sheet is the last screen of your checkout, and it is the one screen your designers
did not draw. Set an appearance once, at init, and every screen the SDK draws matches — the
method list, the card form, the wallet QR, the bank-transfer instructions and the 3-D Secure
chrome:

```kotlin
UQPay.initialize(
    context = this,
    configuration = UQPayConfiguration(
        clientId = "your-client-id",
        environment = Environment.SANDBOX,
        tokenProvider = { … },
        appearance = UQPayAppearance(
            // SYSTEM (the default) follows the device. Say LIGHT or DARK if your app
            // forces its own — otherwise a light-only checkout hands the customer a dark
            // payment sheet at the last step.
            colorMode = UQPayAppearance.ColorMode.SYSTEM,
            lightColors = UQPayAppearance.Colors.MATERIAL_LIGHT.copy(
                primary = 0xFF0B5FFF.toInt(),
                onPrimary = 0xFFFFFFFF.toInt(),
            ),
            darkColors = UQPayAppearance.Colors.MATERIAL_DARK.copy(
                primary = 0xFF9DBBFF.toInt(),
                onPrimary = 0xFF00265C.toInt(),
            ),
            cornerRadiusDp = 8f,
        ),
    ),
)
```

Colours are plain ARGB ints named after Material 3 roles, so a host app on Views configures the
sheet without touching Compose. From Java, use `UQPayAppearance.Builder` and
`UQPayAppearance.Colors.Builder(base)` — see the [API reference](api-reference.md).

**Contrast is yours, and nothing checks it.** Every `on*` colour has to be readable against the
surface it is named for; WCAG AA is the bar. A Pay button nobody can read is a payment that
does not happen.

Text sizes are not themeable: the sheet uses Material 3 typography in `sp`, so it honours the
customer's font-size setting. Neither is the cancel affordance, the amount, or the copy
explaining a blocked back-press — those are how a customer knows what they are agreeing to and
how to get out of it.

### Sandbox sheets say so

While `environment` is `Environment.SANDBOX`, every screen of the sheet carries a **TEST MODE**
banner. It cannot be themed away or switched off. A sandbox screenshot and a live one used to
be pixel-identical, which is how a QA pass ends up running against real money.

## 7. Handle the four outcomes

| Status | What it means | What to do |
|---|---|---|
| `SUCCEEDED` | The payment succeeded. | Confirm server-side, then fulfil. |
| `FAILED` | Definitively failed. `error` is non-null. | Show `error.message`, log `error.developerMessage`; offer another method. |
| `CANCELLED` | The customer left with **nothing submitted**. | Nothing. Not an error. |
| `PENDING` | **The payment may still be live.** | Wait for the webhook. |

### Two messages on an error, and only one of them goes on screen

`UQPayError.message` is written **for the shopper**: a complete sentence, in the app's
language, that says what happened and what — if anything — they can do about it. It never
quotes the gateway, and it is identical in sandbox and production.

`UQPayError.developerMessage` is written **for you**: what the SDK was doing, what came back,
and where to look. English, never localised, never stable enough to parse. In sandbox it also
carries the gateway's own text so you can debug. **Never put it on screen.**

```kotlin
PaymentStatus.FAILED -> {
    showError(result.error?.message)
    Log.w(TAG, "payment ${result.paymentIntentId}: ${result.error?.developerMessage}")
}
```

Branch on `error.code`, never on either sentence — the codes are frozen, the wording is not.

### `PENDING` is the one to get right

There is no `TIMEOUT` status, deliberately. If a QR or 3-D Secure step runs out of time,
the customer may have completed the payment in their banking or wallet app moments
earlier. Reporting that as a failure invites you to charge them a second time.

`PENDING` also covers the case where the customer dismissed the sheet while a
confirmation was in flight — cancelling a request does not un-charge a card.

**On `PENDING`: stop your spinner, do not retry, do not refund, do not release the
order.** Wait for the webhook — see
[Webhooks & Reconciliation](webhooks.md#when-the-sdk-and-the-webhook-disagree).

`PENDING` is final as far as your callback is concerned. The SDK does keep reconciling
briefly in the background — to resolve the idempotency pin and its wallet bookkeeping — but
it will **never invoke your callback a second time**: the result channel delivers exactly
once per launch, and there is no second delivery to wait for. The webhook is the upgrade
path, not the SDK.

## Callback contract

- Invoked **exactly once per `launch` call**, on the **main thread**. Once per *call*, not
  once per *payment* — see the next two points.
- Answered for the intent *that call* was given: a second `launch` for a different intent is
  never answered with the first one's outcome.
- Two launches of the **same** intent are one payment — one confirm, one idempotency key, so
  the customer is never charged twice — but they are two calls, so you get two callbacks
  carrying the same `paymentIntentId`. A customer double-tapping your Pay button is the
  ordinary way this happens. **Make fulfilment idempotent on `paymentIntentId`**: treat a
  result for an order you have already acted on as a no-op. The SDK cannot do this for you,
  because only you know what "already fulfilled" means for an order.
- Delivered across configuration changes and process death.
- Never proof of payment on its own — always confirm via your backend.

## What the SDK stores on the device

One file, in the app's **no-backup** directory (`Context.getNoBackupFilesDir()`): the
idempotency pins that stop a payment being charged twice after a process death. It holds a
digest, an opaque key, coarse device metrics and a timestamp — never card data, and never
anything you have to write backup rules for. It is deliberately outside Android Auto Backup,
so a live idempotency key never leaves the device with your app's cloud backup, and a restored
backup can never replay a payment on a new phone.

You do not need to change `allowBackup`, `android:fullBackupContent` or
`android:dataExtractionRules` for the SDK. If you *exclude* directories in your own rules,
leave the no-backup directory alone — it is excluded by the platform already.

## Localisation

**The SDK ships English only.** Every customer-facing string — the sheet's own copy *and* the
sentences in `UQPayError.message` — lives in `res/values/strings.xml` under `uqpay_*` names,
and none of it is compiled into Kotlin.

That naming is the extension point. Library resources lose to app resources during merging, so
you can translate the sheet from your own app without waiting for an SDK release: declare the
same names under `res/values-<language>/` and yours win.

```xml
<!-- app/src/main/res/values-th/strings.xml -->
<resources>
    <string name="uqpay_card_pay">ชำระเงิน</string>
    <string name="uqpay_choose_method">เลือกวิธีชำระเงิน</string>
    <!-- …and any other uqpay_* name you want to override -->
</resources>
```

Override as many or as few as you like; anything you leave out stays English.

Two things the sheet already does regardless of language:

- **Amounts follow the customer's locale and the currency**, through the platform's currency
  formatter. JPY, KRW and VND render with no decimal places; European locales get their own
  separators and symbol placement. It is not string concatenation.
- **Text scales** with the customer's font-size setting, and layouts are scrollable so a larger
  setting cannot hide the Pay button.

Given the method list — TrueMoney, Touch 'n Go, GCash, DANA, KakaoPay, Toss Pay, Naver Pay,
Alipay CN/HK, GrabPay, PayNow — an English-only sheet is a real limitation in most of the
markets this SDK serves. Shipped translations are planned; the override above is what to do
until then.

## Java

The API is Java-friendly, and that is enforced by two things in this repository rather than
merely intended: a Java test source set in the SDK's own build
(`uqpay-sdk/src/test/java/`), and **a Java Activity in the sample app**
([`JavaCheckoutActivity.java`](../sample-app/src/main/java/com/uqpay/sample/JavaCheckoutActivity.java))
that registers a launcher, opens the sheet and handles a real callback. The unit test
catches a lost `@JvmStatic`; only the Activity proves the result actually comes back.

- `UQPay.initialize(...)`, `UQPay.createPaymentLauncher(...)`, `UQPay.getVersion()` and
  `UQPay.isInitialized()` are static.
- `PaymentCallback` and `UQPayTokenProvider` are single-method interfaces, so Java lambdas
  work.
- `new PaymentSessionParams("PI_xxx")` works without naming a presentation, and
  `new UQPayConfiguration(clientId, environment, tokenProvider)` works without naming
  `loggingEnabled`.
- `PaymentStatus` is an enum, so `switch` works. `UQPayErrorCode` and `PaymentMethodType`
  are deliberately **not** enums — they carry values this SDK version predates — so compare
  them with `equals`, and use `UQPayErrorCode.of(...)` / `PaymentMethodType.of(...)`.
- **One rough edge worth knowing.** `@JvmOverloads` generates `PaymentSessionParams`
  constructors by dropping trailing parameters, so you get `(intentId)`,
  `(intentId, presentation)` and `(intentId, presentation, billingDetails)` — but **not**
  `(intentId, billingDetails)`. To pass a prefill and keep the default sheet, name the
  default explicitly:

  ```java
  new PaymentSessionParams(
      intentId,
      PaymentSessionParams.Presentation.MethodList.INSTANCE,
      billing);
  ```

  Do not pass `null` for the presentation: it is non-null in Kotlin, so it throws at the
  boundary rather than falling back to the default.
- `UQPay.createPaymentLauncher(...)` accepts a `Fragment` as well as an Activity, with no
  cast: both are `ActivityResultCaller`.
- **Use the builders.** Java has no named arguments, and three types here have long runs of
  same-typed parameters where a transposition compiles clean and ships a permanent bug:

  ```java
  BillingDetails billing = new BillingDetails.Builder()
      .firstName("Jo").lastName("Tan")
      .city("Klang").state("Selangor")     // transposed positionally, this is invisible
      .postalCode("41000").countryCode("MY")
      .build();

  UQPayAppearance appearance = new UQPayAppearance.Builder()
      .lightColors(new UQPayAppearance.Colors.Builder(UQPayAppearance.Colors.MATERIAL_LIGHT)
          .primary(0xFF0B5FFF)
          .onPrimary(0xFFFFFFFF)
          .build())
      .cornerRadiusDp(8f)
      .build();
  ```

## Sample app

See [`sample-app/`](../sample-app): a small store checkout — cart, price breakdown,
shipping address, Checkout — written in plain Views and XML rather than Compose, which is
the point. The SDK's payment UI is Compose internally, and the sample is the proof that a
host app does not have to adopt Compose, or match a Compose version, to use it.

It also shows the billing prefill above, in `DemoCustomer.kt`.

`JavaCheckoutActivity.java` is the same payment driven from **Java**, reachable from a
button on the cart screen. It is deliberately plain — its whole job is to be a Java host
that compiles and runs, so the interoperability claims above are demonstrated by an app
rather than asserted by a doc.

Put sandbox credentials in `local.properties` (gitignored) and read them through
`BuildConfig` — never commit them. `DemoMerchantBackend.kt` stands in for your server so
the demo runs standalone; its file header explains, at length, why it must not be copied
into a production app.

## Next steps

- [API Reference](api-reference.md)
- [Testing](testing.md) — sandbox test cards, and the rotation / process-death recipes
- [Webhooks & Reconciliation](webhooks.md) — the authority on what actually happened
- [Error Codes](error-codes.md)
- [Troubleshooting](troubleshooting.md)
