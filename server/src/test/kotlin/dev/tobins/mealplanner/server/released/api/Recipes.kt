// Frozen from v0.1.6 (f0c5fecdfd665b93882988ecd6063a74b6ae974b) by scripts/freeze-api.sh. Do not edit: freeze again after a release.
package dev.tobins.mealplanner.released.api

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
    /**
     * The recipe's picture from the cooking site, relative to the server, e.g.
     * `/api/recipes/12/image?v=3f2a…`; fetched with the login like any other call. The URL
     * changes when the picture does, so it can be cached for good. Null when there is none
     * (always for recipes made in the app).
     */
    val imageUrl: String? = null,
    /**
     * One of my recipes: made in the app, or added from the bank of cooking-site recipes. False for a
     * recipe only in the bank (`GET /api/recipes/bank`). Servers before the bank had no bank: true.
     */
    val added: Boolean = true,
    /** The planner may pick it on its own; one of my recipes outside the rotation is planned only by hand. */
    val inRotation: Boolean = true,
    /** Its file has left the cooking site; an added recipe stays one of my recipes all the same. */
    val removedFromSite: Boolean = false,
)

/** Body of `PUT /api/recipes/{id}/added`: adds a bank recipe to my recipes, or puts it back in the bank. */
@Serializable
data class AddedInput(
    val added: Boolean,
    /** Whether an added recipe is in the rotation; a recipe put back in the bank never is. */
    val inRotation: Boolean = true,
)

/** Body of `PUT /api/recipes/added`: [AddedInput] for many recipes at once; nothing changes unless all can. */
@Serializable
data class ManyAddedInput(
    val ids: List<Long>,
    val added: Boolean,
    val inRotation: Boolean = true,
)

/** Body of `PUT /api/recipes/{id}/rotation`. */
@Serializable
data class RotationInput(val inRotation: Boolean)

/** Body of `PUT /api/recipes/rotation`: [RotationInput] for many recipes at once; nothing changes unless all can. */
@Serializable
data class ManyRotationInput(
    val ids: List<Long>,
    val inRotation: Boolean,
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

/**
 * Body of `PUT /api/recipes/{id}/settings`: how the planner treats a recipe. Works for every source. Leaves
 * whether it is added and in the rotation alone, which released apps that save it know nothing of.
 */
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
