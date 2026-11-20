package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.RecipeNutrition
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.WeekRest
import dev.tobins.mealplanner.recipes.IngredientLine
import kotlin.math.roundToLong

/**
 * `400.0` -> `400`, `1.5` -> `1.5` (`1,5` with a decimal comma), `0.3333` -> `0.33`. Text that
 * is edited and read back by the app uses [FormatLocale.English], so it parses the same everywhere.
 */
fun formatAmount(value: Double, locale: FormatLocale): String {
    val hundredths = (value * 100).roundToLong()
    val whole = hundredths / 100
    val fraction = (hundredths % 100).toInt()
    return when {
        fraction == 0 -> whole.toString()
        fraction % 10 == 0 -> "$whole${locale.decimalSeparator}${fraction / 10}"
        else -> "$whole${locale.decimalSeparator}${fraction.toString().padStart(2, '0')}"
    }
}

/** One ingredient as a reader expects it: `400 g beef`, `1 onion, large`, `salt to taste`. */
fun IngredientLine.displayText(locale: FormatLocale): String =
    listOfNotNull(amount?.let { formatAmount(it, locale) }, unit).joinToString(" ").let { quantity ->
        when {
            quantity.isEmpty() -> item
            item.isEmpty() -> quantity
            // The unit is the ingredient itself ("onion"), so the item is a qualifier.
            unitIsTheIngredient -> "$quantity, $item"
            else -> "$quantity $item"
        }
    }

/**
 * This line for [factor] times its amounts, rounded so that it can be measured: grams and millilitres
 * to whole ones (one decimal below 10, so a pinch does not vanish), spoons, cups and counts
 * (cloves, pieces, "1 onion") to a quarter, never to nothing. Other units (kg, l, oz, lb) are
 * not rounded further than [formatAmount] shows them. A line without an amount stays as it is.
 */
fun IngredientLine.scaled(factor: Double): IngredientLine {
    val value = amount ?: return this
    val scaledValue = value * factor
    val rounded = when {
        unit == "g" || unit == "ml" ->
            if (scaledValue < 10) (scaledValue * 10).roundToLong() / 10.0 else scaledValue.roundToLong().toDouble()
        unit == "kg" || unit == "l" || unit == "oz" || unit == "lb" -> scaledValue
        else -> maxOf((scaledValue * 4).roundToLong() / 4.0, 0.25)
    }
    return copy(amount = rounded, baseAmount = baseAmount?.let { it * rounded / value })
}

/**
 * Keeps each part of a line of `·`-separated numbers together (non-breaking spaces), so that a narrow
 * screen breaks the line between parts and never between a number and its unit (`24` / `g protein`).
 */
fun unbrokenParts(text: String): String = text.split(" · ").joinToString(" · ") { it.replace(' ', ' ') }

/** `512 kcal · 38 g protein · 9 g fibre` in whole numbers ([unbrokenParts]); without the fibre when it is not known (null). */
fun macrosText(kcal: Double, proteinG: Double, fibreG: Double?, strings: Strings): String = unbrokenParts(
    if (fibreG == null) strings(Msg.KCAL_PROTEIN, kcal.roundToLong(), proteinG.roundToLong())
    else strings(Msg.KCAL_PROTEIN_FIBRE, kcal.roundToLong(), proteinG.roundToLong(), fibreG.roundToLong()),
)

/**
 * `512 kcal · 38 g protein`, prefixed with `~` when a density was assumed; [withFibre] adds the fibre,
 * as recipe rows show it. A recipe's page shows its fibre with the other nutrients instead.
 */
fun RecipeNutrition.headline(strings: Strings, withFibre: Boolean = false): String {
    val fibre = perServing.fibreG.takeIf { withFibre }
    return (if (approximate) "~" else "") + macrosText(perServing.kcal ?: 0.0, perServing.proteinG ?: 0.0, fibre, strings)
}

/** Whole grams (`28 g`); below 10 g one decimal (`4.6 g`). A missing value shows as `0 g`. */
fun formatGrams(grams: Double?, locale: FormatLocale): String {
    val value = grams ?: 0.0
    return if (value < 10) "${formatAmount((value * 10).roundToLong() / 10.0, locale)} g" else "${value.roundToLong()} g"
}

/** `€3.49` (`€3,49` with a decimal comma); prices are shown with two decimals. */
fun formatEuro(amount: Double, locale: FormatLocale): String {
    val cents = (amount * 100).roundToLong()
    return "€${cents / 100}${locale.decimalSeparator}${(cents % 100).toString().padStart(2, '0')}"
}

/** How many leftovers the line under the week's cost names before it says "and 2 more". */
const val REST_NAMED = 3

/**
 * What the week leaves of fresh ingredients, as the Plan says it under the week's cost (issue 244): the euros of all
 * of it and the [REST_NAMED] largest named, `About €2.40 of fresh ingredients left over: 100 g crème fraîche, 60 g
 * coriander and 2 more`. Null when there is none.
 */
fun restLine(rest: List<WeekRest>, strings: Strings): String? {
    if (rest.isEmpty()) return null
    val named = rest.take(REST_NAMED).joinToString(", ") { formatGrams(it.grams, strings.locale) + " " + it.ingredient.replaceFirstChar { c -> c.lowercase() } }
    val list = if (rest.size > REST_NAMED) strings(Msg.WEEK_REST_MORE, named, rest.size - REST_NAMED) else named
    return strings(Msg.WEEK_REST, formatEuro(rest.sumOf { it.euros }, strings.locale), list)
}

/** `AH: voor 3.49`, `Lidl: €4.29 (was €5.49)`: the deal in a few words. */
fun Offer.shortText(strings: Strings): String {
    val locale = strings.locale
    val price = price?.let { p -> formatEuro(p, locale) + (originalPrice?.takeIf { it > p }?.let { " " + strings(Msg.OFFER_WAS, formatEuro(it, locale)) } ?: "") }
    // A deal part that only restates the price ("Voor 1,09") is dropped when the price is shown.
    val dealParts = deal?.split(" · ")?.map { it.trim() }?.filter { it.isNotEmpty() && !(price != null && singlePriceDeal(it)) }.orEmpty()
    val what = (dealParts.map { it.lowercase().replaceFirstChar { c -> c.uppercase() } } + listOfNotNull(price)).joinToString(", ").ifEmpty { null }
    return "${store.shortName()}: ${what ?: strings(Msg.ON_OFFER)}"
}

/** True for deals that only restate the price, such as "Voor 3.49". */
private fun singlePriceDeal(deal: String): Boolean = Regex("(?i)^voor\\s+\\d+[.,]\\d{2}$").matches(deal.trim())

/** `1 serving`, `1.75 servings`. */
fun servingsText(portions: Double, strings: Strings): String =
    strings.plural(Plural.SERVINGS_AMOUNT, if (portions == 1.0) 1 else 2, formatAmount(portions, strings.locale))

/**
 * What a planned meal gives, e.g. `1.75 servings · 461 kcal · 46 g protein · 8 g fibre`; its cost is
 * shown apart. From a server before fibre counted, without the fibre.
 */
fun PlannedMeal.macrosText(strings: Strings): String =
    "${unbrokenParts(servingsText(portions, strings))} · ${macrosText(kcal, proteinG, fibreG, strings)}"

/**
 * What a planned meal tells the recipe opened from it, in a sentence: how much to cook now and for
 * whom, or that there is nothing to cook (skipped, or the leftovers of yesterday's dinner). A prepped batch's meal
 * on its made-on day cooks the batch for the later meals, also when itself skipped; the later meals were made then.
 */
fun MealToCook.sentence(strings: Strings): String {
    val locale = strings.locale
    return when {
        prepMadeOn != null && cookPortions > 0.0 -> strings(
            Msg.COOK_PREP, servingsText(cookPortions, strings),
            formatAmount(if (skipped) 0.0 else portions, locale), formatAmount(cookPortions - if (skipped) 0.0 else portions, locale),
        )
        prepMadeOn != null && !skipped -> strings(Msg.NOTHING_TO_COOK_PREPPED, dayLabel(prepMadeOn, locale))
        skipped -> strings(Msg.NOTHING_TO_COOK_SKIPPED)
        cookPortions <= 0.0 -> strings(Msg.NOTHING_TO_COOK_LEFTOVERS)
        cookPortions > portions + 1e-9 -> strings(
            Msg.COOK_WITH_LEFTOVERS, servingsText(cookPortions, strings),
            formatAmount(portions, locale), formatAmount(cookPortions - portions, locale),
        )
        else -> strings(Msg.COOK_SERVINGS, servingsText(cookPortions, strings))
    }
}

/**
 * The parts of a recipe list row, kept apart so each has a fixed place: [nutrition] left and
 * [cost] right on one line, [details] (servings, rating, tags) below, and a [warning] badge
 * when the recipe can't be fully counted.
 */
class RecipeRowText(val nutrition: String?, val cost: String?, val details: String?, val warning: String?)

/** Splits a recipe's facts into a [RecipeRowText]. */
fun RecipeSummary.rowText(strings: Strings): RecipeRowText {
    val locale = strings.locale
    val nutrition = nutrition
    return RecipeRowText(
        nutrition = nutrition?.takeIf { plannable && it.complete }?.headline(strings, withFibre = true),
        cost = cost?.let { "≈ ${formatEuro(it.perServingWithOffers, locale)}" },
        details = listOfNotNull(
            servings?.let { strings.plural(Plural.SERVINGS, it) },
            rating?.let { "★".repeat(it) },
            tags.takeIf { it.isNotEmpty() }?.joinToString(" · "),
        ).joinToString(" · ").ifEmpty { null },
        warning = when {
            !plannable || nutrition == null -> strings(Msg.NO_AMOUNTS_YET)
            !nutrition.complete -> strings.plural(Plural.INGREDIENTS_TO_LINK, nutrition.uncounted.size)
            nutrition.unquantified.isNotEmpty() -> strings(Msg.MISSING_AMOUNTS)
            else -> null
        },
    )
}
