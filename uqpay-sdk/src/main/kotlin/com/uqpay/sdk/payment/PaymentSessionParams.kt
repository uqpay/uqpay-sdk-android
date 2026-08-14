package com.uqpay.sdk.payment

import android.os.Parcel
import android.os.Parcelable

/**
 * Describes one payment to run.
 *
 * The payment intent is created by the **merchant's backend** using UQPAY's server API;
 * the app never creates intents and never holds the amount as a source of truth. The
 * amount, currency, and available payment methods all come from the intent.
 *
 * Per-payment values live here rather than on [com.uqpay.sdk.UQPayConfiguration], so
 * concurrent payments in one process are safe.
 *
 * @property paymentIntentId identifier of the intent created by the merchant backend
 *   (`PI…`). Intents auto-expire 30 minutes after creation.
 * @property presentation which screen the flow opens on. Defaults to
 *   [Presentation.MethodList].
 */
public class PaymentSessionParams @JvmOverloads constructor(
    public val paymentIntentId: String,
    public val presentation: Presentation = Presentation.MethodList,
) : Parcelable {

    /**
     * Where the payment flow starts. Honoured on every path — a merchant asking for
     * [CardOnly] never sees the method list.
     */
    public sealed class Presentation {

        /**
         * Show every method the intent offers, in the gateway's order with card first.
         * Methods this SDK version cannot render are hidden rather than erroring.
         */
        public data object MethodList : Presentation()

        /** Skip the list and open the card form directly. */
        public data object CardOnly : Presentation()

        /**
         * Skip the list and open one wallet's QR flow directly.
         *
         * @property method the wallet to present. Must be offered by the intent.
         */
        public class SingleWallet(public val method: PaymentMethodType) : Presentation() {
            override fun equals(other: Any?): Boolean =
                other is SingleWallet && other.method == method

            override fun hashCode(): Int = method.hashCode()

            override fun toString(): String = "SingleWallet(method=$method)"
        }
    }

    private constructor(parcel: Parcel) : this(
        paymentIntentId = parcel.readString().orEmpty(),
        presentation = readPresentation(parcel),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(paymentIntentId)
        when (val p = presentation) {
            Presentation.MethodList -> dest.writeInt(TAG_METHOD_LIST)
            Presentation.CardOnly -> dest.writeInt(TAG_CARD_ONLY)
            is Presentation.SingleWallet -> {
                dest.writeInt(TAG_SINGLE_WALLET)
                dest.writeString(p.method.raw)
            }
        }
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean =
        other is PaymentSessionParams &&
            other.paymentIntentId == paymentIntentId &&
            other.presentation == presentation

    override fun hashCode(): Int = 31 * paymentIntentId.hashCode() + presentation.hashCode()

    override fun toString(): String =
        "PaymentSessionParams(paymentIntentId=$paymentIntentId, presentation=$presentation)"

    public companion object {
        private const val TAG_METHOD_LIST = 0
        private const val TAG_CARD_ONLY = 1
        private const val TAG_SINGLE_WALLET = 2

        /**
         * Unknown tags fall back to [Presentation.MethodList]: a garbled parcel must
         * degrade, never crash the merchant's app.
         */
        private fun readPresentation(parcel: Parcel): Presentation = when (parcel.readInt()) {
            TAG_CARD_ONLY -> Presentation.CardOnly
            TAG_SINGLE_WALLET ->
                Presentation.SingleWallet(PaymentMethodType.of(parcel.readString().orEmpty()))
            else -> Presentation.MethodList
        }

        @JvmField
        public val CREATOR: Parcelable.Creator<PaymentSessionParams> =
            object : Parcelable.Creator<PaymentSessionParams> {
                override fun createFromParcel(parcel: Parcel): PaymentSessionParams =
                    PaymentSessionParams(parcel)

                override fun newArray(size: Int): Array<PaymentSessionParams?> =
                    arrayOfNulls(size)
            }
    }
}
