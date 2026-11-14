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
    /** Estimated cost per serving; null when nothing in it could be priced. */
    val cost: RecipeCost? = null,
)

/** What a serving costs, from regular store prices and this week's offers. */
@Serializable
data class RecipeCost(
    /** Euros per serving at regular prices. */
    val perServing: Double,
    /** Euros per serving with the current week's offers taken off. */
    val perServingWithOffers: Double,
    /** Ingredients without a known price, counted at a typical price per kg. */
    val estimated: List<String> = emptyList(),
)

@Serializable
data class RatingInput(val rating: Int?)

enum class GenerationMode {
    /** A recipe that fits the slot and the wish. */
    PREFERENCE,

    /** Something different from what was eaten lately, built from what is at home and on offer. */
    TASTE_BREAKER,
}

/** Body of `POST /api/recipes/generate`. */
@Serializable
data class GenerateRequest(
    val mode: GenerationMode = GenerationMode.PREFERENCE,
    val slot: MealSlot = MealSlot.DINNER,
    /** Free text, e.g. "Korean, spicy, under 30 minutes". */
    val wish: String = "",
)

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

/** When the server last read the cooking site, from `GET /api/sync/cooking`. */
@Serializable
data class CookingSyncStatus(
    /** ISO instant of the last successful sync; null if none yet. */
    val syncedAt: String?,
    /** The cooking repo commit that sync read. */
    val commit: String?,
    /** Message of the last failed sync, cleared by a successful one. */
    val error: String?,
    /** How often the server syncs on its own; null when it doesn't (no repo URL configured). */
    val everyHours: Int?,
    /** The account's cooking-site repository; null when none is set. */
    val repoUrl: String? = null,
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
