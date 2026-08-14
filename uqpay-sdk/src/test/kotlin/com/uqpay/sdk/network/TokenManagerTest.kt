package com.uqpay.sdk.network

import com.uqpay.sdk.auth.UQPayAuthToken
import com.uqpay.sdk.auth.UQPayTokenProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Token caching (ios-requirements / plan G26).
 *
 * UQPAY permits **one active access token per merchant** — minting a new one invalidates
 * the previous one — so the SDK must ask the host app as rarely as it can, and a burst of
 * concurrent requests must trigger exactly one fetch rather than a stampede that signs
 * every other device out.
 *
 * Time is injected, never slept.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenManagerTest {

    private class FakeTokenProvider(
        private val tokens: () -> UQPayAuthToken,
    ) : UQPayTokenProvider {
        val calls = AtomicInteger()

        override fun fetchToken(): UQPayAuthToken {
            calls.incrementAndGet()
            return tokens()
        }
    }

    private var now = 1_000_000L

    private fun manager(
        provider: UQPayTokenProvider,
        refreshMarginMillis: Long = 120_000L,
    ) = TokenManager(
        provider = provider,
        workContext = UnconfinedTestDispatcher(),
        refreshMarginMillis = refreshMarginMillis,
        now = { now },
    )

    private fun token(value: String, expiresInMillis: Long) =
        UQPayAuthToken(value, now + expiresInMillis)

    @Test
    fun `a valid token is fetched once and reused`() = runTest {
        val provider = FakeTokenProvider { token("tok-1", expiresInMillis = 30 * 60_000L) }
        val tokenManager = manager(provider)

        assertEquals("tok-1", tokenManager.token())
        assertEquals("tok-1", tokenManager.token())
        assertEquals("tok-1", tokenManager.token())

        assertEquals(1, provider.calls.get())
    }

    @Test
    fun `a token inside the refresh margin is replaced before it can expire mid-payment`() =
        runTest {
            var issued = 0
            val provider = FakeTokenProvider {
                issued++
                // Always about to expire: inside the 120s margin.
                token("tok-$issued", expiresInMillis = 60_000L)
            }
            val tokenManager = manager(provider)

            assertEquals("tok-1", tokenManager.token())
            assertEquals("tok-2", tokenManager.token())
            assertEquals(2, provider.calls.get())
        }

    @Test
    fun `a token is refreshed once the clock reaches the margin`() = runTest {
        var issued = 0
        val provider = FakeTokenProvider {
            issued++
            token("tok-$issued", expiresInMillis = 10 * 60_000L)
        }
        val tokenManager = manager(provider)

        assertEquals("tok-1", tokenManager.token())

        now += 7 * 60_000L // still 3 minutes of life, outside the 2-minute margin
        assertEquals("tok-1", tokenManager.token())
        assertEquals(1, provider.calls.get())

        now += 2 * 60_000L // now inside the margin
        assertEquals("tok-2", tokenManager.token())
        assertEquals(2, provider.calls.get())
    }

    @Test
    fun `a token exactly at the margin boundary is treated as stale`() = runTest {
        val provider = FakeTokenProvider { token("tok", expiresInMillis = 120_000L) }
        val tokenManager = manager(provider)

        tokenManager.token()
        tokenManager.token()

        // now == expiresAt - margin: refresh rather than gamble on a token expiring
        // between here and the gateway.
        assertEquals(2, provider.calls.get())
    }

    @Test
    fun `forceRefresh discards a token that is still valid`() = runTest {
        var issued = 0
        val provider = FakeTokenProvider {
            issued++
            token("tok-$issued", expiresInMillis = 30 * 60_000L)
        }
        val tokenManager = manager(provider)

        assertEquals("tok-1", tokenManager.token())
        assertEquals("tok-2", tokenManager.token(forceRefresh = true))
        assertEquals("tok-2", tokenManager.token())
        assertEquals(2, provider.calls.get())
    }

    @Test
    fun `invalidate drops the cached token`() = runTest {
        var issued = 0
        val provider = FakeTokenProvider {
            issued++
            token("tok-$issued", expiresInMillis = 30 * 60_000L)
        }
        val tokenManager = manager(provider)

        tokenManager.token()
        tokenManager.invalidate()
        assertEquals("tok-2", tokenManager.token())
    }

    @Test
    fun `a provider that throws surfaces as an authentication failure`() = runTest {
        val provider = FakeTokenProvider { throw IOException("boom") }

        val thrown = runCatching { manager(provider).token() }.exceptionOrNull()

        assertTrue(thrown is UQPayApiException.AuthenticationFailed)
    }

    @Test
    fun `the provider's own message is kept as the cause and never leaked publicly`() = runTest {
        // The host app's message may name internal systems, hosts, or customers.
        val cause = IllegalStateException("auth-svc-3.internal refused user 4176660000000027")
        val provider = FakeTokenProvider { throw cause }

        val thrown = runCatching { manager(provider).token() }.exceptionOrNull()

        assertSame(cause, thrown?.cause)
        val message = thrown?.message.orEmpty()
        assertFalse(message.contains("auth-svc-3.internal"))
        assertFalse(message.contains("4176660000000027"))
        assertEquals(
            "Could not obtain a UQPAY access token from the app's token provider.",
            message,
        )
    }

    @Test
    fun `a blank token is rejected rather than sent to the gateway`() = runTest {
        listOf("", "   ").forEach { value ->
            val provider = FakeTokenProvider { token(value, expiresInMillis = 30 * 60_000L) }

            val thrown = runCatching { manager(provider).token() }.exceptionOrNull()

            assertTrue("value=<$value>", thrown is UQPayApiException.AuthenticationFailed)
            assertEquals(
                "The app's token provider returned an empty access token.",
                thrown?.message,
            )
        }
    }

    @Test
    fun `a failed fetch is not cached`() = runTest {
        var attempt = 0
        val provider = FakeTokenProvider {
            attempt++
            if (attempt == 1) throw IOException("transient") else token("tok-2", 30 * 60_000L)
        }
        val tokenManager = manager(provider)

        runCatching { tokenManager.token() }
        assertEquals("tok-2", tokenManager.token())
    }

    @Test
    fun `a cancelled fetch propagates as a cancellation, not an auth failure`() = runTest {
        val provider = FakeTokenProvider { throw CancellationException("screen closed") }

        val thrown = runCatching { manager(provider).token() }.exceptionOrNull()

        assertTrue("got $thrown", thrown is CancellationException)
    }

    @Test
    fun `a burst of concurrent callers triggers exactly one fetch`() = runTest {
        val provider = FakeTokenProvider { token("tok-1", expiresInMillis = 30 * 60_000L) }
        val tokenManager = manager(provider)

        val values = (1..32).map { async { tokenManager.token() } }.awaitAll()

        assertEquals(List(32) { "tok-1" }, values)
        assertEquals("the stampede guard let a second fetch through", 1, provider.calls.get())
    }

    @Test
    fun `the stampede guard holds across real threads`() = runBlocking {
        // The virtual-time test above cannot interleave two threads inside the mutex.
        repeat(20) {
            val provider = FakeTokenProvider {
                Thread.yield()
                UQPayAuthToken("tok-1", System.currentTimeMillis() + 30 * 60_000L)
            }
            val tokenManager = TokenManager(
                provider = provider,
                workContext = Dispatchers.Default,
                refreshMarginMillis = 120_000L,
            )

            (1..64).map { async(Dispatchers.Default) { tokenManager.token() } }.awaitAll()

            assertEquals(1, provider.calls.get())
        }
    }
}
