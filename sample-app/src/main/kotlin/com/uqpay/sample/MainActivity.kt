package com.uqpay.sample

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.uqpay.sdk.UQPay
import com.uqpay.sdk.payment.PaymentResult
import com.uqpay.sdk.payment.PaymentSessionParams
import com.uqpay.sdk.payment.PaymentStatus
import com.uqpay.sdk.payment.UQPayPaymentLauncher
import java.math.BigDecimal
import java.util.concurrent.Executors

/**
 * The demo store's one screen: a cart, a price breakdown, where it is going, and Checkout.
 *
 * This is the whole merchant-side surface of the SDK, and it is four steps:
 *
 * 1. Create a launcher in `onCreate`, unconditionally.
 * 2. Ask **your backend** for a payment intent when the customer checks out.
 * 3. `launch` it, with whatever billing details you already know.
 * 4. Handle four outcomes.
 *
 * Views and XML rather than Compose, on purpose: the SDK's payment UI is Compose
 * internally, and this app is the proof that a merchant does not have to adopt Compose —
 * or match a Compose version — to use it.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var payments: UQPayPaymentLauncher
    private lateinit var checkoutButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    /**
     * One background thread for the pretend backend call. A real app would use whatever it
     * already has — coroutines, RxJava, WorkManager. What matters is that no network call
     * runs on the main thread; the SDK holds itself to the same rule.
     */
    private val backgroundWork = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkoutButton = findViewById(R.id.btn_checkout)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.txt_status)

        // Created unconditionally, on every Activity creation. This is not optional: it is
        // what lets the result come back after Android kills and recreates the process
        // mid-payment. Creating it on the button tap instead would silently lose results.
        payments = UQPay.createPaymentLauncher(this, ::onPaymentResult)

        renderCart()
        renderEnvironmentBadge()

        val setupProblem = DemoMerchantBackend.setupProblem()
        if (setupProblem != null) {
            showSetupInstructions(setupProblem)
        } else {
            checkoutButton.setOnClickListener { checkout() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundWork.shutdown()
    }

    // ---- the screen ---------------------------------------------------------------------

    private fun renderCart() {
        val items = findViewById<LinearLayout>(R.id.container_items)
        for (item in Cart.items) {
            val row = layoutInflater.inflate(R.layout.item_cart, items, false)
            row.findViewById<TextView>(R.id.txt_item_name).text = item.name
            row.findViewById<TextView>(R.id.txt_item_size).text = item.size
            row.findViewById<TextView>(R.id.txt_item_price).text = money(item.price)
            items.addView(row)
        }

        val priceLines = findViewById<LinearLayout>(R.id.container_price_lines)
        priceLine(priceLines, getString(R.string.price_subtotal), Cart.subtotal)
        priceLine(priceLines, getString(R.string.price_delivery), Cart.deliveryFee)
        priceLine(priceLines, getString(R.string.price_discount), Cart.discount)
        findViewById<TextView>(R.id.txt_total).text = money(Cart.total)

        findViewById<TextView>(R.id.txt_shipping_name).text =
            getString(R.string.shipping_line, ShippingAddress.NAME, ShippingAddress.PHONE)
        findViewById<TextView>(R.id.txt_shipping_address).text = ShippingAddress.ADDRESS
    }

    /**
     * A money amount as the customer reads it. `toPlainString` rather than a locale
     * formatter: the two decimal places are the ones the gateway is being sent, and a
     * display that rounded differently from the charge is the bug this whole app avoids by
     * keeping money in `BigDecimal`.
     */
    private fun money(amount: BigDecimal): String =
        getString(R.string.price_amount, amount.toPlainString())

    private fun priceLine(parent: ViewGroup, label: String, amount: BigDecimal) {
        val line = LayoutInflater.from(this).inflate(R.layout.item_price_line, parent, false)
        line.findViewById<TextView>(R.id.txt_price_label).text = label
        line.findViewById<TextView>(R.id.txt_price_value).text = money(amount)
        parent.addView(line)
    }

    /**
     * Reads the configured environment rather than the word "SANDBOX", so a build pointed
     * at production says PRODUCTION. A demo build that looks identical to a live one is how
     * someone ends up testing against real money.
     */
    private fun renderEnvironmentBadge() {
        findViewById<TextView>(R.id.txt_environment_badge).text =
            DemoMerchantBackend.environment.name
    }

    /**
     * What a developer sees before anything is configured: the exact keys to add and where,
     * in place of a Checkout button that could only fail. This is the first screen anyone
     * cloning the repo meets.
     */
    private fun showSetupInstructions(problem: String) {
        checkoutButton.isEnabled = false
        findViewById<TextView>(R.id.txt_setup).apply {
            text = problem
            visibility = View.VISIBLE
        }
    }

    // ---- checkout -----------------------------------------------------------------------

    /**
     * Charges exactly what the cart shows.
     *
     * The amount is taken from [Cart.total] rather than a constant, so what the customer
     * was shown and what the intent is created for cannot drift apart. On a real
     * integration this whole method is one call to your own server, which owns the price.
     */
    private fun checkout() {
        setWorking(true)
        backgroundWork.execute {
            val outcome = runCatching {
                DemoMerchantBackend.createPaymentIntent(
                    amount = Cart.total,
                    description = Cart.description(),
                )
            }
            runOnUiThread {
                setWorking(false)
                outcome
                    .onSuccess { intentId -> startPayment(intentId) }
                    .onFailure { failure ->
                        showStatus(
                            getString(
                                R.string.status_checkout_failed,
                                failure.message ?: failure.javaClass.simpleName,
                            ),
                        )
                    }
            }
        }
    }

    /**
     * Opens the payment sheet.
     *
     * [DemoCustomer.billingDetails] is the whole reason this demo is pleasant to use: the
     * customer reaches the card form with their name, contact details and address already
     * filled in and still editable, leaving only the card number, expiry and security code
     * to type. Those three are never prefillable, from any app.
     *
     * Omitting `billingDetails` is perfectly fine and leaves every field empty — a merchant
     * who does not hold the customer's address simply does not pass one.
     */
    private fun startPayment(paymentIntentId: String) {
        showStatus(getString(R.string.status_starting))
        payments.launch(
            PaymentSessionParams(
                paymentIntentId = paymentIntentId,
                billingDetails = DemoCustomer.billingDetails,
            ),
        )
    }

    private fun setWorking(working: Boolean) {
        checkoutButton.isEnabled = !working
        checkoutButton.setText(if (working) R.string.checkout_working else R.string.checkout_button)
        progress.visibility = if (working) View.VISIBLE else View.GONE
    }

    // ---- the four outcomes ----------------------------------------------------------------

    /**
     * Delivered exactly once per payment, on the main thread, across rotation and process
     * death. Every payment ends in exactly one of these four; the SDK never throws for a
     * payment outcome.
     */
    private fun onPaymentResult(result: PaymentResult) {
        showStatus(
            when (result.status) {
                // Advisory only. A real app confirms with its backend, which knows the
                // webhook outcome, before fulfilling anything — a client-side success is
                // still a client saying so.
                PaymentStatus.SUCCEEDED ->
                    getString(R.string.status_succeeded, result.paymentIntentId)

                // Nothing was charged. Safe to offer the customer another attempt.
                PaymentStatus.FAILED ->
                    getString(R.string.status_failed, result.error?.message.orEmpty())

                // The customer backed out before anything was submitted.
                PaymentStatus.CANCELLED -> getString(R.string.status_cancelled)

                // The one that needs care. A payment was submitted and its outcome is not
                // known yet — the customer closed a wallet QR, or the app died mid-confirm.
                // Do NOT retry it, do NOT refund it, and do NOT release the order: any of
                // the three can double-charge or ship for free. The webhook is the answer.
                PaymentStatus.PENDING -> getString(R.string.status_pending)
            },
        )
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }
}
