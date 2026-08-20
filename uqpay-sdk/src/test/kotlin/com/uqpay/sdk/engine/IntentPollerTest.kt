package com.uqpay.sdk.engine

import com.uqpay.sdk.network.IntentStatus
import com.uqpay.sdk.network.PaymentIntentDto
import com.uqpay.sdk.network.UQPayApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Disaster simulations for the poller, in the order they matter.
 *
 * Every test here runs on a fake clock: nothing waits in real time, and a 150-attempt
 * budget runs to exhaustion in microseconds. That is the whole reason [Clock] is an
 * interface — a timing rule that can only be checked by waiting is a timing rule that will
 * never be checked.
 *
 * The rule this file exists to defend, above all others: **budget exhaustion is not a
 * failure and not a cancellation.** It is an unresolved payment, reported as `PENDING`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IntentPollerTest {

    // ---- The non-negotiable outcome rule --------------------------------------------

    /**
     * The customer never finished 3-D Secure, or the gateway is simply slow. We stop
     * looking; the payment may still be moving.
     *
     * Reporting `FAILED` here invites a second payment for the same order. Reporting
     * `CANCELLED` — which is what Stripe's PaymentSheet does
     * (`DefaultConfirmationHandler.kt:69-73`), and what our own iOS SDK shipped and had to
     * fix as a breaking change — tells a merchant to release nothing and refund nothing for
     * a payment that may well succeed thirty seconds later. Unresolved is the only honest
     * answer.
     */
    @Test
    fun `budget exhaustion is unresolved, never a failure`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource { stillWaiting() }
        val outcome = drive(poller(source, PollBudget(3, 2_000L), clock), clock)

        val unresolved = outcome as PollOutcome.Unresolved

        assertNull("a clean run holds no error", unresolved.heldError)
        assertEquals("the last view of the payment is handed back", "int_1", unresolved.lastIntent?.paymentIntentId)
        // 3 budgeted attempts + the guaranteed final fetch.
        assertEquals(4, unresolved.attemptsMade)
        assertEquals(4, source.calls)
    }

    // ---- Held errors (G15) ------------------------------------------------------------

    /**
     * The run ends on a broken gateway. "Your last reads were refused" is a materially
     * better thing to hand a support engineer than "it timed out", so the held error
     * outranks the generic timeout.
     */
    @Test
    fun `a held error outranks the generic timeout on exhaustion`() = runTest {
        val clock = FakeClock()
        val boom = UQPayApiException.TransportFailure(IOException("no route to host"))
        val source = RecordingSource { call ->
            if (call <= 2) stillWaiting() else throw boom
        }

        val outcome = drive(poller(source, PollBudget(4, 2_000L), clock), clock)

        val unresolved = outcome as PollOutcome.Unresolved
        assertSame("the held error is reported verbatim", boom, unresolved.heldError)
        assertEquals(
            "the flaky poll did not abandon the payment — the full budget still ran",
            5,
            unresolved.attemptsMade,
        )
        assertEquals(
            "the last successful read survives alongside the error",
            "int_1",
            unresolved.lastIntent?.paymentIntentId,
        )
    }

    /**
     * The single most important half of G15: one bad GET is a blip, not an outcome. If a
     * held error survived a later good read, a transient DNS failure at attempt 2 of 150
     * would be reported as the reason a payment went unresolved five minutes later.
     */
    @Test
    fun `a good poll clears a previously held error`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource { call ->
            if (call == 1) throw UQPayApiException.TimedOut() else stillWaiting()
        }

        val outcome = drive(poller(source, PollBudget(3, 2_000L), clock), clock)

        assertNull(
            "the error was cleared by the reads that followed it",
            (outcome as PollOutcome.Unresolved).heldError,
        )
    }

    /**
     * Every attempt failed, so there is no intent to hand back — but there is still an
     * outcome, and it is still not a failure.
     */
    @Test
    fun `a run in which every read throws is still unresolved`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource { throw UQPayApiException.TransportFailure(IOException("down")) }

        val outcome = drive(poller(source, PollBudget(2, 2_000L), clock), clock)

        val unresolved = outcome as PollOutcome.Unresolved
        assertNull(unresolved.lastIntent)
        assertTrue(unresolved.heldError is UQPayApiException.TransportFailure)
    }

    /**
     * A poller is a reader, not a decider. A 404 from a load balancer mid-rollout is not
     * evidence that a payment does not exist, and abandoning a live payment costs far more
     * than a handful of wasted reads.
     */
    @Test
    fun `a definitive-looking error is held too, not treated as a verdict`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource {
            throw UQPayApiException.UnexpectedStatus(statusCode = 404, traceId = null)
        }

        val outcome = drive(poller(source, PollBudget(3, 2_000L), clock), clock)

        assertEquals("the budget ran in full", 4, (outcome as PollOutcome.Unresolved).attemptsMade)
    }

    // ---- The guaranteed final fetch ---------------------------------------------------

    /**
     * Adapted from stripe-android (`PaymentFlowResultProcessor.kt:270-283`). The payment
     * settles exactly one poll after the budget runs out — the case where abandoning it is
     * both the most likely and the most indefensible.
     */
    @Test
    fun `the guaranteed final fetch catches a payment that settled one poll short`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource { call ->
            if (call <= 2) stillWaiting() else settled("SUCCEEDED")
        }

        val outcome = drive(poller(source, PollBudget(2, 2_000L), clock), clock)

        val settledOutcome = outcome as PollOutcome.Settled
        assertEquals(IntentStatus.Succeeded, settledOutcome.status)
        assertEquals("the third read is the final fetch, past the budget", 3, source.calls)
    }

    /** The final fetch also rescues a run whose every budgeted attempt threw. */
    @Test
    fun `the final fetch clears a held error when it settles`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource { call ->
            if (call <= 2) throw UQPayApiException.TimedOut() else settled("SUCCEEDED")
        }

        val outcome = drive(poller(source, PollBudget(2, 2_000L), clock), clock)

        assertTrue("a settled payment beats any held error", outcome is PollOutcome.Settled)
    }

    // ---- The per-attempt ceiling (audit item 13) --------------------------------------

    /**
     * **A read is not one request, and until this bound existed nothing timed it.**
     *
     * Underneath `source.retrieve()` the network client retries a safe GET three times with
     * 2s/4s/8s backoff, each retry carrying its own 30s connect and read timeouts and each
     * able to honour a `Retry-After`. One flaky read could therefore occupy well over a minute
     * while spending a *single* attempt — so a `WalletQr` budget of 300 attempts, sized for ten
     * minutes, could span hours on a degraded network with a customer watching a QR that
     * appears to be unwatched.
     *
     * The ceiling bounds the read and nothing else. Note what this test asserts about the
     * poll: the abandoned attempt is **held as a failure and the loop continues** — the very
     * next read settles the payment. A ceiling that ended the poll would be a wall-clock
     * deadline in disguise, which is the iOS bug this whole file is built to avoid.
     */
    @Test
    fun `a read that outlasts its ceiling is abandoned and held, and the poll continues`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val source = IntentSource {
            calls++
            // The first read never comes back; every later one answers at once.
            if (calls == 1) delay(STUCK_READ_MILLIS)
            if (calls >= 2) settled("SUCCEEDED") else stillWaiting()
        }
        val budget = PollBudget(5, 2_000L, attemptCeilingMillis = 30_000L)

        val outcome = driveWithCeiling(async { poller(source, budget, clock).poll() }, clock)

        assertTrue("the stuck read must not have decided the payment", outcome is PollOutcome.Settled)
        assertEquals(IntentStatus.Succeeded, (outcome as PollOutcome.Settled).status)
        assertEquals("the abandoned read cost exactly one attempt", 2, calls)
    }

    /**
     * An abandoned read is reported as a held [UQPayApiException.TimedOut] if the run then
     * ends unresolved — "your reads were timing out" is a materially better thing to tell a
     * support engineer than "we ran out of attempts".
     */
    @Test
    fun `an abandoned read is held as a timeout, not swallowed`() = runTest {
        val clock = FakeClock()
        val source = IntentSource {
            delay(STUCK_READ_MILLIS)
            stillWaiting()
        }
        val budget = PollBudget(1, 2_000L, attemptCeilingMillis = 30_000L)

        val outcome = driveWithCeiling(async { poller(source, budget, clock).poll() }, clock)

        val unresolved = outcome as PollOutcome.Unresolved
        assertTrue("the held error names the reason", unresolved.heldError is UQPayApiException.TimedOut)
        assertEquals(2, unresolved.attemptsMade)
    }

    /** A ceiling of zero or less means no ceiling at all: the read is left alone. */
    @Test
    fun `a ceiling of zero leaves the read untimed`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource { settled("SUCCEEDED") }

        val outcome = drive(poller(source, PollBudget(2, 2_000L, attemptCeilingMillis = 0L), clock), clock)

        assertTrue(outcome is PollOutcome.Settled)
        assertEquals(1, source.calls)
    }

    // ---- Pathological budgets ---------------------------------------------------------

    /**
     * A miscomputed budget must never produce "unresolved" from a poller that never
     * bothered to ask. One budgeted look plus the guaranteed final fetch.
     */
    @Test
    fun `a zero budget still polls`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource { stillWaiting() }

        val outcome = drive(poller(source, PollBudget(0, 2_000L), clock), clock)

        assertEquals(2, source.calls)
        assertEquals(2, (outcome as PollOutcome.Unresolved).attemptsMade)
    }

    @Test
    fun `a negative budget still polls`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource { stillWaiting() }

        drive(poller(source, PollBudget(-7, 2_000L), clock), clock)

        assertEquals(2, source.calls)
    }

    @Test
    fun `a zero interval never waits and still honours the budget`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource { stillWaiting() }

        drive(poller(source, PollBudget(5, 0L), clock), clock)

        assertEquals(6, source.calls)
        assertEquals("nothing ever slept", 0, clock.sleepRequests.size)
    }

    // ---- The stop rule ----------------------------------------------------------------

    /**
     * `REQUIRES_CAPTURE` is not terminal, but the customer has paid. `isPayable` already
     * encodes the watcher-versus-interceptor split; this test pins that the poller is on
     * the watcher side of it.
     */
    @Test
    fun `an authorised but uncaptured payment settles the poll`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource { settled("REQUIRES_CAPTURE") }

        val outcome = drive(poller(source, PollBudget(50, 2_000L), clock), clock)

        assertEquals(IntentStatus.RequiresCapture, (outcome as PollOutcome.Settled).status)
        assertEquals(1, source.calls)
    }

    /**
     * `REQUIRES_PAYMENT_METHOD` is ambiguous — it can mean "declined, try again" — and the
     * poller deliberately does not resolve that ambiguity. Interpreting it belongs to the
     * caller's [EarlyReturnCheck] (G14), not here.
     */
    @Test
    fun `requires-payment-method does not settle the poll by itself`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource { settled("REQUIRES_PAYMENT_METHOD") }

        val outcome = drive(poller(source, PollBudget(2, 2_000L), clock), clock)

        assertTrue(outcome is PollOutcome.Unresolved)
    }

    /**
     * A status this SDK version predates keeps the poll running. Reporting an outcome we
     * cannot name would be worse than waiting for one we can.
     */
    @Test
    fun `an unknown status keeps polling`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource { settled("REQUIRES_SOMETHING_INVENTED_IN_2027") }

        val outcome = drive(poller(source, PollBudget(2, 2_000L), clock), clock)

        assertTrue(outcome is PollOutcome.Unresolved)
    }

    // ---- Early return (G13 / G14) -----------------------------------------------------

    /**
     * Multi-stage 3DS: the intent still requires customer action, but the action is now a
     * *different type* than the one on screen. Nothing about the intent has settled, yet
     * continuing to poll strands the customer on the wrong page.
     */
    @Test
    fun `the early-return check ends the poll`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource { call ->
            if (call < 3) stillWaiting() else stillWaiting(id = "int_stage_two")
        }
        val poller = poller(
            source,
            PollBudget(100, 2_000L),
            clock,
            earlyReturn = { it.paymentIntentId == "int_stage_two" },
        )

        val outcome = drive(poller, clock)

        assertEquals("int_stage_two", (outcome as PollOutcome.EarlyReturn).intent.paymentIntentId)
        assertEquals(3, source.calls)
    }

    /**
     * Settlement is a fact; an early-return check is a heuristic. The fact wins, or a
     * caller's rule about `next_action` could shadow a payment that actually succeeded.
     */
    @Test
    fun `settlement outranks the early-return check`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource { settled("SUCCEEDED") }
        val poller = poller(source, PollBudget(10, 2_000L), clock, earlyReturn = { true })

        assertTrue(drive(poller, clock) is PollOutcome.Settled)
    }

    // ---- Nudge ------------------------------------------------------------------------

    /**
     * The customer comes back from their banking app. They should see the result now, not
     * up to one interval from now.
     *
     * The assertion that matters is the second one: the nudge **replaces** the pending
     * wait. If it merely added a read, a nudge storm on a busy foreground/background cycle
     * could burn a 150-attempt budget in seconds.
     */
    @Test
    fun `a nudge replaces the pending wait rather than adding a poll`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource { call ->
            if (call == 1) stillWaiting() else settled("SUCCEEDED")
        }
        val poller = poller(source, PollBudget(50, 2_000L), clock)

        val running = async { poller.poll() }
        runCurrent()

        assertEquals("the first read has happened", 1, source.calls)
        assertEquals("and the poller is now waiting", 1, clock.pendingSleeps)

        poller.nudge()
        runCurrent()

        assertTrue("the nudged read settled the payment", running.await() is PollOutcome.Settled)
        assertEquals("exactly one extra read, not two", 2, source.calls)
        assertEquals("no clock time was consumed", 0L, clock.nowMillis)
        assertEquals("the wait is gone, not still pending", 0, clock.pendingSleeps)
    }

    /**
     * A nudge is a wake-up, not a request for an extra read. Ten of them while one wait is
     * pending must produce one read, not ten.
     */
    @Test
    fun `a storm of nudges cannot inflate the number of reads`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource { call ->
            if (call == 1) stillWaiting() else settled("SUCCEEDED")
        }
        val poller = poller(source, PollBudget(50, 2_000L), clock)

        val running = async { poller.poll() }
        runCurrent()
        repeat(10) { poller.nudge() }
        runCurrent()

        running.await()
        assertEquals(2, source.calls)
    }

    /** Foregrounding before anything is running must not throw. */
    @Test
    fun `nudging when no poll is running is a no-op`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource { settled("SUCCEEDED") }
        val poller = poller(source, PollBudget(3, 2_000L), clock)

        poller.nudge()

        assertTrue(drive(poller, clock) is PollOutcome.Settled)
        assertEquals(1, source.calls)
    }

    // ---- The lesson from iOS U1 -------------------------------------------------------

    /**
     * **The bug this whole design exists to prevent.**
     *
     * iOS held a wall-clock deadline across suspension, so a customer who left the app to
     * complete 3-D Secure in their banking app — the single most common real card journey
     * — came back to a payment the SDK had already declared timed out. Nothing was wrong
     * with the payment. The SDK had simply counted the customer's absence against them.
     *
     * Here six hours of elapsed realtime pass in one jump while the poller is parked. The
     * budget must be untouched: the poller issues no reads it missed, declares no timeout,
     * and goes on to spend every one of its attempts.
     */
    @Test
    fun `suspended time spends no budget`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource { stillWaiting() }
        val poller = poller(source, PollBudget(3, 2_000L), clock)

        val running = async { poller.poll() }
        runCurrent()
        assertEquals(1, source.calls)

        // The device sleeps for six hours between attempt 1 and attempt 2.
        val sixHours = 6L * 60 * 60 * 1_000
        clock.advanceBy(sixHours)
        runCurrent()

        assertEquals(
            "the missed intervals are not replayed as a burst of catch-up reads",
            2,
            source.calls,
        )
        assertEquals("and no budget was spent while suspended", 2, poller.attemptsMade)

        val outcome = driveToCompletion(running, clock)

        assertEquals(
            "the full budget still ran, six hours past any deadline a wall clock would have set",
            4,
            (outcome as PollOutcome.Unresolved).attemptsMade,
        )
        assertTrue(clock.nowMillis > sixHours)
    }

    // ---- Cancellation and non-Exception throwables ------------------------------------

    /**
     * Cancellation is structured concurrency doing its job, not a flaky poll. A cancelled
     * poll reports nothing at all — holding a `CancellationException` and carrying on would
     * break the coroutine contract and, worse, keep an abandoned poll running.
     */
    @Test
    fun `a cancellation is never held`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource { throw CancellationException("caller went away") }
        val poller = poller(source, PollBudget(5, 2_000L), clock)

        var thrown: Throwable? = null
        try {
            poller.poll()
        } catch (e: CancellationException) {
            thrown = e
        }

        assertTrue("the cancellation propagated", thrown is CancellationException)
        assertEquals("and no further reads were attempted", 1, source.calls)
    }

    /** An `OutOfMemoryError` is not a flaky GET. It must reach the engine boundary. */
    @Test
    fun `an Error is not held`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource { throw FakeVmError() }
        val poller = poller(source, PollBudget(5, 2_000L), clock)

        var thrown: Throwable? = null
        try {
            poller.poll()
        } catch (e: Error) {
            thrown = e
        }

        assertTrue(thrown is FakeVmError)
        assertEquals(1, source.calls)
    }

    // ---- The persistable start timestamp ----------------------------------------------

    /**
     * stripe-android persists `SystemClock.elapsedRealtime()` in a `SavedStateHandle`
     * (`PollingViewModel.kt:34, 162-179`) so a poll restarted after process death knows how
     * long the customer has been waiting. We expose the same value — as elapsed realtime,
     * never a wall-clock instant.
     */
    @Test
    fun `a restored start timestamp measures the wait across a restart`() = runTest {
        val clock = FakeClock()
        clock.advanceBy(90_000L)
        val poller = poller(
            RecordingSource { stillWaiting() },
            PollBudget(3, 2_000L),
            clock,
            startedAt = 30_000L,
        )

        assertEquals(30_000L, poller.startedAtElapsedRealtime)
        assertEquals(60_000L, poller.elapsedSinceStartMillis())
    }

    /**
     * Elapsed realtime resets to zero at boot, so a persisted start restored after a reboot
     * is *ahead* of the current reading. "No idea" is the only honest answer; a clamped
     * zero would quietly claim the customer just arrived.
     */
    @Test
    fun `a start timestamp from before a reboot reports an unknown wait`() = runTest {
        val clock = FakeClock()
        clock.advanceBy(5_000L)
        val poller = poller(
            RecordingSource { stillWaiting() },
            PollBudget(3, 2_000L),
            clock,
            startedAt = 900_000L,
        )

        assertNull(poller.elapsedSinceStartMillis())
    }

    /** The budget is attempts; the timestamp is diagnostics. They must not interact. */
    @Test
    fun `the start timestamp spends no budget`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource { stillWaiting() }
        val poller = poller(source, PollBudget(3, 2_000L), clock, startedAt = -5_000_000L)

        assertEquals(4, (drive(poller, clock) as PollOutcome.Unresolved).attemptsMade)
    }

    // ---- Budget presets ---------------------------------------------------------------

    /** The three shipped configurations, pinned so a casual edit has to argue for itself. */
    @Test
    fun `the shipped budgets are the ones the plan specifies`() {
        assertEquals(PollBudget(150, 2_000L), PollBudget.ThreeDs)
        assertEquals(PollBudget(12, 5_000L), PollBudget.Reconciliation)
        assertEquals(PollBudget(300, 2_000L), PollBudget.WalletQr)
    }

    /**
     * The realistic worst case, run end to end: a customer who never completes 3DS. 150
     * attempts plus the final fetch, in a test that takes no measurable time.
     */
    @Test
    fun `a full 3DS budget runs to exhaustion without waiting`() = runTest {
        val clock = FakeClock()
        val source = RecordingSource { stillWaiting() }

        val outcome = drive(poller(source, PollBudget.ThreeDs, clock), clock)

        assertEquals(151, (outcome as PollOutcome.Unresolved).attemptsMade)
        // 149 waits between the 150 budgeted reads, plus the grace before the final fetch.
        assertEquals(150 * 2_000L, clock.nowMillis)
    }

    // ---- Harness ----------------------------------------------------------------------

    private fun poller(
        source: IntentSource,
        budget: PollBudget,
        clock: FakeClock,
        earlyReturn: (PaymentIntentDto) -> Boolean = { false },
        startedAt: Long = clock.elapsedRealtime(),
    ): IntentPoller = IntentPoller(
        source = source,
        budget = budget,
        clock = clock,
        earlyReturn = { earlyReturn(it) },
        startedAtElapsedRealtime = startedAt,
    )

    private fun stillWaiting(id: String = "int_1"): PaymentIntentDto =
        PaymentIntentDto(paymentIntentId = id, intentStatus = "REQUIRES_CUSTOMER_ACTION")

    private fun settled(status: String, id: String = "int_1"): PaymentIntentDto =
        PaymentIntentDto(paymentIntentId = id, intentStatus = status)

    /**
     * Runs a poll to completion, advancing the fake clock to each next wake-up. No real
     * time passes, and a poller that parks with nothing scheduled fails the test loudly
     * rather than hanging the suite.
     */
    private suspend fun TestScope.drive(poller: IntentPoller, clock: FakeClock): PollOutcome =
        driveToCompletion(async { poller.poll() }, clock)

    /**
     * Drives a poll whose reads park on the **coroutine** clock rather than the injected
     * [FakeClock].
     *
     * Two clocks are in play and that is deliberate: the poller's own waits are the injected
     * one (so a 300-attempt budget costs microseconds), while the per-attempt ceiling is an
     * ordinary `withTimeoutOrNull` on the coroutine scheduler — the same scheduler the real
     * network client's timeouts would use. When nothing is sleeping on the fake clock, a read
     * is in flight, and the only thing that can move is virtual time.
     */
    private suspend fun TestScope.driveWithCeiling(
        running: Deferred<PollOutcome>,
        clock: FakeClock,
    ): PollOutcome {
        var iterations = 0
        while (!running.isCompleted) {
            runCurrent()
            if (running.isCompleted) break
            if (!clock.advanceToNextWake()) testScheduler.advanceTimeBy(CEILING_STEP_MILLIS)
            if (iterations++ > 1_000) fail("the poll did not terminate")
        }
        return running.await()
    }

    private suspend fun TestScope.driveToCompletion(
        running: Deferred<PollOutcome>,
        clock: FakeClock,
    ): PollOutcome {
        var iterations = 0
        while (!running.isCompleted) {
            runCurrent()
            if (running.isCompleted) break
            if (!clock.advanceToNextWake()) {
                fail("the poll parked with no read in flight and nothing sleeping")
            }
            if (iterations++ > 10_000) fail("the poll did not terminate")
        }
        return running.await()
    }

    /** Scripted intent source. The handler receives the 1-based call number and may throw. */
    private class RecordingSource(
        private val handler: (call: Int) -> PaymentIntentDto,
    ) : IntentSource {
        var calls: Int = 0
            private set

        override suspend fun retrieve(): PaymentIntentDto {
            calls++
            return handler(calls)
        }
    }

    /**
     * A [Clock] the test drives by hand.
     *
     * `sleep` parks on a deferred until the test advances past its wake time, so "the
     * poller is waiting" is an observable state ([pendingSleeps]) and cancelling a wait —
     * which is exactly what a nudge does — is observable too.
     */
    private class FakeClock : Clock {

        var nowMillis: Long = 0L
            private set

        val sleepRequests: MutableList<Long> = mutableListOf()

        private val sleepers: MutableList<Sleeper> = mutableListOf()

        val pendingSleeps: Int get() = sleepers.size

        override fun elapsedRealtime(): Long = nowMillis

        override suspend fun sleep(millis: Long) {
            if (millis <= 0L) return
            sleepRequests += millis
            val sleeper = Sleeper(nowMillis + millis, CompletableDeferred())
            sleepers += sleeper
            try {
                sleeper.wake.await()
            } finally {
                sleepers.remove(sleeper)
            }
        }

        fun advanceBy(millis: Long) {
            nowMillis += millis
            wakeDue()
        }

        /** Jumps to the earliest pending wake-up. False when nothing is sleeping. */
        fun advanceToNextWake(): Boolean {
            val next = sleepers.minOfOrNull { it.wakeAt } ?: return false
            nowMillis = maxOf(nowMillis, next)
            wakeDue()
            return true
        }

        private fun wakeDue() {
            sleepers.toList().forEach { if (it.wakeAt <= nowMillis) it.wake.complete(Unit) }
        }

        private class Sleeper(val wakeAt: Long, val wake: CompletableDeferred<Unit>)
    }

    /** Stands in for an `OutOfMemoryError` without the collateral damage of allocating one. */
    private class FakeVmError : Error("simulated VM error")

    private companion object {

        /** Longer than any ceiling under test: a read that is, for the test's purposes, stuck. */
        const val STUCK_READ_MILLIS = 10 * 60_000L

        /** How far [driveWithCeiling] moves virtual time when a read is in flight. */
        const val CEILING_STEP_MILLIS = 5_000L
    }
}
