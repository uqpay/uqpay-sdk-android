package com.uqpay.sdk.network

import com.uqpay.sdk.Environment
import com.uqpay.sdk.UQPayConfiguration
import com.uqpay.sdk.auth.UQPayAuthToken
import com.uqpay.sdk.auth.UQPayTokenProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The two endpoints the app may call, and the three things that go wrong around them:
 * a token invalidated by another device (`401`), a 2xx that cannot be read, and a
 * confirm sent without the key that makes it safe to replay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UQPayApiClientTest {

    private class RecordingNetworkClient(private vararg val script: UQPayResponse) :
        UQPayNetworkClient {
        val requests: MutableList<UQPayRequest> = mutableListOf()

        override suspend fun execute(request: UQPayRequest): UQPayResponse {
            val response = script[minOf(requests.size, script.size - 1)]
            requests += request
            return response
        }
    }

    private class CountingTokenProvider(private val value: () -> String = { "tok-1" }) :
        UQPayTokenProvider {
        val calls = AtomicInteger()

        override fun fetchToken(): UQPayAuthToken {
            calls.incrementAndGet()
            return UQPayAuthToken(value(), System.currentTimeMillis() + 30 * 60_000L)
        }
    }

    private val provider = CountingTokenProvider()

    private val configuration = UQPayConfiguration(
        clientId = "client-test",
        environment = Environment.SANDBOX,
        tokenProvider = provider,
    )

    private fun client(network: UQPayNetworkClient) = UQPayApiClient(
        configuration = configuration,
        networkClient = network,
        tokenManager = TokenManager(provider, UnconfinedTestDispatcher()),
    )

    private fun response(status: Int, body: String?, traceId: String? = "trace-1") =
        UQPayResponse(status, body, traceId, null)

    private val intentJson = """
        {
          "id": "PI_1",
          "intent_status": "REQUIRES_CUSTOMER_ACTION",
          "amount": "8.98",
          "currency": "SGD",
          "merchant_order_id": "order-77",
          "available_payment_method_types": ["card", "alipaycn"],
          "latest_payment_attempt": {
            "id": "PA_1",
            "attempt_status": "AUTHENTICATION_REDIRECTED",
            "failure_code": "",
            "failure_message": ""
          },
          "next_action": { "type": "redirect_to_url", "redirect_to_url": { "url": "https://3ds.example/x" } },
          "a_field_this_sdk_version_predates": { "nested": true }
        }
    """.trimIndent()

    private val confirmBody =
        """{"payment_method":{"card":{"number":"4176660000000027","expiry_month":"12"}}}"""

    private val idempotencyKey = "11111111-2222-3333-4444-555555555555"

    // ---------------------------------------------------------------- happy path

    @Test
    fun `an intent is retrieved and decoded, tolerating fields this version predates`() = runTest {
        val network = RecordingNetworkClient(response(200, intentJson))

        val dto = client(network).retrieveIntent("PI_1")

        assertEquals("PI_1", dto.id)
        assertEquals("REQUIRES_CUSTOMER_ACTION", dto.intentStatus)
        assertEquals("8.98", dto.amount)
        assertEquals("order-77", dto.merchantOrderId)
        assertEquals(listOf("card", "alipaycn"), dto.availablePaymentMethodTypes)
        assertEquals("PA_1", dto.latestPaymentAttempt?.id)
        assertEquals("https://3ds.example/x", dto.nextAction?.redirectToUrl?.url)
    }

    @Test
    fun `retrieve targets the configured environment with a GET and no idempotency key`() =
        runTest {
            val network = RecordingNetworkClient(response(200, intentJson))

            client(network).retrieveIntent("PI_1")

            val request = network.requests.single()
            assertEquals(HttpMethod.GET, request.method)
            assertEquals(
                "https://api-sandbox.uqpaytech.com/api/v2/payment_intents/PI_1",
                request.url,
            )
            assertNull(request.idempotencyKey)
            assertNull(request.body)
        }

    @Test
    fun `confirm targets the confirm endpoint with a POST and the idempotency key`() = runTest {
        val network = RecordingNetworkClient(response(200, intentJson))

        client(network).confirmIntent("PI_1", confirmBody, idempotencyKey)

        val request = network.requests.single()
        assertEquals(HttpMethod.POST, request.method)
        assertEquals(
            "https://api-sandbox.uqpaytech.com/api/v2/payment_intents/PI_1/confirm",
            request.url,
        )
        assertEquals(idempotencyKey, request.idempotencyKey)
    }

    @Test
    fun `the confirm body is forwarded byte-identically`() = runTest {
        // A replay must resend identical bytes: the gateway rejects a reused key whose
        // payload changed (G7). Re-encoding a model is not guaranteed to be identical,
        // so the client must not touch the string it was given.
        val network = RecordingNetworkClient(response(200, intentJson))

        client(network).confirmIntent("PI_1", confirmBody, idempotencyKey)

        assertEquals(confirmBody, network.requests.single().body)
    }

    @Test
    fun `every request carries the auth headers`() = runTest {
        val network = RecordingNetworkClient(response(200, intentJson))

        client(network).confirmIntent("PI_1", confirmBody, idempotencyKey)

        val headers = network.requests.single().headers
        assertEquals("tok-1", headers["x-auth-token"])
        assertEquals("client-test", headers["x-client-id"])
        assertTrue(headers.getValue("User-Agent").startsWith("UQPay-Android-SDK/"))
    }

    @Test
    fun `caller-supplied headers are preserved alongside the auth headers`() = runTest {
        val network = RecordingNetworkClient(response(200, intentJson))

        client(network).retrieveIntent("PI_1")

        assertTrue(network.requests.single().headers.containsKey("x-auth-token"))
    }

    // ---------------------------------------------------------------- 401 handling

    @Test
    fun `a 401 triggers exactly one token refresh and one retry, then gives up`() = runTest {
        val network = RecordingNetworkClient(response(401, null))

        val thrown = runCatching { client(network).retrieveIntent("PI_1") }.exceptionOrNull()

        // Two sends, two tokens — and no third attempt. Retrying further would turn a
        // configuration problem into a silent hang.
        assertEquals(2, network.requests.size)
        assertEquals(2, provider.calls.get())
        assertTrue(thrown is UQPayApiException)
        assertEquals(401, (thrown as UQPayApiException).statusCode)
    }

    @Test
    fun `a 401 followed by success returns the intent`() = runTest {
        val network = RecordingNetworkClient(response(401, null), response(200, intentJson))

        val dto = client(network).retrieveIntent("PI_1")

        assertEquals("PI_1", dto.id)
        assertEquals(2, network.requests.size)
    }

    @Test
    fun `the retry after a 401 carries a freshly minted token`() = runTest {
        val tokens = ArrayDeque(listOf("tok-1", "tok-2"))
        val rotating = CountingTokenProvider { tokens.removeFirst() }
        val network = RecordingNetworkClient(response(401, null), response(200, intentJson))
        val apiClient = UQPayApiClient(
            configuration = UQPayConfiguration("client-test", Environment.SANDBOX, rotating),
            networkClient = network,
            tokenManager = TokenManager(rotating, UnconfinedTestDispatcher()),
        )

        apiClient.retrieveIntent("PI_1")

        assertEquals("tok-1", network.requests[0].headers["x-auth-token"])
        assertEquals("tok-2", network.requests[1].headers["x-auth-token"])
    }

    @Test
    fun `the confirm resent after a 401 replays the same key and body`() = runTest {
        // The 401 retry is a resend of a mutating call: it must be idempotent by the
        // same rules as any other replay, or a token race becomes a double charge.
        val network = RecordingNetworkClient(response(401, null), response(200, intentJson))

        client(network).confirmIntent("PI_1", confirmBody, idempotencyKey)

        assertEquals(2, network.requests.size)
        assertEquals(listOf(idempotencyKey, idempotencyKey), network.requests.map { it.idempotencyKey })
        assertEquals(listOf(confirmBody, confirmBody), network.requests.map { it.body })
    }

    @Test
    fun `a 403 is not retried - it is not a stale token`() = runTest {
        val network = RecordingNetworkClient(response(403, """{"type":"unauthorized_error","code":"unauthorized_error","message":"denied"}"""))

        runCatching { client(network).retrieveIntent("PI_1") }

        assertEquals(1, network.requests.size)
        assertEquals(1, provider.calls.get())
    }

    // ---------------------------------------------------------------- unreadable 2xx

    @Test
    fun `a 2xx with an unreadable body is a decoding failure, never a decline`() = runTest {
        // The payment WAS processed — the request reached the gateway and was acted on.
        val network = RecordingNetworkClient(
            response(200, "<html><head><title>200 OK</title></head></html>"),
        )

        val thrown = runCatching { client(network).retrieveIntent("PI_1") }.exceptionOrNull()

        assertTrue("got $thrown", thrown is UQPayApiException.DecodingFailure)
        assertTrue((thrown as UQPayApiException).isOutcomeUnknown)
        assertEquals(200, thrown.statusCode)
        assertEquals("trace-1", thrown.traceId)
    }

    @Test
    fun `an empty 2xx body is a decoding failure`() = runTest {
        listOf(null, "", "   ").forEach { body ->
            val network = RecordingNetworkClient(response(200, body))

            val thrown = runCatching { client(network).retrieveIntent("PI_1") }.exceptionOrNull()

            assertTrue("body=<$body>, got $thrown", thrown is UQPayApiException.DecodingFailure)
        }
    }

    @Test
    fun `a truncated 2xx body is a decoding failure`() = runTest {
        val network = RecordingNetworkClient(response(200, """{"id":"PI_1","intent_st"""))

        val thrown = runCatching { client(network).retrieveIntent("PI_1") }.exceptionOrNull()

        assertTrue("got $thrown", thrown is UQPayApiException.DecodingFailure)
    }

    @Test
    fun `a decoding failure never carries the response body into its message`() = runTest {
        val network = RecordingNetworkClient(
            response(200, """{"card":{"number":"4176660000000027"} """),
        )

        val thrown = runCatching { client(network).retrieveIntent("PI_1") }.exceptionOrNull()

        assertFalse(thrown?.message.orEmpty().contains("4176660000000027"))
    }

    // ---------------------------------------------------------------- error bodies

    @Test
    fun `a structured error body becomes an api error carrying the gateway code`() = runTest {
        val network = RecordingNetworkClient(
            response(
                400,
                """{"type":"payment_error","code":"confirm_payment_intent_failed","message":"Confirm payment intent failed"}""",
            ),
        )

        val thrown = runCatching {
            client(network).confirmIntent("PI_1", confirmBody, idempotencyKey)
        }.exceptionOrNull()

        assertTrue("got $thrown", thrown is UQPayApiException.ApiError)
        assertEquals("confirm_payment_intent_failed", (thrown as UQPayApiException).apiError?.code)
        assertEquals(400, thrown.statusCode)
    }

    @Test
    fun `a non-2xx with no parsable body becomes an unexpected status`() = runTest {
        listOf(null, "", "<html>502 Bad Gateway</html>").forEach { body ->
            val network = RecordingNetworkClient(response(502, body))

            val thrown = runCatching { client(network).retrieveIntent("PI_1") }.exceptionOrNull()

            assertTrue("body=<$body>, got $thrown", thrown is UQPayApiException.UnexpectedStatus)
            assertTrue((thrown as UQPayApiException).isOutcomeUnknown)
        }
    }

    @Test
    fun `an error body of empty strings is treated as no body at all`() = runTest {
        // G5 — the API sends "" rather than omitting fields.
        val network = RecordingNetworkClient(response(500, """{"type":"","code":"","message":""}"""))

        val thrown = runCatching { client(network).retrieveIntent("PI_1") }.exceptionOrNull()

        assertTrue("got $thrown", thrown is UQPayApiException.UnexpectedStatus)
    }

    /**
     * **Reported gap, not a passing requirement.**
     *
     * Envelope B (`{"code":200,"message":"Request is processing…"}` with HTTP 400) means
     * the original request with this idempotency key is *still running* — an unknown
     * outcome. [UQPayApiClient] recognises it (it logs the case) but still throws a
     * plain `ApiError(400)`, so `isOutcomeUnknown` is **false** and the shared mapper
     * reports `invalid_request`. Downstream that reads as a definitive rejection of a
     * payment that may be in the middle of succeeding.
     *
     * This test pins the current behaviour so the fix is visible when it lands; see the
     * suite summary.
     */
    @Test
    fun `an in-flight idempotency reply is an unknown outcome, not a rejection`() =
        runTest {
            // Envelope B arrives as HTTP 400 and looks like a client error. It is not:
            // the original confirm is still running and may succeed. Reporting it as a
            // definitive rejection would tell the merchant a live payment had failed,
            // and inviting a fresh idempotency key would double-charge the customer.
            val network = RecordingNetworkClient(
                response(400, """{"code":200,"message":"Request is processing, please try again later."}"""),
            )

            val thrown = runCatching {
                client(network).confirmIntent("PI_1", confirmBody, idempotencyKey)
            }.exceptionOrNull() as UQPayApiException

            assertTrue(
                "expected IdempotencyInFlight, got ${thrown::class.simpleName}",
                thrown is UQPayApiException.IdempotencyInFlight,
            )
            assertEquals("200", thrown.apiError?.code)
            assertTrue("the outcome is unknown — the payment may still succeed", thrown.isOutcomeUnknown)
            assertTrue("the same key must be replayed, so this is retryable", thrown.isRetryable)
        }

    @Test
    fun `an in-flight idempotency reply reaches the merchant as unresolved, not declined`() =
        runTest {
            val network = RecordingNetworkClient(
                response(400, """{"code":200,"message":"Request is processing, please try again later."}"""),
            )

            val thrown = runCatching {
                client(network).confirmIntent("PI_1", confirmBody, idempotencyKey)
            }.exceptionOrNull() as UQPayApiException

            val error = ErrorMapper(com.uqpay.sdk.Environment.PRODUCTION).map(thrown)

            assertEquals(com.uqpay.sdk.error.UQPayErrorCode.TIMEOUT, error.code)
            assertFalse(
                "an unresolved payment must never be described as declined",
                error.message.contains("declined", ignoreCase = true),
            )
        }

    // ---------------------------------------------------------------- programmer errors

    @Test
    fun `confirm requires an idempotency key`() = runTest {
        val network = RecordingNetworkClient(response(200, intentJson))

        listOf("", "   ").forEach { key ->
            val thrown = runCatching {
                client(network).confirmIntent("PI_1", confirmBody, key)
            }.exceptionOrNull()

            assertTrue("key=<$key>, got $thrown", thrown is IllegalArgumentException)
        }
        assertTrue("no confirm may leave without a key", network.requests.isEmpty())
    }

    @Test
    fun `a blank payment intent id is a programmer error, not a network call`() = runTest {
        val network = RecordingNetworkClient(response(200, intentJson))

        listOf(
            runCatching { client(network).retrieveIntent("  ") }.exceptionOrNull(),
            runCatching { client(network).confirmIntent("", confirmBody, idempotencyKey) }
                .exceptionOrNull(),
        ).forEach { thrown -> assertTrue("got $thrown", thrown is IllegalArgumentException) }

        assertTrue(network.requests.isEmpty())
    }

    @Test
    fun `a token provider failure never reaches the network`() = runTest {
        val failing = object : UQPayTokenProvider {
            override fun fetchToken(): UQPayAuthToken = throw IllegalStateException("no backend")
        }
        val network = RecordingNetworkClient(response(200, intentJson))
        val apiClient = UQPayApiClient(
            configuration = UQPayConfiguration("client-test", Environment.SANDBOX, failing),
            networkClient = network,
            tokenManager = TokenManager(failing, UnconfinedTestDispatcher()),
        )

        val thrown = runCatching { apiClient.retrieveIntent("PI_1") }.exceptionOrNull()

        assertTrue(thrown is UQPayApiException.AuthenticationFailed)
        assertTrue(network.requests.isEmpty())
    }
}
