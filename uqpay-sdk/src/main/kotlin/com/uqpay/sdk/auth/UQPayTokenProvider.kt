package com.uqpay.sdk.auth

/**
 * Supplies short-lived UQPAY access tokens to the SDK. Implemented by the host app,
 * which fetches them from its **own backend** over its own authenticated channel.
 *
 * The SDK calls this when it has no cached token, when the cached one is close to
 * expiry, or once after a `401`.
 *
 * ### Why the app never mints its own token
 *
 * A token is minted from the merchant's `x-api-key`, which can issue refunds and
 * payouts and **must never be shipped in an app**. UQPAY also permits only **one active
 * access token per merchant**: minting a new one invalidates the previous one. A device
 * that minted its own would sign out every other device *and the merchant's own
 * backend*. For the same reason a merchant backend must **cache and share** one token
 * rather than minting one per payment.
 *
 * Implementations are called off the main thread and may block.
 */
public fun interface UQPayTokenProvider {
    /**
     * Returns a currently-valid token, fetching a fresh one if necessary.
     *
     * Throwing surfaces to the merchant as
     * [com.uqpay.sdk.error.UQPayErrorCode.AUTHENTICATION_FAILED]; the thrown message is
     * not shown to the customer.
     */
    public fun fetchToken(): UQPayAuthToken
}
