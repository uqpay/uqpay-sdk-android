package com.uqpay.sdk.network

import com.uqpay.sdk.Environment
import com.uqpay.sdk.error.UQPayError
import com.uqpay.sdk.error.UQPayErrorCode

/**
 * The **only** place an internal failure becomes a public [UQPayError].
 *
 * Every payment method routes through this class. On iOS the card screen mapped decline
 * codes properly while the wallet screens hardcoded `unknown` for every non-cancelled
 * failure, so the same server response produced different codes depending on which
 * screen the customer had used. That is the bug this single chokepoint exists to make
 * impossible — acceptance criteria §6.2.
 *
 * @property environment gates how much gateway detail reaches [UQPayError.message].
 */
internal class ErrorMapper(private val environment: Environment) {

    /** Converts any internal failure into the merchant-facing error. */
    fun map(e: UQPayApiException): UQPayError {
        val code = codeFor(e)
        return UQPayError(
            code = code,
            message = messageFor(code, e.apiError?.message),
            declineCode = e.apiError?.code,
            traceId = e.traceId,
        )
    }

    /** Converts an unexpected throwable. Nothing escapes the type. */
    fun map(t: Throwable): UQPayError = when (t) {
        is UQPayApiException -> map(t)
        else -> UQPayError(
            code = UQPayErrorCode.UNKNOWN,
            message = messageFor(UQPayErrorCode.UNKNOWN, null),
        )
    }

    /**
     * Maps a settled-bad intent to a merchant code, per the attempt's `failure_code`.
     *
     * A cancelled intent is always [UQPayErrorCode.CANCELLED] regardless of the failure
     * code — the customer's action outranks whatever the last attempt reported.
     */
    fun mapSettledOutcome(
        intentStatus: IntentStatus,
        failureCode: String?,
        failureMessage: String? = null,
        traceId: String? = null,
    ): UQPayError {
        val normalisedCode = failureCode?.takeIf { it.isNotBlank() }
        val code = when {
            intentStatus is IntentStatus.Cancelled -> UQPayErrorCode.CANCELLED
            normalisedCode == null -> UQPayErrorCode.CARD_DECLINED
            else -> when (normalisedCode.lowercase()) {
                "3ds_failed" -> UQPayErrorCode.THREE_DS_FAILED
                "insufficient_funds" -> UQPayErrorCode.INSUFFICIENT_FUNDS
                else -> UQPayErrorCode.CARD_DECLINED
            }
        }
        return UQPayError(
            code = code,
            message = messageFor(code, failureMessage?.takeIf { it.isNotBlank() }),
            declineCode = normalisedCode,
            traceId = traceId,
        )
    }

    private fun codeFor(e: UQPayApiException): UQPayErrorCode = when (e) {
        is UQPayApiException.NotConfigured -> UQPayErrorCode.INVALID_CONFIGURATION
        is UQPayApiException.AuthenticationFailed -> UQPayErrorCode.AUTHENTICATION_FAILED
        is UQPayApiException.TransportFailure -> UQPayErrorCode.NETWORK_ERROR
        is UQPayApiException.TimedOut -> UQPayErrorCode.TIMEOUT
        is UQPayApiException.Cancelled -> UQPayErrorCode.CANCELLED
        // Both of these mean "we do not know whether this payment went through", and
        // both must be reported as unresolved rather than as a server failure. Mapping
        // them to SERVER_ERROR would attach "please try again" to a payment that may
        // already have been processed — an invitation to double-charge the customer.
        // TIMEOUT is the code the SDK carries on a PENDING result: wait for the webhook.
        is UQPayApiException.DecodingFailure -> UQPayErrorCode.TIMEOUT
        is UQPayApiException.IdempotencyInFlight -> UQPayErrorCode.TIMEOUT
        is UQPayApiException.UnexpectedStatus -> fromStatus(e.statusCode)
        is UQPayApiException.ApiError ->
            e.apiError?.code?.let(::fromApiCode) ?: fromStatus(e.statusCode)
    }

    /**
     * Gateway `code` → merchant code. Checked before the HTTP status, because the code
     * is far more specific.
     *
     * An unrecognised code falls through to the HTTP status rather than becoming
     * `card_declined`: historically, reporting every rejection as a decline told
     * merchants a card had been refused when the request was merely malformed or
     * unauthenticated.
     */
    private fun fromApiCode(raw: String): UQPayErrorCode? = when (raw.lowercase()) {
        "card_declined", "do_not_honor" -> UQPayErrorCode.CARD_DECLINED
        "insufficient_funds" -> UQPayErrorCode.INSUFFICIENT_FUNDS
        "invalid_payment_method" -> UQPayErrorCode.INVALID_PAYMENT_METHOD
        "3ds_failed", "3ds_required" -> UQPayErrorCode.THREE_DS_FAILED
        "unauthorized_error" -> UQPayErrorCode.AUTHENTICATION_FAILED
        "expired_order", "order_cancelled", "invalid_order_status", "repeat_payment_request" ->
            UQPayErrorCode.INTENT_NOT_PAYABLE
        "invalid_parameter", "missing_parameter", "invalid_order_amount",
        "invalid_order_currency", "invalid_description", "invalid_return_url",
        "not_found_id", "invalid_payment_orders",
        -> UQPayErrorCode.INVALID_REQUEST
        "api_error" -> UQPayErrorCode.SERVER_ERROR
        else -> null
    }

    /**
     * HTTP status → merchant code, used only when the gateway sent no recognisable code.
     *
     * Note 400/404/422 map to [UQPayErrorCode.INVALID_REQUEST], where iOS maps them to
     * `invalid_payment_method`. That is not a divergence in behaviour: iOS's published
     * code set has no `invalid_request` member, so a malformed request had nowhere
     * better to go. This SDK has the accurate bucket and uses it.
     */
    private fun fromStatus(status: Int): UQPayErrorCode = when (status) {
        401, 403 -> UQPayErrorCode.AUTHENTICATION_FAILED
        402 -> UQPayErrorCode.CARD_DECLINED
        400, 404, 422 -> UQPayErrorCode.INVALID_REQUEST
        429 -> UQPayErrorCode.SERVER_ERROR
        in 500..599 -> UQPayErrorCode.SERVER_ERROR
        else -> UQPayErrorCode.UNKNOWN
    }

    /**
     * Builds the merchant-facing message.
     *
     * In [Environment.PRODUCTION] only the fixed text for the code is used: gateway
     * messages are documented as unsafe to surface verbatim, and internals must not leak.
     * In [Environment.SANDBOX] the gateway's detail is appended so integrators can debug.
     *
     * The machine code never appears in the sentence — it lives in
     * [UQPayError.code] and [UQPayError.declineCode].
     */
    private fun messageFor(code: UQPayErrorCode, serverDetail: String?): String {
        val base = FIXED_MESSAGES[code.raw] ?: DEFAULT_MESSAGE
        val detail = serverDetail?.takeIf { it.isNotBlank() }
        return if (environment == Environment.SANDBOX && detail != null) "$base ($detail)" else base
    }

    private companion object {
        const val DEFAULT_MESSAGE = "The payment could not be completed."

        val FIXED_MESSAGES: Map<String, String> = mapOf(
            UQPayErrorCode.NOT_INITIALIZED.raw to
                "The UQPAY SDK was used before it was initialized.",
            UQPayErrorCode.INVALID_CONFIGURATION.raw to
                "The payment could not be started because the SDK is not configured correctly.",
            UQPayErrorCode.INVALID_REQUEST.raw to
                "The payment could not be started because the request was not valid.",
            UQPayErrorCode.INVALID_PAYMENT_METHOD.raw to
                "That payment method cannot be used for this payment.",
            UQPayErrorCode.NETWORK_ERROR.raw to
                "The payment could not be completed because of a connection problem.",
            UQPayErrorCode.TIMEOUT.raw to
                "We are still waiting for confirmation of this payment.",
            UQPayErrorCode.AUTHENTICATION_FAILED.raw to
                "The payment could not be authorised. Please try again.",
            UQPayErrorCode.CARD_DECLINED.raw to
                "The card was declined. Please try a different payment method.",
            UQPayErrorCode.INSUFFICIENT_FUNDS.raw to
                "The card was declined for insufficient funds.",
            UQPayErrorCode.THREE_DS_FAILED.raw to
                "The payment could not be verified with your bank.",
            UQPayErrorCode.CANCELLED.raw to
                "The payment was cancelled.",
            UQPayErrorCode.INTENT_NOT_PAYABLE.raw to
                "This payment has already been completed or cancelled.",
            UQPayErrorCode.SERVER_ERROR.raw to
                "UQPAY could not process the payment. Please try again.",
            UQPayErrorCode.UNKNOWN.raw to DEFAULT_MESSAGE,
        )
    }
}
