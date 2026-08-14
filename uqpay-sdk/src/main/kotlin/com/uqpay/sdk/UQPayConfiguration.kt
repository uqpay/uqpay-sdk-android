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
 */
public class UQPayConfiguration(
    public val clientId: String,
    public val environment: Environment,
    public val tokenProvider: UQPayTokenProvider,
) {
    /** Never expose credentials in logs or stack traces. */
    override fun toString(): String =
        "UQPayConfiguration(clientId=$clientId, environment=$environment, tokenProvider=****)"
}
