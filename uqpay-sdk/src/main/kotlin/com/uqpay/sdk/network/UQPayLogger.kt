package com.uqpay.sdk.network

import android.util.Log

/**
 * Internal diagnostics. **Off by default** and never enabled in production builds.
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

    /** Opt-in, for integration debugging in sandbox only. */
    class Logcat(private val tag: String = "UQPay") : UQPayLogger {
        override fun debug(message: String) {
            Log.d(tag, message)
        }

        override fun error(message: String, t: Throwable?) {
            Log.e(tag, message, t)
        }
    }
}
