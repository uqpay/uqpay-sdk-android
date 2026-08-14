package com.uqpay.sample

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Sample integration entry point. Will demonstrate the full merchant flow once SDK
 * logic lands: initialize UQPay, create an intent via a (mock) backend, start a
 * payment, and render each PaymentStatus outcome.
 *
 * Sandbox keys must come from local.properties / BuildConfig — never hardcoded.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
