package com.uqpay.sdk.engine

import com.uqpay.sdk.error.UQPayError
import com.uqpay.sdk.network.PaymentIntentDto
import com.uqpay.sdk.payment.PaymentResult

/**
 * How the engine decided a payment ended, before it becomes the merchant's [PaymentResult].
 *
 * Every settle path in [PaymentEngine] produces one of these four, and exactly one function
 * ([toResult]) turns any of them into a result — through [ReconciledOutcome], so a payment
 * observed by the presentation guard, the confirm response, the on-screen watcher, or a
 * detached reconciler is described in the same words. Having the vocabulary be a type rather
 * than four ad-hoc `PaymentResult(...)` calls is what makes "one payment, one description"
 * something the compiler helps with.
 *
 * The four members map one-to-one onto `PaymentStatus`, and the two that are easiest to
 * confuse are the two that cost money:
 *
 * - [Pending] is **not** [Failed]. The ladder or a poll budget ran out; the money may be
 *   moving. Reporting failure invites a second payment for the same order.
 * - [Pending] is **not** [Cancelled]. A customer who leaves mid-confirm has an attempt in
 *   the air. Reporting cancellation tells the merchant to release nothing and refund nothing
 *   for a payment that may succeed thirty seconds later — the exact bug Stripe's PaymentSheet
 *   ships and our iOS SDK had to fix as a breaking change.
 */
internal sealed class EngineOutcome {

    /** The customer paid: `SUCCEEDED`, or `REQUIRES_CAPTURE` (authorised, therefore paid). */
    data class Succeeded(val intent: PaymentIntentDto) : EngineOutcome()

    /**
     * The payment definitively did not happen and no attempt is in flight.
     *
     * @property intent the intent as last read, when there was one — a load that could not
     *   even reach the gateway has none.
     */
    data class Failed(val error: UQPayError, val intent: PaymentIntentDto?) : EngineOutcome()

    /** The customer left with nothing in the air. */
    data class Cancelled(val intent: PaymentIntentDto?) : EngineOutcome()

    /**
     * The SDK stopped driving the payment with its outcome unresolved.
     *
     * @property error why the SDK stopped waiting — `TIMEOUT` when a ladder or poll budget
     *   ran out, or when the customer was allowed to leave mid-flight.
     */
    data class Pending(val error: UQPayError, val intent: PaymentIntentDto?) : EngineOutcome()

    /**
     * The merchant's result, built by [ReconciledOutcome] and nothing else.
     *
     * @param paymentIntentId the engine's own copy of the intent id, used when the outcome
     *   carries no intent (F3: a `CANCELLED` result must never have a blank id, however
     *   early the customer left).
     * @param observedAtEpochMillis wall-clock time for a success's `completedAt`.
     */
    fun toResult(paymentIntentId: String, observedAtEpochMillis: Long): PaymentResult = when (this) {
        is Succeeded -> ReconciledOutcome.successResult(intent, paymentIntentId, observedAtEpochMillis)
        is Failed -> ReconciledOutcome.failureResult(intent, paymentIntentId, error)
        is Cancelled -> ReconciledOutcome.cancelledResult(intent, paymentIntentId)
        is Pending -> ReconciledOutcome.pendingResult(intent, paymentIntentId, error)
    }
}
