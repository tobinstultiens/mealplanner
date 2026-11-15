package dev.tobins.mealplanner.app

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** Runs [onBack] instead of the platform's default back action while [enabled]. */
@Composable
expect fun BackHandler(enabled: Boolean, onBack: () -> Unit)

/** Makes the status and navigation bar icons readable on a [dark] or light app. */
@Composable
expect fun SystemBars(dark: Boolean)

/** Cryptographically secure random bytes, for the login's PKCE secret and state. */
expect fun secureRandomBytes(size: Int): ByteArray

/** The device's locale as a BCP-47 tag (`nl-NL`), for [FormatLocale]. */
expect fun deviceLocaleTag(): String

/** Whether the device can take the colour scheme from the wallpaper (Android 12 and up). */
expect fun systemColorsAvailable(): Boolean

/** The wallpaper-based colour scheme, or null where [systemColorsAvailable] is false. */
@Composable
expect fun systemColorScheme(dark: Boolean): ColorScheme?
