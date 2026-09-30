package com.uqpay.sdk.ui.threeds

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.uqpay.sdk.ui.UqpayTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCookieManager
import java.util.concurrent.TimeUnit

/**
 * What happens to the **real** WebView the 3-D Secure composable builds: when it is
 * re-loaded, when it is not, and what becomes of the screen when its renderer dies.
 *
 * Its own class because these need an Activity. `createAndroidComposeRule` lays the
 * composition out — so the `AndroidView` is actually realised and its `WebView` can be found
 * and driven the way the framework drives it — and `ActivityScenario` under Robolectric does
 * not lay anything out, so the same assertions there would have nothing to assert on.
 *
 * API 26+: [android.webkit.WebViewClient.onRenderProcessGone] does not exist below it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreeDsWebViewLifetimeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val challengeUrl = "https://acs.example.invalid/challenge/abc"
    private val secondStageUrl = "https://acs.example.invalid/challenge/otp"

    @After
    fun forgetOrigins() {
        ShadowCookieManager.resetCookies()
        ThreeDsBrowsingState.forgetAllForTest()
    }

    /**
     * The screen's progress indicator is indeterminate — an `InfiniteTransition` asking for
     * frames forever — and no page load ever completes here, so an auto-advancing test clock
     * never reaches idle. Composition, layout and state reads all still run; only the endless
     * animation is held still, and frames are advanced by hand where one is needed.
     */
    private fun freezeEndlessAnimations() {
        compose.mainClock.autoAdvance = false
    }

    // ---- M-render ---------------------------------------------------------------------------

    /**
     * The framework's default for `onRenderProcessGone` is to return **false**, which tells
     * Android the app cannot continue — and Android then kills the *merchant's whole process*,
     * during a card payment, with no result delivered. A renderer is killed for reasons that
     * have nothing to do with this SDK (memory pressure while backgrounded is the common one),
     * so "the WebView died" must never mean "the host app died".
     */
    @Test
    fun `a dead renderer keeps the merchant's process and hands the customer to the poller`() {
        freezeEndlessAnimations()
        var nudges = 0
        compose.setContent {
            UqpayTheme {
                ThreeDsScreen(
                    content = ThreeDsContent.Url(challengeUrl),
                    sessionKey = INTENT,
                    returnUrlPrefixes = emptyList(),
                    onReturnedFromChallenge = { nudges++ },
                    onCancel = {},
                )
            }
        }
        compose.waitForIdle()
        val web = requireWebView()

        killRenderer(web)

        compose.onNodeWithContentDescription(INTERRUPTED).assertIsDisplayed()
        assertNull(
            "the dead WebView is released rather than left on screen showing nothing",
            webView(),
        )
        assertEquals(
            "the intent may already have been authenticated before the renderer died; the " +
                "poller is nudged to find out now, and it — not this screen — decides",
            1,
            nudges,
        )
    }

    /**
     * A renderer crash is not the end of the payment, so it must not be the end of the
     * screen's usefulness either: if the engine hands over a fresh action (multi-stage 3DS,
     * G13), the customer gets a working WebView for it rather than an interrupted panel they
     * can never leave.
     */
    @Test
    fun `a fresh action after a renderer crash is shown, not swallowed by the interrupted panel`() {
        freezeEndlessAnimations()
        var content: ThreeDsContent by mutableStateOf(ThreeDsContent.Url(challengeUrl))
        compose.setContent {
            UqpayTheme {
                ThreeDsScreen(
                    content = content,
                    sessionKey = INTENT,
                    returnUrlPrefixes = emptyList(),
                    onReturnedFromChallenge = {},
                    onCancel = {},
                )
            }
        }
        compose.waitForIdle()
        killRenderer(requireWebView())
        compose.onNodeWithContentDescription(INTERRUPTED).assertIsDisplayed()

        // On the UI thread: a snapshot write from the test thread reaches the recomposer only
        // once the frames and the apply notification happen to line up, which is a race the
        // full suite loses often enough to matter.
        compose.runOnUiThread { content = ThreeDsContent.Url(secondStageUrl) }
        awaitFrames("the second challenge to be loaded") {
            webView()?.let { shadowOf(it).lastLoadedUrl == secondStageUrl } == true
        }
    }

    // ---- the challenge is loaded once ---------------------------------------------------------

    /**
     * The behaviour a customer feels: working through the challenge is not undone by the
     * screen redrawing.
     *
     * Two things protect this — Compose memoising the `AndroidView` update lambda while
     * `content` is unchanged, and the tag check inside it — and this pins the outcome rather
     * than either mechanism, because it is the outcome that matters and the mechanism that
     * gets refactored. (Deleting the tag check alone does not fail this test today; it fails
     * the moment anything makes that lambda unstable, which is exactly the change whose blast
     * radius nobody predicts.)
     */
    @Test
    fun `recomposing does not restart the issuer's page under the customer`() {
        freezeEndlessAnimations()
        showChallenge(ThreeDsContent.Url(challengeUrl))
        val web = requireWebView()
        assertEquals(challengeUrl, shadowOf(web).lastLoadedUrl)

        // The customer works through the challenge: the ACS navigates them on, deeper into
        // the flow. Then the screen redraws, from the page callbacks that drive its progress
        // bar — the real source of recomposition here.
        compose.runOnUiThread {
            web.loadUrl(DEEP_IN_CHALLENGE)
            shadowOf(web).webViewClient.onPageStarted(web, DEEP_IN_CHALLENGE, null)
            shadowOf(web).webViewClient.onPageFinished(web, DEEP_IN_CHALLENGE)
        }
        settle()

        assertEquals(
            "re-loading here would restart the issuer's page mid-authentication, every frame",
            DEEP_IN_CHALLENGE,
            shadowOf(web).lastLoadedUrl,
        )
    }

    /**
     * The other half of the same rule: a genuinely *changed* action must load, into the
     * WebView already on screen. A mixed-mode authentication shows the fingerprint step and
     * then a challenge (G13), and the engine delivers the second as a new `next_action` while
     * this screen stays up. Only the tag check can tell that from a redraw.
     */
    @Test
    fun `a changed action loads into the WebView already on screen`() {
        freezeEndlessAnimations()
        var content: ThreeDsContent by mutableStateOf(ThreeDsContent.Url(challengeUrl))
        compose.setContent {
            UqpayTheme {
                ThreeDsScreen(
                    content = content,
                    sessionKey = INTENT,
                    returnUrlPrefixes = emptyList(),
                    onReturnedFromChallenge = {},
                    onCancel = {},
                )
            }
        }
        compose.waitForIdle()
        val first = requireWebView()
        assertEquals(challengeUrl, shadowOf(first).lastLoadedUrl)

        compose.runOnUiThread { content = ThreeDsContent.Url(secondStageUrl) }
        awaitFrames("the second stage to load — multi-stage 3DS depends on it") {
            webView()?.let { shadowOf(it).lastLoadedUrl == secondStageUrl } == true
        }
        assertSame("and into the same WebView, not a new one", first, requireWebView())
    }

    /** Renders [content] and waits for its WebView to exist. */
    private fun showChallenge(content: ThreeDsContent) {
        compose.setContent {
            UqpayTheme {
                ThreeDsScreen(
                    content = content,
                    sessionKey = INTENT,
                    returnUrlPrefixes = emptyList(),
                    onReturnedFromChallenge = {},
                    onCancel = {},
                )
            }
        }
        compose.waitForIdle()
    }

    // ---- a page that fails to load ---------------------------------------------------------------

    /**
     * The 2026-09-29 device finding. The WebView's own error page names the ACS host, is
     * never reloaded, and offered the customer nothing but Cancel.
     */
    @Test
    fun `a failed page load is replaced by the SDK's own panel, and Try again loads a fresh WebView`() {
        freezeEndlessAnimations()
        var nudges = 0
        showChallenge(onReturned = { nudges++ })
        val failed = requireWebView()

        failMainFrame(failed)

        compose.onNodeWithText(LOAD_FAILED).assertIsDisplayed()
        assertEquals("a page that did not load says nothing about the payment; nothing is nudged", 0, nudges)

        compose.onNodeWithText(TRY_AGAIN).performClick()
        awaitFrames("the challenge to be loaded again") {
            webView()?.let { it !== failed && shadowOf(it).lastLoadedUrl == challengeUrl } == true
        }
    }

    /**
     * API 24's version of the same network loss: the ACS page is up, its 3DS-method iframe
     * timed out, and the page sits blank forever. The page is left alone — a beacon fails
     * the same way — and the customer is given a way to start it again.
     */
    @Test
    fun `a sub-frame network failure leaves the challenge on screen and offers Reload`() {
        freezeEndlessAnimations()
        showChallenge()
        val web = requireWebView()
        compose.onNodeWithText(RELOAD).assertDoesNotExist()

        compose.runOnUiThread {
            (shadowOf(web).webViewClient as ThreeDsWebViewClient).onLoadError(
                url = "https://acs.example.invalid/3dsmethod/collect",
                mainFrame = false,
                errorCode = WebViewClient.ERROR_TIMEOUT,
            )
        }
        awaitFrames("Reload to be offered") { compose.onAllNodesWithText(RELOAD).fetchSemanticsNodes().isNotEmpty() }
        assertSame("nothing is reloaded on the customer's behalf", web, webView())

        compose.onNodeWithText(RELOAD).performClick()
        awaitFrames("a fresh WebView to load the challenge") {
            webView()?.let { it !== web && shadowOf(it).lastLoadedUrl == challengeUrl } == true
        }
        awaitFrames("Reload to be withdrawn once it has been used") {
            compose.onAllNodesWithText(RELOAD).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `a page still loading after the stall window offers Reload, and a page that finishes does not`() {
        freezeEndlessAnimations()
        showChallenge()
        val web = requireWebView()

        compose.mainClock.advanceTimeBy(ThreeDsWebView.STALL_MILLIS - 1_000L)
        compose.waitForIdle()
        compose.onNodeWithText(RELOAD).assertDoesNotExist()

        compose.mainClock.advanceTimeBy(2_000L)
        awaitFrames("Reload to be offered") { compose.onAllNodesWithText(RELOAD).fetchSemanticsNodes().isNotEmpty() }
        assertSame(web, webView())

        compose.runOnUiThread { shadowOf(web).webViewClient.onPageFinished(web, challengeUrl) }
        awaitFrames("Reload to be withdrawn from a page that loaded") {
            compose.onAllNodesWithText(RELOAD).fetchSemanticsNodes().isEmpty()
        }
        compose.mainClock.advanceTimeBy(ThreeDsWebView.STALL_MILLIS * 2)
        compose.waitForIdle()
        compose.onNodeWithText(RELOAD).assertDoesNotExist()
    }

    @Test
    fun `a fresh action after a failed load is shown, not swallowed by the error panel`() {
        freezeEndlessAnimations()
        var content: ThreeDsContent by mutableStateOf(ThreeDsContent.Url(challengeUrl))
        compose.setContent {
            UqpayTheme {
                ThreeDsScreen(
                    content = content,
                    sessionKey = INTENT,
                    returnUrlPrefixes = emptyList(),
                    onReturnedFromChallenge = {},
                    onCancel = {},
                )
            }
        }
        compose.waitForIdle()
        failMainFrame(requireWebView())

        compose.runOnUiThread { content = ThreeDsContent.Url(secondStageUrl) }
        awaitFrames("the second challenge to be loaded") {
            webView()?.let { shadowOf(it).lastLoadedUrl == secondStageUrl } == true
        }
    }

    @Test
    fun `the page reloads by itself when the network returns, if the host app can observe it`() {
        freezeEndlessAnimations()
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
        showChallenge()
        failMainFrame(requireWebView())
        val callback = shadowOf(connectivity).networkCallbacks.single()
        val network = connectivity.activeNetwork!!

        // The network was up when the page failed (the default here), so "a network is
        // available" on its own is not a return — acting on it is a reload loop against an
        // ACS that is simply down.
        callback.onAvailable(network)
        shadowOf(Looper.getMainLooper()).idleFor(ThreeDsReconnect.SETTLE_MILLIS, TimeUnit.MILLISECONDS)
        settle()
        assertNull("no reload without an offline-to-online transition", webView())

        callback.onLost(network)
        callback.onAvailable(network)
        shadowOf(Looper.getMainLooper()).idleFor(ThreeDsReconnect.SETTLE_MILLIS, TimeUnit.MILLISECONDS)
        awaitFrames("the challenge to be reloaded once the network is back") {
            webView()?.let { shadowOf(it).lastLoadedUrl == challengeUrl } == true
        }
        assertTrue(
            "the callback is released with the panel, or every failed load leaks one",
            shadowOf(connectivity).networkCallbacks.isEmpty(),
        )
    }

    /**
     * `ACCESS_NETWORK_STATE` is the host app's to declare, not this SDK's. Without it the
     * platform throws on registration, so the screen must not even try.
     */
    @Test
    fun `without the host's network-state permission nothing is registered and Try again still works`() {
        freezeEndlessAnimations()
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
        showChallenge()

        failMainFrame(requireWebView())

        assertTrue(shadowOf(connectivity).networkCallbacks.isEmpty())
        compose.onNodeWithText(TRY_AGAIN).performClick()
        awaitFrames("the challenge to be loaded again") {
            webView()?.let { shadowOf(it).lastLoadedUrl == challengeUrl } == true
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private val application: Application get() = ApplicationProvider.getApplicationContext()

    private val connectivity: ConnectivityManager
        get() = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun showChallenge(onReturned: () -> Unit = {}) {
        compose.setContent {
            UqpayTheme {
                ThreeDsScreen(
                    content = ThreeDsContent.Url(challengeUrl),
                    sessionKey = INTENT,
                    returnUrlPrefixes = emptyList(),
                    onReturnedFromChallenge = onReturned,
                    onCancel = {},
                )
            }
        }
        compose.waitForIdle()
    }

    /** Drives the client the way the framework does when the main-frame navigation fails. */
    private fun failMainFrame(web: WebView) {
        compose.runOnUiThread {
            shadowOf(web).webViewClient.onReceivedError(web, request(challengeUrl, mainFrame = true), null)
        }
        awaitFrames("the failed WebView to be released") { webView() == null }
    }

    private fun request(url: String, mainFrame: Boolean): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = mainFrame
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    private fun killRenderer(web: WebView) {
        compose.runOnUiThread {
            assertTrue(
                "returning false is what turns a renderer OOM into a crash in the merchant's app",
                shadowOf(web).webViewClient.onRenderProcessGone(web, null),
            )
        }
        awaitFrames("the dead WebView to be released") { webView() == null }
    }

    /**
     * Frames by hand, because the clock is held still (see [freezeEndlessAnimations]).
     *
     * Recomposition, the layout it triggers and the `AndroidView` update that follows do not
     * reliably land in the same frame — how many it takes varies with what else has run in
     * the JVM — so waiting on the outcome is the only stable way to do this. A fixed frame
     * count here is a test that passes on a quiet machine and fails in a full suite run.
     */
    private fun awaitFrames(what: String, condition: () -> Boolean) {
        repeat(MAX_FRAMES) {
            applyStateWrites()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            if (condition()) return
        }
        fail("waited $MAX_FRAMES frames for $what")
    }

    /**
     * Hands state written outside a composition to the recomposer.
     *
     * In an app Compose does this by itself. Here it only happens as a side effect of a
     * frame somebody is waiting for, and the load-failed panel is the one state of this
     * screen with nothing animating — so a write made while it is showing (a new action
     * from the engine, a reload when the network returns) sat unapplied and the panel never
     * left. It passed alone and failed in a suite run, where the process-wide fallback that
     * would have applied it belongs to an earlier test's looper.
     */
    private fun applyStateWrites() {
        compose.runOnUiThread { Snapshot.sendApplyNotifications() }
    }

    /** Advances well past any plausible settling point, for assertions that nothing changed. */
    private fun settle() {
        repeat(MAX_FRAMES) {
            applyStateWrites()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    private fun webView(): WebView? = compose.activity.window.decorView.findWebView()

    private fun requireWebView(): WebView =
        webView().also { assertNotNull("the challenge must be on screen for this to mean anything", it) }!!

    /** The first WebView in this view tree, or null. */
    private fun View.findWebView(): WebView? = when {
        this is WebView -> this
        this is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findWebView() }
        else -> null
    }

    private companion object {
        const val INTENT = "PI_webview_lifetime_test"
        const val INTERRUPTED = "Verification was interrupted; checking with your bank"
        const val LOAD_FAILED = "We couldn't load your bank's verification page. Check your connection and try again."
        const val TRY_AGAIN = "Try again"
        const val RELOAD = "Reload"
        const val DEEP_IN_CHALLENGE = "https://acs.example.invalid/challenge/abc/otp-entered"
        const val MAX_FRAMES = 60
    }
}
