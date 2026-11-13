package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.RecipeNutrition
import dev.tobins.mealplanner.recipes.IngredientLine
import kotlin.math.roundToLong

/** `400.0` -> `400`, `1.5` -> `1.5`, `0.3333` -> `0.33`. */
fun formatAmount(value: Double): String {
    val hundredths = (value * 100).roundToLong()
    val whole = hundredths / 100
    val fraction = (hundredths % 100).toInt()
    return when {
        fraction == 0 -> whole.toString()
        fraction % 10 == 0 -> "$whole.${fraction / 10}"
        else -> "$whole.${fraction.toString().padStart(2, '0')}"
    }
}

/** One ingredient as a reader expects it: `400 g beef`, `1 onion, large`, `salt to taste`. */
fun IngredientLine.displayText(): String =
    listOfNotNull(amount?.let(::formatAmount), unit).joinToString(" ").let { quantity ->
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
fun formatGrams(grams: Double?): String {
    val value = grams ?: 0.0
    return if (value < 10) "${formatAmount((value * 10).roundToLong() / 10.0)} g" else "${value.roundToLong()} g"
}

/** `€3.49`; prices are shown with two decimals. */
fun formatEuro(amount: Double): String {
    val cents = (amount * 100).roundToLong()
    return "€${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}

/** `AH: voor 3.49`, `Lidl: €4.29 (was €5.49)`: the deal in a few words. */
fun Offer.shortText(): String {
    val price = price?.let { p -> formatEuro(p) + (originalPrice?.takeIf { it > p }?.let { " (was ${formatEuro(it)})" } ?: "") }
    // A deal part that only restates the price ("Voor 1,09") is dropped when the price is shown.
    val dealParts = deal?.split(" · ")?.map { it.trim() }?.filter { it.isNotEmpty() && !(price != null && singlePriceDeal(it)) }.orEmpty()
    val what = (dealParts.map { it.lowercase().replaceFirstChar { c -> c.uppercase() } } + listOfNotNull(price)).joinToString(", ").ifEmpty { null }
    return "${store.label.substringBefore(' ').let { if (it == "Albert") "AH" else it }}: ${what ?: "on offer"}"
}

/** True for deals that only restate the price, such as "Voor 3.49". */
private fun singlePriceDeal(deal: String): Boolean = Regex("(?i)^voor\\s+\\d+[.,]\\d{2}$").matches(deal.trim())
