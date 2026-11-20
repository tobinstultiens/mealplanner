package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PrepInput
import dev.tobins.mealplanner.api.PrepPastLastDay
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.SparePortion
import dev.tobins.mealplanner.api.SpareMove
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.planner.WeekPlanner
import dev.tobins.mealplanner.server.db.Database
import java.time.LocalDate

/**
 * One account's prepped meals (issue 234): a recipe made once and eaten over several days, started by hand
 * only, as the slots' meals, as sides next to them (step 2), or both (issue 269). Making, growing by a side, removing
 * and the share of a skipped meal are here; how the plan, the shopping list and the stock count a batch is
 * [PlanRepository]'s ([Batch]).
 */
class PrepRepository(
    private val db: Database,
    private val userId: Long,
    private val plans: PlanRepository,
    private val recipes: RecipeRepository,
    private val profiles: ProfileRepository,
    /** Today as the household sees it: a batch is never made on a day that has passed. */
    private val today: () -> LocalDate = ::serverToday,
) {
    private val q = db.planQueries

    /**
     * Makes [input]'s recipe on its made-on day and puts it in each of its meals, locked, each with the portion
     * the planner would give that slot within the profile's range; the batch is their sum. The recipe must be
     * one of mine, a meal, with amounts, and keep for the chosen way ([RecipeKeeps]): the meals are lunches and
     * dinners from the made-on day (the first of them is that day) to the last day it keeps. A slot may be
     * empty or hold a meal that may be replaced ([PlanRepository.whyKeepsItsRecipe]) and is of no other batch;
     * a dinner's leftover lunch goes with it. The made-on day is today or later: a batch can't be cooked in the past, and
     * the app offers no such day (issue 263). Answers the week starting [weekStart], the one the app shows.
     *
     * A slot with a [PrepSlot.side] eats the batch as a side of that many servings (a quarter or half) next to its meal
     * instead ([requireRoomForSide]): the meal keeps its main and its portion. One batch may be eaten so in some slots
     * and as the meal in others (issue 269); it is the sum of all its shares, and made with its first, a meal or a side,
     * on the made-on day. [PrepInput.side], as apps before issue 269 send it, makes every slot such a side.
     */
    fun create(weekStart: String, input: PrepInput): WeekPlan {
        val shown = parseWeekStart(weekStart)
        val madeOn = Batch.parseDate(input.madeOn)
        val places = input.meals.map(Batch::placeOf)
        require(input.meals.size >= 2) { "a prep is eaten in at least two meals" }
        require(places.distinct().size == places.size) { "each meal at most once" }
        require(input.meals.all { it.slot == MealSlot.LUNCH || it.slot == MealSlot.DINNER }) { "a prep is eaten at lunch or dinner" }
        require(input.side == null || input.meals.all { it.side == null }) { "a side is given for every meal or for each, not both" }
        val sides = input.meals.map { it.side ?: input.side }
        require(sides.all { it == null || it in SIDE_PORTIONS }) { "a side is a quarter or half a serving" }
        val dates = input.meals.map { Batch.parseDate(it.date) }
        require(dates.min() == madeOn) { "the first meal is on the day it is made" }
        if (madeOn < today()) throw ConflictException("${Batch.dayName(madeOn)} has passed: a prep is made today or later")
        val recipe = recipes.requireMine(input.recipeId)
        if (recipe.kind == RecipeKind.COMPONENT.name) throw ConflictException("This recipe is a building block, not a meal")
        val keeps = (if (input.vacuum) recipe.keepsVacuumDays else recipe.keepsDays)
            ?: throw ConflictException("Set how many days \"${recipe.title}\" keeps ${if (input.vacuum) "vacuum sealed" else "in a box"} first")
        val lastDay = madeOn.plusDays(keeps)
        if (dates.max() > lastDay) throw ConflictException("\"${recipe.title}\" keeps until ${Batch.dayName(lastDay)}")
        val candidate = recipes.candidate(input.recipeId) ?: throw ConflictException("This recipe has no amounts to plan with")
        val targets = plans.targets()
        val range = profiles.portions()
        db.writing {
            val weeks = places.map { it.first }.distinct().associateWith { plans.meals(it) }
            for ((place, side) in places.zip(sides)) {
                val (week, day, slot) = place
                if (side != null) requireRoomForSide(week, weeks.getValue(week), day, slot) else requireRoom(week, weeks.getValue(week), day, slot)
            }
            q.insertPrep(userId, input.recipeId, madeOn.toString(), lastDay.toString(), if (input.vacuum) 1 else 0)
            val prepId = q.lastPrepId().executeAsOne()
            for ((place, side) in places.zip(sides)) {
                val (week, day, slot) = place
                if (side != null) {
                    q.insertSidePortion(userId, week.toString(), day.toLong(), slot.name, prepId, side)
                    continue
                }
                // A slot of the prep, a side's too, is not the leftover lunch that goes with a dinner it replaces.
                val taken = places.filter { it.first == week }.map { it.second to it.third }.toSet()
                plans.leftoversToReplace(week, weeks.getValue(week), day, slot)?.takeIf { (it.day to it.slot) !in taken }
                    ?.let { plans.deleteMeal(week, it.day, it.slot) }
                val portions = WeekPlanner.portionsFor(candidate, slot, targets, range)
                val meal = PlannedMeal(day, slot, candidate.recipeId, candidate.title, portions, candidate.kcal * portions, candidate.proteinG * portions)
                plans.saveInBatch(week, meal, prepId)
            }
        }
        return plans.view(shown)
    }

    /**
     * Takes batch [id] out of the plan with all its meals, in every week: what removing the meal that makes it
     * does, once the app has named the later meals. Not once the batch was made (one of its meals eaten): its
     * meals then hold what the stock gave, and the later ones are skipped instead. A batch of another account
     * is not there (404). A batch eaten as sides takes only its sides: the meals they go with stay. A meal of
     * the batch with a side of another batch takes that side with it, unless that side stays
     * ([PlanRepository.whySideStays]).
     */
    fun remove(weekStart: String, id: Long): WeekPlan {
        val shown = parseWeekStart(weekStart)
        db.writing {
            val batch = batch(id)
            if (batch.made) throw ConflictException(madeAlready(batch))
            val weeks = batch.meals.map { it.week }.distinct().associateWith { plans.meals(it) }
            for (meal in batch.meals.filterNot { it.side }) {
                val stored = weeks.getValue(meal.week).first { it.day == meal.meal.day && it.slot == meal.meal.slot }
                plans.whySideStays(meal.week, stored)?.let { why ->
                    throw ConflictException("${Batch.slotName(meal.date, meal.meal.slot)}: ${why.replaceFirstChar { it.lowercase() }}")
                }
            }
            for (meal in batch.meals) {
                if (meal.side) q.deleteSidePortion(userId, meal.week.toString(), meal.meal.day.toLong(), meal.meal.slot.name)
                else plans.deleteMeal(meal.week, meal.meal.day, meal.meal.slot)
            }
            q.deletePrep(userId, id)
        }
        return plans.view(shown)
    }

    /**
     * Takes the side next to the meal in [day]'s [slot] of the week starting [weekStart] out of its batch, which is made
     * smaller; the meal keeps its main. Not the side that makes the batch, nor one of a batch that was made
     * ([PlanRepository.whySideStays]); a meal without a side is a [NotFoundException].
     */
    fun removeSide(weekStart: String, day: Int, slot: MealSlot): WeekPlan {
        val start = parseWeekStart(weekStart)
        db.writing {
            val meal = plans.meals(start).firstOrNull { it.day == day && it.slot == slot && it.side != null }
                ?: throw NotFoundException("No side next to the meal of day $day $slot")
            plans.whySideStays(start, meal)?.let { throw ConflictException(it) }
            q.deleteSidePortion(userId, start.toString(), day.toLong(), slot.name)
        }
        return plans.view(start)
    }

    /**
     * Adds a side of [PrepSlot.side] servings of batch [id] next to the meal in [side]'s slot (issue 269): the batch grows
     * by it, and is bought and cooked with it. Only while the batch is still to make, as what was made can't grow: not
     * once one of its meals was eaten. The slot is after the batch's first share, which stays the one that makes it, up
     * to its last day, today or later, and has a meal still to eat without a side that is not of this batch itself
     * ([requireRoomForSide]). A batch of another account is not there (404). Answers the week starting [weekStart].
     */
    fun addSide(weekStart: String, id: Long, side: PrepSlot): WeekPlan {
        val shown = parseWeekStart(weekStart)
        val portions = side.side
        require(portions != null && portions in SIDE_PORTIONS) { "a side is a quarter or half a serving" }
        require(side.slot == MealSlot.LUNCH || side.slot == MealSlot.DINNER) { "a prep is eaten at lunch or dinner" }
        val (week, day, slot) = Batch.placeOf(side)
        val date = week.plusDays(day.toLong())
        db.writing {
            val batch = batch(id)
            if (batch.made) throw ConflictException("This prepped batch was made already: it can't take another side")
            if (date < today()) throw ConflictException("${Batch.dayName(date)} has passed: a side is added today or later")
            if (date > batch.lastDay) throw ConflictException("\"${batch.title}\" keeps until ${Batch.dayName(batch.lastDay)}")
            val first = batch.meals.firstOrNull() ?: throw NotFoundException("No prep with id $id")
            if (!after(date, slot, first)) {
                throw ConflictException("It is made with ${Batch.slotName(first.date, first.meal.slot)}: a side of it comes after that")
            }
            val meals = plans.meals(week)
            requireRoomForSide(week, meals, day, slot)
            if (meals.any { it.day == day && it.slot == slot && it.prep?.id == id }) {
                throw ConflictException("${Batch.slotName(date, slot)} is a meal of this batch already")
            }
            q.insertSidePortion(userId, week.toString(), day.toLong(), slot.name, id, portions)
        }
        return plans.view(shown)
    }

    /**
     * The shares of skipped meals still in the box on [date]: of every batch that keeps until then or later,
     * each skipped meal whose share was not eaten elsewhere, with the lunches and dinners it can still go to
     * ([room]) and the next free one of them to propose ([SparePortion.proposed]): empty for a meal's share, so that one
     * tap never replaces a planned meal. Never one past its last day: from the day after, it is not listed at all.
     */
    fun spare(date: String): List<SparePortion> {
        val day = Batch.parseDate(date)
        val ids = q.selectPrepsKeeping(userId, day.toString()).executeAsList().map { it.id }
        val weeks = HashMap<LocalDate, List<PlannedMeal>>()
        return Batch.load(db, userId, ids).values.sortedBy { it.madeOn }.flatMap { batch ->
            batch.meals.filter { it.meal.status == MealStatus.SKIPPED && it.share > 0.0 }.map { skipped ->
                val weekOf = { week: LocalDate -> weeks.getOrPut(week) { plans.meals(week) } }
                val room = room(batch, skipped, from = day, weekOf)
                SparePortion(
                    prepId = batch.id,
                    recipeId = batch.recipeId,
                    // A side's meal is that of its main: the side is the batch's recipe.
                    title = if (skipped.side) batch.title else skipped.meal.title,
                    portions = skipped.share,
                    skipped = PrepSlot(skipped.date.toString(), skipped.meal.slot),
                    lastDay = batch.lastDay.toString(),
                    room = room,
                    side = skipped.side,
                    proposed = if (skipped.side) room.firstOrNull() else room.firstOrNull { isEmpty(it, weekOf) },
                )
            }
        }
    }

    /**
     * The batches whose last day was the day before [today] that may have left something in a box ([Batch.pastLastDay]),
     * for the notification to throw it away (issue 264). Only the day after: the box is to go then, and a batch is told
     * of once. Each batch goes by its own last day, so one kept vacuum sealed comes later than one in a box.
     */
    fun pastLastDay(today: LocalDate): List<PrepPastLastDay> {
        val ids = q.selectPrepsEndingOn(userId, today.minusDays(1).toString()).executeAsList()
        val batches = Batch.load(db, userId, ids)
        return ids.mapNotNull { batches[it]?.pastLastDay() }
    }

    /**
     * Eats the share of the skipped meal [SpareMove.from] of batch [id] in [SpareMove.to] instead: a lunch or dinner
     * after it, up to the batch's last day, that has room ([hasRoom]); whatever was planned there goes. The skipped
     * meal stays skipped with a share of 0, and what the stock gave for its share and what was in it go along, so
     * the batch, the shopping list and the stock stay as they were. A skipped side goes next to the meal there
     * instead ([hasRoomForSide]), with what the stock gave for it, and is no longer next to the skipped meal; the side
     * the batch is made with stays there at 0, as a skipped meal does, so its meal still makes the batch (it shows
     * the batch to cook and can't be removed on its own). Answers the week starting [weekStart].
     */
    fun eatSpare(weekStart: String, id: Long, move: SpareMove): WeekPlan {
        val shown = parseWeekStart(weekStart)
        require(move.to.slot == MealSlot.LUNCH || move.to.slot == MealSlot.DINNER) { "a prep is eaten at lunch or dinner" }
        db.writing {
            val batch = batch(id)
            val from = batch.mealAt(Batch.parseDate(move.from.date), move.from.slot)
                ?.takeIf { it.meal.status == MealStatus.SKIPPED && it.share > 0.0 }
                ?: throw ConflictException("That meal of the batch is not skipped, or its share is eaten elsewhere already")
            val (week, day, slot) = Batch.placeOf(move.to)
            val target = week.plusDays(day.toLong())
            if (target > batch.lastDay) throw ConflictException("It keeps until ${Batch.dayName(batch.lastDay)}: it can't be eaten later")
            if (!after(target, slot, from)) throw ConflictException("It can only be eaten in a later meal")
            val meals = plans.meals(week)
            val old = from.ref
            if (from.side) {
                if (!hasRoomForSide(meals, day, slot, batch.id)) throw ConflictException("${Batch.slotName(target, slot)} has no meal still to eat without a side")
                q.insertSidePortion(userId, week.toString(), day.toLong(), slot.name, batch.id, from.share)
                // The batch is still made on its made-on day, with that meal's side, as with a meal of a batch.
                if (batch.isCook(old.week, from.meal, side = true)) {
                    q.clearSideShare(userId, old.week.toString(), old.day.toLong(), old.slot.name)
                } else {
                    q.deleteSidePortion(userId, old.week.toString(), old.day.toLong(), old.slot.name)
                }
            } else {
                if (!hasRoom(week, meals, day, slot, batch.id)) throw ConflictException("${Batch.slotName(target, slot)} has a meal that stays")
                plans.leftoversToReplace(week, meals, day, slot)?.let { plans.deleteMeal(week, it.day, it.slot) }
                plans.saveInBatch(week, from.meal.copy(day = day, slot = slot, status = MealStatus.PLANNED), batch.id)
                q.clearPrepShare(userId, old.week.toString(), old.day.toLong(), old.slot.name)
                q.moveMealLines(week.toString(), day.toLong(), slot.name, userId, old.week.toString(), old.day.toLong(), old.slot.name)
            }
            q.moveStockUses(week.toString(), day.toLong(), slot.name, userId, old.week.toString(), old.day.toLong(), old.slot.name, old.role)
        }
        return plans.view(shown)
    }

    /**
     * Why [batch], made already, can't be removed, in the words of how it is eaten: a batch eaten as sides speaks of its
     * sides, which are skipped with their meals (issue 263), and one eaten both ways of both (issue 269).
     */
    private fun madeAlready(batch: Batch): String = when {
        batch.meals.all { it.side } -> "This prepped side was made already: mark the meals you do not eat it with skipped instead"
        batch.meals.none { it.side } -> "This prepped batch was made already: mark the meals you do not eat skipped instead"
        else -> "This prepped batch was made already: mark the meals you do not eat, or do not eat it with, skipped instead"
    }

    /** Batch [id] of this account; a [NotFoundException] for any other. */
    private fun batch(id: Long): Batch = Batch.load(db, userId, listOf(id))[id] ?: throw NotFoundException("No prep with id $id")

    /**
     * The lunches and dinners [skipped]'s share may still be eaten in: after it, from [from] on, up to [batch]'s last
     * day, each one that [hasRoom], or for a side [hasRoomForSide]. [weekOf] gives a week's meals.
     */
    private fun room(batch: Batch, skipped: BatchMeal, from: LocalDate, weekOf: (LocalDate) -> List<PlannedMeal>): List<PrepSlot> =
        generateSequence(maxOf(from, skipped.date)) { it.plusDays(1) }.takeWhile { it <= batch.lastDay }.flatMap { date ->
            listOf(MealSlot.LUNCH, MealSlot.DINNER).filter { slot -> after(date, slot, skipped) }.map { PrepSlot(date.toString(), it) }
        }.filter { slot ->
            val (week, day, mealSlot) = Batch.placeOf(slot)
            if (skipped.side) hasRoomForSide(weekOf(week), day, mealSlot, batch.id) else hasRoom(week, weekOf(week), day, mealSlot, batch.id)
        }.toList()

    /** Whether [slot] holds no meal, in its week as [weekOf] gives it: a free lunch or dinner to propose for a share ([SparePortion.proposed]). */
    private fun isEmpty(slot: PrepSlot, weekOf: (LocalDate) -> List<PlannedMeal>): Boolean {
        val (week, day, mealSlot) = Batch.placeOf(slot)
        return weekOf(week).none { it.day == day && it.slot == mealSlot }
    }

    private fun after(date: LocalDate, slot: MealSlot, meal: BatchMeal): Boolean =
        date > meal.date || (date == meal.date && slot.ordinal > meal.meal.slot.ordinal)

    /**
     * Whether a slot can take a meal of batch [batchId]: it is empty, or its meal is still to eat, not locked (the
     * owner chose it), of no batch, and may be replaced ([PlanRepository.whyKeepsItsRecipe]); and its side, if it has
     * one, is of another batch, as a batch is never both a slot's meal and the side next to it ([Batch.mealAt]).
     */
    private fun hasRoom(week: LocalDate, meals: List<PlannedMeal>, day: Int, slot: MealSlot, batchId: Long): Boolean {
        val meal = meals.firstOrNull { it.day == day && it.slot == slot } ?: return true
        return meal.prep == null && !meal.locked && meal.status == MealStatus.PLANNED && meal.side?.prep?.id != batchId &&
            plans.whyKeepsItsRecipe(week, meal, meals) == null
    }

    /** Whether a slot can take a side of batch [batchId]: its meal is still to eat, has no side, and is not of that batch itself. */
    private fun hasRoomForSide(meals: List<PlannedMeal>, day: Int, slot: MealSlot, batchId: Long): Boolean {
        val meal = meals.firstOrNull { it.day == day && it.slot == slot } ?: return false
        return meal.status == MealStatus.PLANNED && meal.side == null && meal.prep?.id != batchId
    }

    /**
     * Refuses a slot for a side of a batch unless it has a meal still to eat without a side: a side goes next to a
     * meal's main, which it neither replaces nor resizes, and is eaten or skipped with it.
     */
    private fun requireRoomForSide(week: LocalDate, meals: List<PlannedMeal>, day: Int, slot: MealSlot) {
        val meal = meals.firstOrNull { it.day == day && it.slot == slot }
        val name = Batch.slotName(week.plusDays(day.toLong()), slot)
        when {
            meal == null -> throw ConflictException("$name has no meal for a side to go with")
            meal.status != MealStatus.PLANNED -> throw ConflictException("$name was ${meal.status.name.lowercase()} already")
            meal.side != null -> throw ConflictException("$name has a side already")
        }
    }

    /**
     * Refuses a slot for a meal of a batch unless it is empty or its meal may be replaced and is of no other batch:
     * a locked meal the owner chose may be, as making a prep there is choosing again.
     */
    private fun requireRoom(week: LocalDate, meals: List<PlannedMeal>, day: Int, slot: MealSlot) {
        val meal = meals.firstOrNull { it.day == day && it.slot == slot } ?: return
        val name = Batch.slotName(week.plusDays(day.toLong()), slot)
        if (meal.prep != null) throw ConflictException("$name is from another prepped batch already")
        plans.whyKeepsItsRecipe(week, meal, meals)?.let { throw ConflictException("$name: $it") }
    }

    companion object {
        /** The portions a side may be, in servings: a quarter or half a serving, as the owner portions one (issue 234). */
        val SIDE_PORTIONS = setOf(0.25, 0.5)
    }
}
