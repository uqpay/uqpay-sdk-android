package com.uqpay.sdk.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    val id: String? = null,
    @SerialName("intent_status") val intentStatus: String? = null,
    val amount: String? = null,
    val currency: String? = null,
    @SerialName("merchant_order_id") val merchantOrderId: String? = null,
    @SerialName("available_payment_method_types") val availablePaymentMethodTypes: List<String>? = null,
    @SerialName("latest_payment_attempt") val latestPaymentAttempt: PaymentAttemptDto? = null,
    @SerialName("next_action") val nextAction: NextActionDto? = null,
)

@Serializable
internal data class PaymentAttemptDto(
    val id: String? = null,
    @SerialName("attempt_status") val attemptStatus: String? = null,
    @SerialName("payment_method_type") val paymentMethodType: String? = null,
    @SerialName("failure_code") val failureCode: String? = null,
    @SerialName("failure_message") val failureMessage: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
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
