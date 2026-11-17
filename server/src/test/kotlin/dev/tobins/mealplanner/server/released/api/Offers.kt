// Frozen from v0.1.5 (32d25e8a4073a3eed02ca4fd44a7b98461199be5) by scripts/freeze-api.sh. Do not edit: freeze again after a release.
package dev.tobins.mealplanner.released.api

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
    /**
     * The store's picture of the product, at least 200 px wide where the store offers a choice.
     * Always https on one of the stores' own image hosts (docs/data-sources.md, "Product pictures").
     */
    val imageUrl: String? = null,
    /**
     * The share of the ingredient's regular price still paid with this offer: 0.75 for "25% korting",
     * 0.5 for "1+1 gratis" ([dev.tobins.mealplanner.pricing.Discounts.factor]). The regular price is
     * AH's, so for another store's offer this is an estimate. Filled in by the server on shopping
     * list items only; null when the deal can't be worked out or saves nothing.
     */
    val paidShare: Double? = null,
)

/**
 * The stores an account shops at (`GET` and `PUT /api/shops`). Offers at other stores don't count
 * when the server plans meals or costs them.
 */
@Serializable
data class ShopsSetting(
    /** Null when the account never chose, which means every store. Never empty. */
    val stores: List<Store>? = null,
) {
    /** The stores whose offers count: all of them until the account chose. */
    fun storesInUse(): Set<Store> = stores?.toSet()?.ifEmpty { null } ?: Store.entries.toSet()
}

/** How the last fetch for one store went. */
@Serializable
data class OfferSourceStatus(
    val store: Store,
    /** ISO instant of the last successful fetch; null if none succeeded yet. */
    val fetchedAt: String?,
    val offerCount: Int,
    /** Message of the last failed fetch, cleared by a successful one. */
    val error: String?,
    /**
     * A warning about the stored offers, in English, when they look stale although the fetch
     * worked (today only Lidl's page, see docs/data-sources.md). Null when there is none. It is
     * always in the response, so apps up to v0.1.1, which reject unknown fields, cannot read
     * the offers any more.
     */
    val warning: String? = null,
)

@Serializable
data class OffersState(
    /** Offers valid on the requested date. */
    val offers: List<Offer>,
    val sources: List<OfferSourceStatus>,
)

/** The regular price used as the baseline for an ingredient, from a store's product search. */
@Serializable
data class IngredientPrice(
    val ingredientId: Long,
    val ingredientName: String,
    val store: Store,
    /** The product the price was taken from; "Set by hand" for a price entered in the catalogue. */
    val product: String,
    val packSize: String? = null,
    val packPrice: Double? = null,
    val eurosPerKg: Double,
)

@Serializable
data class PricesState(
    val prices: List<IngredientPrice>,
    /** Ingredients no product was found for; costed at a typical price. */
    val unpriced: List<String>,
    /** ISO instant of the last completed lookup; null if none ran yet. */
    val fetchedAt: String?,
    /** True while a lookup is running (it takes a few minutes). */
    val refreshing: Boolean = false,
)
