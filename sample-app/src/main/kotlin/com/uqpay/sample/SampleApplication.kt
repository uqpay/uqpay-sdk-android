package com.uqpay.sample

import android.app.Application
import com.uqpay.sdk.Environment
import com.uqpay.sdk.UQPay
import com.uqpay.sdk.UQPayConfiguration
import com.uqpay.sdk.auth.UQPayAuthToken
import com.uqpay.sdk.auth.UQPayTokenProvider

/**
 * Mirrors what a merchant app does: initialize the SDK once, at startup.
 *
 * `initialize` stores the configuration and nothing else — no network, no disk — so it
 * is safe here and cannot slow cold start.
 */
class SampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        UQPay.initialize(
            context = this,
            configuration = UQPayConfiguration(
                clientId = BuildConfig.UQPAY_CLIENT_ID,
                environment = Environment.SANDBOX,
                tokenProvider = SampleTokenProvider(),
            ),
        )
    }
}

/**
 * Stands in for a real merchant token provider.
 *
 * A production app calls **its own backend** here, over its own authenticated channel,
 * and returns whatever token that backend is currently caching. This sample just reads a
 * token pasted into `local.properties`, because it has no backend.
 *
 * Two rules a real implementation must follow:
 *
 * 1. The merchant's `x-api-key` never reaches the app — it can issue refunds and payouts.
 * 2. The backend mints **one** token and shares it. UQPAY allows a single active token
 *    per merchant, so minting one per checkout invalidates the token every other
 *    customer's device is holding, and the backend's own.
 *
 * Called off the main thread, so blocking here is fine.
 */
private class SampleTokenProvider : UQPayTokenProvider {

    override fun fetchToken(): UQPayAuthToken {
        val token = BuildConfig.UQPAY_SANDBOX_TOKEN
        check(token.isNotBlank()) {
            "No sandbox token. Add uqpay.sandboxToken=<token> to local.properties. " +
                "A real app fetches this from its own backend instead."
        }
        // UQPAY access tokens last about 30 minutes. A real provider returns the expiry
        // its backend reports; this sample assumes the full window from now.
        return UQPayAuthToken(
            value = token,
            expiresAtEpochMillis = System.currentTimeMillis() + THIRTY_MINUTES_MILLIS,
        )
    }

    private companion object {
        const val THIRTY_MINUTES_MILLIS = 30 * 60 * 1000L
    }
}
