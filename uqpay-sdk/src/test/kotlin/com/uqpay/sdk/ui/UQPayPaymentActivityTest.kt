package com.uqpay.sdk.ui

import com.uqpay.sdk.testErrorCopy
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.webkit.CookieManager
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.uqpay.sdk.UQPay
import com.uqpay.sdk.engine.EngineState
import com.uqpay.sdk.engine.PaymentSession
import com.uqpay.sdk.error.UQPayErrorCode
import com.uqpay.sdk.launcher.UQPayPaymentContract
import com.uqpay.sdk.payment.PaymentMethodType
import com.uqpay.sdk.payment.PaymentResult
import com.uqpay.sdk.payment.PaymentSessionParams
import com.uqpay.sdk.payment.PaymentStatus
import com.uqpay.sdk.ui.threeds.ThreeDsBrowsingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowCookieManager
import java.io.IOException
import java.time.Duration

/**
 * The host Activity's disaster cases, end to end: a real [PaymentSession] (built through the
 * production composition root, faked only at the socket), the real ViewModel, the real
 * Compose content, `ActivityScenario` for lifecycle, and the real [UQPayPaymentContract] to
 * parse what the merchant would receive.
 *
 * ### How the socket fake reaches the Activity
 *
 * The Activity calls `PaymentSession.obtain(id)` with production dependencies. Each test
 * obtains the session **first**, with the scripted gateway — and because `obtain` returns
 * the running session for an id, the Activity re-attaches to the test's session exactly as
 * a rotated Activity re-attaches to a live one. No test hook on the Activity is needed, and
 * the re-attach path is exercised by every test as a side effect.
 *
 * ### Two clocks, deliberately
 *
 * The engine runs on a test scheduler ([pump] drives it). The ViewModel's blocked window
 * runs on Android's main looper and `SystemClock.elapsedRealtime` — Robolectric's paused
 * looper advances both together under `idleFor`, which is how [advanceWallClock] passes
 * the bound without waiting ten real seconds.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class UQPayPaymentActivityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val contract = UQPayPaymentContract(testErrorCopy())
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    @Before
    fun setUp() {
        // The screen's progress indicators are *indeterminate*: Compose drives them from an
        // InfiniteTransition, which asks the frame clock for a frame, forever. Robolectric's
        // choreographer serves those frames by advancing its own clock from inside
        // `Looper.idle()` — so idling a window that shows a spinner never returns, and a bare
        // `ActivityScenario.launch` renders animation frames until the machine gives out.
        // Compose's own test infrastructure solves this with an [InfiniteAnimationPolicy] in
        // the recomposer's context; this class installs the same thing, because these tests
        // launch the Activity themselves rather than through a compose rule.
        //
        // Only endless animations are affected: composition, layout, state reads and every
        // one-shot effect run exactly as they do in production.
        WindowRecomposerPolicy.setFactory { view ->
            view.createLifecycleAwareWindowRecomposer(NoEndlessAnimations)
        }
        PaymentSession.clearAllForTest()
        UQPay.initialize(context, UiTestFixtures.configuration())
    }

    @After
    fun tearDown() {
        WindowRecomposerPolicy.setFactory(WindowRecomposerFactory.LifecycleAware)
        PaymentSession.clearAllForTest()
        UQPay.resetForTest()
        // Both are process-global in production and static under Robolectric.
        ShadowCookieManager.resetCookies()
        ThreeDsBrowsingState.forgetAllForTest()
    }

    // ---- predictive back (Slice 6, item 10) --------------------------------------------
    //
    // Escalated from Slice 3: this class used to run at API 24 only, because the library
    // manifest declares no `targetSdk` and Robolectric then falls back to `minSdk`. So §2c's
    // blocked back-press — the rule that stops a customer leaving mid-confirm — had never
    // once been exercised under predictive back. `@Config(sdk = [24, 34])` on this class runs
    // every test at both ends of the supported range, and `testOptions.targetSdk` in
    // uqpay-sdk/build.gradle.kts makes the 34 run a *modern* one.

    @Test
    fun `the ui tests run against a modern target sdk, so predictive back is in play`() {
        assertTrue(
            "targetSdkVersion is ${context.applicationInfo.targetSdkVersion}. Predictive back needs 33+; " +
                "if this regressed, testOptions.targetSdk was dropped from uqpay-sdk/build.gradle.kts and " +
                "the back-press rules are silently only being tested on the legacy path again.",
            context.applicationInfo.targetSdkVersion >= 33,
        )
    }

    /**
     * The **platform's** back entry point, not the AndroidX dispatcher directly.
     *
     * On API 33+ the system no longer calls `Activity.onBackPressed()`; it invokes the
     * `OnBackInvokedCallback` that `ComponentActivity` registers, which then drives the same
     * dispatcher. Every other back test in this class calls the dispatcher, which would keep
     * passing even if the Activity had stopped being reachable from the platform at all.
     * This one goes in one level higher, at both API levels.
     */
    @Test
    fun `a platform back-press reaches the same rule at both api levels`() {
        attach(INTENT)
        val scenario = launch(INTENT)
        pump()

        scenario.onActivity {
            @Suppress("DEPRECATION")
            it.onBackPressed()
        }
        pump()

        val result = deliveredResult(scenario)
        assertEquals(PaymentStatus.CANCELLED, result.status)
        assertEquals(INTENT, result.paymentIntentId)
    }

    // ---- attach and load ---------------------------------------------------------------

    @Test
    fun `launch attaches, loads the intent once, and shows the method list`() {
        val (net, session) = attach(INTENT)
        val scenario = launch(INTENT)
        pump()
        assertTrue(session.hasStarted)
        assertEquals(1, net.gets)
        scenario.onActivity { activity ->
            val list = activity.viewModel.uiState.value as PaymentUiState.MethodList
            assertEquals(listOf(PaymentMethodType.CARD, PaymentMethodType.ALIPAY_CN), list.methods)
        }
        assertFalse(scenario.state == Lifecycle.State.DESTROYED)
        scenario.close()
    }

    // ---- a second launch while one is on screen (audit item 7) -------------------------

    /**
     * **The crossed-result bug, from the Activity's side.**
     *
     * The payment Activity used to be `singleTop`. A second `launch()` while an instance was
     * up therefore reused it, and reuse skips `onCreate` entirely — so the new launch
     * parameters went to `onNewIntent`, which did not exist, and were discarded. The customer
     * carried on paying the *first* intent while the merchant's second launch waited for a
     * result that could only ever describe the first one: the wrong order marked paid, which
     * is a direct contradiction of `UQPayPaymentLauncher.launch`'s own contract and of
     * AC §7.1.
     *
     * The launch mode is now the default, so this cannot arise through the SDK's own Intent.
     * The handler stays as a backstop for anyone who reintroduces an instance-reusing launch
     * mode or flag, and this test drives it directly: a second payment delivered to a live
     * instance must change nothing about the payment already on screen.
     */
    @Test
    fun `a re-launch for a different intent never hijacks the payment on screen`() {
        val (net, session) = attach(INTENT)
        val scenario = launch(INTENT)
        pump()
        val readsBefore = net.gets

        scenario.onActivity { activity ->
            activity.onNewIntent(contract.createIntent(context, PaymentSessionParams(OTHER_INTENT)))
        }
        pump()

        scenario.onActivity { activity ->
            assertEquals("the Activity adopted a different payment", INTENT, activity.viewModel.paymentIntentId)
            assertFalse("a second payment must not end the first", activity.isFinishing)
        }
        assertSame("the session on screen must be untouched", session, PaymentSession.peek(INTENT))
        assertNull("nothing may be built for the discarded launch", PaymentSession.peek(OTHER_INTENT))
        assertEquals("the discarded launch must not have read anything", readsBefore, net.gets)

        // And the payment on screen still ends as its own payment, with its own id.
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        pump()
        assertEquals(INTENT, deliveredResult(scenario).paymentIntentId)
    }

    /** A re-launch of the *same* payment is the harmless case: the running sheet keeps it. */
    @Test
    fun `a re-launch for the same intent leaves the running payment alone`() {
        val (net, session) = attach(INTENT)
        val scenario = launch(INTENT)
        pump()
        val readsBefore = net.gets

        scenario.onActivity { activity ->
            activity.onNewIntent(contract.createIntent(context, PaymentSessionParams(INTENT)))
        }
        pump()

        assertSame(session, PaymentSession.peek(INTENT))
        assertEquals("no second load for a re-launch of the same payment", readsBefore, net.gets)
        scenario.onActivity { assertFalse(it.isFinishing) }
    }

    // ---- rotation mid-payment ----------------------------------------------------------

    /**
     * The disaster the session registry exists for, seen from the Activity: recreated
     * mid-confirm, it finds the same session and the same engine, loads nothing, sends
     * nothing, and shows the confirm still in flight.
     */
    @Test
    fun `rotation mid-confirm re-attaches - same session, no second load, no second confirm`() {
        val (net, session) = attach(INTENT)
        val scenario = launch(INTENT)
        pump()
        scenario.onActivity { it.viewModel.onMethodSelected(PaymentMethodType.ALIPAY_CN) }
        pump()
        assertTrue(session.engine.isConfirmInFlight)
        assertEquals(1, net.posts.size)
        val readsBefore = net.gets

        scenario.recreate()
        pump()

        assertSame("the recreated Activity found the same session", session, PaymentSession.peek(INTENT))
        assertEquals("no second intent read on re-attach", readsBefore, net.gets)
        assertEquals("still exactly one confirm in the air", 1, net.posts.size)
        assertFalse(net.posts[0].cancelled)
        assertTrue(session.engine.isConfirmInFlight)
        scenario.onActivity { activity ->
            assertEquals(
                PaymentUiState.Confirming(PaymentMethodType.ALIPAY_CN, leaveBlocked = false),
                activity.viewModel.uiState.value,
            )
            assertFalse(activity.isFinishing)
        }

        // The one confirm answers; the recreated Activity delivers the one result.
        net.posts[0].answer(200, UiTestFixtures.intentJson(INTENT, "SUCCEEDED"))
        pump()
        val result = deliveredResult(scenario)
        assertEquals(PaymentStatus.SUCCEEDED, result.status)
        assertEquals(INTENT, result.paymentIntentId)
    }

    @Test
    fun `release happens only when finishing for good - never on rotation`() {
        val (_, session) = attach(INTENT)
        val scenario = launch(INTENT)
        pump()

        scenario.recreate()
        pump()
        assertSame("rotation must not release the session", session, PaymentSession.peek(INTENT))
        assertTrue(session.isActive)

        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        pump()
        deliveredResult(scenario)
        scenario.onActivity { assertTrue("the back-press is finishing for good", it.isFinishing) }

        // The release happens in onDestroy, and a finishing Activity is destroyed by the OS,
        // not by `finish()` itself — Robolectric leaves it resumed until the scenario is
        // closed. Closing here is that destruction, with `isChangingConfigurations` false;
        // the rotation above is the same callback with it true, and released nothing.
        scenario.close()
        pump()
        assertNull("finishing for good releases the session", PaymentSession.peek(INTENT))
    }

    // ---- rotation during 3-D Secure (B1) -----------------------------------------------

    /**
     * The blocker this section exists for.
     *
     * Turning the phone during the issuer's challenge used to delete every cookie in the
     * process — the WebView's teardown and the composable's disposal each wiped the jar — so
     * the recreated screen reloaded the same challenge with no ACS session, the issuer
     * rejected it, the poller ran to its budget and the payment settled `PENDING` for a card
     * the customer was actively verifying. Nothing about that is visible from the engine's
     * side, which is why the engine's own rotation test above passed throughout.
     *
     * The cookie set here stands in for the ACS session cookie; the second one stands in for
     * the merchant's own web views, which the old clear signed out on every card payment.
     */
    @Test
    fun `rotating during 3-D Secure keeps the ACS session, and only the payment's end clears it`() {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setCookie(ACS_URL, "ACSSESSION=live-session")
        cookies.setCookie(HOST_APP_URL, "merchant_login=keep-me")

        attach(INTENT, initialStatus = "REQUIRES_CUSTOMER_ACTION", initialAction = ACS_ACTION)
        val scenario = launch(INTENT)
        pump()
        scenario.onActivity {
            assertTrue(
                "the fixture must actually reach the 3DS screen, or this proves nothing",
                it.viewModel.uiState.value is PaymentUiState.ThreeDs,
            )
        }
        // Recorded here rather than left to the composition: Robolectric lays views out only
        // under a compose rule, so whether the AndroidView is realised varies with how these
        // tests are run, and a test whose subject sometimes does not exist is a test that
        // sometimes passes for no reason. That the *screen* records its origins is proved
        // deterministically by ThreeDsScreenTest, under a rule that does lay out. What this
        // test owns is the lifecycle: which callbacks may clear, and which must not.
        ThreeDsBrowsingState.record(INTENT, ACS_URL)

        scenario.recreate()
        pump()

        assertTrue(
            "the challenge cannot be completed without the session the ACS set between the " +
                "fingerprint step and the challenge",
            cookies.getCookie(ACS_URL).orEmpty().contains("ACSSESSION=live-session"),
        )
        scenario.onActivity {
            assertTrue("the recreated screen shows the challenge again", it.viewModel.uiState.value is PaymentUiState.ThreeDs)
            assertFalse(it.isFinishing)
        }

        // Now end the payment for good. This — and only this — is where the session goes.
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        pump()
        assertEquals(PaymentStatus.PENDING, deliveredResult(scenario).status)
        scenario.close()
        pump()

        assertFalse(
            "the issuer's session is authentication state for a payment that is over",
            cookies.getCookie(ACS_URL).orEmpty().contains("live-session"),
        )
        assertTrue(
            "a payment SDK has no business deleting cookies it did not create",
            cookies.getCookie(HOST_APP_URL).orEmpty().contains("merchant_login=keep-me"),
        )
    }

    /**
     * System-initiated destruction is not the end of a payment (fix note item 4).
     *
     * When Android destroys the Activity to reclaim memory mid-challenge, `isFinishing` is
     * false and nothing may be cleared: the relaunch re-adopts the in-flight action and
     * re-shows the challenge, which can only be completed if its session survived. Driven
     * through an `ActivityController` rather than `ActivityScenario` because the scenario's
     * DESTROYED transition calls `finish()` — which is the *other* case.
     */
    @Test
    fun `a destroy that is not a finish leaves the 3DS session intact for the relaunch`() {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setCookie(ACS_URL, "ACSSESSION=live-session")

        attach(INTENT, initialStatus = "REQUIRES_CUSTOMER_ACTION", initialAction = ACS_ACTION)
        val controller = Robolectric.buildActivity(
            UQPayPaymentActivity::class.java,
            contract.createIntent(context, PaymentSessionParams(INTENT)),
        ).setup()
        pump()
        // Not vacuous: the Activity really did attach to the running payment and reach the
        // challenge, so its onDestroy has both a session and a 3DS origin to act on. The
        // origin is recorded here rather than left to the composition because Robolectric
        // lays out no views at API 24; that the *screen* records it is proved end to end by
        // the rotation test above, whose final clear only works if it did.
        assertTrue(controller.get().viewModel.uiState.value is PaymentUiState.ThreeDs)
        ThreeDsBrowsingState.record(INTENT, ACS_URL)

        controller.destroy()
        pump()

        assertFalse("a reclaimed Activity is not a finished one", controller.get().isFinishing)
        assertTrue(
            "an Activity killed mid-challenge is the recovery case, not the end of the payment",
            cookies.getCookie(ACS_URL).orEmpty().contains("ACSSESSION=live-session"),
        )
        assertTrue(
            "the origins are kept too, so the eventual end of the payment still has them",
            ThreeDsBrowsingState.visitedUrls(INTENT).isNotEmpty(),
        )
        assertNotNull("and the engine is kept for the relaunch, by the same predicate", PaymentSession.peek(INTENT))
    }

    // ---- back-press (§2c) --------------------------------------------------------------

    @Test
    fun `back with nothing in the air delivers CANCELLED with the intent id`() {
        attach(INTENT)
        val scenario = launch(INTENT)
        pump()
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        pump()
        val result = deliveredResult(scenario)
        assertEquals(PaymentStatus.CANCELLED, result.status)
        assertEquals(INTENT, result.paymentIntentId)
    }

    @Test
    fun `back while confirming is blocked - visible, then PENDING at the bound with the intent id`() {
        val (net, session) = attach(INTENT)
        val scenario = launch(INTENT)
        pump()
        scenario.onActivity { it.viewModel.onMethodSelected(PaymentMethodType.ALIPAY_CN) }
        pump()
        assertTrue(session.engine.isConfirmInFlight)

        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        pump()
        scenario.onActivity { activity ->
            assertFalse("blocked: the Activity must not be finishing", activity.isFinishing)
            assertEquals(
                PaymentUiState.Confirming(PaymentMethodType.ALIPAY_CN, leaveBlocked = true),
                activity.viewModel.uiState.value,
            )
        }
        assertFalse(session.state.value is EngineState.Terminal)

        // Rotation inside the window does not reset it.
        advanceWallClock(6_000L)
        scenario.recreate()
        pump()
        scenario.onActivity { activity ->
            assertTrue(activity.viewModel.blockedWindowRemainingMillis() <= PaymentViewModel.BLOCKED_WINDOW_MILLIS - 6_000L)
            assertFalse(activity.isFinishing)
        }

        advanceWallClock(4_500L)
        pump()
        val result = deliveredResult(scenario)
        assertEquals("unresolved at the bound is PENDING, never CANCELLED", PaymentStatus.PENDING, result.status)
        assertEquals(INTENT, result.paymentIntentId)
        assertEquals(UQPayErrorCode.TIMEOUT, result.error?.code)
        assertFalse("the attempt keeps running in the session as the reconciler", net.posts[0].cancelled)
    }

    @Test
    fun `back during a customer action delivers PENDING`() {
        val (net, _) = attach(INTENT)
        val scenario = launch(INTENT)
        pump()
        scenario.onActivity { it.viewModel.onMethodSelected(PaymentMethodType.ALIPAY_CN) }
        pump()
        net.posts[0].answer(200, UiTestFixtures.intentJson(INTENT, "REQUIRES_CUSTOMER_ACTION", qrAction = true))
        pump()
        // Slice 5 replaced the QR placeholder with the real screen's state.
        scenario.onActivity { assertTrue(it.viewModel.uiState.value is PaymentUiState.WalletQr) }

        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        pump()
        val result = deliveredResult(scenario)
        assertEquals(PaymentStatus.PENDING, result.status)
        assertEquals(INTENT, result.paymentIntentId)
    }

    @Test
    fun `back during polling delivers PENDING`() {
        val (net, _) = attach(INTENT)
        val scenario = launch(INTENT)
        pump()
        scenario.onActivity { it.viewModel.onMethodSelected(PaymentMethodType.ALIPAY_CN) }
        pump()
        net.posts[0].answer(200, UiTestFixtures.intentJson(INTENT, "PENDING"))
        pump()
        scenario.onActivity { assertEquals(PaymentUiState.Polling, it.viewModel.uiState.value) }

        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        pump()
        val result = deliveredResult(scenario)
        assertEquals(PaymentStatus.PENDING, result.status)
        assertEquals(INTENT, result.paymentIntentId)
    }

    @Test
    fun `a back-press storm delivers exactly one result`() {
        attach(INTENT)
        val scenario = launch(INTENT)
        pump()
        scenario.onActivity { activity ->
            repeat(5) { activity.onBackPressedDispatcher.onBackPressed() }
        }
        pump()
        val result = deliveredResult(scenario)
        assertEquals(PaymentStatus.CANCELLED, result.status)
        assertEquals(INTENT, result.paymentIntentId)
    }

    // ---- recreated after Terminal ------------------------------------------------------

    @Test
    fun `an Activity that finds the session already terminal delivers that result and finishes`() {
        val (_, session) = attach(INTENT)
        session.startIfNeeded()
        pump()
        session.engine.cancel()
        assertTrue(session.state.value is EngineState.Terminal)

        val scenario = launch(INTENT)
        pump()
        val result = deliveredResult(scenario)
        assertEquals(PaymentStatus.CANCELLED, result.status)
        assertEquals(INTENT, result.paymentIntentId)
        assertNull(PaymentSession.peek(INTENT))
    }

    // ---- bad launches never crash the host ---------------------------------------------

    @Test
    fun `garbled args - no extras - finishes RESULT_CANCELED without crashing`() {
        val scenario = ActivityScenario.launchActivityForResult<UQPayPaymentActivity>(Intent(context, UQPayPaymentActivity::class.java))
        pump()
        val result = scenario.result
        assertEquals(Activity.RESULT_CANCELED, result.resultCode)
        // The one documented blank-id case: there was no intent id to report.
        val parsed = contract.parseResult(result.resultCode, result.resultData)
        assertEquals(PaymentStatus.CANCELLED, parsed.status)
        assertEquals(0, PaymentSession.activeCount)
    }

    @Test
    fun `garbled args - wrong type under the params key - finishes RESULT_CANCELED without crashing`() {
        val intent = Intent(context, UQPayPaymentActivity::class.java)
            .putExtra(UQPayPaymentContract.EXTRA_PARAMS, "not-params")
        val scenario = ActivityScenario.launchActivityForResult<UQPayPaymentActivity>(intent)
        pump()
        assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
    }

    @Test
    fun `a blank intent id from the merchant is FAILED INVALID_CONFIGURATION, not a crash`() {
        val scenario = launch("")
        pump()
        val result = deliveredResult(scenario)
        assertEquals(PaymentStatus.FAILED, result.status)
        assertEquals(UQPayErrorCode.INVALID_CONFIGURATION, result.error?.code)
        assertEquals(0, PaymentSession.activeCount)
    }

    @Test
    fun `SDK not initialized delivers FAILED NOT_INITIALIZED with the intent id, never a crash`() {
        UQPay.resetForTest()
        val scenario = launch(INTENT)
        pump()
        val result = deliveredResult(scenario)
        assertEquals(PaymentStatus.FAILED, result.status)
        assertEquals(UQPayErrorCode.NOT_INITIALIZED, result.error?.code)
        assertEquals(INTENT, result.paymentIntentId)
        assertEquals(0, PaymentSession.activeCount)
    }

    @Test
    fun `a load failure delivers FAILED with the intent id rather than a dead screen`() {
        attach(INTENT, getFailure = IOException("no route"))
        val scenario = launch(INTENT)
        pumpUntilIdle()
        val result = deliveredResult(scenario)
        assertEquals(PaymentStatus.FAILED, result.status)
        assertEquals(INTENT, result.paymentIntentId)
        assertNotNull(result.error)
    }

    // ---- presentations (G19) -----------------------------------------------------------

    @Test
    fun `single-wallet presentation confirms the wallet without showing a list`() {
        val (net, _) = attach(INTENT)
        val scenario = launch(INTENT, PaymentSessionParams.Presentation.SingleWallet(PaymentMethodType.GRABPAY))
        pump()
        assertEquals(1, net.posts.size)
        assertTrue(net.posts[0].request.body.orEmpty().contains("\"type\":\"grabpay\""))
        scenario.onActivity {
            assertEquals(PaymentUiState.Confirming(PaymentMethodType.GRABPAY, leaveBlocked = false), it.viewModel.uiState.value)
        }
        scenario.close()
    }

    @Test
    fun `card-only presentation opens the card placeholder and back cancels`() {
        attach(INTENT)
        val scenario = launch(INTENT, PaymentSessionParams.Presentation.CardOnly)
        pump()
        scenario.onActivity {
            assertEquals(PaymentUiState.CardPlaceholder(canReturnToList = false), it.viewModel.uiState.value)
            it.onBackPressedDispatcher.onBackPressed()
        }
        pump()
        assertEquals(PaymentStatus.CANCELLED, deliveredResult(scenario).status)
    }

    // ---- F3 sweep ----------------------------------------------------------------------

    /**
     * Every exit path the Activity has — cancel idle, cancel with an attempt in the air,
     * a terminal outcome, a load failure, an uninitialised SDK — parsed through the real
     * contract, carries a non-blank `paymentIntentId`. The garbled-args exit is the one
     * documented exception and is asserted separately above.
     */
    @Test
    fun `F3 - every exit path names its payment`() {
        val results = mutableMapOf<String, PaymentResult>()

        // 1. Cancel with nothing in the air.
        run {
            val id = "PI_f3_idle"
            attach(id)
            val scenario = launch(id)
            pump()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            pump()
            results["cancel idle"] = deliveredResult(scenario)
        }
        // 2. Cancel with an attempt in the air (blocked window expiring).
        run {
            val id = "PI_f3_air"
            attach(id)
            val scenario = launch(id)
            pump()
            scenario.onActivity { it.viewModel.onMethodSelected(PaymentMethodType.ALIPAY_CN) }
            pump()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            advanceWallClock(PaymentViewModel.BLOCKED_WINDOW_MILLIS + 500L)
            pump()
            results["cancel in air"] = deliveredResult(scenario)
        }
        // 3. Terminal outcome from the gateway.
        run {
            val id = "PI_f3_terminal"
            val (net, _) = attach(id)
            val scenario = launch(id)
            pump()
            scenario.onActivity { it.viewModel.onMethodSelected(PaymentMethodType.ALIPAY_CN) }
            pump()
            net.posts[0].answer(200, UiTestFixtures.intentJson(id, "SUCCEEDED"))
            pump()
            results["terminal"] = deliveredResult(scenario)
        }
        // 4. Load failure.
        run {
            val id = "PI_f3_load"
            attach(id, getFailure = IOException("down"))
            val scenario = launch(id)
            pumpUntilIdle()
            results["load failure"] = deliveredResult(scenario)
        }
        // 5. Not initialised.
        run {
            val id = "PI_f3_uninit"
            UQPay.resetForTest()
            val scenario = launch(id)
            pump()
            results["not initialized"] = deliveredResult(scenario)
        }

        assertEquals(5, results.size)
        results.forEach { (path, result) ->
            assertTrue("$path delivered a blank paymentIntentId", result.paymentIntentId.isNotBlank())
        }
        assertEquals(PaymentStatus.CANCELLED, results["cancel idle"]?.status)
        assertEquals(PaymentStatus.PENDING, results["cancel in air"]?.status)
        assertEquals(PaymentStatus.SUCCEEDED, results["terminal"]?.status)
        assertEquals(PaymentStatus.FAILED, results["load failure"]?.status)
        assertEquals(PaymentStatus.FAILED, results["not initialized"]?.status)
    }

    /** The result Intent carries the id separately from the parcelled result — the F3 extra. */
    @Test
    fun `the result Intent stamps EXTRA_INTENT_ID independently of the parcelled result`() {
        attach(INTENT)
        val scenario = launch(INTENT)
        pump()
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        pump()
        val data = scenario.result.resultData
        assertEquals(INTENT, data.getStringExtra(UQPayPaymentContract.EXTRA_INTENT_ID))
        // Even with the parcelled result stripped, the contract still names the payment.
        val stripped = Intent().putExtra(UQPayPaymentContract.EXTRA_INTENT_ID, data.getStringExtra(UQPayPaymentContract.EXTRA_INTENT_ID))
        assertEquals(INTENT, contract.parseResult(Activity.RESULT_OK, stripped).paymentIntentId)
    }

    // ---- harness -----------------------------------------------------------------------

    /**
     * Builds the session for [id] with the scripted gateway **before** the Activity launches,
     * so the Activity's own `obtain` re-attaches to it. Not started: the Activity starts it.
     */
    // ---- foreground re-read (Slice 6, item 4 · AC 8.1) ---------------------------------

    /**
     * The customer leaves for their wallet or banking app and comes back. `onStart` must ask
     * the poll waiting on its 2-second tick to look now — once — and must not leave a second
     * timer behind.
     *
     * `androidx.lifecycle:lifecycle-process` is not on this SDK's classpath and was
     * deliberately not added; the Activity's own start covers this case, because a customer
     * returning from another app necessarily restarts this screen.
     */
    @Test
    fun `returning to the foreground re-reads the intent once`() {
        val (net, _) = attach(
            INTENT,
            initialStatus = "REQUIRES_CUSTOMER_ACTION",
            initialAction = """{"type":"display_qr_code","display_qr_code":{"qr_code_url":"https://example.invalid/qr","expires_at":null}}""",
        )
        val scenario = launch(INTENT)
        pump()
        val afterLaunch = net.gets
        assertTrue("the adopted action must be being polled", afterLaunch >= 2)

        // Away to the wallet app, and back.
        scenario.moveToState(Lifecycle.State.CREATED)
        pump()
        assertEquals("stopping reads nothing", afterLaunch, net.gets)
        scenario.moveToState(Lifecycle.State.RESUMED)
        pump()
        assertEquals("exactly one immediate re-read on return", afterLaunch + 1, net.gets)
    }

    private fun attach(
        id: String,
        getFailure: Throwable? = null,
        initialStatus: String = "REQUIRES_PAYMENT_METHOD",
        initialAction: String? = null,
    ): Pair<ScriptedGateway, PaymentSession> {
        val net = ScriptedGateway(getFailure = getFailure, initialStatus = initialStatus, initialAction = initialAction)
        val session = PaymentSession.obtain(id, UiTestFixtures.dependencies(net, dispatcher))
        return net to session
    }

    /**
     * Launches the host the way the merchant's app does — **for a result**. `launch` alone
     * would start the Activity without anywhere to put `setResult`, and every assertion here
     * is ultimately about what the merchant receives.
     */
    private fun launch(
        id: String,
        presentation: PaymentSessionParams.Presentation = PaymentSessionParams.Presentation.MethodList,
    ): ActivityScenario<UQPayPaymentActivity> =
        ActivityScenario.launchActivityForResult(contract.createIntent(context, PaymentSessionParams(id, presentation)))

    /**
     * A presentation that names a method the allow-list forbids, for the same launch helper
     * every other test uses.
     */
    private fun launchWithAllowList(
        id: String,
        presentation: PaymentSessionParams.Presentation,
        allowed: Set<PaymentMethodType>?,
    ): ActivityScenario<UQPayPaymentActivity> =
        ActivityScenario.launchActivityForResult(
            contract.createIntent(
                context,
                PaymentSessionParams(id, presentation, allowedPaymentMethods = allowed),
            ),
        )

    /** Runs the engine's pending work and the main looper's, three times, so cross-dispatches settle. */
    private fun pump() {
        repeat(3) {
            scheduler.runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** Like [pump] but lets the engine's own timed work (retry backoff, polls) run out. */
    private fun pumpUntilIdle() {
        repeat(3) {
            scheduler.advanceUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** Advances Android's clock and main looper together — the ViewModel's blocked window. */
    private fun advanceWallClock(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
        pump()
    }

    /** What the merchant would receive: the Activity's result through the real contract. */
    private fun deliveredResult(scenario: ActivityScenario<UQPayPaymentActivity>): PaymentResult {
        val result = scenario.result
        assertEquals("every real exit is RESULT_OK with data", Activity.RESULT_OK, result.resultCode)
        return contract.parseResult(result.resultCode, result.resultData)
    }

    // ---- the payment-method allow-list -------------------------------------------------
    //
    // The allow-list is a merchant's risk control, so the Activity's job is to refuse a
    // launch that contradicts it rather than to quietly honour one half of it. Caught before
    // a session exists, so no intent is read and no byte goes to the gateway.

    @Test
    fun `a card-only presentation with card excluded fails before any network call`() {
        val (net, _) = attach(INTENT)
        val scenario = launchWithAllowList(
            INTENT,
            PaymentSessionParams.Presentation.CardOnly,
            setOf(PaymentMethodType.PAYNOW),
        )
        pump()

        val result = deliveredResult(scenario)
        assertEquals(PaymentStatus.FAILED, result.status)
        assertEquals(UQPayErrorCode.INVALID_PAYMENT_METHOD, result.error?.code)
        assertEquals("the payment must be named even on this exit", INTENT, result.paymentIntentId)
        assertEquals("no request may be made for a launch that contradicts itself", 0, net.requests.size)

        // The shopper's sentence never names the merchant's mistake; the developer's does.
        assertFalse(result.error!!.message.contains("allowedPaymentMethods"))
        assertTrue(result.error!!.developerMessage.orEmpty().contains("allowedPaymentMethods"))
    }

    @Test
    fun `a single-wallet presentation with that wallet excluded fails the same way`() {
        val (net, _) = attach(INTENT)
        val scenario = launchWithAllowList(
            INTENT,
            PaymentSessionParams.Presentation.SingleWallet(PaymentMethodType.GRABPAY),
            setOf(PaymentMethodType.CARD),
        )
        pump()

        val result = deliveredResult(scenario)
        assertEquals(PaymentStatus.FAILED, result.status)
        assertEquals(UQPayErrorCode.INVALID_PAYMENT_METHOD, result.error?.code)
        assertEquals(0, net.requests.size)
        assertTrue(result.error!!.developerMessage.orEmpty().contains("grabpay"))
    }

    /**
     * `MethodList` names no method, so it can never contradict the allow-list — not even an
     * empty one, which simply produces an empty list on screen. A launch that started reading
     * the intent is a launch that was not refused.
     */
    @Test
    fun `a method-list presentation is never refused, even against an empty allow-list`() {
        val (net, _) = attach(INTENT)
        launchWithAllowList(INTENT, PaymentSessionParams.Presentation.MethodList, emptySet())
        pump()

        assertTrue("the intent must still be read", net.requests.isNotEmpty())
    }

    @Test
    fun `a presentation the allow-list permits is launched normally`() {
        val (net, _) = attach(INTENT)
        launchWithAllowList(
            INTENT,
            PaymentSessionParams.Presentation.CardOnly,
            setOf(PaymentMethodType.CARD, PaymentMethodType.PAYNOW),
        )
        pump()

        assertTrue("the intent must still be read", net.requests.isNotEmpty())
    }

    private companion object {
        const val INTENT = "PI_activity_test"

        /** A second, unrelated payment — the one a re-launch must never be answered with. */
        const val OTHER_INTENT = "PI_activity_test_other"
        const val ACS_URL = "https://acs.example.invalid/challenge/abc"
        const val ACS_ACTION =
            """{"type":"redirect_to_url","redirect_to_url":{"url":"$ACS_URL"}}"""
        /** A page belonging to the merchant's own app. The 3DS step never goes near it. */
        const val HOST_APP_URL = "https://shop.example.invalid/account"
    }
}

/**
 * An [InfiniteAnimationPolicy] that never lets an endless animation have its frame — the
 * same trick Compose's own test infrastructure uses. Installed for [UQPayPaymentActivityTest]
 * only, and only because these tests drive `ActivityScenario` directly; the screen's
 * spinners are exercised by `PaymentScreenTest` through a compose rule.
 */
private object NoEndlessAnimations : InfiniteAnimationPolicy {
    override suspend fun <R> onInfiniteOperation(block: suspend () -> R): R = awaitCancellation()
}
