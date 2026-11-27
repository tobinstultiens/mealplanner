package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.BatchToMake
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.PlanDay
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.server.accounts.ProfileRepository
import dev.tobins.mealplanner.server.plan.WeekMeals.Companion.DAYS
import dev.tobins.mealplanner.server.plan.WeekMeals.Companion.cookPortions
import java.time.LocalDate

/**
 * The week as the Plan shows it ([WeekPlan]): its meals with their numbers and costs, each day's sums, the week's cost,
 * the rests of its fresh packs and the batches still to make. What every change of the plan answers, once it is stored.
 */
class WeekView(
    private val store: PlanStore,
    private val weekMeals: WeekMeals,
    private val costs: WeekCosts,
    private val adjustments: MealAdjustments,
    private val foods: SlotFoods,
    private val profiles: ProfileRepository,
) {
    fun get(weekStart: String): WeekPlan = of(parseWeekStart(weekStart))

    internal fun of(start: LocalDate): WeekPlan {
        val costOf = costs.costsIn(start)
        val week = weekMeals.of(start)
        val holding = weekMeals.holdingStock(start, week)
        val shown = foods.withFoods(adjustments.withNutrition(start, week)).map { meal -> costs.priced(meal, costOf).copy(cookPortions = cookPortions(meal, week, holding)) }
        val (cost, costWithoutOffers) = costs.costOfWeek(shown, costOf)
        return WeekPlan(
            weekStart = start.toString(),
            targets = profiles.planTargets(),
            days = (0 until DAYS).map { day ->
                val ofDay = shown.filter { it.day == day }.sortedBy { it.slot.ordinal }
                val sums = costs.sums(ofDay)
                PlanDay(start.plusDays(day.toLong()).toString(), ofDay, sums.kcal, sums.proteinG, sums.fibreG)
            },
            emptySlots = MealSlot.entries.filter { slot -> shown.none { it.slot == slot } },
            cost = cost,
            costWithoutOffers = costWithoutOffers,
            rest = costs.restOf(start, week),
            batchesToMake = batchesToMake(start),
            dinnersChosen = store.countPicks(start),
        )
    }

    /**
     * The account's batches still to make whose days reach into the week starting [start] ([WeekPlan.batchesToMake]): those
     * keeping until its Monday or later, made by its Sunday, with no meal eaten. A batch can only grow by a side until it is
     * made, and one made the week before may have no meal in this week yet keep into it (issue 263).
     */
    private fun batchesToMake(start: LocalDate): List<BatchToMake> {
        val end = start.plusDays(DAYS - 1L).toString()
        val ids = store.prepsKeeping(start).filter { it.madeOn <= end }.map { it.id }
        val batches = store.batches(ids)
        return ids.mapNotNull { batches[it] }.filterNot { it.made }.map { BatchToMake(it.recipeId, it.title, it.toApi()) }
    }
}
