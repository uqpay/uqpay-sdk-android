package com.uqpay.sdk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The amount line on the payment sheet.
 *
 * The screen used to print `"$currency $amount"`, which is wrong for a gateway whose method
 * list is SEA and East Asian: JPY, KRW and VND have no minor unit, so `"1000.00 JPY"` shows a
 * price a hundred times smaller than it is to anyone who reads it carefully, and most of
 * Europe writes the separators the other way round. Every case below is one this SDK's own
 * currency list can produce.
 *
 * No Robolectric: [formatAmount] takes its locale and its fallback pattern as parameters
 * precisely so the behaviour can be pinned per locale rather than per machine.
 */
class AmountFormatTest {

    private val fallback = "%1\$s %2\$s"

    private fun format(amount: String?, currency: String?, locale: Locale = Locale.US) =
        formatAmount(amount, currency, locale, fallback)

    // ---- the minor unit is the currency's, not two ----------------------------------------

    @Test
    fun `a zero-decimal currency is drawn without decimals`() {
        assertEquals("¥1,000", format("1000.00", "JPY"))
        assertEquals("¥1,000", format("1000", "JPY"))
    }

    @Test
    fun `a two-decimal currency keeps both, even when the wire sent fewer`() {
        assertEquals("SGD8.98", format("8.98", "SGD"))
        assertEquals("SGD8.90", format("8.9", "SGD"))
        assertEquals("SGD8.00", format("8", "SGD"))
    }

    @Test
    fun `a three-decimal currency keeps all three`() {
        // Kuwaiti dinar: 1000 fils to the dinar. Rounding this to two decimals would lose a
        // real unit of money, not a rendering nicety.
        assertEquals("KWD8.987", format("8.987", "KWD"))
    }

    // ---- separators and symbol placement are the locale's ----------------------------------

    /**
     * Whitespace is normalised before comparing because CLDR separates the amount from the
     * symbol with a non-breaking space, and which one — U+00A0 or U+202F — has changed
     * between CLDR releases. Pinning the exact code point would make this test a statement
     * about the JDK on the machine rather than about the SDK, and the property under test is
     * the separators and the symbol's side, not the width of a space.
     */
    @Test
    fun `grouping and decimal separators follow the customer's locale`() {
        assertEquals("€1,000.00", format("1000.00", "EUR", Locale.US)?.normaliseSpaces())
        assertEquals("1.000,00 €", format("1000.00", "EUR", Locale.GERMANY)?.normaliseSpaces())
    }

    private fun String.normaliseSpaces(): String = replace(' ', ' ').replace(' ', ' ')

    // ---- nothing here may throw, and nothing may invent a number ---------------------------

    @Test
    fun `a currency the platform cannot resolve falls back to the code beside the amount`() {
        assertEquals("XYZ 8.98", format("8.98", "XYZ"))
    }

    @Test
    fun `a currency with no defined minor unit falls back rather than guessing one`() {
        // XXX is ISO 4217's "no currency"; its defaultFractionDigits is -1.
        assertEquals("XXX 8.98", format("8.98", "XXX"))
    }

    @Test
    fun `an amount that is not a decimal is printed verbatim, never dropped or rounded`() {
        assertEquals("SGD not-a-number", format("not-a-number", "SGD"))
        assertEquals("SGD 8,98", format("8,98", "SGD"))
    }

    @Test
    fun `a missing amount draws no amount line at all`() {
        assertEquals(null, format(null, "SGD"))
        assertEquals(null, format("", "SGD"))
        assertEquals(null, format("   ", "SGD"))
    }

    @Test
    fun `a missing currency prints the bare number rather than guessing one`() {
        assertEquals("8.98", format("8.98", null))
        assertEquals("8.98", format("8.98", ""))
    }

    @Test
    fun `the currency code is matched case-insensitively`() {
        assertEquals("SGD8.98", format("8.98", "sgd"))
        assertEquals("SGD8.98", format("8.98", " Sgd "))
    }

    @Test
    fun `whitespace around the wire amount does not defeat parsing`() {
        assertEquals("SGD8.98", format("  8.98  ", "SGD"))
    }

    /**
     * The fallback pattern is a resource, and resources are documented as overridable by the
     * merchant's own app. Two ways an override goes wrong, and the second is the nastier
     * one: wrong specifiers throw into the sheet's composition, while an empty or
     * specifier-free pattern quietly renders a line with no amount in it — asking the
     * customer to pay a price they cannot see. Both degrade to a plain join.
     */
    @Test
    fun `a broken fallback pattern still shows the amount`() {
        listOf(
            "%3\$s",       // more specifiers than arguments — throws
            "%d %d",       // wrong conversion for a String — throws
            "%",           // truncated specifier — throws
            "",            // silently drops everything
            "Amount:",     // no specifiers at all — silently drops everything
            "%2\$s",       // the amount only, no currency — acceptable, and kept
        ).forEach { pattern ->
            val rendered = formatAmount("8.98", "XYZ", Locale.US, pattern)
            assertTrue(
                "pattern \"$pattern\" rendered \"$rendered\", which does not show the amount",
                rendered.orEmpty().contains("8.98"),
            )
        }
    }

    @Test
    fun `a large amount is grouped, not truncated`() {
        assertEquals("SGD1,234,567.89", format("1234567.89", "SGD"))
    }
}
