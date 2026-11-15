package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.RecipeNutrition
import dev.tobins.mealplanner.api.RecipeSummary
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

/** `512 kcal · 38 g protein`, prefixed with `~` when a density was assumed. */
fun RecipeNutrition.headline(strings: Strings): String {
    val kcal = perServing.kcal?.roundToLong() ?: 0
    val protein = perServing.proteinG?.roundToLong() ?: 0
    return (if (approximate) "~" else "") + strings(Msg.KCAL_PROTEIN, kcal, protein)
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

/** `AH: voor 3.49`, `Lidl: €4.29 (was €5.49)`: the deal in a few words. */
fun Offer.shortText(strings: Strings): String {
    val locale = strings.locale
    val price = price?.let { p -> formatEuro(p, locale) + (originalPrice?.takeIf { it > p }?.let { " " + strings(Msg.OFFER_WAS, formatEuro(it, locale)) } ?: "") }
    // A deal part that only restates the price ("Voor 1,09") is dropped when the price is shown.
    val dealParts = deal?.split(" · ")?.map { it.trim() }?.filter { it.isNotEmpty() && !(price != null && singlePriceDeal(it)) }.orEmpty()
    val what = (dealParts.map { it.lowercase().replaceFirstChar { c -> c.uppercase() } } + listOfNotNull(price)).joinToString(", ").ifEmpty { null }
    return "${store.label.substringBefore(' ').let { if (it == "Albert") "AH" else it }}: ${what ?: strings(Msg.ON_OFFER)}"
}

/** True for deals that only restate the price, such as "Voor 3.49". */
private fun singlePriceDeal(deal: String): Boolean = Regex("(?i)^voor\\s+\\d+[.,]\\d{2}$").matches(deal.trim())

/** `1 serving`, `1.75 servings`. */
fun servingsText(portions: Double, strings: Strings): String =
    strings.plural(Plural.SERVINGS_AMOUNT, if (portions == 1.0) 1 else 2, formatAmount(portions, strings.locale))

/** What a planned meal gives, e.g. `1.75 servings · 461 kcal · 46 g protein`; its cost is shown apart. */
fun PlannedMeal.macrosText(strings: Strings): String =
    "${servingsText(portions, strings)} · ${strings(Msg.KCAL_PROTEIN, kcal.roundToLong(), proteinG.roundToLong())}"

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
        nutrition = nutrition?.takeIf { plannable && it.complete }?.headline(strings),
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
