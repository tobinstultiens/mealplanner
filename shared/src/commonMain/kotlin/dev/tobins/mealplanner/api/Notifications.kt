package dev.tobins.mealplanner.api

import kotlinx.serialization.Serializable

/**
 * `GET /api/notifications`: what the calling account's data says is due on the server's today, for the
 * Android app's notifications (issue 225). Facts, not sentences: the conditions are tested once, on the
 * server, and the app words them in its own language and decides by its switches and the hour whether to
 * post them. Each field is empty (or null, or 0) when its condition does not hold.
 */
@Serializable
data class NotificationFacts(
    /** The server's today (ISO), the day these facts are for and what the app remembers telling them on. */
    val date: String,
    /**
     * The rows of this week's shopping list with an offer at one of the account's stores that ends today
     * or tomorrow: rows of what the meals need that are not ticked, still needed and not covered by what is
     * known to be at home, own items not ticked, and buy-ahead and stock-up advice not ticked or dismissed.
     * Those ending today first, then in the order of the list; one entry per row.
     */
    val offersEnding: List<EndingOffer> = emptyList(),
    /**
     * The first day (a Monday, ISO) of next week, on a Saturday or Sunday while that week has no meals,
     * whatever the stores' offers; null otherwise.
     */
    val planNextWeek: String? = null,
    /** The newest weigh-in, when it is 7 or more days old and the account has a profile; null otherwise. */
    val weighIn: WeighInDue? = null,
    /** Today's dinner, when one is planned and neither eaten nor skipped; null otherwise. */
    val dinner: DinnerToday? = null,
    /** How many of today's meals are still planned: neither eaten nor skipped. */
    val mealsToMark: Int = 0,
    /**
     * The prepped batches whose last day was yesterday that may have left something in a box (issue 264), oldest
     * first: made (one of their meals eaten) with a share skipped and not eaten elsewhere, or a meal never marked.
     * Not a batch eaten completely, nor one never made. Each batch is here on that one day only.
     */
    val prepsPastLastDay: List<PrepPastLastDay> = emptyList(),
)

/**
 * A prepped batch past its last day ([NotificationFacts.prepsPastLastDay]): its recipe, the day it was made and its
 * last day (ISO), whether it was eaten as sides next to meals ([side]), and what of it may still be in a box. [left]
 * counts the shares of skipped meals not eaten elsewhere: to throw away. [unmarked] counts the meals of it still
 * planned, neither eaten nor skipped, which the app cannot tell from eaten; [firstUnmarked] is the day of the first.
 */
@Serializable
data class PrepPastLastDay(
    val prepId: Long,
    val recipeId: Long,
    val title: String,
    val madeOn: String,
    val lastDay: String,
    val side: Boolean = false,
    val left: Int = 0,
    val unmarked: Int = 0,
    val firstUnmarked: String? = null,
)

/** A row of the shopping list ([NotificationFacts.offersEnding]) by its name, and the day its offer ends (ISO): today or tomorrow. */
@Serializable
data class EndingOffer(val name: String, val endsOn: String)

/** The newest weigh-in ([NotificationFacts.weighIn]): its ISO date and how many days before today it was. */
@Serializable
data class WeighInDue(val lastDate: String, val daysAgo: Int)

/**
 * Today's dinner still to cook ([NotificationFacts.dinner]): the recipe, the servings eaten and to cook (with the
 * next day's leftovers, as [PlannedMeal.cookPortions]), and where it is in the plan, so a tap can open the
 * recipe as the Today screen does.
 */
@Serializable
data class DinnerToday(
    val recipeId: Long,
    val title: String,
    val portions: Double,
    val cookPortions: Double,
    /** The plan's week (its Monday, ISO) and the day in it, 0 for Monday. */
    val weekStart: String,
    val day: Int,
)
