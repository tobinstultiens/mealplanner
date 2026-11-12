package dev.tobins.mealplanner.recipes

import dev.tobins.mealplanner.units.Dimension
import kotlinx.serialization.Serializable

/** One `amount | unit | item` line of an ingredients block. */
@Serializable
data class IngredientLine(
    /** Null for a non-scalable line such as `| | salt to taste`. */
    val amount: Double?,
    /** Canonical unit key when the unit is known (`g`, `tbsp`), otherwise the text as written. */
    val unit: String?,
    /** Null when the line has no unit at all. Unknown units count as [Dimension.COUNT]. */
    val dimension: Dimension?,
    /** Amount in grams or millilitres for weight and volume lines; null otherwise. */
    val baseAmount: Double?,
    val item: String,
    /** The `# Group` heading the line sits under, if any. */
    val group: String?,
) {
    /**
     * Text to match against the ingredient catalogue. For `1 | onion | large` the
     * ingredient is the unit itself, so the unit leads and the item is a qualifier.
     */
    val ingredientText: String
        get() = if (dimension == Dimension.COUNT && unit != null && !isKnownCountUnit) {
            if (item.isBlank()) unit else "$unit, $item"
        } else {
            item
        }

    private val isKnownCountUnit: Boolean
        get() = unit == "clove" || unit == "piece"
}

/** A recipe file from the cooking repo, as far as it can be read without a catalogue. */
@Serializable
data class ParsedRecipe(
    /** Path relative to `content/`, e.g. `rice-bowls/gyudon.md`. Stable identity across syncs. */
    val path: String,
    /** Top-level folder under `content/`, e.g. `rice-bowls`. */
    val section: String,
    val title: String,
    val tags: List<String>,
    /** Base yield the amounts are written for; null when there is no ingredients block. */
    val servings: Int?,
    val ingredients: List<IngredientLine>,
    /** The page body without front matter and ingredients blocks, as Markdown. */
    val instructions: String,
    val hasIngredientsBlock: Boolean,
    /** Lines that could not be read; the recipe still imports. */
    val problems: List<String>,
)
