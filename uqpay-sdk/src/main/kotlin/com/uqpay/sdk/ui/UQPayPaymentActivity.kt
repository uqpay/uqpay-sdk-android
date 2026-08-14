package com.uqpay.sdk.ui

import androidx.appcompat.app.AppCompatActivity

/**
 * Internal Activity that owns the user-facing payment flow. Launched only via
 * [com.uqpay.sdk.UQPay.startPayment]; never exported.
 *
 * Must survive rotation, background/foreground transitions, and process death without
 * losing or duplicating the payment (see docs/architecture.md).
 */
internal class UQPayPaymentActivity : AppCompatActivity()
