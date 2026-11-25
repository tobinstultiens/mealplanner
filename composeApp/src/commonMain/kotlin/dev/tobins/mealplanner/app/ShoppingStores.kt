package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.shopping.bestOffer
import dev.tobins.mealplanner.shopping.savingOffer
import dev.tobins.mealplanner.shopping.savingToday

/** The store as the app names it in short: `AH` rather than `Albert Heijn`. */
fun Store.shortName(): String = label.substringBefore(' ').let { if (it == "Albert") "AH" else it }

/** True when the offer starts after [today]: shown with its day, but not something to buy for yet. */
fun Offer.startsAfter(today: String): Boolean = validFrom > today

/**
 * The store a ticked row was bought at for its offer, as the server kept it with the tick
 * ([ShoppingItem.boughtOfferStore]), while that store is in [shops]. Null for a row not ticked (an
 * un-tick waiting to be sent included), one whose tick saved nothing or is not known to the server yet.
 */
private fun ShoppingItem.keptStore(shops: Set<Store>): Store? =
    boughtOfferStore?.takeIf { checked && boughtSaving != null && it in shops }

/**
 * The store whose group this item is in: the one a bought row was bought at for its offer
 * ([keptStore]), so that it stays there with its saving when the offer ends or another starts; else
 * that of its [savingOffer]. None for a row with nothing left to buy ([covered]), as there is nothing
 * to go there for.
 */
private fun ShoppingItem.groupStore(shops: Set<Store>, today: String): Store? =
    if (covered) null else keptStore(shops) ?: savingOffer(shops, today)?.store

/**
 * The offer a row shows as its chip. A row bought for an offer ([keptStore]) shows that store's offer
 * while it lasts and no chip once it has ended, never another store's offer of today, which it was
 * not bought for. Any other row shows its best offer.
 */
fun ShoppingItem.chipOffer(shops: Set<Store>, today: String): Offer? {
    val kept = keptStore(shops) ?: return bestOffer(shops, today)
    return bestOffer(setOf(kept), today)
}

/**
 * What this row adds to its group's saving ([savingOf]). A ticked row adds what it saved when it was
 * ticked ([ShoppingItem.boughtSaving]), so a meal eaten or an offer ended since does not take it back;
 * nothing when that store is no longer in [shops], as the row is then listed by today's offers. Any
 * other row, and a ticked one the server kept no saving of (a tick not sent yet, a server from before
 * it), adds what it saves today ([savingToday]).
 */
private fun ShoppingItem.savingCounted(shops: Set<Store>, today: String): Double? {
    val kept = boughtSaving?.takeIf { checked } ?: return savingToday(shops, today)
    return kept.takeIf { boughtOfferStore == null || boughtOfferStore in shops }
}

/**
 * The offers a tap on the row's chip shows ([DealDialog]): those at the account's [shops], one per store and deal, those
 * that start later included. Ended offers are left out, as they are everywhere on the list.
 */
fun ShoppingItem.dealOffers(shops: Set<Store>, today: String): List<Offer> =
    offers.filter { it.store in shops && it.validUntil >= today }.distinctBy { it.store to it.deal }

/** The deal in a few words ([shortText]), and the days of one that starts later: `Lidl: €1.00 from Thu until 25 Nov`. */
fun Offer.dealHeading(today: String, strings: Strings): String {
    val later = if (startsAfter(today)) " ${startLabel(today, strings)} ${endLabel(today, strings)}" else ""
    return "${shortText(strings)}$later"
}

/** The product as the store names it, with the pack size when the title does not already say it: `Chicken, 500 g`. */
fun Offer.productText(): String {
    val pack = packSize?.takeIf { size -> size.isNotBlank() && !title.contains(size, ignoreCase = true) }
    return listOfNotNull(title, pack).joinToString(", ")
}

/**
 * What [items] save together in [shops] on [today] ([savingCounted]): what the bought ones saved when
 * they were ticked, and what the others save when bought today. Null when no saving is known, or it is
 * less than a cent.
 */
fun savingOf(items: List<ShoppingItem>, shops: Set<Store>, today: String): Double? =
    items.mapNotNull { it.savingCounted(shops, today) }.sum().takeIf { it >= 0.005 }

/**
 * What this row of the list shows as it is ticked on [today], for the server to remember when the tick
 * reaches it late ([ShoppingCheck.Shown]): the weight and pieces it says, and what its offer saves at the
 * account's [shops] ([savingToday]), with the store of that offer when it saves anything.
 */
internal fun ShoppingItem.shownAtTick(shops: Set<Store>, today: String): ShoppingCheck.Shown {
    val saving = savingToday(shops, today) ?: 0.0
    return ShoppingCheck.Shown(
        grams = grams ?: knownGrams,
        pieces = pieces,
        saving = saving,
        savingStore = savingOffer(shops, today)?.store?.takeIf { saving > 0 },
        tickedOn = today,
    )
}

/** The items whose group is at [store]([groupStore]), and what they save ([savingOf]). */
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
 * Splits [items] by their store among the stores in [shops] ([groupStore]). Stores without
 * items get no group. Each item lands in exactly one group, in the order of the list.
 */
fun storeOverview(items: List<ShoppingItem>, shops: Set<Store>, today: String): StoreOverview {
    val (covered, toBuy) = items.partition { it.covered }
    val byStore = toBuy.groupBy { it.groupStore(shops, today) }
    return StoreOverview(
        stores = Store.entries.filter { it in shops }.mapNotNull { store -> byStore[store]?.let { StoreGroup(store, it, savingOf(it, shops, today)) } },
        anyStore = byStore[null].orEmpty(),
        covered = covered,
    )
}

/** An item that is cheaper at another store than the one chosen, with the [offer] that makes it so. */
data class CheaperElsewhere(val item: ShoppingItem, val offer: Offer?)

/**
 * The list for one visit to [store]: what can be bought there ([sections], in walking order)
 * and what is on offer elsewhere ([elsewhere]). Together they hold every item once. [saving] is
 * what the store's own offers save on them ([savingOf]): today, or when bought there.
 */
data class StoreVisit(
    val store: Store,
    val sections: List<ShoppingSectionView>,
    val elsewhere: List<CheaperElsewhere>,
    val saving: Double? = null,
)

/**
 * The visit to [store]: its own offer items and everything without an offer, grouped by shop
 * section as in [shoppingSections]; the items listed under another store in [shops] ([groupStore]:
 * its saving offer is there) are set apart as [StoreVisit.elsewhere], unless nothing of them is left
 * to buy. A row already bought is never "cheaper elsewhere": it stays in its section, with the chip
 * of the store it was bought at ([chipOffer]).
 */
fun storeVisit(items: List<ShoppingItem>, store: Store, shops: Set<Store>, today: String, shoppingMode: Boolean): StoreVisit {
    val (here, elsewhere) = items.partition { it.keptStore(shops) != null || (it.groupStore(shops, today) ?: store) == store }
    return StoreVisit(
        store,
        shoppingSections(here, shoppingMode),
        elsewhere.map { CheaperElsewhere(it, it.savingOffer(shops, today)) },
        // Items without a saving offer here add nothing, nor do rows bought for another store's offer.
        savingOf(here.filter { it.keptStore(shops).let { kept -> kept == null || kept == store } }, shops, today),
    )
}

/** `Jumbo €1,99`: where an item is cheaper and for how much; null without an [CheaperElsewhere.offer]. */
fun CheaperElsewhere.where(strings: Strings, today: String): String? = offer?.badgeText(strings, today)
