package com.uqpay.sdk.network

import com.uqpay.sdk.Environment
import com.uqpay.sdk.UQPayConfiguration
import com.uqpay.sdk.auth.UQPayAuthToken
import com.uqpay.sdk.engine.BrowserDetails
import com.uqpay.sdk.engine.BrowserInfo
import com.uqpay.sdk.engine.ConfirmPayload
import com.uqpay.sdk.engine.MobileInfo
import com.uqpay.sdk.engine.NextAction
import com.uqpay.sdk.ui.wallet.parseExpiresAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Confirms a **real sandbox intent** with a **real wallet** and checks the `next_action`
 * shape this slice renders. Skipped unless explicitly enabled.
 *
 * Wallet QR was the flow with the most unverified wire shape in the whole SDK: nothing in
 * the repository had ever seen a `display_qr_code` from this gateway, and the DTO modelled
 * only one of the two fields it actually sends. This test is what keeps that from drifting
 * back into an assumption.
 *
 * Enable with:
 * ```
 * UQPAY_SMOKE=1 UQPAY_CLIENT_ID=… UQPAY_SANDBOX_TOKEN=… UQPAY_INTENT_ID=… \
 *   UQPAY_WALLET=grabpay ./gradlew :uqpay-sdk:testDebugUnitTest --tests '*SandboxWalletSmokeTest'
 * ```
 *
 * The intent must be **fresh and unconfirmed** — this test confirms it, which creates a live
 * payment attempt. Sandbox only; no real money moves.
 *
 * Never prints a token or a credential. The QR payload *is* printed, deliberately: it carries
 * no card data and no customer field, and its shape is the whole point of the test.
 */
class SandboxWalletSmokeTest {

    private val enabled = System.getenv("UQPAY_SMOKE") == "1"
    private val clientId = System.getenv("UQPAY_CLIENT_ID").orEmpty()
    private val token = System.getenv("UQPAY_SANDBOX_TOKEN").orEmpty()
    private val intentId = System.getenv("UQPAY_INTENT_ID").orEmpty()
    private val wallet = System.getenv("UQPAY_WALLET").orEmpty().ifBlank { "grabpay" }
    private val out = System.getenv("UQPAY_SMOKE_OUT").orEmpty()

    private fun report(line: String) {
        println(line)
        if (out.isNotBlank()) File(out).appendText(line + "\n")
    }

    /**
     * A wallet confirm returns a renderable QR.
     *
     * What each assertion protects:
     * - `type` is **present**, so dispatching on it is safe (audit M-2 asked for this).
     * - `display_qr_code` carries `qr_code_url`, so [com.uqpay.sdk.ui.wallet.QrImageLoader]
     *   has something to fetch. If this ever fails while `qr_code` is present, the gateway
     *   has moved to payload-only QRs and this SDK needs a QR *encoder*, which it does not
     *   have — that is a slice of work, not a patch.
     * - `expires_at` parses with the SDK's own parser, so the countdown is real.
     */
    @Test
    fun walletConfirmReturnsARenderableQr() {
        assumeTrue("UQPAY_SMOKE not set", enabled)
        assumeTrue("credentials absent", clientId.isNotBlank() && token.isNotBlank())
        assumeTrue("no UQPAY_INTENT_ID supplied", intentId.isNotBlank())

        report("--- wallet confirm: $wallet ---")

        val payload = ConfirmPayload.Wallet(intentId, wallet, walletDetails(wallet))
        val body = payload.encodeBody(browserInfo(), IP_ADDRESS)
        val intent = runBlocking {
            client().confirmIntent(intentId, body, UUID.randomUUID().toString())
        }

        report("intent_status:   ${intent.intentStatus}")
        report("attempt_status:  ${intent.latestPaymentAttempt?.attemptStatus}")
        report("method_type:     ${intent.latestPaymentAttempt?.paymentMethod?.type}")
        report("next_action.type:${intent.nextAction?.type}")

        assertEquals(
            "the gateway did not ask for a QR: ${intent.latestPaymentAttempt?.failureCode}",
            "REQUIRES_CUSTOMER_ACTION",
            intent.intentStatus,
        )

        val action = intent.nextAction
        assertNotNull("no next_action at all", action)
        assertEquals("dispatching on `type` requires it to be present", "display_qr_code", action?.type)

        val qr = action?.displayQrCode
        assertNotNull("display_qr_code missing from a QR action", qr)
        report("qr_code_url present: ${!qr?.qrCodeUrl.isNullOrBlank()}")
        report("expires_at:          ${qr?.expiresAt}")
        assertFalse(
            "no qr_code_url: this SDK has no QR encoder and cannot render a payload-only QR",
            qr?.qrCodeUrl.isNullOrBlank(),
        )
        assertTrue("qr_code_url must be HTTPS", qr!!.qrCodeUrl!!.startsWith("https://"))

        // The parser the countdown uses, on the value the gateway actually sent.
        qr.expiresAt?.let { raw ->
            val parsed = parseExpiresAt(raw)
            assertNotNull("the SDK cannot parse the gateway's expires_at: $raw", parsed)
            report("expires_at parses to: $parsed")
        }

        // And the decode the UI branches on.
        val decoded = NextAction.from(action)
        assertTrue("a live QR must decode to NextAction.Qr, not Unknown", decoded is NextAction.Qr)
        report("decoded: ${decoded!!::class.simpleName}")
    }

    /**
     * The one-confirm rule, from the gateway's side.
     *
     * Run this only when you are willing to create **two** live attempts on one intent: it
     * proves the gateway accepts a duplicate wallet confirm under a fresh idempotency key and
     * issues a *different* QR, which is why `WalletConfirmLatch` exists at all. Gated behind
     * its own flag because it deliberately does the thing the SDK must never do.
     */
    @Test
    fun aSecondConfirmUnderAFreshKeyIsAcceptedAndIssuesADifferentQr() {
        assumeTrue("UQPAY_SMOKE not set", enabled)
        assumeTrue("UQPAY_SMOKE_DOUBLE_CONFIRM not set", System.getenv("UQPAY_SMOKE_DOUBLE_CONFIRM") == "1")
        assumeTrue("credentials absent", clientId.isNotBlank() && token.isNotBlank())
        assumeTrue("no UQPAY_INTENT_ID supplied", intentId.isNotBlank())

        val payload = ConfirmPayload.Wallet(intentId, wallet, walletDetails(wallet))
        val body = payload.encodeBody(browserInfo(), IP_ADDRESS)

        val first = runBlocking { client().confirmIntent(intentId, body, UUID.randomUUID().toString()) }
        val second = runBlocking { client().confirmIntent(intentId, body, UUID.randomUUID().toString()) }

        report("--- duplicate wallet confirm ---")
        report("first  qr present: ${first.nextAction?.displayQrCode?.qrCodeUrl != null}")
        report("second qr present: ${second.nextAction?.displayQrCode?.qrCodeUrl != null}")
        report("QRs differ:        ${first.nextAction?.displayQrCode?.qrCodeUrl != second.nextAction?.displayQrCode?.qrCodeUrl}")

        assertEquals("REQUIRES_CUSTOMER_ACTION", second.intentStatus)
        assertTrue(
            "if this ever fails the gateway has started refusing duplicates — good news, " +
                "but the latch stays until it is documented behaviour",
            first.nextAction?.displayQrCode?.qrCodeUrl != second.nextAction?.displayQrCode?.qrCodeUrl,
        )
    }

    // ---- fixtures ------------------------------------------------------------------------

    /**
     * The method-details object for a wallet, as iOS builds it. Every value is a **constant
     * of the flow** — which is what makes the wallet digest `[intentId, methodType]` safe.
     * Nothing here is customer-editable, and nothing customer-editable may be added without
     * joining `ConfirmPayload.Wallet.methodIdentityFields()`.
     */
    private fun walletDetails(methodType: String): JsonObject = buildJsonObject {
        put("flow", "qrcode")
        put("is_present", false)
        if (methodType.startsWith("alipay")) put("os_type", "android")
    }

    /**
     * Device values shaped like the real ones. `timezone` is the **UTC offset as a numeric
     * string**, not an IANA zone id: the gateway parses it with `strconv.Atoi` and rejects
     * `"Asia/Singapore"` with `invalid_payment_method`. Confirmed live on 2026-08-18, and it
     * is what `DeviceInfo.timezoneOffsetHours()` already produces.
     */
    private fun browserInfo() = BrowserInfo(
        acceptHeader = "application/json",
        browser = BrowserDetails(
            javaEnabled = false,
            javascriptEnabled = true,
            userAgent = "UQPaySDK-Android/smoke",
            cookieEnabled = true,
            plugins = emptyList(),
            doNotTrack = false,
        ),
        deviceId = "smoke-device",
        language = "en-SG",
        mobile = MobileInfo(deviceModel = "smoke", osType = "android", osVersion = "14"),
        screenColorDepth = 24,
        screenHeight = 2400,
        screenWidth = 1080,
        timezone = "8",
        touchSupport = true,
        hardwareConcurrency = 8,
        deviceMemory = 8,
    )

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
                maxRetries = 0,
            ),
            tokenManager = TokenManager(provider = configuration.tokenProvider, workContext = Dispatchers.IO),
        )
    }

    private companion object {
        /**
         * A public resolver address. The gateway produces no QR without an `ip_address`, and
         * a unit-test JVM has no meaningful one; the real value comes from `DeviceInfo`.
         */
        const val IP_ADDRESS = "8.8.8.8"
    }
}
