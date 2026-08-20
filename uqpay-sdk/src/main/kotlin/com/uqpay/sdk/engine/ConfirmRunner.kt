package com.uqpay.sdk.engine

import com.uqpay.sdk.error.UQPayError
import com.uqpay.sdk.network.ErrorMapper
import com.uqpay.sdk.network.IntentStatus
import com.uqpay.sdk.network.PaymentIntentDto
import com.uqpay.sdk.network.UQPayApiClient
import com.uqpay.sdk.network.UQPayApiException
import com.uqpay.sdk.network.UQPayLogger
import com.uqpay.sdk.store.ConfirmAttempt
import kotlinx.coroutines.CancellationException

/**
 * Where a confirm is sent.
 *
 * A narrow seam rather than a [UQPayApiClient] plus an intent id, for the same two reasons
 * [IntentSource] is one: a test must be able to script a sequence of answers without a
 * socket, and the runner has no business being able to reach any other endpoint. Use
 * [forIntent] for the production wiring.
 *
 * The body arrives **pre-encoded** and is passed through untouched. A replay must resend
 * byte-identical content under the same key, so nothing between here and the socket is
 * allowed to re-serialise it.
 */
internal fun interface ConfirmSender {

    suspend fun send(body: String, idempotencyKey: String): PaymentIntentDto

    companion object {
        /** The production sender: one POST to one intent's confirm endpoint. */
        fun forIntent(client: UQPayApiClient, paymentIntentId: String): ConfirmSender =
            ConfirmSender { body, key -> client.confirmIntent(paymentIntentId, body, key) }
    }
}

/**
 * How one confirm ended. Four outcomes, and the difference between the last two is money.
 *
 * Nothing here is an exception and nothing here carries gateway text in a field a merchant
 * reads: every failure has already been through [ErrorMapper], which is the SDK's only
 * chokepoint from an internal failure to a public [UQPayError].
 */
internal sealed class ConfirmOutcome {

    /**
     * The intent had already settled **before** this confirm was sent, and no confirm was
     * sent. This is the relaunch-recovery path: paid a moment before the app was killed,
     * or cancelled from the merchant dashboard while nobody was watching.
     *
     * @property status parsed once here so the caller does not re-parse the wire string and
     *   risk applying a different rule to it than the interceptor did.
     * @property error null when the settled state is a **success** (`SUCCEEDED`, or
     *   `REQUIRES_CAPTURE` — authorised, therefore paid); non-null, and already mapped
     *   through [ErrorMapper.mapSettledOutcome], when the payment is dead.
     */
    data class AlreadySettled(
        val intent: PaymentIntentDto,
        val status: IntentStatus,
        val error: UQPayError?,
    ) : ConfirmOutcome()

    /**
     * The gateway answered the confirm. The intent it returned is the answer — this class
     * does not interpret it, because "what does `REQUIRES_CUSTOMER_ACTION` mean here" is
     * the engine's question, not the sender's.
     */
    data class Confirmed(val intent: PaymentIntentDto) : ConfirmOutcome()

    /**
     * A definitive failure: either the gateway rejected the confirm outright, or nothing
     * ever left the device. Either way **no payment is in flight** and the customer may
     * safely be shown a failure and offered another method.
     */
    data class Failed(val error: UQPayError) : ConfirmOutcome()

    /**
     * The replay ladder ran out with the outcome still unknown. **The customer's money may
     * well be moving.** The idempotency pin is deliberately kept, so the next tap replays
     * this very attempt rather than opening a second one, and the engine reports `PENDING`.
     *
     * This is emphatically not a failure and not a cancellation. Stripe's PaymentSheet
     * reports `Canceled` here (`DefaultConfirmationHandler.kt:69-73`) and our own iOS SDK
     * shipped the same bug and had to fix it as a breaking change — a merchant told
     * "cancelled" releases nothing and refunds nothing for a payment that may succeed
     * thirty seconds later. Reporting `FAILED` would be worse still: it invites a second
     * payment for the same order.
     */
    data class Unresolved(val error: UQPayError) : ConfirmOutcome()
}

/**
 * Runs exactly one confirm to a conclusion: pre-confirm intercept, idempotent send, replay
 * ladder, and the engine's error boundary.
 *
 * ### The sequence
 *
 * 1. **Pre-confirm intercept (G3).** Re-read the intent before anything else. A payment
 *    that already settled must not be confirmed again — at best the gateway rejects it, at
 *    worst it opens a second attempt. Runs **before any pin is minted**, so an
 *    already-finished payment never leaves a pin behind.
 * 2. **Pin.** Compute the payload digest, ask [ConfirmIdempotency] for the attempt, and
 *    build the body from the attempt's *frozen* device values plus the payload's values
 *    read now.
 * 3. **Send, replaying while the outcome is unknown** — same key, byte-identical body, at
 *    3s / 6s / 10s.
 * 4. **Conclude.** A definitive answer resolves the pin. An exhausted ladder surrenders to
 *    reconciliation with the pin **kept**. Cancellation reports nothing and leaves the pin
 *    exactly as it was.
 *
 * ### Why the intercept fails open
 *
 * If the pre-confirm GET throws, the confirm proceeds anyway. That looks backwards for a
 * payment SDK and is not: **idempotency owns double-charge protection, this check does
 * not.** The intercept is a UX affordance — it turns "your payment already went through"
 * into a success screen instead of a confusing rejection. Failing closed would convert
 * every flaky status GET into a new way to refuse a payment, trading a real and common
 * failure (the customer cannot pay) for a hypothetical one the pin already covers. iOS
 * fails open here for the same stated reason.
 *
 * ### Why replay is the only honest retry
 *
 * The gateway's answer *to this idempotency key* is *this attempt's* outcome. If the first
 * send arrived, the replay returns its recorded result; if it never arrived, the replay
 * performs it. Either way no replay can become a second charge — provided the bytes are
 * identical, which is why the device fingerprint is frozen in the attempt
 * (`ConfirmAttempt`) and the body is encoded canonically (`ConfirmBodyEncoder`).
 *
 * The ladder is counted in **attempts on the injected [Clock], not against a deadline**.
 * Nothing here ever reads [Clock.elapsedRealtime]: a deadline is precisely the bug iOS
 * shipped, where a customer who left for their banking app came back to a payment the SDK
 * had already given up on purely because time had passed. Suspended time spends no budget
 * because there is no budget denominated in time.
 *
 * ### F4 — the engine boundary
 *
 * Both halves of [run] catch [Throwable]. **No exception of any kind escapes**, and in
 * particular no [UQPayApiException]: gateway text rides on its `message`, and an exception
 * that reaches a merchant's crash reporter takes that text with it. Everything leaves as an
 * [ErrorMapper]-produced [UQPayError] inside a [ConfirmOutcome], where `message` is a fixed
 * sentence outside sandbox.
 *
 * [CancellationException] is the single exception that propagates, and it must: swallowing
 * it would break structured concurrency for every caller. A cancelled confirm reports
 * nothing and touches no pin.
 *
 * ### What this class never does
 *
 * It creates no scope and starts nothing in the background. The caller supplies the
 * coroutine it runs on — Slice 3's confirmation Activity and its ViewModel scope, with
 * back-press blocked while a confirm is in flight — and this class must stay usable from
 * any of them.
 *
 * @param idempotency the process-wide registry. Construct **one per process**: its pending
 *   map is static while its store is per-instance, so two registries over different stores
 *   would share one pin map and disagree about what is persisted.
 * @param replayLadderMillis the waits before each replay. Injectable for tests only;
 *   production uses [REPLAY_LADDER_MILLIS] and Slice 3's back-press block is sized to it.
 */
internal class ConfirmRunner(
    private val idempotency: ConfirmIdempotency,
    private val errorMapper: ErrorMapper,
    private val clock: Clock,
    private val replayLadderMillis: List<Long> = REPLAY_LADDER_MILLIS,
    private val logger: UQPayLogger = UQPayLogger.Noop,
) {

    /**
     * Confirms [payload], and returns how it ended. See the class KDoc for the sequence.
     *
     * @param intentSource reads the intent for the pre-confirm intercept.
     * @param sender posts the confirm. Both are per-intent and passed per call so one
     *   runner serves every payment in a process.
     */
    suspend fun run(
        payload: ConfirmPayload,
        intentSource: IntentSource,
        sender: ConfirmSender,
    ): ConfirmOutcome {
        // Everything before the first byte goes out. A failure here is definitive by
        // construction: no request was made, so no payment can be in flight.
        val prepared = try {
            prepare(payload, intentSource)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // Class name only — a gateway message can contain anything.
            logger.error("Confirm could not be prepared (${failure.javaClass.simpleName})")
            return ConfirmOutcome.Failed(errorMapper.map(failure))
        }

        val ready = when (prepared) {
            is Preparation.Intercepted -> return prepared.outcome
            is Preparation.Ready -> prepared
        }

        // Everything from the first byte onwards. A failure here may have happened *after*
        // the request reached the gateway, so the honest answer is "unknown", never
        // "failed" — and the pin stays, because `handle` was never reached to release it.
        return try {
            sendReplayingWhileUnknown(ready, sender)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            logger.error("Confirm ended unclassifiably (${failure.javaClass.simpleName})")
            ConfirmOutcome.Unresolved(errorMapper.map(failure))
        }
    }

    // ---- Before the send -----------------------------------------------------------

    /**
     * The intercept, the pin, and the body — in that order, which is the order that matters.
     *
     * A pin minted before the intercept would outlive a payment that was already over.
     */
    private suspend fun prepare(payload: ConfirmPayload, intentSource: IntentSource): Preparation {
        interceptSettledIntent(intentSource)?.let { return Preparation.Intercepted(it) }

        val attempt = idempotency.attempt(payload.digest(), payload.paymentIntentId)
        verifyAttemptBelongsTo(payload, attempt)

        // Frozen values from the attempt; card values read from the payload right now.
        return Preparation.Ready(
            attempt = attempt,
            body = payload.encodeBody(attempt.browserInfo, attempt.ipAddress),
        )
    }

    /**
     * Reports an intent that had already settled, or null to go ahead and confirm.
     *
     * **Fails open**: any failure to read the intent returns null. See the class KDoc.
     *
     * The settled test is `!isPayable`, which is one rule with one owner rather than a
     * second list of statuses that could drift from `IntentStatus`'s. It covers
     * `REQUIRES_CAPTURE`: an authorised, uncaptured payment has been paid by the customer,
     * and confirming it again would ask the gateway to authorise a second time.
     *
     * **This diverges from iOS deliberately.** iOS's `interceptTerminalIntent` matches only
     * `.succeeded`, `.failed` and `.cancelled`, so an intent in `REQUIRES_CAPTURE` falls
     * through and is confirmed again. `ios-engine-notes.md` records that as intentional
     * ("the pre-confirm interceptor treats `REQUIRES_CAPTURE` as unsettled"), but the
     * execution plan, the presentation-time guard in WU-2.7 and the poller's stop rule in
     * WU-2.8 all take the other side, and so does the money: re-confirming an authorised
     * payment can only produce a gateway rejection reported as a failure for a payment the
     * customer has in fact made. Reporting the authorisation as the success it is costs
     * nothing and risks nothing.
     */
    private suspend fun interceptSettledIntent(intentSource: IntentSource): ConfirmOutcome? {
        val intent = try {
            intentSource.retrieve()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            logger.debug(
                "Pre-confirm intent read failed (${failure.javaClass.simpleName}); " +
                    "proceeding to confirm",
            )
            return null
        }

        val status = IntentStatus.from(intent.intentStatus)
        if (status.isPayable) return null

        logger.debug("Intent had already settled before this confirm; not sending it")
        return ConfirmOutcome.AlreadySettled(
            intent = intent,
            status = status,
            // Succeeded and RequiresCapture are the only other non-payable statuses, and
            // both are the customer having paid.
            error = when (status) {
                is IntentStatus.Failed, is IntentStatus.Cancelled -> errorMapper.mapSettledOutcome(
                    intentStatus = status,
                    failureCode = intent.latestPaymentAttempt?.failureCode,
                    failureMessage = intent.latestPaymentAttempt?.failureMessage,
                )
                else -> null
            },
        )
    }

    /**
     * Defence in depth for the one invariant this class cannot enforce alone.
     *
     * The registry files pins by digest and hands back the record's *own* intent id. That is
     * correct and necessary — a pin restored after process death must not trust whatever the
     * new launch happens to hold — but it means an incomplete digest is a mis-charge:
     * payment B, digesting the same as payment A, would be confirmed under A's key and
     * against A's id.
     *
     * `ConfirmPayload.digestFields` makes that impossible by construction, prepending the
     * intent id in a method no variant can override. This check is the runtime half of the
     * same guarantee, and it exists because "impossible by construction" is a property of
     * today's code. If it ever fires, **nothing has been sent**: the confirm is refused as a
     * configuration failure rather than aimed at the wrong payment.
     *
     * The pin is left alone. Releasing it would be the wrong instinct twice over — the
     * pinned attempt belongs to a *different* payment, and it may still be in flight.
     */
    private fun verifyAttemptBelongsTo(payload: ConfirmPayload, attempt: ConfirmAttempt) {
        if (attempt.paymentIntentId == payload.paymentIntentId) return
        // NotConfigured maps to INVALID_CONFIGURATION and carries no `apiError`, so this
        // sentence stays internal and never reaches a merchant or a customer.
        throw UQPayApiException.NotConfigured(
            "A pinned confirm attempt for this payload belongs to a different payment intent.",
        )
    }

    // ---- The send and the ladder ----------------------------------------------------

    /**
     * Sends, and while the outcome stays unknown resends the identical request.
     *
     * The identical request: same key, same bytes, from the same [Preparation.Ready]. The
     * body is encoded once, before the first send, and never rebuilt — rebuilding it per
     * attempt would be one refactor away from a replay the gateway rejects.
     *
     * The loop mirrors iOS's `sendConfirmReplayingWhileUnknown`, including the check
     * *before* each wait: a definitive rejection ends the ladder immediately rather than
     * costing the customer nineteen seconds of spinner for an answer we already have.
     */
    private suspend fun sendReplayingWhileUnknown(
        ready: Preparation.Ready,
        sender: ConfirmSender,
    ): ConfirmOutcome {
        var lastError: Throwable = when (val first = sendOnce(ready, sender)) {
            is Send.Answered -> return concluded(ready.attempt, first.intent)
            is Send.Threw -> first.error
        }

        for (waitMillis in replayLadderMillis) {
            if (!lastError.leavesOutcomeUnknown) break
            logger.debug(
                "Confirm outcome unknown (${lastError.javaClass.simpleName}); " +
                    "replaying the same key in ${waitMillis}ms",
            )
            // Cancellable. A customer who walks away mid-ladder cancels here, and the
            // CancellationException that comes out reports nothing and touches no pin.
            clock.sleep(waitMillis)
            when (val replay = sendOnce(ready, sender)) {
                is Send.Answered -> return concluded(ready.attempt, replay.intent)
                is Send.Threw -> lastError = replay.error
            }
        }

        // The three-way split lives in the registry, not here: cancellation leaves the pin,
        // an unknown outcome keeps it so the next tap replays, a definitive answer ends it.
        idempotency.handle(lastError, ready.attempt)

        return if (lastError.leavesOutcomeUnknown) {
            logger.debug("Confirm surrendered to reconciliation with its pin kept")
            ConfirmOutcome.Unresolved(errorMapper.map(lastError))
        } else {
            ConfirmOutcome.Failed(errorMapper.map(lastError))
        }
    }

    /**
     * One send.
     *
     * `Error`s are **not** caught — an `OutOfMemoryError` is not a flaky request — and reach
     * [run]'s boundary, where they become an unresolved outcome with the pin intact.
     */
    private suspend fun sendOnce(ready: Preparation.Ready, sender: ConfirmSender): Send =
        try {
            Send.Answered(sender.send(ready.body, ready.attempt.key))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (cancelled: UQPayApiException.Cancelled) {
            // A locally-cancelled request is cancellation wearing an API type. Converting it
            // back means one rule for "the customer walked away" instead of two: report
            // nothing, leave the pin, let structured concurrency unwind the caller.
            throw CancellationException("The confirm request was cancelled.")
        } catch (failure: Exception) {
            Send.Threw(failure)
        }

    /** A definitive answer from the gateway ends the attempt. */
    private fun concluded(attempt: ConfirmAttempt, intent: PaymentIntentDto): ConfirmOutcome {
        idempotency.resolve(attempt)
        return ConfirmOutcome.Confirmed(intent)
    }

    /**
     * Whether this failure leaves the payment's fate undetermined — the question that
     * decides both whether to replay and whether the merchant hears "failed" or "pending".
     *
     * A [UQPayApiException] answers for itself through `isOutcomeUnknown`, which already
     * matches iOS's classification exactly and is reused rather than re-derived so the two
     * SDKs cannot drift apart on the definition of "may already have been charged".
     *
     * **Anything else counts as unknown.** An error we cannot classify is not a definitive
     * answer from the gateway: an `IllegalStateException` raised somewhere inside the send
     * path may have been raised after the bytes went out. Replaying it costs at most a few
     * seconds and a request the gateway either honours or rejects as a changed body;
     * treating it as a decline can cost a customer a second charge when they tap Pay again.
     * The registry made the same call for the same reason (WU-2.4's accepted divergence),
     * and having the two agree means the pin's fate and the merchant's message can never
     * describe the payment differently.
     */
    private val Throwable.leavesOutcomeUnknown: Boolean
        get() = this !is UQPayApiException || isOutcomeUnknown

    /** The result of [prepare]: either an answer already, or the request to send. */
    private sealed class Preparation {
        class Intercepted(val outcome: ConfirmOutcome) : Preparation()

        class Ready(val attempt: ConfirmAttempt, val body: String) : Preparation()
    }

    /** The result of one send. */
    private sealed class Send {
        class Answered(val intent: PaymentIntentDto) : Send()

        class Threw(val error: Throwable) : Send()
    }

    internal companion object {

        /**
         * The replay ladder: three resends, ~3s / 6s / 10s after the one before.
         *
         * Copied from the shipped iOS SDK, which sized it against real gateway behaviour;
         * the total (~19s plus four request timeouts) is also what Slice 3's back-press
         * block is bounded by, so the customer is never held longer than the ladder runs.
         *
         * Waits, not deadlines. See the class KDoc.
         */
        val REPLAY_LADDER_MILLIS: List<Long> = listOf(3_000L, 6_000L, 10_000L)
    }
}
