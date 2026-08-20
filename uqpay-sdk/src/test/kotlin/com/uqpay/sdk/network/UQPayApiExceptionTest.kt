package com.uqpay.sdk.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * Outcome classification (ios-requirements §5.1). This is the flag the idempotency layer
 * reads, so getting it wrong is a double charge or a false decline:
 *
 * - **unknown outcome** → keep the pin, replay the same key with a byte-identical body;
 * - **definitive** → the attempt is over, release the key;
 * - **local cancellation** → report nothing, leave the pin untouched.
 */
class UQPayApiExceptionTest {

    private fun apiError(status: Int, code: String? = "confirm_payment_intent_failed") =
        UQPayApiException.ApiError(ApiErrorBody(code = code, message = "m"), "trace", status)

    @Test
    fun `a 2xx that could not be read is an unknown outcome, never a failure`() {
        // The payment WAS processed — the request reached the gateway and was acted on.
        val e = UQPayApiException.DecodingFailure(200, "trace", IOException("truncated"))
        assertTrue(e.isOutcomeUnknown)
    }

    @Test
    fun `a decoding failure is not retried on its own - only replayed with the same key`() {
        // isRetryable drives the blind transport retry; a decoding failure must go
        // through the idempotent replay ladder instead.
        assertFalse(UQPayApiException.DecodingFailure(201, null, null).isRetryable)
    }

    @Test
    fun `no response arriving is an unknown outcome and retryable`() {
        val transport = UQPayApiException.TransportFailure(IOException("connection reset"))
        assertTrue(transport.isOutcomeUnknown)
        assertTrue(transport.isRetryable)
    }

    @Test
    fun `a timeout is an unknown outcome and retryable`() {
        val timedOut = UQPayApiException.TimedOut(SocketTimeoutException())
        assertTrue(timedOut.isOutcomeUnknown)
        assertTrue(timedOut.isRetryable)
    }

    @Test
    fun `a structured 4xx is definitive`() {
        listOf(400, 401, 402, 403, 404, 422).forEach { status ->
            val e = apiError(status)
            assertFalse("HTTP $status", e.isOutcomeUnknown)
            assertFalse("HTTP $status", e.isRetryable)
        }
    }

    @Test
    fun `a 429 or 5xx is an unknown outcome - the acquirer may have authorised`() {
        listOf(429, 500, 502, 503, 599).forEach { status ->
            assertTrue("HTTP $status", apiError(status).isOutcomeUnknown)
            assertTrue("HTTP $status", apiError(status).isRetryable)
            assertTrue("HTTP $status", UQPayApiException.UnexpectedStatus(status, null).isOutcomeUnknown)
            assertTrue("HTTP $status", UQPayApiException.UnexpectedStatus(status, null).isRetryable)
        }
    }

    /**
     * **A redirect proves nothing about what the origin did with the body (audit item 15).**
     *
     * Redirects are deliberately not followed (`DefaultConnectionFactory`), so a 3xx surfaces
     * here as a non-2xx. Classified as definitive, it released the idempotency pin and was
     * reported as `FAILED` — for a confirm that an edge may well have handed on to a backend
     * that processed it. The customer is told the payment failed, taps Pay again, and the
     * second attempt mints a *fresh* key against a payment that may already be authorising.
     */
    @Test
    fun `a 3xx is an unknown outcome - the origin may still have processed the body`() {
        listOf(300, 301, 302, 303, 307, 308, 399).forEach { status ->
            assertTrue("HTTP $status", apiError(status).isOutcomeUnknown)
            assertTrue("HTTP $status", UQPayApiException.UnexpectedStatus(status, null).isOutcomeUnknown)
        }
    }

    /** The classification is one rule, in one place, for both exception shapes. */
    @Test
    fun `the unknown-outcome status rule covers 3xx, 429 and anything at or above 500`() {
        listOf(300, 399, 429, 500, 599).forEach { status ->
            assertTrue("HTTP $status", UQPayApiException.isUnknownOutcomeStatus(status))
        }
        // Everything a gateway can say that is a *decision* about the request stays definitive.
        listOf(200, 201, 400, 401, 404, 422, 428).forEach { status ->
            assertFalse("HTTP $status", UQPayApiException.isUnknownOutcomeStatus(status))
        }
    }

    @Test
    fun `a local cancellation is neither unknown nor retryable`() {
        val cancelled = UQPayApiException.Cancelled()
        assertFalse(cancelled.isOutcomeUnknown)
        assertFalse(cancelled.isRetryable)
    }

    @Test
    fun `a configuration failure never left the device so nothing is unknown`() {
        val notConfigured = UQPayApiException.NotConfigured("missing intent id")
        assertFalse(notConfigured.isOutcomeUnknown)
        assertFalse(notConfigured.isRetryable)
    }

    @Test
    fun `an authentication failure is definitive`() {
        val auth = UQPayApiException.AuthenticationFailed("no token")
        assertFalse(auth.isOutcomeUnknown)
        assertFalse(auth.isRetryable)
    }

    @Test
    fun `an api error keeps the gateway body, status and trace id for the mapper`() {
        val e = apiError(402, code = "card_declined")
        assertEquals(402, e.statusCode)
        assertEquals("trace", e.traceId)
        assertEquals("card_declined", e.apiError?.code)
    }

    @Test
    fun `an api error with a blank message still has a usable message`() {
        val e = UQPayApiException.ApiError(ApiErrorBody(message = ""), null, 400)
        assertEquals("The gateway rejected the request.", e.message)
    }

    @Test
    fun `an exception with no response carries status zero`() {
        assertEquals(0, UQPayApiException.TransportFailure(IOException()).statusCode)
        assertEquals(0, UQPayApiException.TimedOut().statusCode)
        assertEquals(0, UQPayApiException.Cancelled().statusCode)
    }

    // ---------------------------------------------------------------- asApiException

    @Test
    fun `an interrupted io exception becomes a timeout`() {
        val cause = SocketTimeoutException("read timed out")
        val mapped = cause.asApiException()
        assertTrue(mapped is UQPayApiException.TimedOut)
        assertSame(cause, mapped.cause)
    }

    @Test
    fun `a plain io exception becomes a transport failure`() {
        val cause = IOException("connection reset")
        val mapped = cause.asApiException()
        assertTrue(mapped is UQPayApiException.TransportFailure)
        assertSame(cause, mapped.cause)
    }

    @Test
    fun `an arbitrary throwable still becomes a typed transport failure`() {
        val mapped = RuntimeException("unexpected").asApiException()
        assertTrue(mapped is UQPayApiException.TransportFailure)
    }

    @Test
    fun `an already-typed exception passes through unchanged`() {
        val original: UQPayApiException = UQPayApiException.Cancelled()
        assertSame(original, original.asApiException())
    }

    @Test
    fun `an interrupted io subclass other than socket timeout is still a timeout`() {
        val mapped = InterruptedIOException("aborted").asApiException()
        assertTrue(mapped is UQPayApiException.TimedOut)
    }
}
