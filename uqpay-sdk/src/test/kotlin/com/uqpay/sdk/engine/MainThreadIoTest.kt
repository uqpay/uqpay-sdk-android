package com.uqpay.sdk.engine

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.uqpay.sdk.Environment
import com.uqpay.sdk.UQPay
import com.uqpay.sdk.UQPayConfiguration
import com.uqpay.sdk.auth.UQPayAuthToken
import com.uqpay.sdk.auth.UQPayTokenProvider
import com.uqpay.sdk.network.HttpMethod
import com.uqpay.sdk.network.UQPayNetworkClient
import com.uqpay.sdk.network.UQPayRequest
import com.uqpay.sdk.network.UQPayResponse
import com.uqpay.sdk.payment.PaymentStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * AC §10.3 — **the payment paths never touch disk or network on the main thread.**
 *
 * ### Why this is not a StrictMode test
 *
 * `StrictMode` is enforced by the ART runtime and the framework's own I/O primitives.
 * Robolectric implements neither, so `penaltyDeath()` under Robolectric detects exactly
 * nothing and a green StrictMode test here would be a green light with no bulb in it. This
 * test instead **instruments the three I/O surfaces the SDK actually has** and records the
 * thread each was touched from, while a whole payment runs on real threads:
 *
 * 1. the pin store — a file in `getNoBackupFilesDir()`, written synchronously and `fsync`ed,
 *    plus the one-shot deletion of the `SharedPreferences` file it replaced;
 * 2. the API client — every request, through the injected socket;
 * 3. [DeviceInfo] — `Settings.Secure` via the `ContentResolver`, and display metrics via
 *    `Resources`.
 *
 * ### Why real threads
 *
 * Every other session-level test runs the engine on a `StandardTestDispatcher`, whose
 * scheduler runs coroutine bodies on the **test** thread — which under Robolectric *is* the
 * main thread. Such a test cannot tell correct threading from incorrect. So this one gives
 * the session a genuine background dispatcher and waits for real completion.
 *
 * A test that recorded no touches at all would also pass, so each surface is asserted to
 * have been exercised.
 *
 * No real card number, key or API secret appears in this file.
 */
@RunWith(RobolectricTestRunner::class)
class MainThreadIoTest {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "uqpay-io-test") }
    private val io = executor.asCoroutineDispatcher()

    /** Every I/O touch, as `site` → whether it happened on the main looper. */
    private val touches = ConcurrentLinkedQueue<Touch>()

    private lateinit var context: RecordingContext

    @Before
    fun setUp() {
        PaymentSession.clearAllForTest()
        context = RecordingContext(ApplicationProvider.getApplicationContext()) { site ->
            touches += Touch(site, Looper.myLooper() === Looper.getMainLooper())
        }
        UQPay.initialize(context, configuration())
    }

    @After
    fun tearDown() {
        PaymentSession.clearAllForTest()
        UQPay.resetForTest()
        io.close()
        executor.shutdownNow()
    }

    @Test
    fun `a whole payment touches disk and network, and never on the main thread`() {
        val net = RecordingNetworkClient { touches += Touch("network", Looper.myLooper() === Looper.getMainLooper()) }
        val session = PaymentSession.obtain(
            INTENT,
            SessionDependencies(networkClient = net, workContext = io, wallClock = { FIXED_NOW }),
        )

        // Everything below is called from the test (main) thread, exactly as the ViewModel
        // calls it from the main thread. Nothing it starts may do I/O there.
        session.startIfNeeded(Presentation.MethodList)
        awaitState("SelectingMethod") { session.state.value is EngineState.SelectingMethod }

        session.engine.confirm(cardPayload())
        awaitState("Terminal") { session.state.value is EngineState.Terminal }

        assertEquals(PaymentStatus.SUCCEEDED, (session.state.value as EngineState.Terminal).result.status)

        val sites = touches.map { it.site }.toSet()
        assertTrue("the pin store must have been exercised, or this test proves nothing", "noBackupFilesDir" in sites)
        assertTrue("the legacy store cleanup must have been exercised", "deleteSharedPreferences" in sites)
        assertTrue("DeviceInfo's ANDROID_ID read must have been exercised", "contentResolver" in sites)
        assertTrue("the socket must have been exercised", "network" in sites)

        val onMain = touches.filter { it.onMainThread }.map { it.site }.distinct()
        assertEquals("I/O on the main thread: $onMain", emptyList<String>(), onMain)
    }

    /**
     * The production graph's own default. Every engine coroutine, the token fetch and the
     * socket all inherit this one context, so pinning it here is what makes the test above
     * a statement about production rather than about its own wiring.
     */
    @Test
    fun `the production dependency graph runs on the IO dispatcher`() {
        assertSame(Dispatchers.IO, SessionDependencies.production().workContext)
    }

    // ---- harness -----------------------------------------------------------------------

    private data class Touch(val site: String, val onMainThread: Boolean)

    private fun awaitState(what: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /**
     * An application context that reports every I/O-bearing surface the SDK asks it for.
     * `getApplicationContext` returns `this` so that `UQPay.initialize`'s retention of the
     * application context keeps the instrumentation in place.
     */
    private class RecordingContext(
        base: Context,
        private val onTouch: (String) -> Unit,
    ) : ContextWrapper(base) {

        override fun getApplicationContext(): Context = this

        /**
         * The pin store's directory. Resolving it *creates* it when absent, so this is a real
         * disk touch and not merely a path lookup — which is what makes it the right surface
         * to instrument now that the store writes a file rather than `SharedPreferences`.
         */
        override fun getNoBackupFilesDir(): File {
            onTouch("noBackupFilesDir")
            return super.getNoBackupFilesDir()
        }

        /** The store's one-shot cleanup of the pre-`no_backup` preferences file. */
        override fun deleteSharedPreferences(name: String?): Boolean {
            onTouch("deleteSharedPreferences")
            return super.deleteSharedPreferences(name)
        }

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            onTouch("sharedPreferences")
            return super.getSharedPreferences(name, mode)
        }

        override fun getContentResolver(): ContentResolver {
            onTouch("contentResolver")
            return super.getContentResolver()
        }

        override fun getResources(): Resources {
            onTouch("resources")
            return super.getResources()
        }
    }

    private class RecordingNetworkClient(private val onCall: () -> Unit) : UQPayNetworkClient {
        override suspend fun execute(request: UQPayRequest): UQPayResponse {
            onCall()
            val id = request.url.substringAfterLast('/')
            return when (request.method) {
                HttpMethod.GET -> UQPayResponse(200, intentJson(id, "REQUIRES_PAYMENT_METHOD"), "trace-get", null)
                HttpMethod.POST -> UQPayResponse(200, intentJson(INTENT, "SUCCEEDED"), "trace-post", null)
            }
        }
    }

    private fun configuration() = UQPayConfiguration(
        clientId = CLIENT_ID,
        environment = Environment.SANDBOX,
        tokenProvider = UQPayTokenProvider { UQPayAuthToken(TOKEN, System.currentTimeMillis() + 30 * 60_000L) },
    )

    private companion object {
        const val INTENT = "PI_main_thread_io"
        const val CLIENT_ID = "client-test"
        const val TOKEN = "tok-fixture"
        const val FIXED_NOW = 1_755_500_000_000L

        fun intentJson(id: String, status: String) = """
            {
              "payment_intent_id": "$id",
              "intent_status": "$status",
              "amount": "8.98",
              "currency": "SGD",
              "merchant_order_id": "order-1",
              "available_payment_method_types": ["card", "alipaycn"]
            }
        """.trimIndent()

        fun cardPayload() = ConfirmPayload.Card(
            paymentIntentId = INTENT,
            cardNumber = "4242424242424242",
            expiryMonth = "12",
            expiryYear = "2030",
            cvc = "123",
            cardholderName = "Test Card",
            network = "visa",
        )
    }
}
