package dev.tobins.mealplanner.app

import androidx.compose.runtime.Composable

/** Runs [onBack] instead of the platform's default back action while [enabled]. */
@Composable
expect fun BackHandler(enabled: Boolean, onBack: () -> Unit)

/** Makes the status and navigation bar icons readable on a [dark] or light app. */
@Composable
expect fun SystemBars(dark: Boolean)

/** Cryptographically secure random bytes, for the login's PKCE secret and state. */
expect fun secureRandomBytes(size: Int): ByteArray
