package com.uqpay.sdk.engine

import com.uqpay.sdk.error.UQPayError
import com.uqpay.sdk.error.UQPayErrorCode
import com.uqpay.sdk.network.AttemptStatus
import com.uqpay.sdk.network.ErrorMapper
import com.uqpay.sdk.network.IntentStatus
import com.uqpay.sdk.network.PaymentIntentDto
import com.uqpay.sdk.network.UQPayApiException
import com.uqpay.sdk.network.UQPayLogger
import com.uqpay.sdk.payment.PaymentMethodType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The confirm half of the engine's two seams: runs one confirm to a [ConfirmOutcome].
 *
 * In production this is [ConfirmRunner] bound to one intent's source and sender (see
 * [forIntent]). It is a seam rather than the runner itself so the engine's own rules — the
 * latch, supersession, stale-result protection, cancellation semantics — can be tested with
 * a scripted confirm that resolves exactly when the test says, without a clock, a registry
 * or a socket. The runner's own behaviour is already covered by its own tests.
 */
internal fun interface ConfirmStep {

    suspend fun run(payload: ConfirmPayload): ConfirmOutcome

    companion object {
        /** The production step: [ConfirmRunner] over one intent's source and sender. */
        fun forIntent(runner: ConfirmRunner, intentSource: IntentSource, sender: ConfirmSender): ConfirmStep =
            ConfirmStep { payload -> runner.run(payload, intentSource, sender) }
    }
}

/**
 * The watch half of the engine's two seams: polls the intent to a [PollOutcome].
 *
 * In production this wraps [IntentPoller] (see [forIntent]); one poller is built per poll
 * because the budget and the early-return rule depend on which customer action is on screen.
 * [nudge] reaches the poller currently running, so Slice 6's foreground re-read is a
 * one-line call on the engine rather than surgery on it.
 */
internal interface WatchStep {

    suspend fun poll(budget: PollBudget, earlyReturn: EarlyReturnCheck): PollOutcome

    /** Replaces the wait currently in progress with an immediate re-read. No-op if idle. */
    fun nudge()

    companion object {
        /** The production step: a fresh [IntentPoller] per poll over [intentSource]. */
        fun forIntent(intentSource: IntentSource, clock: Clock, logger: UQPayLogger = UQPayLogger.Noop): WatchStep =
            PollerWatchStep(intentSource, clock, logger)
    }
}

private class PollerWatchStep(
    private val intentSource: IntentSource,
    private val clock: Clock,
    private val logger: UQPayLogger,
) : WatchStep {

    @Volatile
    private var current: IntentPoller? = null

    override suspend fun poll(budget: PollBudget, earlyReturn: EarlyReturnCheck): PollOutcome {
        val poller = IntentPoller(intentSource, budget, clock, earlyReturn, logger)
        current = poller
        try {
            return poller.poll()
        } finally {
            if (current === poller) current = null
        }
    }

    override fun nudge() {
        current?.nudge()
    }
}

/**
 * The caller's verdict on a payload before the engine will send it.
 *
 * The engine does not know what a valid card number is — Slice 4's form does. It only needs
 * to know *whether* this tap is a real attempt, because that decides whether the previous
 * attempt is superseded. See [PaymentEngine.confirm].
 */
internal enum class PayloadValidation { VALID, INVALID }

/** What [PaymentEngine.confirm] did with a tap. */
internal enum class ConfirmAcceptance {

    /** A confirm attempt is now in flight (and any previous one has been superseded). */
    STARTED,

    /** The payload failed the caller's validation. **Nothing was cancelled.** */
    REJECTED_INVALID,

    /**
     * The payload's method is not offered under the [Presentation] the engine was loaded
     * with — a card payload on a [Presentation.SingleWallet] screen, say. The presentation is
     * enforced here as well as in the method list, so no screen can route around it (G19).
     */
    REJECTED_METHOD_NOT_OFFERED,

    /** The engine is not in a state that accepts a confirm: not loaded yet, or finished. */
    REJECTED_NOT_CONFIRMABLE,

    /**
     * A confirm with a **different payload** is still in flight for this intent — its
     * request or replay ladder has not yet returned. **Nothing was cancelled**; the
     * in-flight attempt keeps running and its answer still decides the payment.
     *
     * A different payload means a different digest, therefore a different idempotency key.
     * Two keys in flight against one intent is the one duplicate the gateway cannot dedupe
     * (Slice 2 audit M-5): each key is a legitimate, distinct attempt to it. The engine
     * refuses to open the second while the first is unresolved. Once the first has returned
     * — the customer is on a 3-D Secure page, or the ladder gave up — a corrected payload
     * is a legitimate retry and is accepted.
     */
    REJECTED_DIFFERENT_PAYLOAD_IN_FLIGHT,
}

/**
 * The headless payment engine: one instance drives one payment intent from load to exactly
 * one terminal outcome. Slices 3–5 render [state]; nothing here knows about Android UI.
 *
 * ### One payment, one outcome, once
 *
 * [EngineState.Terminal] is entered through a single atomic compare-and-set ([settle]).
 * Every later attempt to settle — a late poll, a superseded confirm finishing, a second
 * back-press, a reconciler that learned the answer after the screen did — is dropped and
 * counted in [droppedSettleAttempts]. This is the session latch, and like iOS's
 * `hasReportedOutcome` it is **never reset**: an engine is single-use per payment. A new
 * payment gets a new engine. Reusing one is not supported and is refused rather than
 * defended against (a second [load] is ignored, a [confirm] after [EngineState.Terminal] is
 * rejected).
 *
 * ### The two questions the merchant's money depends on
 *
 * 1. **Is a confirm in flight?** While the request and its replay ladder are running the
 *    engine is in [EngineState.Confirming] and [isConfirmInFlight] is true. Slice 3 blocks
 *    back-press for that window (§2c). If the caller cancels anyway, the outcome is
 *    [com.uqpay.sdk.payment.PaymentStatus.PENDING] — never `CANCELLED`, because there is an
 *    attempt in the air whose result the customer's departure does not change.
 * 2. **Which attempt is this answer for?** Every confirm tap that starts an attempt bumps a
 *    generation; every asynchronous result carries the generation that started it and is
 *    compared before it may settle. A superseded attempt's job is cancelled *and* its answer,
 *    should it arrive anyway, is dropped (G11/G12). Cancellation is cooperative; the
 *    generation check is not.
 *
 * ### Presentation-time guard (G3, WU-2.6 decision B)
 *
 * [load] re-reads the intent and refuses to show a payment screen for one that is not
 * payable. `SUCCEEDED` and `REQUIRES_CAPTURE` are the customer having paid and report success
 * (relaunch recovery: the confirm went through just before the process died); `FAILED` and
 * `CANCELLED` report the intent-level failure. One predicate — `IntentStatus.isPayable` — is
 * shared with the pre-confirm intercept and the poller's stop rule, so the three cannot
 * disagree about the same intent.
 *
 * ### G4 — the decline the merchant must hear about
 *
 * `REQUIRES_PAYMENT_METHOD` is ambiguous: a fresh intent, or an intent whose last attempt
 * was declined. With `latest_payment_attempt.attempt_status == FAILED` it is a decline, and
 * it is reported as `FAILED` with the attempt's own failure code through
 * [ErrorMapper.mapSettledOutcome] — at load, after a confirm, and after a poll. Telling a
 * customer to enter their card again after a decline, and telling the merchant nothing, is
 * the iOS bug this closes.
 *
 * ### `PENDING`, and why it is never `FAILED` or `CANCELLED`
 *
 * A replay ladder or poll budget running out is *not* an outcome. It is reported as
 * `PENDING` carrying [UQPayErrorCode.TIMEOUT] — whose fixed message is "still waiting" — and
 * deliberately not the last underlying error's code: `SERVER_ERROR`'s copy says "please try
 * again", which on a payment that may already have been taken is an invitation to pay twice.
 * The underlying error is logged by class name only.
 *
 * ### F4 at this layer
 *
 * Nothing thrown by the confirm step, the watch step, or the intent source escapes this
 * class's public surface except [CancellationException]. The runner and poller already hold
 * that line; this class holds it again, because a seam can be implemented by something that
 * does not.
 *
 * ### Threading
 *
 * [load], [confirm], [cancel] and [nudge] may be called from any thread. Coroutines run on
 * the injected [scope] — this class never creates a scope tied to a lifecycle (§2a). The
 * scope's owner (Slice 3's confirmation ViewModel) decides how long work outlives a screen.
 * After a `PENDING` the in-flight attempt is **not** cancelled: it keeps running for pin
 * resolution and nothing else, exactly the reconciler role §2c allows.
 *
 * @param paymentIntentId the payment this engine drives. Held here rather than passed to
 *   [load] so that a `CANCELLED` result exists — with a non-blank id (F3) — however early the
 *   customer leaves.
 * @param wallClock wall-clock time for a success's `completedAt`. Not the engine [Clock],
 *   which is monotonic-only by design; injected so tests are deterministic.
 */
internal class PaymentEngine(
    val paymentIntentId: String,
    private val scope: CoroutineScope,
    private val confirmStep: ConfirmStep,
    private val watchStep: WatchStep,
    private val intentSource: IntentSource,
    private val errorMapper: ErrorMapper,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val logger: UQPayLogger = UQPayLogger.Noop,
) {

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)

    /** Where the payment is. Renders directly; see [EngineState]. */
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** The latch. Set exactly once, by the one settle that wins; never reset. */
    private val settled = AtomicBoolean(false)

    private val dropped = AtomicInteger(0)

    /**
     * Settle attempts refused because the outcome was already decided or the attempt was
     * stale. Diagnostics and tests; a healthy payment sees zero or a small number.
     */
    val droppedSettleAttempts: Int
        get() = dropped.get()

    /** Bumped by every confirm that starts. Results from an older generation may not settle. */
    private val generation = AtomicInteger(0)

    /** Guards the read-state-then-act sequences in [confirm] and [cancel]. */
    private val lock = Any()

    private val loadStarted = AtomicBoolean(false)

    private var loadJob: Job? = null

    /** The current attempt's job: confirm, then whatever watching it needs. One at a time. */
    private var attemptJob: Job? = null

    /**
     * The payload digest of the confirm currently in flight — set when a generation enters
     * [EngineState.Confirming], meaningful only while the state is still `Confirming`, and
     * consulted only under [lock]. A digest, never the payload: nothing card-derived is
     * retained here. See the M-5 rule in [confirm].
     */
    private var inFlightDigest: String? = null

    @Volatile
    private var presentation: Presentation = Presentation.MethodList

    /** The most recent read of the intent, for the context a result carries. */
    @Volatile
    private var lastIntent: PaymentIntentDto? = null

    /**
     * True while a confirm request or its replay ladder is running. Slice 3 blocks back-press
     * while this is true; a forced [cancel] during it settles `PENDING`.
     */
    val isConfirmInFlight: Boolean
        get() = _state.value is EngineState.Confirming

    /**
     * True while an attempt exists whose outcome this engine has not yet learned: confirming,
     * waiting on a customer action, or polling. A [cancel] here is `PENDING`, not `CANCELLED`
     * — the customer walking away from a 3-D Secure page or a QR does not un-send the
     * attempt, and its QR may still be paid.
     */
    val hasAttemptInAir: Boolean
        get() = when (_state.value) {
            is EngineState.Confirming, is EngineState.RequiresAction, is EngineState.Polling -> true
            else -> false
        }

    // ---- load ------------------------------------------------------------------------

    /**
     * Reads the intent, applies the presentation-time guard, and moves to
     * [EngineState.SelectingMethod] — or straight to [EngineState.Terminal] when the intent
     * cannot be paid, or to [EngineState.RequiresAction] when the intent is *already*
     * awaiting a customer action it can render (see [adoptExistingAction]).
     *
     * Once per engine. A second call is ignored with a log line rather than thrown: this can
     * only be a caller bug, and a crash mid-payment is a worse failure than a no-op.
     *
     * @param presentation which methods to offer, honoured on every path — in the method list
     *   and again in [confirm].
     */
    fun load(presentation: Presentation = Presentation.MethodList) {
        if (!loadStarted.compareAndSet(false, true)) {
            logger.debug("PaymentEngine.load called twice; engines are single-use, ignoring")
            return
        }
        this.presentation = presentation
        moveTo(EngineState.LoadingIntent, generation = null)
        loadJob = scope.launch {
            try {
                val intent = intentSource.retrieve()
                lastIntent = intent
                onLoaded(intent)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // Nothing has been sent, so a failure to *read* the intent is a definitive
                // failure of this session — no attempt exists that a `PENDING` would protect.
                logger.error("Intent could not be loaded (${failure.javaClass.simpleName})")
                settle(EngineOutcome.Failed(errorMapper.map(failure), null), generation = null)
            }
        }
    }

    private fun onLoaded(intent: PaymentIntentDto) {
        val status = IntentStatus.from(intent.intentStatus)
        when {
            !status.isPayable -> settle(settledOutcome(intent, status), generation = null)
            isDecline(intent, status) -> settle(declineOutcome(intent, onScreen = null), generation = null)
            else -> {
                // The same rule the post-confirm path applies (see [onIntent]): an action is
                // the customer's to act on only while the intent still says so.
                val action = if (status is IntentStatus.RequiresCustomerAction) NextAction.from(intent.nextAction) else null
                if (action != null) {
                    adoptExistingAction(intent, action)
                } else {
                    moveTo(
                        EngineState.SelectingMethod(intent, methodsFor(intent, presentation), presentation),
                        generation = null,
                    )
                }
            }
        }
    }

    /**
     * Attaches to an attempt that was **already in flight when this engine was built** — a
     * relaunch, or a fresh process, landing on an intent that is already
     * `REQUIRES_CUSTOMER_ACTION` with a renderable `next_action`.
     *
     * ### Why this branch exists (the unobserved-attempt gap)
     *
     * Without it, such an intent fell through to [EngineState.SelectingMethod]. For a wallet
     * that is worse than merely wrong: [WalletConfirmLatch] correctly refuses to send a
     * second confirm for an intent+method already confirmed and re-serves the *same, still
     * valid* QR — so the customer sees a payable code that **nobody is polling**. They pay,
     * the intent succeeds, and this engine never learns; the merchant is told `CANCELLED` or
     * `PENDING` for a payment that was taken. The money risk is the missing observer, not the
     * missing confirm.
     *
     * So: show the action, and watch it, exactly as [onIntent] does after a confirm. **No
     * confirm is sent** — the attempt already exists, and sending one here is the duplicate
     * this whole slice exists to prevent.
     *
     * A generation is claimed so that a later [confirm] (the customer choosing to start over
     * with a card, say) supersedes this watcher through the ordinary path rather than racing
     * it. From here on the engine is indistinguishable from one that just confirmed:
     * [hasAttemptInAir] is true, so leaving reports `PENDING`, not `CANCELLED` — which is the
     * honest answer while a live QR or 3-D Secure page is outstanding.
     */
    private fun adoptExistingAction(intent: PaymentIntentDto, action: NextAction) {
        synchronized(lock) {
            if (settled.get()) return
            logger.debug("Loaded an intent already awaiting a customer action; watching it without confirming")
            val thisGeneration = generation.incrementAndGet()
            moveTo(EngineState.RequiresAction(action, intent), thisGeneration)
            attemptJob = scope.launch { watch(action, thisGeneration, stage = 0) }
        }
    }

    /**
     * The methods to offer. **The only place the list is built**, so the presentation cannot
     * be honoured on one path and forgotten on another (audit A5).
     *
     * For [Presentation.MethodList]: `available_payment_method_types` in API order (G21) with
     * `card` pinned first when present. Types this SDK predates are carried as
     * `PaymentMethodType.of(raw)`, never dropped and never an error — hiding what it cannot
     * render is the UI's job (G19).
     */
    private fun methodsFor(intent: PaymentIntentDto, presentation: Presentation): List<PaymentMethodType> =
        when (presentation) {
            Presentation.CardOnly -> listOf(PaymentMethodType.CARD)
            is Presentation.SingleWallet -> listOf(presentation.method)
            Presentation.MethodList -> {
                val offered = intent.availablePaymentMethodTypes.orEmpty()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .map(PaymentMethodType::of)
                if (PaymentMethodType.CARD in offered) {
                    listOf(PaymentMethodType.CARD) + offered.filter { it != PaymentMethodType.CARD }
                } else {
                    offered
                }
            }
        }

    // ---- confirm ---------------------------------------------------------------------

    /**
     * Sends [payload] — or, if a confirm is already in flight, **supersedes** it.
     *
     * ### Double-tap (G12)
     *
     * A second tap with a **valid** payload cancels the previous attempt's job — its confirm
     * *and* the watcher that would have polled its outcome — and starts a new attempt in a
     * new generation. Two observers of one intent would race to report; the newest wins, and
     * because the payload digest is the same the runner replays the same idempotency key, so
     * this is a retry of the same attempt, never a second charge.
     *
     * A tap whose payload **fails validation** cancels nothing. Its previous attempt may be
     * mid-ladder for a payment that has in fact been taken; killing the only observer of that
     * payment because the customer mistyped a digit would leave the screen — and the merchant
     * — with no one watching. This is the nuance iOS's `payButtonTapped` comment spells out.
     *
     * ### A different payload while a confirm is in flight (M-5)
     *
     * Supersession is safe **only because the digest is the same**: the replay carries the
     * same idempotency key, and the gateway collapses it onto the first attempt. A tap with a
     * *different* payload — an edited expiry, another card, a wallet instead of a card — has a
     * different digest and would mint a **second key against the same intent while the first
     * is still unresolved**. That is the one duplicate the gateway cannot dedupe: to it, two
     * keys are two honest, distinct attempts. So while [isConfirmInFlight] and the digest
     * differs, the tap is refused with [ConfirmAcceptance.REJECTED_DIFFERENT_PAYLOAD_IN_FLIGHT]
     * and, like a failed-validation tap, cancels nothing — the in-flight attempt stays the
     * only observer of a payment that may already be taken.
     *
     * The window is exactly [EngineState.Confirming]. Once the confirm step has returned, the
     * first attempt is either resolved (its pin released — the customer is now on a 3-D Secure
     * page or the intent is polling) or definitively unresolved (the ladder gave up, and the
     * engine has settled `PENDING`); a corrected payload after that is a legitimate retry with
     * corrected details, and is accepted wherever a confirm is accepted at all. Slice 3/4's UI
     * blocks input for the same window (§2c); this is the engine-level backstop so no screen
     * can route around it.
     *
     * ### What is accepted
     *
     * Confirms are accepted from [EngineState.SelectingMethod], [EngineState.Confirming],
     * [EngineState.RequiresAction] and [EngineState.Polling]. The last two are the customer
     * pressing Pay again while a 3-D Secure page or QR is up: the runner's pre-confirm
     * intercept re-reads the intent, and the same-key replay is the correct way to learn
     * where that attempt stands (iOS `watchUnsettledIntent`'s reasoning).
     *
     * @param validation the caller's verdict on the payload's fields. The engine does not
     *   inspect the payload beyond its method and intent id.
     * @throws IllegalArgumentException if [payload] belongs to a different intent. That is a
     *   programmer error, and the alternative — sending it — would confirm the wrong payment.
     */
    fun confirm(payload: ConfirmPayload, validation: PayloadValidation = PayloadValidation.VALID): ConfirmAcceptance {
        require(payload.paymentIntentId == paymentIntentId) {
            "This engine drives a different payment intent than the payload names."
        }
        if (validation != PayloadValidation.VALID) {
            logger.debug("Confirm rejected by validation; the previous attempt is untouched")
            return ConfirmAcceptance.REJECTED_INVALID
        }
        val methodType = PaymentMethodType.of(payload.methodType)
        // The pin identity of this payload — the same digest the runner will pin under. A
        // hash of the identity fields, so retaining it discloses nothing card-derived.
        val digest = payload.digest()
        synchronized(lock) {
            if (settled.get()) return ConfirmAcceptance.REJECTED_NOT_CONFIRMABLE
            when (_state.value) {
                is EngineState.Idle, is EngineState.LoadingIntent, is EngineState.Terminal ->
                    return ConfirmAcceptance.REJECTED_NOT_CONFIRMABLE
                else -> Unit
            }
            if (!isOffered(methodType)) {
                logger.debug("Confirm rejected: method not offered under the current presentation")
                return ConfirmAcceptance.REJECTED_METHOD_NOT_OFFERED
            }
            // M-5: while a confirm is unresolved, only the *same* payload may be resent. A new
            // digest would be a second idempotency key in flight against this intent, and
            // that is the duplicate nothing downstream can collapse. Refuse; cancel nothing.
            if (_state.value is EngineState.Confirming && inFlightDigest != null && inFlightDigest != digest) {
                logger.debug("Confirm rejected: a different payload is still in flight for this intent")
                return ConfirmAcceptance.REJECTED_DIFFERENT_PAYLOAD_IN_FLIGHT
            }

            // Supersede: the new generation is claimed *before* the old job is cancelled, so
            // even a job that ignores cancellation is stale from this instant.
            val thisGeneration = generation.incrementAndGet()
            attemptJob?.let {
                logger.debug("Superseding the in-flight confirm and its watcher")
                it.cancel(CancellationException("Superseded by a newer confirm"))
            }
            inFlightDigest = digest
            moveTo(EngineState.Confirming(methodType), thisGeneration)
            attemptJob = scope.launch { runAttempt(payload, thisGeneration) }
            return ConfirmAcceptance.STARTED
        }
    }

    /** The presentation, enforced at the send as well as in the list. */
    private fun isOffered(method: PaymentMethodType): Boolean = when (val p = presentation) {
        Presentation.MethodList -> true
        Presentation.CardOnly -> method == PaymentMethodType.CARD
        is Presentation.SingleWallet -> method == p.method
    }

    private suspend fun runAttempt(payload: ConfirmPayload, thisGeneration: Int) {
        val outcome = try {
            confirmStep.run(payload)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // The runner's boundary should make this unreachable. If a step throws anyway,
            // the bytes may have gone out, so the honest answer is unresolved, not failed.
            logger.error("Confirm step threw (${failure.javaClass.simpleName}); treating as unresolved")
            ConfirmOutcome.Unresolved(errorMapper.map(failure))
        }
        when (outcome) {
            is ConfirmOutcome.AlreadySettled -> {
                lastIntent = outcome.intent
                val settledAs = outcome.error?.let { EngineOutcome.Failed(it, outcome.intent) }
                    ?: EngineOutcome.Succeeded(outcome.intent)
                settle(settledAs, thisGeneration)
            }
            is ConfirmOutcome.Failed -> settle(EngineOutcome.Failed(outcome.error, lastIntent), thisGeneration)
            is ConfirmOutcome.Unresolved -> {
                logger.debug("Confirm ladder exhausted (${outcome.error.code}); reporting pending")
                settle(EngineOutcome.Pending(timeoutError(), lastIntent), thisGeneration)
            }
            is ConfirmOutcome.Confirmed -> onIntent(outcome.intent, thisGeneration, stage = 0)
        }
    }

    // ---- interpreting an intent -----------------------------------------------------

    /**
     * Interprets a freshly read intent for a live attempt: settle it, report a decline, or
     * show the customer what to do next and watch.
     *
     * @param stage how many customer-action stages this attempt has been through. A server
     *   that keeps changing its `next_action` is bounded by [MAX_ACTION_STAGES] rather than
     *   trusted forever.
     */
    private suspend fun onIntent(intent: PaymentIntentDto, thisGeneration: Int, stage: Int) {
        lastIntent = intent
        val status = IntentStatus.from(intent.intentStatus)
        when {
            !status.isPayable -> settle(settledOutcome(intent, status), thisGeneration)
            isDecline(intent, status) -> settle(declineOutcome(intent, onScreen = null), thisGeneration)
            else -> {
                // A next_action is only the customer's to act on while the intent says so; a
                // stale one left on a PENDING intent must not re-open a finished 3DS page.
                val action = if (status is IntentStatus.RequiresCustomerAction) NextAction.from(intent.nextAction) else null
                if (action != null) {
                    moveTo(EngineState.RequiresAction(action, intent), thisGeneration)
                } else {
                    moveTo(EngineState.Polling(intent), thisGeneration)
                }
                watch(action, thisGeneration, stage)
            }
        }
    }

    /**
     * Polls the intent while [onScreen] (or nothing) is displayed, and acts on how it ends.
     *
     * The early-return rule is the engine's, not the poller's: stop when the intent falls
     * back to `REQUIRES_PAYMENT_METHOD` (a decline — G14 during 3-D Secure, G4 otherwise) or
     * when a *different* customer action appears (multi-stage 3-D Secure, G13). The poller
     * hands the intent back and this method decides what it meant.
     */
    private suspend fun watch(onScreen: NextAction?, thisGeneration: Int, stage: Int) {
        // A stale or finished attempt spends no network.
        if (settled.get() || thisGeneration != generation.get()) return

        val budget = if (onScreen is NextAction.Qr) PollBudget.WalletQr else PollBudget.ThreeDs
        val earlyReturn = EarlyReturnCheck { intent ->
            val status = IntentStatus.from(intent.intentStatus)
            status is IntentStatus.RequiresPaymentMethod ||
                (status is IntentStatus.RequiresCustomerAction && actionKey(intent) != onScreen?.type)
        }

        val outcome = try {
            watchStep.poll(budget, earlyReturn)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            logger.error("Watch step threw (${failure.javaClass.simpleName}); treating as unresolved")
            PollOutcome.Unresolved(lastIntent = null, heldError = null, attemptsMade = 0)
        }

        when (outcome) {
            is PollOutcome.Settled -> {
                lastIntent = outcome.intent
                settle(settledOutcome(outcome.intent, outcome.status), thisGeneration)
            }
            is PollOutcome.Unresolved -> {
                outcome.lastIntent?.let { lastIntent = it }
                // Class name only: the held error may carry gateway text.
                logger.debug(
                    "Poll budget exhausted after ${outcome.attemptsMade} attempt(s)" +
                        (outcome.heldError?.let { " holding ${it.javaClass.simpleName}" } ?: "") +
                        "; reporting pending",
                )
                settle(EngineOutcome.Pending(timeoutError(), lastIntent), thisGeneration)
            }
            is PollOutcome.EarlyReturn -> {
                lastIntent = outcome.intent
                val status = IntentStatus.from(outcome.intent.intentStatus)
                when {
                    status is IntentStatus.RequiresPaymentMethod ->
                        settle(declineOutcome(outcome.intent, onScreen), thisGeneration)
                    stage >= MAX_ACTION_STAGES -> {
                        logger.error("next_action changed $stage times; giving up on this attempt as pending")
                        settle(EngineOutcome.Pending(timeoutError(), outcome.intent), thisGeneration)
                    }
                    else -> onIntent(outcome.intent, thisGeneration, stage + 1)
                }
            }
        }
    }

    /** The comparable identity of an intent's current action, or null when it has none. */
    private fun actionKey(intent: PaymentIntentDto): String? = NextAction.from(intent.nextAction)?.type

    // ---- deciding outcomes -----------------------------------------------------------

    /**
     * The outcome for a non-payable intent. `SUCCEEDED` and `REQUIRES_CAPTURE` are the
     * customer having paid; `FAILED` and `CANCELLED` are the payment being dead. Nothing else
     * is non-payable today; the fallback exists so a future status cannot fall into "paid".
     */
    private fun settledOutcome(intent: PaymentIntentDto, status: IntentStatus): EngineOutcome = when (status) {
        is IntentStatus.Succeeded, is IntentStatus.RequiresCapture -> EngineOutcome.Succeeded(intent)
        is IntentStatus.Failed, is IntentStatus.Cancelled ->
            EngineOutcome.Failed(ReconciledOutcome.failureError(intent, errorMapper), intent)
        else -> EngineOutcome.Failed(notPayableError(), intent)
    }

    /** G4: `REQUIRES_PAYMENT_METHOD` whose latest attempt failed is a decline. */
    private fun isDecline(intent: PaymentIntentDto, status: IntentStatus): Boolean =
        status is IntentStatus.RequiresPaymentMethod &&
            AttemptStatus.from(intent.latestPaymentAttempt?.attemptStatus) is AttemptStatus.Failed

    /**
     * The decline outcome, with the attempt's own failure code. When the attempt carries no
     * code and a 3-D Secure step was on screen, the fallback is `3ds_failed` (G14): the
     * customer failed or abandoned authentication, and the attempt row does not always say so.
     */
    private fun declineOutcome(intent: PaymentIntentDto, onScreen: NextAction?): EngineOutcome {
        val fallback = if (onScreen is NextAction.Redirect || onScreen is NextAction.Iframe) THREE_DS_FAILED_CODE else null
        return EngineOutcome.Failed(ReconciledOutcome.failureError(intent, errorMapper, fallback), intent)
    }

    /**
     * The error carried on every `PENDING`: [UQPayErrorCode.TIMEOUT], through the mapper.
     * See the class KDoc for why it is never the underlying error's code.
     */
    private fun timeoutError(): UQPayError = errorMapper.map(UQPayApiException.TimedOut())

    /**
     * `INTENT_NOT_PAYABLE` for a non-payable status that is neither paid nor dead. Unreachable
     * with today's `IntentStatus`; built directly because [ErrorMapper] has no entry point for
     * a code without an underlying failure, and the copy mirrors the mapper's fixed text.
     */
    private fun notPayableError(): UQPayError = UQPayError(
        code = UQPayErrorCode.INTENT_NOT_PAYABLE,
        message = "This payment has already been completed or cancelled.",
    )

    // ---- cancel ----------------------------------------------------------------------

    /**
     * The customer is leaving.
     *
     * With **nothing in the air** — before load, while loading, or while choosing a method —
     * this is exactly one `CANCELLED`, with the intent id this engine was built for (F3:
     * never blank). With an attempt in the air (see [hasAttemptInAir]) it is `PENDING`, never
     * `CANCELLED`: the customer's departure does not un-send a confirm, and a merchant told
     * "cancelled" releases and refunds nothing for a payment that may succeed moments later.
     *
     * The in-flight attempt is **not** cancelled by this call. It keeps running for pin
     * resolution — a definitive gateway answer that arrives later still releases the
     * idempotency pin — and its result is dropped by the latch. That is the reconciler role
     * §2c permits: bookkeeping, never a second delivery. The scope's owner ends it.
     *
     * A cancel after the outcome is already decided is dropped and counted.
     */
    fun cancel() {
        synchronized(lock) {
            val outcome = if (hasAttemptInAir) {
                logger.debug("Cancel requested with an attempt in the air; reporting pending, not cancelled")
                EngineOutcome.Pending(timeoutError(), lastIntent)
            } else {
                EngineOutcome.Cancelled(lastIntent)
            }
            if (settle(outcome, generation = null)) {
                // A load still in progress has nobody to report to; stop spending network on it.
                loadJob?.cancel(CancellationException("Cancelled by the customer"))
            }
        }
    }

    /** Foreground re-read: makes the poll currently waiting look at the intent now. */
    fun nudge() {
        watchStep.nudge()
    }

    // ---- the latch -------------------------------------------------------------------

    /**
     * Enters [EngineState.Terminal] — or does not, and says so.
     *
     * The order of the two checks matters. Staleness first: a result from a superseded
     * generation must not even compete for the latch. Then the compare-and-set on [settled],
     * which exactly one caller wins. Only the winner writes the state, so a `Terminal` can
     * never be overwritten and never appears twice.
     *
     * @param generation the attempt this result belongs to, or null for results that belong
     *   to the session as a whole (load, cancel).
     * @return true if this call decided the payment.
     */
    private fun settle(outcome: EngineOutcome, generation: Int?): Boolean {
        if (generation != null && generation != this.generation.get()) {
            dropped.incrementAndGet()
            logger.debug("Dropped a settle from a superseded attempt")
            return false
        }
        if (!settled.compareAndSet(false, true)) {
            dropped.incrementAndGet()
            logger.debug("Dropped a settle: the outcome was already decided")
            return false
        }
        val result = outcome.toResult(paymentIntentId, wallClock())
        _state.value = EngineState.Terminal(result)
        logger.debug("Payment settled as ${result.status}")
        return true
    }

    /**
     * Moves to a non-terminal state unless the payment is already decided or the move belongs
     * to a superseded attempt. Uses the flow's own compare-and-set so a move racing a settle
     * can never repaint a `Terminal`.
     */
    private fun moveTo(next: EngineState, generation: Int?) {
        if (generation != null && generation != this.generation.get()) return
        _state.update { current -> if (current is EngineState.Terminal) current else next }
    }

    internal companion object {

        /**
         * How many times one attempt may change its `next_action` before the engine stops
         * following and reports `PENDING`. Real flows need at most two (device fingerprint,
         * then challenge); this is a bound on a misbehaving server, not a feature.
         */
        const val MAX_ACTION_STAGES: Int = 6

        /** The failure code for a 3-D Secure step that fell back to needing a method (G14). */
        const val THREE_DS_FAILED_CODE: String = "3ds_failed"
    }
}
