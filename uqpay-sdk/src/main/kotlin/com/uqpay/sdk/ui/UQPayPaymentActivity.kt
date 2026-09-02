package com.uqpay.sdk.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.uqpay.sdk.engine.EngineState
import com.uqpay.sdk.engine.PaymentSession
import com.uqpay.sdk.engine.Presentation
import com.uqpay.sdk.error.ErrorCopy
import com.uqpay.sdk.error.UQPayError
import com.uqpay.sdk.error.UQPayErrorCode
import com.uqpay.sdk.launcher.UQPayPaymentContract
import com.uqpay.sdk.UQPay
import com.uqpay.sdk.appearance.UQPayAppearance
import com.uqpay.sdk.payment.PaymentMethodType
import com.uqpay.sdk.payment.PaymentResult
import com.uqpay.sdk.payment.PaymentSessionParams
import com.uqpay.sdk.payment.PaymentStatus
import com.uqpay.sdk.ui.threeds.ThreeDsBrowsingState
import kotlinx.coroutines.launch

/**
 * The SDK-owned host of the payment flow. Launched only through
 * [com.uqpay.sdk.UQPay.createPaymentLauncher] via [UQPayPaymentContract]; never exported.
 *
 * ### What it owns, and what it does not
 *
 * It owns exactly three things: **attaching** to the payment (a [PaymentSession] found or
 * built by intent id), **hosting** the screen (Compose, driven by [PaymentViewModel]), and
 * **delivering** the one result. It owns no payment logic — the engine in the session
 * decides everything about money — and no screen logic — the ViewModel decides what a
 * back-press means. That split is what makes each disaster case checkable in isolation.
 *
 * It hosts exactly **the payment it was created for**, and one payment per instance: the
 * session, the ViewModel, the delivery collector and [paymentIntentId] are all fixed at
 * `onCreate`. A second launch is a second instance (see the manifest), and a second launch
 * delivered to *this* instance is refused rather than adopted — see [onNewIntent].
 *
 * ### Rotation re-attaches; it never re-submits
 *
 * `onCreate` calls [PaymentSession.obtain], which returns the *running* session for this
 * intent if there is one, and then [PaymentSession.attachHost] to claim it. A recreated
 * Activity therefore finds the same engine mid-flight;
 * [PaymentSession.startIfNeeded] is a no-op the second time, so the intent is not read
 * again and no second confirm can be sent. `onDestroy` releases the session **only** when
 * finishing for good — on a configuration change it does nothing, which is precisely what
 * keeps the session there for the next instance to find.
 *
 * ### One result, one path (F3)
 *
 * Every exit goes through [finishWith], which stamps `EXTRA_INTENT_ID` from the launch
 * params — so a merchant can always tell *which* payment ended, even if some future result
 * object carried a blank id — and delivers `RESULT_OK` with the parcelled result. Back-press
 * never calls `finish()` directly: it asks the ViewModel, which settles the engine, and the
 * engine's `Terminal` is delivered by the same collector as every other outcome. Delivered
 * once per Activity instance; a recreated Activity that finds the session already terminal
 * delivers that result and finishes rather than spinning.
 *
 * **The single exception**, and the one place a result can leave without an intent id:
 * launch arguments that are missing or garbled (G23). There is no id to report because
 * there is no readable request; the honest answer is `RESULT_CANCELED` with no data, which
 * [UQPayPaymentContract.parseResult] maps to `CANCELLED` with a blank id — and never a
 * crash in the merchant's app.
 *
 * ### Back-press (§2c)
 *
 * Handled by an `OnBackPressedCallback` so the rule is one function: while a confirm is in
 * flight the press is blocked, visibly, for a bounded window; with an attempt in the air but
 * no confirm in flight it settles `PENDING`; with nothing in the air, `CANCELLED`. The
 * ViewModel owns that decision; see [PaymentViewModel.onBackRequested].
 */
internal class UQPayPaymentActivity : ComponentActivity() {

    private lateinit var paymentIntentId: String
    private var session: PaymentSession? = null

    /**
     * The merchant's optional card-form prefill, re-read from the launch Intent on every
     * creation — including the one after process death, which is why it lives in the parcel
     * and not in a field the OS would have to persist. Held only for as long as this
     * Activity is alive, handed to the form, and written nowhere.
     */
    private var billingDetails: PaymentSessionParams.BillingDetails? = null

    /**
     * The merchant's optional payment-method allow-list, re-read from the launch Intent on
     * every creation for the same reason as [billingDetails]. Null means no restriction. It
     * reaches the ViewModel through the factory rather than being applied here, because
     * "which methods the list shows" is a screen decision and every other one lives there.
     */
    private var allowedPaymentMethods: Set<PaymentMethodType>? = null

    /** Set by [finishWith]; the exactly-once guard for this Activity instance. */
    private var delivered = false

    /**
     * The customer-facing sentences for the failures this class reports directly.
     *
     * Built from the Activity rather than the application context so the sentence follows
     * any per-Activity locale or configuration the host has applied — the same reason
     * [rememberFormattedAmount] reads the configuration rather than `Locale.getDefault()`.
     */
    private val errorCopy: ErrorCopy by lazy { ErrorCopy.from(this) }

    /**
     * Created lazily on first access, which happens only after [session] is set — every
     * early-exit path in [onCreate] returns before touching it.
     */
    @get:VisibleForTesting
    internal val viewModel: PaymentViewModel by viewModels {
        PaymentViewModel.factory(
            paymentIntentId,
            checkNotNull(session) { "session must be attached first" },
            allowedPaymentMethods,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        paintWindowBackground()

        val params = readParams()
        if (params == null) {
            // Garbled or missing launch args (G23). The one exit with no intent id — see the
            // class KDoc. Never crash the merchant's app.
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        paymentIntentId = params.paymentIntentId
        billingDetails = params.billingDetails
        allowedPaymentMethods = params.allowedPaymentMethods
        if (paymentIntentId.isBlank()) {
            // The merchant handed us no intent. Reportable — the merchant's own bug, named
            // as such — but there is nothing to attach to. The id is blank because it was
            // given blank; this is not an SDK exit path losing it.
            finishWith(
                failure(
                    UQPayErrorCode.INVALID_CONFIGURATION,
                    "PaymentSessionParams.paymentIntentId is blank. Create the intent on your backend and pass its id.",
                ),
            )
            return
        }

        // `SingleWallet(CARD)` is a type error the compiler cannot catch: `SingleWallet` takes
        // any `PaymentMethodType`, and card is one. Left alone it reaches the ViewModel's
        // auto-confirm, which sends a *wallet* confirm whose type is "card" — a body the
        // gateway has no reading of — and claims the wallet latch for this intent under
        // "card", so a later, correct launch finds the wallet already "in flight". Refused
        // here, before a session exists, with the fix named for the developer.
        if (params.presentation.isSingleWalletCard()) {
            finishWith(
                failure(
                    UQPayErrorCode.INVALID_PAYMENT_METHOD,
                    "PaymentSessionParams.presentation is SingleWallet(CARD), but card is not " +
                        "a wallet. Use Presentation.CardOnly for card.",
                ),
            )
            return
        }

        // A presentation that names a method the allow-list excludes contradicts itself.
        // Caught here, before a session exists and before a single byte goes to the gateway:
        // the alternative is opening the card form for a payment the merchant's own rules
        // said may not use a card, which is worse than any error message.
        contradictedMethod(params)?.let { excluded ->
            finishWith(
                failure(
                    UQPayErrorCode.INVALID_PAYMENT_METHOD,
                    "PaymentSessionParams.presentation asks for '${excluded.raw}', which is " +
                        "not in allowedPaymentMethods " +
                        "(${params.allowedPaymentMethods?.joinToString { it.raw }}). " +
                        "Either widen the allow-list or present a method that is in it.",
                ),
            )
            return
        }

        val attached = try {
            PaymentSession.obtain(paymentIntentId)
        } catch (notInitialized: IllegalStateException) {
            // UQPay.initialize was not called before the launch — after process death, for a
            // host that initialises lazily. A programmer error, but one that must reach the
            // merchant as a result, not as a crash of their app from inside our Activity.
            finishWith(
                failure(
                    UQPayErrorCode.NOT_INITIALIZED,
                    "UQPay is not initialized. Call UQPay.initialize(context, configuration) from Application.onCreate().",
                ),
            )
            return
        }
        session = attached
        // Declares this instance a host of the payment, balanced in onDestroy. Two Activities
        // can legitimately hold one payment at a time — split-screen, two tasks, or simply the
        // overlap while one is replaced — and the session's scope must outlive the first of
        // them to be destroyed. See PaymentSession.attachHost.
        attached.attachHost()

        // First creation: loads the intent. Recreation: a no-op — the engine is already
        // wherever it got to. The screen never has to know which.
        attached.startIfNeeded(params.presentation.toEngine())

        onBackPressedDispatcher.addCallback(this) { onBackRequested() }

        setContent {
            UqpayTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                PaymentScreen(
                    state = uiState,
                    paymentIntentId = paymentIntentId,
                    onMethodSelected = viewModel::onMethodSelected,
                    // Slice 4. The form has already validated the payload; the engine still
                    // decides whether it becomes an attempt.
                    onCardSubmitted = { viewModel.onCardSubmitted(it) },
                    onThreeDsReturned = viewModel::onThreeDsReturned,
                    onReturnToList = viewModel::onReturnToList,
                    onCancel = { viewModel.onCancelConfirmed(); deliverIfTerminal() },
                    onClose = ::onBackRequested,
                    billingDetails = billingDetails,
                )
            }
        }

        // The one delivery path. A StateFlow replays its current value, so a recreated
        // Activity that finds the session already Terminal delivers it here immediately.
        lifecycleScope.launch {
            attached.state.collect { state ->
                if (state is EngineState.Terminal) finishWith(state.result)
            }
        }
    }

    /**
     * The foreground re-read (AC §8.1).
     *
     * The customer who left for their banking or wallet app comes back through here, and the
     * poll waiting on a 2-second tick should look at the intent *now* rather than up to a
     * tick later. The decision — including that the very first start is not a return — lives
     * in the ViewModel, like every other screen decision; this only reports the lifecycle.
     *
     * Guarded on [session] because the early exits in [onCreate] (`finish()` with no session)
     * still receive an `onStart`, and touching [viewModel] there would throw.
     */
    override fun onStart() {
        super.onStart()
        if (session != null) viewModel.onForegrounded()
    }

    /**
     * System back and the on-screen close, one rule. The ViewModel decides; if that decision
     * settled the engine, deliver now rather than a frame later. Never `finish()` directly.
     */
    private fun onBackRequested() {
        viewModel.onBackRequested()
        deliverIfTerminal()
    }

    private fun deliverIfTerminal() {
        val terminal = session?.state?.value as? EngineState.Terminal ?: return
        finishWith(terminal.result)
    }

    /**
     * The payment-over hook, and the only one. **One predicate decides two lifetimes.**
     *
     * `isFinishing && !isChangingConfigurations` means the result is delivered (or the launch
     * was unusable) and no Activity will come looking for this payment again. Both the
     * engine's session and the 3-D Secure step's cookies end here and nowhere else:
     *
     * - On a **configuration change** — a rotation — neither is touched. The session must
     *   stay where it is for the next instance to find, and the ACS session cookie must stay
     *   for the challenge the customer is half-way through. Clearing that cookie on rotation
     *   was B1: the challenge reloads unauthenticated and the payment runs out to `PENDING`.
     * - On a **system-initiated destroy** (process death, low memory) `isFinishing` is false,
     *   so again nothing is cleared. That is the relaunch-recovery case: the engine re-adopts
     *   the in-flight action and the challenge is re-shown, and it can only be completed if
     *   its cookie survived.
     *
     * [ThreeDsBrowsingState.clear] is scoped to the origins this payment's 3DS step visited —
     * never the process — and is a no-op for a payment that never reached 3DS, so it is safe
     * to call on every path.
     */
    override fun onDestroy() {
        super.onDestroy()
        val attached = session ?: return
        val forGood = isFinishing && !isChangingConfigurations
        if (forGood) ThreeDsBrowsingState.clear(paymentIntentId)
        // Detached on **every** destroy, not only the final one, so the host count stays
        // balanced against the attach in onCreate — a rotation that only ever attached would
        // count a new host per turn of the phone and the session could never be retired.
        // `forGood` is what decides whether the payment is over; the count decides only
        // whether this was the last Activity that could still be driving it.
        attached.detachHost(forGood = forGood)
    }

    /**
     * A second launch arrived while this instance is still up.
     *
     * Reachable only if the launch Intent carries `FLAG_ACTIVITY_SINGLE_TOP` or the Activity's
     * launch mode is changed to one that reuses instances — neither of which the SDK does
     * today (see the manifest). It exists because the failure it prevents is silent and
     * expensive, and because "the manifest currently says otherwise" is not a property this
     * class can enforce from here.
     *
     * When an existing instance is reused, `onCreate` does **not** run: [paymentIntentId],
     * the session, the ViewModel and the delivery collector all still belong to the *first*
     * payment. A second payment's parameters would simply be dropped, the customer would
     * complete the payment already on screen, and the merchant's second `launch` would be
     * answered by the first payment's outcome — the wrong order marked paid.
     *
     * There is no honest way to host two payments in one instance, so this refuses to try. A
     * re-launch of the *same* payment is the harmless case and is adopted, so that a later
     * re-read of the launch arguments sees the Intent the caller actually sent. A re-launch of
     * a *different* one is ignored outright: the payment on screen is left exactly as it
     * was, which is the only option that cannot lose money. (Ignored silently — this class
     * holds no logger; the session owns the SDK's logging, and reaching for it here would
     * put a second construction site behind `loggingEnabled`.)
     */
    // Widened from `protected` so the test can deliver a second launch the way the framework
    // would; `ActivityScenario` has no hook for it. Nothing is exposed — the class itself is
    // internal, so this is visible only inside the SDK.
    @VisibleForTesting
    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val incoming = readParams(intent)?.paymentIntentId
        if (session != null && incoming != null && incoming == paymentIntentId) {
            setIntent(intent)
        }
    }

    /**
     * Reads the launch arguments defensively (G22). After process death the framework
     * re-marshals the Intent without the app's classloader attached, so it must be set
     * explicitly or `getParcelable` silently returns null.
     */
    private fun readParams(source: Intent? = intent): PaymentSessionParams? = runCatching {
        source?.extras
            ?.apply { classLoader = PaymentSessionParams::class.java.classLoader }
            ?.let { extras ->
                @Suppress("DEPRECATION")
                extras.getParcelable<PaymentSessionParams>(UQPayPaymentContract.EXTRA_PARAMS)
            }
    }.getOrNull()

    /**
     * Finishes with a terminal outcome. **The only way this Activity reports a result** —
     * it never invokes a merchant callback directly, so delivery always goes through the OS
     * and survives process death. Idempotent per instance: a second call is ignored, so a
     * back-press racing a settle, or a re-collection after recreation, cannot deliver twice.
     *
     * `EXTRA_INTENT_ID` is stamped from the **launch params**, not from the result (F3):
     * whatever a result object says, the merchant learns which payment this was.
     */
    private fun finishWith(result: PaymentResult) {
        if (delivered) return
        delivered = true
        val data = Intent()
            .putExtra(UQPayPaymentContract.EXTRA_RESULT, result)
            .putExtra(UQPayPaymentContract.EXTRA_INTENT_ID, paymentIntentId)
        setResult(RESULT_OK, data)
        finish()
    }

    /**
     * A failure this Activity decided on its own.
     *
     * Two sentences, from two places. The customer's comes from [ErrorCopy] — that is, from
     * `strings.xml`, translatable and overridable — and the developer's is the [detail]
     * passed in here, which names the merchant's mistake precisely and is never shown to a
     * shopper. The old signature took one string and used it for both, which is how a
     * shopper could end up reading "PaymentSessionParams.paymentIntentId is blank".
     */
    private fun failure(code: UQPayErrorCode, detail: String): PaymentResult =
        PaymentResult(
            status = PaymentStatus.FAILED,
            paymentIntentId = paymentIntentId,
            error = UQPayError(
                code = code,
                message = errorCopy.forCode(code),
                developerMessage = detail,
            ),
        )

    /**
     * The method an explicit presentation names but the allow-list forbids, or null when
     * there is no contradiction.
     *
     * Only [PaymentSessionParams.Presentation.CardOnly] and
     * [PaymentSessionParams.Presentation.SingleWallet] can contradict anything: they name
     * exactly one method. [PaymentSessionParams.Presentation.MethodList] names none — it
     * shows whatever survives the filter, up to and including nothing — so it is never a
     * contradiction, only a shorter list.
     *
     * A null allow-list is no restriction and therefore excludes nothing.
     */
    private fun contradictedMethod(params: PaymentSessionParams): PaymentMethodType? {
        val allowed = params.allowedPaymentMethods ?: return null
        val named = when (val presentation = params.presentation) {
            PaymentSessionParams.Presentation.CardOnly -> PaymentMethodType.CARD
            is PaymentSessionParams.Presentation.SingleWallet -> presentation.method
            PaymentSessionParams.Presentation.MethodList -> null
        }
        return named?.takeIf { it !in allowed }
    }

    /**
     * Paints the window with the configured background before Compose draws anything.
     *
     * The manifest theme can only express one background per resource qualifier, so on its
     * own it draws the *device's* preference — which is wrong for a merchant who set
     * `UQPayAppearance.ColorMode.LIGHT` on a phone in dark mode, and wrong for any merchant
     * whose brand background is not Material's. The visible symptom is a dark or lilac flash
     * on the frame between the Activity's window appearing and the first Compose frame, at
     * the last step of a checkout.
     *
     * Reads the same [UQPayAppearance] the Compose theme does, through the same
     * dark-or-light decision, so the two cannot disagree. `uiMode` is deliberately not in the
     * Activity's `configChanges`, so a dark-mode change recreates the Activity and this runs
     * again with the new configuration.
     */
    private fun paintWindowBackground() {
        val appearance = UQPay.appearanceOrDefault()
        val dark = when (appearance.colorMode) {
            UQPayAppearance.ColorMode.LIGHT -> false
            UQPayAppearance.ColorMode.DARK -> true
            UQPayAppearance.ColorMode.SYSTEM ->
                resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
        }
        val colors = if (dark) appearance.darkColors else appearance.lightColors
        window.setBackgroundDrawable(ColorDrawable(colors.background))
    }

    /**
     * True for `SingleWallet(CARD)`: a wallet presentation naming the one method that is not
     * a wallet. See the refusal in [onCreate]; `PaymentViewModel.confirmWallet` holds the
     * same rule as a backstop.
     */
    private fun PaymentSessionParams.Presentation.isSingleWalletCard(): Boolean =
        this is PaymentSessionParams.Presentation.SingleWallet && method == PaymentMethodType.CARD

    /** The public presentation → the engine's. One place, so the two cannot drift. */
    private fun PaymentSessionParams.Presentation.toEngine(): Presentation = when (this) {
        PaymentSessionParams.Presentation.MethodList -> Presentation.MethodList
        PaymentSessionParams.Presentation.CardOnly -> Presentation.CardOnly
        is PaymentSessionParams.Presentation.SingleWallet -> Presentation.SingleWallet(method)
    }
}
