package com.uqpay.sdk.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads a gateway error body.
 *
 * **This must never throw.** It runs on the failure path, where a secondary crash would
 * replace a recoverable payment error with a dead app. Anything unparsable — HTML from a
 * proxy, an empty body, a truncated response — yields a usable [ApiErrorBody] instead.
 *
 * UQPAY sends two envelope shapes:
 *
 * - **A** `{"type": "payment_error", "code": "confirm_failed", "message": "…"}`
 * - **B** `{"code": 200, "message": "Request is processing, please try again later."}`,
 *   returned (with HTTP 400) when an idempotency key is replayed while the original
 *   request is still in flight.
 *
 * `code` is a string in A and a number in B, so the body is read as a generic object
 * rather than a typed model.
 */
internal object ApiErrorParser {

    /** Wire message for envelope B. */
    private const val PROCESSING_MESSAGE_HINT = "processing"

    fun parse(body: String?): ApiErrorBody = runCatching {
        if (body.isNullOrBlank()) return@runCatching ApiErrorBody()

        val root = UQPayJson.instance.parseToJsonElement(body) as? JsonObject
            ?: return@runCatching ApiErrorBody()

        ApiErrorBody(
            type = root["type"]?.asStringOrNull(),
            code = root["code"]?.asStringOrNull(),
            message = root["message"]?.asStringOrNull(),
        )
    }.getOrElse { ApiErrorBody() }

    /**
     * Whether this body is envelope B — the original request with this idempotency key
     * is still running.
     *
     * The outcome is unknown, not failed: the caller should back off and replay the
     * **same** key rather than minting a new one.
     */
    fun isIdempotencyInFlight(body: ApiErrorBody): Boolean =
        body.type == null &&
            body.code?.toIntOrNull() != null &&
            body.message?.contains(PROCESSING_MESSAGE_HINT, ignoreCase = true) == true

    /**
     * Reads a JSON value as a string regardless of its underlying type, so a numeric
     * `code` does not blow up a string field. Blank and JSON `null` become null.
     */
    private fun kotlinx.serialization.json.JsonElement.asStringOrNull(): String? {
        val primitive = this as? JsonPrimitive ?: runCatching { jsonPrimitive }.getOrNull()
        val raw = primitive?.content ?: return null
        return raw.takeIf { it.isNotBlank() && it != "null" }
    }
}
