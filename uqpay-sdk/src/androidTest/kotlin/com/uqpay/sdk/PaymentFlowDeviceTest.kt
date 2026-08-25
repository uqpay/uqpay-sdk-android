package com.uqpay.sdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Parcel
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.uqpay.sdk.auth.UQPayAuthToken
import com.uqpay.sdk.auth.UQPayTokenProvider
import com.uqpay.sdk.engine.PaymentSession
import com.uqpay.sdk.error.ErrorCopy
import com.uqpay.sdk.error.UQPayError
import com.uqpay.sdk.error.UQPayErrorCode
import com.uqpay.sdk.launcher.UQPayPaymentContract
import com.uqpay.sdk.payment.PaymentResult
import com.uqpay.sdk.payment.PaymentSessionParams
import com.uqpay.sdk.payment.PaymentStatus
import com.uqpay.sdk.ui.UQPayPaymentActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch

/**
 * The payment flow's disaster cases on a **real device or emulator** — real framework
 * marshalling, real `ActivityResultRegistry` semantics, real Compose rendering — where the
 * Robolectric suite can only model them (AC §7.2 / §8.1).
 *
 * ### Deterministic by construction — no network, no credentials
 *
 * No test here talks to the gateway. The rotation test holds the engine mid-flight with a
 * token provider that blocks on a latch, so the session is genuinely *live* — a load in the
 * air on `Dispatchers.IO` — without a byte leaving the device. The early-exit tests never
 * build a session at all. This keeps the suite runnable on any emulator with no
 * `local.properties`, which is what lets CI run it.
 *
 * ### What still needs a human
 *
 * True process death (`adb shell am kill` mid-confirm and relaunch) and a full sandbox 3-DS
 * challenge require credentials and an outside-the-runner kill; they remain on the manual
 * release pass in `docs/testing.md`. What this suite proves on real Android: launch-argument
 * safety, result parcelling through the OS, the uninitialised-relaunch path (the
 * process-death-then-lazy-init shape), and rotation re-attachment with exactly-once delivery.
 */
@RunWith(AndroidJUnit4::class)
internal class PaymentFlowDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val contract = UQPayPaymentContract(ErrorCopy.from(context))

    /** Released in [tearDown]; a test that never blocks a provider never awaits it. */
    private val tokenGate = CountDownLatch(1)

    @Before
    fun setUp() {
        PaymentSession.clearAllForTest()
        UQPay.resetForTest()
    }

    @After
    fun tearDown() {
        // Order matters: cancel every session's scope first, then release any thread still
        // blocked inside the token provider — so the resumed coroutine lands on a cancelled
        // scope and dies at its next suspension instead of proceeding toward a socket.
        PaymentSession.clearAllForTest()
        tokenGate.countDown()
        UQPay.resetForTest()
    }

    // ---- launch-argument safety (G22/G23) — the parcels cross a real process boundary ----

    @Test
    fun garbledLaunchFinishesCanceledWithoutCrashing() {
        initializeSdk()
        val intent = Intent(context, UQPayPaymentActivity::class.java) // no extras at all

        val scenario = ActivityScenario.launchActivityForResult<UQPayPaymentActivity>(intent)
        val result = scenario.result

        assertEquals(Activity.RESULT_CANCELED, result.resultCode)
        assertNull(result.resultData)
        // What the merchant's callback would receive: CANCELLED, blank id, never a crash.
        val parsed = contract.parseResult(result.resultCode, result.resultData)
        assertEquals(PaymentStatus.CANCELLED, parsed.status)
        assertEquals("", parsed.paymentIntentId)
        scenario.close()
    }

    @Test
    fun blankIntentIdFailsWithInvalidConfiguration() {
        initializeSdk()
        val intent = launchIntent(PaymentSessionParams(paymentIntentId = "   "))

        val scenario = ActivityScenario.launchActivityForResult<UQPayPaymentActivity>(intent)
        val result = scenario.result

        assertEquals(Activity.RESULT_OK, result.resultCode)
        val parsed = contract.parseResult(result.resultCode, result.resultData)
        assertEquals(PaymentStatus.FAILED, parsed.status)
        assertEquals(UQPayErrorCode.INVALID_CONFIGURATION, parsed.error?.code)
        scenario.close()
    }

    /**
     * The process-death shape a merchant actually ships: the OS relaunches the payment
     * Activity from a redelivered result, but the host app initialises the SDK lazily and
     * has not done so yet. The contract is a FAILED result naming the fix — never a crash
     * of the merchant's app from inside our Activity.
     */
    @Test
    fun uninitializedSdkDeliversNotInitializedResultInsteadOfCrashing() {
        // Deliberately no initializeSdk() — setUp() has reset the statics.
        val intent = launchIntent(PaymentSessionParams(paymentIntentId = "pi_device_uninit"))

        val scenario = ActivityScenario.launchActivityForResult<UQPayPaymentActivity>(intent)
        val result = scenario.result

        assertEquals(Activity.RESULT_OK, result.resultCode)
        val parsed = contract.parseResult(result.resultCode, result.resultData)
        assertEquals(PaymentStatus.FAILED, parsed.status)
        assertEquals(UQPayErrorCode.NOT_INITIALIZED, parsed.error?.code)
        assertEquals("pi_device_uninit", parsed.paymentIntentId)
        scenario.close()
    }

    // ---- rotation mid-payment (AC §8.1) — the register's first device scenario ----------

    /**
     * Recreates the Activity while the engine is genuinely mid-flight (its load is blocked
     * inside the token provider) and asserts the rotation guarantee on real Android: the
     * recreated instance re-attaches to the **same** session and engine, the host count is
     * balanced, the load is not re-issued, and the one result is still delivered exactly
     * once when the payment ends.
     */
    @Test
    fun recreationMidPaymentReattachesSameSessionAndDeliversExactlyOnce() {
        initializeSdk(blockTokenFetch = true)
        val intentId = "pi_device_rotation"
        val intent = launchIntent(PaymentSessionParams(paymentIntentId = intentId))

        val scenario = ActivityScenario.launchActivityForResult<UQPayPaymentActivity>(intent)
        waitUntil("session attached and started") {
            PaymentSession.peek(intentId)?.let { it.hostCount == 1 && it.hasStarted } == true
        }
        val before = checkNotNull(PaymentSession.peek(intentId))

        scenario.recreate()

        val after = checkNotNull(PaymentSession.peek(intentId))
        assertSame("a recreated Activity must find the same session", before, after)
        assertEquals("attach/detach must stay balanced across recreation", 1, after.hostCount)
        assertTrue("the engine must not be loaded a second time", after.hasStarted)
        assertTrue("the session's scope must survive recreation", after.isActive)

        // End the payment the way the on-screen cancel does. The recreated instance owns
        // delivery now; exactly one result must come back, carrying the intent id.
        scenario.onActivity { it.viewModel.onCancelConfirmed() }
        val result = scenario.result

        assertEquals(Activity.RESULT_OK, result.resultCode)
        val parsed = contract.parseResult(result.resultCode, result.resultData)
        assertEquals(PaymentStatus.CANCELLED, parsed.status)
        assertEquals(intentId, parsed.paymentIntentId)
        assertNull(parsed.error)
        // Eviction happens in onDestroy, which the framework runs *after* finish() has
        // produced the result above — close() drives the Activity to DESTROYED, and only
        // then must the registry stop offering this payment to a future launch.
        scenario.close()
        waitUntil("session evicted from the registry") { PaymentSession.peek(intentId) == null }
    }

    // ---- result parcelling through a real Parcel (the F3 carrier) -----------------------

    @Test
    fun paymentResultSurvivesARealParcelRoundTrip() {
        val original = PaymentResult(
            status = PaymentStatus.FAILED,
            paymentIntentId = "pi_device_parcel",
            error = UQPayError(
                code = UQPayErrorCode.AUTHENTICATION_FAILED,
                message = "m",
                developerMessage = "d",
            ),
        )
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(original, 0)
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION")
            val restored = parcel.readParcelable<PaymentResult>(PaymentResult::class.java.classLoader)

            assertNotNull(restored)
            assertEquals(original.status, restored!!.status)
            assertEquals(original.paymentIntentId, restored.paymentIntentId)
            assertEquals(original.error?.code, restored.error?.code)
        } finally {
            parcel.recycle()
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun initializeSdk(blockTokenFetch: Boolean = false) {
        val provider = if (blockTokenFetch) {
            UQPayTokenProvider {
                tokenGate.await() // held until tearDown; the engine stays honestly mid-flight
                UQPayAuthToken("tok-device-test", System.currentTimeMillis() + 30 * 60_000L)
            }
        } else {
            UQPayTokenProvider {
                UQPayAuthToken("tok-device-test", System.currentTimeMillis() + 30 * 60_000L)
            }
        }
        UQPay.initialize(
            context,
            UQPayConfiguration(
                clientId = "client-device-test",
                environment = Environment.SANDBOX,
                tokenProvider = provider,
            ),
        )
    }

    private fun launchIntent(params: PaymentSessionParams): Intent =
        Intent(context, UQPayPaymentActivity::class.java)
            .putExtra(UQPayPaymentContract.EXTRA_PARAMS, params)

    private fun waitUntil(what: String, timeoutMillis: Long = 10_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for: $what")
    }
}
