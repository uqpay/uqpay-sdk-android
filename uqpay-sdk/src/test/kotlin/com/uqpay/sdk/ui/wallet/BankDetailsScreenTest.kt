package com.uqpay.sdk.ui.wallet

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.uqpay.sdk.engine.NextAction
import com.uqpay.sdk.network.NextActionDto
import com.uqpay.sdk.ui.UqpayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bank-transfer instructions: what is shown, what is copied, and what happens when the
 * gateway sends a payload this SDK version cannot read.
 *
 * The values used here are a **merchant's receiving account** — the only category of value
 * this SDK is allowed to put on the clipboard. No card number, CVC, token or customer field
 * appears in this file, and none may ever reach `copyToClipboard`.
 */
@RunWith(RobolectricTestRunner::class)
// Amounts are rendered by the platform's currency formatter, which is locale-dependent by
// design (SGD is "SGD8.98" in en-US and "8,98 SGD" in de-DE). Pinning the locale here keeps
// the assertions below about *what is drawn* rather than about the machine running them;
// AmountFormatTest is where the per-locale behaviour itself is checked.
@Config(qualifiers = "en-rUS")
class BankDetailsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var cancelled = 0

    private fun show(details: BankTransferDetails) {
        compose.setContent {
            UqpayTheme {
                BankDetailsScreen(
                    details = details,
                    amount = "8.98",
                    currency = "SGD",
                    onCancel = { cancelled++ },
                )
            }
        }
    }

    // ---- display ------------------------------------------------------------------------

    @Test
    fun `every field the gateway sent is labelled and shown`() {
        show(FULL)

        compose.onNodeWithText("Bank transfer").assertIsDisplayed()
        compose.onNodeWithText("SGD8.98").assertIsDisplayed()
        compose.onNodeWithText("Bank name").assertIsDisplayed()
        compose.onNodeWithText("Test Bank of Singapore").assertIsDisplayed()
        compose.onNodeWithText("Account number").assertIsDisplayed()
        compose.onNodeWithText("000-123-456").assertIsDisplayed()
        compose.onNodeWithText("Payment reference").assertIsDisplayed()
        compose.onNodeWithText("REF-0001").assertIsDisplayed()
    }

    /** A field that did not arrive is omitted, never rendered as a blank value with a label. */
    @Test
    fun `an absent field is not drawn at all`() {
        show(BankTransferDetails(bankName = "Test Bank of Singapore"))

        compose.onNodeWithText("Bank name").assertIsDisplayed()
        compose.onNodeWithText("Account number").assertDoesNotExist()
        compose.onNodeWithText("Payment reference").assertDoesNotExist()
    }

    @Test
    fun `a blank field counts as absent`() {
        show(BankTransferDetails(bankName = "   ", accountNumber = "", reference = null))

        compose.onNodeWithText("Bank name").assertDoesNotExist()
        compose.onNodeWithText(UNAVAILABLE).assertIsDisplayed()
    }

    /**
     * The honest state while `display_bank_details` is unmodelled on the wire: say what
     * cannot be shown rather than drawing three empty rows.
     */
    @Test
    fun `no details at all says so instead of showing an empty form`() {
        show(BankTransferDetails())

        compose.onNodeWithText(UNAVAILABLE).assertIsDisplayed()
        compose.onNodeWithText("Copy").assertDoesNotExist()
    }

    // ---- copy ---------------------------------------------------------------------------

    @Test
    fun `each field copies its own value`() {
        show(FULL)

        compose.onNodeWithContentDescription("Copy Account number").performClick()
        assertEquals("000-123-456", clipboardText())

        compose.onNodeWithContentDescription("Copy Payment reference").performClick()
        assertEquals("REF-0001", clipboardText())

        compose.onNodeWithContentDescription("Copy Bank name").performClick()
        assertEquals("Test Bank of Singapore", clipboardText())
    }

    /**
     * Three identical "Copy" buttons would be three indistinguishable targets for a screen
     * reader. Each names the field it copies.
     */
    @Test
    fun `the copy buttons are distinguishable to a screen reader`() {
        show(FULL)

        compose.onNodeWithContentDescription("Copy Bank name").assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy Account number").assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy Payment reference").assertIsDisplayed()
    }

    @Test
    fun `copyToClipboard puts the value on the clipboard under its label`() {
        copyToClipboard(context, "Account number", "000-123-456")

        val clip = clipboard().primaryClip!!
        assertEquals("000-123-456", clip.getItemAt(0).text)
        assertEquals("Account number", clip.description.label)
    }

    // ---- the way out ---------------------------------------------------------------------

    /**
     * The same Cancel as every other waiting screen, which settles `PENDING` because an
     * attempt is in the air. There is deliberately no "I've paid" button: a customer's claim
     * is not evidence, and the intent's status is.
     */
    @Test
    fun `the only control is Cancel, on both the populated and the empty state`() {
        show(FULL)
        compose.onNodeWithContentDescription("Cancel and leave this screen").performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun `the empty state still offers a way out`() {
        show(BankTransferDetails())
        compose.onNodeWithContentDescription("Cancel and leave this screen").performClick()
        assertEquals(1, cancelled)
    }

    // ---- the wire gap ---------------------------------------------------------------------

    /**
     * Records the gap rather than papering over it. `NextActionDto` has no
     * `display_bank_details` field, so a bank-details action decodes with nothing to show —
     * and the shape could not be verified against the live sandbox (none of the 24 offered
     * method types produced one on 2026-08-18). Adding a guessed DTO to a shared file is
     * exactly how `payment_intent_id` decoded to null for months.
     *
     * When a live capture confirms the shape, this test is the one that should start failing.
     */
    @Test
    fun `a bank-details action currently carries no renderable payload`() {
        val dto = NextActionDto(type = "display_bank_details")
        val action = NextAction.from(dto)

        assertTrue(action is NextAction.BankDetails)
        // Nothing on the DTO to map from: the only field it has is the type.
        assertTrue(BankTransferDetails().isEmpty)
        assertFalse(FULL.isEmpty)
    }

    private fun clipboard(): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun clipboardText(): String? =
        clipboard().primaryClip?.getItemAt(0)?.text?.toString()

    private companion object {
        const val UNAVAILABLE =
            "We can't show the transfer details here. Please check your payment instructions with the merchant."

        val FULL = BankTransferDetails(
            bankName = "Test Bank of Singapore",
            accountNumber = "000-123-456",
            reference = "REF-0001",
        )
    }
}
