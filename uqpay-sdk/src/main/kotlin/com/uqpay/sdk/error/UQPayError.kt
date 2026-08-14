package com.uqpay.sdk.error

/**
 * A failure surfaced by the SDK. [message] is human-readable, actionable, and never
 * contains sensitive data (safe to log).
 */
public data class UQPayError(
    val code: UQPayErrorCode,
    val message: String,
)
