package com.uqpay.sample

import java.math.BigDecimal

/**
 * One line in the cart.
 *
 * @property name what the customer is buying.
 * @property size the variant, shown under the name.
 * @property price the line price, in major units.
 */
data class CartItem(
    val name: String,
    val size: String,
    val price: BigDecimal,
)

/**
 * The demo store's cart, and the only place a price is decided in this app.
 *
 * ### Money is `BigDecimal`, never `Double`
 *
 * `10.00 + 10.00 + 0.01` in binary floating point is not `20.01`, and the error compounds
 * across a subtotal, a delivery fee and a discount. The gateway takes a decimal string in
 * major units (`"20.01"`), so a `Double` would have to be formatted back into one anyway —
 * and formatting is where a rounding difference between what the customer was shown and
 * what they were charged gets introduced. `BigDecimal` throughout removes the question.
 *
 * Every amount is fixed at scale 2, so `toPlainString()` is the wire format directly.
 *
 * ### What a real app does differently
 *
 * A real store's prices come from its backend, and its backend — not this class — decides
 * what the customer is charged. See [DemoMerchantBackend].
 */
object Cart {

    val items: List<CartItem> = listOf(
        CartItem(name = "T-Shirt", size = "XL", price = money("10.00")),
        CartItem(name = "Jeans", size = "XL", price = money("10.00")),
        // Wallet QRs from the sandbox can charge REAL money on a real phone, so testing
        // one for real needs a one-cent order: remove the other two lines and this is the
        // whole charge. The delivery fee below is waived so it stays exactly 0.01.
        CartItem(
            name = "Test Payment",
            size = "1 cent · for real-money wallet tests",
            price = money("0.01"),
        ),
    )

    /** No promotion in the demo, but the line is drawn so the breakdown is complete. */
    val discount: BigDecimal = money("0.00")

    val currency: String = "SGD"

    val subtotal: BigDecimal
        get() = items.fold(money("0.00")) { running, item -> running + item.price }

    /**
     * Waived under one dollar, so a one-cent wallet test charges exactly one cent rather
     * than 2.01 — which on a real device is real money.
     */
    val deliveryFee: BigDecimal
        get() = if (subtotal < money("1.00")) money("0.00") else money("2.00")

    val total: BigDecimal
        get() = subtotal + deliveryFee - discount

    /** What the intent's description says. */
    fun description(): String = "Order of ${items.size} item(s)"
}

/**
 * Where the order is going. Hard-coded, and obviously fake — see [DemoCustomer] for the
 * same rule applied to the billing details the SDK is handed.
 */
object ShippingAddress {
    const val NAME: String = "John Tan"
    const val PHONE: String = "+65 9123 4567"
    const val ADDRESS: String = "123 Orchard Road, #12-01, Singapore 238888"
}

/** Fixed scale 2: every amount in this app is a two-decimal-place money value. */
private fun money(value: String): BigDecimal = BigDecimal(value).setScale(2)
