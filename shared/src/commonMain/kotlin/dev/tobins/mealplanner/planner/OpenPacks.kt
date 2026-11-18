package dev.tobins.mealplanner.planner

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.PlannedMeal
import kotlin.math.ceil
import kotlin.math.min

/** A pack of a fresh ingredient as the store sells it: [grams] in it, for [euros] at the regular price. */
data class Pack(val grams: Double, val euros: Double)

/**
 * A fresh ingredient a recipe uses, which is bought in a [pack] and keeps only days once opened.
 * [gramsPerServing] is what one serving of the recipe takes of it.
 */
data class FreshUse(val ingredientId: Long, val gramsPerServing: Double, val pack: Pack)

/**
 * The packs of fresh ingredients a plan has opened so far, and what is left in them: the planner prefers
 * a recipe that uses up the rest of a pack the week already buys over one that opens another (issue 222).
 *
 * The rest of a pack counts for meals on the day of the meal that opened it and at most [WINDOW_DAYS]
 * days after (a fresh ingredient keeps about that long, for every ingredient alike). After that it is
 * thrown away, and the next meal that needs the ingredient opens a new pack.
 */
class OpenPacks {
    private class Open(val pack: Pack, val openedOn: Int, var restGrams: Double)

    private val open = mutableMapOf<Long, Open>()

    /** Euros of packs left unused when they were closed: the rest this plan buys and does not eat. */
    var restEuros = 0.0
        private set

    /**
     * What [servings] of a recipe ([uses] per serving) would take from the rest of the packs open on [day],
     * in euros of pack price: the packs it does not have to open for.
     */
    fun reusable(day: Int, uses: List<FreshUse>, servings: Double): Double = uses.sumOf { use ->
        val rest = openOn(day, use.ingredientId)?.restGrams ?: return@sumOf 0.0
        min(rest, use.gramsPerServing * servings) / use.pack.grams * use.pack.euros
    }

    /** Cooks [servings] of a recipe on [day]: takes from the rest of open packs and opens new ones for the remainder. */
    fun cook(day: Int, uses: List<FreshUse>, servings: Double) {
        for (use in uses) {
            var need = use.gramsPerServing * servings
            openOn(day, use.ingredientId)?.let { current ->
                val taken = min(current.restGrams, need)
                current.restGrams -= taken
                need -= taken
            }
            if (need > 0) {
                open.remove(use.ingredientId)?.let(::close)
                val packs = ceil(need / use.pack.grams)
                open[use.ingredientId] = Open(use.pack, day, packs * use.pack.grams - need)
            }
        }
    }

    /** Closes every pack still open, as at the end of the week. */
    fun finish() {
        open.values.forEach(::close)
        open.clear()
    }

    /** The open pack of the ingredient if its rest still counts on [day]: null once its days are past (the next cook closes it). */
    private fun openOn(day: Int, ingredientId: Long): Open? {
        val pack = open[ingredientId] ?: return null
        if (day - pack.openedOn > WINDOW_DAYS) return null
        return pack
    }

    private fun close(pack: Open) {
        restEuros += pack.restGrams / pack.pack.grams * pack.pack.euros
    }

    companion object {
        const val WINDOW_DAYS = 3

        /**
         * The packs the meals of [meals] that [counts] opened. Leftovers open nothing, they are the same pot.
         */
        fun opened(meals: List<PlannedMeal>, candidates: Map<Long, PlanCandidate>, counts: (PlannedMeal) -> Boolean = { true }): OpenPacks {
            val packs = OpenPacks()
            meals.filter { !it.leftover && counts(it) }
                .sortedWith(compareBy({ it.day }, { it.slot.ordinal }))
                .forEach { meal -> candidates[meal.recipeId]?.let { packs.cook(meal.day, it.freshUses, meal.portions) } }
            return packs
        }

        /** The packs open when [slot] on [day] is about to be planned: those the meals before it opened. */
        fun before(meals: List<PlannedMeal>, candidates: Map<Long, PlanCandidate>, day: Int, slot: MealSlot): OpenPacks =
            opened(meals, candidates) { it.day < day || (it.day == day && it.slot.ordinal < slot.ordinal) }

        /** Euros of pack rest a whole plan leaves, replayed from its meals: the measure the planner tries to lower. */
        fun restOf(meals: List<PlannedMeal>, candidates: Map<Long, PlanCandidate>): Double =
            opened(meals, candidates).also { it.finish() }.restEuros
    }
}
