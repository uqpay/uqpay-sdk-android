package com.uqpay.sdk.payment

/**
 * A payment method identifier.
 *
 * An **open set**, not an enum: the methods offered for a payment come from the
 * gateway's `available_payment_method_types` and UQPAY can enable new ones without an
 * SDK release. A method this SDK version cannot render is hidden from the customer,
 * never surfaced as an error.
 */
public class PaymentMethodType private constructor(
    /** Wire value, e.g. `"alipaycn"`. */
    public val raw: String,
) {
    override fun equals(other: Any?): Boolean = other is PaymentMethodType && other.raw == raw

    override fun hashCode(): Int = raw.hashCode()

    override fun toString(): String = raw

    public companion object {
        /** Card payment, with 3-D Secure enforced. */
        @JvmField
        public val CARD: PaymentMethodType = PaymentMethodType("card")

        @JvmField
        public val WECHAT_PAY: PaymentMethodType = PaymentMethodType("wechatpay")

        @JvmField
        public val ALIPAY_CN: PaymentMethodType = PaymentMethodType("alipaycn")

        @JvmField
        public val ALIPAY_HK: PaymentMethodType = PaymentMethodType("alipayhk")

        @JvmField
        public val GRABPAY: PaymentMethodType = PaymentMethodType("grabpay")

        @JvmField
        public val PAYNOW: PaymentMethodType = PaymentMethodType("paynow")

        @JvmField
        public val UNIONPAY: PaymentMethodType = PaymentMethodType("unionpay")

        @JvmField
        public val TRUEMONEY: PaymentMethodType = PaymentMethodType("truemoney")

        /** Touch 'n Go. */
        @JvmField
        public val TNG: PaymentMethodType = PaymentMethodType("tng")

        @JvmField
        public val GCASH: PaymentMethodType = PaymentMethodType("gcash")

        @JvmField
        public val DANA: PaymentMethodType = PaymentMethodType("dana")

        @JvmField
        public val KAKAOPAY: PaymentMethodType = PaymentMethodType("kakaopay")

        @JvmField
        public val TOSSPAY: PaymentMethodType = PaymentMethodType("tosspay")

        @JvmField
        public val NAVERPAY: PaymentMethodType = PaymentMethodType("naverpay")

        /** Returns the method for [raw], preserving values this SDK version predates. */
        @JvmStatic
        public fun of(raw: String): PaymentMethodType = PaymentMethodType(raw)
    }
}
