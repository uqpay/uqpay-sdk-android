package com.uqpay.sdk.payment

/**
 * Receives the outcome of a payment started via
 * [com.uqpay.sdk.UQPay.startPayment].
 *
 * Contract: invoked exactly once per payment, on the main thread, including across
 * configuration changes and process death.
 */
public fun interface PaymentCallback {
    public fun onResult(result: PaymentResult)
}
