package com.uqpay.sdk.engine

import androidx.annotation.VisibleForTesting
import com.uqpay.sdk.UQPay
import com.uqpay.sdk.network.DefaultUQPayNetworkClient
import com.uqpay.sdk.network.ErrorMapper
import com.uqpay.sdk.network.TokenManager
import com.uqpay.sdk.network.UQPayApiClient
import com.uqpay.sdk.network.UQPayLogger
import com.uqpay.sdk.network.UQPayNetworkClient
import com.uqpay.sdk.store.PreferencesConfirmAttemptStore
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

    /** Where the payment is; the same flow as [PaymentEngine.state], for convenience. */
    val state: StateFlow<EngineState>
        get() = engine.state

    /**
     * Whether [startIfNeeded] has already loaded this session's engine. A recreated Activity
     * finding this `true` is a re-attach: it renders [state] and calls nothing.
     */
    val hasStarted: Boolean
        get() = started.get()

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
     * @return `true` if this call started the load, `false` if it had already been started.
     */
    fun startIfNeeded(presentation: Presentation = Presentation.MethodList): Boolean {
        if (!started.compareAndSet(false, true)) return false
        engine.load(presentation)
        return true
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
     * when [orphanLifetimeMillis] elapses, whichever is first. Called by the registry only.
     */
    private fun retire(onRetired: () -> Unit) {
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
         * Releasing an intent that has no session is a no-op.
         */
        fun release(paymentIntentId: String) {
            val session = synchronized(lock) { sessions.remove(paymentIntentId) } ?: return
            synchronized(lock) { orphans += session }
            session.retire(onRetired = { synchronized(lock) { orphans -= session } })
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
            // F5: the one place `UQPayLogger.Logcat` is ever constructed. Merchants opt in
            // through the configuration; everything else in the process gets the discarding
            // logger, and no code path chooses a logger for itself.
            val logger = deps.logger
                ?: if (configuration.loggingEnabled) UQPayLogger.Logcat() else UQPayLogger.Noop

            // Transport and authentication.
            val networkClient = deps.networkClient
                ?: DefaultUQPayNetworkClient(logger = logger, workContext = deps.workContext)
            val tokenManager = TokenManager(configuration.tokenProvider, deps.workContext)
            val apiClient = UQPayApiClient(configuration, networkClient, tokenManager, logger)
            val errorMapper = ErrorMapper(configuration.environment)

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
                store = PreferencesConfirmAttemptStore(appContext, logger),
                browserInfo = { DeviceInfo.currentDevice(appContext) },
                ipAddress = { DeviceInfo.currentIpAddress() },
                now = System::currentTimeMillis,
            ).also { sharedIdempotency = it }
        }
    }
}
