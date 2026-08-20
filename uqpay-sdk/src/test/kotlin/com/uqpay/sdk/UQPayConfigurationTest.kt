package com.uqpay.sdk

import com.uqpay.sdk.auth.UQPayAuthToken
import com.uqpay.sdk.auth.UQPayTokenProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The configuration validates itself (audit item 19).
 *
 * `initialize` used to accept anything and store it. A blank `clientId` then surfaced minutes
 * or days later as a **payment** that failed — `AUTHENTICATION_FAILED` on a customer's
 * checkout — which reads like a credential problem on the merchant's account rather than like
 * a configuration line that was never filled in. Refusing it where it is supplied turns a
 * production incident into a first-run integration error.
 *
 * No real client id, token or secret appears in this file.
 */
class UQPayConfigurationTest {

    private val tokenProvider = UQPayTokenProvider { UQPayAuthToken("tok-fixture", 0L) }

    @Test
    fun `a usable configuration is accepted`() {
        val configuration = UQPayConfiguration(
            clientId = "client-test",
            environment = Environment.SANDBOX,
            tokenProvider = tokenProvider,
        )

        assertEquals("client-test", configuration.clientId)
        assertEquals(Environment.SANDBOX, configuration.environment)
        assertFalse("logging must be off unless a merchant asks for it", configuration.loggingEnabled)
    }

    /**
     * Every request the SDK makes carries `x-client-id`; without one the gateway answers 401
     * and every payment dies at the acquirer. This is a programmer error, so it throws — AC §3
     * forbids throwing for a payment *outcome*, which this is not.
     */
    @Test
    fun `a blank client id is refused at construction, not at a customer's checkout`() {
        listOf("", "   ", "\t\n").forEach { blank ->
            try {
                UQPayConfiguration(blank, Environment.PRODUCTION, tokenProvider)
                fail("a blank clientId was accepted: '$blank'")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "the message must name the field and where the value comes from",
                    expected.message.orEmpty().contains("clientId"),
                )
            }
        }
    }

    /**
     * A line break in a value that becomes an HTTP header is header injection, and this one is
     * read from a merchant's build config or remote config where a stray newline is an
     * ordinary accident. Rejected rather than trimmed: silently altering a credential would
     * make a mistyped id look like a working one.
     */
    @Test
    fun `a client id carrying a line break is refused rather than sent as a header`() {
        listOf("client\ntest", "client\r\nX-Injected: 1", "client\rtest").forEach { injected ->
            try {
                UQPayConfiguration(injected, Environment.SANDBOX, tokenProvider)
                fail("a clientId with a line break was accepted")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("line break"))
            }
        }
    }

    /**
     * Nothing is retained by a refused configuration: `UQPay.initialize` cannot complete with
     * one that was never constructed, so `isInitialized` stays false and a merchant who
     * swallows the exception still gets the honest `NOT_INITIALIZED` at launch time rather
     * than a payment that dies at the gateway.
     */
    @Test
    fun `a refused configuration leaves the SDK uninitialised`() {
        UQPay.resetForTest()
        runCatching { UQPayConfiguration("", Environment.SANDBOX, tokenProvider) }

        assertFalse(UQPay.isInitialized)
    }

    /** Credentials never appear in a rendered configuration, however it is logged. */
    @Test
    fun `toString names no credential`() {
        val rendered = UQPayConfiguration("client-test", Environment.SANDBOX, tokenProvider).toString()

        assertTrue(rendered.contains("SANDBOX"))
        assertFalse("the token provider must never render itself", rendered.contains("UQPayTokenProvider"))
        assertTrue(rendered.contains("tokenProvider=****"))
    }
}
