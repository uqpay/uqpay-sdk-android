package com.uqpay.sdk.network

import android.util.Log

/**
 * Internal diagnostics. **Off by default**; a merchant opts in with
 * `UQPayConfiguration(loggingEnabled = true)`, and [Noop] is what every other build gets.
 *
 * Hard rule: this logger never receives a request or response body, in any environment.
 * Airwallex's SDK logs full bodies — PAN included — whenever `environment != PRODUCTION`,
 * which puts card numbers one misconfiguration away from Logcat. We do not have that
 * switch at all. Callers pass method, redacted path, status, and trace id; nothing else.
 */
internal interface UQPayLogger {

    fun debug(message: String)

    fun error(message: String, t: Throwable? = null)

    /** The default. Discards everything. */
    object Noop : UQPayLogger {
        override fun debug(message: String): Unit = Unit

        override fun error(message: String, t: Throwable?): Unit = Unit
    }

    /**
     * The opt-in implementation, selected by `UQPayConfiguration.loggingEnabled` and
     * constructed in exactly one place — `PaymentSession.build`.
     *
     * This exists because the SDK's degradations are all "log and continue": an unwritable
     * pin store, a discarded pin blob, a superseded confirm, an exhausted poll budget. With
     * only [Noop] in the graph those lines went nowhere, and a merchant reporting "it
     * sometimes just says pending" had nothing to send us. See the interface KDoc for the
     * hard rule on what may be passed here — no bodies, ever, in any environment.
     */
    class Logcat(private val tag: String = "UQPay") : UQPayLogger {
        override fun debug(message: String) {
            Log.d(tag, message)
        }

        override fun error(message: String, t: Throwable?) {
            Log.e(tag, message, t)
        }
    }
}
