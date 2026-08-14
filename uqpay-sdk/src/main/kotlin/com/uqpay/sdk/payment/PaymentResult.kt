package com.uqpay.sdk.payment

import com.uqpay.sdk.error.UQPayError

/**
 * Outcome of a payment attempt, delivered once via [PaymentCallback.onResult].
 *
 * @property status terminal status of the payment.
 * @property paymentIntentId the intent this result belongs to.
 * @property error populated iff [status] is [PaymentStatus.FAILED].
 */
public data class PaymentResult(
    val status: PaymentStatus,
    val paymentIntentId: String,
    val error: UQPayError? = null,
)
