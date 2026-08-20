package com.uqpay.sdk

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCaller
import com.uqpay.sdk.appearance.UQPayAppearance
import com.uqpay.sdk.error.ErrorCopy
import com.uqpay.sdk.launcher.UQPayPaymentContract
import com.uqpay.sdk.launcher.UQPayPaymentLauncherImpl
import com.uqpay.sdk.payment.PaymentCallback
import com.uqpay.sdk.payment.UQPayPaymentLauncher
import java.util.concurrent.atomic.AtomicReference

/**
 * Single public entry point of the UQPAY SDK for Android.
 *
 * Initialize once in `Application.onCreate`, then create a launcher in each Activity or
 * Fragment that takes payments:
 *
 * ```kotlin
 * class CheckoutActivity : ComponentActivity() {
 *     private lateinit var payments: UQPayPaymentLauncher
 *
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         payments = UQPay.createPaymentLauncher(this) { result ->
 *             when (result.status) {
 *                 PaymentStatus.SUCCEEDED -> confirmWithBackend(result.paymentIntentId)
 *                 PaymentStatus.CANCELLED -> Unit
 *                 PaymentStatus.PENDING   -> awaitWebhook(result.paymentIntentId)
 *                 PaymentStatus.FAILED    -> {
 *                     // `message` is written for the shopper and is the one to show.
 *                     showError(result.error?.message)
 *                     // `developerMessage` is written for you. Log it; never show it.
 *                     Log.w("checkout", result.error?.developerMessage.orEmpty())
 *                 }
 *             }
 *         }
 *     }
 *
 *     private fun onPayClicked(intentId: String) {
 *         payments.launch(PaymentSessionParams(intentId))
 *     }
 * }
 * ```
 *
 * The same call works from a `Fragment` — see [createPaymentLauncher]. From a Compose host,
 * still create the launcher in `onCreate` and pass it into the composition; the integration
 * guide has the pattern.
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
     *   provider the SDK authenticates with. A configuration that cannot authenticate —
     *   a blank `clientId` — cannot be constructed at all; see [UQPayConfiguration].
     * @throws IllegalArgumentException never from here directly: the configuration
     *   validates itself when it is built, so an unusable one fails at its own
     *   construction site rather than at a customer's checkout.
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
    ): UQPayPaymentLauncher = createPaymentLauncher(activity as ActivityResultCaller, callback)

    /**
     * Creates a payment launcher bound to any [ActivityResultCaller] — which is to say a
     * `ComponentActivity` **or a `Fragment`**.
     *
     * This overload is what makes the SDK usable from a Fragment. Both types implement
     * [ActivityResultCaller], and registering through the interface gets the same
     * process-death-safe redelivery in both: the framework tracks the registration against
     * the host's own lifecycle, so a Fragment recreated in a new process re-registers and
     * the pending result finds it.
     *
     * ```kotlin
     * class CheckoutFragment : Fragment() {
     *     private lateinit var payments: UQPayPaymentLauncher
     *
     *     override fun onCreate(savedInstanceState: Bundle?) {
     *         super.onCreate(savedInstanceState)
     *         payments = UQPay.createPaymentLauncher(this) { result -> … }
     *     }
     * }
     * ```
     *
     * ### Where to call it, in either host
     *
     * The same rule as the Activity overload, in the Fragment's vocabulary: **`onCreate` or
     * `onViewCreated`, unconditionally, every time.** Registering in `onResume`, behind an
     * `if`, or on a click is what loses a result after process death.
     *
     * ### From a `@Composable`
     *
     * Do not register inside a composable. A composable is by definition conditional — it
     * runs when something decides to compose it — and a registration that has not happened
     * by the time a redelivered result arrives is a payment outcome dropped on the floor.
     * Create the launcher in the host Activity's or Fragment's `onCreate` and pass it into
     * the composition; see "Compose hosts" in the integration guide for the six lines.
     *
     * [callback] is invoked exactly once per payment, on the main thread.
     *
     * @throws IllegalStateException if the SDK is not initialized. This is a programmer
     *   error, never a payment outcome.
     */
    @JvmStatic
    public fun createPaymentLauncher(
        caller: ActivityResultCaller,
        callback: PaymentCallback,
    ): UQPayPaymentLauncher {
        check(isInitialized) {
            "UQPay is not initialized. Call UQPay.initialize(context, configuration) " +
                "from Application.onCreate() before creating a payment launcher."
        }
        // Safe: `isInitialized` is true, and initialize() sets the context before the
        // configuration, so a context is present whenever a configuration is.
        val copy = ErrorCopy.from(requireAppContext())
        val registered = caller.registerForActivityResult(UQPayPaymentContract(copy)) { result ->
            callback.onResult(result)
        }
        return UQPayPaymentLauncherImpl(registered, callback, copy)
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

    /**
     * The configured appearance, or the stock one.
     *
     * Never throws, because its callers are Compose functions: a `@Preview`, a screenshot
     * test, or the one frame between process death and the host's `Application.onCreate`
     * running again must draw *something* rather than take the app down over a colour. The
     * payment itself still refuses to start uninitialised — that check lives in
     * [createPaymentLauncher] and in `PaymentSession.obtain`, where it can be reported as a
     * result instead of thrown at a composition.
     */
    internal fun appearanceOrDefault(): UQPayAppearance =
        configuration.get()?.appearance ?: UQPayAppearance.DEFAULT

    /**
     * The configured environment, or null when the SDK is not initialised.
     *
     * Read by the payment UI for one purpose: drawing the test-mode badge in
     * [Environment.SANDBOX]. Null is treated as "not sandbox" by that call site, so an
     * uninitialised preview does not claim to be a test payment.
     */
    internal fun environmentOrNull(): Environment? = configuration.get()?.environment

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
