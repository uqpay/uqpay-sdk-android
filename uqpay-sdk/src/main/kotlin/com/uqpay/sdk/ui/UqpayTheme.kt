package com.uqpay.sdk.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * The payment UI's Material 3 theme. Deliberately minimal: the stock light and dark colour
 * schemes, chosen by the system setting, and the default typography — which is specified
 * in `sp`, so every text on the payment screen scales with the customer's font-size
 * preference without any work here.
 *
 * No dynamic colour (it would make the payment sheet look different on every device and
 * needs API 31), no brand palette (that is a Slice 6 decision, and a merchant-facing one).
 */
@Composable
internal fun UqpayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
