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
 * ### The intent id is never lost (F3)
 *
 * [UQPayPaymentActivity] finishes every flow through one function that stamps
 * [EXTRA_INTENT_ID] from the launch params alongside the parcelled [PaymentResult]. So
 * even when the result parcel cannot be read — a classloader mishap after process death,
 * a garbled extra — [parseResult] still names the payment: the fallback result is built
 * from [EXTRA_INTENT_ID], never from a blank.
 *
 * **The single exception**: `RESULT_CANCELED` with no data Intent at all. Android
 * produces exactly that shape when an Activity finishes without calling `setResult`, and
 * our Activity produces it deliberately in one case only — launch arguments that were
 * missing or garbled (G23), where there *is* no intent id to report because the request
 * itself was unreadable. That is reported as `CANCELLED` with a blank `paymentIntentId`
 * rather than thrown, because the alternative is crashing the merchant's app at the end
 * of a payment. Every other exit path — back press, cancel with an attempt in the air,
 * a terminal outcome, a load failure, an uninitialised SDK — carries the id; the
 * Activity's tests sweep each of them.
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

        // No parsable result. The id travels separately precisely for this case (F3);
        // it is blank only on the garbled-args exit — see the class KDoc.
        val intentId = intent?.getStringExtra(EXTRA_INTENT_ID).orEmpty()

        // RESULT_CANCELED means the flow ended without ever producing a result — the
        // customer backed out before anything was submitted, or the launch was unusable.
        return if (resultCode == Activity.RESULT_CANCELED) {
            PaymentResult(
                status = PaymentStatus.CANCELLED,
                paymentIntentId = intentId,
            )
        } else {
            // RESULT_OK with no readable result is a genuine anomaly: report it as a
            // failure rather than inventing a success or a cancellation.
            PaymentResult(
                status = PaymentStatus.FAILED,
                paymentIntentId = intentId,
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

        /**
         * The intent id, stamped on every result Intent by the Activity independently of
         * [EXTRA_RESULT]. The F3 guarantee lives on this extra.
         */
        const val EXTRA_INTENT_ID: String = "com.uqpay.sdk.extra.INTENT_ID"
    }
}
