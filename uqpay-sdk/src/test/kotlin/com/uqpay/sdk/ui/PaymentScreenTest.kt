package com.uqpay.sdk.ui

import com.uqpay.sdk.auth.UQPayTokenProvider
import com.uqpay.sdk.auth.UQPayAuthToken
import com.uqpay.sdk.UQPayConfiguration
import com.uqpay.sdk.UQPay
import com.uqpay.sdk.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.uqpay.sdk.engine.ConfirmPayload
import com.uqpay.sdk.payment.PaymentMethodType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Compose shell, state by state: what each state shows, what it lets the customer tap,
 * and — for the blocked confirm — what it deliberately does not. Content descriptions are
 * asserted because they are both the accessibility contract and the way these tests find
 * things; a missing one fails here before it fails a screen reader.
 */
@RunWith(RobolectricTestRunner::class)
// Amounts are rendered by the platform's currency formatter, which is locale-dependent by
// design (SGD is "SGD8.98" in en-US and "8,98 SGD" in de-DE). Pinning the locale here keeps
// the assertions below about *what is drawn* rather than about the machine running them;
// AmountFormatTest is where the per-locale behaviour itself is checked.
@Config(qualifiers = "en-rUS")
class PaymentScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val selected = mutableListOf<PaymentMethodType>()
    private var returnedToList = 0
    private var cancelled = 0
    private var closed = 0
    private val cardPayloads = mutableListOf<ConfirmPayload.Card>()
    private var threeDsReturns = 0

    private fun show(state: PaymentUiState) {
        compose.setContent {
            UqpayTheme {
                PaymentScreen(
                    state = state,
                    paymentIntentId = INTENT,
                    onMethodSelected = { selected += it },
                    // Slice 4 routed the card branch to the real form and the 3DS branch to
                    // the WebView host; both are covered by CardFormTest / ThreeDsScreenTest.
                    onCardSubmitted = { cardPayloads += it },
                    onThreeDsReturned = { threeDsReturns++ },
                    onReturnToList = { returnedToList++ },
                    onCancel = { cancelled++ },
                    onClose = { closed++ },
                )
            }
        }
    }

    @Test
    fun `method list shows the offered methods in order and taps select them`() {
        show(
            PaymentUiState.MethodList(
                amount = "8.98",
                currency = "SGD",
                merchantOrderId = "order-1",
                methods = listOf(PaymentMethodType.CARD, PaymentMethodType.ALIPAY_CN, PaymentMethodType.GRABPAY),
            ),
        )
        compose.onNodeWithText("SGD8.98").assertIsDisplayed()
        compose.onNodeWithText("Order order-1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pay with Card").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pay with Alipay").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pay with GrabPay").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pay with WeChat Pay").assertDoesNotExist()

        compose.onNodeWithContentDescription("Pay with Alipay").performClick()
        assertEquals(listOf(PaymentMethodType.ALIPAY_CN), selected)

        compose.onNodeWithContentDescription("Close payment").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun `an empty method list says so instead of showing nothing`() {
        show(PaymentUiState.MethodList("8.98", "SGD", null, emptyList()))
        compose.onNodeWithText("No payment methods are available for this payment.").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Pay with", substring = true).assertCountEquals(0)
    }

    @Test
    fun `confirming while blocked shows progress and the reason, and offers no way out`() {
        show(PaymentUiState.Confirming(PaymentMethodType.ALIPAY_CN, leaveBlocked = true))
        compose.onNodeWithContentDescription("Confirming your payment").assertIsDisplayed()
        compose.onNodeWithText("Confirming your payment. Please don't leave this screen.").assertIsDisplayed()
        // No button of any kind: not Cancel, not Close, not Back.
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.onNodeWithContentDescription("Cancel and leave this screen").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close payment").assertDoesNotExist()
    }

    /**
     * **The blocked back-press is announced, not merely drawn (audit item 17).**
     *
     * §2c requires that a customer who cannot leave is told why. Changing the sentence on
     * screen does not tell anybody: TalkBack reads what has focus, and a back-press moves
     * nothing. Without a live region the rule was satisfied for sighted users only — and this
     * is the one press in the whole flow that produces no other feedback at all: nothing
     * closes, nothing moves, no dialog appears.
     *
     * Assertive rather than Polite because it answers an action the customer just took.
     */
    @Test
    fun `the blocked-confirm message is a live region so a screen reader announces it`() {
        show(PaymentUiState.Confirming(PaymentMethodType.ALIPAY_CN, leaveBlocked = true))

        val blocked = compose.onNodeWithText("Confirming your payment. Please don't leave this screen.")
        blocked.assertIsDisplayed()
        assertEquals(
            "the blocked message must interrupt, or a screen-reader user is told nothing",
            LiveRegionMode.Assertive,
            blocked.fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion),
        )
    }

    /**
     * Before the customer has tried to leave, the same text is ordinary progress copy and must
     * **not** interrupt: nothing has happened that needs answering, and a live region that
     * fires on arrival talks over whatever the customer was reading.
     */
    @Test
    fun `plain confirming progress is not a live region`() {
        show(PaymentUiState.Confirming(PaymentMethodType.CARD, leaveBlocked = false))

        assertEquals(
            null,
            compose.onNodeWithText("Processing your payment…")
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion),
        )
    }

    @Test
    fun `confirming before any back-press shows plain progress and still no way out`() {
        show(PaymentUiState.Confirming(PaymentMethodType.CARD, leaveBlocked = false))
        compose.onNodeWithText("Processing your payment…").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun `awaiting a customer action names the step and offers Cancel`() {
        show(PaymentUiState.AwaitingAction(ActionKind.QR))
        compose.onNodeWithText("Scan the QR code to complete your payment.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel and leave this screen").performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun `an unknown action still gets copy and a Cancel`() {
        show(PaymentUiState.AwaitingAction(ActionKind.UNKNOWN))
        compose.onNodeWithText("Complete the payment in your banking or wallet app.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel and leave this screen").assertIsDisplayed()
    }

    @Test
    fun `polling shows waiting copy and offers Cancel`() {
        show(PaymentUiState.Polling)
        compose.onNodeWithText("Waiting for confirmation…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Waiting for confirmation").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel and leave this screen").performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun `the card state routes to the real form and offers a way back to the list`() {
        show(PaymentUiState.CardPlaceholder(canReturnToList = true))
        compose.onNodeWithContentDescription("Card number").assertIsDisplayed()
        // The card form scrolls; its footer buttons start below the fold on a phone viewport.
        compose.onNodeWithContentDescription("Back to payment methods").performScrollTo().performClick()
        assertEquals(1, returnedToList)
        assertEquals(0, cancelled)
    }

    @Test
    fun `a card-only presentation offers Cancel instead of a list to return to`() {
        show(PaymentUiState.CardPlaceholder(canReturnToList = false))
        compose.onNodeWithContentDescription("Back to payment methods").assertDoesNotExist()
        compose.onNodeWithContentDescription("Cancel and leave this screen").performScrollTo().performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun `loading shows a described progress indicator`() {
        show(PaymentUiState.Loading)
        compose.onNodeWithContentDescription("Loading payment").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun `finishing shows nothing interactive`() {
        show(PaymentUiState.Finishing)
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    // ---- the sandbox badge -----------------------------------------------------------------
    //
    // A sandbox sheet and a live one used to be pixel-identical, so a screenshot in a bug
    // report, a demo, or a QA pass on a build already flipped to production said nothing
    // about which environment the money was in.

    @Test
    fun `the sandbox badge is drawn over every screen while the SDK points at sandbox`() {
        UQPay.initialize(ApplicationProvider.getApplicationContext(), UiTestFixtures.configuration())
        try {
            show(PaymentUiState.Loading)
            compose.onNodeWithContentDescription(
                "Test mode. This is a sandbox payment; no real money will move.",
            ).assertIsDisplayed()
        } finally {
            UQPay.resetForTest()
        }
    }

    @Test
    fun `the badge is absent in production`() {
        UQPay.initialize(
            ApplicationProvider.getApplicationContext(),
            UQPayConfiguration(
                clientId = UiTestFixtures.CLIENT_ID,
                environment = Environment.PRODUCTION,
                tokenProvider = UQPayTokenProvider {
                    UQPayAuthToken(UiTestFixtures.TOKEN, System.currentTimeMillis() + 60_000L)
                },
            ),
        )
        try {
            show(PaymentUiState.Loading)
            compose.onNodeWithText("TEST MODE — no real money will move").assertDoesNotExist()
        } finally {
            UQPay.resetForTest()
        }
    }

    /**
     * An uninitialised SDK — a `@Preview`, a screenshot test, the frame after process death
     * before the host's `Application.onCreate` runs again — draws no badge. Claiming "test
     * mode" without knowing is as misleading as claiming production.
     */
    @Test
    fun `an uninitialised SDK claims neither environment`() {
        UQPay.resetForTest()
        show(PaymentUiState.Loading)
        compose.onNodeWithText("TEST MODE — no real money will move").assertDoesNotExist()
    }

    private companion object {
        const val INTENT = "PI_screen_test"
    }
}
