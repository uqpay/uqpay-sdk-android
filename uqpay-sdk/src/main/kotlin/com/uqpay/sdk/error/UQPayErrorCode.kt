package com.uqpay.sdk.error

/**
 * Stable error codes — documented in `docs/error-codes.md`. Codes are never renamed,
 * renumbered, or repurposed after release; new codes may be added.
 */
public enum class UQPayErrorCode {
    NOT_INITIALIZED,
    INVALID_CONFIGURATION,
    INVALID_REQUEST,
    NETWORK_ERROR,
    TIMEOUT,
    AUTHENTICATION_FAILED,
    PAYMENT_DECLINED,
    USER_CANCELLED,
    SERVER_ERROR,
    UNKNOWN,
}
