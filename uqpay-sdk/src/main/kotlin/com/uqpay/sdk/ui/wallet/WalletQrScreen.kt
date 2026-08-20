package com.uqpay.sdk.ui.wallet

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.uqpay.sdk.R
import com.uqpay.sdk.ui.rememberFormattedAmount
import kotlinx.coroutines.delay
import java.util.Locale

/** Where the QR image download has got to. */
internal sealed class QrImagePhase {

    /** Downloading. */
    data object Loading : QrImagePhase()

    /** Downloaded and decoded. */
    data class Ready(val bitmap: Bitmap) : QrImagePhase()

    /** The download or the decode failed. The customer is offered a Retry. */
    data object Failed : QrImagePhase()
}

/**
 * The merchant-presented QR wallet screen: the code to scan, what it costs, how long it is
 * good for, and the one way out.
 *
 * ### Why Cancel settles PENDING and never CANCELLED
 *
 * By the time this screen exists a confirm has already succeeded and the gateway has issued
 * a live QR. The customer may have scanned it a second ago in another app; we would not know
 * yet, because the only way this SDK learns is the poll. Reporting `CANCELLED` to the
 * merchant would say "no payment was made" about a payment that may be settling right now —
 * the exact false-cancellation bug the iOS SDK shipped and had to fix as a breaking change.
 * The Cancel here therefore routes to the engine, which settles `PENDING` whenever an
 * attempt is in the air. The button copy is deliberately the same "Cancel" the rest of the
 * sheet uses: the customer is leaving, and it is not their job to know the difference.
 *
 * ### Accessibility
 *
 * The QR image carries a content description **naming the wallet**. A screen-reader user
 * cannot see that the square is a GrabPay code; without the name they are told "image" and
 * asked to scan something with an app nobody identified. Everything else is Material 3
 * typography (so it scales with the system font size), the shared [com.uqpay.sdk.ui.UqpayTheme]
 * colour scheme (so dark mode follows the system), and a content description on every
 * interactive element.
 *
 * @param walletName the customer-facing wallet name, already resolved ("GrabPay").
 * @param phase the image download's state. [QrImagePhase.Failed] is always retryable — a
 *   failed download has no effect on the payment attempt.
 * @param rawPayload the EMVCo string the gateway also sends as `qr_code`. Rendered as text
 *   **only** when no image could be shown, so a customer whose wallet app accepts a pasted
 *   payload still has a route through, rather than a blank square.
 * @param remainingMillis time left before `expires_at`, or null when the gateway sent no
 *   expiry. Zero or less renders the expired state: the code on screen will be refused, and
 *   saying so is better than letting a customer scan it and be told by their bank.
 */
@Composable
internal fun WalletQrScreen(
    walletName: String,
    amount: String?,
    currency: String?,
    phase: QrImagePhase,
    rawPayload: String?,
    remainingMillis: Long?,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val expired = remainingMillis != null && remainingMillis <= 0L
    val cancelDescription = stringResource(R.string.uqpay_cd_cancel)
    val cancelLabel = stringResource(R.string.uqpay_cancel)
    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.uqpay_wallet_qr_title, walletName),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            rememberFormattedAmount(amount, currency)?.let { formattedAmount ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = formattedAmount,
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            Spacer(Modifier.height(24.dp))

            if (expired) {
                ExpiredContent(walletName)
            } else {
                QrContent(walletName, phase, rawPayload, onRetry)
                remainingMillis?.let { remaining ->
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.uqpay_qr_expires_in, formatRemaining(remaining)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.uqpay_action_still_waiting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
                .semantics { contentDescription = cancelDescription },
        ) {
            // The label is read into a local *outside* this lambda so the lambda captures
            // it. A non-capturing composable lambda is hoisted by the Compose compiler into
            // a public `ComposableSingletons$…` holder, which then has to be excluded from
            // `apiCheck` by hand in the root build file — a shared file this slice does not
            // own. Capturing keeps the API dump unchanged.
            Text(cancelLabel)
        }
    }
}

@Composable
private fun QrContent(
    walletName: String,
    phase: QrImagePhase,
    rawPayload: String?,
    onRetry: () -> Unit,
) {
    when (phase) {
        QrImagePhase.Loading -> {
            val loadingDescription = stringResource(R.string.uqpay_cd_qr_loading)
            CircularProgressIndicator(
                modifier = Modifier.semantics { contentDescription = loadingDescription },
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.uqpay_qr_loading), style = MaterialTheme.typography.bodyLarge)
        }

        is QrImagePhase.Ready -> {
            // Names the wallet: a screen reader must be able to say *what* is being scanned.
            val imageDescription = stringResource(R.string.uqpay_cd_qr_image, walletName)
            Image(
                bitmap = phase.bitmap.asImageBitmap(),
                contentDescription = imageDescription,
                modifier = Modifier.size(240.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.uqpay_wallet_qr_instruction, walletName),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }

        QrImagePhase.Failed -> {
            val retryDescription = stringResource(R.string.uqpay_cd_qr_retry)
            val retryLabel = stringResource(R.string.uqpay_qr_retry)
            Text(
                text = stringResource(R.string.uqpay_qr_failed),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.semantics { contentDescription = retryDescription },
            ) {
                Text(retryLabel)
            }
            // Last resort: the payload itself. Some wallet apps accept a pasted EMVCo
            // string, and a customer with the code is better off than one with a blank box.
            if (!rawPayload.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.uqpay_qr_payload_fallback),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = rawPayload,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ExpiredContent(walletName: String) {
    Text(
        text = stringResource(R.string.uqpay_qr_expired),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.uqpay_qr_expired_hint, walletName),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * The screen plus the two things it needs from the outside: the image download, and a clock.
 *
 * Kept separate from [WalletQrScreen] so the screen itself stays a pure function of its
 * arguments — every state it can be in is reachable in a test without a network or a
 * ticking clock.
 *
 * @param onCancel routed to the engine, which settles `PENDING`. See [WalletQrScreen].
 */
// ProduceStateDoesNotAssignValue is a false positive here, suppressed after two attempts
// to satisfy it honestly. Both producers below assign `value` unconditionally — the first
// as a single trailing `value = next` on every path, the second as its opening statement.
// The check does not see through `by produceState(...)` delegation. It guards a real
// hazard (a producer that never assigns leaves state pinned to its initial value), so it
// is suppressed at this one function rather than disabled project-wide, and the two
// producers are covered by tests: QR load success/failure/retry and the expiry countdown
// all assert the emitted states.
@Suppress("ProduceStateDoesNotAssignValue")
@Composable
internal fun WalletQrRoute(
    walletName: String,
    amount: String?,
    currency: String?,
    qrUrl: String?,
    rawPayload: String?,
    expiresAtEpochMillis: Long?,
    onCancel: () -> Unit,
    loader: QrImageLoader = remember { QrImageLoader() },
    now: () -> Long = System::currentTimeMillis,
) {
    // Bumped by Retry. Keying the load on it is what makes a retry actually re-fetch
    // rather than re-read a completed effect.
    var attempt by remember { mutableIntStateOf(0) }

    // `value` is assigned on every path, unconditionally and last. Written as plain
    // statements rather than an `if`/`when` expression because lint's
    // ProduceStateDoesNotAssignValue check is syntactic: it looks for a direct `value =`
    // and does not see one hidden inside an expression body. The rule guards a real
    // hazard — a producer that never assigns leaves the state stuck on its initial value
    // forever — so it is worth writing in the shape the check can read.
    // Computed into a local and assigned to `value` exactly once, at the end, on every
    // path. Lint's ProduceStateDoesNotAssignValue check is syntactic and does not follow
    // assignments nested inside expressions or `when` branches; the rule guards a real
    // hazard (a producer that never assigns leaves the state stuck on its initial value
    // forever), so the code is written in the shape the check can actually read.
    val phase by produceState<QrImagePhase>(QrImagePhase.Loading, qrUrl, attempt) {
        value = QrImagePhase.Loading
        val url = qrUrl
        val next: QrImagePhase = if (url.isNullOrBlank()) {
            QrImagePhase.Failed
        } else {
            val result = loader.load(url)
            if (result is QrImageResult.Loaded) QrImagePhase.Ready(result.bitmap) else QrImagePhase.Failed
        }
        value = next
    }

    // Same shape rule as above: the first statement is an unconditional `value =`, so the
    // producer visibly assigns before any early return. With no expiry the initial value
    // is null and re-assigning null is a no-op — cheaper than teaching the check about
    // the elvis-return that used to come first.
    val remaining by produceState(expiresAtEpochMillis?.minus(now()), expiresAtEpochMillis) {
        value = expiresAtEpochMillis?.minus(now())
        val deadline = expiresAtEpochMillis ?: return@produceState
        while (true) {
            val left = deadline - now()
            value = left
            if (left <= 0L) return@produceState
            delay(COUNTDOWN_TICK_MILLIS)
        }
    }

    WalletQrScreen(
        walletName = walletName,
        amount = amount,
        currency = currency,
        phase = phase,
        rawPayload = rawPayload,
        remainingMillis = remaining,
        onRetry = { attempt++ },
        onCancel = onCancel,
    )
}

private const val COUNTDOWN_TICK_MILLIS = 1_000L

/**
 * `mm:ss` for a duration in milliseconds, rounded **up** to the next whole second.
 *
 * Rounding up rather than down so a countdown never shows `00:00` while the code is still
 * valid; it reaches zero exactly when the QR does. Minutes are not capped at 60 — a
 * 90-minute QR would read `90:00`, which is honest, where `30:00` would be a lie.
 */
internal fun formatRemaining(millis: Long): String {
    val totalSeconds = if (millis <= 0L) 0L else (millis + 999L) / 1000L
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    // Locale.ROOT, not the default locale: `String.format` renders digits in the locale's
    // own numbering system, so a device set to a locale with Arabic-Indic digits would draw
    // a countdown in numerals the rest of the sheet does not use. Same class of latent bug
    // as the locale-sensitive hex formatting `ConfirmPayloadIdentity` avoids.
    return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}

/**
 * Parses the gateway's `expires_at` into epoch milliseconds.
 *
 * ### Why this is hand-rolled
 *
 * `java.time` is API 26 and this SDK is `minSdk 24` with no core-library desugaring, so
 * `OffsetDateTime.parse` is not available. `SimpleDateFormat` is the usual fallback and is
 * the wrong tool here: it is locale- and timezone-sensitive (the same class of latent bug as
 * `String.format("%02x")` in `ConfirmPayloadIdentity`), it silently accepts garbage under
 * lenient parsing, and its pattern letters for offsets differ across API levels. Twenty lines
 * of arithmetic with a committed test vector is smaller, exact, and cannot drift with a
 * device's locale.
 *
 * Accepts what the gateway actually sends — verified live on 2026-08-18:
 * `2026-08-18T17:14:19.855+08:00` — plus the shapes it is entitled to send instead: `Z`,
 * `+0800`, no fractional seconds, a space instead of `T`, and a missing offset (read as UTC,
 * which is the only defensible default and is at worst an hours-scale error on a value used
 * solely to draw a countdown).
 *
 * @return epoch millis, or **null** for anything it cannot parse. Null means "no expiry
 *   shown", never "expired" — inventing an expiry from an unreadable string would blank out
 *   a QR that is perfectly good.
 */
internal fun parseExpiresAt(text: String?): Long? {
    val input = text?.trim().orEmpty()
    if (input.isEmpty()) return null
    val g = (ISO_8601.matchEntire(input) ?: return null).groupValues

    val year = g[1].toInt()
    val month = g[2].toInt()
    val day = g[3].toInt()
    val hour = g[4].toInt()
    val minute = g[5].toInt()
    val second = g[6].toInt()
    if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) {
        return null
    }

    // However many fractional digits arrived, keep the first three as milliseconds.
    val millisOfSecond = g[7].take(3).padEnd(3, '0').toLong()
    val offsetSeconds = offsetSecondsOf(g[8]) ?: return null

    val days = daysFromCivil(year, month, day)
    val secondsOfDay = hour * 3600L + minute * 60L + second
    return (days * 86_400L + secondsOfDay - offsetSeconds) * 1000L + millisOfSecond
}

/**
 * Days since 1970-01-01 for a proleptic Gregorian date. Howard Hinnant's `days_from_civil`,
 * the algorithm the C++ and Java date libraries are both built on — exact for every date in
 * range, with no calendar object and no timezone database.
 */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400                                            // [0, 399]
    val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy                    // [0, 146096]
    return era * 146_097 + doe - 719_468
}

private val ISO_8601 = Regex(
    "^(\\d{4})-(\\d{2})-(\\d{2})[Tt ](\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?" +
        "([Zz]|[+-]\\d{2}:?\\d{2})?$",
)

/**
 * The offset in seconds east of UTC. An empty offset or `Z` is UTC; anything unparseable is
 * null, which [parseExpiresAt] turns into "no expiry shown".
 */
private fun offsetSecondsOf(raw: String): Long? {
    if (raw.isEmpty() || raw.equals("Z", ignoreCase = true)) return 0L
    val sign = if (raw[0] == '-') -1 else 1
    val digits = raw.drop(1).replace(":", "")
    if (digits.length != 4) return null
    return sign * (digits.take(2).toLong() * 3600L + digits.drop(2).toLong() * 60L)
}
