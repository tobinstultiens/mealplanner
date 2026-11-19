package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.MealLine
import dev.tobins.mealplanner.api.MealLineChange
import dev.tobins.mealplanner.api.MealLines
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.nutrition.NutritionCalculator
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.units.Dimension
import java.time.LocalDate
import kotlin.math.abs

/**
 * What planned meals ate that differs from their recipes (issue 226, the table `mealLine`): each meal's
 * changes by ingredient, and what they come to: the meal's lines, its numbers, and what eating it takes
 * from the stock. The recipe itself never changes; a change belongs to one meal on one day. Whether a
 * meal may be adjusted, and moving the stock when it was eaten already, is [PlanRepository.adjust]'s.
 */
class MealAdjustments(
    db: Database,
    private val userId: Long,
    private val recipes: RecipeRepository,
    private val catalogue: IngredientRepository,
) {
    private val q = db.planQueries

    /** [meal]'s changes, grams eaten by ingredient id; empty for a meal eaten as its recipe. */
    fun changesOf(start: LocalDate, meal: PlannedMeal): Map<Long, Double> =
        q.selectMealLines(userId, start.toString(), meal.day.toLong(), meal.slot.name).executeAsList().associate { it.ingredientId to it.grams }

    /** The changes of every adjusted meal of the week starting [start], by day and slot; grams eaten by ingredient id. */
    fun changesIn(start: LocalDate): Map<Pair<Int, MealSlot>, Map<Long, Double>> =
        q.selectWeekLines(userId, start.toString()).executeAsList()
            .groupBy({ it.day.toInt() to MealSlot.valueOf(it.slot) }, { it.ingredientId to it.grams })
            .mapValues { (_, lines) -> lines.toMap() }

    /**
     * [meals] of one week as [shortfalls] walks them: what each one's recipe gives it and what
     * it eats with [changes] ([changesIn]); those in [toCook], by day and slot, are still to cook.
     */
    fun demands(meals: List<PlannedMeal>, changes: Map<Pair<Int, MealSlot>, Map<Long, Double>>, toCook: Set<Pair<Int, MealSlot>>): List<MealDemand> {
        val recipeLines = recipes.linesWithIngredients(meals.map { it.recipeId })
        return meals.map { meal ->
            val recipe = recipeLines[meal.recipeId]?.let { gramsUsed(it, meal.portions) }.orEmpty()
            val eaten = (recipe + changes[meal.day to meal.slot].orEmpty()).filterValues { it > 0.0 }
            MealDemand(meal, recipe, eaten, toCook = (meal.day to meal.slot) in toCook)
        }
    }

    /** Grams of each ingredient that eating [meal] uses: its portions of [recipe] ([gramsUsed]) with its changes. */
    fun gramsEaten(start: LocalDate, meal: PlannedMeal, recipe: RecipeLines): Map<Long, Double> =
        (gramsUsed(recipe, meal.portions) + changesOf(start, meal)).filterValues { it > 0.0 }

    /**
     * [meals] of the week starting [start] with the numbers they count. An adjusted meal has its kcal,
     * protein and fibre worked out from what was in it ([mealNutrition]), with the recipe's lines and the
     * catalogue as they are now, as fibre is for every meal; it is [PlannedMeal.adjusted]. Any other meal
     * keeps the kcal and protein stored when it was planned, with the fibre of its portions of the recipe.
     * So one rule holds for an adjusted meal's three numbers, and putting it back to its recipe needs
     * nothing restored: the stored numbers were never changed.
     */
    fun withNutrition(start: LocalDate, meals: List<PlannedMeal>): List<PlannedMeal> {
        val fibre = recipes.fibrePerServing(meals.map { it.recipeId })
        val changes = changesIn(start)
        val adjusted = meals.filter { (it.day to it.slot) in changes }
        if (adjusted.isEmpty()) return meals.map { it.copy(fibreG = fibre[it.recipeId]?.let { perServing -> perServing * it.portions }) }
        val recipeLines = recipes.linesWithIngredients(adjusted.map { it.recipeId })
        val ingredients = catalogue.list().associateBy { it.id }
        return meals.map { meal ->
            val recipe = recipeLines[meal.recipeId]
            val mine = changes[meal.day to meal.slot]
            if (recipe == null || mine == null) {
                meal.copy(fibreG = fibre[meal.recipeId]?.let { it * meal.portions })
            } else {
                val eaten = mealNutrition(recipe, meal.portions, mine, ingredients)
                meal.copy(kcal = eaten.kcal, proteinG = eaten.proteinG, fibreG = eaten.fibreG, adjusted = true)
            }
        }
    }

    /**
     * [meal] with what was in it: its recipe's ingredients for its portions, in the recipe's order, each
     * with this meal's amount, then what was added. Seasoning without an amount ("salt to taste") is left
     * out, as it counts for nothing; a line linked to no ingredient is listed, but can't be changed.
     */
    fun lines(start: LocalDate, meal: PlannedMeal): MealLines {
        val recipe = recipeOf(meal)
        val changes = changesOf(start, meal)
        val ingredients = catalogue.list().associateBy { it.id }
        val recipeGrams = gramsUsed(recipe, meal.portions)
        val shown = linkedMapOf<String, MealLine>()
        for ((line, ingredient) in recipe.lines) {
            if (line.amount == null && (ingredient?.negligible == true || NutritionCalculator.isSeasoning(line.ingredientText, ingredient))) continue
            if (ingredient == null) {
                val text = IngredientLine.coreText(line.ingredientText)
                val grams = line.baseAmount?.takeIf { line.dimension == Dimension.WEIGHT }?.let { it * meal.portions / recipe.servings }
                // Two lines of one text weigh what both do, or nothing known when either can't be weighed.
                shown.merge("t:$text", MealLine(null, text.replaceFirstChar { it.uppercase() }, grams)) { first, second ->
                    first.copy(grams = first.grams?.let { a -> second.grams?.let { b -> a + b } })
                }
                continue
            }
            shown.getOrPut("i${ingredient.id}") {
                MealLine(
                    ingredientId = ingredient.id,
                    name = ingredient.name,
                    grams = changes[ingredient.id] ?: recipeGrams[ingredient.id],
                    recipeGrams = recipeGrams[ingredient.id],
                    changed = ingredient.id in changes,
                    gramsPerPiece = ingredient.gramsPerPiece,
                )
            }
        }
        val inRecipe = recipe.lines.mapNotNullTo(HashSet()) { it.second?.id }
        for ((id, grams) in changes) {
            if (shown.containsKey("i$id")) continue
            val ingredient = ingredients[id] ?: continue
            shown["i$id"] = MealLine(id, ingredient.name, grams, fromRecipe = id in inRecipe, changed = true, gramsPerPiece = ingredient.gramsPerPiece)
        }
        val eaten = mealNutrition(recipe, meal.portions, changes, ingredients)
        return MealLines(
            weekStart = start.toString(),
            day = meal.day,
            slot = meal.slot,
            recipeId = meal.recipeId,
            title = meal.title,
            portions = meal.portions,
            lines = shown.values.toList(),
            // Not adjusted, the meal counts what was stored when it was planned, as everywhere else.
            kcal = if (changes.isEmpty()) meal.kcal else eaten.kcal,
            proteinG = if (changes.isEmpty()) meal.proteinG else eaten.proteinG,
            fibreG = eaten.fibreG,
            complete = eaten.uncounted.isEmpty(),
            uncounted = eaten.uncounted,
            adjusted = changes.isNotEmpty(),
        )
    }

    /**
     * Replaces [meal]'s changes with [changes], as `PUT .../lines` sends them; an empty list puts it back to
     * its recipe. An amount the same as the recipe's (to a twentieth of a gram) is no change, nor is 0 g of
     * an ingredient the recipe does not have, so a meal is adjusted only when something differs. Each
     * ingredient at most once, 0 to [MAX_GRAMS] g, at most [MAX_LINES] of them; an ingredient that is not
     * the account's is a [NotFoundException].
     */
    fun replace(start: LocalDate, meal: PlannedMeal, changes: List<MealLineChange>) {
        require(changes.size <= MAX_LINES) { "at most $MAX_LINES lines" }
        require(changes.distinctBy { it.ingredientId }.size == changes.size) { "each ingredient at most once" }
        for (change in changes) {
            require(change.grams.isFinite() && change.grams in 0.0..MAX_GRAMS) { "grams must be between 0 and ${MAX_GRAMS.toInt()}" }
            catalogue.find(change.ingredientId) ?: throw NotFoundException("No ingredient with id ${change.ingredientId}")
        }
        val recipe = recipeOf(meal)
        val recipeGrams = gramsUsed(recipe, meal.portions)
        val inRecipe = recipe.lines.mapNotNullTo(HashSet()) { it.second?.id }
        val kept = changes.filter { change ->
            val planned = recipeGrams[change.ingredientId]
            when {
                change.ingredientId !in inRecipe -> change.grams > 0.0
                planned != null -> abs(change.grams - planned) >= SAME_GRAMS
                else -> true
            }
        }
        q.deleteMealLines(userId, start.toString(), meal.day.toLong(), meal.slot.name)
        for (change in kept) q.insertMealLine(userId, start.toString(), meal.day.toLong(), meal.slot.name, change.ingredientId, change.grams)
    }

    /** [meal]'s recipe with what each line means; a recipe that is gone has no lines. */
    private fun recipeOf(meal: PlannedMeal): RecipeLines =
        recipes.linesWithIngredients(listOf(meal.recipeId))[meal.recipeId] ?: RecipeLines(1, emptyList())

    companion object {
        /** More than any one ingredient of a meal weighs: a typing mistake rather than an amount. */
        const val MAX_GRAMS = 5000.0

        /** More lines than a meal has: a list sent by mistake. */
        const val MAX_LINES = 100

        /** Amounts closer than this to the recipe's are the recipe's: what the app rounds pieces to. */
        private const val SAME_GRAMS = 0.05
    }
}

/** What a meal comes to: its kcal, protein and fibre, and the names of what counts as nothing for want of numbers. */
data class MealNutrition(val kcal: Double, val proteinG: Double, val fibreG: Double, val uncounted: List<String>)

/**
 * The nutrition of [portions] of [recipe] when [changes] (grams eaten by ingredient id) replace the
 * recipe's amounts of those ingredients, for the whole meal. Worked out the way a recipe's is
 * ([NutritionCalculator.perServing]): the meal's own amounts become weighed lines of the recipe's
 * whole yield, so the recipe's other lines count exactly as they do in it. An ingredient without a
 * NEVO food counts as nothing and is named in [MealNutrition.uncounted], as a recipe's is.
 * [ingredients] is the catalogue by id, for those the recipe does not have.
 */
fun mealNutrition(recipe: RecipeLines, portions: Double, changes: Map<Long, Double>, ingredients: Map<Long, Ingredient>): MealNutrition {
    val kept = recipe.lines.filter { (_, ingredient) -> ingredient == null || ingredient.id !in changes }
    val toYield = if (portions > 0.0) recipe.servings / portions else 0.0
    val own = changes.filterValues { it > 0.0 }.mapNotNull { (id, grams) ->
        ingredients[id]?.let { weighed(it.name, grams * toYield) to it }
    }
    val result = NutritionCalculator.perServing(kept + own, recipe.servings)
    return MealNutrition(
        kcal = (result.perServing.kcal ?: 0.0) * portions,
        proteinG = (result.perServing.proteinG ?: 0.0) * portions,
        fibreG = (result.perServing.fibreG ?: 0.0) * portions,
        uncounted = result.uncounted.distinct(),
    )
}

/** A line of [grams] of [name], the way a recipe would weigh it. */
private fun weighed(name: String, grams: Double) =
    IngredientLine(amount = grams, unit = "g", dimension = Dimension.WEIGHT, baseAmount = grams, item = name, group = null)
