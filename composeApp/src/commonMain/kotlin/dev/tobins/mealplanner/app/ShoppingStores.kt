package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.Store

/** The store as the app names it in short: `AH` rather than `Albert Heijn`. */
fun Store.shortName(): String = label.substringBefore(' ').let { if (it == "Albert") "AH" else it }

/** True when the offer can be had on [today] (ISO date): it has started and not ended. */
fun Offer.validOn(today: String): Boolean = validFrom <= today && today <= validUntil

/** True when the offer starts after [today]: shown with its day, but not something to buy for yet. */
fun Offer.startsAfter(today: String): Boolean = validFrom > today

/**
 * The preferred offer among those of the stores in [shops]. Which one is the shared
 * [dev.tobins.mealplanner.shopping.bestOffer]: the one that saves most. Offers that ended before
 * [today] are ignored. One that is valid today wins over any that starts later, so the list never
 * promises what the till will not give; only an item with nothing valid today shows the best of
 * its coming offers (with the day it starts, see [badgeText]).
 */
fun ShoppingItem.bestOffer(shops: Set<Store>, today: String): Offer? {
    val notEnded = offers.filter { it.store in shops && it.validUntil >= today }
    return dev.tobins.mealplanner.shopping.bestOffer(notEnded.filter { it.validOn(today) })
        ?: dev.tobins.mealplanner.shopping.bestOffer(notEnded)
}

/**
 * The offer that puts this item under a store: the preferred one when it is known to save
 * something. An item whose offers are only deals nobody could value (no share) is not claimed by
 * their store; it keeps its badge but is listed under any store.
 */
fun ShoppingItem.savingOffer(shops: Set<Store>, today: String): Offer? = bestOffer(shops, today)?.takeIf { it.paidShare != null }

/**
 * The store whose group this item is in: that of its [savingOffer], but none for a row with nothing
 * left to buy ([covered]), as there is nothing to go there for.
 */
private fun ShoppingItem.groupStore(shops: Set<Store>, today: String): Store? = if (covered) null else savingOffer(shops, today)?.store

/**
 * The full deals of the item's offers in [shops] for the row's snackbar, one per line: those valid
 * today as they are, those that start later with their day. Ended offers are left out, as they
 * are everywhere on the list.
 */
fun ShoppingItem.dealText(shops: Set<Store>, today: String, strings: Strings): String =
    offers.filter { it.store in shops && it.validUntil >= today }.distinctBy { it.store to it.deal }.joinToString("\n") {
        val later = if (it.startsAfter(today)) " ${it.startLabel(today, strings)}" else ""
        // The pack size is added when the title does not already say it.
        val pack = it.packSize?.takeIf { size -> size.isNotBlank() && !it.title.contains(size, ignoreCase = true) }
        "${it.shortText(strings)}$later (${listOfNotNull(it.title, pack).joinToString(", ")})"
    }

/**
 * What buying this item with [offer] saves in euros: its regular price times the share the offer
 * takes off, for the part of the need still to buy ([leftToBuyGrams], ticked or not, so ticking a
 * row changes nothing; nothing for a row the stock or the meals done cover). The regular
 * price is AH's, so at another store this is an estimate. It assumes the deal applies to that
 * amount, which [savingOf] checks against the packs. Null when the server could not work out
 * either part.
 */
fun ShoppingItem.saving(offer: Offer): Double? {
    val regular = regularPrice ?: return null
    val paid = offer.paidShare ?: return null
    return regular * shareToBuy() * (1 - paid)
}

/** The share of the need ([ShoppingItem.grams]) still to buy ([leftToBuyGrams]); all of it when that is not known, as for advice. */
private fun ShoppingItem.shareToBuy(): Double {
    val toBuy = leftToBuyGrams() ?: return 1.0
    val need = grams?.takeIf { it > 0 } ?: return 1.0
    return (toBuy / need).coerceIn(0.0, 1.0)
}

/**
 * What [items] save together when each is bought with its preferred offer in [shops] on [today];
 * null when no saving is known, or it is less than a cent. An offer that only starts later saves
 * nothing today, so it is left out of the sum although its badge stays on the row. So is a deal
 * that wants more packs than what is left to buy needs ("1+1 gratis" for one pack): the saving
 * would only be had by buying more than needed.
 */
fun savingOf(items: List<ShoppingItem>, shops: Set<Store>, today: String): Double? =
    items.mapNotNull { item -> item.bestOffer(shops, today)?.takeIf { it.validOn(today) && item.packsLeftToBuy(it)?.dealFits != false }?.let(item::saving) }.sum().takeIf { it >= 0.005 }

/** The items whose saving offer ([savingOffer]) is at [store], and what those offers save ([savingOf]). */
data class StoreGroup(val store: Store, val items: List<ShoppingItem>, val saving: Double? = null) {
    /** `Jumbo · 5 items on offer`. */
    fun title(strings: Strings): String = strings.plural(Plural.STORE_ITEMS_ON_OFFER, items.size, store.shortName())
}

/**
 * The list split by where to buy: a group per store for what saves most there this week, [anyStore]
 * for everything without an offer known to save at a store in use, which any shop sells, and
 * [covered] for the rows with nothing left to buy. Those are in neither, so the groups' counts add
 * up to what the progress line counts ([shoppingProgress]).
 */
data class StoreOverview(val stores: List<StoreGroup>, val anyStore: List<ShoppingItem>, val covered: List<ShoppingItem> = emptyList())

/**
 * Splits [items] by their saving offer among the stores in [shops] ([groupStore]). Stores without
 * items get no group. Each item lands in exactly one group, in the order of the list.
 */
fun storeOverview(items: List<ShoppingItem>, shops: Set<Store>, today: String): StoreOverview {
    val (covered, toBuy) = items.partition { it.covered }
    val byStore = toBuy.groupBy { it.savingOffer(shops, today)?.store }
    return StoreOverview(
        stores = Store.entries.filter { it in shops }.mapNotNull { store -> byStore[store]?.let { StoreGroup(store, it, savingOf(it, shops, today)) } },
        anyStore = byStore[null].orEmpty(),
        covered = covered,
    )
}

/** An item that is cheaper at another store than the one chosen, with the [offer] that makes it so. */
data class CheaperElsewhere(val item: ShoppingItem, val offer: Offer)

/**
 * The list for one visit to [store]: what can be bought there ([sections], in walking order)
 * and what is on offer elsewhere ([elsewhere]). Together they hold every item once. [saving] is
 * what the store's own offers save on them today.
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
 * [shops] are set apart as [StoreVisit.elsewhere], unless nothing of them is left to buy.
 */
fun storeVisit(items: List<ShoppingItem>, store: Store, shops: Set<Store>, today: String, shoppingMode: Boolean): StoreVisit {
    val (here, elsewhere) = items.partition { (it.groupStore(shops, today) ?: store) == store }
    return StoreVisit(
        store,
        shoppingSections(here, shoppingMode),
        elsewhere.map { CheaperElsewhere(it, it.savingOffer(shops, today)!!) },
        // Items without a saving offer here add nothing.
        savingOf(here, shops, today),
    )
}

/** `Jumbo €1,99`: where an item is cheaper and for how much. */
fun CheaperElsewhere.where(strings: Strings, today: String): String = offer.badgeText(strings, today)
