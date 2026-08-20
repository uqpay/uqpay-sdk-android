package com.uqpay.sdk.appearance

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.uqpay.sdk.ui.colorScheme
import com.uqpay.sdk.ui.shapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merchant-facing appearance API and the Material 3 theme it produces.
 *
 * Two things have to hold, and neither is cosmetic. A brand colour a merchant sets must
 * actually reach the sheet — an appearance API that is quietly ignored is worse than none,
 * because it passes design review and then ships stock purple. And nothing a merchant can
 * type may produce a theme the sheet cannot draw, since this is configured in
 * `Application.onCreate` and a throw there takes their whole app down.
 */
class UQPayAppearanceTest {

    private val brandBlue = 0xFF0B5FFF.toInt()
    private val brandWhite = 0xFFFFFFFF.toInt()

    // ---- the palette actually reaches the theme -------------------------------------------

    @Test
    fun `a configured colour reaches the Material 3 scheme it is named for`() {
        val appearance = UQPayAppearance(
            lightColors = UQPayAppearance.Colors.MATERIAL_LIGHT.copy(
                primary = brandBlue,
                onPrimary = brandWhite,
            ),
        )

        val scheme = appearance.colorScheme(dark = false)

        assertEquals(brandBlue, scheme.primary.toArgb())
        assertEquals(brandWhite, scheme.onPrimary.toArgb())
    }

    @Test
    fun `the light and dark palettes are independent`() {
        val appearance = UQPayAppearance(
            lightColors = UQPayAppearance.Colors.MATERIAL_LIGHT.copy(primary = brandBlue),
            darkColors = UQPayAppearance.Colors.MATERIAL_DARK.copy(primary = brandWhite),
        )

        assertEquals(brandBlue, appearance.colorScheme(dark = false).primary.toArgb())
        assertEquals(brandWhite, appearance.colorScheme(dark = true).primary.toArgb())
    }

    /**
     * `surfaceVariant` is the fill behind text fields. Material's default is a lilac that
     * has nothing to do with a merchant's surface colour, so it follows [surface] rather
     * than being left stock — otherwise a merchant setting a white sheet gets white
     * everywhere except behind the card number.
     */
    @Test
    fun `surfaceVariant follows the configured surface`() {
        val surface = 0xFFF7F7F7.toInt()
        val appearance = UQPayAppearance(
            lightColors = UQPayAppearance.Colors.MATERIAL_LIGHT.copy(surface = surface),
        )

        val scheme = appearance.colorScheme(dark = false)
        assertEquals(surface, scheme.surface.toArgb())
        assertEquals(surface, scheme.surfaceVariant.toArgb())
    }

    /**
     * Roles a merchant is never asked about still have to be coherent, and they come from
     * the stock scheme for the same mode. If they did not, adding a Material role would
     * silently give the sheet a transparent or black one.
     */
    @Test
    fun `roles the merchant does not configure are still different between light and dark`() {
        val appearance = UQPayAppearance()
        assertNotEquals(
            appearance.colorScheme(dark = false).secondaryContainer,
            appearance.colorScheme(dark = true).secondaryContainer,
        )
    }

    @Test
    fun `the default palettes are the stock Material 3 ones and differ by mode`() {
        val appearance = UQPayAppearance()
        assertEquals(UQPayAppearance.Colors.MATERIAL_LIGHT, appearance.lightColors)
        assertEquals(UQPayAppearance.Colors.MATERIAL_DARK, appearance.darkColors)
        assertNotEquals(appearance.lightColors, appearance.darkColors)
    }

    // ---- nothing a merchant types may break the sheet ---------------------------------------

    @Test
    fun `a negative corner radius is clamped rather than thrown`() {
        assertEquals(0f, UQPayAppearance(cornerRadiusDp = -12f).cornerRadiusDp, 0f)
    }

    @Test
    fun `an absurd corner radius is clamped to the largest the sheet will draw`() {
        assertEquals(
            UQPayAppearance.MAX_CORNER_RADIUS_DP,
            UQPayAppearance(cornerRadiusDp = 9_000f).cornerRadiusDp,
            0f,
        )
    }

    @Test
    fun `every radius a merchant can type produces a drawable shape set`() {
        // The derived sizes divide and multiply the radius, so the extremes are where a
        // negative or an overflowed corner would appear. Building the set is the assertion:
        // Compose rejects a negative corner at construction.
        listOf(-9_000f, -1f, 0f, 0.5f, 12f, 28f, 9_000f).forEach { requested ->
            val appearance = UQPayAppearance(cornerRadiusDp = requested)
            val shapes = appearance.shapes()
            assertEquals(
                RoundedCornerShape(appearance.cornerRadiusDp.dp),
                shapes.medium,
            )
        }
    }

    // ---- the builders name what they set -----------------------------------------------------

    @Test
    fun `the appearance builder produces the same value as named arguments`() {
        val built = UQPayAppearance.Builder()
            .colorMode(UQPayAppearance.ColorMode.DARK)
            .cornerRadiusDp(4f)
            .build()

        assertEquals(
            UQPayAppearance(colorMode = UQPayAppearance.ColorMode.DARK, cornerRadiusDp = 4f),
            built,
        )
    }

    @Test
    fun `the colours builder starts from its base and changes only what is named`() {
        val base = UQPayAppearance.Colors.MATERIAL_DARK
        val built = UQPayAppearance.Colors.Builder(base).primary(brandBlue).build()

        assertEquals(brandBlue, built.primary)
        assertEquals(base.onPrimary, built.onPrimary)
        assertEquals(base.background, built.background)
        assertEquals(base.onError, built.onError)
        assertEquals(base.copy(primary = brandBlue), built)
    }

    @Test
    fun `a builder with nothing set is stock Material 3 light, not ten transparent blacks`() {
        assertEquals(UQPayAppearance.Colors.MATERIAL_LIGHT, UQPayAppearance.Colors.Builder().build())
    }

    @Test
    fun `building twice from one builder gives equal, independent values`() {
        val builder = UQPayAppearance.Colors.Builder().primary(brandBlue)
        val first = builder.build()
        val second = builder.primary(brandWhite).build()

        assertEquals(brandBlue, first.primary)
        assertEquals(brandWhite, second.primary)
    }

    // ---- toString is for logs, and logs are read by humans ------------------------------------

    @Test
    fun `toString prints colours as hex rather than as signed ints`() {
        val text = UQPayAppearance.Colors.MATERIAL_LIGHT.copy(primary = brandBlue).toString()
        assertTrue(text, text.contains("#FF0B5FFF"))
    }
}
