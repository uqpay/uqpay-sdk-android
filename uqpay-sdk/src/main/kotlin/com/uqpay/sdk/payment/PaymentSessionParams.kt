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
 * @property billingDetails values to prefill the card form's billing section with, when
 *   your app already knows them. Null — the default — leaves every field empty, which is
 *   exactly what earlier SDK versions did.
 */
public class PaymentSessionParams @JvmOverloads constructor(
    public val paymentIntentId: String,
    public val presentation: Presentation = Presentation.MethodList,
    public val billingDetails: BillingDetails? = null,
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

    /**
     * Billing details your app already knows, used to **prefill** the card form.
     *
     * Everything here is a convenience, never a commitment: the customer sees every value
     * in an ordinary, editable field and can change or clear any of it before paying. What
     * is sent to the gateway is what the form holds at the moment Pay is tapped, which is
     * the only thing the customer actually agreed to.
     *
     * ### What is deliberately absent
     *
     * There is no card number, expiry, or security code, and there never will be. Accepting
     * them would mean a merchant app holding card data — the thing this SDK exists to keep
     * out of merchant apps — and would put a PAN into an `Intent` extra, where the OS can
     * write it to disk. The customer types those three fields, always.
     *
     * ### What the SDK does with these values
     *
     * They travel in the launch parcel and seed the form's in-memory state. Nothing here is
     * written to `SharedPreferences`, to the saved-state `Bundle`, or to Logcat, and
     * [toString] redacts [email] and [phone] because a merchant's crash reporter will
     * stringify whatever it is handed.
     *
     * Nested inside [PaymentSessionParams] rather than declared at the top level: it
     * matches [Presentation], and it cannot collide with a `BillingDetails` the merchant
     * already has of their own.
     *
     * @property firstName the cardholder's given name.
     * @property lastName the cardholder's family name.
     * @property email billing email address.
     * @property phone billing phone number, in whatever form your records hold it.
     * @property addressLine1 street address, first line.
     * @property addressLine2 street address, second line (unit, floor, building).
     * @property city city or town.
     * @property state state, province or region.
     * @property postalCode postal or ZIP code.
     * @property countryCode ISO 3166-1 alpha-2, e.g. `"SG"`. Case-insensitive. A code this
     *   SDK does not recognise is **ignored**, and the picker opens on the device's own
     *   region instead — a merchant typo must not leave the customer unable to pay.
     */
    public class BillingDetails @JvmOverloads constructor(
        public val firstName: String? = null,
        public val lastName: String? = null,
        public val email: String? = null,
        public val phone: String? = null,
        public val addressLine1: String? = null,
        public val addressLine2: String? = null,
        public val city: String? = null,
        public val state: String? = null,
        public val postalCode: String? = null,
        public val countryCode: String? = null,
    ) : Parcelable {

        private constructor(parcel: Parcel) : this(
            firstName = parcel.readString(),
            lastName = parcel.readString(),
            email = parcel.readString(),
            phone = parcel.readString(),
            addressLine1 = parcel.readString(),
            addressLine2 = parcel.readString(),
            city = parcel.readString(),
            state = parcel.readString(),
            postalCode = parcel.readString(),
            countryCode = parcel.readString(),
        )

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeString(firstName)
            dest.writeString(lastName)
            dest.writeString(email)
            dest.writeString(phone)
            dest.writeString(addressLine1)
            dest.writeString(addressLine2)
            dest.writeString(city)
            dest.writeString(state)
            dest.writeString(postalCode)
            dest.writeString(countryCode)
        }

        override fun describeContents(): Int = 0

        override fun equals(other: Any?): Boolean =
            other is BillingDetails &&
                other.firstName == firstName &&
                other.lastName == lastName &&
                other.email == email &&
                other.phone == phone &&
                other.addressLine1 == addressLine1 &&
                other.addressLine2 == addressLine2 &&
                other.city == city &&
                other.state == state &&
                other.postalCode == postalCode &&
                other.countryCode == countryCode

        override fun hashCode(): Int = listOf(
            firstName, lastName, email, phone, addressLine1,
            addressLine2, city, state, postalCode, countryCode,
        ).hashCode()

        /**
         * Never expose the customer's contact details in logs or stack traces.
         *
         * [email] and [phone] are the two fields that identify and reach a specific person
         * on their own, so they are replaced rather than printed — the same rule
         * [com.uqpay.sdk.UQPayConfiguration] and [com.uqpay.sdk.auth.UQPayAuthToken] follow
         * for credentials. Whether each was supplied is still visible, because "did my
         * prefill arrive?" is the question this string exists to answer.
         */
        override fun toString(): String =
            "BillingDetails(firstName=$firstName, lastName=$lastName, " +
                "email=${redact(email)}, phone=${redact(phone)}, " +
                "addressLine1=$addressLine1, addressLine2=$addressLine2, city=$city, " +
                "state=$state, postalCode=$postalCode, countryCode=$countryCode)"

        public companion object {
            /** Absent stays visibly absent; anything present becomes `****`. */
            private fun redact(value: String?): String = if (value == null) "null" else "****"

            @JvmField
            public val CREATOR: Parcelable.Creator<BillingDetails> =
                object : Parcelable.Creator<BillingDetails> {
                    override fun createFromParcel(parcel: Parcel): BillingDetails =
                        BillingDetails(parcel)

                    override fun newArray(size: Int): Array<BillingDetails?> = arrayOfNulls(size)
                }
        }
    }

    private constructor(parcel: Parcel) : this(
        paymentIntentId = parcel.readString().orEmpty(),
        presentation = readPresentation(parcel),
        billingDetails = readBillingDetails(parcel),
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
        val billing = billingDetails
        if (billing == null) {
            dest.writeInt(BILLING_ABSENT)
        } else {
            dest.writeInt(BILLING_PRESENT)
            billing.writeToParcel(dest, flags)
        }
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean =
        other is PaymentSessionParams &&
            other.paymentIntentId == paymentIntentId &&
            other.presentation == presentation &&
            other.billingDetails == billingDetails

    override fun hashCode(): Int {
        var result = paymentIntentId.hashCode()
        result = 31 * result + presentation.hashCode()
        result = 31 * result + (billingDetails?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "PaymentSessionParams(paymentIntentId=$paymentIntentId, presentation=$presentation, " +
            "billingDetails=$billingDetails)"

    public companion object {
        private const val TAG_METHOD_LIST = 0
        private const val TAG_CARD_ONLY = 1
        private const val TAG_SINGLE_WALLET = 2

        private const val BILLING_ABSENT = 0
        private const val BILLING_PRESENT = 1

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

        /**
         * Read through [BillingDetails.CREATOR] directly rather than
         * `Parcel.readParcelable`, which needs a classloader the framework does not attach
         * when it re-marshals an Intent after process death — the same trap
         * `UQPayPaymentActivity.readParams` guards against one level up. A tag that is
         * neither [BILLING_PRESENT] nor [BILLING_ABSENT] is read as absent: no prefill is
         * always survivable, a crash mid-payment is not.
         */
        private fun readBillingDetails(parcel: Parcel): BillingDetails? =
            if (parcel.readInt() == BILLING_PRESENT) {
                BillingDetails.CREATOR.createFromParcel(parcel)
            } else {
                null
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
