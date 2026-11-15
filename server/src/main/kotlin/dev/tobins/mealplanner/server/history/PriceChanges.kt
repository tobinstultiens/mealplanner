package dev.tobins.mealplanner.server.history

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import java.time.LocalDate

/**
 * What one refresh does to the price history: the periods it [continued] (by row id, with what it saw)
 * and the ones it [started]. An open period in neither is over; nothing is written for it.
 */
data class Changes<T>(val continued: List<Pair<Long, T>>, val started: List<T>)

/**
 * Compares what a refresh [saw] with the [open] periods, the ones the previous recording saw, by an
 * [identity] that names the product and holds everything that counts as a change. What is identical to an
 * open period continues it; anything else starts a new period: a changed price or deal, a product not seen
 * before, and one that comes back after a refresh without it (it was not open), so the gap shows.
 *
 * Sightings with the same identity are one, the first: the caller merges what differs between them. Should
 * two open periods share an identity, the later one (highest id) is continued.
 */
fun <T, I> changes(open: List<Pair<Long, I>>, saw: List<T>, identity: (T) -> I): Changes<T> {
    val openByIdentity = open.groupBy({ it.second }, { it.first }).mapValues { (_, ids) -> ids.max() }
    val continued = mutableListOf<Pair<Long, T>>()
    val started = mutableListOf<T>()
    for (sighting in saw.distinctBy(identity)) {
        val id = openByIdentity[identity(sighting)]
        if (id != null) continued += id to sighting else started += sighting
    }
    return Changes(continued, started)
}

/**
 * Text as the history compares it: lower case, runs of spaces as one, a decimal comma as a point. Stores
 * change case and spacing ("VOOR 2.99", "Voor 2.99") without changing what they offer.
 */
fun normalised(text: String?): String = text.orEmpty().lowercase().replace(',', '.').replace(whitespace, " ").trim()

private val whitespace = Regex("""\s+""")

/** One product at one store over time: the store, its title and its pack size, [normalised]. */
data class ProductKey(val store: Store, val title: String, val pack: String) {
    companion object {
        fun of(offer: Offer) = ProductKey(offer.store, normalised(offer.title), normalised(offer.packSize))
    }
}

/**
 * An offer as the history compares it: the [product] and how it is offered. The pack size is part of the
 * product, so another pack is another product, and a new row either way.
 */
data class OfferIdentity(val product: ProductKey, val price: Double?, val originalPrice: Double?, val deal: String)

/** An offer one refresh saw, as the history compares it. */
data class OfferSighting(val offer: Offer) {
    val identity = OfferIdentity(ProductKey.of(offer), offer.price, offer.originalPrice, normalised(offer.deal))

    companion object {
        /**
         * What a store's refresh on [today] saw, for the history. Offers that have already ended are left out:
         * a page that still shows them does not make them run longer. The same offer listed twice (Jumbo shows
         * this week's and next week's) is one sighting valid over both; the same product offered two ways is two.
         */
        fun of(offers: List<Offer>, today: LocalDate): List<OfferSighting> =
            offers.filter { LocalDate.parse(it.validUntil) >= today }
                .groupBy { OfferSighting(it).identity }.values
                .map { same -> OfferSighting(same.first().copy(validFrom = same.minOf { it.validFrom }, validUntil = same.maxOf { it.validUntil })) }
    }
}

/** A regular price as one lookup found it for the ingredient [name]. */
data class RegularPriceSighting(
    val name: String,
    val store: Store,
    val product: String,
    val packSize: String?,
    val packPrice: Double?,
    val eurosPerKg: Double,
) {
    /** Another product, pack or price is a change. */
    val identity get() = listOf(normalised(product), normalised(packSize), packPrice, eurosPerKg)
}
