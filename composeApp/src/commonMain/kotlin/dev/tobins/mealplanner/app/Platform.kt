package dev.tobins.mealplanner.app

import androidx.compose.runtime.Composable

/** Runs [onBack] instead of the platform's default back action while [enabled]. */
@Composable
expect fun BackHandler(enabled: Boolean, onBack: () -> Unit)

/** Today's date as an ISO string, e.g. `2026-11-12`. */
expect fun todayIso(): String

/** The ISO date [days] after [isoDate] (before, when negative). */
expect fun plusDays(isoDate: String, days: Int): String

/** The Monday on or before [isoDate]. */
expect fun mondayOf(isoDate: String): String

/** A short label for a date, e.g. `Mon 16 Nov`. */
expect fun dayLabel(isoDate: String): String
