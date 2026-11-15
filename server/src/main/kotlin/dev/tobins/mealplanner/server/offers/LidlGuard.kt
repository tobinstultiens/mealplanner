package dev.tobins.mealplanner.server.offers

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Notices when the Lidl offers page has silently stopped showing new offers: the fetch
 * still finds tiles, so it reports no error, but they are old. A warning, not an error:
 * the store keeps its offers.
 */
object LidlGuard {
    /**
     * The warning for Lidl's offers as of [today], or null when they look current. [ends] are
     * the (inclusive) last days of all stored Lidl offers. Without offers there is nothing to
     * judge; a failed or missing fetch is reported on its own.
     *
     * Warns when every offer has ended, or when it is Friday or later and none runs into
     * next week (Lidl publishes the next week's offers by then).
     */
    fun warning(ends: List<LocalDate>, today: LocalDate): String? {
        val last = ends.maxOrNull() ?: return null
        if (last < today) return "All Lidl offers have ended (the last on $last); the offers page may no longer list the current ones."
        val nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        if (today.dayOfWeek >= DayOfWeek.FRIDAY && last < nextMonday) {
            return "No Lidl offer runs into next week (the last ends $last); the offers page may not show next week's offers."
        }
        return null
    }
}
