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

/** True when the list shows any advice, or a line saying it was hidden: then this week's part gets a title too. */
fun ShoppingList.hasAdvice(): Boolean = AdviceTexts.entries.any { it.items(this).isNotEmpty() || it.dismissed(this) }

/**
 * The advice items to show: all of them across the stores, or with a chosen [store] only those
 * whose preferred offer (among [shops]) is there. Advice is only worth following on offer, so
 * at another store it is left out rather than set apart like this week's items.
 */
fun adviceAt(items: List<ShoppingItem>, store: Store?, shops: Set<Store>): List<ShoppingItem> =
    if (store == null) items else items.filter { it.bestOffer(shops)?.store == store }

/** The hint under an advice group's title: what it is for, and what its offers save when that is known. */
fun adviceHint(texts: AdviceTexts, items: List<ShoppingItem>, shops: Set<Store>, strings: Strings): String =
    listOfNotNull(strings(texts.hint), savingOf(items, shops)?.let { strings(Msg.ADVICE_SAVING, formatEuro(it, strings.locale)) })
        .joinToString(" ")
