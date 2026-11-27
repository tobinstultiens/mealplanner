package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.planner.PlanCandidate
import dev.tobins.mealplanner.planner.PortionRange
import dev.tobins.mealplanner.planner.WeekPlanner
import dev.tobins.mealplanner.server.ConflictException
import dev.tobins.mealplanner.server.NotFoundException
import dev.tobins.mealplanner.server.accounts.ProfileRepository
import dev.tobins.mealplanner.server.db.SelectBoxPortion
import dev.tobins.mealplanner.server.db.SelectStockUses
import dev.tobins.mealplanner.server.ingredients.MealRef
import dev.tobins.mealplanner.server.ingredients.StockRepository
import dev.tobins.mealplanner.server.plan.WeekMeals.Companion.DAYS
import dev.tobins.mealplanner.server.plan.WeekMeals.Companion.leftoversOf
import dev.tobins.mealplanner.server.recipes.MyRecipes
import dev.tobins.mealplanner.server.recipes.RecipeCandidates
import dev.tobins.mealplanner.server.serverToday
import java.time.LocalDate
import kotlin.random.Random

/**
 * Changing one meal of the plan: another recipe for it, chosen ([choose], [place]) or by the planner ([reroll]), a dinner
 * moved to another day ([moveDinner]), a standard meal in its place ([putStandard]), locking it, and taking it out
 * ([remove]). A dinner's leftover lunch changes with it; a meal that stays ([KeptMeals]) is refused, and nothing changes then.
 */
class MealChanges(
    private val store: PlanStore,
    private val weekMeals: WeekMeals,
    private val keptMeals: KeptMeals,
    private val plans: WeekPlans,
    private val eating: Eating,
    private val view: WeekView,
    private val adjustments: MealAdjustments,
    private val foods: SlotFoods,
    private val recipes: RecipeCandidates,
    private val myRecipes: MyRecipes,
    private val profiles: ProfileRepository,
    private val stock: StockRepository,
    /** Today as the household sees it: a dinner moves only between today and the days after it (issue 255). */
    private val today: () -> LocalDate = ::serverToday,
) {
    /**
     * Picks a different recipe for one meal and keeps the rest of the week. When a dinner
     * changes, the next day's lunch made of its leftovers changes with it ([KeptMeals.leftoversToReplace]).
     * When no other recipe fits the meal, it is kept and a [ConflictException] says so; so it is
     * when the meal was cooked ([KeptMeals.requireNotCooked]). A side stays next to the new main ([WeekMeals.save]), so the new main is
     * never the side's recipe: that would be one recipe twice on one plate.
     */
    fun reroll(weekStart: String, day: Int, slot: MealSlot, seed: Long = Random.nextLong()): WeekPlan {
        val start = parseWeekStart(weekStart)
        // Read and written in one transaction, so a meal eaten meanwhile is not replaced after all.
        store.writing {
            val current = weekMeals.of(start)
            val target = current.firstOrNull { it.day == day && it.slot == slot }
                ?: throw NotFoundException("No meal planned for day $day $slot")
            keptMeals.requireNotCooked(start, target, current)
            val replaced = listOfNotNull(target, keptMeals.leftoversToReplace(start, current, day, slot))
            val excluded = setOfNotNull(target.recipeId, target.side?.recipeId)
            // Nor one hidden for the week on the planning screen ("Not this week", issue 255), as Fill the rest leaves it out.
            val planned = plans.plan(plans.request(start, locked = current - replaced.toSet(), seed = seed).copy(excluded = excluded).leavingOut(plans.hiddenIn(start)))
            val replacement = planned.firstOrNull { it.day == day && it.slot == slot }
            if (replacement == null || replacement.recipeId == target.recipeId) throw ConflictException("No other recipe fits this meal")
            for (old in replaced) {
                val new = planned.firstOrNull { it.day == old.day && it.slot == old.slot }
                if (new != null) weekMeals.save(start, new.copy(locked = false, status = MealStatus.PLANNED)) else store.deleteMeal(start, old.day, old.slot)
            }
        }
        return view.of(start)
    }

    /**
     * Moves the dinner of [day] to [toDay] and the dinner there, if any, to [day] (issue 255), each as it is: its recipe,
     * portions, lock, status and what was in it. The lunches after the two days follow ([lunchesAfterMove]): each dinner's
     * leftover lunch goes with it, and a lunch of its own may take the place of leftovers that left. A
     * [ConflictException] when either dinner stays on its day ([whyStaysOnItsDay]), or either day has passed; nothing
     * changes then.
     */
    fun moveDinner(weekStart: String, day: Int, toDay: Int): WeekPlan {
        require(toDay in 0 until DAYS && toDay != day) { "toDay must be another day of the week, 0 to 6" }
        val start = parseWeekStart(weekStart)
        for (passed in listOf(day, toDay).map { start.plusDays(it.toLong()) }.filter { it < today() }) {
            throw ConflictException("${Batch.dayName(passed)} has passed: a dinner moves only between today and the days after it")
        }
        store.writing {
            val week = weekMeals.of(start)
            val moving = week.firstOrNull { it.day == day && it.slot == MealSlot.DINNER } ?: throw NotFoundException("No dinner planned for day $day")
            val there = week.firstOrNull { it.day == toDay && it.slot == MealSlot.DINNER }
            // Each dinner with where it is now and where it goes.
            val dinners = listOfNotNull(moving to moving.copy(day = toDay), there?.let { it to it.copy(day = day) })
            for ((dinner, _) in dinners) whyStaysOnItsDay(start, dinner, week)?.let { throw ConflictException(it) }
            val lunches = lunchesAfterMove(start, week, dinners, listOf(day + 1, toDay + 1).filter { it < DAYS })
            relocate(start, dinners + lunches.values.filterNotNull(), emptied = lunches.filterValues { it == null }.keys)
        }
        return view.of(start)
    }

    /**
     * What the lunches on [days] (the days after the two of a [moveDinner]) become once [dinners] (each as it is and as it
     * will be) moved, by day: the old and the new meal, or null for a lunch that goes. A lunch that may not be replaced
     * ([KeptMeals.replaceableLunch]) stays as it is and is left out. Each of the others is first the leftovers of the dinner before
     * it: that dinner's leftover lunch where it was, moved along, or else its leftovers as the planner makes them
     * ([leftoverLunchAfter]). A day without leftovers keeps its own lunch if it had one that is no leftover, or takes the
     * other day's when that one had to make way for leftovers; else its lunch goes, as it was the leftovers of a dinner
     * that left. A lunch is unchanged when old and new are the same meal.
     */
    private fun lunchesAfterMove(
        start: LocalDate,
        week: List<PlannedMeal>,
        dinners: List<Pair<PlannedMeal, PlannedMeal>>,
        days: List<Int>,
    ): Map<Int, Pair<PlannedMeal?, PlannedMeal>?> {
        fun lunchOn(day: Int) = week.firstOrNull { it.day == day && it.slot == MealSlot.LUNCH }
        val free = days.distinct().filter { day -> lunchOn(day)?.let { keptMeals.replaceableLunch(start, week, it) } ?: true }
        val leftovers = free.associateWith { day ->
            dinners.firstOrNull { (_, moved) -> moved.day == day - 1 }?.let { (dinner, moved) ->
                leftoversOf(dinner, week)?.let { it to it.copy(day = day) } ?: leftoverLunchAfter(moved)?.let { null to it }
            }
        }
        val ownLunches = free.mapNotNull { lunchOn(it) }.filter { !it.leftover }.toMutableList()
        val withoutLeftovers = free.filter { leftovers[it] == null }
        val own = withoutLeftovers.associateWith { day -> ownLunches.firstOrNull { it.day == day }?.also { ownLunches.remove(it) } }
        return free.associateWith { day ->
            leftovers[day] ?: own[day]?.let { it to it } ?: ownLunches.removeFirstOrNull()?.let { it to it.copy(day = day) }
        }
    }

    /**
     * Writes [moves] (each meal as it is, or null for a new one, and as it will be) and takes the lunches of the [emptied]
     * days out, in the caller's transaction: what was in a moved meal goes with it, its foods too ([WeekMeals.save]), and what is in a
     * slot a meal moves to goes. A meal moved to where it is changes nothing. Every old row goes before a new one is
     * written, as two meals may swap places.
     */
    private fun relocate(start: LocalDate, moves: List<Pair<PlannedMeal?, PlannedMeal>>, emptied: Collection<Int>) {
        val changing = moves.filter { (old, new) -> old != new }
        val lines = changing.map { (old, _) -> old?.let { adjustments.changesOf(start, it) }.orEmpty() }
        val freed = changing.flatMap { (old, new) -> listOfNotNull(old, new) }.map { it.day to it.slot } + emptied.map { it to MealSlot.LUNCH }
        for ((day, slot) in freed) store.deleteMeal(start, day, slot)
        changing.zip(lines).forEach { (move, changes) ->
            val meal = move.second
            weekMeals.save(start, meal)
            changes.forEach { (id, grams) -> store.insertMealLine(start, meal.day, meal.slot, id, grams) }
        }
    }

    /**
     * Why [dinner] of [week] (starting [start]) stays on its day ([moveDinner]), or null when it may move: when it keeps
     * its recipe ([KeptMeals.whyKeepsItsRecipe]: eaten, or of a batch made or that it makes), is a later meal of a prepped batch,
     * whose meals are the batch's days, has a prepped side, which is eaten with it on its day, or has a skipped prepped
     * meal's share in the box under it; and when its leftover lunch is one that can't follow it, as [KeptMeals.replaceableLunch]
     * says.
     */
    private fun whyStaysOnItsDay(start: LocalDate, dinner: PlannedMeal, week: List<PlannedMeal>): String? {
        keptMeals.whyKeepsItsRecipe(start, dinner, week)?.let { return it }
        val name = Batch.slotName(start.plusDays(dinner.day.toLong()), dinner.slot)
        return when {
            dinner.prep != null -> "$name is a meal of a prepped batch, eaten on the days of its prep: change the prep instead"
            dinner.side != null -> "$name has a prepped side, which is eaten with it on its day: remove the side first"
            weekMeals.box(start, dinner.day, dinner.slot) != null -> "A skipped prepped meal's share is in the box under $name, so it stays on its day"
            else -> leftoversOf(dinner, week)?.takeUnless { keptMeals.replaceableLunch(start, week, it) }?.let {
                "The leftovers of $name, on ${Batch.slotName(start.plusDays(it.day.toLong()), it.slot)}, stay where they are " +
                    "(eaten, skipped, locked or with a side), so it can't move without them"
            }
        }
    }

    /**
     * The leftover lunch of [dinner] on the day after it, as the planner makes one ([WeekPlanner.leftoverServings]); null
     * when its recipe leaves too little, or has no amounts.
     */
    private fun leftoverLunchAfter(dinner: PlannedMeal): PlannedMeal? {
        val recipe = recipes.candidate(dinner.recipeId) ?: return null
        val servings = WeekPlanner.leftoverServings(recipe, dinner) ?: return null
        return WeekPlanner.mealOf(recipe, dinner.day + 1, MealSlot.LUNCH, profiles.planTargets(), profiles.portions(), leftover = true, available = servings)
    }

    /**
     * Puts the account's recipe [recipeId] in a slot because the user chose it: [place]d and locked,
     * so that replanning keeps it. The recipe need not be marked for the slot, and an empty slot can be
     * filled. A [NotFoundException] when the recipe is not the account's, a [ConflictException] when it
     * has no amounts to plan with, is a component or when the slot's meal was cooked ([KeptMeals.requireNotCooked]).
     * Any of my recipes can be chosen, in the rotation or not; one only in the bank is a [ConflictException]
     * until it is added ([MyRecipes.requireMine]).
     */
    fun choose(weekStart: String, day: Int, slot: MealSlot, recipeId: Long): WeekPlan {
        val recipe = myRecipes.requireMine(recipeId)
        if (recipe.kind == RecipeKind.COMPONENT.name) throw ConflictException("This recipe is a building block, not a meal")
        val candidate = recipes.candidate(recipeId) ?: throw ConflictException("This recipe has no amounts to plan with")
        return place(weekStart, day, slot, candidate, locked = true)
    }

    /**
     * Puts [candidate] in a slot, sized to the slot's share of the targets within the profile's portion
     * range, [locked] or not; also a recipe too light to be a meal there, as the user chose it. A dinner's
     * leftovers replace the next day's leftover lunch ([KeptMeals.leftoversToReplace]). A meal that was cooked is
     * not replaced ([KeptMeals.requireNotCooked]): checked in the transaction that writes, as a recipe can take a
     * minute to write and the meal may be eaten meanwhile.
     */
    fun place(weekStart: String, day: Int, slot: MealSlot, candidate: PlanCandidate, locked: Boolean = false): WeekPlan {
        val start = parseWeekStart(weekStart)
        placeIn(start, day, slot, candidate, locked)
        return view.of(start)
    }

    /** [place] without answering the week, for a caller that writes more in the same transaction. */
    private fun placeIn(start: LocalDate, day: Int, slot: MealSlot, candidate: PlanCandidate, locked: Boolean) {
        val targets = profiles.planTargets()
        val range = profiles.portions()
        val portions = WeekPlanner.portionsFor(candidate, slot, targets, range)
        val meal = PlannedMeal(day, slot, candidate.recipeId, candidate.title, portions, candidate.kcal * portions, candidate.proteinG * portions, locked = locked)
        store.writing {
            val week = weekMeals.of(start)
            keptMeals.requireReplaceable(start, week, day, slot)
            val nextLunch = keptMeals.leftoversToReplace(start, week, day, slot)
            weekMeals.save(start, meal)
            val remaining = candidate.servings - portions
            if (nextLunch != null) {
                if (remaining >= PortionRange.LEAST_LEFTOVER) {
                    val lunchPortions = WeekPlanner.portionsFor(candidate, MealSlot.LUNCH, targets, range, available = remaining)
                    weekMeals.save(start, nextLunch.copy(recipeId = candidate.recipeId, title = candidate.title, portions = lunchPortions,
                        kcal = candidate.kcal * lunchPortions, proteinG = candidate.proteinG * lunchPortions))
                } else {
                    // Nothing left over: the old dinner's leftovers are gone too.
                    store.deleteMeal(start, nextLunch.day, nextLunch.slot)
                }
            }
        }
    }

    /**
     * Puts the standard meal [candidate] in a slot because the user chose it there (issue 265): [place]d and locked, as a
     * recipe chosen by hand, and marked eaten at once when [eaten], as Today asks for a meal of today or a day before. It
     * takes the place of any meal that may be replaced ([KeptMeals.whyKeepsItsRecipe]), and also of a skipped later meal of a
     * prepped batch, whose share stays in the box ([boxShare]) to be eaten elsewhere as any skipped share is. Refused
     * ([KeptMeals.whyNoStandardMeal]) where the slot's meal stays. The caller checks that the recipe is a standard meal for the slot.
     */
    fun putStandard(weekStart: String, day: Int, slot: MealSlot, candidate: PlanCandidate, eaten: Boolean): WeekPlan {
        val start = parseWeekStart(weekStart)
        store.writing {
            val week = weekMeals.of(start)
            week.firstOrNull { it.day == day && it.slot == slot }?.let { meal ->
                keptMeals.whyNoStandardMeal(start, meal, week)?.let { throw ConflictException(it) }
                if (meal.prep != null && meal.status == MealStatus.SKIPPED) boxShare(start, meal)
            }
            placeIn(start, day, slot, candidate, locked = true)
            if (eaten) eating.markStatus(start, day, slot, MealStatus.EATEN)
        }
        return view.of(start)
    }

    /**
     * Puts the share of [meal], a skipped later meal of a prepped batch, in the box under its slot (issue 265), with what
     * the stock gave for it: the standard meal that takes the slot next is no meal of the batch, and the share stays in
     * the batch, in the box, until it is eaten elsewhere ([PrepRepository.eatSpare]). What was in the skipped meal goes
     * with it ([WeekMeals.save]): a share in the box is eaten as its recipe has it.
     */
    private fun boxShare(start: LocalDate, meal: PlannedMeal) {
        val batch = weekMeals.batchesOf(listOf(meal)).values.single()
        val share = batch.mealAt(start.plusDays(meal.day.toLong()), meal.slot)!!.share
        store.deleteMealPortion(start, meal.day, meal.slot)
        store.insertBoxPortion(start, meal.day, meal.slot, batch.id, share)
        store.moveStockUses(from = MealRef(start, meal.day, meal.slot), to = MealRef(start, meal.day, meal.slot, boxed = true))
    }

    /**
     * Locks a meal or unlocks it. A prepped meal is always locked: the batch is made for it, so unlocking it is a
     * [ConflictException]; so is a slot of foods alone (issue 358), which the planner leaves alone whatever a lock says.
     */
    fun setLocked(weekStart: String, day: Int, slot: MealSlot, locked: Boolean): WeekPlan {
        val start = parseWeekStart(weekStart)
        store.writing {
            val meal = weekMeals.of(start).firstOrNull { it.day == day && it.slot == slot }
            if (!locked && meal?.prep != null) throw ConflictException("A prepped meal stays in the plan: remove it or the prep instead")
            if (!locked && meal?.foodsOnly == true) throw ConflictException("A meal of foods stays in the plan: remove it instead")
            store.setLocked(start, day, slot, locked)
        }
        return view.of(start)
    }

    /**
     * Takes a meal out of the plan, unless it keeps its recipe ([KeptMeals.requireNotCooked]): what the stock gave
     * for it would go with it, so un-eating could not give it back. The leftover lunch of an eaten dinner
     * is refused rather than given its share back: the dinner was cooked with it, so that share is used
     * whether the lunch is eaten or not (as skipping the lunch says, which keeps it). Its side goes with it,
     * unless the side stays ([KeptMeals.whySideStays]). A standard meal that took the place of a skipped prepped meal gives
     * the slot back to it ([unbox]), so that its share is not lost from the box (issue 265).
     */
    fun remove(weekStart: String, day: Int, slot: MealSlot): WeekPlan {
        val start = parseWeekStart(weekStart)
        removeIn(start, day, slot)
        return view.of(start)
    }

    /** [remove] without answering the week, for a caller that writes more in the same transaction. */
    internal fun removeIn(start: LocalDate, day: Int, slot: MealSlot) {
        store.writing {
            val week = weekMeals.of(start)
            keptMeals.requireReplaceable(start, week, day, slot)
            week.firstOrNull { it.day == day && it.slot == slot }?.let { meal -> keptMeals.whySideStays(start, meal)?.let { throw ConflictException(it) } }
            val box = weekMeals.box(start, day, slot)
            // Read before the meal goes, which takes them along.
            val inBox = stock.taken(MealRef(start, day, slot, boxed = true))
            store.deleteMeal(start, day, slot)
            box?.let { unbox(start, day, slot, it, inBox) }
            foods.dropUnused()
        }
    }

    /**
     * Puts the skipped meal of the batch of [box] back in [day]'s [slot] of the week starting [start], whose standard meal
     * was just removed: its share comes out of the box, with what the stock gave for it ([inBox]), as the meal's own
     * again, as it was before the standard meal took its place. A share eaten elsewhere meanwhile (0) leaves nothing to
     * put back, and the slot stays empty.
     */
    private fun unbox(start: LocalDate, day: Int, slot: MealSlot, box: SelectBoxPortion, inBox: List<SelectStockUses>) {
        val share = box.portions ?: 0.0
        val batch = store.batches(listOf(box.prepId)).values.singleOrNull()
        if (share <= 0.0 || batch == null) return
        val recipe = recipes.candidate(batch.recipeId)
        val skipped = PlannedMeal(
            day, slot, batch.recipeId, batch.title, share, (recipe?.kcal ?: 0.0) * share, (recipe?.proteinG ?: 0.0) * share,
            status = MealStatus.SKIPPED,
        )
        weekMeals.saveInBatch(start, skipped, batch.id)
        stock.keep(MealRef(start, day, slot), inBox)
    }
}
