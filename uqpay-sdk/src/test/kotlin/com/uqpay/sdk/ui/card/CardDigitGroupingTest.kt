package com.uqpay.sdk.ui.card

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The display grouping and, above all, its [OffsetMapping].
 *
 * A caret mapping is the one piece of a text field that cannot be eyeballed. Get it wrong by
 * one in either direction and either the caret drifts as the customer types — which is the
 * bug this class exists to keep fixed — or Compose refuses the mapping outright and the card
 * screen dies with `IllegalStateException`. So nothing here is hand-tabulated: every
 * assertion is derived from the displayed string itself, by counting digits.
 *
 * Two properties, asserted at **every** offset of every number below, pin the mapping
 * completely:
 *
 * 1. `transformedToOriginal(t)` is exactly how many digits stand before displayed offset `t`.
 * 2. `originalToTransformed(i)` is a displayed offset with exactly `i` digits before it, and
 *    it round-trips back to `i`.
 *
 * Card numbers are documented UQPAY sandbox values or synthetic fillers; no real PAN appears.
 */
class CardDigitGroupingTest {

    // ---- the grouping itself -----------------------------------------------------------------

    @Test
    fun `each brand groups the way its own table says`() {
        assertEquals("5346 9301 0010 8117", formatPan("5346930100108117", CardBrand.MASTERCARD))
        assertEquals("3782 822463 10005", formatPan("378282246310005", CardBrand.AMEX))
        assertEquals("3056 930902 5904", formatPan("30569309025904", CardBrand.DINERS))
        assertEquals("6250 9400 0000 0000 008", formatPan("6250940000000000008", CardBrand.UNKNOWN))
    }

    /**
     * A Diners number longer than its 4-6-4 table describes keeps every digit on screen. A
     * customer who cannot see what they typed believes the field ate it, and retypes.
     */
    @Test
    fun `digits past the last group run on rather than vanishing`() {
        assertEquals("3056 930902 5904 12345", formatPan("30569309025904" + "12345", CardBrand.DINERS))
    }

    @Test
    fun `nothing is drawn before the first digit or after the last`() {
        // A leading separator would appear before the customer has typed anything, and a
        // trailing one would be a character they then have to backspace through.
        assertEquals("", formatPan("", CardBrand.VISA))
        assertEquals("4", formatPan("4", CardBrand.VISA))
        assertEquals("4242", formatPan("4242", CardBrand.VISA))
        assertEquals("4242 4", formatPan("42424", CardBrand.VISA))
        assertEquals("", formatExpiry(""))
        assertEquals("1", formatExpiry("1"))
        assertEquals("12", formatExpiry("12"))
        assertEquals("12/3", formatExpiry("123"))
        assertEquals("12/30", formatExpiry("1230"))
    }

    // ---- the offset mapping ------------------------------------------------------------------

    @Test
    fun `the mapping is exact at every offset of a sixteen-digit number`() {
        assertMappingIsExact("5346930100108117", CardBrand.MASTERCARD)
    }

    @Test
    fun `the mapping is exact at every offset of a fifteen-digit Amex`() {
        assertMappingIsExact("378282246310005", CardBrand.AMEX)
    }

    @Test
    fun `the mapping is exact at every offset of a fourteen-digit Diners`() {
        assertMappingIsExact("30569309025904", CardBrand.DINERS)
    }

    @Test
    fun `the mapping is exact at every offset of a nineteen-digit number`() {
        assertMappingIsExact("6250940000000000008", CardBrand.UNIONPAY)
        // And on a brand whose table stops at 14, so the last group is the run-on one.
        assertMappingIsExact("3056930902590412345", CardBrand.DINERS)
    }

    /** Every prefix, too: the mapping is rebuilt on each keystroke and must hold on each one. */
    @Test
    fun `the mapping is exact for every prefix of every brand, empty string included`() {
        for (brand in CardBrand.entries) {
            val number = "6250940000000000008".take(brand.maxPanLength)
            for (length in 0..number.length) assertMappingIsExact(number.take(length), brand)
        }
    }

    @Test
    fun `the mapping is exact at every offset of an expiry`() {
        for (length in 0..4) assertMappingIsExact("1230".take(length), ExpiryTransformation)
    }

    // ---- the group edges, named ---------------------------------------------------------------

    /**
     * The convention at a group edge, stated once so it cannot drift: the caret sits **after**
     * the separator, never in front of it. Typing the fifth digit of a card must put the caret
     * where the fifth digit will appear, not on the far side of the space it just pushed out.
     */
    @Test
    fun `a caret at a group edge sits after the separator`() {
        val mapping = mappingFor("5346930100108117", CardBrand.MASTERCARD)
        assertEquals("offset 0 is always offset 0", 0, mapping.originalToTransformed(0))
        assertEquals(4 + 1, mapping.originalToTransformed(4))
        assertEquals(8 + 2, mapping.originalToTransformed(8))
        assertEquals(12 + 3, mapping.originalToTransformed(12))
        assertEquals("the end of the raw value is the end of the display", 19, mapping.originalToTransformed(16))

        val amex = mappingFor("378282246310005", CardBrand.AMEX)
        assertEquals(4 + 1, amex.originalToTransformed(4))
        assertEquals(10 + 2, amex.originalToTransformed(10))
        assertEquals(17, amex.originalToTransformed(15))
    }

    /** A separator has a digit on each side, and both sides answer with that digit's index. */
    @Test
    fun `both sides of a separator map back to the same raw offset`() {
        val mapping = mappingFor("5346930100108117", CardBrand.MASTERCARD)
        assertEquals(4, mapping.transformedToOriginal(4))
        assertEquals(4, mapping.transformedToOriginal(5))
        assertEquals(0, mapping.transformedToOriginal(0))
        assertEquals(16, mapping.transformedToOriginal(19))
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun transform(digits: String, brand: CardBrand): TransformedText =
        DigitGroupingTransformation(brand.panGroups, ' ').filter(AnnotatedString(digits))

    private fun mappingFor(digits: String, brand: CardBrand): OffsetMapping =
        transform(digits, brand).offsetMapping

    private fun assertMappingIsExact(digits: String, brand: CardBrand) =
        assertMappingIsExact(digits, DigitGroupingTransformation(brand.panGroups, ' '))

    /**
     * Both directions, at every offset, checked against the displayed string rather than
     * against a table — which is what makes this able to see an off-by-one either way.
     */
    private fun assertMappingIsExact(digits: String, transformation: VisualTransformation) {
        val transformed = transformation.filter(AnnotatedString(digits))
        val displayed = transformed.text.text
        val mapping = transformed.offsetMapping

        assertEquals(
            "'$displayed' must hold exactly the digits of '$digits'",
            digits,
            displayed.filter(Char::isDigit),
        )

        for (t in 0..displayed.length) {
            val expected = displayed.take(t).count(Char::isDigit)
            assertEquals(
                "transformedToOriginal($t) on '$displayed': $expected digits stand before it",
                expected,
                mapping.transformedToOriginal(t),
            )
        }

        for (i in 0..digits.length) {
            val at = mapping.originalToTransformed(i)
            assertTrue(
                "originalToTransformed($i) on '$displayed' returned $at, outside 0..${displayed.length}",
                at in 0..displayed.length,
            )
            assertEquals(
                "originalToTransformed($i) on '$displayed' must land where $i digits stand before it",
                i,
                displayed.take(at).count(Char::isDigit),
            )
            assertEquals(
                "originalToTransformed($i) on '$displayed' must round-trip",
                i,
                mapping.transformedToOriginal(at),
            )
        }
    }
}
