package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.Store

/** The store as the app names it in short: `AH` rather than `Albert Heijn`. */
fun Store.shortName(): String = label.substringBefore(' ').let { if (it == "Albert") "AH" else it }

/**
 * The preferred offer among those of the stores in [shops]; all stores by default. Which one is
 * the shared [dev.tobins.mealplanner.shopping.bestOffer]: the one that saves most.
 */
fun ShoppingItem.bestOffer(shops: Set<Store> = Store.entries.toSet()): Offer? =
    dev.tobins.mealplanner.shopping.bestOffer(offers.filter { it.store in shops })

/**
 * The offer that puts this item under a store: the preferred one when it is known to save
 * something. An item whose offers are only deals nobody could value (no share) is not claimed by
 * their store; it keeps its badge but is listed under any store.
 */
fun ShoppingItem.savingOffer(shops: Set<Store>): Offer? = bestOffer(shops)?.takeIf { it.paidShare != null }

/**
 * What buying this item with [offer] saves in euros: its regular price times the share the offer
 * takes off. The regular price is AH's, so at another store this is an estimate. Like the plan's
 * costs it assumes the deal applies to the amount needed (a "1+1 gratis" halves it even when one
 * pack would do). Null when the server could not work out either part.
 */
fun ShoppingItem.saving(offer: Offer): Double? {
    val regular = regularPrice ?: return null
    val paid = offer.paidShare ?: return null
    return regular * (1 - paid)
}

/**
 * What [items] save together when each is bought with its preferred offer in [shops]; null when
 * no saving is known, or it is less than a cent.
 */
fun savingOf(items: List<ShoppingItem>, shops: Set<Store>): Double? =
    items.mapNotNull { item -> item.bestOffer(shops)?.let(item::saving) }.sum().takeIf { it >= 0.005 }

/** The items whose saving offer ([savingOffer]) is at [store], and what those offers save ([savingOf]). */
data class StoreGroup(val store: Store, val items: List<ShoppingItem>, val saving: Double? = null) {
    /** `Jumbo · 5 items on offer`. */
    fun title(strings: Strings): String = strings.plural(Plural.STORE_ITEMS_ON_OFFER, items.size, store.shortName())
}

/**
 * The list split by where to buy: a group per store for what saves most there this week, and
 * [anyStore] for everything without an offer known to save at a store in use, which any shop sells.
 */
data class StoreOverview(val stores: List<StoreGroup>, val anyStore: List<ShoppingItem>)

/**
 * Splits [items] by their saving offer among the stores in [shops]. Stores without items
 * get no group. Each item lands in exactly one group, in the order of the list.
 */
fun storeOverview(items: List<ShoppingItem>, shops: Set<Store>): StoreOverview {
    val byStore = items.groupBy { it.savingOffer(shops)?.store }
    return StoreOverview(
        stores = Store.entries.filter { it in shops }.mapNotNull { store -> byStore[store]?.let { StoreGroup(store, it, savingOf(it, shops)) } },
        anyStore = byStore[null].orEmpty(),
    )
}

/** An item that is cheaper at another store than the one chosen, with the [offer] that makes it so. */
data class CheaperElsewhere(val item: ShoppingItem, val offer: Offer)

/**
 * The list for one visit to [store]: what can be bought there ([sections], in walking order)
 * and what is on offer elsewhere ([elsewhere]). Together they hold every item once. [saving] is
 * what the store's own offers save on them.
 */
data class StoreVisit(
    val store: Store,
    val sections: List<ShoppingSectionView>,
    val elsewhere: List<CheaperElsewhere>,
    val saving: Double? = null,
)

/**
 * The visit to [store]: its own offer items and everything without an offer, grouped by shop
 * section as in [shoppingSections]; the items whose saving offer is at another store in
 * [shops] are set apart as [StoreVisit.elsewhere].
 */
fun storeVisit(items: List<ShoppingItem>, store: Store, shops: Set<Store>, shoppingMode: Boolean): StoreVisit {
    val (here, elsewhere) = items.partition { (it.savingOffer(shops)?.store ?: store) == store }
    return StoreVisit(
        store,
        shoppingSections(here, shoppingMode),
        elsewhere.map { CheaperElsewhere(it, it.savingOffer(shops)!!) },
        // Items without a saving offer here add nothing.
        savingOf(here, shops),
    )
}

/** `Jumbo €1,99`: where an item is cheaper and for how much. */
fun CheaperElsewhere.where(strings: Strings): String = offer.badgeText(strings)
