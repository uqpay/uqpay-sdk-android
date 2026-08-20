package com.uqpay.sdk.ui.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Brand detection, and the two things detection is *for*: the CVC length and the input bound.
 *
 * Disaster-first. The failures that matter here are not "Visa was labelled Mastercard" — that
 * is cosmetic — but the two that make a real card unpayable: a 3-digit CVC rule applied to an
 * Amex, and a 16-digit input cap applied to a 19-digit UnionPay number. Both shipped on iOS
 * (audit G20), which is why they are the first tests in the file.
 *
 * No real card numbers anywhere. Every PAN below is either a documented UQPAY sandbox value
 * (`api-contract.md` §9) or a prefix-plus-zeros string that identifies nobody.
 */
class CardBrandTest {

    // ---- the two G20 failures ------------------------------------------------------------

    @Test
    fun `Amex takes a four-digit CVC and every other brand takes three`() {
        assertEquals(4, CardBrand.AMEX.cvcLength)
        for (brand in CardBrand.entries - CardBrand.AMEX) {
            assertEquals("$brand must take a 3-digit security code", 3, brand.cvcLength)
        }
    }

    @Test
    fun `no brand caps the card number below nineteen digits, except Amex at its real fifteen`() {
        for (brand in CardBrand.entries - CardBrand.AMEX) {
            assertEquals(
                "$brand capped below 19 digits makes long PANs untypeable — the shipped G20 bug",
                19,
                brand.maxPanLength,
            )
        }
        assertEquals("Amex numbers are exactly 15 digits", 15, CardBrand.AMEX.maxPanLength)
    }

    @Test
    fun `a nineteen-digit UnionPay number is recognised and fits inside its own bound`() {
        val nineteen = "6250940000000000008"
        assertEquals(19, nineteen.length)
        val brand = CardBrand.of(nineteen)
        assertEquals(CardBrand.UNIONPAY, brand)
        assertTrue("a 19-digit number must fit the bound", nineteen.length <= brand.maxPanLength)
    }

    // ---- unknown must stay usable ----------------------------------------------------------

    @Test
    fun `an unrecognised prefix is usable, not blocked`() {
        // 9xxx is not issued by any network this table knows.
        val brand = CardBrand.of("9999000000000004")
        assertEquals(CardBrand.UNKNOWN, brand)
        assertEquals("an unknown card must accept an ordinary 3-digit code", 3, brand.cvcLength)
        assertEquals("an unknown card must accept the full ISO length", 19, brand.maxPanLength)
    }

    @Test
    fun `an empty or partial number is unknown rather than a guess`() {
        assertEquals(CardBrand.UNKNOWN, CardBrand.of(""))
        assertEquals(CardBrand.UNKNOWN, CardBrand.of("   "))
        // "2" alone is inside no range: the Mastercard 2-series needs four digits, and
        // answering MASTERCARD here would flip the CVC length back and forth as they type.
        assertEquals(CardBrand.UNKNOWN, CardBrand.of("2"))
        assertEquals(CardBrand.UNKNOWN, CardBrand.of("22"))
    }

    // ---- the prefix table --------------------------------------------------------------------

    @Test
    fun `each network is recognised from its IIN prefix`() {
        assertEquals(CardBrand.VISA, CardBrand.of("4176660000000027"))
        assertEquals(CardBrand.MASTERCARD, CardBrand.of("5413330057004047"))
        assertEquals(CardBrand.MASTERCARD, CardBrand.of("5346930100108117"))
        assertEquals(CardBrand.UNIONPAY, CardBrand.of("6250947000000014"))
        assertEquals(CardBrand.AMEX, CardBrand.of("340000000000000"))
        assertEquals(CardBrand.AMEX, CardBrand.of("370000000000000"))
        assertEquals(CardBrand.DISCOVER, CardBrand.of("6011000000000000"))
        assertEquals(CardBrand.DISCOVER, CardBrand.of("6500000000000000"))
        assertEquals(CardBrand.JCB, CardBrand.of("3530000000000000"))
        assertEquals(CardBrand.DINERS, CardBrand.of("36000000000000"))
        assertEquals(CardBrand.DINERS, CardBrand.of("30000000000000"))
    }

    @Test
    fun `the Mastercard two-series is recognised at both ends of its range and not outside it`() {
        // Issued since 2017. A table that stopped at 51-55 declines these outright.
        assertEquals(CardBrand.MASTERCARD, CardBrand.of("2221000000000000"))
        assertEquals(CardBrand.MASTERCARD, CardBrand.of("2720000000000000"))
        assertEquals(CardBrand.UNKNOWN, CardBrand.of("2220000000000000"))
        assertEquals(CardBrand.UNKNOWN, CardBrand.of("2721000000000000"))
    }

    @Test
    fun `the JCB range is bounded at both ends`() {
        assertEquals(CardBrand.JCB, CardBrand.of("3528000000000000"))
        assertEquals(CardBrand.JCB, CardBrand.of("3589000000000000"))
        // 3527 and 3590 are Diners territory (36/38 aside, 3xxx is shared).
        assertEquals(CardBrand.UNKNOWN, CardBrand.of("3527000000000000"))
        assertEquals(CardBrand.UNKNOWN, CardBrand.of("3590000000000000"))
    }

    @Test
    fun `spaces in the typed number do not change the brand`() {
        assertEquals(CardBrand.MASTERCARD, CardBrand.of("5346 9301 0010 8117"))
        assertEquals(CardBrand.AMEX, CardBrand.of("3400 000000 00000"))
    }

    // ---- display grouping -----------------------------------------------------------------

    @Test
    fun `the number is grouped by brand, and a long one keeps every digit`() {
        assertEquals("5346 9301 0010 8117", formatPan("5346930100108117", CardBrand.MASTERCARD))
        // Amex groups 4-6-5, which is how the digits are printed on the card.
        assertEquals("3400 000000 00009", formatPan("340000000000009", CardBrand.AMEX))
        // The grouping table describes 16 digits. The three beyond it must still be drawn:
        // a customer who cannot see the digits they typed believes the field ate them, and
        // retypes — which is how a correct number becomes a wrong one.
        assertEquals("6250 9400 0000 0000 008", formatPan("6250940000000000008", CardBrand.UNIONPAY))
        // Diners groups 4-6-4 — fourteen digits — but co-badged Diners numbers run to
        // nineteen. The five digits past the grouping table must still be drawn, or the
        // field silently swallows them on screen while sending them on the wire.
        assertEquals("3600 000000 0000 0008", formatPan("360000000000000008", CardBrand.DINERS))
        assertEquals("", formatPan("", CardBrand.UNKNOWN))
        assertEquals("534", formatPan("534", CardBrand.MASTERCARD))
    }

    @Test
    fun `expiry digits are displayed as MM slash YY`() {
        assertEquals("", formatExpiry(""))
        assertEquals("1", formatExpiry("1"))
        assertEquals("12", formatExpiry("12"))
        assertEquals("12/2", formatExpiry("122"))
        assertEquals("12/26", formatExpiry("1226"))
    }

    @Test
    fun `the input bound keeps digits only, up to the maximum`() {
        assertEquals("1234", acceptDigits("1a2b3c4d5e", 4))
        assertEquals("", acceptDigits("abc", 4))
        assertEquals("6250940000000000008", acceptDigits("6250 9400 0000 0000 008", 19))
    }

    // ---- the wire value ---------------------------------------------------------------------

    @Test
    fun `every network value is lowercase, as the API requires`() {
        for (brand in CardBrand.entries) {
            assertEquals("$brand's wire value must be lowercase", brand.wireName.lowercase(), brand.wireName)
            assertTrue("$brand must have a wire value at all", brand.wireName.isNotBlank())
        }
    }

    @Test
    fun `the wire values match the shipped iOS SDK verbatim`() {
        // iOS sends `CardBrand.rawValue.lowercased()`. These spellings are the reference
        // bytes of the only client verified to work against this gateway — including the
        // literal "unknown", which iOS sends for an unrecognised prefix rather than omitting
        // the field.
        assertEquals("visa", CardBrand.VISA.wireName)
        assertEquals("mastercard", CardBrand.MASTERCARD.wireName)
        assertEquals("amex", CardBrand.AMEX.wireName)
        assertEquals("discover", CardBrand.DISCOVER.wireName)
        assertEquals("jcb", CardBrand.JCB.wireName)
        assertEquals("dinersclub", CardBrand.DINERS.wireName)
        assertEquals("unionpay", CardBrand.UNIONPAY.wireName)
        assertEquals("unknown", CardBrand.UNKNOWN.wireName)
    }
}
