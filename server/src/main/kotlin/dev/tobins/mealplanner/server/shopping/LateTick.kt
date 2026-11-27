package dev.tobins.mealplanner.server.shopping

import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.Store
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
 * The day the phone ticked the row, as the server's days go: [ShoppingCheck.Shown.tickedOn] when it is a valid
 * day at most [ShoppingCheck.MAX_DAYS_LATE] days before [today] and at most [ShoppingCheck.MAX_DAYS_AHEAD] day
 * after it, null otherwise. A phone whose clock runs ahead (a time zone, a minute) dates a tick of today
 * as tomorrow, so such a day is the server's [today]; a day further ahead is not a clock but an invention.
 */
internal fun ShoppingCheck.Shown.tickDay(today: LocalDate): LocalDate? {
    val day = tickedOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    if (ChronoUnit.DAYS.between(today, day) > ShoppingCheck.MAX_DAYS_AHEAD) return null
    return minOf(day, today).takeIf { ChronoUnit.DAYS.between(it, today) <= ShoppingCheck.MAX_DAYS_LATE }
}

/**
 * The most the phone's saving for [row] may be: the row's regular price (what its whole need costs, so a
 * row without one saved nothing known) for the amount the phone showed ([gramsFor], else [piecesFor]) in
 * proportion to what the row says now, so that a need that shrank since the tick does not cut a real saving
 * down, and one that grew does not let it grow. The phone's amount is itself bounded by [gramsFor] and
 * [piecesFor]; without one the cap is the price of the row as it is.
 */
private fun ShoppingCheck.Shown.cap(row: ShoppingItem): Double? {
    val price = row.regularPrice ?: return null
    val byWeight = gramsFor(row)?.let { sent -> row.weighed?.takeIf { it > 0 }?.let { now -> sent / now } }
    val byPieces = piecesFor(row)?.let { sent -> row.pieces?.takeIf { it > 0 }?.let { now -> sent / now } }
    return price * (byWeight ?: byPieces ?: 1.0)
}

/**
 * The saving the phone counted at the tick, when it is to be believed: the tick is dated ([tickDay]) on an
 * earlier day than [today], as a tick of today (also one the phone's clock dated a day ahead) has its offer
 * on the server's list still and the server works it out; and the saving is from 0 to the [cap]. Null otherwise.
 */
internal fun ShoppingCheck.Shown.savingFor(row: ShoppingItem, today: LocalDate): Double? {
    val day = tickDay(today) ?: return null
    if (day >= today) return null
    val cap = cap(row) ?: return null
    return saving?.takeIf { it.isFinite() && it >= 0 && it <= cap }
}

/**
 * The store of the offer the phone's saving was made with, when it is to be believed: one of the account's
 * stores ([inUse]) that was recorded with an offer for this [row]'s ingredient valid around the day of the
 * tick ([offered], asked for the days from the day before to the day after, as the phone's clock may be off by one).
 * Null otherwise; the saving then stays without a store (see [savingFor]) rather than none, since its amount
 * is bounded by the row all the same and only the store header it is listed under is unknown.
 */
internal fun ShoppingCheck.Shown.storeFor(
    row: ShoppingItem,
    today: LocalDate,
    inUse: Set<Store>,
    offered: (Store, String, LocalDate, LocalDate) -> Boolean,
): Store? {
    val day = tickDay(today) ?: return null
    val store = savingStore?.takeIf { it in inUse } ?: return null
    return store.takeIf { offered(it, row.name, day.minusDays(1), day.plusDays(1)) }
}
