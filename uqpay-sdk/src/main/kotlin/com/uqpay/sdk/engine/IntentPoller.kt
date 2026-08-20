package com.uqpay.sdk.engine

import com.uqpay.sdk.network.IntentStatus
import com.uqpay.sdk.network.PaymentIntentDto
import com.uqpay.sdk.network.UQPayApiClient
import com.uqpay.sdk.network.UQPayApiException
import com.uqpay.sdk.network.UQPayLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where the intent being watched is read from.
 *
 * A lambda rather than a [UQPayApiClient] plus an id, because this poller is a primitive
 * reused by three unrelated callers (3-D Secure, wallet QR, the post-dismissal
 * reconciler) and because a test needs to script a sequence of responses without a socket.
 * Use [forIntent] for the production wiring.
 */
internal fun interface IntentSource {

    suspend fun retrieve(): PaymentIntentDto

    companion object {
        /** The production source: one GET of one intent, per attempt. */
        fun forIntent(client: UQPayApiClient, paymentIntentId: String): IntentSource =
            IntentSource { client.retrieveIntent(paymentIntentId) }
    }
}

/**
 * A caller-supplied reason to stop polling before the intent settles.
 *
 * The poller's own stop rule (see [IntentPoller.poll]) only knows about settlement. Two
 * cases it cannot know about, both from the 3-D Secure flow:
 *
 * - **Multi-stage 3DS (G13).** The intent comes back still requiring customer action, but
 *   with a `next_action` of a *different type* than the one currently on screen — the
 *   issuer has moved the customer from a device-fingerprint iframe to a challenge, or the
 *   other way round. The intent has not settled, yet continuing to poll while the wrong
 *   page is displayed strands the customer.
 * - **3DS decline (G14).** The intent falls back to `REQUIRES_PAYMENT_METHOD` during a
 *   3DS poll. That status is ambiguous on its own, but *during a 3DS poll* it means the
 *   authentication failed. Polling on would burn the whole budget and then report a
 *   timeout for what is really a decline the merchant must hear about.
 *
 * Returning `true` produces [PollOutcome.EarlyReturn] and hands the intent back to the
 * caller, which owns the interpretation. The poller never guesses what the stop meant.
 */
internal fun interface EarlyReturnCheck {

    fun shouldStop(intent: PaymentIntentDto): Boolean

    companion object {
        /** The default: nothing stops the poll early. */
        val Never: EarlyReturnCheck = EarlyReturnCheck { false }
    }
}

/**
 * How long a poll runs, expressed as **a number of attempts**, never as a deadline.
 *
 * ### Why attempts and not a wall-clock deadline
 *
 * This is the single most important decision in this file, and it is a fix for a bug that
 * actually shipped. The iOS SDK held a wall-clock deadline across app suspension. The most
 * common real card journey — the customer leaves the app for their banking app to complete
 * 3-D Secure and comes back a minute later — therefore returned to a payment the SDK had
 * already declared timed out, purely because the customer was away. The payment was fine;
 * the SDK's clock was not. The fix was to count attempts.
 *
 * An attempt budget has the property a deadline lacks: **suspended time spends no budget.**
 * A process that is backgrounded, cached, frozen by App Standby, or dozing issues no polls,
 * so it consumes none of its allowance and resumes with everything it had. On Android that
 * matters more than on iOS, not less: the OS is more aggressive about stopping our work and
 * it can kill the process outright.
 *
 * The interval is the *spacing* between attempts, so `maxAttempts × intervalMillis` is a
 * lower bound on the wall-clock span of a poll that is never interrupted — it is not a
 * budget, and nothing in this file compares it to a clock reading.
 *
 * ### The attempt ceiling is a bound on a *read*, not on the poll
 *
 * [attemptCeilingMillis] is the one wall-clock value in this file, and it is deliberately not
 * a budget: it bounds how long a **single** `retrieve()` may take before it is abandoned and
 * held as a failed read. Nothing about it can end a poll early — the attempt is spent either
 * way and the loop continues — so the suspended-time property above is untouched.
 *
 * It exists because a read is not one request. Underneath it, `DefaultUQPayNetworkClient`
 * retries a safe GET three times with 2s/4s/8s backoff, each retry carrying its own 30s
 * connect and read timeouts, and each able to honour a `Retry-After`. One flaky read can
 * therefore occupy a minute or more while spending a single attempt, and a `WalletQr` budget
 * of 300 such attempts spans hours rather than the ten minutes it is sized for. The customer
 * meanwhile sees a QR nobody appears to be watching. Bounding the read puts the poll's real
 * duration back within sight of `maxAttempts × intervalMillis`.
 *
 * @property maxAttempts reads inside the loop. Values below 1 are treated as 1: a
 *   pathological budget must still take one look at the payment rather than report an
 *   unresolved outcome having never asked.
 * @property intervalMillis spacing between attempts, and the wait before the guaranteed
 *   final fetch. Zero or negative means no wait.
 * @property attemptCeilingMillis how long one read may take before it is abandoned and held
 *   as a failure. Zero or negative means no ceiling. Defaults to [DEFAULT_ATTEMPT_CEILING_MILLIS].
 */
internal data class PollBudget(
    val maxAttempts: Int,
    val intervalMillis: Long,
    val attemptCeilingMillis: Long = DEFAULT_ATTEMPT_CEILING_MILLIS,
) {
    companion object {

        /**
         * The default bound on one read: 45 seconds.
         *
         * Sized to admit a slow-but-working read and to refuse a stuck one. A single request
         * has a 30s connect and a 30s read timeout, so a first attempt that is merely slow
         * still finishes inside this; what it cuts off is the *retry chain* underneath —
         * three resends with backoff, or one honouring a long `Retry-After` — which is where
         * a read stops being a read and becomes an outage nobody is timing.
         *
         * Abandoning a read costs nothing but one attempt: the intent is re-read on the next
         * tick, and the abandoned attempt's held error is what the poller reports if the run
         * ends unresolved.
         */
        const val DEFAULT_ATTEMPT_CEILING_MILLIS: Long = 45_000L

        /**
         * Card 3-D Secure: ~150 attempts, 2s apart.
         *
         * Sized for the slowest realistic authentication — an issuer app, an SMS OTP that
         * has to arrive, a customer reading their card reader. Because the budget is in
         * attempts, the time the customer spends outside our app finding that OTP costs
         * nothing.
         */
        val ThreeDs: PollBudget = PollBudget(maxAttempts = 150, intervalMillis = 2_000L)

        /**
         * Wallet QR: **300 attempts, 2s apart** — ten minutes of foreground polling.
         *
         * Set by Slice 5 from the shipped iOS SDK's actual wallet polling rather than from
         * the plan's round number. `WalletQRPaymentViewController` watches with
         * `awaitThreeDSOutcome(timeout: 600, pollInterval: 2)`, and that helper computes its
         * budget as `Int(timeout / pollInterval)` — 300 attempts at 2s. The plan's
         * "~600s-equivalent" is therefore satisfied by *both* possible splits, and the tie is
         * broken by matching the client whose behaviour the gateway has already seen in
         * production.
         *
         * The split matters more than the product. A merchant-presented QR is scanned by a
         * human in another app: the interval sets how long a customer stares at a spinner
         * after they have paid, and 2s is at the edge of what reads as instant. Ten minutes
         * of attempts is sized for the whole ritual — open the wallet, find the scanner,
         * approve, wait for the wallet's own confirmation — with room for a slow one.
         *
         * Longer would not be safer. Exhaustion is not a failure: it settles `PENDING`, keeps
         * the idempotency pin, and — critically — keeps the wallet latch, so the QR on the
         * customer's screen stays the only one in existence. See
         * [com.uqpay.sdk.engine.WalletConfirmLatch.attemptFinished].
         */
        val WalletQr: PollBudget = PollBudget(maxAttempts = 300, intervalMillis = 2_000L)

        /**
         * Post-dismissal reconciliation: 12 attempts, 5s apart.
         *
         * Short on purpose. This one is not watched by anybody — its job is to resolve the
         * idempotency pin and the wallet latch after the customer has gone, not to keep a
         * screen honest.
         */
        val Reconciliation: PollBudget = PollBudget(maxAttempts = 12, intervalMillis = 5_000L)
    }
}

/**
 * How a poll ended. Three outcomes, and the third is the one with the history.
 */
internal sealed class PollOutcome {

    /**
     * The intent reached a state the customer cannot change: succeeded, failed, cancelled,
     * or authorised and awaiting capture.
     *
     * @property status parsed once here so the caller does not re-parse and risk applying a
     *   different rule to the same string.
     */
    data class Settled(
        val intent: PaymentIntentDto,
        val status: IntentStatus,
    ) : PollOutcome()

    /** The caller's [EarlyReturnCheck] matched. The caller decides what it meant. */
    data class EarlyReturn(val intent: PaymentIntentDto) : PollOutcome()

    /**
     * The budget ran out — including the guaranteed final fetch — without the intent
     * settling.
     *
     * **This is not a failure, and it is emphatically not a cancellation.** The customer's
     * money may well be moving; all we know is that we stopped looking. The engine reports
     * this as `PENDING`, keeps the idempotency pin, and keeps the wallet latch.
     *
     * Two references get this wrong in opposite directions and both are instructive.
     * Stripe's PaymentSheet reports `Canceled` for an unknown outcome
     * (`DefaultConfirmationHandler.kt:69-73`) — that is the exact false-cancellation bug
     * our own iOS SDK shipped and had to fix as a breaking change, because a merchant who
     * is told "cancelled" releases the goods to nobody and refunds nothing. Reporting
     * `FAILED` would be worse still: it invites the customer to pay a second time.
     *
     * @property lastIntent the most recent intent we managed to read, if any. Null only
     *   when every single attempt threw.
     * @property heldError the most recent poll error, if the run ended on one. See
     *   [IntentPoller.poll] for why this outranks the generic timeout.
     * @property attemptsMade reads actually issued, including the final fetch. Diagnostics
     *   only.
     */
    data class Unresolved(
        val lastIntent: PaymentIntentDto?,
        val heldError: Exception?,
        val attemptsMade: Int,
    ) : PollOutcome()
}

/**
 * Re-reads a payment intent until it settles, a caller-supplied check stops it, or the
 * attempt budget runs out.
 *
 * One primitive, three configurations (see [PollBudget]). It owns no networking, no UI,
 * and no interpretation of the result: it decides *when to look* and hands back *what it
 * saw*.
 *
 * ### The stop rule
 *
 * Polling stops when the intent is no longer payable — that is, terminal *or*
 * `REQUIRES_CAPTURE`. `IntentStatus.isPayable` already encodes exactly this split, and it
 * is deliberately not the same rule the pre-confirm interceptor uses: an authorised,
 * uncaptured payment must never be charged again (so the interceptor treats it as
 * unsettled and refuses to confirm) but it *has* been paid by the customer (so a watcher
 * like this one treats it as an outcome). Do not unify the two.
 *
 * An `IntentStatus.Unknown` — a status this SDK version predates — is payable by
 * definition, so the poll continues. Reporting an outcome we cannot name would be worse
 * than waiting.
 *
 * ### Held errors (G15)
 *
 * A single flaky GET must never abandon a live payment. When an attempt throws, the error
 * is *held* and polling continues; a later good read clears it. If the run then ends
 * unresolved, the held error is reported in [PollOutcome.Unresolved.heldError] and outranks
 * the generic "we ran out of attempts" story, because "your last four reads were refused
 * by the gateway" is a materially better thing to tell a support engineer than "it timed
 * out".
 *
 * Every non-cancellation [Exception] is held, including ones that look definitive. A poller
 * is a reader, not a decider: a 404 from a load balancer mid-rollout is not evidence the
 * payment does not exist, and the cost of being wrong (abandoning a live payment) is far
 * higher than the cost of a few wasted reads. `Error`s are *not* caught — an
 * `OutOfMemoryError` is not a flaky poll and must reach the engine boundary.
 * `CancellationException` is not caught either: cancellation is structured concurrency
 * doing its job, and a cancelled poll reports nothing at all.
 *
 * ### The guaranteed final fetch
 *
 * When the budget runs out, one more read is always issued before reporting
 * [PollOutcome.Unresolved]. Adapted from stripe-android's
 * `PaymentFlowResultProcessor.kt:270-283` ("Ensures we always call retrieve at least once
 * after the polling duration"). The case it exists for: a process that was frozen for the
 * whole poll, or a payment that settles one poll short of the end. Abandoning a payment
 * that had already succeeded, without looking one last time, is not a defensible outcome.
 *
 * ### Threading and reuse
 *
 * [poll] runs on whatever context the caller provides — the engine passes an injected
 * scope, and this class never creates one tied to a lifecycle. One [poll] at a time per
 * instance: [nudge] targets "the wait currently in progress", and two concurrent polls
 * would make that ambiguous.
 *
 * @property startedAtElapsedRealtime when this poll began, as a [Clock.elapsedRealtime]
 *   reading. Defaults to now.
 *
 *   Exposed — and accepted as a parameter — so a caller can persist it (a
 *   `SavedStateHandle`, say) and hand it back to a poller rebuilt after process death,
 *   which is how stripe-android's `PollingViewModel` keeps its own timing honest across a
 *   restart (`:34, :162-179`). Elapsed realtime rather than a wall-clock instant, so it is
 *   monotonic and cannot be moved by the device's owner.
 *
 *   **It spends no budget.** Nothing in this class compares it to a deadline; the budget is
 *   attempts and only attempts. This value is for telling the customer how long they have
 *   been waiting, and for diagnostics — never for deciding to stop. See
 *   [elapsedSinceStartMillis] for the reboot caveat.
 */
internal class IntentPoller(
    private val source: IntentSource,
    private val budget: PollBudget,
    private val clock: Clock,
    private val earlyReturn: EarlyReturnCheck = EarlyReturnCheck.Never,
    private val logger: UQPayLogger = UQPayLogger.Noop,
    val startedAtElapsedRealtime: Long = clock.elapsedRealtime(),
) {

    /** Reads issued so far by the current or last [poll], including the final fetch. */
    @Volatile
    var attemptsMade: Int = 0
        private set

    /**
     * The wait currently in progress, or null when a read is in flight or no poll is
     * running. Written from the polling coroutine, read from any thread by [nudge].
     */
    @Volatile
    private var pendingWait: Job? = null

    /**
     * How long the customer has been waiting, or **null if that cannot be known**.
     *
     * Elapsed realtime resets to zero at boot, so a [startedAtElapsedRealtime] restored
     * from disk after a reboot is *ahead* of the current reading. That shows up here as a
     * negative difference, and the honest answer then is "no idea" rather than a
     * nonsensical duration or a silently clamped zero. Callers restoring a persisted start
     * must handle the null.
     */
    fun elapsedSinceStartMillis(): Long? =
        (clock.elapsedRealtime() - startedAtElapsedRealtime).takeIf { it >= 0L }

    /**
     * Replaces the wait currently in progress with an immediate re-read.
     *
     * Slice 6 wires this to app-foreground via a `DefaultLifecycleObserver`: a customer
     * returning from their banking app should see the result of their 3-D Secure
     * immediately, not up to one poll interval later. The seam exists now so that wiring is
     * a one-line change rather than surgery on the loop.
     *
     * It **replaces** the wait rather than adding a poll: the pending delay is cancelled,
     * the loop proceeds to its next scheduled attempt, and that attempt spends the budget it
     * would have spent anyway. A nudge storm cannot inflate the number of reads beyond the
     * budget — it can only make them happen sooner.
     *
     * Safe to call from any thread, at any time, including when no poll is running. A nudge
     * that arrives while a read is already in flight is deliberately a no-op: a fresh look
     * at the payment is already happening, which is exactly what the nudge was asking for.
     */
    fun nudge() {
        pendingWait?.cancel()
    }

    /**
     * Runs the poll to one of the three [PollOutcome]s. See the class KDoc for the rules.
     */
    suspend fun poll(): PollOutcome {
        // Values below 1 still get one look; see PollBudget.maxAttempts.
        val budgetedAttempts = budget.maxAttempts.coerceAtLeast(1)

        attemptsMade = 0
        var heldError: Exception? = null
        var lastIntent: PaymentIntentDto? = null

        for (attempt in 1..budgetedAttempts) {
            when (val read = readOnce()) {
                is Read.Failed -> heldError = read.error
                is Read.Succeeded -> {
                    // A good read clears the held error: whatever was wrong has passed, and
                    // reporting a stale error alongside a fresh view of the payment would
                    // describe a problem that no longer exists.
                    heldError = null
                    lastIntent = read.intent
                    settlementOf(read.intent)?.let { return it }
                }
            }
            // No trailing wait on the last budgeted attempt — the wait before the
            // guaranteed final fetch below provides the spacing instead.
            if (attempt < budgetedAttempts) awaitInterval()
        }

        logger.debug("Poll budget of $budgetedAttempts attempts exhausted; issuing final read")

        // The guaranteed final fetch. One interval of grace first, so this is a genuinely
        // fresh look rather than a duplicate of the read that just happened — and it is
        // nudgeable, so a customer returning to the app at this moment gets their answer
        // without waiting it out.
        awaitInterval()
        when (val read = readOnce()) {
            // The newest error replaces an older held one: it describes the state of the
            // world at the moment we gave up, which is the state worth reporting.
            is Read.Failed -> heldError = read.error
            is Read.Succeeded -> {
                heldError = null
                lastIntent = read.intent
                settlementOf(read.intent)?.let { return it }
            }
        }

        return PollOutcome.Unresolved(
            lastIntent = lastIntent,
            heldError = heldError,
            attemptsMade = attemptsMade,
        )
    }

    /**
     * Applies the two stop rules, in order, to a freshly read intent. Null means keep going.
     *
     * Settlement is checked before the caller's early-return check: a payment that has
     * actually settled is a fact, and a caller's heuristic about a changed `next_action`
     * must not shadow it.
     */
    private fun settlementOf(intent: PaymentIntentDto): PollOutcome? {
        val status = IntentStatus.from(intent.intentStatus)
        if (!status.isPayable) {
            logger.debug("Poll settled after $attemptsMade attempt(s)")
            return PollOutcome.Settled(intent, status)
        }
        if (earlyReturn.shouldStop(intent)) {
            logger.debug("Poll stopped early after $attemptsMade attempt(s)")
            return PollOutcome.EarlyReturn(intent)
        }
        return null
    }

    /** One read, with the held-error classification of the class KDoc applied. */
    private suspend fun readOnce(): Read {
        attemptsMade++
        return try {
            val intent = retrieveWithinCeiling()
                ?: return Read.Failed(UQPayApiException.TimedOut()).also {
                    logger.debug(
                        "Poll attempt $attemptsMade exceeded its ${budget.attemptCeilingMillis}ms " +
                            "ceiling; abandoning the read and holding it as a failure",
                    )
                }
            Read.Succeeded(intent)
        } catch (cancellation: CancellationException) {
            // Never held. A cancelled poll reports nothing to anybody.
            throw cancellation
        } catch (error: Exception) {
            // Class name only. A gateway message can contain anything and this logger's
            // hard rule is that no body text reaches it.
            logger.debug("Poll attempt $attemptsMade failed (${error.javaClass.simpleName}); holding")
            Read.Failed(error)
        }
    }

    /**
     * One `retrieve()`, abandoned if it outlasts [PollBudget.attemptCeilingMillis]. Null means
     * it was abandoned; see the [PollBudget] KDoc for why a read needs a bound of its own.
     *
     * [withTimeoutOrNull] rather than `withTimeout`: the latter reports the expiry as a
     * [kotlinx.coroutines.TimeoutCancellationException], which is a [CancellationException],
     * and [readOnce]'s rule — correctly — is that a cancellation ends the whole poll and
     * reports nothing. A read that ran long is not the customer walking away. Cancellation of
     * the *caller* still propagates out of here, exactly as it must.
     */
    private suspend fun retrieveWithinCeiling(): PaymentIntentDto? {
        val ceiling = budget.attemptCeilingMillis
        if (ceiling <= 0L) return source.retrieve()
        return withTimeoutOrNull(ceiling) { source.retrieve() }
    }

    /**
     * Waits one interval, unless [nudge] cuts it short.
     *
     * The wait runs as a child coroutine so it can be cancelled independently. Cancelling a
     * child does not cancel its parent, so a nudge ends the wait and nothing else;
     * `join` then returns normally and the loop continues. Cancelling the *caller*, by
     * contrast, still propagates out of `join` as it must.
     */
    private suspend fun awaitInterval() {
        if (budget.intervalMillis <= 0L) return
        coroutineScope {
            val wait = launch { clock.sleep(budget.intervalMillis) }
            pendingWait = wait
            try {
                wait.join()
            } finally {
                pendingWait = null
            }
        }
    }

    /** The result of one read: either an intent or the error we are holding for it. */
    private sealed class Read {
        class Succeeded(val intent: PaymentIntentDto) : Read()

        class Failed(val error: Exception) : Read()
    }
}
