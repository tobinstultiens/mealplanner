package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.nutrition.NutritionCalculator
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.shopping.ShopSection
import dev.tobins.mealplanner.shopping.bestOffer
import dev.tobins.mealplanner.units.Dimension
import kotlin.math.ceil

/** A recipe in the amount to shop for: [portions] of its servings, leftovers included. */
data class PlannedRecipe(val recipeId: Long, val title: String, val portions: Double)

/** One thing to buy, summed over the recipes that use it. [key] identifies it within its list. */
class ShoppingNeed(val key: ShoppingKey, val ingredient: Ingredient?, val name: String, val section: ShopSection) {
    var grams = 0.0
    var gramsKnown = true
    var pieces = 0.0

    /** The part of [pieces] counted in the ingredient itself (`2 | onion`), not in a container (`1 bag`). */
    private var ownPieces = 0.0

    /** What could not be converted to grams and is not the ingredient itself, summed per unit as written, scaled. */
    private val others = linkedMapOf<String, Double>()
    val recipes = linkedSetOf<String>()

    /** The total weight, when every use could be converted to grams and there is some. */
    val totalGrams: Double? get() = grams.takeIf { gramsKnown && it > 0 }

    /** The weight of the lines that could be converted, when [totalGrams] is null because other lines could not. */
    val knownGrams: Double? get() = grams.takeIf { !gramsKnown && it > 0 }

    /** `1 bag`, `250 ml`: [others] rounded up to whole units, in the order first met. */
    val otherAmounts: List<String> get() = others.map { (unit, amount) -> "${ceil(amount - 1e-9).toInt()} $unit" }

    /** Adds a count of the ingredient itself, which [pieces] counts too. */
    fun addOwnPieces(amount: Double) {
        ownPieces += amount
    }

    /** Adds a line that has no weight: a count in a container or a volume without a density. */
    fun addOther(unit: String, amount: Double) {
        others.merge(unit.lowercase(), amount, Double::plus)
    }

    /** The row on the list, with what is known about buying it. */
    fun toItem(
        key: ShoppingKey,
        checked: Boolean,
        onHand: Boolean,
        offers: List<Offer>,
        productImage: String?,
        perKg: Double?,
    ) = ShoppingItem(
        key = key.toString(),
        name = name,
        grams = totalGrams,
        knownGrams = knownGrams,
        otherAmounts = otherAmounts,
        pieces = pieces.takeIf { it > 0 },
        ownPieces = ownPieces.takeIf { it > 0 && otherAmounts.isNotEmpty() },
        category = section.title,
        recipes = recipes.toList(),
        onHand = onHand,
        checked = checked,
        offers = offers,
        imageUrl = bestOffer(offers)?.imageUrl ?: productImage,
        regularPrice = totalGrams?.let { g -> perKg?.let { g / 1000 * it } },
    )
}

/** What some recipes need, in walking order, with the staples and to-taste names they use set apart. */
class ShoppingNeeds(val needs: List<ShoppingNeed>, val staples: List<String>, val toTaste: List<String>)

/**
 * What [recipes] need. Each is scaled to its portions; staples are left out and only named as a
 * reminder, as is what is used "to taste". Needs come in the order of the walk through the shop,
 * by section ([sectionOf], by ingredient id) and then by name.
 */
fun shoppingNeeds(recipes: List<PlannedRecipe>, lines: Map<Long, RecipeLines>, sectionOf: Map<Long, ShopSection>): ShoppingNeeds {
    val needs = linkedMapOf<ShoppingKey, ShoppingNeed>()
    val staples = sortedSetOf<String>()
    val toTaste = sortedSetOf<String>()
    for (planned in recipes) {
        val recipe = lines[planned.recipeId] ?: continue
        val factor = planned.portions / recipe.servings
        for ((line, ingredient) in recipe.lines) {
            if (ingredient?.isStaple == true) {
                staples += ingredient.name
                continue
            }
            if (ingredient?.negligible == true || (line.amount == null && NutritionCalculator.isSeasoning(line.ingredientText, ingredient))) {
                if (ingredient?.name != "Water") toTaste += ingredient?.name ?: IngredientLine.coreText(line.ingredientText)
                continue
            }
            val key = ingredient?.let { ShoppingKey.Ingredient(it.id) }
                ?: ShoppingKey.Text(IngredientRepository.normalise(IngredientLine.coreText(line.ingredientText)))
            val need = needs.getOrPut(key) {
                ShoppingNeed(
                    key = key,
                    ingredient = ingredient,
                    name = ingredient?.name ?: IngredientLine.coreText(line.ingredientText).replaceFirstChar { it.uppercase() },
                    section = ingredient?.let { sectionOf[it.id] } ?: ShopSection.OTHER,
                )
            }
            need.recipes += planned.title
            val amount = line.amount ?: continue // "salt to taste": needed, but no quantity
            val grams = gramsOf(line, ingredient)
            if (grams != null) need.grams += grams * factor else need.gramsKnown = false
            if (line.dimension == Dimension.COUNT) need.pieces += amount * factor
            if (line.unitIsTheIngredient) need.addOwnPieces(amount * factor)
            val unit = line.unit
            if (grams == null && unit != null && !line.unitIsTheIngredient) need.addOther(unit, amount * factor)
        }
    }
    return ShoppingNeeds(
        needs = needs.values.sortedWith(compareBy({ it.section.ordinal }, { it.name.lowercase() })),
        staples = staples.toList(),
        toTaste = toTaste.toList(),
    )
}

/** The weight of a line with an amount, by the ingredient's conversions or else its own; null when it can't be told. */
private fun gramsOf(line: IngredientLine, ingredient: Ingredient?): Double? =
    ingredient?.let { NutritionCalculator.quantityOf(line, it)?.grams } ?: line.baseAmount.takeIf { line.dimension == Dimension.WEIGHT }

/**
 * Grams of each ingredient that [portions] of [recipe]'s servings use, by ingredient id. Staples and
 * spices count too: this is what a meal takes from the pantry, not what the list buys. A volume
 * without a density counts as water, as for nutrition. A line whose weight can't be told ("olive oil
 * to taste", "1 bunch of coriander" without a weight per piece) adds nothing: guessing would invent an
 * amount, and taking the whole stock for it (as before issue 84) would empty a litre of oil on its
 * first use. An ingredient with no weighed line is left out, so a meal takes nothing of it.
 */
fun gramsUsed(recipe: RecipeLines, portions: Double): Map<Long, Double> {
    val factor = portions / recipe.servings
    val grams = HashMap<Long, Double>()
    for ((line, ingredient) in recipe.lines) {
        if (ingredient == null) continue
        val weight = line.amount?.let { gramsOf(line, ingredient) }?.takeIf { it > 0 } ?: continue
        grams.merge(ingredient.id, weight * factor, Double::plus)
    }
    return grams
}
