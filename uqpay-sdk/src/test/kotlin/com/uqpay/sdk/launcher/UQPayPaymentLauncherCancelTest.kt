package com.uqpay.sdk.launcher

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.test.core.app.ApplicationProvider
import com.uqpay.sdk.UQPay
import com.uqpay.sdk.engine.ConfirmPayload
import com.uqpay.sdk.engine.EngineState
import com.uqpay.sdk.engine.PaymentSession
import com.uqpay.sdk.engine.Presentation
import com.uqpay.sdk.payment.PaymentCallback
import com.uqpay.sdk.payment.PaymentSessionParams
import com.uqpay.sdk.payment.PaymentStatus
import com.uqpay.sdk.testErrorCopy
import com.uqpay.sdk.ui.ScriptedGateway
import com.uqpay.sdk.ui.UiTestFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `UQPayPaymentLauncher.cancel()` — the merchant closing a sheet that has become wrong: the
 * basket timed out, the order was cancelled from the back office, the customer paid on
 * another device.
 *
 * The behaviour that carries money is the second test. Cancelling a sheet does **not** reach
 * into the gateway and un-send a confirm, so a cancel with an attempt in the air must settle
 * `PENDING`, never `CANCELLED`. Reporting `CANCELLED` there is how an order gets released for
 * money that did move.
 *
 * These assert against the **engine's** settled state rather than against a delivered
 * `PaymentResult`, because that is where the choice is made; `UQPayPaymentActivityTest`
 * covers the delivery of a `Terminal` to the merchant through the real contract.
 *
 * No real card number, key or API secret appears in this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UQPayPaymentLauncherCancelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        PaymentSession.clearAllForTest()
        UQPay.initialize(context, UiTestFixtures.configuration())
    }

    @After
    fun tearDown() {
        PaymentSession.clearAllForTest()
        UQPay.resetForTest()
    }

    @Test
    fun `cancelling with nothing submitted settles cancelled`() = runTest {
        val (launcher, session) = launched()
        runCurrent()
        assertTrue(session.state.value is EngineState.SelectingMethod)

        launcher.cancel()

        assertEquals(PaymentStatus.CANCELLED, session.terminalStatus())
    }

    @Test
    fun `cancelling with a confirm in the air settles pending, never cancelled`() = runTest {
        val (launcher, session) = launched()
        runCurrent()
        session.engine.confirm(ConfirmPayload.Wallet.forMethod(INTENT, "alipaycn"))
        runCurrent()
        assertTrue("the confirm must be in the air, or this test proves nothing", session.engine.hasAttemptInAir)

        launcher.cancel()

        assertEquals(PaymentStatus.PENDING, session.terminalStatus())
    }

    @Test
    fun `cancelling before any launch does nothing`() {
        val launcher = UQPayPaymentLauncherImpl(NoopLauncher, PaymentCallback {}, testErrorCopy())
        launcher.cancel()
        assertEquals(0, PaymentSession.activeCount)
    }

    @Test
    fun `cancelling twice is a no-op the second time and never changes the outcome`() = runTest {
        val (launcher, session) = launched()
        runCurrent()

        launcher.cancel()
        val first = session.terminalStatus()
        launcher.cancel()

        assertEquals(first, session.terminalStatus())
    }

    /**
     * A cancel after the payment has finished must not disturb it. The session has already
     * left the registry by then, so the lookup finds nothing — which is also what makes a
     * stale remembered intent id harmless.
     */
    @Test
    fun `cancelling after the payment has ended leaves the outcome alone`() = runTest {
        val (launcher, session) = launched()
        runCurrent()
        launcher.cancel()
        val settled = session.terminalStatus()

        session.detachHost(forGood = true)
        launcher.cancel()

        assertEquals(settled, session.terminalStatus())
    }

    /** Only the launcher's own most recent launch is affected. */
    @Test
    fun `a launcher cancels its own payment and not another launcher's`() = runTest {
        val (mine, myself) = launched(INTENT)
        val (theirs, other) = launched(OTHER_INTENT)
        runCurrent()

        mine.cancel()

        assertEquals(PaymentStatus.CANCELLED, myself.terminalStatus())
        assertTrue("the other payment must be untouched", other.state.value !is EngineState.Terminal)
        theirs.cancel()
        assertEquals(PaymentStatus.CANCELLED, other.terminalStatus())
    }

    // ---- helpers -------------------------------------------------------------------------

    /**
     * A launcher that has "launched" [id], plus the session the payment Activity would have
     * created for it.
     *
     * The session is obtained here rather than by an Activity for the same reason
     * `UQPayPaymentActivityTest` does it: `obtain` returns the running session for an id, so
     * a test that obtains first is standing exactly where the Activity would.
     */
    private fun TestScope.launched(id: String = INTENT): Pair<UQPayPaymentLauncherImpl, PaymentSession> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = PaymentSession.obtain(id, UiTestFixtures.dependencies(ScriptedGateway(), dispatcher))
        session.startIfNeeded(Presentation.MethodList)
        val launcher = UQPayPaymentLauncherImpl(NoopLauncher, PaymentCallback {}, testErrorCopy())
        launcher.launch(PaymentSessionParams(id))
        return launcher to session
    }

    private fun PaymentSession.terminalStatus(): PaymentStatus {
        val state = state.value
        assertTrue("expected Terminal, was $state", state is EngineState.Terminal)
        return (state as EngineState.Terminal).result.status
    }

    /**
     * Stands in for the registered `ActivityResultLauncher`. `cancel()` never touches it —
     * it settles the engine and lets the Activity's own collector deliver — so recording the
     * launch is all this has to do.
     */
    private object NoopLauncher : ActivityResultLauncher<PaymentSessionParams>() {
        override fun launch(input: PaymentSessionParams, options: androidx.core.app.ActivityOptionsCompat?) = Unit
        override fun unregister() = Unit
        override val contract: ActivityResultContract<PaymentSessionParams, *>
            get() = throw UnsupportedOperationException("cancel() never reads the contract")
    }

    private companion object {
        const val INTENT = "PI_cancel_test"
        const val OTHER_INTENT = "PI_cancel_test_other"
    }
}
