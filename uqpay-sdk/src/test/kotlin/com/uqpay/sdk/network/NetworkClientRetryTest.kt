package com.uqpay.sdk.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Retry policy — the money-critical part of the network layer.
 *
 * The invariant: a request is resent **only** when resending it provably cannot charge
 * the customer twice. A GET is always safe; a mutating call is safe only while carrying
 * an idempotency key, because a 5xx or a dropped connection can mean "the acquirer
 * authorised while the edge gave up" (ios-requirements §5.1).
 *
 * Delays run on virtual time, so the suite never waits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkClientRetryTest {

    private val instantBackoff = RetryDelaySupplier { FIXED_DELAY_MILLIS }

    private fun get(url: String = "https://api-sandbox.uqpaytech.com/api/v2/payment_intents/PI_1") =
        UQPayRequest(method = HttpMethod.GET, url = url)

    private fun confirm(idempotencyKey: String? = "11111111-2222-3333-4444-555555555555") =
        UQPayRequest(
            method = HttpMethod.POST,
            url = "https://api-sandbox.uqpaytech.com/api/v2/payment_intents/PI_1/confirm",
            idempotencyKey = idempotencyKey,
            body = CONFIRM_BODY,
        )

    // ---------------------------------------------------------------- what is retried

    @Test
    fun `a 429 is retried and the eventual success is returned`() = runTest {
        val factory = FakeConnectionFactory(FakeReply(status = 429), FakeReply(status = 200))
        val response = client(factory).execute(get())

        assertEquals(200, response.statusCode)
        assertEquals(2, factory.callCount)
    }

    @Test
    fun `a 5xx is retried`() = runTest {
        listOf(500, 502, 503, 599).forEach { status ->
            val factory = FakeConnectionFactory(FakeReply(status = status), FakeReply(status = 200))
            val response = client(factory).execute(get())

            assertEquals("HTTP $status", 200, response.statusCode)
            assertEquals("HTTP $status", 2, factory.callCount)
        }
    }

    @Test
    fun `a 4xx is never retried`() = runTest {
        listOf(400, 401, 402, 403, 404, 409, 422).forEach { status ->
            val factory = FakeConnectionFactory(FakeReply(status = status))
            val response = client(factory).execute(get())

            assertEquals("HTTP $status", status, response.statusCode)
            assertEquals("HTTP $status was retried", 1, factory.callCount)
        }
    }

    @Test
    fun `a 2xx is returned on the first attempt`() = runTest {
        val factory = FakeConnectionFactory(FakeReply(status = 200, body = """{"id":"PI_1"}"""))
        val response = client(factory).execute(get())

        assertEquals(200, response.statusCode)
        assertEquals("""{"id":"PI_1"}""", response.body)
        assertEquals(1, factory.callCount)
    }

    @Test
    fun `retrying stops at the configured maximum`() = runTest {
        val factory = FakeConnectionFactory(FakeReply(status = 503))
        val response = client(factory, maxRetries = 3).execute(get())

        // One attempt plus three retries, then the last response is handed back for the
        // caller to classify — a live retry loop is worse than a reported failure.
        assertEquals(4, factory.callCount)
        assertEquals(503, response.statusCode)
    }

    @Test
    fun `a maxRetries of zero means one attempt`() = runTest {
        val factory = FakeConnectionFactory(FakeReply(status = 503))
        client(factory, maxRetries = 0).execute(get())

        assertEquals(1, factory.callCount)
    }

    // ---------------------------------------------------------------- double-charge guard

    @Test
    fun `a post with no idempotency key is never resent - resending could double charge`() =
        runTest {
            val factory = FakeConnectionFactory(FakeReply(status = 503))
            val response = client(factory).execute(confirm(idempotencyKey = null))

            assertEquals("an unkeyed POST was resent", 1, factory.callCount)
            assertEquals(503, response.statusCode)
        }

    @Test
    fun `a post with no idempotency key is not resent after a transport failure either`() =
        runTest {
            val factory = FakeConnectionFactory(FakeReply.failing(IOException("connection reset")))

            val thrown = runCatching { client(factory).execute(confirm(idempotencyKey = null)) }
                .exceptionOrNull()

            assertTrue(thrown is UQPayApiException.TransportFailure)
            assertEquals(1, factory.callCount)
        }

    @Test
    fun `a post with an idempotency key is resent`() = runTest {
        val factory = FakeConnectionFactory(FakeReply(status = 503), FakeReply(status = 200))
        val response = client(factory).execute(confirm())

        assertEquals(200, response.statusCode)
        assertEquals(2, factory.callCount)
    }

    @Test
    fun `a replayed post carries the same key and a byte-identical body`() = runTest {
        val factory = FakeConnectionFactory(FakeReply(status = 503), FakeReply(status = 200))
        client(factory).execute(confirm())

        // The gateway rejects a reused key whose payload changed (G7).
        assertEquals(1, factory.requests.map { it.idempotencyKey }.toSet().size)
        assertEquals("11111111-2222-3333-4444-555555555555", factory.requests[1].idempotencyKey)
        assertEquals(listOf(CONFIRM_BODY, CONFIRM_BODY), factory.bodiesWritten)
    }

    // ---------------------------------------------------------------- transport failures

    @Test
    fun `a transport failure on a safe request is retried`() = runTest {
        val factory = FakeConnectionFactory(
            FakeReply.failing(IOException("connection reset")),
            FakeReply(status = 200),
        )
        val response = client(factory).execute(get())

        assertEquals(200, response.statusCode)
        assertEquals(2, factory.callCount)
    }

    @Test
    fun `a transport failure that outlives every retry surfaces as a typed exception`() = runTest {
        val factory = FakeConnectionFactory(FakeReply.failing(IOException("connection reset")))

        val thrown = runCatching { client(factory).execute(get()) }.exceptionOrNull()

        assertTrue(thrown is UQPayApiException.TransportFailure)
        assertEquals(4, factory.callCount)
    }

    @Test
    fun `a read timeout surfaces as a timed out rather than a transport failure`() = runTest {
        val factory = FakeConnectionFactory(FakeReply.failing(SocketTimeoutException("read timed out")))

        val thrown = runCatching { client(factory).execute(get()) }.exceptionOrNull()

        assertTrue(thrown is UQPayApiException.TimedOut)
    }

    // ---------------------------------------------------------------- backoff timing

    @Test
    fun `the backoff schedule is used between retries`() = runTest {
        val factory = FakeConnectionFactory(FakeReply(status = 503), FakeReply(status = 200))
        val started = testScheduler.currentTime

        client(factory).execute(get())

        assertEquals(FIXED_DELAY_MILLIS, testScheduler.currentTime - started)
    }

    @Test
    fun `retry-after wins over the backoff schedule`() = runTest {
        val factory = FakeConnectionFactory(
            FakeReply.retryAfter(429, seconds = "7"),
            FakeReply(status = 200),
        )
        val started = testScheduler.currentTime

        client(factory).execute(get())

        // The gateway knows better than our schedule does.
        assertEquals(7_000L, testScheduler.currentTime - started)
    }

    @Test
    fun `an unusable retry-after falls back to the backoff schedule`() = runTest {
        listOf("0", "-3", "soon", "").forEach { header ->
            val factory = FakeConnectionFactory(
                FakeReply.retryAfter(503, seconds = header),
                FakeReply(status = 200),
            )
            val started = testScheduler.currentTime

            client(factory).execute(get())

            assertEquals("retry-after: $header", FIXED_DELAY_MILLIS, testScheduler.currentTime - started)
        }
    }

    @Test
    fun `retry-after is honoured on every retry it is sent on`() = runTest {
        val factory = FakeConnectionFactory(
            FakeReply.retryAfter(503, seconds = "3"),
            FakeReply.retryAfter(503, seconds = "5"),
            FakeReply(status = 200),
        )
        val started = testScheduler.currentTime

        client(factory).execute(get())

        assertEquals(8_000L, testScheduler.currentTime - started)
        assertEquals(3, factory.callCount)
    }

    // ---------------------------------------------------------------- cancellation

    @Test
    fun `a cancellation propagates rather than being retried`() = runTest {
        val factory = FakeConnectionFactory(FakeReply.failing(CancellationException("user left")))

        val thrown = runCatching { client(factory).execute(get()) }.exceptionOrNull()

        assertTrue("expected a CancellationException, got $thrown", thrown is CancellationException)
        assertEquals("a cancelled request was retried", 1, factory.callCount)
    }

    // ---------------------------------------------------------------- response reading

    @Test
    fun `the trace id is read from whichever header the gateway used`() = runTest {
        listOf("x-request-id", "request-id", "x-b3-traceid").forEach { header ->
            val factory = FakeConnectionFactory(
                FakeReply(status = 200, headers = mapOf(header to "trace-9")),
            )
            assertEquals(header, "trace-9", client(factory).execute(get()).traceId)
        }
    }

    @Test
    fun `a response with no trace id header is still usable`() = runTest {
        val response = client(FakeConnectionFactory(FakeReply(status = 200))).execute(get())
        assertNull(response.traceId)
        assertNull(response.retryAfterSeconds)
    }

    private fun TestScope.client(
        factory: FakeConnectionFactory,
        maxRetries: Int = 3,
    ) = DefaultUQPayNetworkClient(
        connectionFactory = factory,
        retryDelaySupplier = instantBackoff,
        workContext = StandardTestDispatcher(testScheduler),
        maxRetries = maxRetries,
    )

    private companion object {
        const val FIXED_DELAY_MILLIS = 2_000L

        /** Documented sandbox values only (api-contract §9) — never a real PAN. */
        const val CONFIRM_BODY =
            """{"payment_method":{"card":{"number":"4176660000000027","expiry_month":"12"}}}"""
    }
}
