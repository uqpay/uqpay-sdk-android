package com.uqpay.sdk.payment

/**
 * Starts payments and delivers their outcome.
 *
 * Obtain one from [com.uqpay.sdk.UQPay.createPaymentLauncher] during host Activity or
 * Fragment creation, then call [launch] whenever the customer pays.
 *
 * Implemented by the SDK. Merchants use the instance they are handed and do not implement
 * this interface — it gains members as the SDK grows, and [cancel] was one of them.
 */
public interface UQPayPaymentLauncher {
    /**
     * Launches the payment flow for an intent created by the merchant backend.
     *
     * Safe to call more than once over the life of the host Activity. A call for a **new**
     * intent starts a fresh payment; a call for an intent that is **already running** joins
     * that payment rather than starting another — see below for what that means for the
     * second call's parameters. A double-tap cannot **charge** twice: two launches of one intent
     * share a single engine, confirm and idempotency key, so the SDK guards the submission
     * rather than relying on the merchant disabling a button. It is still two launches, and
     * therefore two callbacks — see below.
     *
     * Each call is answered by **exactly one** [PaymentCallback] invocation, for **that
     * call's** payment intent. Launching a second, different intent while the first sheet is
     * still on screen starts a second payment, and neither is ever answered with the other's
     * outcome. Launching the *same* intent twice is not a second payment — both sheets share
     * one engine, one confirm and one idempotency key — but it is two launches, so the
     * callback is invoked once for each.
     *
     * **The first launch's presentation wins.** A second launch of an intent that is still
     * running keeps the screen the first one opened with: its
     * [PaymentSessionParams.presentation] is ignored, because the customer may be half-way
     * through that screen and swapping it — or rebuilding the payment to honour the new one —
     * is how a payment gets a second Pay button. The SDK logs the difference at debug when
     * `loggingEnabled` is set. The second call's [PaymentSessionParams.billingDetails] and
     * [PaymentSessionParams.allowedPaymentMethods] are read by the second sheet as normal.
     * To present the same intent differently, wait for the first launch's callback.
     *
     * If the host is in a state where the flow cannot start, the failure is delivered
     * through the [PaymentCallback] rather than thrown.
     */
    public fun launch(params: PaymentSessionParams)

    /**
     * Asks the SDK to close the payment sheet this launcher most recently opened.
     *
     * For the merchant-side events that make a sheet on screen wrong: the customer's basket
     * timed out, the order was cancelled from your back office, the intent expired, a
     * push arrived saying the customer paid on another device. Without this the sheet could
     * only be dismissed by the customer, and a merchant with a live cancellation had nothing
     * to call.
     *
     * ### It cancels the payment; it does not un-send one
     *
     * The outcome still arrives through the [PaymentCallback], exactly once, like every
     * other ending — this method never invokes it directly and never suppresses it.
     *
     * - Nothing submitted yet: the callback receives [PaymentStatus.CANCELLED].
     * - **An attempt already in the air** — a confirm on the wire, a QR the customer may
     *   have just scanned, a 3-D Secure challenge half-completed — the callback receives
     *   [PaymentStatus.PENDING], never `CANCELLED`. Closing a sheet does not reach into the
     *   gateway and stop a payment, and telling you "cancelled" for a payment that may
     *   succeed a second later is how an order gets released for money that did move. Treat
     *   `PENDING` exactly as you would from any other path: wait for the webhook.
     * - The payment has already ended: nothing happens. The callback has been, or is about
     *   to be, invoked with the real outcome.
     *
     * ### Bounds
     *
     * Call it from the main thread. It affects only this launcher's most recent [launch],
     * so a host holding two launchers cancels each independently. Calling it before any
     * [launch], or after the sheet has closed, is a documented no-op rather than an error.
     * If it is called in the moment between [launch] returning and the sheet appearing, the
     * sheet is not yet cancellable and the call does nothing; the customer's own dismissal
     * remains the way out, so re-issue the cancel if your condition still holds.
     */
    public fun cancel()
}
