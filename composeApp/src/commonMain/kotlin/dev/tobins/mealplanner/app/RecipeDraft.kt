package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeLineInput
import dev.tobins.mealplanner.api.RecipeNutrition
import dev.tobins.mealplanner.nutrition.NutritionCalculator
import dev.tobins.mealplanner.recipes.HugoRecipeParser
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.recipes.parseAmount
import dev.tobins.mealplanner.recipes.toIngredientLine
import dev.tobins.mealplanner.units.Dimension
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

/*
 * The recipe editor's model (issue 342): a recipe as rows of picked ingredients, what it saves and what it counts.
 * Kept apart from the screen so that it is tested without drawing it.
 */

/**
 * One ingredient line being written: the [amount] as typed, a [unit] (a key of the unit table, such as `g`, or the
 * word a recipe of the cooking site has), the [text] the recipe shows, the [group] heading it sits under, and the
 * catalogue ingredient it means ([ingredientId]): picked, found for a pasted line, or for a line as it was loaded the one
 * the server says it means. [key] tells rows apart while they move and change.
 *
 * A loaded line keeps what it was ([original], with its own ingredient, and [originalMeaning], the one it meant): while
 * the row shows it as it was, it is saved exactly so ([input]), so a recipe opened and saved without a change is the
 * same recipe, and a line that was looked up by its text stays so. A row that was changed is saved with the ingredient
 * the editor shows for it, which is then its own.
 */
@Serializable
data class DraftRow(
    val key: Long,
    val amount: String,
    val unit: String?,
    val text: String,
    val ingredientId: Long?,
    val group: String?,
    val original: RecipeLineInput? = null,
    val originalMeaning: Long? = null,
) {
    /** The amount: the one loaded while its text is as it was shown (so a rounded number changes nothing), else as typed. */
    val amountValue: Double?
        get() = original?.takeIf { amount == amountText(it.amount) }?.amount ?: readAmount(amount)

    /** False while the amount field holds something that is no amount above 0; empty is none. */
    val amountValid: Boolean get() = amount.isBlank() || amountValue != null

    /** What the row saves: as loaded when unchanged ([original]), else as it reads now with the ingredient shown. */
    fun input(): RecipeLineInput {
        val now = RecipeLineInput(amountValue, unit, text.trim(), ingredientId, group?.trim()?.ifEmpty { null })
        return original?.takeIf { it.copy(ingredientId = originalMeaning) == now } ?: now
    }

    /** The row as the line it saves ([toIngredientLine]); null when it has no amount, unit or text. */
    fun line(): IngredientLine? = input().toIngredientLine()

    /** What the row is called: the text the recipe shows, with a unit that names the ingredient (`lemon, juiced`). */
    fun label(): String = line()?.ingredientText ?: text
}

/** Typed [text] as an amount, as the server reads one ([parseAmount]: `1.5`, `1,5`, `1/2`); null for none or 0. */
fun readAmount(text: String): Double? = parseAmount(text)?.takeIf { it > 0 && it.isFinite() }

/** [amount] as the amount field shows it, read back by [readAmount] in every language; empty for none. */
fun amountText(amount: Double?): String = amount?.let { formatAmount(it, FormatLocale.English) }.orEmpty()

/**
 * The recipe editor's fields. The defaults are the empty form of a new recipe: one breakfast, as "200 g low-fat quark"
 * is a complete one. [addingTo] is the heading new rows go under: that of the last row, unless "New group" chose another.
 * [unread] are the lines of a recipe of the cooking site that its file had but the site could not read, offered as rows.
 */
@Serializable
data class RecipeDraft(
    val title: String = "",
    val servings: String = "1",
    val rows: List<DraftRow> = emptyList(),
    val instructions: String = "",
    val tags: List<String> = emptyList(),
    val slots: Set<MealSlot> = setOf(MealSlot.BREAKFAST),
    val keeps: RecipeKeeps = RecipeKeeps(),
    val meal: Boolean = true,
    val addingTo: String? = null,
    val unread: List<String> = emptyList(),
    val nextKey: Long = 0,
) {
    val servingCount: Int? get() = servings.trim().toIntOrNull()?.takeIf { it in 1..100 }

    /** Whether the server would take it: a name, 1 to 100 servings, and rows that each read as a line. */
    val canSave: Boolean
        get() = title.isNotBlank() && servingCount != null && rows.isNotEmpty() && rows.all { it.amountValid && it.line() != null }

    /** The body that saves it; only when [canSave]. Instructions and title are trimmed, as the server stores them. */
    fun input(): RecipeInput = RecipeInput(
        title = title.trim(),
        servings = servingCount!!,
        instructions = instructions.trim(),
        tags = tags,
        mealSlots = slots.sortedBy { it.ordinal },
        lines = rows.map { it.input() },
    )

    /**
     * Per serving, what the saved recipe will have ([NutritionCalculator.ofLines]), each row with the ingredient it means
     * in [catalogue]; null without a valid number of servings or an amount. Rows whose amount cannot be read are left out.
     */
    fun nutrition(catalogue: Map<Long, Ingredient>): RecipeNutrition? {
        val servings = servingCount ?: return null
        return NutritionCalculator.ofLines(rows.filter { it.amountValid }.map { it.input() to it.ingredientId?.let(catalogue::get) }, servings)
    }

    /** With a row for [ingredient], picked: its name as the text, in a unit it has ([startingAmount]), under [addingTo]. */
    fun adding(ingredient: Ingredient): RecipeDraft {
        val (amount, unit) = startingAmount(ingredient)
        return copy(rows = rows + DraftRow(nextKey, amountText(amount), unit, ingredient.name, ingredient.id, addingTo), nextKey = nextKey + 1)
    }

    /**
     * With a row for each of [lines] (pasted, [dev.tobins.mealplanner.recipes.PlainLines]), meaning the ingredient of
     * [meanings] at its place (null for none). A line without a heading of its own goes under [addingTo].
     */
    fun pasting(lines: List<RecipeLineInput>, meanings: List<Long?>): RecipeDraft {
        val added = lines.mapIndexed { i, line ->
            DraftRow(nextKey + i, amountText(line.amount), line.unit, line.text, meanings.getOrNull(i), line.group ?: addingTo)
        }
        return copy(rows = rows + added, nextKey = nextKey + added.size, addingTo = added.lastOrNull()?.group ?: addingTo)
    }

    fun changing(key: Long, change: (DraftRow) -> DraftRow): RecipeDraft = copy(rows = rows.map { if (it.key == key) change(it) else it })

    fun removing(key: Long): RecipeDraft = copy(rows = rows.filterNot { it.key == key })

    /** With the heading [from] called [to] on every row under it (none when blank), and for the rows to come. */
    fun renaming(from: String?, to: String?): RecipeDraft {
        val name = to?.trim()?.ifEmpty { null }
        return copy(rows = rows.map { if (it.group == from) it.copy(group = name) else it }, addingTo = if (addingTo == from) name else addingTo)
    }
}

/** The recipe as the editor starts on it: each line a row as it was ([DraftRow.original]), with the ingredient it means. */
fun RecipeDetail.toDraft(): RecipeDraft {
    val rows = ingredients.mapIndexed { index, mapped ->
        val line = mapped.line
        val original = RecipeLineInput(line.amount, line.unit, line.item, line.ingredientId, line.group)
        DraftRow(index.toLong(), amountText(line.amount), line.unit, line.item, mapped.ingredientId, line.group, original, mapped.ingredientId)
    }
    return RecipeDraft(
        title = summary.title,
        servings = (summary.servings ?: 1).toString(),
        rows = rows,
        instructions = instructions,
        tags = summary.tags,
        slots = summary.mealSlots.toSet(),
        keeps = RecipeKeeps(summary.keepsDays, summary.keepsVacuumDays),
        meal = summary.kind == RecipeKind.MEAL,
        addingTo = rows.lastOrNull()?.group,
        unread = problems.filter { it.startsWith(UNREADABLE_LINE) }.map { it.removePrefix(UNREADABLE_LINE) },
        nextKey = rows.size.toLong(),
    )
}

/** How the server names a line of a cooking-site file it could not read, in a recipe's problems. */
private const val UNREADABLE_LINE = "Unreadable ingredient line: "

/** A row's unit when it counts pieces: the table's key, which the cooking site's `stuks` means too. */
const val PIECE = "piece"

/**
 * The units a row of [ingredient] can be in (issue 342): grams always, millilitres when a millilitre's weight is known
 * (its density, or a food counted per 100 ml), pieces when a piece's weight is; and the row's [current] unit, whatever
 * it is, so that a line of the cooking site keeps the unit it had (`tbsp`, `clove`).
 */
fun unitChoices(ingredient: Ingredient?, current: String?): List<String> = buildList {
    add("g")
    if (ingredient == null || ingredient.gramsPerMl != null || ingredient.food?.basis == FoodBasis.PER_100_ML) add("ml")
    if (ingredient == null || ingredient.gramsPerPiece != null) add(PIECE)
    if (current != null && current !in this) add(current)
}

/**
 * What a picked [ingredient]'s row starts with (issue 346): one piece, 100 ml or 100 g of the unit the account last saved
 * it in ([Ingredient.lastUnit]) when the ingredient still has that unit ([unitChoices]); else, as for one never used, one
 * piece when a piece's weight is known, else 100 ml of a drink or 100 g.
 */
fun startingAmount(ingredient: Ingredient): Pair<Double, String> {
    val last = ingredient.lastUnit?.takeIf { it in unitChoices(ingredient, null) }
    return when {
        last == PIECE -> 1.0 to PIECE
        last != null -> 100.0 to last
        ingredient.gramsPerPiece != null -> 1.0 to PIECE
        ingredient.food?.basis == FoodBasis.PER_100_ML -> 100.0 to "ml"
        else -> 100.0 to "g"
    }
}

/**
 * [row] in [unit], its amount the same quantity of [ingredient] where that can be worked out (the same kind of measure,
 * or both with a known weight), rounded to what can be measured; else the same number. A unit that named the ingredient
 * (`1 | lemon | juiced`) leaves its name in the text (`lemon, juiced`), so the row still says what it is.
 */
fun DraftRow.inUnit(unit: String, ingredient: Ingredient?): DraftRow {
    val before = line()
    val text = if (before?.unitIsTheIngredient == true) before.ingredientText else text
    val amount = amountValue?.let { value -> converted(value, this.unit, unit, ingredient)?.let { rounded(it, unit) } }
    return copy(unit = unit, text = text, amount = amount?.let(::amountText) ?: this.amount)
}

/** [amount] of [from] in [to]: through the unit table within weights or volumes, else through grams; null when unknown. */
private fun converted(amount: Double, from: String?, to: String, ingredient: Ingredient?): Double? {
    val one = from?.let { HugoRecipeParser().line(1.0, it, "", null) } ?: return null
    val other = HugoRecipeParser().line(1.0, to, "", null) ?: return null
    if (one.dimension == other.dimension && one.dimension != Dimension.COUNT) return amount * one.baseAmount!! / other.baseAmount!!
    ingredient ?: return null
    fun grams(line: IngredientLine) = NutritionCalculator.quantityOf(line, ingredient)?.takeUnless { it.assumedDensity }?.grams
    val gramsFrom = grams(one) ?: return null
    val gramsTo = grams(other) ?: return null
    return amount * gramsFrom / gramsTo
}

/** Grams and millilitres whole (one decimal below 10), anything else to a quarter and at least one. */
private fun rounded(amount: Double, unit: String): Double = when (unit) {
    "g", "ml" -> if (amount < 10) (amount * 10).roundToLong() / 10.0 else amount.roundToLong().toDouble()
    else -> maxOf((amount * 4).roundToLong() / 4.0, 0.25)
}

/** How a row counts in the totals, for the line under it. */
enum class RowCount { COUNTED, NO_INGREDIENT, NEGLIGIBLE, NO_AMOUNT, NO_NUTRITION, WEIGHT_UNKNOWN }

/** How [this] row counts with [ingredient], the one it means; as [NutritionCalculator.perServing] counts its line. */
fun DraftRow.count(ingredient: Ingredient?): RowCount {
    val line = line()
    return when {
        ingredient == null -> RowCount.NO_INGREDIENT
        ingredient.negligible -> RowCount.NEGLIGIBLE
        line?.amount == null -> RowCount.NO_AMOUNT
        ingredient.food == null -> RowCount.NO_NUTRITION
        NutritionCalculator.quantityOf(line, ingredient) == null -> RowCount.WEIGHT_UNKNOWN
        else -> RowCount.COUNTED
    }
}

/** What one search found to pick: an ingredient of the account's own, or a NEVO food it does not have yet. */
sealed interface Found {
    data class Mine(val ingredient: Ingredient) : Found

    data class NewFood(val food: Food) : Found
}

/**
 * What a search for [query] offers (issue 342): the account's ingredients whose name, or whose food's name in either
 * language, has it (those starting with it first), and those whose food the NEVO search found; then the [foods] it
 * found that no ingredient of the account is yet, which picking adds. Nothing for fewer than two letters.
 */
fun found(catalogue: List<Ingredient>, foods: List<Food>, query: String): List<Found> {
    val words = query.trim().lowercase()
    if (words.length < 2) return emptyList()
    fun Ingredient.names() = listOfNotNull(name, food?.nameEn, food?.nameNl, food?.synonym).map { it.lowercase() }
    val owned = catalogue.mapNotNull { ingredient -> ingredient.food?.nevoCode?.let { it to ingredient } }.toMap()
    val byName = catalogue.filter { ingredient -> ingredient.names().any { words in it } }
        .sortedWith(compareBy({ !it.name.lowercase().startsWith(words) }, { it.name.lowercase() }))
    val byFood = foods.mapNotNull { owned[it.nevoCode] }
    val mine = (byName + byFood).distinctBy { it.id }.take(MAX_FOUND)
    val new = foods.filter { it.nevoCode !in owned }.take(MAX_FOUND)
    return mine.map(Found::Mine) + new.map(Found::NewFood)
}

/** At most this many of each kind of [found], so that the rows under the search field fit on a screen. */
private const val MAX_FOUND = 8

/**
 * A name for the ingredient of [food], picked to be added: its English name, as linking makes it, followed by " (NEVO)"
 * as often as another ingredient of [catalogue] has it already (names are told apart without regard to case).
 */
fun nameFor(food: Food, catalogue: List<Ingredient>): String {
    val taken = catalogue.mapTo(HashSet()) { it.name.lowercase() }
    return generateSequence(food.nameEn) { "$it (NEVO)" }.first { it.lowercase() !in taken }
}
