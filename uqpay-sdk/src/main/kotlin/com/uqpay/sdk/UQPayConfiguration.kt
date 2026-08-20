package com.uqpay.sdk

import com.uqpay.sdk.appearance.UQPayAppearance
import com.uqpay.sdk.auth.UQPayTokenProvider

/**
 * Immutable SDK configuration supplied to [UQPay.initialize].
 *
 * There is no publishable key: UQPAY has no such credential. The app authenticates with
 * a short-lived access token fetched through [tokenProvider], and the merchant's
 * `x-api-key` never enters the app.
 *
 * Per-payment values live on [com.uqpay.sdk.payment.PaymentSessionParams], not here.
 *
 * @property clientId the merchant's `x-client-id`, supplied by your backend.
 * @property environment target environment. There is no default and no silent fallback —
 *   tokens and intents are environment-specific and never interchangeable.
 * @property tokenProvider supplies short-lived access tokens. See [UQPayTokenProvider]
 *   for why the app must never mint its own.
 * @property loggingEnabled writes the SDK's own diagnostics to Logcat under the tag
 *   `UQPay`. **Off by default**, and off is the right setting for a shipped app.
 *
 *   Turn it on while integrating, or when reproducing a payment that behaved oddly in the
 *   field. What it buys: the SDK's degraded paths — a pin store that could not be written,
 *   a poll that exhausted its budget, a confirm that was superseded — currently *log and
 *   continue* into a discarding logger, which makes them invisible to the one person who
 *   could act on them.
 *
 *   What it can never emit, in any environment: a request or response **body**. Card
 *   number, CVC, cardholder name, tokens and API secrets are not passed to the logger at
 *   all, so no setting exposes them. It emits method, redacted path, status code, trace id,
 *   payment intent id, state transitions and exception *class names*. Treat the payment
 *   intent id as you would an order id.
 * @property appearance how the payment sheet looks — colours, dark-mode behaviour, corner
 *   radius. Defaults to stock Material 3 following the device's dark-mode setting. See
 *   [UQPayAppearance].
 *
 *   It lives here rather than on [com.uqpay.sdk.payment.PaymentSessionParams] because a
 *   merchant's brand does not change between two payments in one app, and because a value
 *   that travelled in the launch parcel would have to survive process death as a parcel of
 *   its own. Set once, at init, and every sheet the SDK ever draws matches.
 */
public class UQPayConfiguration @JvmOverloads constructor(
    public val clientId: String,
    public val environment: Environment,
    public val tokenProvider: UQPayTokenProvider,
    public val loggingEnabled: Boolean = false,
    public val appearance: UQPayAppearance = UQPayAppearance.DEFAULT,
) {
    init {
        // Validated here, at construction, rather than at the first payment.
        //
        // A blank `x-client-id` cannot authenticate: every request the SDK makes carries it,
        // and the gateway answers 401. Accepted silently, that surfaces minutes or days later
        // as a *payment* that failed — `AUTHENTICATION_FAILED` on a customer's checkout — and
        // it reads like a credential problem on the merchant's account rather than like a
        // configuration line that was never filled in. Refusing it at the moment it is
        // supplied turns a production incident into a first-run integration error, which is
        // exactly the trade `createPaymentLauncher`'s own `check` makes.
        //
        // This is a programmer error, so it throws (AC §3: never throw for a payment
        // *outcome*). Nothing is retained: `UQPay.initialize` cannot complete with a
        // configuration that was never constructed, so `isInitialized` stays false and a
        // merchant who swallows this still gets the honest `NOT_INITIALIZED` at launch time
        // rather than a payment that dies at the gateway.
        require(clientId.isNotBlank()) {
            "UQPayConfiguration.clientId is blank. Pass the merchant's x-client-id, " +
                "supplied by your backend; the SDK cannot authenticate without it."
        }
        // A line break in a value that becomes an HTTP header is header injection, and this
        // one is read from a merchant's build config or remote config where a stray newline
        // is an ordinary accident. Rejected rather than trimmed: silently altering a
        // credential would make a mistyped id look like a working one.
        require(clientId.none { it == '\n' || it == '\r' }) {
            "UQPayConfiguration.clientId contains a line break. It is sent as the " +
                "x-client-id header and must be a single line."
        }
    }

    /** Never expose credentials in logs or stack traces. */
    override fun toString(): String =
        "UQPayConfiguration(clientId=$clientId, environment=$environment, " +
            "tokenProvider=****, loggingEnabled=$loggingEnabled, appearance=$appearance)"
}
