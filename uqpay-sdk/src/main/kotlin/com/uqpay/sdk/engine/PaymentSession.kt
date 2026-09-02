package com.uqpay.sdk.engine

import androidx.annotation.VisibleForTesting
import com.uqpay.sdk.UQPay
import com.uqpay.sdk.UQPayConfiguration
import com.uqpay.sdk.error.ErrorCopy
import com.uqpay.sdk.network.DefaultUQPayNetworkClient
import com.uqpay.sdk.network.ErrorMapper
import com.uqpay.sdk.network.TokenManager
import com.uqpay.sdk.network.UQPayApiClient
import com.uqpay.sdk.network.UQPayLogger
import com.uqpay.sdk.network.UQPayNetworkClient
import com.uqpay.sdk.store.NoBackupConfirmAttemptStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * Everything a [PaymentSession] needs that is not derived from [UQPay]'s configuration.
 *
 * Production callers use [production] and never think about this class. It exists so the
 * composition root in [PaymentSession.obtain] can be exercised end to end in a unit test —
 * with a scripted [UQPayNetworkClient] instead of a socket, a test dispatcher instead of
 * [Dispatchers.IO], and a short orphan lifetime instead of a minute — while still building
 * the **real** token manager, API client, error mapper, runner, poller and engine. A wiring
 * mistake between any two of those is exactly the bug a fake at a higher seam would hide.
 *
 * @property networkClient the socket seam. `null` builds [DefaultUQPayNetworkClient].
 * @property workContext where the engine's coroutines run and where the token manager
 *   fetches. [Dispatchers.IO] in production; a `TestDispatcher` in tests, which makes the
 *   whole session deterministic under `runTest`.
 * @property clock the monotonic clock for the replay ladder and the poller.
 * @property orphanLifetimeMillis how long a released session with work still in flight may
 *   live before it is cancelled and evicted; see [PaymentSession.release].
 * @property wallClock wall-clock time for a success's `completedAt`.
 * @property logger `null` — the default — means "ask the configuration", which is how
 *   `UQPayConfiguration.loggingEnabled` reaches the graph. A test that wants to *assert* on
 *   log lines passes its own recorder and is then independent of the configuration.
 */
internal class SessionDependencies(
    val networkClient: UQPayNetworkClient? = null,
    val workContext: CoroutineContext = Dispatchers.IO,
    val clock: Clock = ElapsedRealtimeClock,
    val orphanLifetimeMillis: Long = PaymentSession.ORPHAN_LIFETIME_MILLIS,
    val wallClock: () -> Long = System::currentTimeMillis,
    val logger: UQPayLogger? = null,
) {
    internal companion object {
        /** The production graph: real socket, IO dispatcher, real clocks. */
        fun production(): SessionDependencies = SessionDependencies()
    }
}

/**
 * One live payment: a [PaymentEngine], the [CoroutineScope] it runs on, and the identity
 * that lets a **recreated** screen find it again.
 *
 * ### Why this exists — rotation must re-attach, never re-submit
 *
 * A configuration change destroys and recreates the payment Activity in the middle of
 * whatever the engine is doing. If the new Activity built a new engine, that engine would
 * `load()` again and offer the customer a Pay button while the *old* engine's confirm was
 * still in the air: a second tap is then a second attempt, and the customer is charged twice.
 * The rotation-mid-payment disaster is avoided by construction rather than by care: sessions
 * live in a **process-scoped registry keyed by payment intent id**, and a recreated Activity
 * calls [obtain] and gets back the *same* session, the *same* engine, mid-flight, with its
 * state exactly where it was. [PaymentEngine.load] is single-use, so the session tracks
 * whether it has been started and the caller uses [startIfNeeded] — the recreated screen
 * cannot accidentally load twice, because it never decides whether to load at all.
 *
 * ### What this layer does not do — process death
 *
 * The registry is in memory. When Android kills the process the map is gone, and a relaunch
 * builds a fresh session whose engine loads afresh. That is correct and deliberate: after
 * process death there is no running confirm to re-attach to, and the honest thing to do is
 * re-read the intent. What protects the customer's money across process death is the
 * **persisted idempotency pin** (WU-2.4): the fresh engine's runner re-reads the intent
 * (pre-confirm intercept) and, if the customer pays again with the same details, replays the
 * pinned key rather than minting a new one. This class does not — must not — pretend
 * otherwise by persisting anything itself; the confirm body holds card data.
 *
 * ### The scope, and why it is neither the Activity's nor the ViewModel's
 *
 * Per the plan's §2a/§2c, back-press is blocked while a confirm is in flight, so a screen
 * cannot normally leave mid-confirm and a ViewModel scope would usually suffice. It is still
 * held here, on the session, for two reasons. First, rotation: a `viewModelScope` survives
 * rotation but the *engine* must be found again by the new screen, and the natural owner of
 * "the engine and the scope it runs on" is one object. Second, the bounded block expires
 * (§2c: ~10s, then finish with `PENDING`) and the customer may also be forced out — after a
 * `PENDING` is delivered, the in-flight attempt keeps running as a **detached reconciler**
 * whose only job is pin resolution and wallet-latch bookkeeping. It never delivers a second
 * result (there is no channel for one); it exists so a definitive answer that arrives late
 * still releases the idempotency pin. That reconciler needs a scope that outlives the screen,
 * bounded so it cannot leak — see [release].
 *
 * ### One `ConfirmIdempotency` per process
 *
 * The registry's pending map is static while its store is per-instance (WU-2.4). Two
 * registries over two stores would share one pin map and disagree about what is persisted.
 * So there is exactly one, built lazily in the companion the first time any session needs it
 * and shared by every session in the process.
 *
 * ### One `TokenManager` per configuration
 *
 * For the same reason at the other end of the graph: UQPAY allows one active access token
 * per merchant, so every session under a configuration shares one manager and one token
 * rather than each minting its own and invalidating the others'. See
 * [sharedTokenManagerLocked].
 *
 * ### The composition root
 *
 * [obtain] is the first place the SDK is wired end to end: configuration → token manager →
 * API client → per-intent source and sender → runner → steps → engine. It is written out
 * explicitly, in dependency order, so the graph can be read top to bottom.
 *
 * Everything here is `internal`. Slice 3's ViewModel and Activity consume it; merchants
 * never see it.
 */
internal class PaymentSession private constructor(
    /** The payment this session drives. The registry key. */
    val paymentIntentId: String,
    /** The engine, mid-flight or not. Slice 3 renders [PaymentEngine.state] from here. */
    val engine: PaymentEngine,
    /** The scope every coroutine of this payment runs on. Cancelled by [release] — eventually. */
    private val scope: CoroutineScope,
    private val orphanLifetimeMillis: Long,
    private val logger: UQPayLogger,
    /**
     * The registry this session's runner pins through. Held only so a test can assert that
     * every session in the process shares **one** instance (WU-2.4) — the socket cannot show
     * the difference, because the registry's pending map is static either way.
     */
    @get:VisibleForTesting
    val idempotency: ConfirmIdempotency,
) {

    private val started = AtomicBoolean(false)

    /** The presentation [startIfNeeded] loaded with; null until it has. See [startIfNeeded]. */
    @Volatile
    private var startedWith: Presentation? = null

    /**
     * How many Activities are currently hosting this payment. See [attachHost].
     *
     * Read and written under [lock] together with [retiring], because "was that the last one"
     * and "has the flow been declared over" have to be answered as one question.
     */
    private var hosts = 0

    /** True once some host declared the flow finished for good. Guarded by [lock]. */
    private var retiring = false

    /** True once [retire] has run. Guarded by [lock]; makes retirement idempotent. */
    private var retired = false

    /** Where the payment is; the same flow as [PaymentEngine.state], for convenience. */
    val state: StateFlow<EngineState>
        get() = engine.state

    /** How many hosts are attached. Diagnostics and tests. */
    @get:VisibleForTesting
    val hostCount: Int
        get() = synchronized(lock) { hosts }

    /**
     * Whether [startIfNeeded] has already loaded this session's engine. A recreated Activity
     * finding this `true` is a re-attach: it renders [state] and calls nothing.
     */
    val hasStarted: Boolean
        get() = started.get()

    /** The presentation this session was started with, or null before [startIfNeeded]. Tests. */
    @get:VisibleForTesting
    val startedPresentation: Presentation?
        get() = startedWith

    /**
     * Whether this session's scope is still alive. `false` once [release] has cancelled it —
     * immediately, or at the end of an orphan's bounded lifetime. Diagnostics and tests.
     */
    val isActive: Boolean
        get() = scope.isActive

    /**
     * Loads the engine the first time it is called; a no-op on every later call.
     *
     * The recreated-Activity path and the first-creation path are the **same call**: the
     * screen never has to know whether it is new or recreated, which is what makes rotation
     * safe rather than merely handled. Idempotent by an atomic flag rather than by the
     * engine's own single-use guard so the session can answer [hasStarted] and so a second
     * call is a documented no-op instead of a logged programming error.
     *
     * ### The first launch's presentation wins
     *
     * A second launch of the *same* intent while it is still running — a double-tap, or a
     * merchant relaunching a sheet the customer left open — re-attaches to this session, and
     * this call is the no-op described above. Its [presentation] is therefore **ignored**:
     * the engine already chose its screen when it loaded, and the customer may be half-way
     * through it. Rebuilding the engine to honour the new presentation would be the
     * rotation disaster by another name (a second load, a second Pay button, a second
     * confirm), and swapping the screen under a customer typing a card number is not better.
     * The difference is logged at debug so an integrator who meant the second one can see
     * why they got the first; the launch parcel's `billingDetails` and
     * `allowedPaymentMethods` are read by the new Activity regardless.
     *
     * @return `true` if this call started the load, `false` if it had already been started.
     */
    fun startIfNeeded(presentation: Presentation = Presentation.MethodList): Boolean {
        if (!started.compareAndSet(false, true)) {
            val first = startedWith
            if (first != null && first != presentation) {
                logger.debug(
                    "Ignoring presentation $presentation for a payment already running as $first; " +
                        "the first launch's presentation wins",
                )
            }
            return false
        }
        startedWith = presentation
        engine.load(presentation)
        return true
    }

    /**
     * Declares that an Activity is now hosting this payment. Balanced by [detachHost].
     *
     * ### Why a count, and not a flag
     *
     * A session is keyed by payment intent id, and **more than one Activity can legitimately
     * hold the same key at the same time**: split-screen or two tasks showing the same order,
     * a merchant that launches the sheet again while the first is still on screen, or simply
     * the overlap between a new instance being created and the old one being destroyed.
     *
     * Before this count existed, the *first* of those hosts to be destroyed retired the shared
     * scope. The second host was then holding an engine whose coroutines could not run: its
     * confirm launched on a cancelled scope and returned nothing, back-press stayed blocked
     * for the full ten seconds because the confirm it was waiting on could never resolve, and
     * the payment settled `PENDING` for a confirm that never left the device. `PENDING` tells
     * the merchant to wait for a webhook, and no webhook is ever coming for a request that was
     * not sent — a permanently stuck order, which is the worst outcome this SDK can produce.
     *
     * Attaching is *not* what [obtain] does. Obtaining is a lookup — tests and diagnostics do
     * it without hosting anything — while hosting is a claim on the session's lifetime that
     * exactly one component makes: `UQPayPaymentActivity`, once per instance, balanced in
     * `onDestroy`.
     */
    fun attachHost() {
        synchronized(lock) { hosts++ }
    }

    /**
     * The counterpart of [attachHost]: this host is gone.
     *
     * @param forGood true when the flow is over — the result has been delivered and no
     *   Activity will come looking for this payment again. False for a *recreation*: a
     *   configuration change, or a system-initiated destroy that a relaunch may recover from.
     *   The distinction is the caller's, because only the caller can tell them apart, and it
     *   is exactly the `isFinishing && !isChangingConfigurations` predicate.
     *
     * `forGood` evicts the session from the registry immediately, so a later launch for the
     * same intent builds a fresh engine rather than adopting a finished one. The scope,
     * though, is only ended when the **last** host has gone: another Activity may still be
     * driving this very payment, and cancelling underneath it is the bug described in
     * [attachHost].
     */
    fun detachHost(forGood: Boolean) {
        val shouldRetire = synchronized(lock) {
            if (hosts > 0) hosts--
            if (forGood) {
                if (sessions[paymentIntentId] === this) sessions.remove(paymentIntentId)
                if (!retiring) {
                    retiring = true
                    orphans += this
                }
            }
            retiring && hosts == 0 && !retired
        }
        if (shouldRetire) retire()
    }

    /**
     * Whether any coroutine of this payment is still running — an attempt in the air, a
     * detached reconciler, a load. Reads the scope's own job tree, which is the truth about
     * outstanding work; the engine's state is not, because a `Terminal(PENDING)` engine may
     * still have its attempt job replaying for pin resolution.
     */
    private fun hasLiveWork(excluding: Job? = null): Boolean =
        scope.coroutineContext.job.children.any { it !== excluding && it.isActive }

    /**
     * Ends this session's work: immediately if there is none, otherwise when it finishes or
     * when [orphanLifetimeMillis] elapses, whichever is first.
     *
     * Called once, by the last host to detach from a session that has been declared finished
     * ([detachHost]) — or by [release] for a session with no hosts at all. The [retired] flag
     * makes a second call a no-op rather than a second watchdog coroutine over a scope that
     * is already cancelled.
     */
    private fun retire() {
        synchronized(lock) {
            if (retired) return
            retired = true
        }
        val onRetired = { synchronized(lock) { orphans -= this } }
        if (!hasLiveWork()) {
            scope.cancel(CancellationException("Payment session released"))
            onRetired()
            return
        }
        // Work is still in the air — an attempt whose answer resolves the pin, at most. Let
        // it finish, but not forever: the reconciler budget bounds a well-behaved attempt,
        // and the lifetime bounds a misbehaving one. Either way the registry cannot leak.
        logger.debug("Session released with work in flight; keeping it alive for pin resolution")
        scope.launch {
            val watchdog = coroutineContext.job
            withTimeoutOrNull(orphanLifetimeMillis) {
                scope.coroutineContext.job.children
                    .filter { it !== watchdog }
                    .forEach { it.join() }
            }
            onRetired()
            scope.cancel(CancellationException("Orphaned payment session lifetime elapsed"))
        }
    }

    internal companion object {

        /**
         * How long a released session with work still in flight is kept alive.
         *
         * Sized to the detached reconciler's own budget — `PollBudget.Reconciliation` is 12
         * attempts 5s apart, sixty seconds — plus a request timeout's worth of slack. A
         * released session's remaining work is that reconciler or the tail of a replay
         * ladder, both of which finish well inside this. Anything still running at the bound
         * is cancelled: the pin it was trying to resolve stays pinned, which is the safe
         * side (the next tap replays it), and the process no longer holds an engine for a
         * payment nobody is watching.
         */
        const val ORPHAN_LIFETIME_MILLIS: Long = 75_000L

        /** Guards [sessions], [orphans] and [sharedIdempotency]. Never held across a suspension. */
        private val lock = Any()

        /** Live sessions by intent id — the registry a recreated Activity re-attaches through. */
        private val sessions = mutableMapOf<String, PaymentSession>()

        /**
         * Released sessions still finishing their work. Kept out of [sessions] on purpose: a
         * *new* launch for the same intent must get a fresh engine that re-reads the intent,
         * not a released one that is already `Terminal` and would deliver a stale result to a
         * new screen. Two engines for one intent are safe — both pin under the same digest,
         * so a resend is a replay of the same key, never a second charge.
         */
        private val orphans = mutableSetOf<PaymentSession>()

        /** The one registry per process. See the class KDoc. */
        private var sharedIdempotency: ConfirmIdempotency? = null

        /** The one token manager per configuration. See [sharedTokenManagerLocked]. */
        private var sharedTokenManager: TokenManager? = null

        /** The configuration [sharedTokenManager] was built for, compared by identity. */
        private var sharedTokenManagerFor: UQPayConfiguration? = null

        /**
         * The session for [paymentIntentId]: the running one if it exists, else a new one.
         *
         * **This is the rotation guarantee.** A recreated Activity gets the same instance,
         * the same engine and the same in-flight state as the one it replaced.
         *
         * @param dependencies the non-configuration inputs; tests inject fakes at the socket
         *   seam here. Consulted only when a session is *built* — a re-attach ignores it,
         *   because the running session already has its graph.
         * @throws IllegalStateException if [UQPay.initialize] has not been called. A
         *   programmer error, raised before any payment work starts.
         */
        fun obtain(
            paymentIntentId: String,
            dependencies: SessionDependencies = SessionDependencies.production(),
        ): PaymentSession {
            require(paymentIntentId.isNotBlank()) { "paymentIntentId must not be blank" }
            synchronized(lock) {
                sessions[paymentIntentId]?.let { return it }
                val session = build(paymentIntentId, dependencies)
                sessions[paymentIntentId] = session
                return session
            }
        }

        /** The running session for [paymentIntentId], or null. Never builds one. */
        fun peek(paymentIntentId: String): PaymentSession? = synchronized(lock) { sessions[paymentIntentId] }

        /**
         * The flow for [paymentIntentId] is over: its terminal result has been delivered and
         * the Activity is finishing **for good** — not being recreated. Do not call this on
         * rotation; that is precisely the case [obtain] exists to survive.
         *
         * The session leaves the registry immediately, so a later launch for the same intent
         * builds a fresh engine. Its scope is cancelled immediately if nothing is running,
         * and otherwise kept alive — bounded by [ORPHAN_LIFETIME_MILLIS] — for the one thing
         * a released attempt is still allowed to do: resolve its idempotency pin (and, for
         * wallets, its latch bookkeeping). It never delivers a second result. When that work
         * finishes, or the bound elapses, the scope is cancelled and the session evicted.
         *
         * **A session with hosts still attached is evicted but not ended.** The scope belongs
         * to whoever is still driving the payment; see [attachHost]. `UQPayPaymentActivity`
         * therefore calls [detachHost] rather than this, and reaches the same place when it is
         * the last host — which is every ordinary payment.
         *
         * Releasing an intent that has no session is a no-op.
         */
        fun release(paymentIntentId: String) {
            val session = synchronized(lock) { sessions[paymentIntentId] } ?: return
            session.detachHost(forGood = true)
        }

        /**
         * How many sessions the registry is holding, live and orphaned. Diagnostics and the
         * leak test; a quiet process reads zero.
         */
        @get:VisibleForTesting
        val activeCount: Int
            get() = synchronized(lock) { sessions.size + orphans.size }

        /**
         * Test hook: cancels every session, live or orphaned, forgets them all, and drops the
         * shared idempotency registry **including its pins**. Static state outlives a test
         * method; without this, one test's in-flight confirm leaks into the next.
         *
         * Slice 5 added the wallet latch to that list, for the same reason and one line
         * below: its registry is a process-lifetime `ConcurrentHashMap` keyed
         * `intentId|methodType`, so a test that confirms a wallet on `PI_x` leaves `PI_x`
         * latched for every later test that reuses the id — which then sends no confirm at
         * all and fails somewhere that looks unrelated.
         */
        @VisibleForTesting
        fun clearAllForTest() {
            val toCancel: List<PaymentSession>
            val idempotency: ConfirmIdempotency?
            synchronized(lock) {
                toCancel = sessions.values.toList() + orphans.toList()
                sessions.clear()
                orphans.clear()
                idempotency = sharedIdempotency
                sharedIdempotency = null
                sharedTokenManager = null
                sharedTokenManagerFor = null
            }
            toCancel.forEach { it.scope.cancel(CancellationException("clearAllForTest")) }
            idempotency?.clearAllForTest()
            WalletConfirmLatch.clearForTest()
        }

        // ---- The composition root ----------------------------------------------------

        /**
         * Builds one session's dependency graph, top to bottom. Called under [lock].
         *
         * Reads [UQPay.requireConfiguration] and [UQPay.requireAppContext] here — the first
         * and only place the SDK's static configuration meets the engine — so an
         * uninitialised SDK fails at `obtain`, before any screen is shown.
         */
        private fun build(paymentIntentId: String, deps: SessionDependencies): PaymentSession {
            val configuration = UQPay.requireConfiguration()
            val appContext = UQPay.requireAppContext()
            // F5: the one place `UQPayLogger.Logcat` is ever constructed. Merchants opt in
            // through the configuration; everything else in the process gets the discarding
            // logger, and no code path chooses a logger for itself.
            val logger = deps.logger
                ?: if (configuration.loggingEnabled) UQPayLogger.Logcat() else UQPayLogger.Noop

            // Transport and authentication.
            val networkClient = deps.networkClient
                ?: DefaultUQPayNetworkClient(logger = logger, workContext = deps.workContext)
            val tokenManager = sharedTokenManagerLocked(configuration, deps.workContext)
            val apiClient = UQPayApiClient(configuration, networkClient, tokenManager, logger)
            val errorMapper = ErrorMapper(configuration.environment, ErrorCopy.from(appContext))

            // This intent's two seams onto the API: read it, confirm it.
            val intentSource = IntentSource.forIntent(apiClient, paymentIntentId)
            val confirmSender = ConfirmSender.forIntent(apiClient, paymentIntentId)

            // The confirm path: shared registry, per-process; runner; the engine's step.
            val idempotency = sharedIdempotencyLocked(logger)
            val runner = ConfirmRunner(
                idempotency = idempotency,
                errorMapper = errorMapper,
                clock = deps.clock,
                logger = logger,
            )
            val confirmStep = ConfirmStep.forIntent(runner, intentSource, confirmSender)
            val watchStep = WatchStep.forIntent(intentSource, deps.clock, logger)

            // The scope: supervised so one failed child cannot take the others down, and
            // owned here rather than by any lifecycle (§2a).
            val scope = CoroutineScope(SupervisorJob() + deps.workContext)
            val engine = PaymentEngine(
                paymentIntentId = paymentIntentId,
                scope = scope,
                confirmStep = confirmStep,
                watchStep = watchStep,
                intentSource = intentSource,
                errorMapper = errorMapper,
                wallClock = deps.wallClock,
                logger = logger,
            )
            return PaymentSession(paymentIntentId, engine, scope, deps.orphanLifetimeMillis, logger, idempotency)
        }

        /**
         * The one [TokenManager] for [configuration], built on first use. Called under [lock].
         *
         * ### Why not one per session
         *
         * UQPAY permits **one active access token per merchant**: minting a new one
         * invalidates the previous one. A manager per session meant every launch asked the
         * merchant's `fetchToken()` for a token of its own, and two intents alive at once —
         * the customer's second order while the first sheet reconciles, or split-screen —
         * held two managers that took turns invalidating each other's token. Each `401`
         * then forced the other side to mint again: a ping-pong that ends in an
         * authentication failure for a payment that had every right to succeed. One manager
         * per configuration makes the SDK hold **one** token, refresh it behind **one**
         * mutex, and ask the host for a new one only when that one is about to expire.
         *
         * Keyed by the configuration **instance** rather than by the process: a second
         * [UQPay.initialize] replaces the configuration, and a token minted for the old
         * provider must not be presented on behalf of the new one. The `workContext` is the
         * first builder's, which in production is always [Dispatchers.IO]; a test that needs
         * its own dispatcher clears the shared state first, as every session test does.
         */
        private fun sharedTokenManagerLocked(
            configuration: UQPayConfiguration,
            workContext: CoroutineContext,
        ): TokenManager {
            sharedTokenManager
                ?.takeIf { sharedTokenManagerFor === configuration }
                ?.let { return it }
            return TokenManager(configuration.tokenProvider, workContext).also {
                sharedTokenManager = it
                sharedTokenManagerFor = configuration
            }
        }

        /**
         * The process's one [ConfirmIdempotency], built on first use. Called under [lock].
         *
         * The device suppliers are lambdas the registry calls **only when minting**, so a
         * pin hit never pays for display metrics or a `NetworkInterface` walk. The wall
         * clock is the real one: the 24h TTL is a persisted, reboot-surviving deadline and
         * must not be the engine's monotonic [Clock] (WU-2.8's two-clocks rule).
         */
        private fun sharedIdempotencyLocked(logger: UQPayLogger): ConfirmIdempotency {
            sharedIdempotency?.let { return it }
            val appContext = UQPay.requireAppContext()
            return ConfirmIdempotency(
                store = NoBackupConfirmAttemptStore(appContext, logger),
                browserInfo = { DeviceInfo.currentDevice(appContext) },
                ipAddress = { DeviceInfo.currentIpAddress() },
                now = System::currentTimeMillis,
            ).also { sharedIdempotency = it }
        }
    }
}
