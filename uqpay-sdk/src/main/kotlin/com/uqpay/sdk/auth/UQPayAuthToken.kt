package com.uqpay.sdk.auth

/**
 * A short-lived UQPAY access token, issued by the **merchant's backend** and supplied to
 * the SDK by a [UQPayTokenProvider].
 *
 * @property value the token sent as `x-auth-token`. Never logged; [toString] redacts it.
 * @property expiresAtEpochMillis when the token stops being accepted, in Unix
 *   milliseconds. UQPAY tokens are valid for roughly 30 minutes. The SDK refreshes on a
 *   margin before this instant and on any `401`.
 */
public class UQPayAuthToken(
    public val value: String,
    public val expiresAtEpochMillis: Long,
) {
    /** Never expose the token in logs or stack traces. */
    override fun toString(): String =
        "UQPayAuthToken(value=****, expiresAtEpochMillis=$expiresAtEpochMillis)"
}
