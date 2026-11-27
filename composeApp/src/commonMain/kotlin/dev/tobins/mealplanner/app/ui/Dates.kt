package dev.tobins.mealplanner.app.ui

import dev.tobins.mealplanner.app.Msg
import dev.tobins.mealplanner.app.Strings
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
 * Date helpers shared by Android and the web. Dates travel as ISO strings, as in the API;
 * the labels follow the app's language ([FormatLocale], [Strings]).
 */

/** Where "now" comes from. Only the screenshot tests replace it, so that their pictures show the same date every day. */
@OptIn(ExperimentalTime::class)
internal var appClock: Clock = Clock.System

/** Today's date as an ISO string, e.g. `2026-11-12`. */
@OptIn(ExperimentalTime::class)
fun todayIso(): String = appClock.todayIn(TimeZone.currentSystemDefault()).toString()

/** The ISO date [days] after [isoDate] (before, when negative). */
fun plusDays(isoDate: String, days: Int): String = LocalDate.parse(isoDate).plus(DatePeriod(days = days)).toString()

/** The Monday on or before [isoDate]. */
fun mondayOf(isoDate: String): String {
    val date = LocalDate.parse(isoDate)
    return date.minus(DatePeriod(days = date.dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber)).toString()
}

/** A short label for a date, e.g. `Mon 16 Nov` (`Mon, Nov 16` in American English). */
fun dayLabel(isoDate: String, locale: FormatLocale): String {
    val date = LocalDate.parse(isoDate)
    val weekday = locale.dayNames[date.dayOfWeek.isoDayNumber - 1]
    return "$weekday${if (locale.monthFirst) "," else ""} ${dayMonthLabel(isoDate, locale)}"
}

/** A date without its weekday, e.g. `16 Nov` (`Nov 16` in American English). */
fun dayMonthLabel(isoDate: String, locale: FormatLocale): String {
    val date = LocalDate.parse(isoDate)
    val month = locale.monthNames[date.month.ordinal]
    return if (locale.monthFirst) "$month ${date.day}" else "${date.day} $month"
}

/**
 * When something happened, as briefly as is clear: `today 12:40`, `yesterday 12:40`, or
 * `Fri 13 Nov 12:40` for older ones. [isoDate] and [time] are local; [today] is today's date.
 */
fun whenLabel(isoDate: String, time: String, today: String, strings: Strings): String =
    when (daysBetween(isoDate, today)) {
        0 -> strings(Msg.WHEN_TODAY, time)
        1 -> strings(Msg.WHEN_YESTERDAY, time)
        else -> "${dayLabel(isoDate, strings.locale)} $time"
    }

/** Whole days from [fromIso] to [toIso]; negative when [toIso] is earlier. */
fun daysBetween(fromIso: String, toIso: String): Int = LocalDate.parse(fromIso).daysUntil(LocalDate.parse(toIso))

/** The current moment as an ISO instant, for saving when something happened; show it with [instantLabel]. */
@OptIn(ExperimentalTime::class)
fun nowIso(): String = appClock.now().toString()

/** An ISO instant in local time, e.g. `today 12:40` or `Fri 13 Nov 12:40`. */
@OptIn(ExperimentalTime::class)
fun instantLabel(isoInstant: String, strings: Strings): String {
    val local = kotlin.time.Instant.parse(isoInstant).toLocalDateTime(TimeZone.currentSystemDefault())
    val time = "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
    return whenLabel(local.date.toString(), time, todayIso(), strings)
}
