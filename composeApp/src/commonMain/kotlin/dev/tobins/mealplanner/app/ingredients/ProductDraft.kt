package dev.tobins.mealplanner.app.ingredients

import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.FoundProduct
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.LabelNutrition
import dev.tobins.mealplanner.api.LabelSource
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.Portion
import dev.tobins.mealplanner.api.hasValues
import dev.tobins.mealplanner.app.Msg
import dev.tobins.mealplanner.app.recipes.readAmount
import dev.tobins.mealplanner.app.ui.FormatLocale
import dev.tobins.mealplanner.nutrition.Salt
import dev.tobins.mealplanner.nutrition.portionKey
import dev.tobins.mealplanner.nutrition.portionNameProblem
import dev.tobins.mealplanner.recipes.parseAmount
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

/*
 * The product form's model (issue 351): a product's name, the values of its label and its named portions, as typed, and
 * what it saves. Kept apart from the screen so that it is tested without drawing it. A value is saved as it was given or
 * typed (issue 354): the form may show it rounded, and while its text is as shown it saves the number it stood for.
 */

/** The values of a label, in the order a Dutch label lists them, each with its unit and its text on the form. */
enum class LabelValue(val unit: String, val msg: Msg) {
    ENERGY("kcal", IngredientsMsg.LABEL_ENERGY),
    FAT("g", IngredientsMsg.LABEL_FAT),
    SATURATED_FAT("g", IngredientsMsg.LABEL_SATURATED_FAT),
    CARBS("g", IngredientsMsg.LABEL_CARBS),
    SUGARS("g", IngredientsMsg.LABEL_SUGARS),
    FIBRE("g", IngredientsMsg.LABEL_FIBRE),
    PROTEIN("g", IngredientsMsg.LABEL_PROTEIN),
    SALT("g", IngredientsMsg.LABEL_SALT),
}

/** A number as the form was filled with it: the [text] it showed, and the [number] it stands for, unrounded. */
@Serializable
data class Given(val text: String, val number: Double)

/** [given]'s number while [text] is still the text it showed, else null. */
private fun given(given: Given?, text: String): Double? = given?.takeIf { it.text == text }?.number

/**
 * One portion being written: its [name] and its [size] as typed, in millilitres when [ml], else in grams; [given] is the
 * size it was loaded with, saved as it was while its text is unchanged.
 */
@Serializable
data class PortionRow(val key: Long, val name: String = "", val size: String = "", val ml: Boolean = false, val given: Given? = null) {
    val sizeValue: Double? get() = given(given, size) ?: readAmount(size)

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
 * What the values of the form were filled with, as the product was loaded or a lookup gave them: the [source] they came
 * from (null: typed from the label), whether per 100 ml (null: the source did not say, and the person chose), and each
 * value given (salt's number as sodium, as it is kept).
 */
@Serializable
data class Filled(val source: String?, val perMl: Boolean?, val values: Map<LabelValue, Given>)

/**
 * The product form's fields: the [name], whether the [values] (each as typed) are per 100 ml ([perMl]) or per 100 g, and
 * the [portions]. [original] is the ingredient being changed, null for a new one: what the form does not show of it
 * (its NEVO food, weights, price, being a staple) is saved as it was. [filled] is what its values were filled with.
 * [basisNeeded] while a lookup gave values without saying per what: the person chooses before it can be saved.
 */
@Serializable
data class ProductDraft(
    val name: String = "",
    val perMl: Boolean = false,
    val values: Map<LabelValue, String> = emptyMap(),
    val portions: List<PortionRow> = emptyList(),
    val nextKey: Long = 0,
    val original: Ingredient? = null,
    val filled: Filled? = null,
    val basisNeeded: Boolean = false,
) {
    fun value(of: LabelValue): String = values[of].orEmpty()

    fun withValue(of: LabelValue, text: String): ProductDraft = copy(values = values + (of to text))

    /** [of] as a number: null when empty, which is unknown; also null when it is no number of 0 or more ([valueValid]). */
    fun number(of: LabelValue): Double? = readLabelValue(value(of))

    /** [of] as it is kept, salt as sodium: as it was given while its text is as shown, else as typed. */
    private fun kept(of: LabelValue): Double? = given(filled?.values?.get(of), value(of))
        ?: number(of)?.let { if (of == LabelValue.SALT) Salt.sodiumMg(it) else it }

    /** Whether a value or the basis is no longer what the form was filled with: then its source says so once saved. */
    val changedByHand: Boolean
        get() = filled != null &&
            ((filled.perMl != null && perMl != filled.perMl) || LabelValue.entries.any { value(it) != filled.values[it]?.text.orEmpty() })

    /** Where the values came from, as saved ([LabelSource]): marked once changed by hand; null for values typed from the label. */
    val source: String?
        get() {
            val from = filled?.source ?: return null
            return if (changedByHand) LabelSource.parse(from)?.changed()?.toString() ?: from else from
        }

    /** False while the field of [of] holds something that is no number of 0 or more; empty is unknown. */
    fun valueValid(of: LabelValue): Boolean = numberProblem(value(of)) == null

    /** What is wrong with the field of [of], or null: no number, below 0, or the energy when it is needed ([needsEnergy]). */
    fun valueProblem(of: LabelValue): NumberProblem? =
        numberProblem(value(of)) ?: NumberProblem.NEEDED.takeIf { of == LabelValue.ENERGY && needsEnergy }

    /** Whether any value of the label is filled in: then it is a label, and its energy is needed. */
    val hasLabel: Boolean get() = LabelValue.entries.any { value(it).isNotBlank() }

    /** The energy is needed for a label, and for a product without a NEVO food to count by. */
    val needsEnergy: Boolean get() = value(LabelValue.ENERGY).isBlank() && (hasLabel || original?.food == null)

    /** The label as given or typed, salt as sodium ([Salt]); null when no value is filled in, so it counts by its NEVO food. */
    fun label(): LabelNutrition? {
        if (!hasLabel) return null
        val per100 = Nutrition(
            kcal = kept(LabelValue.ENERGY),
            proteinG = kept(LabelValue.PROTEIN),
            fatG = kept(LabelValue.FAT),
            saturatedFatG = kept(LabelValue.SATURATED_FAT),
            carbsG = kept(LabelValue.CARBS),
            sugarG = kept(LabelValue.SUGARS),
            fibreG = kept(LabelValue.FIBRE),
            sodiumMg = kept(LabelValue.SALT),
        )
        return LabelNutrition(if (perMl) FoodBasis.PER_100_ML else FoodBasis.PER_100_G, per100, source)
    }

    /**
     * Whether another ingredient of [catalogue] has this name, told apart without regard to case as the server does: it
     * would refuse it, so the form says so at the name and keeps Save off.
     */
    fun nameTaken(catalogue: List<Ingredient>): Boolean =
        name.isNotBlank() && catalogue.any { it.id != original?.id && it.name.trim().equals(name.trim(), ignoreCase = true) }

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
        get() = name.isNotBlank() && !basisNeeded && LabelValue.entries.all { valueProblem(it) == null } &&
            portions.all { nameProblem(it) == null && it.sizeProblem == null }

    /**
     * The portions the ingredient had that this draft no longer has, renamed ones too: a recipe line in one of them is
     * not counted once it is saved, so the form asks first.
     */
    val removedPortions: List<Portion>
        get() = original?.portions.orEmpty().filter { had -> portions.none { portionKey(it.name) == portionKey(had.name) } }

    /** With the [removedPortions] back as rows, at the end, as they were, their sizes as [locale] writes them: "keep it". */
    fun keepingRemovedPortions(locale: FormatLocale): ProductDraft {
        val back = removedPortions.mapIndexed { index, portion -> portion.row(nextKey + index, locale) }
        return copy(portions = portions + back, nextKey = nextKey + back.size)
    }

    /**
     * The form filled with what a lookup [found]: its name, its values, each left empty that it does not give, its basis
     * (to be chosen when it does not say: [basisNeeded]) and its source; its pack as a portion named [packName], unless a
     * portion of that name is there. The portions there are stay: a lookup only adds. A product without any value
     * ([hasValues]) gives its name and pack only: the values are empty, to be typed, and no source is kept, as none of
     * them comes from it.
     */
    fun filledWith(found: FoundProduct, packName: String, locale: FormatLocale): ProductDraft {
        val pack = found.pack?.takeIf { size -> (size.grams ?: size.ml) != null && portions.none { portionKey(it.name) == portionKey(packName) } }
            ?.let { size -> Portion(packName, grams = size.grams, ml = size.ml).row(nextKey, locale) }
        val named = copy(name = found.name, portions = portions + listOfNotNull(pack), nextKey = nextKey + if (pack != null) 1 else 0)
        if (!found.hasValues) return named.copy(values = emptyMap(), filled = null, basisNeeded = false)
        val ml = found.basis?.let { it == FoodBasis.PER_100_ML }
        val given = found.per100.given(locale)
        return named.copy(
            perMl = ml ?: perMl,
            values = given.mapValues { it.value.text },
            filled = Filled(found.source, ml, given),
            basisNeeded = ml == null,
        )
    }

    /** Values per 100 ml when [ml], else per 100 g, as the person chose. */
    fun choosingBasis(ml: Boolean): ProductDraft = copy(perMl = ml, basisNeeded = false)

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
 * from its sodium), each kept as it was while unchanged ([ProductDraft.filled]), its portions, and the rest kept as it is
 * ([ProductDraft.original]).
 */
fun Ingredient.toProductDraft(locale: FormatLocale): ProductDraft {
    val given = label?.per100?.given(locale).orEmpty()
    val rows = portions.mapIndexed { index, portion -> portion.row(index.toLong(), locale) }
    val perMl = (label?.basis ?: food?.basis) == FoodBasis.PER_100_ML
    return ProductDraft(
        name = name,
        perMl = perMl,
        values = given.mapValues { it.value.text },
        portions = rows,
        nextKey = rows.size.toLong(),
        original = this,
        filled = Filled(label?.source, perMl, given),
    )
}

/** Each value of [this] as the form shows it in [locale]'s way ([formatLabelValue]; salt from its sodium), with its number as kept. */
private fun Nutrition.given(locale: FormatLocale): Map<LabelValue, Given> = mapOf(
    LabelValue.ENERGY to kcal,
    LabelValue.FAT to fatG,
    LabelValue.SATURATED_FAT to saturatedFatG,
    LabelValue.CARBS to carbsG,
    LabelValue.SUGARS to sugarG,
    LabelValue.FIBRE to fibreG,
    LabelValue.PROTEIN to proteinG,
    LabelValue.SALT to sodiumMg,
).mapNotNull { (value, number) ->
    number?.let { value to Given(formatLabelValue(if (value == LabelValue.SALT) Salt.saltG(it) else it, locale), it) }
}.toMap()

/** [this] portion as a row of the form, its size as [locale] writes it and kept as it was ([PortionRow.given]). */
private fun Portion.row(key: Long, locale: FormatLocale): PortionRow {
    val size = grams ?: ml ?: 0.0
    val text = formatLabelValue(size, locale)
    return PortionRow(key, name, text, ml = grams == null, given = Given(text, size))
}

/**
 * A label's value as the form shows it: up to three decimals, so that a salt of `0,005` g shows as such; a longer one is
 * shown rounded and saved as it was ([Given]).
 */
fun formatLabelValue(value: Double, locale: FormatLocale): String {
    val thousandths = (value * 1000).roundToLong()
    val fraction = (thousandths % 1000).toString().padStart(3, '0').trimEnd('0')
    return if (fraction.isEmpty()) "${thousandths / 1000}" else "${thousandths / 1000}${locale.decimalSeparator}$fraction"
}

/** A label's value as typed: `1.5` or `1,5`, and 0, which a label gives for sugar it has none of; null for anything else. */
fun readLabelValue(text: String): Double? = parseAmount(text)?.takeIf { it.isFinite() }

private fun Char.isLineBreak(): Boolean = this == '\n' || this == '\r' || this in "\u000B\u000C\u0085  "
