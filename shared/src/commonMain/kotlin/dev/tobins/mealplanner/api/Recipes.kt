package dev.tobins.mealplanner.api

import dev.tobins.mealplanner.recipes.IngredientLine
import kotlinx.serialization.Serializable

enum class RecipeSource { COOKING_SITE, MANUAL, GENERATED }

/** Whether a recipe can fill a meal slot on its own, or is a building block (sauce, rub, dough). */
enum class RecipeKind { MEAL, COMPONENT }

@Serializable
data class RecipeSummary(
    val id: Long,
    val title: String,
    val section: String,
    val tags: List<String>,
    val servings: Int?,
    val source: RecipeSource,
    val kind: RecipeKind,
    /** False until the recipe has amounts to plan with (an ingredients block on the site). */
    val plannable: Boolean,
)

@Serializable
data class RecipeDetail(
    val summary: RecipeSummary,
    val ingredients: List<IngredientLine>,
    /** Markdown. */
    val instructions: String,
    val problems: List<String>,
)

/** Outcome of `POST /api/sync/cooking`. */
@Serializable
data class SyncResult(
    val added: Int,
    val updated: Int,
    val unchanged: Int,
    val removed: Int,
    /** Parse problems by recipe path. */
    val problems: Map<String, List<String>>,
)
