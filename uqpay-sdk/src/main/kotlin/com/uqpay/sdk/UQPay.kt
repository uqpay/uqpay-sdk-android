package com.uqpay.sdk

import android.content.Context
import androidx.activity.ComponentActivity
import com.uqpay.sdk.launcher.UQPayPaymentContract
import com.uqpay.sdk.launcher.UQPayPaymentLauncherImpl
import com.uqpay.sdk.payment.PaymentCallback
import com.uqpay.sdk.payment.UQPayPaymentLauncher
import java.util.concurrent.atomic.AtomicReference

/**
 * Single public entry point of the UQPAY SDK for Android.
 *
 * Initialize once in `Application.onCreate`, then create a launcher in each Activity
 * that takes payments:
 *
 * ```kotlin
 * class CheckoutActivity : AppCompatActivity() {
 *     private lateinit var payments: UQPayPaymentLauncher
 *
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         payments = UQPay.createPaymentLauncher(this) { result ->
 *             when (result.status) {
 *                 PaymentStatus.SUCCEEDED -> confirmWithBackend(result.paymentIntentId)
 *                 PaymentStatus.FAILED    -> showError(result.error?.message)
 *                 PaymentStatus.CANCELLED -> Unit
 *                 PaymentStatus.PENDING   -> awaitWebhook(result.paymentIntentId)
 *             }
 *         }
 *     }
 *
 *     private fun onPayClicked(intentId: String) {
 *         payments.launch(PaymentSessionParams(intentId))
 *     }
 * }
 * ```
 */
public object UQPay {

    private val configuration = AtomicReference<UQPayConfiguration?>(null)
    private val appContext = AtomicReference<Context?>(null)

    /** SDK version string, e.g. `"0.1.0"`. */
    @JvmStatic
    public val version: String
        get() = BuildConfig.UQPAY_SDK_VERSION

    /** Whether [initialize] has been called successfully. */
    @JvmStatic
    public val isInitialized: Boolean
        get() = configuration.get() != null

    /**
     * Initializes the SDK. Safe and cheap to call from `Application.onCreate`: it stores
     * the configuration and nothing else — no network, no disk, no background work — so
     * it cannot affect host app cold start.
     *
     * Calling it again replaces the configuration.
     *
     * @param context any [Context]; the application context is retained, never the
     *   passed instance.
     * @param configuration merchant client id, target [Environment], and the token
     *   provider the SDK authenticates with.
     */
    @JvmStatic
    public fun initialize(context: Context, configuration: UQPayConfiguration) {
        appContext.set(context.applicationContext)
        this.configuration.set(configuration)
    }

    /**
     * Creates a payment launcher bound to [activity].
     *
     * **Call this unconditionally during `onCreate`, before the Activity is STARTED.**
     * Creating it lazily — on a button tap, or only when `savedInstanceState == null` —
     * breaks result delivery after process death, because the framework redelivers a
     * pending result only to a launcher that has been re-registered by then. Creating it
     * on every Activity creation is exactly what makes delivery survive.
     *
     * [callback] is invoked exactly once per payment, on the main thread.
     *
     * @throws IllegalStateException if the SDK is not initialized. This is a programmer
     *   error, never a payment outcome.
     */
    @JvmStatic
    public fun createPaymentLauncher(
        activity: ComponentActivity,
        callback: PaymentCallback,
    ): UQPayPaymentLauncher {
        check(isInitialized) {
            "UQPay is not initialized. Call UQPay.initialize(context, configuration) " +
                "from Application.onCreate() before creating a payment launcher."
        }
        val registered = activity.registerForActivityResult(UQPayPaymentContract()) { result ->
            callback.onResult(result)
        }
        return UQPayPaymentLauncherImpl(registered, callback)
    }

    /**
     * The active configuration.
     *
     * @throws IllegalStateException if the SDK is not initialized.
     */
    internal fun requireConfiguration(): UQPayConfiguration =
        checkNotNull(configuration.get()) {
            "UQPay is not initialized. Call UQPay.initialize(context, configuration) " +
                "from Application.onCreate()."
        }

    /** The retained application context. */
    internal fun requireAppContext(): Context =
        checkNotNull(appContext.get()) {
            "UQPay is not initialized. Call UQPay.initialize(context, configuration) " +
                "from Application.onCreate()."
        }

    /** Resets all state. Test-only. */
    internal fun resetForTest() {
        configuration.set(null)
        appContext.set(null)
    }
}
