package com.uqpay.sdk.launcher

import androidx.activity.result.ActivityResultLauncher
import com.uqpay.sdk.engine.PaymentSession
import com.uqpay.sdk.error.ErrorCopy
import com.uqpay.sdk.error.UQPayError
import com.uqpay.sdk.error.UQPayErrorCode
import com.uqpay.sdk.payment.PaymentCallback
import com.uqpay.sdk.payment.PaymentResult
import com.uqpay.sdk.payment.PaymentSessionParams
import com.uqpay.sdk.payment.PaymentStatus
import com.uqpay.sdk.payment.UQPayPaymentLauncher
import java.util.concurrent.atomic.AtomicReference

/**
 * Thin wrapper over the registered [ActivityResultLauncher].
 *
 * Holds no payment state — everything that must survive process death lives in the parcel
 * or the internal ViewModel's saved state. The one thing it does remember is the id of the
 * payment it last launched, and only so [cancel] knows which payment to end; losing it to
 * process death costs nothing, because after process death there is no sheet on screen for
 * this launcher to cancel either.
 */
internal class UQPayPaymentLauncherImpl(
    private val launcher: ActivityResultLauncher<PaymentSessionParams>,
    private val callback: PaymentCallback,
    private val copy: ErrorCopy,
) : UQPayPaymentLauncher {

    /**
     * The intent id of the most recent [launch]. Written before the sheet exists and never
     * cleared: a stale id is harmless, because [cancel] resolves it through the session
     * registry, which no longer holds a payment that has finished.
     */
    private val lastLaunched = AtomicReference<String?>(null)

    override fun launch(params: PaymentSessionParams) {
        lastLaunched.set(params.paymentIntentId)
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
                        message = copy.forCode(UQPayErrorCode.INVALID_CONFIGURATION),
                        developerMessage = "launch() was called on a launcher whose host is " +
                            "no longer active. Create the launcher unconditionally in " +
                            "onCreate (Activity) or onCreate/onViewCreated (Fragment), and " +
                            "launch while the host is resumed.",
                    ),
                ),
            )
        }
    }

    /**
     * Ends the payment this launcher last opened, by settling its **engine** rather than by
     * finishing an Activity.
     *
     * That direction matters. The engine is the only thing that knows whether an attempt is
     * in the air, and therefore the only thing that can choose honestly between `CANCELLED`
     * and `PENDING`. Settling it puts the outcome into the same `Terminal` state that every
     * other ending goes through, and the payment Activity's single collector delivers it —
     * so a merchant-initiated cancel produces exactly one callback, on the same path, with
     * the same guarantees as a customer's back-press. Finishing the Activity directly would
     * have bypassed all of that and could have reported `CANCELLED` for a confirm already on
     * the wire.
     *
     * [PaymentSession.peek] never builds a session, so this cannot start anything: an id
     * with no live session — never launched, already finished, or a process that has since
     * been killed and restarted — resolves to null and the call does nothing.
     */
    override fun cancel() {
        val intentId = lastLaunched.get() ?: return
        PaymentSession.peek(intentId)?.engine?.cancel()
    }
}
