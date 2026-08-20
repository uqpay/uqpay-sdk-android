package com.uqpay.sdk.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * The QR a wallet confirm issued, as the latch remembers it.
 *
 * @property url the `display_qr_code.qr_code_url` — a gateway-hosted PNG rendering of the
 *   payload. Verified live on 2026-08-18: `image/png`, 256×256, no auth headers needed.
 * @property rawPayload the `display_qr_code.qr_code` — the EMVCo string the QR *encodes*.
 *   Captured because the gateway sends both and because a wallet that one day sends only
 *   the payload leaves a trace of what was missing rather than an empty screen.
 * @property expiresAt the wire `expires_at`, verbatim, e.g. `2026-08-18T17:14:19.855+08:00`.
 *   Kept as the string the gateway sent; parsing belongs to whatever draws a countdown.
 */
internal data class IssuedQr(
    val url: String?,
    val rawPayload: String? = null,
    val expiresAt: String? = null,
) {
    /** True when the confirm succeeded but delivered nothing a customer could scan. */
    val isEmpty: Boolean get() = url.isNullOrBlank() && rawPayload.isNullOrBlank()
}

/**
 * What [WalletConfirmLatch.claim] decided. Exactly one caller can ever be told [Granted]
 * for a given intent-and-wallet until the attempt is declared finished.
 */
internal sealed class WalletConfirmClaim {

    /**
     * Nobody has confirmed this intent through this wallet. **The caller now owns the
     * obligation** to either record what the confirm issued ([WalletConfirmLatch.recordIssued])
     * or declare the attempt finished ([WalletConfirmLatch.attemptFinished]) if the gateway
     * definitively refused it. Doing neither leaves the wallet latched for the process
     * lifetime, which is the safe direction to fail.
     */
    data object Granted : WalletConfirmClaim()

    /**
     * A confirm for this intent and wallet is already on the wire and has not reported back.
     *
     * There is nothing to show yet and there must not be a second confirm: the first one may
     * be creating a payment attempt at this instant.
     */
    data object AlreadyInFlight : WalletConfirmClaim()

    /**
     * A confirm already succeeded and this is the QR it issued. **Serve this one.** Do not
     * confirm again — see the class KDoc of [WalletConfirmLatch] for what a second confirm
     * costs, measured against the live sandbox.
     */
    data class AlreadyIssued(val qr: IssuedQr) : WalletConfirmClaim()
}

/**
 * One confirm per payment **and wallet**, for the lifetime of the attempt. Port of the
 * shipped iOS `WalletQRConfirm`, with the concurrency iOS got for free from `@MainActor`
 * made explicit.
 *
 * ### Why this exists — measured, not assumed
 *
 * Confirmed against the live UQPAY sandbox on 2026-08-18: confirming one intent **twice**
 * with GrabPay under two fresh idempotency keys is **accepted both times**. Each confirm
 * returns a *different* `display_qr_code` (a different nonce in the EMVCo payload), and a
 * subsequent `GET /payment_intents/{id}` returns only the **second** one. The gateway does
 * not refuse the duplicate, so nothing on the server side prevents this.
 *
 * That is a money bug with two distinct victims:
 * - a customer who scanned the **first** QR pays into an attempt this SDK is no longer
 *   watching and no longer able to report; and
 * - the merchant sees two live attempts against one order.
 *
 * The idempotency registry cannot cover it. `ConfirmRunner` resolves a pin on a *definitive*
 * gateway answer, and a successful confirm is definitive — so the pin is released the moment
 * the first QR arrives, and the next tap legitimately mints a fresh key. The pin protects an
 * **unresolved** confirm from being sent twice; this latch protects a **resolved** one from
 * being sent again. They are different invariants and both are needed.
 *
 * ### Keyed by intent AND method, never intent alone
 *
 * One intent can be attempted through different wallets, and an Alipay screen must never be
 * served the QR a GrabPay confirm issued: the customer would scan a code their app cannot
 * pay, or — worse, if the codes are interchangeable-looking — pay through a rail the
 * merchant is not expecting. The registry key is `"$intentId|$methodType"`.
 *
 * ### Static, because the invariant belongs to the payment
 *
 * The registry is a companion-object map, not per-instance state. Backing out of a wallet
 * screen and re-entering it builds a new ViewModel, a new `PaymentSession`, and a new
 * `PaymentEngine`; none of those may be allowed to forget that a live QR exists. Anything
 * shorter-lived than the process would reopen the hole this class closes.
 *
 * Process death is *not* covered by this class and is not meant to be: the recovery there is
 * a `GET` on the intent, which the live sandbox confirms still returns the issued
 * `next_action.display_qr_code`. That is the server telling us what we forgot, which is
 * strictly better than trusting a disk cache of our own.
 *
 * ### Thread safety
 *
 * Engine work runs on IO dispatchers, and a confirm can be requested from a UI callback
 * while a watcher settles on another thread. [claim] is a single atomic
 * `putIfAbsent`, so with N threads racing for the same wallet exactly one is [WalletConfirmClaim.Granted]
 * — checking a map and then writing to it would let two of them through, and two confirms is
 * precisely the outcome this class exists to prevent.
 */
internal class WalletConfirmLatch {

    /**
     * Claims the right to confirm [intentId] through [methodType], atomically.
     *
     * @return [WalletConfirmClaim.Granted] to exactly one caller; every later caller gets
     *   [WalletConfirmClaim.AlreadyInFlight] until the QR is recorded and
     *   [WalletConfirmClaim.AlreadyIssued] afterwards.
     */
    fun claim(intentId: String, methodType: String): WalletConfirmClaim {
        val key = registryKey(intentId, methodType)
        // putIfAbsent is the whole guard: one atomic operation, no read-then-write window.
        val existing = entries.putIfAbsent(key, Entry.InFlight)
            ?: return WalletConfirmClaim.Granted
        return when (existing) {
            Entry.InFlight -> WalletConfirmClaim.AlreadyInFlight
            is Entry.Issued -> WalletConfirmClaim.AlreadyIssued(existing.qr)
        }
    }

    /** True once a confirm for this intent and wallet has issued a QR, on any screen. */
    fun hasIssued(intentId: String, methodType: String): Boolean =
        entries[registryKey(intentId, methodType)] is Entry.Issued

    /** The QR issued for this intent and wallet, or null if none has been issued yet. */
    fun issuedQr(intentId: String, methodType: String): IssuedQr? =
        (entries[registryKey(intentId, methodType)] as? Entry.Issued)?.qr

    /**
     * Records what the confirm issued, so a re-entry is served this QR rather than a second
     * confirm.
     *
     * Safe to call repeatedly with the same QR — a rotation that re-observes the same engine
     * state will. Recording without a prior [claim] is allowed and deliberate: the QR can
     * also reach us from a plain intent read after process death, and refusing to remember
     * it then would be refusing the only recovery there is.
     */
    fun recordIssued(intentId: String, methodType: String, qr: IssuedQr) {
        entries[registryKey(intentId, methodType)] = Entry.Issued(qr)
    }

    /**
     * Frees the latch, so a fresh confirm for this intent and wallet becomes possible again.
     *
     * Call this **only** when the attempt has demonstrably finished: it was paid, it was
     * terminally declined or cancelled, or the confirm came back with no QR at all so no
     * attempt the customer could pay was ever created.
     *
     * **NEVER CALL THIS BECAUSE A POLL TIMED OUT OR ERRORED.** iOS carries that sentence in
     * capitals and it is repeated here for the same reason: an unobserved attempt is not a
     * finished one. Its QR is still on the customer's screen, still scannable, and still
     * payable. Freeing the latch there lets the next tap open a second live attempt against
     * a payment that is about to be made — which is the exact double-charge this whole class
     * exists to prevent, reintroduced by a well-meaning cleanup.
     *
     * The same rule is why a `PENDING` outcome must not free the latch: `PENDING` is the SDK
     * saying "we stopped looking", never "nothing happened".
     */
    fun attemptFinished(intentId: String, methodType: String) {
        entries.remove(registryKey(intentId, methodType))
    }

    /** How many wallets are currently latched. Diagnostics and tests only. */
    fun size(): Int = entries.size

    private sealed class Entry {
        /** Claimed; the confirm is on the wire and has not reported back. */
        data object InFlight : Entry()

        /** Confirmed; this is what it issued. */
        data class Issued(val qr: IssuedQr) : Entry()
    }

    internal companion object {

        /**
         * The one registry. `ConcurrentHashMap` rather than a synchronized map because the
         * guard depends on `putIfAbsent` being atomic, not merely on individual reads being
         * safe.
         */
        private val entries = ConcurrentHashMap<String, Entry>()

        /**
         * `intentId|methodType`. The separator matches iOS byte for byte so the two SDKs'
         * behaviour can be reasoned about as one rule; neither part can contain a `|`
         * (both are gateway-issued identifiers).
         */
        private fun registryKey(intentId: String, methodType: String): String =
            "$intentId|$methodType"

        /** Test hook: empties the static registry so one case cannot leak into the next. */
        fun clearForTest() {
            entries.clear()
        }
    }
}
