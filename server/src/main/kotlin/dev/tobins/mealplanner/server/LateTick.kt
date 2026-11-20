package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingItem
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// What a tick sent late (made offline, sent after the list moved on) may take from what the phone showed
// (issue 116). The phone is trusted only within bounds that the server's own row sets, and a figure out
// of bounds is left out, so the tick is stored all the same with what the server works out itself.

/** The weight the row says: [ShoppingItem.grams], or what is known of it when not all could be weighed. */
private val ShoppingItem.weighed: Double? get() = grams ?: knownGrams

/** The weight the phone showed, when it is one this [row] could have had; null otherwise. */
internal fun ShoppingCheck.Shown.gramsFor(row: ShoppingItem): Double? {
    val sent = grams?.takeIf { it.isFinite() && it > 0 && it <= ShoppingCheck.MAX_GRAMS } ?: return null
    return sent.takeIf { row.weighed.let { now -> now == null || sent <= now * ShoppingCheck.AMOUNT_FACTOR } }
}

/** The pieces the phone showed, when this [row] counts pieces and could have had that many; null otherwise. */
internal fun ShoppingCheck.Shown.piecesFor(row: ShoppingItem): Double? {
    val sent = pieces?.takeIf { it.isFinite() && it > 0 } ?: return null
    val now = row.pieces ?: return null
    return sent.takeIf { it <= now * ShoppingCheck.AMOUNT_FACTOR }
}

/**
 * The saving the phone counted at the tick, when it is to be believed: the tick is dated (a valid day,
 * made on an earlier day than [today], as a tick of today has its offer on the server's list still, and
 * at most [ShoppingCheck.MAX_DAYS_LATE] days before it), and the saving is from 0 to the row's regular
 * price, which the saving of its whole need cannot exceed (a row without one saved nothing known).
 * A phone whose clock runs ahead dates its tick in the future, which is not believed either. Null otherwise.
 */
internal fun ShoppingCheck.Shown.savingFor(row: ShoppingItem, today: LocalDate): Double? {
    val day = tickedOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    if (day >= today || ChronoUnit.DAYS.between(day, today) > ShoppingCheck.MAX_DAYS_LATE) return null
    val price = row.regularPrice ?: return null
    return saving?.takeIf { it.isFinite() && it >= 0 && it <= price }
}
