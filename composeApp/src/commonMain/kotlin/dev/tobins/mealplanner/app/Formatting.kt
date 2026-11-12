package dev.tobins.mealplanner.app

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
            // An unknown unit is the ingredient itself ("onion"), so the item is a qualifier.
            baseAmount == null && unit != null && unit != "clove" && unit != "piece" -> "$quantity, $item"
            else -> "$quantity $item"
        }
    }
