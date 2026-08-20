package com.uqpay.sdk.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.uqpay.sdk.engine.EngineState
import com.uqpay.sdk.engine.PaymentSession
import com.uqpay.sdk.engine.Presentation
import com.uqpay.sdk.error.UQPayError
import com.uqpay.sdk.error.UQPayErrorCode
import com.uqpay.sdk.launcher.UQPayPaymentContract
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
 * ### Rotation re-attaches; it never re-submits
 *
 * `onCreate` calls [PaymentSession.obtain], which returns the *running* session for this
 * intent if there is one. A recreated Activity therefore finds the same engine mid-flight;
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
internal class UQPayPaymentActivity : AppCompatActivity() {

    private lateinit var paymentIntentId: String
    private var session: PaymentSession? = null

    /**
     * The merchant's optional card-form prefill, re-read from the launch Intent on every
     * creation — including the one after process death, which is why it lives in the parcel
     * and not in a field the OS would have to persist. Held only for as long as this
     * Activity is alive, handed to the form, and written nowhere.
     */
    private var billingDetails: PaymentSessionParams.BillingDetails? = null

    /** Set by [finishWith]; the exactly-once guard for this Activity instance. */
    private var delivered = false

    /**
     * Created lazily on first access, which happens only after [session] is set — every
     * early-exit path in [onCreate] returns before touching it.
     */
    @get:VisibleForTesting
    internal val viewModel: PaymentViewModel by viewModels {
        PaymentViewModel.factory(paymentIntentId, checkNotNull(session) { "session must be attached first" })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        if (isFinishing && !isChangingConfigurations && session != null) {
            ThreeDsBrowsingState.clear(paymentIntentId)
            PaymentSession.release(paymentIntentId)
        }
    }

    /**
     * Reads the launch arguments defensively (G22). After process death the framework
     * re-marshals the Intent without the app's classloader attached, so it must be set
     * explicitly or `getParcelable` silently returns null.
     */
    private fun readParams(): PaymentSessionParams? = runCatching {
        intent?.extras
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

    private fun failure(code: UQPayErrorCode, message: String): PaymentResult =
        PaymentResult(
            status = PaymentStatus.FAILED,
            paymentIntentId = paymentIntentId,
            error = UQPayError(code = code, message = message),
        )

    /** The public presentation → the engine's. One place, so the two cannot drift. */
    private fun PaymentSessionParams.Presentation.toEngine(): Presentation = when (this) {
        PaymentSessionParams.Presentation.MethodList -> Presentation.MethodList
        PaymentSessionParams.Presentation.CardOnly -> Presentation.CardOnly
        is PaymentSessionParams.Presentation.SingleWallet -> Presentation.SingleWallet(method)
    }
}
