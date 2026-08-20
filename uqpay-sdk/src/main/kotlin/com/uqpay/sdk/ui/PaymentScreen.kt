package com.uqpay.sdk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.uqpay.sdk.R
import com.uqpay.sdk.engine.ConfirmPayload
import com.uqpay.sdk.payment.PaymentMethodType
import com.uqpay.sdk.payment.PaymentSessionParams
import com.uqpay.sdk.ui.card.CardForm
import com.uqpay.sdk.ui.threeds.ThreeDsScreen
import com.uqpay.sdk.ui.wallet.BankDetailsScreen
import com.uqpay.sdk.ui.wallet.WalletQrRoute

/**
 * The whole payment UI, one composable per [PaymentUiState] member. Plain and small on
 * purpose — Slices 4 (card form), 5 (QR) and 6 (polish) replace the placeholders; what must
 * already be right here is the *shape*: which states offer a way out and which do not.
 *
 * Accessibility baseline, kept from the first screen: Material 3 typography (scales with the
 * system font size), system dark mode via [UqpayTheme], and a content description on every
 * interactive element so a screen reader can name it.
 *
 * @param onClose the close affordance on the method list — routed to the same back-press
 *   rule as the system back button, so there is one rule.
 * @param onCancel the explicit way out of a waiting state (M-3/M-4).
 * @param billingDetails the merchant's optional card-form prefill, taken straight from the
 *   launch params and handed to the card form. Null on every other screen's behalf — no
 *   other state has anything to prefill.
 */
@Composable
internal fun PaymentScreen(
    state: PaymentUiState,
    paymentIntentId: String,
    onMethodSelected: (PaymentMethodType) -> Unit,
    onCardSubmitted: (ConfirmPayload.Card) -> Unit,
    onThreeDsReturned: () -> Unit,
    onReturnToList: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    billingDetails: PaymentSessionParams.BillingDetails? = null,
) {
    Scaffold { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (state) {
                PaymentUiState.Loading -> ProgressContent(
                    text = stringResource(R.string.uqpay_loading),
                    contentDescription = stringResource(R.string.uqpay_cd_loading),
                )
                is PaymentUiState.MethodList -> MethodListContent(state, onMethodSelected, onClose)
                // Slice 4: the real card form. The state member keeps its name so the
                // ViewModel projection and its tests are untouched by this slice.
                is PaymentUiState.CardPlaceholder -> CardForm(
                    paymentIntentId = paymentIntentId,
                    canReturnToList = state.canReturnToList,
                    submitEnabled = true,
                    onSubmit = onCardSubmitted,
                    onReturnToList = onReturnToList,
                    onCancel = onCancel,
                    billingDetails = billingDetails,
                )
                is PaymentUiState.Confirming -> ConfirmingContent(state)
                is PaymentUiState.AwaitingAction -> AwaitingActionContent(state, onCancel)
                // Slice 4: 3-D Secure. The screen renders and reports; the engine, which is
                // already polling, decides the outcome from the API and nothing else.
                is PaymentUiState.ThreeDs -> ThreeDsScreen(
                    content = state.content,
                    // Scopes the 3DS browsing state this step creates to this payment, so
                    // ending one payment cannot clear another's authentication.
                    sessionKey = paymentIntentId,
                    returnUrlPrefixes = state.returnUrlPrefixes,
                    onReturnedFromChallenge = onThreeDsReturned,
                    onCancel = onCancel,
                )
                // Slice 5: the wallet QR and the bank-transfer instructions.
                is PaymentUiState.WalletQr -> WalletQrRoute(
                    walletName = state.methodType?.let { methodDisplayName(it) }
                        ?: stringResource(R.string.uqpay_method_wallet_generic),
                    amount = state.amount,
                    currency = state.currency,
                    qrUrl = state.qrUrl,
                    rawPayload = state.rawPayload,
                    expiresAtEpochMillis = state.expiresAtEpochMillis,
                    onCancel = onCancel,
                )
                is PaymentUiState.BankTransfer -> BankDetailsScreen(
                    details = state.details,
                    amount = state.amount,
                    currency = state.currency,
                    onCancel = onCancel,
                )
                PaymentUiState.Polling -> WaitingContent(
                    title = stringResource(R.string.uqpay_polling),
                    body = stringResource(R.string.uqpay_action_still_waiting),
                    contentDescription = stringResource(R.string.uqpay_cd_polling),
                    onCancel = onCancel,
                )
                // Terminal: the Activity is finishing. Nothing to tap, so nothing to draw
                // but the last progress frame — a blank flash is worse than a spinner.
                PaymentUiState.Finishing -> ProgressContent(text = "", contentDescription = null)
            }
        }
    }
}

// ---- Loading / finishing ------------------------------------------------------------------

@Composable
private fun ProgressContent(text: String, contentDescription: String?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = if (contentDescription != null) {
                Modifier.semantics { this.contentDescription = contentDescription }
            } else {
                Modifier
            },
        )
        if (text.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        }
    }
}

// ---- Method list ---------------------------------------------------------------------------

@Composable
private fun MethodListContent(
    state: PaymentUiState.MethodList,
    onMethodSelected: (PaymentMethodType) -> Unit,
    onClose: () -> Unit,
) {
    val closeDescription = stringResource(R.string.uqpay_cd_close)
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.uqpay_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            TextButton(
                onClick = onClose,
                modifier = Modifier.semantics { contentDescription = closeDescription },
            ) {
                Text(stringResource(R.string.uqpay_cancel))
            }
        }
        val amount = state.amount
        val currency = state.currency
        if (amount != null && currency != null) {
            Text(
                text = stringResource(R.string.uqpay_amount_format, currency, amount),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        state.merchantOrderId?.let { order ->
            Text(
                text = stringResource(R.string.uqpay_order_format, order),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.uqpay_choose_method),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        if (state.methods.isEmpty()) {
            Text(
                text = stringResource(R.string.uqpay_no_methods),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            // API order, card first — as the engine delivered it. Not re-sorted here (G21).
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(state.methods, key = { it.raw }) { method ->
                    MethodRow(method, onClick = { onMethodSelected(method) })
                }
            }
        }
    }
}

@Composable
private fun MethodRow(method: PaymentMethodType, onClick: () -> Unit) {
    val name = methodDisplayName(method)
    val description = stringResource(R.string.uqpay_cd_method_format, name)
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { contentDescription = description },
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * The customer-facing name of a method this SDK can render. Every member of
 * `PaymentViewModel.RENDERABLE_METHODS` has an entry; the fallback is the wire value, and
 * exists only so a missing entry is a cosmetic bug and never a crash.
 */
@Composable
private fun methodDisplayName(method: PaymentMethodType): String = when (method) {
    PaymentMethodType.CARD -> stringResource(R.string.uqpay_method_card)
    PaymentMethodType.WECHAT_PAY -> stringResource(R.string.uqpay_method_wechatpay)
    PaymentMethodType.ALIPAY_CN -> stringResource(R.string.uqpay_method_alipaycn)
    PaymentMethodType.ALIPAY_HK -> stringResource(R.string.uqpay_method_alipayhk)
    PaymentMethodType.GRABPAY -> stringResource(R.string.uqpay_method_grabpay)
    PaymentMethodType.PAYNOW -> stringResource(R.string.uqpay_method_paynow)
    PaymentMethodType.UNIONPAY -> stringResource(R.string.uqpay_method_unionpay)
    PaymentMethodType.TRUEMONEY -> stringResource(R.string.uqpay_method_truemoney)
    PaymentMethodType.TNG -> stringResource(R.string.uqpay_method_tng)
    PaymentMethodType.GCASH -> stringResource(R.string.uqpay_method_gcash)
    PaymentMethodType.DANA -> stringResource(R.string.uqpay_method_dana)
    PaymentMethodType.KAKAOPAY -> stringResource(R.string.uqpay_method_kakaopay)
    PaymentMethodType.TOSSPAY -> stringResource(R.string.uqpay_method_tosspay)
    PaymentMethodType.NAVERPAY -> stringResource(R.string.uqpay_method_naverpay)
    else -> method.raw
}

// ---- Confirming ----------------------------------------------------------------------------

/**
 * A confirm is in flight. **No button, no back affordance, on purpose** (§2c): the customer
 * cannot leave here, and the screen must show why rather than swallow the press. Before the
 * customer has tried to leave the copy is ordinary progress; after, it says plainly that
 * the payment is being confirmed and asks them to stay.
 */
@Composable
private fun ConfirmingContent(state: PaymentUiState.Confirming) {
    val description = stringResource(R.string.uqpay_cd_confirming)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = description })
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(
                if (state.leaveBlocked) R.string.uqpay_confirming_blocked else R.string.uqpay_confirming,
            ),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        if (state.leaveBlocked) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.uqpay_confirming_blocked_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---- Awaiting a customer action / polling ------------------------------------------------

@Composable
private fun AwaitingActionContent(state: PaymentUiState.AwaitingAction, onCancel: () -> Unit) {
    val body = when (state.kind) {
        ActionKind.QR -> R.string.uqpay_action_qr
        ActionKind.REDIRECT, ActionKind.IFRAME -> R.string.uqpay_action_redirect
        ActionKind.BANK_DETAILS -> R.string.uqpay_action_bank_details
        ActionKind.UNKNOWN -> R.string.uqpay_action_unknown
    }
    WaitingContent(
        title = stringResource(R.string.uqpay_action_pending_title),
        body = stringResource(body),
        contentDescription = stringResource(R.string.uqpay_cd_polling),
        onCancel = onCancel,
    )
}

/**
 * The shape of every "attempt in the air, nothing renderable yet" state: what is happening,
 * a spinner, and a **Cancel** — the visible way out audit M-3/M-4 requires. Cancelling here
 * settles `PENDING`, never `CANCELLED`, because the attempt was sent.
 */
@Composable
private fun WaitingContent(title: String, body: String, contentDescription: String, onCancel: () -> Unit) {
    val cancelDescription = stringResource(R.string.uqpay_cd_cancel)
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.semantics { this.contentDescription = contentDescription })
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        }
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
                .semantics { this.contentDescription = cancelDescription },
        ) {
            Text(stringResource(R.string.uqpay_cancel))
        }
    }
}
