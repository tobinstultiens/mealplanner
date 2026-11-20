package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.DinnerSuggestion
import dev.tobins.mealplanner.api.PlanningTotals
import dev.tobins.mealplanner.api.SuggestionGround
import dev.tobins.mealplanner.api.WeekPlanning
import kotlin.math.roundToLong

/*
 * What the planning screen says (issue 255), apart from how it is laid out: the reasons on a suggestion, its price and
 * numbers, the strip's lines and how many dinners are left to choose.
 */

/**
 * Why a suggestion is there, one line per ground in the server's order: the offers with their stores and deals, what it
 * uses that is on hand or left of a pack a chosen dinner opens, its rating and how long ago it was eaten, or that it was
 * never tried. A ground the server sends without its details still gets its line.
 */
fun DinnerSuggestion.reasons(strings: Strings): List<String> = grounds.map { ground ->
    when (ground) {
        SuggestionGround.ON_OFFER -> strings(
            Msg.REASON_ON_OFFER,
            offers.joinToString(", ") { offer ->
                val ingredient = offer.ingredient.replaceFirstChar { it.lowercase() }
                offer.deal?.takeIf { it.isNotBlank() }?.let { strings(Msg.REASON_OFFER_DEAL, ingredient, offer.store.shortName(), it) }
                    ?: strings(Msg.REASON_OFFER, ingredient, offer.store.shortName())
            }.ifEmpty { strings(Msg.ON_OFFER) },
        )
        SuggestionGround.USES_WHAT_IS_THERE -> strings(
            Msg.REASON_THERE,
            (onHand.map { it.replaceFirstChar { c -> c.lowercase() } } + packRests.map { strings(Msg.REASON_PACK_REST, it.ingredient.replaceFirstChar { c -> c.lowercase() }, it.openedBy) })
                .joinToString(", "),
        )
        SuggestionGround.FAVOURITE -> strings(
            Msg.REASON_FAVOURITE,
            listOfNotNull(
                rating?.let { strings(Msg.REASON_RATED, it) },
                weeksSinceEaten?.let { strings.plural(Plural.REASON_WEEKS, it) },
            ).joinToString(" · "),
        )
        SuggestionGround.NOT_TRIED_YET -> strings(Msg.REASON_NOT_TRIED)
    }
}

/** A serving's price with the week's offers, and what the offers take off it when that is a cent or more; null when unknown. */
fun DinnerSuggestion.priceText(strings: Strings): String? {
    val price = costPerServing ?: return null
    val saved = savingPerServing?.takeIf { it >= 0.005 }
    return if (saved == null) strings(Msg.SUGGESTION_PRICE, formatEuro(price, strings.locale))
    else strings(Msg.SUGGESTION_PRICE_SAVED, formatEuro(price, strings.locale), formatEuro(saved, strings.locale))
}

/** What the dinner gives at the portion the planner gives it: `1.25 servings · 812 kcal · 56 g protein · 6 g fibre`. */
fun DinnerSuggestion.numbersText(strings: Strings): String =
    "${unbrokenParts(servingsText(portions, strings))} · ${macrosText(kcal, proteinG, fibreG, strings)}"

/** How many dinners are still to choose, or why none is. */
fun WeekPlanning.toChooseText(strings: Strings): String {
    val left = openDays.size - picks.size
    return when {
        openDays.isEmpty() -> strings(Msg.PLANNING_NO_OPEN_DAYS)
        left <= 0 -> strings(Msg.PLANNING_ALL_CHOSEN)
        else -> strings.plural(Plural.DINNERS_TO_CHOOSE, left)
    }
}

/**
 * The strip's lines (issue 255): what the meals that stay and the dinners chosen cost, with what the offers save, and
 * their calories, protein and fibre against what those meals should give. Only shown, never a warning. The cost says how
 * many meals it is for, as a chosen dinner counts with its leftover lunch: one dinner of ≈ €3.07 is ≈ €6.14 for 2 meals,
 * which is what the Plan shows once the week is planned. Empty when no meal counts yet.
 */
fun PlanningTotals.lines(strings: Strings): List<String> {
    if (meals == 0) return emptyList()
    val locale = strings.locale
    val costLine = cost?.let { spent ->
        val saved = (costWithoutOffers ?: spent) - spent
        if (saved >= 0.01) strings.plural(Plural.PLANNING_COST_SAVED, meals, formatEuro(spent, locale), formatEuro(saved, locale))
        else strings.plural(Plural.PLANNING_COST, meals, formatEuro(spent, locale))
    }
    fun whole(value: Double) = value.roundToLong()
    val fibreTarget = targetFibreG
    val totals = unbrokenParts(
        if (fibreTarget == null) strings(Msg.PLANNING_TOTALS, whole(kcal), whole(targetKcal), whole(proteinG), whole(targetProteinG))
        else strings(Msg.PLANNING_TOTALS_FIBRE, whole(kcal), whole(targetKcal), whole(proteinG), whole(targetProteinG), whole(fibreG), whole(fibreTarget)),
    )
    return listOfNotNull(costLine, totals)
}

/** Whether the dinners chosen fill every day that is open: the button then says "Done", else "Fill the rest". */
val WeekPlanning.complete: Boolean get() = picks.size >= openDays.size
