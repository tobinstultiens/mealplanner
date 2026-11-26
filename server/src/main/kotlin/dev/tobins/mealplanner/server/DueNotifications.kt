package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.DinnerToday
import dev.tobins.mealplanner.api.EndingOffer
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.NotificationFacts
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.WeighInDue
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * What one account's data says is due on the server's today, for the Android app's notifications
 * (`GET /api/notifications`, issue 225). The conditions are tested here, once, so that every app agrees;
 * the app words the facts in its own language and decides by its switches and the hour whether to tell.
 * Everything comes through the account's own repositories; only the offers are shared.
 */
class DueNotifications(
    private val shopping: ShoppingListRepository,
    private val plans: PlanRepository,
    private val preps: PrepRepository,
    private val tracking: TrackingRepository,
    private val profiles: ProfileRepository,
    /** The stores the account shops at: an offer elsewhere is no reason to hurry. */
    private val shops: () -> Set<Store>,
    private val today: () -> LocalDate = ::serverToday,
) {
    fun facts(): NotificationFacts {
        val day = today()
        val (weekStart, index, meals) = plans.mealsOn(day)
        return NotificationFacts(
            date = day.toString(),
            offersEnding = offersEnding(day, weekStart),
            planNextWeek = planNextWeek(day, weekStart)?.toString(),
            weighIn = weighIn(day),
            dinner = meals.firstOrNull { it.slot == MealSlot.DINNER && it.status == MealStatus.PLANNED }?.let { dinner(it, weekStart, index) },
            mealsToMark = meals.count { it.status == MealStatus.PLANNED },
            prepsPastLastDay = preps.pastLastDay(day),
        )
    }

    /**
     * The rows of this week's list (the week starting [weekStart]) still to buy that have an offer at one of
     * the account's stores ending on [day] or the next day. The offer must have started: one that begins
     * tomorrow is no reason to hurry. The advice is empty when dismissed.
     */
    private fun offersEnding(day: LocalDate, weekStart: LocalDate): List<EndingOffer> {
        val list = shopping.get(weekStart.toString())
        val inUse = shops()
        val stillToBuy = list.items.filter(::stillToBuy) + (list.buyAhead + list.stockUp).filter { !it.checked }
        return stillToBuy.mapNotNull { item -> endingOn(item, day, inUse)?.let { EndingOffer(item.name, it.toString()) } }
            .distinctBy { it.name }
            .sortedBy { it.endsOn }
    }

    /**
     * Whether [item], a row of what the meals need or an own item, is still to buy: not ticked, needed by a meal
     * still to cook ([ShoppingItem.toComeGrams] not 0), and not covered by what is at home: a row on hand with a
     * known amount left ([ShoppingItem.onHandGrams]) at least what those meals need, as the list itself counts it.
     * A row on hand in an amount nobody knows stays: the list asks to check, and it may have to be bought.
     */
    private fun stillToBuy(item: ShoppingItem): Boolean {
        if (item.checked || item.toComeGrams == 0.0) return false
        val left = item.onHandGrams ?: return true
        val needed = item.toComeGrams ?: return true
        return left < needed
    }

    /** The earliest day, [day] or the next, that an offer of [item] at one of [inUse] valid on [day] ends; null when none does. */
    private fun endingOn(item: ShoppingItem, day: LocalDate, inUse: Set<Store>): LocalDate? =
        item.offers.filter { it.store in inUse && !LocalDate.parse(it.validFrom).isAfter(day) }
            .map { LocalDate.parse(it.validUntil) }
            .filter { it == day || it == day.plusDays(1) }
            .minOrNull()

    /**
     * Next week's first day, on a Saturday or Sunday, while that week has no meals of its own (those of a prepped
     * batch made this week are there already, [PlanRepository.isPlanned]). Whatever the offers: the
     * owner plans on Saturday, and only Jumbo lists next week's offers before the week starts (AH and Lidl are
     * fetched once it has), so tying the reminder to offers would leave AH and Lidl shoppers without it.
     */
    private fun planNextWeek(day: LocalDate, weekStart: LocalDate): LocalDate? {
        if (day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY) return null
        val next = weekStart.plusWeeks(1)
        return next.takeIf { !plans.isPlanned(next) }
    }

    /** The newest weigh-in, when it is a week old or older and there is a profile its weight goes to. */
    private fun weighIn(day: LocalDate): WeighInDue? {
        if (profiles.profile() == null) return null
        val last = tracking.lastWeighed() ?: return null
        val days = ChronoUnit.DAYS.between(last, day)
        return if (days >= WEIGH_IN_AFTER_DAYS) WeighInDue(last.toString(), days.toInt()) else null
    }

    /**
     * Tonight's [meal], still to eat, as what there is to cook: its main, also when that makes a prepped batch ([DinnerToday.prep]);
     * else, when the main cooks nothing (a later meal of a batch made on an earlier day), the side next to it when that is
     * a batch made tonight ([DinnerToday.side], issue 263); null when nothing is cooked tonight. A dinner of foods alone (issue
     * 358) has no recipe to cook.
     */
    private fun dinner(meal: PlannedMeal, weekStart: LocalDate, index: Int): DinnerToday? {
        val cooks = meal.cookPortions ?: meal.portions
        if (cooks > 0.0 && !meal.foodsOnly) return DinnerToday(meal.recipeId, meal.title, meal.portions, cooks, weekStart.toString(), index, prep = meal.prep != null)
        val side = meal.side?.takeIf { it.cookPortions > 0.0 } ?: return null
        return DinnerToday(side.recipeId, side.title, side.portions, side.cookPortions, weekStart.toString(), index, prep = true, side = true)
    }

    companion object {
        /** A weigh-in this many days old is due again. */
        const val WEIGH_IN_AFTER_DAYS = 7L
    }
}
