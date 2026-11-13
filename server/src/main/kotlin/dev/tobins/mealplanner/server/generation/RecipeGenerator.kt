package dev.tobins.mealplanner.server.generation

import dev.tobins.mealplanner.api.GenerationMode
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.recipes.parseAmount
import kotlinx.serialization.Serializable

/** Everything a generator needs to know to write a recipe that fits. */
data class GenerationContext(
    val mode: GenerationMode,
    val slot: MealSlot,
    val wish: String,
    /** Calories and protein one serving should come close to. */
    val kcalPerServing: Int,
    val proteinPerServing: Int,
    val servings: Int,
    val staples: List<String>,
    val onHand: List<String>,
    /** Ingredients on offer at the stores this week. */
    val onOffer: List<String>,
    /** Titles of meals planned recently, to stay away from. */
    val recent: List<String>,
    /** Titles already in the recipe collection, so no duplicate is written. */
    val existing: List<String>,
    val likes: List<String> = emptyList(),
    val dislikes: List<String> = emptyList(),
)

/** A recipe as a generator returns it, before it is checked and stored. */
@Serializable
data class GeneratedRecipe(
    val title: String,
    val servings: Int,
    val ingredients: List<GeneratedIngredient>,
    val steps: List<String>,
    val tags: List<String> = emptyList(),
    /** One or two sentences on why it fits, shown with the recipe. */
    val description: String = "",
)

/** [unit] is one of [GeneratedIngredient.UNITS]; an empty unit with no amount means "to taste". */
@Serializable
data class GeneratedIngredient(val amount: Double?, val unit: String, val item: String) {
    /** As an ingredients-block line, the format the rest of the app parses. */
    fun asLine(): String {
        val amountText = amount?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }.orEmpty()
        return "$amountText | $unit | ${item.replace('|', '/').trim()}"
    }

    companion object {
        val UNITS = listOf("g", "ml", "tsp", "tbsp", "piece", "clove", "")
    }
}

/** Writes recipes. Implementations: [ClaudeRecipeGenerator]; tests use a fake. */
interface RecipeGenerator {
    /** @throws GenerationException when no usable recipe came back. */
    suspend fun generate(context: GenerationContext): GeneratedRecipe
}

/** Generation failed in a way worth telling the user about; mapped to 502. */
class GenerationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Problems that make a generated recipe unusable. The app then shows the message instead
 * of storing something half-broken.
 */
fun GeneratedRecipe.problems(): List<String> = buildList {
    if (title.isBlank()) add("no title")
    if (servings !in 1..12) add("servings $servings is out of range")
    if (ingredients.isEmpty()) add("no ingredients")
    if (steps.isEmpty()) add("no steps")
    ingredients.forEach { line ->
        if (line.unit !in GeneratedIngredient.UNITS) add("unknown unit '${line.unit}' for ${line.item}")
        if (line.item.isBlank()) add("an ingredient without a name")
        if (line.amount != null && (line.amount <= 0 || parseAmount(line.asLine().substringBefore('|').trim()) == null)) {
            add("bad amount ${line.amount} for ${line.item}")
        }
        if (line.amount == null && line.unit.isNotEmpty()) add("unit without amount for ${line.item}")
    }
}
