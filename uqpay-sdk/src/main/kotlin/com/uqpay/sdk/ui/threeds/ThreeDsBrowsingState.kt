package com.uqpay.sdk.ui.threeds

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.annotation.VisibleForTesting

/**
 * The browsing state one payment's 3-D Secure step created, and the only thing allowed to
 * delete it.
 *
 * ### Why this is not a `DisposableEffect`
 *
 * The obvious place to clear a WebView's cookies is where the WebView goes away, and that is
 * exactly what makes it wrong here. On Android the WebView goes away on **every configuration
 * change** — a rotation, a font-scale change, a fold — and the ACS sets a session cookie
 * between the fingerprint step and the challenge that the challenge cannot be completed
 * without. Clearing on disposal therefore deletes the customer's authentication in the middle
 * of them authenticating: the recreated screen re-loads the same challenge with no session,
 * the issuer rejects it, and the payment runs the poller out to `PENDING` for a card that was
 * one tap from succeeding. The cookie's lifetime is the **payment's**, not the composition's,
 * so the clear is driven from where the payment ends — `UQPayPaymentActivity.onDestroy`,
 * under the same `isFinishing && !isChangingConfigurations` predicate that decides whether to
 * release the [com.uqpay.sdk.engine.PaymentSession]. One predicate, one lifetime, both.
 *
 * ### Why the clear is scoped to hosts instead of `removeAllCookies`
 *
 * `CookieManager` and `WebStorage` are **process-global**: they are shared with every other
 * WebView in the merchant's app. `removeAllCookies(null)` inside a payment SDK signs the
 * host app's own users out of the host app's own web views, silently, on every card payment —
 * a payment SDK has no business deleting data it did not create. So this records the origins
 * the 3DS step actually visited and expires only the cookies those origins set. iOS gets the
 * same property for free from `WKWebsiteDataStore.nonPersistent()`; Android has no
 * per-WebView store, so it is produced by hand.
 *
 * ### What this deliberately does not reach
 *
 * A cookie the ACS set on a **parent** domain (`Domain=.example.com` while on
 * `acs.example.com`) is not expired: the parent domain can be shared with pages the merchant
 * owns, and deleting there would be the same overreach in a smaller shape. Likewise
 * `WebStorage` offers no reliable per-origin delete for modern DOM storage —
 * [WebStorage.deleteOrigin] covers the legacy stores only — and `deleteAllData()` is exactly
 * the global wipe this class exists to stop, so it is never called. The residue in both cases
 * is bounded and belongs to a spent challenge; the alternative is certain damage to the host
 * app on every payment.
 *
 * Keyed by payment intent id so two payments in one process cannot clear each other's
 * session, which a single shared set would do.
 */
internal object ThreeDsBrowsingState {

    /**
     * A challenge that navigated more than this many distinct pages is not a 3DS flow any
     * more; the cap stops a runaway page growing the set without bound. Deep enough that no
     * real redirect chain reaches it.
     */
    @VisibleForTesting
    internal const val MAX_URLS_PER_SESSION: Int = 128

    private val lock = Any()

    /** Payment intent id → the normalised URLs that payment's 3DS step touched. */
    private val visited = mutableMapOf<String, LinkedHashSet<String>>()

    /**
     * Notes that the 3DS step for [sessionKey] touched [url].
     *
     * Called for every navigation *and* every sub-resource, because the host that sets the
     * session cookie is often not the host in the address bar — the fingerprint step frames
     * one origin and posts to another. Anything that is not `http(s)` is ignored: a custom
     * scheme is the merchant's own return deep link, which has no cookie jar.
     */
    fun record(sessionKey: String, url: String) {
        if (sessionKey.isBlank()) return
        val normalised = ThreeDsUrls.normalise(url) ?: return
        synchronized(lock) {
            val urls = visited.getOrPut(sessionKey) { LinkedHashSet() }
            if (urls.size < MAX_URLS_PER_SESSION || normalised in urls) urls += normalised
        }
    }

    /** The URLs recorded for [sessionKey], oldest first. Diagnostics and tests. */
    @VisibleForTesting
    internal fun visitedUrls(sessionKey: String): List<String> =
        synchronized(lock) { visited[sessionKey]?.toList().orEmpty() }

    /**
     * The payment for [sessionKey] is over: expire every cookie its 3DS step's origins set,
     * and forget them.
     *
     * Never call this on a configuration change — see the class KDoc. Clearing an unknown key
     * is a no-op, so the payment-over path can call it unconditionally.
     *
     * Every WebView call is guarded: `CookieManager.getInstance()` throws on a device whose
     * WebView package is missing or mid-update, and a payment that has already produced its
     * result must not be turned into a crash in the merchant's app by its own cleanup.
     */
    fun clear(sessionKey: String) {
        val urls = synchronized(lock) { visited.remove(sessionKey) }.orEmpty()
        if (urls.isEmpty()) return
        val cookies = runCatching { CookieManager.getInstance() }.getOrNull()
        if (cookies != null) {
            for (url in urls) {
                val header = runCatching { cookies.getCookie(url) }.getOrNull() ?: continue
                for (name in ThreeDsCookies.namesIn(header)) {
                    for (value in ThreeDsCookies.deletionValues(name, url)) {
                        runCatching { cookies.setCookie(url, value) }
                    }
                }
            }
            runCatching { cookies.flush() }
        }
        val storage = runCatching { WebStorage.getInstance() }.getOrNull()
        if (storage != null) {
            for (origin in urls.mapNotNull { ThreeDsUrls.storageOrigin(it) }.distinct()) {
                runCatching { storage.deleteOrigin(origin) }
            }
        }
    }

    /** Forgets every session without touching the cookie jar. Tests only. */
    @VisibleForTesting
    internal fun forgetAllForTest() {
        synchronized(lock) { visited.clear() }
    }
}

/**
 * URL shapes the 3DS clear needs. Pure string work, so every rule is assertable without a
 * WebView.
 */
internal object ThreeDsUrls {

    /**
     * `scheme://host[:port]/path`, lowercased, with the query and fragment dropped.
     *
     * The query is dropped for two reasons. A challenge page that polls with a changing query
     * string is one page for cookie purposes, and keeping each variant would fill the set with
     * duplicates of one origin. And an ACS puts session identifiers in its query — this class
     * holds what it records for the length of the payment, and there is no reason for it to
     * hold that. Returns null for anything that is not `http(s)` with a host: a custom-scheme
     * return URL is the merchant's own deep link and has no cookie jar.
     */
    fun normalise(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()
        if (host.isNullOrBlank()) return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val path = uri.path.orEmpty().ifEmpty { "/" }
        return "$scheme://$host$port$path"
    }

    /** The host of a [normalise]d URL, for a `Domain=` attribute. */
    fun hostOf(normalisedUrl: String): String =
        normalisedUrl.substringAfter("://").substringBefore('/').substringBefore(':')

    /**
     * The origin in the shape `WebStorage.Origin.getOrigin()` uses — scheme, host and an
     * explicit port, even the default one.
     */
    fun storageOrigin(normalisedUrl: String): String? {
        val scheme = normalisedUrl.substringBefore("://").takeIf { it.isNotBlank() } ?: return null
        val authority = normalisedUrl.substringAfter("://").substringBefore('/')
        if (authority.isBlank()) return null
        if (':' in authority) return "$scheme://$authority"
        val defaultPort = if (scheme == "https") 443 else 80
        return "$scheme://$authority:$defaultPort"
    }

    /**
     * Every path a cookie seen at [normalisedUrl] could have been scoped to: the root, each
     * ancestor directory, and the path itself.
     *
     * `CookieManager.getCookie` returns a cookie's name and value but never its `Path`, and a
     * deletion cookie only replaces one whose path matches. Walking the ancestors is how a
     * cookie set with `Path=/challenge` is reached from a URL of `/challenge/abc/step2`.
     */
    fun pathCandidates(normalisedUrl: String): List<String> {
        val path = "/" + normalisedUrl.substringAfter("://").substringAfter('/', "").trimStart('/')
        val segments = path.split('/').filter { it.isNotBlank() }.take(MAX_PATH_DEPTH)
        val candidates = mutableListOf("/")
        var current = ""
        for (segment in segments) {
            current += "/$segment"
            candidates += current
        }
        return candidates
    }

    private const val MAX_PATH_DEPTH = 6
}

/** Reading a `Cookie:` header and writing the `Set-Cookie` values that delete what it named. */
internal object ThreeDsCookies {

    /**
     * The cookie names in a `CookieManager.getCookie` result (`"a=1; b=2"`).
     *
     * An entry with no `=` is not a cookie and is skipped rather than treated as a name — an
     * empty or malformed header must produce no writes at all, not a write for `""`.
     */
    fun namesIn(header: String?): List<String> =
        header.orEmpty()
            .split(';')
            .filter { '=' in it }
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotBlank() }
            .distinct()

    /**
     * The `Set-Cookie` values that delete [name] for [normalisedUrl].
     *
     * `CookieManager` has no "remove this cookie" call. The only way to delete one is to set
     * it again, expired — and a cookie is only replaced by one whose name, domain **and** path
     * all match, none of which `getCookie` reveals. So each candidate path gets four writes,
     * and each one is doing a different job:
     *
     * - **Blank first, expire second.** The secret is the *value*, not the name. The unexpired
     *   write lands unconditionally and leaves the session token gone even on a WebView that
     *   ignores the expiry that follows; the expiring write then removes the cookie itself.
     *   Ordered this way round because the reverse leaves the token in place whenever the
     *   delete is the write that fails.
     * - **Host-only and `Domain=`.** A cookie set with an explicit `Domain` for this host is a
     *   different cookie from a host-only one of the same name, and only its own shape
     *   replaces it.
     *
     * Both `Max-Age=0` and a 1970 `Expires` are sent — the first is what modern WebViews
     * honour, the second is what older ones do.
     */
    fun deletionValues(name: String, normalisedUrl: String): List<String> {
        val host = ThreeDsUrls.hostOf(normalisedUrl)
        return ThreeDsUrls.pathCandidates(normalisedUrl).flatMap { path ->
            listOf(
                "$name=; Path=$path",
                "$name=; Path=$path; Max-Age=0; Expires=$EPOCH",
                "$name=; Path=$path; Domain=$host",
                "$name=; Path=$path; Domain=$host; Max-Age=0; Expires=$EPOCH",
            )
        }
    }

    private const val EPOCH = "Thu, 01 Jan 1970 00:00:00 GMT"
}
