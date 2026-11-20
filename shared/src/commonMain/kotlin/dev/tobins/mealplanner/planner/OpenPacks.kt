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

/** What a closed pack left of [ingredientId]: [grams] of a pack of [packGrams], worth [euros] of the pack price. */
data class PackRest(val ingredientId: Long, val grams: Double, val euros: Double, val packGrams: Double)

/**
 * What the planner's pack term counts, per euro of pack price (issue 222): the [reuse] reward for the rest of an
 * opened pack a recipe uses up, and the [waste] penalty for the rest a recipe leaves by opening a new pack, which the
 * week may not finish. Chosen with the measurement of PackRestMeasurementTest; a request may set others, as that does.
 */
data class PackWeights(val reuse: Double = 0.75, val waste: Double = 0.2)

/**
 * The packs of fresh ingredients a plan has opened so far, and what is left in them: the planner prefers
 * a recipe that uses up the rest of a pack the week already buys over one that opens another (issue 222).
 *
 * The rest of a pack counts for meals on the day of the meal that opened it and at most [WINDOW_DAYS]
 * days after (a fresh ingredient keeps about that long, for every ingredient alike). After that it is
 * thrown away, and the next meal that needs the ingredient opens a new pack.
 */
class OpenPacks {
    private class Open(val ingredientId: Long, val pack: Pack, val openedOn: Int, var restGrams: Double)

    private val open = mutableMapOf<Long, Open>()

    /** Euros of packs left unused when they were closed: the rest this plan buys and does not eat. */
    var restEuros = 0.0
        private set

    private val closed = mutableListOf<PackRest>()

    /** The rest of every pack closed so far, one per opening: the last pack opened for an ingredient is the one with a rest. */
    val rests: List<PackRest> get() = closed

    /**
     * What [servings] of a recipe ([uses] per serving) would take from the rest of the packs open on [day],
     * in euros of pack price: the packs it does not have to open for.
     */
    fun reusable(day: Int, uses: List<FreshUse>, servings: Double): Double = uses.sumOf { use ->
        val rest = openOn(day, use.ingredientId)?.restGrams ?: return@sumOf 0.0
        min(rest, use.gramsPerServing * servings) / use.pack.grams * use.pack.euros
    }

    /**
     * The euros of pack rest that [servings] of a recipe would leave on [day] by opening new packs for what the
     * open ones cannot give: the rest the plan would then be likely to throw away.
     */
    fun wasted(day: Int, uses: List<FreshUse>, servings: Double): Double = uses.sumOf { use ->
        val need = use.gramsPerServing * servings - (openOn(day, use.ingredientId)?.restGrams ?: 0.0).coerceAtLeast(0.0)
        if (need <= 0) 0.0 else (ceil(need / use.pack.grams) * use.pack.grams - need) / use.pack.grams * use.pack.euros
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
                open[use.ingredientId] = Open(use.ingredientId, use.pack, day, packs * use.pack.grams - need)
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
        val euros = pack.restGrams / pack.pack.grams * pack.pack.euros
        restEuros += euros
        if (pack.restGrams > 0) closed += PackRest(pack.ingredientId, pack.restGrams, euros, pack.pack.grams)
    }

    companion object {
        const val WINDOW_DAYS = 3

        /** A rest is small, and worth telling the user about, when it is less than this share of the pack it is left of. */
        const val SMALL_SHARE = 0.5

        /** Rests worth less than this many euros are not told: a pinch of a spice is no leftover. */
        const val LEAST_EUROS = 0.05

        /**
         * The packs the meals of [meals] that [counts] opened. Leftovers open nothing, they are the same pot; so do
         * the later meals of a prepped batch, while the meal that makes it cooks the whole batch: one purchase.
         */
        fun opened(meals: List<PlannedMeal>, candidates: Map<Long, PlanCandidate>, counts: (PlannedMeal) -> Boolean = { true }): OpenPacks {
            val packs = OpenPacks()
            meals.filter { !it.leftover && counts(it) }
                .sortedWith(compareBy({ it.day }, { it.slot.ordinal }))
                .forEach { meal -> candidates[meal.recipeId]?.let { packs.cook(meal.day, it.freshUses, meal.prep?.portions ?: meal.portions) } }
            return packs
        }

        /** The packs open when [slot] on [day] is about to be planned: those the meals before it opened. */
        fun before(meals: List<PlannedMeal>, candidates: Map<Long, PlanCandidate>, day: Int, slot: MealSlot): OpenPacks =
            opened(meals, candidates) { it.day < day || (it.day == day && it.slot.ordinal < slot.ordinal) }

        /** Euros of pack rest a whole plan leaves, replayed from its meals: the measure the planner tries to lower. */
        fun restOf(meals: List<PlannedMeal>, candidates: Map<Long, PlanCandidate>): Double =
            opened(meals, candidates).also { it.finish() }.restEuros

        /**
         * What the plan leaves of fresh ingredients that is worth using: the rests [restOf] counts, only those under
         * [SMALL_SHARE] of the pack they are left of, as it was bought (a big rest is a pack mostly unused, which
         * is the planner's business, not a leftover to eat), and worth at least [LEAST_EUROS]. Each pack opened is
         * judged by itself: an ingredient whose week opens two packs shows the rests of those under half, added up.
         * Pieces and litres are already grams of the pack ([Pack.grams]), so the share is one of weight for them too.
         * Largest rest in euros first.
         */
        fun smallRestsOf(meals: List<PlannedMeal>, candidates: Map<Long, PlanCandidate>): List<PackRest> =
            opened(meals, candidates).also { it.finish() }.rests
                .filter { it.grams < it.packGrams * SMALL_SHARE && it.euros >= LEAST_EUROS }
                .groupBy { it.ingredientId }
                .map { (id, rests) -> PackRest(id, rests.sumOf { it.grams }, rests.sumOf { it.euros }, rests.first().packGrams) }
                .sortedByDescending { it.euros }
    }
}
