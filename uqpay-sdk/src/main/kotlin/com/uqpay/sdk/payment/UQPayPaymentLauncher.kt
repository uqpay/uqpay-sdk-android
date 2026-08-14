package com.uqpay.sdk.payment

/**
 * Starts payments and delivers their outcome.
 *
 * Obtain one from [com.uqpay.sdk.UQPay.createPaymentLauncher] during host Activity
 * creation, then call [launch] whenever the customer pays.
 */
public interface UQPayPaymentLauncher {
    /**
     * Launches the payment flow for an intent created by the merchant backend.
     *
     * Safe to call more than once over the life of the host Activity — each call starts
     * a fresh payment. A double-tap cannot start two payments: the SDK guards the
     * submission internally rather than relying on the merchant disabling a button.
     *
     * If the host is in a state where the flow cannot start, the failure is delivered
     * through the [PaymentCallback] rather than thrown.
     */
    public fun launch(params: PaymentSessionParams)
}
