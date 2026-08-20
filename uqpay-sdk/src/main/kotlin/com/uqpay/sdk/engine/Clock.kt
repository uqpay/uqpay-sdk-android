package com.uqpay.sdk.engine

import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * The engine's only source of time and of waiting.
 *
 * Two separate reasons this is an interface rather than a call to the platform:
 *
 * 1. **Testability.** Every timing rule in the engine — the confirm replay ladder, the
 *    poll interval, the nudge that replaces a pending wait — is a money-risk rule, and a
 *    rule that can only be exercised by waiting in real time is a rule that will not be
 *    exercised. With this seam a 150 × 2s poll budget runs to exhaustion in a test in
 *    microseconds and asserts on exactly what happened.
 * 2. **Correctness.** It fixes the definition of "now" at one place, so nothing in the
 *    engine can accidentally reach for the wall clock; see below.
 *
 * ### Why elapsed realtime and not wall-clock time
 *
 * [elapsedRealtime] is backed by [SystemClock.elapsedRealtime], which counts milliseconds
 * since boot **including deep sleep** and is monotonic. It cannot go backwards, and no
 * setting, no user, and no NTP correction can move it.
 *
 * `System.currentTimeMillis()` is none of those things. It is user-settable in Settings,
 * jumps when the network time source corrects it, and can move backwards. An engine that
 * measured a payment window with it would let a customer — or a badly synced device —
 * shorten or extend the SDK's own timing rules. For a payment SDK that is not a
 * theoretical concern: the idempotency registry already carries a record cap precisely
 * because a user-settable clock can defeat a wall-clock TTL.
 *
 * ### What this interface deliberately does NOT expose
 *
 * There is **no `currentTimeMillis()` here, and none may be added.** Elapsed realtime
 * resets to zero on every reboot, so it is meaningless once written to disk: a value
 * persisted before a reboot and compared after it produces nonsense. Anything that must
 * survive a reboot — most importantly the 24h TTL on a persisted confirm pin — needs a
 * wall clock, and needs it under its own abstraction so that the two can never be mixed
 * up at a call site. Keeping this interface elapsed-only means a caller cannot reach for
 * the wrong one by accident.
 *
 * The corollary for callers: a value read from [elapsedRealtime] may be persisted (see
 * [IntentPoller.startedAtElapsedRealtime]) only if the reader also handles the reboot
 * case, where the restored value is *ahead* of the current reading.
 */
internal interface Clock {

    /**
     * Milliseconds since boot, including time the device spent in deep sleep. Monotonic.
     *
     * The absolute value is meaningless; only differences between two readings from the
     * same boot mean anything.
     */
    fun elapsedRealtime(): Long

    /**
     * Suspends for [millis], or returns immediately if [millis] is zero or negative.
     *
     * Suspends — never blocks a thread. Cancellable: cancelling the calling coroutine
     * resumes this immediately with a `CancellationException`, which is what
     * [IntentPoller.nudge] relies on to turn a pending wait into an immediate re-read.
     */
    suspend fun sleep(millis: Long)
}

/**
 * The production [Clock]: the device's monotonic uptime and the coroutine delay.
 *
 * An `object`, not a class, because it holds nothing — there is no state a second
 * instance could disagree about.
 */
internal object ElapsedRealtimeClock : Clock {

    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    override suspend fun sleep(millis: Long) {
        if (millis <= 0L) return
        delay(millis)
    }
}
