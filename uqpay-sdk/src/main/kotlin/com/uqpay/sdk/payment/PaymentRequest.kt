package com.uqpay.sdk.payment

/**
 * Describes the payment to run. Payment intents are created server-side by the
 * merchant backend using UQPAY's server API — the app never creates intents or holds
 * the amount as a source of truth.
 *
 * @property paymentIntentId identifier of the intent created by the merchant backend.
 * @property clientSecret client secret bound to the intent; safe for client use,
 *   but must never be logged.
 */
public data class PaymentRequest(
    val paymentIntentId: String,
    val clientSecret: String,
) {
    /** Never expose the client secret in logs/stack traces. */
    override fun toString(): String =
        "PaymentRequest(paymentIntentId=$paymentIntentId, clientSecret=****)"
}
