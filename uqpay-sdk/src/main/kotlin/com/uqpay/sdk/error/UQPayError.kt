package com.uqpay.sdk.error

import android.os.Parcel
import android.os.Parcelable

/**
 * A failure surfaced by the SDK.
 *
 * ### Two messages, and they are not interchangeable
 *
 * [message] is written **for the customer** and is the one to put on screen. [developerMessage]
 * is written **for you** and belongs in a log line or a bug report. Showing the wrong one is
 * the mistake this split exists to prevent: before it, a merchant following the SDK's own
 * sample would eventually show a shopper the sentence "The UQPAY SDK was used before it was
 * initialized", which is true, useless to them, and embarrassing to everyone.
 *
 * Both are always safe to **log**: neither ever contains a PAN, CVV, expiry, access token or
 * customer PII.
 *
 * @property code stable identifier — branch on this, not on either message.
 * @property message a complete sentence for the customer, in the app's language (see
 *   "Localisation" in the integration guide). It says what happened and, where the customer
 *   can do something about it, what that is. Where they cannot — an uninitialised SDK, a
 *   rejected merchant token, a malformed request — it says the payment could not be started
 *   rather than naming a fault the customer has no part in. It never quotes the gateway's own
 *   text, in any environment.
 * @property developerMessage the technical description: what the SDK was doing, what it got
 *   back, and where to look. English, never localised, never stable enough to parse, and
 *   **never shown to a customer**. Null when there is nothing to add beyond [code] and
 *   [message]. In [com.uqpay.sdk.Environment.SANDBOX] it also carries the gateway's own
 *   message so an integrator can debug; in [com.uqpay.sdk.Environment.PRODUCTION] it never
 *   does, because gateway text is documented as unsafe to surface and a crash reporter is a
 *   surface.
 * @property declineCode the acquirer's raw decline reason, when one was supplied.
 * @property traceId the gateway's request/trace id, when it returns one — read from
 *   `x-request-id`, `request-id` or `x-b3-traceid`. It contains no sensitive data.
 *
 *   **Today this is always null.** The UQPAY gateway emits none of those headers: three
 *   live sandbox captures found no correlation header, and the shipped iOS SDK reads none
 *   across 87 source files. The field is retained rather than removed so that adding the
 *   header server-side needs no SDK API change — but nothing in this SDK, and nothing in a
 *   merchant's support flow, may depend on it being populated. Quote [PaymentResult]'s
 *   `paymentIntentId` and `transactionId` instead.
 *
 *   Open item **F8**: keep-or-remove needs sign-off with the UQPAY platform team before
 *   1.0 freezes the surface. See `docs/api-reference.md`.
 */
public class UQPayError(
    public val code: UQPayErrorCode,
    public val message: String,
    public val declineCode: String? = null,
    public val traceId: String? = null,
    public val developerMessage: String? = null,
) : Parcelable {

    private constructor(parcel: Parcel) : this(
        code = UQPayErrorCode.of(parcel.readString().orEmpty()),
        message = parcel.readString().orEmpty(),
        declineCode = parcel.readString(),
        traceId = parcel.readString(),
        developerMessage = parcel.readString(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(code.raw)
        dest.writeString(message)
        dest.writeString(declineCode)
        dest.writeString(traceId)
        dest.writeString(developerMessage)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean =
        other is UQPayError &&
            other.code == code &&
            other.message == message &&
            other.declineCode == declineCode &&
            other.traceId == traceId &&
            other.developerMessage == developerMessage

    override fun hashCode(): Int {
        var result = code.hashCode()
        result = 31 * result + message.hashCode()
        result = 31 * result + (declineCode?.hashCode() ?: 0)
        result = 31 * result + (traceId?.hashCode() ?: 0)
        result = 31 * result + (developerMessage?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "UQPayError(code=$code, message=$message, declineCode=$declineCode, " +
            "traceId=$traceId, developerMessage=$developerMessage)"

    public companion object {
        @JvmField
        public val CREATOR: Parcelable.Creator<UQPayError> =
            object : Parcelable.Creator<UQPayError> {
                override fun createFromParcel(parcel: Parcel): UQPayError = UQPayError(parcel)

                override fun newArray(size: Int): Array<UQPayError?> = arrayOfNulls(size)
            }
    }
}
