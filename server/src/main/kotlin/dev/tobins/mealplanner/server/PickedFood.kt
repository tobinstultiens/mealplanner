package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.MealFoodInput
import dev.tobins.mealplanner.api.RecipeLineInput
import dev.tobins.mealplanner.nutrition.NutritionCalculator
import dev.tobins.mealplanner.nutrition.portionKey
import dev.tobins.mealplanner.nutrition.units
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.recipes.toIngredientLine
import dev.tobins.mealplanner.server.plan.MealAdjustments
import dev.tobins.mealplanner.server.plan.SlotFoods
import dev.tobins.mealplanner.server.tracking.TrackingRepository

/*
 * A food of the account's catalogue picked in an amount of one of its units: what a slot of the plan holds (issue 358,
 * [SlotFoods]) and what the log of food eaten outside the plan records (issue 362, [TrackingRepository]). Both check and
 * count it alike, so that a bar in the log counts what the same bar in a slot does.
 */

/** More than one food weighs: a typing mistake rather than an amount, as for a meal's own lines. */
const val MAX_FOOD_GRAMS = MealAdjustments.MAX_GRAMS

/**
 * [input] as the line of a food of [ingredient], the one it names: a positive amount of one of the units [ingredient] has
 * ([units], a portion's name in any case, kept as the portion spells it), weighing at most [MAX_FOOD_GRAMS]; else a 400.
 */
fun foodLine(ingredient: Ingredient, input: MealFoodInput): IngredientLine {
    require(input.amount.isFinite() && input.amount > 0.0) { "amount must be more than 0" }
    val unit = ingredient.units.firstOrNull { portionKey(it) == portionKey(input.unit) }
        ?: throw IllegalArgumentException("${ingredient.name} is counted in ${ingredient.units.joinToString()}, not in '${input.unit}'")
    val line = checkNotNull(RecipeLineInput(input.amount, unit, ingredient.name, ingredient.id).toIngredientLine())
    val grams = NutritionCalculator.quantityOf(line, ingredient)?.grams
    require(grams == null || grams <= MAX_FOOD_GRAMS) { "a food weighs at most ${MAX_FOOD_GRAMS.toInt()} g" }
    return line
}

/** What a food's [line] of [ingredient] weighs and counts. [counted] is false when it counts as nothing: no values, or no weight. */
data class FoodCount(val grams: Double?, val kcal: Double, val proteinG: Double, val fibreG: Double, val counted: Boolean)

/** [line] of [ingredient] counted as one serving of it, from the values [ingredient] has now. */
fun countOf(line: IngredientLine, ingredient: Ingredient): FoodCount {
    val counted = NutritionCalculator.perServing(listOf(line to ingredient), servings = 1)
    return FoodCount(
        grams = NutritionCalculator.quantityOf(line, ingredient)?.grams,
        kcal = counted.perServing.kcal ?: 0.0,
        proteinG = counted.perServing.proteinG ?: 0.0,
        fibreG = counted.perServing.fibreG ?: 0.0,
        counted = counted.complete && counted.perServing.kcal != null,
    )
}
