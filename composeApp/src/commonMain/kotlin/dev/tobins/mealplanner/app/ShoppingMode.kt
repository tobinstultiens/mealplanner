package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem
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
 * Null for a row that is not ticked, one that remembers no bought amount (a tick from before the
 * server kept it: no claim) and one whose amount is within a rounding step. The one rule behind
 * the row, the progress line and the sections, so they never disagree.
 */
fun ShoppingItem.moreNeeded(): MoreNeeded? {
    // An own item has no amounts to compare.
    if (!checked || extraId != null) return null
    val needed = grams ?: knownGrams
    val boughtWeight = boughtGrams
    if (needed != null && boughtWeight != null) {
        val more = needed - boughtWeight
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

/** Ticked and nothing more needed since: what counts as done in the progress line and the Done groups. */
val ShoppingItem.done: Boolean get() = checked && moreNeeded() == null

/** `600 g more (600 g bought)` or `2 more (3 bought)`. */
fun MoreNeeded.text(strings: Strings): String =
    if (grams != null) strings(Msg.MORE_NEEDED, weightText(grams, strings.locale), weightText(boughtGrams ?: 0.0, strings.locale))
    else strings(Msg.MORE_NEEDED, pieces ?: 0, boughtPieces ?: 0)

/**
 * One shop section of the list as the screen shows it. With shopping mode on, ticked items sit
 * in [done]; with it off [done] is empty and [open] holds every item, in the server's order.
 */
data class ShoppingSectionView(val title: String, val open: List<ShoppingItem>, val done: List<ShoppingItem>) {
    /** True when shopping mode is on and nothing is left to buy in this section. */
    val finished: Boolean get() = open.isEmpty() && done.isNotEmpty()
}

/**
 * Groups [items] by section, keeping the order of the list (the route through the shop). With
 * [shoppingMode] the ticked ones are split off so the rest stay together.
 */
fun shoppingSections(items: List<ShoppingItem>, shoppingMode: Boolean): List<ShoppingSectionView> =
    items.groupBy { it.category }.map { (title, group) ->
        if (shoppingMode) {
            val (done, open) = group.partition { it.done }
            ShoppingSectionView(title, open, done)
        } else {
            ShoppingSectionView(title, group, emptyList())
        }
    }

/** `12 of 43 · 3 sections to go`, or `All 43 ticked off` when nothing is left. */
fun shoppingProgress(items: List<ShoppingItem>, strings: Strings): String {
    val ticked = items.count { it.done }
    if (ticked == items.size) return strings(Msg.ALL_TICKED_OFF, items.size)
    val sectionsToGo = items.filter { !it.done }.map { it.category }.toSet().size
    return strings.plural(Plural.SECTIONS_TO_GO, sectionsToGo, ticked, items.size)
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

/** `from Thu` for a start within the coming week, else `from 23 Nov`: the day an offer can first be had. */
fun Offer.startLabel(today: String, strings: Strings): String {
    val day = if (daysBetween(today, validFrom) < 7) strings.locale.dayNames[LocalDate.parse(validFrom).dayOfWeek.isoDayNumber - 1] else dayMonthLabel(validFrom, strings.locale)
    return strings(Msg.OFFER_FROM, day)
}
