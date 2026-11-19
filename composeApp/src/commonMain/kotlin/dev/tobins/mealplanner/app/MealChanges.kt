package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.AddOn
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.MealLine
import dev.tobins.mealplanner.api.MealLineChange
import dev.tobins.mealplanner.api.MealLines
import kotlin.math.abs
import kotlin.math.round

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

/**
 * `1.5 pieces · 90 g` for an ingredient counted in pieces, `150 g` for one that is weighed or whose amount is no
 * sensible number of pieces ([piecesText]); null when not known.
 */
fun amountText(grams: Double?, gramsPerPiece: Double?, strings: Strings): String? {
    grams ?: return null
    val weight = formatGrams(grams, strings.locale)
    return piecesText(grams, gramsPerPiece, strings)?.let { "$it · $weight" } ?: weight
}

/**
 * `1 piece`, `1.5 pieces`: [grams] in pieces of [gramsPerPiece] g. Null for an ingredient that is weighed, and for
 * an amount that is no sensible count ([inPieces]): "0.09 pieces" of a cucumber says less than "30 g".
 */
fun piecesText(grams: Double, gramsPerPiece: Double?, strings: Strings): String? {
    if (!inPieces(grams, gramsPerPiece)) return null
    // The quarter it was judged to be, so a count rounded on its way through grams reads as that count.
    val pieces = round(grams / gramsPerPiece!! * 4) / 4
    return strings.plural(Plural.PIECES_AMOUNT, if (pieces == 1.0) 1 else 2, formatAmount(pieces, strings.locale))
}

/**
 * Whether [grams] reads best in pieces of [gramsPerPiece] g: the catalogue knows a piece's weight and the amount is
 * whole pieces, halves or quarters (to a hundredth of a quarter), at least a quarter. Any other amount is said and
 * typed in grams.
 */
fun inPieces(grams: Double, gramsPerPiece: Double?): Boolean {
    if (gramsPerPiece == null || gramsPerPiece <= 0.0) return false
    val quarters = grams / gramsPerPiece * 4
    return quarters >= 1.0 - QUARTER_SLACK && abs(quarters - round(quarters)) < QUARTER_SLACK
}

/** How far from a whole quarter a count may be and still be one: rounding of the grams stored, not a real amount. */
private const val QUARTER_SLACK = 0.01

/**
 * A quick add-on as this meal offers it: [addOn]'s amount goes on top of the [has] grams the meal has of it already
 * (0 when none, or when it is a recipe line left out), so a topping the recipe has too, or a second egg, adds to it.
 */
data class QuickAddOn(val addOn: AddOn, val has: Double) {
    /** What the meal then has of it in all, as `PUT .../lines` takes it. */
    val total: Double get() = has + addOn.grams

    /** True when the meal has some already, so the chip says it is extra. */
    val extra: Boolean get() = has > 0.0
}

/**
 * The quick add-ons to offer for this meal, in the server's order (the recipe's toppings, then what its meals had
 * added before), each adding to what the meal has of it ([QuickAddOn]). Left out: one whose line has an amount
 * nobody can weigh ("1 bunch"), which there is nothing to add to, and one that would make more than a meal can
 * hold ([MAX_MEAL_GRAMS]).
 */
fun MealLines.quickAddOns(addOns: List<AddOn>): List<QuickAddOn> = addOns.mapNotNull { addOn ->
    val line = lines.firstOrNull { it.ingredientId == addOn.ingredientId }
    val has = if (line == null) 0.0 else line.grams ?: return@mapNotNull null
    QuickAddOn(addOn, has).takeIf { it.total <= MAX_MEAL_GRAMS }
}

/** The changes with [quick] added to the meal. */
fun MealLines.adding(quick: QuickAddOn): List<MealLineChange> = withAmount(quick.addOn.ingredientId, quick.total)

/**
 * `Egg · 1 piece`, `Feta · 40 g`, or `+ 30 g Cucumber` when the meal has some of it already: a quick add-on as its
 * chip says it, in pieces where that is a sensible count.
 */
fun QuickAddOn.chipText(strings: Strings): String {
    val amount = piecesText(addOn.grams, addOn.gramsPerPiece, strings) ?: formatGrams(addOn.grams, strings.locale)
    return if (extra) "+ $amount ${addOn.name}" else "${addOn.name} · $amount"
}

/**
 * What the warning card says, one line per ingredient: `Beef: 93 g short for Bowl, lunch Sun 22 Nov; Bowl, dinner
 * Sun 22 Nov`. The amount is the meals' shortfalls added up and rounded once, as the shopping list rounds the
 * same amount it asks for again, so the two agree.
 */
fun MealLines.shortfallTexts(strings: Strings): List<String> =
    shortfalls.groupBy { it.ingredientId }.values.map { short ->
        val meals = short.joinToString("; ") { strings(Msg.ADJUST_SHORT_MEAL, it.title, strings.slot(it.slot), dayLabel(plusDays(weekStart, it.day), strings.locale)) }
        strings(Msg.ADJUST_SHORT, short.first().name, formatGrams(short.sumOf { it.grams }, strings.locale), meals)
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
 * first, then by name. Those in [left] are left out, as what the meal (or the recipe's toppings) has
 * already is changed on its own line.
 */
fun ingredientsMatching(all: List<Ingredient>, query: String, left: Set<Long>): List<Ingredient> {
    val words = query.trim().lowercase()
    return all.filter { it.id !in left && (words.isEmpty() || words in it.name.lowercase()) }
        .sortedWith(compareBy({ !it.name.lowercase().startsWith(words) }, { it.name.lowercase() }))
}
