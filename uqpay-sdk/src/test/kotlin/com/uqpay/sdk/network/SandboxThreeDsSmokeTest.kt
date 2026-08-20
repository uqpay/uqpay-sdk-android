package com.uqpay.sdk.network

import com.uqpay.sdk.Environment
import com.uqpay.sdk.UQPayConfiguration
import com.uqpay.sdk.auth.UQPayAuthToken
import com.uqpay.sdk.engine.BrowserDetails
import com.uqpay.sdk.engine.BrowserInfo
import com.uqpay.sdk.engine.ConfirmBilling
import com.uqpay.sdk.engine.ConfirmPayload
import com.uqpay.sdk.engine.MobileInfo
import com.uqpay.sdk.engine.NextAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/**
 * Confirms a **real sandbox intent** with a documented 3-D Secure test card and asserts the
 * shape of the `next_action` that comes back. Skipped unless explicitly enabled.
 *
 * ### Why this test exists — audit finding M-2
 *
 * `NextAction.from` dispatches on `next_action.type` **alone**. Nothing in the API
 * documentation guarantees that field is present, and the audit could not confirm it. A
 * populated `redirect_to_url` under a missing or unexpected `type` would decode to
 * `NextAction.Unknown`, which the UI declines to render — leaving the customer on a spinner
 * while the engine polls for its full 300-second budget and then reports `PENDING` for a
 * payment that only needed a WebView. That is the failure this test is here to make
 * impossible to ship blind.
 *
 * Enable with:
 * ```
 * UQPAY_SMOKE=1 UQPAY_CLIENT_ID=… UQPAY_SANDBOX_TOKEN=… UQPAY_INTENT_ID=… \
 *   ./gradlew :uqpay-sdk:testDebugUnitTest --tests '*SandboxThreeDsSmokeTest'
 * ```
 *
 * The intent must be **freshly created and unpaid** (`scripts/create-sandbox-intent.sh`);
 * a confirm against a settled intent is refused, correctly, and proves nothing about
 * `next_action`. Sandbox only: no real money moves.
 *
 * ### On the card number below
 *
 * `5346930100108117` is a documented UQPAY sandbox test card (`api-contract.md` §9.1). It
 * identifies nobody. **No real PAN may ever appear in this file**, and nothing here prints a
 * token, a body, or any customer field — the assertions are structural only.
 *
 * The documented Visa 3DS cards in §9.2 (`41766600000000{68,92,118}`) were tried first and
 * every one returned `attempt_status: FAILED`, `failure_code: system_error` on this merchant
 * account — Visa acquiring appears not to be enabled for it. Recorded in the Slice 4 report;
 * the Mastercard path below is the one that reaches an issuer.
 */
class SandboxThreeDsSmokeTest {

    private val enabled = System.getenv("UQPAY_SMOKE") == "1"
    private val clientId = System.getenv("UQPAY_CLIENT_ID").orEmpty()
    private val token = System.getenv("UQPAY_SANDBOX_TOKEN").orEmpty()
    private val intentId = System.getenv("UQPAY_INTENT_ID").orEmpty()

    /** Documented sandbox Mastercard: `api-contract.md` §9.1, expiry 12/26, CVC 811. */
    private val testCard = "5346930100108117"

    private fun client(): UQPayApiClient {
        val configuration = UQPayConfiguration(
            clientId = clientId,
            environment = Environment.SANDBOX,
            tokenProvider = { UQPayAuthToken(value = token, expiresAtEpochMillis = Long.MAX_VALUE / 2) },
        )
        return UQPayApiClient(
            configuration = configuration,
            networkClient = DefaultUQPayNetworkClient(
                connectionFactory = DefaultConnectionFactory(),
                workContext = Dispatchers.IO,
                // One attempt. A confirm is not a call to retry blindly.
                maxRetries = 0,
            ),
            tokenManager = TokenManager(provider = configuration.tokenProvider, workContext = Dispatchers.IO),
        )
    }

    /**
     * M-2: confirm with `enforce_3ds` and assert that the returned `next_action` carries a
     * `type` this SDK recognises, and that [NextAction.from] decodes it to something
     * renderable rather than to [NextAction.Unknown].
     */
    @Test
    fun `M-2 - a live 3DS confirm returns a next_action with a recognised type`() {
        assumeTrue("set UQPAY_SMOKE=1 to run", enabled)
        assumeTrue("UQPAY_CLIENT_ID is required", clientId.isNotBlank())
        assumeTrue("UQPAY_SANDBOX_TOKEN is required", token.isNotBlank())
        assumeTrue("UQPAY_INTENT_ID must name a fresh, unpaid sandbox intent", intentId.isNotBlank())

        val payload = ConfirmPayload.Card(
            paymentIntentId = intentId,
            cardNumber = testCard,
            expiryMonth = "12",
            expiryYear = "2026",
            cvc = "811",
            cardholderName = "Test Cardholder",
            network = "mastercard",
            // Every optional emitted as "" with an address object always present — audit L-6,
            // the convention the card form uses and the one iOS is verified to send.
            billing = ConfirmBilling(
                firstName = "Test", lastName = "Cardholder", email = "test@example.invalid",
                phoneNumber = "", countryCode = "SG", state = "", city = "Singapore",
                street = "1 Test Street", postcode = "018956",
            ),
            threeDsAction = "enforce_3ds",
        )
        val body = payload.encodeBody(browserInfo(), "1.1.1.1")

        val intent = runBlocking {
            client().confirmIntent(intentId, body, UUID.randomUUID().toString().lowercase())
        }

        println("M-2 intent_status:   ${intent.intentStatus}")
        println("M-2 attempt_status:  ${intent.latestPaymentAttempt?.attemptStatus}")
        println("M-2 failure_code:    ${intent.latestPaymentAttempt?.failureCode}")
        println("M-2 next_action.type: ${intent.nextAction?.type}")

        assertEquals(
            "the intent must be waiting on the customer; a settled or failed one proves nothing here",
            "REQUIRES_CUSTOMER_ACTION",
            intent.intentStatus,
        )
        val dto = assertNotNull("a 3DS confirm must return a next_action", intent.nextAction)
            .let { intent.nextAction!! }
        assertTrue(
            "M-2: `type` was absent or blank on the wire — NextAction.from dispatches on it " +
                "alone and would decode this to Unknown, polling for 300 s instead of rendering",
            !dto.type.isNullOrBlank(),
        )

        val action = NextAction.from(dto)
        assertTrue(
            "a populated next_action must decode to something renderable, not Unknown (was $action)",
            action is NextAction.Iframe || action is NextAction.Redirect,
        )
        // The payload has to be there too: a recognised type with an empty body would render
        // a WebView pointed at nothing.
        when (action) {
            is NextAction.Iframe -> assertTrue("the iframe fragment must be non-empty", action.html.isNotBlank())
            is NextAction.Redirect -> assertTrue("the challenge URL must be non-empty", action.url.isNotBlank())
            else -> Unit
        }
    }

    /** A plausible fingerprint. `timezone` is a **whole-hour offset**; a zone id is rejected. */
    private fun browserInfo() = BrowserInfo(
        acceptHeader = "*/*",
        browser = BrowserDetails(
            javaEnabled = false,
            javascriptEnabled = true,
            userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            cookieEnabled = true,
            plugins = emptyList(),
            doNotTrack = false,
        ),
        deviceId = "smoke-test-device",
        language = "en-SG",
        mobile = MobileInfo(deviceModel = "Pixel 7", osType = "ANDROID", osVersion = "Android 14"),
        screenColorDepth = 24,
        screenHeight = 2400,
        screenWidth = 1080,
        timezone = "8",
        touchSupport = true,
        hardwareConcurrency = 8,
        deviceMemory = 8,
    )
}
