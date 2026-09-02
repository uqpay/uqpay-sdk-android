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
 * @property allowedPaymentMethods restricts which of the intent's methods this payment may
 *   use. Null — the default — means no restriction: every method the intent offers and this
 *   SDK version can render is shown. See the property's own documentation for what an empty
 *   set means and how it interacts with [presentation].
 */
public class PaymentSessionParams @JvmOverloads constructor(
    public val paymentIntentId: String,
    public val presentation: Presentation = Presentation.MethodList,
    public val billingDetails: BillingDetails? = null,
    /**
     * The methods this payment may use, or null for "whatever the intent offers".
     *
     * The case this exists for is a per-region or per-risk-tier rule: "cards and PayNow
     * only for this customer", decided by the merchant's own backend, with one intent shape
     * behind it. Before this, the only ways to express that were an all-methods sheet
     * (which ignores the rule) or [Presentation.SingleWallet] (which can express exactly one
     * method and no card).
     *
     * ### It is a restriction, never an addition
     *
     * The sheet shows the **intersection** of this set, the intent's own
     * `available_payment_method_types`, and the methods this SDK version can render — in the
     * gateway's order, card first, unchanged. Naming a method the intent does not offer adds
     * nothing; it is ignored rather than treated as an error, because the intent is the
     * authority on what is payable and a merchant list that drifts from it must not break a
     * checkout.
     *
     * ### An empty set is honoured, not widened
     *
     * An empty set means "no method may be used", and the sheet says so rather than
     * quietly falling back to showing everything. Widening a restriction because it came out
     * empty is how a risk control turns into a decoration; if your rules can produce an
     * empty list, do not launch the sheet at all.
     *
     * ### With an explicit [presentation]
     *
     * [Presentation.CardOnly] and [Presentation.SingleWallet] each name one method. If that
     * method is not in this set the request contradicts itself, and the payment ends
     * immediately with [PaymentStatus.FAILED] and
     * [com.uqpay.sdk.error.UQPayErrorCode.INVALID_PAYMENT_METHOD] — before any network call,
     * and without the customer being shown a method they were not allowed to use. The
     * failure names the contradiction in
     * [com.uqpay.sdk.error.UQPayError.developerMessage].
     *
     * ### Values this SDK version predates
     *
     * [PaymentMethodType] is not an enum, so a set may contain a method this SDK cannot
     * render. Such an entry restricts nothing it could have shown anyway, and is neither an
     * error nor a crash.
     */
    public val allowedPaymentMethods: Set<PaymentMethodType>? = null,
) : Parcelable {

    /**
     * Where the payment flow starts. Honoured on every path — a merchant asking for
     * [CardOnly] never sees the method list.
     */
    public sealed class Presentation {

        /**
         * Show every method the intent offers, in the gateway's order with card first.
         * Methods this SDK version cannot render are hidden rather than erroring, and
         * [allowedPaymentMethods] narrows the list further when it is set.
         */
        public data object MethodList : Presentation()

        /** Skip the list and open the card form directly. */
        public data object CardOnly : Presentation()

        /**
         * Skip the list and open one wallet's QR flow directly.
         *
         * @property method the wallet to present. Must be offered by the intent, and must be
         *   a wallet: `SingleWallet(PaymentMethodType.CARD)` ends the payment immediately with
         *   `FAILED` / `INVALID_PAYMENT_METHOD` before any network call — use [CardOnly] for
         *   card.
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
     * ### Build one with [Builder], not with ten positional strings
     *
     * The constructor takes ten `String?` parameters in a row, six of which are address
     * lines. From Kotlin, name them. From Java there are no named arguments, and
     * `new BillingDetails(f, l, e, p, a1, a2, "Singapore", "Singapore", "238888", "SG")`
     * compiles just as cleanly with `city` and `state` the wrong way round — which sends
     * wrong AVS data on every payment, forever, with nothing to notice it by. [Builder]
     * exists so the field name is written next to the value:
     *
     * ```java
     * BillingDetails billing = new BillingDetails.Builder()
     *     .firstName("Jo").lastName("Tan")
     *     .city("Singapore").state("Singapore")
     *     .countryCode("SG")
     *     .build();
     * ```
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

        /**
         * Names every value it sets, so no pair of adjacent fields can be transposed
         * without the compiler — or a reviewer — seeing it.
         *
         * Each setter returns `this`, so calls chain. Every field is optional; send only
         * what you actually hold. Calling a setter twice keeps the last value, and building
         * twice from the same builder produces two equal, independent instances.
         *
         * From Kotlin, named arguments on the constructor do the same job and read better;
         * this exists mainly for Java, where they do not exist.
         */
        public class Builder {
            private var firstName: String? = null
            private var lastName: String? = null
            private var email: String? = null
            private var phone: String? = null
            private var addressLine1: String? = null
            private var addressLine2: String? = null
            private var city: String? = null
            private var state: String? = null
            private var postalCode: String? = null
            private var countryCode: String? = null

            /** The cardholder's given name. */
            public fun firstName(firstName: String?): Builder = apply { this.firstName = firstName }

            /** The cardholder's family name. */
            public fun lastName(lastName: String?): Builder = apply { this.lastName = lastName }

            /** Billing email address. */
            public fun email(email: String?): Builder = apply { this.email = email }

            /** Billing phone number, in whatever form your records hold it. */
            public fun phone(phone: String?): Builder = apply { this.phone = phone }

            /** Street address, first line. */
            public fun addressLine1(addressLine1: String?): Builder =
                apply { this.addressLine1 = addressLine1 }

            /** Street address, second line (unit, floor, building). */
            public fun addressLine2(addressLine2: String?): Builder =
                apply { this.addressLine2 = addressLine2 }

            /** City or town. */
            public fun city(city: String?): Builder = apply { this.city = city }

            /** State, province or region. */
            public fun state(state: String?): Builder = apply { this.state = state }

            /** Postal or ZIP code. */
            public fun postalCode(postalCode: String?): Builder =
                apply { this.postalCode = postalCode }

            /** ISO 3166-1 alpha-2 country code, e.g. `"SG"`. Case-insensitive. */
            public fun countryCode(countryCode: String?): Builder =
                apply { this.countryCode = countryCode }

            /** Builds the prefill. Safe to call more than once. */
            public fun build(): BillingDetails = BillingDetails(
                firstName = firstName,
                lastName = lastName,
                email = email,
                phone = phone,
                addressLine1 = addressLine1,
                addressLine2 = addressLine2,
                city = city,
                state = state,
                postalCode = postalCode,
                countryCode = countryCode,
            )
        }

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
        allowedPaymentMethods = readAllowedMethods(parcel),
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
        // -1 rather than 0 for "no restriction": zero is a real, meaningful size here (an
        // empty allow-list is honoured, not widened), so the two cannot share an encoding.
        val allowed = allowedPaymentMethods
        if (allowed == null) {
            dest.writeInt(ALLOWED_ABSENT)
        } else {
            dest.writeInt(allowed.size)
            allowed.forEach { dest.writeString(it.raw) }
        }
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean =
        other is PaymentSessionParams &&
            other.paymentIntentId == paymentIntentId &&
            other.presentation == presentation &&
            other.billingDetails == billingDetails &&
            other.allowedPaymentMethods == allowedPaymentMethods

    override fun hashCode(): Int {
        var result = paymentIntentId.hashCode()
        result = 31 * result + presentation.hashCode()
        result = 31 * result + (billingDetails?.hashCode() ?: 0)
        result = 31 * result + (allowedPaymentMethods?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "PaymentSessionParams(paymentIntentId=$paymentIntentId, presentation=$presentation, " +
            "billingDetails=$billingDetails, allowedPaymentMethods=$allowedPaymentMethods)"

    public companion object {
        private const val TAG_METHOD_LIST = 0
        private const val TAG_CARD_ONLY = 1
        private const val TAG_SINGLE_WALLET = 2

        private const val BILLING_ABSENT = 0
        private const val BILLING_PRESENT = 1

        private const val ALLOWED_ABSENT = -1

        /**
         * A defensive upper bound on the allow-list read back from a parcel. The set can
         * only ever hold as many entries as there are methods, and a garbled parcel that
         * reads a huge count here would otherwise allocate against it before failing.
         */
        private const val MAX_ALLOWED_METHODS = 256

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

        /**
         * Reads the allow-list, degrading to "no restriction" for anything that is not a
         * plausible count.
         *
         * The degrade direction is deliberate and is the *opposite* of the empty-set rule
         * above. An empty set the merchant actually wrote is a decision and is honoured; a
         * count this code cannot make sense of is a corrupt parcel, and inventing an empty
         * restriction from corruption would leave a customer looking at a sheet with no way
         * to pay. Between "show more than intended" and "show nothing at all" on data we
         * cannot trust, the former still lets the payment happen and the intent still bounds
         * what is actually payable.
         */
        private fun readAllowedMethods(parcel: Parcel): Set<PaymentMethodType>? {
            val size = parcel.readInt()
            if (size < 0 || size > MAX_ALLOWED_METHODS) return null
            if (size == 0) return emptySet()
            val methods = LinkedHashSet<PaymentMethodType>(size)
            repeat(size) {
                parcel.readString()?.takeIf { it.isNotBlank() }
                    ?.let { methods += PaymentMethodType.of(it) }
            }
            return methods
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
