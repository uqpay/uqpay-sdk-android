package com.uqpay.sdk.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.uqpay.sdk.error.UQPayError
import com.uqpay.sdk.error.UQPayErrorCode
import com.uqpay.sdk.payment.PaymentResult
import com.uqpay.sdk.payment.PaymentSessionParams
import com.uqpay.sdk.payment.PaymentStatus
import com.uqpay.sdk.ui.UQPayPaymentActivity

/**
 * Carries the payment across the process boundary.
 *
 * Arguments and result both travel as parcels through the OS, so they survive process
 * death by construction — this is what lets the SDK promise exactly-once delivery
 * without retaining anything in memory.
 *
 * Note this class is named in `consumer-rules.pro`: `ActivityResultRegistry` matches
 * contracts across process death, so if R8 strips or renames it the payment result is
 * silently lost in release builds only.
 */
internal class UQPayPaymentContract : ActivityResultContract<PaymentSessionParams, PaymentResult>() {

    override fun createIntent(context: Context, input: PaymentSessionParams): Intent =
        Intent(context, UQPayPaymentActivity::class.java)
            .putExtra(EXTRA_PARAMS, input)

    override fun parseResult(resultCode: Int, intent: Intent?): PaymentResult {
        val result = intent?.let { readResult(it) }
        if (result != null) return result

        // No parsable result. RESULT_CANCELED means the flow ended without ever
        // producing one — the customer backed out before anything was submitted.
        return if (resultCode == Activity.RESULT_CANCELED) {
            PaymentResult(
                status = PaymentStatus.CANCELLED,
                paymentIntentId = intent?.getStringExtra(EXTRA_INTENT_ID).orEmpty(),
            )
        } else {
            PaymentResult(
                status = PaymentStatus.FAILED,
                paymentIntentId = intent?.getStringExtra(EXTRA_INTENT_ID).orEmpty(),
                error = UQPayError(
                    code = UQPayErrorCode.UNKNOWN,
                    message = "The payment ended without reporting a result.",
                ),
            )
        }
    }

    /**
     * Reads the result parcel defensively. After process death the framework re-marshals
     * the Intent without the app's classloader attached, so it must be set explicitly or
     * `getParcelable` silently returns null.
     */
    private fun readResult(intent: Intent): PaymentResult? = runCatching {
        intent.extras
            ?.apply { classLoader = PaymentResult::class.java.classLoader }
            ?.let { extras ->
                @Suppress("DEPRECATION")
                extras.getParcelable<PaymentResult>(EXTRA_RESULT)
            }
    }.getOrNull()

    companion object {
        const val EXTRA_PARAMS: String = "com.uqpay.sdk.extra.PARAMS"
        const val EXTRA_RESULT: String = "com.uqpay.sdk.extra.RESULT"
        const val EXTRA_INTENT_ID: String = "com.uqpay.sdk.extra.INTENT_ID"
    }
}
