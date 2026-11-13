package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientPrice
import dev.tobins.mealplanner.api.PricesState
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.pricing.Discounts
import dev.tobins.mealplanner.pricing.PackSize
import dev.tobins.mealplanner.pricing.Pricing
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.offers.OfferMatcher
import dev.tobins.mealplanner.server.prices.AhAisles
import dev.tobins.mealplanner.server.prices.PriceSource
import dev.tobins.mealplanner.server.prices.StoreProduct
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Regular prices per ingredient, looked up in a store's product search (AH): the baseline
 * that offers are measured against and that recipes are costed with. A price per kg set
 * by hand in the catalogue wins over a looked-up one.
 */
class PriceRepository(
    private val db: Database,
    private val source: PriceSource?,
    private val ingredients: IngredientRepository,
    private val offers: OfferRepository,
) {
    private val q = db.priceQueries
    private val log = LoggerFactory.getLogger(PriceRepository::class.java)
    private val refreshing = AtomicBoolean(false)

    fun state(): PricesState {
        val looked = q.selectPrices().executeAsList().map {
            IngredientPrice(it.ingredientId, it.ingredientName, Store.valueOf(it.store), it.product, it.packSize, it.packPrice, it.eurosPerKg)
        }.associateBy { it.ingredientId }
        val all = priceable()
        val byHand = all.mapNotNull { i -> i.pricePerKg?.let { IngredientPrice(i.id, i.name, Store.AH, BY_HAND, eurosPerKg = it) } }
        val prices = (looked + byHand.associateBy { it.ingredientId }).values.sortedBy { it.ingredientName.lowercase() }
        val priced = prices.mapTo(HashSet()) { it.ingredientId }
        return PricesState(
            prices = prices,
            unpriced = all.filter { it.id !in priced }.map { it.name },
            fetchedAt = db.foodQueries.selectMeta(FETCHED_KEY).executeAsOneOrNull(),
            refreshing = refreshing.get(),
        )
    }

    /** Euros per kg by ingredient id. */
    fun perKg(): Map<Long, Double> =
        q.selectPrices().executeAsList().associate { it.ingredientId to it.eurosPerKg } +
            ingredients.list().mapNotNull { i -> i.pricePerKg?.let { i.id to it } }

    /** Prices with the discounts of offers valid at some point between [from] and [until]. */
    fun pricing(from: LocalDate, until: LocalDate): Pricing {
        val perKg = perKg()
        val byId = ingredients.list().associateBy { it.id }
        val discounts = offers.byIngredient(from, until, ingredients.matchTerms()).mapNotNull { (id, onOffer) ->
            val ingredient = byId[id] ?: return@mapNotNull null
            onOffer.mapNotNull { offer ->
                val packGrams = PackSize.grams(offer.packSize ?: offer.title, ingredient.gramsPerPiece, ingredient.gramsPerMl)
                val regularPack = perKg[id]?.let { perKg -> packGrams?.let { perKg * it / 1000 } }
                Discounts.factor(offer, regularPack)
            }.minOrNull()?.let { id to it }
        }.toMap()
        return Pricing(perKg, discounts)
    }

    /**
     * Looks up every ingredient, or nothing when the last lookup is younger than [olderThan].
     * One lookup at a time; a second call while one runs returns at once. An ingredient
     * whose search fails keeps its previous price.
     */
    suspend fun refresh(olderThan: Duration? = null, now: Instant = Instant.now(), pause: Duration = Duration.ofMillis(300)): PricesState {
        val source = source ?: return state()
        val last = db.foodQueries.selectMeta(FETCHED_KEY).executeAsOneOrNull()?.let(Instant::parse)
        if (olderThan != null && last != null && Duration.between(last, now) < olderThan) return state()
        if (!refreshing.compareAndSet(false, true)) return state()
        try {
            val terms = ingredients.matchTerms()
            val matcher = OfferMatcher(terms)
            var found = 0
            var failures = 0
            for (ingredient in priceable()) {
                val price = try {
                    lookUp(source, ingredient, terms[ingredient.id].orEmpty(), matcher, pause)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Price lookup for ${ingredient.name} failed: ${e.message}")
                    // The store is down or blocking us: stop rather than fail a hundred times.
                    if (++failures >= 5) {
                        log.warn("Stopped looking up prices after $failures failures")
                        return state()
                    }
                    continue
                }
                if (price == null) {
                    q.deletePrice(ingredient.id)
                } else {
                    val (product, perKg) = price
                    q.upsertPrice(ingredient.id, source.store.name, product.title, product.packSize, product.regularPrice, perKg, now.toString())
                    found++
                }
            }
            db.foodQueries.upsertMeta(FETCHED_KEY, now.toString())
            log.info("Found ${source.store.label} prices for $found ingredients")
        } finally {
            refreshing.set(false)
        }
        return state()
    }

    /**
     * Searches the store for a few of the ingredient's names, Dutch ones first (they come
     * last in the catalogue), and pools the products that are this ingredient. Of those in
     * the most common category, the middle of the three cheapest per kg is taken: cheap, as a budget shopper buys, without
     * trusting a single odd product.
     */
    private suspend fun lookUp(
        source: PriceSource,
        ingredient: Ingredient,
        names: List<String>,
        matcher: OfferMatcher,
        pause: Duration,
    ): Pair<StoreProduct, Double>? {
        // Where AH sells this kind of food; null for kinds without a known aisle.
        val aisles = AhAisles.forGroup(ingredient.food?.groupEn)
        val queries = names.reversed().map { it.trim().lowercase() }.filter { it.length >= 3 }.distinct().take(MAX_SEARCHES)
        val priced = queries.flatMap { query ->
            source.search(query).also { delay(pause.toMillis()) }
        }
            .distinctBy { it.title }
            .filter { product ->
                if (aisles == null) matcher.match(product.title, product.category) == ingredient.id
                else product.category in aisles && matcher.match(product.title, product.category, checkCategory = false) == ingredient.id
            }
            .mapNotNull { product -> perKg(product, ingredient)?.let { product to it } }
            .filter { it.second in 0.05..200.0 }
        // The ingredient itself sits in one aisle; stray matches ("croissants cinnamon",
        // "naan garlic") in others. Keep the category with the most matches, the most
        // relevant one on a tie (groupBy keeps search order).
        val aisle = priced.groupBy { it.first.category }.values.maxByOrNull { it.size } ?: return null
        val cheapest = aisle.sortedBy { it.second }.take(3)
        return cheapest[(cheapest.size - 1) / 2]
    }

    private fun perKg(product: StoreProduct, ingredient: Ingredient): Double? {
        val grams = PackSize.grams(product.packSize, ingredient.gramsPerPiece, ingredient.gramsPerMl)
        if (grams != null && grams > 0 && product.regularPrice != null) return product.regularPrice / grams * 1000
        val unit = unitPrice.find(product.unitPrice?.lowercase() ?: return null) ?: return null
        val price = unit.groupValues[2].replace(',', '.').toDouble()
        return if (unit.groupValues[1].startsWith("l")) price / (ingredient.gramsPerMl ?: 1.0) else price
    }

    /** Ingredients worth a price: not negligible, and not tap water or salt. */
    private fun priceable() = ingredients.list().filter { !it.negligible && it.name !in setOf("Water", "Salt") }

    private companion object {
        const val FETCHED_KEY = "pricesFetchedAt"
        const val BY_HAND = "Set by hand"
        const val MAX_SEARCHES = 4
        val unitPrice = Regex("""per\s*(kg|kilo|liter|l)\s*€\s*(\d+(?:[.,]\d+)?)""")
    }
}
