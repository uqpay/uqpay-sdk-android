package com.uqpay.sdk.ui.card

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.uqpay.sdk.UQPay
import com.uqpay.sdk.engine.BrowserDetails
import com.uqpay.sdk.engine.BrowserInfo
import com.uqpay.sdk.engine.ConfirmBilling
import com.uqpay.sdk.engine.ConfirmPayload
import com.uqpay.sdk.engine.MobileInfo
import com.uqpay.sdk.engine.PaymentSession
import com.uqpay.sdk.engine.Presentation
import com.uqpay.sdk.payment.PaymentMethodType
import com.uqpay.sdk.payment.PaymentSessionParams
import com.uqpay.sdk.ui.PaymentViewModel
import com.uqpay.sdk.ui.ScriptedGateway
import com.uqpay.sdk.ui.UiTestFixtures
import com.uqpay.sdk.ui.UqpayTheme
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The card form: what it draws, what it refuses to submit, what it puts on the wire — and,
 * first, what it must never write down.
 *
 * Card numbers are documented UQPAY sandbox values (`api-contract.md` §9) or synthetic
 * Luhn-valid fillers. No real PAN, key or secret appears in this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CardFormTest {

    @get:Rule
    val compose = createComposeRule()

    /** Documented sandbox Mastercard, and the code that goes with it. */
    private val pan = "5346930100108117"
    private val cvc = "811"

    @Before
    fun setUp() {
        PaymentSession.clearAllForTest()
        UQPay.initialize(ApplicationProvider.getApplicationContext(), UiTestFixtures.configuration())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        PaymentSession.clearAllForTest()
        UQPay.resetForTest()
    }

    // ---- nothing card-derived at rest (AC §4.3) --------------------------------------------

    /**
     * The one that matters most. `SavedStateHandle` is a Bundle the OS **persists to disk**
     * across process death; a PAN or a CVC in one is card data at rest, in a file this SDK
     * neither owns nor can erase.
     *
     * Driven through the real ViewModel and the real engine, then the saved state is scanned
     * — every key, every value, and the Bundle the handle would actually hand the OS — for
     * the number, its last four digits and the security code.
     */
    @Test
    fun `no card value ever reaches the saved state, through a real confirm`() = runTest {
        val savedState = SavedStateHandle()
        val (session, vm) = viewModel(savedState)
        runCurrent()

        vm.onMethodSelected(PaymentMethodType.CARD)
        runCurrent()
        vm.onCardSubmitted(payload())
        runCurrent()
        assertTrue("the confirm must actually have started", session.engine.isConfirmInFlight)

        val haystack = scan(savedState)
        for (secret in listOf(pan, pan.takeLast(4), cvc, "12", "2026")) {
            assertFalse(
                "saved state must not contain any card value; found something matching '$secret'",
                haystack.contains(secret),
            )
        }

        // Positive control. A scan that could not see a PAN even when one is there would
        // pass forever and prove nothing — this is what keeps the assertion above honest.
        val poisoned = SavedStateHandle(mapOf("uqpay.oops" to pan))
        assertTrue("the scan must be able to see a card value at all", scan(poisoned).contains(pan))

        // Only the two screen-state keys the ViewModel documents may exist at all.
        assertTrue(
            "unexpected saved-state keys: ${savedState.keys()}",
            savedState.keys().all {
                it == PaymentViewModel.KEY_CARD_FORM_SHOWN || it == PaymentViewModel.KEY_BLOCKED_SINCE
            },
        )
    }

    @Test
    fun `a payload never renders its own card values`() {
        // A data class's generated toString would print the PAN and the CVC into the first
        // crash report that interpolated one.
        val rendered = payload().toString()
        assertFalse(rendered.contains(pan))
        assertFalse(rendered.contains(pan.takeLast(4)))
        assertFalse(rendered.contains(cvc))
    }

    @Test
    fun `the form state holds card values only in memory`() {
        val state = CardFormState()
        // Neither Parcelable nor Serializable: there is nothing the framework could persist
        // even if some future code handed it to a Bundle.
        assertFalse(state is android.os.Parcelable)
        assertFalse(state is java.io.Serializable)
    }

    // ---- L-6: the wire says "", the digest says U+0000 ---------------------------------------

    /**
     * Audit L-6, both halves at once, because they are deliberately different and only a test
     * that asserts them together can stop someone "unifying" them.
     *
     * **Wire:** an untouched optional billing field is emitted as `""`, not omitted, and the
     * `address` object is always present. That is what iOS sends — the only client verified
     * to work against this gateway.
     *
     * **Digest:** `ConfirmPayload.ABSENT_FIELD` (U+0000) still means *absent*, and still
     * digests differently from empty. The form simply never produces the absent case.
     */
    @Test
    fun `L-6 - untouched billing fields are empty strings on the wire and address is always present`() {
        val state = CardFormState().apply {
            this.pan = this@CardFormTest.pan
            expiry = "1226"
            this.cvc = this@CardFormTest.cvc
            firstName = "Test"
            lastName = "Cardholder"
            countryCode = "SG"
            // email, phone, both address lines, city, state and postcode are left untouched.
        }
        val result = state.validate(YearMonth(2026, 8))
        assertTrue(result.errors.toString(), result.isValid)

        val built = state.toPayload("PI_1", result.expiry!!)
        for (value in listOf(
            built.billing.email,
            built.billing.phoneNumber,
            built.billing.state,
            built.billing.city,
            built.billing.street,
            built.billing.postcode,
        )) {
            assertEquals("an untouched optional billing field must be \"\", never null", "", value)
        }

        val body = built.encodeBody(frozenDevice, null)
        assertTrue("the address object must always be present", body.contains("\"address\""))
        assertTrue("an untouched optional must be written, not omitted", body.contains("\"state\":\"\""))
        assertTrue(body.contains("\"postcode\":\"\""))
        assertTrue(body.contains("\"country_code\":\"SG\""))
    }

    @Test
    fun `L-6 - the digest half is untouched - absent and empty still hash differently`() {
        val absent = ConfirmPayload.Card(
            paymentIntentId = "PI_1",
            cardNumber = pan,
            expiryMonth = "12",
            expiryYear = "2026",
            cvc = cvc,
            cardholderName = "Test Cardholder",
            network = "mastercard",
            billing = ConfirmBilling(countryCode = "SG"),
        )
        val empty = absent.copy(
            billing = ConfirmBilling(
                firstName = "", lastName = "", email = "", phoneNumber = "",
                countryCode = "SG", state = "", city = "", street = "", postcode = "",
            ),
        )
        assertTrue(
            "an absent optional and an empty one serialise to different bytes and must digest differently",
            absent.digest() != empty.digest(),
        )
        assertTrue(
            "an absent optional must still be the U+0000 placeholder, not \"\"",
            absent.digestFields().contains(ConfirmPayload.ABSENT_FIELD),
        )
        assertFalse(
            "the form's payload never carries the absent placeholder",
            empty.digestFields().contains(ConfirmPayload.ABSENT_FIELD),
        )
        // Arity is fixed either way — 15 fields for a card, always.
        assertEquals(15, absent.digestFields().size)
        assertEquals(15, empty.digestFields().size)
    }

    @Test
    fun `the payload sends digits only, the brand as network, and no return url on a first confirm`() {
        val state = CardFormState().apply {
            this.pan = "5346 9301 0010 8117"
            expiry = "1226"
            this.cvc = this@CardFormTest.cvc
            firstName = " Test "
            lastName = " Cardholder "
            addressLine1 = "1 Test Street"
            addressLine2 = "Unit 5"
            countryCode = "SG"
        }
        val built = state.toPayload("PI_1", state.validate(YearMonth(2026, 8)).expiry!!)
        assertEquals(pan, built.cardNumber)
        assertEquals("mastercard", built.network)
        assertEquals("Test Cardholder", built.cardholderName)
        // Lines 1 and 2 are joined into the single `street` the API takes, exactly as iOS
        // joins them before building either the request or the digest.
        assertEquals("1 Test Street, Unit 5", built.billing.street)
        // Null on a first card confirm, exactly as iOS sends it; the gateway answers with
        // next_action.redirect_iframe (verified live 2026-08-18).
        assertNull(built.returnUrl)
        assertEquals("enforce_3ds", built.threeDsAction)
    }

    @Test
    fun `an address line 2 left empty does not leave a dangling separator`() {
        val state = CardFormState().apply { addressLine1 = "1 Test Street" }
        assertEquals("1 Test Street", state.street)
    }

    // ---- the screen -----------------------------------------------------------------------

    @Test
    fun `an incomplete form refuses to submit and says which field is wrong`() {
        var submitted: ConfirmPayload.Card? = null
        showForm(onSubmit = { submitted = it })

        // Pay is *enabled* on an invalid form, deliberately. Validation errors appear only
        // after a submit attempt, so a button greyed out by validation is a dead end: the
        // customer sees a form with no message anywhere and nothing to try. Tapping it
        // submits nothing and reveals every field error at once.
        pay().assertIsEnabled()
        pay().performClick()
        assertNull("an invalid form must not submit", submitted)
        compose.onAllNodesWithText("This field is required.").onFirst().assertExists()

        type("Card number", pan)
        type("Card expiry date, month and year", "1230")
        type("Card security code", cvc)
        type("Cardholder first name", "Test")

        // Still nothing: the last name is blank, so the cardholder name is a single word...
        // which is a real name. The form is now complete.
        pay().assertIsEnabled()
        pay().performClick()

        val payload = assertNotNull("Pay must hand up a payload", submitted).let { submitted!! }
        assertEquals(pan, payload.cardNumber)
        assertEquals(cvc, payload.cvc)
        assertEquals("12", payload.expiryMonth)
        assertEquals("2030", payload.expiryYear)
        assertEquals("SG", payload.billing.countryCode)
    }

    @Test
    fun `a Luhn-failing number refuses to submit and says which field is wrong`() {
        var submitted: ConfirmPayload.Card? = null
        showForm(onSubmit = { submitted = it })
        type("Card number", "5346930100108118")
        type("Card expiry date, month and year", "1230")
        type("Card security code", cvc)
        type("Cardholder first name", "Test")

        pay().performClick()

        assertNull("a mistyped number must never reach the acquirer", submitted)
        compose.onNodeWithText("That card number doesn't look right. Please check it and try again.")
            .assertExists()
    }

    /**
     * **The silent dead end (audit item 11).**
     *
     * The CVC field's length follows the brand — four digits on an Amex, three elsewhere — and
     * it cannot apply that retroactively. Enter an Amex, type a four-digit code, then replace
     * the number with a Visa: the CVC field holds four digits that Visa's rules cap at three.
     * Every field looks filled, no error is drawn (they appear only after a submit attempt),
     * and the Pay button used to be disabled by the very validation failure it would have
     * explained. The form looked complete and was dead, with no way for the customer to find
     * out why.
     *
     * The number's setter now re-truncates the code, so the state cannot hold a value the
     * brand forbids however the number is changed — typed, pasted, autofilled or cleared.
     */
    @Test
    fun `changing the brand re-truncates a security code the new brand cannot hold`() {
        var submitted: ConfirmPayload.Card? = null
        showForm(onSubmit = { submitted = it })

        type("Card number", "340000000000009")
        type("Card security code", "1234")
        compose.onNodeWithText("1234").assertIsDisplayed()

        // Retyped as a Visa. The four-digit code is now one digit too long for the brand.
        compose.onNodeWithContentDescription("Card number").performTextClearance()
        type("Card number", "4242424242424242")

        compose.onNodeWithText("123").assertIsDisplayed()
        compose.onAllNodesWithText("1234").assertCountEquals(0)

        // And the form pays from there. What is left in the field is a complete Visa code,
        // so the customer is not even asked to retype it — the state simply stopped holding
        // a value the brand forbids.
        type("Card expiry date, month and year", "1230")
        type("Cardholder first name", "Test")
        pay().performClick()
        assertEquals("123", submitted?.cvc)
        assertEquals("visa", submitted?.network)
    }

    @Test
    fun `the number is grouped as it is typed and the brand is named`() {
        showForm()
        type("Card number", pan)
        compose.onNodeWithText("5346 9301 0010 8117").assertIsDisplayed()
        compose.onNodeWithText("Mastercard").assertIsDisplayed()
    }

    @Test
    fun `the security code field accepts four digits on an Amex and three elsewhere`() {
        var submitted: ConfirmPayload.Card? = null
        showForm(onSubmit = { submitted = it })
        type("Card number", "340000000000009")
        type("Card expiry date, month and year", "1230")
        // Five digits typed; the field must keep exactly four on an Amex.
        type("Card security code", "12345")
        type("Cardholder first name", "Test")
        pay().performClick()
        assertEquals("1234", submitted?.cvc)
        assertEquals("amex", submitted?.network)
    }

    @Test
    fun `a nineteen-digit number stays fully visible while it is typed`() {
        // The grouping table describes 16 digits; the three beyond it must still be drawn.
        // A customer who cannot see the digits they typed believes the field ate them, and
        // retypes — which is how a correct number becomes a wrong one.
        showForm()
        type("Card number", "6250940000000000008")
        compose.onNodeWithText("6250 9400 0000 0000 008").assertIsDisplayed()
    }

    @Test
    fun `a nineteen-digit number can be typed in full`() {
        var submitted: ConfirmPayload.Card? = null
        showForm(onSubmit = { submitted = it })
        type("Card number", "6250940000000000008")
        type("Card expiry date, month and year", "1230")
        type("Card security code", "123")
        type("Cardholder first name", "Test")
        pay().performClick()
        assertEquals(
            "a 19-digit PAN must survive the input bound — the shipped G20 bug",
            "6250940000000000008",
            submitted?.cardNumber,
        )
    }

    @Test
    fun `the country picker offers the full ISO list and an obscure code can be chosen`() {
        var submitted: ConfirmPayload.Card? = null
        showForm(onSubmit = { submitted = it })
        compose.onNodeWithContentDescription("Choose your billing country or region").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Search countries and regions").performTextInput("Timor")
        compose.onNodeWithContentDescription("Select Timor-Leste").performClick()

        type("Card number", pan)
        type("Card expiry date, month and year", "1230")
        type("Card security code", cvc)
        type("Cardholder first name", "Test")
        pay().performClick()
        assertEquals("TL", submitted?.billing?.countryCode)
    }

    @Test
    fun `a card-only presentation offers Cancel and no way back to a list that does not exist`() {
        var cancelled = 0
        showForm(canReturnToList = false, onCancel = { cancelled++ })
        compose.onNodeWithContentDescription("Back to payment methods").assertDoesNotExist()
        compose.onNodeWithContentDescription("Cancel and leave this screen").performScrollTo().performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun `over a method list the form offers a way back and cancels nothing`() {
        var back = 0
        var cancelled = 0
        showForm(canReturnToList = true, onReturnToList = { back++ }, onCancel = { cancelled++ })
        compose.onNodeWithContentDescription("Back to payment methods").performScrollTo().performClick()
        assertEquals(1, back)
        assertEquals(0, cancelled)
    }

    @Test
    fun `Pay stays disabled while the screen says a confirm is already accepted`() {
        showForm(submitEnabled = false, prefill = true)
        pay().assertIsNotEnabled()
    }

    // ---- the caret: every digit lands where it was typed ------------------------------------

    /**
     * The regression test for the scramble.
     *
     * Typed **one keystroke at a time**, because that is the only way the bug appears: the
     * field used to be handed a re-grouped string on every change, and each space the
     * formatter inserted was a length change the caret could not survive. It re-anchored one
     * position back, so the next digit landed in front of the one before it. Typing
     * 5346930100108117 produced 5346 3010 1081 7109 — not the customer's card, failing Luhn,
     * and reported as "your card number is invalid" with nothing on screen to explain why.
     *
     * A single `performTextInput` of the whole string never saw it, which is exactly why the
     * suite was green while an emulator was not. Assert on the **raw** state: the digits the
     * form is holding are the digits that will be sent.
     */
    @Test
    fun `a number typed one digit at a time is the number that was typed`() {
        val state = CardFormState()
        showForm(formState = state)

        for (digit in pan) type("Card number", digit.toString())

        assertEquals("every keystroke must land where it was typed", pan, state.pan)
    }

    /** The same, for the other field the formatter used to rewrite under the caret. */
    @Test
    fun `an expiry typed one digit at a time is held as MMYY and drawn as MM slash YY`() {
        val state = CardFormState()
        showForm(formState = state)

        for (digit in "1230") type("Card expiry date, month and year", digit.toString())

        assertEquals("1230", state.expiry)
        assertEquals("12/30", drawn(ExpiryTransformation, state.expiry))
        compose.onNodeWithText("12/30").assertIsDisplayed()

        // And it is read as December 2030, not as whatever the digits were shuffled into.
        val parsed = state.validate(YearMonth(2026, 8)).expiry
        assertEquals("12", parsed?.month)
        assertEquals("2030", parsed?.year)
    }

    /**
     * A customer who mistyped one digit taps into the middle to fix it. The caret must stay
     * where they put it, across the separator the transformation draws — otherwise correcting
     * a typo introduces a worse one.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a digit typed into the middle lands in the middle and the caret stays put`() {
        val state = CardFormState()
        showForm(formState = state)
        for (digit in pan) type("Card number", digit.toString())

        // Raw offset 4 — the first group edge, and the hardest place for a mapping to be
        // right, because it is where a separator stands on screen.
        number().performTextInputSelection(TextRange(4))
        type("Card number", "1")
        type("Card number", "2")

        assertEquals(
            "both digits must land at the caret, in order",
            "5346" + "12" + "930100108117",
            state.pan,
        )
    }

    /**
     * Backspace deletes a digit, never a separator. It cannot do otherwise once the spaces
     * exist only on screen — there is no separator in the value to delete — and that is the
     * property worth pinning: a customer who backspaces across a group edge must not have to
     * press it twice to remove one digit.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `backspace across a group edge deletes a digit, not a separator`() {
        val state = CardFormState()
        showForm(formState = state)
        for (digit in "53469") type("Card number", digit.toString())
        // Five digits: the display already carries a space the caret has just crossed.
        assertEquals("5346 9", drawn(panTransformation(state), state.pan))

        number().performKeyInput { pressKey(Key.Backspace) }
        assertEquals("one press, one digit", "5346", state.pan)

        number().performKeyInput { pressKey(Key.Backspace) }
        assertEquals("and again, straight across the edge", "534", state.pan)
    }

    /**
     * The value is digits; the grouping is drawn. Asserted through the transformation itself
     * as well as through the screen, so the two can never drift apart.
     */
    @Test
    fun `the field holds digits only and draws them grouped`() {
        val state = CardFormState()
        showForm(formState = state)
        for (digit in "340000000000009") type("Card number", digit.toString())

        assertEquals("340000000000009", state.pan)
        // Amex regroups to 4-6-5 as soon as its prefix identifies it, from the brand's own
        // table — this form owns no second copy of the grouping.
        assertEquals("3400 000000 00009", drawn(panTransformation(state), state.pan))
        compose.onNodeWithText("3400 000000 00009").assertIsDisplayed()
    }

    // ---- the merchant's billing prefill ------------------------------------------------------

    /**
     * The whole point of the feature: the merchant hands over what it already knows, and the
     * customer is left with only the card itself to type.
     */
    @Test
    fun `a prefill seeds every billing field and leaves the card fields empty`() {
        val state = CardFormState(fullPrefill)

        assertEquals("John", state.firstName)
        assertEquals("Tan", state.lastName)
        assertEquals("john.tan@example.com", state.email)
        assertEquals("+6591234567", state.phone)
        assertEquals("123 Orchard Road", state.addressLine1)
        assertEquals("#12-01", state.addressLine2)
        assertEquals("Singapore", state.city)
        assertEquals("Singapore", state.state)
        assertEquals("238888", state.postcode)
        assertEquals("SG", state.countryCode)

        // The three the customer must always type. There is no constructor parameter that
        // could seed them, which is the actual guarantee; this asserts the consequence.
        assertEquals("", state.pan)
        assertEquals("", state.expiry)
        assertEquals("", state.cvc)
    }

    /**
     * `CardFormState` takes one seed parameter and it is the public billing type. A future
     * `pan =` or `cvc =` seed would have to appear on this constructor, and it must not —
     * a PAN handed in by the host app has already travelled through an Intent extra, which
     * is card data in a Bundle the OS may write to disk.
     */
    @Test
    fun `no card value is prefillable, by construction`() {
        val seedable = CardFormState::class.java.declaredConstructors
            .flatMap { it.parameterTypes.asList() }
            .map { it.name }

        assertTrue(
            "CardFormState must seed from PaymentSessionParams.BillingDetails and nothing else",
            seedable.any { it == PaymentSessionParams.BillingDetails::class.java.name },
        )
        // BillingDetails has no card properties to offer in the first place.
        val offered = PaymentSessionParams.BillingDetails::class.java.declaredMethods
            .map { it.name.lowercase() }
        for (forbidden in listOf("getpan", "getcardnumber", "getexpiry", "getcvc", "getcvv", "getsecuritycode")) {
            assertFalse(
                "no card value may ever become prefillable: found $forbidden",
                offered.contains(forbidden),
            )
        }
    }

    @Test
    fun `an absent prefill leaves the form exactly as it was before the feature existed`() {
        val seeded = CardFormState(null)
        val bare = CardFormState()

        for ((label, value) in listOf(
            "firstName" to seeded.firstName, "lastName" to seeded.lastName,
            "email" to seeded.email, "phone" to seeded.phone,
            "addressLine1" to seeded.addressLine1, "addressLine2" to seeded.addressLine2,
            "city" to seeded.city, "state" to seeded.state, "postcode" to seeded.postcode,
        )) {
            assertEquals("$label must stay empty without a prefill", "", value)
        }
        // The country still opens on the device's region, and still may be null.
        assertEquals(bare.countryCode, seeded.countryCode)
    }

    @Test
    fun `a partial prefill fills only what it was given`() {
        val state = CardFormState(
            PaymentSessionParams.BillingDetails(firstName = "John", countryCode = "SG"),
        )

        assertEquals("John", state.firstName)
        assertEquals("SG", state.countryCode)
        assertEquals("", state.lastName)
        assertEquals("", state.email)
        assertEquals("", state.postcode)
    }

    /**
     * A merchant typo in `countryCode` costs the customer one tap in the picker, never a
     * payment that cannot be started — and it must never be *substituted*, which is the
     * shipped-iOS bug `CountryCodes` exists to prevent.
     */
    @Test
    fun `an unrecognised country code falls back to the device default rather than erroring`() {
        val deviceDefault = CardFormState().countryCode

        for (nonsense in listOf("SGP", "Singapore", "ZZ", "", "  ", "1")) {
            val state = CardFormState(PaymentSessionParams.BillingDetails(countryCode = nonsense))
            assertEquals(
                "'$nonsense' is not an ISO code and must be dropped, not guessed at",
                deviceDefault,
                state.countryCode,
            )
        }
    }

    @Test
    fun `a country code is canonicalised rather than taken literally`() {
        assertEquals("SG", CardFormState(prefill(countryCode = "sg")).countryCode)
        assertEquals("SG", CardFormState(prefill(countryCode = " Sg ")).countryCode)
    }

    /**
     * End to end through the real screen: a prefilled form still validates, and the payload
     * it hands up carries the merchant's values — trimmed and joined exactly as a typed form
     * would be, so a prefilled payment and a typed one produce the same bytes.
     */
    @Test
    fun `a prefilled form validates and submits with only the card typed`() {
        var submitted: ConfirmPayload.Card? = null
        showForm(billingDetails = fullPrefill, onSubmit = { submitted = it })

        // A prefill is not a payment: with no card typed, Pay submits nothing.
        pay().performClick()
        assertNull("a prefill alone must not submit", submitted)

        type("Card number", pan)
        type("Card expiry date, month and year", "1230")
        type("Card security code", cvc)

        pay().assertIsEnabled()
        pay().performClick()

        val payload = assertNotNull("a prefilled form must submit", submitted).let { submitted!! }
        assertEquals("John Tan", payload.cardholderName)
        assertEquals("John", payload.billing.firstName)
        assertEquals("Tan", payload.billing.lastName)
        assertEquals("john.tan@example.com", payload.billing.email)
        assertEquals("+6591234567", payload.billing.phoneNumber)
        // Lines 1 and 2 are joined into the single `street` the API takes, prefilled or not.
        assertEquals("123 Orchard Road, #12-01", payload.billing.street)
        assertEquals("Singapore", payload.billing.city)
        assertEquals("Singapore", payload.billing.state)
        assertEquals("238888", payload.billing.postcode)
        assertEquals("SG", payload.billing.countryCode)
        assertEquals(pan, payload.cardNumber)
        assertEquals(cvc, payload.cvc)
    }

    /** Prefilled values are shown in ordinary editable fields, and the customer wins. */
    @Test
    fun `a prefilled value is displayed and can be overwritten by the customer`() {
        var submitted: ConfirmPayload.Card? = null
        showForm(billingDetails = fullPrefill, onSubmit = { submitted = it })

        // Displayed, not hidden behind a "we know who you are" summary: a customer cannot
        // consent to a billing address they were never shown.
        compose.onNodeWithText("John").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("123 Orchard Road").performScrollTo().assertIsDisplayed()

        // And editable. What is sent is what the field holds, not what the merchant sent.
        compose.onNodeWithContentDescription("Billing city").performScrollTo()
            .performTextReplacement("Jurong")

        type("Card number", pan)
        type("Card expiry date, month and year", "1230")
        type("Card security code", cvc)
        pay().performClick()

        assertEquals("Jurong", submitted?.billing?.city)
        // The rest of the prefill is untouched by that edit.
        assertEquals("238888", submitted?.billing?.postcode)
    }

    /**
     * A prefill must not become card data at rest by the back door. The saved-state scan
     * that guards the PAN is re-run with a prefill attached — and extended to the customer's
     * contact details, which are PII in the same file the SDK does not own.
     */
    @Test
    fun `a prefill never reaches the saved state, through a real confirm`() = runTest {
        val savedState = SavedStateHandle()
        val (session, vm) = viewModel(savedState)
        runCurrent()

        vm.onMethodSelected(PaymentMethodType.CARD)
        runCurrent()

        // The payload a prefilled form actually builds, pushed through the real ViewModel
        // and the real engine — the same path the PAN scan above takes.
        val state = CardFormState(fullPrefill).apply {
            pan = this@CardFormTest.pan
            expiry = "1230"
            cvc = this@CardFormTest.cvc
        }
        vm.onCardSubmitted(state.toPayload(INTENT, state.validate(YearMonth(2026, 8)).expiry!!))
        runCurrent()
        assertTrue("the confirm must actually have started", session.engine.isConfirmInFlight)

        val haystack = scan(savedState)
        for (value in listOf(
            "john.tan@example.com", "+6591234567", "123 Orchard Road", "238888", "John", "Tan",
        )) {
            assertFalse(
                "a prefilled billing value must never be persisted; found '$value'",
                haystack.contains(value),
            )
        }
        // Still nothing card-derived either.
        assertFalse(haystack.contains(pan))
        assertFalse(haystack.contains(cvc))
    }

    // ---- helpers ---------------------------------------------------------------------------

    /**
     * Everything a [SavedStateHandle] holds, flattened: its keys, its values, and the Bundle
     * it would actually hand the OS to write to disk.
     */
    private fun scan(handle: SavedStateHandle): String = buildString {
        val bundle = handle.savedStateProvider().saveState()
        append(bundle.toString())
        for (key in handle.keys()) {
            append(key).append('=').append(handle.get<Any>(key)).append('\n')
            @Suppress("DEPRECATION")
            append(bundle.get(key)).append('\n')
        }
    }

    /** The Pay button, scrolled into view — the form is taller than a phone viewport. */
    private fun pay() = compose.onNodeWithContentDescription("Pay with this card").performScrollTo()

    /** The card number field, scrolled into view. */
    private fun number() = compose.onNodeWithContentDescription("Card number").performScrollTo()

    /** What [transformation] would draw for [raw] — the display, read straight from the source. */
    private fun drawn(transformation: VisualTransformation, raw: String): String =
        transformation.filter(AnnotatedString(raw)).text.text

    /** The transformation the number field is using right now, for the detected brand. */
    private fun panTransformation(state: CardFormState): VisualTransformation =
        DigitGroupingTransformation(state.brand.panGroups, ' ')

    private fun type(contentDescription: String, text: String) {
        compose.onNodeWithContentDescription(contentDescription).performScrollTo().performTextInput(text)
    }

    private fun showForm(
        canReturnToList: Boolean = true,
        submitEnabled: Boolean = true,
        prefill: Boolean = false,
        billingDetails: PaymentSessionParams.BillingDetails? = null,
        // Handed in when the test needs to read the raw values the form is holding — which is
        // the only way to see what the customer's keystrokes actually became.
        formState: CardFormState? = null,
        onSubmit: (ConfirmPayload.Card) -> Unit = {},
        onReturnToList: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        compose.setContent {
            UqpayTheme {
                // The country is set here rather than left to the device's region, so the
                // test does not depend on the machine's locale. A merchant prefill, when one
                // is given, seeds the state through the real constructor.
                val state = remember {
                    (formState ?: CardFormState(billingDetails)).apply {
                        if (billingDetails?.countryCode == null) countryCode = "SG"
                        if (prefill) {
                            pan = this@CardFormTest.pan
                            expiry = "1230"
                            cvc = this@CardFormTest.cvc
                            firstName = "Test"
                        }
                    }
                }
                CardForm(
                    paymentIntentId = INTENT,
                    canReturnToList = canReturnToList,
                    submitEnabled = submitEnabled,
                    onSubmit = onSubmit,
                    onReturnToList = onReturnToList,
                    onCancel = onCancel,
                    billingDetails = billingDetails,
                    state = state,
                )
            }
        }
    }

    /** A frozen device fingerprint; nothing here is device- or card-derived. */
    private val frozenDevice = BrowserInfo(
        acceptHeader = "*/*",
        browser = BrowserDetails(
            javaEnabled = false,
            javascriptEnabled = true,
            userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8)",
            cookieEnabled = true,
            plugins = emptyList(),
            doNotTrack = false,
        ),
        deviceId = "device-pinned",
        language = "en-SG",
        mobile = MobileInfo(deviceModel = "Pixel 8", osType = "ANDROID", osVersion = "Android 14"),
        screenColorDepth = 24,
        screenHeight = 2400,
        screenWidth = 1080,
        timezone = "8",
        touchSupport = true,
        hardwareConcurrency = 8,
        deviceMemory = 8,
    )

    private fun payload() = ConfirmPayload.Card(
        paymentIntentId = INTENT,
        cardNumber = pan,
        expiryMonth = "12",
        expiryYear = "2026",
        cvc = cvc,
        cardholderName = "Test Cardholder",
        network = "mastercard",
        billing = ConfirmBilling(
            firstName = "Test", lastName = "Cardholder", email = "", phoneNumber = "",
            countryCode = "SG", state = "", city = "", street = "", postcode = "",
        ),
    )

    /** A real session over the shared socket fake, and the real ViewModel on top of it. */
    private fun TestScope.viewModel(savedState: SavedStateHandle): Pair<PaymentSession, PaymentViewModel> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val session = PaymentSession.obtain(INTENT, UiTestFixtures.dependencies(ScriptedGateway(), dispatcher))
        session.startIfNeeded(Presentation.MethodList)
        return session to PaymentViewModel(INTENT, session, savedState, now = { testScheduler.currentTime })
    }

    /** An obviously-synthetic prefill. No real person\'s details appear in this file. */
    private val fullPrefill = prefill()

    private fun prefill(
        countryCode: String? = "SG",
    ) = PaymentSessionParams.BillingDetails(
        firstName = "John",
        lastName = "Tan",
        email = "john.tan@example.com",
        phone = "+6591234567",
        addressLine1 = "123 Orchard Road",
        addressLine2 = "#12-01",
        city = "Singapore",
        state = "Singapore",
        postalCode = "238888",
        countryCode = countryCode,
    )

    private companion object {
        const val INTENT = "PI_card_form_test"
    }
}
