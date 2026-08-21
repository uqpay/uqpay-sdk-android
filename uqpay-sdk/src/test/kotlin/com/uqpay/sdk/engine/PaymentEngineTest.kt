package com.uqpay.sdk.engine

import com.uqpay.sdk.testErrorCopy
import com.uqpay.sdk.Environment
import com.uqpay.sdk.error.UQPayErrorCode
import com.uqpay.sdk.network.AttemptPaymentMethodDto
import com.uqpay.sdk.network.ApiErrorBody
import com.uqpay.sdk.network.DisplayQrCodeDto
import com.uqpay.sdk.network.ErrorMapper
import com.uqpay.sdk.network.IntentStatus
import com.uqpay.sdk.network.NextActionDto
import com.uqpay.sdk.network.PaymentAttemptDto
import com.uqpay.sdk.network.PaymentIntentDto
import com.uqpay.sdk.network.RedirectIframeDto
import com.uqpay.sdk.network.RedirectToUrlDto
import com.uqpay.sdk.network.UQPayApiException
import com.uqpay.sdk.payment.PaymentMethodType
import com.uqpay.sdk.payment.PaymentStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Disaster simulations for the state machine that decides what a merchant is told.
 *
 * Everything below runs on scripted steps — no clock, no registry, no socket — because the
 * rules under test are the engine's own: the once-only latch, supersession, stale-result
 * protection, the presentation guard, the decline that must be reported, and the difference
 * between `PENDING`, `FAILED` and `CANCELLED`. The runner and poller are tested in their own
 * files.
 *
 * No real card number, key or API secret appears in this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PaymentEngineTest {

    // ---- Latch: exactly one terminal --------------------------------------------------

    @Test
    fun `racing resolvers produce exactly one terminal and the losers are counted`() = runTest {
        val h = harness()
        h.loadPayable()
        h.engine.confirm(cardPayload())
        runCurrent()

        // Two answers for one attempt: the confirm says success, a cancel arrives at the
        // same moment. Whichever is first wins; the other is dropped and counted.
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "SUCCEEDED")))
        runCurrent()
        h.engine.cancel()

        val terminal = h.terminal()
        assertEquals(PaymentStatus.SUCCEEDED, terminal.status)
        assertEquals(1, h.engine.droppedSettleAttempts)
        assertEquals(1, h.terminals.size)
    }

    /**
     * The same latch under **real** contention. Every other test here runs on a single
     * scheduler thread, so `settled.compareAndSet` is never actually contended and a naive
     * `if (settled) return; settled = true` would keep the file green. Here N platform
     * threads are released through one gate to settle one engine at the same instant, many
     * rounds over, and every round must produce exactly one winner, N−1 counted drops, and a
     * `Terminal` that is the winner's — never a later loser's overwrite.
     *
     * The public entry points that reach `settle` are either serialised by the engine's own
     * lock (`cancel`) or one-per-generation (a confirm's job), so the only way to put N
     * unserialised callers on the CAS is to call `settle` itself; it is private, so it is
     * reached reflectively. Each racer carries a distinct outcome so an overwrite is visible.
     */
    @Test
    fun `N real threads racing to settle produce exactly one terminal per engine`() {
        val threads = 32
        val rounds = 40
        val settle = PaymentEngine::class.java
            .getDeclaredMethod("settle", EngineOutcome::class.java, Int::class.javaObjectType)
            .apply { isAccessible = true }
        val realScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(rounds) { round ->
                val engine = PaymentEngine(
                    paymentIntentId = INTENT_ID,
                    scope = realScope,
                    confirmStep = ScriptedConfirmStep(),
                    watchStep = ScriptedWatchStep(),
                    intentSource = ScriptedIntentSource(),
                    errorMapper = ErrorMapper(Environment.SANDBOX, testErrorCopy()),
                    wallClock = { FIXED_NOW },
                )
                val startGate = CountDownLatch(1)
                val finished = CountDownLatch(threads)
                val winners = ConcurrentLinkedQueue<Int>()
                repeat(threads) { i ->
                    pool.execute {
                        // Even racers cancel, odd racers fail: distinct outcomes make an
                        // overwritten Terminal detectable.
                        val outcome: EngineOutcome = if (i % 2 == 0) {
                            EngineOutcome.Cancelled(null)
                        } else {
                            EngineOutcome.Failed(ErrorMapper(Environment.SANDBOX, testErrorCopy()).map(IllegalStateException("racer-$i")), null)
                        }
                        try {
                            startGate.await()
                            if (settle.invoke(engine, outcome, null) as Boolean) winners += i
                        } finally {
                            finished.countDown()
                        }
                    }
                }
                startGate.countDown()
                assertTrue("round $round did not finish", finished.await(20, TimeUnit.SECONDS))

                assertEquals("round $round: exactly one settle may win", 1, winners.size)
                assertEquals("round $round: every loser is counted", threads - 1, engine.droppedSettleAttempts)
                val terminal = engine.state.value
                assertTrue("round $round: expected Terminal, was $terminal", terminal is EngineState.Terminal)
                val expected = if (winners.first() % 2 == 0) PaymentStatus.CANCELLED else PaymentStatus.FAILED
                assertEquals("round $round: Terminal must be the winner's, not a later overwrite", expected, (terminal as EngineState.Terminal).result.status)
                assertEquals(ConfirmAcceptance.REJECTED_NOT_CONFIRMABLE, engine.confirm(cardPayload()))
            }
        } finally {
            pool.shutdownNow()
            realScope.cancel()
        }
    }

    /**
     * **`settle` holds the same monitor as `confirm` (audit item 16).**
     *
     * The once-only latch makes *settling* exactly-once, but on its own it does not order a
     * settle against [PaymentEngine.confirm], which reads `settled` and the current state and
     * *then* launches an attempt. Interleaved, a confirm passes its guard, a poll or a cancel
     * settles microseconds later, and the confirm — already past the guard — puts a request on
     * the wire for a payment the merchant has just been told is finished. Nothing on screen
     * betrays it: `moveTo` refuses to repaint a `Terminal`. The request still goes.
     *
     * Asserted structurally rather than by racing, because the window is a handful of
     * instructions wide and a thread race that happens to miss it proves nothing. A holder
     * takes the engine's own lock; `settle` must then block until it is released. Remove the
     * `synchronized` from `settle` and this test fails immediately and every time.
     */
    @Test
    fun `settle waits for the engine's lock, so no confirm can slip past a decided payment`() {
        val realScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val engine = PaymentEngine(
                paymentIntentId = INTENT_ID,
                scope = realScope,
                confirmStep = ScriptedConfirmStep(),
                watchStep = ScriptedWatchStep(),
                intentSource = ScriptedIntentSource(),
                errorMapper = ErrorMapper(Environment.SANDBOX, testErrorCopy()),
                wallClock = { FIXED_NOW },
            )
            val lock = PaymentEngine::class.java.getDeclaredField("lock")
                .apply { isAccessible = true }
                .get(engine)!!
            val settle = PaymentEngine::class.java
                .getDeclaredMethod("settle", EngineOutcome::class.java, Int::class.javaObjectType)
                .apply { isAccessible = true }

            val holding = CountDownLatch(1)
            val release = CountDownLatch(1)
            val settled = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                pool.execute {
                    synchronized(lock) {
                        holding.countDown()
                        release.await()
                    }
                }
                assertTrue(holding.await(20, TimeUnit.SECONDS))

                pool.execute {
                    settle.invoke(engine, EngineOutcome.Cancelled(null), null)
                    settled.countDown()
                }

                assertFalse(
                    "settle decided the payment while another caller held the engine's lock — " +
                        "a confirm mid-guard can then launch a request for a payment already reported",
                    settled.await(250, TimeUnit.MILLISECONDS),
                )
                assertFalse("nothing may be reported yet", engine.state.value is EngineState.Terminal)

                release.countDown()
                assertTrue("settle must proceed once the lock is free", settled.await(20, TimeUnit.SECONDS))
                assertTrue(engine.state.value is EngineState.Terminal)
            } finally {
                release.countDown()
                pool.shutdownNow()
            }
        } finally {
            realScope.cancel()
        }
    }

    /**
     * **A confirm may not be sent after the payment has been reported (audit item 16).**
     *
     * The once-only latch makes *settling* exactly-once, but on its own it does not order a
     * settle against [PaymentEngine.confirm], which reads `settled` and the current state and
     * *then* launches an attempt. Interleaved, a confirm passes its guard, a cancel settles
     * `CANCELLED` microseconds later, and the confirm — already past the guard — puts a
     * request on the wire for a payment the merchant has just been told nobody made. Nothing
     * on screen shows it: `moveTo` refuses to repaint a `Terminal`. The request still goes.
     *
     * The invariant this asserts is the one that costs money: a `CANCELLED` outcome means
     * **nothing was sent**. Either the confirm won the lock — in which case there is an
     * attempt in the air, the cancel finds it, and the outcome is `PENDING` — or the settle
     * won and the confirm is refused. `CANCELLED` alongside a sent confirm is the bug, and it
     * is what this test looks for over many rounds of a genuine two-thread race.
     *
     * Rounds that do not hit the window pass vacuously; a round that hits it can only fail if
     * the ordering is actually broken, so this cannot produce a false failure.
     */
    @Test
    fun `a confirm racing a settle never sends a request for a cancelled payment`() {
        val rounds = 300
        val realScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(rounds) { round ->
                val sends = ConcurrentLinkedQueue<String>()
                val engine = PaymentEngine(
                    paymentIntentId = INTENT_ID,
                    scope = realScope,
                    // Records that an attempt actually started, which is the observable
                    // equivalent of "a request left the device".
                    confirmStep = ConfirmStep { payload ->
                        sends += payload.paymentIntentId
                        ConfirmOutcome.Unresolved(ErrorMapper(Environment.SANDBOX, testErrorCopy()).map(IllegalStateException("held")))
                    },
                    watchStep = ScriptedWatchStep(),
                    intentSource = ScriptedIntentSource().apply {
                        enqueue(intent(status = "REQUIRES_PAYMENT_METHOD", methods = listOf("card")))
                    },
                    errorMapper = ErrorMapper(Environment.SANDBOX, testErrorCopy()),
                    wallClock = { FIXED_NOW },
                )
                engine.load()
                // The load runs on the real scope; wait for the method list before racing.
                val ready = CountDownLatch(1)
                pool.execute {
                    while (engine.state.value !is EngineState.SelectingMethod) Thread.sleep(1)
                    ready.countDown()
                }
                assertTrue("round $round never loaded", ready.await(20, TimeUnit.SECONDS))

                val startGate = CountDownLatch(1)
                val finished = CountDownLatch(2)
                pool.execute {
                    try {
                        startGate.await()
                        engine.confirm(cardPayload())
                    } finally {
                        finished.countDown()
                    }
                }
                pool.execute {
                    try {
                        startGate.await()
                        engine.cancel()
                    } finally {
                        finished.countDown()
                    }
                }
                startGate.countDown()
                assertTrue("round $round did not finish", finished.await(20, TimeUnit.SECONDS))

                val terminal = engine.state.value as? EngineState.Terminal
                if (terminal?.result?.status == PaymentStatus.CANCELLED) {
                    // Give a confirm that slipped past the guard time to actually start.
                    Thread.sleep(2)
                    assertTrue(
                        "round $round reported CANCELLED for a payment whose confirm was sent anyway",
                        sends.isEmpty(),
                    )
                }
            }
        } finally {
            pool.shutdownNow()
            realScope.cancel()
        }
    }

    @Test
    fun `terminal is never overwritten by a later state move`() = runTest {
        val h = harness()
        h.loadPayable()
        h.engine.confirm(cardPayload())
        runCurrent()
        h.engine.cancel() // PENDING, confirm still in flight
        assertEquals(PaymentStatus.PENDING, h.terminal().status)

        // The confirm now answers with a customer action; nothing may repaint the terminal.
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "REQUIRES_CUSTOMER_ACTION", nextAction = redirect("https://acs.example/3ds"))))
        runCurrent()
        assertTrue(h.engine.state.value is EngineState.Terminal)
        assertEquals(PaymentStatus.PENDING, h.terminal().status)
        assertEquals(0, h.watch.polls.size) // and a finished session spends no network
    }

    // ---- Stale-task protection (G11) --------------------------------------------------

    @Test
    fun `stale watcher settling after supersession is dropped`() = runTest {
        val h = harness()
        h.loadPayable()

        // Attempt 1: confirmed into a 3DS page; its watcher is now polling. The watcher is
        // written to *ignore* cancellation, so only the generation check stands between its
        // late answer and the merchant.
        h.watch.ignoreCancellationOnNext = true
        h.engine.confirm(cardPayload())
        runCurrent()
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "REQUIRES_CUSTOMER_ACTION", nextAction = redirect("https://acs.example/3ds"))))
        runCurrent()
        assertTrue(h.engine.state.value is EngineState.RequiresAction)
        assertEquals(1, h.watch.polls.size)

        // Attempt 2 supersedes.
        h.engine.confirm(cardPayload())
        runCurrent()
        assertTrue(h.engine.state.value is EngineState.Confirming)

        h.watch.polls[0].resolve(PollOutcome.Settled(intent(status = "SUCCEEDED"), IntentStatus.Succeeded))
        runCurrent()

        assertFalse("stale watcher must not settle", h.engine.state.value is EngineState.Terminal)
        assertEquals(1, h.engine.droppedSettleAttempts)

        // The live attempt still decides the payment.
        h.confirm.resolveNext(ConfirmOutcome.Failed(h.mapper.map(IllegalStateException("x"))))
        runCurrent()
        assertEquals(PaymentStatus.FAILED, h.terminal().status)
    }

    @Test
    fun `stale confirm result arriving after supersession is dropped`() = runTest {
        val h = harness()
        h.loadPayable()
        h.confirm.ignoreCancellationOnNext = true
        h.engine.confirm(cardPayload())
        runCurrent()

        h.engine.confirm(cardPayload())
        runCurrent()
        assertEquals(2, h.confirm.calls.size)

        h.confirm.calls[0].resolve(ConfirmOutcome.Confirmed(intent(status = "SUCCEEDED")))
        runCurrent()
        assertFalse(h.engine.state.value is EngineState.Terminal)
        assertEquals(1, h.engine.droppedSettleAttempts)
    }

    // ---- G4: the decline the merchant must hear about ---------------------------------

    @Test
    fun `G4 decline at load reports FAILED with the attempt's failure code`() = runTest {
        val h = harness()
        h.source.enqueue(intent(status = "REQUIRES_PAYMENT_METHOD", attemptStatus = "FAILED", failureCode = "insufficient_funds", failureMessage = ""))
        h.engine.load()
        runCurrent()

        val terminal = h.terminal()
        assertEquals(PaymentStatus.FAILED, terminal.status)
        assertEquals(UQPayErrorCode.INSUFFICIENT_FUNDS, terminal.error?.code)
        assertEquals("insufficient_funds", terminal.error?.declineCode)
    }

    @Test
    fun `G4 decline after a poll reports FAILED with the attempt's failure code`() = runTest {
        val h = harness()
        h.loadPayable()
        h.engine.confirm(cardPayload())
        runCurrent()
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "PENDING")))
        runCurrent()
        assertTrue(h.engine.state.value is EngineState.Polling)

        val declined = intent(status = "REQUIRES_PAYMENT_METHOD", attemptStatus = "FAILED", failureCode = "card_declined")
        assertTrue("engine's early-return rule must stop on the fallback", h.watch.polls[0].earlyReturn.shouldStop(declined))
        h.watch.polls[0].resolve(PollOutcome.EarlyReturn(declined))
        runCurrent()

        val terminal = h.terminal()
        assertEquals(PaymentStatus.FAILED, terminal.status)
        assertEquals(UQPayErrorCode.CARD_DECLINED, terminal.error?.code)
        assertEquals("card_declined", terminal.error?.declineCode)
    }

    @Test
    fun `G4 decline straight from the confirm response`() = runTest {
        val h = harness()
        h.loadPayable()
        h.engine.confirm(cardPayload())
        runCurrent()
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "REQUIRES_PAYMENT_METHOD", attemptStatus = "FAILED", failureCode = "insufficient_funds")))
        runCurrent()
        assertEquals(UQPayErrorCode.INSUFFICIENT_FUNDS, h.terminal().error?.code)
    }

    @Test
    fun `G14 fallback to REQUIRES_PAYMENT_METHOD during 3DS is 3ds_failed when the attempt has no code`() = runTest {
        val h = harness()
        h.loadPayable()
        h.engine.confirm(cardPayload())
        runCurrent()
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "REQUIRES_CUSTOMER_ACTION", nextAction = iframe("<form/>"))))
        runCurrent()
        assertTrue(h.engine.state.value is EngineState.RequiresAction)

        h.watch.polls[0].resolve(PollOutcome.EarlyReturn(intent(status = "REQUIRES_PAYMENT_METHOD", failureCode = "")))
        runCurrent()
        val terminal = h.terminal()
        assertEquals(PaymentStatus.FAILED, terminal.status)
        assertEquals(UQPayErrorCode.THREE_DS_FAILED, terminal.error?.code)
    }

    @Test
    fun `a fresh REQUIRES_PAYMENT_METHOD intent is payable, not a decline`() = runTest {
        val h = harness()
        h.source.enqueue(intent(status = "REQUIRES_PAYMENT_METHOD", attemptStatus = "INITIATED"))
        h.engine.load()
        runCurrent()
        assertTrue(h.engine.state.value is EngineState.SelectingMethod)
    }

    // ---- Presentation-time guard (G3, decision B) -------------------------------------

    @Test
    fun `SUCCEEDED at load reports success via ReconciledOutcome`() = runTest {
        val h = harness()
        h.source.enqueue(intent(status = "SUCCEEDED", amount = "8.98", methodType = "alipaycn", attemptId = "PA_1"))
        h.engine.load()
        runCurrent()
        val terminal = h.terminal()
        assertEquals(PaymentStatus.SUCCEEDED, terminal.status)
        assertEquals("8.98", terminal.amount?.toPlainString())
        assertEquals(PaymentMethodType.ALIPAY_CN, terminal.paymentMethodType)
        assertEquals("PA_1", terminal.transactionId)
        assertEquals(FIXED_NOW, terminal.completedAtEpochMillis)
        assertNull(terminal.error)
    }

    @Test
    fun `REQUIRES_CAPTURE at load reports success without confirming`() = runTest {
        val h = harness()
        h.source.enqueue(intent(status = "REQUIRES_CAPTURE"))
        h.engine.load()
        runCurrent()
        assertEquals(PaymentStatus.SUCCEEDED, h.terminal().status)
        assertEquals(0, h.confirm.calls.size)
    }

    @Test
    fun `FAILED and CANCELLED at load report FAILED through mapSettledOutcome`() = runTest {
        run {
            val h = harness()
            h.source.enqueue(intent(status = "FAILED", failureCode = "3ds_failed"))
            h.engine.load()
            runCurrent()
            assertEquals(PaymentStatus.FAILED, h.terminal().status)
            assertEquals(UQPayErrorCode.THREE_DS_FAILED, h.terminal().error?.code)
        }
        run {
            val h = harness()
            h.source.enqueue(intent(status = "CANCELED", failureCode = "card_declined"))
            h.engine.load()
            runCurrent()
            assertEquals(PaymentStatus.FAILED, h.terminal().status)
            // The customer's (or merchant's) cancellation outranks the attempt's code.
            assertEquals(UQPayErrorCode.CANCELLED, h.terminal().error?.code)
        }
    }

    @Test
    fun `presentation guard uses one predicate - every payable status reaches SelectingMethod`() = runTest {
        for (status in listOf("REQUIRES_PAYMENT_METHOD", "REQUIRES_CUSTOMER_ACTION", "PENDING", "SOME_FUTURE_STATUS", "")) {
            val h = harness()
            h.source.enqueue(intent(status = status))
            h.engine.load()
            runCurrent()
            assertTrue("$status should be payable", h.engine.state.value is EngineState.SelectingMethod)
        }
    }

    // ---- Method list (G21, G19) --------------------------------------------------------

    @Test
    fun `method list keeps API order with card pinned first and unknown types carried`() = runTest {
        val h = harness()
        h.source.enqueue(intent(status = "REQUIRES_PAYMENT_METHOD", methods = listOf("alipaycn", "grabpay", "card", "futurepay", " ", "grabpay")))
        h.engine.load()
        runCurrent()
        val selecting = h.engine.state.value as EngineState.SelectingMethod
        assertEquals(
            listOf(PaymentMethodType.CARD, PaymentMethodType.ALIPAY_CN, PaymentMethodType.GRABPAY, PaymentMethodType.of("futurepay")),
            selecting.methods,
        )
    }

    @Test
    fun `method list without card does not invent one`() = runTest {
        val h = harness()
        h.source.enqueue(intent(status = "REQUIRES_PAYMENT_METHOD", methods = listOf("grabpay", "alipayhk")))
        h.engine.load()
        runCurrent()
        val selecting = h.engine.state.value as EngineState.SelectingMethod
        assertEquals(listOf(PaymentMethodType.GRABPAY, PaymentMethodType.ALIPAY_HK), selecting.methods)
    }

    @Test
    fun `CardOnly and SingleWallet presentations are honoured in the list and at the send`() = runTest {
        run {
            val h = harness()
            h.source.enqueue(intent(status = "REQUIRES_PAYMENT_METHOD", methods = listOf("alipaycn", "card")))
            h.engine.load(Presentation.CardOnly)
            runCurrent()
            val selecting = h.engine.state.value as EngineState.SelectingMethod
            assertEquals(listOf(PaymentMethodType.CARD), selecting.methods)
            assertEquals(Presentation.CardOnly, selecting.presentation)
            assertEquals(ConfirmAcceptance.REJECTED_METHOD_NOT_OFFERED, h.engine.confirm(walletPayload("alipaycn")))
            assertEquals(ConfirmAcceptance.STARTED, h.engine.confirm(cardPayload()))
        }
        run {
            val h = harness()
            h.source.enqueue(intent(status = "REQUIRES_PAYMENT_METHOD", methods = listOf("alipaycn", "card", "grabpay")))
            h.engine.load(Presentation.SingleWallet(PaymentMethodType.GRABPAY))
            runCurrent()
            val selecting = h.engine.state.value as EngineState.SelectingMethod
            assertEquals(listOf(PaymentMethodType.GRABPAY), selecting.methods)
            assertEquals(ConfirmAcceptance.REJECTED_METHOD_NOT_OFFERED, h.engine.confirm(cardPayload()))
            assertEquals(ConfirmAcceptance.REJECTED_METHOD_NOT_OFFERED, h.engine.confirm(walletPayload("alipaycn")))
            assertEquals(ConfirmAcceptance.STARTED, h.engine.confirm(walletPayload("grabpay")))
        }
    }

    // ---- Double-tap (G12) --------------------------------------------------------------

    @Test
    fun `valid retry supersedes - old confirm and watcher cancelled, exactly one terminal`() = runTest {
        val h = harness()
        h.loadPayable()

        h.engine.confirm(cardPayload())
        runCurrent()
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "REQUIRES_CUSTOMER_ACTION", nextAction = redirect("https://acs.example/3ds"))))
        runCurrent()
        val firstWatcher = h.watch.polls[0]
        assertFalse(firstWatcher.cancelled)

        assertEquals(ConfirmAcceptance.STARTED, h.engine.confirm(cardPayload()))
        runCurrent()
        assertTrue("the superseded watcher must be cancelled", firstWatcher.cancelled)
        assertTrue(h.engine.state.value is EngineState.Confirming)
        assertEquals(2, h.confirm.calls.size)

        // A double-tap mid-ladder: the second confirm's job is cancelled by a third tap.
        assertEquals(ConfirmAcceptance.STARTED, h.engine.confirm(cardPayload()))
        runCurrent()
        assertTrue(h.confirm.calls[1].cancelled)
        assertEquals(3, h.confirm.calls.size)

        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "SUCCEEDED")))
        runCurrent()
        assertEquals(PaymentStatus.SUCCEEDED, h.terminal().status)
        assertEquals(1, h.terminals.size)
    }

    @Test
    fun `a tap that fails validation cancels nothing`() = runTest {
        val h = harness()
        h.loadPayable()
        h.engine.confirm(cardPayload())
        runCurrent()
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "REQUIRES_CUSTOMER_ACTION", nextAction = redirect("https://acs.example/3ds"))))
        runCurrent()
        val watcher = h.watch.polls[0]

        assertEquals(ConfirmAcceptance.REJECTED_INVALID, h.engine.confirm(cardPayload(), PayloadValidation.INVALID))
        runCurrent()

        assertFalse("the only observer of a possibly-settled payment must survive", watcher.cancelled)
        assertEquals(1, h.confirm.calls.size)
        assertTrue(h.engine.state.value is EngineState.RequiresAction)

        // And that observer still decides the payment.
        watcher.resolve(PollOutcome.Settled(intent(status = "SUCCEEDED"), IntentStatus.Succeeded))
        runCurrent()
        assertEquals(PaymentStatus.SUCCEEDED, h.terminal().status)
    }

    @Test
    fun `a failed-validation tap mid-ladder leaves the confirm running`() = runTest {
        val h = harness()
        h.loadPayable()
        h.engine.confirm(cardPayload())
        runCurrent()
        assertEquals(ConfirmAcceptance.REJECTED_INVALID, h.engine.confirm(cardPayload(), PayloadValidation.INVALID))
        runCurrent()
        assertFalse(h.confirm.calls[0].cancelled)
        assertTrue(h.engine.isConfirmInFlight)
    }

    // ---- M-5: a different payload while a confirm is in flight ---------------------------

    /**
     * Supersession is only safe because the digest — and so the idempotency key — is the same.
     * An **edited** payload mid-flight would put a second key in the air against one intent,
     * the one duplicate the gateway cannot dedupe. It is refused, and like a failed-validation
     * tap it cancels nothing: the in-flight attempt stays the only observer.
     */
    @Test
    fun `M-5 a different payload while a confirm is in flight is refused and cancels nothing`() = runTest {
        val h = harness()
        h.loadPayable()
        assertEquals(ConfirmAcceptance.STARTED, h.engine.confirm(cardPayload()))
        runCurrent()
        assertTrue(h.engine.isConfirmInFlight)

        // Same card, corrected expiry: a different digest, therefore a different key.
        val edited = cardPayload().copy(expiryYear = "2031")
        assertTrue(edited.digest() != cardPayload().digest())
        assertEquals(ConfirmAcceptance.REJECTED_DIFFERENT_PAYLOAD_IN_FLIGHT, h.engine.confirm(edited))
        // A different method entirely is a different digest too.
        assertEquals(ConfirmAcceptance.REJECTED_DIFFERENT_PAYLOAD_IN_FLIGHT, h.engine.confirm(walletPayload("alipaycn")))
        runCurrent()

        assertEquals("nothing new was sent", 1, h.confirm.calls.size)
        assertFalse("the in-flight attempt must not be cancelled", h.confirm.calls[0].cancelled)
        assertTrue(h.engine.isConfirmInFlight)

        // The one attempt in the air still decides the payment — exactly once.
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "SUCCEEDED")))
        runCurrent()
        assertEquals(PaymentStatus.SUCCEEDED, h.terminal().status)
        assertEquals(1, h.terminals.size)
        assertEquals(0, h.engine.droppedSettleAttempts)
    }

    @Test
    fun `M-5 the same payload while a confirm is in flight still supersedes`() = runTest {
        val h = harness()
        h.loadPayable()
        h.engine.confirm(cardPayload())
        runCurrent()

        // Byte-identical resubmission: same digest, same key on replay — safe to supersede.
        assertEquals(ConfirmAcceptance.STARTED, h.engine.confirm(cardPayload()))
        runCurrent()
        assertEquals(2, h.confirm.calls.size)
        assertTrue("the superseded confirm is cancelled", h.confirm.calls[0].cancelled)
        assertFalse(h.confirm.calls[1].cancelled)
        assertTrue(h.engine.isConfirmInFlight)

        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "SUCCEEDED")))
        runCurrent()
        assertEquals(PaymentStatus.SUCCEEDED, h.terminal().status)
        assertEquals(1, h.terminals.size)
    }

    /**
     * Once the first confirm has *returned* — the customer is on a 3-D Secure page, the intent
     * is polling — its pin is resolved and a corrected payload is a legitimate retry with
     * corrected details. The M-5 window is exactly `Confirming`; it must not outlive it.
     * (After an *unresolved* ladder the engine is `Terminal(PENDING)`, where every confirm is
     * refused for the stronger reason that the payment is decided.)
     */
    @Test
    fun `M-5 a different payload after the first attempt has returned is accepted`() = runTest {
        val h = harness()
        h.loadPayable()
        h.engine.confirm(cardPayload())
        runCurrent()
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "REQUIRES_CUSTOMER_ACTION", nextAction = redirect("https://acs.example/3ds"))))
        runCurrent()
        assertTrue(h.engine.state.value is EngineState.RequiresAction)
        assertFalse(h.engine.isConfirmInFlight)

        val edited = cardPayload().copy(expiryYear = "2031")
        assertEquals(ConfirmAcceptance.STARTED, h.engine.confirm(edited))
        runCurrent()
        assertEquals(2, h.confirm.calls.size)
        assertTrue("the old watcher is superseded", h.watch.polls[0].cancelled)
        assertTrue(h.engine.isConfirmInFlight)

        // And while *this* one is in flight, the original payload is now the different one.
        assertEquals(ConfirmAcceptance.REJECTED_DIFFERENT_PAYLOAD_IN_FLIGHT, h.engine.confirm(cardPayload()))
        assertEquals(2, h.confirm.calls.size)

        // Polling (no action on screen) is likewise outside the window.
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "PENDING")))
        runCurrent()
        assertTrue(h.engine.state.value is EngineState.Polling)
        assertEquals(ConfirmAcceptance.STARTED, h.engine.confirm(cardPayload()))
        runCurrent()
        assertEquals(3, h.confirm.calls.size)

        // An unresolved ladder ends the payment as PENDING; a retry there is refused, but for
        // being decided, not for the digest.
        h.confirm.resolveNext(ConfirmOutcome.Unresolved(h.mapper.map(UQPayApiException.TimedOut())))
        runCurrent()
        assertEquals(PaymentStatus.PENDING, h.terminal().status)
        assertEquals(ConfirmAcceptance.REJECTED_NOT_CONFIRMABLE, h.engine.confirm(edited))
    }

    @Test
    fun `confirm is refused before load and after terminal`() = runTest {
        val h = harness()
        assertEquals(ConfirmAcceptance.REJECTED_NOT_CONFIRMABLE, h.engine.confirm(cardPayload()))
        h.loadPayable()
        h.engine.cancel()
        assertEquals(ConfirmAcceptance.REJECTED_NOT_CONFIRMABLE, h.engine.confirm(cardPayload()))
        assertEquals(0, h.confirm.calls.size)
    }

    @Test
    fun `a payload for another intent is a programmer error and is never sent`() = runTest {
        val h = harness()
        h.loadPayable()
        try {
            h.engine.confirm(cardPayload(intentId = "PI_other"))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertEquals(0, h.confirm.calls.size)
    }

    // ---- After confirm -----------------------------------------------------------------

    @Test
    fun `AlreadySettled from the intercept settles without polling`() = runTest {
        run {
            val h = harness()
            h.loadPayable()
            h.engine.confirm(cardPayload())
            runCurrent()
            h.confirm.resolveNext(ConfirmOutcome.AlreadySettled(intent(status = "SUCCEEDED"), IntentStatus.Succeeded, error = null))
            runCurrent()
            assertEquals(PaymentStatus.SUCCEEDED, h.terminal().status)
            assertEquals(0, h.watch.polls.size)
        }
        run {
            val h = harness()
            h.loadPayable()
            h.engine.confirm(cardPayload())
            runCurrent()
            val dead = intent(status = "FAILED", failureCode = "card_declined")
            h.confirm.resolveNext(ConfirmOutcome.AlreadySettled(dead, IntentStatus.Failed, ReconciledOutcome.failureError(dead, h.mapper)))
            runCurrent()
            assertEquals(PaymentStatus.FAILED, h.terminal().status)
            assertEquals(UQPayErrorCode.CARD_DECLINED, h.terminal().error?.code)
        }
    }

    @Test
    fun `Confirmed with a customer action shows it and polls with the 3DS budget`() = runTest {
        val h = harness()
        h.loadPayable()
        h.engine.confirm(cardPayload())
        runCurrent()
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "REQUIRES_CUSTOMER_ACTION", nextAction = redirect("https://acs.example/3ds"))))
        runCurrent()

        val state = h.engine.state.value as EngineState.RequiresAction
        assertEquals(NextAction.Redirect("https://acs.example/3ds"), state.action)
        assertEquals(PollBudget.ThreeDs, h.watch.polls[0].budget)
        assertTrue(h.engine.hasAttemptInAir)
        assertFalse(h.engine.isConfirmInFlight)

        h.watch.polls[0].resolve(PollOutcome.Settled(intent(status = "SUCCEEDED"), IntentStatus.Succeeded))
        runCurrent()
        assertEquals(PaymentStatus.SUCCEEDED, h.terminal().status)
    }

    @Test
    fun `Confirmed with a QR polls with the wallet budget`() = runTest {
        val h = harness()
        h.loadPayable()
        h.engine.confirm(walletPayload("alipaycn"))
        runCurrent()
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "REQUIRES_CUSTOMER_ACTION", nextAction = qr("https://qr.example/x", "2026-08-18T10:00:00Z"))))
        runCurrent()
        val state = h.engine.state.value as EngineState.RequiresAction
        assertEquals(NextAction.Qr("https://qr.example/x", "2026-08-18T10:00:00Z"), state.action)
        assertEquals(PollBudget.WalletQr, h.watch.polls[0].budget)
    }

    // ---- Relaunch onto an attempt already in the air (Slice 6, item 1) ------------------
    //
    // The gap these close: an intent already in REQUIRES_CUSTOMER_ACTION used to fall through
    // to SelectingMethod. For a wallet the latch then re-serves the *same live QR* with
    // nobody polling it — the customer pays and the SDK never learns.

    @Test
    fun `relaunch into a QR intent watches it instead of offering the method list`() = runTest {
        val h = harness()
        h.source.enqueue(intent(status = "REQUIRES_CUSTOMER_ACTION", nextAction = qr("https://qr.example/x", "2026-08-18T10:00:00Z")))
        h.engine.load()
        runCurrent()

        val state = h.engine.state.value as EngineState.RequiresAction
        assertEquals(NextAction.Qr("https://qr.example/x", "2026-08-18T10:00:00Z"), state.action)
        assertEquals("the QR must be watched, not merely shown", 1, h.watch.polls.size)
        assertEquals(PollBudget.WalletQr, h.watch.polls[0].budget)
        assertTrue(h.engine.hasAttemptInAir)
        assertFalse(h.engine.isConfirmInFlight)
        assertEquals("adopting an attempt must never send a confirm", 0, h.confirm.calls.size)
    }

    @Test
    fun `relaunch into a 3DS intent watches it with the 3DS budget`() = runTest {
        for (action in listOf(redirect("https://acs.example/3ds"), iframe("<form/>"))) {
            val h = harness()
            h.source.enqueue(intent(status = "REQUIRES_CUSTOMER_ACTION", nextAction = action))
            h.engine.load()
            runCurrent()

            assertTrue("$action must be adopted", h.engine.state.value is EngineState.RequiresAction)
            assertEquals(1, h.watch.polls.size)
            assertEquals(PollBudget.ThreeDs, h.watch.polls[0].budget)
            assertEquals(0, h.confirm.calls.size)
        }
    }

    /**
     * Parity with the post-confirm path is deliberate: an action this SDK version cannot
     * render is still an attempt in the air. Watching it is what stops a merchant being told
     * `CANCELLED` for a payment that may complete.
     */
    @Test
    fun `relaunch into an action this version cannot render is still watched`() = runTest {
        val h = harness()
        val raw = NextActionDto(type = "display_hologram")
        h.source.enqueue(intent(status = "REQUIRES_CUSTOMER_ACTION", nextAction = raw))
        h.engine.load()
        runCurrent()

        assertEquals(NextAction.Unknown(raw), (h.engine.state.value as EngineState.RequiresAction).action)
        assertEquals(1, h.watch.polls.size)
    }

    @Test
    fun `relaunch into a customer-action intent with no next_action still selects a method`() = runTest {
        val h = harness()
        h.source.enqueue(intent(status = "REQUIRES_CUSTOMER_ACTION", nextAction = null))
        h.engine.load()
        runCurrent()

        assertTrue(h.engine.state.value is EngineState.SelectingMethod)
        assertEquals("nothing renderable to watch", 0, h.watch.polls.size)
        assertFalse(h.engine.hasAttemptInAir)
    }

    @Test
    fun `an adopted attempt settles through its own watcher`() = runTest {
        val h = harness()
        h.source.enqueue(intent(status = "REQUIRES_CUSTOMER_ACTION", nextAction = qr("https://qr.example/x", null)))
        h.engine.load()
        runCurrent()
        h.watch.polls[0].resolve(PollOutcome.Settled(intent(status = "SUCCEEDED"), IntentStatus.Succeeded))
        runCurrent()

        assertEquals(PaymentStatus.SUCCEEDED, h.terminal().status)
        assertEquals(1, h.terminals.size)
    }

    @Test
    fun `leaving an adopted QR reports PENDING, never CANCELLED`() = runTest {
        val h = harness()
        h.source.enqueue(intent(status = "REQUIRES_CUSTOMER_ACTION", nextAction = qr("https://qr.example/x", null)))
        h.engine.load()
        runCurrent()
        h.engine.cancel()
        runCurrent()

        assertEquals(PaymentStatus.PENDING, h.terminal().status)
        assertEquals(UQPayErrorCode.TIMEOUT, h.terminal().error?.code)
    }

    @Test
    fun `a confirm after an adopted action supersedes its watcher and sends exactly one confirm`() = runTest {
        val h = harness()
        h.source.enqueue(intent(status = "REQUIRES_CUSTOMER_ACTION", nextAction = redirect("https://acs.example/3ds"), methods = listOf("card")))
        h.engine.load()
        runCurrent()
        val adopted = h.watch.polls[0]

        assertEquals(ConfirmAcceptance.STARTED, h.engine.confirm(cardPayload()))
        runCurrent()

        assertTrue("the adopted watcher must be superseded", adopted.cancelled)
        assertEquals(1, h.confirm.calls.size)
        assertTrue(h.engine.isConfirmInFlight)
    }

    @Test
    fun `Confirmed without a customer action polls`() = runTest {
        val h = harness()
        h.loadPayable()
        h.engine.confirm(cardPayload())
        runCurrent()
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "PENDING")))
        runCurrent()
        assertTrue(h.engine.state.value is EngineState.Polling)
        assertEquals(1, h.watch.polls.size)
    }

    @Test
    fun `Confirmed already settled reports without polling`() = runTest {
        val h = harness()
        h.loadPayable()
        h.engine.confirm(cardPayload())
        runCurrent()
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "REQUIRES_CAPTURE")))
        runCurrent()
        assertEquals(PaymentStatus.SUCCEEDED, h.terminal().status)
        assertEquals(0, h.watch.polls.size)
    }

    @Test
    fun `unknown next_action type surfaces as Unknown and does not crash`() = runTest {
        val h = harness()
        h.loadPayable()
        h.engine.confirm(cardPayload())
        runCurrent()
        val raw = NextActionDto(type = "display_hologram")
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "REQUIRES_CUSTOMER_ACTION", nextAction = raw)))
        runCurrent()
        val state = h.engine.state.value as EngineState.RequiresAction
        assertEquals(NextAction.Unknown(raw), state.action)
        assertEquals(1, h.watch.polls.size)
    }

    @Test
    fun `redirect_to_url with a non-https url is Unknown, never a Redirect`() {
        fun redirectTo(url: String) = NextAction.from(NextActionDto(type = "redirect_to_url", redirectToUrl = RedirectToUrlDto(url)))
        // These would reach WebView.loadUrl with JavaScript enabled and no
        // shouldOverrideUrlLoading in between; the engine must never carry them.
        listOf(
            "javascript:alert(document.cookie)",
            "file:///sdcard/Download/x.html",
            "data:text/html,<script>1</script>",
            "http://acs.example/3ds",
            "intent://acs.example/#Intent;scheme=https;end",
            "about:blank",
            "//acs.example/3ds",
            "acs.example/3ds",
            "https://",
            "ht tps://acs.example/3ds",
        ).forEach { url ->
            assertTrue("'$url' must be Unknown", redirectTo(url) is NextAction.Unknown)
        }
        assertEquals(NextAction.Redirect("https://acs.example/3ds?x=1"), redirectTo("https://acs.example/3ds?x=1"))
        assertEquals(NextAction.Redirect("HTTPS://acs.example/3ds"), redirectTo("HTTPS://acs.example/3ds"))
    }

    @Test
    fun `display_qr_code with a non-https url is Unknown`() {
        fun qr(url: String) = NextAction.from(NextActionDto(type = "display_qr_code", displayQrCode = DisplayQrCodeDto(qrCodeUrl = url)))
        assertTrue(qr("http://qr.example/a.png") is NextAction.Unknown)
        assertTrue(qr("javascript:1") is NextAction.Unknown)
        assertTrue(qr("https://qr.example/a.png") is NextAction.Qr)
    }

    @Test
    fun `known action type with a missing payload is Unknown, not a blank redirect`() {
        assertTrue(NextAction.from(NextActionDto(type = "redirect_to_url", redirectToUrl = RedirectToUrlDto(url = ""))) is NextAction.Unknown)
        assertTrue(NextAction.from(NextActionDto(type = "display_qr_code")) is NextAction.Unknown)
        assertTrue(NextAction.from(NextActionDto(type = "display_bank_details")) is NextAction.BankDetails)
        assertNull(NextAction.from(null))
        assertEquals(NextAction.Iframe("<form/>"), NextAction.from(NextActionDto(type = "REDIRECT_IFRAME", redirectIframe = RedirectIframeDto("<form/>"))))
    }

    @Test
    fun `G13 a different next_action stops the poll early and is shown, the same one does not`() = runTest {
        val h = harness()
        h.loadPayable()
        h.engine.confirm(cardPayload())
        runCurrent()
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "REQUIRES_CUSTOMER_ACTION", nextAction = iframe("<form/>"))))
        runCurrent()
        val check = h.watch.polls[0].earlyReturn

        assertFalse("same action must keep polling", check.shouldStop(intent(status = "REQUIRES_CUSTOMER_ACTION", nextAction = iframe("<form/>"))))
        assertFalse("a stale action on a PENDING intent is not the customer's", check.shouldStop(intent(status = "PENDING", nextAction = redirect("https://acs.example/c"))))
        val challenge = intent(status = "REQUIRES_CUSTOMER_ACTION", nextAction = redirect("https://acs.example/challenge"))
        assertTrue(check.shouldStop(challenge))

        h.watch.polls[0].resolve(PollOutcome.EarlyReturn(challenge))
        runCurrent()
        val state = h.engine.state.value as EngineState.RequiresAction
        assertEquals(NextAction.Redirect("https://acs.example/challenge"), state.action)
        assertEquals(2, h.watch.polls.size)
    }

    // ---- PENDING, never FAILED or CANCELLED ------------------------------------------

    @Test
    fun `ladder exhaustion is PENDING with TIMEOUT, never FAILED`() = runTest {
        val h = harness()
        h.loadPayable()
        h.engine.confirm(cardPayload())
        runCurrent()
        h.confirm.resolveNext(ConfirmOutcome.Unresolved(h.mapper.map(UQPayApiException.ApiError(ApiErrorBody(code = "api_error", message = "GATEWAY-TEXT"), null, 503))))
        runCurrent()
        val terminal = h.terminal()
        assertEquals(PaymentStatus.PENDING, terminal.status)
        assertEquals(UQPayErrorCode.TIMEOUT, terminal.error?.code)
        assertFalse(terminal.error!!.message.contains("GATEWAY-TEXT"))
        assertFalse(terminal.error!!.message.contains("try again", ignoreCase = true))
    }

    @Test
    fun `poll budget exhaustion is PENDING with TIMEOUT, never FAILED`() = runTest {
        val h = harness()
        h.loadPayable()
        h.engine.confirm(cardPayload())
        runCurrent()
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "PENDING")))
        runCurrent()
        val last = intent(status = "PENDING", amount = "12.50")
        h.watch.polls[0].resolve(PollOutcome.Unresolved(last, heldError = UQPayApiException.TimedOut(), attemptsMade = 151))
        runCurrent()
        val terminal = h.terminal()
        assertEquals(PaymentStatus.PENDING, terminal.status)
        assertEquals(UQPayErrorCode.TIMEOUT, terminal.error?.code)
        assertEquals("12.50", terminal.amount?.toPlainString())
    }

    // ---- Cancellation semantics (G1, §2c, F3) -----------------------------------------

    @Test
    fun `cancel with nothing in flight is exactly one CANCELLED with a non-blank id`() = runTest {
        run {
            val h = harness()
            h.engine.cancel() // before load
            h.engine.cancel()
            val terminal = h.terminal()
            assertEquals(PaymentStatus.CANCELLED, terminal.status)
            assertEquals(INTENT_ID, terminal.paymentIntentId)
            assertNull(terminal.error)
            assertEquals(1, h.terminals.size)
            assertEquals(1, h.engine.droppedSettleAttempts)
        }
        run {
            val h = harness()
            h.loadPayable()
            h.engine.cancel()
            assertEquals(PaymentStatus.CANCELLED, h.terminal().status)
            assertEquals(INTENT_ID, h.terminal().paymentIntentId)
        }
        run {
            // Even a wire intent with no id of its own cannot blank the result.
            val h = harness()
            h.source.enqueue(intent(status = "REQUIRES_PAYMENT_METHOD", id = null))
            h.engine.load()
            runCurrent()
            h.engine.cancel()
            assertEquals(INTENT_ID, h.terminal().paymentIntentId)
        }
    }

    @Test
    fun `cancel while loading is CANCELLED and the late load cannot resurrect the screen`() = runTest {
        val h = harness()
        h.source.gate = CompletableDeferred()
        h.engine.load()
        runCurrent()
        assertTrue(h.engine.state.value is EngineState.LoadingIntent)
        h.engine.cancel()
        assertEquals(PaymentStatus.CANCELLED, h.terminal().status)
        runCurrent()
        assertTrue("a load nobody can hear must stop spending network", h.source.readCancelled)
        h.source.gate!!.complete(intent(status = "REQUIRES_PAYMENT_METHOD"))
        runCurrent()
        assertTrue(h.engine.state.value is EngineState.Terminal)
    }

    @Test
    fun `a stale next_action on a PENDING intent is not re-shown to the customer`() = runTest {
        val h = harness()
        h.loadPayable()
        h.engine.confirm(cardPayload())
        runCurrent()
        // The customer finished 3DS; the intent is processing but still carries the old action.
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "PENDING", nextAction = redirect("https://acs.example/3ds"))))
        runCurrent()
        assertTrue("must poll, not re-open the challenge", h.engine.state.value is EngineState.Polling)
    }

    @Test
    fun `cancel mid-confirm is PENDING, never CANCELLED, and the confirm keeps running`() = runTest {
        val h = harness()
        h.loadPayable()
        h.engine.confirm(cardPayload())
        runCurrent()
        assertTrue(h.engine.isConfirmInFlight)

        h.engine.cancel()
        val terminal = h.terminal()
        assertEquals(PaymentStatus.PENDING, terminal.status)
        assertEquals(UQPayErrorCode.TIMEOUT, terminal.error?.code)
        assertEquals(INTENT_ID, terminal.paymentIntentId)

        // The attempt is not cancelled: it may still resolve its pin. Its answer is dropped.
        assertFalse(h.confirm.calls[0].cancelled)
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "SUCCEEDED")))
        runCurrent()
        assertEquals(PaymentStatus.PENDING, h.terminal().status)
        assertEquals(1, h.engine.droppedSettleAttempts)
    }

    @Test
    fun `cancel during a customer action or polling is PENDING`() = runTest {
        run {
            val h = harness()
            h.loadPayable()
            h.engine.confirm(cardPayload())
            runCurrent()
            h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "REQUIRES_CUSTOMER_ACTION", nextAction = qr("https://qr.example/x", null))))
            runCurrent()
            assertTrue(h.engine.hasAttemptInAir)
            h.engine.cancel()
            assertEquals(PaymentStatus.PENDING, h.terminal().status)
        }
        run {
            val h = harness()
            h.loadPayable()
            h.engine.confirm(cardPayload())
            runCurrent()
            h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "PENDING")))
            runCurrent()
            h.engine.cancel()
            assertEquals(PaymentStatus.PENDING, h.terminal().status)
        }
    }

    // ---- F4 at this layer --------------------------------------------------------------

    @Test
    fun `a throwing intent source at load is a Terminal FAILED, never an escaped exception`() = runTest {
        val h = harness(Environment.PRODUCTION)
        h.source.enqueueFailure(UQPayApiException.ApiError(ApiErrorBody(code = "not_found_id", message = "GATEWAY-TEXT"), "trace-1", 404))
        h.engine.load()
        runCurrent()
        val terminal = h.terminal()
        assertEquals(PaymentStatus.FAILED, terminal.status)
        assertEquals(UQPayErrorCode.INVALID_REQUEST, terminal.error?.code)
        assertFalse(terminal.error!!.message.contains("GATEWAY-TEXT"))
        assertEquals(INTENT_ID, terminal.paymentIntentId)
    }

    @Test
    fun `a throwing confirm step is a Terminal PENDING, never an escaped exception`() = runTest {
        val h = harness(Environment.PRODUCTION)
        h.loadPayable()
        h.confirm.throwNext = IllegalStateException("GATEWAY-TEXT")
        h.engine.confirm(cardPayload())
        runCurrent()
        val terminal = h.terminal()
        assertEquals(PaymentStatus.PENDING, terminal.status)
        assertEquals(UQPayErrorCode.TIMEOUT, terminal.error?.code)
        assertFalse(terminal.error!!.message.contains("GATEWAY-TEXT"))
    }

    @Test
    fun `a throwing watch step is a Terminal PENDING, never an escaped exception`() = runTest {
        val h = harness(Environment.PRODUCTION)
        h.loadPayable()
        h.engine.confirm(cardPayload())
        runCurrent()
        h.watch.throwNext = UQPayApiException.UnexpectedStatus(500, "trace")
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "PENDING")))
        runCurrent()
        assertEquals(PaymentStatus.PENDING, h.terminal().status)
        assertEquals(UQPayErrorCode.TIMEOUT, h.terminal().error?.code)
    }

    @Test
    fun `an Error from a step reaches the boundary as a terminal, not a crash`() = runTest {
        val h = harness()
        h.loadPayable()
        h.confirm.throwNext = OutOfMemoryError("simulated")
        h.engine.confirm(cardPayload())
        runCurrent()
        assertEquals(PaymentStatus.PENDING, h.terminal().status)
    }

    // ---- Misc ------------------------------------------------------------------------

    @Test
    fun `load is single-use`() = runTest {
        val h = harness()
        h.loadPayable()
        h.engine.load()
        runCurrent()
        assertEquals(1, h.source.reads)
    }

    @Test
    fun `nudge reaches the watch step`() = runTest {
        val h = harness()
        h.engine.nudge()
        assertEquals(1, h.watch.nudges)
    }

    @Test
    fun `runaway next_action changes are bounded and end as PENDING`() = runTest {
        val h = harness()
        h.loadPayable()
        h.engine.confirm(cardPayload())
        runCurrent()
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "REQUIRES_CUSTOMER_ACTION", nextAction = iframe("<a/>"))))
        runCurrent()
        var flip = 0
        while (h.engine.state.value !is EngineState.Terminal && flip < 50) {
            val next = if (flip % 2 == 0) redirect("https://acs.example/$flip") else iframe("<f$flip/>")
            h.watch.polls.last().resolve(PollOutcome.EarlyReturn(intent(status = "REQUIRES_CUSTOMER_ACTION", nextAction = next)))
            runCurrent()
            flip++
        }
        assertEquals(PaymentStatus.PENDING, h.terminal().status)
        // Pinned to the literal, not the constant: reading MAX_ACTION_STAGES back would pass
        // for any value. Six is the ratified bound (real flows need at most two).
        assertEquals(6, PaymentEngine.MAX_ACTION_STAGES)
        assertEquals("gives up on the flip after the sixth stage", 7, flip)
    }

    /**
     * `INTENT_NOT_PAYABLE` is the fallback for a settled status that is neither paid nor
     * dead. No `IntentStatus` reaches it today (an `Unknown` is payable, so it never reaches
     * `settledOutcome` through `load` or a confirm), but the poller seam hands the engine a
     * status of its own choosing, so the branch is driven — and its exact code and copy
     * pinned — here. A future non-payable status landing in the fallback must report FAILED
     * with this code, never "paid".
     */
    @Test
    fun `a settled status that is neither paid nor dead is FAILED with INTENT_NOT_PAYABLE`() = runTest {
        val h = harness()
        h.loadPayable()
        h.engine.confirm(cardPayload())
        runCurrent()
        h.confirm.resolveNext(ConfirmOutcome.Confirmed(intent(status = "PENDING")))
        runCurrent()
        assertTrue(h.engine.state.value is EngineState.Polling)

        val novel = intent(status = "SOME_FUTURE_STATUS")
        h.watch.polls[0].resolve(PollOutcome.Settled(novel, IntentStatus.Unknown("SOME_FUTURE_STATUS")))
        runCurrent()

        val terminal = h.terminal()
        assertEquals(PaymentStatus.FAILED, terminal.status)
        assertEquals(UQPayErrorCode.INTENT_NOT_PAYABLE, terminal.error?.code)
        assertEquals("This payment has already been completed or cancelled.", terminal.error?.message)
        assertNull(terminal.error?.declineCode)
        assertEquals(1, h.terminals.size)
    }

    // ---- Harness ---------------------------------------------------------------------

    private class Harness(
        val engine: PaymentEngine,
        val confirm: ScriptedConfirmStep,
        val watch: ScriptedWatchStep,
        val source: ScriptedIntentSource,
        val mapper: ErrorMapper,
        val terminals: MutableList<EngineState.Terminal>,
        private val scope: TestScope,
    ) {
        fun terminal(): com.uqpay.sdk.payment.PaymentResult {
            val s = engine.state.value
            if (s !is EngineState.Terminal) fail("expected Terminal, was $s")
            return (s as EngineState.Terminal).result
        }

        fun loadPayable() {
            source.enqueue(intent(status = "REQUIRES_PAYMENT_METHOD", methods = listOf("card", "alipaycn", "grabpay")))
            engine.load()
            scope.runCurrent()
            assertTrue(engine.state.value is EngineState.SelectingMethod)
        }
    }

    private fun TestScope.harness(environment: Environment = Environment.SANDBOX): Harness {
        val confirm = ScriptedConfirmStep()
        val watch = ScriptedWatchStep()
        val source = ScriptedIntentSource()
        val mapper = ErrorMapper(environment, testErrorCopy())
        val engine = PaymentEngine(
            paymentIntentId = INTENT_ID,
            scope = backgroundScope,
            confirmStep = confirm,
            watchStep = watch,
            intentSource = source,
            errorMapper = mapper,
            wallClock = { FIXED_NOW },
        )
        val terminals = mutableListOf<EngineState.Terminal>()
        // Unconfined so every distinct emission is observed synchronously.
        engine.state.onEach { if (it is EngineState.Terminal) terminals += it }
            .launchIn(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        return Harness(engine, confirm, watch, source, mapper, terminals, this)
    }

    /** One scripted invocation of a step: resolves when the test says, or ignores cancellation. */
    private class Call<T>(
        val ignoreCancellation: Boolean,
        val budget: PollBudget? = null,
        val earlyReturn: EarlyReturnCheck = EarlyReturnCheck.Never,
    ) {
        private val answer = CompletableDeferred<T>()
        var cancelled = false

        fun resolve(value: T) {
            answer.complete(value)
        }

        suspend fun await(): T =
            if (ignoreCancellation) {
                withContext(NonCancellable) { answer.await() }
            } else {
                try {
                    answer.await()
                } catch (c: CancellationException) {
                    cancelled = true
                    throw c
                }
            }
    }

    private class ScriptedConfirmStep : ConfirmStep {
        val calls = mutableListOf<Call<ConfirmOutcome>>()
        var throwNext: Throwable? = null
        var ignoreCancellationOnNext = false

        override suspend fun run(payload: ConfirmPayload): ConfirmOutcome {
            throwNext?.let { throwNext = null; throw it }
            val call = Call<ConfirmOutcome>(ignoreCancellationOnNext)
            ignoreCancellationOnNext = false
            calls += call
            return call.await()
        }

        fun resolveNext(outcome: ConfirmOutcome) = calls.last().resolve(outcome)
    }

    private class ScriptedWatchStep : WatchStep {
        val polls = mutableListOf<Call<PollOutcome>>()
        var throwNext: Throwable? = null
        var ignoreCancellationOnNext = false
        var nudges = 0

        override suspend fun poll(budget: PollBudget, earlyReturn: EarlyReturnCheck): PollOutcome {
            throwNext?.let { throwNext = null; throw it }
            val call = Call<PollOutcome>(ignoreCancellationOnNext, budget, earlyReturn)
            ignoreCancellationOnNext = false
            polls += call
            return call.await()
        }

        override fun nudge() {
            nudges++
        }
    }

    private class ScriptedIntentSource : IntentSource {
        private val queue = ArrayDeque<Result<PaymentIntentDto>>()
        var gate: CompletableDeferred<PaymentIntentDto>? = null
        var reads = 0
        var readCancelled = false

        fun enqueue(intent: PaymentIntentDto) = queue.addLast(Result.success(intent))
        fun enqueueFailure(t: Throwable) = queue.addLast(Result.failure(t))

        override suspend fun retrieve(): PaymentIntentDto {
            reads++
            gate?.let {
                try {
                    return it.await()
                } catch (c: CancellationException) {
                    readCancelled = true
                    throw c
                }
            }
            return queue.removeFirst().getOrThrow()
        }
    }

    private companion object {
        const val INTENT_ID = "PI_engine_test"
        const val FIXED_NOW = 1_755_500_000_000L

        fun intent(
            status: String,
            id: String? = INTENT_ID,
            attemptStatus: String? = null,
            failureCode: String? = null,
            failureMessage: String? = null,
            amount: String? = "8.98",
            methodType: String? = "card",
            attemptId: String? = "PA_1",
            methods: List<String>? = listOf("card"),
            nextAction: NextActionDto? = null,
        ) = PaymentIntentDto(
            paymentIntentId = id,
            intentStatus = status,
            amount = amount,
            currency = "SGD",
            merchantOrderId = "order-1",
            availablePaymentMethodTypes = methods,
            latestPaymentAttempt = if (attemptStatus == null && failureCode == null && failureMessage == null && methodType == null && attemptId == null) {
                null
            } else {
                PaymentAttemptDto(
                    attemptId = attemptId,
                    attemptStatus = attemptStatus,
                    paymentMethod = methodType?.let(::AttemptPaymentMethodDto),
                    failureCode = failureCode,
                    failureMessage = failureMessage,
                )
            },
            nextAction = nextAction,
        )

        fun redirect(url: String) = NextActionDto(type = "redirect_to_url", redirectToUrl = RedirectToUrlDto(url))
        fun iframe(html: String) = NextActionDto(type = "redirect_iframe", redirectIframe = RedirectIframeDto(html))
        fun qr(url: String, expiresAt: String?) = NextActionDto(type = "display_qr_code", displayQrCode = DisplayQrCodeDto(url, expiresAt))

        fun cardPayload(intentId: String = INTENT_ID) = ConfirmPayload.Card(
            paymentIntentId = intentId,
            cardNumber = "4242424242424242",
            expiryMonth = "12",
            expiryYear = "2030",
            cvc = "123",
            cardholderName = "Test Card",
            network = "visa",
        )

        fun walletPayload(method: String) = ConfirmPayload.Wallet(paymentIntentId = INTENT_ID, methodType = method)
    }
}
