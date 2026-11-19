package dev.tobins.mealplanner.api

import kotlinx.serialization.Serializable

/** The meals of a day, in eating order. [share] is the part of the daily calories a slot gets. */
enum class MealSlot(val share: Double) {
    BREAKFAST(0.22),
    LUNCH(0.30),
    DINNER(0.38),
    SNACK(0.10),
}

/** One meal in a week plan. */
@Serializable
data class PlannedMeal(
    /** 0 is the plan's first day. */
    val day: Int,
    val slot: MealSlot,
    val recipeId: Long,
    val title: String,
    /** How many of the recipe's servings to eat. */
    val portions: Double,
    val kcal: Double,
    val proteinG: Double,
    /** True when this is what is left of a recipe cooked on an earlier day. */
    val leftover: Boolean = false,
    /** Locked meals survive regeneration of the plan. */
    val locked: Boolean = false,
    val status: MealStatus = MealStatus.PLANNED,
    /** Estimated euros for the portions eaten, with the week's offers; null when unknown. */
    val cost: Double? = null,
    /**
     * The servings to cook when this meal is made, what the recipe shows when opened from it; null
     * from a server that does not send it. A dinner: its own [portions] plus those of the next day's
     * leftover lunch cooked with it. That lunch: 0, it was cooked the day before. A skipped meal: 0.
     * Not stored: it follows from the week's meals, and agrees with what the shopping list counts.
     */
    val cookPortions: Double? = null,
    /**
     * Grams of fibre in the portions eaten; null from a server before fibre counted (issue 223), and then
     * the app shows no fibre. Not stored with the meal as [kcal] is: it follows from the recipe's
     * nutrition as it is now, as [cost] follows from today's prices, so meals planned before fibre
     * counted have it too.
     */
    val fibreG: Double? = null,
    /**
     * True when what was in this meal differs from its recipe (issue 226, [MealLines]). Then [kcal],
     * [proteinG] and [fibreG] are those of the meal as adjusted, worked out when read; an app that
     * does not know adjustments shows the right numbers without this.
     */
    val adjusted: Boolean = false,
    /**
     * The prepped batch this meal is eaten from (issue 234); null for any other meal and from a server
     * before preps. A prepped meal is always [locked]. The meal on the day the batch is made cooks all
     * of it ([cookPortions]); the later ones are [leftover] and cook nothing, so an app that does not
     * know preps shows them as leftovers.
     */
    val prep: MealPrep? = null,
)

/**
 * One batch of a recipe, cooked once on [madeOn] and eaten in [meals] until [lastDay] (issue 234). Started
 * by hand only: the planner plans around it and never starts one.
 */
@Serializable
data class MealPrep(
    val id: Long,
    /** ISO date it is made; its first meal is that day. */
    val madeOn: String,
    /** ISO date of the last day it keeps: [madeOn] plus the recipe's days for a normal box or [vacuum] sealed. */
    val lastDay: String,
    val vacuum: Boolean,
    /** Every meal of the batch in eating order, also those in another plan week. */
    val meals: List<PrepMeal>,
) {
    /** The servings in the batch: what is cooked and bought on [madeOn]. */
    val portions: Double get() = meals.sumOf { it.portions }
}

/** A meal of a [MealPrep]: where it is eaten, its share of the batch and what became of it. */
@Serializable
data class PrepMeal(
    val date: String,
    val slot: MealSlot,
    /** Its share of the batch: the meal's portions, or 0 when it was skipped and its share is eaten in another meal. */
    val portions: Double,
    val status: MealStatus,
)

/** A lunch or dinner on one date, as a prep names its meals. */
@Serializable
data class PrepSlot(
    /** ISO date. */
    val date: String,
    val slot: MealSlot,
)

/**
 * Body of `POST /api/plans/{weekStart}/preps`: [recipeId] made on [madeOn] in a normal box or [vacuum]
 * sealed, and eaten in [meals] (lunches and dinners, the first on [madeOn], none after the day the
 * recipe's keeps allow). Each meal gets the portion the planner would give it, within the profile's range.
 */
@Serializable
data class PrepInput(
    val recipeId: Long,
    val madeOn: String,
    val vacuum: Boolean = false,
    val meals: List<PrepSlot>,
)

/**
 * The share of a skipped meal of a batch, still in the box (`GET /api/preps/spare/{date}`): to eat in a
 * later lunch or dinner up to [lastDay]. On [lastDay] itself it is to eat that day or throw away; after
 * it, it is not offered at all.
 */
@Serializable
data class SparePortion(
    val prepId: Long,
    val recipeId: Long,
    val title: String,
    val portions: Double,
    /** The meal that was skipped. */
    val skipped: PrepSlot,
    val lastDay: String,
    /** The lunches and dinners it can still be eaten in, from the date asked about on, earliest first: empty or free to plan. */
    val room: List<PrepSlot>,
)

/** Body of `POST /api/plans/{weekStart}/preps/{id}/spare`: eat the share of the skipped meal [from] in [to] instead. */
@Serializable
data class SpareMove(val from: PrepSlot, val to: PrepSlot)

/**
 * The recipe the user puts in a meal's slot themselves (`PUT /api/plans/{weekStart}/meals/{day}/{slot}`).
 * A request type only: the answer is the week's [WeekPlan], unchanged. Leaving a meal out is the
 * existing status route with [MealStatus.SKIPPED].
 */
@Serializable
data class MealChoice(val recipeId: Long)

@Serializable
data class PlanDay(
    /** ISO date, e.g. `2026-11-16`. */
    val date: String,
    val meals: List<PlannedMeal>,
    val kcal: Double,
    val proteinG: Double,
    /** Fibre of the meals not skipped, as [kcal]; null from a server before fibre counted. */
    val fibreG: Double? = null,
)

@Serializable
data class WeekPlan(
    /** ISO date of the first day. */
    val weekStart: String,
    val targets: DailyTargets,
    val days: List<PlanDay>,
    /** Slots the planner had no suitable recipe for. */
    val emptySlots: List<MealSlot>,
    /** Estimated euros for every planned meal with the week's offers, and at regular prices. */
    val cost: Double? = null,
    val costWithoutOffers: Double? = null,
)
