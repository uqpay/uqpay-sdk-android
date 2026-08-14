package com.uqpay.sdk

/**
 * Immutable SDK configuration supplied to [UQPay.initialize].
 *
 * @property merchantId UQPAY merchant identifier.
 * @property publishableKey client-safe publishable key. Secret keys must never be
 *   embedded in an app.
 * @property environment target environment; keys are environment-specific.
 */
public data class UQPayConfiguration(
    val merchantId: String,
    val publishableKey: String,
    val environment: Environment,
) {
    /** Never expose the key in logs/stack traces. */
    override fun toString(): String =
        "UQPayConfiguration(merchantId=$merchantId, publishableKey=****, environment=$environment)"
}
