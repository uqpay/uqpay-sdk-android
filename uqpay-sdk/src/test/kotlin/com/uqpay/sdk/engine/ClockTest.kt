package com.uqpay.sdk.engine

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production [Clock] has almost no behaviour, which is the point — but the little it
 * has is load-bearing for every timing rule above it.
 *
 * [ElapsedRealtimeClock.elapsedRealtime] is not exercised here: it delegates straight to
 * `android.os.SystemClock`, which a JVM unit test stubs out to a constant. Asserting on a
 * stub would test the stub. What *is* worth pinning is the contract the engine relies on:
 * a non-positive sleep is free, a positive sleep suspends for exactly what was asked, and
 * a sleep is cancellable — the last of which is the entire mechanism behind
 * [IntentPoller.nudge].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClockTest {

    @Test
    fun `a zero sleep returns immediately`() = runTest {
        val before = testScheduler.currentTime
        ElapsedRealtimeClock.sleep(0L)
        assertEquals(before, testScheduler.currentTime)
    }

    /**
     * A negative interval reaches here only from a miscomputed budget. It must be free
     * rather than throw: a poll loop must not die because somebody subtracted in the wrong
     * order.
     */
    @Test
    fun `a negative sleep returns immediately`() = runTest {
        val before = testScheduler.currentTime
        ElapsedRealtimeClock.sleep(-5_000L)
        assertEquals(before, testScheduler.currentTime)
    }

    @Test
    fun `a positive sleep suspends for exactly the requested interval`() = runTest {
        val before = testScheduler.currentTime
        ElapsedRealtimeClock.sleep(2_000L)
        assertEquals(2_000L, testScheduler.currentTime - before)
    }

    /**
     * The nudge contract. Cancelling the coroutine that is sleeping must end the sleep at
     * once — if it did not, a customer returning from their banking app would still wait
     * out the remaining interval.
     */
    @Test
    fun `a sleep in progress is cancellable`() = runTest {
        var finished = false
        val sleeping = launch(start = CoroutineStart.UNDISPATCHED) {
            ElapsedRealtimeClock.sleep(60_000L)
            finished = true
        }

        sleeping.cancel()
        runCurrent()

        assertTrue("the sleep ended on cancellation", sleeping.isCancelled)
        assertTrue("and did not run on to completion", !finished)
        assertEquals("no virtual time was consumed", 0L, testScheduler.currentTime)
    }
}
