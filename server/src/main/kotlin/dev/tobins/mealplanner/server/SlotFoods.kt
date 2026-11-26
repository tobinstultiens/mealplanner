package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.MealFood
import dev.tobins.mealplanner.api.MealFoodInput
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.db.RecipeIngredient
import dev.tobins.mealplanner.units.Dimension
import java.time.Instant

/**
 * The foods planned in the slots of one account's plan (issue 358; README, "Foods in a slot"): ingredients and products
 * of its catalogue, each in an amount of a unit it has, kept as it was picked ("2 bar"). A slot's foods are the lines of a
 * recipe the plan makes for that slot alone (`recipe.slotFoods`, [PlannedMeal.foodsId]), one serving, never added, so in
 * no list of recipes: the shopping list, the stock and the numbers read them as they read any recipe's lines. With foods
 * alone that recipe is the slot's meal ([PlannedMeal.foodsOnly]), which an app that does not know foods reads as a meal of
 * a recipe; next to a recipe the foods count with it ([withFoods]). Which meal holds them, and the stock, is
 * [PlanRepository]'s.
 */
class SlotFoods(private val db: Database, private val userId: Long, private val recipes: RecipeRepository) {
    private val q = db.recipeQueries

    /** [input] as the line of a food ([foodLine]): the account's ingredient (another's is not there, 404). */
    fun lineOf(input: MealFoodInput): IngredientLine {
        val ingredient = recipes.ingredients.find(input.ingredientId) ?: throw NotFoundException("No ingredient with id ${input.ingredientId}")
        return foodLine(ingredient, input)
    }

    /** Makes the recipe of a slot's foods with [line] as its first, and answers its id. In the caller's transaction. */
    fun create(line: IngredientLine): Long {
        q.insertSlotFoods(userId, title(listOf(line.item)), Instant.now().toString())
        val id = q.lastInsertId().executeAsOne()
        insert(id, line, position = 0)
        return id
    }

    /** Adds [line] as the last of the foods of [foodsId], at most [MAX_FOODS] of them. In the caller's transaction. */
    fun add(foodsId: Long, line: IngredientLine) {
        val rows = rowsOf(foodsId)
        require(rows.size < MAX_FOODS) { "a slot holds at most $MAX_FOODS foods" }
        insert(foodsId, line, position = (rows.maxOfOrNull { it.position } ?: -1L) + 1)
        retitle(foodsId)
    }

    /** Replaces food [foodId] of [foodsId] with [line]; a food that is not there is a [NotFoundException]. */
    fun change(foodsId: Long, foodId: Long, line: IngredientLine) {
        requireFood(foodsId, foodId)
        q.updateSlotFood(line.amount, line.unit, line.dimension?.name, line.baseAmount, line.item, line.ingredientId, foodId, userId, foodsId)
        retitle(foodsId)
    }

    /** Takes food [foodId] out of [foodsId]; answers how many are left. A food that is not there is a [NotFoundException]. */
    fun remove(foodsId: Long, foodId: Long): Int {
        requireFood(foodsId, foodId)
        q.deleteSlotFood(foodId, userId, foodsId)
        retitle(foodsId)
        return rowsOf(foodsId).size
    }

    /** The ids of the foods of [foodsId], in their order. */
    fun idsOf(foodsId: Long): List<Long> = rowsOf(foodsId).map { it.id }

    /** The name [foodsId] goes by where one line is shown ([title]). */
    fun titleOf(foodsId: Long): String = title(rowsOf(foodsId).map { it.item })

    /**
     * Deletes the recipes of foods that no meal of the plan holds any more, with their lines: every write that may take a
     * slot's foods out (removing the slot, taking out its last food) ends with it; a meal that replaces another keeps the
     * slot's foods ([PlanRepository.save]). Across the account and
     * idempotent, so a write that missed it leaves nothing for longer than the next one.
     */
    fun dropUnused() = q.deleteUnusedSlotFoods(userId)

    /**
     * [meals] with their foods and the numbers they count (issue 358): those of a slot of foods alone are its foods' own, as
     * they are now, the way a recipe's fibre is; next to a recipe, the foods' come on top of the recipe's (adjusted or not).
     */
    fun withFoods(meals: List<PlannedMeal>): List<PlannedMeal> {
        val ids = meals.mapNotNull { it.foodsId }.distinct()
        if (ids.isEmpty()) return meals
        val resolve = recipes.ingredients.resolver()
        val foods = ids.associateWith { id -> rowsOf(id).map { food(it, resolve) } }
        return meals.map { meal ->
            val listed = meal.foodsId?.let(foods::get) ?: return@map meal
            val kcal = listed.sumOf { it.kcal }
            val protein = listed.sumOf { it.proteinG }
            val fibre = listed.sumOf { it.fibreG }
            if (meal.foodsOnly) {
                meal.copy(foods = listed, kcal = kcal, proteinG = protein, fibreG = fibre)
            } else {
                meal.copy(foods = listed, kcal = meal.kcal + kcal, proteinG = meal.proteinG + protein, fibreG = (meal.fibreG ?: 0.0) + fibre)
            }
        }
    }

    /** The kcal and protein of the foods of [foodsId], as a slot of foods alone stores them for apps that do not know foods. */
    fun numbersOf(foodsId: Long): Pair<Double, Double> {
        val resolve = recipes.ingredients.resolver()
        val foods = rowsOf(foodsId).map { food(it, resolve) }
        return foods.sumOf { it.kcal } to foods.sumOf { it.proteinG }
    }

    /** Grams of each ingredient that eating the foods next to [meal]'s recipe uses; none for a meal without, or of foods alone. */
    fun gramsNextTo(meal: PlannedMeal): Map<Long, Double> {
        val id = meal.foodsId?.takeUnless { meal.foodsOnly } ?: return emptyMap()
        return recipes.linesWithIngredients(listOf(id))[id]?.let { gramsUsed(it, 1.0) }.orEmpty()
    }

    private fun food(row: RecipeIngredient, resolve: (IngredientLine) -> Ingredient?): MealFood {
        val line = lineOf(row)
        val ingredient = resolve(line)
        val counted = ingredient?.let { countOf(line, it) }
        return MealFood(
            id = row.id,
            ingredientId = ingredient?.id ?: row.ingredientId ?: 0,
            name = ingredient?.name ?: row.item,
            amount = row.amount ?: 0.0,
            unit = row.unit.orEmpty(),
            grams = counted?.grams,
            kcal = counted?.kcal ?: 0.0,
            proteinG = counted?.proteinG ?: 0.0,
            fibreG = counted?.fibreG ?: 0.0,
            counted = counted?.counted == true,
        )
    }

    private fun lineOf(row: RecipeIngredient) = IngredientLine(
        amount = row.amount,
        unit = row.unit,
        dimension = row.dimension?.let(Dimension::valueOf),
        baseAmount = row.baseAmount,
        item = row.item,
        group = row.groupName,
        ingredientId = row.ingredientId,
    )

    /** The foods' lines, in their order. [foodsId] is read from the account's plan, or was just made for it. */
    private fun rowsOf(foodsId: Long): List<RecipeIngredient> = q.selectIngredients(foodsId).executeAsList()

    private fun insert(foodsId: Long, line: IngredientLine, position: Long) =
        q.insertIngredient(foodsId, position, line.amount, line.unit, line.dimension?.name, line.baseAmount, line.item, null, line.ingredientId)

    private fun requireFood(foodsId: Long, foodId: Long) {
        if (foodId !in idsOf(foodsId)) throw NotFoundException("No food with id $foodId in this meal")
    }

    /** The recipe's title after a change, and when it changed, which makes its units the last picked ([IngredientRepository.list]). */
    private fun retitle(foodsId: Long) = q.setSlotFoodsTitle(titleOf(foodsId), Instant.now().toString(), userId, foodsId)

    companion object {
        /** More foods than a slot holds: a list made by mistake. */
        const val MAX_FOODS = 30

        /**
         * The foods' names on one line, for the readers that show one (an app that does not know foods, notifications):
         * one or two of them as they are ("Banana, Protein bar"), more as the first and how many follow ("Banana + 2 more").
         */
        fun title(names: List<String>): String = when (names.size) {
            0 -> ""
            1, 2 -> names.joinToString(", ")
            else -> "${names.first()} + ${names.size - 1} more"
        }
    }
}
