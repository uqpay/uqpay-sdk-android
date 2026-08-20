package com.uqpay.sdk.engine

import androidx.annotation.VisibleForTesting
import com.uqpay.sdk.network.UQPayApiException
import com.uqpay.sdk.store.ConfirmAttempt
import com.uqpay.sdk.store.ConfirmAttemptStore
import com.uqpay.sdk.store.PersistedConfirmAttempt
import com.uqpay.sdk.store.toAttempt
import com.uqpay.sdk.store.toPersisted
import kotlin.coroutines.cancellation.CancellationException

/**
 * Tracks the confirm attempts whose outcome is not yet known. This is the SDK's
 * double-charge guard.
 *
 * ### The three ways a confirm can end
 *
 * A confirm POST ends in exactly one of three states, and they need different handling:
 *
 * 1. **The gateway answered definitively** — approved, declined, rejected. The attempt is
 *    over; the next tap is a new payment and gets a new key.
 * 2. **The gateway never answered, or answered unreadably** — a dropped connection, a
 *    timeout, a 5xx, a 2xx whose body would not parse. **The payment may well have been
 *    processed.** The next send *must* reuse the same `x-idempotency-key` and the same
 *    bytes, or the customer can be charged twice.
 * 3. **The customer changed their details** — that is a different payment and must get a
 *    fresh key, or the gateway will reject the reused key against a changed body.
 *
 * State (2) is the reason this class exists. The registry's job is to answer one question
 * — *"is there an unresolved attempt for this exact payload, and if so what key and what
 * frozen body values did it use?"* — and to keep answering it correctly across screens,
 * across configuration changes, and across process death.
 *
 * ### Why the registry is static
 *
 * [pending] is held in the companion object, keyed by payload digest. The unresolved-attempt
 * invariant belongs to the **payment**, not to whichever screen, ViewModel or engine
 * instance happened to send it. A customer who backs out of the payment screen mid-confirm
 * and re-enters it must replay the pinned attempt — a fresh registry per screen would mint a
 * fresh key against a payment that may already be authorising, which is precisely the
 * double charge this SDK exists to prevent. iOS reached the same conclusion for the same
 * reason (`ConfirmRequestSupport.swift`).
 *
 * Entries hold nothing card-derived and are removed the moment an outcome is known, so the
 * registry stays small.
 *
 * ### Why pins are also persisted
 *
 * Process death mid-confirm is the same invariant on a longer timescale: the relaunched app
 * paying again with the same details must replay the same key. The in-memory map stays the
 * session's source of truth; the [ConfirmAttemptStore] is consulted only on a miss and
 * written through on pin and on resolve. On Android this matters *more* than on iOS, not
 * less — Android has no equivalent of `beginBackgroundTask`, so the persisted pin is the
 * only safety net when the OS kills the process mid-confirm.
 *
 * ### Threading
 *
 * iOS got mutual exclusion for free from `@MainActor`. Our engine runs on IO dispatchers
 * and a double-tap is a genuine race: two coroutines can reach [attempt] for the same
 * digest at the same instant, and without a lock both would mint, both would send, and the
 * customer would be charged twice. Every read-modify-write of [pending] **and of the
 * store** therefore happens under [lock] — including the store round trips, which are a
 * read-modify-write of a shared list and would otherwise lose records under concurrency.
 *
 * Holding a lock across disk IO is deliberate. Writing the pin file — a few hundred bytes,
 * an `fsync` and a rename — is a few milliseconds and the caller is already on an IO
 * dispatcher; correctness of the pin is worth far more than the contention. The lock is never held across a suspension point,
 * because nothing here suspends.
 *
 * ### Preconditions the caller owes
 *
 * **The payload digest must include the payment intent id.** A restored pin brings back the
 * record's *own* [ConfirmAttempt.paymentIntentId] (see [restoredAttempt]), which is what
 * makes relaunch recovery safe — but it also means that if two *different* intents could
 * ever produce the same digest, the second payment would be confirmed against the first
 * intent's id under the first intent's key. The digest is the identity; it must be
 * complete. See `ConfirmPayloadIdentity` and the fixed-arity field-list rule from WU-2.1.
 *
 * @param store where pins outlive the process. Injectable so the disaster cases — a store
 *   that returns nothing, a store that throws — are reachable in tests.
 * @param browserInfo captures the device fingerprint to freeze, called **only when a fresh
 *   key is minted**. A supplier rather than a value because capture is not free (it reads
 *   display metrics and `Settings.Secure`) and a pin hit must not pay for it.
 * @param ipAddress captures the device IP to freeze; same contract as [browserInfo].
 * @param now the **wall clock**, epoch milliseconds, defaulting to
 *   `System.currentTimeMillis`. Deliberately *not* [Clock]: [Clock.elapsedRealtime] resets
 *   to zero at boot and is therefore meaningless the moment it is written to disk, and the
 *   TTL enforced here is a persisted, reboot-surviving deadline. The two clocks are
 *   separate types so they cannot be confused at a call site. Injected as a lambda so tests
 *   can move it freely, including backwards — which a user-settable wall clock genuinely
 *   can do, and which [MAX_PERSISTED_ATTEMPTS] exists to backstop.
 */
internal class ConfirmIdempotency(
    private val store: ConfirmAttemptStore,
    private val browserInfo: () -> BrowserInfo,
    private val ipAddress: () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * The attempt to send for this payload.
     *
     * While an attempt for this payload is unresolved the same key and the same frozen
     * device values come back — from memory within a session, from the store after process
     * death. A payload with no live pin gets a fresh key, current device values and a
     * write-through to the store.
     *
     * Order matters: memory first (the session's source of truth), then the store (only on
     * a miss), then mint. Consulting the store first would let a stale record shadow a pin
     * that is live *right now* in this process.
     *
     * [paymentIntentId] is used **only when minting**. On a restore the record's own intent
     * id wins — see [restoredAttempt] for why that is not an oversight.
     *
     * Never returns null and never reports failure: a payment must always have a key to
     * send under. A storage failure degrades to an in-memory-only pin.
     */
    fun attempt(payloadDigest: String, paymentIntentId: String): ConfirmAttempt =
        synchronized(lock) {
            pending[payloadDigest]?.let { return@synchronized it }

            restoredAttempt(payloadDigest)?.let { restored ->
                pending[payloadDigest] = restored
                return@synchronized restored
            }

            // Minting reads the device *now*; from here on these values are frozen with the
            // key and replayed verbatim. `mint` is WU-2.2's, so the key format — a
            // lowercase UUID, which UQPAY requires and rejects otherwise — is defined in
            // exactly one place and cannot be re-cased downstream.
            val minted = ConfirmAttempt.mint(
                payloadDigest = payloadDigest,
                paymentIntentId = paymentIntentId,
                browserInfo = browserInfo(),
                ipAddress = ipAddress(),
                createdAt = now(),
            )
            pending[payloadDigest] = minted
            persistPin(minted)
            minted
        }

    /**
     * Ends **this** attempt, if it is still the pinned one.
     *
     * The key comparison is the entire stale-task protection, and it is load-bearing enough
     * to be worth spelling out: a superseded attempt can finish late — its request was
     * already in flight when the customer's second, valid tap minted a newer attempt for
     * the same payload. If the late finisher un-pinned the registry, the *newer* attempt
     * would lose its replay protection while still in flight, and the tap after that would
     * mint a third key against a payment that may already be authorising. Comparing keys
     * means a stale task can only ever clear its own pin.
     *
     * The store rewrite drops this record and, as a side effect of [liveRecords], any record
     * that has aged out. It can only shrink the stored set, so no cap is applied here.
     */
    fun resolve(attempt: ConfirmAttempt) {
        synchronized(lock) {
            if (pending[attempt.payloadDigest]?.key != attempt.key) return@synchronized
            pending.remove(attempt.payloadDigest)
            saveQuietly(liveRecords().filterNot { it.identityDigest == attempt.payloadDigest })
        }
    }

    /**
     * Call when a send of [attempt] threw. Decides whether the pin survives.
     *
     * The classification is a **three-way** split, and collapsing it to two is a shipped
     * bug in both directions:
     *
     * 1. **Cancellation** — the customer walked away, or a newer tap superseded this send.
     *    Return, leaving the pin exactly as it was. A cancelled task reports nothing and is
     *    explicitly *not* unknown-outcome handling's business; treating it as definitive
     *    would release a pin belonging to a request that may still be in flight.
     * 2. **Outcome unknown** ([UQPayApiException.isOutcomeUnknown]) — keep the pin, so the
     *    next send replays the same key with the same bytes. That property already matches
     *    iOS's `isOutcomeUnknown` exactly and is reused rather than re-derived, so the two
     *    SDKs cannot drift apart on the definition of "may already have been charged".
     * 3. **Definitive** — the gateway answered; the attempt is over. [resolve] it.
     *
     * Takes a [Throwable] rather than an [Exception] because the engine boundary (F4)
     * catches `Throwable`, and a caller that has to narrow a type before asking this
     * question is a caller that will one day narrow it wrongly.
     *
     * ### Anything unrecognised keeps the pin — a deliberate divergence from iOS
     *
     * iOS falls through to `resolve` for an error type it does not recognise. We do not.
     * An error we cannot classify is by definition **not** a definitive answer from the
     * gateway: an `IllegalStateException` thrown from somewhere inside the send path may
     * have been thrown after the bytes went out. Keeping the pin costs at most one replay
     * that the gateway either honours (correct) or rejects as a changed body (after which
     * the pin resolves and the next tap mints fresh). Releasing it can cost a second
     * charge. The asymmetry is not close, and the pin is bounded anyway by the 24h TTL and
     * the record cap.
     *
     * A useful corollary: because unrecognised errors keep the pin, a cancellation that
     * arrives wrapped in some other exception type still leaves the pin untouched. Failing
     * to *detect* cancellation can no longer cost a customer money here.
     */
    fun handle(error: Throwable, attempt: ConfirmAttempt) {
        if (error is CancellationException) return
        if (error is UQPayApiException.Cancelled) return
        if (error !is UQPayApiException) return
        if (error.isOutcomeUnknown) return
        resolve(attempt)
    }

    // ---- Persistence ---------------------------------------------------------------

    /**
     * A previous launch's pin for this payload, if it is still inside the replay window.
     *
     * Everything comes back from the record and **nothing is re-read from current state**:
     *
     * - The **key**, byte-identical. The gateway matches keys as opaque strings.
     * - The **frozen `browserInfo` and `ipAddress`**. The relaunched process's own values
     *   will differ — a different IP after leaving Wi-Fi, different screen dimensions after
     *   a rotation — and a replayed key with a changed body is rejected, not honoured.
     * - The record's **own `paymentIntentId`**. Whatever the current configuration or
     *   session holds after a relaunch is whatever the *new* launch put there; trusting it
     *   would let a pin from payment A be replayed against payment B.
     */
    private fun restoredAttempt(payloadDigest: String): ConfirmAttempt? =
        liveRecords().firstOrNull { it.identityDigest == payloadDigest }?.toAttempt()

    /**
     * Writes a freshly minted pin through to the store.
     *
     * Replaces any record for the same payload (there should be none — this only runs on a
     * miss — but an unresolved duplicate would shadow the new pin forever), appends the new
     * record **last** so that list order stays insertion order, and enforces the cap by
     * dropping from the **front**.
     *
     * Eviction is by list position, not by timestamp, and that is the point:
     * [MAX_PERSISTED_ATTEMPTS] exists as the backstop for a wall clock the user can move.
     * A timestamp-based eviction would be defeated by exactly the same clock change that
     * defeats the TTL. `takeLast` on an insertion-ordered list needs no clock at all.
     */
    private fun persistPin(attempt: ConfirmAttempt) {
        val others = liveRecords().filterNot { it.identityDigest == attempt.payloadDigest }
        saveQuietly((others + attempt.toPersisted()).takeLast(MAX_PERSISTED_ATTEMPTS))
    }

    /**
     * The stored records still inside the replay window.
     *
     * **Expiry is enforced here, on read.** Not on launch, not on a maintenance pass: there
     * is no hook that can be trusted to have run first, and on iOS the equivalent store
     * (Keychain) even survives app reinstall. A pin older than the gateway's own 24h
     * idempotency window cannot protect anyone — replaying its key would simply be a new
     * payment under a stale identifier.
     *
     * A record whose `createdAt` is in the *future* — which a wall clock moved backwards
     * produces — has a negative age and is therefore kept. That is intentional: the record
     * cap, not the TTL, is the defence against a tampered clock, and dropping pins because
     * the clock jumped would throw away live double-charge protection at exactly the moment
     * it is hardest to reason about.
     */
    private fun liveRecords(): List<PersistedConfirmAttempt> {
        val instant = now()
        return loadQuietly().filter { instant - it.createdAt <= TIME_TO_LIVE_MILLIS }
    }

    /**
     * [ConfirmAttemptStore.load], with a belt to go with the interface's braces.
     *
     * The contract says implementations never throw, and the production one does not. This
     * catch is for the implementation that gets written next year, or the injected one a
     * merchant-adjacent test harness supplies: **a broken store must never crash a
     * payment.** The degradation is precise — recovery after process death is lost, the
     * in-memory pin still protects this session.
     *
     * `Exception`, not `Throwable`: an `OutOfMemoryError` is not something to swallow on
     * the confirm path, and the store layer does not eat `Error` either. A
     * `CancellationException` is rethrown untouched rather than being mistaken for a
     * storage failure — swallowing one breaks structured concurrency for the whole engine.
     */
    private fun loadQuietly(): List<PersistedConfirmAttempt> =
        try {
            store.load()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            emptyList()
        }

    /**
     * [ConfirmAttemptStore.save], degrading exactly as [loadQuietly] does.
     *
     * Nothing is logged from here. The store owns its own logging and is the only layer
     * that knows *why* a write failed; a second log line from here would add no information
     * and would tempt a future change into passing the throwable — whose message can quote
     * the blob, which contains a live idempotency key.
     */
    private fun saveQuietly(records: List<PersistedConfirmAttempt>) {
        try {
            store.save(records)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Never fatal. See [ConfirmAttemptStore.save].
        }
    }

    // ---- Test hooks ------------------------------------------------------------------

    /**
     * Test hook: clears the static registry **and** this instance's store.
     *
     * Static state outlives a test method, and tests that drive real screens pin real
     * attempts; without a purge they leak into each other and produce a green suite that
     * proves nothing. An instance method rather than a static one because our store is
     * injected — iOS could clear its production Keychain from a static hook, we would have
     * to guess which store the caller meant.
     */
    @VisibleForTesting
    fun clearAllForTest() {
        synchronized(lock) {
            pending.clear()
            saveQuietly(emptyList())
        }
    }

    internal companion object {

        /**
         * Matches the gateway's replay window: a reused key is honoured for 24 hours, after
         * which a pin protects nobody.
         *
         * **Milliseconds, because [ConfirmAttempt.createdAt] is epoch milliseconds.** iOS
         * stores epoch *seconds* and uses `86_400`. Carrying that constant across would not
         * crash and would not fail an obvious test — it would silently make the TTL 1000×
         * too long, leaving dead pins live for 27 years.
         */
        const val TIME_TO_LIVE_MILLIS: Long = 86_400_000L

        /**
         * The store never holds more than this many records; the oldest is evicted first.
         *
         * This is the backstop for a wall clock the user controls. Everything the TTL does
         * depends on `System.currentTimeMillis()` being honest, and it is not: it is
         * settable in Settings and moves when the network time source corrects it. The cap
         * needs no clock, so it holds regardless. Sixteen simultaneously unresolved
         * payments is already implausible.
         */
        const val MAX_PERSISTED_ATTEMPTS: Int = 16

        /**
         * Guards [pending] *and* every store round trip. See the class KDoc on threading.
         *
         * One lock for both, deliberately: the in-memory map and the persisted list are one
         * piece of state in two places, and a lock per half would allow the interleaving
         * where two mints each read the store, each append their own record, and each write
         * back — losing one pin entirely.
         */
        private val lock = Any()

        /**
         * The unresolved attempts, keyed by payload digest. Static; see the class KDoc.
         *
         * Never iterate or log this. Values contain live idempotency keys, which are
         * credentials for replaying a payment.
         */
        private val pending = mutableMapOf<String, ConfirmAttempt>()

        /**
         * Test hook: clears **only** the in-memory registry, leaving stores untouched.
         *
         * This is what process death looks like to the registry — the map is gone, the blob
         * on disk is not — and it is how the single most important test in this module is
         * written.
         */
        @VisibleForTesting
        fun clearInMemoryOnlyForTest() {
            synchronized(lock) { pending.clear() }
        }
    }
}
