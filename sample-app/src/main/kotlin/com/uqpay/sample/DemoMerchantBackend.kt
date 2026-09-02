package com.uqpay.sample

import android.util.Log
import com.uqpay.sdk.Environment
import com.uqpay.sdk.auth.UQPayAuthToken
import com.uqpay.sdk.auth.UQPayTokenProvider
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

// ─────────────────────────────────────────────────────────────────────────────────────────
//  ⚠️  THIS FILE STANDS IN FOR YOUR SERVER. DO NOT COPY IT INTO A PRODUCTION APP.
//
//  It does two things that belong on a backend and nowhere else:
//
//  1. It exchanges an `x-api-key` for an access token. That key can issue refunds and
//     payouts. An app binary cannot keep a secret — `strings` on a downloaded APK is
//     enough to read it — so a key that ships in an app is a key that has been published.
//     It is here only so this demo runs standalone, and only in a DEBUG build:
//     `sample-app/build.gradle.kts` gives `release` an empty key deliberately.
//
//  2. It creates the payment intent, which is where the price is decided. Your backend
//     owns that call because an app that chooses its own amount can be edited by the
//     customer — a five-line patch turns a 20.01 order into a 0.01 one.
//
//  There is also a quieter reason, which bites in production rather than in review:
//  UQPAY allows exactly ONE active access token per merchant, and minting a new one
//  invalidates the previous one. If every device mints its own, each customer logs out
//  every other customer and your own backend. With one tester it looks fine.
//
//  To go to production: delete this file, serve `/uqpay/token` and `/orders/{id}/intent`
//  from your own server behind your own user authentication, and implement
//  `UQPayTokenProvider` as a call to your server.
// ─────────────────────────────────────────────────────────────────────────────────────────

/**
 * The demo's pretend backend: mints access tokens and creates payment intents.
 *
 * Every method here blocks and must be called off the main thread. The SDK already calls
 * [fetchToken] on a background thread; [createPaymentIntent] is called from the checkout
 * screen's own executor.
 *
 * Nothing in this class logs a key, a token, or a response body — only what happened.
 */
object DemoMerchantBackend : UQPayTokenProvider {

    /** The environment this demo talks to. Also what the SANDBOX badge on screen reads. */
    val environment: Environment = Environment.SANDBOX

    private const val TAG = "UQPaySample"

    /** Refresh this far before the gateway's stated expiry, as the iOS demo does. */
    private const val REFRESH_MARGIN_MILLIS = 2 * 60 * 1000L

    /** Assumed window for a hand-pasted token, whose real expiry we are not told. */
    private const val ASSUMED_TOKEN_LIFETIME_MILLIS = 30 * 60 * 1000L

    private const val CONNECT_TIMEOUT_MILLIS = 15_000
    private const val READ_TIMEOUT_MILLIS = 30_000

    /**
     * The intent's `return_url`: the URL the issuer's 3-D Secure page navigates to when its
     * part is over. The SDK treats reaching it as the *end of the browser step* — a signal
     * to re-read the intent, never an outcome — and consumes the navigation inside its own
     * WebView. It is never opened as a deep link into this app, so nothing is declared for
     * this scheme in `AndroidManifest.xml` and nothing needs to be. A custom scheme like
     * this one is the recommended shape: an `https` URL would be a page the SDK may have to
     * render inside the sheet when the issuer POSTs to it.
     */
    private const val RETURN_URL = "uqpaysample://payment"

    private val baseUrl: String
        get() = when (environment) {
            Environment.SANDBOX -> "https://api-sandbox.uqpaytech.com"
            Environment.PRODUCTION -> "https://api.uqpay.com"
        }

    private val clientId: String get() = BuildConfig.UQPAY_CLIENT_ID.trim()
    private val apiKey: String get() = BuildConfig.UQPAY_SANDBOX_API_KEY.trim()
    private val pastedToken: String get() = BuildConfig.UQPAY_SANDBOX_TOKEN.trim()

    /** The minted token and when it stops being usable. Memory only; never written down. */
    private var cachedToken: String? = null
    private var cachedTokenExpiresAt: Long = 0L

    /**
     * Set once the hand-pasted token has been handed out.
     *
     * A pasted token is preferred on the first ask, so a developer who has a token but no
     * API key can still run the demo. But the SDK only asks a second time when the first
     * answer stopped working — it expired, or the gateway rejected it — and re-handing the
     * same dead string would loop. From then on we mint, if we can.
     */
    private var pastedTokenSpent = false

    // ---- setup ---------------------------------------------------------------------------

    /**
     * What is missing before a payment can be attempted, phrased for the developer who
     * just cloned this repo — or null when everything needed is present.
     *
     * Checked before the Checkout button is drawn, so a missing credential reads as setup
     * instructions rather than as a network error thirty seconds later.
     */
    fun setupProblem(): String? = when {
        clientId.isEmpty() ->
            "uqpay.clientId is not set.\n\nAdd it to local.properties in the project root:\n" +
                "    uqpay.clientId=<your sandbox client id>\n\n" +
                "local.properties is gitignored, so nothing you put there reaches git."

        apiKey.isEmpty() && pastedToken.isEmpty() ->
            "No sandbox credential.\n\nEither add your sandbox API key to local.properties " +
                "so this demo can mint its own tokens:\n" +
                "    uqpay.sandboxApiKey=<your sandbox x-api-key>\n\n" +
                "…or paste a short-lived access token instead:\n" +
                "    ./scripts/mint-sandbox-token.sh\n" +
                "which writes uqpay.sandboxToken for you.\n\n" +
                "The API key is compiled into DEBUG builds only, and never into a release " +
                "APK. A real merchant keeps it on their own server."

        else -> null
    }

    // ---- UQPayTokenProvider ----------------------------------------------------------------

    /**
     * Hands the SDK a usable access token, minting one if needed.
     *
     * Called by the SDK on a background thread, on first use and again whenever the token
     * it holds is near expiry or has been rejected — so blocking here is correct, and
     * caching means one app run mints once rather than once per checkout.
     */
    override fun fetchToken(): UQPayAuthToken {
        if (pastedToken.isNotEmpty() && !pastedTokenSpent) {
            pastedTokenSpent = true
            Log.i(TAG, "Using the access token from local.properties.")
            return UQPayAuthToken(
                value = pastedToken,
                expiresAtEpochMillis = System.currentTimeMillis() + ASSUMED_TOKEN_LIFETIME_MILLIS,
            )
        }

        val cached = cachedToken
        if (cached != null && System.currentTimeMillis() < cachedTokenExpiresAt) {
            return UQPayAuthToken(cached, cachedTokenExpiresAt)
        }

        check(apiKey.isNotEmpty()) {
            if (pastedToken.isEmpty()) {
                "No sandbox credential. " + setupProblem().orEmpty()
            } else {
                "The access token in local.properties is no longer accepted, and there is " +
                    "no API key to mint a new one with. Either run " +
                    "./scripts/mint-sandbox-token.sh again, or add uqpay.sandboxApiKey to " +
                    "local.properties and run a DEBUG build — a release build never carries " +
                    "the key."
            }
        }

        val minted = mintToken()
        cachedToken = minted.value
        cachedTokenExpiresAt = minted.expiresAtEpochMillis
        Log.i(TAG, "Minted a fresh access token from the sandbox.")
        return minted
    }

    /**
     * `POST /api/v1/connect/token` — the API key traded for a ~30-minute access token.
     *
     * The key travels in a header on one HTTPS request and is never logged, never written
     * to disk, and never put in the returned object.
     */
    private fun mintToken(): UQPayAuthToken {
        val body = post(
            path = "/api/v1/connect/token",
            headers = mapOf("x-client-id" to clientId, "x-api-key" to apiKey),
            json = null,
            what = "access token",
        )

        val token = body.optString("auth_token").takeIf { it.isNotEmpty() }
            ?: throw IOException("The gateway returned no auth_token. Check the API key and client id.")

        // `expired_at` is Unix epoch SECONDS. Refresh early, so a token never expires
        // mid-payment — the customer would see an authentication failure at the worst
        // possible moment.
        val expiresAt = body.optDouble("expired_at", 0.0)
            .takeIf { it > 0.0 }
            ?.let { (it * 1000L).toLong() - REFRESH_MARGIN_MILLIS }
            ?: (System.currentTimeMillis() + ASSUMED_TOKEN_LIFETIME_MILLIS - REFRESH_MARGIN_MILLIS)

        return UQPayAuthToken(value = token, expiresAtEpochMillis = expiresAt)
    }

    // ---- payment intents --------------------------------------------------------------------

    /**
     * `POST /api/v2/payment_intents/create` — creates the payment the customer is about to
     * make, and returns its id.
     *
     * **On your backend, this is where the price comes from.** Here the amount is passed in
     * from the cart, which is the thing a real integration must not do; the comment at the
     * top of this file explains why.
     *
     * @param amount in major units. Sent as a decimal string — `"20.01"`, never cents and
     *   never a JSON number.
     * @return the intent id (`PI…`). It expires 30 minutes after creation.
     */
    fun createPaymentIntent(amount: BigDecimal, description: String): String = try {
        createPaymentIntentOnce(amount, description)
    } catch (unauthorized: DemoUnauthorizedException) {
        // The credential we had was stale — a pasted token past its half hour, or a minted
        // one invalidated because something else minted after us (UQPAY allows one active
        // token per merchant). Throw it away and try once more with a fresh one; a second
        // failure is a real configuration problem and is reported as one.
        Log.i(TAG, "The access token was rejected; minting a fresh one and retrying once.")
        invalidateToken()
        createPaymentIntentOnce(amount, description)
    }

    private fun createPaymentIntentOnce(amount: BigDecimal, description: String): String {
        val token = fetchToken().value
        val json = JSONObject()
            .put("amount", amount.setScale(2).toPlainString())
            .put("currency", Cart.currency)
            // Your own order reference. Lowercase: UQPAY rejects uppercase in id-shaped fields.
            .put("merchant_order_id", newIdempotencyKey())
            .put("description", description)
            .put("return_url", RETURN_URL)

        val body = post(
            path = "/api/v2/payment_intents/create",
            headers = mapOf(
                "x-auth-token" to "Bearer $token",
                "x-client-id" to clientId,
                // Required on every mutating call, and format-validated server side: a
                // non-UUID comes back as `invalid idempotency key format`.
                "x-idempotency-key" to newIdempotencyKey(),
            ),
            json = json,
            what = "payment intent",
        )

        return body.optString("id").takeIf { it.isNotEmpty() }
            ?: body.optString("payment_intent_id").takeIf { it.isNotEmpty() }
            ?: throw IOException("The gateway returned no payment intent id.")
    }

    // ---- plumbing -----------------------------------------------------------------------------

    /**
     * Forgets the current access token, so the next [fetchToken] mints a new one.
     *
     * Also spends the hand-pasted token: it was just rejected, and handing the same dead
     * string back would loop forever.
     */
    private fun invalidateToken() {
        cachedToken = null
        cachedTokenExpiresAt = 0L
        pastedTokenSpent = true
    }

    /** Lowercase, because UQPAY rejects uppercase UUIDs. */
    private fun newIdempotencyKey(): String = UUID.randomUUID().toString().lowercase()

    /**
     * One HTTPS POST, read as JSON.
     *
     * [HttpsURLConnection] rather than a client library, so this sample adds no dependency
     * to a merchant's build — the SDK itself makes the same choice for the same reason.
     * The type is `HttpsURLConnection`, not its HTTP superclass, so a URL that was not
     * `https` fails here rather than sending a credential in the clear.
     */
    private fun post(
        path: String,
        headers: Map<String, String>,
        json: JSONObject?,
        what: String,
    ): JSONObject {
        val connection = URL(baseUrl + path).openConnection() as HttpsURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/json")
            for ((name, value) in headers) connection.setRequestProperty(name, value)

            if (json != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(json.toString().toByteArray()) }
            }

            val status = connection.responseCode
            // The response body is read but never logged: a token response contains a
            // credential, and an intent response contains order data.
            val text = if (status in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }

            if (status !in 200..299) {
                Log.w(TAG, "Could not create the $what: HTTP $status")
                val described = describeFailure(what, status, text)
                if (status == HttpURLConnection.HTTP_UNAUTHORIZED ||
                    status == HttpURLConnection.HTTP_FORBIDDEN
                ) {
                    throw DemoUnauthorizedException(described)
                }
                throw IOException(described)
            }
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * A failure a developer can act on.
     *
     * Error bodies from UQPAY carry a message and no credential, so quoting one is safe —
     * and far more useful than "HTTP 401", which is the same string for an expired token,
     * a wrong client id and a key from the other environment.
     */
    private fun describeFailure(what: String, status: Int, body: String): String {
        val message = runCatching {
            val json = JSONObject(body)
            json.optString("message").takeIf { it.isNotEmpty() }
                ?: json.optString("code").takeIf { it.isNotEmpty() }
        }.getOrNull()

        val detail = if (message.isNullOrEmpty()) "" else ": $message"
        val hint = when (status) {
            HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN ->
                "\n\nCheck uqpay.clientId and uqpay.sandboxApiKey in local.properties, and " +
                    "that both belong to the SANDBOX environment."
            else -> ""
        }
        return "Could not create the $what (HTTP $status)$detail$hint"
    }
}

/**
 * The gateway rejected our credential. Separate from a plain [IOException] so intent
 * creation can retry exactly once with a fresh token rather than retrying everything.
 */
private class DemoUnauthorizedException(message: String) : IOException(message)
