package com.uqpay.sdk.network

import com.uqpay.sdk.Environment
import com.uqpay.sdk.error.UQPayError
import com.uqpay.sdk.error.UQPayErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Modifier

/**
 * Acceptance criteria §6.2 — the headline test.
 *
 * Every code this SDK declares must be produced by at least one **real** input to the
 * one shared [ErrorMapper]. A declared-but-unreachable code is dead documentation; a
 * failure path that cannot reach the code it deserves is the iOS bug this SDK exists to
 * avoid (wallet screens returned `unknown` for failures the card screen mapped
 * properly — ios-requirements §4.4, audit E1/E2).
 *
 * The table below is the whole point: it is built from inputs, and the reachable set is
 * *derived*, never hand-listed. Adding a code to [UQPayErrorCode] without giving it a
 * path here fails this test.
 */
class ErrorMapperReachabilityTest {

    private val mapper = ErrorMapper(Environment.PRODUCTION)

    /**
     * [UQPayErrorCode.NOT_INITIALIZED] is deliberately **not** reachable from
     * [ErrorMapper] in slice 1. It describes calling a payment API before
     * `UQPay.initialize`, which is raised by the SDK entry point (a later slice), not by
     * the network layer. It is named here explicitly rather than the assertion being
     * loosened to "at least most codes".
     */
    private val expectedUnreachable = setOf(UQPayErrorCode.NOT_INITIALIZED)

    private class Case(val description: String, val expected: UQPayErrorCode, val actual: UQPayError)

    private fun apiError(code: String?, status: Int, message: String? = "gateway text") =
        UQPayApiException.ApiError(ApiErrorBody(code = code, message = message), "trace-1", status)

    private val cases: List<Case> = listOf(
        case("a request that never left the device", UQPayErrorCode.INVALID_CONFIGURATION) {
            mapper.map(UQPayApiException.NotConfigured("no environment"))
        },
        case("the host app's token provider failed", UQPayErrorCode.AUTHENTICATION_FAILED) {
            mapper.map(UQPayApiException.AuthenticationFailed("no token"))
        },
        case("HTTP 401 with no parsable body", UQPayErrorCode.AUTHENTICATION_FAILED) {
            mapper.map(UQPayApiException.UnexpectedStatus(401, "trace-1"))
        },
        case("no response arrived", UQPayErrorCode.NETWORK_ERROR) {
            mapper.map(UQPayApiException.TransportFailure(IOException("socket closed")))
        },
        case("the request exceeded its deadline", UQPayErrorCode.TIMEOUT) {
            mapper.map(UQPayApiException.TimedOut())
        },
        case("the caller cancelled locally", UQPayErrorCode.CANCELLED) {
            mapper.map(UQPayApiException.Cancelled())
        },
        // Unresolved, not failed: the request reached the gateway and was acted on, we
        // simply could not read the answer. SERVER_ERROR would attach "please try again"
        // to a payment that may already have been processed.
        case("a 2xx that could not be read", UQPayErrorCode.TIMEOUT) {
            mapper.map(UQPayApiException.DecodingFailure(200, "trace-1", IOException()))
        },
        case("an idempotent request still in flight", UQPayErrorCode.TIMEOUT) {
            mapper.map(
                UQPayApiException.IdempotencyInFlight(
                    ApiErrorBody(code = "200", message = "Request is processing, please try again later."),
                    "trace-1",
                    400,
                ),
            )
        },
        case("HTTP 500 with no parsable body", UQPayErrorCode.SERVER_ERROR) {
            mapper.map(UQPayApiException.UnexpectedStatus(500, "trace-1"))
        },
        case("HTTP 400 with no parsable body", UQPayErrorCode.INVALID_REQUEST) {
            mapper.map(UQPayApiException.UnexpectedStatus(400, "trace-1"))
        },
        case("HTTP 402", UQPayErrorCode.CARD_DECLINED) {
            mapper.map(UQPayApiException.UnexpectedStatus(402, "trace-1"))
        },
        case("api code card_declined", UQPayErrorCode.CARD_DECLINED) {
            mapper.map(apiError("card_declined", 400))
        },
        case("api code insufficient_funds", UQPayErrorCode.INSUFFICIENT_FUNDS) {
            mapper.map(apiError("insufficient_funds", 400))
        },
        case("api code invalid_payment_method", UQPayErrorCode.INVALID_PAYMENT_METHOD) {
            mapper.map(apiError("invalid_payment_method", 400))
        },
        case("api code 3ds_failed", UQPayErrorCode.THREE_DS_FAILED) {
            mapper.map(apiError("3ds_failed", 400))
        },
        case("api code expired_order", UQPayErrorCode.INTENT_NOT_PAYABLE) {
            mapper.map(apiError("expired_order", 400))
        },
        case("an unexpected throwable", UQPayErrorCode.UNKNOWN) {
            mapper.map(IllegalStateException("boom"))
        },
        case("HTTP 418 — a status with no bucket", UQPayErrorCode.UNKNOWN) {
            mapper.map(UQPayApiException.UnexpectedStatus(418, null))
        },
        case("a settled-cancelled intent", UQPayErrorCode.CANCELLED) {
            mapper.mapSettledOutcome(IntentStatus.Cancelled, failureCode = "do_not_honor")
        },
        case("a settled-failed intent with 3ds_failed", UQPayErrorCode.THREE_DS_FAILED) {
            mapper.mapSettledOutcome(IntentStatus.Failed, failureCode = "3ds_failed")
        },
        case("a settled-failed intent with insufficient_funds", UQPayErrorCode.INSUFFICIENT_FUNDS) {
            mapper.mapSettledOutcome(IntentStatus.Failed, failureCode = "insufficient_funds")
        },
        case("a settled-failed intent with no failure code", UQPayErrorCode.CARD_DECLINED) {
            mapper.mapSettledOutcome(IntentStatus.Failed, failureCode = null)
        },
    )

    private fun case(description: String, expected: UQPayErrorCode, produce: () -> UQPayError) =
        Case(description, expected, produce())

    @Test
    fun `every declared error code is reachable from at least one real input`() {
        val reachable = cases.map { it.actual.code }.toSet()
        val declared = declaredCodes()

        val unreachable = declared - reachable
        assertEquals(
            "Declared codes with no path through ErrorMapper: " +
                unreachable.joinToString { it.raw },
            expectedUnreachable,
            unreachable,
        )
    }

    @Test
    fun `the reachability table produces the code each input deserves`() {
        // Reachability alone is not enough: a table that reaches every code by accident
        // would still be wrong. Each row asserts its own mapping.
        cases.forEach { case ->
            assertEquals(case.description, case.expected, case.actual.code)
        }
    }

    @Test
    fun `no input in the table produces a code the SDK does not declare`() {
        val declared = declaredCodes()
        cases.forEach { case ->
            assertTrue(
                "${case.description} produced undeclared code ${case.actual.code.raw}",
                case.actual.code in declared,
            )
        }
    }

    @Test
    fun `the codes the SDK declares are exactly the ones documented in error-codes`() {
        // Guards the other direction: a code added in source but never documented.
        val expected = setOf(
            "not_initialized", "invalid_configuration", "invalid_request",
            "invalid_payment_method", "network_error", "timeout", "authentication_failed",
            "card_declined", "insufficient_funds", "3ds_failed", "cancelled",
            "intent_not_payable", "server_error", "unknown",
        )
        assertEquals(expected, declaredCodes().map { it.raw }.toSet())
    }

    private fun declaredCodes(): Set<UQPayErrorCode> =
        UQPayErrorCode::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == UQPayErrorCode::class.java }
            .map { it.isAccessible = true; it.get(null) as UQPayErrorCode }
            .toSet()
}
