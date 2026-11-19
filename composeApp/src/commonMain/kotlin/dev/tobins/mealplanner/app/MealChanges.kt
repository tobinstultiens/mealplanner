package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.MealLine
import dev.tobins.mealplanner.api.MealLineChange
import dev.tobins.mealplanner.api.MealLines
import kotlin.math.abs

/*
 * What the Adjust screen sends and shows (issue 226). Every edit sends the meal's whole list of changes,
 * as `PUT .../lines` replaces them; an undo puts back only what its own edit touched ([undoing]).
 */

/** The most of one ingredient a meal can have, as the server takes it: more is a typing mistake. */
const val MAX_MEAL_GRAMS = 5000.0

/** The changes this meal has: every line that differs from its recipe, as `PUT .../lines` takes them back. */
fun MealLines.changes(): List<MealLineChange> = lines.mapNotNull { line ->
    val id = line.ingredientId ?: return@mapNotNull null
    val grams = line.grams ?: return@mapNotNull null
    MealLineChange(id, grams).takeIf { line.changed }
}

/** The changes with [ingredientId] eaten in [grams]; the server drops one that is what the recipe has. */
fun MealLines.withAmount(ingredientId: Long, grams: Double): List<MealLineChange> =
    changes().filter { it.ingredientId != ingredientId } + MealLineChange(ingredientId, grams)

/** The changes with [line] as its recipe has it again, or for an added ingredient without it. */
fun MealLines.asInRecipe(line: MealLine): List<MealLineChange> = changes().filter { it.ingredientId != line.ingredientId }

/** The changes without [line]: left out of the recipe (0 g), or for an added ingredient not added after all. */
fun MealLines.leavingOut(line: MealLine): List<MealLineChange> {
    val id = line.ingredientId ?: return changes()
    return if (line.fromRecipe) withAmount(id, 0.0) else asInRecipe(line)
}

/** The ingredients whose change differs between [old] and [new]: what one edit touched. */
fun touched(old: List<MealLineChange>, new: List<MealLineChange>): Set<Long> {
    val before = old.associate { it.ingredientId to it.grams }
    val after = new.associate { it.ingredientId to it.grams }
    return (before.keys + after.keys).filterTo(HashSet()) { before[it] != after[it] }
}

/** What [ingredients] had before an edit, to take that edit back on its own: each one's change, null where it had none. */
fun MealLines.before(ingredients: Set<Long>): Map<Long, MealLineChange?> =
    ingredients.associateWith { id -> changes().firstOrNull { it.ingredientId == id } }

/**
 * The changes with one edit taken back: the ingredients it touched as they were before it ([previous]),
 * every other line as it is now, so an edit made since stays.
 */
fun MealLines.undoing(previous: Map<Long, MealLineChange?>): List<MealLineChange> =
    changes().filter { it.ingredientId !in previous } + previous.values.filterNotNull()

/** True for a line of the recipe that was left out of this meal. */
val MealLine.leftOut: Boolean get() = fromRecipe && changed && grams == 0.0

/** `1.5 pieces · 90 g` for an ingredient counted in pieces, `150 g` for one that is weighed; null when not known. */
fun amountText(grams: Double?, gramsPerPiece: Double?, strings: Strings): String? {
    grams ?: return null
    val weight = formatGrams(grams, strings.locale)
    if (gramsPerPiece == null || gramsPerPiece <= 0.0) return weight
    val pieces = grams / gramsPerPiece
    return "${strings.plural(Plural.PIECES_AMOUNT, if (abs(pieces - 1.0) < 1e-9) 1 else 2, formatAmount(pieces, strings.locale))} · $weight"
}

/** What a line says under its name: its amount, and how it differs from the recipe. */
fun MealLine.note(strings: Strings): String {
    val amount = amountText(grams, gramsPerPiece, strings) ?: strings(Msg.ADJUST_AMOUNT_UNKNOWN)
    val recipe = amountText(recipeGrams, gramsPerPiece, strings)?.let { strings(Msg.ADJUST_WAS, it) }
    return when {
        ingredientId == null -> "$amount\n${strings(Msg.ADJUST_NOT_LINKED)}"
        leftOut -> listOfNotNull(strings(Msg.ADJUST_LEFT_OUT), recipe).joinToString(" · ")
        !fromRecipe -> "${strings(Msg.ADJUST_ADDED)} · $amount"
        changed -> listOfNotNull(amount, recipe).joinToString(" · ")
        else -> amount
    }
}

/**
 * The grams [text] says, typed in pieces of [gramsPerPiece] g when [inPieces] (halves and the like
 * allowed), else in grams; null when it is no amount above 0 and up to [MAX_MEAL_GRAMS].
 */
fun gramsEntered(text: String, inPieces: Boolean, gramsPerPiece: Double?): Double? {
    val value = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() } ?: return null
    val grams = if (inPieces && gramsPerPiece != null) value * gramsPerPiece else value
    return grams.takeIf { it <= MAX_MEAL_GRAMS }
}

/** What the amount field starts with: [grams] in pieces or in grams as [inPieces] says, empty when there is none. */
fun amountFieldText(grams: Double?, inPieces: Boolean, gramsPerPiece: Double?): String {
    grams ?: return ""
    // Read back by [gramsEntered], so written the same in every language.
    return formatAmount(if (inPieces && gramsPerPiece != null) grams / gramsPerPiece else grams, FormatLocale.English)
}

/**
 * The catalogue's ingredients to add, for [query]: those whose name has it, names that start with it
 * first, then by name. What the meal has already is left out: it is changed on its own line.
 */
fun ingredientsMatching(all: List<Ingredient>, query: String, inMeal: Set<Long>): List<Ingredient> {
    val words = query.trim().lowercase()
    return all.filter { it.id !in inMeal && (words.isEmpty() || words in it.name.lowercase()) }
        .sortedWith(compareBy({ !it.name.lowercase().startsWith(words) }, { it.name.lowercase() }))
}
