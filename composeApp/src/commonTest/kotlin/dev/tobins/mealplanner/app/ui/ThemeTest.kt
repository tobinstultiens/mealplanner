package dev.tobins.mealplanner.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeTest {
    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    /** The text colour and the background it sits on, for every pairing the components use. */
    private fun pairs(c: ColorScheme, warning: Color) = mapOf(
        "onPrimary/primary" to (c.onPrimary to c.primary),
        "onPrimaryContainer/primaryContainer" to (c.onPrimaryContainer to c.primaryContainer),
        "onSecondaryContainer/secondaryContainer" to (c.onSecondaryContainer to c.secondaryContainer),
        "onTertiaryContainer/tertiaryContainer" to (c.onTertiaryContainer to c.tertiaryContainer),
        "onError/error" to (c.onError to c.error),
        "onErrorContainer/errorContainer" to (c.onErrorContainer to c.errorContainer),
        "onSurface/surface" to (c.onSurface to c.surface),
        "onBackground/background" to (c.onBackground to c.background),
        "onSurfaceVariant/surface" to (c.onSurfaceVariant to c.surface),
        "onSurfaceVariant/surfaceContainer" to (c.onSurfaceVariant to c.surfaceContainer),
        "onSurfaceVariant/surfaceContainerHighest" to (c.onSurfaceVariant to c.surfaceContainerHighest),
        "inverseOnSurface/inverseSurface" to (c.inverseOnSurface to c.inverseSurface),
        // Coloured text straight on the screen: headers, errors, warnings.
        "primary/surface" to (c.primary to c.surface),
        "error/surface" to (c.error to c.surface),
        "tertiary/surface" to (c.tertiary to c.surface),
        "warning/surface" to (warning to c.surface),
        "warning/surfaceContainer" to (warning to c.surfaceContainer),
        "warning/surfaceContainerHighest" to (warning to c.surfaceContainerHighest),
    )

    @Test
    fun textMeetsWcagAaOnItsBackgroundInBothSchemes() {
        for (dark in listOf(false, true)) {
            pairs(appColorScheme(dark), warningColor(dark)).forEach { (name, pair) ->
                val ratio = contrast(pair.first, pair.second)
                assertTrue(ratio >= 4.5, "$name in ${if (dark) "dark" else "light"} has contrast $ratio, below 4.5")
            }
        }
    }

    @Test
    fun theSchemesAreGreenNotTheDefaultPurple() {
        val light = appColorScheme(false)
        val dark = appColorScheme(true)
        assertTrue(light.background.luminance() > 0.5 && dark.background.luminance() < 0.1)
        for (scheme in listOf(light, dark)) {
            assertTrue(scheme.primary.green > scheme.primary.red && scheme.primary.green > scheme.primary.blue)
        }
    }

    @Test
    fun numericStyleTurnsOnTabularFiguresAndKeepsTheRest() {
        val base = AppTypography.bodyMedium
        val styled = base.numeric()
        assertEquals("tnum", styled.fontFeatureSettings)
        assertEquals(base.fontSize, styled.fontSize)
    }
}
