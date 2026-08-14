package com.uqpay.sdk.launcher

import androidx.activity.result.ActivityResultLauncher
import com.uqpay.sdk.error.UQPayError
import com.uqpay.sdk.error.UQPayErrorCode
import com.uqpay.sdk.payment.PaymentCallback
import com.uqpay.sdk.payment.PaymentResult
import com.uqpay.sdk.payment.PaymentSessionParams
import com.uqpay.sdk.payment.PaymentStatus
import com.uqpay.sdk.payment.UQPayPaymentLauncher

/**
 * Thin wrapper over the registered [ActivityResultLauncher]. Holds no payment state —
 * everything that must survive process death lives in the parcel or the internal
 * ViewModel's saved state.
 */
internal class UQPayPaymentLauncherImpl(
    private val launcher: ActivityResultLauncher<PaymentSessionParams>,
    private val callback: PaymentCallback,
) : UQPayPaymentLauncher {

    override fun launch(params: PaymentSessionParams) {
        try {
            launcher.launch(params)
        } catch (e: IllegalStateException) {
            // The host was destroyed or the launcher was unregistered. Report through
            // the callback rather than throwing into the merchant's click handler.
            callback.onResult(
                PaymentResult(
                    status = PaymentStatus.FAILED,
                    paymentIntentId = params.paymentIntentId,
                    error = UQPayError(
                        code = UQPayErrorCode.INVALID_CONFIGURATION,
                        message = "The payment could not be started because the host " +
                            "Activity is no longer active. Create the launcher in " +
                            "onCreate() and launch while the Activity is resumed.",
                    ),
                ),
            )
        }
    }
}
