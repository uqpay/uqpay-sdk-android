package com.uqpay.sdk.engine

import com.uqpay.sdk.Environment
import com.uqpay.sdk.error.UQPayErrorCode
import com.uqpay.sdk.network.ApiErrorBody
import com.uqpay.sdk.network.ErrorMapper
import com.uqpay.sdk.network.IntentStatus
import com.uqpay.sdk.network.PaymentAttemptDto
import com.uqpay.sdk.network.PaymentIntentDto
import com.uqpay.sdk.network.UQPayApiException
import com.uqpay.sdk.store.ConfirmAttemptStore
import com.uqpay.sdk.store.PersistedConfirmAttempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Disaster simulations for the one class that actually sends a payment.
 *
 * Three rules are defended here above everything else:
 *
 * 1. **An unknown outcome is never a decline.** A dropped connection, an unreadable 2xx, a
 *    5xx — the customer's money may already be moving. Reporting failure invites a second
 *    payment for the same order.
 * 2. **A replay is byte-identical, under the same key.** A gateway honours a reused
 *    idempotency key only for unchanged content; a replay that drifts is a rejection at
 *    best.
 * 3. **Nothing escapes the boundary.** No exception, and above all no `UQPayApiException`,
 *    whose `message` carries gateway text straight into a merchant's crash reporter.
 *
 * Every test runs on a fake clock, so the ~19s replay ladder completes in microseconds and
 * can be asserted on exactly.
 *
 * No real card number, CVC, idempotency key or API key appears in this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmRunnerTest {

    // ---- Fixtures --------------------------------------------------------------------

    /** Distinct per test, so the static pin registry cannot leak one test into another. */
    private val intentIds = AtomicInteger(0)

    private val store = FakeConfirmAttemptStore()

    private val deviceCaptures = AtomicInteger(0)

    private val frozenDevice = BrowserInfo(
        acceptHeader = "*/*",
        browser = BrowserDetails(
            javaEnabled = true,
            javascriptEnabled = true,
            userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8)",
            cookieEnabled = true,
            plugins = emptyList(),
            doNotTrack = false,
        ),
        deviceId = "device-pinned",
        language = "en-US",
        mobile = MobileInfo("Pixel 8", "ANDROID", "Android 14", null),
        screenColorDepth = 24,
        screenHeight = 2400,
        screenWidth = 1080,
        timezone = "8",
        touchSupport = true,
        hardwareConcurrency = 8,
        deviceMemory = 8,
    )

    private var currentDevice = frozenDevice
    private var currentIp: String? = "192.168.1.42"

    private fun registry() = ConfirmIdempotency(
        store = store,
        browserInfo = {
            deviceCaptures.incrementAndGet()
            currentDevice
        },
        ipAddress = { currentIp },
        now = { FIXED_NOW },
    )

    private fun payload(intentId: String) = ConfirmPayload.Card(
        paymentIntentId = intentId,
        cardNumber = "4242424242424242",
        expiryMonth = "12",
        expiryYear = "2030",
        cvc = "917",
        cardholderName = "Ada Lovelace",
        network = "visa",
        billing = ConfirmBilling(firstName = "Ada", lastName = "Lovelace", countryCode = "SG"),
    )

    private fun nextIntentId(): String = "int_${intentIds.incrementAndGet()}_${javaClass.simpleName}"

    @Before
    fun setUp() {
        ConfirmIdempotency.clearInMemoryOnlyForTest()
    }

    @After
    fun tearDown() {
        ConfirmIdempotency.clearInMemoryOnlyForTest()
    }

    // ---- Network loss after send ------------------------------------------------------

    /**
     * The bytes went out and nothing came back. **The payment may have been taken.**
     *
     * The ladder resends the identical request — same key, same body — because the
     * gateway's answer *to this key* is the only attribution that cannot lie: if the first
     * send arrived, the replay returns its recorded result; if it did not, the replay
     * performs it. Either way no replay is a second charge.
     */
    @Test
    fun `network loss after send replays the identical request and keeps the pin`() = runTest {
        val intentId = nextIntentId()
        val idempotency = registry()
        val clock = FakeClock()
        val sender = RecordingSender { throw UQPayApiException.TransportFailure(IOException("no route")) }

        val outcome = drive(clock) {
            runner(idempotency, clock).run(payload(intentId), settledSource(null), sender)
        }

        assertTrue("an unobserved payment is unresolved, never failed", outcome is ConfirmOutcome.Unresolved)
        assertEquals("the first send plus the three-rung ladder", 4, sender.requests.size)
        assertEquals(
            "every replay used the same idempotency key",
            1,
            sender.requests.map { it.key }.toSet().size,
        )
        assertEquals(
            "every replay sent byte-identical content",
            1,
            sender.requests.map { it.body }.toSet().size,
        )
        assertEquals("the pin survives for the next tap", 1, store.records.size)
    }

    /** The ladder is the iOS one: 3s, then 6s, then 10s, on the injected clock. */
    @Test
    fun `the replay ladder waits three then six then ten seconds`() = runTest {
        val clock = FakeClock()
        val sender = RecordingSender { throw UQPayApiException.TimedOut() }

        drive(clock) { runner(registry(), clock).run(payload(nextIntentId()), settledSource(null), sender) }

        assertEquals(listOf(3_000L, 6_000L, 10_000L), clock.sleepRequests)
    }

    /**
     * **Suspended time spends no ladder budget.**
     *
     * The device sleeps for a day between rungs — dozing, backgrounded, frozen by App
     * Standby. The ladder still issues exactly four sends, because it counts attempts and
     * never compares a clock reading to a deadline. iOS shipped the deadline version and
     * had to fix it: a customer who left for their banking app came back to a payment the
     * SDK had already abandoned purely because time had passed.
     */
    @Test
    fun `suspended time spends no ladder budget`() = runTest {
        val clock = FakeClock()
        val sender = RecordingSender { throw UQPayApiException.TransportFailure(IOException("down")) }

        val running = async {
            runner(registry(), clock).run(payload(nextIntentId()), settledSource(null), sender)
        }
        var guard = 0
        while (!running.isCompleted) {
            runCurrent()
            if (running.isCompleted) break
            // A whole day passes at every rung.
            clock.advanceBy(86_400_000L)
            if (guard++ > 100) fail("the ladder did not terminate")
        }
        running.await()

        assertEquals("four sends, however much time passed", 4, sender.requests.size)
    }

    // ---- The unreadable 2xx -----------------------------------------------------------

    /**
     * A 2xx arrived and could not be parsed. **The payment was processed** — the request
     * reached the gateway and was acted on, we simply could not read the answer.
     *
     * Reporting this as a decline would be the worst available answer: the merchant
     * releases nothing while the customer's statement shows a charge.
     */
    @Test
    fun `a two hundred that cannot be decoded is never reported as a decline`() = runTest {
        val clock = FakeClock()
        val sender = RecordingSender {
            throw UQPayApiException.DecodingFailure(200, "trace-1", IllegalStateException("bad json"))
        }

        val outcome = drive(clock) {
            runner(registry(), clock).run(payload(nextIntentId()), settledSource(null), sender)
        }

        val unresolved = outcome as ConfirmOutcome.Unresolved
        assertEquals(
            "TIMEOUT is the code a PENDING result carries: wait for the webhook",
            UQPayErrorCode.TIMEOUT,
            unresolved.error.code,
        )
        assertEquals("it was replayed like any other unknown outcome", 4, sender.requests.size)
        assertEquals("and the pin is kept", 1, store.records.size)
    }

    // ---- Envelope B: the idempotent request still in flight ---------------------------

    /**
     * HTTP 400 that is not a client error at all: the original confirm under this key is
     * still running and may succeed. Backing off and replaying the same key is the only
     * safe move — minting a new one would open a second attempt on a live payment.
     */
    @Test
    fun `idempotency in flight backs off and replays the same key`() = runTest {
        val clock = FakeClock()
        val confirmed = PaymentIntentDto(paymentIntentId = "int_x", intentStatus = "SUCCEEDED")
        val sender = RecordingSender { call ->
            if (call < 3) {
                throw UQPayApiException.IdempotencyInFlight(
                    ApiErrorBody(code = "200", message = "Request is processing, please try again later."),
                    null,
                    400,
                )
            }
            confirmed
        }

        val outcome = drive(clock) {
            runner(registry(), clock).run(payload(nextIntentId()), settledSource(null), sender)
        }

        assertSame(confirmed, (outcome as ConfirmOutcome.Confirmed).intent)
        assertEquals(3, sender.requests.size)
        assertEquals(1, sender.requests.map { it.key }.toSet().size)
        assertEquals("it backed off before each replay", listOf(3_000L, 6_000L), clock.sleepRequests)
        assertEquals("a definitive answer ends the attempt", 0, store.records.size)
    }

    // ---- Ladder exhaustion ------------------------------------------------------------

    /**
     * The ladder ran out with the outcome still unknown. The pin is **kept**, so the next
     * tap replays this very attempt rather than opening a second one — which is the whole
     * point of surrendering to reconciliation rather than to a fresh mint.
     */
    @Test
    fun `ladder exhaustion leaves the pin in place for the next tap`() = runTest {
        val intentId = nextIntentId()
        val idempotency = registry()
        val clock = FakeClock()
        val sender = RecordingSender { throw UQPayApiException.TransportFailure(IOException("gone")) }
        val payload = payload(intentId)

        drive(clock) { runner(idempotency, clock).run(payload, settledSource(null), sender) }

        val keyFromTheFailedRun = sender.requests.first().key
        // The customer taps Pay again with the same details.
        val replayed = idempotency.attempt(payload.digest(), intentId)

        assertEquals("the next tap replays, it does not mint", keyFromTheFailedRun, replayed.key)
        assertEquals("and no second device capture happened", 1, deviceCaptures.get())
    }

    // ---- The pre-confirm intercept (G3) -----------------------------------------------

    /**
     * **Fails open.** A flaky status GET must never become a new way to refuse a payment:
     * double-charge protection belongs to the idempotency machinery, and this check is a UX
     * affordance on top of it.
     */
    @Test
    fun `the intercept fails open and the confirm is still sent`() = runTest {
        val clock = FakeClock()
        val confirmed = PaymentIntentDto(paymentIntentId = "int_x", intentStatus = "SUCCEEDED")
        val sender = RecordingSender { confirmed }
        val source = IntentSource { throw UQPayApiException.TransportFailure(IOException("no route")) }

        val outcome = drive(clock) {
            runner(registry(), clock).run(payload(nextIntentId()), source, sender)
        }

        assertTrue(outcome is ConfirmOutcome.Confirmed)
        assertEquals("the confirm went out anyway", 1, sender.requests.size)
    }

    /** An `Error` from the pre-confirm read must not take the payment down either. */
    @Test
    fun `the intercept fails open even on a non-Exception throwable`() = runTest {
        val clock = FakeClock()
        val sender = RecordingSender { PaymentIntentDto(intentStatus = "SUCCEEDED") }
        val source = IntentSource { throw FakeVmError() }

        val outcome = drive(clock) {
            runner(registry(), clock).run(payload(nextIntentId()), source, sender)
        }

        assertTrue(outcome is ConfirmOutcome.Confirmed)
        assertEquals(1, sender.requests.size)
    }

    /**
     * The payment went through just before the app was killed. Relaunch recovery reports
     * the success it finds — and, critically, **mints no pin and sends no confirm**, which
     * is why the intercept runs before the registry is touched at all.
     */
    @Test
    fun `a settled succeeded intent mints no pin and sends no confirm`() = runTest {
        val clock = FakeClock()
        val sender = RecordingSender { PaymentIntentDto(intentStatus = "SUCCEEDED") }
        val settled = PaymentIntentDto(paymentIntentId = "int_x", intentStatus = "SUCCEEDED")

        val outcome = drive(clock) {
            runner(registry(), clock).run(payload(nextIntentId()), settledSource(settled), sender)
        }

        val alreadySettled = outcome as ConfirmOutcome.AlreadySettled
        assertSame(settled, alreadySettled.intent)
        assertEquals(IntentStatus.Succeeded, alreadySettled.status)
        assertNull("a settled success is not an error", alreadySettled.error)
        assertTrue("no pin was minted", store.saves.isEmpty())
        assertEquals("no device was captured", 0, deviceCaptures.get())
        assertTrue(sender.requests.isEmpty())
    }

    /**
     * Authorised but not captured. The customer has paid; confirming again would ask the
     * gateway to authorise a second time and, on rejection, would report a failure for a
     * payment that succeeded.
     *
     * This is a deliberate divergence from iOS, whose `interceptTerminalIntent` matches only
     * the three terminal statuses and re-confirms a `REQUIRES_CAPTURE` intent.
     */
    @Test
    fun `a settled requires-capture intent is reported as the success it is`() = runTest {
        val clock = FakeClock()
        val sender = RecordingSender { PaymentIntentDto(intentStatus = "SUCCEEDED") }
        val settled = PaymentIntentDto(paymentIntentId = "int_x", intentStatus = "REQUIRES_CAPTURE")

        val outcome = drive(clock) {
            runner(registry(), clock).run(payload(nextIntentId()), settledSource(settled), sender)
        }

        val alreadySettled = outcome as ConfirmOutcome.AlreadySettled
        assertEquals(IntentStatus.RequiresCapture, alreadySettled.status)
        assertNull(alreadySettled.error)
        assertTrue("an authorised payment must never be confirmed again", sender.requests.isEmpty())
        assertTrue(store.saves.isEmpty())
    }

    /** A payment that died while nobody was watching, reported through the one mapper. */
    @Test
    fun `a settled failed intent is reported through the settled-outcome mapper`() = runTest {
        val clock = FakeClock()
        val sender = RecordingSender { PaymentIntentDto(intentStatus = "SUCCEEDED") }
        val settled = PaymentIntentDto(
            paymentIntentId = "int_x",
            intentStatus = "FAILED",
            latestPaymentAttempt = PaymentAttemptDto(
                attemptStatus = "FAILED",
                failureCode = "insufficient_funds",
                failureMessage = "Insufficient funds on the card",
            ),
        )

        val outcome = drive(clock) {
            runner(registry(), clock).run(payload(nextIntentId()), settledSource(settled), sender)
        }

        val error = (outcome as ConfirmOutcome.AlreadySettled).error
        assertNotNull(error)
        assertEquals(UQPayErrorCode.INSUFFICIENT_FUNDS, error!!.code)
        assertEquals("insufficient_funds", error.declineCode)
        assertTrue("a dead payment must never be confirmed again", sender.requests.isEmpty())
        assertTrue("no pin was minted", store.saves.isEmpty())
    }

    /** The customer's cancellation outranks whatever the last attempt reported. */
    @Test
    fun `a settled cancelled intent is reported as cancelled`() = runTest {
        val clock = FakeClock()
        val settled = PaymentIntentDto(
            paymentIntentId = "int_x",
            intentStatus = "CANCELLED",
            latestPaymentAttempt = PaymentAttemptDto(failureCode = "card_declined"),
        )

        val sender = RecordingSender { PaymentIntentDto(intentStatus = "SUCCEEDED") }

        val outcome = drive(clock) {
            runner(registry(), clock).run(payload(nextIntentId()), settledSource(settled), sender)
        }

        assertEquals(
            UQPayErrorCode.CANCELLED,
            (outcome as ConfirmOutcome.AlreadySettled).error?.code,
        )
        assertTrue("a cancelled payment must never be confirmed again", sender.requests.isEmpty())
    }

    /** Everything still payable proceeds, including a status this SDK version predates. */
    @Test
    fun `a payable intent proceeds to the confirm`() = runTest {
        val clock = FakeClock()
        listOf("REQUIRES_PAYMENT_METHOD", "REQUIRES_CUSTOMER_ACTION", "PENDING", "SOMETHING_NEW")
            .forEach { status ->
                val sender = RecordingSender { PaymentIntentDto(intentStatus = "SUCCEEDED") }
                val outcome = drive(clock) {
                    runner(registry(), clock).run(
                        payload(nextIntentId()),
                        settledSource(PaymentIntentDto(intentStatus = status)),
                        sender,
                    )
                }
                assertTrue("$status must not be intercepted", outcome is ConfirmOutcome.Confirmed)
                assertEquals(1, sender.requests.size)
            }
    }

    // ---- F4: the engine boundary ------------------------------------------------------

    /**
     * A raw `UQPayApiException` reaching a merchant is a leak, not just untidiness: its
     * `message` is gateway text, and an exception that escapes takes that text into
     * whatever crash reporter the merchant has installed.
     */
    @Test
    fun `no api exception escapes the boundary`() = runTest {
        val clock = FakeClock()
        val sender = RecordingSender {
            throw UQPayApiException.ApiError(
                ApiErrorBody(type = "payment_error", code = "card_declined", message = GATEWAY_TEXT),
                "trace-9",
                402,
            )
        }

        val outcome = drive(clock) {
            runner(registry(), clock).run(payload(nextIntentId()), settledSource(null), sender)
        }

        val failed = outcome as ConfirmOutcome.Failed
        assertEquals(UQPayErrorCode.CARD_DECLINED, failed.error.code)
        assertEquals("card_declined", failed.error.declineCode)
        assertEquals("trace-9", failed.error.traceId)
        assertEquals("a definitive rejection ends the ladder at once", 1, sender.requests.size)
        assertEquals("and releases the pin", 0, store.records.size)
    }

    /** In production the gateway's own sentence never reaches the merchant's UI. */
    @Test
    fun `no gateway text reaches the merchant in production`() = runTest {
        val clock = FakeClock()
        val sender = RecordingSender {
            throw UQPayApiException.ApiError(
                ApiErrorBody(code = "card_declined", message = GATEWAY_TEXT),
                null,
                402,
            )
        }

        val outcome = drive(clock) {
            runner(registry(), clock, Environment.PRODUCTION)
                .run(payload(nextIntentId()), settledSource(null), sender)
        }

        val message = (outcome as ConfirmOutcome.Failed).error.message
        assertFalse("gateway text must not be surfaced in production", message.contains(GATEWAY_TEXT))
        assertEquals("The card was declined. Please try a different payment method.", message)
    }

    /**
     * An unclassifiable throwable from inside the send path. It may have been raised
     * *after* the bytes went out, so the honest answer is unknown — and its message, which
     * could contain anything at all, must not travel with it.
     */
    @Test
    fun `a raw runtime exception becomes an unresolved outcome and leaks nothing`() = runTest {
        val clock = FakeClock()
        val sender = RecordingSender { throw IllegalStateException(GATEWAY_TEXT) }

        val outcome = drive(clock) {
            runner(registry(), clock, Environment.PRODUCTION)
                .run(payload(nextIntentId()), settledSource(null), sender)
        }

        val unresolved = outcome as ConfirmOutcome.Unresolved
        assertEquals(UQPayErrorCode.UNKNOWN, unresolved.error.code)
        assertFalse(unresolved.error.message.contains(GATEWAY_TEXT))
        assertEquals("an unknown outcome is replayed like any other", 4, sender.requests.size)
        assertEquals("and keeps its pin", 1, store.records.size)
    }

    /** Even an `Error` leaves as an outcome. Nothing at all escapes [ConfirmRunner.run]. */
    @Test
    fun `an Error from the sender does not escape the boundary`() = runTest {
        val clock = FakeClock()
        val sender = RecordingSender { throw FakeVmError() }

        val outcome = drive(clock) {
            runner(registry(), clock).run(payload(nextIntentId()), settledSource(null), sender)
        }

        assertTrue("unknown, because it may have been raised after the send", outcome is ConfirmOutcome.Unresolved)
        assertEquals("an Error is not replayed", 1, sender.requests.size)
        assertEquals("the pin is kept", 1, store.records.size)
    }

    /** A broken store cannot crash a payment; it only costs relaunch recovery. */
    @Test
    fun `a store that throws does not stop the confirm`() = runTest {
        val clock = FakeClock()
        val idempotency = ConfirmIdempotency(
            store = ThrowingStore,
            browserInfo = { frozenDevice },
            ipAddress = { currentIp },
            now = { FIXED_NOW },
        )
        val payload = payload(nextIntentId())
        val sends = mutableListOf<SentRequest>()
        val replayedDuringSend = mutableListOf<String>()
        val sender = ConfirmSender { body, key ->
            sends += SentRequest(body, key)
            // While this send is in flight the registry — memory only, disk gone — must
            // still hand a re-entrant tap the *same* key, not mint a second attempt.
            replayedDuringSend += idempotency.attempt(payload.digest(), payload.paymentIntentId).key
            PaymentIntentDto(intentStatus = "SUCCEEDED")
        }

        val outcome = drive(clock) {
            runner(idempotency, clock).run(payload, settledSource(null), sender)
        }

        assertTrue(outcome is ConfirmOutcome.Confirmed)
        assertEquals("exactly one send", 1, sends.size)
        assertTrue("the send carried a real key", sends.single().key.isNotBlank())
        assertEquals("the in-memory pin replays the sent key", listOf(sends.single().key), replayedDuringSend)
        // A definitive answer resolves the pin in memory even though the store cannot be
        // rewritten: the next attempt for the same payload is a fresh key, not a stale replay.
        val afterwards = idempotency.attempt(payload.digest(), payload.paymentIntentId).key
        assertTrue(afterwards.isNotBlank())
        assertNotEquals("resolved despite the broken store", sends.single().key, afterwards)
    }

    // ---- Cancellation ------------------------------------------------------------------

    /**
     * The customer walked away, or a newer tap superseded this send. A cancelled confirm
     * reports **nothing** and leaves the pin exactly as it was — so if the customer pays
     * again with the same details, the send is a replay of this very attempt rather than a
     * second one against a payment that may already be authorising.
     */
    @Test
    fun `cancellation mid-ladder reports nothing and leaves the pin untouched`() = runTest {
        val intentId = nextIntentId()
        val idempotency = registry()
        val clock = FakeClock()
        val sender = RecordingSender { throw UQPayApiException.TransportFailure(IOException("dropped")) }
        val payload = payload(intentId)

        val running = async {
            runner(idempotency, clock).run(payload, settledSource(null), sender)
        }
        runCurrent()
        // One send has happened and the runner is parked on the first rung of the ladder.
        assertEquals(1, sender.requests.size)
        assertEquals(1, clock.pendingSleeps)

        val savesBeforeCancel = store.saves.size
        running.cancel()
        runCurrent()

        var thrown: Throwable? = null
        try {
            running.await()
        } catch (cancellation: CancellationException) {
            thrown = cancellation
        }

        assertTrue("cancellation propagates; it is not swallowed", thrown is CancellationException)
        assertEquals("no further sends", 1, sender.requests.size)
        assertEquals("the pin was neither resolved nor rewritten", savesBeforeCancel, store.saves.size)
        assertEquals(
            "and it still replays for the next tap",
            sender.requests.first().key,
            idempotency.attempt(payload.digest(), intentId).key,
        )
    }

    /**
     * A cancellation thrown by the **send itself**, not by the wait.
     *
     * `CancellationException` is an ordinary `Exception`, so a catch-all one rung up will
     * silently absorb it — and the damage is not merely a broken coroutine contract. An
     * absorbed cancellation is classified as an unknown outcome, replayed three more times
     * against a payment the caller has already abandoned, and then *reported*, when a
     * cancelled confirm must report nothing at all.
     */
    @Test
    fun `a cancellation thrown by the sender propagates untouched`() = runTest {
        val clock = FakeClock()
        val sender = RecordingSender { throw CancellationException("the network layer was cancelled") }

        var thrown: Throwable? = null
        try {
            runner(registry(), clock).run(payload(nextIntentId()), settledSource(null), sender)
        } catch (cancellation: CancellationException) {
            thrown = cancellation
        }

        assertTrue("cancellation is never absorbed into an outcome", thrown is CancellationException)
        assertEquals("a cancelled send is never replayed", 1, sender.requests.size)
        assertTrue("and nothing waited on the ladder", clock.sleepRequests.isEmpty())
        assertEquals("the pin is left exactly as it was", 1, store.records.size)
    }

    /**
     * Cancellation wearing an API type. Treating it as anything else would report a failure
     * for a request the SDK itself abandoned — and would release a pin belonging to a send
     * that may still be in flight.
     */
    @Test
    fun `an api-level cancellation is treated as cancellation`() = runTest {
        val clock = FakeClock()
        val sender = RecordingSender { throw UQPayApiException.Cancelled() }

        var thrown: Throwable? = null
        try {
            runner(registry(), clock).run(payload(nextIntentId()), settledSource(null), sender)
        } catch (cancellation: CancellationException) {
            thrown = cancellation
        }

        assertTrue(thrown is CancellationException)
        assertEquals("no replay of a cancelled request", 1, sender.requests.size)
        assertEquals("the pin is left exactly as it was", 1, store.records.size)
    }

    // ---- The happy path, and the frozen body ------------------------------------------

    /** A definitive answer ends the attempt: the next tap is a new payment, with a new key. */
    @Test
    fun `a confirmed payment resolves its pin`() = runTest {
        val clock = FakeClock()
        val confirmed = PaymentIntentDto(paymentIntentId = "int_x", intentStatus = "REQUIRES_CUSTOMER_ACTION")
        val sender = RecordingSender { confirmed }

        val outcome = drive(clock) {
            runner(registry(), clock).run(payload(nextIntentId()), settledSource(null), sender)
        }

        assertSame(confirmed, (outcome as ConfirmOutcome.Confirmed).intent)
        assertEquals(1, sender.requests.size)
        assertTrue("nothing waited", clock.sleepRequests.isEmpty())
        assertEquals(0, store.records.size)
    }

    /**
     * The body carries the **frozen** device values, not whatever the device reads now.
     *
     * Rotation and a Wi-Fi-to-cellular switch change screen dimensions and the IP with no
     * customer action at all. A replay that re-measured them would send a changed body
     * under an unchanged key, which the gateway rejects rather than honours.
     */
    @Test
    fun `the body is built from the values frozen with the attempt`() = runTest {
        val intentId = nextIntentId()
        val idempotency = registry()
        val clock = FakeClock()
        var call = 0
        val sender = RecordingSender {
            call++
            if (call == 1) throw UQPayApiException.TransportFailure(IOException("dropped"))
            // Between the send and the replay the customer rotates the phone and leaves Wi-Fi.
            PaymentIntentDto(intentStatus = "SUCCEEDED")
        }
        currentDevice = frozenDevice
        currentIp = "192.168.1.42"

        val running = async {
            runner(idempotency, clock).run(payload(intentId), settledSource(null), sender)
        }
        runCurrent()
        currentDevice = frozenDevice.copy(screenHeight = 1080, screenWidth = 2400)
        currentIp = "10.20.30.40"
        drive(clock) { running.await() }

        assertEquals(2, sender.requests.size)
        assertEquals(
            "the replay is byte-identical despite the device having changed",
            sender.requests[0].body,
            sender.requests[1].body,
        )
        assertTrue(sender.requests[0].body.contains("\"ip_address\":\"192.168.1.42\""))
        assertTrue(sender.requests[0].body.contains("\"screen_height\":2400"))
    }

    // ---- The intent-id invariant ------------------------------------------------------

    /**
     * The runtime half of the rule that the intent id must be in the digest.
     *
     * A pin restored for this digest belongs to a *different* payment — which
     * `ConfirmPayload` makes impossible by construction, and which is checked anyway
     * because "impossible by construction" is a property of today's code. When it fires,
     * **nothing is sent**: a confirm aimed at the wrong intent is the mis-charge this whole
     * design exists to prevent, and the pin is left alone because it belongs to a payment
     * that may still be in flight.
     */
    @Test
    fun `a pin belonging to a different intent refuses to send anything`() = runTest {
        val clock = FakeClock()
        val payload = payload(nextIntentId())
        val idempotency = registry()
        // Simulate a digest that failed to carry the intent id: the same digest, pinned
        // earlier by a different payment.
        idempotency.attempt(payload.digest(), "int_a_completely_different_payment")
        val sender = RecordingSender { PaymentIntentDto(intentStatus = "SUCCEEDED") }

        val outcome = drive(clock) {
            runner(idempotency, clock).run(payload, settledSource(null), sender)
        }

        val failed = outcome as ConfirmOutcome.Failed
        assertEquals(UQPayErrorCode.INVALID_CONFIGURATION, failed.error.code)
        assertTrue(sender.requests.isEmpty())
        assertEquals("the other payment's pin is left alone", 1, store.records.size)
    }

    // ---- Harness ------------------------------------------------------------------------

    private fun runner(
        idempotency: ConfirmIdempotency,
        clock: FakeClock,
        environment: Environment = Environment.SANDBOX,
    ) = ConfirmRunner(
        idempotency = idempotency,
        errorMapper = ErrorMapper(environment),
        clock = clock,
    )

    /** An intent source that returns [intent], or a payable placeholder when null. */
    private fun settledSource(intent: PaymentIntentDto?): IntentSource = IntentSource {
        intent ?: PaymentIntentDto(paymentIntentId = "int_x", intentStatus = "REQUIRES_PAYMENT_METHOD")
    }

    /**
     * Runs [block] to completion, advancing the fake clock to each next wake-up. No real
     * time passes, and a run that parks with nothing scheduled fails loudly rather than
     * hanging the suite.
     */
    private suspend fun TestScope.drive(
        clock: FakeClock,
        block: suspend () -> ConfirmOutcome,
    ): ConfirmOutcome = drive(clock, async { block() })

    private suspend fun TestScope.drive(
        clock: FakeClock,
        running: Deferred<ConfirmOutcome>,
    ): ConfirmOutcome {
        var guard = 0
        while (!running.isCompleted) {
            runCurrent()
            if (running.isCompleted) break
            if (!clock.advanceToNextWake()) fail("the confirm parked with nothing in flight")
            if (guard++ > 1_000) fail("the confirm did not terminate")
        }
        return running.await()
    }

    /** One recorded send: the bytes and the key, which are what a replay must not change. */
    private data class SentRequest(val body: String, val key: String)

    private class RecordingSender(
        private val handler: (call: Int) -> PaymentIntentDto,
    ) : ConfirmSender {
        val requests: MutableList<SentRequest> = mutableListOf()

        override suspend fun send(body: String, idempotencyKey: String): PaymentIntentDto {
            requests += SentRequest(body, idempotencyKey)
            return handler(requests.size)
        }
    }

    private class FakeConfirmAttemptStore : ConfirmAttemptStore {
        var records: List<PersistedConfirmAttempt> = emptyList()
        val saves: MutableList<List<PersistedConfirmAttempt>> = mutableListOf()

        override fun load(): List<PersistedConfirmAttempt> = records

        override fun save(records: List<PersistedConfirmAttempt>) {
            saves += records
            this.records = records
        }
    }

    /** A store that violates its own never-throws contract, in both directions. */
    private object ThrowingStore : ConfirmAttemptStore {
        override fun load(): List<PersistedConfirmAttempt> = throw IllegalStateException("unavailable")

        override fun save(records: List<PersistedConfirmAttempt>): Unit =
            throw IllegalStateException("disk full")
    }

    /**
     * A [Clock] the test drives by hand.
     *
     * `sleep` parks on a deferred until the test advances past its wake time, so "the ladder
     * is waiting" is an observable state and cancelling a wait is observable too.
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
        /** A fixed wall-clock instant; nothing here depends on the real one. */
        const val FIXED_NOW = 1_786_924_800_000L

        /** Stands in for whatever the gateway might say. It must never reach a merchant. */
        const val GATEWAY_TEXT = "internal-decline-detail-do-not-surface"
    }
}
