package com.uqpay.sdk.ui.threeds

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ApplicationProvider
import com.uqpay.sdk.ui.UqpayTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCookieManager

/**
 * The 3-D Secure host: how the WebView is configured, how each of the two `next_action`
 * shapes is loaded, and — most importantly — what the screen refuses to conclude.
 *
 * The iframe fragment below is the **real one captured from the live sandbox on 2026-08-18**
 * (Mastercard `5346930100108117`, `three_ds_action: enforce_3ds`), trimmed of its
 * session-specific ACS path. It carries no card data and no credential; it is the shape a
 * `redirect_iframe` actually has, which is the only reason it is worth pinning.
 */
@RunWith(RobolectricTestRunner::class)
class ThreeDsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val liveFragment =
        """<iframe name="threeDSRedirectIframe" id="threeDSRedirectIframe" style="display: none;"></iframe>""" +
            """<form id="threeDSRedirectForm" method="POST" target="_top" """ +
            """action="https://sit-3dss-cpxy.example.invalid/auth/2.1.0/token" style="display:none" >""" +
            """<input type="hidden" name="threedsIntegratorOid" value="1008470" /></form>""" +
            """<script>(function(){document.getElementById("threeDSRedirectForm").submit();})();</script>"""

    private val challengeUrl = "https://sit-3dss-cpxy.example.invalid/challenge/abc"

    private fun webView(): WebView = WebView(ApplicationProvider.getApplicationContext())

    /** The origin the customer is authenticating against. */
    private val acsUrl = "https://acs.example.invalid/challenge/abc"

    /** A page the merchant's own app uses. The 3DS step never goes near it. */
    private val hostAppUrl = "https://shop.example.invalid/account"

    private val cookies: CookieManager get() = CookieManager.getInstance()

    /**
     * `CookieManager` is process-global in production and static in Robolectric, and so is
     * [ThreeDsBrowsingState]'s map. Reset both, or a test that leaves a cookie behind decides
     * the next one.
     */
    @After
    fun resetBrowsingState() {
        ShadowCookieManager.resetCookies()
        ThreeDsBrowsingState.forgetAllForTest()
    }

    // ---- the iframe must be a document, never a GET ------------------------------------------

    @Test
    fun `an iframe action is loaded as data against a real https base, never as a URL`() {
        val web = webView()
        ThreeDsWebView.load(web, ThreeDsContent.Iframe(liveFragment))
        val shadow = shadowOf(web)

        val loaded = assertNotNull("the iframe must be loaded as data", shadow.lastLoadDataWithBaseURL)
            .let { shadow.lastLoadDataWithBaseURL!! }
        assertNull(
            "rewriting a self-submitting POST form as a GET navigation drops the body and 3DS fails",
            shadow.lastLoadedUrl,
        )
        assertEquals(ThreeDsWebView.IFRAME_BASE_URL, loaded.baseUrl)
        assertTrue("the base must be a real https origin, not about:blank", loaded.baseUrl.startsWith("https://"))
        assertEquals("text/html", loaded.mimeType)
        assertEquals("UTF-8", loaded.encoding)
        assertTrue("the fragment must survive verbatim", loaded.data.contains("threeDSRedirectForm"))
        assertTrue(loaded.data.contains("""method="POST""""))
    }

    @Test
    fun `the wrapper is a document that submits the form it was given`() {
        val document = ThreeDsWebView.wrapFragment(liveFragment)
        assertTrue(document.startsWith("<!doctype html>"))
        assertTrue("without a viewport the challenge renders at desktop width", document.contains("viewport"))
        assertTrue("the fragment must be embedded, not summarised", document.contains(liveFragment))
        assertTrue("a fragment without its own script must still submit", document.contains("forms[0].submit()"))
    }

    @Test
    fun `a redirect action is loaded as a URL`() {
        val web = webView()
        ThreeDsWebView.load(web, ThreeDsContent.Url(challengeUrl))
        val shadow = shadowOf(web)
        assertEquals(challengeUrl, shadow.lastLoadedUrl)
        assertNull(shadow.lastLoadDataWithBaseURL)
    }

    // ---- WebView settings ----------------------------------------------------------------------

    @Test
    fun `JavaScript is on, because 3-D Secure does not work without it`() {
        val web = webView()
        ThreeDsWebView.configure(web)
        assertTrue(
            "the fingerprint fragment submits from a <script>, and the ACS page is script-driven",
            web.settings.javaScriptEnabled,
        )
    }

    @Test
    fun `no JavaScript bridge is ever installed`() {
        val web = webView()
        ThreeDsWebView.configure(web)
        ThreeDsWebView.load(web, ThreeDsContent.Iframe(liveFragment))
        // Robolectric exposes no public accessor for the whole map, and asking about a few
        // guessed names would not be an assertion at all — a bridge under any other name
        // would pass. The private field is read directly so that *adding a bridge of any
        // name* fails this test.
        val field = shadowOf(web).javaClass.getDeclaredField("javascriptInterfaces")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val interfaces = field.get(shadowOf(web)) as Map<String, Any>
        assertTrue(
            "a bridge would hand every script on an issuer page a callable object inside a " +
                "payment SDK's process; found $interfaces",
            interfaces.isEmpty(),
        )
    }

    @Test
    fun `file and content access are off, so a remote script cannot read local storage`() {
        val web = webView()
        ThreeDsWebView.configure(web)
        assertFalse(web.settings.allowFileAccess)
        assertFalse(web.settings.allowContentAccess)
        assertFalse(web.settings.allowFileAccessFromFileURLs)
        assertFalse(web.settings.allowUniversalAccessFromFileURLs)
    }

    @Test
    fun `DOM storage is on for the challenge, and nothing is cached`() {
        val web = webView()
        ThreeDsWebView.configure(web)
        assertTrue("ACS implementations carry challenge state in DOM storage", web.settings.domStorageEnabled)
        assertEquals(
            "the URL identifies a payment; a cached response could re-serve a spent challenge",
            WebSettings.LOAD_NO_CACHE,
            web.settings.cacheMode,
        )
        assertFalse(web.settings.javaScriptCanOpenWindowsAutomatically)
    }

    @Test
    fun `teardown stops the page and destroys the view rather than leaving it navigating`() {
        val web = webView()
        ThreeDsWebView.configure(web)
        ThreeDsWebView.load(web, ThreeDsContent.Url(challengeUrl))
        ThreeDsWebView.teardown(web)
        val shadow = shadowOf(web)
        assertTrue("a WebView destroyed mid-navigation can keep talking to the issuer", shadow.wasDestroyCalled())
        assertTrue(shadow.wasClearCacheCalled())
        assertEquals("about:blank", shadow.lastLoadedUrl)
    }

    // ---- the return URL is a signal, never an outcome -------------------------------------------

    @Test
    fun `a custom scheme ends the browser step without any configuration`() {
        // The case that actually fires for a native SDK: a WebView cannot load
        // `uqpaysample://payment` at all, and left alone it renders an error page.
        assertTrue(ThreeDsReturnUrl.isEndOfBrowserStep("uqpaysample://payment", emptyList()))
        assertTrue(ThreeDsReturnUrl.isEndOfBrowserStep("myapp://return?p=succeeded", emptyList()))
        assertTrue(ThreeDsReturnUrl.isEndOfBrowserStep("intent://x#Intent;end", emptyList()))
    }

    @Test
    fun `an ordinary issuer page is not the end of the step`() {
        assertFalse(ThreeDsReturnUrl.isEndOfBrowserStep(challengeUrl, emptyList()))
        assertFalse(ThreeDsReturnUrl.isEndOfBrowserStep("http://acs.example.invalid/otp", emptyList()))
        assertFalse(ThreeDsReturnUrl.isEndOfBrowserStep("", emptyList()))
    }

    @Test
    fun `a configured https return url is matched by prefix`() {
        val prefixes = listOf("https://merchant.example.invalid/return")
        assertTrue(ThreeDsReturnUrl.isEndOfBrowserStep("https://merchant.example.invalid/return?p=pending", prefixes))
        assertFalse(ThreeDsReturnUrl.isEndOfBrowserStep("https://merchant.example.invalid/other", prefixes))
    }

    @Test
    fun `a blank prefix is ignored, so it cannot end the step on the first navigation`() {
        assertFalse(ThreeDsReturnUrl.isEndOfBrowserStep(challengeUrl, listOf("", "   ")))
    }

    @Test
    fun `the return url's query parameters are never read as an outcome`() {
        // UQPAY appends `p` (a status), `token` and `mid` to the return URL. Every one of
        // those is under the control of the page the WebView last navigated to. A screen that
        // believed them would show "Payment successful" for a declined payment.
        val prefixes = listOf("https://merchant.example.invalid/return")
        val succeeded = "https://merchant.example.invalid/return?p=succeeded&token=x&mid=y"
        val failed = "https://merchant.example.invalid/return?p=failed&token=x&mid=y"
        assertEquals(
            "the two must be indistinguishable to this screen — only the API decides",
            ThreeDsReturnUrl.isEndOfBrowserStep(succeeded, prefixes),
            ThreeDsReturnUrl.isEndOfBrowserStep(failed, prefixes),
        )
    }

    // ---- the screen -------------------------------------------------------------------------------

    @Test
    fun `the screen shows the issuer page and offers a way out`() {
        var cancelled = 0
        compose.setContent {
            UqpayTheme {
                ThreeDsScreen(
                    content = ThreeDsContent.Iframe(liveFragment),
                    sessionKey = INTENT,
                    returnUrlPrefixes = emptyList(),
                    onReturnedFromChallenge = {},
                    onCancel = { cancelled++ },
                )
            }
        }
        compose.onNodeWithContentDescription("Card verification page from your bank").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel verification and leave this screen").performClick()
        assertEquals(1, cancelled)
    }

    // ---- B1: the ACS session outlives the WebView ------------------------------------------------
    //
    // The bug this section exists for: the WebView is destroyed on every configuration change,
    // and the ACS sets a session cookie between the fingerprint step and the challenge that
    // the challenge cannot be completed without. Clearing cookies from anything tied to the
    // view's lifetime therefore deletes the customer's authentication mid-authentication, the
    // reloaded challenge is rejected, and the payment runs the poller out to PENDING.

    @Test
    fun `tearing the WebView down leaves the issuer session alone - a rotation is a teardown`() {
        cookies.setAcceptCookie(true)
        cookies.setCookie(acsUrl, "ACSSESSION=live-session")

        val web = webView()
        ThreeDsWebView.configure(web)
        ThreeDsWebView.load(web, ThreeDsContent.Url(acsUrl))
        ThreeDsWebView.teardown(web)

        assertTrue(
            "teardown runs on every rotation; deleting the ACS session cookie here is B1 — the " +
                "customer can no longer finish the challenge they were half-way through",
            cookies.getCookie(acsUrl).orEmpty().contains("ACSSESSION=live-session"),
        )
    }

    @Test
    fun `leaving the composition leaves the issuer session alone, and remembers the origin to clear later`() {
        cookies.setAcceptCookie(true)
        cookies.setCookie(acsUrl, "ACSSESSION=live-session")
        var shown by mutableStateOf(true)

        compose.setContent {
            UqpayTheme {
                if (shown) {
                    ThreeDsScreen(
                        content = ThreeDsContent.Url(acsUrl),
                        sessionKey = INTENT,
                        returnUrlPrefixes = emptyList(),
                        onReturnedFromChallenge = {},
                        onCancel = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        // Exactly what a rotation does to this screen: the composable leaves the tree.
        shown = false
        compose.waitForIdle()

        assertTrue(
            "a disposal is a configuration change as often as it is the end of a payment",
            cookies.getCookie(acsUrl).orEmpty().contains("ACSSESSION=live-session"),
        )
        assertTrue(
            "the origin must still be recorded, or the payment-over path has nothing to clear",
            ThreeDsBrowsingState.visitedUrls(INTENT).any { it.startsWith("https://acs.example.invalid") },
        )
    }

    @Test
    fun `ending the payment expires the 3DS cookies and nothing else - the host app stays signed in`() {
        cookies.setAcceptCookie(true)
        cookies.setCookie(acsUrl, "ACSSESSION=live-session")
        cookies.setCookie(hostAppUrl, "merchant_login=keep-me")
        ThreeDsBrowsingState.record(INTENT, acsUrl)

        ThreeDsBrowsingState.clear(INTENT)

        assertFalse(
            "the issuer's session is authentication state for a payment that is over",
            cookies.getCookie(acsUrl).orEmpty().contains("live-session"),
        )
        assertTrue(
            "removeAllCookies would sign the merchant's own users out on every card payment",
            cookies.getCookie(hostAppUrl).orEmpty().contains("merchant_login=keep-me"),
        )
    }

    @Test
    fun `clearing one payment cannot end another payment's challenge`() {
        cookies.setAcceptCookie(true)
        cookies.setCookie(acsUrl, "ACSSESSION=live-session")
        ThreeDsBrowsingState.record("PI_other_payment", acsUrl)

        ThreeDsBrowsingState.clear(INTENT)

        assertTrue(
            "the recorded origins are scoped per payment intent id",
            cookies.getCookie(acsUrl).orEmpty().contains("live-session"),
        )
    }

    @Test
    fun `clearing a payment that never reached 3-D Secure is a no-op`() {
        cookies.setAcceptCookie(true)
        cookies.setCookie(hostAppUrl, "merchant_login=keep-me")

        ThreeDsBrowsingState.clear(INTENT)

        assertTrue(cookies.getCookie(hostAppUrl).orEmpty().contains("merchant_login=keep-me"))
    }

    @Test
    fun `a cookie set by a sub-resource host is recorded, because that is where the ACS session lives`() {
        val client = client()
        client.onLoadResource(null, "https://fingerprint.example.invalid/collect?id=1")
        client.onPageStarted(null, "https://acs.example.invalid/challenge/abc", null)
        client.shouldOverrideUrlLoading(null, request("https://acs.example.invalid/challenge/step2"))

        assertEquals(
            listOf(
                "https://fingerprint.example.invalid/collect",
                "https://acs.example.invalid/challenge/abc",
                "https://acs.example.invalid/challenge/step2",
            ),
            ThreeDsBrowsingState.visitedUrls(INTENT),
        )
    }

    @Test
    fun `the merchant's return deep link is never recorded - a custom scheme has no cookie jar`() {
        val client = client()
        client.onLoadResource(null, "uqpaysample://payment?p=succeeded")
        client.onPageStarted(null, "about:blank", null)
        assertEquals(emptyList<String>(), ThreeDsBrowsingState.visitedUrls(INTENT))
    }

    // ---- the URL and cookie rules the clear is built on -------------------------------------------

    @Test
    fun `origins are normalised so one challenge page is one entry, however it is polled`() {
        assertEquals(
            "https://acs.example.invalid/challenge/abc",
            ThreeDsUrls.normalise("HTTPS://ACS.Example.Invalid/challenge/abc?nonce=1#top"),
        )
        assertEquals("https://acs.example.invalid/", ThreeDsUrls.normalise("https://acs.example.invalid"))
        assertEquals("https://acs.example.invalid:8443/x", ThreeDsUrls.normalise("https://acs.example.invalid:8443/x"))
        assertNull("a merchant deep link has no cookies", ThreeDsUrls.normalise("uqpaysample://payment"))
        assertNull(ThreeDsUrls.normalise("about:blank"))
        assertNull(ThreeDsUrls.normalise(""))
    }

    @Test
    fun `every path a cookie could have been scoped to is walked, because getCookie never says`() {
        assertEquals(
            listOf("/", "/challenge", "/challenge/abc"),
            ThreeDsUrls.pathCandidates("https://acs.example.invalid/challenge/abc"),
        )
        assertEquals(listOf("/"), ThreeDsUrls.pathCandidates("https://acs.example.invalid/"))
    }

    @Test
    fun `cookie names are read from the header, and a malformed entry produces no write`() {
        assertEquals(listOf("a", "b"), ThreeDsCookies.namesIn("a=1; b=2"))
        assertEquals(listOf("a"), ThreeDsCookies.namesIn("  a=1=2  "))
        assertEquals(emptyList<String>(), ThreeDsCookies.namesIn("garbage"))
        assertEquals(emptyList<String>(), ThreeDsCookies.namesIn(""))
        assertEquals(emptyList<String>(), ThreeDsCookies.namesIn(null))
    }

    @Test
    fun `a deletion cookie is written for each path, host-only and domain-scoped, blanked then expired`() {
        val values = ThreeDsCookies.deletionValues("ACSSESSION", "https://acs.example.invalid/challenge/abc")
        // Three candidate paths x {host-only, Domain=} x {blank, expire}.
        assertEquals(12, values.size)
        assertTrue(values.all { it.startsWith("ACSSESSION=;") })
        assertEquals(
            "half the writes carry no expiry: they land unconditionally and take the session " +
                "token with them even if the delete that follows is ignored",
            6,
            values.count { it.contains("Max-Age=0") },
        )
        assertTrue(
            "the value must be blanked before it is expired, or a failed delete keeps the token",
            values.indexOf("ACSSESSION=; Path=/") < values.indexOf("ACSSESSION=; Path=/; Max-Age=0; Expires=$EPOCH"),
        )
        assertTrue("older WebViews honour Expires, not Max-Age", values.all {
            !it.contains("Max-Age=0") || it.contains("Expires=Thu, 01 Jan 1970")
        })
        assertTrue(values.any { it.contains("Path=/challenge;") })
        assertTrue(values.any { it.contains("Domain=acs.example.invalid") })
        assertFalse(
            "a parent domain can be shared with pages the merchant owns; deleting there is the " +
                "same overreach in a smaller shape",
            values.any { it.contains("Domain=.example.invalid") || it.contains("Domain=example.invalid") },
        )
    }

    @Test
    fun `a runaway page cannot grow the recorded set without bound`() {
        repeat(ThreeDsBrowsingState.MAX_URLS_PER_SESSION + 50) { i ->
            ThreeDsBrowsingState.record(INTENT, "https://acs.example.invalid/p$i")
        }
        assertEquals(ThreeDsBrowsingState.MAX_URLS_PER_SESSION, ThreeDsBrowsingState.visitedUrls(INTENT).size)
    }

    // ---- M-render: a renderer crash must not take the merchant's app with it ----------------------

    @Test
    fun `a dead renderer is handled, so Android does not kill the merchant's process`() {
        var rendererGone = 0
        var returned = 0
        val client = ThreeDsWebViewClient(
            returnUrlPrefixes = emptyList(),
            onStarted = {},
            onFinished = {},
            onVisited = {},
            onReachedReturnUrl = {},
            onRendererGone = { rendererGone++; returned++ },
        )

        assertTrue(
            "returning false tells the framework the app cannot continue, and Android then " +
                "kills the whole host process mid-payment",
            client.onRenderProcessGone(null, null),
        )
        assertEquals(1, rendererGone)
        assertEquals("the poller is nudged; it, not this screen, decides the outcome", 1, returned)
    }

    /**
     * `onRenderProcessGone` and its `RenderProcessGoneDetail` parameter are API 26; this SDK's
     * `minSdk` is 24, where neither type exists. Overriding a callback the running platform
     * has never heard of is safe — the signature is resolved lazily, and the framework simply
     * never calls it — but "safe by my reading of the verifier" is not something to ship into
     * a payment SDK on faith. This loads and uses the class on a platform that has no such
     * method, which is the only way to find out.
     */
    @Test
    @Config(sdk = [24])
    fun `the client loads on API 24, where the renderer callback it overrides does not exist`() {
        val client = client()
        client.onPageStarted(null, acsUrl, null)
        assertEquals(listOf(acsUrl), ThreeDsBrowsingState.visitedUrls(INTENT))
    }

    private fun client(): ThreeDsWebViewClient = ThreeDsWebViewClient(
        returnUrlPrefixes = emptyList(),
        onStarted = {},
        onFinished = {},
        onVisited = { url -> ThreeDsBrowsingState.record(INTENT, url) },
        onReachedReturnUrl = {},
        onRendererGone = {},
    )

    private fun request(url: String): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = true
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    private companion object {
        const val INTENT = "PI_threeds_screen_test"
        const val EPOCH = "Thu, 01 Jan 1970 00:00:00 GMT"
    }
}
