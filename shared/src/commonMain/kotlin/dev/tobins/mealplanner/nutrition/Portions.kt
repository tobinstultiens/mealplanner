package dev.tobins.mealplanner.nutrition

import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.Portion
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.units.Dimension
import dev.tobins.mealplanner.units.UnitTable

/*
 * An ingredient's own values and its named portions (issue 351): what it counts by, and what a line in a portion weighs.
 */

/**
 * Salt as a Dutch label gives it, against the sodium [Nutrition] holds as NEVO does. A label's salt is its sodium times
 * 2.5 (EU regulation 1169/2011), so a gram of salt is 400 mg of sodium. The one place the two are turned into each other.
 */
object Salt {
    private const val MG_SODIUM_PER_G_SALT = 400.0

    fun sodiumMg(saltG: Double): Double = saltG * MG_SODIUM_PER_G_SALT

    fun saltG(sodiumMg: Double): Double = sodiumMg / MG_SODIUM_PER_G_SALT
}

/** What an ingredient counts by: [per100] in 100 g or 100 ml of it, as [basis] says. */
data class Values(val basis: FoodBasis, val per100: Nutrition)

/** The values [this] counts by: those of its own label when it has them, else its NEVO food's; null for neither. */
val Ingredient.values: Values?
    get() = label?.let { Values(it.basis, it.per100) } ?: food?.let { Values(it.basis, it.per100) }

/** The portion of [this] that [unit] names, told apart without regard to case; null for none. */
fun Ingredient.portionNamed(unit: String?): Portion? {
    val key = unit?.let(::portionKey) ?: return null
    return portions.firstOrNull { portionKey(it.name) == key }
}

/** True when [unit] names a portion [this] had and removed, and has not been given again. */
fun Ingredient.hadPortion(unit: String?): Boolean {
    val key = unit?.let(::portionKey) ?: return false
    return portionNamed(unit) == null && removedPortions.any { portionKey(it) == key }
}

/** True when [line] is in one of [ingredient]'s portions, or in one it had and removed: a count, but not of pieces. */
fun IngredientLine.inPortionOf(ingredient: Ingredient): Boolean =
    dimension == Dimension.COUNT && (ingredient.portionNamed(unit) != null || ingredient.hadPortion(unit))

/**
 * [line] as what it stands for of [ingredient]: a line in one of its portions as that many grams or millilitres (the
 * portion's), any other line as it is; null for a line in a portion it removed, which is not counted at all. The unit
 * stays the portion's name, so the line still reads as written.
 */
fun measured(line: IngredientLine, ingredient: Ingredient): IngredientLine? {
    if (!line.inPortionOf(ingredient)) return line
    val portion = ingredient.portionNamed(line.unit) ?: return null
    val amount = line.amount ?: return line
    return when {
        portion.grams != null -> line.copy(dimension = Dimension.WEIGHT, baseAmount = amount * portion.grams)
        portion.ml != null -> line.copy(dimension = Dimension.VOLUME, baseAmount = amount * portion.ml)
        else -> null
    }
}

/** A portion's name as names are compared: trimmed, in lower case. */
fun portionKey(name: String): String = name.trim().lowercase()

/**
 * Why [name] cannot be a portion's name, or null when it can: not empty, no `|` or line break (a recipe line in the
 * import format would read differently), and no unit of [units] (`g`, `el`, `piece`), which a line would be read in
 * instead. Worded as the server answers it.
 */
fun portionNameProblem(name: String, units: UnitTable = UnitTable.Default): String? = when {
    name.isBlank() -> "A portion needs a name"
    name.any { it == '|' || it == '\n' || it == '\r' || it in "\u000B\u000C\u0085  " } -> "A portion's name cannot hold | or a line break: '$name'"
    units.resolve(name) != null -> "'${name.trim()}' is a unit already, not a portion's name"
    else -> null
}
