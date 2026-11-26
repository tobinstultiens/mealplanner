package dev.tobins.mealplanner.nutrition

import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.RecipeLineInput
import dev.tobins.mealplanner.api.RecipeNutrition
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.recipes.toIngredientLine
import dev.tobins.mealplanner.units.Dimension

/** How much of an ingredient a line stands for. */
data class LineQuantity(
    val grams: Double,
    /** True when a volume was converted to weight (or back) with an assumed density of 1 g/ml. */
    val assumedDensity: Boolean,
)

object NutritionCalculator {

    /**
     * Weight of [line] in grams, or null when it cannot be known: no amount, a count ("2 onions") without a weight per
     * piece, or a count in a portion the ingredient no longer has. A line in one of its portions weighs what the portion
     * does ([measured]), now: so a portion corrected corrects every line in it.
     */
    fun quantityOf(line: IngredientLine, ingredient: Ingredient): LineQuantity? {
        val measured = measured(line, ingredient) ?: return null
        return weightOf(measured, ingredient)
    }

    /** [quantityOf] a line that is no count in a portion. */
    private fun weightOf(line: IngredientLine, ingredient: Ingredient): LineQuantity? {
        val amount = line.amount ?: return null
        return when (line.dimension) {
            Dimension.WEIGHT -> line.baseAmount?.let { LineQuantity(it, assumedDensity = false) }
            Dimension.VOLUME -> line.baseAmount?.let { ml ->
                LineQuantity(ml * (ingredient.gramsPerMl ?: 1.0), assumedDensity = ingredient.gramsPerMl == null)
            }
            Dimension.COUNT -> ingredient.gramsPerPiece?.let { LineQuantity(amount * it, assumedDensity = false) }
            null -> null
        }
    }

    /**
     * Nutrition of one of [servings] servings. Negligible ingredients are ignored. Lines
     * without an amount cannot be counted: seasoning ("salt to taste") is ignored, anything
     * else is reported in [RecipeNutrition.unquantified]. Lines with an amount that cannot be counted (unmapped,
     * no values, no weight per piece, or a portion that is gone) make the result incomplete. An ingredient counts by
     * the values of its own label when it has them, else by its NEVO food ([values], issue 351).
     */
    fun perServing(lines: List<Pair<IngredientLine, Ingredient?>>, servings: Int): RecipeNutrition {
        require(servings > 0) { "servings must be positive" }
        var total = Zero
        var approximate = false
        val uncounted = mutableListOf<String>()
        val unquantified = mutableListOf<String>()

        for ((line, ingredient) in lines) {
            if (ingredient?.negligible == true) continue
            if (line.amount == null) {
                if (!isSeasoning(line.ingredientText, ingredient)) unquantified += line.ingredientText
                continue
            }
            val values = ingredient?.values
            val measured = ingredient?.let { measured(line, it) }
            val quantity = measured?.let { weightOf(it, ingredient) }
            if (ingredient == null || values == null || measured == null || quantity == null) {
                uncounted += line.ingredientText
                continue
            }
            // NEVO and labels give most drinks per 100 ml, so those need the volume, not the weight.
            val hundreds = when (values.basis) {
                FoodBasis.PER_100_G -> quantity.grams / 100
                FoodBasis.PER_100_ML -> when (measured.dimension) {
                    Dimension.VOLUME -> measured.baseAmount!! / 100
                    else -> {
                        if (ingredient.gramsPerMl == null) approximate = true
                        quantity.grams / (ingredient.gramsPerMl ?: 1.0) / 100
                    }
                }
            }
            if (quantity.assumedDensity && !(values.basis == FoodBasis.PER_100_ML && measured.dimension == Dimension.VOLUME)) {
                approximate = true
            }
            total = total + values.per100 * hundreds
        }
        return RecipeNutrition(
            perServing = total * (1.0 / servings),
            complete = uncounted.isEmpty(),
            approximate = approximate,
            uncounted = uncounted,
            unquantified = unquantified.distinct(),
        )
    }

    /**
     * What a recipe saved with [lines], each with the ingredient it means, has per serving of [servings]; null when no
     * line has an amount, as a saved recipe has none then. Each line is read as the server stores it
     * ([toIngredientLine]) and summed by [perServing], as the saved recipe is: so the totals the editor shows while a
     * recipe is written are the saved recipe's (issue 342).
     */
    fun ofLines(lines: List<Pair<RecipeLineInput, Ingredient?>>, servings: Int): RecipeNutrition? {
        val read = lines.mapNotNull { (input, ingredient) -> input.toIngredientLine()?.let { it to ingredient } }
        if (read.none { (line, _) -> line.amount != null }) return null
        return perServing(read, servings)
    }

    /** Words that mark a line without an amount as seasoning, garnish or optional: fine to leave out. */
    private val seasoningWords = Regex(
        "taste|smaak|optional|optioneel|garnish|serving|topping|frying|greasing|dusting|naar keuze|pinch|snufje|sprinkle|beetje|scheutje|" +
            // Salt is named in every salt-and-pepper line; "peper" alone would also catch chillies ("rode peper").
            "\\bsalt\\b|\\bzout\\b|cranks",
        RegexOption.IGNORE_CASE,
    )

    /** Ingredients that, without an amount, add next to nothing however much is used. */
    private val freeFoods = setOf("Salt", "Water", "Black pepper")

    /** True for a line without an amount that is only seasoning, garnish or optional ("salt to taste"). */
    fun isSeasoning(text: String, ingredient: Ingredient?): Boolean =
        seasoningWords.containsMatchIn(text) || ingredient?.name in freeFoods || ingredient?.food?.groupEn == "Herbs and spices"

    private val Zero = Nutrition(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    /** A missing value in the source counts as zero in a sum; there is nothing better to add. */
    private operator fun Nutrition.plus(other: Nutrition) = Nutrition(
        kcal = (kcal ?: 0.0) + (other.kcal ?: 0.0),
        proteinG = (proteinG ?: 0.0) + (other.proteinG ?: 0.0),
        fatG = (fatG ?: 0.0) + (other.fatG ?: 0.0),
        saturatedFatG = (saturatedFatG ?: 0.0) + (other.saturatedFatG ?: 0.0),
        carbsG = (carbsG ?: 0.0) + (other.carbsG ?: 0.0),
        sugarG = (sugarG ?: 0.0) + (other.sugarG ?: 0.0),
        fibreG = (fibreG ?: 0.0) + (other.fibreG ?: 0.0),
        sodiumMg = (sodiumMg ?: 0.0) + (other.sodiumMg ?: 0.0),
    )

    private operator fun Nutrition.times(factor: Double) = Nutrition(
        kcal = kcal?.times(factor),
        proteinG = proteinG?.times(factor),
        fatG = fatG?.times(factor),
        saturatedFatG = saturatedFatG?.times(factor),
        carbsG = carbsG?.times(factor),
        sugarG = sugarG?.times(factor),
        fibreG = fibreG?.times(factor),
        sodiumMg = sodiumMg?.times(factor),
    )
}
