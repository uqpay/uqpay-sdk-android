package com.uqpay.sdk.payment

import android.os.Parcel
import android.os.Parcelable
import com.uqpay.sdk.error.UQPayError
import java.math.BigDecimal

/**
 * Outcome of a payment, delivered exactly once via [PaymentCallback.onResult] on the
 * main thread.
 *
 * The result is **advisory**. The merchant's webhook is the authority on whether money
 * moved; confirm server-side before fulfilling an order.
 *
 * @property status terminal status — see [PaymentStatus].
 * @property paymentIntentId the intent this result belongs to.
 * @property paymentMethodType the method the customer used, when one was selected.
 * @property amount authorised amount in **major units** (`8.98`, never minor units).
 *   `BigDecimal` because binary floating point is not a money type.
 * @property currency ISO 4217 code.
 * @property merchantOrderId the merchant's own order reference, echoed from the intent.
 * @property transactionId the payment attempt id, when an attempt was created.
 * @property completedAtEpochMillis when the gateway settled the payment, in Unix
 *   milliseconds. A primitive rather than `java.time.Instant` so the SDK needs no core
 *   library desugaring at `minSdk 24`.
 * @property error non-null when [status] is [PaymentStatus.FAILED]. Also populated on
 *   [PaymentStatus.PENDING] to carry the reason the SDK stopped waiting — commonly
 *   [com.uqpay.sdk.error.UQPayErrorCode.TIMEOUT].
 */
public class PaymentResult(
    public val status: PaymentStatus,
    public val paymentIntentId: String,
    public val paymentMethodType: PaymentMethodType? = null,
    public val amount: BigDecimal? = null,
    public val currency: String? = null,
    public val merchantOrderId: String? = null,
    public val transactionId: String? = null,
    public val completedAtEpochMillis: Long? = null,
    public val error: UQPayError? = null,
) : Parcelable {

    // readParcelable(ClassLoader, Class) requires API 33; this is the only form
    // available at minSdk 24.
    @Suppress("DEPRECATION")
    private constructor(parcel: Parcel) : this(
        status = PaymentStatus.entries.getOrElse(parcel.readInt()) { PaymentStatus.FAILED },
        paymentIntentId = parcel.readString().orEmpty(),
        paymentMethodType = parcel.readString()?.let(PaymentMethodType::of),
        amount = parcel.readString()?.let(::BigDecimal),
        currency = parcel.readString(),
        merchantOrderId = parcel.readString(),
        transactionId = parcel.readString(),
        completedAtEpochMillis = parcel.readValue(Long::class.java.classLoader) as? Long,
        error = parcel.readParcelable(UQPayError::class.java.classLoader),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(status.ordinal)
        dest.writeString(paymentIntentId)
        dest.writeString(paymentMethodType?.raw)
        dest.writeString(amount?.toPlainString())
        dest.writeString(currency)
        dest.writeString(merchantOrderId)
        dest.writeString(transactionId)
        dest.writeValue(completedAtEpochMillis)
        dest.writeParcelable(error, flags)
    }

    override fun describeContents(): Int = 0

    override fun toString(): String =
        "PaymentResult(status=$status, paymentIntentId=$paymentIntentId, " +
            "paymentMethodType=$paymentMethodType, amount=$amount, currency=$currency, " +
            "merchantOrderId=$merchantOrderId, transactionId=$transactionId, " +
            "completedAtEpochMillis=$completedAtEpochMillis, error=$error)"

    public companion object {
        @JvmField
        public val CREATOR: Parcelable.Creator<PaymentResult> =
            object : Parcelable.Creator<PaymentResult> {
                override fun createFromParcel(parcel: Parcel): PaymentResult =
                    PaymentResult(parcel)

                override fun newArray(size: Int): Array<PaymentResult?> = arrayOfNulls(size)
            }
    }
}
