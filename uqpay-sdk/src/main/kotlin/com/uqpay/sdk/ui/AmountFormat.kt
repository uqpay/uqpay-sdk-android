package com.uqpay.sdk.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.uqpay.sdk.R
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Renders the intent's amount the way the customer's own device writes money.
 *
 * The gateway sends the amount as a decimal string in **major units** (`"8.98"`) and the
 * currency as an ISO 4217 code (`"SGD"`). Concatenating those two — which is what this
 * screen used to do — is wrong in three ordinary cases, none of them exotic for a gateway
 * whose method list is SEA and East Asian:
 *
 * - **JPY, KRW and VND have no minor unit.** `"1000.00 JPY"` reads as a hundredth of the
 *   real price to anyone who pauses on it, and as a formatting bug to everyone else.
 * - **Most of Europe groups and separates differently.** A German customer expects
 *   `8.998,50 €`, not `EUR 8998.50`.
 * - **The symbol's side is a property of the locale, not of the currency.** `$12.00` in
 *   `en-US`, `12,00 $` in `fr-CA`.
 *
 * [NumberFormat.getCurrencyInstance] knows all three, per locale, and is on every device.
 *
 * ### It never throws, and it never invents a number
 *
 * Every input here comes off the wire, so every input can be absent or malformed. A
 * currency code the platform cannot resolve, an amount that is not a decimal, a currency
 * with no defined minor unit (`XXX`) — each falls back to printing the code beside the raw
 * amount, which is exactly what the screen did before and is never wrong, only plain. The
 * one thing this must not do is drop or round a digit silently, so the fallback prints the
 * gateway's own string untouched.
 *
 * @param amount the wire amount, in major units. Blank or null means there is nothing to
 *   show and the caller draws no amount line at all.
 * @param currencyCode ISO 4217, case-insensitive.
 * @param locale the customer's locale, taken from the Activity configuration rather than
 *   [Locale.getDefault] so a host app that overrides its own locale is honoured.
 * @param fallbackFormat the "%1$s %2$s" pattern from resources — passed in rather than read
 *   here so this function stays a pure, testable function of its inputs.
 */
internal fun formatAmount(
    amount: String?,
    currencyCode: String?,
    locale: Locale,
    fallbackFormat: String,
): String? {
    val rawAmount = amount?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val code = currencyCode?.trim()?.takeIf { it.isNotEmpty() }
        // No currency to name. Printing the bare number is better than printing nothing,
        // and better than guessing a currency the intent did not state.
        ?: return rawAmount

    // `fallbackFormat` comes from resources, and resources are documented as overridable by
    // the merchant's own app (see "Localisation" in the integration guide). Two ways an
    // override goes wrong, and neither may reach the customer:
    //
    // - wrong specifiers (`%3$s`, `%d`) make String.format *throw*, and throwing here would
    //   take the payment sheet down mid-checkout over a translation typo;
    // - an empty or specifier-free pattern does not throw, it silently produces a line with
    //   **no amount in it** — which is worse, because the customer is then asked to pay a
    //   price they cannot see.
    //
    // Both degrade to a plain join, which is never elegant and never wrong.
    val fallback = runCatching { String.format(locale, fallbackFormat, code, rawAmount) }
        .getOrNull()
        ?.takeIf { it.contains(rawAmount) }
        ?: "$code $rawAmount"

    val value = runCatching { BigDecimal(rawAmount) }.getOrNull() ?: return fallback
    val currency = runCatching { Currency.getInstance(code.uppercase(Locale.ROOT)) }.getOrNull()
        ?: return fallback
    // -1 for currencies with no minor unit defined (`XXX`, and anything the platform's own
    // table does not know). Formatting one would be a guess.
    val digits = currency.defaultFractionDigits.takeIf { it >= 0 } ?: return fallback

    return runCatching {
        NumberFormat.getCurrencyInstance(locale).apply {
            this.currency = currency
            // Set explicitly rather than trusting setCurrency to carry them: the contract
            // for that differs between NumberFormat and DecimalFormat, and the whole point
            // of this function is that JPY shows no decimals and SGD shows two.
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }.format(value)
    }.getOrDefault(fallback)
}

/**
 * The composable form: the amount line for the current configuration's locale, or null when
 * the intent carried no amount. Remembered per input so a recomposition does not rebuild a
 * [NumberFormat].
 */
@Composable
internal fun rememberFormattedAmount(amount: String?, currencyCode: String?): String? {
    val fallbackFormat = stringResource(R.string.uqpay_amount_fallback_format)
    // Configuration rather than Locale.getDefault(): a host app that applies its own locale
    // per-Activity (an in-app language picker, androidx AppCompatDelegate.setApplicationLocales)
    // sets it there, and the payment sheet must follow the app the customer is inside.
    val locales = LocalConfiguration.current.locales
    val locale = if (locales.isEmpty) Locale.getDefault() else locales[0]
    return remember(amount, currencyCode, locale, fallbackFormat) {
        formatAmount(amount, currencyCode, locale, fallbackFormat)
    }
}
