package com.uqpay.sdk.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.uqpay.sdk.engine.ConfirmPayload
import com.uqpay.sdk.payment.PaymentMethodType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Compose shell, state by state: what each state shows, what it lets the customer tap,
 * and — for the blocked confirm — what it deliberately does not. Content descriptions are
 * asserted because they are both the accessibility contract and the way these tests find
 * things; a missing one fails here before it fails a screen reader.
 */
@RunWith(RobolectricTestRunner::class)
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
        compose.onNodeWithText("SGD 8.98").assertIsDisplayed()
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

    private companion object {
        const val INTENT = "PI_screen_test"
    }
}
