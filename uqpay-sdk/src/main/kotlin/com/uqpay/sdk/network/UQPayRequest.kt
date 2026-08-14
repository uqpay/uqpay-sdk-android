package com.uqpay.sdk.network

import com.uqpay.sdk.Environment

/** Base URL for the environment. Internal so it stays out of the public API surface. */
internal val Environment.baseUrl: String
    get() = when (this) {
        Environment.SANDBOX -> "https://api-sandbox.uqpaytech.com/api"
        Environment.PRODUCTION -> "https://api.uqpay.com/api"
    }

internal enum class HttpMethod { GET, POST }

/**
 * One HTTP call.
 *
 * @property idempotencyKey `x-idempotency-key`. Required by UQPAY on every mutating
 *   call. Its presence is also what makes a retry safe — see [isRetrySafe].
 * @property body pre-encoded JSON. Encoding happens at the call site so a replay can
 *   resend **byte-identical** content: the gateway rejects a reused key whose payload
 *   changed, and re-encoding a model is not guaranteed to produce identical bytes.
 */
internal class UQPayRequest(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val idempotencyKey: String? = null,
    val body: String? = null,
) {
    /**
     * Whether this request may be resent after an ambiguous failure.
     *
     * A GET is always safe. A mutating call is safe **only** with an idempotency key:
     * without one, a 5xx or a dropped connection can mean "the acquirer authorised while
     * the edge gave up", and resending would charge the customer twice.
     */
    val isRetrySafe: Boolean
        get() = method == HttpMethod.GET || idempotencyKey != null

    /** Never render the body — it can carry card data. */
    override fun toString(): String = "UQPayRequest(method=$method, url=${redactUrl(url)})"
}

/**
 * A response, read fully into memory. Payment payloads are small.
 *
 * @property traceId gateway request id, surfaced to merchants for support tickets.
 */
internal class UQPayResponse(
    val statusCode: Int,
    val body: String?,
    val traceId: String?,
    val retryAfterSeconds: Long?,
) {
    val isSuccessful: Boolean get() = statusCode in 200..299

    /** Never render the body — it can carry payment details. */
    override fun toString(): String =
        "UQPayResponse(statusCode=$statusCode, traceId=$traceId)"
}

/**
 * Strips anything credential-shaped from a URL before it can reach a log or an exception
 * message. Query strings are dropped wholesale rather than filtered by name: a parameter
 * we have not thought of is exactly the one that leaks.
 */
internal fun redactUrl(url: String): String = url.substringBefore('?')
