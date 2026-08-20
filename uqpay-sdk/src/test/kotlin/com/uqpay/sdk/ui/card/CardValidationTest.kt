package com.uqpay.sdk.ui.card

import com.uqpay.sdk.ui.CountryCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every rule that stands between a customer's typing and an authorisation attempt.
 *
 * Disaster-first: the tests that come first are the ones whose failure costs money or makes
 * a real card unpayable — a mistyped digit reaching the acquirer as a decline, an Amex that
 * cannot be entered, a 19-digit UnionPay number refused, a customer in Indonesia having
 * `US` sent to their issuer.
 *
 * Card numbers are documented UQPAY sandbox values (`api-contract.md` §9) or
 * prefix-plus-filler strings. No real PAN appears in this file.
 */
class CardValidationTest {

    private val august2026 = YearMonth(2026, 8)

    /** The sandbox Mastercard the live 3-D Secure run of 2026-08-18 was driven with. */
    private val sandboxMastercard = "5346930100108117"

    private fun validate(
        pan: String = sandboxMastercard,
        expiry: String = "12/26",
        cvc: String = "811",
        name: String = "Test Cardholder",
        country: String? = "SG",
        today: YearMonth = august2026,
    ) = CardValidation.validate(pan, expiry, cvc, name, country, today)

    // ---- Luhn --------------------------------------------------------------------------

    @Test
    fun `a single mistyped digit is rejected before it can become a decline`() {
        // One digit off the sandbox card — the classic mistype Luhn exists to catch.
        val typo = "5346930100108118"
        assertFalse(CardValidation.isLuhnValid(typo))
        assertEquals(CardFieldError.PAN_LUHN, validate(pan = typo)[CardField.PAN])
    }

    @Test
    fun `two transposed digits are rejected`() {
        val transposed = "5346930100108171"
        assertFalse(CardValidation.isLuhnValid(transposed))
        assertEquals(CardFieldError.PAN_LUHN, validate(pan = transposed)[CardField.PAN])
    }

    @Test
    fun `every documented sandbox card passes Luhn`() {
        // If a validation change ever breaks these, every end-to-end suite breaks with it —
        // so they fail here first, where the cause is obvious.
        for (card in listOf("5413330057004047", "5346930100108117", "4176660000000027", "6250947000000014")) {
            assertTrue("$card is driven by a live test and must stay valid", CardValidation.isLuhnValid(card))
        }
    }

    @Test
    fun `a short string that happens to satisfy mod-ten is still rejected`() {
        // "0" sums to 0, which is a multiple of ten. Accepting it would let a truncated
        // number through to the acquirer.
        assertFalse(CardValidation.isLuhnValid("0"))
        assertFalse(CardValidation.isLuhnValid("00000000"))
        assertEquals(CardFieldError.PAN_LENGTH, validate(pan = "424242")[CardField.PAN])
    }

    @Test
    fun `an empty number is missing, not malformed`() {
        assertEquals(CardFieldError.MISSING, validate(pan = "")[CardField.PAN])
    }

    // ---- 19 digits (G20) ------------------------------------------------------------------

    @Test
    fun `a nineteen-digit number is accepted`() {
        // UnionPay issues up to 19 digits; a 16-digit ceiling makes those cards unpayable.
        val nineteen = "6250940000000000008"
        assertEquals(19, nineteen.length)
        assertTrue(CardValidation.isLuhnValid(nineteen))
        assertTrue(validate(pan = nineteen, cvc = "123").isValid)
    }

    @Test
    fun `a twenty-digit number is refused`() {
        assertEquals(CardFieldError.PAN_LENGTH, validate(pan = "62509470000000000000")[CardField.PAN])
    }

    @Test
    fun `formatting spaces are ignored`() {
        assertTrue(validate(pan = "5346 9301 0010 8117").isValid)
    }

    // ---- CVC is sized by brand (G20) --------------------------------------------------------

    @Test
    fun `Amex needs four digits and refuses three`() {
        val amex = "340000000000009"
        assertEquals(CardBrand.AMEX, CardBrand.of(amex))
        assertTrue(validate(pan = amex, cvc = "1234", expiry = "12/28").isValid)
        assertEquals(
            "a 3-digit code on an Amex is the customer reading the wrong number off the card",
            CardFieldError.CVC_LENGTH,
            validate(pan = amex, cvc = "123", expiry = "12/28")[CardField.CVC],
        )
    }

    @Test
    fun `every other brand needs three digits and refuses four`() {
        assertTrue(validate(cvc = "811").isValid)
        assertEquals(CardFieldError.CVC_LENGTH, validate(cvc = "8110")[CardField.CVC])
    }

    @Test
    fun `an unknown brand takes three digits, so an unrecognised card is still payable`() {
        // Prefix 9 is issued by nobody this table knows; the card must still go through.
        val unknown = "9999000000000004"
        assertEquals(CardBrand.UNKNOWN, CardBrand.of(unknown))
        assertTrue("Luhn fixture must be valid", CardValidation.isLuhnValid(unknown))
        val result = validate(pan = unknown, cvc = "123")
        assertTrue("an unrecognised prefix must never block a payment", result.isValid)
        assertEquals(CardBrand.UNKNOWN, result.brand)
    }

    @Test
    fun `an empty code is missing and a non-numeric one is refused`() {
        assertEquals(CardFieldError.MISSING, validate(cvc = "")[CardField.CVC])
        assertEquals(CardFieldError.CVC_LENGTH, validate(cvc = "8a11")[CardField.CVC])
    }

    // ---- expiry -------------------------------------------------------------------------------

    @Test
    fun `an impossible month is rejected and named as a month problem`() {
        assertEquals(CardFieldError.EXPIRY_MONTH, validate(expiry = "13/30")[CardField.EXPIRY])
        assertEquals(CardFieldError.EXPIRY_MONTH, validate(expiry = "00/30")[CardField.EXPIRY])
        assertEquals(CardFieldError.EXPIRY_MONTH, validate(expiry = "99/30")[CardField.EXPIRY])
    }

    @Test
    fun `a past month is rejected`() {
        assertEquals(CardFieldError.EXPIRY_PAST, validate(expiry = "01/20")[CardField.EXPIRY])
        assertEquals(CardFieldError.EXPIRY_PAST, validate(expiry = "07/26")[CardField.EXPIRY])
    }

    @Test
    fun `the current month is not past`() {
        // A card marked 08/26 is good through the last day of August 2026. Being off by one
        // month here refuses a valid card for up to thirty days.
        assertTrue(validate(expiry = "08/26").isValid)
        assertFalse(CardValidation.isPast(ExpiryDate("08", "2026"), august2026))
        assertTrue(CardValidation.isPast(ExpiryDate("07", "2026"), august2026))
        assertFalse(CardValidation.isPast(ExpiryDate("01", "2027"), august2026))
        assertTrue(CardValidation.isPast(ExpiryDate("12", "2025"), august2026))
    }

    @Test
    fun `December of the current year is not past`() {
        assertFalse(CardValidation.isPast(ExpiryDate("12", "2026"), august2026))
    }

    @Test
    fun `two-digit and four-digit years both parse, with and without a slash`() {
        assertEquals(ExpiryDate("12", "2026"), CardValidation.parseExpiry("12/26"))
        assertEquals(ExpiryDate("12", "2026"), CardValidation.parseExpiry("1226"))
        assertEquals(ExpiryDate("12", "2026"), CardValidation.parseExpiry("12 / 26"))
        assertEquals(ExpiryDate("12", "2027"), CardValidation.parseExpiry("12/2027"))
        assertEquals(ExpiryDate("01", "2029"), CardValidation.parseExpiry("01/29"))
    }

    @Test
    fun `an unreadable expiry is malformed rather than silently corrected`() {
        assertNull(CardValidation.parseExpiry("hello"))
        assertNull(CardValidation.parseExpiry("1"))
        assertNull(CardValidation.parseExpiry("12345"))
        // 13 is not clamped to 12 — a form that did that would send a date nobody typed.
        assertNull(CardValidation.parseExpiry("13/30"))
        assertEquals(CardFieldError.EXPIRY_MALFORMED, validate(expiry = "12345")[CardField.EXPIRY])
        assertEquals(CardFieldError.MISSING, validate(expiry = "")[CardField.EXPIRY])
    }

    @Test
    fun `a valid expiry is reported back so the caller can send exactly what was read`() {
        val result = validate(expiry = "12/26")
        assertEquals(ExpiryDate("12", "2026"), result.expiry)
        assertEquals("12", result.expiry?.month)
        assertEquals("2026", result.expiry?.year)
    }

    // ---- cardholder name ------------------------------------------------------------------------

    @Test
    fun `a blank cardholder name is refused`() {
        assertEquals(CardFieldError.MISSING, validate(name = "")[CardField.CARDHOLDER_NAME])
        assertEquals(CardFieldError.MISSING, validate(name = "   ")[CardField.CARDHOLDER_NAME])
        assertNull(validate(name = "A B")[CardField.CARDHOLDER_NAME])
    }

    // ---- billing country: the full ISO list -------------------------------------------------------

    @Test
    fun `the country list is the whole ISO table, not a hand-picked subset`() {
        // iOS shipped nine entries with "US" as the fallback, so every customer outside those
        // nine markets had the wrong country sent to their issuer. 150 is a floor chosen to
        // fail loudly if anyone ever swaps the platform list for a curated one.
        assertTrue(
            "expected the full ISO list, got ${CountryCodes.all.size}",
            CountryCodes.all.size > 150,
        )
    }

    @Test
    fun `the obscure codes a hand-written list always forgets all resolve`() {
        for (code in listOf("AX", "BQ", "SX", "TL", "CW", "SS", "ME", "BL", "MF", "GG", "IM", "JE")) {
            assertTrue("$code must be a selectable ISO country", CountryCodes.isValid(code))
            val country = CountryCodes.all.firstOrNull { it.code == code }
            assertNotNull("$code must appear in the picker", country)
            assertTrue("$code must have something to read", country!!.name.isNotBlank())
        }
    }

    @Test
    fun `a code that is not ISO is refused rather than substituted`() {
        // The whole point: nothing here falls back to US, or to anything else.
        assertEquals(CardFieldError.COUNTRY_UNKNOWN, validate(country = "ZZ")[CardField.BILLING_COUNTRY])
        assertEquals(CardFieldError.COUNTRY_UNKNOWN, validate(country = "SGP")[CardField.BILLING_COUNTRY])
        assertEquals(CardFieldError.COUNTRY_UNKNOWN, validate(country = "Singapore")[CardField.BILLING_COUNTRY])
        assertFalse(CountryCodes.isValid("ZZ"))
        assertFalse(CountryCodes.isValid(null))
        assertNull(CountryCodes.canonical("ZZ"))
    }

    @Test
    fun `no selection is missing, and is never quietly filled in`() {
        assertEquals(CardFieldError.MISSING, validate(country = null)[CardField.BILLING_COUNTRY])
        assertEquals(CardFieldError.MISSING, validate(country = "")[CardField.BILLING_COUNTRY])
    }

    @Test
    fun `codes are matched case-insensitively but sent canonically`() {
        assertTrue(CountryCodes.isValid("sg"))
        assertEquals("SG", CountryCodes.canonical(" sg "))
        assertTrue(validate(country = "sg").isValid)
    }

    @Test
    fun `the picker filter matches names and codes, and a blank query keeps everything`() {
        assertEquals(CountryCodes.all.size, CountryCodes.filter("   ").size)
        assertTrue(CountryCodes.filter("SG").any { it.code == "SG" })
        assertTrue(CountryCodes.filter("Timor").any { it.code == "TL" })
        assertTrue(CountryCodes.filter("zzzzzz").isEmpty())
    }

    // ---- the result is per field ------------------------------------------------------------------

    @Test
    fun `every bad field is reported, not just the first`() {
        val result = CardValidation.validate(
            pan = "1234",
            expiry = "13/20",
            cvc = "",
            cardholderName = "",
            billingCountryCode = null,
            today = august2026,
        )
        assertFalse(result.isValid)
        assertEquals(
            setOf(
                CardField.PAN,
                CardField.EXPIRY,
                CardField.CVC,
                CardField.CARDHOLDER_NAME,
                CardField.BILLING_COUNTRY,
            ),
            result.errors.keys,
        )
    }

    @Test
    fun `a wholly valid form has no errors at all`() {
        val result = validate()
        assertTrue(result.errors.toString(), result.isValid)
        assertEquals(CardBrand.MASTERCARD, result.brand)
    }

    @Test
    fun `YearMonth now reads a one-based month`() {
        // Calendar.MONTH is zero-based; forgetting that rejects every card expiring this
        // month, and only on the affected days.
        val calendar = java.util.Calendar.getInstance()
        calendar.set(2026, java.util.Calendar.JANUARY, 15)
        assertEquals(YearMonth(2026, 1), YearMonth.now(calendar))
        calendar.set(2026, java.util.Calendar.DECEMBER, 1)
        assertEquals(YearMonth(2026, 12), YearMonth.now(calendar))
    }
}
