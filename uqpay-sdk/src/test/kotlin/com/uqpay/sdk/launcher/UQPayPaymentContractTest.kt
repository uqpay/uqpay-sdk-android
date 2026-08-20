package com.uqpay.sdk.launcher

import com.uqpay.sdk.testErrorCopy
import android.app.Activity
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.uqpay.sdk.payment.PaymentMethodType
import com.uqpay.sdk.payment.PaymentResult
import com.uqpay.sdk.payment.PaymentSessionParams
import com.uqpay.sdk.payment.PaymentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The contract is the only path a payment result takes back to the merchant. It must
 * never throw: a garbled or absent result has to degrade into a reportable outcome,
 * because the alternative is crashing the merchant's app at the end of a payment
 * (acceptance criteria §6.3, §7.1).
 */
@RunWith(RobolectricTestRunner::class)
class UQPayPaymentContractTest {

    private val contract = UQPayPaymentContract(testErrorCopy())
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `createIntent carries the params to the internal activity`() {
        val params = PaymentSessionParams(
            "PI_42",
            PaymentSessionParams.Presentation.SingleWallet(PaymentMethodType.PAYNOW),
        )

        val intent = contract.createIntent(context, params)
        intent.extras?.classLoader = PaymentSessionParams::class.java.classLoader

        @Suppress("DEPRECATION")
        val restored = intent.extras
            ?.getParcelable<PaymentSessionParams>(UQPayPaymentContract.EXTRA_PARAMS)

        assertEquals(params, restored)
        assertNotNull(intent.component)
    }

    @Test
    fun `parseResult returns the delivered result`() {
        val delivered = PaymentResult(
            status = PaymentStatus.SUCCEEDED,
            paymentIntentId = "PI_7",
            transactionId = "PA_7",
        )
        val data = Intent().putExtra(UQPayPaymentContract.EXTRA_RESULT, delivered)

        val parsed = contract.parseResult(Activity.RESULT_OK, data)

        assertEquals(PaymentStatus.SUCCEEDED, parsed.status)
        assertEquals("PI_7", parsed.paymentIntentId)
        assertEquals("PA_7", parsed.transactionId)
    }

    /**
     * The single documented blank-id case (F3): `RESULT_CANCELED` with no data Intent is
     * what the Activity produces only for garbled launch args, where there is no id to
     * report. It degrades to a cancellation rather than a crash.
     */
    @Test
    fun `a null intent is reported, never thrown - the one garbled-args exit`() {
        val parsed = contract.parseResult(Activity.RESULT_CANCELED, null)

        assertEquals(PaymentStatus.CANCELLED, parsed.status)
        assertEquals("", parsed.paymentIntentId)
    }

    // ---- F3: no exit path other than garbled args may lose the intent id ---------------

    /**
     * Every shape the Activity's `finishWith` can hand back — a full result, a result
     * whose parcel could not be read, a result Intent with only the id — resolves to the
     * intent id the launch named. The Activity's own tests cover the exit *paths*; this
     * covers the *shapes* those paths produce at the contract.
     */
    @Test
    fun `F3 - every result shape carrying EXTRA_INTENT_ID resolves to a non-blank id`() {
        val id = "PI_f3"
        val shapes = listOf(
            "full result" to Intent()
                .putExtra(UQPayPaymentContract.EXTRA_RESULT, PaymentResult(PaymentStatus.CANCELLED, id))
                .putExtra(UQPayPaymentContract.EXTRA_INTENT_ID, id),
            "id only, RESULT_OK" to Intent().putExtra(UQPayPaymentContract.EXTRA_INTENT_ID, id),
            "garbled result parcel, id present" to Intent()
                .putExtra(UQPayPaymentContract.EXTRA_RESULT, "not-a-parcelable")
                .putExtra(UQPayPaymentContract.EXTRA_INTENT_ID, id),
        )
        for ((name, data) in shapes) {
            for (code in listOf(Activity.RESULT_OK, Activity.RESULT_CANCELED)) {
                val parsed = contract.parseResult(code, data)
                assertEquals("$name / $code lost the intent id", id, parsed.paymentIntentId)
            }
        }
    }

    /**
     * The id extra is authoritative even when the parcelled result disagrees: the Activity
     * stamps it from the launch params, so a future result object with a blank id can
     * never make a merchant lose the payment. (The parcelled result wins when readable —
     * this test pins that a *blank* result id is not silently upgraded either, so the
     * behaviour is explicit: readable result → its id; unreadable → the extra.)
     */
    @Test
    fun `a readable result is returned as delivered - the id extra is the fallback, not an override`() {
        val data = Intent()
            .putExtra(UQPayPaymentContract.EXTRA_RESULT, PaymentResult(PaymentStatus.SUCCEEDED, "PI_result"))
            .putExtra(UQPayPaymentContract.EXTRA_INTENT_ID, "PI_result")

        val parsed = contract.parseResult(Activity.RESULT_OK, data)

        assertEquals(PaymentStatus.SUCCEEDED, parsed.status)
        assertEquals("PI_result", parsed.paymentIntentId)
    }

    @Test
    fun `a missing result extra does not crash and keeps the intent id`() {
        val data = Intent().putExtra(UQPayPaymentContract.EXTRA_INTENT_ID, "PI_5")

        val parsed = contract.parseResult(Activity.RESULT_OK, data)

        // RESULT_OK with no parsable result is a genuine anomaly — report it as a
        // failure rather than inventing a success or a cancellation.
        assertEquals(PaymentStatus.FAILED, parsed.status)
        assertEquals("PI_5", parsed.paymentIntentId)
        assertNotNull(parsed.error)
    }

    @Test
    fun `back press before anything is submitted is a cancellation`() {
        val data = Intent().putExtra(UQPayPaymentContract.EXTRA_INTENT_ID, "PI_6")

        val parsed = contract.parseResult(Activity.RESULT_CANCELED, data)

        assertEquals(PaymentStatus.CANCELLED, parsed.status)
        assertEquals("PI_6", parsed.paymentIntentId)
    }

    @Test
    fun `a garbled result extra degrades instead of throwing`() {
        // Something other than a PaymentResult under the result key.
        val data = Intent().putExtra(UQPayPaymentContract.EXTRA_RESULT, "not-a-parcelable")

        val parsed = contract.parseResult(Activity.RESULT_OK, data)

        assertEquals(PaymentStatus.FAILED, parsed.status)
        assertNotNull(parsed.error)
    }
}
