package com.uqpay.sdk.payment

/**
 * Terminal status of a payment attempt. Every payment ends in exactly one of these.
 * Values are stable once released — never renamed or repurposed.
 */
public enum class PaymentStatus {
    /** Payment completed successfully (confirm server-side before fulfillment). */
    SUCCESS,

    /** Payment attempted and failed — see [PaymentResult.error]. */
    FAILED,

    /** User abandoned the flow (back press / cancel). */
    CANCELLED,

    /** Payment did not reach a terminal state within the SDK timeout. */
    TIMEOUT,
}
