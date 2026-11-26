// Frozen from v0.1.12 (dd5ad93e7c8489f1199ba88c609352532d2b298f) by scripts/freeze-api.sh. Do not edit: freeze again after a release.
package dev.tobins.mealplanner.released.api

import kotlinx.serialization.Serializable

/**
 * The planning screen of a week (issue 255), `GET /api/plans/{weekStart}/planning`: the dinners chosen for it so far
 * from the suggestions, not yet put on days, and how the week stands with them. Every route under `/planning` that
 * changes something answers it as it is then.
 */
@Serializable
data class WeekPlanning(
    /** ISO date of the week's first day. */
    val weekStart: String,
    val targets: DailyTargets,
    /**
     * The days (0 is the first) whose dinner is still to choose: those without a dinner that stays when the week is
     * planned, which is one locked, prepped, eaten or skipped, with a prepped side, or with a skipped share in the box
     * under it. A chosen dinner goes on one of them.
     */
    val openDays: List<Int>,
    /** The dinners chosen, in the order they were, each at the portion the planner gives it. */
    val picks: List<DinnerPick>,
    /** The recipes hidden from the suggestions for this week only ("Not this week"), in no order. */
    val notThisWeek: List<HiddenRecipe>,
    /** The week as it stands: its meals that stay and the dinners chosen, with their leftover lunches. */
    val totals: PlanningTotals,
)

/**
 * A dinner chosen for the week, not yet on a day: its numbers at the portion the planner gives a dinner of it, which
 * is the portion it has on the Plan once the week is planned. [cost] with the week's offers, [costWithoutOffers]
 * without; null when unknown. Not [plannable] when its recipe lost its amounts since it was chosen: it has no numbers
 * (0), counts in no total, and "Done" refuses until it is taken out.
 */
@Serializable
data class DinnerPick(
    val recipeId: Long,
    val title: String,
    val portions: Double,
    val kcal: Double,
    val proteinG: Double,
    val fibreG: Double? = null,
    val cost: Double? = null,
    val costWithoutOffers: Double? = null,
    val plannable: Boolean = true,
)

/** A recipe hidden from the week's suggestions ([WeekPlanning.notThisWeek]). */
@Serializable
data class HiddenRecipe(val recipeId: Long, val title: String)

/**
 * The strip of the planning screen: what the week's meals that stay and the dinners chosen come to, each chosen dinner
 * on the day it would get and with its leftover lunch, summed as the Plan sums its meals (a skipped meal counts for
 * nothing). Once the week is planned, the Plan has these same meals with these same numbers, next to those the planner
 * adds. [targetKcal], [targetProteinG] and [targetFibreG] are the share of the daily targets these meals stand for,
 * slot by slot ([MealSlot.share]), so a week of dinners alone is measured against what dinners should give; null fibre
 * without a fibre target. [meals] is how many meals are counted.
 */
@Serializable
data class PlanningTotals(
    val meals: Int,
    val cost: Double? = null,
    val costWithoutOffers: Double? = null,
    val kcal: Double,
    val proteinG: Double,
    val fibreG: Double,
    val targetKcal: Double,
    val targetProteinG: Double,
    val targetFibreG: Double? = null,
)

/**
 * Why a recipe is suggested for the week's dinners. A recipe may be suggested on several grounds; the suggestions are
 * mixed from all of them, so that they are not offers only. Only `GET .../planning/suggestions` sends it, which no app
 * before the planning screen reads; once a release that reads it is installed, a new value is no longer safe (README,
 * "API compatibility": a list of enums has no default).
 */
enum class SuggestionGround {
    /** One of its ingredients is on offer at a store of the account during the week, and that makes it cheaper. */
    ON_OFFER,

    /** It uses something on hand, or the rest of a fresh pack a dinner already chosen opens. */
    USES_WHAT_IS_THERE,

    /** It is rated high, or it was last eaten weeks ago. */
    FAVOURITE,

    /**
     * It was never planned (or only ever skipped), in an earlier week or this one: one to try. Its own ground rather than
     * a favourite's, so that the app can say "Not tried yet".
     */
    NOT_TRIED_YET,
}

/**
 * `GET /api/plans/{weekStart}/planning/suggestions`: the next suggestions, and whether there are [more] after them. With
 * `?q=` it is the account's recipes whose title has every word, best first, whatever their grounds ("Something else").
 */
@Serializable
data class DinnerSuggestions(val suggestions: List<DinnerSuggestion>, val more: Boolean)

/**
 * A recipe suggested for one of the week's dinners, with why ([grounds], at least one unless it was searched for) and the
 * details of each: the [offers] it uses, what it uses that is [onHand] and the [packRests] of chosen dinners it uses up,
 * its [rating] and the [weeksSinceEaten]. [portions] is the portion the planner gives it as a dinner; [kcal], [proteinG]
 * and [fibreG] are of that portion. [costPerServing] is a serving's price with the week's offers, and [savingPerServing]
 * what they take off it; null when unknown.
 */
@Serializable
data class DinnerSuggestion(
    val recipeId: Long,
    val title: String,
    val grounds: List<SuggestionGround>,
    val portions: Double,
    val kcal: Double,
    val proteinG: Double,
    val fibreG: Double,
    val costPerServing: Double? = null,
    val savingPerServing: Double? = null,
    val offers: List<SuggestionOffer> = emptyList(),
    /** The names of the ingredients on hand it uses. */
    val onHand: List<String> = emptyList(),
    val packRests: List<PackRestUse> = emptyList(),
    /** 1 to 5; null when not rated. */
    val rating: Int? = null,
    /** Whole weeks since the last week it was planned in and not skipped; null when it never was. */
    val weeksSinceEaten: Int? = null,
)

/**
 * An offer a suggested recipe uses: the best one for its [ingredient] at the account's stores during the week, as the
 * store words it, and what it takes off a serving of the recipe ([savingPerServing], null when it can't be worked out).
 */
@Serializable
data class SuggestionOffer(
    val ingredient: String,
    val store: Store,
    val title: String,
    val deal: String? = null,
    val savingPerServing: Double? = null,
)

/** The rest of a fresh pack of [ingredient] that the chosen dinner [openedBy] (its title) opens, which a suggestion uses. */
@Serializable
data class PackRestUse(val ingredient: String, val openedBy: String)

/**
 * Body of `POST /api/plans/{weekStart}/meals/{day}/DINNER/move`: puts the dinner on day [toDay] (0 is the week's first)
 * and the dinner there, if any, on its day; their leftover lunches follow them.
 */
@Serializable
data class DinnerMove(val toDay: Int)
