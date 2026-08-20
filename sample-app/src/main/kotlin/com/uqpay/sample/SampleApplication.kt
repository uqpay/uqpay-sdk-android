package com.uqpay.sample

import android.app.Application
import com.uqpay.sdk.UQPay
import com.uqpay.sdk.UQPayConfiguration

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
                environment = DemoMerchantBackend.environment,
                // In a real app this calls **your own backend**, over your own
                // authenticated channel, and returns whatever token that backend is
                // currently caching. Here it is the demo stand-in — see the file header on
                // DemoMerchantBackend.kt for why that file must not be copied into
                // production.
                //
                // The SDK calls it on a background thread, on first use and again whenever
                // the token it holds is near expiry or has been rejected.
                tokenProvider = DemoMerchantBackend,
                // Off in a shipped app. On here because this *is* the integration-debugging
                // build, and the SDK's degraded paths are silent otherwise. It never logs a
                // request or response body, in any environment.
                loggingEnabled = BuildConfig.DEBUG,
            ),
        )
    }
}
