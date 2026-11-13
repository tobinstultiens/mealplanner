package dev.tobins.mealplanner.api

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
    /** Null when the recipe has no amounts to compute from. */
    val nutrition: RecipeNutrition? = null,
    /** Meal slots the planner may use this recipe for. */
    val mealSlots: List<MealSlot> = emptyList(),
    /** 1 to 5; null when not rated. The planner prefers higher-rated recipes. */
    val rating: Int? = null,
)

@Serializable
data class RatingInput(val rating: Int?)

/** Recipes eaten every day in a slot, chosen by the user instead of the planner. */
@Serializable
data class Routines(val breakfast: Long? = null, val snack: Long? = null)

/** Body for creating or replacing a recipe of the user's own (source MANUAL). */
@Serializable
data class RecipeInput(
    val title: String,
    val servings: Int,
    /** One ingredient per line as `amount | unit | item`, the format of the cooking site. */
    val ingredients: String,
    val instructions: String = "",
    val tags: List<String> = emptyList(),
    val mealSlots: List<MealSlot> = emptyList(),
)

/** Body of `PUT /api/recipes/{id}/settings`: how the planner treats a recipe. Works for every source. */
@Serializable
data class RecipeSettings(
    val kind: RecipeKind,
    val mealSlots: List<MealSlot>,
)

@Serializable
data class RecipeDetail(
    val summary: RecipeSummary,
    val ingredients: List<MappedLine>,
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
    /** Ingredient texts linked automatically after this sync because they match a food name exactly. */
    val autoLinked: Int = 0,
    /** The cooking repo commit that was read, when the server fetches the repo itself. */
    val commit: String? = null,
    /** Parse problems by recipe path. */
    val problems: Map<String, List<String>>,
)
