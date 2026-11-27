package dev.tobins.mealplanner.server.ingredients.products

import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.PackSize

/*
 * A label's values and a pack's content as the sources write them in text (issue 354): `12 g`, `<0,5 g`,
 * `1618.0 kJ (388.0 kcal)`, `100.0 Gram`, `6 x 250 ml`. What does not read is null, never a guess.
 */

/** A number with a decimal point or comma. */
private const val NUMBER = """(\d+(?:[.,]\d+)?)"""

private val GRAMS = Regex("""^[<≤]?\s*$NUMBER\s*(g|gr|gram|mg)$""", RegexOption.IGNORE_CASE)

private val KCAL = Regex("""[<≤]?\s*$NUMBER\s*kcal\b""", RegexOption.IGNORE_CASE)

private val QUANTITY = Regex("""^(?:(\d+)\s*[x×]\s*)?$NUMBER\s*([a-z]+)$""", RegexOption.IGNORE_CASE)

private fun number(text: String): Double? = text.replace(',', '.').toDoubleOrNull()

/** Whether this is the digits 0 to 9 and nothing else (no other script's digits): what may go into a source's address. */
fun String.isDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }

/**
 * Grams of a nutrient as a label gives them: `12 g`, `0.68 g`, `450 mg`. "Less than" (`<0,5 g`) is read as the number
 * itself, the most the product can have: the label says no more. Null for anything else, such as a value without a unit.
 */
fun labelGrams(text: String): Double? {
    val match = GRAMS.matchEntire(text.trim()) ?: return null
    val amount = number(match.groupValues[1]) ?: return null
    return if (match.groupValues[2].equals("mg", ignoreCase = true)) amount / 1000 else amount
}

/** The kcal of an energy as a label gives it (`1618.0 kJ (388.0 kcal)`, `388 kcal`); null without kcal: kJ alone is not converted. */
fun labelKcal(text: String): Double? = KCAL.find(text)?.groupValues?.get(1)?.let(::number)

/** A quantity as text: an amount in grams or millilitres ([ml]), of [count] items when it says `6 x 250 ml`. */
data class Quantity(val amount: Double, val ml: Boolean, val count: Int? = null)

/** Grams or millilitres in one of each unit a source writes. */
private val UNITS = mapOf(
    "g" to (1.0 to false), "gr" to (1.0 to false), "gram" to (1.0 to false), "kg" to (1000.0 to false), "kilogram" to (1000.0 to false),
    "ml" to (1.0 to true), "milliliter" to (1.0 to true), "millilitre" to (1.0 to true), "cl" to (10.0 to true), "centiliter" to (10.0 to true),
    "dl" to (100.0 to true), "deciliter" to (100.0 to true), "l" to (1000.0 to true), "liter" to (1000.0 to true), "litre" to (1000.0 to true),
)

/** `100.0 Gram`, `1.0 Liter`, `500ml`, `6 x 250 ml`, `1,5 l`; null for a count (`4 stuks`) or anything else. */
fun quantity(text: String): Quantity? {
    val match = QUANTITY.matchEntire(text.trim()) ?: return null
    val (factor, ml) = UNITS[match.groupValues[3].lowercase()] ?: return null
    val amount = number(match.groupValues[2])?.takeIf { it > 0 } ?: return null
    return Quantity(amount * factor, ml, match.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull())
}

/** What a table's basis (`100.0 Gram`, `100.0 Milliliter`) says; null for any other, such as a portion's `60.0 Gram`. */
fun basisOf(text: String): FoodBasis? {
    val quantity = quantity(text)?.takeIf { it.count == null && it.amount == 100.0 } ?: return null
    return if (quantity.ml) FoodBasis.PER_100_ML else FoodBasis.PER_100_G
}

/**
 * The pack of the first of [texts] that reads: of a multi-pack (`6 x 250 ml`) its single item, which is what a portion
 * is, so one of those is taken before a whole pack; null when none reads.
 */
fun packOf(texts: List<String>): PackSize? {
    val read = texts.mapNotNull(::quantity)
    val quantity = read.firstOrNull { it.count != null } ?: read.firstOrNull() ?: return null
    return if (quantity.ml) PackSize(ml = quantity.amount, single = quantity.count != null) else PackSize(grams = quantity.amount, single = quantity.count != null)
}
