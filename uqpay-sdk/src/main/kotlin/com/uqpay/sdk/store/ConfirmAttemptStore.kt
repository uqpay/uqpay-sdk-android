package com.uqpay.sdk.store

/**
 * Where unresolved confirm attempts live between processes.
 *
 * ### Why this is an interface and not a class
 *
 * The registry that sits on top of this (`com.uqpay.sdk.engine.ConfirmIdempotency`) has
 * to keep working when storage does not, and "keeps working when storage does not" is a
 * behaviour, so it needs a test. The tests that matter are a store that returns garbage and
 * a store whose [save] throws — neither can be written against a concrete
 * `SharedPreferences`-backed class without corrupting a real file or mocking the framework.
 * iOS reached the same conclusion and declared a protocol for exactly this reason; the
 * abstraction exists to make the disaster cases reachable, not for the sake of layering.
 *
 * ### The contract implementations owe
 *
 * Both methods are **total**. Neither may throw, for any input, in any state:
 *
 * - [load] answers "what pins might still be live?" A store that cannot answer says
 *   `emptyList()`. The cost of a wrong empty answer is a fresh idempotency key on the next
 *   tap; the cost of a throw is an exception on the confirm path, which is worse in every
 *   scenario.
 * - [save] is best-effort. The registry's in-memory map is the session's source of truth
 *   and is already updated by the time [save] is called; persistence only buys recovery
 *   after process death. Losing that is a degradation, not a failure, and it must never
 *   become one.
 *
 * ### Order is part of the contract
 *
 * [load] returns records in the order [save] received them. The registry caps the store at
 * a fixed number of records and evicts the **oldest first** — with a user-settable wall
 * clock, list order is a more trustworthy notion of "oldest" than any timestamp in the
 * records themselves, which is precisely why the cap exists as a backstop to the TTL.
 * An implementation that reorders (a `Set`, a map, a sort "for determinism") silently
 * changes which pin gets thrown away.
 *
 * ### What may be stored
 *
 * Records are [PersistedConfirmAttempt]s and nothing else: a digest, an opaque key, coarse
 * device metrics and a timestamp. Nothing card-derived is representable — see
 * [ConfirmAttempt] for the long form of that argument — which is what allows the production
 * implementation to use plain app-private storage.
 *
 * Implementations may be called from any thread.
 */
internal interface ConfirmAttemptStore {

    /**
     * Every persisted attempt, oldest first, or an empty list when there are none and when
     * there are some but they cannot be read. **Never throws.**
     */
    fun load(): List<PersistedConfirmAttempt>

    /**
     * Replaces the stored set wholesale, preserving [records] order.
     *
     * Whole-set replacement rather than per-record insert/delete: the registry already
     * holds the complete list it wants at rest, and a partial-update API would need a
     * second, weaker consistency story for the case where one of two writes lands.
     *
     * **Never throws**, including when the write does not happen.
     */
    fun save(records: List<PersistedConfirmAttempt>)
}
