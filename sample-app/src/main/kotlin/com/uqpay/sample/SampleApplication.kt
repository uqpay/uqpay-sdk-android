package com.uqpay.sample

import android.app.Application
import com.uqpay.sdk.UQPay
import com.uqpay.sdk.UQPayConfiguration
import com.uqpay.sdk.appearance.UQPayAppearance

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
                // The payment sheet in this store's colours rather than stock Material
                // purple. Set once, here, and every sheet the SDK ever draws matches — the
                // method list, the card form, the wallet QR, the 3-D Secure chrome.
                //
                // Only the roles this demo actually cares about are named; everything else
                // stays Material 3's, which is a sane default and one fewer thing to get
                // wrong. Colours are plain ARGB ints, so a host app on Views (like this one)
                // configures the sheet without touching Compose.
                appearance = storeAppearance(),
            ),
        )
    }

    /**
     * The store's brand, as a payment-sheet appearance.
     *
     * `colorMode = SYSTEM` is the default and the right answer for an app that itself follows
     * the device. An app that forces its own light or dark mode should say so here instead —
     * otherwise a light-only checkout hands the customer a dark payment sheet at the last
     * step, which reads as somebody else's screen.
     *
     * Contrast is the merchant's job and nothing checks it: `onPrimary` against `primary` has
     * to be readable, or the Pay button is a payment that does not happen. The pair below is
     * white on a deep blue, which clears WCAG AA comfortably.
     */
    private fun storeAppearance(): UQPayAppearance = UQPayAppearance(
        colorMode = UQPayAppearance.ColorMode.SYSTEM,
        lightColors = UQPayAppearance.Colors.MATERIAL_LIGHT.copy(
            primary = BRAND_BLUE,
            onPrimary = BRAND_ON_BLUE,
        ),
        darkColors = UQPayAppearance.Colors.MATERIAL_DARK.copy(
            primary = BRAND_BLUE_DARK,
            onPrimary = BRAND_ON_BLUE_DARK,
        ),
        cornerRadiusDp = 8f,
    )

    private companion object {
        const val BRAND_BLUE = 0xFF0B5FFF.toInt()
        const val BRAND_ON_BLUE = 0xFFFFFFFF.toInt()

        /** Lifted for dark backgrounds; the light-mode blue is too dark to read against one. */
        const val BRAND_BLUE_DARK = 0xFF9DBBFF.toInt()
        const val BRAND_ON_BLUE_DARK = 0xFF00265C.toInt()
    }
}
