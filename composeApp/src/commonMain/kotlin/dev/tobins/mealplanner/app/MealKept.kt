package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal

/**
 * Why a meal on the Plan keeps its recipe and its place, so that its menu offers neither another recipe, a chosen
 * one, a new one nor a prep for it: the server answers 409 to each (`PlanRepository.whyKeepsItsRecipe`), because
 * the stock went down for the meal or a batch is made for it.
 */
enum class WhyKept {
    /** Marked eaten: what it took from the stock would be lost with it. */
    EATEN,

    /** The leftover lunch of a dinner that was eaten: cooked with it, so its share is used whether the lunch is eaten or not. */
    DINNER_EATEN,

    /** A meal of a prepped batch that was made (one of its meals is eaten): its share is in the box and in what the stock gave. */
    BATCH_MADE,

    /** The meal that makes a prepped batch: it stays while the batch does, so the batch is removed instead. */
    MAKES_BATCH,
}

/**
 * Why this meal, on the day [date], keeps its recipe ([WhyKept]), or null when it may be replaced. [dayBefore] are the
 * meals of the day before in the same plan: a Monday lunch's dinner is in last week's plan, which this one cannot see,
 * so its menu still offers what the server may then refuse (and say why).
 */
fun PlannedMeal.whyKept(date: String, dayBefore: List<PlannedMeal>): WhyKept? {
    val prep = prep
    return when {
        status == MealStatus.EATEN -> WhyKept.EATEN
        prep != null -> when {
            prep.meals.any { it.status == MealStatus.EATEN } -> WhyKept.BATCH_MADE
            prep.meals.firstOrNull()?.let { it.date == prep.madeOn && it.date == date && it.slot == slot && !it.side } == true -> WhyKept.MAKES_BATCH
            else -> null
        }
        slot == MealSlot.LUNCH && leftover && dayBefore.any {
            it.slot == MealSlot.DINNER && !it.leftover && it.prep == null && it.recipeId == recipeId && it.status == MealStatus.EATEN
        } -> WhyKept.DINNER_EATEN
        else -> null
    }
}

/**
 * True when the side next to this meal, on the day [date], stays with it: its batch was made (a meal or side of it is
 * eaten) or it makes the batch, which goes only as a whole through "Remove the side's prep" (`PlanRepository.whySideStays`).
 */
fun PlannedMeal.sideStays(date: String): Boolean {
    val prep = side?.prep ?: return false
    val first = prep.meals.firstOrNull()
    return prep.meals.any { it.status == MealStatus.EATEN } ||
        (first != null && first.date == prep.madeOn && first.date == date && first.slot == slot && first.side)
}

/** True when this prepped meal's share was eaten in another meal: nothing is left of it to eat, so it can only stay skipped. */
fun PlannedMeal.shareGone(date: String): Boolean {
    val ofBatch = prep?.meals?.firstOrNull { it.date == date && it.slot == slot && !it.side } ?: return false
    return status == MealStatus.SKIPPED && ofBatch.portions == 0.0
}

/**
 * Whether this dinner, on the day [date], may move to another day or give its day to another dinner (issue 255), as the
 * server lets it (`PlanRepository.whyStaysOnItsDay`): not one that keeps its recipe ([whyKept]), of a prepped batch or
 * with a side, which are eaten on their days; and its leftover lunch in [dayAfter], if it has one, must be able to follow
 * it: still planned, not locked and without a side. A skipped prepped meal's share in the box under it, which the plan
 * does not show, the server refuses with a sentence of its own.
 */
fun PlannedMeal.canMove(date: String, dayBefore: List<PlannedMeal>, dayAfter: List<PlannedMeal>): Boolean {
    if (slot != MealSlot.DINNER || whyKept(date, dayBefore) != null || prep != null || side != null) return false
    val leftovers = dayAfter.firstOrNull { it.slot == MealSlot.LUNCH && it.leftover && it.prep == null && it.recipeId == recipeId }
    return leftovers == null || (leftovers.status == MealStatus.PLANNED && !leftovers.locked && leftovers.side == null)
}
