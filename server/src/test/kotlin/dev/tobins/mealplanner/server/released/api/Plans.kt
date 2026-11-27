// Frozen from v0.1.13 (bd9ebd8e6d679057d814bb48ea7186dbd3816907) by scripts/freeze-api.sh. Do not edit: freeze again after a release.
package dev.tobins.mealplanner.released.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
    /**
     * The side next to this meal's main, taken from a prepped batch (issue 234, step 2); null for a meal without
     * one and from a server before sides. The main keeps its own [portions], [kcal], [proteinG], [fibreG] and
     * [cost]: the side's are its own, and the day's totals ([PlanDay], [DayView]) count both. It shares the
     * meal's [status]: eaten, skipped or planned with it. An app that does not know sides shows the main alone.
     */
    val side: MealSide? = null,
    /**
     * The foods planned in this slot (issue 358), in the order they were added; empty for a meal of a recipe alone and from
     * a server before foods. Next to a recipe, [kcal], [proteinG], [fibreG] and [cost] count them with it, as an adjusted
     * meal's do, so an app that does not know foods shows the right numbers.
     */
    val foods: List<MealFood> = emptyList(),
    /**
     * True when the slot holds [foods] and no recipe. [recipeId] and [title] are then those of the recipe the server makes of
     * the foods for an app that does not know them (the foods' names, "Banana + 2 more"); it is in no list of recipes, is
     * one portion, and is no leftover. The meal is eaten, skipped, moved and removed as any other.
     */
    val foodsOnly: Boolean = false,
    /** The server's own: the recipe that holds the slot's [foods], or null for none. Never sent. */
    @Transient
    val foodsId: Long? = null,
) {
    /** The kcal of the meal with its [side]: what the meal and its day count. */
    val totalKcal: Double get() = kcal + (side?.kcal ?: 0.0)

    /** The protein of the meal with its [side]. */
    val totalProteinG: Double get() = proteinG + (side?.proteinG ?: 0.0)

    /** The fibre of the meal with its [side]; null when the main's is not known. */
    val totalFibreG: Double? get() = fibreG?.let { it + (side?.fibreG ?: 0.0) }

    /** The cost of the meal with its [side]; null when the main's is not known. */
    val totalCost: Double? get() = cost?.let { it + (side?.cost ?: 0.0) }
}

/**
 * A side next to a meal's main (issue 234, step 2): [portions] (a quarter or half a serving) of a prepped batch's
 * recipe, added by hand; the planner never adds one. Its numbers are those of its portions of the recipe as the
 * recipe is now, as a meal's fibre is.
 */
@Serializable
data class MealSide(
    val recipeId: Long,
    val title: String,
    val portions: Double,
    val kcal: Double,
    val proteinG: Double,
    val fibreG: Double? = null,
    /** Estimated euros for its portions with the week's offers; null when unknown. */
    val cost: Double? = null,
    /**
     * The servings to cook when this meal is made, as [PlannedMeal.cookPortions]: the whole batch on the day it is
     * made with this side, and 0 on its later days.
     */
    val cookPortions: Double,
    /** The batch it is taken from: its [MealPrep.meals] hold this side ([PrepMeal.side]). */
    val prep: MealPrep,
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
    /** True when this is a side next to the meal at [date]'s [slot], whose status it shares, rather than the meal itself. */
    val side: Boolean = false,
)

/** A lunch or dinner on one date, as a prep names its meals. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PrepSlot(
    /** ISO date. */
    val date: String,
    val slot: MealSlot,
    /**
     * Only in what the app sends (issue 269): in [PrepInput.meals], the batch is eaten here as a side of this many servings
     * (0.25 or 0.5) next to the slot's meal, null as the meal itself; in `POST .../preps/{id}/sides`, the side's servings.
     * Null in every answer. Left out of the JSON when null, so a slot reads and is sent as before it was there: a server
     * from before issue 269 refuses a field it does not know, also in a [SpareMove].
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val side: Double? = null,
)

/**
 * Body of `POST /api/plans/{weekStart}/preps`: [recipeId] made on [madeOn] in a normal box or [vacuum]
 * sealed, and eaten in [meals] (lunches and dinners, the first on [madeOn], none after the day the
 * recipe's keeps allow). A meal of it gets the portion the planner would give that slot, within the profile's range;
 * a side next to a slot's meal, which keeps its main, is of the servings its [PrepSlot.side] says (issue 269), so one
 * batch may be eaten partly as meals and partly as sides.
 * [side] is how apps before issue 269 ask for a batch eaten only as sides: every one of [meals] a side of that many
 * servings. It is null when the meals say it each; both at once is a 400.
 */
@Serializable
data class PrepInput(
    val recipeId: Long,
    val madeOn: String,
    val vacuum: Boolean = false,
    val meals: List<PrepSlot>,
    val side: Double? = null,
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
    /**
     * The lunches and dinners it can still be eaten in, from the date asked about on, earliest first: empty or free to
     * plan; for a [side], those with a meal still to eat that has no side yet.
     */
    val room: List<PrepSlot>,
    /** True when the share is a side, which goes next to the meal it is eaten with. */
    val side: Boolean = false,
    /**
     * The next free lunch or dinner of [room], which the app offers to eat it in with one tap (issue 263): the earliest
     * that is empty, in whichever week; for a [side], the earliest of [room], as each is a meal still to eat without a
     * side. Null when none is free: a meal planned there is only replaced by the user's own choice from [room].
     */
    val proposed: PrepSlot? = null,
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
    /**
     * The fresh ingredients the week's shopping leaves over in small amounts (issue 244), largest first; empty
     * when there are none and from a server before this. Only rests under half of the pack count ([OpenPacks]).
     */
    val rest: List<WeekRest> = emptyList(),
    /**
     * The account's batches still to make (no meal of them eaten) whose days, from the made-on day to the last day, reach
     * into this week, also those with no meal or side in it: what "Add a prepped side…" offers (issue 263). Empty from a
     * server before this.
     */
    val batchesToMake: List<BatchToMake> = emptyList(),
    /**
     * How many dinners were chosen on the planning screen ([WeekPlanning.picks], issue 255) and are not on a day yet:
     * while there are any, the Plan offers "Continue planning". 0 from a server before this.
     */
    val dinnersChosen: Int = 0,
)

/** A batch still to make ([WeekPlan.batchesToMake]): its recipe's id and [title], and the batch. */
@Serializable
data class BatchToMake(val recipeId: Long, val title: String, val prep: MealPrep)

/** What the week leaves of a fresh ingredient: [grams] of it, worth [euros] of its pack's regular price. */
@Serializable
data class WeekRest(val ingredient: String, val grams: Double, val euros: Double)
