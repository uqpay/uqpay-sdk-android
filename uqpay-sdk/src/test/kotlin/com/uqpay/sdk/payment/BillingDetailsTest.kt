package com.uqpay.sdk.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PaymentSessionParams.BillingDetails] as a *public API commitment*, separately from what
 * the card form does with it: the shapes a merchant can construct, and what the type is
 * allowed to say about a customer out loud.
 *
 * No real person's details appear in this file. Every value is obviously synthetic.
 */
class BillingDetailsTest {

    // ---- PII never reaches a log line or a crash report ------------------------------------

    /**
     * The one that matters. A merchant's crash reporter stringifies whatever it is handed,
     * and `PaymentSessionParams` is exactly the sort of object that ends up in a breadcrumb.
     * Email and phone reach a specific person on their own, so they are replaced rather than
     * printed — the rule `UQPayConfiguration` and `UQPayAuthToken` already follow.
     */
    @Test
    fun `toString redacts email and phone`() {
        val rendered = full().toString()

        assertFalse("the email must never be rendered", rendered.contains("john.tan@example.com"))
        assertFalse("the phone must never be rendered", rendered.contains("+6591234567"))
        // Redacted, not omitted: "did my prefill arrive?" is the question this string answers.
        assertTrue(rendered.contains("email=****"))
        assertTrue(rendered.contains("phone=****"))
    }

    @Test
    fun `toString distinguishes an absent contact field from a redacted one`() {
        // **** for a value that is there, null for one that is not. A redaction that erased
        // the difference would make the string useless for the only thing it is for.
        val rendered = PaymentSessionParams.BillingDetails(email = "a@example.com").toString()
        assertTrue(rendered.contains("email=****"))
        assertTrue(rendered.contains("phone=null"))
    }

    /**
     * The nesting is deliberate, and a merchant with their own `BillingDetails` must still be
     * able to spell ours. Asserting the binary name pins that: a "tidy-up" that promoted this
     * to a top-level type would be a source-breaking change for anyone importing both.
     */
    @Test
    fun `BillingDetails is nested inside PaymentSessionParams`() {
        assertEquals(
            "com.uqpay.sdk.payment.PaymentSessionParams\$BillingDetails",
            PaymentSessionParams.BillingDetails::class.java.name,
        )
        assertEquals(
            PaymentSessionParams::class.java,
            PaymentSessionParams.BillingDetails::class.java.enclosingClass,
        )
    }

    // ---- what a merchant can construct -----------------------------------------------------

    /**
     * The 1- and 2-argument constructions that shipped before `billingDetails` existed still
     * compile, unchanged, and still mean what they meant. This is a compile-time assertion
     * first; the runtime checks are the second line of defence.
     */
    @Test
    fun `the pre-existing constructions still compile and still mean the same thing`() {
        val one = PaymentSessionParams("PI_1")
        assertEquals("PI_1", one.paymentIntentId)
        assertEquals(PaymentSessionParams.Presentation.MethodList, one.presentation)
        assertNull("an unmentioned prefill must stay absent", one.billingDetails)

        val two = PaymentSessionParams("PI_2", PaymentSessionParams.Presentation.CardOnly)
        assertEquals(PaymentSessionParams.Presentation.CardOnly, two.presentation)
        assertNull(two.billingDetails)

        // Named arguments, the form the integration guide uses.
        val named = PaymentSessionParams(paymentIntentId = "PI_3")
        assertNull(named.billingDetails)

        // And skipping straight to the third argument, which is what @JvmOverloads plus a
        // default is for.
        val prefilled = PaymentSessionParams(
            paymentIntentId = "PI_4",
            billingDetails = PaymentSessionParams.BillingDetails(firstName = "John"),
        )
        assertEquals(PaymentSessionParams.Presentation.MethodList, prefilled.presentation)
        assertEquals("John", prefilled.billingDetails?.firstName)
    }

    @Test
    fun `every billing field is optional and defaults to absent`() {
        val empty = PaymentSessionParams.BillingDetails()
        for (value in listOf(
            empty.firstName, empty.lastName, empty.email, empty.phone,
            empty.addressLine1, empty.addressLine2, empty.city, empty.state,
            empty.postalCode, empty.countryCode,
        )) {
            assertNull("an unset billing field must be null, never a placeholder", value)
        }
    }

    @Test
    fun `equality covers every field, so no property can be added and quietly ignored`() {
        assertEquals(full(), full())
        assertEquals(full().hashCode(), full().hashCode())
        assertNotEquals(full(), PaymentSessionParams.BillingDetails())

        // One field at a time: a copy differing only in that field must not compare equal.
        val mutations = listOf(
            full(firstName = "Jane"), full(lastName = "Lim"),
            full(email = "other@example.com"), full(phone = "+6598765432"),
            full(addressLine1 = "1 Other Road"), full(addressLine2 = "#02-02"),
            full(city = "Jurong"), full(state = "Central"),
            full(postalCode = "111111"), full(countryCode = "MY"),
        )
        for (mutated in mutations) {
            assertNotEquals("equals must read every field: $mutated", full(), mutated)
        }
        assertEquals("ten fields, ten mutations — one per property", 10, mutations.size)
    }

    @Test
    fun `session params equality reads the prefill too`() {
        assertEquals(PaymentSessionParams("PI_1"), PaymentSessionParams("PI_1"))
        assertNotEquals(
            PaymentSessionParams("PI_1"),
            PaymentSessionParams("PI_1", billingDetails = full()),
        )
        assertEquals(
            PaymentSessionParams("PI_1", billingDetails = full()),
            PaymentSessionParams("PI_1", billingDetails = full()),
        )
    }

    @Test
    fun `session params toString carries the prefill through the same redaction`() {
        val rendered = PaymentSessionParams("PI_1", billingDetails = full()).toString()
        assertFalse(rendered.contains("john.tan@example.com"))
        assertFalse(rendered.contains("+6591234567"))
        assertTrue(rendered.contains("PI_1"))
    }

    // ---- the builder names what it sets -----------------------------------------------------
    //
    // The constructor takes ten String? in a row, six of them address lines. From Java there
    // are no named arguments, so `new BillingDetails(…, "Singapore", "Singapore", …)` with
    // city and state transposed compiles clean and sends wrong AVS data on every payment
    // forever. The builder is what makes the field name appear beside the value.

    @Test
    fun `the builder produces exactly what named arguments produce`() {
        val built = PaymentSessionParams.BillingDetails.Builder()
            .firstName("John")
            .lastName("Tan")
            .email("john.tan@example.com")
            .phone("+6591234567")
            .addressLine1("123 Orchard Road")
            .addressLine2("#12-01")
            .city("Singapore")
            .state("Singapore")
            .postalCode("238888")
            .countryCode("SG")
            .build()

        assertEquals(full(), built)
    }

    @Test
    fun `a builder with nothing set is the same as the no-argument constructor`() {
        assertEquals(
            PaymentSessionParams.BillingDetails(),
            PaymentSessionParams.BillingDetails.Builder().build(),
        )
    }

    @Test
    fun `only what is named is set, and the rest stay absent`() {
        val built = PaymentSessionParams.BillingDetails.Builder()
            .city("Singapore")
            .countryCode("SG")
            .build()

        assertEquals("Singapore", built.city)
        assertEquals("SG", built.countryCode)
        assertNull(built.state)
        assertNull(built.firstName)
        assertNull(built.email)
    }

    /**
     * The transposition the builder exists to prevent, written out: `city` and `state` hold
     * the same value in the Singapore case, so getting them the wrong way round is invisible
     * there — but the builder makes the *intent* explicit at the call site, and a reviewer
     * reading `.state("Singapore")` can see which field was meant.
     */
    @Test
    fun `city and state are set independently, whatever their values`() {
        val built = PaymentSessionParams.BillingDetails.Builder()
            .city("Klang")
            .state("Selangor")
            .build()

        assertEquals("Klang", built.city)
        assertEquals("Selangor", built.state)
    }

    @Test
    fun `setting a field twice keeps the last value`() {
        val built = PaymentSessionParams.BillingDetails.Builder()
            .city("Klang")
            .city("Shah Alam")
            .build()

        assertEquals("Shah Alam", built.city)
    }

    @Test
    fun `building twice gives equal but independent values`() {
        val builder = PaymentSessionParams.BillingDetails.Builder().city("Klang")
        val first = builder.build()
        val second = builder.build()

        assertEquals(first, second)
        assertTrue("build() must not return the same instance twice", first !== second)
    }

    @Test
    fun `a builder result redacts the customer's contact details in toString like any other`() {
        val built = PaymentSessionParams.BillingDetails.Builder()
            .email("john.tan@example.com")
            .phone("+6591234567")
            .build()

        val rendered = built.toString()
        assertFalse(rendered.contains("john.tan@example.com"))
        assertFalse(rendered.contains("+6591234567"))
    }

    private fun full(
        firstName: String? = "John",
        lastName: String? = "Tan",
        email: String? = "john.tan@example.com",
        phone: String? = "+6591234567",
        addressLine1: String? = "123 Orchard Road",
        addressLine2: String? = "#12-01",
        city: String? = "Singapore",
        state: String? = "Singapore",
        postalCode: String? = "238888",
        countryCode: String? = "SG",
    ) = PaymentSessionParams.BillingDetails(
        firstName = firstName,
        lastName = lastName,
        email = email,
        phone = phone,
        addressLine1 = addressLine1,
        addressLine2 = addressLine2,
        city = city,
        state = state,
        postalCode = postalCode,
        countryCode = countryCode,
    )
}
