package com.uqpay.sdk.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The documented schedule is 2s, 4s, 8s, each ±25% (api-contract §8 recommends
 * exponential backoff with random jitter).
 *
 * The jitter is not decoration: without it every device that hit the same outage retries
 * in lockstep and hammers the gateway the moment it recovers.
 */
class ExponentialBackoffTest {

    private val backoff = ExponentialBackoff(random = Random(20260814))

    @Test
    fun `each retry waits roughly twice as long as the last`() {
        val expectedCentres = mapOf(1 to 2_000L, 2 to 4_000L, 3 to 8_000L, 4 to 16_000L)

        expectedCentres.forEach { (attempt, centre) ->
            repeat(200) {
                val delay = backoff.delayMillis(attempt)
                assertTrue(
                    "attempt $attempt produced ${delay}ms, outside ±25% of ${centre}ms",
                    delay in (centre * 3 / 4)..(centre * 5 / 4),
                )
            }
        }
    }

    @Test
    fun `the delay is jittered rather than fixed`() {
        val samples = (1..200).map { backoff.delayMillis(1) }.toSet()
        assertTrue("every device would retry in lockstep", samples.size > 1)
    }

    @Test
    fun `the schedule is capped so a long outage cannot produce an absurd wait`() {
        // (attempt - 1) is clamped at 6, i.e. 2s << 6 = 128s ±25%.
        listOf(7, 8, 20, 100).forEach { attempt ->
            val delay = backoff.delayMillis(attempt)
            assertTrue("attempt $attempt produced ${delay}ms", delay in 96_000L..160_000L)
        }
    }

    @Test
    fun `the first retry is never instant`() {
        // A zero delay would turn a 5xx into a tight loop against a struggling gateway.
        repeat(200) { assertTrue(backoff.delayMillis(1) >= 1_500L) }
    }

    @Test
    fun `a custom base is honoured`() {
        val fast = ExponentialBackoff(baseMillis = 100L, random = Random(7))
        repeat(50) { assertTrue(fast.delayMillis(1) in 75L..125L) }
    }

    @Test
    fun `an out-of-range attempt number does not throw`() {
        assertEquals(true, backoff.delayMillis(0) in 1_500L..2_500L)
    }
}
