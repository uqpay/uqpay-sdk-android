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

    // ---- billing prefill ---------------------------------------------------------------

    /**
     * The prefill crosses the same process boundary as everything else, and a form that
     * came back half-seeded after process death would be worse than one that came back
     * empty — the customer would trust fields the merchant no longer stands behind. Every
     * one of the ten is asserted individually.
     *
     * No real person's details appear here.
     */
    @Test
    fun `billing details survive with every field populated`() {
        val original = PaymentSessionParams.BillingDetails(
            firstName = "John",
            lastName = "Tan",
            email = "john.tan@example.com",
            phone = "+6591234567",
            addressLine1 = "123 Orchard Road",
            addressLine2 = "#12-01",
            city = "Singapore",
            state = "Singapore",
            postalCode = "238888",
            countryCode = "SG",
        )

        val restored = roundTrip(original, PaymentSessionParams.BillingDetails.CREATOR)

        assertEquals("John", restored.firstName)
        assertEquals("Tan", restored.lastName)
        assertEquals("john.tan@example.com", restored.email)
        assertEquals("+6591234567", restored.phone)
        assertEquals("123 Orchard Road", restored.addressLine1)
        assertEquals("#12-01", restored.addressLine2)
        assertEquals("Singapore", restored.city)
        assertEquals("Singapore", restored.state)
        assertEquals("238888", restored.postalCode)
        assertEquals("SG", restored.countryCode)
        assertEquals(original, restored)
    }

    /**
     * Absent must come back absent, not `""`. The two are different on the wire — see
     * `ConfirmPayload.ABSENT_FIELD` — and a parcel that turned one into the other would
     * change the confirm body under an already-pinned idempotency key.
     */
    @Test
    fun `billing details survive with nothing populated at all`() {
        val restored = roundTrip(
            PaymentSessionParams.BillingDetails(),
            PaymentSessionParams.BillingDetails.CREATOR,
        )

        assertNull(restored.firstName)
        assertNull(restored.lastName)
        assertNull(restored.email)
        assertNull(restored.phone)
        assertNull(restored.addressLine1)
        assertNull(restored.addressLine2)
        assertNull(restored.city)
        assertNull(restored.state)
        assertNull(restored.postalCode)
        assertNull(restored.countryCode)
    }

    /** A partial prefill must not shift the fields that follow it. */
    @Test
    fun `a partly populated prefill keeps each value on its own field`() {
        val restored = roundTrip(
            PaymentSessionParams.BillingDetails(
                firstName = "John",
                city = "Singapore",
                countryCode = "SG",
            ),
            PaymentSessionParams.BillingDetails.CREATOR,
        )

        assertEquals("John", restored.firstName)
        assertNull(restored.lastName)
        assertNull(restored.email)
        assertNull(restored.phone)
        assertEquals("Singapore", restored.city)
        assertNull(restored.state)
        assertNull(restored.postalCode)
        assertEquals("SG", restored.countryCode)
    }

    @Test
    fun `session params carry the prefill across the boundary, and its absence too`() {
        val prefill = PaymentSessionParams.BillingDetails(
            firstName = "John",
            email = "john.tan@example.com",
            countryCode = "SG",
        )

        val withPrefill = roundTrip(
            PaymentSessionParams("PI_7", billingDetails = prefill),
            PaymentSessionParams.CREATOR,
        )
        assertEquals(prefill, withPrefill.billingDetails)
        assertEquals("PI_7", withPrefill.paymentIntentId)
        assertEquals(PaymentSessionParams.Presentation.MethodList, withPrefill.presentation)

        val without = roundTrip(PaymentSessionParams("PI_8"), PaymentSessionParams.CREATOR)
        assertNull("a launch with no prefill must not invent one", without.billingDetails)
    }

    /**
     * The prefill is written after the presentation, and `SingleWallet` writes an extra
     * string of its own. Reading them back out of order would silently hand the wallet's
     * name to the form as a first name — so every presentation is checked with a prefill
     * attached, not only the default one.
     */
    @Test
    fun `the prefill reads back correctly behind every presentation`() {
        val prefill = PaymentSessionParams.BillingDetails(firstName = "John", countryCode = "SG")

        for (presentation in listOf(
            PaymentSessionParams.Presentation.MethodList,
            PaymentSessionParams.Presentation.CardOnly,
            PaymentSessionParams.Presentation.SingleWallet(PaymentMethodType.GRABPAY),
        )) {
            val restored = roundTrip(
                PaymentSessionParams("PI_9", presentation, prefill),
                PaymentSessionParams.CREATOR,
            )
            assertEquals(presentation, restored.presentation)
            assertEquals("behind $presentation", prefill, restored.billingDetails)
        }
    }
}
