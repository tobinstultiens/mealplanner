// Frozen from v0.1.12 (dd5ad93e7c8489f1199ba88c609352532d2b298f) by scripts/freeze-api.sh. Do not edit: freeze again after a release.
package dev.tobins.mealplanner.released.planner

import dev.tobins.mealplanner.released.api.MealSlot
import dev.tobins.mealplanner.released.api.Profile
import kotlin.math.roundToInt

/**
 * How large one meal may be, in servings of its recipe: the profile's "portion per meal" (issue 227).
 * The planner sizes every meal in quarter servings within it, so that a plan never holds a meal
 * nobody eats, such as twice what a recipe calls a serving. The rules on sizes are all here, so that
 * planning, choosing by hand and the buy-ahead advice cannot drift apart.
 */
data class PortionRange(val min: Double = DEFAULT_MIN, val max: Double = DEFAULT_MAX) {
    /**
     * The portions a [slot] meal may have, smallest first. Breakfast and snack go up to two servings
     * however low [max] is: a bowl of quark or a piece of fruit written as one serving is simply made
     * larger.
     */
    fun steps(slot: MealSlot): List<Double> {
        val top = if (slot == MealSlot.BREAKFAST || slot == MealSlot.SNACK) maxOf(max, ROUTINE_MAX) else max
        return (quarters(min)..quarters(top)).map { it * STEP }
    }

    /**
     * The portions a [slot] meal may have when only [available] servings are left, as for the
     * leftover lunch of a dinner: those of [steps] that fit, or else all that is left, which may be
     * less than [min] (down to [LEAST_LEFTOVER]) rather than thrown away.
     */
    fun steps(slot: MealSlot, available: Double): List<Double> =
        steps(slot).filter { it <= available }.ifEmpty { listOf(available) }

    /** Why this range cannot be saved, worded like the rest of the profile's problems; empty when it can. */
    fun problems(): List<String> = buildList {
        if (min !in LOWEST..HIGHEST) add("minPortions must be between 0.25 and 3")
        if (max !in LOWEST..HIGHEST) add("maxPortions must be between 0.25 and 3")
        if (!isQuarter(min)) add("minPortions must be in quarter servings")
        if (!isQuarter(max)) add("maxPortions must be in quarter servings")
        if (min > max) add("minPortions must not be more than maxPortions")
    }

    companion object {
        /** Portions go up and down by a quarter serving. */
        const val STEP = 0.25
        const val DEFAULT_MIN = 0.75
        const val DEFAULT_MAX = 1.5

        /** The smallest [min] and the largest [max] a profile may set. */
        const val LOWEST = 0.25
        const val HIGHEST = 3.0

        /** Breakfast and snack may always reach this ([steps]). */
        const val ROUTINE_MAX = 2.0

        /** A dinner leaves a leftover lunch when at least this much of the batch remains. */
        const val LEAST_LEFTOVER = 0.5

        /** The range of [profile], or the defaults without one. */
        fun of(profile: Profile?): PortionRange =
            profile?.let { PortionRange(it.minPortions, it.maxPortions) } ?: PortionRange()

        private fun quarters(servings: Double): Int = (servings / STEP).roundToInt()

        private fun isQuarter(servings: Double): Boolean = servings.isFinite() && quarters(servings) * STEP == servings
    }
}
