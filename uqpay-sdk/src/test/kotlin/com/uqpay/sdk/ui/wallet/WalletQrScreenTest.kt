package com.uqpay.sdk.ui.wallet

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.uqpay.sdk.UQPay
import com.uqpay.sdk.engine.EngineState
import com.uqpay.sdk.engine.PaymentSession
import com.uqpay.sdk.engine.WalletConfirmClaim
import com.uqpay.sdk.engine.WalletConfirmLatch
import com.uqpay.sdk.payment.PaymentMethodType
import com.uqpay.sdk.payment.PaymentStatus
import com.uqpay.sdk.ui.PaymentUiState
import com.uqpay.sdk.ui.PaymentViewModel
import com.uqpay.sdk.ui.ScriptedGateway
import com.uqpay.sdk.ui.UiTestFixtures
import com.uqpay.sdk.ui.UqpayTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wallet QR screen and the ViewModel projection that feeds it.
 *
 * Two halves, deliberately. The composable is a pure function of its arguments, so every
 * state it can be in — loading, ready, failed, expired — is reachable without a network or a
 * clock. The flow half drives a **real** [PaymentSession] through the production composition
 * root with one fake at the socket, so what it asserts about the one-confirm rule is what
 * ships.
 *
 * The spinner in the loading state is an indeterminate `CircularProgressIndicator`, which
 * Compose drives from an `InfiniteTransition`. `createComposeRule` installs the
 * `InfiniteAnimationPolicy` that parks those; a test that launched this screen through
 * `ActivityScenario` instead would need to install one by hand, or it renders animation
 * frames until the machine gives out (Slice 3's 34-minute suite).
 *
 * No card value, key or customer field appears in this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// Amounts are rendered by the platform's currency formatter, which is locale-dependent by
// design (SGD is "SGD8.98" in en-US and "8,98 SGD" in de-DE). Pinning the locale here keeps
// the assertions below about *what is drawn* rather than about the machine running them;
// AmountFormatTest is where the per-locale behaviour itself is checked.
@Config(qualifiers = "en-rUS")
class WalletQrScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var retried = 0
    private var cancelled = 0

    @Before
    fun setUp() {
        WalletConfirmLatch.clearForTest()
        PaymentSession.clearAllForTest()
        UQPay.initialize(ApplicationProvider.getApplicationContext(), UiTestFixtures.configuration())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        WalletConfirmLatch.clearForTest()
        PaymentSession.clearAllForTest()
        UQPay.resetForTest()
    }

    private fun show(
        phase: QrImagePhase,
        rawPayload: String? = null,
        remainingMillis: Long? = null,
        walletName: String = "GrabPay",
    ) {
        compose.setContent {
            UqpayTheme {
                WalletQrScreen(
                    walletName = walletName,
                    amount = "8.98",
                    currency = "SGD",
                    phase = phase,
                    rawPayload = rawPayload,
                    remainingMillis = remainingMillis,
                    onRetry = { retried++ },
                    onCancel = { cancelled++ },
                )
            }
        }
    }

    // ---- accessibility -----------------------------------------------------------------

    /**
     * A screen-reader user cannot see that the square is a GrabPay code. Without the wallet's
     * name they are told "image" and asked to scan something nobody identified.
     */
    @Test
    fun `the QR image has a content description naming the wallet`() {
        show(QrImagePhase.Ready(bitmap()))

        compose.onNodeWithContentDescription("Payment QR code. Scan it with your GrabPay app.")
            .assertIsDisplayed()
    }

    @Test
    fun `a different wallet is named differently`() {
        show(QrImagePhase.Ready(bitmap()), walletName = "Alipay")

        compose.onNodeWithContentDescription("Payment QR code. Scan it with your Alipay app.")
            .assertIsDisplayed()
        compose.onNodeWithText("Scan this QR code with the Alipay app to pay.").assertIsDisplayed()
    }

    @Test
    fun `the loading spinner is named too`() {
        show(QrImagePhase.Loading)

        compose.onNodeWithContentDescription("Getting your QR code").assertIsDisplayed()
    }

    @Test
    fun `the amount and the wallet are on screen`() {
        show(QrImagePhase.Ready(bitmap()))

        compose.onNodeWithText("Pay with GrabPay").assertIsDisplayed()
        compose.onNodeWithText("SGD8.98").assertIsDisplayed()
    }

    // ---- failure is retryable ----------------------------------------------------------

    @Test
    fun `a failed image load offers Retry and retrying calls back`() {
        show(QrImagePhase.Failed)

        compose.onNodeWithText("We couldn't load the QR code.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Try loading the QR code again").performClick()

        assertEquals(1, retried)
    }

    @Test
    fun `a failed image load falls back to the payload when the gateway sent one`() {
        show(QrImagePhase.Failed, rawPayload = "PLACEHOLDER-PAYLOAD")

        compose.onNodeWithText("PLACEHOLDER-PAYLOAD").assertIsDisplayed()
    }

    // ---- expiry --------------------------------------------------------------------------

    @Test
    fun `an expired QR says so and stops showing the code`() {
        show(QrImagePhase.Ready(bitmap()), remainingMillis = 0L)

        compose.onNodeWithText("This QR code has expired.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Payment QR code. Scan it with your GrabPay app.")
            .assertDoesNotExist()
    }

    @Test
    fun `a live QR shows a countdown`() {
        show(QrImagePhase.Ready(bitmap()), remainingMillis = 125_000L)

        compose.onNodeWithText("Expires in 02:05").assertExists()
    }

    @Test
    fun `no expiry means no countdown, not a countdown at zero`() {
        show(QrImagePhase.Ready(bitmap()), remainingMillis = null)

        compose.onNodeWithText("Expires in 00:00").assertDoesNotExist()
        compose.onNodeWithContentDescription("Payment QR code. Scan it with your GrabPay app.")
            .assertIsDisplayed()
    }

    // ---- the way out --------------------------------------------------------------------

    @Test
    fun `every phase offers Cancel, including the expired one`() {
        val phase = mutableStateOf<QrImagePhase>(QrImagePhase.Loading)
        val remaining = mutableStateOf<Long?>(null)
        compose.setContent {
            UqpayTheme {
                WalletQrScreen(
                    walletName = "GrabPay",
                    amount = "8.98",
                    currency = "SGD",
                    phase = phase.value,
                    rawPayload = null,
                    remainingMillis = remaining.value,
                    onRetry = { retried++ },
                    onCancel = { cancelled++ },
                )
            }
        }

        val states = listOf(
            QrImagePhase.Loading to null,
            QrImagePhase.Ready(bitmap()) to null,
            QrImagePhase.Failed to null,
            QrImagePhase.Ready(bitmap()) to 0L,
        )
        states.forEachIndexed { index, (nextPhase, nextRemaining) ->
            phase.value = nextPhase
            remaining.value = nextRemaining
            compose.onNodeWithContentDescription("Cancel and leave this screen").performClick()
            assertEquals("state $index offered no way out", index + 1, cancelled)
        }
    }

    // ---- countdown formatting ------------------------------------------------------------

    @Test
    fun `the countdown rounds up so it never reads zero while the code is still valid`() {
        assertEquals("00:01", formatRemaining(1L))
        assertEquals("00:01", formatRemaining(1_000L))
        assertEquals("00:02", formatRemaining(1_001L))
        assertEquals("00:00", formatRemaining(0L))
        assertEquals("00:00", formatRemaining(-5_000L))
        assertEquals("10:00", formatRemaining(600_000L))
        // Not wrapped at an hour: 90 minutes is honest, 30 would be a lie.
        assertEquals("90:00", formatRemaining(5_400_000L))
    }

    // ---- expires_at parsing ----------------------------------------------------------------

    /** The exact value the live sandbox returned on 2026-08-18. */
    @Test
    fun `the live expires_at parses to the right instant`() {
        val parsed = parseExpiresAt("2026-08-18T17:14:19.855+08:00")

        // 2026-08-18T09:14:19.855Z. Recomputed independently rather than from this code.
        assertEquals(1_787_044_459_855L, parsed)
    }

    @Test
    fun `the shapes the gateway is entitled to send instead all parse to the same instant`() {
        val utc = parseExpiresAt("2026-08-18T09:14:19.855Z")
        assertEquals(utc, parseExpiresAt("2026-08-18T17:14:19.855+0800"))
        assertEquals(utc, parseExpiresAt("2026-08-18 09:14:19.855Z"))
        assertEquals(utc, parseExpiresAt("2026-08-18T01:14:19.855-08:00"))
        assertEquals(utc?.minus(855L), parseExpiresAt("2026-08-18T09:14:19Z"))
        // A missing offset is read as UTC rather than as the device's zone.
        assertEquals(utc, parseExpiresAt("2026-08-18T09:14:19.855"))
    }

    /**
     * Null means "draw no countdown", never "expired" — inventing an expiry from an
     * unreadable string would blank out a QR that is perfectly good.
     */
    @Test
    fun `an unparseable expires_at is null, never zero`() {
        listOf(null, "", "   ", "soon", "2026-13-99T99:99:99Z", "2026-08-18", "1755500000")
            .forEach { assertNull(it, parseExpiresAt(it)) }
    }

    @Test
    fun `a leap second and a leap day both parse`() {
        assertTrue(parseExpiresAt("2016-12-31T23:59:60Z") != null)
        assertEquals(parseExpiresAt("2024-03-01T00:00:00Z")?.minus(86_400_000L), parseExpiresAt("2024-02-29T00:00:00Z"))
    }

    // ---- the flow: one confirm per intent and wallet --------------------------------------

    /**
     * The engine reaches `RequiresAction` with a QR, and the projection carries everything
     * the screen needs — including the wallet's name, taken from the intent's own attempt so
     * it survives a process death that loses whatever this screen remembered.
     */
    @Test
    fun `a QR action projects to the wallet QR state`() = runTest {
        val h = harness()
        runCurrent()
        h.vm.onMethodSelected(PaymentMethodType.GRABPAY)
        runCurrent()
        h.answerWithQr()
        runCurrent()

        val state = h.vm.uiState.value as PaymentUiState.WalletQr
        assertEquals(PaymentMethodType.GRABPAY, state.methodType)
        assertEquals("8.98", state.amount)
        assertEquals("SGD", state.currency)
        assertEquals(QR_URL, state.qrUrl)
        assertEquals(parseExpiresAt(EXPIRES_AT), state.expiresAtEpochMillis)
    }

    /** The QR the engine surfaced is latched, so nothing can confirm this wallet again. */
    @Test
    fun `surfacing a QR latches the wallet`() = runTest {
        val h = harness()
        runCurrent()
        h.vm.onMethodSelected(PaymentMethodType.GRABPAY)
        runCurrent()
        h.answerWithQr()
        runCurrent()

        val claim = WalletConfirmLatch().claim(INTENT, "grabpay")
        assertTrue("a second confirm must be impossible", claim is WalletConfirmClaim.AlreadyIssued)
        assertEquals(QR_URL, (claim as WalletConfirmClaim.AlreadyIssued).qr.url)
    }

    /**
     * The disaster: the customer leaves with the QR live (`PENDING`), the merchant relaunches
     * the sheet for the same intent, and they tap the same wallet again. The live sandbox
     * **accepts** that second confirm and issues a second QR, orphaning the first. Exactly
     * one POST may ever leave.
     */
    @Test
    fun `re-entering after leaving re-serves the issued QR and sends no second confirm`() = runTest {
        val h = harness()
        runCurrent()
        h.vm.onMethodSelected(PaymentMethodType.GRABPAY)
        runCurrent()
        h.answerWithQr()
        runCurrent()
        assertEquals(1, h.net.posts.size)

        // The customer leaves: an attempt is in the air, so this settles PENDING, not
        // CANCELLED — and PENDING must not free the latch.
        h.vm.onCancelConfirmed()
        runCurrent()
        val terminal = h.session.state.value as EngineState.Terminal
        assertEquals(PaymentStatus.PENDING, terminal.result.status)

        // A brand-new session and ViewModel for the same intent — the relaunch. Released
        // rather than cleared: `clearAllForTest` also empties the wallet latch, which is
        // exactly the state under test. A real relaunch keeps it.
        PaymentSession.release(INTENT)
        val second = harness()
        runCurrent()
        second.vm.onMethodSelected(PaymentMethodType.GRABPAY)
        runCurrent()

        assertEquals("a second confirm would open a second live attempt", 0, second.net.posts.size)
        val reServed = second.vm.uiState.value as PaymentUiState.WalletQr
        assertEquals(QR_URL, reServed.qrUrl)
        assertEquals(PaymentMethodType.GRABPAY, reServed.methodType)
    }

    /**
     * **A re-served QR must be watched by somebody (audit item 14).**
     *
     * Re-serving the QR is only half of what a refused claim owes the customer. The QR on
     * their screen is live and payable; if no poller is watching the intent, they scan it, the
     * gateway settles the payment, and this session never learns. Worse, with nothing in the
     * air the engine treats a back-press as `CANCELLED` — so the merchant is told the customer
     * walked away from an order that was in fact paid, and releases nothing.
     *
     * The engine now watches the attempt without confirming it, so the QR arrives as ordinary
     * `RequiresAction` state, the intent is polled, and leaving reports `PENDING`.
     */
    @Test
    fun `a re-served QR is polled, and leaving it reports PENDING rather than CANCELLED`() = runTest {
        val h = harness()
        runCurrent()
        h.vm.onMethodSelected(PaymentMethodType.GRABPAY)
        runCurrent()
        h.answerWithQr()
        runCurrent()
        h.vm.onCancelConfirmed()
        runCurrent()
        PaymentSession.release(INTENT)

        val second = harness()
        runCurrent()
        val readsBeforeTap = second.net.gets
        second.vm.onMethodSelected(PaymentMethodType.GRABPAY)
        runCurrent()

        assertEquals("a second confirm would open a second live attempt", 0, second.net.posts.size)
        assertTrue(
            "the engine must be watching the attempt the QR belongs to",
            second.session.state.value is EngineState.RequiresAction,
        )
        assertTrue("nobody is polling the re-served QR", second.net.gets > readsBeforeTap)
        assertTrue("a live QR means an attempt is in the air", second.session.engine.hasAttemptInAir)

        // The customer leaves the re-served QR. The attempt is outstanding, so this is
        // PENDING — never CANCELLED, which would tell the merchant nothing happened.
        second.vm.onCancelConfirmed()
        runCurrent()
        val terminal = second.session.state.value as EngineState.Terminal
        assertEquals(PaymentStatus.PENDING, terminal.result.status)
    }

    /**
     * **A tap that is refused because a confirm is already on the wire must still do
     * something (audit item 14).**
     *
     * The latch is right to refuse — the first confirm may be creating a payment attempt at
     * that instant — but the tap used to return with no state change whatsoever: no progress,
     * no error, nothing. A button that visibly does nothing reads as broken and invites the
     * customer to tap it until it does something.
     *
     * The engine now watches the intent until the other confirm's action appears or the
     * payment settles, so the screen shows progress with a way out.
     */
    @Test
    fun `a tap while another confirm is in flight shows progress instead of doing nothing`() = runTest {
        // Somebody else's confirm is on the wire for this intent and wallet: claimed, not yet
        // reported. This is the state a detached reconciler from a previous session leaves.
        assertEquals(WalletConfirmClaim.Granted, WalletConfirmLatch().claim(INTENT, PaymentMethodType.GRABPAY.raw))

        val h = harness()
        runCurrent()
        assertTrue(h.vm.uiState.value is PaymentUiState.MethodList)

        h.vm.onMethodSelected(PaymentMethodType.GRABPAY)
        runCurrent()

        assertEquals("nothing may be sent while another confirm is unresolved", 0, h.net.posts.size)
        assertTrue(
            "the tap left the customer on the method list with no feedback at all",
            h.vm.uiState.value is PaymentUiState.Polling,
        )
        assertTrue(h.session.engine.hasAttemptInAir)
    }

    /**
     * The relaxed decline rule that watching-without-confirming needs, stated on its own.
     *
     * An attempt this engine sent and then finds back at `REQUIRES_PAYMENT_METHOD` has died,
     * and the engine reports that decline. An attempt it merely inherited has no such
     * guarantee: that status is also what the intent reads while somebody else's confirm is
     * still in the air. Failing the payment on that reading would be a false decline for a
     * payment that is about to be made.
     */
    @Test
    fun `an inherited attempt is not declined merely because the intent has no attempt yet`() = runTest {
        assertEquals(WalletConfirmClaim.Granted, WalletConfirmLatch().claim(INTENT, PaymentMethodType.GRABPAY.raw))

        val h = harness()
        runCurrent()
        h.vm.onMethodSelected(PaymentMethodType.GRABPAY)
        runCurrent()

        // The scripted gateway keeps answering REQUIRES_PAYMENT_METHOD with no attempt on it.
        repeat(3) {
            advanceTimeBy(2_500L)
            runCurrent()
        }

        assertFalse(
            "a not-yet-recorded confirm was reported as a decline",
            h.session.state.value is EngineState.Terminal,
        )
    }

    /** An Alipay tap on the same intent is a different wallet and gets its own confirm. */
    @Test
    fun `a different wallet on the same intent is still allowed to confirm`() = runTest {
        val h = harness()
        runCurrent()
        h.vm.onMethodSelected(PaymentMethodType.GRABPAY)
        runCurrent()
        h.answerWithQr()
        runCurrent()

        PaymentSession.release(INTENT)
        val second = harness()
        runCurrent()
        second.vm.onMethodSelected(PaymentMethodType.ALIPAY_CN)
        runCurrent()

        assertEquals(1, second.net.posts.size)
    }

    // ---- fixtures --------------------------------------------------------------------------

    private class Harness(val net: ScriptedGateway, val session: PaymentSession, val vm: PaymentViewModel) {
        fun answerWithQr() {
            net.posts.last().answer(
                200,
                UiTestFixtures.intentJson(
                    INTENT,
                    "REQUIRES_CUSTOMER_ACTION",
                    methods = METHODS,
                    rawAction = """{"type":"display_qr_code","display_qr_code":""" +
                        """{"qr_code_url":"$QR_URL","qr_code":"PLACEHOLDER","expires_at":"$EXPIRES_AT"}}""",
                ).replace(
                    "\"available_payment_method_types\"",
                    "\"latest_payment_attempt\":{\"attempt_id\":\"PA_x\",\"attempt_status\":" +
                        "\"AUTHENTICATION_REDIRECTED\",\"payment_method_type\":\"grabpay\"}," +
                        "\"available_payment_method_types\"",
                ),
            )
        }
    }

    private fun TestScope.harness(): Harness {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val net = ScriptedGateway(methods = METHODS)
        val session = PaymentSession.obtain(INTENT, UiTestFixtures.dependencies(net, dispatcher))
        session.startIfNeeded()
        val vm = PaymentViewModel(INTENT, session, SavedStateHandle(), now = { testScheduler.currentTime })
        return Harness(net, session, vm)
    }

    private fun bitmap(): Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    private companion object {
        const val INTENT = "PI_wallet_qr_test"
        val METHODS = listOf("card", "grabpay", "alipaycn")
        const val QR_URL = "https://api-sandbox.invalid/api/v2/payment/qr?data=PLACEHOLDER"
        const val EXPIRES_AT = "2026-08-18T17:14:19.855+08:00"
    }
}
