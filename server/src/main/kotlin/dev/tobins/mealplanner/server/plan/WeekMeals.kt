package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.MealSide
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.planner.PlanCandidate
import dev.tobins.mealplanner.server.NotFoundException
import dev.tobins.mealplanner.server.db.SelectBoxPortion
import dev.tobins.mealplanner.server.ingredients.MealRef
import dev.tobins.mealplanner.server.ingredients.StockRepository
import dev.tobins.mealplanner.server.recipes.RecipeCandidates
import dev.tobins.mealplanner.server.shopping.ShoppingListView
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters

/**
 * The meals of a week as the plan, the shopping list and the stock count them, and storing a meal in a slot. Every
 * other class of the plan reads the week here: a meal with its batch and side ([of]), the week as the shopping list buys
 * it ([forShopping]), what is still to cook ([stillToCook]). The shopping list of a week is [ShoppingListView]'s.
 */
class WeekMeals(
    private val store: PlanStore,
    private val recipes: RecipeCandidates,
    private val stock: StockRepository,
    private val adjustments: MealAdjustments,
    private val foods: SlotFoods,
) {
    /**
     * The meals stored for the week starting [start], by day. A meal of a prepped batch has the batch
     * ([PlannedMeal.prep]), is locked, and is a leftover unless it is the meal that makes the batch
     * ([Batch.cook]): what an app that does not know preps shows.
     * A meal with a side taken from a batch has it ([PlannedMeal.side], [sideOf]).
     */
    fun of(start: LocalDate): List<PlannedMeal> {
        val meals = store.mealsIn(start)
        val prepOf = store.prepIdsIn(start)
        val sideOf = store.sideIdsIn(start)
        if (prepOf.isEmpty() && sideOf.isEmpty()) return meals
        val batches = store.batches(prepOf.values + sideOf.values)
        val recipesOfSides = HashMap<Long, PlanCandidate?>()
        return meals.map { meal ->
            val inBatch = prepOf[meal.day to meal.slot]?.let { batches[it] }
                ?.let { batch -> meal.copy(prep = batch.toApi(), leftover = !batch.isCook(start, meal), locked = true) }
                ?: meal
            val side = sideOf[meal.day to meal.slot]?.let { batches[it] }?.let { batch -> sideOf(start, meal, batch, recipesOfSides) }
            inBatch.copy(side = side)
        }
    }

    /**
     * The side of [batch] next to [meal] of the week starting [start], with the numbers of its portions of the recipe as
     * the recipe is now ([recipesOfSides] holds the recipes read so far, by id): there are none stored, as a side has no
     * row of its own. A recipe gone or without amounts counts as nothing.
     */
    private fun sideOf(start: LocalDate, meal: PlannedMeal, batch: Batch, recipesOfSides: MutableMap<Long, PlanCandidate?>): MealSide? {
        val side = batch.sideOf(start, meal) ?: return null
        val recipe = recipesOfSides.getOrPut(batch.recipeId) { recipes.candidate(batch.recipeId) }
        return MealSide(
            recipeId = batch.recipeId,
            title = batch.title,
            portions = side.share,
            kcal = (recipe?.kcal ?: 0.0) * side.share,
            proteinG = (recipe?.proteinG ?: 0.0) * side.share,
            fibreG = recipe?.let { it.fibreG * side.share },
            cookPortions = batch.cookPortions(start, meal, side = true),
            prep = batch.toApi(),
        )
    }

    /** The batches [meals] are eaten from, by id. */
    internal fun batchesOf(meals: List<PlannedMeal>): Map<Long, Batch> = store.batches(meals.mapNotNull { it.prep?.id })

    /** The share in the box under [day]'s [slot] of the week starting [start] (issue 265); null when it holds none. */
    internal fun box(start: LocalDate, day: Int, slot: MealSlot): SelectBoxPortion? = store.box(start, day, slot)

    /** The batch of each share in the box under a slot of the week starting [start], by day and slot. */
    internal fun boxesIn(start: LocalDate): Map<Pair<Int, MealSlot>, Long> = store.boxesIn(start)

    /**
     * The meals of the week starting [start] as its shopping list and the stock count them: a prepped batch
     * made this week is one meal of all its portions, where it is made, with the batch's status
     * ([Batch.status]); the other meals of a batch are not there, wherever it was made, as they cook and buy
     * nothing. So the batch is bought once, in the week it is made, also for meals the week after. A batch made
     * with a side is that one meal too, next to the meal the side goes with, in its slot (its `prep` has a side
     * there); the meal's main is there as itself. Foods next to a recipe (issue 358) are a meal of their own in the slot
     * ([foodsNextTo]).
     */
    fun forShopping(start: LocalDate, week: List<PlannedMeal> = of(start)): List<PlannedMeal> {
        val batches = batchesOf(week)
        val meals = week.mapNotNull { meal ->
            val prep = meal.prep ?: return@mapNotNull meal
            val batch = batches[prep.id]?.takeIf { it.isCook(start, meal) } ?: return@mapNotNull null
            meal.copy(portions = batch.portions, status = batch.status)
        }
        val sideBatches = store.batches(week.mapNotNull { it.side?.prep?.id })
        val cookedAsSides = week.mapNotNull { meal ->
            val side = meal.side ?: return@mapNotNull null
            val batch = sideBatches[side.prep.id]?.takeIf { it.isCook(start, meal, side = true) } ?: return@mapNotNull null
            // Of the recipe, not of the side: the side it is made with is 0 once its share is eaten elsewhere.
            val recipe = recipes.candidate(batch.recipeId)
            PlannedMeal(
                meal.day, meal.slot, batch.recipeId, batch.title, batch.portions,
                kcal = (recipe?.kcal ?: 0.0) * batch.portions, proteinG = (recipe?.proteinG ?: 0.0) * batch.portions,
                locked = true, status = batch.status, prep = side.prep,
            )
        }
        // A side is bought with its batch, where that is cooked: the meals here are their mains.
        return meals.map { it.copy(side = null) } + cookedAsSides + week.mapNotNull(::foodsNextTo)
    }

    /**
     * The foods next to [meal]'s recipe as the shopping list and the stock count them ([forShopping]): a meal of foods alone in
     * its slot, one portion, eaten or skipped with [meal]; null when it has none. A meal of a batch has none ([FoodChanges.addFood]).
     */
    private fun foodsNextTo(meal: PlannedMeal): PlannedMeal? {
        val id = meal.foodsId?.takeUnless { meal.foodsOnly || meal.prep != null } ?: return null
        return PlannedMeal(meal.day, meal.slot, id, meal.title, 1.0, 0.0, 0.0, locked = true, status = meal.status, foodsOnly = true, foodsId = id)
    }

    /** Whether the week starting [start] has meals of its own: the meals of a batch made the week before are there before it is planned. */
    fun isPlanned(start: LocalDate): Boolean = forShopping(start).isNotEmpty()

    /** The meals planned for one date; the plan week is the one starting on that date's Monday. */
    fun mealsOn(date: LocalDate): Triple<LocalDate, Int, List<PlannedMeal>> {
        val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val index = (date.toEpochDay() - start.toEpochDay()).toInt()
        val week = of(start)
        val holding = holdingStock(start, week)
        val ofDay = week.filter { it.day == index }.sortedBy { it.slot.ordinal }.map { it.copy(cookPortions = cookPortions(it, week, holding)) }
        return Triple(start, index, foods.withFoods(adjustments.withNutrition(start, ofDay)))
    }

    /** Titles of meals planned from two weeks before [today] up to [today], newest first; a slot of foods alone is no recipe eaten. */
    fun recentTitles(today: LocalDate): List<String> {
        val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return listOf(start, start.minusDays(7), start.minusDays(14))
            .flatMap { week -> of(week).filter { !it.foodsOnly && !week.plusDays(it.day.toLong()).isAfter(today) }.sortedByDescending { it.day } }
            .map { it.title }
            .distinct()
    }

    /**
     * Whether some of the stock was taken for [meal] and is kept under its slot, to give back when it is un-eaten. Its
     * own share only: that of its side goes with the side's batch, and stays when the meal's main is replaced ([save]).
     */
    internal fun holdsStock(start: LocalDate, meal: PlannedMeal): Boolean = store.holdsStock(start, meal.day, meal.slot)

    /**
     * The meals of [week] (starting [start]) that hold a share of the stock although they are not eaten
     * ([holdsStock]), by day and slot, for [toCook] and [cookPortions]: the leftover lunch of an eaten
     * dinner, also one whose eaten dinner a server before issue 165 removed.
     */
    internal fun holdingStock(start: LocalDate, week: List<PlannedMeal>): Set<Pair<Int, MealSlot>> =
        week.filter { it.status != MealStatus.EATEN && holdsStock(start, it) }.mapTo(HashSet()) { it.day to it.slot }

    /** The meals of [week], the week starting [start] as [forShopping] has it, still to cook and to buy for ([toCook]). */
    fun stillToCook(start: LocalDate, week: List<PlannedMeal>): List<PlannedMeal> = toCook(week, holdingStock(start, week))

    /**
     * Whether [meal]'s share of the stock was taken ([Eating]): it was eaten, it is the leftover lunch
     * of an eaten dinner (cooked with it), or what was taken for it is kept under its slot all the same; for a
     * meal of a prepped batch, and a batch cooked next to a meal as [forShopping] has it, the batch was made.
     */
    internal fun shareTaken(start: LocalDate, meal: PlannedMeal, week: List<PlannedMeal>): Boolean =
        meal.prep?.let { prep -> prep.meals.any { it.status == MealStatus.EATEN } }
            ?: (meal.status == MealStatus.EATEN || dinnerOf(meal, week)?.status == MealStatus.EATEN || holdsStock(start, meal))

    /** Saves [meal] in a batch: locked and eaten from batch [prepId]. Not a leftover in the table: that follows from the batch ([of]). */
    internal fun saveInBatch(start: LocalDate, meal: PlannedMeal, prepId: Long): Unit = store.writing {
        save(start, meal.copy(leftover = false, locked = true, prep = null))
        store.insertPrepPortion(start, meal.day, meal.slot, prepId)
    }

    internal fun deleteMeal(start: LocalDate, day: Int, slot: MealSlot): Unit = store.writing { store.deleteMeal(start, day, slot) }

    /**
     * Stores [meal] in its slot, in place of the meal there. What belonged to that meal goes with it (its share of a
     * batch, what it took from the stock, what was in it); its side stays, next to the new main, with what the stock
     * gave for it: the side was added to the slot by hand, and its batch is not made smaller by a new main. So does a
     * skipped prepped meal's share in the box under the slot (issue 265), with what the stock gave for it: it is in a
     * box in the fridge, whatever is eaten in its place. So do the slot's foods (issue 358): they leave it only when taken
     * out or when the slot is removed, so a new recipe goes in next to them, and a slot of foods alone becomes a recipe with
     * those foods next to it. A meal moved carries its own ([PlannedMeal.foodsId]) to a slot its move emptied first.
     */
    internal fun save(start: LocalDate, meal: PlannedMeal): Unit = store.writing {
        val foodsId = meal.foodsId ?: store.foodsIdIn(start, meal.day, meal.slot)
        val side = store.side(start, meal.day, meal.slot)
        val sideRef = MealRef(start, meal.day, meal.slot, side = true)
        val taken = if (side == null) emptyList() else stock.taken(sideRef)
        val box = box(start, meal.day, meal.slot)
        val boxRef = MealRef(start, meal.day, meal.slot, boxed = true)
        val inBox = if (box == null) emptyList() else stock.taken(boxRef)
        store.upsertMeal(start, meal)
        foodsId?.let { store.setFoods(start, meal.day, meal.slot, it) }
        if (side != null) {
            store.insertSidePortion(start, meal.day, meal.slot, side.prepId, side.portions)
            stock.keep(sideRef, taken)
        }
        if (box != null) {
            store.insertBoxPortion(start, meal.day, meal.slot, box.prepId, box.portions)
            stock.keep(boxRef, inBox)
        }
    }

    companion object {
        /** The days of a plan week, Monday first. */
        const val DAYS = 7

        /** The meal of [week] in [day]'s [slot]; a [NotFoundException] for an empty slot. */
        internal fun mealAt(week: List<PlannedMeal>, day: Int, slot: MealSlot): PlannedMeal =
            week.firstOrNull { it.day == day && it.slot == slot } ?: throw NotFoundException("No meal planned for day $day $slot")

        /**
         * The meals of [week] whose portions are still to be cooked, which is what is still to buy
         * for: the shopping list's [dev.tobins.mealplanner.api.ShoppingItem.toComeGrams]. It goes by
         * the rules of [Eating]'s stock, so the list never counts a share the stock went down for: an
         * eaten meal is cooked, and so is a leftover lunch whose dinner was eaten; a skipped meal is
         * not cooked, unless it is a leftover lunch whose dinner still is, as that dinner is cooked
         * with its leftovers (and takes their share when eaten). Un-eating a meal brings it back.
         *
         * [holding] are the meals, by day and slot, that hold a share of the stock although not eaten: their
         * share was taken, so they are cooked whatever the plan says now. That is only ever the leftover lunch
         * of an eaten dinner, which the rules above have as cooked while the dinner is in the plan; a server
         * before issue 165 could remove the dinner and leave the lunch with its share.
         */
        fun toCook(week: List<PlannedMeal>, holding: Set<Pair<Int, MealSlot>> = emptySet()): List<PlannedMeal> = week.filter { meal ->
            val dinner = dinnerOf(meal, week)
            when {
                meal.status == MealStatus.EATEN -> false
                // A prepped batch as [forShopping] has it: one meal with the batch's status. Before [holding]: a batch
                // cooked as a side is in the slot of a meal that may hold stock of its own.
                meal.prep != null -> meal.status == MealStatus.PLANNED
                (meal.day to meal.slot) in holding -> false
                dinner == null -> meal.status == MealStatus.PLANNED
                else -> dinner.status != MealStatus.EATEN && MealStatus.PLANNED in setOf(meal.status, dinner.status)
            }
        }

        /**
         * The servings to cook when [meal] is made ([PlannedMeal.cookPortions]): its own portions plus
         * those of the leftover lunch cooked with it, unless that lunch was eaten and took its own. A
         * skipped meal, an eaten leftover lunch (cooked already) and a leftover lunch whose dinner is in
         * the plan (cooked then, or skipped and then not cooked, as [toCook] has it) are 0, except the lunch of a skipped dinner, which
         * is cooked for itself. So for a week where a recipe is planned once, the amounts of
         * these servings are what the shopping list counts for it. A meal in [holding] was cooked, as for
         * [toCook]; a dinner still to cook does not cook such a lunch again.
         *
         * A meal of a prepped batch: the one that makes it cooks the whole batch, also when it was skipped itself,
         * as the batch is made that day all the same, unless every meal of it was skipped; the later ones cook nothing.
         */
        fun cookPortions(meal: PlannedMeal, week: List<PlannedMeal>, holding: Set<Pair<Int, MealSlot>> = emptySet()): Double {
            meal.prep?.let { prep ->
                return if (meal.leftover || prep.meals.all { it.status == MealStatus.SKIPPED }) 0.0 else prep.portions
            }
            if (meal.status == MealStatus.SKIPPED || (meal.leftover && meal.status == MealStatus.EATEN)) return 0.0
            if ((meal.day to meal.slot) in holding) return 0.0
            val dinner = dinnerOf(meal, week)
            if (dinner != null) return if (dinner.status == MealStatus.SKIPPED) meal.portions else 0.0
            // An eaten dinner says what it was cooked with; one still to cook leaves out what was cooked already.
            val leftovers = leftoversOf(meal, week)
                ?.takeIf { it.status != MealStatus.EATEN && (meal.status == MealStatus.EATEN || (it.day to it.slot) !in holding) }
            return meal.portions + (leftovers?.portions ?: 0.0)
        }

        /** The next day's leftover lunch cooked with [meal], when it is a dinner that has one. */
        internal fun leftoversOf(meal: PlannedMeal, week: List<PlannedMeal>): PlannedMeal? = week.firstOrNull {
            meal.slot == MealSlot.DINNER && !meal.leftover && meal.prep == null && it.prep == null &&
                it.leftover && it.recipeId == meal.recipeId && it.day == meal.day + 1 && it.slot == MealSlot.LUNCH
        }

        /** The dinner [meal] is the leftovers of, when it is a leftover lunch and that dinner is in the plan. */
        internal fun dinnerOf(meal: PlannedMeal, week: List<PlannedMeal>): PlannedMeal? = week.firstOrNull {
            meal.slot == MealSlot.LUNCH && meal.leftover && meal.prep == null && it.prep == null &&
                !it.leftover && it.recipeId == meal.recipeId && it.day == meal.day - 1 && it.slot == MealSlot.DINNER
        }
    }
}

/** The first day of a week's plan and shopping list, as the path gives it; a 400 when it is no date. */
internal fun parseWeekStart(weekStart: String): LocalDate = try {
    LocalDate.parse(weekStart)
} catch (e: DateTimeParseException) {
    throw IllegalArgumentException("weekStart must be an ISO date such as 2026-11-16")
}
