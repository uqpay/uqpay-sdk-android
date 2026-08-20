package com.uqpay.sdk.engine

import com.uqpay.sdk.network.NextActionDto
import com.uqpay.sdk.network.PaymentIntentDto
import com.uqpay.sdk.payment.PaymentMethodType
import com.uqpay.sdk.payment.PaymentResult

/**
 * How a payment screen is presented — which methods the customer is offered.
 *
 * Applied at **one** place ([PaymentEngine]'s method-list construction) so it holds on every
 * path. iOS ignored `.cardOnly` on its UIKit path and handed a card-only merchant the full
 * method list (audit A5); making the choice a property of the *state* rather than of a
 * particular screen is what prevents a second screen from forgetting it.
 */
internal sealed class Presentation {

    /** Every method the intent offers, in API order with card first. The default. */
    data object MethodList : Presentation()

    /** The card form only, regardless of what else the intent offers. */
    data object CardOnly : Presentation()

    /**
     * One wallet only — the merchant has already asked the customer which one.
     *
     * @property method the wallet, e.g. [PaymentMethodType.ALIPAY_CN].
     */
    data class SingleWallet(val method: PaymentMethodType) : Presentation()
}

/**
 * Something the customer must do before the payment can settle, decoded from the intent's
 * `next_action`.
 *
 * Decoded here rather than left as a [NextActionDto] so that a UI branches on a type it can
 * render, never on a raw wire string — and so that a `next_action` this SDK version predates
 * becomes [Unknown] instead of a crash or a silent no-op. **Unknown is a first-class member**:
 * the UI declines to render it and the engine keeps polling, because the customer may well
 * complete the step through some other channel (a wallet app, an issuer push notification).
 *
 * The names are deliberately neutral about *what* the redirect is for. On the card path
 * [Redirect] and [Iframe] are the two halves of 3-D Secure (a challenge URL, and a
 * self-submitting device-fingerprint form that must be loaded as data, not fetched); on a
 * wallet path a `redirect_to_url` may be the wallet's own app link. The rendering rule is the
 * same either way and belongs to Slices 4 and 5.
 */
internal sealed class NextAction {

    /** The wire `type`, kept so two actions can be compared for "did the step change". */
    abstract val type: String

    /** `redirect_to_url`: open [url] and watch the intent. */
    data class Redirect(val url: String) : NextAction() {
        override val type: String get() = TYPE_REDIRECT
    }

    /**
     * `redirect_iframe`: render [html] directly (a self-submitting POST form — rewriting it
     * as a GET drops the body) and watch the intent.
     */
    data class Iframe(val html: String) : NextAction() {
        override val type: String get() = TYPE_IFRAME
    }

    /**
     * `display_qr_code`: show the QR at [url] until [expiresAt] and watch the intent.
     *
     * @property expiresAt the wire timestamp, verbatim. Rendering the countdown is the
     *   UI's; the engine's own stop rule is an attempt budget, never this value.
     */
    data class Qr(val url: String, val expiresAt: String?) : NextAction() {
        override val type: String get() = TYPE_QR
    }

    /**
     * `display_bank_details`: show transfer instructions and watch the intent.
     *
     * The type is recognised but **its payload is not yet modelled** — `NextActionDto` has
     * no `display_bank_details` field (the API contract lists it; the DTO predates it). This
     * is a distinct member rather than [Unknown] so the UI can at least tell the customer
     * *what* it cannot show, and so the gap is visible at the type level for Slice 5 to
     * close by adding the DTO field and populating this class.
     */
    data class BankDetails(val raw: NextActionDto) : NextAction() {
        override val type: String get() = TYPE_BANK_DETAILS
    }

    /**
     * A `next_action` this SDK version cannot render: a type it predates, or a known type
     * whose payload was missing (`redirect_to_url` without a `url`).
     *
     * Carries the raw DTO so a support engineer can see what was sent. The engine treats it
     * exactly like any other action for polling purposes.
     */
    data class Unknown(val raw: NextActionDto) : NextAction() {
        override val type: String get() = raw.type?.trim().orEmpty()
    }

    internal companion object {
        const val TYPE_REDIRECT: String = "redirect_to_url"
        const val TYPE_IFRAME: String = "redirect_iframe"
        const val TYPE_QR: String = "display_qr_code"
        const val TYPE_BANK_DETAILS: String = "display_bank_details"

        /**
         * Decodes the intent's `next_action`, or returns null when there is none.
         *
         * Never throws. A known type with a missing payload is [Unknown], not the known
         * type with a blank field: a `Redirect("")` would send a WebView to nowhere.
         */
        fun from(dto: NextActionDto?): NextAction? {
            if (dto == null) return null
            val url = dto.redirectToUrl?.url?.takeIf { it.isNotBlank() }
            val html = dto.redirectIframe?.iframe?.takeIf { it.isNotBlank() }
            val qrUrl = dto.displayQrCode?.qrCodeUrl?.takeIf { it.isNotBlank() }
            return when (dto.type?.trim()?.lowercase()) {
                TYPE_REDIRECT -> url?.let(::Redirect) ?: Unknown(dto)
                TYPE_IFRAME -> html?.let(::Iframe) ?: Unknown(dto)
                TYPE_QR -> qrUrl?.let { Qr(it, dto.displayQrCode?.expiresAt) } ?: Unknown(dto)
                TYPE_BANK_DETAILS -> BankDetails(dto)
                else -> Unknown(dto)
            }
        }
    }
}

/**
 * Where a payment is, as seen by whatever renders it.
 *
 * Exposed by [PaymentEngine] as a `StateFlow`. Every state carries what a screen needs to
 * draw itself and nothing a screen must not have: no card values, no idempotency keys, no
 * gateway text. Slices 3–5 render these; nothing here knows about Android UI.
 *
 * ```
 * Idle → LoadingIntent → SelectingMethod → Confirming → { RequiresAction | Polling } → Terminal
 * ```
 *
 * [Terminal] is entered at most once and never left. Every other transition is guarded by
 * the same latch, so a late-arriving state from a superseded attempt cannot repaint a
 * finished payment.
 */
internal sealed class EngineState {

    /** Constructed, [PaymentEngine.load] not yet called. */
    data object Idle : EngineState()

    /** Reading the intent for the presentation-time guard and the method list. */
    data object LoadingIntent : EngineState()

    /**
     * The intent is payable and the customer is choosing how to pay.
     *
     * @property intent the intent as loaded — amount, currency, order reference for the
     *   screen header.
     * @property methods what to offer, already filtered by [presentation]: API order with
     *   `card` first for [Presentation.MethodList], exactly one entry otherwise. May contain
     *   types this SDK version predates; hiding those is the UI's job, not an error.
     */
    data class SelectingMethod(
        val intent: PaymentIntentDto,
        val methods: List<PaymentMethodType>,
        val presentation: Presentation,
    ) : EngineState()

    /**
     * A confirm is in flight — the request, and its replay ladder if the outcome is unknown.
     *
     * Slice 3 blocks back-press while the engine is here (§2c). See
     * [PaymentEngine.isConfirmInFlight].
     *
     * @property methodType what is being confirmed, for the progress copy.
     */
    data class Confirming(val methodType: PaymentMethodType) : EngineState()

    /**
     * The gateway wants something from the customer. The engine is **already polling** the
     * intent while this is on screen; the UI renders [action] and waits for the state to
     * move on. It never has to tell the engine "the customer finished" — a return URL is a
     * hint at best, and the outcome is always re-read from the API.
     *
     * @property intent the intent that carried the action, for amounts and references.
     */
    data class RequiresAction(
        val action: NextAction,
        val intent: PaymentIntentDto,
    ) : EngineState()

    /**
     * Nothing for the customer to do; the payment is processing and the engine is watching
     * it. Shown as progress.
     *
     * @property intent the latest read of the intent, when there has been one.
     */
    data class Polling(val intent: PaymentIntentDto?) : EngineState()

    /**
     * The one and only outcome. Delivered to the merchant exactly as it is here.
     */
    data class Terminal(val result: PaymentResult) : EngineState()
}
