package com.uqpay.sdk.ui

import android.content.Context
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.uqpay.sdk.UQPay
import com.uqpay.sdk.engine.EngineState
import com.uqpay.sdk.engine.PaymentSession
import com.uqpay.sdk.engine.Presentation
import com.uqpay.sdk.payment.PaymentMethodType
import com.uqpay.sdk.payment.PaymentSessionParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `PaymentSessionParams.allowedPaymentMethods` — a merchant restricting which of the
 * intent's methods this payment may use, for a per-region or per-risk-tier rule.
 *
 * The properties that matter are the ones a security control needs rather than the ones a
 * filter needs: it can only ever **narrow**, it survives the trip through the launch parcel
 * (and therefore process death), and it is not quietly widened when it comes out empty.
 *
 * No real card number, key or API secret appears in this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PaymentMethodAllowListTest {

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
        Dispatchers.resetMain()
    }

    // ---- the list the screen draws ----------------------------------------------------------

    @Test
    fun `no allow-list shows every method the intent offers`() = runTest {
        val methods = shownMethods(offered = listOf("card", "alipaycn", "grabpay"), allowed = null)
        assertEquals(
            listOf(PaymentMethodType.CARD, PaymentMethodType.ALIPAY_CN, PaymentMethodType.GRABPAY),
            methods,
        )
    }

    @Test
    fun `an allow-list narrows the list to its intersection with the intent`() = runTest {
        val methods = shownMethods(
            offered = listOf("card", "alipaycn", "grabpay", "paynow"),
            allowed = setOf(PaymentMethodType.CARD, PaymentMethodType.PAYNOW),
        )
        assertEquals(listOf(PaymentMethodType.CARD, PaymentMethodType.PAYNOW), methods)
    }

    /**
     * The gateway's order, card first, is what the sheet shows (G21). A `Set` says nothing
     * about order, so honouring its iteration order would rearrange the sheet according to
     * how the merchant happened to build the set.
     */
    @Test
    fun `the gateway's order is kept, not the allow-list's`() = runTest {
        val methods = shownMethods(
            offered = listOf("card", "alipaycn", "grabpay"),
            allowed = linkedSetOf(PaymentMethodType.GRABPAY, PaymentMethodType.CARD),
        )
        assertEquals(listOf(PaymentMethodType.CARD, PaymentMethodType.GRABPAY), methods)
    }

    /**
     * The allow-list is a restriction. Naming a method the intent does not offer cannot add
     * it: the intent is the authority on what is payable, and a merchant list that has
     * drifted from it must not break a checkout.
     */
    @Test
    fun `naming a method the intent does not offer adds nothing`() = runTest {
        val methods = shownMethods(
            offered = listOf("card"),
            allowed = setOf(PaymentMethodType.CARD, PaymentMethodType.KAKAOPAY),
        )
        assertEquals(listOf(PaymentMethodType.CARD), methods)
    }

    /**
     * An empty allow-list is honoured, and the screen says no methods are available. Widening
     * a restriction because it came out empty is how a risk control becomes a decoration.
     */
    @Test
    fun `an empty allow-list shows nothing rather than everything`() = runTest {
        val methods = shownMethods(offered = listOf("card", "grabpay"), allowed = emptySet())
        assertEquals(emptyList<PaymentMethodType>(), methods)
    }

    @Test
    fun `a method this SDK version cannot render is still hidden`() = runTest {
        val methods = shownMethods(
            offered = listOf("card", "somefuturepay"),
            allowed = setOf(PaymentMethodType.CARD, PaymentMethodType.of("somefuturepay")),
        )
        assertEquals(listOf(PaymentMethodType.CARD), methods)
    }

    /**
     * The screen never draws a forbidden method, so no tap can produce one — but the
     * restriction is checked again where a method becomes an attempt, so a future list, a
     * deep link or a test helper cannot route around it.
     */
    @Test
    fun `selecting a forbidden method does nothing, even when the screen never offered it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val net = ScriptedGateway(methods = listOf("card", "alipaycn"))
        val session = PaymentSession.obtain(INTENT, UiTestFixtures.dependencies(net, dispatcher))
        session.startIfNeeded(Presentation.MethodList)
        val vm = PaymentViewModel(
            INTENT,
            session,
            SavedStateHandle(),
            allowedPaymentMethods = setOf(PaymentMethodType.CARD),
            now = { testScheduler.currentTime },
        )
        runCurrent()

        vm.onMethodSelected(PaymentMethodType.ALIPAY_CN)
        runCurrent()

        assertEquals("no confirm may be sent for a forbidden method", 0, net.posts.size)
        assertTrue(session.state.value is EngineState.SelectingMethod)
    }

    // ---- it survives the launch parcel ---------------------------------------------------------

    @Test
    fun `the allow-list round-trips through the launch parcel`() {
        val allowed = setOf(PaymentMethodType.CARD, PaymentMethodType.PAYNOW)
        val restored = roundTrip(PaymentSessionParams("PI_x", allowedPaymentMethods = allowed))
        assertEquals(allowed, restored.allowedPaymentMethods)
    }

    /**
     * "No restriction" and "restricted to nothing" are different requests and must survive
     * the parcel as different values — if they shared an encoding, a merchant's empty
     * allow-list would come back after process death as no allow-list at all.
     */
    @Test
    fun `absent and empty are distinguishable after a round trip`() {
        assertNull(roundTrip(PaymentSessionParams("PI_x")).allowedPaymentMethods)
        assertEquals(
            emptySet<PaymentMethodType>(),
            roundTrip(PaymentSessionParams("PI_x", allowedPaymentMethods = emptySet()))
                .allowedPaymentMethods,
        )
    }

    @Test
    fun `a method this SDK version predates survives the parcel rather than being dropped`() {
        val future = PaymentMethodType.of("somefuturepay")
        val restored = roundTrip(PaymentSessionParams("PI_x", allowedPaymentMethods = setOf(future)))
        assertEquals(setOf(future), restored.allowedPaymentMethods)
    }

    @Test
    fun `the allow-list takes part in equality and in the redacted toString`() {
        val a = PaymentSessionParams("PI_x", allowedPaymentMethods = setOf(PaymentMethodType.CARD))
        val b = PaymentSessionParams("PI_x", allowedPaymentMethods = setOf(PaymentMethodType.PAYNOW))
        assertTrue(a != b)
        assertTrue(a.toString().contains("allowedPaymentMethods"))
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * Drives a real session to `SelectingMethod` and returns what the method list would draw.
     *
     * The ViewModel is built directly rather than through the Activity so this file stays
     * about the filter; the Activity's own half — refusing a presentation that contradicts
     * the list — is asserted in `UQPayPaymentActivityTest`.
     */
    private fun TestScope.shownMethods(
        offered: List<String>,
        allowed: Set<PaymentMethodType>?,
    ): List<PaymentMethodType> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val net = ScriptedGateway(methods = offered)
        val session = PaymentSession.obtain(INTENT, UiTestFixtures.dependencies(net, dispatcher))
        session.startIfNeeded(Presentation.MethodList)
        val vm = PaymentViewModel(
            INTENT,
            session,
            SavedStateHandle(),
            allowedPaymentMethods = allowed,
            now = { testScheduler.currentTime },
        )
        runCurrent()
        val state = vm.uiState.value
        assertTrue("expected the method list, was $state", state is PaymentUiState.MethodList)
        return (state as PaymentUiState.MethodList).methods
    }

    private fun roundTrip(params: PaymentSessionParams): PaymentSessionParams {
        val parcel = Parcel.obtain()
        return try {
            params.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            PaymentSessionParams.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private companion object {
        const val INTENT = "PI_allow_list_test"
    }
}
