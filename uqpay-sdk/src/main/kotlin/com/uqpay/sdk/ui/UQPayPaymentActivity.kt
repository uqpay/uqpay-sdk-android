package com.uqpay.sdk.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.uqpay.sdk.error.UQPayError
import com.uqpay.sdk.error.UQPayErrorCode
import com.uqpay.sdk.launcher.UQPayPaymentContract
import com.uqpay.sdk.payment.PaymentResult
import com.uqpay.sdk.payment.PaymentSessionParams
import com.uqpay.sdk.payment.PaymentStatus

/**
 * Internal Activity that owns the user-facing payment flow. Launched only through
 * [com.uqpay.sdk.UQPay.createPaymentLauncher]; never exported.
 *
 * Holds no payment logic itself — the flow lives in a ViewModel with saved state so
 * recreation re-attaches to the payment in progress instead of re-submitting it.
 *
 * **Implementation status:** the result-delivery plumbing below is complete. The payment
 * engine, card form, 3-D Secure, and wallet QR flows land in later slices; until then a
 * launched payment finishes as [PaymentStatus.CANCELLED] with nothing submitted.
 */
internal class UQPayPaymentActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val params = readParams()
        if (params == null) {
            // Garbled or missing launch args. Never crash the merchant's app.
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        finishWith(
            PaymentResult(
                status = PaymentStatus.CANCELLED,
                paymentIntentId = params.paymentIntentId,
            ),
        )
    }

    /**
     * Reads the launch arguments defensively. After process death the framework
     * re-marshals the Intent without the app's classloader attached, so it must be set
     * explicitly or `getParcelable` silently returns null.
     */
    private fun readParams(): PaymentSessionParams? = runCatching {
        intent?.extras
            ?.apply { classLoader = PaymentSessionParams::class.java.classLoader }
            ?.let { extras ->
                @Suppress("DEPRECATION")
                extras.getParcelable<PaymentSessionParams>(UQPayPaymentContract.EXTRA_PARAMS)
            }
    }.getOrNull()

    /**
     * Finishes with a terminal outcome. The only way this Activity reports a result —
     * it never invokes a merchant callback directly, so delivery always goes through the
     * OS and survives process death.
     */
    private fun finishWith(result: PaymentResult) {
        val data = Intent()
            .putExtra(UQPayPaymentContract.EXTRA_RESULT, result)
            .putExtra(UQPayPaymentContract.EXTRA_INTENT_ID, result.paymentIntentId)
        setResult(RESULT_OK, data)
        finish()
    }

    /** Reserved for the engine wiring in a later slice. */
    @Suppress("unused")
    private fun failWith(intentId: String, code: UQPayErrorCode, message: String) {
        finishWith(
            PaymentResult(
                status = PaymentStatus.FAILED,
                paymentIntentId = intentId,
                error = UQPayError(code = code, message = message),
            ),
        )
    }
}
