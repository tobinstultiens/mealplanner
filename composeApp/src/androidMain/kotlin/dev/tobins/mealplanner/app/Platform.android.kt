package dev.tobins.mealplanner.app

import androidx.compose.runtime.Composable

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) =
    androidx.activity.compose.BackHandler(enabled, onBack)

actual fun todayIso(): String = java.time.LocalDate.now().toString()

actual fun plusDays(isoDate: String, days: Int): String =
    java.time.LocalDate.parse(isoDate).plusDays(days.toLong()).toString()

actual fun mondayOf(isoDate: String): String =
    java.time.LocalDate.parse(isoDate).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toString()

actual fun dayLabel(isoDate: String): String =
    java.time.LocalDate.parse(isoDate).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH))

actual fun daysBetween(fromIso: String, toIso: String): Int =
    (java.time.LocalDate.parse(toIso).toEpochDay() - java.time.LocalDate.parse(fromIso).toEpochDay()).toInt()
