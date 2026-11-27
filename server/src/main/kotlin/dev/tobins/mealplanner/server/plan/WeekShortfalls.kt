package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.LaterShortfall
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.server.ingredients.MealRef
import dev.tobins.mealplanner.server.ingredients.StockRepository
import dev.tobins.mealplanner.server.plan.WeekMeals.Companion.dinnerOf
import dev.tobins.mealplanner.server.plan.WeekMeals.Companion.mealAt
import dev.tobins.mealplanner.server.recipes.RecipeLines
import dev.tobins.mealplanner.server.recipes.RecipeCandidates
import dev.tobins.mealplanner.server.shopping.gramsUsed
import dev.tobins.mealplanner.server.shopping.withoutWeighedRow
import java.time.LocalDate

/**
 * What the adjusted meals of a week leave its later meals short of (issue 226): for the shopping list, which asks for it
 * again, and for the Adjust screen, which warns of it ([warnings]). The meals are walked in the order they are cooked,
 * each taking from what was at home for it.
 */
class WeekShortfalls(
    private val store: PlanStore,
    private val weekMeals: WeekMeals,
    private val adjustments: MealAdjustments,
    private val recipes: RecipeCandidates,
    private val stock: StockRepository,
) {
    /**
     * What the adjustments of the week starting [start] leave its meals still to cook short of (issue 226,
     * [dev.tobins.mealplanner.server.plan.shortfalls]), from what was at home for them ([StockRepository.atHome]).
     * The meals walked are those cooked ([WeekMeals.shareTaken]) or still to cook ([WeekMeals.stillToCook]), in the order they
     * are cooked ([cookingOrder]). An ingredient the week's list has no weighed row for ([withoutWeighedRow]:
     * a staple, one to taste, one some line of the week can't weigh) counts as not known at home, so it is
     * never short: there would be no row to ask for it again, and the warning would say what the list doesn't.
     * Nothing without an adjusted meal in the week. [week] is the week as the shopping list counts it
     * ([WeekMeals.forShopping]): a prepped batch made this week is cooked as one meal, with what each of its meals had, also
     * one made next to a meal as its side, which is a meal of its own there.
     */
    fun shortfallsIn(start: LocalDate, week: List<PlannedMeal>): Shortfalls {
        val lines = recipes.linesWithIngredients(week.map { it.recipeId })
        val changes = changesForShopping(start, week, lines)
        if (changes.isEmpty()) return Shortfalls.NONE
        // By the meals themselves, not their slots: a batch cooked as a side shares its meal's slot.
        val toCook = weekMeals.stillToCook(start, week).toSet()
        val cooked = week.filter { it in toCook || weekMeals.shareTaken(start, it, week) }.sortedBy { cookingOrder(it, week) }
        val noRow = lines.values.flatMapTo(HashSet(), ::withoutWeighedRow)
        val (elsewhere, here) = cookedAcrossWeeks(start)
        return shortfalls(adjustments.demands(cooked, lines, changes::get, toCook), stock.atHome(start, elsewhere, here) + noRow.associateWith { null })
    }

    /**
     * The meals and sides of batches eaten across two weeks, for what was at home for the meals of the week starting
     * [start] ([StockRepository.atHome]): first those of this week of a batch made in the week before, then those of the
     * next week of a batch made in this one. A batch is cooked from the stock of the week it is made in ([Eating]).
     */
    private fun cookedAcrossWeeks(start: LocalDate): Pair<Set<MealRef>, List<MealRef>> {
        val batches = store.batches(weekMeals.of(start).flatMap { listOfNotNull(it.prep?.id, it.side?.prep?.id) } + weekMeals.boxesIn(start).values).values
        val elsewhere = batches.filter { it.week != start }.flatMap { batch -> batch.meals.filter { it.week == start }.map { it.ref } }
        val here = batches.filter { it.week == start }.flatMap { batch -> batch.meals.filter { it.week != start }.map { it.ref } }
        return elsewhere.toSet() to here
    }

    /**
     * The changes of [week]'s adjusted meals ([MealAdjustments.changesIn]), [week] as [WeekMeals.forShopping] has it, by meal rather
     * than slot, as a batch cooked as a side shares its slot with the meal it goes with, whose changes are that meal's
     * own. A prepped batch, one meal there, has what all its meals ate of each ingredient one of them changed, in
     * whichever week they are ([batchChanges]); also a batch cooked as a side, whose meals may be eaten as meals too
     * (issue 269). [lines] are the recipes' lines.
     */
    private fun changesForShopping(start: LocalDate, week: List<PlannedMeal>, lines: Map<Long, RecipeLines>): Map<PlannedMeal, Map<Long, Double>> {
        val ofSlots = adjustments.changesIn(start)
        val batches = weekMeals.batchesOf(week)
        return week.mapNotNull { meal ->
            val batch = meal.prep?.let { batches[it.id] }
            val changes = when {
                // Foods are never adjusted: those next to a recipe share its slot, whose changes are the recipe's.
                meal.foodsOnly -> null
                batch == null -> ofSlots[meal.day to meal.slot]
                else -> batchChanges(batch, lines[batch.recipeId])
            }
            changes?.let { meal to it }
        }.toMap()
    }

    /**
     * What [batch], cooked as one meal, ate of each ingredient one of its meals changed: those meals' grams, and the others'
     * from its [recipe]; null when none of its meals was adjusted. A side can't be adjusted: what its meal changed is the main's.
     */
    private fun batchChanges(batch: Batch, recipe: RecipeLines?): Map<Long, Double>? {
        val shares = batch.meals.filter { it.share > 0.0 }
        val changed = shares.associateWith { if (it.side || it.boxed) emptyMap() else adjustments.changesOf(it.week, it.meal) }
        val ids = changed.values.flatMapTo(HashSet()) { it.keys }
        if (ids.isEmpty() || recipe == null) return null
        return ids.associateWith { id -> shares.sumOf { share -> changed.getValue(share)[id] ?: gramsUsed(recipe, share.share)[id] ?: 0.0 } }
    }

    /**
     * The meals cooked after [meal] that its week leaves short ([shortfallsIn]) of an ingredient [meal] took more
     * of than its recipe from what was at home: what the Adjust screen warns of. A meal cooked before it took
     * its share first, and an ingredient [meal] had from the fridge took nothing another meal needed. A meal of
     * a prepped batch took what it had when the batch was made, so it is where the batch is in the week: a meal of
     * a batch made in the week before has the warning of the meal that made it, the same in both weeks (issue 372).
     */
    internal fun warnings(start: LocalDate, meal: PlannedMeal, week: List<PlannedMeal>): List<LaterShortfall> {
        val cook = meal.prep?.let { weekMeals.batchesOf(listOf(meal))[it.id] }?.cook?.takeIf { it.week != start }
        if (cook != null) {
            val madeIn = weekMeals.of(cook.week)
            return warnings(cook.week, mealAt(madeIn, cook.meal.day, cook.meal.slot), madeIn)
        }
        val view = weekMeals.forShopping(start, week)
        val result = shortfallsIn(start, view)
        val cooked = meal.prep?.let { prep -> view.firstOrNull { it.prep?.id == prep.id } } ?: meal
        val order = cookingOrder(cooked, view)
        val names = recipes.ingredients.list().associate { it.id to it.name }
        return result.short.filter { short ->
            cookingOrder(short.meal, view) > order &&
                result.taken[short.ingredientId].orEmpty().any { it.demand.meal.day == cooked.day && it.demand.meal.slot == cooked.slot }
        }.map { LaterShortfall(it.meal.day, it.meal.slot, it.meal.title, it.ingredientId, names[it.ingredientId].orEmpty(), it.grams) }
    }

    /**
     * Where [meal] comes in the order [week]'s meals are cooked: by day and slot, but a leftover lunch with the
     * dinner it is cooked with, unless that dinner was skipped and the lunch is cooked for itself.
     */
    private fun cookingOrder(meal: PlannedMeal, week: List<PlannedMeal>): Double {
        val cookedWith = dinnerOf(meal, week)?.takeIf { it.status != MealStatus.SKIPPED }
        return cookedWith?.let { at(it) + 0.5 } ?: at(meal)
    }

    /** [meal]'s place in its week, slot by slot: what [cookingOrder] counts from. */
    private fun at(meal: PlannedMeal): Double = (meal.day * MealSlot.entries.size + meal.slot.ordinal).toDouble()
}
