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
fun formatAmount(value: Double, locale: FormatLocale = FormatLocale.device): String {
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
fun IngredientLine.displayText(locale: FormatLocale = FormatLocale.device): String =
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
fun RecipeNutrition.headline(): String {
    val kcal = perServing.kcal?.roundToLong() ?: 0
    val protein = perServing.proteinG?.roundToLong() ?: 0
    return "${if (approximate) "~" else ""}$kcal kcal · $protein g protein"
}

/** Whole grams (`28 g`); below 10 g one decimal (`4.6 g`). A missing value shows as `0 g`. */
fun formatGrams(grams: Double?, locale: FormatLocale = FormatLocale.device): String {
    val value = grams ?: 0.0
    return if (value < 10) "${formatAmount((value * 10).roundToLong() / 10.0, locale)} g" else "${value.roundToLong()} g"
}

/** `€3.49` (`€3,49` with a decimal comma); prices are shown with two decimals. */
fun formatEuro(amount: Double, locale: FormatLocale = FormatLocale.device): String {
    val cents = (amount * 100).roundToLong()
    return "€${cents / 100}${locale.decimalSeparator}${(cents % 100).toString().padStart(2, '0')}"
}

/** `AH: voor 3.49`, `Lidl: €4.29 (was €5.49)`: the deal in a few words. */
fun Offer.shortText(locale: FormatLocale = FormatLocale.device): String {
    val price = price?.let { p -> formatEuro(p, locale) + (originalPrice?.takeIf { it > p }?.let { " (was ${formatEuro(it, locale)})" } ?: "") }
    // A deal part that only restates the price ("Voor 1,09") is dropped when the price is shown.
    val dealParts = deal?.split(" · ")?.map { it.trim() }?.filter { it.isNotEmpty() && !(price != null && singlePriceDeal(it)) }.orEmpty()
    val what = (dealParts.map { it.lowercase().replaceFirstChar { c -> c.uppercase() } } + listOfNotNull(price)).joinToString(", ").ifEmpty { null }
    return "${store.label.substringBefore(' ').let { if (it == "Albert") "AH" else it }}: ${what ?: "on offer"}"
}

/** True for deals that only restate the price, such as "Voor 3.49". */
private fun singlePriceDeal(deal: String): Boolean = Regex("(?i)^voor\\s+\\d+[.,]\\d{2}$").matches(deal.trim())

/** `1 serving`, `1.75 servings`. */
fun servingsText(portions: Double, locale: FormatLocale = FormatLocale.device): String =
    "${formatAmount(portions, locale)} ${if (portions == 1.0) "serving" else "servings"}"

/** What a planned meal gives, e.g. `1.75 servings · 461 kcal · 46 g protein`; its cost is shown apart. */
fun PlannedMeal.macrosText(locale: FormatLocale = FormatLocale.device): String =
    "${servingsText(portions, locale)} · ${kcal.roundToLong()} kcal · ${proteinG.roundToLong()} g protein"

/**
 * The parts of a recipe list row, kept apart so each has a fixed place: [nutrition] left and
 * [cost] right on one line, [details] (servings, rating, tags) below, and a [warning] badge
 * when the recipe can't be fully counted.
 */
class RecipeRowText(val nutrition: String?, val cost: String?, val details: String?, val warning: String?)

/** Splits a recipe's facts into a [RecipeRowText]. */
fun RecipeSummary.rowText(locale: FormatLocale = FormatLocale.device): RecipeRowText {
    val nutrition = nutrition
    return RecipeRowText(
        nutrition = nutrition?.takeIf { plannable && it.complete }?.headline(),
        cost = cost?.let { "≈ ${formatEuro(it.perServingWithOffers, locale)}" },
        details = listOfNotNull(
            servings?.let { if (it == 1) "1 serving" else "$it servings" },
            rating?.let { "★".repeat(it) },
            tags.takeIf { it.isNotEmpty() }?.joinToString(" · "),
        ).joinToString(" · ").ifEmpty { null },
        warning = when {
            !plannable || nutrition == null -> "No amounts yet"
            !nutrition.complete -> nutrition.uncounted.size.let { if (it == 1) "1 ingredient to link" else "$it ingredients to link" }
            nutrition.unquantified.isNotEmpty() -> "Missing amounts"
            else -> null
        },
    )
}
