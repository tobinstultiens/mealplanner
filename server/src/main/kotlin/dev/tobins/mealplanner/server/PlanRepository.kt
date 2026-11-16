package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.PlanDay
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.planner.PlanCandidate
import dev.tobins.mealplanner.planner.PlanRequest
import dev.tobins.mealplanner.planner.WeekPlanner
import dev.tobins.mealplanner.pricing.Pricing
import dev.tobins.mealplanner.server.db.Database
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.format.DateTimeParseException
import kotlin.random.Random

/**
 * Week plans: generating them with [WeekPlanner] and storing the result. The shopping list of a
 * week is [ShoppingListRepository]'s; it reads the week's [meals] here.
 */
class PlanRepository(
    private val db: Database,
    private val userId: Long,
    private val recipes: RecipeRepository,
    private val profiles: ProfileRepository,
    private val ingredients: IngredientRepository,
    /** Offers valid between two dates at every store, grouped by ingredient id. */
    private val offersByIngredient: (LocalDate, LocalDate) -> Map<Long, List<Offer>> = { _, _ -> emptyMap() },
    /** Prices with the offers valid between two dates, at the stores the account shops at. */
    private val pricing: (LocalDate, LocalDate) -> Pricing = { _, _ -> Pricing.NONE },
    /** The stores the account shops at: only their offers make the planner prefer a meal. */
    private val shops: () -> Set<Store> = { Store.entries.toSet() },
) {
    private val q = db.planQueries

    fun get(weekStart: String): WeekPlan = view(parseWeekStart(weekStart))

    /** Replaces every unlocked meal of the week with a newly planned one. */
    fun generate(weekStart: String, seed: Long = Random.nextLong()): WeekPlan {
        val start = parseWeekStart(weekStart)
        // Meals already eaten or skipped are history: they stay, like locked ones.
        val kept = meals(start).filter { it.locked || it.status != MealStatus.PLANNED }
        val planned = WeekPlanner.plan(request(start, locked = kept, seed = seed))
        db.transaction {
            q.deleteUnlocked(userId, start.toString())
            planned.filter { new -> kept.none { it.day == new.day && it.slot == new.slot } }.forEach { save(start, it.copy(locked = false)) }
        }
        return view(start)
    }

    /**
     * Picks a different recipe for one meal and keeps the rest of the week. When a dinner
     * changes, the next day's lunch made of its leftovers changes with it ([leftoversToReplace]).
     * When no other recipe fits the meal, it is kept and a [ConflictException] says so; so it is
     * when the meal was cooked ([requireNotCooked]).
     */
    fun reroll(weekStart: String, day: Int, slot: MealSlot, seed: Long = Random.nextLong()): WeekPlan {
        val start = parseWeekStart(weekStart)
        // Read and written in one transaction, so a meal eaten meanwhile is not replaced after all.
        db.transaction {
            val current = meals(start)
            val target = current.firstOrNull { it.day == day && it.slot == slot }
                ?: throw NotFoundException("No meal planned for day $day $slot")
            requireNotCooked(target, current)
            val replaced = listOfNotNull(target, leftoversToReplace(current, day, slot))
            val planned = WeekPlanner.plan(
                request(start, locked = current - replaced.toSet(), seed = seed).copy(excluded = setOf(target.recipeId)),
            )
            val replacement = planned.firstOrNull { it.day == day && it.slot == slot }
            if (replacement == null || replacement.recipeId == target.recipeId) throw ConflictException("No other recipe fits this meal")
            for (old in replaced) {
                q.deleteMeal(userId, start.toString(), old.day.toLong(), old.slot.name)
                planned.firstOrNull { it.day == old.day && it.slot == old.slot }
                    ?.let { save(start, it.copy(locked = false, status = MealStatus.PLANNED)) }
            }
        }
        return view(start)
    }

    /**
     * Puts the account's recipe [recipeId] in a slot because the user chose it: [place]d and locked,
     * so that replanning keeps it. The recipe need not be marked for the slot, and an empty slot can be
     * filled. A [NotFoundException] when the recipe is not the account's, a [ConflictException] when it
     * has no amounts to plan with, is a component or when the slot's meal was cooked ([requireNotCooked]).
     * A removed recipe is as good as not there.
     */
    fun choose(weekStart: String, day: Int, slot: MealSlot, recipeId: Long): WeekPlan {
        val detail = recipes.find(recipeId)?.takeIf { recipes.isActive(recipeId) } ?: throw NotFoundException("No recipe $recipeId")
        if (detail.summary.kind == RecipeKind.COMPONENT) throw ConflictException("This recipe is a building block, not a meal")
        val candidate = recipes.candidate(recipeId) ?: throw ConflictException("This recipe has no amounts to plan with")
        return place(weekStart, day, slot, candidate, locked = true)
    }

    /**
     * Refuses to replace [meal] when the stock went down for it: it was eaten, or it is the leftover
     * lunch of a dinner that was eaten ([eatFromStock] took its share too). What a meal took is kept
     * under its day and slot, and goes when the meal is replaced, so replacing it would lose that stock
     * for good; the user un-eats the meal (or its dinner) first.
     */
    private fun requireNotCooked(meal: PlannedMeal, week: List<PlannedMeal>) {
        if (meal.status == MealStatus.EATEN) throw ConflictException("This meal was eaten already")
        if (dinnerOf(meal, week)?.status == MealStatus.EATEN) throw ConflictException("The dinner before it was eaten, and these leftovers with it")
    }

    /**
     * [requireNotCooked] for whatever is in a slot; an empty slot can be filled. Writing a new recipe
     * asks it first, so that Claude does not write one for a meal it may not replace.
     */
    fun requireReplaceable(weekStart: String, day: Int, slot: MealSlot) = requireReplaceable(meals(parseWeekStart(weekStart)), day, slot)

    private fun requireReplaceable(week: List<PlannedMeal>, day: Int, slot: MealSlot) {
        week.firstOrNull { it.day == day && it.slot == slot }?.let { requireNotCooked(it, week) }
    }

    /**
     * The next day's leftover lunch that changes along with the dinner in [day]'s [slot]: only one
     * still planned and not locked. An eaten or skipped one is history and stays as it is; an eaten
     * one keeps what it took from the stock, to give it back when it is un-eaten.
     */
    private fun leftoversToReplace(week: List<PlannedMeal>, day: Int, slot: MealSlot): PlannedMeal? = week.firstOrNull {
        slot == MealSlot.DINNER && it.day == day + 1 && it.slot == MealSlot.LUNCH && it.leftover && !it.locked && it.status == MealStatus.PLANNED
    }

    /**
     * Puts [candidate] in a slot, sized to the slot's share of the targets, [locked] or not. A dinner's
     * leftovers replace the next day's leftover lunch ([leftoversToReplace]). A meal that was cooked is
     * not replaced ([requireNotCooked]): checked in the transaction that writes, as a recipe can take a
     * minute to write and the meal may be eaten meanwhile.
     */
    fun place(weekStart: String, day: Int, slot: MealSlot, candidate: PlanCandidate, locked: Boolean = false): WeekPlan {
        val start = parseWeekStart(weekStart)
        val targets = targets()
        val portions = WeekPlanner.portionsFor(candidate, targets.kcal * slot.share)
        val meal = PlannedMeal(day, slot, candidate.recipeId, candidate.title, portions, candidate.kcal * portions, candidate.proteinG * portions, locked = locked)
        db.transaction {
            val week = meals(start)
            requireReplaceable(week, day, slot)
            val nextLunch = leftoversToReplace(week, day, slot)
            save(start, meal)
            val remaining = candidate.servings - portions
            if (nextLunch != null) {
                if (remaining >= 0.5) {
                    val lunchPortions = WeekPlanner.portionsFor(candidate, targets.kcal * MealSlot.LUNCH.share, remaining)
                    save(start, nextLunch.copy(recipeId = candidate.recipeId, title = candidate.title, portions = lunchPortions,
                        kcal = candidate.kcal * lunchPortions, proteinG = candidate.proteinG * lunchPortions))
                } else {
                    // Nothing left over: the old dinner's leftovers are gone too.
                    q.deleteMeal(userId, start.toString(), nextLunch.day.toLong(), nextLunch.slot.name)
                }
            }
        }
        return view(start)
    }

    fun setLocked(weekStart: String, day: Int, slot: MealSlot, locked: Boolean): WeekPlan {
        val start = parseWeekStart(weekStart)
        q.setLocked(if (locked) 1 else 0, userId, start.toString(), day.toLong(), slot.name)
        return view(start)
    }

    /**
     * Marks a meal eaten, skipped or planned again. Eating it is eating from what was bought ahead or
     * stocked up for it ([eatFromStock]); marking it skipped or planned again puts that back
     * ([giveBackToStock]), and marking it eaten when it already is takes nothing more.
     */
    fun setStatus(weekStart: String, day: Int, slot: MealSlot, status: MealStatus): WeekPlan {
        val start = parseWeekStart(weekStart)
        db.transaction {
            val meals = meals(start)
            val meal = meals.firstOrNull { it.day == day && it.slot == slot }
            q.setStatus(status.name, userId, start.toString(), day.toLong(), slot.name)
            if (meal != null && (meal.status == MealStatus.EATEN) != (status == MealStatus.EATEN)) {
                if (status == MealStatus.EATEN) eatFromStock(start, meal, meals) else giveBackToStock(start, meal, meals)
            }
        }
        return view(start)
    }

    /**
     * Takes from the stock what eating [meal] used ([IngredientRepository.useUp]), each share kept
     * under the meal whose portions it is for. A dinner is cooked with its leftovers, so it takes the
     * next day's leftover lunch's share too, unless that lunch was eaten already and took its own. A
     * leftover lunch takes its own share only when its dinner was not eaten: then it was cooked for
     * the lunch. Whichever is eaten first, the two take the same in the end.
     */
    private fun eatFromStock(start: LocalDate, meal: PlannedMeal, week: List<PlannedMeal>) {
        if (dinnerOf(meal, week)?.status != MealStatus.EATEN) take(start, meal)
        leftoversOf(meal, week)?.takeIf { it.status != MealStatus.EATEN }?.let { take(start, it) }
    }

    /**
     * The inverse of [eatFromStock]: puts back what was taken for [meal]'s portions, unless it is a
     * leftover lunch whose dinner is still eaten (cooked, so used whether eaten or not), and for its
     * leftover lunch's, unless that lunch is eaten.
     */
    private fun giveBackToStock(start: LocalDate, meal: PlannedMeal, week: List<PlannedMeal>) {
        if (dinnerOf(meal, week)?.status != MealStatus.EATEN) ingredients.giveBack(MealRef(start, meal.day, meal.slot))
        leftoversOf(meal, week)?.takeIf { it.status != MealStatus.EATEN }?.let { ingredients.giveBack(MealRef(start, it.day, it.slot)) }
    }

    /** Takes [meal]'s portions of its recipe from the stock ([gramsUsed]), kept under [meal]. */
    private fun take(start: LocalDate, meal: PlannedMeal) {
        val recipe = recipes.linesWithIngredients(listOf(meal.recipeId))[meal.recipeId] ?: return
        ingredients.useUp(MealRef(start, meal.day, meal.slot), gramsUsed(recipe, meal.portions))
    }

    /** The meals planned for one date; the plan week is the one starting on that date's Monday. */
    fun mealsOn(date: LocalDate): Triple<LocalDate, Int, List<PlannedMeal>> {
        val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val index = (date.toEpochDay() - start.toEpochDay()).toInt()
        val week = meals(start)
        return Triple(start, index, week.filter { it.day == index }.sortedBy { it.slot.ordinal }.map { it.copy(cookPortions = cookPortions(it, week)) })
    }

    /** Titles of meals planned from two weeks before [today] up to [today], newest first. */
    fun recentTitles(today: LocalDate): List<String> {
        val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return listOf(start, start.minusDays(7), start.minusDays(14))
            .flatMap { week -> meals(week).filter { !week.plusDays(it.day.toLong()).isAfter(today) }.sortedByDescending { it.day } }
            .map { it.title }
            .distinct()
    }

    fun remove(weekStart: String, day: Int, slot: MealSlot): WeekPlan {
        val start = parseWeekStart(weekStart)
        q.deleteMeal(userId, start.toString(), day.toLong(), slot.name)
        return view(start)
    }

    private fun request(start: LocalDate, locked: List<PlannedMeal>, seed: Long) = PlanRequest(
        targets = targets(),
        candidates = recipes.planCandidates(
            onOffer = onOffer(start),
            dislikes = profiles.profile()?.dislikes.orEmpty(),
            prices = pricing(start, start.plusDays(DAYS - 1L)),
            onHand = ingredients.onHandFor(start),
        ),
        routines = recipes.routines().let { r ->
            listOfNotNull(r.breakfast?.let { MealSlot.BREAKFAST to it }, r.snack?.let { MealSlot.SNACK to it }).toMap()
        },
        days = DAYS,
        locked = locked,
        // What was planned the week before counts as recently eaten.
        recent = meals(start.minusDays(DAYS.toLong())).mapTo(HashSet()) { it.recipeId },
        seed = seed,
    )

    /**
     * The ingredients on offer during the week starting [start] at a store the account shops at:
     * those the planner prefers. The shopping list asks it for next week, to buy ahead only what
     * will not be on offer then.
     */
    fun onOffer(start: LocalDate): Set<Long> {
        val inUse = shops()
        return offersByIngredient(start, start.plusDays(DAYS - 1L)).filterValues { offers -> offers.any { it.store in inUse } }.keys
    }

    private fun targets() = profiles.state().targets
        ?: throw ConflictException("Set up your profile first: the planner needs daily targets")

    private fun view(start: LocalDate): WeekPlan {
        val prices = pricing(start, start.plusDays(DAYS - 1L))
        val costs = HashMap<Long, dev.tobins.mealplanner.api.RecipeCost?>()
        val week = meals(start)
        val meals = week.map { meal ->
            val cost = costs.getOrPut(meal.recipeId) { recipes.cost(meal.recipeId, prices) }
            meal.copy(cost = cost?.let { it.perServingWithOffers * meal.portions }, cookPortions = cookPortions(meal, week))
        }
        // A skipped meal is not eaten: it counts towards neither the day nor the week.
        val priced = meals.filter { it.cost != null && it.status != MealStatus.SKIPPED }
        return WeekPlan(
            weekStart = start.toString(),
            targets = targets(),
            days = (0 until DAYS).map { day ->
                val ofDay = meals.filter { it.day == day }.sortedBy { it.slot.ordinal }
                val eaten = ofDay.filter { it.status != MealStatus.SKIPPED }
                PlanDay(start.plusDays(day.toLong()).toString(), ofDay, eaten.sumOf { it.kcal }, eaten.sumOf { it.proteinG })
            },
            emptySlots = MealSlot.entries.filter { slot -> meals.none { it.slot == slot } },
            cost = priced.takeIf { it.isNotEmpty() }?.sumOf { it.cost!! },
            costWithoutOffers = priced.takeIf { it.isNotEmpty() }?.sumOf { costs[it.recipeId]!!.perServing * it.portions },
        )
    }

    /** The meals stored for the week starting [start], by day. */
    fun meals(start: LocalDate): List<PlannedMeal> = q.selectWeek(userId, start.toString()).executeAsList().map {
        PlannedMeal(
            day = it.day.toInt(),
            slot = MealSlot.valueOf(it.slot),
            recipeId = it.recipeId,
            title = it.title,
            portions = it.portions,
            kcal = it.kcal,
            proteinG = it.proteinG,
            leftover = it.leftover != 0L,
            locked = it.locked != 0L,
            status = MealStatus.valueOf(it.status),
        )
    }

    private fun save(start: LocalDate, meal: PlannedMeal) = q.upsertMeal(
        userId, start.toString(), meal.day.toLong(), meal.slot.name, meal.recipeId, meal.title, meal.portions,
        meal.kcal, meal.proteinG, if (meal.leftover) 1 else 0, if (meal.locked) 1 else 0, meal.status.name,
    )

    companion object {
        /** The days of a plan week, Monday first. */
        const val DAYS = 7

        /**
         * The meals of [week] whose portions are still to be cooked, which is what is still to buy
         * for: the shopping list's [dev.tobins.mealplanner.api.ShoppingItem.toComeGrams]. It goes by
         * the rules of [eatFromStock], so the list never counts a share the stock went down for: an
         * eaten meal is cooked, and so is a leftover lunch whose dinner was eaten; a skipped meal is
         * not cooked, unless it is a leftover lunch whose dinner still is, as that dinner is cooked
         * with its leftovers (and takes their share when eaten). Un-eating a meal brings it back.
         */
        fun toCook(week: List<PlannedMeal>): List<PlannedMeal> = week.filter { meal ->
            val dinner = dinnerOf(meal, week)
            when {
                meal.status == MealStatus.EATEN -> false
                dinner == null -> meal.status == MealStatus.PLANNED
                else -> dinner.status != MealStatus.EATEN && MealStatus.PLANNED in setOf(meal.status, dinner.status)
            }
        }

        /**
         * The servings to cook when [meal] is made ([PlannedMeal.cookPortions]): its own portions plus
         * those of the leftover lunch cooked with it, unless that lunch was eaten and took its own. A
         * skipped meal and a leftover lunch whose dinner is in the plan (cooked then, or skipped and
         * then not cooked, as [toCook] has it) are 0, except the lunch of a skipped dinner, which
         * is cooked for itself. So for a week where a recipe is planned once, the amounts of
         * these servings are what the shopping list counts for it.
         */
        fun cookPortions(meal: PlannedMeal, week: List<PlannedMeal>): Double {
            if (meal.status == MealStatus.SKIPPED) return 0.0
            val dinner = dinnerOf(meal, week)
            if (dinner != null) return if (dinner.status == MealStatus.SKIPPED) meal.portions else 0.0
            val leftovers = leftoversOf(meal, week)?.takeIf { it.status != MealStatus.EATEN }
            return meal.portions + (leftovers?.portions ?: 0.0)
        }

        /** The next day's leftover lunch cooked with [meal], when it is a dinner that has one. */
        private fun leftoversOf(meal: PlannedMeal, week: List<PlannedMeal>): PlannedMeal? = week.firstOrNull {
            meal.slot == MealSlot.DINNER && !meal.leftover &&
                it.leftover && it.recipeId == meal.recipeId && it.day == meal.day + 1 && it.slot == MealSlot.LUNCH
        }

        /** The dinner [meal] is the leftovers of, when it is a leftover lunch and that dinner is in the plan. */
        private fun dinnerOf(meal: PlannedMeal, week: List<PlannedMeal>): PlannedMeal? = week.firstOrNull {
            meal.slot == MealSlot.LUNCH && meal.leftover &&
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
