package com.uqpay.sdk.error

/**
 * Stable error identifier, documented in `docs/error-codes.md`.
 *
 * This is an **open set**, not an enum: UQPAY can introduce new failure codes at any
 * time, and a merchant's exhaustive `when` over an enum would stop compiling when we
 * added one. Unrecognised wire codes are preserved verbatim via [of] rather than being
 * flattened into [UNKNOWN], so merchants can log and branch on codes this SDK version
 * predates.
 *
 * Compare with the constants below, and always provide a fallback branch:
 * ```
 * when (error.code) {
 *     UQPayErrorCode.CARD_DECLINED -> promptForAnotherCard()
 *     UQPayErrorCode.NETWORK_ERROR -> offerRetry()
 *     else -> showGenericFailure(error.message)
 * }
 * ```
 *
 * Codes are never renamed, renumbered, or repurposed once released.
 */
public class UQPayErrorCode private constructor(
    /** Wire value of the code, e.g. `"card_declined"`. Stable and safe to log. */
    public val raw: String,
) {
    override fun equals(other: Any?): Boolean = other is UQPayErrorCode && other.raw == raw

    override fun hashCode(): Int = raw.hashCode()

    override fun toString(): String = raw

    public companion object {
        /** A payment API was called before [com.uqpay.sdk.UQPay.initialize]. */
        @JvmField
        public val NOT_INITIALIZED: UQPayErrorCode = UQPayErrorCode("not_initialized")

        /**
         * The request never left the device: missing environment, token, or intent id.
         * A configuration problem, not a decline — it never pins an idempotency attempt.
         */
        @JvmField
        public val INVALID_CONFIGURATION: UQPayErrorCode = UQPayErrorCode("invalid_configuration")

        /** The gateway rejected the request as malformed. */
        @JvmField
        public val INVALID_REQUEST: UQPayErrorCode = UQPayErrorCode("invalid_request")

        /** The selected payment method is not valid for this account, currency, or country. */
        @JvmField
        public val INVALID_PAYMENT_METHOD: UQPayErrorCode = UQPayErrorCode("invalid_payment_method")

        /** Connectivity failure — no response was received. */
        @JvmField
        public val NETWORK_ERROR: UQPayErrorCode = UQPayErrorCode("network_error")

        /**
         * The SDK stopped waiting for an outcome. Carried on a
         * [com.uqpay.sdk.payment.PaymentStatus.PENDING] result, never on a failure:
         * the payment may still be live.
         */
        @JvmField
        public val TIMEOUT: UQPayErrorCode = UQPayErrorCode("timeout")

        /** The access token was missing, malformed, or expired. */
        @JvmField
        public val AUTHENTICATION_FAILED: UQPayErrorCode = UQPayErrorCode("authentication_failed")

        /** The issuer or acquirer declined the payment. */
        @JvmField
        public val CARD_DECLINED: UQPayErrorCode = UQPayErrorCode("card_declined")

        /** Declined specifically for insufficient funds. */
        @JvmField
        public val INSUFFICIENT_FUNDS: UQPayErrorCode = UQPayErrorCode("insufficient_funds")

        /** 3-D Secure authentication failed or was abandoned. */
        @JvmField
        public val THREE_DS_FAILED: UQPayErrorCode = UQPayErrorCode("3ds_failed")

        /** The customer abandoned the flow. */
        @JvmField
        public val CANCELLED: UQPayErrorCode = UQPayErrorCode("cancelled")

        /**
         * The payment UI was launched for an intent that is already settled
         * (succeeded, failed, or cancelled).
         */
        @JvmField
        public val INTENT_NOT_PAYABLE: UQPayErrorCode = UQPayErrorCode("intent_not_payable")

        /** The gateway failed to process an otherwise valid request. */
        @JvmField
        public val SERVER_ERROR: UQPayErrorCode = UQPayErrorCode("server_error")

        /** No more specific code applies. */
        @JvmField
        public val UNKNOWN: UQPayErrorCode = UQPayErrorCode("unknown")

        /**
         * Returns the code for [raw], preserving values this SDK version does not know.
         * Blank input yields [UNKNOWN].
         */
        @JvmStatic
        public fun of(raw: String): UQPayErrorCode =
            if (raw.isBlank()) UNKNOWN else UQPayErrorCode(raw)
    }
}
