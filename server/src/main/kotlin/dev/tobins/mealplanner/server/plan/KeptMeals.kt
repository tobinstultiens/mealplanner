package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.ClosedSlot
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.server.ConflictException
import dev.tobins.mealplanner.server.plan.WeekMeals.Companion.dinnerOf
import java.time.LocalDate

/**
 * Which meals of a week stay as they are, and why: those planning the week keeps ([kept]), a meal that keeps its recipe
 * because the stock went down for it or it makes a batch ([whyKeepsItsRecipe]), a side that stays ([whySideStays]), and
 * the slots no standard meal can take ([whyNoStandardMeal]). Every change of a meal asks here first.
 */
class KeptMeals(private val store: PlanStore, private val weekMeals: WeekMeals) {
    /**
     * The meals of [week] (starting [start]) that planning it keeps as they are ([WeekPlans.generate]): locked ones, those eaten or
     * skipped (history), one with a side or foods added by hand (a slot of foods alone too, which the planner never fills
     * itself), one with a skipped prepped meal's share in the box under its slot, and any other meal the stock went down
     * for ([whyKeepsItsRecipe]).
     */
    internal fun kept(start: LocalDate, week: List<PlannedMeal>): List<PlannedMeal> {
        val boxes = weekMeals.boxesIn(start)
        return week.filter {
            it.locked || it.status != MealStatus.PLANNED || it.side != null || it.foodsId != null || (it.day to it.slot) in boxes ||
                whyKeepsItsRecipe(start, it, week) != null
        }
    }

    /**
     * Why [meal] keeps its recipe and its place in the plan, or null when it may be replaced or removed.
     * The stock went down for it: it was eaten, or it is the leftover lunch of a dinner that was eaten
     * ([Eating.setStatus] took its share too), or what was taken for it is kept under its slot all the same
     * (a leftover lunch whose eaten dinner was removed, which servers before issue 165 allowed). What a
     * meal took is kept under its day and slot and goes with the meal, so replacing or removing it would
     * lose that stock for good; the user un-eats the meal (or its dinner) first.
     */
    internal fun whyKeepsItsRecipe(start: LocalDate, meal: PlannedMeal, week: List<PlannedMeal>): String? = when {
        meal.status == MealStatus.EATEN -> "This meal was eaten already"
        meal.prep != null -> whyKeepsItsBatch(start, meal)
        dinnerOf(meal, week)?.status == MealStatus.EATEN -> "The dinner before it was eaten, and these leftovers with it"
        weekMeals.holdsStock(start, meal) -> "These leftovers were cooked with a dinner that was eaten"
        else -> null
    }

    /**
     * Why [meal], of a prepped batch, keeps its place: it makes the batch, which goes only as a whole (`DELETE
     * .../preps/{id}`, after the app named the later meals), or the batch was made, so its share is in the box
     * and in what the stock gave. A later meal of a batch still to make may be replaced or removed: the batch
     * is then made smaller.
     */
    private fun whyKeepsItsBatch(start: LocalDate, meal: PlannedMeal): String? {
        val batch = weekMeals.batchesOf(listOf(meal)).values.singleOrNull() ?: return null
        return when {
            batch.made -> "This meal is from a prepped batch that was made already: mark it skipped instead"
            batch.isCook(start, meal) && batch.meals.size > 1 ->
                "This meal makes the prepped batch for ${batch.laterMealsText()}: remove the prep to take those out too"
            batch.isCook(start, meal) -> "This meal makes a prepped batch: remove the prep instead"
            else -> null
        }
    }

    /** Refuses to replace or remove [meal] when it keeps its recipe ([whyKeepsItsRecipe]). */
    internal fun requireNotCooked(start: LocalDate, meal: PlannedMeal, week: List<PlannedMeal>) {
        whyKeepsItsRecipe(start, meal, week)?.let { throw ConflictException(it) }
    }

    /**
     * [requireNotCooked] for whatever is in a slot; an empty slot can be filled. Writing a new recipe
     * asks it first, so that Claude does not write one for a meal it may not replace.
     */
    fun requireReplaceable(weekStart: String, day: Int, slot: MealSlot) {
        val start = parseWeekStart(weekStart)
        requireReplaceable(start, weekMeals.of(start), day, slot)
    }

    internal fun requireReplaceable(start: LocalDate, week: List<PlannedMeal>, day: Int, slot: MealSlot) {
        week.firstOrNull { it.day == day && it.slot == slot }?.let { requireNotCooked(start, it, week) }
    }

    /**
     * The next day's leftover lunch that changes along with the dinner in [day]'s [slot]: only one
     * still planned, not locked and that may be replaced ([whyKeepsItsRecipe]). An eaten or skipped one
     * is history and stays as it is; an eaten one, or one that holds stock, keeps what was taken from
     * the stock, to give it back when it is un-eaten. One with a side added by hand stays as it is too, as
     * it would go when the new dinner leaves no leftovers, and its side with it; so does one with a share in the
     * box under its slot (issue 265).
     */
    internal fun leftoversToReplace(start: LocalDate, week: List<PlannedMeal>, day: Int, slot: MealSlot): PlannedMeal? = week.firstOrNull {
        slot == MealSlot.DINNER && it.day == day + 1 && it.slot == MealSlot.LUNCH && it.leftover && replaceableLunch(start, week, it)
    }

    /**
     * Whether [lunch] of [week] may change when the dinner before it changes day ([MealChanges.moveDinner]): one still planned, not
     * locked, of no batch, without a side or foods and without a share in the box under it, that does not keep its recipe
     * ([whyKeepsItsRecipe]). A leftover lunch of that kind changes with its dinner, as [leftoversToReplace] has it; so may
     * any other lunch of that kind, which is the planner's to choose.
     */
    internal fun replaceableLunch(start: LocalDate, week: List<PlannedMeal>, lunch: PlannedMeal): Boolean =
        lunch.prep == null && !lunch.locked && lunch.side == null && lunch.foodsId == null && lunch.status == MealStatus.PLANNED &&
            whyKeepsItsRecipe(start, lunch, week) == null && weekMeals.box(start, lunch.day, lunch.slot) == null

    /**
     * Why the side next to [meal] of the week starting [start] stays in the plan, or null when it has none or it may go
     * with its meal or on its own, which makes its batch smaller. It makes its batch, which goes only as a whole, as the
     * meal that makes one does ([whyKeepsItsBatch]); or its batch was made, so its share is in the box and in what the
     * stock gave.
     */
    internal fun whySideStays(start: LocalDate, meal: PlannedMeal): String? {
        val batch = meal.side?.let { store.batches(listOf(it.prep.id)).values.singleOrNull() } ?: return null
        return when {
            batch.made -> "The side is from a prepped batch that was made already: mark the meal skipped if it is not eaten"
            batch.isCook(start, meal, side = true) && batch.meals.size > 1 ->
                "The side makes the prepped batch for ${batch.laterMealsText()}: remove the prep to take those out too"
            batch.isCook(start, meal, side = true) -> "The side makes a prepped batch: remove the prep instead"
            else -> null
        }
    }

    /**
     * Why no standard meal can take the place of [meal] of the week starting [start] (issue 265), or null when one can: in
     * the place of a meal that may be replaced ([whyKeepsItsRecipe]), and of a skipped later meal of a prepped batch, as
     * its share stays in the box. Not of the meal that makes a batch, which stays while the batch does, skipped or not;
     * nor of a meal with a prepped side, which is eaten or skipped with whatever is in its slot.
     */
    internal fun whyNoStandardMeal(start: LocalDate, meal: PlannedMeal, week: List<PlannedMeal>): String? {
        if (meal.side != null) return "This meal has a prepped side, which is eaten or skipped with it: a standard meal can't take its place"
        if (meal.prep == null || meal.status != MealStatus.SKIPPED) return whyKeepsItsRecipe(start, meal, week)
        val batch = weekMeals.batchesOf(listOf(meal)).values.singleOrNull() ?: return null
        return if (batch.isCook(start, meal)) "This meal makes the prepped batch, which stays in the plan with it" else null
    }

    /** The meals of the week starting [start] that no standard meal can take the place of, each with why ([whyNoStandardMeal]). */
    fun closedToStandardMeals(start: LocalDate): List<ClosedSlot> {
        val week = weekMeals.of(start)
        return week.mapNotNull { meal -> whyNoStandardMeal(start, meal, week)?.let { ClosedSlot(meal.day, meal.slot, it) } }
    }
}
