package com.uqpay.sdk.payment

/**
 * Terminal status of a payment. Exactly one is delivered per payment, and never more
 * than one. Values are stable once released — never renamed or repurposed.
 *
 * Note there is deliberately no `TIMEOUT` value. A poll window closing is **not** an
 * outcome: the customer may have completed the payment in their banking or wallet app
 * moments before. Reporting such a payment as failed invites a duplicate charge, so it
 * is reported as [PENDING] carrying [com.uqpay.sdk.error.UQPayErrorCode.TIMEOUT].
 */
public enum class PaymentStatus {
    /**
     * The payment succeeded.
     *
     * Advisory only — the merchant's `acquiring.payment_intent.succeeded` webhook is the
     * authority. Confirm server-side before fulfilling an order.
     */
    SUCCEEDED,

    /** The payment definitively failed. [PaymentResult.error] is non-null. */
    FAILED,

    /**
     * The customer abandoned the flow with **no payment attempt in the air**.
     *
     * Never delivered for a dismissal that happened while a confirmation was in flight
     * (that is [PENDING]), and never delivered after any other outcome.
     */
    CANCELLED,

    /**
     * The SDK has stopped driving this payment while its outcome is unresolved — the
     * gateway returned a pending state, a 3-D Secure or QR step timed out with the
     * payment possibly still live, or the customer dismissed the sheet mid-confirmation.
     *
     * The payment may still succeed. Do not release, refund, or retry the order: wait
     * for the webhook. The SDK keeps reconciling briefly and may still deliver a
     * [SUCCEEDED] or [FAILED] result afterwards.
     */
    PENDING,
}
