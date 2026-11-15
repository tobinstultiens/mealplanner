package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Store

/** The texts of one advice group: its title, what it is for, and the line shown while it is hidden. */
enum class AdviceTexts(val group: ShoppingAdviceGroup, val title: Msg, val hint: Msg, val hidden: Msg) {
    BUY_AHEAD(ShoppingAdviceGroup.BUY_AHEAD, Msg.BUY_AHEAD_TITLE, Msg.BUY_AHEAD_HINT, Msg.BUY_AHEAD_HIDDEN),
    STOCK_UP(ShoppingAdviceGroup.STOCK_UP, Msg.STOCK_UP_TITLE, Msg.STOCK_UP_HINT, Msg.STOCK_UP_HIDDEN),
    ;

    fun items(list: ShoppingList): List<ShoppingItem> = when (group) {
        ShoppingAdviceGroup.BUY_AHEAD -> list.buyAhead
        ShoppingAdviceGroup.STOCK_UP -> list.stockUp
    }

    fun dismissed(list: ShoppingList): Boolean = when (group) {
        ShoppingAdviceGroup.BUY_AHEAD -> list.buyAheadDismissed
        ShoppingAdviceGroup.STOCK_UP -> list.stockUpDismissed
    }
}

/**
 * The advice items to show: those with a preferred offer among [shops], or with a chosen [store]
 * only those whose offer is there. Advice is only worth following on offer, so an item without
 * one (say its store was switched off since the list was made) is left out in every view, and at
 * another store it is left out rather than set apart like this week's items.
 */
fun adviceAt(items: List<ShoppingItem>, store: Store?, shops: Set<Store>, today: String): List<ShoppingItem> =
    items.filter { item -> item.bestOffer(shops, today)?.let { store == null || it.store == store } == true }

/**
 * True when an advice group follows this week's part in the view for [store]: one with items to
 * show, or a line saying it was hidden. Only then does this week's part get a title.
 */
fun ShoppingList.hasAdvice(store: Store?, shops: Set<Store>, today: String): Boolean =
    AdviceTexts.entries.any { it.dismissed(this) || adviceAt(it.items(this), store, shops, today).isNotEmpty() }

/** The hint under an advice group's title: what it is for, and what its offers save when that is known. */
fun adviceHint(texts: AdviceTexts, items: List<ShoppingItem>, shops: Set<Store>, today: String, strings: Strings): String =
    listOfNotNull(strings(texts.hint), savingOf(items, shops, today)?.let { strings(Msg.ADVICE_SAVING, formatEuro(it, strings.locale)) })
        .joinToString(" ")
