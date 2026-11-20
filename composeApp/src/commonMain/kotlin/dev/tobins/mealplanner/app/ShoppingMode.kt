package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.shopping.leftToBuyGrams
import dev.tobins.mealplanner.shopping.nothingToBuy
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlin.math.ceil

/**
 * What a ticked row needs beyond what was bought: [grams] of weight or [pieces], with what was
 * bought then ([boughtGrams] or [boughtPieces]) for the row to say. Weight when the weight grew,
 * pieces otherwise.
 */
data class MoreNeeded(val grams: Double? = null, val boughtGrams: Double? = null, val pieces: Int? = null, val boughtPieces: Int? = null)

/** What a rounding step is: the weight must grow by more than this share of what was bought, and by [MIN_MORE_GRAMS]. */
private const val MORE_SHARE = 0.05
private const val MIN_MORE_GRAMS = 25.0

/**
 * Whether the meals need more of this ticked row than was bought: the weight grew by more than 5 %
 * and at least 25 g, or the pieces (counted rounded up, as the list shows them) by at least one.
 * Never more than the meals still to cook need less the stock ([leftToBuyGrams]): a meal eaten since
 * was made with what was at home, so once its meals are done a row needs nothing more, whatever its
 * unit ([nothingToBuy]). Null for a row that is not ticked, one that remembers no bought amount (a
 * tick from before the server kept it: no claim) and one whose amount is within a rounding step.
 * The one rule behind the row, the progress line and the sections, so they never disagree.
 */
fun ShoppingItem.moreNeeded(): MoreNeeded? {
    // An own item has no amounts to compare.
    if (!checked || extraId != null || nothingToBuy()) return null
    val needed = grams ?: knownGrams
    val boughtWeight = boughtGrams
    if (needed != null && boughtWeight != null) {
        val more = minOf(needed - boughtWeight, leftToBuyGrams() ?: Double.MAX_VALUE)
        if (more >= MIN_MORE_GRAMS && more > MORE_SHARE * boughtWeight) return MoreNeeded(grams = more, boughtGrams = boughtWeight)
    }
    val boughtCount = boughtPieces
    if (pieces != null && boughtCount != null) {
        val need = ceil(pieces!! - 1e-9).toInt()
        val bought = ceil(boughtCount - 1e-9).toInt()
        if (need - bought >= 1) return MoreNeeded(pieces = need - bought, boughtPieces = bought)
    }
    return null
}

/** Ticked and nothing more needed since: what the row's checkbox shows, and what counts as ticked off in the progress line. */
val ShoppingItem.done: Boolean get() = checked && moreNeeded() == null

/** [done], or [covered]: nothing to buy for this row, so shopping mode's Done groups hold it. */
val ShoppingItem.nothingLeftToBuy: Boolean get() = done || covered

/** `600 g more (600 g bought)` or `2 more (3 bought)`. */
fun MoreNeeded.text(strings: Strings): String =
    if (grams != null) strings(Msg.MORE_NEEDED, weightText(grams, strings.locale), weightText(boughtGrams ?: 0.0, strings.locale))
    else strings(Msg.MORE_NEEDED, pieces ?: 0, boughtPieces ?: 0)

/**
 * One shop section of the list as the screen shows it. With shopping mode on, the items with
 * nothing left to buy ([nothingLeftToBuy]) sit in [done]; with it off [done] is empty and [open]
 * holds every item, in the server's order.
 */
data class ShoppingSectionView(val title: String, val open: List<ShoppingItem>, val done: List<ShoppingItem>) {
    /** True when shopping mode is on and nothing is left to buy in this section. */
    val finished: Boolean get() = open.isEmpty() && done.isNotEmpty()
}

/**
 * Groups [items] by section, keeping the order of the list (the route through the shop). With
 * [shoppingMode] the ones with nothing left to buy are split off so the rest stay together.
 */
fun shoppingSections(items: List<ShoppingItem>, shoppingMode: Boolean): List<ShoppingSectionView> =
    items.groupBy { it.category }.map { (title, group) ->
        if (shoppingMode) {
            val (done, open) = group.partition { it.nothingLeftToBuy }
            ShoppingSectionView(title, open, done)
        } else {
            ShoppingSectionView(title, group, emptyList())
        }
    }

/**
 * The progress of this week's trip: [shoppingProgress] of the list's own rows. The buy-ahead and
 * stock-up rows below it are advice for later, not part of the trip: ticking one never moves the
 * count, and an untouched one never keeps "all ticked off" from showing. The screen puts the line
 * under "For this week" when advice follows, so it says what it counts.
 */
fun shoppingProgress(list: ShoppingList, strings: Strings): String = shoppingProgress(list.items, strings)

/**
 * `12 of 43 · 3 sections to go`, or `All 43 ticked off` when nothing is left. A row the stock or the
 * meals done leave nothing to buy of ([covered]) is no item to buy, so it counts neither way.
 */
fun shoppingProgress(items: List<ShoppingItem>, strings: Strings): String {
    val toBuy = items.filterNot { it.covered }
    if (toBuy.isEmpty()) return strings(Msg.NOTHING_LEFT_TO_BUY)
    val ticked = toBuy.count { it.done }
    if (ticked == toBuy.size) return strings(Msg.ALL_TICKED_OFF, toBuy.size)
    val sectionsToGo = toBuy.filter { !it.done }.map { it.category }.toSet().size
    return strings.plural(Plural.SECTIONS_TO_GO, sectionsToGo, ticked, toBuy.size)
}

/**
 * `AH €1.99`, or `AH 1+1 gratis` when the store states no price: short enough for the row's edge.
 * An offer that starts after [today] says when on a second line, which the row's narrow edge
 * needs: `Lidl €1.79` and `from Thu`.
 */
fun Offer.badgeText(strings: Strings, today: String): String {
    // Some stores shout their deals in capitals; in a badge that is too loud.
    val text = "${store.shortName()} ${price?.let { formatEuro(it, strings.locale) } ?: deal?.takeIf { it.isNotBlank() }?.lowercase() ?: strings(Msg.BADGE_OFFER)}"
    return if (startsAfter(today)) "$text\n${startLabel(today, strings)}" else text
}

/** `Thu` for a day within the coming week, else `23 Nov`. */
private fun dayLabelFor(date: String, today: String, strings: Strings): String =
    if (daysBetween(today, date) < 7) strings.locale.dayNames[LocalDate.parse(date).dayOfWeek.isoDayNumber - 1] else dayMonthLabel(date, strings.locale)

/** `from Thu` for a start within the coming week, else `from 23 Nov`: the day an offer can first be had. */
fun Offer.startLabel(today: String, strings: Strings): String = strings(Msg.OFFER_FROM, dayLabelFor(validFrom, today, strings))

/** `until Sun`, or `until 30 Nov`: the last day an offer can be had, the way [startLabel] names its first. */
fun Offer.endLabel(today: String, strings: Strings): String = strings(Msg.OFFER_UNTIL, dayLabelFor(validUntil, today, strings))
