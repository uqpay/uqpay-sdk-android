package com.uqpay.sdk.engine

import android.content.Context
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
import com.uqpay.sdk.network.baseUrl
import com.uqpay.sdk.payment.PaymentStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * The owner registry, exercised through the **real** composition root: every test below
 * builds the production graph — token manager, API client, error mapper, runner, shared
 * idempotency registry over real Robolectric preferences, poller, engine — with exactly one
 * fake, at the socket. A wiring mistake anywhere between `UQPay.initialize` and the engine's
 * `Terminal` fails here.
 *
 * No real card number, key or API secret appears in this file: the PAN below is the
 * documented test value, the token is a fixture string.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PaymentSessionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun initialize() {
        PaymentSession.clearAllForTest()
        UQPay.initialize(context, configuration())
    }

    @After
    fun tearDown() {
        PaymentSession.clearAllForTest()
        UQPay.resetForTest()
    }

    // ---- Identity: the registry --------------------------------------------------------

    @Test
    fun `obtain twice for one intent is the same session and the same engine`() = runTest {
        val deps = deps(ScriptedNetworkClient())
        val first = PaymentSession.obtain(INTENT_A, deps)
        val second = PaymentSession.obtain(INTENT_A, deps)
        assertSame(first, second)
        assertSame(first.engine, second.engine)
        assertEquals(1, PaymentSession.activeCount)
    }

    @Test
    fun `different intents are different sessions`() = runTest {
        val deps = deps(ScriptedNetworkClient())
        val a = PaymentSession.obtain(INTENT_A, deps)
        val b = PaymentSession.obtain(INTENT_B, deps)
        assertNotSame(a, b)
        assertNotSame(a.engine, b.engine)
        assertEquals(INTENT_A, a.engine.paymentIntentId)
        assertEquals(INTENT_B, b.engine.paymentIntentId)
        assertEquals(2, PaymentSession.activeCount)
    }

    @Test
    fun `peek never builds`() = runTest {
        assertEquals(null, PaymentSession.peek(INTENT_A))
        val s = PaymentSession.obtain(INTENT_A, deps(ScriptedNetworkClient()))
        assertSame(s, PaymentSession.peek(INTENT_A))
    }

    @Test
    fun `obtain before initialize is a programmer error, not a payment outcome`() {
        UQPay.resetForTest()
        try {
            PaymentSession.obtain(INTENT_A, SessionDependencies(networkClient = ScriptedNetworkClient()))
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertEquals(0, PaymentSession.activeCount)
    }

    // ---- Rotation: re-attach, never re-submit ------------------------------------------

    /**
     * The disaster this class exists for. The Activity is destroyed and recreated while a
     * confirm is in the air; the recreated Activity must find the same engine mid-flight, must
     * not load again (no second intent read), and must not be able to cause a second confirm.
     */
    @Test
    fun `rotation mid-confirm re-attaches to the same engine and does not load or confirm again`() = runTest {
        val net = ScriptedNetworkClient()
        val deps = deps(net)

        // First creation of the Activity.
        val session = PaymentSession.obtain(INTENT_A, deps)
        assertTrue(session.startIfNeeded())
        runCurrent()
        assertTrue("expected SelectingMethod, was ${session.state.value}", session.state.value is EngineState.SelectingMethod)
        assertEquals(1, net.gets)

        assertEquals(ConfirmAcceptance.STARTED, session.engine.confirm(cardPayload(INTENT_A)))
        runCurrent()
        assertTrue(session.engine.isConfirmInFlight)
        assertEquals("pre-confirm intercept re-reads the intent", 2, net.gets)
        assertEquals("exactly one confirm has been sent", 1, net.posts.size)
        val readsBeforeRotation = net.gets

        // Rotation: the Activity is recreated and does exactly what it did the first time.
        val recreated = PaymentSession.obtain(INTENT_A, deps)
        assertSame(session, recreated)
        assertSame(session.engine, recreated.engine)
        assertFalse("a recreated screen must not load again", recreated.startIfNeeded())
        runCurrent()
        assertTrue(recreated.hasStarted)
        assertEquals("no additional intent read on re-attach", readsBeforeRotation, net.gets)
        assertTrue("the recreated screen observes the in-flight state", recreated.state.value is EngineState.Confirming)
        assertEquals("still exactly one confirm in the air", 1, net.posts.size)
        assertFalse(net.posts[0].cancelled)

        // The one confirm answers; the one engine settles once.
        net.posts[0].answer(200, intentJson(INTENT_A, "SUCCEEDED"))
        runCurrent()
        val terminal = recreated.state.value
        assertTrue("expected Terminal, was $terminal", terminal is EngineState.Terminal)
        assertEquals(PaymentStatus.SUCCEEDED, (terminal as EngineState.Terminal).result.status)
        assertEquals(INTENT_A, terminal.result.paymentIntentId)
        assertEquals(1, net.posts.size)
    }

    /**
     * Process death is **not** this layer's job. The registry is in memory; after death a
     * relaunch must build a fresh engine that re-reads the intent — never a false re-attach to
     * an engine that no longer exists. What protects the money across death is the persisted
     * pin (WU-2.4), exercised in `ConfirmIdempotencyTest`, not anything here.
     */
    @Test
    fun `a fresh registry for the same intent builds a new engine - no false re-attach after death`() = runTest {
        val deps = deps(ScriptedNetworkClient())
        val before = PaymentSession.obtain(INTENT_A, deps)
        assertTrue(before.startIfNeeded())
        runCurrent()

        // Death: the process, and with it the registry, is gone.
        PaymentSession.clearAllForTest()
        UQPay.initialize(context, configuration())

        val after = PaymentSession.obtain(INTENT_A, deps)
        assertNotSame(before, after)
        assertNotSame(before.engine, after.engine)
        assertFalse(after.hasStarted)
        assertTrue(after.state.value is EngineState.Idle)
        assertTrue("the relaunched screen loads afresh", after.startIfNeeded())
    }

    // ---- The shared idempotency registry ---------------------------------------------

    /**
     * Two engines for one intent — a released session still replaying, and a fresh launch for
     * the same payment — must send the **same** idempotency key for the same payload. That is
     * only true if every session shares one `ConfirmIdempotency` (WU-2.4: construct once per
     * process); a registry per session would mint a second key and the gateway would see two
     * attempts.
     */
    @Test
    fun `a relaunch while an orphaned confirm is still in flight replays the same key`() = runTest {
        val net = ScriptedNetworkClient()
        val deps = deps(net)

        val first = PaymentSession.obtain(INTENT_A, deps)
        first.startIfNeeded()
        runCurrent()
        first.engine.confirm(cardPayload(INTENT_A))
        runCurrent()
        assertEquals(1, net.posts.size)
        val firstKey = net.posts[0].request.idempotencyKey

        // The customer is forced out mid-confirm: PENDING delivered, session released.
        first.engine.cancel()
        PaymentSession.release(INTENT_A)

        // Relaunched for the same intent, same details.
        val relaunched = PaymentSession.obtain(INTENT_A, deps)
        assertNotSame(first, relaunched)
        relaunched.startIfNeeded()
        runCurrent()
        relaunched.engine.confirm(cardPayload(INTENT_A))
        runCurrent()

        assertEquals(2, net.posts.size)
        assertEquals("same payload, same pin, same key — a replay, not a second charge", firstKey, net.posts[1].request.idempotencyKey)
        assertEquals("byte-identical body on replay", net.posts[0].request.body, net.posts[1].request.body)
    }

    @Test
    fun `every session in the process shares one ConfirmIdempotency`() = runTest {
        val deps = deps(ScriptedNetworkClient())
        val a = PaymentSession.obtain(INTENT_A, deps)
        val b = PaymentSession.obtain(INTENT_B, deps)
        assertSame("WU-2.4: construct once per process, never per session", a.idempotency, b.idempotency)

        // Still the same instance for a session built after others were released.
        PaymentSession.release(INTENT_A)
        val c = PaymentSession.obtain(INTENT_A, deps)
        assertSame(a.idempotency, c.idempotency)
    }

    // ---- Hosts: one payment, more than one Activity (audit item 8) ---------------------

    /**
     * **The stuck-order bug this counter exists for.**
     *
     * Two Activities can legitimately hold one payment intent at the same time: split-screen,
     * two tasks, a merchant that launches the sheet again while the first is still up, or the
     * overlap while one instance replaces another.
     *
     * Before the count existed, the *first* of them to be destroyed retired the shared scope.
     * The second host was left holding an engine whose coroutines could not run: its confirm
     * launched on a cancelled scope and returned nothing, back-press stayed blocked for the
     * full ten seconds waiting on a confirm that could never resolve, and the payment settled
     * `PENDING` — which tells the merchant to wait for a webhook that is never coming, because
     * the request never left the device. A permanently stuck order.
     */
    @Test
    fun `a second host keeps the session alive when the first one finishes`() = runTest {
        val net = ScriptedNetworkClient()
        val deps = deps(net)

        val session = PaymentSession.obtain(INTENT_A, deps)
        session.attachHost()
        PaymentSession.obtain(INTENT_A, deps).attachHost()
        assertEquals(2, session.hostCount)

        session.startIfNeeded()
        runCurrent()

        // The first host finishes for good. The session leaves the registry — a *new* launch
        // must build a fresh engine — but the scope belongs to whoever is still driving it.
        session.detachHost(forGood = true)
        runCurrent()
        assertEquals("a new launch must not adopt a finished flow", null, PaymentSession.peek(INTENT_A))
        assertTrue("the surviving host's engine was cut off mid-payment", session.isActive)
        assertEquals(1, session.hostCount)

        // …and it can still send a confirm, which is the whole point.
        assertEquals(ConfirmAcceptance.STARTED, session.engine.confirm(cardPayload(INTENT_A)))
        runCurrent()
        assertEquals("the surviving host's confirm never left the device", 1, net.posts.size)

        // The last host leaves: now it is retired.
        session.engine.cancel()
        session.detachHost(forGood = true)
        runCurrent()
        assertEquals(0, session.hostCount)
    }

    /**
     * A rotation detaches and re-attaches, so the count is balanced across it. A host that
     * only ever attached would add one per turn of the phone and the session could never be
     * retired at all — a leak dressed as a fix.
     */
    @Test
    fun `rotation leaves the host count where it started`() = runTest {
        val deps = deps(ScriptedNetworkClient())
        val session = PaymentSession.obtain(INTENT_A, deps)
        session.attachHost()

        repeat(3) {
            session.detachHost(forGood = false)
            assertSame("a recreation must find the same session", session, PaymentSession.obtain(INTENT_A, deps))
            session.attachHost()
        }

        assertEquals(1, session.hostCount)
        assertTrue(session.isActive)

        session.detachHost(forGood = true)
        runCurrent()
        assertEquals(0, PaymentSession.activeCount)
        assertFalse(session.isActive)
    }

    /**
     * A system-initiated destroy — process death, low memory — is not the flow ending. The
     * host goes, the session stays where a relaunch can find it, exactly as before this
     * counter existed.
     */
    @Test
    fun `a host lost without finishing leaves the session in the registry`() = runTest {
        val deps = deps(ScriptedNetworkClient())
        val session = PaymentSession.obtain(INTENT_A, deps)
        session.attachHost()

        session.detachHost(forGood = false)
        runCurrent()

        assertSame(session, PaymentSession.peek(INTENT_A))
        assertTrue(session.isActive)
        assertEquals(0, session.hostCount)
    }

    /** Retirement is idempotent: a repeated detach cannot cancel a scope twice or leak a watchdog. */
    @Test
    fun `repeated detaches are harmless`() = runTest {
        val session = PaymentSession.obtain(INTENT_A, deps(ScriptedNetworkClient()))
        session.attachHost()

        repeat(4) { session.detachHost(forGood = true) }
        runCurrent()

        assertEquals(0, PaymentSession.activeCount)
        assertFalse(session.isActive)
        assertEquals("the count must never go negative", 0, session.hostCount)
    }

    /**
     * `obtain` is a lookup, not a claim. Tests and diagnostics obtain sessions without hosting
     * them, and a lookup that silently counted as a host would keep every such session alive
     * for the life of the process.
     */
    @Test
    fun `obtain alone hosts nothing`() = runTest {
        val session = PaymentSession.obtain(INTENT_A, deps(ScriptedNetworkClient()))

        assertEquals(0, session.hostCount)

        PaymentSession.release(INTENT_A)
        runCurrent()
        assertEquals(0, PaymentSession.activeCount)
    }

    // ---- Lifecycle: release ------------------------------------------------------------

    @Test
    fun `release on a terminal session with nothing in flight cancels the scope and evicts`() = runTest {
        val net = ScriptedNetworkClient()
        val session = PaymentSession.obtain(INTENT_A, deps(net))
        session.startIfNeeded()
        runCurrent()
        session.engine.cancel()
        assertEquals(PaymentStatus.CANCELLED, session.terminalStatus())

        PaymentSession.release(INTENT_A)
        assertEquals(0, PaymentSession.activeCount)
        assertEquals(null, PaymentSession.peek(INTENT_A))
        assertFalse(session.isActive)
    }

    @Test
    fun `release with an attempt in the air keeps the scope alive, then self-evicts within the bound`() = runTest {
        val net = ScriptedNetworkClient()
        val session = PaymentSession.obtain(INTENT_A, deps(net, orphanLifetimeMillis = 10_000L))
        session.startIfNeeded()
        runCurrent()
        session.engine.confirm(cardPayload(INTENT_A))
        runCurrent()
        assertEquals(1, net.posts.size)

        // The bounded block expired: the Activity delivers PENDING and finishes for good.
        session.engine.cancel()
        assertEquals(PaymentStatus.PENDING, session.terminalStatus())
        PaymentSession.release(INTENT_A)

        // Kept alive: the confirm is still replaying for pin resolution.
        runCurrent()
        assertTrue("the orphaned attempt must keep running", session.isActive)
        assertFalse(net.posts[0].cancelled)
        assertEquals("the orphan is still accounted for", 1, PaymentSession.activeCount)
        assertEquals("but a new launch would not find it", null, PaymentSession.peek(INTENT_A))

        advanceTimeBy(9_000L)
        runCurrent()
        assertTrue(session.isActive)
        assertEquals(1, PaymentSession.activeCount)

        // The bound elapses: cancelled and evicted, whatever the attempt was doing.
        advanceTimeBy(2_000L)
        runCurrent()
        assertFalse(session.isActive)
        assertTrue("the hung confirm is cancelled at the bound", net.posts[0].cancelled)
        assertEquals(0, PaymentSession.activeCount)
    }

    @Test
    fun `an orphaned session evicts as soon as its work finishes, before the bound`() = runTest {
        val net = ScriptedNetworkClient()
        val session = PaymentSession.obtain(INTENT_A, deps(net, orphanLifetimeMillis = 60_000L))
        session.startIfNeeded()
        runCurrent()
        session.engine.confirm(cardPayload(INTENT_A))
        runCurrent()
        session.engine.cancel()
        PaymentSession.release(INTENT_A)
        runCurrent()
        assertEquals(1, PaymentSession.activeCount)

        // The gateway answers late. The engine is already Terminal(PENDING): the answer is
        // dropped by the latch (never a second delivery) but the pin is resolved, and the
        // session has nothing left to do.
        net.posts[0].answer(200, intentJson(INTENT_A, "SUCCEEDED"))
        runCurrent()
        assertEquals(PaymentStatus.PENDING, session.terminalStatus())
        assertEquals(1, session.engine.droppedSettleAttempts)
        assertEquals(0, PaymentSession.activeCount)
        assertFalse(session.isActive)
    }

    @Test
    fun `release of an unknown intent is a no-op`() = runTest {
        PaymentSession.release("PI_never_obtained")
        assertEquals(0, PaymentSession.activeCount)
    }

    @Test
    fun `the registry never leaks - N sessions released return it to zero`() = runTest {
        val net = ScriptedNetworkClient()
        val ids = (1..8).map { "PI_leak_$it" }
        for (id in ids) {
            val s = PaymentSession.obtain(id, deps(net))
            s.startIfNeeded()
            runCurrent()
            s.engine.cancel()
        }
        assertEquals(ids.size, PaymentSession.activeCount)
        ids.forEach(PaymentSession::release)
        assertEquals(0, PaymentSession.activeCount)
    }

    // ---- The composition root ----------------------------------------------------------

    /**
     * `UQPay.initialize` → session → `startIfNeeded` drives one intent read through the real
     * `UQPayApiClient`: right URL for the configured environment, right intent, authenticated
     * with the configured client id and a Bearer token from the configured provider, and the
     * engine reaches `SelectingMethod` from the decoded response.
     */
    @Test
    fun `composition root - startIfNeeded drives a retrieveIntent through the real API client`() = runTest {
        val net = ScriptedNetworkClient()
        val session = PaymentSession.obtain(INTENT_A, deps(net))
        assertTrue(session.startIfNeeded())
        runCurrent()

        assertEquals(1, net.requests.size)
        val request = net.requests[0]
        assertEquals(HttpMethod.GET, request.method)
        assertEquals("${Environment.SANDBOX.baseUrl}/v2/payment_intents/$INTENT_A", request.url)
        assertEquals(CLIENT_ID, request.headers["x-client-id"])
        assertEquals("Bearer $TOKEN", request.headers["x-auth-token"])
        assertEquals(1, tokenFetches)

        val state = session.state.value
        assertTrue("expected SelectingMethod, was $state", state is EngineState.SelectingMethod)
        assertEquals(INTENT_A, (state as EngineState.SelectingMethod).intent.paymentIntentId)
        assertEquals(listOf("card", "alipaycn"), state.methods.map { it.raw })
    }

    @Test
    fun `composition root - a confirm reaches the socket with a key and the frozen device body`() = runTest {
        val net = ScriptedNetworkClient()
        val session = PaymentSession.obtain(INTENT_A, deps(net))
        session.startIfNeeded()
        runCurrent()
        session.engine.confirm(cardPayload(INTENT_A))
        runCurrent()

        assertEquals(1, net.posts.size)
        val post = net.posts[0].request
        assertEquals("${Environment.SANDBOX.baseUrl}/v2/payment_intents/$INTENT_A/confirm", post.url)
        assertFalse(post.idempotencyKey.isNullOrBlank())
        val body = post.body.orEmpty()
        assertTrue("browser_info frozen from the device must be in the body", body.contains("browser_info"))
        assertTrue(body.contains("\"os_type\":\"ANDROID\""))
    }

    // ---- F5: the opt-in logger (Slice 6, item 8) ---------------------------------------
    //
    // `UQPayLogger.Logcat` was constructed nowhere, which meant every "log and continue"
    // degradation in the SDK — an unwritable pin store above all — went to a discarding
    // logger in every build that ships. These pin the switch that fixed that.

    @Test
    fun `logging is off unless the merchant asks for it`() = runTest {
        ShadowLog.clear()
        val session = PaymentSession.obtain(INTENT_A, deps(ScriptedNetworkClient()))

        // A second load is a caller bug the engine logs and ignores; it is the cheapest
        // deterministic log line in the graph.
        session.engine.load()
        session.engine.load()
        runCurrent()

        assertTrue("nothing may reach Logcat by default", ShadowLog.getLogsForTag("UQPay").isEmpty())
    }

    @Test
    fun `loggingEnabled routes the SDK's diagnostics to Logcat, and no body ever appears`() = runTest {
        UQPay.initialize(context, configuration(loggingEnabled = true))
        ShadowLog.clear()
        val session = PaymentSession.obtain(INTENT_A, deps(ScriptedNetworkClient()))

        session.engine.load()
        session.engine.load()
        runCurrent()

        val lines = ShadowLog.getLogsForTag("UQPay")
        assertTrue("the opt-in must actually reach Logcat", lines.isNotEmpty())
        assertTrue(lines.any { it.msg.contains("load called twice") })
        // The hard rule, asserted rather than trusted: no request or response body text.
        val all = lines.joinToString("\n") { it.msg }
        for (forbidden in listOf("4242", "browser_info", "payment_method", TOKEN, CLIENT_ID)) {
            assertFalse("a log line leaked \"$forbidden\"", all.contains(forbidden))
        }
    }

    // ---- Harness -----------------------------------------------------------------------

    private var tokenFetches = 0

    private fun configuration(loggingEnabled: Boolean = false) = UQPayConfiguration(
        clientId = CLIENT_ID,
        environment = Environment.SANDBOX,
        tokenProvider = UQPayTokenProvider {
            tokenFetches++
            UQPayAuthToken(TOKEN, System.currentTimeMillis() + 30 * 60_000L)
        },
        loggingEnabled = loggingEnabled,
    )

    private fun TestScope.deps(
        net: UQPayNetworkClient,
        orphanLifetimeMillis: Long = PaymentSession.ORPHAN_LIFETIME_MILLIS,
    ) = SessionDependencies(
        networkClient = net,
        workContext = StandardTestDispatcher(testScheduler),
        orphanLifetimeMillis = orphanLifetimeMillis,
        wallClock = { FIXED_NOW },
    )

    private fun PaymentSession.terminalStatus(): PaymentStatus {
        val s = state.value
        if (s !is EngineState.Terminal) fail("expected Terminal, was $s")
        return (s as EngineState.Terminal).result.status
    }

    /**
     * The socket. Every GET answers with a payable intent; every POST hangs until the test
     * answers it, and records whether it was cancelled instead.
     */
    private class ScriptedNetworkClient : UQPayNetworkClient {
        val requests = mutableListOf<UQPayRequest>()
        val posts = mutableListOf<PendingPost>()
        val gets: Int get() = requests.count { it.method == HttpMethod.GET }

        override suspend fun execute(request: UQPayRequest): UQPayResponse {
            requests += request
            return when (request.method) {
                HttpMethod.GET -> {
                    val id = request.url.substringAfterLast('/')
                    UQPayResponse(200, intentJson(id, "REQUIRES_PAYMENT_METHOD"), "trace-get", null)
                }
                HttpMethod.POST -> {
                    val post = PendingPost(request)
                    posts += post
                    post.await()
                }
            }
        }
    }

    private class PendingPost(val request: UQPayRequest) {
        private val response = CompletableDeferred<UQPayResponse>()
        var cancelled = false

        fun answer(status: Int, body: String) {
            response.complete(UQPayResponse(status, body, "trace-post", null))
        }

        suspend fun await(): UQPayResponse = try {
            response.await()
        } catch (c: CancellationException) {
            cancelled = true
            throw c
        }
    }

    private companion object {
        const val INTENT_A = "PI_session_a"
        const val INTENT_B = "PI_session_b"
        const val CLIENT_ID = "client-test"
        const val TOKEN = "tok-fixture"
        const val FIXED_NOW = 1_755_500_000_000L

        // Wire keys as the gateway sends them (see UQPayApiClientTest).
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

        fun cardPayload(intentId: String) = ConfirmPayload.Card(
            paymentIntentId = intentId,
            cardNumber = "4242424242424242",
            expiryMonth = "12",
            expiryYear = "2030",
            cvc = "123",
            cardholderName = "Test Card",
            network = "visa",
        )
    }
}
