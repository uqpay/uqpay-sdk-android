package com.uqpay.sdk.engine

import com.uqpay.sdk.error.UQPayError
import com.uqpay.sdk.network.ErrorMapper
import com.uqpay.sdk.network.IntentStatus
import com.uqpay.sdk.network.PaymentIntentDto
import com.uqpay.sdk.payment.PaymentMethodType
import com.uqpay.sdk.payment.PaymentResult
import com.uqpay.sdk.payment.PaymentStatus
import java.math.BigDecimal

/**
 * The merchant-facing payload for an intent, built the same way by everyone who builds one.
 *
 * Several paths describe a payment to the merchant: the presentation-time guard that finds
 * an intent already settled, the confirm response, the on-screen watcher after 3-D Secure or
 * a QR scan, and Slice 3's detached reconciler after the customer has gone. They **all**
 * build their payloads here, so the same payment cannot describe itself one way to a
 * merchant whose customer stayed on the screen and another way to one whose customer did
 * not. That is the whole reason this object exists (iOS `ReconciledOutcome`, whose comment
 * says exactly this).
 *
 * ### What it holds and what it deliberately does not
 *
 * Only payload construction. Deciding *whether* an intent counts as settled — and what a
 * `REQUIRES_CAPTURE` means at a given call site — stays with each caller, because those
 * rules legitimately differ per call site (`IntentStatus.isPayable`'s own KDoc). Nothing
 * here reads a status to make a decision; the caller has already decided and is asking for
 * the words.
 *
 * ### Two iOS defects deliberately not copied
 *
 * - iOS hardcodes `paymentMethodType: "card"` in every reconciled payload, so a wallet
 *   payment observed by polling reports itself as a card payment. Ours reads the method from
 *   `latest_payment_attempt.payment_method_type` and reports null when there is none.
 * - iOS reports `transactionId: intent.paymentIntentId`. The transaction id is the
 *   **attempt** id — that is what a merchant reconciles against a webhook — and the intent
 *   id is already its own field.
 *
 * ### Money is a `BigDecimal`
 *
 * The wire amount is a decimal string in major units (`"8.98"`). It becomes a [BigDecimal]
 * from that string, never via `Double`: `8.98` is not representable in binary floating point
 * and a merchant comparing amounts must not see `8.9800000000000004`. An unparseable amount
 * becomes null rather than an exception — a result with no amount is still a result, and a
 * settled payment must never fail to be reported because a display field was odd.
 */
internal object ReconciledOutcome {

    /**
     * The failure code the API actually returned, or null when it returned the empty
     * string — which it commonly does instead of omitting the field (G5).
     */
    fun failureCode(intent: PaymentIntentDto): String? =
        intent.latestPaymentAttempt?.failureCode?.takeIf { it.isNotBlank() }

    /**
     * The failure message the API actually returned, or null when it returned the empty
     * string.
     *
     * Nullable on purpose: this is what goes *into* [ErrorMapper.mapSettledOutcome], which
     * owns the fallback copy and decides — per environment — whether gateway text may appear
     * at all. Handing it a fallback sentence would make that sentence look like gateway
     * detail and get it appended in sandbox as `(…)`. See [failureMessageOrFallback] for
     * the customer-facing form.
     */
    fun failureMessage(intent: PaymentIntentDto): String? =
        intent.latestPaymentAttempt?.failureMessage?.takeIf { it.isNotBlank() }

    /**
     * The customer-facing failure message: the API's, or the SDK's own copy when the API
     * supplied none. For a screen, not for a [UQPayError].
     */
    fun failureMessageOrFallback(intent: PaymentIntentDto): String =
        failureMessage(intent) ?: FALLBACK_FAILURE_MESSAGE

    /**
     * The result for an intent whose authorisation succeeded — `SUCCEEDED`, or
     * `REQUIRES_CAPTURE` where the caller has decided that counts.
     *
     * @param paymentIntentId the caller's own copy of the id, used if the wire intent lacks
     *   one (F3: a result never carries a blank intent id).
     * @param observedAtEpochMillis the wall-clock moment the settlement was observed. The
     *   caller supplies it (the engine's own [Clock] is monotonic-only by design and cannot
     *   produce a wall-clock instant). The wire `completed_at` is a string of an unverified
     *   format and is not parsed here; a wrong parse would be worse than the observation
     *   time, which is what iOS reports too.
     */
    fun successResult(
        intent: PaymentIntentDto,
        paymentIntentId: String,
        observedAtEpochMillis: Long,
    ): PaymentResult =
        PaymentResult(
            status = PaymentStatus.SUCCEEDED,
            paymentIntentId = idOf(intent, paymentIntentId),
            paymentMethodType = paymentMethodType(intent),
            amount = amount(intent),
            currency = intent.currency,
            merchantOrderId = intent.merchantOrderId,
            transactionId = transactionId(intent),
            completedAtEpochMillis = observedAtEpochMillis,
            error = null,
        )

    /**
     * The error for an intent that is dead — `FAILED` or `CANCELLED` — or for a
     * `REQUIRES_PAYMENT_METHOD` whose latest attempt failed (G4: a decline the merchant must
     * hear about). The intent's own status drives the mapping, so a cancelled intent is
     * `CANCELLED` whatever its attempt says.
     *
     * Routed through [ErrorMapper.mapSettledOutcome], the SDK's only chokepoint from a
     * failure to a public [UQPayError]; empty-string normalisation lives there and in
     * [failureCode]/[failureMessage], and is not repeated.
     *
     * @param fallbackFailureCode used only when the attempt carries no code — the 3-D Secure
     *   watcher passes `3ds_failed` for a fallback to `REQUIRES_PAYMENT_METHOD` mid-poll
     *   (G14), which is a decline that the attempt row does not always label.
     */
    fun failureError(
        intent: PaymentIntentDto,
        mapper: ErrorMapper,
        fallbackFailureCode: String? = null,
    ): UQPayError = mapper.mapSettledOutcome(
        intentStatus = IntentStatus.from(intent.intentStatus),
        failureCode = failureCode(intent) ?: fallbackFailureCode,
        failureMessage = failureMessage(intent),
    )

    /** A `FAILED` result carrying [error], with whatever the intent can contribute. */
    fun failureResult(intent: PaymentIntentDto?, paymentIntentId: String, error: UQPayError): PaymentResult =
        PaymentResult(
            status = PaymentStatus.FAILED,
            paymentIntentId = idOf(intent, paymentIntentId),
            paymentMethodType = intent?.let(::paymentMethodType),
            amount = intent?.let(::amount),
            currency = intent?.currency,
            merchantOrderId = intent?.merchantOrderId,
            transactionId = intent?.let(::transactionId),
            error = error,
        )

    /**
     * A `PENDING` result: the SDK stopped driving the payment with its outcome unresolved.
     * [error] says why (commonly `TIMEOUT`); the payment may still succeed.
     */
    fun pendingResult(intent: PaymentIntentDto?, paymentIntentId: String, error: UQPayError): PaymentResult =
        PaymentResult(
            status = PaymentStatus.PENDING,
            paymentIntentId = idOf(intent, paymentIntentId),
            paymentMethodType = intent?.let(::paymentMethodType),
            amount = intent?.let(::amount),
            currency = intent?.currency,
            merchantOrderId = intent?.merchantOrderId,
            transactionId = intent?.let(::transactionId),
            error = error,
        )

    /**
     * A `CANCELLED` result: the customer left with **no attempt in the air**. No error — the
     * customer's choice is not a failure.
     */
    fun cancelledResult(intent: PaymentIntentDto?, paymentIntentId: String): PaymentResult =
        PaymentResult(
            status = PaymentStatus.CANCELLED,
            paymentIntentId = idOf(intent, paymentIntentId),
            paymentMethodType = intent?.let(::paymentMethodType),
            amount = intent?.let(::amount),
            currency = intent?.currency,
            merchantOrderId = intent?.merchantOrderId,
            transactionId = intent?.let(::transactionId),
            error = null,
        )

    /**
     * The intent id for a result: the wire intent's own when present, else the caller's.
     * They are the same payment; the fallback exists so no result can carry a blank id.
     */
    fun idOf(intent: PaymentIntentDto?, fallback: String): String =
        intent?.paymentIntentId?.takeIf { it.isNotBlank() } ?: fallback

    /** The method from the latest attempt, or null. Never a hardcoded default. */
    fun paymentMethodType(intent: PaymentIntentDto): PaymentMethodType? =
        intent.latestPaymentAttempt?.paymentMethod?.type?.takeIf { it.isNotBlank() }?.let(PaymentMethodType::of)

    /** The attempt id, which is what a merchant reconciles a webhook against. */
    fun transactionId(intent: PaymentIntentDto): String? =
        intent.latestPaymentAttempt?.attemptId?.takeIf { it.isNotBlank() }

    /** The amount as exact decimal, or null when absent or unparseable. */
    fun amount(intent: PaymentIntentDto): BigDecimal? =
        intent.amount?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            try {
                BigDecimal(raw)
            } catch (_: NumberFormatException) {
                null
            }
        }

    /** The SDK's own copy when the API sends no failure message. */
    const val FALLBACK_FAILURE_MESSAGE: String = "The payment could not be completed. Please try again."
}
