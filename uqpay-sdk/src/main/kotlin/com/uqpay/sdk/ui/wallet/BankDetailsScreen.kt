package com.uqpay.sdk.ui.wallet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.uqpay.sdk.R
import com.uqpay.sdk.ui.rememberFormattedAmount

/**
 * Bank-transfer instructions, as the gateway sent them.
 *
 * Every field is nullable because the wire shape is **not yet verified live** — see
 * [BankDetailsScreen]. A field that did not arrive is not rendered at all rather than
 * rendered blank, so a customer is never shown "Account number:" followed by nothing.
 *
 * @property reference what the customer must quote so the merchant can match the transfer.
 *   iOS names this field `routing_number`; the label here is deliberately the customer-facing
 *   idea rather than a bank-clearing term, and the label string is the one thing that will
 *   need revisiting once the live shape is known.
 */
internal data class BankTransferDetails(
    val bankName: String? = null,
    val accountNumber: String? = null,
    val reference: String? = null,
) {
    /** True when nothing at all could be shown. */
    val isEmpty: Boolean
        get() = bankName.isNullOrBlank() && accountNumber.isNullOrBlank() && reference.isNullOrBlank()
}

/**
 * Display-only bank-transfer instructions with a copy affordance per field.
 *
 * ### Display-only, and why there is no "I've paid" button
 *
 * A bank transfer settles out of band and on the bank's schedule, not the app's. The customer
 * cannot tell this SDK anything it would be right to believe: "I've paid" is a claim, and the
 * intent's status is the fact. So this screen shows the instructions and the engine keeps
 * watching the intent, exactly as it does behind a QR. The only control is the same Cancel
 * every waiting screen has, which settles `PENDING` because an attempt is in the air.
 *
 * ### Copy to clipboard
 *
 * Typing a 12-digit account number into a banking app from another app's screen is where
 * transfers go to the wrong account. Each field therefore gets its own copy button, labelled
 * with the field it copies so a screen reader announces "Copy account number" rather than
 * three identical "Copy" buttons.
 *
 * The clipboard is a deliberate, narrow exception to this SDK's no-persistence rules: these
 * values are the *merchant's* receiving account, published to the customer by the gateway
 * for the express purpose of being retyped into a banking app. **No card value, token, or
 * customer identifier may ever be put on the clipboard** — it is world-readable to other
 * apps on older Android versions and survives the process.
 *
 * ### ⚠️ The payload is not yet on the wire
 *
 * `NextActionDto` has no `display_bank_details` field, so [BankTransferDetails] currently
 * arrives empty and this screen renders its "we cannot show the details" state. That is not
 * an oversight: the shape could not be verified. Twenty-four payment method types are offered
 * by the live sandbox and **none of them produced a `display_bank_details` next action** on
 * 2026-08-18 — every non-GrabPay method this merchant can reach answers
 * `invalid_payment_method`. Adding a guessed DTO to a shared file would put an unverified
 * wire contract into a payment SDK, and a wrong field name decodes to null in exactly the way
 * `payment_intent_id` did before it was checked against the live gateway: silently, forever,
 * with every fixture written to agree with the mistake.
 *
 * iOS's `DisplayBankDetails` decodes `bank_name`, `account_number`, `routing_number` as
 * **non-optional** strings, which is real evidence but from a path this sandbox cannot
 * exercise. When a live capture confirms it, the change is two additive lines in
 * `network/Dtos.kt`, one in `NextAction.BankDetails`, and one mapping here.
 */
@Composable
internal fun BankDetailsScreen(
    details: BankTransferDetails,
    amount: String?,
    currency: String?,
    onCancel: () -> Unit,
) {
    val cancelDescription = stringResource(R.string.uqpay_cd_cancel)
    val cancelLabel = stringResource(R.string.uqpay_cancel)
    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 96.dp),
        ) {
            Text(
                text = stringResource(R.string.uqpay_bank_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            rememberFormattedAmount(amount, currency)?.let { formattedAmount ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = formattedAmount,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(16.dp))

            if (details.isEmpty) {
                Text(
                    text = stringResource(R.string.uqpay_bank_unavailable),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    text = stringResource(R.string.uqpay_bank_instruction),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(16.dp))
                CopyableField(R.string.uqpay_bank_name, details.bankName)
                CopyableField(R.string.uqpay_bank_account, details.accountNumber)
                CopyableField(R.string.uqpay_bank_reference, details.reference)
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.uqpay_action_still_waiting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
                .semantics { contentDescription = cancelDescription },
        ) {
            Text(cancelLabel)
        }
    }
}

/**
 * One label / value row with its own copy button, or nothing at all when the value is absent.
 *
 * The copy button's content description names the field, so three copy buttons on one screen
 * are three distinguishable targets for a screen reader rather than three identical ones.
 */
@Composable
private fun CopyableField(labelRes: Int, value: String?) {
    if (value.isNullOrBlank()) return
    val context = LocalContext.current
    val label = stringResource(labelRes)
    val copyDescription = stringResource(R.string.uqpay_cd_copy_format, label)
    val copyLabel = stringResource(R.string.uqpay_copy)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = value, style = MaterialTheme.typography.bodyLarge)
        }
        TextButton(
            onClick = { copyToClipboard(context, label, value) },
            modifier = Modifier.semantics { contentDescription = copyDescription },
        ) {
            Text(copyLabel)
        }
    }
}

/**
 * Puts [value] on the clipboard under [label].
 *
 * Extracted so the copy itself is testable without a compose rule, and so there is exactly
 * one place in this SDK that touches the clipboard — see [BankDetailsScreen] for what may
 * and may not be put on it.
 */
internal fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}
