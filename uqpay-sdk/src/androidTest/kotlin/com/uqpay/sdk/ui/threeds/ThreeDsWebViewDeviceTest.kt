package com.uqpay.sdk.ui.threeds

import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The 3-D Secure WebView hardening on a **real WebView engine** — the register's third
 * device scenario. Robolectric shadows `WebSettings`, so until this suite ran on hardware
 * nothing had proved the settings actually take effect in the renderer a customer's
 * challenge runs in, or that `loadDataWithBaseURL` really grants the iframe fragment the
 * origin the ACS cross-origin check depends on.
 *
 * No network: the hardening is asserted on the settings object, and the origin test loads
 * a local document — [ThreeDsWebView.IFRAME_BASE_URL] is origin metadata that is never
 * fetched (the bare domain deliberately has no DNS record; see its KDoc).
 */
@RunWith(AndroidJUnit4::class)
internal class ThreeDsWebViewDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var webView: WebView

    @Before
    fun setUp() {
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext)
            ThreeDsWebView.configure(webView)
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { webView.destroy() }
    }

    /**
     * Every restriction in [ThreeDsWebView.configure], read back from a real
     * [android.webkit.WebSettings]. JS and DOM storage are the two the challenge needs;
     * everything else is the wall between a compromised issuer page and the host app.
     */
    @Test
    fun configureHardensARealWebView() {
        instrumentation.runOnMainSync {
            val settings = webView.settings
            assertTrue("JS is required by ACS challenge pages", settings.javaScriptEnabled)
            assertTrue("DOM storage carries challenge state", settings.domStorageEnabled)
            assertFalse("file access must be off", settings.allowFileAccess)
            assertFalse("content access must be off", settings.allowContentAccess)
            @Suppress("DEPRECATION")
            assertFalse(settings.allowFileAccessFromFileURLs)
            @Suppress("DEPRECATION")
            assertFalse(settings.allowUniversalAccessFromFileURLs)
            assertFalse(
                "a second window would take the customer where this screen cannot see",
                settings.supportMultipleWindows(),
            )
            assertFalse(settings.javaScriptCanOpenWindowsAutomatically)
            assertEquals(
                "a cached response could re-serve a spent challenge page",
                WebSettings.LOAD_NO_CACHE,
                settings.cacheMode,
            )
        }
    }

    /**
     * An iframe fragment rendered through `loadDataWithBaseURL` must get the **real**
     * origin [ThreeDsWebView.IFRAME_BASE_URL] — not an opaque one — because the ACS
     * refuses the cross-origin submission that follows an opaque origin. This is the
     * property the constant exists for, checked in the engine that will actually run it.
     */
    @Test
    fun iframeFragmentGetsTheRealOriginFromTheBaseUrl() {
        val pageLoaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageLoaded.countDown()
                }
            }
            webView.loadDataWithBaseURL(
                ThreeDsWebView.IFRAME_BASE_URL,
                "<html><body>challenge fragment</body></html>",
                "text/html",
                "utf-8",
                null,
            )
        }
        assertTrue("page never finished loading", pageLoaded.await(15, TimeUnit.SECONDS))

        val origin = AtomicReference<String>()
        val evaluated = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.evaluateJavascript("window.location.origin") { value ->
                origin.set(value)
                evaluated.countDown()
            }
        }
        assertTrue("evaluateJavascript never returned", evaluated.await(15, TimeUnit.SECONDS))
        // evaluateJavascript returns a JSON-encoded value, hence the quotes.
        assertEquals("\"${ThreeDsWebView.IFRAME_BASE_URL}\"", origin.get())
    }

    /** The address a redirect and a fragment are each recorded against. */
    @Test
    fun originUrlOfMatchesTheContentKind() {
        assertEquals(
            ThreeDsWebView.IFRAME_BASE_URL,
            ThreeDsWebView.originUrlOf(ThreeDsContent.Iframe("<html></html>")),
        )
        assertEquals(
            "https://acs.example.test/challenge",
            ThreeDsWebView.originUrlOf(ThreeDsContent.Url("https://acs.example.test/challenge")),
        )
    }
}
