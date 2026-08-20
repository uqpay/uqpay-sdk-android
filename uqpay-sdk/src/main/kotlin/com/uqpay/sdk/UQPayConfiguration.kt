package com.uqpay.sdk

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
 */
public class UQPayConfiguration @JvmOverloads constructor(
    public val clientId: String,
    public val environment: Environment,
    public val tokenProvider: UQPayTokenProvider,
    public val loggingEnabled: Boolean = false,
) {
    /** Never expose credentials in logs or stack traces. */
    override fun toString(): String =
        "UQPayConfiguration(clientId=$clientId, environment=$environment, " +
            "tokenProvider=****, loggingEnabled=$loggingEnabled)"
}
