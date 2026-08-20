package com.uqpay.sdk.network

import com.uqpay.sdk.Environment
import com.uqpay.sdk.error.ErrorCopy
import com.uqpay.sdk.error.UQPayError
import com.uqpay.sdk.error.UQPayErrorCode
import com.uqpay.sdk.payment.PaymentMethodType

/**
 * The **only** place an internal failure becomes a public [UQPayError].
 *
 * Every payment method routes through this class. On iOS the card screen mapped decline
 * codes properly while the wallet screens hardcoded `unknown` for every non-cancelled
 * failure, so the same server response produced different codes depending on which
 * screen the customer had used. That is the bug this single chokepoint exists to make
 * impossible — acceptance criteria §6.2.
 *
 * ### Two audiences, two sentences
 *
 * [UQPayError.message] is the customer's, and comes from [ErrorCopy] — that is, from
 * `strings.xml`, so it can be translated and is identical in both environments.
 * [UQPayError.developerMessage] is the integrator's, is written here in English, and is the
 * only one of the two that ever carries the gateway's own text — and only in
 * [Environment.SANDBOX], because gateway messages are documented as unsafe to surface and a
 * merchant's crash reporter is a surface.
 *
 * @property environment gates how much gateway detail reaches [UQPayError.developerMessage].
 * @property copy the customer-facing sentences, from resources.
 */
internal class ErrorMapper(
    private val environment: Environment,
    private val copy: ErrorCopy,
) {

    /** Converts any internal failure into the merchant-facing error. */
    fun map(e: UQPayApiException): UQPayError {
        val code = codeFor(e)
        return UQPayError(
            code = code,
            message = copy.forCode(code),
            declineCode = e.apiError?.code,
            traceId = e.traceId,
            developerMessage = developerMessageFor(e),
        )
    }

    /**
     * Converts an unexpected throwable. Nothing escapes the type.
     *
     * The throwable's own `message` is never used, in either environment or in either of the
     * two output sentences. A `RuntimeException` raised deep in the stack can quote whatever
     * it was handling — including a request body with a card number in it — and there is no
     * way to tell from here which ones can. Its **class name** is safe and is what an
     * integrator actually needs in order to find the line.
     */
    fun map(t: Throwable): UQPayError = when (t) {
        is UQPayApiException -> map(t)
        else -> UQPayError(
            code = UQPayErrorCode.UNKNOWN,
            message = copy.forCode(UQPayErrorCode.UNKNOWN),
            developerMessage = "Unexpected ${t.javaClass.simpleName} inside the SDK. " +
                "Its message is deliberately not reported: it can quote data the SDK is " +
                "not allowed to surface. Enable UQPayConfiguration.loggingEnabled to see " +
                "the SDK's own trace.",
        )
    }

    /**
     * Builds a [UQPayError] for a code the SDK decided on its own, with no underlying
     * failure to map — an unpayable intent, a presentation that contradicts the payment
     * method allow-list, an uninitialised SDK.
     *
     * It exists so those call sites cannot quietly reintroduce a hardcoded customer
     * sentence: the customer half always comes from [ErrorCopy], and the caller supplies
     * only the developer half.
     */
    fun forCode(code: UQPayErrorCode, developerMessage: String? = null): UQPayError =
        UQPayError(
            code = code,
            message = copy.forCode(code),
            developerMessage = developerMessage,
        )

    /**
     * Maps a settled-bad intent to a merchant code, per the attempt's `failure_code`.
     *
     * A cancelled intent is always [UQPayErrorCode.CANCELLED] regardless of the failure
     * code — the customer's action outranks whatever the last attempt reported.
     *
     * @param methodType the method the failed attempt used, when the intent named one. It
     *   decides the *fallback* only — see [declineFallback] — never a code the gateway was
     *   explicit about.
     */
    fun mapSettledOutcome(
        intentStatus: IntentStatus,
        failureCode: String?,
        failureMessage: String? = null,
        traceId: String? = null,
        methodType: PaymentMethodType? = null,
    ): UQPayError {
        val normalisedCode = failureCode?.takeIf { it.isNotBlank() }
        val code = when {
            intentStatus is IntentStatus.Cancelled -> UQPayErrorCode.CANCELLED
            normalisedCode == null -> declineFallback(methodType)
            else -> when (normalisedCode.lowercase()) {
                "3ds_failed" -> UQPayErrorCode.THREE_DS_FAILED
                "insufficient_funds" -> UQPayErrorCode.INSUFFICIENT_FUNDS
                else -> declineFallback(methodType)
            }
        }
        return UQPayError(
            code = code,
            message = copy.forCode(code),
            declineCode = normalisedCode,
            traceId = traceId,
            developerMessage = buildString {
                append("The intent settled as ")
                append(intentStatus::class.simpleName)
                append(" with failure_code=")
                append(normalisedCode ?: "(none)")
                if (methodType != null) append(", payment_method_type=${methodType.raw}")
                append('.')
                appendGatewayDetail(failureMessage)
            },
        )
    }

    /**
     * The code for a failed attempt the gateway did not explain: no `failure_code`, or one
     * this SDK version does not recognise.
     *
     * **[UQPayErrorCode.CARD_DECLINED] is a claim about a card**, and its fixed copy says so
     * out loud: "The card was declined. Please try a different payment method." Attaching it
     * to a wallet attempt tells a customer who never entered a card that their card was
     * refused, points them at a fix that does not exist, and files the failure under card
     * declines in the merchant's own analytics. A GrabPay or PayNow QR that simply expired
     * unscanned is the common way to reach here, and it is not a decline of anything.
     *
     * So the claim is only made when the attempt is known to be a card. A known wallet, and
     * an attempt whose method the intent did not name, both get [UQPayErrorCode.UNKNOWN] —
     * whose copy ("The payment could not be completed.") is the honest description of a
     * failure the gateway declined to characterise.
     *
     * This is a *fallback* and nothing else. A wallet attempt that comes back with
     * `insufficient_funds` or `3ds_failed` still maps to those, because the gateway said so.
     */
    private fun declineFallback(methodType: PaymentMethodType?): UQPayErrorCode =
        if (methodType == PaymentMethodType.CARD) UQPayErrorCode.CARD_DECLINED else UQPayErrorCode.UNKNOWN

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
     * The integrator's sentence: what the SDK was doing, what came back, and — where the
     * answer is actionable — where to look.
     *
     * Names the failure kind rather than the exception's own message, for the reason given
     * on [map]. The two failures a merchant most often reaches while integrating,
     * [UQPayApiException.NotConfigured] and [UQPayApiException.AuthenticationFailed], say
     * outright which part of *their* setup is at fault, because "authentication failed" on
     * its own has sent more than one integrator hunting through card handling for a problem
     * that was in their token endpoint.
     */
    private fun developerMessageFor(e: UQPayApiException): String = buildString {
        when (e) {
            is UQPayApiException.NotConfigured ->
                append(
                    "The SDK was asked to make a request before UQPay.initialize(context, " +
                        "configuration) had been called with a usable configuration.",
                )

            is UQPayApiException.AuthenticationFailed ->
                append(
                    "UQPAY rejected the access token (HTTP ${e.statusCode}). This is your " +
                        "backend's token, not the customer's card: check that " +
                        "UQPayTokenProvider returns a live token for the same clientId and " +
                        "Environment, and remember that minting a new token invalidates the " +
                        "previous one.",
                )

            is UQPayApiException.TransportFailure ->
                append("The request never reached UQPAY: the connection failed or was lost.")

            is UQPayApiException.TimedOut ->
                append(
                    "The request timed out. Whether the payment was processed is unknown, " +
                        "which is why this reports as pending rather than as a failure.",
                )

            is UQPayApiException.Cancelled ->
                append("The request was cancelled before it completed.")

            is UQPayApiException.DecodingFailure ->
                append(
                    "UQPAY answered with a body this SDK version could not decode. The " +
                        "payment's outcome is therefore unknown; wait for the webhook.",
                )

            is UQPayApiException.IdempotencyInFlight ->
                append(
                    "A confirm with this idempotency key is still being processed by UQPAY. " +
                        "The outcome is unknown until it resolves; wait for the webhook " +
                        "rather than sending another payment.",
                )

            is UQPayApiException.UnexpectedStatus ->
                append("UQPAY answered HTTP ${e.statusCode} with no error body.")

            is UQPayApiException.ApiError -> {
                append("UQPAY answered HTTP ${e.statusCode}")
                e.apiError?.code?.let { append(" with code=$it") }
                append('.')
            }
        }
        appendGatewayDetail(e.apiError?.message)
    }

    /**
     * Appends the gateway's own sentence, in [Environment.SANDBOX] only.
     *
     * It is genuinely useful while integrating — "Do not honour" beats "the card was
     * declined" when you are trying to reproduce a decline — and genuinely unsafe in
     * production, where it may quote request data and where this string lands in whatever
     * crash reporter the merchant uses. So it is gated, and it is gated on the *only*
     * sentence that never reaches a customer.
     */
    private fun StringBuilder.appendGatewayDetail(detail: String?) {
        if (environment != Environment.SANDBOX) return
        val text = detail?.takeIf { it.isNotBlank() } ?: return
        append(" Gateway said: ")
        append(text)
    }
}
