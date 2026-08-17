package com.uqpay.sdk.network

import com.uqpay.sdk.BuildConfig
import com.uqpay.sdk.UQPayConfiguration

/**
 * Internal HTTP client for the UQPAY gateway. HTTPS only — no cleartext, ever.
 * Not part of the public API; may change without notice.
 *
 * Only two endpoints are reachable from the app. Creating intents, capturing, refunding,
 * and minting tokens all require the merchant's `x-api-key` and belong exclusively to
 * the merchant's backend.
 */
internal class UQPayApiClient(
    private val configuration: UQPayConfiguration,
    private val networkClient: UQPayNetworkClient,
    private val tokenManager: TokenManager,
    private val logger: UQPayLogger = UQPayLogger.Noop,
) {

    /** Reads the current state of an intent. The source of truth after every step. */
    suspend fun retrieveIntent(paymentIntentId: String): PaymentIntentDto {
        require(paymentIntentId.isNotBlank()) { "paymentIntentId must not be blank" }
        return call(
            UQPayRequest(
                method = HttpMethod.GET,
                url = "${configuration.environment.baseUrl}/v2/payment_intents/$paymentIntentId",
            ),
        )
    }

    /**
     * Submits a payment method against an intent.
     *
     * @param body pre-encoded JSON. Passed through untouched so a replay of
     *   [idempotencyKey] resends byte-identical content — the gateway rejects a reused
     *   key whose payload changed.
     */
    suspend fun confirmIntent(
        paymentIntentId: String,
        body: String,
        idempotencyKey: String,
    ): PaymentIntentDto {
        require(paymentIntentId.isNotBlank()) { "paymentIntentId must not be blank" }
        require(idempotencyKey.isNotBlank()) { "confirm requires an idempotency key" }
        return call(
            UQPayRequest(
                method = HttpMethod.POST,
                url = "${configuration.environment.baseUrl}/v2/payment_intents/$paymentIntentId/confirm",
                idempotencyKey = idempotencyKey,
                body = body,
            ),
        )
    }

    /**
     * Executes a request, refreshing the token once on a `401`.
     *
     * The retry is deliberately single and deliberately only for auth: UQPAY invalidates
     * the previous token whenever a new one is minted, so a `401` most often means
     * another device refreshed rather than that the merchant is misconfigured. Retrying
     * further would turn a configuration problem into a silent hang.
     */
    private suspend fun call(request: UQPayRequest): PaymentIntentDto {
        val first = send(request, forceTokenRefresh = false)
        val response = if (first.statusCode == 401) {
            logger.debug("Refreshing access token after HTTP 401")
            // forceTokenRefresh invalidates the cache itself — no separate call needed.
            send(request, forceTokenRefresh = true)
        } else {
            first
        }
        return parse(response)
    }

    private suspend fun send(request: UQPayRequest, forceTokenRefresh: Boolean): UQPayResponse {
        val token = tokenManager.token(forceRefresh = forceTokenRefresh)
        val authenticated = UQPayRequest(
            method = request.method,
            url = request.url,
            headers = request.headers + mapOf(
                "x-auth-token" to bearer(token),
                "x-client-id" to configuration.clientId,
                "User-Agent" to USER_AGENT,
            ),
            idempotencyKey = request.idempotencyKey,
            body = request.body,
        )
        return networkClient.execute(authenticated)
    }

    private fun parse(response: UQPayResponse): PaymentIntentDto {
        if (response.isSuccessful) {
            return runCatching {
                UQPayJson.instance.decodeFromString(
                    PaymentIntentDto.serializer(),
                    response.body.orEmpty(),
                )
            }.getOrElse { cause ->
                // A 2xx we could not read. The payment WAS processed — this must never
                // be reported as a decline, and any retry must replay the same key.
                throw UQPayApiException.DecodingFailure(
                    statusCode = response.statusCode,
                    traceId = response.traceId,
                    cause = cause,
                )
            }
        }

        val body = ApiErrorParser.parse(response.body)
        if (ApiErrorParser.isIdempotencyInFlight(body)) {
            // Arrives as HTTP 400 and looks like a client error, but the original confirm
            // is still running and may succeed. Reporting it as a definitive rejection
            // would tell the merchant a live payment had failed.
            logger.debug("Idempotent request still in flight (HTTP ${response.statusCode})")
            throw UQPayApiException.IdempotencyInFlight(body, response.traceId, response.statusCode)
        }

        throw if (body.code == null && body.message == null) {
            UQPayApiException.UnexpectedStatus(response.statusCode, response.traceId)
        } else {
            UQPayApiException.ApiError(body, response.traceId, response.statusCode)
        }
    }

    private companion object {
        val USER_AGENT: String = "UQPay-Android-SDK/${BuildConfig.UQPAY_SDK_VERSION}"

        const val BEARER_PREFIX = "Bearer "

        /**
         * `x-auth-token` carries a **`Bearer `-prefixed** token, despite not being the
         * `Authorization` header. Verified against the shipped iOS SDK, which applies the
         * prefix at all four of its call sites (`UqpayHTTPClient.swift:173`,
         * `ApiClient+Payments.swift:43`, `UqpayPaymentSheet.swift:690`). Sending the raw
         * token yields `401 unauthorized_error`, which reads exactly like an expired
         * credential and sent us looking in the wrong place.
         *
         * Tolerates a provider that already prefixes: the token comes from the merchant's
         * backend and we cannot dictate its shape. Double-prefixing would 401 with the
         * same misleading message.
         */
        fun bearer(token: String): String =
            if (token.startsWith(BEARER_PREFIX)) token else BEARER_PREFIX + token
    }
}
