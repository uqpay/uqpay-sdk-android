package com.uqpay.sdk.network

import java.io.IOException

/**
 * Internal failure model. Never surfaced to merchants — [ErrorMapper] converts these
 * into a public [com.uqpay.sdk.error.UQPayError] at exactly one chokepoint.
 *
 * @property apiError parsed error body, when the gateway sent one.
 * @property traceId gateway request/trace id, for support tickets.
 * @property statusCode HTTP status, or 0 when no response arrived.
 */
internal sealed class UQPayApiException(
    val apiError: ApiErrorBody? = null,
    val traceId: String? = null,
    val statusCode: Int = 0,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The request never left the device: no environment, token, or intent id. */
    class NotConfigured(message: String) : UQPayApiException(message = message)

    /** A token could not be obtained from the host app's provider. */
    class AuthenticationFailed(
        message: String,
        cause: Throwable? = null,
        statusCode: Int = 0,
        traceId: String? = null,
    ) : UQPayApiException(
        traceId = traceId,
        statusCode = statusCode,
        message = message,
        cause = cause,
    )

    /** The gateway returned a structured error. */
    class ApiError(
        apiError: ApiErrorBody?,
        traceId: String?,
        statusCode: Int,
    ) : UQPayApiException(
        apiError = apiError,
        traceId = traceId,
        statusCode = statusCode,
        message = apiError?.message.orEmpty().ifBlank { "The gateway rejected the request." },
    )

    /**
     * The gateway is still processing an earlier request that used this idempotency key.
     *
     * Arrives as HTTP 400 with envelope B, which makes it look like a client error — it
     * is not. The original confirm is **still in flight and may succeed**, so this is an
     * unknown outcome: back off and replay the *same* key rather than reporting a
     * rejection or minting a new one.
     */
    class IdempotencyInFlight(
        apiError: ApiErrorBody?,
        traceId: String?,
        statusCode: Int,
    ) : UQPayApiException(
        apiError = apiError,
        traceId = traceId,
        statusCode = statusCode,
        message = "A payment with this idempotency key is still being processed.",
    )

    /** A non-2xx whose body could not be parsed. */
    class UnexpectedStatus(
        statusCode: Int,
        traceId: String?,
    ) : UQPayApiException(
        traceId = traceId,
        statusCode = statusCode,
        message = "The gateway returned an unexpected response (HTTP $statusCode).",
    )

    /**
     * A 2xx arrived but could not be read.
     *
     * **The payment was processed.** This is an unknown outcome, not a failure: the
     * request reached the gateway and was acted on, we simply could not read the answer.
     * It must never be reported as a decline, and any retry must replay the same
     * idempotency key with a byte-identical body.
     */
    class DecodingFailure(
        statusCode: Int,
        traceId: String?,
        cause: Throwable?,
    ) : UQPayApiException(
        traceId = traceId,
        statusCode = statusCode,
        message = "The gateway's response could not be read.",
        cause = cause,
    )

    /** No response arrived. */
    class TransportFailure(cause: IOException) : UQPayApiException(
        message = "The request could not reach UQPAY.",
        cause = cause,
    )

    /** The request exceeded its deadline. */
    class TimedOut(cause: Throwable? = null) : UQPayApiException(
        message = "The request to UQPAY timed out.",
        cause = cause,
    )

    /** The caller cancelled the request locally. Report nothing; leave any pin alone. */
    class Cancelled : UQPayApiException(message = "The request was cancelled.")

    /**
     * Whether the request may be safely retried.
     *
     * A retry **must reuse the same idempotency key**: a 429 or 5xx can mean "the
     * acquirer authorised while the edge gave up", which is precisely the double-charge
     * case.
     */
    val isRetryable: Boolean
        get() = when (this) {
            is TimedOut, is TransportFailure, is IdempotencyInFlight -> true
            is ApiError, is UnexpectedStatus -> statusCode == 429 || statusCode >= 500
            else -> false
        }

    /**
     * Whether the payment's outcome is genuinely unknown — the request may have been
     * processed.
     *
     * Drives idempotency: an unknown outcome keeps the pin so the next send replays the
     * same attempt. A definitive rejection (a structured 4xx) releases it, and a local
     * cancellation leaves it untouched.
     */
    val isOutcomeUnknown: Boolean
        get() = when (this) {
            is TransportFailure, is TimedOut, is DecodingFailure, is IdempotencyInFlight -> true
            is ApiError, is UnexpectedStatus -> isUnknownOutcomeStatus(statusCode)
            else -> false
        }

    internal companion object {

        /**
         * HTTP statuses that leave a *sent* request's outcome undetermined.
         *
         * 429 and 5xx are the familiar pair: the edge gave up or shed load, and the acquirer
         * may have authorised anyway.
         *
         * **3xx belongs here too, and that is not obvious.** Redirects are deliberately not
         * followed (`DefaultConnectionFactory.instanceFollowRedirects = false`), so one
         * surfaces as a non-2xx and would otherwise fall through to "definitive failure" —
         * releasing the idempotency pin and reporting `FAILED`. A redirect proves nothing
         * about what the origin did with the POST body: an edge that answers `302` may have
         * handed the confirm on to a backend that processed it. Treating it as an answer is
         * how a customer is told a payment failed and taps Pay again under a *fresh* key.
         */
        fun isUnknownOutcomeStatus(status: Int): Boolean =
            status in 300..399 || status == 429 || status >= 500
    }
}

/**
 * Parsed gateway error body: `{ "type": …, "code": …, "message": … }`.
 *
 * Every field is optional. The API commonly returns `""` rather than omitting a field,
 * so blanks are normalised to null here — a blank must never reach a customer or be
 * mistaken for a real code.
 */
internal class ApiErrorBody(
    type: String? = null,
    code: String? = null,
    message: String? = null,
) {
    val type: String? = type?.takeIf { it.isNotBlank() }
    val code: String? = code?.takeIf { it.isNotBlank() }
    val message: String? = message?.takeIf { it.isNotBlank() }

    override fun toString(): String = "ApiErrorBody(type=$type, code=$code, message=$message)"
}
