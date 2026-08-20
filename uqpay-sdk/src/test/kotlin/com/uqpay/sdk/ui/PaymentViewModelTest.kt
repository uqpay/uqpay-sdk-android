package com.uqpay.sdk.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.uqpay.sdk.Environment
import com.uqpay.sdk.UQPay
import com.uqpay.sdk.UQPayConfiguration
import com.uqpay.sdk.auth.UQPayAuthToken
import com.uqpay.sdk.auth.UQPayTokenProvider
import com.uqpay.sdk.engine.EngineState
import com.uqpay.sdk.engine.PaymentSession
import com.uqpay.sdk.engine.Presentation
import com.uqpay.sdk.engine.SessionDependencies
import com.uqpay.sdk.network.HttpMethod
import com.uqpay.sdk.network.UQPayNetworkClient
import com.uqpay.sdk.network.UQPayRequest
import com.uqpay.sdk.network.UQPayResponse
import com.uqpay.sdk.payment.PaymentMethodType
import com.uqpay.sdk.payment.PaymentResult
import com.uqpay.sdk.payment.PaymentStatus
import com.uqpay.sdk.ui.threeds.ThreeDsReturnUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * The ViewModel's rules, exercised against a **real** [PaymentSession] built through the
 * production composition root with one fake at the socket — the same seam
 * `PaymentSessionTest` uses. Nothing between the screen and the wire is stubbed, so a rule
 * that holds here holds against the engine as shipped.
 *
 * Time is virtual throughout: the engine runs on the test scheduler, `Dispatchers.Main` is
 * the same scheduler, and the ViewModel's monotonic clock reads it. Advancing the scheduler
 * advances the blocked window and the engine's ladders and polls together.
 *
 * No real card number, key or API secret appears in this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PaymentViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        PaymentSession.clearAllForTest()
        UQPay.initialize(context, UiTestFixtures.configuration())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        PaymentSession.clearAllForTest()
        UQPay.resetForTest()
    }

    // ---- projection --------------------------------------------------------------------

    @Test
    fun `starts Loading and projects the method list once the intent is read`() = runTest {
        val h = harness()
        assertEquals(PaymentUiState.Loading, h.vm.uiState.value)
        runCurrent()
        val list = h.vm.uiState.value as PaymentUiState.MethodList
        assertEquals("8.98", list.amount)
        assertEquals("SGD", list.currency)
        assertEquals("order-1", list.merchantOrderId)
    }

    /** G21: unknown types hidden, never an error; API order preserved; card pinned first. */
    @Test
    fun `G21 - the list shows only methods this SDK can name, in engine order, card first`() = runTest {
        val h = harness(methods = listOf("alipaycn", "futurepay", "card", "grabpay", "somethingelse"))
        runCurrent()
        val list = h.vm.uiState.value as PaymentUiState.MethodList
        assertEquals(
            listOf(PaymentMethodType.CARD, PaymentMethodType.ALIPAY_CN, PaymentMethodType.GRABPAY),
            list.methods,
        )
    }

    @Test
    fun `an intent offering only unknown methods renders an empty list, not an error`() = runTest {
        val h = harness(methods = listOf("futurepay"))
        runCurrent()
        val list = h.vm.uiState.value as PaymentUiState.MethodList
        assertTrue(list.methods.isEmpty())
        assertTrue(h.session.state.value is EngineState.SelectingMethod)
    }

    @Test
    fun `every renderable method has exactly the card and the thirteen declared wallets`() {
        assertEquals(14, PaymentViewModel.RENDERABLE_METHODS.size)
        assertTrue(PaymentMethodType.CARD in PaymentViewModel.RENDERABLE_METHODS)
    }

    // ---- method selection --------------------------------------------------------------

    @Test
    fun `selecting card shows the placeholder and confirms nothing`() = runTest {
        val h = harness()
        runCurrent()
        h.vm.onMethodSelected(PaymentMethodType.CARD)
        runCurrent()
        assertEquals(PaymentUiState.CardPlaceholder(canReturnToList = true), h.vm.uiState.value)
        assertEquals(0, h.net.posts.size)
        assertEquals(true, h.savedState.get<Boolean>(PaymentViewModel.KEY_CARD_FORM_SHOWN))

        h.vm.onReturnToList()
        runCurrent()
        assertTrue(h.vm.uiState.value is PaymentUiState.MethodList)
    }

    @Test
    fun `selecting a wallet confirms it and the screen shows Confirming`() = runTest {
        val h = harness()
        runCurrent()
        h.vm.onMethodSelected(PaymentMethodType.ALIPAY_CN)
        runCurrent()
        assertEquals(1, h.net.posts.size)
        assertTrue(h.net.posts[0].request.body.orEmpty().contains("\"type\":\"alipaycn\""))
        assertEquals(PaymentUiState.Confirming(PaymentMethodType.ALIPAY_CN, leaveBlocked = false), h.vm.uiState.value)
    }

    @Test
    fun `a stale method tap after the confirm started is ignored`() = runTest {
        val h = harness()
        runCurrent()
        h.vm.onMethodSelected(PaymentMethodType.ALIPAY_CN)
        runCurrent()
        h.vm.onMethodSelected(PaymentMethodType.GRABPAY)
        h.vm.onMethodSelected(PaymentMethodType.CARD)
        runCurrent()
        assertEquals("no second attempt from a stale tap", 1, h.net.posts.size)
        assertTrue(h.vm.uiState.value is PaymentUiState.Confirming)
    }

    // ---- back-press: nothing in the air ------------------------------------------------

    @Test
    fun `back with nothing in the air is CANCELLED with the intent id`() = runTest {
        val h = harness()
        runCurrent()
        assertEquals(BackDecision.CANCELLED, h.vm.onBackRequested())
        val terminal = h.session.terminal()
        assertEquals(PaymentStatus.CANCELLED, terminal.status)
        assertEquals(INTENT, terminal.paymentIntentId)
        runCurrent()
        assertEquals(PaymentUiState.Finishing, h.vm.uiState.value)
    }

    @Test
    fun `back while still loading is CANCELLED, never blank`() = runTest {
        val h = harness()
        // No runCurrent: the intent read has not returned.
        assertEquals(BackDecision.CANCELLED, h.vm.onBackRequested())
        assertEquals(PaymentStatus.CANCELLED, h.session.terminal().status)
        assertEquals(INTENT, h.session.terminal().paymentIntentId)
    }

    @Test
    fun `back on the card placeholder returns to the list and cancels nothing`() = runTest {
        val h = harness()
        runCurrent()
        h.vm.onMethodSelected(PaymentMethodType.CARD)
        runCurrent()
        assertEquals(BackDecision.RETURNED_TO_LIST, h.vm.onBackRequested())
        runCurrent()
        assertTrue(h.vm.uiState.value is PaymentUiState.MethodList)
        assertFalse(h.session.state.value is EngineState.Terminal)
    }

    @Test
    fun `back after the outcome is decided is ALREADY_FINISHED`() = runTest {
        val h = harness()
        runCurrent()
        h.vm.onBackRequested()
        assertEquals(BackDecision.ALREADY_FINISHED, h.vm.onBackRequested())
        assertEquals(BackDecision.ALREADY_FINISHED, h.vm.onCancelConfirmed())
    }

    // ---- back-press: confirm in flight (§2c) -------------------------------------------

    @Test
    fun `back while confirming is BLOCKED - visible, bounded, and PENDING at the bound`() = runTest {
        val h = harness()
        startWalletConfirm(h)

        assertEquals(BackDecision.BLOCKED, h.vm.onBackRequested())
        runCurrent()
        assertEquals(PaymentUiState.Confirming(PaymentMethodType.ALIPAY_CN, leaveBlocked = true), h.vm.uiState.value)
        assertEquals(PaymentViewModel.BLOCKED_WINDOW_MILLIS, h.vm.blockedWindowRemainingMillis())
        assertFalse("still confirming, still on screen", h.session.state.value is EngineState.Terminal)

        // A second press does not shorten or restart the window.
        advanceTimeBy(4_000L)
        assertEquals(BackDecision.BLOCKED, h.vm.onBackRequested())
        assertEquals(PaymentViewModel.BLOCKED_WINDOW_MILLIS - 4_000L, h.vm.blockedWindowRemainingMillis())

        advanceTimeBy(5_999L)
        runCurrent()
        assertFalse("one millisecond before the bound the customer is still held", h.session.state.value is EngineState.Terminal)

        advanceTimeBy(2L)
        runCurrent()
        val terminal = h.session.terminal()
        assertEquals("unresolved at the bound is PENDING, never CANCELLED", PaymentStatus.PENDING, terminal.status)
        assertEquals(INTENT, terminal.paymentIntentId)
        assertEquals("the attempt keeps running as the reconciler", false, h.net.posts[0].cancelled)
    }

    @Test
    fun `a confirm that succeeds inside the blocked window delivers SUCCEEDED and cancels nothing`() = runTest {
        val h = harness()
        startWalletConfirm(h)
        h.vm.onBackRequested()
        advanceTimeBy(3_000L)
        h.net.posts[0].answer(200, UiTestFixtures.intentJson(INTENT, "SUCCEEDED"))
        advanceUntilIdle()
        assertEquals(PaymentStatus.SUCCEEDED, h.session.terminal().status)
        assertEquals(0, h.session.engine.droppedSettleAttempts)
    }

    @Test
    fun `a confirm that resolves to a customer action inside the window honours the leave as PENDING`() = runTest {
        val h = harness()
        startWalletConfirm(h)
        h.vm.onBackRequested()
        advanceTimeBy(1_000L)
        h.net.posts[0].answer(200, UiTestFixtures.intentJson(INTENT, "REQUIRES_CUSTOMER_ACTION", qrAction = true))
        runCurrent()
        assertEquals("the customer asked to leave; a sent attempt is PENDING", PaymentStatus.PENDING, h.session.terminal().status)
    }

    @Test
    fun `rotation mid-block - a new ViewModel over the same saved state does not reset the window`() = runTest {
        val h = harness()
        startWalletConfirm(h)
        h.vm.onBackRequested()
        advanceTimeBy(7_000L)

        // Recreation: same session, same SavedStateHandle contents, new ViewModel instance.
        val recreated = h.recreateViewModel()
        runCurrent()
        assertEquals(PaymentViewModel.BLOCKED_WINDOW_MILLIS - 7_000L, recreated.blockedWindowRemainingMillis())
        assertEquals(PaymentUiState.Confirming(PaymentMethodType.ALIPAY_CN, leaveBlocked = true), recreated.uiState.value)

        advanceTimeBy(3_001L)
        runCurrent()
        assertEquals("the restored watchdog fires at the original bound", PaymentStatus.PENDING, h.session.terminal().status)
    }

    @Test
    fun `after process death a stale blocked window is forgotten - the fresh session is not confirming`() = runTest {
        val h = harness(savedState = SavedStateHandle(mapOf(PaymentViewModel.KEY_BLOCKED_SINCE to 5L)))
        assertEquals(PaymentViewModel.NO_BLOCK, h.savedState.get<Long>(PaymentViewModel.KEY_BLOCKED_SINCE))
        assertEquals(0L, h.vm.blockedWindowRemainingMillis())
        runCurrent()
        assertTrue(h.vm.uiState.value is PaymentUiState.MethodList)
    }

    // ---- back-press: attempt in the air, not confirming (M-3 / M-4) --------------------

    @Test
    fun `back during a customer action is PENDING immediately`() = runTest {
        val h = harness()
        startWalletConfirm(h)
        h.net.posts[0].answer(200, UiTestFixtures.intentJson(INTENT, "REQUIRES_CUSTOMER_ACTION", qrAction = true))
        runCurrent()
        // Slice 5 replaced the QR placeholder with the real screen's state.
        assertTrue(h.vm.uiState.value is PaymentUiState.WalletQr)
        assertEquals(BackDecision.PENDING, h.vm.onBackRequested())
        assertEquals(PaymentStatus.PENDING, h.session.terminal().status)
    }

    @Test
    fun `back during polling is PENDING immediately`() = runTest {
        val h = harness()
        startWalletConfirm(h)
        h.net.posts[0].answer(200, UiTestFixtures.intentJson(INTENT, "PENDING"))
        runCurrent()
        assertEquals(PaymentUiState.Polling, h.vm.uiState.value)
        assertEquals(BackDecision.PENDING, h.vm.onBackRequested())
        assertEquals(PaymentStatus.PENDING, h.session.terminal().status)
    }

    @Test
    fun `the on-screen Cancel is the same way out - PENDING with an attempt in the air`() = runTest {
        val h = harness()
        startWalletConfirm(h)
        h.net.posts[0].answer(200, UiTestFixtures.intentJson(INTENT, "PENDING"))
        runCurrent()
        assertEquals(BackDecision.PENDING, h.vm.onCancelConfirmed())
        assertEquals(INTENT, h.session.terminal().paymentIntentId)
    }

    @Test
    fun `an unknown next_action renders as a placeholder with a way out, never an error`() = runTest {
        val h = harness()
        startWalletConfirm(h)
        h.net.posts[0].answer(200, UiTestFixtures.intentJson(INTENT, "REQUIRES_CUSTOMER_ACTION", rawAction = """{"type":"hologram"}"""))
        runCurrent()
        assertEquals(PaymentUiState.AwaitingAction(ActionKind.UNKNOWN), h.vm.uiState.value)
        assertEquals(BackDecision.PENDING, h.vm.onCancelConfirmed())
    }

    // ---- presentations (G19) -----------------------------------------------------------

    @Test
    fun `card-only goes straight to the card placeholder with no list to return to, and back cancels`() = runTest {
        val h = harness(presentation = Presentation.CardOnly)
        runCurrent()
        assertEquals(PaymentUiState.CardPlaceholder(canReturnToList = false), h.vm.uiState.value)
        assertEquals(BackDecision.CANCELLED, h.vm.onBackRequested())
    }

    @Test
    fun `single-wallet confirms the wallet immediately, once, and never shows a list`() = runTest {
        val h = harness(presentation = Presentation.SingleWallet(PaymentMethodType.GRABPAY))
        val seen = mutableListOf<PaymentUiState>()
        runCurrent()
        seen += h.vm.uiState.value
        assertEquals(1, h.net.posts.size)
        assertTrue(h.net.posts[0].request.body.orEmpty().contains("\"type\":\"grabpay\""))
        assertTrue(seen.none { it is PaymentUiState.MethodList })
        assertEquals(PaymentUiState.Confirming(PaymentMethodType.GRABPAY, leaveBlocked = false), h.vm.uiState.value)

        // A recreated ViewModel finds the confirm already in flight and adds nothing.
        h.recreateViewModel()
        runCurrent()
        assertEquals(1, h.net.posts.size)
    }

    // ---- load failure ------------------------------------------------------------------

    @Test
    fun `a load failure settles FAILED with the intent id - delivered, not shown as a dead screen`() = runTest {
        val h = harness(getFailure = IOException("no route"))
        advanceUntilIdle()
        val terminal = h.session.terminal()
        assertEquals(PaymentStatus.FAILED, terminal.status)
        assertEquals(INTENT, terminal.paymentIntentId)
        assertEquals(PaymentUiState.Finishing, h.vm.uiState.value)
    }

    // ---- return_url (Slice 6, item 2) --------------------------------------------------

    @Test
    fun `the 3-D Secure screen is given the intent's own return_url as its end-of-step prefix`() = runTest {
        val returnUrl = "https://merchant.example.invalid/uqpay/return"
        val h = harness(
            initialStatus = "REQUIRES_CUSTOMER_ACTION",
            initialAction = """{"type":"redirect_to_url","redirect_to_url":{"url":"https://acs.example.invalid/challenge"}}""",
            returnUrl = returnUrl,
        )
        runCurrent()

        val state = h.vm.uiState.value as PaymentUiState.ThreeDs
        assertEquals(listOf(returnUrl), state.returnUrlPrefixes)

        // The wiring is only worth anything if it decides the step. Same predicate the
        // WebView client uses.
        assertTrue(
            "the merchant's return page ends the step",
            ThreeDsReturnUrl.isEndOfBrowserStep("$returnUrl?p=succeeded", state.returnUrlPrefixes),
        )
        assertFalse(
            "the issuer's own page does not",
            ThreeDsReturnUrl.isEndOfBrowserStep("https://acs.example.invalid/challenge/otp", state.returnUrlPrefixes),
        )
        assertFalse(
            "a different https host does not",
            ThreeDsReturnUrl.isEndOfBrowserStep("https://merchant.example.invalid/other", state.returnUrlPrefixes),
        )
    }

    @Test
    fun `an intent with no return_url offers no prefix, so nothing can match everything`() = runTest {
        val h = harness(
            initialStatus = "REQUIRES_CUSTOMER_ACTION",
            initialAction = """{"type":"redirect_to_url","redirect_to_url":{"url":"https://acs.example.invalid/challenge"}}""",
            returnUrl = null,
        )
        runCurrent()

        val state = h.vm.uiState.value as PaymentUiState.ThreeDs
        assertEquals(emptyList<String>(), state.returnUrlPrefixes)
        assertFalse(ThreeDsReturnUrl.isEndOfBrowserStep("https://acs.example.invalid/challenge", state.returnUrlPrefixes))
    }

    @Test
    fun `a blank return_url is dropped rather than matching the first page loaded`() = runTest {
        val h = harness(
            initialStatus = "REQUIRES_CUSTOMER_ACTION",
            initialAction = """{"type":"redirect_to_url","redirect_to_url":{"url":"https://acs.example.invalid/challenge"}}""",
            returnUrl = "   ",
        )
        runCurrent()

        val state = h.vm.uiState.value as PaymentUiState.ThreeDs
        assertEquals(emptyList<String>(), state.returnUrlPrefixes)
    }

    // ---- foreground re-read (Slice 6, item 4 · AC 8.1) ---------------------------------

    @Test
    fun `returning to the foreground re-reads the intent once, and schedules no extra poll`() = runTest {
        val h = harness(
            initialStatus = "REQUIRES_CUSTOMER_ACTION",
            initialAction = """{"type":"display_qr_code","display_qr_code":{"qr_code_url":"https://example.invalid/qr","expires_at":null}}""",
        )
        runCurrent()
        // One GET to load the intent, one for the watcher's first look. Then it waits.
        val afterAdopt = h.net.gets
        assertTrue("the adopted action must be being polled", afterAdopt >= 2)

        // The screen's own first start. Not a return from anywhere.
        h.vm.onForegrounded()
        runCurrent()
        assertEquals("the first foreground is not a return", afterAdopt, h.net.gets)

        advanceTimeBy(500)
        runCurrent()
        assertEquals("still inside the poll interval", afterAdopt, h.net.gets)

        // The customer comes back from their wallet app.
        h.vm.onForegrounded()
        runCurrent()
        assertEquals("exactly one immediate re-read", afterAdopt + 1, h.net.gets)

        // A nudge *replaces* the wait; it must not leave a second timer behind.
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals("no extra poll was scheduled", afterAdopt + 1, h.net.gets)
    }

    @Test
    fun `foregrounding with nothing being polled is harmless`() = runTest {
        val h = harness()
        runCurrent()
        val before = h.net.gets
        repeat(4) { h.vm.onForegrounded() }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(before, h.net.gets)
        assertTrue(h.vm.uiState.value is PaymentUiState.MethodList)
    }

    // ---- harness -----------------------------------------------------------------------

    private class Harness(
        val net: ScriptedGateway,
        val session: PaymentSession,
        val savedState: SavedStateHandle,
        private val build: (SavedStateHandle) -> PaymentViewModel,
    ) {
        var vm: PaymentViewModel = build(savedState)
            private set

        fun recreateViewModel(): PaymentViewModel {
            vm = build(SavedStateHandle(savedState.keys().associateWith { savedState.get<Any>(it) }))
            return vm
        }
    }

    private fun TestScope.harness(
        methods: List<String> = listOf("card", "alipaycn"),
        presentation: Presentation = Presentation.MethodList,
        savedState: SavedStateHandle = SavedStateHandle(),
        getFailure: Throwable? = null,
        initialStatus: String = "REQUIRES_PAYMENT_METHOD",
        initialAction: String? = null,
        returnUrl: String? = null,
    ): Harness {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val net = ScriptedGateway(
            methods = methods,
            getFailure = getFailure,
            initialStatus = initialStatus,
            initialAction = initialAction,
            returnUrl = returnUrl,
        )
        val session = PaymentSession.obtain(INTENT, UiTestFixtures.dependencies(net, dispatcher))
        session.startIfNeeded(presentation)
        return Harness(net, session, savedState) { handle ->
            PaymentViewModel(INTENT, session, handle, now = { testScheduler.currentTime })
        }
    }

    /** Reaches `SelectingMethod`, taps a wallet, and lets the pre-confirm intercept and POST go out. */
    private fun TestScope.startWalletConfirm(h: Harness) {
        runCurrent()
        h.vm.onMethodSelected(PaymentMethodType.ALIPAY_CN)
        runCurrent()
        assertEquals(1, h.net.posts.size)
        assertTrue(h.session.engine.isConfirmInFlight)
    }

    private fun PaymentSession.terminal(): PaymentResult {
        val s = state.value
        if (s !is EngineState.Terminal) fail("expected Terminal, was $s")
        return (s as EngineState.Terminal).result
    }

    private companion object {
        const val INTENT = "PI_vm_test"
    }
}

// ---- Shared fixtures for the ui package's tests ---------------------------------------------

/**
 * The socket, with the one piece of gateway behaviour the engine depends on: **an intent
 * has a state, and a GET returns it.**
 *
 * Until a confirm is answered a GET reports an unpaid intent whose method list the test
 * chooses (or throws, to simulate a dead network). Answering a confirm moves the intent to
 * whatever that answer said, and every later GET reports *that* — which is what a real
 * gateway does, and what the engine assumes. A fake whose GET kept saying
 * `REQUIRES_PAYMENT_METHOD` after a confirm returned `REQUIRES_CUSTOMER_ACTION` would be
 * telling the engine the attempt had failed, and the engine would rightly settle FAILED: an
 * artefact of the fake, not of the SDK.
 *
 * Every POST hangs until the test answers it, and records whether it was cancelled instead.
 */
internal class ScriptedGateway(
    private val methods: List<String> = listOf("card", "alipaycn"),
    private val getFailure: Throwable? = null,
    /** The intent's status before any confirm — a relaunch fixture can start it mid-flight. */
    private val initialStatus: String = "REQUIRES_PAYMENT_METHOD",
    /** A raw `next_action` object carried by that first GET, for the relaunch fixtures. */
    private val initialAction: String? = null,
    /** The intent's `return_url`, as a merchant supplies it at create time. */
    private val returnUrl: String? = null,
) : UQPayNetworkClient {
    val requests = mutableListOf<UQPayRequest>()
    val posts = mutableListOf<PendingPost>()
    val gets: Int get() = requests.count { it.method == HttpMethod.GET }

    /** The intent as the gateway currently holds it; null until a confirm has answered. */
    @Volatile
    private var currentIntentJson: String? = null

    override suspend fun execute(request: UQPayRequest): UQPayResponse {
        requests += request
        return when (request.method) {
            HttpMethod.GET -> {
                getFailure?.let { throw it }
                val id = request.url.substringAfterLast('/')
                val body = currentIntentJson
                    ?: UiTestFixtures.intentJson(
                        id,
                        initialStatus,
                        methods = methods,
                        rawAction = initialAction,
                        returnUrl = returnUrl,
                    )
                UQPayResponse(200, body, "trace-get", null)
            }
            HttpMethod.POST -> {
                val post = PendingPost(request) { body -> currentIntentJson = body }
                posts += post
                post.await()
            }
        }
    }

    class PendingPost(
        val request: UQPayRequest,
        private val onSettled: (String) -> Unit = {},
    ) {
        private val response = CompletableDeferred<UQPayResponse>()
        var cancelled = false

        /** Answers this confirm, and moves the gateway's intent to what the answer says. */
        fun answer(status: Int, body: String) {
            if (status in 200..299) onSettled(body)
            response.complete(UQPayResponse(status, body, "trace-post", null))
        }

        suspend fun await(): UQPayResponse = try {
            response.await()
        } catch (c: CancellationException) {
            cancelled = true
            throw c
        }
    }
}

internal object UiTestFixtures {
    const val CLIENT_ID = "client-test"
    const val TOKEN = "tok-fixture"
    const val FIXED_NOW = 1_755_500_000_000L

    fun configuration() = UQPayConfiguration(
        clientId = CLIENT_ID,
        environment = Environment.SANDBOX,
        tokenProvider = UQPayTokenProvider { UQPayAuthToken(TOKEN, System.currentTimeMillis() + 30 * 60_000L) },
    )

    fun dependencies(net: UQPayNetworkClient, dispatcher: kotlin.coroutines.CoroutineContext) = SessionDependencies(
        networkClient = net,
        workContext = dispatcher,
        wallClock = { FIXED_NOW },
    )

    /** Wire keys as the gateway sends them (see UQPayApiClientTest). */
    fun intentJson(
        id: String,
        status: String,
        methods: List<String> = listOf("card", "alipaycn"),
        qrAction: Boolean = false,
        rawAction: String? = null,
        returnUrl: String? = null,
    ): String {
        val action = when {
            rawAction != null -> rawAction
            qrAction -> """{"type":"display_qr_code","display_qr_code":{"qr_code_url":"https://example.invalid/qr","expires_at":"2026-01-01T00:00:00Z"}}"""
            else -> null
        }
        val methodsJson = methods.joinToString(",") { "\"$it\"" }
        return buildString {
            append("{")
            append("\"payment_intent_id\":\"$id\",")
            append("\"intent_status\":\"$status\",")
            append("\"amount\":\"8.98\",")
            append("\"currency\":\"SGD\",")
            append("\"merchant_order_id\":\"order-1\",")
            append("\"available_payment_method_types\":[$methodsJson]")
            if (action != null) append(",\"next_action\":$action")
            if (returnUrl != null) append(",\"return_url\":\"$returnUrl\"")
            append("}")
        }
    }
}
