package com.uqpay.sdk.error

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The sentences a shopper reads.
 *
 * These used to be a `when (code) -> "…"` in `ErrorMapper`, which broke the SDK's own rule
 * — nothing in Kotlin may hardcode customer-facing text — and made the SDK untranslatable
 * without an API change. The tests below are about the two properties that rule protects:
 * every code resolves to a real sentence from resources, and no sentence tells a shopper
 * something only an integrator can act on.
 */
@RunWith(RobolectricTestRunner::class)
class ErrorCopyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val copy = ErrorCopy.from(context)

    /** Every code this SDK version declares. Kept here so a new one fails this file first. */
    private val declared = listOf(
        UQPayErrorCode.NOT_INITIALIZED,
        UQPayErrorCode.INVALID_CONFIGURATION,
        UQPayErrorCode.INVALID_REQUEST,
        UQPayErrorCode.INVALID_PAYMENT_METHOD,
        UQPayErrorCode.NETWORK_ERROR,
        UQPayErrorCode.TIMEOUT,
        UQPayErrorCode.AUTHENTICATION_FAILED,
        UQPayErrorCode.CARD_DECLINED,
        UQPayErrorCode.INSUFFICIENT_FUNDS,
        UQPayErrorCode.THREE_DS_FAILED,
        UQPayErrorCode.CANCELLED,
        UQPayErrorCode.INTENT_NOT_PAYABLE,
        UQPayErrorCode.SERVER_ERROR,
        UQPayErrorCode.UNKNOWN,
    )

    @Test
    fun `every declared code resolves to a complete sentence`() {
        declared.forEach { code ->
            val sentence = copy.forCode(code)
            assertTrue("${code.raw} has no sentence", sentence.isNotBlank())
            assertTrue("${code.raw} is not a sentence: $sentence", sentence.endsWith("."))
        }
    }

    /**
     * The type is deliberately not an enum, so a code the gateway adds after this release
     * still has to produce something. The generic sentence is the honest answer.
     */
    @Test
    fun `a code this version predates falls back to the generic sentence`() {
        assertEquals(
            copy.forCode(UQPayErrorCode.UNKNOWN),
            copy.forCode(UQPayErrorCode.of("some_future_code")),
        )
    }

    /**
     * A shopper cannot fix an uninitialised SDK, a merchant's rejected access token, or a
     * malformed request. Naming those to them is the failure this split exists to prevent —
     * the SDK's own sample once showed a shopper "The UQPAY SDK was used before it was
     * initialized".
     */
    @Test
    fun `no sentence names an internal detail a shopper cannot act on`() {
        val forbidden = listOf(
            "SDK", "UQPay.initialize", "token", "clientId", "configuration",
            "PaymentSessionParams", "HTTP", "API", "null", "backend",
        )
        declared.forEach { code ->
            val sentence = copy.forCode(code)
            forbidden.forEach { word ->
                assertFalse(
                    "${code.raw} says \"$word\" to a shopper: $sentence",
                    sentence.contains(word, ignoreCase = true),
                )
            }
        }
    }

    /**
     * `AUTHENTICATION_FAILED` is the merchant's backend token being rejected — nothing the
     * shopper did, and nothing retrying will fix. Its copy used to read "The payment could
     * not be authorised. Please try again", which invites a shopper to retry a broken token
     * until they give up on the order.
     */
    @Test
    fun `the codes a retry cannot fix do not invite a retry`() {
        listOf(
            UQPayErrorCode.AUTHENTICATION_FAILED,
            UQPayErrorCode.NOT_INITIALIZED,
            UQPayErrorCode.INVALID_CONFIGURATION,
            UQPayErrorCode.INVALID_REQUEST,
            UQPayErrorCode.INTENT_NOT_PAYABLE,
        ).forEach { code ->
            val sentence = copy.forCode(code)
            assertFalse(
                "${code.raw} tells the shopper to try again: $sentence",
                sentence.contains("try again", ignoreCase = true),
            )
        }
    }

    /**
     * The codes a shopper genuinely can act on should say so. A decline they can pay around,
     * a connection they can restore, a transient server error they can retry.
     */
    @Test
    fun `the codes a shopper can act on tell them what to do`() {
        assertTrue(copy.forCode(UQPayErrorCode.CARD_DECLINED).contains("different payment method"))
        assertTrue(copy.forCode(UQPayErrorCode.SERVER_ERROR).contains("try again", ignoreCase = true))
        assertTrue(
            copy.forCode(UQPayErrorCode.AUTHENTICATION_FAILED).contains("contact", ignoreCase = true),
        )
    }

    /**
     * The three developer-error codes may share one sentence — a shopper does not need the
     * distinction — but the codes a shopper *can* tell apart must not be flattened together.
     */
    @Test
    fun `distinguishable failures get distinguishable sentences`() {
        val distinct = listOf(
            UQPayErrorCode.NETWORK_ERROR,
            UQPayErrorCode.TIMEOUT,
            UQPayErrorCode.CARD_DECLINED,
            UQPayErrorCode.INSUFFICIENT_FUNDS,
            UQPayErrorCode.THREE_DS_FAILED,
            UQPayErrorCode.CANCELLED,
            UQPayErrorCode.INTENT_NOT_PAYABLE,
            UQPayErrorCode.SERVER_ERROR,
            UQPayErrorCode.UNKNOWN,
            UQPayErrorCode.INVALID_PAYMENT_METHOD,
        ).map { copy.forCode(it) }

        assertEquals("two of these codes share a sentence", distinct.size, distinct.toSet().size)
    }
}
