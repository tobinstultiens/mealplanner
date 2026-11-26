package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.LabelNutrition
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.Portion
import dev.tobins.mealplanner.nutrition.Salt
import dev.tobins.mealplanner.nutrition.portionKey
import dev.tobins.mealplanner.nutrition.portionNameProblem
import dev.tobins.mealplanner.recipes.parseAmount
import kotlinx.serialization.Serializable

/*
 * The product form's model (issue 351): a product's name, the values of its label and its named portions, as typed, and
 * what it saves. Kept apart from the screen so that it is tested without drawing it.
 */

/** The values of a label, in the order a Dutch label lists them, each with its unit and its text on the form. */
enum class LabelValue(val unit: String, val msg: Msg) {
    ENERGY("kcal", Msg.LABEL_ENERGY),
    FAT("g", Msg.LABEL_FAT),
    SATURATED_FAT("g", Msg.LABEL_SATURATED_FAT),
    CARBS("g", Msg.LABEL_CARBS),
    SUGARS("g", Msg.LABEL_SUGARS),
    FIBRE("g", Msg.LABEL_FIBRE),
    PROTEIN("g", Msg.LABEL_PROTEIN),
    SALT("g", Msg.LABEL_SALT),
}

/** One portion being written: its [name] and its [size] as typed, in millilitres when [ml], else in grams. */
@Serializable
data class PortionRow(val key: Long, val name: String = "", val size: String = "", val ml: Boolean = false) {
    val sizeValue: Double? get() = readAmount(size)

    /** What is wrong with the size, or null: none given, no number, below 0, or 0, which no portion weighs. */
    val sizeProblem: NumberProblem?
        get() = when {
            size.isBlank() -> NumberProblem.NEEDED
            sizeValue != null -> null
            else -> numberProblem(size) ?: NumberProblem.ZERO
        }

    fun portion(): Portion = Portion(name.trim(), grams = sizeValue.takeUnless { ml }, ml = sizeValue.takeIf { ml })
}

/** What is wrong with a portion's name, for the line under its field. */
enum class PortionNameProblem { NEEDED, TAKEN, IS_UNIT, BAD_CHARACTER }

/** What is wrong with a number of the form, for the line under its field: each keeps Save off until it is put right. */
enum class NumberProblem { NEEDED, NOT_A_NUMBER, NEGATIVE, ZERO }

/**
 * Why [text] is no number of 0 or more, or null when it is one (or is empty): a number with a minus sign is one, but may
 * not be below 0, which is said as such rather than as no number at all.
 */
fun numberProblem(text: String): NumberProblem? = when {
    text.isBlank() || readLabelValue(text) != null -> null
    text.trim().startsWith("-") && readLabelValue(text.trim().removePrefix("-")) != null -> NumberProblem.NEGATIVE
    else -> NumberProblem.NOT_A_NUMBER
}

/**
 * The product form's fields: the [name], whether the [values] (each as typed) are per 100 ml ([perMl]) or per 100 g, and
 * the [portions]. [original] is the ingredient being changed, null for a new one: what the form does not show of it
 * (its NEVO food, weights, price, being a staple, where its values came from) is saved as it was.
 */
@Serializable
data class ProductDraft(
    val name: String = "",
    val perMl: Boolean = false,
    val values: Map<LabelValue, String> = emptyMap(),
    val portions: List<PortionRow> = emptyList(),
    val nextKey: Long = 0,
    val original: Ingredient? = null,
) {
    fun value(of: LabelValue): String = values[of].orEmpty()

    fun withValue(of: LabelValue, text: String): ProductDraft = copy(values = values + (of to text))

    /** [of] as a number: null when empty, which is unknown; also null when it is no number of 0 or more ([valueValid]). */
    fun number(of: LabelValue): Double? = readLabelValue(value(of))

    /** False while the field of [of] holds something that is no number of 0 or more; empty is unknown. */
    fun valueValid(of: LabelValue): Boolean = numberProblem(value(of)) == null

    /** What is wrong with the field of [of], or null: no number, below 0, or the energy when it is needed ([needsEnergy]). */
    fun valueProblem(of: LabelValue): NumberProblem? =
        numberProblem(value(of)) ?: NumberProblem.NEEDED.takeIf { of == LabelValue.ENERGY && needsEnergy }

    /** Whether any value of the label is filled in: then it is a label, and its energy is needed. */
    val hasLabel: Boolean get() = LabelValue.entries.any { value(it).isNotBlank() }

    /** The energy is needed for a label, and for a product without a NEVO food to count by. */
    val needsEnergy: Boolean get() = value(LabelValue.ENERGY).isBlank() && (hasLabel || original?.food == null)

    /** The label as typed, salt as sodium ([Salt]); null when no value is filled in, so it counts by its NEVO food. */
    fun label(): LabelNutrition? {
        if (!hasLabel) return null
        val per100 = Nutrition(
            kcal = number(LabelValue.ENERGY),
            proteinG = number(LabelValue.PROTEIN),
            fatG = number(LabelValue.FAT),
            saturatedFatG = number(LabelValue.SATURATED_FAT),
            carbsG = number(LabelValue.CARBS),
            sugarG = number(LabelValue.SUGARS),
            fibreG = number(LabelValue.FIBRE),
            sodiumMg = number(LabelValue.SALT)?.let(Salt::sodiumMg),
        )
        return LabelNutrition(if (perMl) FoodBasis.PER_100_ML else FoodBasis.PER_100_G, per100, original?.label?.source)
    }

    /** What is wrong with the name of portion [row], or null; as the server refuses it ([portionNameProblem]). */
    fun nameProblem(row: PortionRow): PortionNameProblem? = when {
        row.name.isBlank() -> PortionNameProblem.NEEDED
        portions.any { it.key != row.key && portionKey(it.name) == portionKey(row.name) } -> PortionNameProblem.TAKEN
        portionNameProblem(row.name) == null -> null
        row.name.any { it == '|' || it.isLineBreak() } -> PortionNameProblem.BAD_CHARACTER
        else -> PortionNameProblem.IS_UNIT
    }

    /**
     * Whether the server would take it: a name, values that read, the energy when needed, and portions that each can be.
     * Each thing that keeps it off is said at its own field ([valueProblem], [nameProblem], [PortionRow.sizeProblem]).
     */
    val canSave: Boolean
        get() = name.isNotBlank() && LabelValue.entries.all { valueProblem(it) == null } &&
            portions.all { nameProblem(it) == null && it.sizeProblem == null }

    /**
     * The portions the ingredient had that this draft no longer has, renamed ones too: a recipe line in one of them is
     * not counted once it is saved, so the form asks first.
     */
    val removedPortions: List<Portion>
        get() = original?.portions.orEmpty().filter { had -> portions.none { portionKey(it.name) == portionKey(had.name) } }

    /** With the [removedPortions] back as rows, at the end, as they were, their sizes as [locale] writes them: "keep it". */
    fun keepingRemovedPortions(locale: FormatLocale): ProductDraft {
        val back = removedPortions.mapIndexed { index, portion ->
            PortionRow(nextKey + index, portion.name, formatAmount(portion.grams ?: portion.ml ?: 0.0, locale), ml = portion.grams == null)
        }
        return copy(portions = portions + back, nextKey = nextKey + back.size)
    }

    /** The body that saves it; only when [canSave]. */
    fun input(): IngredientInput {
        val kept = original
        return IngredientInput(
            name = name.trim(),
            nevoCode = kept?.food?.nevoCode,
            gramsPerPiece = kept?.gramsPerPiece,
            gramsPerMl = kept?.gramsPerMl,
            category = kept?.category,
            pricePerKg = kept?.pricePerKg,
            isStaple = kept?.isStaple ?: false,
            negligible = kept?.negligible ?: false,
            label = label(),
            portions = portions.map { it.portion() },
        )
    }

    fun addingPortion(): ProductDraft = copy(portions = portions + PortionRow(nextKey, ml = perMl), nextKey = nextKey + 1)

    fun changingPortion(key: Long, change: (PortionRow) -> PortionRow): ProductDraft =
        copy(portions = portions.map { if (it.key == key) change(it) else it })

    fun removingPortion(key: Long): ProductDraft = copy(portions = portions.filterNot { it.key == key })
}

/**
 * [this] ingredient as the product form starts on it: its label's values in [locale]'s way of writing a number (salt
 * from its sodium), its portions, and the rest kept as it is ([ProductDraft.original]).
 */
fun Ingredient.toProductDraft(locale: FormatLocale): ProductDraft {
    val per100 = label?.per100
    val numbers = mapOf(
        LabelValue.ENERGY to per100?.kcal,
        LabelValue.FAT to per100?.fatG,
        LabelValue.SATURATED_FAT to per100?.saturatedFatG,
        LabelValue.CARBS to per100?.carbsG,
        LabelValue.SUGARS to per100?.sugarG,
        LabelValue.FIBRE to per100?.fibreG,
        LabelValue.PROTEIN to per100?.proteinG,
        LabelValue.SALT to per100?.sodiumMg?.let(Salt::saltG),
    )
    val rows = portions.mapIndexed { index, portion ->
        PortionRow(index.toLong(), portion.name, formatAmount(portion.grams ?: portion.ml ?: 0.0, locale), ml = portion.grams == null)
    }
    return ProductDraft(
        name = name,
        perMl = (label?.basis ?: food?.basis) == FoodBasis.PER_100_ML,
        values = numbers.mapNotNull { (value, number) -> number?.let { value to formatAmount(it, locale) } }.toMap(),
        portions = rows,
        nextKey = rows.size.toLong(),
        original = this,
    )
}

/** A label's value as typed: `1.5` or `1,5`, and 0, which a label gives for sugar it has none of; null for anything else. */
fun readLabelValue(text: String): Double? = parseAmount(text)?.takeIf { it.isFinite() }

private fun Char.isLineBreak(): Boolean = this == '\n' || this == '\r' || this in "\u000B\u000C\u0085  "
