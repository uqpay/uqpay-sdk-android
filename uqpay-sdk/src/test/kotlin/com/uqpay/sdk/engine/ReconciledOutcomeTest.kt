package com.uqpay.sdk.engine

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.uqpay.sdk.testErrorCopy
import com.uqpay.sdk.Environment
import com.uqpay.sdk.error.UQPayErrorCode
import com.uqpay.sdk.network.AttemptPaymentMethodDto
import com.uqpay.sdk.network.ErrorMapper
import com.uqpay.sdk.network.PaymentAttemptDto
import com.uqpay.sdk.network.PaymentIntentDto
import com.uqpay.sdk.payment.PaymentMethodType
import com.uqpay.sdk.payment.PaymentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * The shared payload builder. One payment, one description — and none of iOS's defects.
 */
@RunWith(RobolectricTestRunner::class)
class ReconciledOutcomeTest {

    private val sandbox = ErrorMapper(Environment.SANDBOX, testErrorCopy())
    private val production = ErrorMapper(Environment.PRODUCTION, testErrorCopy())

    @Test
    fun `empty failure code and message normalise to null`() {
        val intent = intent(failureCode = "", failureMessage = "  ")
        assertNull(ReconciledOutcome.failureCode(intent))
        assertNull(ReconciledOutcome.failureMessage(intent))
        assertEquals(ReconciledOutcome.FALLBACK_FAILURE_MESSAGE, ReconciledOutcome.failureMessageOrFallback(intent))
    }

    @Test
    fun `real failure code and message pass through`() {
        val intent = intent(failureCode = "insufficient_funds", failureMessage = "Not enough")
        assertEquals("insufficient_funds", ReconciledOutcome.failureCode(intent))
        assertEquals("Not enough", ReconciledOutcome.failureMessage(intent))
        assertEquals("Not enough", ReconciledOutcome.failureMessageOrFallback(intent))
    }

    @Test
    fun `no attempt at all is null code and fallback copy`() {
        val intent = PaymentIntentDto(paymentIntentId = "PI_1", intentStatus = "FAILED")
        assertNull(ReconciledOutcome.failureCode(intent))
        assertNull(ReconciledOutcome.failureMessage(intent))
        assertEquals(ReconciledOutcome.FALLBACK_FAILURE_MESSAGE, ReconciledOutcome.failureMessageOrFallback(intent))
    }

    @Test
    fun `amount is BigDecimal-exact from the string, never a float`() {
        val result = ReconciledOutcome.successResult(intent(amount = "8.98"), "PI_1", 1_000L)
        assertEquals(BigDecimal("8.98"), result.amount)
        assertEquals("8.98", result.amount?.toPlainString())
        assertEquals(0, BigDecimal("8.98").compareTo(result.amount))
        // 8.98f would print as 8.979999542236328 through BigDecimal(double).
        assertEquals(BigDecimal("0.10").add(BigDecimal("0.20")), ReconciledOutcome.amount(intent(amount = "0.30")))
    }

    @Test
    fun `unparseable or absent amount is null, not an exception`() {
        assertNull(ReconciledOutcome.amount(intent(amount = "eight")))
        assertNull(ReconciledOutcome.amount(intent(amount = "")))
        assertNull(ReconciledOutcome.amount(intent(amount = null)))
        assertEquals(PaymentStatus.SUCCEEDED, ReconciledOutcome.successResult(intent(amount = "eight"), "PI_1", 0L).status)
    }

    @Test
    fun `payment method type comes from the attempt, never hardcoded to card`() {
        assertEquals(PaymentMethodType.GRABPAY, ReconciledOutcome.successResult(intent(methodType = "grabpay"), "PI_1", 0L).paymentMethodType)
        assertEquals(PaymentMethodType.of("futurepay"), ReconciledOutcome.paymentMethodType(intent(methodType = "futurepay")))
        assertNull(ReconciledOutcome.successResult(intent(methodType = null), "PI_1", 0L).paymentMethodType)
        assertNull(ReconciledOutcome.successResult(intent(methodType = ""), "PI_1", 0L).paymentMethodType)
    }

    @Test
    fun `transaction id is the attempt id, not the intent id`() {
        val result = ReconciledOutcome.successResult(intent(attemptId = "PA_77"), "PI_1", 0L)
        assertEquals("PA_77", result.transactionId)
        assertEquals("PI_1", result.paymentIntentId)
        assertNull(ReconciledOutcome.transactionId(intent(attemptId = null)))
    }

    @Test
    fun `success result carries the intent's context and the observation time`() {
        val result = ReconciledOutcome.successResult(intent(), "PI_fallback", 1_755_500_000_000L)
        assertEquals(PaymentStatus.SUCCEEDED, result.status)
        assertEquals("PI_1", result.paymentIntentId)
        assertEquals("SGD", result.currency)
        assertEquals("order-1", result.merchantOrderId)
        assertEquals(1_755_500_000_000L, result.completedAtEpochMillis)
        assertNull(result.error)
    }

    @Test
    fun `a wire intent without an id falls back to the caller's id, never blank`() {
        val noId = PaymentIntentDto(paymentIntentId = "", intentStatus = "SUCCEEDED")
        assertEquals("PI_caller", ReconciledOutcome.successResult(noId, "PI_caller", 0L).paymentIntentId)
        assertEquals("PI_caller", ReconciledOutcome.cancelledResult(null, "PI_caller").paymentIntentId)
        assertEquals("PI_caller", ReconciledOutcome.pendingResult(null, "PI_caller", production.mapSettledOutcome(com.uqpay.sdk.network.IntentStatus.Failed, null)).paymentIntentId)
    }

    @Test
    fun `failure error routes through mapSettledOutcome with normalised code`() {
        val declined = intent(status = "FAILED", failureCode = "insufficient_funds", failureMessage = "Not enough")
        val error = ReconciledOutcome.failureError(declined, sandbox)
        assertEquals(UQPayErrorCode.INSUFFICIENT_FUNDS, error.code)
        assertEquals("insufficient_funds", error.declineCode)
        // The customer's sentence is the same in both environments and never quotes the
        // gateway. The gateway's own words go to the developer sentence, in sandbox only.
        assertEquals("The card was declined for insufficient funds.", error.message)
        assertTrue(error.developerMessage.orEmpty().contains("Not enough"))
        assertTrue(error.developerMessage.orEmpty().contains("insufficient_funds"))

        // Production: no gateway text at all, in either sentence.
        val inProduction = ReconciledOutcome.failureError(declined, production)
        assertEquals("The card was declined for insufficient funds.", inProduction.message)
        assertFalse(inProduction.developerMessage.orEmpty().contains("Not enough"))

        // Empty code → CARD_DECLINED and no decline code; a cancelled intent → CANCELLED.
        val blank = ReconciledOutcome.failureError(intent(status = "FAILED", failureCode = ""), production)
        assertEquals(UQPayErrorCode.CARD_DECLINED, blank.code)
        assertNull(blank.declineCode)
        assertEquals(UQPayErrorCode.CANCELLED, ReconciledOutcome.failureError(intent(status = "CANCELLED", failureCode = "card_declined"), production).code)
    }

    @Test
    fun `fallback failure code is used only when the attempt has none`() {
        val silent = intent(status = "REQUIRES_PAYMENT_METHOD", failureCode = "")
        assertEquals(UQPayErrorCode.THREE_DS_FAILED, ReconciledOutcome.failureError(silent, production, "3ds_failed").code)
        val loud = intent(status = "REQUIRES_PAYMENT_METHOD", failureCode = "insufficient_funds")
        assertEquals(UQPayErrorCode.INSUFFICIENT_FUNDS, ReconciledOutcome.failureError(loud, production, "3ds_failed").code)
    }

    @Test
    fun `failure pending and cancelled results carry status, context and error correctly`() {
        val error = production.mapSettledOutcome(com.uqpay.sdk.network.IntentStatus.Failed, "card_declined")
        val failed = ReconciledOutcome.failureResult(intent(), "PI_1", error)
        assertEquals(PaymentStatus.FAILED, failed.status)
        assertEquals(error, failed.error)
        assertEquals(BigDecimal("8.98"), failed.amount)
        assertEquals("PA_1", failed.transactionId)

        val pending = ReconciledOutcome.pendingResult(intent(), "PI_1", error)
        assertEquals(PaymentStatus.PENDING, pending.status)
        assertEquals(error, pending.error)
        assertEquals(PaymentMethodType.CARD, pending.paymentMethodType)

        val cancelled = ReconciledOutcome.cancelledResult(intent(), "PI_1")
        assertEquals(PaymentStatus.CANCELLED, cancelled.status)
        assertNull(cancelled.error)
        assertEquals("SGD", cancelled.currency)

        val cancelledBlind = ReconciledOutcome.cancelledResult(null, "PI_1")
        assertNull(cancelledBlind.amount)
        assertEquals("PI_1", cancelledBlind.paymentIntentId)
    }

    @Test
    fun `EngineOutcome converts through ReconciledOutcome only`() {
        val ok = EngineOutcome.Succeeded(intent(methodType = "alipaycn")).toResult("PI_1", 5L)
        assertEquals(PaymentMethodType.ALIPAY_CN, ok.paymentMethodType)
        assertEquals(5L, ok.completedAtEpochMillis)
        val error = production.mapSettledOutcome(com.uqpay.sdk.network.IntentStatus.Failed, null)
        assertEquals(PaymentStatus.FAILED, EngineOutcome.Failed(error, null).toResult("PI_1", 5L).status)
        assertEquals(PaymentStatus.PENDING, EngineOutcome.Pending(error, null).toResult("PI_1", 5L).status)
        assertEquals(PaymentStatus.CANCELLED, EngineOutcome.Cancelled(null).toResult("PI_1", 5L).status)
        assertEquals("PI_1", EngineOutcome.Cancelled(null).toResult("PI_1", 5L).paymentIntentId)
    }

    private fun intent(
        status: String = "SUCCEEDED",
        amount: String? = "8.98",
        methodType: String? = "card",
        attemptId: String? = "PA_1",
        failureCode: String? = null,
        failureMessage: String? = null,
    ) = PaymentIntentDto(
        paymentIntentId = "PI_1",
        intentStatus = status,
        amount = amount,
        currency = "SGD",
        merchantOrderId = "order-1",
        latestPaymentAttempt = PaymentAttemptDto(
            attemptId = attemptId,
            attemptStatus = "FAILED",
            paymentMethod = methodType?.let(::AttemptPaymentMethodDto),
            failureCode = failureCode,
            failureMessage = failureMessage,
        ),
    )
}
