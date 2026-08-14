package com.uqpay.sdk

import android.app.Activity
import android.content.Context
import com.uqpay.sdk.payment.PaymentCallback
import com.uqpay.sdk.payment.PaymentRequest

/**
 * Single public entry point of the UQPAY SDK for Android.
 *
 * Initialize once (typically in `Application.onCreate`) via [initialize], then start
 * payments with [startPayment]. Calling any payment API before initialization throws
 * [IllegalStateException].
 */
public object UQPay {

    /** SDK version string, e.g. `"0.1.0"`. */
    public val version: String
        get() = TODO("Not yet implemented")

    /** Whether [initialize] has been called successfully. */
    public val isInitialized: Boolean
        get() = TODO("Not yet implemented")

    /**
     * Initializes the SDK. Safe and cheap to call from `Application.onCreate`;
     * performs no network I/O.
     *
     * @param context any [Context]; the application context is retained, never the
     *   passed instance.
     * @param configuration merchant credentials and target [Environment].
     */
    @JvmStatic
    public fun initialize(context: Context, configuration: UQPayConfiguration) {
        TODO("Not yet implemented")
    }

    /**
     * Launches the payment flow for a payment intent created by the merchant backend.
     *
     * [callback] is invoked exactly once per payment, on the main thread, with a
     * terminal [com.uqpay.sdk.payment.PaymentStatus] — including across configuration
     * changes and process death.
     *
     * The client-side result is advisory: merchants must confirm the final payment
     * state server-side before fulfillment.
     *
     * @throws IllegalStateException if the SDK is not initialized.
     */
    @JvmStatic
    public fun startPayment(
        activity: Activity,
        request: PaymentRequest,
        callback: PaymentCallback,
    ) {
        TODO("Not yet implemented")
    }
}
