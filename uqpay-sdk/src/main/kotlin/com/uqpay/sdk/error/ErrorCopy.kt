package com.uqpay.sdk.error

import android.content.Context
import androidx.annotation.StringRes
import com.uqpay.sdk.R

/**
 * The customer-facing sentence for a [UQPayErrorCode], read from resources.
 *
 * ### Why these are not `when (code) -> "…"` in Kotlin
 *
 * They used to be, and it broke the SDK's own rule — the one written at the top of
 * `res/values/strings.xml`: nothing in Kotlin may hardcode text a customer can read. A
 * sentence compiled into a `.class` file cannot be translated, cannot be overridden by a
 * merchant, and cannot be reviewed alongside the rest of the vocabulary. `UQPayError.message`
 * is shown to shoppers — the integration guide tells merchants to show it — so it is
 * customer-facing text by any reading, and it belongs with the rest of it.
 *
 * Moving it here is what makes localisation possible without an API change: a translated
 * `values-th/strings.xml`, shipped by us or declared by the merchant's own app, changes the
 * sentence with no code touched anywhere.
 *
 * ### Every code resolves, including ones this version predates
 *
 * [UQPayErrorCode] is deliberately not an enum — it preserves values the gateway may add
 * after this release — so the lookup must answer for a code it has never seen. It answers
 * with the generic sentence, which is honest: the SDK genuinely does not know what happened.
 *
 * ### The lookup is deferred, not done at construction
 *
 * [context] is held and `resources` is read per lookup, rather than resolved once when this
 * is built. Two reasons, and the second is the load-bearing one:
 *
 * - a sentence resolved at construction would be frozen at that moment's configuration,
 *   while an error mapped later — after the customer changed the system language, after a
 *   host applied its own locale — should read in the language the app is in *now*;
 * - construction happens inside `PaymentSession.obtain`, which the payment screen calls on
 *   the main thread, and AC §10.3's test instruments `Context.getResources()` as one of the
 *   I/O surfaces that the payment path may not touch from there. Nothing is read until an
 *   error actually has to be described, which for a successful payment is never.
 *
 * Only ever built from the application context or from the payment Activity, each of which
 * outlives the object holding the copy, so there is nothing here to leak.
 *
 * @param context the host's context, so the sentence follows the app's language and any
 *   per-app or per-Activity locale it has applied.
 */
internal class ErrorCopy(private val context: Context) {

    /** The sentence for [code], or the generic one for a code this version does not know. */
    fun forCode(code: UQPayErrorCode): String = context.resources.getString(resourceFor(code))

    @StringRes
    private fun resourceFor(code: UQPayErrorCode): Int = when (code) {
        UQPayErrorCode.NOT_INITIALIZED -> R.string.uqpay_error_not_initialized
        UQPayErrorCode.INVALID_CONFIGURATION -> R.string.uqpay_error_invalid_configuration
        UQPayErrorCode.INVALID_REQUEST -> R.string.uqpay_error_invalid_request
        UQPayErrorCode.INVALID_PAYMENT_METHOD -> R.string.uqpay_error_invalid_payment_method
        UQPayErrorCode.NETWORK_ERROR -> R.string.uqpay_error_network
        UQPayErrorCode.TIMEOUT -> R.string.uqpay_error_timeout
        UQPayErrorCode.AUTHENTICATION_FAILED -> R.string.uqpay_error_authentication_failed
        UQPayErrorCode.CARD_DECLINED -> R.string.uqpay_error_card_declined
        UQPayErrorCode.INSUFFICIENT_FUNDS -> R.string.uqpay_error_insufficient_funds
        UQPayErrorCode.THREE_DS_FAILED -> R.string.uqpay_error_three_ds_failed
        UQPayErrorCode.CANCELLED -> R.string.uqpay_error_cancelled
        UQPayErrorCode.INTENT_NOT_PAYABLE -> R.string.uqpay_error_intent_not_payable
        UQPayErrorCode.SERVER_ERROR -> R.string.uqpay_error_server
        else -> R.string.uqpay_error_unknown
    }

    internal companion object {
        /** Builds one from any [Context]. See the class documentation for what is held. */
        fun from(context: Context): ErrorCopy = ErrorCopy(context)
    }
}
