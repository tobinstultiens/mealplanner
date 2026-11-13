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

/** Whole days from [fromIso] to [toIso]; negative when [toIso] is earlier. */
expect fun daysBetween(fromIso: String, toIso: String): Int

/** The current local time, e.g. `Fri 13 Nov 12:40`. */
expect fun nowLabel(): String
