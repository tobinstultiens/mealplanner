package dev.tobins.mealplanner.api

import kotlinx.serialization.Serializable

enum class Store(val label: String) {
    AH("Albert Heijn"),
    JUMBO("Jumbo"),
    LIDL("Lidl"),
}

/** One weekly offer as a store publishes it. */
@Serializable
data class Offer(
    val store: Store,
    val title: String,
    /** The deal as the store words it: "25% korting", "1+1 gratis", "voor 3,49". */
    val deal: String?,
    /** Offer price of one item, when the store states one. */
    val price: Double? = null,
    /** Price before the offer, when known. */
    val originalPrice: Double? = null,
    /** Pack size as written by the store, e.g. "500 g". */
    val packSize: String? = null,
    /** The store's own category, when it gives one. */
    val category: String? = null,
    /** ISO dates, inclusive. */
    val validFrom: String,
    val validUntil: String,
    val url: String? = null,
    /** The catalogue ingredient this offer is for, when it matches one. Filled in by the server. */
    val ingredientId: Long? = null,
    val ingredientName: String? = null,
)

/** How the last fetch for one store went. */
@Serializable
data class OfferSourceStatus(
    val store: Store,
    /** ISO instant of the last successful fetch; null if none succeeded yet. */
    val fetchedAt: String?,
    val offerCount: Int,
    /** Message of the last failed fetch, cleared by a successful one. */
    val error: String?,
)

@Serializable
data class OffersState(
    /** Offers valid on the requested date. */
    val offers: List<Offer>,
    val sources: List<OfferSourceStatus>,
)
