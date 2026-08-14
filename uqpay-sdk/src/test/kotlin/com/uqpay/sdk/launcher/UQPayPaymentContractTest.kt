package com.uqpay.sdk.launcher

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

    private val contract = UQPayPaymentContract()
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

    @Test
    fun `a null intent is reported, never thrown`() {
        val parsed = contract.parseResult(Activity.RESULT_CANCELED, null)

        assertEquals(PaymentStatus.CANCELLED, parsed.status)
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
