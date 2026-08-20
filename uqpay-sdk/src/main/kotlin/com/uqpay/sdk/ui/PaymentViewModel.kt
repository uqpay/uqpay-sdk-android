package com.uqpay.sdk.ui

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.uqpay.sdk.engine.ConfirmAcceptance
import com.uqpay.sdk.engine.ConfirmPayload
import com.uqpay.sdk.engine.EngineState
import com.uqpay.sdk.engine.NextAction
import com.uqpay.sdk.engine.PaymentEngine
import com.uqpay.sdk.engine.PaymentSession
import com.uqpay.sdk.engine.Presentation
import com.uqpay.sdk.engine.IssuedQr
import com.uqpay.sdk.engine.WalletConfirmClaim
import com.uqpay.sdk.engine.WalletConfirmLatch
import com.uqpay.sdk.ui.wallet.BankTransferDetails
import com.uqpay.sdk.ui.wallet.parseExpiresAt
import com.uqpay.sdk.network.PaymentIntentDto
import com.uqpay.sdk.payment.PaymentMethodType
import com.uqpay.sdk.ui.threeds.ThreeDsContent
import com.uqpay.sdk.payment.PaymentStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the payment screen draws. A pure projection of [EngineState] plus the little
 * screen-only state the ViewModel keeps; the Compose layer branches on this and nothing
 * else, so it never sees a DTO, a payload, or the engine.
 *
 * Every member carries only what a screen needs to draw itself: amounts and references for
 * the header, method types for the list, a kind for a placeholder. No card values, no
 * idempotency keys, no gateway text — those never reach this layer at all.
 */
internal sealed class PaymentUiState {

    /** The intent is being read. Also shown for the instant before a single-wallet auto-confirm. */
    data object Loading : PaymentUiState()

    /**
     * The customer is choosing how to pay.
     *
     * @property methods what to show, in the engine's order (API order, card first — G21)
     *   and already filtered to the types this SDK version can name. Types it predates are
     *   hidden here, never shown as an error, and never re-sorted.
     */
    data class MethodList(
        val amount: String?,
        val currency: String?,
        val merchantOrderId: String?,
        val methods: List<PaymentMethodType>,
    ) : PaymentUiState()

    /**
     * The card form's place. Slice 4 replaces the placeholder with the real form.
     *
     * @property canReturnToList false under [Presentation.CardOnly] — there is no list to
     *   go back to, and back-press cancels instead.
     */
    data class CardPlaceholder(val canReturnToList: Boolean) : PaymentUiState()

    /**
     * A confirm is in flight.
     *
     * @property leaveBlocked true once the customer has tried to leave: the screen must say
     *   why it will not let them (§2c — blocked, never silently swallowed) and must offer no
     *   way out. False while they have not tried, when ordinary progress copy is enough.
     */
    data class Confirming(val methodType: PaymentMethodType, val leaveBlocked: Boolean) : PaymentUiState()

    /**
     * The gateway wants something from the customer. Slices 4 and 5 render the action
     * itself; until then this is a placeholder naming the kind of step, with a way out.
     */
    data class AwaitingAction(val kind: ActionKind) : PaymentUiState()

    /**
     * The card path's 3-D Secure step: the issuer's page, hosted in the SDK's own WebView.
     * Slice 4.
     *
     * Carries the content rather than a [ActionKind] because this is the one action whose
     * *payload* the screen must render — an HTML document to load, or a challenge URL. Kept
     * as a UI-layer type ([ThreeDsContent]) so the rule that a screen never sees an engine
     * type or a wire DTO still holds.
     *
     * @property returnUrlPrefixes URLs that mark the end of the browser step. Only a signal
     *   to stop waiting; the outcome is always re-read from the API — see [ThreeDsScreen].
     */
    data class ThreeDs(
        val content: ThreeDsContent,
        val returnUrlPrefixes: List<String>,
    ) : PaymentUiState()

    /** Nothing for the customer to do; the engine is watching the intent. */
    data object Polling : PaymentUiState()

    // ---- Slice 5 ---------------------------------------------------------------------

    /**
     * A merchant-presented wallet QR is on screen and the engine is watching the intent.
     *
     * @property methodType the wallet the QR belongs to, for the heading and the image's
     *   content description. Null only when neither this screen's own confirm nor the
     *   intent's latest attempt named one — a screen-reader user then hears a generic
     *   "wallet" rather than a wrong brand.
     * @property qrUrl the gateway-hosted PNG. Null when the gateway sent only a payload.
     * @property rawPayload the EMVCo string, shown as text only if no image can be drawn.
     * @property expiresAtEpochMillis parsed from the wire `expires_at`, or null when it was
     *   absent or unparseable — in which case no countdown is drawn at all.
     */
    data class WalletQr(
        val methodType: PaymentMethodType?,
        val amount: String?,
        val currency: String?,
        val qrUrl: String?,
        val rawPayload: String?,
        val expiresAtEpochMillis: Long?,
    ) : PaymentUiState()

    /**
     * Bank-transfer instructions are on screen and the engine is watching the intent.
     *
     * @property details empty until `display_bank_details` is modelled on the wire — see
     *   [com.uqpay.sdk.ui.wallet.BankDetailsScreen].
     */
    data class BankTransfer(
        val details: BankTransferDetails,
        val amount: String?,
        val currency: String?,
    ) : PaymentUiState()

    /** The outcome is decided and the Activity is delivering it. Draw nothing interactive. */
    data object Finishing : PaymentUiState()
}

/** The kinds of customer action a screen can name, decoded from [NextAction]. */
internal enum class ActionKind { QR, REDIRECT, IFRAME, BANK_DETAILS, UNKNOWN }

/**
 * What the ViewModel decided a back-press meant. The Activity does not act on it beyond
 * staying put — every outcome that ends the flow reaches the Activity through the engine's
 * `Terminal` state, so there is exactly one delivery path — but naming the decision makes
 * the rule testable without a screen.
 */
internal enum class BackDecision {

    /** A confirm is in flight; the customer stays, with the blocked state on screen (§2c). */
    BLOCKED,

    /** The card placeholder went back to the method list. Nothing was cancelled. */
    RETURNED_TO_LIST,

    /** Nothing was in the air; the engine settled `CANCELLED`. */
    CANCELLED,

    /** An attempt was in the air; the engine settled `PENDING`, never `CANCELLED`. */
    PENDING,

    /** The outcome was already decided; the Activity is delivering it. */
    ALREADY_FINISHED,
}

/**
 * The payment screen's ViewModel. Deliberately thin: it projects the session's
 * [EngineState] into a [PaymentUiState], holds the screen-only state that must survive
 * recreation, and owns the one timing rule the screen has — the bounded back-press block.
 *
 * ### What lives where — and what may never be here
 *
 * **Money state lives in the [PaymentSession]** (which engine, which attempt, in flight or
 * not) and survives rotation because the session registry does. This class holds a
 * reference to it and nothing derived from it. **Screen state lives in [SavedStateHandle]**
 * — whether the card placeholder is showing, and when the blocked back-press window
 * started — because it must survive rotation *and* process death, and because losing it
 * would only cost a screen, never a payment.
 *
 * Nothing card-derived — no PAN, CVC, expiry, name — may ever be written to the saved
 * state, now or when Slice 4 adds the card form. Saved state is a Bundle the OS persists to
 * disk across process death; card data at rest is the one thing this SDK must never
 * produce. Slice 4's form must keep its values in memory and re-read them per send; the
 * string-scan test of the saved state is theirs to add.
 *
 * ### The blocked window (§2c) — bounded, persisted, and honest
 *
 * While the engine is `Confirming` a back-press does not leave; it starts a window of
 * [blockedWindowMillis] measured on the monotonic clock. Its start instant is saved, so
 * rotation cannot reset it and a customer cannot be held longer than the bound by turning
 * their phone. When the window ends — by expiry, or earlier because the confirm resolved —
 * the customer's request to leave is honoured on whatever state the engine is then in:
 * `Terminal` needs nothing (the Activity delivers it); anything with an attempt in the air
 * is cancelled and settles `PENDING`, never `CANCELLED`, because the attempt was sent.
 *
 * ### The single-wallet presentation (G19)
 *
 * [Presentation.SingleWallet] means the merchant already asked the customer which wallet.
 * The engine still enters `SelectingMethod` (with a one-entry list); this class confirms
 * that entry the moment it appears, so the customer never sees a list. Honoured here rather
 * than in Compose so it holds even if a future screen forgets.
 */
internal class PaymentViewModel(
    val paymentIntentId: String,
    private val session: PaymentSession,
    private val savedState: SavedStateHandle,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val blockedWindowMillis: Long = BLOCKED_WINDOW_MILLIS,
    /**
     * Slice 5. The one-confirm-per-intent-and-wallet guard. Its registry is static, so the
     * default instance is as good as a shared one; it is a parameter only so a test can
     * hand in an instance whose registry it has just cleared.
     */
    private val walletLatch: WalletConfirmLatch = WalletConfirmLatch(),
) : ViewModel() {

    private val engine: PaymentEngine get() = session.engine

    /** Screen-only: is the card placeholder showing (over a method-list presentation)? */
    private val cardFormShown: StateFlow<Boolean> = savedState.getStateFlow(KEY_CARD_FORM_SHOWN, false)

    /** Screen-only: when the blocked back-press window started, or [NO_BLOCK]. Monotonic clock. */
    private val blockedSince: StateFlow<Long> = savedState.getStateFlow(KEY_BLOCKED_SINCE, NO_BLOCK)

    private var watchdog: Job? = null

    /**
     * Whether the screen has been in the foreground once already. Held on the ViewModel, so
     * it survives rotation with everything else about this screen; see [onForegrounded].
     */
    private var hasBeenForegrounded = false

    /**
     * Slice 5. The QR the latch handed back when this screen tried to confirm a wallet that
     * had already been confirmed — see [confirmWallet]. Not saved state: it is recoverable
     * from the latch (same process) or from the intent (any process), and a live QR is not
     * something to write to disk.
     */
    private val reIssuedQr = MutableStateFlow<IssuedQr?>(null)

    /** What the screen draws now. */
    val uiState: StateFlow<PaymentUiState> =
        combine(session.state, cardFormShown, blockedSince, reIssuedQr) { engineState, card, blocked, reIssued ->
            withReIssuedQr(render(engineState, card, blocked != NO_BLOCK), reIssued)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            withReIssuedQr(
                render(session.state.value, cardFormShown.value, blockedSince.value != NO_BLOCK),
                reIssuedQr.value,
            ),
        )

    init {
        // A saved blocked window only means something while the confirm it blocked is still
        // in flight (rotation). After process death the session is fresh and Idle: forget it.
        if (blockedSince.value != NO_BLOCK) {
            if (engine.isConfirmInFlight) ensureWatchdog() else savedState[KEY_BLOCKED_SINCE] = NO_BLOCK
        }
        viewModelScope.launch { autoConfirmSingleWallet() }
        viewModelScope.launch { keepWalletLatch() }
    }

    // ---- projection ---------------------------------------------------------------------

    private fun render(state: EngineState, cardFormShown: Boolean, leaveBlocked: Boolean): PaymentUiState =
        when (state) {
            EngineState.Idle, EngineState.LoadingIntent -> PaymentUiState.Loading
            is EngineState.SelectingMethod -> when (state.presentation) {
                Presentation.CardOnly -> PaymentUiState.CardPlaceholder(canReturnToList = false)
                is Presentation.SingleWallet -> PaymentUiState.Loading
                Presentation.MethodList ->
                    if (cardFormShown) {
                        PaymentUiState.CardPlaceholder(canReturnToList = true)
                    } else {
                        PaymentUiState.MethodList(
                            amount = state.intent.amount,
                            currency = state.intent.currency,
                            merchantOrderId = state.intent.merchantOrderId,
                            methods = state.methods.filter { it in RENDERABLE_METHODS },
                        )
                    }
            }
            is EngineState.Confirming -> PaymentUiState.Confirming(state.methodType, leaveBlocked)
            // Slice 4: the two 3-D Secure shapes are rendered, not merely named. Every other
            // action still falls through to the kind-only placeholder (Slice 5 renders QR).
            is EngineState.RequiresAction -> when (val action = state.action) {
                is NextAction.Iframe -> PaymentUiState.ThreeDs(
                    ThreeDsContent.Iframe(action.html),
                    returnUrlPrefixes(state.intent),
                )
                is NextAction.Redirect -> PaymentUiState.ThreeDs(
                    ThreeDsContent.Url(action.url),
                    returnUrlPrefixes(state.intent),
                )
                // Slice 5: the QR and the transfer instructions are rendered, not named.
                is NextAction.Qr -> PaymentUiState.WalletQr(
                    methodType = walletOf(state.intent),
                    amount = state.intent.amount,
                    currency = state.intent.currency,
                    qrUrl = action.url,
                    rawPayload = null,
                    expiresAtEpochMillis = parseExpiresAt(action.expiresAt),
                )
                is NextAction.BankDetails -> PaymentUiState.BankTransfer(
                    // Empty until `display_bank_details` is modelled on the wire; the screen
                    // says so honestly rather than drawing three blank rows.
                    details = BankTransferDetails(),
                    amount = state.intent.amount,
                    currency = state.intent.currency,
                )
                else -> PaymentUiState.AwaitingAction(action.kind())
            }
            is EngineState.Polling -> PaymentUiState.Polling
            is EngineState.Terminal -> PaymentUiState.Finishing
        }

    private fun NextAction.kind(): ActionKind = when (this) {
        is NextAction.Qr -> ActionKind.QR
        is NextAction.Redirect -> ActionKind.REDIRECT
        is NextAction.Iframe -> ActionKind.IFRAME
        is NextAction.BankDetails -> ActionKind.BANK_DETAILS
        is NextAction.Unknown -> ActionKind.UNKNOWN
    }

    // ---- intents from the screen ----------------------------------------------------------

    /**
     * The customer tapped a method. Card opens the card placeholder (Slice 4: the form);
     * a wallet confirms straight away — that is the wallet flow, and it is real: the
     * gateway issues the QR the moment the confirm lands (Slice 5 renders it).
     *
     * Ignored unless the engine is choosing a method: a stale tap after the confirm started
     * must not become a second attempt (the engine would refuse it anyway — this is the
     * screen-side half of the same rule).
     */
    fun onMethodSelected(method: PaymentMethodType) {
        if (session.state.value !is EngineState.SelectingMethod) return
        if (method == PaymentMethodType.CARD) {
            savedState[KEY_CARD_FORM_SHOWN] = true
        } else {
            confirmWallet(method)
        }
    }

    /**
     * The card form's Pay button, with a payload the form has already validated (Slice 4).
     *
     * The engine still owns every decision about whether this becomes an attempt — it is the
     * one thing holding the idempotency pin and the in-flight state. This method exists only
     * to add the screen-side half of the duplicate guard: a tap that arrives when the engine
     * is no longer choosing a method (a double-tap, a stale click queued behind a recomposition)
     * is dropped here rather than argued about downstream.
     */
    fun onCardSubmitted(payload: ConfirmPayload.Card): ConfirmAcceptance {
        if (session.state.value !is EngineState.SelectingMethod) return ConfirmAcceptance.REJECTED_NOT_CONFIRMABLE
        return engine.confirm(payload)
    }

    /**
     * The 3-D Secure WebView reached the return URL.
     *
     * **Not an outcome.** It re-reads the intent now instead of waiting for the poller's next
     * tick, which is the entire value of noticing: the customer waits a second rather than up
     * to a poll interval. Nothing here settles anything, and nothing here reads the return
     * URL's query parameters — see [ThreeDsScreen] for why that would be a security bug.
     */
    fun onThreeDsReturned() {
        engine.nudge()
    }

    /**
     * The payment screen came to the foreground (AC §8.1, the G-series foreground re-read).
     *
     * The case this exists for is the customer who left to their **banking or wallet app** —
     * scanned the QR in Alipay, approved the 3-D Secure push in their bank's app — and came
     * back. The gateway already knows the payment succeeded; without this the customer stares
     * at a spinner for up to one poll interval after the fact.
     *
     * It is wired from `UQPayPaymentActivity.onStart` rather than from
     * `ProcessLifecycleOwner`: `androidx.lifecycle:lifecycle-process` is not on this SDK's
     * classpath, and a payment SDK does not add a dependency to observe a lifecycle it
     * already hosts. The Activity's own start covers the case that matters, because the
     * customer returning from another app necessarily restarts this screen.
     *
     * **The first foreground is not a return.** It arrives before the intent has even been
     * read, so nudging then would be a no-op with a misleading name; skipping it also keeps
     * the "one re-read per return" property honest for tests.
     *
     * A nudge **replaces** the wait in progress; it never adds a poll and never inflates the
     * attempt budget (see [com.uqpay.sdk.engine.IntentPoller.nudge]). A rotation also
     * restarts the Activity and so reads as a return here — harmless for exactly that reason.
     */
    fun onForegrounded() {
        if (!hasBeenForegrounded) {
            hasBeenForegrounded = true
            return
        }
        engine.nudge()
    }

    /**
     * Return-URL prefixes for the 3-D Secure screen: the intent's own `return_url`.
     *
     * A custom (non-`http`) scheme is recognised by [ThreeDsReturnUrl] without any
     * configuration, so this matters for the merchant who registered an **https** return
     * instead — for them it is the only signal that the browser step is over, and without it
     * the customer sits on the merchant's own landing page inside our WebView until the poll
     * budget runs out.
     *
     * Only the *prefix* is used. Nothing is ever read out of the returned URL's query
     * parameters — see [ThreeDsScreen] for why trusting them would be a security bug — and
     * the outcome is always re-read from the API.
     *
     * Blank or malformed values are dropped rather than passed on: `isEndOfBrowserStep`
     * would treat an empty prefix as matching every URL, which would end the step on the
     * ACS page itself.
     */
    private fun returnUrlPrefixes(intent: PaymentIntentDto): List<String> =
        listOfNotNull(intent.returnUrl?.trim()?.takeIf { it.isNotEmpty() })

    /** The card placeholder's "back to list". Nothing is cancelled. */
    fun onReturnToList() {
        savedState[KEY_CARD_FORM_SHOWN] = false
    }

    /**
     * The customer pressed back. Reads "confirm in flight?" **before** cancelling anything
     * (G1): a confirm in flight blocks; an attempt in the air is `PENDING`; a card
     * placeholder over a list goes back to the list; otherwise `CANCELLED`.
     *
     * Never finishes anything itself. Every path that ends the flow does so by settling the
     * engine, and the Activity delivers `Terminal` — one path, exactly once.
     */
    fun onBackRequested(): BackDecision {
        val state = session.state.value
        return when {
            state is EngineState.Terminal -> BackDecision.ALREADY_FINISHED
            engine.isConfirmInFlight -> {
                beginBlockedWindow()
                BackDecision.BLOCKED
            }
            engine.hasAttemptInAir -> cancelAndReport()
            cardFormShown.value && state is EngineState.SelectingMethod && state.presentation == Presentation.MethodList -> {
                onReturnToList()
                BackDecision.RETURNED_TO_LIST
            }
            else -> cancelAndReport()
        }
    }

    /**
     * The explicit Cancel on a waiting screen (QR / redirect / polling placeholders): the
     * customer's way out of an attempt whose outcome the SDK has not yet learned (audit
     * M-3/M-4). Settles `PENDING` when an attempt is in the air, `CANCELLED` otherwise.
     */
    fun onCancelConfirmed(): BackDecision =
        if (session.state.value is EngineState.Terminal) BackDecision.ALREADY_FINISHED else cancelAndReport()

    /**
     * How much of the blocked window is left, in milliseconds; zero when no window is open.
     * For the screen's optional countdown and for tests.
     */
    fun blockedWindowRemainingMillis(): Long {
        val since = blockedSince.value
        if (since == NO_BLOCK) return 0L
        return (since + blockedWindowMillis - now()).coerceAtLeast(0L)
    }

    // ---- internals ------------------------------------------------------------------------

    /**
     * Confirms a wallet — **at most once per intent and wallet**, enforced by
     * [WalletConfirmLatch] rather than by this screen's own state.
     *
     * The engine already refuses a second tap *within one session*: [onMethodSelected]
     * returns early unless the engine is choosing a method. What it cannot see is a second
     * *session* — the customer leaves (settling `PENDING`, which leaves the QR live), the
     * merchant relaunches the sheet for the same intent, a fresh engine loads it, and the
     * method list appears again. Tapping the same wallet there sends a second confirm under
     * a fresh idempotency key, and the live sandbox **accepts it**: two attempts, two QRs,
     * and the first one orphaned but still payable. The latch is what stops that.
     *
     * @return the engine's acceptance, or null when no confirm was sent because one had
     *   already been made for this intent and wallet.
     */
    private fun confirmWallet(method: PaymentMethodType): ConfirmAcceptance? {
        savedState[KEY_WALLET_METHOD] = method.raw
        return when (val claim = walletLatch.claim(paymentIntentId, method.raw)) {
            WalletConfirmClaim.Granted ->
                engine.confirm(ConfirmPayload.Wallet.forMethod(paymentIntentId, method.raw))

            // Re-serve the QR the first confirm issued. Never a second confirm.
            is WalletConfirmClaim.AlreadyIssued -> {
                reIssuedQr.value = claim.qr
                null
            }

            // A confirm is on the wire and has not answered. There is nothing to show yet
            // and nothing safe to send; the engine's own progress state covers the wait.
            WalletConfirmClaim.AlreadyInFlight -> null
        }
    }

    /**
     * The wallet this screen is showing: the one it confirmed, else the intent's own.
     *
     * **The fallback is currently inert, and that is a wire bug, not a design choice.**
     * Verified live on 2026-08-18: `latest_payment_attempt` has **no `payment_method_type`
     * key at all** — the method lives at `latest_payment_attempt.payment_method.type`
     * (`{"grabpay":{…},"type":"grabpay"}`), and `PaymentAttemptDto` models the name that does
     * not exist, so it decodes to null forever. The same failure mode as `payment_intent_id`
     * before it was checked against the live gateway.
     *
     * The saved-state half carries the name in practice, including across process death, so
     * nothing on screen is wrong today. Fixing the DTO is an additive change to a file this
     * slice does not own and one that Slice 4's decline mapping also depends on; it is
     * reported rather than made here.
     */
    private fun walletOf(intent: PaymentIntentDto): PaymentMethodType? {
        val raw = savedState.get<String>(KEY_WALLET_METHOD)
            ?: intent.latestPaymentAttempt?.paymentMethod?.type
        return raw?.takeIf { it.isNotBlank() }?.let(PaymentMethodType::of)
    }

    /**
     * Overlays a re-served QR on the method list.
     *
     * Only over [PaymentUiState.MethodList]: a re-entry finds the engine in
     * `SelectingMethod`, and the customer who just tapped the wallet must see the QR that
     * already exists rather than the list they tapped from. Every other state — a confirm in
     * flight, an action the engine is already rendering, a decided outcome — outranks it.
     */
    private fun withReIssuedQr(state: PaymentUiState, qr: IssuedQr?): PaymentUiState {
        if (qr == null || state !is PaymentUiState.MethodList) return state
        return PaymentUiState.WalletQr(
            methodType = savedState.get<String>(KEY_WALLET_METHOD)?.let(PaymentMethodType::of),
            amount = state.amount,
            currency = state.currency,
            qrUrl = qr.url,
            rawPayload = qr.rawPayload,
            expiresAtEpochMillis = parseExpiresAt(qr.expiresAt),
        )
    }

    /**
     * Keeps the wallet latch in step with the engine, for the lifetime of this screen.
     *
     * Two rules, and the second is the money rule:
     *
     * 1. When the engine surfaces a QR, **record it** — that is what makes a later re-entry
     *    re-serve instead of re-confirm, and it also captures a QR that arrived from a plain
     *    intent read rather than from our own confirm.
     * 2. When the payment reaches a **demonstrably finished** outcome, free the latch.
     *    `SUCCEEDED`, `FAILED` and `CANCELLED` are finished. **`PENDING` is not**, and that
     *    is the whole point: `PENDING` is the SDK saying "we stopped looking" — the poll
     *    budget ran out, or the customer left while an attempt was in the air. The QR is
     *    still on their screen and still payable. Freeing the latch there would let the next
     *    tap open a second live attempt against a payment that is about to be made.
     */
    private suspend fun keepWalletLatch() {
        session.state.collect { state ->
            when (state) {
                is EngineState.RequiresAction -> {
                    val action = state.action
                    val wallet = walletOf(state.intent)?.raw
                    if (action is NextAction.Qr && wallet != null) {
                        walletLatch.recordIssued(
                            paymentIntentId,
                            wallet,
                            IssuedQr(url = action.url, expiresAt = action.expiresAt),
                        )
                    }
                }

                is EngineState.Terminal -> {
                    val wallet = savedState.get<String>(KEY_WALLET_METHOD)
                    // PENDING is deliberately absent from this test. See the KDoc.
                    if (wallet != null && state.result.status != PaymentStatus.PENDING) {
                        walletLatch.attemptFinished(paymentIntentId, wallet)
                    }
                }

                else -> Unit
            }
        }
    }

    /**
     * Lets the engine decide between `CANCELLED` and `PENDING` — it holds the lock and the
     * truth about what is in the air — and reports what it decided.
     */
    private fun cancelAndReport(): BackDecision {
        engine.cancel()
        val terminal = session.state.value as? EngineState.Terminal ?: return BackDecision.ALREADY_FINISHED
        return if (terminal.result.status == PaymentStatus.PENDING) BackDecision.PENDING else BackDecision.CANCELLED
    }

    private fun beginBlockedWindow() {
        if (blockedSince.value == NO_BLOCK) savedState[KEY_BLOCKED_SINCE] = now()
        ensureWatchdog()
    }

    /**
     * Waits for the window to end — by the bound, or earlier because the engine left
     * `Confirming` — then honours the customer's request to leave on the current state.
     * One watchdog at a time; a repeated back-press while blocked does not shorten anything.
     */
    private fun ensureWatchdog() {
        if (watchdog?.isActive == true) return
        watchdog = viewModelScope.launch {
            withTimeoutOrNull(blockedWindowRemainingMillis()) {
                session.state.first { it !is EngineState.Confirming }
            }
            resolveBlockedBack()
        }
    }

    /**
     * The window is over. If the outcome is decided the Activity is already delivering it;
     * otherwise the customer wanted out and gets out: the engine cancels, and because an
     * attempt was sent this settles `PENDING`. That is the "best-known result" §2c asks for
     * — the customer leaves, the merchant is told the truth, and the attempt keeps running
     * in the session as a reconciler for pin resolution only.
     */
    private fun resolveBlockedBack() {
        if (session.state.value is EngineState.Terminal) return
        engine.cancel()
    }

    /**
     * G19 on the wallet path: under [Presentation.SingleWallet], confirm the one offered
     * wallet as soon as the engine offers it. Once, per screen: after the confirm the engine
     * never re-enters `SelectingMethod`, so a recreated ViewModel finds nothing to do.
     */
    private suspend fun autoConfirmSingleWallet() {
        val selecting = session.state.filterIsInstance<EngineState.SelectingMethod>().first()
        val presentation = selecting.presentation as? Presentation.SingleWallet ?: return
        if (engine.hasAttemptInAir || session.state.value !is EngineState.SelectingMethod) return
        confirmWallet(presentation.method)
    }

    internal companion object {

        /**
         * The bound on the blocked back-press window (§2c: "~10 s, matching the replay
         * ladder"). Long enough for a normal confirm to answer, short enough that a customer
         * is never held hostage by a slow gateway.
         */
        const val BLOCKED_WINDOW_MILLIS: Long = 10_000L

        /** Saved-state key: the card placeholder is showing. */
        @VisibleForTesting
        const val KEY_CARD_FORM_SHOWN: String = "uqpay.cardFormShown"

        /** Saved-state key: `elapsedRealtime` when the blocked window opened, or [NO_BLOCK]. */
        @VisibleForTesting
        const val KEY_BLOCKED_SINCE: String = "uqpay.blockedSince"

        /**
         * Saved-state key: the wire type of the wallet this screen confirmed. A gateway
         * method name (`grabpay`), never anything card-derived or customer-identifying, so
         * it is safe in a Bundle the OS writes to disk — and it must survive process death,
         * or a relaunched screen cannot name the wallet whose QR it is showing.
         */
        @VisibleForTesting
        const val KEY_WALLET_METHOD: String = "uqpay.walletMethod"

        const val NO_BLOCK: Long = -1L

        /**
         * G21: the method types this SDK version can name and therefore render. Card plus
         * the thirteen declared wallets. Anything else in `available_payment_method_types`
         * is hidden — never an error, and never a crash.
         */
        val RENDERABLE_METHODS: Set<PaymentMethodType> = setOf(
            PaymentMethodType.CARD,
            PaymentMethodType.WECHAT_PAY,
            PaymentMethodType.ALIPAY_CN,
            PaymentMethodType.ALIPAY_HK,
            PaymentMethodType.GRABPAY,
            PaymentMethodType.PAYNOW,
            PaymentMethodType.UNIONPAY,
            PaymentMethodType.TRUEMONEY,
            PaymentMethodType.TNG,
            PaymentMethodType.GCASH,
            PaymentMethodType.DANA,
            PaymentMethodType.KAKAOPAY,
            PaymentMethodType.TOSSPAY,
            PaymentMethodType.NAVERPAY,
        )

        /** The factory the Activity uses; the saved-state handle comes from the owner. */
        fun factory(paymentIntentId: String, session: PaymentSession): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    PaymentViewModel(paymentIntentId, session, createSavedStateHandle())
                }
            }
    }
}
