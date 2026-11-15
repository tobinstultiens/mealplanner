package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientPrice
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.PricesState
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.pricing.Discounts
import dev.tobins.mealplanner.pricing.PackSize
import dev.tobins.mealplanner.pricing.Pricing
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.history.RegularPriceSighting
import dev.tobins.mealplanner.server.offers.OfferMatcher
import dev.tobins.mealplanner.server.offers.StoreImages
import dev.tobins.mealplanner.server.prices.AhAisles
import dev.tobins.mealplanner.server.prices.PriceSource
import dev.tobins.mealplanner.server.prices.StoreAisle
import dev.tobins.mealplanner.server.prices.StoreProduct
import dev.tobins.mealplanner.shopping.bestOffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Regular prices, looked up in a store's product search (AH): the baseline that offers are
 * measured against and that recipes are costed with. Shared by every account: a price is
 * stored per ingredient name, and each account's ingredient of that name uses it. A price
 * per kg set by hand on an account's ingredient wins over the looked-up one.
 */
class PriceRepository(
    private val db: Database,
    private val source: PriceSource?,
    private val foods: FoodRepository,
    private val offers: OfferRepository,
) {
    private val q = db.priceQueries
    private val history = PriceHistoryRepository(db)
    private val log = LoggerFactory.getLogger(PriceRepository::class.java)
    private val refreshing = AtomicBoolean(false)

    /** The prices for one account's catalogue. */
    fun state(ingredients: IngredientRepository): PricesState {
        val looked = q.selectPrices().executeAsList().associateBy { it.name.lowercase() }
        val all = ingredients.list().filter(::priceable)
        val prices = all.mapNotNull { i ->
            i.pricePerKg?.let { IngredientPrice(i.id, i.name, Store.AH, BY_HAND, eurosPerKg = it) }
                ?: looked[i.name.lowercase()]?.let {
                    IngredientPrice(i.id, i.name, Store.valueOf(it.store), it.product, it.packSize, it.packPrice, it.eurosPerKg)
                }
        }
        val priced = prices.mapTo(HashSet()) { it.ingredientId }
        return PricesState(
            prices = prices,
            unpriced = all.filter { it.id !in priced }.map { it.name },
            fetchedAt = db.foodQueries.selectMeta(FETCHED_KEY).executeAsOneOrNull(),
            refreshing = refreshing.get(),
        )
    }

    /** Euros per kg by the account's ingredient id. */
    fun perKg(ingredients: IngredientRepository): Map<Long, Double> {
        val looked = q.selectPrices().executeAsList().associate { it.name.lowercase() to it.eurosPerKg }
        return ingredients.list().mapNotNull { i -> (i.pricePerKg ?: looked[i.name.lowercase()])?.let { i.id to it } }.toMap()
    }

    /**
     * Where the store files the product priced for each ingredient name (lower case), for the
     * ingredient's shop section. Shared like the prices; names without a category are left out.
     */
    fun aisles(): Map<String, StoreAisle> = q.selectPrices().executeAsList().mapNotNull { row ->
        row.category?.let { row.name.lowercase() to StoreAisle(Store.valueOf(row.store), it, row.subCategory) }
    }.toMap()

    /**
     * The picture of the product each of the account's ingredients is priced with, by ingredient
     * id. Ingredients with a price set by hand are left out: that price is not the product's, and
     * a hand price often means the product found was the wrong one.
     */
    fun productImages(ingredients: IngredientRepository): Map<Long, String> {
        val images = q.selectPrices().executeAsList().mapNotNull { row -> row.imageUrl?.let { row.name.lowercase() to it } }.toMap()
        return ingredients.list().filter { it.pricePerKg == null }.mapNotNull { i -> images[i.name.lowercase()]?.let { i.id to it } }.toMap()
    }

    /**
     * Offers valid at some point between [from] and [until] for one account's ingredients, by
     * ingredient id, at every store; each with its [Offer.paidShare] against the ingredient's regular price.
     */
    fun offers(ingredients: IngredientRepository, from: LocalDate, until: LocalDate): Map<Long, List<Offer>> =
        offersWithShares(ingredients, perKg(ingredients), from, until)

    /**
     * One account's prices with the discounts of offers valid at some point between [from] and
     * [until], at the stores in [shops] only: an offer where the account never shops saves it nothing.
     */
    fun pricing(ingredients: IngredientRepository, from: LocalDate, until: LocalDate, shops: Set<Store> = Store.entries.toSet()): Pricing {
        val perKg = perKg(ingredients)
        val discounts = offersWithShares(ingredients, perKg, from, until).mapNotNull { (id, onOffer) ->
            bestOffer(onOffer.filter { it.store in shops })?.paidShare?.let { id to it }
        }.toMap()
        return Pricing(perKg, discounts)
    }

    private fun offersWithShares(ingredients: IngredientRepository, perKg: Map<Long, Double>, from: LocalDate, until: LocalDate): Map<Long, List<Offer>> {
        val byId = ingredients.list().associateBy { it.id }
        return offers.byIngredient(from, until, ingredients.matchTerms()).mapValues { (id, onOffer) ->
            val ingredient = byId[id]
            onOffer.map { offer -> offer.copy(paidShare = ingredient?.let { paidShare(offer, it, perKg[id]) }) }
        }
    }

    /**
     * The share of the regular price paid with [offer] ([Discounts.factor]). Deals stated as a price
     * are compared with a pack of the offer's size at [perKg], the ingredient's regular price.
     */
    private fun paidShare(offer: Offer, ingredient: Ingredient, perKg: Double?): Double? {
        val packGrams = PackSize.grams(offer.packSize ?: offer.title, ingredient.gramsPerPiece, ingredient.gramsPerMl)
        val regularPack = perKg?.let { price -> packGrams?.let { price * it / 1000 } }
        return Discounts.factor(offer, regularPack)
    }

    /**
     * The names to look up: one entry per ingredient name across all accounts, known by the
     * names and aliases any account gave it. Ids are positions in the list, for the matcher.
     */
    private fun catalogue(): List<Pair<Ingredient, List<String>>> {
        val iq = db.ingredientQueries
        val aliases = iq.selectEveryAlias().executeAsList().groupBy({ it.ingredientId }, { it.text })
        return iq.selectEveryIngredient().executeAsList()
            .filter { it.negligible == 0L && it.name !in FREE }
            .groupBy { it.name.lowercase() }.values
            .mapIndexed { index, rows ->
                val first = rows.first()
                val ingredient = Ingredient(
                    id = index.toLong(),
                    name = first.name,
                    food = first.nevoCode?.let { foods.find(it.toInt()) },
                    gramsPerPiece = rows.firstNotNullOfOrNull { it.gramsPerPiece },
                    gramsPerMl = rows.firstNotNullOfOrNull { it.gramsPerMl },
                    category = first.category,
                    pricePerKg = null,
                    isStaple = false,
                )
                ingredient to (listOf(first.name) + rows.flatMap { aliases[it.id].orEmpty() }).distinct()
            }
    }

    /**
     * Looks up every ingredient name of every account, or nothing when the last lookup is
     * younger than [olderThan].
     * One lookup at a time; a second call while one runs returns at once. An ingredient
     * whose search fails keeps its previous price. Each price found is also kept in the price
     * history ([PriceHistoryRepository.recordRegularPrice]), in the transaction that stores it.
     */
    suspend fun refresh(olderThan: Duration? = null, now: Instant = Instant.now(), pause: Duration = Duration.ofMillis(300)) {
        val source = source ?: return
        val last = db.foodQueries.selectMeta(FETCHED_KEY).executeAsOneOrNull()?.let(Instant::parse)
        if (olderThan != null && last != null && Duration.between(last, now) < olderThan) return
        if (!refreshing.compareAndSet(false, true)) return
        try {
            val catalogue = catalogue()
            val matcher = OfferMatcher(catalogue.associate { (ingredient, names) -> ingredient.id to names })
            // When the previous lookup found each name's price; only this lookup changes it, one at a time.
            val lastFound = q.selectPrices().executeAsList().associate { it.name.lowercase() to it.fetchedAt }
            var found = 0
            var failures = 0
            for ((ingredient, names) in catalogue) {
                val price = try {
                    lookUp(source, ingredient, names, matcher, pause)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Price lookup for ${ingredient.name} failed: ${e.message}")
                    // The store is down or blocking us: stop rather than fail a hundred times.
                    if (++failures >= 5) {
                        log.warn("Stopped looking up prices after $failures failures")
                        return
                    }
                    continue
                }
                if (price == null) {
                    q.deletePrice(ingredient.name)
                } else {
                    val (product, perKg) = price
                    db.transaction {
                        q.upsertPrice(
                            ingredient.name, source.store.name, product.title, product.packSize, product.regularPrice, perKg, now.toString(),
                            product.category, product.subCategory, StoreImages.accept(product.imageUrl),
                        )
                        history.recordRegularPrice(
                            RegularPriceSighting(ingredient.name, source.store, product.title, product.packSize, product.regularPrice, perKg),
                            lastFound[ingredient.name.lowercase()]?.let(Instant::parse),
                            now,
                        )
                    }
                    found++
                }
            }
            history.pruneRegularPrices(now)
            db.foodQueries.upsertMeta(FETCHED_KEY, now.toString())
            log.info("Found ${source.store.label} prices for $found ingredients")
        } finally {
            refreshing.set(false)
        }
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
    private fun priceable(ingredient: Ingredient) = !ingredient.negligible && ingredient.name !in FREE

    private companion object {
        const val FETCHED_KEY = "pricesFetchedAt"
        const val BY_HAND = "Set by hand"
        const val MAX_SEARCHES = 4
        val FREE = setOf("Water", "Salt")
        val unitPrice = Regex("""per\s*(kg|kilo|liter|l)\s*€\s*(\d+(?:[.,]\d+)?)""")
    }
}
