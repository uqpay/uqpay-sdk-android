package com.uqpay.sdk.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.uqpay.sdk.UQPay
import com.uqpay.sdk.appearance.UQPayAppearance

/**
 * The payment UI's Material 3 theme, built from the merchant's [UQPayAppearance].
 *
 * Typography is left as Material 3's default, which is specified in `sp` — every text on
 * the payment screen therefore scales with the customer's font-size preference without any
 * work here, and a merchant cannot switch that off.
 *
 * No dynamic colour: it would make the payment sheet look different on every device, needs
 * API 31, and would silently override a brand palette the merchant did configure.
 *
 * @param appearance defaults to the configured one. A parameter so the SDK's own screenshot
 *   and Compose tests can drive a palette without initialising the whole SDK.
 */
@Composable
internal fun UqpayTheme(
    appearance: UQPayAppearance = UQPay.appearanceOrDefault(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = appearance.colorScheme(dark = appearance.isDark()),
        shapes = appearance.shapes(),
        content = content,
    )
}

/**
 * Whether to draw the dark palette.
 *
 * [UQPayAppearance.ColorMode.SYSTEM] asks the device, which is the default and the right
 * answer for an app that also follows the device. The two explicit modes exist for the app
 * that does *not* — a light-only checkout on a phone in dark mode would otherwise hand the
 * customer a dark payment sheet at the last step, which reads as someone else's screen.
 */
@Composable
internal fun UQPayAppearance.isDark(): Boolean = when (colorMode) {
    UQPayAppearance.ColorMode.SYSTEM -> isSystemInDarkTheme()
    UQPayAppearance.ColorMode.LIGHT -> false
    UQPayAppearance.ColorMode.DARK -> true
}

/**
 * The palette for one mode, as a Material 3 [ColorScheme].
 *
 * Built by overriding the roles the merchant configures on top of the stock scheme rather
 * than by constructing a [ColorScheme] from ten colours: Material 3 has around thirty roles,
 * and the ones a merchant is not asked about — `secondaryContainer`, `inverseOnSurface`,
 * `scrim` — still have to be *something* coherent. Taking the rest from the stock scheme for
 * the same mode keeps them consistent, and keeps this correct when Material adds a role.
 *
 * `surfaceVariant` follows [UQPayAppearance.Colors.surface] rather than being left stock,
 * because a merchant who sets a white surface and gets Material's lilac `surfaceVariant`
 * behind their text fields has not been given the theme they asked for.
 */
internal fun UQPayAppearance.colorScheme(dark: Boolean): ColorScheme {
    val colors = if (dark) darkColors else lightColors
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Color(colors.primary),
        onPrimary = Color(colors.onPrimary),
        background = Color(colors.background),
        onBackground = Color(colors.onBackground),
        surface = Color(colors.surface),
        onSurface = Color(colors.onSurface),
        surfaceVariant = Color(colors.surface),
        onSurfaceVariant = Color(colors.onSurfaceVariant),
        outline = Color(colors.outline),
        error = Color(colors.error),
        onError = Color(colors.onError),
    )
}

/**
 * One radius for everything the sheet draws with a corner.
 *
 * Material 3 wants four sizes; a payment sheet does not need four knobs. The configured
 * radius drives `small`, `medium` and `large` — buttons, text fields, cards — and
 * `extraSmall`/`extraLarge` scale off it so nothing looks unrelated to the rest. Every value
 * is clamped by [UQPayAppearance] before it reaches here, so none of this can go negative.
 */
internal fun UQPayAppearance.shapes(): Shapes {
    val radius = cornerRadiusDp.dp
    return Shapes(
        extraSmall = RoundedCornerShape((cornerRadiusDp / 3f).dp),
        small = RoundedCornerShape((cornerRadiusDp / 1.5f).dp),
        medium = RoundedCornerShape(radius),
        large = RoundedCornerShape(radius),
        extraLarge = RoundedCornerShape((cornerRadiusDp * 2f).dp),
    )
}
