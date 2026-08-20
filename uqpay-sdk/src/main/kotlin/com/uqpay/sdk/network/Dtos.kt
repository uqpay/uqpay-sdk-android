package com.uqpay.sdk.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames

/**
 * Wire models. Dumb data only — parsing happens at the network boundary and nothing here
 * makes decisions.
 *
 * Every field is nullable and every status is a plain `String`, deliberately: the parser
 * must never fail on a payload shape this SDK version predates. Statuses become
 * [IntentStatus] / [AttemptStatus] (which carry an `Unknown` member) at the boundary.
 *
 * Amounts stay `String` here and become `BigDecimal` at the boundary — they are decimal
 * values in **major units** (`"8.98"`), and binary floating point is not a money type.
 */
internal object UQPayJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }
}

@Serializable
internal data class PaymentIntentDto(
    /**
     * The intent identifier.
     *
     * The wire key is `payment_intent_id`, **not** `id` — verified against the live
     * sandbox on 2026-08-17 (`GET /api/v2/payment_intents/{id}` returns
     * `payment_intent_id`, and there is no `id` key at all) and against the shipped iOS
     * SDK, whose `UqpayPaymentIntent.paymentIntentId` is non-optional.
     *
     * This was declared as `id` until that check. Nothing failed, because every test
     * fixture was written to match the wrong assumption — the field simply decoded to
     * null forever. That is the failure mode a fake-only test suite cannot see.
     */
    @SerialName("payment_intent_id") val paymentIntentId: String? = null,
    @SerialName("intent_status") val intentStatus: String? = null,
    val amount: String? = null,
    val currency: String? = null,
    @SerialName("merchant_order_id") val merchantOrderId: String? = null,
    @SerialName("available_payment_method_types") val availablePaymentMethodTypes: List<String>? = null,
    @SerialName("latest_payment_attempt") val latestPaymentAttempt: PaymentAttemptDto? = null,
    @SerialName("next_action") val nextAction: NextActionDto? = null,
    /**
     * The merchant's post-authentication return URL, as supplied when the intent was created.
     *
     * Modelled because the 3-D Secure WebView needs it: it is the one prefix that reliably
     * marks the end of the browser step for a merchant who uses an `https` return instead of
     * a custom scheme. See `PaymentViewModel.returnUrlPrefixes`. Nothing is ever read *from*
     * the returned URL — only the fact that it was reached (`ThreeDsScreen`).
     */
    @SerialName("return_url") val returnUrl: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class PaymentAttemptDto(
    /**
     * The attempt identifier, under either of the two names the API uses for it.
     *
     * UQPAY returns `attempt_id` when the attempt is nested under a payment intent and
     * `payment_attempt_id` in webhook payloads. The shipped iOS SDK decodes both
     * (`UqpayPaymentIntentModels.swift:164-200`), and so do we — a merchant reconciling a
     * webhook against an SDK result must see the same identifier from both directions.
     *
     * Was declared as `id`, which matches neither.
     */
    @SerialName("attempt_id")
    @JsonNames("payment_attempt_id")
    val attemptId: String? = null,
    @SerialName("attempt_status") val attemptStatus: String? = null,
    /**
     * The method used, nested — **not** a flat `payment_method_type`.
     *
     * Live-verified 2026-08-18 against a confirmed sandbox attempt: the wire carries
     * `"payment_method": { "type": "grabpay", "grabpay": { … } }` and there is no
     * `payment_method_type` key anywhere on the attempt. iOS's `UqpayPaymentAttempt`
     * models no method type at all, so it offered no counter-evidence.
     *
     * Declared flat until that check, which meant it decoded to null forever and every
     * [com.uqpay.sdk.payment.PaymentResult] reported a null payment method. Third
     * instance of this failure mode today, after `payment_intent_id` and `attempt_id`:
     * a field name assumed from documentation, with fixtures written to match the
     * assumption.
     */
    @SerialName("payment_method") val paymentMethod: AttemptPaymentMethodDto? = null,
    @SerialName("failure_code") val failureCode: String? = null,
    @SerialName("failure_message") val failureMessage: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
)

/**
 * The `payment_method` object on a payment attempt. Only [type] is read today; the
 * per-method detail object beside it (`grabpay`, `card`, …) is keyed by that type and is
 * not modelled, because nothing in the SDK consumes it.
 */
@Serializable
internal data class AttemptPaymentMethodDto(
    val type: String? = null,
)

@Serializable
internal data class NextActionDto(
    val type: String? = null,
    @SerialName("redirect_to_url") val redirectToUrl: RedirectToUrlDto? = null,
    @SerialName("redirect_iframe") val redirectIframe: RedirectIframeDto? = null,
    @SerialName("display_qr_code") val displayQrCode: DisplayQrCodeDto? = null,
)

@Serializable
internal data class RedirectToUrlDto(val url: String? = null)

@Serializable
internal data class RedirectIframeDto(val iframe: String? = null)

@Serializable
internal data class DisplayQrCodeDto(
    @SerialName("qr_code_url") val qrCodeUrl: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

// Error bodies are NOT modelled as a @Serializable class. UQPAY sends two shapes:
//   A  { "type": "payment_error", "code": "retrieve_failed", "message": "…" }
//   B  { "code": 200, "message": "Request is processing, please try again later." }
// `code` is a string in one and a number in the other, so a single typed model would
// throw on one of them. See ApiErrorParser, which reads both without ever failing.
