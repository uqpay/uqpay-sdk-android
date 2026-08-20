package com.uqpay.sdk.appearance

import androidx.annotation.ColorInt
import java.util.Locale

/**
 * How the payment sheet looks.
 *
 * Supplied once on [com.uqpay.sdk.UQPayConfiguration] and applied to every screen the SDK
 * draws — the method list, the card form, the wallet QR, the bank-transfer instructions and
 * the 3-D Secure chrome. Omit it and the sheet renders in stock Material 3, which is a
 * usable default and a recognisably generic one.
 *
 * ### Why this is not optional for a payment SDK
 *
 * The payment sheet is the last screen of a merchant's checkout, and it is the one screen
 * their designers did not draw. An integration that cannot be made to match the surrounding
 * app gets rejected at design review, whatever its engineering is like — which is why every
 * comparable SDK ships an appearance API.
 *
 * ### Colours are `@ColorInt`, not Compose types
 *
 * The payment UI is Compose internally and that is deliberately invisible from here: a
 * merchant on Views, on Compose, or on a Compose version we have never heard of configures
 * the sheet with plain ARGB ints. `android.graphics.Color.parseColor("#0B5FFF")`,
 * `ContextCompat.getColor(context, R.color.brand)` and a hard-coded `0xFF0B5FFF.toInt()` all
 * work, from Kotlin and from Java.
 *
 * ### What cannot be themed, and why
 *
 * There is no way to hide the amount, the cancel affordance, the test-mode badge, or the
 * copy explaining a blocked back-press. Those are the parts of the sheet a customer needs in
 * order to know what they are agreeing to and how to get out of it, and a theming API that
 * can remove them is a theming API that will be used to remove them.
 *
 * ### Building one
 *
 * From Kotlin, use the constructor with named arguments. From Java, use [Builder] — the
 * constructor takes two [Colors] in a row, and `new UQPayAppearance(mode, dark, light, 12f)`
 * compiles as cleanly as the correct order while inverting the whole palette. There are no
 * `@JvmOverloads` here for that reason: a family of positional constructors is precisely the
 * hazard the builder exists to remove.
 *
 * @property colorMode which palette to draw. See [ColorMode].
 * @property lightColors the palette for light mode. Defaults to stock Material 3 light.
 * @property darkColors the palette for dark mode. Defaults to stock Material 3 dark.
 * @property cornerRadiusDp corner radius, in dp, for buttons, fields and cards. Clamped to
 *   `0f..28f`; a negative value is a merchant typo, not a request for a negative radius.
 */
public class UQPayAppearance(
    public val colorMode: ColorMode = ColorMode.SYSTEM,
    public val lightColors: Colors = Colors.MATERIAL_LIGHT,
    public val darkColors: Colors = Colors.MATERIAL_DARK,
    cornerRadiusDp: Float = DEFAULT_CORNER_RADIUS_DP,
) {

    /**
     * Corner radius in dp, already clamped to `0f..28f`.
     *
     * Clamped rather than rejected: a wrong radius is cosmetic, and throwing from a
     * configuration a merchant builds in `Application.onCreate` would take their whole app
     * down over a rounded corner. A blank `clientId` throws because it cannot pay; this
     * cannot fail to pay.
     */
    public val cornerRadiusDp: Float = cornerRadiusDp.coerceIn(0f, MAX_CORNER_RADIUS_DP)

    /**
     * Light, dark, or whatever the device is set to.
     *
     * [SYSTEM] is the default and follows the device's dark-mode setting. Choose [LIGHT] or
     * [DARK] when your app forces its own — an app that is light-only on a phone in dark mode
     * would otherwise hand the customer a dark payment sheet at the last step of a light
     * checkout.
     */
    public enum class ColorMode {
        /** Follow the device's dark-mode setting. The default. */
        SYSTEM,

        /** Always draw [lightColors], whatever the device is set to. */
        LIGHT,

        /** Always draw [darkColors], whatever the device is set to. */
        DARK,
    }

    /**
     * One palette. Every value is an opaque ARGB colour int.
     *
     * The names are Material 3 roles rather than invented ones, because that is what they
     * are wired to and because a designer handed "surface / on-surface" can answer without
     * learning our vocabulary.
     *
     * **Every `on*` colour must have enough contrast against the surface it is named for.**
     * Nothing here checks that — it cannot, since only you know your brand — and a payment
     * sheet whose Pay button is unreadable is a payment that does not happen. WCAG AA (4.5:1
     * for body text, 3:1 for large text and UI edges) is the bar to hold yourself to.
     *
     * @property primary the accent: the Pay button's fill, the focused field's outline.
     * @property onPrimary text and icons drawn on [primary].
     * @property background the sheet's backdrop.
     * @property onBackground primary text on [background].
     * @property surface cards, fields and raised areas.
     * @property onSurface primary text on [surface].
     * @property onSurfaceVariant secondary text: hints, captions, the order reference.
     * @property outline field borders and dividers.
     * @property error validation messages and the outline of a field that failed one.
     * @property onError text drawn on an [error]-filled surface.
     */
    public class Colors(
        @get:ColorInt public val primary: Int,
        @get:ColorInt public val onPrimary: Int,
        @get:ColorInt public val background: Int,
        @get:ColorInt public val onBackground: Int,
        @get:ColorInt public val surface: Int,
        @get:ColorInt public val onSurface: Int,
        @get:ColorInt public val onSurfaceVariant: Int,
        @get:ColorInt public val outline: Int,
        @get:ColorInt public val error: Int,
        @get:ColorInt public val onError: Int,
    ) {

        override fun equals(other: Any?): Boolean =
            other is Colors &&
                other.primary == primary &&
                other.onPrimary == onPrimary &&
                other.background == background &&
                other.onBackground == onBackground &&
                other.surface == surface &&
                other.onSurface == onSurface &&
                other.onSurfaceVariant == onSurfaceVariant &&
                other.outline == outline &&
                other.error == error &&
                other.onError == onError

        override fun hashCode(): Int = listOf(
            primary, onPrimary, background, onBackground, surface,
            onSurface, onSurfaceVariant, outline, error, onError,
        ).hashCode()

        override fun toString(): String =
            "Colors(primary=${hex(primary)}, onPrimary=${hex(onPrimary)}, " +
                "background=${hex(background)}, onBackground=${hex(onBackground)}, " +
                "surface=${hex(surface)}, onSurface=${hex(onSurface)}, " +
                "onSurfaceVariant=${hex(onSurfaceVariant)}, outline=${hex(outline)}, " +
                "error=${hex(error)}, onError=${hex(onError)})"

        /**
         * Returns a copy with only the named colours changed.
         *
         * The common case is a merchant who wants their brand colour on the Pay button and
         * the platform's judgement about everything else:
         * `Colors.MATERIAL_LIGHT.copy(primary = 0xFF0B5FFF.toInt())`.
         *
         * Kotlin only, deliberately: with `@JvmOverloads` this becomes eleven `copy(int…)`
         * overloads that differ by arity alone, which is unreadable at the call site and
         * trivially miscounted. Java callers use [Builder].
         */
        public fun copy(
            @ColorInt primary: Int = this.primary,
            @ColorInt onPrimary: Int = this.onPrimary,
            @ColorInt background: Int = this.background,
            @ColorInt onBackground: Int = this.onBackground,
            @ColorInt surface: Int = this.surface,
            @ColorInt onSurface: Int = this.onSurface,
            @ColorInt onSurfaceVariant: Int = this.onSurfaceVariant,
            @ColorInt outline: Int = this.outline,
            @ColorInt error: Int = this.error,
            @ColorInt onError: Int = this.onError,
        ): Colors = Colors(
            primary = primary,
            onPrimary = onPrimary,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            error = error,
            onError = onError,
        )

        /**
         * Names every colour it sets, starting from a base palette.
         *
         * Ten ARGB ints in a row is the worst possible constructor signature: every argument
         * has the same type, none of them is checkable, and swapping `surface` with
         * `onSurface` produces a payment sheet whose text is the same colour as the field it
         * sits in. This is the shape to use from Java, and a reasonable one from Kotlin:
         *
         * ```java
         * UQPayAppearance.Colors brand = new UQPayAppearance.Colors.Builder(
         *         UQPayAppearance.Colors.MATERIAL_LIGHT)
         *     .primary(0xFF0B5FFF)
         *     .onPrimary(0xFFFFFFFF)
         *     .build();
         * ```
         *
         * @param base the palette to start from; every colour not set here keeps its value.
         *   Defaults to [MATERIAL_LIGHT], so a builder with nothing set is stock Material 3
         *   light rather than ten transparent blacks.
         */
        public class Builder @JvmOverloads constructor(base: Colors = MATERIAL_LIGHT) {
            private var primary: Int = base.primary
            private var onPrimary: Int = base.onPrimary
            private var background: Int = base.background
            private var onBackground: Int = base.onBackground
            private var surface: Int = base.surface
            private var onSurface: Int = base.onSurface
            private var onSurfaceVariant: Int = base.onSurfaceVariant
            private var outline: Int = base.outline
            private var error: Int = base.error
            private var onError: Int = base.onError

            /** The accent: the Pay button's fill, the focused field's outline. */
            public fun primary(@ColorInt primary: Int): Builder = apply { this.primary = primary }

            /** Text and icons drawn on [primary]. */
            public fun onPrimary(@ColorInt onPrimary: Int): Builder =
                apply { this.onPrimary = onPrimary }

            /** The sheet's backdrop. */
            public fun background(@ColorInt background: Int): Builder =
                apply { this.background = background }

            /** Primary text on [background]. */
            public fun onBackground(@ColorInt onBackground: Int): Builder =
                apply { this.onBackground = onBackground }

            /** Cards, fields and raised areas. */
            public fun surface(@ColorInt surface: Int): Builder = apply { this.surface = surface }

            /** Primary text on [surface]. */
            public fun onSurface(@ColorInt onSurface: Int): Builder =
                apply { this.onSurface = onSurface }

            /** Secondary text: hints, captions, the order reference. */
            public fun onSurfaceVariant(@ColorInt onSurfaceVariant: Int): Builder =
                apply { this.onSurfaceVariant = onSurfaceVariant }

            /** Field borders and dividers. */
            public fun outline(@ColorInt outline: Int): Builder = apply { this.outline = outline }

            /** Validation messages and the outline of a field that failed one. */
            public fun error(@ColorInt error: Int): Builder = apply { this.error = error }

            /** Text drawn on an [error]-filled surface. */
            public fun onError(@ColorInt onError: Int): Builder = apply { this.onError = onError }

            /** Builds the palette. Safe to call more than once. */
            public fun build(): Colors = Colors(
                primary = primary,
                onPrimary = onPrimary,
                background = background,
                onBackground = onBackground,
                surface = surface,
                onSurface = onSurface,
                onSurfaceVariant = onSurfaceVariant,
                outline = outline,
                error = error,
                onError = onError,
            )
        }

        public companion object {

            private fun hex(@ColorInt value: Int): String = String.format(Locale.ROOT, "#%08X", value)

            /**
             * Stock Material 3 light, verbatim from `lightColorScheme()`'s baseline palette.
             *
             * Written out as literals rather than read from Compose so that this class — the
             * one a merchant configures — carries no Compose type, and so the default cannot
             * shift under a merchant's feet when the Material 3 baseline is next revised.
             */
            @JvmField
            public val MATERIAL_LIGHT: Colors = Colors(
                primary = 0xFF6750A4.toInt(),
                onPrimary = 0xFFFFFFFF.toInt(),
                background = 0xFFFFFBFE.toInt(),
                onBackground = 0xFF1C1B1F.toInt(),
                surface = 0xFFFFFBFE.toInt(),
                onSurface = 0xFF1C1B1F.toInt(),
                onSurfaceVariant = 0xFF49454F.toInt(),
                outline = 0xFF79747E.toInt(),
                error = 0xFFB3261E.toInt(),
                onError = 0xFFFFFFFF.toInt(),
            )

            /** Stock Material 3 dark, verbatim from `darkColorScheme()`'s baseline palette. */
            @JvmField
            public val MATERIAL_DARK: Colors = Colors(
                primary = 0xFFD0BCFF.toInt(),
                onPrimary = 0xFF381E72.toInt(),
                background = 0xFF1C1B1F.toInt(),
                onBackground = 0xFFE6E1E5.toInt(),
                surface = 0xFF1C1B1F.toInt(),
                onSurface = 0xFFE6E1E5.toInt(),
                onSurfaceVariant = 0xFFCAC4D0.toInt(),
                outline = 0xFF938F99.toInt(),
                error = 0xFFF2B8B5.toInt(),
                onError = 0xFF601410.toInt(),
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is UQPayAppearance &&
            other.colorMode == colorMode &&
            other.lightColors == lightColors &&
            other.darkColors == darkColors &&
            other.cornerRadiusDp == cornerRadiusDp

    override fun hashCode(): Int {
        var result = colorMode.hashCode()
        result = 31 * result + lightColors.hashCode()
        result = 31 * result + darkColors.hashCode()
        result = 31 * result + cornerRadiusDp.hashCode()
        return result
    }

    override fun toString(): String =
        "UQPayAppearance(colorMode=$colorMode, lightColors=$lightColors, " +
            "darkColors=$darkColors, cornerRadiusDp=$cornerRadiusDp)"

    /**
     * Names every value it sets. The shape to use from Java; from Kotlin, named arguments on
     * the constructor do the same job in fewer lines.
     *
     * ```java
     * UQPayAppearance appearance = new UQPayAppearance.Builder()
     *     .colorMode(UQPayAppearance.ColorMode.LIGHT)
     *     .lightColors(brand)
     *     .cornerRadiusDp(4f)
     *     .build();
     * ```
     *
     * Every value is optional; anything not set keeps the stock Material 3 default, so a
     * merchant who only wants their brand colour writes one line.
     */
    public class Builder {
        private var colorMode: ColorMode = ColorMode.SYSTEM
        private var lightColors: Colors = Colors.MATERIAL_LIGHT
        private var darkColors: Colors = Colors.MATERIAL_DARK
        private var cornerRadiusDp: Float = DEFAULT_CORNER_RADIUS_DP

        /** Light, dark, or the device's setting. See [ColorMode]. */
        public fun colorMode(colorMode: ColorMode): Builder = apply { this.colorMode = colorMode }

        /** The palette drawn in light mode. */
        public fun lightColors(lightColors: Colors): Builder =
            apply { this.lightColors = lightColors }

        /** The palette drawn in dark mode. */
        public fun darkColors(darkColors: Colors): Builder = apply { this.darkColors = darkColors }

        /** Corner radius in dp for buttons, fields and cards. Clamped to `0f..28f`. */
        public fun cornerRadiusDp(cornerRadiusDp: Float): Builder =
            apply { this.cornerRadiusDp = cornerRadiusDp }

        /** Builds the appearance. Safe to call more than once. */
        public fun build(): UQPayAppearance = UQPayAppearance(
            colorMode = colorMode,
            lightColors = lightColors,
            darkColors = darkColors,
            cornerRadiusDp = cornerRadiusDp,
        )
    }

    public companion object {

        /** The default radius, matching Material 3's own medium shape. */
        public const val DEFAULT_CORNER_RADIUS_DP: Float = 12f

        /** The largest radius the sheet will draw; past this, buttons stop reading as buttons. */
        public const val MAX_CORNER_RADIUS_DP: Float = 28f

        /** Stock Material 3, following the device's dark-mode setting. */
        @JvmField
        public val DEFAULT: UQPayAppearance = UQPayAppearance()
    }
}
