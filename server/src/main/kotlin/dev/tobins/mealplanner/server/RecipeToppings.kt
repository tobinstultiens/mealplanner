package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.AddOn
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.ToppingsInput
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.db.SelectRecipeMealLines

/**
 * One account's optional toppings of its recipes, and the quick add-ons of a recipe's meals (issue 226).
 * A topping is kept apart from the recipe's lines (`recipeTopping`), so a sync of a cooking-site recipe
 * never changes it, and nothing counts it (nutrition, cost, shopping) until a meal has it added.
 */
class RecipeToppings(private val db: Database, private val userId: Long, private val recipes: RecipeRepository) {
    private val q = db.recipeQueries

    /**
     * Replaces recipe [recipeId]'s toppings with [input]'s, in their order, and answers the recipe. Any recipe of
     * the account's, in the bank or one of mine; another account's is a [NotFoundException], as is an ingredient
     * not in its catalogue. Each ingredient at most once, above 0 and up to [MealAdjustments.MAX_GRAMS] g, at most
     * [MAX_TOPPINGS] of them.
     */
    fun set(recipeId: Long, input: ToppingsInput): RecipeDetail {
        val toppings = input.toppings
        require(toppings.size <= MAX_TOPPINGS) { "at most $MAX_TOPPINGS toppings" }
        require(toppings.distinctBy { it.ingredientId }.size == toppings.size) { "each ingredient at most once" }
        for (topping in toppings) {
            require(topping.grams.isFinite() && topping.grams > 0.0 && topping.grams <= MealAdjustments.MAX_GRAMS) {
                "grams must be above 0 and at most ${MealAdjustments.MAX_GRAMS.toInt()}"
            }
        }
        db.writing {
            requireRecipe(recipeId)
            for (topping in toppings) {
                recipes.ingredients.find(topping.ingredientId) ?: throw NotFoundException("No ingredient with id ${topping.ingredientId}")
            }
            q.deleteToppings(userId, recipeId)
            toppings.forEachIndexed { position, topping -> q.insertTopping(userId, recipeId, topping.ingredientId, position.toLong(), topping.grams) }
        }
        return recipes.find(recipeId)!!
    }

    /**
     * What a meal of recipe [recipeId] can have added with one tap: its toppings first, in their order, then
     * the ingredients added to its meals before (in any week) that it does not have, most often added first
     * and then the most recently, each with the amount its latest meal had; at most [MAX_EARLIER] of those.
     * A topping is offered whatever the recipe has: the app adds its amount on top of what the meal has. From
     * earlier meals, though, an ingredient the recipe has is no add-on: a meal stores what it ate of it in all,
     * not what was extra, and that depended on its portions and on the recipe as it was then.
     */
    fun addOns(recipeId: Long): List<AddOn> {
        requireRecipe(recipeId)
        val toppings = recipes.toppings(recipeId)
        val inRecipe = recipes.linesWithIngredients(listOf(recipeId))[recipeId]?.lines.orEmpty().mapNotNullTo(HashSet()) { it.second?.id }
        val added = db.planQueries.selectRecipeMealLines(userId, recipeId).executeAsList()
            .filter { it.ingredientId !in inRecipe }
            .groupBy { it.ingredientId }
        val first = toppings.map { AddOn(it.ingredientId, it.name, it.grams, it.gramsPerPiece, topping = true, times = added[it.ingredientId]?.size ?: 0) }
        val catalogue = recipes.ingredients.list().associateBy { it.id }
        val earlier = added.filterKeys { id -> toppings.none { it.ingredientId == id } }.mapNotNull { (id, meals) ->
            val ingredient = catalogue[id] ?: return@mapNotNull null
            val latest = meals.maxWith(planOrder)
            latest to AddOn(id, ingredient.name, latest.grams, ingredient.gramsPerPiece, times = meals.size)
        }
            .sortedWith(compareByDescending<Pair<SelectRecipeMealLines, AddOn>> { it.second.times }.thenByDescending(planOrder) { it.first }.thenBy { it.second.name.lowercase() })
            .take(MAX_EARLIER)
            .map { it.second }
        return first + earlier
    }

    /** Fails unless [id] is one of the account's recipes, so another account's answers 404. */
    private fun requireRecipe(id: Long) {
        q.selectById(userId, id).executeAsOneOrNull() ?: throw NotFoundException("No recipe with id $id")
    }

    companion object {
        /** More toppings than any recipe offers: a list sent by mistake. */
        const val MAX_TOPPINGS = 20

        /** The add-ons from earlier meals offered at most, next to the toppings: a row of chips, not a catalogue. */
        const val MAX_EARLIER = 8

        /** Meals in the order they were planned: by week, day and slot. */
        private val planOrder = compareBy<SelectRecipeMealLines>({ it.weekStart }, { it.day }, { MealSlot.valueOf(it.slot).ordinal })
    }
}
