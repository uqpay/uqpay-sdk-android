package com.uqpay.sdk.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one-confirm rule, disaster first.
 *
 * Every case here is a way a customer ends up with two live QR codes for one payment, which
 * the live sandbox proved on 2026-08-18 is a thing the gateway will happily do: confirming
 * one intent twice with GrabPay under two fresh idempotency keys is accepted both times and
 * issues two different codes, the first of which is then orphaned but still payable.
 *
 * No card value, key or customer field appears in this file.
 */
class WalletConfirmLatchTest {

    private val latch = WalletConfirmLatch()

    @Before
    fun setUp() = WalletConfirmLatch.clearForTest()

    @After
    fun tearDown() = WalletConfirmLatch.clearForTest()

    // ---- one confirm, ever -----------------------------------------------------------

    @Test
    fun `the first claim is granted and the second is not`() {
        assertEquals(WalletConfirmClaim.Granted, latch.claim(INTENT, GRABPAY))
        assertEquals(WalletConfirmClaim.AlreadyInFlight, latch.claim(INTENT, GRABPAY))
    }

    /**
     * Re-entry is the money case: a new screen, a new ViewModel, a new engine — and the same
     * payment. It must be served the QR that already exists rather than being granted a
     * second confirm.
     */
    @Test
    fun `re-entry after a QR was issued re-serves that QR and never grants a second confirm`() {
        latch.claim(INTENT, GRABPAY)
        latch.recordIssued(INTENT, GRABPAY, QR)

        // A brand-new instance: the invariant belongs to the payment, not to an object.
        val reEntry = WalletConfirmLatch().claim(INTENT, GRABPAY)

        assertTrue(reEntry is WalletConfirmClaim.AlreadyIssued)
        assertEquals(QR, (reEntry as WalletConfirmClaim.AlreadyIssued).qr)
    }

    @Test
    fun `a hundred re-entries still issue exactly one confirm`() {
        var granted = 0
        repeat(100) {
            if (WalletConfirmLatch().claim(INTENT, GRABPAY) == WalletConfirmClaim.Granted) {
                granted++
                latch.recordIssued(INTENT, GRABPAY, QR)
            }
        }
        assertEquals(1, granted)
    }

    // ---- keyed by intent AND method ---------------------------------------------------

    /** An Alipay screen must never be handed the QR a GrabPay confirm issued. */
    @Test
    fun `a second wallet on the same intent gets its own claim, never GrabPay's QR`() {
        latch.claim(INTENT, GRABPAY)
        latch.recordIssued(INTENT, GRABPAY, QR)

        assertEquals(WalletConfirmClaim.Granted, latch.claim(INTENT, ALIPAY))
        assertNull(latch.issuedQr(INTENT, ALIPAY))
        assertFalse(latch.hasIssued(INTENT, ALIPAY))
        // …and GrabPay's is untouched.
        assertEquals(QR, latch.issuedQr(INTENT, GRABPAY))
    }

    @Test
    fun `the same wallet on a different intent is a different latch`() {
        latch.claim(INTENT, GRABPAY)
        latch.recordIssued(INTENT, GRABPAY, QR)

        assertEquals(WalletConfirmClaim.Granted, latch.claim(OTHER_INTENT, GRABPAY))
        assertNull(latch.issuedQr(OTHER_INTENT, GRABPAY))
    }

    @Test
    fun `finishing one wallet does not free another on the same intent`() {
        latch.claim(INTENT, GRABPAY)
        latch.recordIssued(INTENT, GRABPAY, QR)
        latch.claim(INTENT, ALIPAY)
        latch.recordIssued(INTENT, ALIPAY, IssuedQr(url = "https://example.invalid/alipay.png"))

        latch.attemptFinished(INTENT, ALIPAY)

        assertNull(latch.issuedQr(INTENT, ALIPAY))
        assertEquals(QR, latch.issuedQr(INTENT, GRABPAY))
    }

    /**
     * The keys must not be able to collide by concatenation. `a|b` and `ab` would be the same
     * string under a missing separator, and two different payments would share one latch.
     */
    @Test
    fun `keys built from different splits do not collide`() {
        latch.claim("PI_a", "b")
        assertEquals(WalletConfirmClaim.Granted, latch.claim("PI_ab", ""))
        assertEquals(WalletConfirmClaim.Granted, latch.claim("PI_", "ab"))
    }

    // ---- attemptFinished: the never-on-timeout rule ------------------------------------

    @Test
    fun `attemptFinished frees the latch so a legitimate fresh confirm is possible`() {
        latch.claim(INTENT, GRABPAY)
        latch.recordIssued(INTENT, GRABPAY, QR)

        latch.attemptFinished(INTENT, GRABPAY)

        assertEquals(WalletConfirmClaim.Granted, latch.claim(INTENT, GRABPAY))
    }

    /**
     * The rule iOS writes in capitals, as a test.
     *
     * A poll that ran out of budget, or one that kept erroring, tells us **nothing** about
     * the attempt. Its QR is still on the customer's screen and still scannable. This test
     * asserts what the *caller* must do — it is the contract `PaymentViewModel.keepWalletLatch`
     * implements by excluding `PENDING` from the outcomes that free the latch.
     */
    @Test
    fun `a poll timeout does not free the latch - only an explicit finish does`() {
        latch.claim(INTENT, GRABPAY)
        latch.recordIssued(INTENT, GRABPAY, QR)

        // Everything a timed-out or erroring watcher is allowed to do: nothing.
        assertTrue(latch.hasIssued(INTENT, GRABPAY))
        assertEquals(WalletConfirmClaim.AlreadyIssued(QR), latch.claim(INTENT, GRABPAY))
        assertEquals(1, latch.size())
    }

    @Test
    fun `finishing a wallet that was never claimed is harmless`() {
        latch.attemptFinished(INTENT, GRABPAY)
        assertEquals(0, latch.size())
        assertEquals(WalletConfirmClaim.Granted, latch.claim(INTENT, GRABPAY))
    }

    // ---- recovery ----------------------------------------------------------------------

    /**
     * After process death the registry is empty but the gateway still holds the QR — verified
     * live: a `GET` on an intent in `REQUIRES_CUSTOMER_ACTION` returns the issued
     * `display_qr_code`. Recording that without a prior claim must be allowed, or the only
     * recovery there is would be refused.
     */
    @Test
    fun `a QR recovered from an intent read can be recorded without a prior claim`() {
        latch.recordIssued(INTENT, GRABPAY, QR)

        assertEquals(WalletConfirmClaim.AlreadyIssued(QR), latch.claim(INTENT, GRABPAY))
    }

    @Test
    fun `re-recording the same QR is idempotent`() {
        latch.claim(INTENT, GRABPAY)
        latch.recordIssued(INTENT, GRABPAY, QR)
        latch.recordIssued(INTENT, GRABPAY, QR)

        assertEquals(1, latch.size())
    }

    @Test
    fun `a confirm that issued no QR at all is remembered as empty, not as absent`() {
        latch.claim(INTENT, GRABPAY)
        latch.recordIssued(INTENT, GRABPAY, IssuedQr(url = null))

        val claim = latch.claim(INTENT, GRABPAY) as WalletConfirmClaim.AlreadyIssued
        assertTrue(claim.qr.isEmpty)
        assertTrue(latch.hasIssued(INTENT, GRABPAY))
    }

    @Test
    fun `a QR with only a raw payload is not empty`() {
        assertFalse(IssuedQr(url = null, rawPayload = "00020101…").isEmpty)
        assertTrue(IssuedQr(url = "  ", rawPayload = "  ").isEmpty)
    }

    // ---- concurrency -------------------------------------------------------------------

    /**
     * The reason `claim` is a single `putIfAbsent` and not a read followed by a write.
     *
     * Engine work runs on IO dispatchers and a confirm can be requested from a UI callback
     * while a watcher settles on another thread. If two threads could both be granted, the
     * customer gets two live QR codes — the exact outcome this class exists to prevent.
     */
    @Test
    fun `sixty-four threads racing for one wallet produce exactly one grant`() {
        val threads = 64
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val granted = AtomicInteger()
        val issued = AtomicInteger()

        repeat(threads) {
            pool.execute {
                start.await()
                when (latch.claim(INTENT, GRABPAY)) {
                    WalletConfirmClaim.Granted -> granted.incrementAndGet()
                    is WalletConfirmClaim.AlreadyIssued -> issued.incrementAndGet()
                    WalletConfirmClaim.AlreadyInFlight -> Unit
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("threads did not finish", done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals(1, granted.get())
        assertEquals(0, issued.get())
        assertEquals(1, latch.size())
    }

    /**
     * The same race with two wallets on one intent: exactly one grant *each*, and neither
     * wallet's entry lost to the other's write.
     */
    @Test
    fun `two wallets raced concurrently each get exactly one grant`() {
        val perWallet = 32
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        val done = CountDownLatch(perWallet * 2)
        val grants = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

        listOf(GRABPAY, ALIPAY).forEach { wallet ->
            grants[wallet] = AtomicInteger()
            repeat(perWallet) {
                pool.execute {
                    start.await()
                    if (latch.claim(INTENT, wallet) == WalletConfirmClaim.Granted) {
                        grants.getValue(wallet).incrementAndGet()
                    }
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue("threads did not finish", done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals(1, grants.getValue(GRABPAY).get())
        assertEquals(1, grants.getValue(ALIPAY).get())
        assertEquals(2, latch.size())
    }

    /**
     * Mixed traffic — claims, records and finishes on many threads at once — must never
     * throw and must never leave the registry holding a half-written entry.
     */
    @Test
    fun `mixed concurrent traffic never throws and never corrupts an entry`() {
        val pool = Executors.newFixedThreadPool(12)
        val done = CountDownLatch(300)
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

        repeat(300) { i ->
            pool.execute {
                runCatching {
                    val wallet = if (i % 2 == 0) GRABPAY else ALIPAY
                    when (i % 3) {
                        0 -> latch.claim(INTENT, wallet)
                        1 -> latch.recordIssued(INTENT, wallet, QR)
                        else -> latch.attemptFinished(INTENT, wallet)
                    }
                    // Whatever is there must be a whole value, never a partial one.
                    latch.issuedQr(INTENT, wallet)?.let { assertSame(QR.url, it.url) }
                }.onFailure { failures += it }
                done.countDown()
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertTrue("threw: ${failures.firstOrNull()}", failures.isEmpty())
        assertTrue(latch.size() <= 2)
    }

    // ---- registry hygiene --------------------------------------------------------------

    @Test
    fun `the registry is shared across instances, because the invariant belongs to the payment`() {
        WalletConfirmLatch().claim(INTENT, GRABPAY)
        assertEquals(WalletConfirmClaim.AlreadyInFlight, WalletConfirmLatch().claim(INTENT, GRABPAY))
    }

    @Test
    fun `finished attempts leave nothing behind`() {
        repeat(20) { i ->
            latch.claim("PI_$i", GRABPAY)
            latch.recordIssued("PI_$i", GRABPAY, QR)
            latch.attemptFinished("PI_$i", GRABPAY)
        }
        assertEquals(0, latch.size())
    }

    private companion object {
        const val INTENT = "PI_latch_test"
        const val OTHER_INTENT = "PI_latch_test_other"
        const val GRABPAY = "grabpay"
        const val ALIPAY = "alipaycn"

        /** Shaped like the live one, with the payment payload replaced by a placeholder. */
        val QR = IssuedQr(
            url = "https://api-sandbox.invalid/api/v2/payment/qr?data=PLACEHOLDER",
            rawPayload = "PLACEHOLDER",
            expiresAt = "2026-08-18T17:14:19.855+08:00",
        )
    }
}
