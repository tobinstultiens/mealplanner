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
     * For `1 | can | black beans` the unit is only a container, so the item is the ingredient.
     */
    val ingredientText: String
        get() = if (unitIsTheIngredient) {
            if (item.isBlank()) unit!! else "$unit, $item"
        } else {
            item
        }

    /** True for count lines such as `1 | onion | large`, where the unit names the ingredient. */
    val unitIsTheIngredient: Boolean
        get() = dimension == Dimension.COUNT && unit != null && unit.lowercase() !in pieceWords

    companion object {
        /**
         * Count units that say how something is portioned, not what it is. The first
         * two are the known count units of the cooking repo's `units.yaml`.
         */
        val pieceWords = setOf(
            "clove", "piece",
            "can", "tin", "blik", "blikje", "jar", "pot", "package", "packet", "pack", "pakje", "zakje", "bag",
            "slice", "plak", "plakje", "sheet", "sprig", "takje", "bunch", "bos", "knob", "fillet", "whole",
            "pinch", "snufje", "handful", "handje", "stick", "stalk", "stengel", "head",
        )

        /**
         * Leading words that describe preparation or size, not the ingredient. "ground" and
         * "dried" are deliberately absent: ground pork is not pork, dried basil is not basil.
         */
        private val descriptors = setOf(
            "chopped", "minced", "diced", "sliced", "grated", "crushed", "shredded", "peeled", "toasted", "melted",
            "softened", "cooked", "coarse", "coarsely", "finely", "freshly", "thinly", "roughly", "fresh", "large",
            "small", "medium", "cold", "warm", "hot", "boneless", "skinless", "whole", "ripe", "pure", "good", "quality",
            "verse", "vers", "grote", "kleine", "gesneden", "fijngesneden", "gehakte", "geraspte",
        )

        /** [core] without leading [descriptors]: `freshly ground black pepper` stays, `chopped green onion` -> `green onion`. */
        fun withoutDescriptors(core: String): String {
            val words = core.split(' ')
            return words.dropWhile { it in descriptors }.ifEmpty { words }.joinToString(" ")
        }

        /**
         * The essential part of an ingredient text: lower case, without bracketed notes
         * and without the preparation note after a comma. `Garlic, minced (fresh)` -> `garlic`.
         */
        fun coreText(text: String): String =
            text.replace(Regex("\\(.*?\\)"), " ").substringBefore(',').lowercase().replace(Regex("\\s+"), " ").trim()
    }
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
