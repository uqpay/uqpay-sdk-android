package com.uqpay.sdk

import android.os.Parcel
import android.os.Parcelable
import com.uqpay.sdk.error.UQPayError
import com.uqpay.sdk.error.UQPayErrorCode
import com.uqpay.sdk.payment.PaymentMethodType
import com.uqpay.sdk.payment.PaymentResult
import com.uqpay.sdk.payment.PaymentSessionParams
import com.uqpay.sdk.payment.PaymentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal

/**
 * Parcelling is not an implementation detail — it *is* the process-death guarantee
 * (acceptance criteria §7.2). Arguments and results survive only because they round-trip
 * through the OS as parcels, so every public model that crosses that boundary is
 * verified here.
 */
@RunWith(RobolectricTestRunner::class)
class ParcelRoundTripTest {

    private fun <T : Parcelable> roundTrip(value: T, creator: Parcelable.Creator<T>): T {
        val parcel = Parcel.obtain()
        try {
            value.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return creator.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `payment result survives with every field populated`() {
        val original = PaymentResult(
            status = PaymentStatus.SUCCEEDED,
            paymentIntentId = "PI_123",
            paymentMethodType = PaymentMethodType.ALIPAY_CN,
            amount = BigDecimal("8.98"),
            currency = "SGD",
            merchantOrderId = "order-77",
            transactionId = "PA_456",
            completedAtEpochMillis = 1_755_000_000_000L,
            error = null,
        )

        val restored = roundTrip(original, PaymentResult.CREATOR)

        assertEquals(PaymentStatus.SUCCEEDED, restored.status)
        assertEquals("PI_123", restored.paymentIntentId)
        assertEquals(PaymentMethodType.ALIPAY_CN, restored.paymentMethodType)
        assertEquals(BigDecimal("8.98"), restored.amount)
        assertEquals("SGD", restored.currency)
        assertEquals("order-77", restored.merchantOrderId)
        assertEquals("PA_456", restored.transactionId)
        assertEquals(1_755_000_000_000L, restored.completedAtEpochMillis)
        assertNull(restored.error)
    }

    @Test
    fun `payment result survives with only the required fields`() {
        val restored = roundTrip(
            PaymentResult(status = PaymentStatus.CANCELLED, paymentIntentId = "PI_1"),
            PaymentResult.CREATOR,
        )

        assertEquals(PaymentStatus.CANCELLED, restored.status)
        assertEquals("PI_1", restored.paymentIntentId)
        assertNull(restored.amount)
        assertNull(restored.paymentMethodType)
        assertNull(restored.completedAtEpochMillis)
        assertNull(restored.error)
    }

    @Test
    fun `amount keeps exact decimal value - money is never a float`() {
        val restored = roundTrip(
            PaymentResult(
                status = PaymentStatus.SUCCEEDED,
                paymentIntentId = "PI_1",
                amount = BigDecimal("0.10"),
            ),
            PaymentResult.CREATOR,
        )

        // Scale is preserved too: "0.10" must not come back as "0.1".
        assertEquals(BigDecimal("0.10"), restored.amount)
        assertEquals("0.10", restored.amount?.toPlainString())
    }

    @Test
    fun `error survives including trace and decline codes`() {
        val restored = roundTrip(
            PaymentResult(
                status = PaymentStatus.FAILED,
                paymentIntentId = "PI_9",
                error = UQPayError(
                    code = UQPayErrorCode.CARD_DECLINED,
                    message = "Your card was declined.",
                    declineCode = "do_not_honor",
                    traceId = "trace-abc",
                ),
            ),
            PaymentResult.CREATOR,
        )

        val error = restored.error
        assertEquals(UQPayErrorCode.CARD_DECLINED, error?.code)
        assertEquals("Your card was declined.", error?.message)
        assertEquals("do_not_honor", error?.declineCode)
        assertEquals("trace-abc", error?.traceId)
    }

    @Test
    fun `error preserves a code this SDK version does not know`() {
        val restored = roundTrip(
            UQPayError(code = UQPayErrorCode.of("some_future_code"), message = "…"),
            UQPayError.CREATOR,
        )

        assertEquals("some_future_code", restored.code.raw)
    }

    @Test
    fun `session params survive each presentation mode`() {
        val list = roundTrip(PaymentSessionParams("PI_1"), PaymentSessionParams.CREATOR)
        assertEquals(PaymentSessionParams.Presentation.MethodList, list.presentation)
        assertEquals("PI_1", list.paymentIntentId)

        val card = roundTrip(
            PaymentSessionParams("PI_2", PaymentSessionParams.Presentation.CardOnly),
            PaymentSessionParams.CREATOR,
        )
        assertEquals(PaymentSessionParams.Presentation.CardOnly, card.presentation)

        val wallet = roundTrip(
            PaymentSessionParams(
                "PI_3",
                PaymentSessionParams.Presentation.SingleWallet(PaymentMethodType.GRABPAY),
            ),
            PaymentSessionParams.CREATOR,
        )
        assertEquals(
            PaymentSessionParams.Presentation.SingleWallet(PaymentMethodType.GRABPAY),
            wallet.presentation,
        )
    }
}
