package dev.tobins.mealplanner.shopping

import dev.tobins.mealplanner.api.Offer

/**
 * The offer to prefer among [offers] of one item: the one that saves most on the amount needed.
 *
 * 1. Of the offers with a [paidShare][Offer.paidShare] the lowest share wins. The share is of the
 *    amount needed, so a small pack on a deeper deal does not beat a big one that saves more.
 * 2. Offers without a share come after all of those: one that states a price before one with only a
 *    deal text, as a price at least says what the item costs.
 * 3. Ties keep the order of [offers], the server's.
 *
 * Null without offers. The app and the server both use this, so the badge, the picture, the store
 * an item is listed under and the buy-ahead advice name the same offer.
 */
fun bestOffer(offers: List<Offer>): Offer? =
    offers.withIndex().minWithOrNull(compareBy({ it.value.paidShare ?: Double.MAX_VALUE }, { if (it.value.price != null) 0 else 1 }, { it.index }))?.value
