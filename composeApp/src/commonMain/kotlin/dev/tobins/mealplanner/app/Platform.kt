package dev.tobins.mealplanner.app

import androidx.compose.runtime.Composable

/** Runs [onBack] instead of the platform's default back action while [enabled]. */
@Composable
expect fun BackHandler(enabled: Boolean, onBack: () -> Unit)
