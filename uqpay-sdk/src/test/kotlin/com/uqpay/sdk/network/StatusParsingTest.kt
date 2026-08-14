package com.uqpay.sdk.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-status decoding (api-contract §3.2/§3.3, gaps G5 and G6).
 *
 * Two things must hold for a shipped app: a status this SDK version predates is
 * preserved verbatim and never an error, and both `CANCELLED` and `CANCELED` decode to
 * the same thing — the API is not consistent about the spelling, and a payment that
 * decodes as "unknown" instead of "cancelled" is a payment the merchant cannot close.
 */
class StatusParsingTest {

    // ---------------------------------------------------------------- intent statuses

    @Test
    fun `every documented intent status decodes`() {
        val expected = mapOf(
            "REQUIRES_PAYMENT_METHOD" to IntentStatus.RequiresPaymentMethod,
            "REQUIRES_CUSTOMER_ACTION" to IntentStatus.RequiresCustomerAction,
            "REQUIRES_CAPTURE" to IntentStatus.RequiresCapture,
            "PENDING" to IntentStatus.Pending,
            "SUCCEEDED" to IntentStatus.Succeeded,
            "CANCELLED" to IntentStatus.Cancelled,
            "FAILED" to IntentStatus.Failed,
        )
        expected.forEach { (raw, status) -> assertEquals(raw, status, IntentStatus.from(raw)) }
    }

    @Test
    fun `an intent status decodes regardless of case or surrounding whitespace`() {
        assertEquals(IntentStatus.Succeeded, IntentStatus.from("succeeded"))
        assertEquals(IntentStatus.Succeeded, IntentStatus.from("  SUCCEEDED  "))
        assertEquals(IntentStatus.RequiresCustomerAction, IntentStatus.from("Requires_Customer_Action"))
    }

    @Test
    fun `both CANCELLED and CANCELED decode to cancelled`() {
        // G6 — the API uses both spellings.
        assertEquals(IntentStatus.Cancelled, IntentStatus.from("CANCELLED"))
        assertEquals(IntentStatus.Cancelled, IntentStatus.from("CANCELED"))
        assertEquals(IntentStatus.Cancelled, IntentStatus.from("canceled"))
    }

    @Test
    fun `an unknown intent status is preserved verbatim, never an error`() {
        val status = IntentStatus.from("REQUIRES_SOMETHING_NEW")
        assertEquals(IntentStatus.Unknown("REQUIRES_SOMETHING_NEW"), status)
        assertEquals("REQUIRES_SOMETHING_NEW", (status as IntentStatus.Unknown).raw)
    }

    @Test
    fun `an unknown intent status keeps the raw casing it arrived with`() {
        assertEquals("someFutureStatus", (IntentStatus.from("someFutureStatus") as IntentStatus.Unknown).raw)
    }

    @Test
    fun `a null or blank intent status is unknown with an empty raw`() {
        // G5: the API sends "" rather than omitting the field.
        listOf(null, "", "   ").forEach { raw ->
            assertEquals("raw=<$raw>", IntentStatus.Unknown(""), IntentStatus.from(raw))
        }
    }

    @Test
    fun `only settled intent statuses are terminal`() {
        val terminal = listOf(IntentStatus.Succeeded, IntentStatus.Cancelled, IntentStatus.Failed)
        val nonTerminal = listOf(
            IntentStatus.RequiresPaymentMethod,
            IntentStatus.RequiresCustomerAction,
            IntentStatus.RequiresCapture,
            IntentStatus.Pending,
            IntentStatus.Unknown("SOMETHING_NEW"),
        )
        terminal.forEach { assertTrue("$it", it.isTerminal) }
        nonTerminal.forEach { assertFalse("$it", it.isTerminal) }
    }

    @Test
    fun `an intent that can still be paid is payable`() {
        listOf(
            IntentStatus.RequiresPaymentMethod,
            IntentStatus.RequiresCustomerAction,
            IntentStatus.Pending,
            IntentStatus.Unknown("SOMETHING_NEW"),
        ).forEach { assertTrue("$it", it.isPayable) }
    }

    @Test
    fun `a settled intent is never payable`() {
        listOf(IntentStatus.Succeeded, IntentStatus.Cancelled, IntentStatus.Failed)
            .forEach { assertFalse("$it", it.isPayable) }
    }

    @Test
    fun `requires capture is not payable but is not terminal either`() {
        // The deliberate asymmetry: an authorised payment must not be charged again, but
        // it also has not finished, and the outcome watchers report it as customer-paid.
        assertFalse(IntentStatus.RequiresCapture.isPayable)
        assertFalse(IntentStatus.RequiresCapture.isTerminal)
    }

    // ---------------------------------------------------------------- attempt statuses

    @Test
    fun `every documented attempt status decodes`() {
        val expected = mapOf(
            "INITIATED" to AttemptStatus.Initiated,
            "AUTHENTICATION_REDIRECTED" to AttemptStatus.AuthenticationRedirected,
            "PENDING_AUTHORIZATION" to AttemptStatus.PendingAuthorization,
            "AUTHORIZED" to AttemptStatus.Authorized,
            "CAPTURE_REQUESTED" to AttemptStatus.CaptureRequested,
            "SETTLED" to AttemptStatus.Settled,
            "SUCCEEDED" to AttemptStatus.Succeeded,
            "CANCELLED" to AttemptStatus.Cancelled,
            "EXPIRED" to AttemptStatus.Expired,
            "FAILED" to AttemptStatus.Failed,
        )
        expected.forEach { (raw, status) -> assertEquals(raw, status, AttemptStatus.from(raw)) }
    }

    @Test
    fun `both attempt cancellation spellings decode to cancelled`() {
        assertEquals(AttemptStatus.Cancelled, AttemptStatus.from("CANCELLED"))
        assertEquals(AttemptStatus.Cancelled, AttemptStatus.from("CANCELED"))
    }

    @Test
    fun `an unknown attempt status is preserved verbatim`() {
        val status = AttemptStatus.from("PARTIALLY_REFUNDED")
        assertEquals(AttemptStatus.Unknown("PARTIALLY_REFUNDED"), status)
    }

    @Test
    fun `a null or blank attempt status is unknown with an empty raw`() {
        listOf(null, "", "  ").forEach { raw ->
            assertEquals("raw=<$raw>", AttemptStatus.Unknown(""), AttemptStatus.from(raw))
        }
    }

    @Test
    fun `only settled attempt statuses are terminal`() {
        listOf(
            AttemptStatus.Succeeded,
            AttemptStatus.Cancelled,
            AttemptStatus.Expired,
            AttemptStatus.Failed,
        ).forEach { assertTrue("$it", it.isTerminal) }

        listOf(
            AttemptStatus.Initiated,
            AttemptStatus.AuthenticationRedirected,
            AttemptStatus.PendingAuthorization,
            AttemptStatus.Authorized,
            AttemptStatus.CaptureRequested,
            AttemptStatus.Settled,
            AttemptStatus.Unknown("SOMETHING_NEW"),
        ).forEach { assertFalse("$it", it.isTerminal) }
    }

    @Test
    fun `capture requested is not terminal even though it signals success`() {
        // api-contract §3.3 footnote: the guide says this "also indicates the payment has
        // succeeded", but the state table says it is not terminal. Both are honoured —
        // this flag follows the state table; success reporting is a separate decision.
        assertFalse(AttemptStatus.CaptureRequested.isTerminal)
    }

    @Test
    fun `a failed attempt is terminal even while the intent is still payable`() {
        // G4: REQUIRES_PAYMENT_METHOD + a FAILED attempt is a decline the merchant must
        // hear about, not an invitation to fill in the card form again.
        assertTrue(AttemptStatus.from("FAILED").isTerminal)
        assertTrue(IntentStatus.from("REQUIRES_PAYMENT_METHOD").isPayable)
    }
}
