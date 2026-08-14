package com.uqpay.sdk.network

import com.uqpay.sdk.auth.UQPayAuthToken
import com.uqpay.sdk.auth.UQPayTokenProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Caches the merchant's short-lived access token and refreshes it when needed.
 *
 * UQPAY permits only **one active access token per merchant** — minting a new one
 * invalidates the previous one — so tokens are shared, not per-call. This class exists
 * to make sure the SDK asks the host app for one as rarely as possible: it holds the
 * token until it nears expiry, and serialises refreshes behind a mutex so a burst of
 * concurrent requests triggers exactly one fetch rather than a stampede.
 *
 * @property refreshMarginMillis how far before expiry a token is treated as stale. A
 *   token that expires mid-flight fails a payment, so we refresh early.
 */
internal class TokenManager(
    private val provider: UQPayTokenProvider,
    private val workContext: CoroutineContext,
    private val refreshMarginMillis: Long = 120_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: UQPayAuthToken? = null

    /**
     * Returns a usable token value.
     *
     * @param forceRefresh discard the cached token first. Set after a `401`: the token
     *   may have been invalidated by another device minting a new one.
     */
    suspend fun token(forceRefresh: Boolean = false): String {
        if (forceRefresh) invalidate()

        usableToken()?.let { return it.value }

        return mutex.withLock {
            // Another coroutine may have refreshed while we waited for the lock.
            usableToken()?.let { return@withLock it.value }

            val fetched = fetch()
            cached = fetched
            fetched.value
        }
    }

    /** Drops the cached token so the next call fetches a fresh one. */
    fun invalidate() {
        cached = null
    }

    private fun usableToken(): UQPayAuthToken? =
        cached?.takeIf { it.value.isNotBlank() && now() < it.expiresAtEpochMillis - refreshMarginMillis }

    private suspend fun fetch(): UQPayAuthToken = withContext(workContext) {
        val token = try {
            provider.fetchToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The host app's provider failed. Its message may name internal systems, so
            // it is kept as the cause and never surfaced to the customer.
            throw UQPayApiException.AuthenticationFailed(
                message = "Could not obtain a UQPAY access token from the app's token provider.",
                cause = e,
            )
        }

        if (token.value.isBlank()) {
            throw UQPayApiException.AuthenticationFailed(
                message = "The app's token provider returned an empty access token.",
            )
        }
        token
    }
}
