package dev.tobins.mealplanner.app

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/*
 * Date helpers shared by Android and the web. Dates travel as ISO strings, as in the API.
 * Labels are English, like the rest of the app.
 */

private val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** Today's date as an ISO string, e.g. `2026-11-12`. */
@OptIn(ExperimentalTime::class)
fun todayIso(): String = Clock.System.todayIn(TimeZone.currentSystemDefault()).toString()

/** The ISO date [days] after [isoDate] (before, when negative). */
fun plusDays(isoDate: String, days: Int): String = LocalDate.parse(isoDate).plus(DatePeriod(days = days)).toString()

/** The Monday on or before [isoDate]. */
fun mondayOf(isoDate: String): String {
    val date = LocalDate.parse(isoDate)
    return date.minus(DatePeriod(days = date.dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber)).toString()
}

/** A short label for a date, e.g. `Mon 16 Nov`. */
fun dayLabel(isoDate: String): String {
    val date = LocalDate.parse(isoDate)
    return "${dayNames[date.dayOfWeek.isoDayNumber - 1]} ${date.day} ${monthNames[date.month.ordinal]}"
}

/** Whole days from [fromIso] to [toIso]; negative when [toIso] is earlier. */
fun daysBetween(fromIso: String, toIso: String): Int = LocalDate.parse(fromIso).daysUntil(LocalDate.parse(toIso))

/** The current local time, e.g. `Fri 13 Nov 12:40`. */
@OptIn(ExperimentalTime::class)
fun nowLabel(): String {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return "${dayLabel(now.date.toString())} ${now.hour.toString().padStart(2, '0')}:${now.minute.toString().padStart(2, '0')}"
}

/** An ISO instant in local time, e.g. `Fri 13 Nov 12:40`. */
@OptIn(ExperimentalTime::class)
fun instantLabel(isoInstant: String): String {
    val local = kotlin.time.Instant.parse(isoInstant).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${dayLabel(local.date.toString())} ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
}
