package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.Store

/** The store as the app names it in short: `AH` rather than `Albert Heijn`. */
fun Store.shortName(): String = label.substringBefore(' ').let { if (it == "Albert") "AH" else it }

/**
 * The offer to prefer among [offers] of one item. Offers that state a price are compared by
 * that price and the cheapest wins; a deal that states none ("1+1 gratis") can't be compared
 * with a price, so any priced offer beats it. When nothing is priced, or prices tie, the
 * server's order decides, so the first offer wins. Null without offers.
 */
fun cheapestOffer(offers: List<Offer>): Offer? =
    offers.filter { it.price != null }.minByOrNull { it.price!! } ?: offers.firstOrNull()

/** The preferred offer among those of the stores in [shops]; all stores by default. */
fun ShoppingItem.bestOffer(shops: Set<Store> = Store.entries.toSet()): Offer? =
    cheapestOffer(offers.filter { it.store in shops })

/** The items whose preferred offer is at [store]. */
data class StoreGroup(val store: Store, val items: List<ShoppingItem>) {
    /** `Jumbo · 5 items on offer`. */
    val title: String get() = "${store.shortName()} · ${items.size} ${if (items.size == 1) "item" else "items"} on offer"
}

/**
 * The list split by where to buy: a group per store for what is cheapest there this week, and
 * [anyStore] for everything without an offer at a store in use, which any shop sells.
 */
data class StoreOverview(val stores: List<StoreGroup>, val anyStore: List<ShoppingItem>)

/**
 * Splits [items] by their preferred offer among the stores in [shops]. Stores without items
 * get no group. Each item lands in exactly one group, in the order of the list.
 */
fun storeOverview(items: List<ShoppingItem>, shops: Set<Store>): StoreOverview {
    val byStore = items.groupBy { it.bestOffer(shops)?.store }
    return StoreOverview(
        stores = Store.entries.filter { it in shops }.mapNotNull { store -> byStore[store]?.let { StoreGroup(store, it) } },
        anyStore = byStore[null].orEmpty(),
    )
}

/** An item that is cheaper at another store than the one chosen, with the [offer] that makes it so. */
data class CheaperElsewhere(val item: ShoppingItem, val offer: Offer)

/**
 * The list for one visit to [store]: what can be bought there ([sections], in walking order)
 * and what is on offer elsewhere ([elsewhere]). Together they hold every item once.
 */
data class StoreVisit(val store: Store, val sections: List<ShoppingSectionView>, val elsewhere: List<CheaperElsewhere>)

/**
 * The visit to [store]: its own offer items and everything without an offer, grouped by shop
 * section as in [shoppingSections]; the items whose preferred offer is at another store in
 * [shops] are set apart as [StoreVisit.elsewhere].
 */
fun storeVisit(items: List<ShoppingItem>, store: Store, shops: Set<Store>, shoppingMode: Boolean): StoreVisit {
    val (here, elsewhere) = items.partition { (it.bestOffer(shops)?.store ?: store) == store }
    return StoreVisit(
        store,
        shoppingSections(here, shoppingMode),
        elsewhere.map { CheaperElsewhere(it, it.bestOffer(shops)!!) },
    )
}

/** `Jumbo €1,99`: where an item is cheaper and for how much. */
fun CheaperElsewhere.where(locale: FormatLocale = FormatLocale.device): String = offer.badgeText(locale)
