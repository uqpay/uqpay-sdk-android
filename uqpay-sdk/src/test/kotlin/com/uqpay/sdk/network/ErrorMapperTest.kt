package com.uqpay.sdk.network

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.uqpay.sdk.testErrorCopy
import com.uqpay.sdk.Environment
import com.uqpay.sdk.error.UQPayError
import com.uqpay.sdk.error.UQPayErrorCode
import com.uqpay.sdk.payment.PaymentMethodType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The single mapping chokepoint (ios-requirements §4.4, error-codes.md).
 *
 * Two rules carry money: the API `code` outranks the HTTP status, and an *unrecognised*
 * code must never be flattened into a decline — telling a merchant a card was refused
 * when the request was merely malformed is a bug that shipped on another platform.
 */
@RunWith(RobolectricTestRunner::class)
class ErrorMapperTest {

    private val production = ErrorMapper(Environment.PRODUCTION, testErrorCopy())
    private val sandbox = ErrorMapper(Environment.SANDBOX, testErrorCopy())

    private fun apiError(
        code: String? = null,
        status: Int = 400,
        message: String? = null,
        type: String? = "payment_error",
        traceId: String? = "trace-1",
    ): UQPayApiException = UQPayApiException.ApiError(
        ApiErrorBody(type = type, code = code, message = message),
        traceId,
        status,
    )

    // ---------------------------------------------------------------- api code table

    @Test
    fun `api code card_declined maps to card declined`() {
        assertEquals(UQPayErrorCode.CARD_DECLINED, production.map(apiError("card_declined")).code)
    }

    @Test
    fun `api code do_not_honor maps to card declined`() {
        assertEquals(UQPayErrorCode.CARD_DECLINED, production.map(apiError("do_not_honor")).code)
    }

    @Test
    fun `api code insufficient_funds maps to insufficient funds`() {
        assertEquals(
            UQPayErrorCode.INSUFFICIENT_FUNDS,
            production.map(apiError("insufficient_funds")).code,
        )
    }

    @Test
    fun `api code invalid_payment_method maps to invalid payment method`() {
        assertEquals(
            UQPayErrorCode.INVALID_PAYMENT_METHOD,
            production.map(apiError("invalid_payment_method")).code,
        )
    }

    @Test
    fun `api code 3ds_failed maps to three ds failed`() {
        assertEquals(UQPayErrorCode.THREE_DS_FAILED, production.map(apiError("3ds_failed")).code)
    }

    @Test
    fun `api code 3ds_required maps to three ds failed`() {
        // Documented in api-contract §6.3 as a confirm-time code.
        assertEquals(UQPayErrorCode.THREE_DS_FAILED, production.map(apiError("3ds_required")).code)
    }

    @Test
    fun `api codes for a settled or expired intent map to intent not payable`() {
        listOf("expired_order", "order_cancelled", "invalid_order_status", "repeat_payment_request")
            .forEach { code ->
                assertEquals(code, UQPayErrorCode.INTENT_NOT_PAYABLE, production.map(apiError(code)).code)
            }
    }

    @Test
    fun `api codes for a malformed request map to invalid request`() {
        listOf(
            "invalid_parameter", "missing_parameter", "invalid_order_amount",
            "invalid_order_currency", "invalid_description", "invalid_return_url",
            "not_found_id", "invalid_payment_orders",
        ).forEach { code ->
            assertEquals(code, UQPayErrorCode.INVALID_REQUEST, production.map(apiError(code)).code)
        }
    }

    @Test
    fun `an api code is matched case-insensitively`() {
        assertEquals(UQPayErrorCode.CARD_DECLINED, production.map(apiError("CARD_DECLINED")).code)
    }

    // ---------------------------------------------------------------- code beats status

    @Test
    fun `the api code wins over the http status`() {
        // HTTP 500 alone would be SERVER_ERROR; the gateway's code is more specific.
        assertEquals(
            UQPayErrorCode.INSUFFICIENT_FUNDS,
            production.map(apiError("insufficient_funds", status = 500)).code,
        )
        // HTTP 401 alone would be AUTHENTICATION_FAILED.
        assertEquals(
            UQPayErrorCode.CARD_DECLINED,
            production.map(apiError("card_declined", status = 401)).code,
        )
    }

    @Test
    fun `an unrecognised api code falls through to the http status`() {
        assertEquals(
            UQPayErrorCode.INVALID_REQUEST,
            production.map(apiError("some_code_we_have_never_seen", status = 400)).code,
        )
        assertEquals(
            UQPayErrorCode.AUTHENTICATION_FAILED,
            production.map(apiError("some_code_we_have_never_seen", status = 401)).code,
        )
        assertEquals(
            UQPayErrorCode.SERVER_ERROR,
            production.map(apiError("some_code_we_have_never_seen", status = 503)).code,
        )
    }

    @Test
    fun `an unrecognised api code is never reported as a decline`() {
        // The specific historical bug: a malformed or unauthenticated request reported
        // as "your card was refused".
        listOf(400, 401, 403, 404, 422, 429, 500, 503).forEach { status ->
            val mapped = production.map(apiError("velocity_limit_exceeded", status = status))
            assertFalse(
                "HTTP $status with an unknown code became a decline",
                mapped.code == UQPayErrorCode.CARD_DECLINED,
            )
        }
    }

    @Test
    fun `a rate limit body is a server error, not a decline`() {
        // api-contract §8: HTTP 429 with {"code":"too_many_requests", …}.
        val mapped = production.map(
            apiError("too_many_requests", status = 429, message = "Too many requests"),
        )
        assertEquals(UQPayErrorCode.SERVER_ERROR, mapped.code)
    }

    // ---------------------------------------------------------------- http status table

    @Test
    fun `http statuses map per the documented fallback table`() {
        val expectations = mapOf(
            400 to UQPayErrorCode.INVALID_REQUEST,
            401 to UQPayErrorCode.AUTHENTICATION_FAILED,
            402 to UQPayErrorCode.CARD_DECLINED,
            403 to UQPayErrorCode.AUTHENTICATION_FAILED,
            404 to UQPayErrorCode.INVALID_REQUEST,
            422 to UQPayErrorCode.INVALID_REQUEST,
            429 to UQPayErrorCode.SERVER_ERROR,
            500 to UQPayErrorCode.SERVER_ERROR,
            502 to UQPayErrorCode.SERVER_ERROR,
            503 to UQPayErrorCode.SERVER_ERROR,
            599 to UQPayErrorCode.SERVER_ERROR,
            418 to UQPayErrorCode.UNKNOWN,
            0 to UQPayErrorCode.UNKNOWN,
        )
        expectations.forEach { (status, expected) ->
            assertEquals(
                "HTTP $status",
                expected,
                production.map(UQPayApiException.UnexpectedStatus(status, null)).code,
            )
        }
    }

    @Test
    fun `internal failure classes map to their own codes`() {
        assertEquals(
            UQPayErrorCode.INVALID_CONFIGURATION,
            production.map(UQPayApiException.NotConfigured("no intent id")).code,
        )
        assertEquals(
            UQPayErrorCode.AUTHENTICATION_FAILED,
            production.map(UQPayApiException.AuthenticationFailed("no token")).code,
        )
        assertEquals(
            UQPayErrorCode.NETWORK_ERROR,
            production.map(UQPayApiException.TransportFailure(IOException())).code,
        )
        assertEquals(UQPayErrorCode.TIMEOUT, production.map(UQPayApiException.TimedOut()).code)
        assertEquals(UQPayErrorCode.CANCELLED, production.map(UQPayApiException.Cancelled()).code)
    }

    @Test
    fun `an arbitrary throwable becomes unknown rather than escaping`() {
        val mapped: UQPayError = production.map(RuntimeException("kaboom") as Throwable)
        assertEquals(UQPayErrorCode.UNKNOWN, mapped.code)
        assertNull(mapped.declineCode)
        assertNull(mapped.traceId)
    }

    /**
     * An arbitrary throwable's message is **never** the server detail. A `RuntimeException`
     * raised deep in the stack can quote whatever it was handling — a request body with a
     * card number in it — and the sandbox message path appends any detail it is given. The
     * detail must be null here (audit ac-audit-slice-2 M-1: passing `t.message` survived
     * every other test).
     */
    @Test
    fun `an arbitrary throwable's message never reaches the merchant, in either environment`() {
        // Test PAN 4111 1111 1111 1111 (documented test value, not a real card).
        val leaky = RuntimeException("body {\"number\":\"4111111111111111\"} rejected")
        val fixedUnknownCopy = "The payment could not be completed."

        val inSandbox = sandbox.map(leaky as Throwable)
        assertEquals(UQPayErrorCode.UNKNOWN, inSandbox.code)
        assertEquals(fixedUnknownCopy, inSandbox.message)
        assertFalse(inSandbox.message.contains("4111"))
        assertFalse(inSandbox.message.contains("rejected"))

        val inProduction = production.map(leaky as Throwable)
        assertEquals(UQPayErrorCode.UNKNOWN, inProduction.code)
        assertEquals(fixedUnknownCopy, inProduction.message)
        assertFalse(inProduction.message.contains("4111"))
    }

    @Test
    fun `a UQPayApiException passed as a throwable takes the typed path`() {
        val mapped = production.map(apiError("card_declined") as Throwable)
        assertEquals(UQPayErrorCode.CARD_DECLINED, mapped.code)
        assertEquals("card_declined", mapped.declineCode)
    }

    // ---------------------------------------------------------------- passthrough fields

    @Test
    fun `the trace id and decline code are carried through for support`() {
        val mapped = production.map(apiError("do_not_honor", traceId = "req-abc"))
        assertEquals("req-abc", mapped.traceId)
        assertEquals("do_not_honor", mapped.declineCode)
    }

    @Test
    fun `a blank api code is normalised away rather than surfaced`() {
        // G5: the API sends "" instead of omitting the field.
        val mapped = production.map(apiError(code = "", status = 402, message = ""))
        assertNull(mapped.declineCode)
        assertEquals(UQPayErrorCode.CARD_DECLINED, mapped.code)
    }

    // ---------------------------------------------------------------- mapSettledOutcome

    @Test
    fun `a cancelled intent is cancelled regardless of the failure code`() {
        listOf("do_not_honor", "3ds_failed", "insufficient_funds", null, "").forEach { failureCode ->
            assertEquals(
                "failure_code=$failureCode",
                UQPayErrorCode.CANCELLED,
                production.mapSettledOutcome(IntentStatus.Cancelled, failureCode).code,
            )
        }
    }

    @Test
    fun `a settled failure code of 3ds_failed maps to three ds failed`() {
        assertEquals(
            UQPayErrorCode.THREE_DS_FAILED,
            production.mapSettledOutcome(IntentStatus.Failed, "3ds_failed").code,
        )
    }

    @Test
    fun `a settled failure code of insufficient_funds maps to insufficient funds`() {
        assertEquals(
            UQPayErrorCode.INSUFFICIENT_FUNDS,
            production.mapSettledOutcome(IntentStatus.Failed, "insufficient_funds").code,
        )
    }

    @Test
    fun `any other settled failure code on a card maps to card declined`() {
        listOf("do_not_honor", "issuer_unavailable", "fraud_suspected", "something_new")
            .forEach { failureCode ->
                assertEquals(
                    failureCode,
                    UQPayErrorCode.CARD_DECLINED,
                    production.mapSettledOutcome(
                        IntentStatus.Failed,
                        failureCode,
                        methodType = PaymentMethodType.CARD,
                    ).code,
                )
            }
    }

    @Test
    fun `a null or blank settled failure code on a card still maps to card declined`() {
        // G5 again: "" must behave exactly like a missing field.
        assertEquals(
            UQPayErrorCode.CARD_DECLINED,
            production.mapSettledOutcome(IntentStatus.Failed, null, methodType = PaymentMethodType.CARD).code,
        )
        val blank = production.mapSettledOutcome(
            IntentStatus.Failed,
            "   ",
            methodType = PaymentMethodType.CARD,
        )
        assertEquals(UQPayErrorCode.CARD_DECLINED, blank.code)
        assertNull("a blank failure code must never surface as a decline code", blank.declineCode)
    }

    /**
     * **`card_declined` is a claim about a card, and it may only be made about a card.**
     *
     * Its fixed copy says so out loud — "The card was declined. Please try a different payment
     * method." — and the common way to reach the unexplained-failure branch is a wallet QR that
     * expired unscanned. Telling a customer who never entered a card that their card was
     * refused points them at a fix that does not exist, and files the failure under card
     * declines in the merchant's analytics.
     *
     * A wallet, and an attempt whose method the intent never named, both get `UNKNOWN`, whose
     * copy describes a failure the gateway declined to characterise. This is the *fallback*
     * only: a code the gateway was explicit about is honoured whatever the method — see below.
     */
    @Test
    fun `an unexplained wallet failure is never reported as a card decline`() {
        listOf(PaymentMethodType.GRABPAY, PaymentMethodType.PAYNOW, PaymentMethodType.ALIPAY_CN)
            .forEach { wallet ->
                assertEquals(
                    "$wallet with no failure code",
                    UQPayErrorCode.UNKNOWN,
                    production.mapSettledOutcome(IntentStatus.Failed, null, methodType = wallet).code,
                )
                assertEquals(
                    "$wallet with an unrecognised failure code",
                    UQPayErrorCode.UNKNOWN,
                    production.mapSettledOutcome(IntentStatus.Failed, "qr_expired", methodType = wallet).code,
                )
            }
    }

    /** An unnamed method is not evidence of a card either. */
    @Test
    fun `an unexplained failure with no method named is unknown, not a card decline`() {
        assertEquals(
            UQPayErrorCode.UNKNOWN,
            production.mapSettledOutcome(IntentStatus.Failed, null).code,
        )
    }

    /** A code the gateway *did* send outranks the method every time. */
    @Test
    fun `a recognised failure code is honoured whatever the method`() {
        listOf(null, PaymentMethodType.CARD, PaymentMethodType.GRABPAY).forEach { method ->
            assertEquals(
                UQPayErrorCode.INSUFFICIENT_FUNDS,
                production.mapSettledOutcome(IntentStatus.Failed, "insufficient_funds", methodType = method).code,
            )
            assertEquals(
                UQPayErrorCode.THREE_DS_FAILED,
                production.mapSettledOutcome(IntentStatus.Failed, "3ds_failed", methodType = method).code,
            )
        }
    }

    @Test
    fun `a settled outcome on a non-terminal status still yields a decline`() {
        // G4: REQUIRES_PAYMENT_METHOD after a failed attempt is a decline, not a prompt.
        assertEquals(
            UQPayErrorCode.CARD_DECLINED,
            production.mapSettledOutcome(
                IntentStatus.RequiresPaymentMethod,
                "do_not_honor",
                methodType = PaymentMethodType.CARD,
            ).code,
        )
    }

    @Test
    fun `a settled failure code is matched case-insensitively but preserved verbatim`() {
        val mapped = production.mapSettledOutcome(IntentStatus.Failed, "Insufficient_Funds")
        assertEquals(UQPayErrorCode.INSUFFICIENT_FUNDS, mapped.code)
        assertEquals("Insufficient_Funds", mapped.declineCode)
    }

    // ---------------------------------------------------------------- message safety §4.1

    @Test
    fun `production never repeats the gateway's own text`() {
        val gatewayText = "Issuer host 10.2.3.4 refused: acct 4111111111111111 blocked"
        val mapped = production.map(apiError("card_declined", message = gatewayText))

        assertFalse(mapped.message.contains("10.2.3.4"))
        assertFalse(mapped.message.contains("4111111111111111"))
        assertFalse(mapped.message.contains(gatewayText))
        assertEquals("The card was declined. Please try a different payment method.", mapped.message)
    }

    @Test
    fun `the production message for a code is fixed whatever the server said`() {
        val messages = listOf(
            null,
            "",
            "Insufficient balance on account 12345",
            "<html><body>502 Bad Gateway</body></html>",
        ).map { production.map(apiError("insufficient_funds", message = it)).message }

        assertEquals(1, messages.toSet().size)
        assertEquals("The card was declined for insufficient funds.", messages.first())
    }

    @Test
    fun `the production settled-outcome message is fixed whatever the attempt reported`() {
        val messages = listOf(null, "", "Bank said no — customer ref 998877")
            .map { production.mapSettledOutcome(IntentStatus.Failed, "3ds_failed", it).message }

        assertEquals(1, messages.toSet().size)
        assertEquals("The payment could not be verified with your bank.", messages.first())
    }

    /**
     * Sandbox detail belongs to the **developer** sentence, and only to it.
     *
     * The gateway's own text used to be appended to `message` in sandbox, which made the one
     * string a merchant is told to show a customer mean two different things depending on
     * which environment the build pointed at. It now goes where it was always for: a log
     * line. `message` is identical in both environments, which is the property that makes it
     * safe to put on a screen.
     */
    @Test
    fun `sandbox puts the gateway detail in the developer message, never in the customer one`() {
        val mapped = sandbox.map(apiError("card_declined", message = "Do not honour"))

        assertEquals("The card was declined. Please try a different payment method.", mapped.message)
        assertFalse(mapped.message.contains("Do not honour"))

        val developer = mapped.developerMessage.orEmpty()
        assertTrue(developer.contains("Do not honour"))
        assertTrue(developer.contains("card_declined"))
    }

    @Test
    fun `production never puts the gateway detail in either sentence`() {
        val mapped = production.map(apiError("card_declined", message = "Do not honour"))

        assertFalse(mapped.message.contains("Do not honour"))
        assertFalse(mapped.developerMessage.orEmpty().contains("Do not honour"))
        // The developer sentence still exists and still says something useful.
        assertTrue(mapped.developerMessage.orEmpty().contains("card_declined"))
    }

    /**
     * The two failures an integrator hits most, and the two whose developer sentence is
     * worth writing by hand: both are the merchant's own setup, and neither is anything the
     * customer or their card did.
     */
    @Test
    fun `the developer message names the merchant's own setup for configuration failures`() {
        val notConfigured = production.map(UQPayApiException.NotConfigured("x"))
        assertTrue(notConfigured.developerMessage.orEmpty().contains("UQPay.initialize"))

        val unauthenticated = production.map(UQPayApiException.AuthenticationFailed("x"))
        assertTrue(unauthenticated.developerMessage.orEmpty().contains("UQPayTokenProvider"))
        // And the customer is told to contact the store, not to keep retrying a token they
        // have no part in.
        assertFalse(unauthenticated.message.contains("try again", ignoreCase = true))
    }

    @Test
    fun `sandbox falls back to the fixed sentence when the gateway sent nothing`() {
        listOf(null, "", "   ").forEach { detail ->
            assertEquals(
                "The card was declined. Please try a different payment method.",
                sandbox.map(apiError("card_declined", message = detail)).message,
            )
        }
    }

    @Test
    fun `the machine code never appears in the sentence a customer reads`() {
        val cases = listOf(
            production.map(apiError("card_declined", message = "card_declined")),
            production.map(apiError("insufficient_funds")),
            production.map(apiError("3ds_failed")),
            production.map(apiError("invalid_payment_method")),
            production.map(UQPayApiException.UnexpectedStatus(500, null)),
            production.mapSettledOutcome(IntentStatus.Failed, "3ds_failed"),
            production.mapSettledOutcome(IntentStatus.Cancelled, null),
            production.map(RuntimeException() as Throwable),
        )
        cases.forEach { error ->
            // A machine identifier is snake_case or digit-led (`card_declined`,
            // `3ds_failed`). The English word "cancelled" appearing in "The payment was
            // cancelled." is prose, not the identifier — the check targets the shape.
            assertFalse(
                "message leaked a machine identifier: ${error.message}",
                error.message.contains('_') || error.message.contains(Regex("""\b\d\w""")),
            )
            listOfNotNull(error.code.raw, error.declineCode)
                .filter { it.contains('_') }
                .forEach { identifier ->
                    assertFalse(
                        "message leaked $identifier: ${error.message}",
                        error.message.contains(identifier),
                    )
                }
        }
    }

    @Test
    fun `every declared code has a written message rather than the generic fallback`() {
        // A code whose message is the generic sentence gives the merchant nothing to
        // show. UNKNOWN is the one legitimate user of the fallback.
        val codeToError = mapOf(
            UQPayErrorCode.INVALID_CONFIGURATION to production.map(UQPayApiException.NotConfigured("x")),
            UQPayErrorCode.INVALID_REQUEST to production.map(apiError("invalid_parameter")),
            UQPayErrorCode.INVALID_PAYMENT_METHOD to production.map(apiError("invalid_payment_method")),
            UQPayErrorCode.NETWORK_ERROR to production.map(UQPayApiException.TransportFailure(IOException())),
            UQPayErrorCode.TIMEOUT to production.map(UQPayApiException.TimedOut()),
            UQPayErrorCode.AUTHENTICATION_FAILED to production.map(UQPayApiException.AuthenticationFailed("x")),
            UQPayErrorCode.CARD_DECLINED to production.map(apiError("card_declined")),
            UQPayErrorCode.INSUFFICIENT_FUNDS to production.map(apiError("insufficient_funds")),
            UQPayErrorCode.THREE_DS_FAILED to production.map(apiError("3ds_failed")),
            UQPayErrorCode.CANCELLED to production.map(UQPayApiException.Cancelled()),
            UQPayErrorCode.INTENT_NOT_PAYABLE to production.map(apiError("expired_order")),
            UQPayErrorCode.SERVER_ERROR to production.map(UQPayApiException.UnexpectedStatus(500, null)),
        )
        codeToError.forEach { (code, error) ->
            assertEquals(code, error.code)
            assertTrue("${code.raw} has no message", error.message.isNotBlank())
            assertFalse(
                "${code.raw} falls back to the generic sentence",
                error.message == "The payment could not be completed.",
            )
        }
    }
}
