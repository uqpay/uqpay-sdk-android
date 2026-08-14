package com.uqpay.sdk.network

/**
 * Status of a PaymentIntent on the wire.
 *
 * Modelled as a sealed class with an [Unknown] member rather than an enum: UQPAY can
 * introduce a status at any time, and a shipped app must not break — or worse, crash
 * mid-payment — when it sees one.
 */
internal sealed class IntentStatus {

    /**
     * Waiting for a confirm with a payment method.
     *
     * **Ambiguous by itself.** It means either "no method attached yet" (a fresh intent)
     * or "the last attempt was declined". Disambiguate via the intent's latest attempt:
     * an attempt row with status `FAILED` is a decline the merchant must hear about.
     * Telling a customer to fill in their card details after a decline is a lie.
     */
    data object RequiresPaymentMethod : IntentStatus()

    /** Waiting on the customer — 3-D Secure, QR scan. Check `next_action`. */
    data object RequiresCustomerAction : IntentStatus()

    /** Authorised but not yet captured. */
    data object RequiresCapture : IntentStatus()

    /** Processing with the provider. No action needed. */
    data object Pending : IntentStatus()

    data object Succeeded : IntentStatus()

    data object Cancelled : IntentStatus()

    data object Failed : IntentStatus()

    /** A status this SDK version predates. Preserved verbatim, never an error. */
    data class Unknown(val raw: String) : IntentStatus()

    /** Whether the intent has settled and can no longer change. */
    val isTerminal: Boolean
        get() = this is Succeeded || this is Cancelled || this is Failed

    /**
     * Whether a payment can still be attempted against this intent.
     *
     * Note this deliberately treats [RequiresCapture] as *not* payable, while the
     * outcome watchers treat it as customer-paid. Both are correct: an authorised
     * payment must not be charged again, but it also must be reported as a success.
     * These rules legitimately differ per call site — do not unify them.
     */
    val isPayable: Boolean
        get() = !isTerminal && this !is RequiresCapture

    companion object {
        /**
         * Parses a wire status. Accepts both `CANCELLED` and `CANCELED`: the API is not
         * consistent about the spelling.
         */
        fun from(raw: String?): IntentStatus = when (raw?.trim()?.uppercase()) {
            null, "" -> Unknown("")
            "REQUIRES_PAYMENT_METHOD" -> RequiresPaymentMethod
            "REQUIRES_CUSTOMER_ACTION" -> RequiresCustomerAction
            "REQUIRES_CAPTURE" -> RequiresCapture
            "PENDING" -> Pending
            "SUCCEEDED" -> Succeeded
            "CANCELLED", "CANCELED" -> Cancelled
            "FAILED" -> Failed
            else -> Unknown(raw)
        }
    }
}

/**
 * Status of a single PaymentAttempt. An intent can host several attempts, so an attempt
 * failing is not the same as the payment failing.
 */
internal sealed class AttemptStatus {

    data object Initiated : AttemptStatus()

    data object AuthenticationRedirected : AttemptStatus()

    data object PendingAuthorization : AttemptStatus()

    data object Authorized : AttemptStatus()

    /** Capture submitted. The guide notes this also indicates the payment succeeded. */
    data object CaptureRequested : AttemptStatus()

    data object Settled : AttemptStatus()

    data object Succeeded : AttemptStatus()

    data object Cancelled : AttemptStatus()

    data object Expired : AttemptStatus()

    data object Failed : AttemptStatus()

    data class Unknown(val raw: String) : AttemptStatus()

    val isTerminal: Boolean
        get() = this is Succeeded || this is Cancelled || this is Expired || this is Failed

    companion object {
        fun from(raw: String?): AttemptStatus = when (raw?.trim()?.uppercase()) {
            null, "" -> Unknown("")
            "INITIATED" -> Initiated
            "AUTHENTICATION_REDIRECTED" -> AuthenticationRedirected
            "PENDING_AUTHORIZATION" -> PendingAuthorization
            "AUTHORIZED" -> Authorized
            "CAPTURE_REQUESTED" -> CaptureRequested
            "SETTLED" -> Settled
            "SUCCEEDED" -> Succeeded
            "CANCELLED", "CANCELED" -> Cancelled
            "EXPIRED" -> Expired
            "FAILED" -> Failed
            else -> Unknown(raw)
        }
    }
}
