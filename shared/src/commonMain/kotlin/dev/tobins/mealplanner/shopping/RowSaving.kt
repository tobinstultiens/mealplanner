package dev.tobins.mealplanner.shopping

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.pricing.PackSize
import dev.tobins.mealplanner.pricing.PacksToBuy

// What a row of the shopping list still needs bought, and what its offer saves on that: the rules behind
// the app's store headers. They live here rather than in the app so that the server, which keeps what a
// row saved when it is ticked (ShoppingItem.boughtSaving), works it out exactly as the header did.

/** True when the offer can be had on [today] (ISO date): it has started and not ended. */
fun Offer.validOn(today: String): Boolean = validFrom <= today && today <= validUntil

/**
 * The preferred offer among those of the stores in [shops]. Which one is [bestOffer]: the one that
 * saves most. Offers that ended before [today] are ignored. One that is valid today wins over any that
 * starts later, so the list never promises what the till will not give; only an item with nothing
 * valid today shows the best of its coming offers.
 */
fun ShoppingItem.bestOffer(shops: Set<Store>, today: String): Offer? {
    val notEnded = offers.filter { it.store in shops && it.validUntil >= today }
    return bestOffer(notEnded.filter { it.validOn(today) }) ?: bestOffer(notEnded)
}

/**
 * The offer that puts this item under a store: the preferred one when it is known to save
 * something. An item whose offers are only deals nobody could value (no share) is not claimed by
 * their store; it keeps its badge but is listed under any store.
 */
fun ShoppingItem.savingOffer(shops: Set<Store>, today: String): Offer? = bestOffer(shops, today)?.takeIf { it.paidShare != null }

/** Less than a gram still to buy is nothing to buy, as the server drops a stock once less than a gram is left. */
const val SCRAP_GRAMS = 1.0

/** No meal still to cook needs this row, whatever its unit: the server says the meals to come need exactly 0 ([ShoppingItem.toComeGrams]). */
val ShoppingItem.mealsDone: Boolean get() = toComeGrams == 0.0

/**
 * The weight still to buy of this row, ticked or not: what the meals to come need
 * ([ShoppingItem.toComeGrams]) less what is on hand ([ShoppingItem.onHandGrams]), at least 0; 0 when
 * no meal to come needs it. Null when either is not known. On a ticked row it doesn't count what
 * was bought, which only the tick knows: there it is only a ceiling (the app's "more" line) and the
 * saving's share.
 */
fun ShoppingItem.leftToBuyGrams(): Double? {
    val toCome = toComeGrams ?: return null
    if (toCome == 0.0) return 0.0
    val left = if (onHand) onHandGrams ?: return null else 0.0
    return (toCome - left).coerceAtLeast(0.0)
}

/**
 * Nothing of this row is left to buy, ticked or not: no meal to come needs it ([mealsDone]), or the
 * stock covers what they need to within [SCRAP_GRAMS]. The scrap only forgives what the stock leaves
 * over: a need of under a gram with nothing at home (a pinch of oregano) is still to buy.
 */
fun ShoppingItem.nothingToBuy(): Boolean {
    if (mealsDone) return true
    val toBuy = leftToBuyGrams() ?: return false
    return onHand && toBuy < SCRAP_GRAMS
}

/**
 * What to pick up for this item when bought as [offer]: [PackSize.toBuy] for the weight the recipes
 * need, or their pieces. What could not be weighed is not guessed at, so an item with other amounts
 * (or without a weight) gets nothing.
 */
fun ShoppingItem.packsToBuy(offer: Offer): PacksToBuy? = PackSize.toBuy(offer, grams, if (otherAmounts.isEmpty()) pieces else null)

/**
 * [packsToBuy] for what is left to buy ([leftToBuyGrams]), ticked or not: none when nothing is
 * ([nothingToBuy]); for that weight alone when it is less than the whole need, pieces then left out
 * as a weight says nothing about them; else for the whole need. What a saving checks a deal against
 * ([savingToday]).
 */
fun ShoppingItem.packsLeftToBuy(offer: Offer): PacksToBuy? {
    if (nothingToBuy()) return null
    val toBuy = leftToBuyGrams()
    if (toBuy == null || grams.let { it == null || it - toBuy < SCRAP_GRAMS }) return packsToBuy(offer)
    return PackSize.toBuy(offer, toBuy, null)
}

/**
 * What buying this item with [offer] saves in euros: its regular price times the share the offer
 * takes off, for the part of the need still to buy ([leftToBuyGrams], ticked or not; nothing for a
 * row the stock or the meals done cover). The regular price is AH's, so at another store this is an
 * estimate. It assumes the deal applies to that amount, which [savingToday] checks against the packs.
 * Null when the server could not work out either part.
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
 * What this item saves when bought on [today] with its preferred offer in [shops] ([saving]); null
 * when nothing is known to be saved. An offer that only starts later saves nothing today, although its
 * badge stays on the row. Nor does a deal that wants more packs than what is left to buy needs ("1+1
 * gratis" for one pack): the saving would only be had by buying more than needed.
 */
fun ShoppingItem.savingToday(shops: Set<Store>, today: String): Double? =
    bestOffer(shops, today)?.takeIf { it.validOn(today) && packsLeftToBuy(it)?.dealFits != false }?.let(::saving)
