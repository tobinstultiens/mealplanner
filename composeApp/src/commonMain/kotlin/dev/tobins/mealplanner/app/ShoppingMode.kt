package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem

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
            val (done, open) = group.partition { it.checked }
            ShoppingSectionView(title, open, done)
        } else {
            ShoppingSectionView(title, group, emptyList())
        }
    }

/** `12 of 43 · 3 sections to go`, or `All 43 ticked off` when nothing is left. */
fun shoppingProgress(items: List<ShoppingItem>): String {
    val ticked = items.count { it.checked }
    if (ticked == items.size) return "All ${items.size} ticked off"
    val sectionsToGo = items.filter { !it.checked }.map { it.category }.toSet().size
    return "$ticked of ${items.size} · $sectionsToGo ${if (sectionsToGo == 1) "section" else "sections"} to go"
}

/** The cheapest offer that states a price; when none does, the first one. Null without offers. */
fun ShoppingItem.bestOffer(): Offer? = offers.filter { it.price != null }.minByOrNull { it.price!! } ?: offers.firstOrNull()

/** `AH €1.99`, or `AH 1+1 gratis` when the store states no price: short enough for the row's edge. */
fun Offer.badgeText(locale: FormatLocale = FormatLocale.device): String {
    val shop = store.label.substringBefore(' ').let { if (it == "Albert") "AH" else it }
    return "$shop ${price?.let { formatEuro(it, locale) } ?: deal?.takeIf { it.isNotBlank() } ?: "offer"}"
}
