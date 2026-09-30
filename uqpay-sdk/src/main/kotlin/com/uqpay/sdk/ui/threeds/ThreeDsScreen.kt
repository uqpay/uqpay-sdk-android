package com.uqpay.sdk.ui.threeds

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.uqpay.sdk.R
import kotlinx.coroutines.delay

/**
 * What the 3-D Secure step has to show, in the UI layer's own vocabulary.
 *
 * A deliberate re-statement of the engine's `NextAction.Redirect` / `NextAction.Iframe`
 * rather than a re-use of them: the payment UI is built so that a screen never sees an
 * engine type or a wire DTO, and the two shapes differ in exactly one way this screen cares
 * about — whether the payload is a document to render or an address to fetch.
 */
internal sealed class ThreeDsContent {

    /**
     * `redirect_iframe`: an HTML fragment carrying a **self-submitting POST form** aimed at
     * the issuer's access control server.
     *
     * It must be rendered as a document. Pulling the form's `action` out and navigating to
     * it as a GET — which looks like a tidy simplification — drops the POST body, and
     * authentication fails with nothing on screen to explain why. The live sandbox fragment
     * captured on 2026-08-18 is a hidden `<iframe>` plus a `<form method="POST" target="_top">`
     * plus a `<script>` that submits it; every part of that only works inside a document.
     */
    data class Iframe(val html: String) : ThreeDsContent()

    /** `redirect_to_url`: the challenge page (OTP, biometric). Loaded as a normal URL. */
    data class Url(val url: String) : ThreeDsContent()
}

/**
 * Hosts the issuer's 3-D Secure step in a WebView.
 *
 * ### This screen never decides the outcome
 *
 * It renders a web page and reports that the page finished. It does **not** read the return
 * URL's query parameters, and it must never be changed to. UQPAY appends `p` (a status),
 * `token` and `mid` to the return URL, and every one of those values is under the control of
 * whatever the WebView last navigated to — a page we did not write, on a host we do not own,
 * reachable by anyone who can point the device at it. Treating them as an outcome is how a
 * payment sheet is talked into showing "Payment successful" for a payment that was declined.
 *
 * The authoritative answer comes from the API, always: the engine is **already polling** the
 * intent for the whole time this screen is up, and it is the engine's poll — not this screen
 * — that settles the payment. Reaching the return URL is a *signal to stop waiting*, nothing
 * more: it lets [onReturnedFromChallenge] nudge the poller so the customer waits a second
 * instead of up to the next poll interval. If this screen were deleted entirely, the payment
 * would still settle correctly; it would just take longer and look worse.
 *
 * ### Multi-stage 3DS and declines
 *
 * A mixed-mode authentication shows the fingerprint iframe, then a challenge page, and the
 * engine may hand this screen a *second* [ThreeDsContent] as the intent's `next_action`
 * changes (G13). A 3DS decline arrives the same way any other decline does — as a settled
 * intent — and is detected by the engine's poller (G14). Neither is this screen's decision;
 * it re-renders whatever it is given and reports.
 *
 * ### A page that fails to load is this screen's to explain
 *
 * Losing the network while the issuer's page loads used to strand the customer with Cancel
 * as the only control, and it does so in two shapes (both seen on the 2026-09-29 device pass):
 *
 * - **The main frame fails.** The WebView shows its own "Webpage not available" page with the
 *   raw ACS address on it, and never reloads it. The screen now replaces the WebView with its
 *   own panel — a sentence and a **Try again** — and, where the host app can observe
 *   connectivity, reloads by itself when the network returns ([ThreeDsReconnect]).
 * - **The main frame loads and then stalls.** The ACS page arrives, its hidden 3DS-method
 *   iframe does not, and the customer looks at a blank white page under a progress bar that
 *   never ends (API 24's WebView 53, every time). Nothing failed that this screen can name,
 *   so nothing is replaced: a **Reload** action appears beside Cancel once the page has been
 *   loading for [ThreeDsWebView.STALL_MILLIS] or a sub-frame has failed at the network level.
 *   It is never automatic — the same signals can fire on a challenge the customer is
 *   half-way through typing an OTP into, and that is theirs to reload, not ours.
 *
 * Either way the reload is the same act: [content] loaded into a fresh WebView, which is
 * exactly what a configuration change already does, so the issuer sees no new request shape.
 * The engine's poll runs throughout and still decides the outcome; nothing here settles
 * anything.
 *
 * ### Rotation must not end the authentication (B1)
 *
 * A configuration change destroys this composition and its WebView, and the recreated screen
 * builds a new one from the same engine state. That is survivable — the challenge is
 * re-loaded — **only** because the ACS session cookie it depends on is no longer deleted
 * here. The cookie's lifetime is the payment's, not the composition's, and it is cleared from
 * the payment-over path in `UQPayPaymentActivity.onDestroy`; see [ThreeDsBrowsingState] for
 * why that is the only correct place and why the clear is scoped to the origins this step
 * visited instead of the whole process. Nothing in this file may clear cookies again.
 *
 * @param content what to show.
 * @param sessionKey the payment intent id, used to scope the recorded 3DS origins to *this*
 *   payment so two payments in one process cannot clear each other's session.
 * @param returnUrlPrefixes URLs whose prefix marks the end of the browser step: the intent's
 *   `return_url` and any merchant scheme. May be empty — a custom (non-`http`) scheme is
 *   recognised without it, and that is the case that actually fires for a native SDK.
 * @param onReturnedFromChallenge the browser step ended. **Not** an outcome; see above.
 * @param onCancel the customer's way out. Settles `PENDING`, never `CANCELLED`: the confirm
 *   was sent, the issuer may already have authenticated it, and reporting a cancellation for
 *   a payment that is in fact authorising is how a merchant ships an order they were never
 *   paid for.
 */
@Composable
internal fun ThreeDsScreen(
    content: ThreeDsContent,
    sessionKey: String,
    returnUrlPrefixes: List<String>,
    onReturnedFromChallenge: () -> Unit,
    onCancel: () -> Unit,
) {
    val title = stringResource(R.string.uqpay_threeds_title)
    val cancelLabel = stringResource(R.string.uqpay_cancel)
    val cancelDescription = stringResource(R.string.uqpay_cd_threeds_cancel)
    val verifyingText = stringResource(R.string.uqpay_threeds_verifying)
    val verifyingDescription = stringResource(R.string.uqpay_cd_threeds_verifying)
    val interruptedText = stringResource(R.string.uqpay_threeds_interrupted)
    val interruptedDescription = stringResource(R.string.uqpay_cd_threeds_interrupted)
    val loadingDescription = stringResource(R.string.uqpay_cd_threeds_loading)
    val webDescription = stringResource(R.string.uqpay_cd_threeds_web)
    val loadFailedText = stringResource(R.string.uqpay_threeds_load_failed)
    val retryLabel = stringResource(R.string.uqpay_threeds_retry)
    val reloadLabel = stringResource(R.string.uqpay_threeds_reload)
    val retryDescription = stringResource(R.string.uqpay_cd_threeds_retry)

    // Screen-only, in memory only. Nothing here is card-derived and nothing here is worth a
    // Bundle: after a rotation the engine's state re-drives this screen from the top.
    var loading by remember { mutableStateOf(true) }
    var finished by remember { mutableStateOf(false) }

    // The WebView's renderer died (M-render). A separate flag from [finished] because the
    // customer is told something different: the step did not complete, and the engine's poll
    // is now the only thing that can answer.
    //
    // Keyed on [content], so a *new* action clears it. A crash during the fingerprint step
    // would otherwise leave the customer on the interrupted panel even when the engine has a
    // fresh challenge for them (multi-stage 3DS, G13) — stuck watching a poll that a second
    // WebView could have finished.
    var interrupted by remember(content) { mutableStateOf(false) }

    // The issuer's page failed to load in the main frame. Keyed on [content] for the same
    // reason as [interrupted]: a fresh action from the engine deserves a fresh WebView, not
    // the previous action's error panel.
    var loadFailed by remember(content) { mutableStateOf(false) }

    // The quieter trouble signals, which reveal Reload without replacing anything: a
    // sub-frame failed at the network level, or the page has been loading for too long.
    var subFrameFailed by remember(content) { mutableStateOf(false) }
    var stalled by remember(content) { mutableStateOf(false) }

    // Bumped to discard the WebView and build a new one, which loads [content] from the top.
    var reloads by remember(content) { mutableIntStateOf(0) }
    val retry: () -> Unit = {
        loading = true
        loadFailed = false
        subFrameFailed = false
        reloads += 1
    }

    val browsing = !finished && !interrupted && !loadFailed
    LaunchedEffect(content, reloads, loading, browsing) {
        stalled = false
        if (loading && browsing) {
            delay(ThreeDsWebView.STALL_MILLIS)
            stalled = true
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            if (browsing && (stalled || subFrameFailed)) {
                TextButton(
                    onClick = retry,
                    modifier = Modifier.semantics { contentDescription = retryDescription },
                ) {
                    Text(reloadLabel)
                }
            }
            TextButton(
                onClick = onCancel,
                modifier = Modifier.semantics { contentDescription = cancelDescription },
            ) {
                Text(cancelLabel)
            }
        }
        if (loading && browsing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = loadingDescription },
            )
        }
        Box(modifier = Modifier.fillMaxSize()) {
            if (finished || interrupted) {
                // The browser step is over — completed, or ended by a renderer crash — and
                // the engine is re-reading the intent. Showing the spent challenge page would
                // invite a second attempt at it, and a dead WebView shows nothing at all.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics {
                            contentDescription =
                                if (interrupted) interruptedDescription else verifyingDescription
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = if (interrupted) interruptedText else verifyingText,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            } else if (loadFailed) {
                // SDK-drawn, so the customer never sees the WebView's own error page and
                // the ACS address printed on it.
                ThreeDsReconnect.RetryWhenBackOnline(onBackOnline = retry)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = loadFailedText,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = retry,
                        modifier = Modifier.semantics { contentDescription = retryDescription },
                    ) {
                        Text(retryLabel)
                    }
                }
            } else key(reloads) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = webDescription },
                    factory = { context ->
                        WebView(context).also { web ->
                            ThreeDsWebView.configure(web)
                            web.webViewClient = ThreeDsWebViewClient(
                                returnUrlPrefixes = returnUrlPrefixes,
                                onStarted = { loading = true },
                                onFinished = { loading = false },
                                onVisited = { url -> ThreeDsBrowsingState.record(sessionKey, url) },
                                onReachedReturnUrl = {
                                    finished = true
                                    onReturnedFromChallenge()
                                },
                                onMainFrameLoadFailed = { loadFailed = true },
                                onSubFrameLoadFailed = { subFrameFailed = true },
                                onRendererGone = {
                                    interrupted = true
                                    // The challenge cannot be finished now, but the intent
                                    // may already have been authenticated before the renderer
                                    // died. Nudging costs one read and can end the wait now
                                    // instead of at the poller's next tick; it decides
                                    // nothing, exactly as reaching the return URL decides
                                    // nothing.
                                    onReturnedFromChallenge()
                                },
                            )
                        }
                    },
                    // `update` runs again whenever this lambda is a new instance — which is
                    // as soon as `content` changes, and, because Compose memoises a lambda
                    // that captures only unchanged stable values, *not* merely because the
                    // screen recomposed when the progress bar toggled. The tag check does not
                    // rely on that memoisation holding: it records what is already loaded, so
                    // a *changed* action (multi-stage 3DS, G13) re-loads and an unchanged one
                    // is left alone whatever makes this block run. Without it, the first
                    // change that makes this lambda unstable restarts the issuer's page under
                    // the customer, on every frame, and the challenge can never be finished.
                    update = { web ->
                        if (web.tag != content) {
                            web.tag = content
                            // Recorded here as well as from the client's callbacks: this is
                            // the one origin we know is involved before a single byte has
                            // loaded, and it is the origin an iframe fragment is rendered
                            // against, which no navigation callback would name.
                            ThreeDsBrowsingState.record(sessionKey, ThreeDsWebView.originUrlOf(content))
                            ThreeDsWebView.load(web, content)
                        }
                    },
                    onRelease = ThreeDsWebView::teardown,
                )
            }
        }
    }

    // There is deliberately no DisposableEffect clearing cookies here, and none may be added.
    // A composable leaving the tree is a *configuration change* as often as it is the end of
    // a payment, and deleting the ACS session cookie on rotation is B1: the customer can no
    // longer complete the challenge they were half-way through. The clear belongs to the
    // payment's lifetime and lives in UQPayPaymentActivity.onDestroy — see
    // [ThreeDsBrowsingState].
}

/**
 * The WebView's configuration, loading and teardown, kept out of the composable so every
 * rule below is assertable in a unit test.
 *
 * Each setting here is a security decision, not a default someone liked:
 *
 * - **JavaScript on.** Non-negotiable: the fingerprint fragment submits its form from a
 *   `<script>`, and the sandbox ACS page captured on 2026-08-18 is entirely script-driven
 *   (`threeDSMethodUrl`, jQuery, a JS-populated form). 3-D Secure does not work without it.
 * - **No JavaScript bridge.** `addJavascriptInterface` is never called. A bridge would hand
 *   every script on an issuer's page — and on whatever that page frames or is redirected to
 *   — a callable Java object inside a payment SDK's process. There is nothing the 3DS step
 *   needs from the app, so there is nothing to expose.
 * - **File and content access off.** Without this a script on a remote page can read
 *   `file://` URLs and content providers, which is a path from a compromised issuer page to
 *   the host app's private storage. The 3DS step loads `https` and one in-memory document;
 *   it has no business with either.
 * - **DOM storage on.** ACS implementations use it to carry challenge state between steps.
 *   Cleared with the payment, not with the view; see [ThreeDsBrowsingState].
 * - **Cookies accepted, cleared when the payment ends — never on teardown.** The ACS sets a
 *   session cookie between the fingerprint step and the challenge; without it the challenge
 *   cannot be completed. A WebView is torn down on every configuration change, so clearing
 *   here would delete the customer's authentication in the middle of authenticating (B1).
 *   iOS gets both halves for free by using a `.nonPersistent()` `WKWebsiteDataStore`;
 *   Android's `CookieManager` is process-global and persistent, so the same guarantee is
 *   produced by hand — scoped to the origins this step visited — in [ThreeDsBrowsingState].
 */
internal object ThreeDsWebView {

    /**
     * The base URL an iframe fragment is rendered against.
     *
     * A real `https` origin, not `about:blank`. A document loaded with a null base gets an
     * **opaque origin**, and access control servers refuse the cross-origin submission that
     * follows — iOS carries the same constant for the same reason, discovered the same way.
     *
     * The bare domain deliberately has **no DNS record** (only `api-sandbox.uqpaytech.com`
     * resolves), and nothing ever fetches it: `loadDataWithBaseURL` uses it purely as origin
     * metadata. Do not "fix" it to a resolving host — that would change which cookies the
     * fragment's origin can see, and would let an ACS page fetch relative sub-resources from
     * a server this SDK never intended to involve in a challenge.
     */
    const val IFRAME_BASE_URL: String = "https://uqpaytech.com"

    /**
     * Applies every setting in the class KDoc. `setJavaScriptEnabled` is flagged by lint
     * because it is dangerous in general; it is required here, and the surrounding
     * restrictions are what make it safe enough to ship in a payment SDK.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            // One WebView, one flow. A challenge page that opened a second window would
            // leave the customer somewhere this screen cannot see or cancel.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            // The URL identifies a payment in progress; a cached response could re-serve a
            // spent challenge page. Same reasoning as the wallet QR fetch (Slice 5).
            cacheMode = WebSettings.LOAD_NO_CACHE
            // The issuer's page is not designed for a phone unless we let it lay out for one.
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    }

    /**
     * The origin [content] will be loaded against — the address for a redirect, and the
     * iframe base for a fragment.
     *
     * A fragment's origin is a real one ([IFRAME_BASE_URL]) and cookies set against it are
     * this SDK's to clear, which is why it is recorded like any navigation.
     */
    fun originUrlOf(content: ThreeDsContent): String = when (content) {
        is ThreeDsContent.Iframe -> IFRAME_BASE_URL
        is ThreeDsContent.Url -> content.url
    }

    /**
     * Renders [content].
     *
     * [ThreeDsContent.Iframe] goes through `loadDataWithBaseURL` — **not** `loadUrl`, and
     * **not** `loadData`. `loadUrl` would need a URL we do not have (the payload is a
     * document, not an address) and rewriting the form's action as a GET drops the POST body.
     * `loadData` has no base URL, so the document gets an opaque origin and the ACS refuses
     * the submission.
     */
    fun load(webView: WebView, content: ThreeDsContent) {
        when (content) {
            is ThreeDsContent.Iframe -> webView.loadDataWithBaseURL(
                IFRAME_BASE_URL,
                wrapFragment(content.html),
                "text/html",
                "UTF-8",
                null,
            )
            is ThreeDsContent.Url -> webView.loadUrl(content.url)
        }
    }

    /**
     * Wraps a `redirect_iframe` fragment in a document and submits the form it contains.
     *
     * The fragment normally carries its own auto-submit script — the live sandbox one does.
     * Submitting again when it has already submitted is harmless (the navigation is already
     * under way), and it is what makes the step work at all when a fragment arrives without
     * one. The viewport meta is what stops the challenge rendering at desktop width.
     */
    fun wrapFragment(fragment: String): String =
        """
        <!doctype html>
        <html>
        <head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
        <body style="margin:0">
        $fragment
        <script>
        (function () {
          var submit = function () {
            var forms = document.getElementsByTagName('form');
            if (forms.length > 0) { forms[0].submit(); }
          };
          if (document.readyState === 'complete') { submit(); }
          else { window.addEventListener('load', submit); }
        })();
        </script>
        </body>
        </html>
        """.trimIndent()

    /**
     * Stops this WebView and destroys it. **Nothing here touches the cookie jar.**
     *
     * `loadUrl("about:blank")` before `destroy()` is not ceremony: a WebView destroyed
     * mid-navigation can leave its renderer running, and on a 3DS page that means a request
     * to an issuer continuing after the screen that authorised it is gone.
     *
     * This runs on every configuration change, which is the whole reason the 3DS session's
     * cookies are not cleared from it (B1). `clearCache(true)` stays: the WebView resource
     * cache is process-wide, but dropping it costs the host app a re-fetch and never a
     * session, and it is the one part of a challenge page's residue that has no per-origin
     * API. Cookies and DOM storage do have one — [ThreeDsBrowsingState].
     */
    fun teardown(webView: WebView) {
        webView.stopLoading()
        webView.loadUrl(ABOUT_BLANK)
        webView.clearHistory()
        webView.clearCache(true)
        webView.destroy()
    }

    /**
     * How long a page may go on loading before the customer is offered Reload.
     *
     * Long enough that an ordinary issuer page on a slow connection never shows the action,
     * short enough that someone watching a blank page has not already given up. It only
     * reveals a button; nothing is reloaded on the customer's behalf.
     */
    const val STALL_MILLIS: Long = 10_000L

    private const val ABOUT_BLANK = "about:blank"
}

/**
 * Recognises the end of the browser step. **A signal, never an outcome** — see
 * [ThreeDsScreen].
 */
internal object ThreeDsReturnUrl {

    /**
     * True when [url] means the issuer's part is over.
     *
     * Two rules, and the first is the one that decides.
     *
     * 1. **The configured return URL**, matched by prefix. Blank entries are ignored — an
     *    empty prefix matches everything, which would end the step on the very first
     *    navigation.
     * 2. **A custom scheme**, as a fallback. A merchant return URL for a native app is
     *    typically `uqpaysample://payment`, which a WebView cannot load at all — left alone it
     *    produces a blank error page and a customer stranded on a dead screen. Recognising the
     *    shape needs no configuration to be right, which matters when a merchant creates the
     *    intent without a `return_url` at all.
     *
     * ### The schemes this must *not* claim
     *
     * Rule 2 used to read "any non-`http(s)` scheme", and that is too broad by a long way. An
     * access control server is an ordinary web page: it can carry a `tel:` link to the bank's
     * support line, a `mailto:`, or — in app-to-app authentication, which is now the common
     * case — an `intent://` or bank-app deep link that is *part of* the challenge. Treating
     * any of those as the return URL ends the browser step and destroys the WebView while the
     * customer is mid-authentication, and the payment then runs out to `PENDING` because the
     * challenge can never be completed.
     *
     * So the well-known device-handler and browser-internal schemes are excluded by name.
     * The list is a *denylist* rather than an allowlist on purpose: a merchant's return scheme
     * is arbitrary (`myshop-checkout://`, `com.acme.app://`) and cannot be enumerated, while
     * the schemes a page uses to reach the device are few, stable and well known.
     */
    fun isEndOfBrowserStep(url: String, prefixes: List<String>): Boolean {
        if (url.isBlank()) return false
        if (prefixes.any { it.isNotBlank() && url.startsWith(it) }) return true
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme.isEmpty()) return false
        return scheme !in WEB_SCHEMES && scheme !in DEVICE_HANDLER_SCHEMES
    }

    /**
     * True when [url] addresses the device or another app — a phone dialler, a mail client,
     * a banking app — rather than a page to render.
     *
     * The WebView cannot load any of these: left to try, it replaces the challenge with an
     * `ERR_UNKNOWN_URL_SCHEME` error page, which loses the authentication just as surely as
     * tearing the view down. The client consumes them instead, so the challenge stays exactly
     * where it was and an accidental tap costs nothing.
     *
     * **They are not launched**, and that is a deliberate limitation rather than an
     * oversight: firing an arbitrary `intent://` from a page this SDK did not write, inside a
     * merchant's process, is an outbound action on behalf of an untrusted origin. App-to-app
     * authentication therefore does not complete in the bank's app today; issuers fall back to
     * an in-page challenge, and the engine's poll settles the payment either way.
     */
    fun isDeviceHandlerUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return url.substringBefore(':', missingDelimiterValue = "").lowercase() in DEVICE_HANDLER_SCHEMES
    }

    /** Schemes the WebView loads as content. Never the end of anything. */
    private val WEB_SCHEMES: Set<String> = setOf("http", "https", "about", "data", "blob", "javascript", "file", "content")

    /**
     * Schemes that hand off to the device or another app rather than navigating.
     *
     * `intent` and `android-app` are the two that matter for money: they are how an issuer
     * moves a customer into their banking app for app-to-app 3-D Secure. Ending the step there
     * is precisely backwards — the authentication is *starting*, not finishing.
     */
    private val DEVICE_HANDLER_SCHEMES: Set<String> = setOf(
        "tel", "callto", "sms", "smsto", "mms", "mailto", "geo", "maps",
        "market", "intent", "android-app", "wtai", "bip", "whatsapp",
    )
}

/**
 * Watches navigation for the end of the browser step, drives the progress bar, records which
 * origins the step touched, reports a page that failed to load, and survives a renderer crash.
 *
 * `shouldOverrideUrlLoading` returns true for a return URL, which both stops the WebView
 * trying to load a scheme it cannot handle and prevents the customer seeing the merchant's
 * own deep link render as an error page.
 *
 * [onVisited] is reported from three places on purpose. The host that sets the ACS session
 * cookie is frequently not the one in the address bar — the fingerprint step frames one
 * origin and posts to another — so page-level callbacks alone would miss it, and a host whose
 * cookie was never recorded is a host whose cookie is never cleared. `onLoadResource` catches
 * the sub-resources; the two page callbacks catch the navigations. Every one of them is
 * filtered by [ThreeDsBrowsingState] down to `http(s)` origins.
 *
 * Internal rather than private so the tests can drive these callbacks the way the framework
 * does; [ThreeDsScreen] is the only production caller.
 */
@VisibleForTesting
internal class ThreeDsWebViewClient(
    private val returnUrlPrefixes: List<String>,
    private val onStarted: () -> Unit,
    private val onFinished: () -> Unit,
    private val onVisited: (String) -> Unit,
    private val onReachedReturnUrl: () -> Unit,
    private val onMainFrameLoadFailed: () -> Unit,
    private val onSubFrameLoadFailed: () -> Unit,
    private val onRendererGone: () -> Unit,
) : WebViewClient() {

    /**
     * Decides what a navigation means. Three answers, and only one of them ends the payment
     * step.
     *
     * 1. **A device-handler URL** (`tel:`, `mailto:`, `intent:` …) is consumed and dropped.
     *    See [ThreeDsReturnUrl.isDeviceHandlerUrl].
     * 2. **A return URL in the main frame** is the end of the browser step.
     * 3. **Anything else** — including a return URL reached in a *sub-frame* — is left to the
     *    WebView, or consumed if it cannot be loaded.
     *
     * The main-frame test is what makes rule 2 safe. `shouldOverrideUrlLoading` fires for
     * every frame, and an access control server routinely loads things in hidden iframes — a
     * device-fingerprint form, an analytics beacon, occasionally the merchant's own return
     * page. A sub-frame reaching the return URL is not the customer finishing the challenge,
     * and acting on it destroys a WebView the customer is still typing an OTP into. The
     * outcome is re-read from the API regardless, so the cost of *missing* an end-of-step
     * signal is one poll interval; the cost of acting on a false one is the whole payment.
     */
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString().orEmpty()
        if (ThreeDsReturnUrl.isDeviceHandlerUrl(url)) return true
        if (!ThreeDsReturnUrl.isEndOfBrowserStep(url, returnUrlPrefixes)) {
            onVisited(url)
            return false
        }
        // A return URL that a sub-frame reached: consumed (the WebView could not load a custom
        // scheme anyway) but emphatically not reported as the end of the step.
        if (request?.isForMainFrame == false) return true
        onReachedReturnUrl()
        return true
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        onVisited(url.orEmpty())
        onStarted()
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        onFinished()
    }

    override fun onLoadResource(view: WebView?, url: String?) {
        onVisited(url.orEmpty())
    }

    /**
     * A resource failed to load at the network level — no connection, DNS, a timeout.
     *
     * Only a **main-frame** failure replaces the page. An access control server loads
     * fingerprint iframes, beacons and fonts, any of which can fail without the customer
     * noticing or the challenge caring; replacing a working page because a tracking pixel
     * timed out would create the very dead end this exists to remove. A sub-frame failure
     * is reported separately, only when it is connectivity-shaped ([CONNECTIVITY_ERRORS]),
     * and all it does is offer the customer a Reload: it is how a lost 3DS-method iframe
     * looks, and that one does stall the whole step.
     *
     * A main-frame failure on a URL the WebView was never meant to render is not a failure.
     * A return URL that got this far — a server redirect the WebView followed without asking
     * `shouldOverrideUrlLoading` — is the end of the browser step, as it would have been
     * there. A device-handler URL is dropped, as it is there.
     *
     * `onReceivedHttpError` is deliberately **not** overridden. A 4xx/5xx from the issuer
     * still renders the issuer's own body — often the page that explains a failed
     * authentication and then redirects to the return URL — and covering it with our panel
     * would hide the real end of the step.
     *
     * The error's description is not read, logged or shown: it names the ACS host.
     */
    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        onLoadError(
            url = request?.url?.toString().orEmpty(),
            mainFrame = request?.isForMainFrame == true,
            errorCode = error?.errorCode ?: WebViewClient.ERROR_UNKNOWN,
        )
    }

    /**
     * [onReceivedError] with the framework types taken apart — `WebResourceError` has no
     * public constructor, so a test cannot hand one in.
     */
    fun onLoadError(url: String, mainFrame: Boolean, errorCode: Int) {
        if (!mainFrame) {
            if (errorCode in CONNECTIVITY_ERRORS) onSubFrameLoadFailed()
            return
        }
        if (ThreeDsReturnUrl.isDeviceHandlerUrl(url)) return
        if (ThreeDsReturnUrl.isEndOfBrowserStep(url, returnUrlPrefixes)) {
            onReachedReturnUrl()
            return
        }
        onMainFrameLoadFailed()
    }

    /**
     * The WebView's renderer process died — crashed, or was killed by the system to reclaim
     * memory while the app was in the background (M-render).
     *
     * **Returning `true` is the entire point.** The default is `false`, which tells the
     * framework the app cannot continue, and Android then kills the *merchant's whole
     * process*: a renderer OOM during a card payment becomes a crash in someone else's app,
     * with the payment in flight and no result delivered. Returning true keeps the process
     * alive; the screen moves to its interrupted state, the WebView is released by the
     * recomposition that follows — the one place it is destroyed, so it is never destroyed
     * twice — and the engine's poller, which was always the thing that decides the outcome,
     * carries on and settles the payment.
     *
     * `detail.didCrash()` is not consulted: a crashed renderer and a reclaimed one leave the
     * customer in exactly the same place.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        onRendererGone()
        return true
    }

    private companion object {
        /**
         * The errors that mean "the network", as opposed to "this one resource": a blocked
         * scheme, a bad URL or a failed TLS handshake on a beacon says nothing about whether
         * the customer can finish the challenge.
         */
        val CONNECTIVITY_ERRORS: Set<Int> = setOf(
            WebViewClient.ERROR_HOST_LOOKUP,
            WebViewClient.ERROR_CONNECT,
            WebViewClient.ERROR_TIMEOUT,
            WebViewClient.ERROR_IO,
        )
    }
}
