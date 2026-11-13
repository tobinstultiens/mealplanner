package dev.tobins.mealplanner.app

import androidx.compose.runtime.Composable

/** The browser has its own back button and history; the app does not intercept it. */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
