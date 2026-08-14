package com.uqpay.sdk.payment

/**
 * Receives the outcome of a payment.
 *
 * Supplied when the launcher is created — see
 * [com.uqpay.sdk.UQPay.createPaymentLauncher] — and therefore re-supplied on every host
 * Activity creation. That re-registration is what makes delivery survive process death:
 * the callback itself is never retained across it.
 *
 * ### Contract
 *
 * - Invoked **exactly once** per payment, on the **main thread**.
 * - Delivered across configuration changes and process death.
 * - The result is **advisory**. The merchant's webhook is the authority on whether money
 *   moved; confirm server-side before fulfilling an order.
 * - A [PaymentStatus.PENDING] result means the payment may still be live. Do not
 *   release, refund, or retry the order.
 */
public fun interface PaymentCallback {
    public fun onResult(result: PaymentResult)
}
