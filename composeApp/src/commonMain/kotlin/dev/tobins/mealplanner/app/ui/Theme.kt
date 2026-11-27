package dev.tobins.mealplanner.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tobins.mealplanner.app.systemColorScheme

/*
 * The app's look in one place: colours, type scale and spacing. Screens take all three from
 * here (through MaterialTheme and Spacing) and never write a colour of their own.
 */

/*
 * The colour schemes come from Material's tonal method (material-color-utilities, tonal spot,
 * contrast 0) with the seed #2E7D32, a fresh green. The values are written out so the app
 * needs no colour library and the palette is reviewable. Every text/background pair the
 * components use (on-primary on primary, on-surface on surface, and so on) has a contrast
 * ratio of at least 4.5:1 in both schemes (WCAG AA); ThemeTest enforces it.
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF3C6939),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBCF0B4),
    onPrimaryContainer = Color(0xFF245024),
    inversePrimary = Color(0xFFA1D39A),
    secondary = Color(0xFF52634F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6E8CE),
    onSecondaryContainer = Color(0xFF3B4B38),
    tertiary = Color(0xFF38656A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBCEBF0),
    onTertiaryContainer = Color(0xFF1F4D52),
    background = Color(0xFFF7FBF1),
    onBackground = Color(0xFF191D17),
    surface = Color(0xFFF7FBF1),
    onSurface = Color(0xFF191D17),
    surfaceVariant = Color(0xFFDEE5D8),
    onSurfaceVariant = Color(0xFF424940),
    surfaceTint = Color(0xFF3C6939),
    inverseSurface = Color(0xFF2D322C),
    inverseOnSurface = Color(0xFFEFF2E9),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF72796F),
    outlineVariant = Color(0xFFC2C9BD),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF7FBF1),
    surfaceDim = Color(0xFFD8DBD2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5EB),
    surfaceContainer = Color(0xFFECEFE6),
    surfaceContainerHigh = Color(0xFFE6E9E0),
    surfaceContainerHighest = Color(0xFFE0E4DB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA1D39A),
    onPrimary = Color(0xFF0A390F),
    primaryContainer = Color(0xFF245024),
    onPrimaryContainer = Color(0xFFBCF0B4),
    inversePrimary = Color(0xFF3C6939),
    secondary = Color(0xFFBACCB3),
    onSecondary = Color(0xFF253423),
    secondaryContainer = Color(0xFF3B4B38),
    onSecondaryContainer = Color(0xFFD6E8CE),
    tertiary = Color(0xFFA0CFD4),
    onTertiary = Color(0xFF00363B),
    tertiaryContainer = Color(0xFF1F4D52),
    onTertiaryContainer = Color(0xFFBCEBF0),
    background = Color(0xFF10140F),
    onBackground = Color(0xFFE0E4DB),
    surface = Color(0xFF10140F),
    onSurface = Color(0xFFE0E4DB),
    surfaceVariant = Color(0xFF424940),
    onSurfaceVariant = Color(0xFFC2C9BD),
    surfaceTint = Color(0xFFA1D39A),
    inverseSurface = Color(0xFFE0E4DB),
    inverseOnSurface = Color(0xFF2D322C),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8C9388),
    outlineVariant = Color(0xFF424940),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF363A34),
    surfaceDim = Color(0xFF10140F),
    surfaceContainerLowest = Color(0xFF0B0F0A),
    surfaceContainerLow = Color(0xFF191D17),
    surfaceContainer = Color(0xFF1D211B),
    surfaceContainerHigh = Color(0xFF272B25),
    surfaceContainerHighest = Color(0xFF323630),
)

/** The colour scheme for light or dark mode. */
fun appColorScheme(dark: Boolean): ColorScheme = if (dark) DarkColors else LightColors

/**
 * The colour of warnings (text and icon on the screen's surfaces): an amber, since no slot of
 * the Material scheme reads as "careful" (tertiary is teal here, and wallpaper colours would
 * make it anything). It keeps at least 4.5:1 on the surfaces in both schemes, and stays ours
 * when the scheme comes from the wallpaper.
 */
fun warningColor(dark: Boolean): Color = if (dark) Color(0xFFFFB95C) else Color(0xFF8A5100)

private val LocalWarningColor = staticCompositionLocalOf { warningColor(dark = false) }

/** The colour of warnings in the current theme; see [warningColor]. */
val MaterialTheme.warning: Color
    @Composable @ReadOnlyComposable get() = LocalWarningColor.current

/**
 * The type scale: Material's sizes with firmer weights on titles and labels, so a screen's
 * hierarchy reads at a glance. It keeps the platform's own typeface; a font file would add
 * to the web bundle.
 */
val AppTypography: Typography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
    )
}

/**
 * Figures of equal width (the OpenType "tnum" feature), so kcal, grams and euros line up in
 * columns and don't jitter when a value changes. The one style all number-heavy text uses.
 */
private val TabularFigures = TextStyle(fontFeatureSettings = "tnum")

/** This style with tabular figures: for text made of amounts. */
fun TextStyle.numeric(): TextStyle = merge(TabularFigures)

/** The distances between things; layout-specific sizes (icons, touch targets) stay literal. */
object Spacing {
    /** Between the screen's edge and its content. */
    val ScreenEdge = 16.dp

    /** Between the cards or sections of a screen. */
    val BetweenCards = 12.dp

    /** Inside a card, around its content. */
    val InsideCard = 16.dp

    /** Between the lines of a card, or the controls of a row. */
    val Related = 8.dp

    /** Between a label and its value, or the parts of a compact row. */
    val Tight = 4.dp

    /** Around a message that fills the empty part of a screen. */
    val Message = 24.dp
}

/**
 * Applies the app's colours and type scale to [content]. With [systemColors] (where the
 * device offers them, see [systemColorScheme]) the colour scheme comes from the wallpaper;
 * the type scale stays the app's own.
 */
@Composable
fun MealPlannerTheme(dark: Boolean, systemColors: Boolean = false, content: @Composable () -> Unit) {
    val scheme = (if (systemColors) systemColorScheme(dark) else null) ?: appColorScheme(dark)
    CompositionLocalProvider(LocalWarningColor provides warningColor(dark)) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
