package com.uqpay.sdk.network

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.uqpay.sdk.testErrorCopy
import com.uqpay.sdk.Environment
import com.uqpay.sdk.UQPayConfiguration
import com.uqpay.sdk.auth.UQPayAuthToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.uqpay.sdk.error.UQPayErrorCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Talks to the **real UQPAY sandbox**. Skipped unless explicitly enabled.
 *
 * Every other test in this module runs against fakes, which means the gateway contract
 * itself — base URL, auth header name, error envelope shape, status vocabulary — is
 * currently an assumption that our own fakes were built to satisfy. This test is the only
 * thing that can falsify it. See `docs/spec/api-contract.md`, which marks ten items
 * UNVERIFIED, and `docs/spec/ac-audit-slice-0-1.md` finding F6.
 *
 * Enable with:
 * ```
 * UQPAY_SMOKE=1 UQPAY_CLIENT_ID=… UQPAY_SANDBOX_TOKEN=… ./gradlew :uqpay-sdk:testDebugUnitTest --tests '*SandboxSmokeTest'
 * ```
 *
 * Never asserts on, prints, or records a token, a response body, or any customer field.
 * Findings are written as structural facts only — status codes, enum values, and whether
 * a field was present.
 */
@RunWith(RobolectricTestRunner::class)
class SandboxSmokeTest {

    private val enabled = System.getenv("UQPAY_SMOKE") == "1"
    private val clientId = System.getenv("UQPAY_CLIENT_ID").orEmpty()
    private val token = System.getenv("UQPAY_SANDBOX_TOKEN").orEmpty()
    private val realIntentId = System.getenv("UQPAY_INTENT_ID").orEmpty()
    private val out = System.getenv("UQPAY_SMOKE_OUT").orEmpty()

    private fun report(line: String) {
        println(line)
        if (out.isNotBlank()) File(out).appendText(line + "\n")
    }

    private fun client(): UQPayApiClient {
        val configuration = UQPayConfiguration(
            clientId = clientId,
            environment = Environment.SANDBOX,
            tokenProvider = {
                // Expiry is unknown here; the backend owns it. Far enough out that
                // TokenManager treats it as usable for the duration of this test.
                UQPayAuthToken(value = token, expiresAtEpochMillis = Long.MAX_VALUE / 2)
            },
        )
        return UQPayApiClient(
            configuration = configuration,
            networkClient = DefaultUQPayNetworkClient(
                connectionFactory = DefaultConnectionFactory(),
                workContext = Dispatchers.IO,
                // One attempt. A smoke test should report what happened, not paper over
                // it with 2s/4s/8s of retries.
                maxRetries = 0,
            ),
            tokenManager = TokenManager(
                provider = configuration.tokenProvider,
                workContext = Dispatchers.IO,
            ),
        )
    }

    /**
     * The load-bearing check. A deliberately absent intent id needs no fixture, yet
     * exercises the whole chain: DNS and base URL, TLS, the `x-auth-token` / `x-client-id`
     * header names, the error envelope, and [ErrorMapper].
     *
     * What the outcome means:
     * - `AUTHENTICATION_FAILED` → the auth model or a header name is wrong.
     * - `INVALID_REQUEST` / `INTENT_NOT_PAYABLE` → auth works and the envelope parses.
     * - `NETWORK_ERROR` → the base URL is wrong or the host is unreachable.
     */
    @Test
    fun unknownIntentIsRejectedWithoutAuthFailure() {
        assumeTrue("UQPAY_SMOKE not set", enabled)
        assumeTrue("credentials absent", clientId.isNotBlank() && token.isNotBlank())

        val mapper = ErrorMapper(Environment.SANDBOX, testErrorCopy())
        report("--- retrieveIntent with an unknown id ---")
        report("base URL: ${Environment.SANDBOX.baseUrl}")

        val outcome = runCatching {
            runBlocking { client().retrieveIntent("PI_smoke_test_does_not_exist") }
        }

        outcome.onSuccess {
            report("UNEXPECTED: the gateway returned 200 for an unknown intent id.")
            fail("the gateway returned 200 for an intent id that does not exist")
        }.onFailure { t ->
            val api = t as? UQPayApiException
            val mapped = mapper.map(t)
            report("exception:        ${t::class.simpleName}")
            report("http status:      ${api?.statusCode ?: "none"}")
            report("gateway code:     ${api?.apiError?.code ?: "none"}")
            report("gateway type:     ${api?.apiError?.type ?: "none"}")
            report("trace id present: ${!api?.traceId.isNullOrBlank()}")
            report("mapped code:      ${mapped.code}")
            report("outcome unknown:  ${api?.isOutcomeUnknown}")
            report("retryable:        ${api?.isRetryable}")

            // The report is how the live result is read; these are what it must mean.
            assertNotEquals("auth model or a header name is wrong", UQPayErrorCode.AUTHENTICATION_FAILED, mapped.code)
            assertNotEquals("base URL wrong or host unreachable", UQPayErrorCode.NETWORK_ERROR, mapped.code)
            assertTrue(
                "expected INVALID_REQUEST or INTENT_NOT_PAYABLE for an unknown intent, got ${mapped.code}",
                mapped.code == UQPayErrorCode.INVALID_REQUEST || mapped.code == UQPayErrorCode.INTENT_NOT_PAYABLE,
            )
        }
    }

    /**
     * Decodes a real intent. Runs only when `UQPAY_INTENT_ID` names one your backend
     * created — the SDK cannot create intents, only read and confirm them.
     *
     * Reports field *presence* and parsed enum values, never customer data.
     */
    @Test
    fun realIntentDecodes() {
        assumeTrue("UQPAY_SMOKE not set", enabled)
        assumeTrue("no UQPAY_INTENT_ID supplied", realIntentId.isNotBlank())

        report("--- retrieveIntent with a real id ---")

        val outcome = runCatching {
            runBlocking { client().retrieveIntent(realIntentId) }
        }

        outcome.onFailure { t ->
            val api = t as? UQPayApiException
            report("FAILED: ${t::class.simpleName} http=${api?.statusCode} code=${api?.apiError?.code}")
            fail("retrieveIntent threw ${t::class.simpleName} (http=${api?.statusCode}, code=${api?.apiError?.code})")
        }.onSuccess { dto ->
            report("decoded:              true")
            report("id present:           ${!dto.paymentIntentId.isNullOrBlank()}")
            report("raw intent_status:    ${dto.intentStatus}")
            report("parsed status:        ${IntentStatus.from(dto.intentStatus)}")
            report("status recognised:    ${IntentStatus.from(dto.intentStatus) !is IntentStatus.Unknown}")
            report("amount present:       ${!dto.amount.isNullOrBlank()}")
            report("currency:             ${dto.currency}")
            report("available methods:    ${dto.availablePaymentMethodTypes}")
            report("latest attempt:       ${dto.latestPaymentAttempt != null}")
            dto.latestPaymentAttempt?.let { a ->
                report("  raw attempt_status: ${a.attemptStatus}")
                report("  parsed:             ${AttemptStatus.from(a.attemptStatus)}")
                report("  recognised:         ${AttemptStatus.from(a.attemptStatus) !is AttemptStatus.Unknown}")
                report("  failure_code blank: ${a.failureCode?.isBlank()}")
            }
            report("next_action type:     ${dto.nextAction?.type}")

            assertFalse("payment_intent_id must decode non-blank", dto.paymentIntentId.isNullOrBlank())
            assertFalse(
                "intent_status must be one this SDK recognises",
                IntentStatus.from(dto.intentStatus) is IntentStatus.Unknown,
            )
        }
    }
}
