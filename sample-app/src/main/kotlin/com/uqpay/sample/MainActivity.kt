package com.uqpay.sample

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.uqpay.sdk.UQPay
import com.uqpay.sdk.payment.PaymentResult
import com.uqpay.sdk.payment.PaymentSessionParams
import com.uqpay.sdk.payment.PaymentStatus
import com.uqpay.sdk.payment.UQPayPaymentLauncher

/**
 * Sample integration. This is the whole merchant-side surface: create a launcher in
 * `onCreate`, call `launch` with an intent id your backend created, handle four
 * outcomes.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var payments: UQPayPaymentLauncher
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.txt_status)
        val intentId = findViewById<EditText>(R.id.edit_intent_id)

        // Created unconditionally, on every Activity creation. This is not optional:
        // it is what lets the result come back after Android kills and recreates the
        // process mid-payment. Creating it on the button tap instead would silently
        // lose results.
        payments = UQPay.createPaymentLauncher(this, ::onPaymentResult)

        findViewById<Button>(R.id.btn_pay).setOnClickListener {
            val id = intentId.text.toString().trim()
            if (id.isEmpty()) {
                status.text = getString(R.string.status_need_intent_id)
            } else {
                status.text = getString(R.string.status_starting)
                payments.launch(PaymentSessionParams(paymentIntentId = id))
            }
        }
    }

    private fun onPaymentResult(result: PaymentResult) {
        status.text = when (result.status) {
            // Advisory only. A real app confirms with its backend, which knows the
            // webhook outcome, before fulfilling anything.
            PaymentStatus.SUCCEEDED ->
                getString(R.string.status_succeeded, result.paymentIntentId)

            PaymentStatus.FAILED ->
                getString(
                    R.string.status_failed,
                    result.error?.message.orEmpty(),
                    result.error?.traceId.orEmpty(),
                )

            PaymentStatus.CANCELLED -> getString(R.string.status_cancelled)

            // The payment may still be live. Do not retry, refund, or release the
            // order — wait for the webhook.
            PaymentStatus.PENDING -> getString(R.string.status_pending)
        }
    }
}
