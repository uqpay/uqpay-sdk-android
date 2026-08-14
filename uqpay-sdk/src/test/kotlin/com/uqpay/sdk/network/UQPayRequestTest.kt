package com.uqpay.sdk.network

import com.uqpay.sdk.Environment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Request-level safety: what may be resent, and what may reach a log (acceptance §4.1).
 */
class UQPayRequestTest {

    private val url = "https://api-sandbox.uqpaytech.com/api/v2/payment_intents/PI_1"

    @Test
    fun `redactUrl strips the query string wholesale`() {
        // Dropped wholesale rather than filtered by name: the parameter we have not
        // thought of is exactly the one that leaks.
        assertEquals(
            "https://api.uqpay.com/api/v2/payment_intents/PI_1",
            redactUrl("https://api.uqpay.com/api/v2/payment_intents/PI_1?token=abc123&pan=4176660000000027"),
        )
    }

    @Test
    fun `redactUrl leaves a url with no query string alone`() {
        assertEquals(url, redactUrl(url))
    }

    @Test
    fun `redactUrl strips a bare trailing question mark`() {
        assertEquals(url, redactUrl("$url?"))
    }

    @Test
    fun `redactUrl handles an empty string`() {
        assertEquals("", redactUrl(""))
    }

    @Test
    fun `a get is always safe to resend`() {
        assertTrue(UQPayRequest(HttpMethod.GET, url).isRetrySafe)
    }

    @Test
    fun `a post is safe to resend only with an idempotency key`() {
        assertFalse(UQPayRequest(HttpMethod.POST, url, body = "{}").isRetrySafe)
        assertTrue(
            UQPayRequest(HttpMethod.POST, url, idempotencyKey = "key-1", body = "{}").isRetrySafe,
        )
    }

    @Test
    fun `a request never renders its body`() {
        val rendered = UQPayRequest(
            method = HttpMethod.POST,
            url = "$url/confirm?secret=abc",
            idempotencyKey = "key-1",
            body = """{"card":{"number":"4176660000000027","cvc":"303"}}""",
        ).toString()

        assertFalse(rendered.contains("4176660000000027"))
        assertFalse(rendered.contains("303"))
        assertFalse(rendered.contains("secret"))
        assertTrue(rendered.contains("$url/confirm"))
    }

    @Test
    fun `a response never renders its body`() {
        val rendered = UQPayResponse(
            statusCode = 200,
            body = """{"id":"PI_1","payment_method":{"card":{"last4":"0027"}}}""",
            traceId = "trace-1",
            retryAfterSeconds = null,
        ).toString()

        assertFalse(rendered.contains("payment_method"))
        assertTrue(rendered.contains("trace-1"))
    }

    @Test
    fun `only 2xx counts as successful`() {
        listOf(200, 201, 204, 299).forEach {
            assertTrue("HTTP $it", response(it).isSuccessful)
        }
        listOf(100, 199, 300, 302, 400, 500).forEach {
            assertFalse("HTTP $it", response(it).isSuccessful)
        }
    }

    @Test
    fun `both environments are https and distinct`() {
        // Acceptance §4.2 — cleartext is not a configuration option.
        assertTrue(Environment.SANDBOX.baseUrl.startsWith("https://"))
        assertTrue(Environment.PRODUCTION.baseUrl.startsWith("https://"))
        assertFalse(Environment.SANDBOX.baseUrl == Environment.PRODUCTION.baseUrl)
    }

    private fun response(status: Int) = UQPayResponse(status, null, null, null)
}
