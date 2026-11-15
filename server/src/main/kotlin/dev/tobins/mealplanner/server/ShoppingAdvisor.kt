package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.planner.AheadOption
import dev.tobins.mealplanner.planner.BuyAhead
import dev.tobins.mealplanner.pricing.PackSize
import dev.tobins.mealplanner.shopping.ShelfLife
import dev.tobins.mealplanner.shopping.ShopSection

/**
 * The advice on one week's shopping list next to what its meals need: what to buy ahead for next
 * week's meals ([buyAhead]) and what to stock up on ([stockUp]). Both go by this week's offers at
 * the stores the account shops at only, and by what those offers save against the regular price.
 *
 * The keys of the items it makes are those of [ShoppingList.buyAhead][dev.tobins.mealplanner.api.ShoppingList.buyAhead]
 * and `stockUp`: [aheadKey] and [stockKey].
 */
class ShoppingAdvisor(
    /** This week's offers at the stores in use, by ingredient id, each with its paid share. */
    private val offers: Map<Long, List<Offer>>,
    /** Regular euros per kg, by ingredient id. */
    private val perKg: Map<Long, Double>,
    private val sectionOf: Map<Long, ShopSection>,
    /** Whether each ingredient keeps until next week, by id. */
    private val keeps: Map<Long, Boolean>,
    private val productImages: Map<Long, String>,
    /** The keys ticked on this week's list. */
    private val checked: Set<String>,
) {
    /** The share of the regular price paid with the best offer that saves; null without one. */
    private fun paidShare(id: Long): Double? = offers[id]?.mapNotNull { it.paidShare }?.minOrNull()

    /** Euros the offers save on [need] against the regular price; null when either is unknown. */
    private fun saving(need: ShoppingNeed): Double? {
        val id = need.ingredient?.id ?: return null
        val regular = need.totalGrams?.let { g -> perKg[id]?.let { g / 1000 * it } } ?: return null
        return paidShare(id)?.let { regular * (1 - it) }
    }

    /**
     * What to buy now for next week's [meals] (one batch each, or as planned when next week is
     * planned already): the ingredients that keep, are on offer now but not next week
     * ([onOfferNextWeek]), and are not on hand next week ([onHand]) unless this list's own tick
     * put them there. Staples never are: the list leaves them out. Of the meals, [BuyAhead.choose]
     * picks those that save most; [ratings] by recipe id break ties.
     */
    fun buyAhead(
        meals: List<PlannedRecipe>,
        lines: Map<Long, RecipeLines>,
        ratings: Map<Long, Int?>,
        onHand: Set<Long>,
        onOfferNextWeek: Set<Long>,
    ): List<ShoppingItem> {
        fun qualifies(need: ShoppingNeed): Boolean {
            val id = need.ingredient?.id ?: return false
            return keeps[id] == true && id !in onOfferNextWeek && (id !in onHand || aheadKey(id) in checked) && saving(need) != null
        }
        val options = meals.map { meal ->
            val needs = shoppingNeeds(listOf(meal), lines, sectionOf).needs.filter(::qualifies)
            AheadOption(meal.recipeId, meal.title, needs.sumOf { saving(it)!! }, ratings[meal.recipeId])
        }
        val chosen = BuyAhead.choose(options).mapTo(HashSet()) { it.recipeId }
        return shoppingNeeds(meals.filter { it.recipeId in chosen }, lines, sectionOf).needs.filter(::qualifies).map { need ->
            val id = need.ingredient!!.id
            val key = aheadKey(id)
            need.toItem(key, key in checked, onHand = false, offers[id].orEmpty(), productImages[id], perKg[id])
        }
    }

    /**
     * What to stock up on: [ingredients] that keep and are a staple, or that a recipe uses ([used])
     * and keep for months; on offer now, not on hand ([onHand]) unless this list's own tick put them
     * there, and not on the list already ([listed]). One pack of the best offer each; the
     * [MAX_STOCK_UP] that save most, in walking order.
     */
    fun stockUp(ingredients: List<Ingredient>, used: Set<Long>, onHand: Set<Long>, listed: Set<Long>): List<ShoppingItem> =
        ingredients.asSequence()
            .filter { !it.negligible && it.id !in listed && keeps[it.id] == true }
            .filter { it.isStaple || (it.id in used && sectionOf[it.id]?.shelfLife == ShelfLife.LONG) }
            .filter { it.id !in onHand || stockKey(it.id) in checked }
            .mapNotNull(::pack)
            .filter { it.saving >= MIN_STOCK_UP_SAVING }
            .sortedByDescending { it.saving }
            .take(MAX_STOCK_UP)
            .sortedWith(compareBy({ it.section.ordinal }, { it.ingredient.name.lowercase() }))
            .map { it.toItem() }
            .toList()

    /** One pack of [ingredient] bought with its best offer; null when what that saves is unknown. */
    private fun pack(ingredient: Ingredient): Pack? {
        val offer = offers[ingredient.id]?.filter { it.paidShare != null }?.minByOrNull { it.paidShare!! } ?: return null
        val grams = PackSize.grams(offer.packSize ?: offer.title, ingredient.gramsPerPiece, ingredient.gramsPerMl)
        val regular = grams?.let { g -> perKg[ingredient.id]?.let { g / 1000 * it } }
            ?: offer.originalPrice?.takeIf { offer.price != null }
            ?: return null
        return Pack(ingredient, sectionOf[ingredient.id] ?: ShopSection.OTHER, offer, grams, regular)
    }

    private inner class Pack(val ingredient: Ingredient, val section: ShopSection, val offer: Offer, val grams: Double?, val regular: Double) {
        val saving get() = regular * (1 - offer.paidShare!!)

        fun toItem(): ShoppingItem {
            val key = stockKey(ingredient.id)
            return ShoppingItem(
                key = key,
                name = ingredient.name,
                grams = grams,
                pieces = null,
                category = section.title,
                recipes = emptyList(),
                onHand = false,
                checked = key in checked,
                offers = listOf(offer),
                imageUrl = offer.imageUrl ?: productImages[ingredient.id],
                regularPrice = regular,
            )
        }
    }

    companion object {
        /** More than this many is a page of advertising, not advice. */
        const val MAX_STOCK_UP = 6

        /** Euros a pack must save to be worth stocking up on. */
        const val MIN_STOCK_UP_SAVING = 0.25

        const val AHEAD_PREFIX = "ahead:i"
        const val STOCK_PREFIX = "stock:i"

        fun aheadKey(ingredientId: Long) = "$AHEAD_PREFIX$ingredientId"

        fun stockKey(ingredientId: Long) = "$STOCK_PREFIX$ingredientId"
    }
}
