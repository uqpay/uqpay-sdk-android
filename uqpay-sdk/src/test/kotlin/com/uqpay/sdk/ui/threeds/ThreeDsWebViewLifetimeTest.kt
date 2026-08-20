package com.uqpay.sdk.ui.threeds

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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

    // ---- helpers -------------------------------------------------------------------------------

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
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            if (condition()) return
        }
        fail("waited $MAX_FRAMES frames for $what")
    }

    /** Advances well past any plausible settling point, for assertions that nothing changed. */
    private fun settle() {
        repeat(MAX_FRAMES) {
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
        const val DEEP_IN_CHALLENGE = "https://acs.example.invalid/challenge/abc/otp-entered"
        const val MAX_FRAMES = 60
    }
}
