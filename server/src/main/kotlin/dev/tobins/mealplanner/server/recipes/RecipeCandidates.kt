package dev.tobins.mealplanner.server.recipes

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.RecipeCost
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.nutrition.NutritionCalculator
import dev.tobins.mealplanner.planner.FreshUse
import dev.tobins.mealplanner.planner.Pack
import dev.tobins.mealplanner.planner.PlanCandidate
import dev.tobins.mealplanner.pricing.Pricing
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.server.ingredients.IngredientRepository
import dev.tobins.mealplanner.server.ingredients.StockRepository
import dev.tobins.mealplanner.server.plan.MealAdjustments
import dev.tobins.mealplanner.server.plan.MealChanges
import dev.tobins.mealplanner.server.prices.PriceRepository
import kotlinx.serialization.json.Json

/** A recipe's base yield and its lines, each with the ingredient it means (null when unknown). */
data class RecipeLines(val servings: Int, val lines: List<Pair<IngredientLine, Ingredient?>>)

/**
 * The recipes as the plan, the planner and the shopping list read them: as candidates to plan (my recipes in the
 * rotation, one put in a slot by hand, the standard meals), with their lines and ingredients, fibre and cost.
 */
class RecipeCandidates(
    private val store: RecipeStore,
    /** The catalogue the recipes' lines mean ingredients of; also what a planned meal's own lines name ([MealAdjustments]). */
    val ingredients: IngredientRepository,
) {
    /**
     * One recipe as a plan candidate, even with incomplete nutrition (used when the user
     * puts a recipe in a slot themselves). Null when the recipe has no amounts at all.
     * Callers make sure it is one of my recipes first ([MyRecipes.requireMine]). With [freshPacks] (and the ingredients
     * [onHand]) it has its [PlanCandidate.freshUses] as [planCandidates] gives them: for the packs a week opened.
     */
    fun candidate(id: Long, freshPacks: Map<Long, Pack> = emptyMap(), onHand: Set<Long> = emptySet()): PlanCandidate? {
        val row = store.byId(id) ?: return null
        val resolve = ingredients.resolver()
        val lines = store.linesOf(id)
        val nutrition = nutritionOf(row, lines, resolve) ?: return null
        return PlanCandidate(
            recipeId = row.id,
            title = row.title,
            slots = slotsOf(row),
            servings = row.servings!!.toInt(),
            kcal = nutrition.perServing.kcal ?: 0.0,
            proteinG = nutrition.perServing.proteinG ?: 0.0,
            fibreG = nutrition.perServing.fibreG ?: 0.0,
            freshUses = if (freshPacks.isEmpty()) emptyList() else freshUsesOf(row.servings!!.toInt(), lines, resolve, freshPacks, onHand),
        )
    }

    /**
     * Grams of fibre in a serving of each of [recipeIds] as the recipe's nutrition is now, by id; recipes
     * that are gone or have no amounts are left out. For the fibre of planned meals ([dev.tobins.mealplanner.api.PlannedMeal.fibreG]).
     */
    fun fibrePerServing(recipeIds: Collection<Long>): Map<Long, Double> {
        val resolve = ingredients.resolver()
        return recipeIds.distinct().mapNotNull { id ->
            val row = store.byId(id) ?: return@mapNotNull null
            val nutrition = nutritionOf(row, store.linesOf(id), resolve) ?: return@mapNotNull null
            id to (nutrition.perServing.fibreG ?: 0.0)
        }.toMap()
    }

    /**
     * The recipes the planner may pick on its own: my recipes in the rotation that are meals with complete
     * nutrition. One of my recipes outside the rotation is planned only by hand ([candidate]).
     *
     * @param dislikes words for ingredients never to plan; a recipe with a line whose text
     *   or linked ingredient name contains one of them as a word (or its plural) is left out.
     * @param onHand the ingredients on hand in the week being planned ([StockRepository.onHandFor]).
     * @param freshPacks the packs of the fresh ingredients ([PriceRepository.freshPacks]); a recipe's
     *   [PlanCandidate.freshUses] are those of its ingredients that have one and are not on hand.
     * @param everyMeal every one of my recipes that is a meal with amounts, in the rotation or not, disliked or not and
     *   with complete nutrition or not: those the user may put in a plan by hand ([MealChanges.choose]), as the planning
     *   screen's search finds them (issue 255).
     */
    fun planCandidates(
        onOffer: Set<Long> = emptySet(),
        dislikes: List<String> = emptyList(),
        prices: Pricing = Pricing.NONE,
        onHand: Set<Long> = emptySet(),
        freshPacks: Map<Long, Pack> = emptyMap(),
        everyMeal: Boolean = false,
    ): List<PlanCandidate> {
        val resolve = ingredients.resolver()
        val disliked = dislikes.takeUnless { everyMeal }.orEmpty().map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .map { Regex("\\b${Regex.escape(it)}(s|es|en|n)?\\b", RegexOption.IGNORE_CASE) }
        return store.mine().mapNotNull { row ->
            if (row.kind != RecipeKind.MEAL.name || (row.inRotation == 0L && !everyMeal)) return@mapNotNull null
            val lines = store.linesOf(row.id)
            if (disliked.isNotEmpty() && lines.any { line ->
                    val names = listOfNotNull(line.ingredientText, resolve(line)?.name)
                    disliked.any { word -> names.any(word::containsMatchIn) }
                }
            ) return@mapNotNull null
            val nutrition = nutritionOf(row, lines, resolve)?.takeIf { it.complete || everyMeal } ?: return@mapNotNull null
            val cost = costOf(row, lines, resolve, prices)
            PlanCandidate(
                recipeId = row.id,
                title = row.title,
                slots = slotsOf(row),
                servings = row.servings!!.toInt(),
                kcal = nutrition.perServing.kcal ?: 0.0,
                proteinG = nutrition.perServing.proteinG ?: 0.0,
                fibreG = nutrition.perServing.fibreG ?: 0.0,
                tags = Json.decodeFromString(row.tagsJson),
                onHandCount = lines.mapNotNull { resolve(it)?.id }.distinct().count { it in onHand },
                offerCount = lines.mapNotNull { resolve(it)?.id }.distinct().count { it in onOffer },
                costPerServing = cost?.perServingWithOffers,
                rating = row.rating?.toInt(),
                freshUses = if (freshPacks.isEmpty()) emptyList() else freshUsesOf(row.servings!!.toInt(), lines, resolve, freshPacks, onHand),
                regularCostPerServing = cost?.perServing,
            )
        }
    }

    /** What a serving of the recipe takes of each of its fresh ingredients that is not on hand, in grams. */
    private fun freshUsesOf(
        servings: Int,
        lines: List<IngredientLine>,
        resolve: (IngredientLine) -> Ingredient?,
        freshPacks: Map<Long, Pack>,
        onHand: Set<Long>,
    ): List<FreshUse> = lines.mapNotNull { line ->
        val ingredient = resolve(line)?.takeIf { it.id in freshPacks && it.id !in onHand && !it.negligible } ?: return@mapNotNull null
        NutritionCalculator.quantityOf(line, ingredient)?.grams?.let { ingredient.id to it }
    }.groupBy({ it.first }, { it.second }).map { (id, grams) -> FreshUse(id, grams.sum() / servings, freshPacks.getValue(id)) }

    /**
     * Each recipe's base yield and its lines with the ingredient each one means, by recipe id; recipes
     * that are gone are left out. One lookup of the catalogue serves them all.
     */
    fun linesWithIngredients(recipeIds: Collection<Long>): Map<Long, RecipeLines> {
        val resolve = ingredients.resolver()
        return recipeIds.distinct().mapNotNull { id ->
            val row = store.byId(id) ?: return@mapNotNull null
            id to RecipeLines(row.servings?.toInt() ?: 1, store.linesOf(id).map { it to resolve(it) })
        }.toMap()
    }

    /** Cost per serving of one recipe with [prices]; null when it cannot be priced. */
    fun cost(recipeId: Long, prices: Pricing): RecipeCost? {
        val row = store.byId(recipeId) ?: return null
        return costOf(row, store.linesOf(recipeId), ingredients.resolver(), prices)
    }

    /**
     * What a standard meal [id] is made of, as the pantry's staples go (issue 265): the ingredients its lines are linked
     * to, each once, in the recipe's order, but not those that count for nothing (spices, water), which the shopping
     * list never buys; and the texts of its lines linked to none yet, but not those used to taste.
     */
    internal fun standardIngredients(id: Long): Pair<List<Ingredient>, List<String>> {
        val resolve = ingredients.resolver()
        val linked = ArrayList<Ingredient>()
        val unlinked = LinkedHashSet<String>()
        for (line in store.linesOf(id)) {
            val ingredient = resolve(line)
            when {
                ingredient != null -> if (!ingredient.negligible) linked += ingredient
                line.amount != null || !NutritionCalculator.isSeasoning(line.ingredientText, null) -> unlinked += IngredientLine.coreText(line.ingredientText)
            }
        }
        return linked.distinctBy { it.id } to unlinked.toList()
    }

    /** My standard meals (issue 265) as plan candidates: the meals marked so that have amounts to size a portion by, by title. */
    fun standardMeals(): List<PlanCandidate> =
        store.mine().filter { it.standardMeal != 0L && it.kind == RecipeKind.MEAL.name }.mapNotNull { candidate(it.id) }
}
