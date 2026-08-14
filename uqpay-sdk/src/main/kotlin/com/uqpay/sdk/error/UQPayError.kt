package com.uqpay.sdk.error

import android.os.Parcel
import android.os.Parcelable

/**
 * A failure surfaced by the SDK.
 *
 * [message] is human-readable and **always safe to log**: it never contains a PAN, CVV,
 * expiry, access token, or customer PII. In [com.uqpay.sdk.Environment.PRODUCTION] it is
 * a fixed message chosen per [code]; in [com.uqpay.sdk.Environment.SANDBOX] the gateway's
 * own detail is appended for debuggability.
 *
 * @property code stable identifier — branch on this, not on [message].
 * @property message safe-to-log description of what went wrong.
 * @property declineCode the acquirer's raw decline reason, when one was supplied.
 * @property traceId the gateway's request/trace id. Quote this in support tickets; it
 *   contains no sensitive data.
 */
public class UQPayError(
    public val code: UQPayErrorCode,
    public val message: String,
    public val declineCode: String? = null,
    public val traceId: String? = null,
) : Parcelable {

    private constructor(parcel: Parcel) : this(
        code = UQPayErrorCode.of(parcel.readString().orEmpty()),
        message = parcel.readString().orEmpty(),
        declineCode = parcel.readString(),
        traceId = parcel.readString(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(code.raw)
        dest.writeString(message)
        dest.writeString(declineCode)
        dest.writeString(traceId)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean =
        other is UQPayError &&
            other.code == code &&
            other.message == message &&
            other.declineCode == declineCode &&
            other.traceId == traceId

    override fun hashCode(): Int {
        var result = code.hashCode()
        result = 31 * result + message.hashCode()
        result = 31 * result + (declineCode?.hashCode() ?: 0)
        result = 31 * result + (traceId?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "UQPayError(code=$code, message=$message, declineCode=$declineCode, traceId=$traceId)"

    public companion object {
        @JvmField
        public val CREATOR: Parcelable.Creator<UQPayError> =
            object : Parcelable.Creator<UQPayError> {
                override fun createFromParcel(parcel: Parcel): UQPayError = UQPayError(parcel)

                override fun newArray(size: Int): Array<UQPayError?> = arrayOfNulls(size)
            }
    }
}
