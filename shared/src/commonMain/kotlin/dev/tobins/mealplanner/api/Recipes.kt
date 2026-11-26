package dev.tobins.mealplanner.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
    /** Days it keeps once made in a normal box (issue 234); null: not for prepping that way, and from a server before preps. */
    val keepsDays: Int? = null,
    /** Days it keeps once made and vacuum sealed; null as [keepsDays]. */
    val keepsVacuumDays: Int? = null,
    /**
     * A standard meal (issue 265): one of my meals I can always make from what is at home, offered in one tap where a
     * meal of one of its [mealSlots] falls away. The planner picks it as any other recipe. False from a server before them.
     */
    val standardMeal: Boolean = false,
    /**
     * Saved with a change in the app (issue 342; a save that changes nothing does not count). A recipe taken from the
     * cooking site that was has no bank to go back to: putting it back is refused (409), it is deleted instead. False
     * from a server before it.
     */
    val edited: Boolean = false,
)

/**
 * Body of `PUT /api/recipes/{id}/keeps`: how many days the recipe keeps once made, in a normal box and
 * vacuum sealed, each 1 to [MAX_DAYS]; null for a way it is not prepped. Its own route, so that released
 * apps saving [RecipeSettings] leave it alone.
 */
@Serializable
data class RecipeKeeps(val days: Int? = null, val vacuumDays: Int? = null) {
    companion object {
        /** A cooked meal keeps a week at most, also vacuum sealed: so a batch reaches into the next plan week, never further. */
        const val MAX_DAYS = 7
    }
}

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

/**
 * Body for creating (`POST /api/recipes`) or replacing (`PUT /api/recipes/{id}`) a recipe of mine: one made in the app,
 * or one taken from the cooking site, which is the account's own copy (issue 340). Its ingredients are either [lines]
 * or, when that is null, the text [ingredients].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RecipeInput(
    val title: String,
    val servings: Int,
    /**
     * One ingredient per line as `amount | unit | item`, the format of the cooking site, with `# Group` lines for
     * headings; what released apps send. Read only when [lines] is null. Lines read from it carry no ingredient of
     * their own, except that a replaced recipe's line keeps the ingredient of a line it had with the same text.
     */
    val ingredients: String = "",
    val instructions: String = "",
    val tags: List<String> = emptyList(),
    val mealSlots: List<MealSlot> = emptyList(),
    /**
     * The ingredients one by one, each with the catalogue ingredient it is when known; null to send [ingredients] instead.
     * Never sent when null, so that a body with text only is the one apps sent before, which a server from before
     * lines (refusing fields it does not know) still takes.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val lines: List<RecipeLineInput>? = null,
)

/**
 * One ingredient line of [RecipeInput.lines]: what `amount | unit | text` under the heading [group] says, and the
 * account's catalogue ingredient it is. A line with an [ingredientId] is that ingredient whatever its text says; one
 * without is looked up by its text, as a line of the import format is. A recipe read back ([RecipeDetail]) gives each
 * line's fields in [MappedLine.line] (`amount`, `unit`, `item`, `ingredientId`, `group`), so it can be saved as it was.
 */
@Serializable
data class RecipeLineInput(
    /** Null for a line without an amount, such as salt to taste. */
    val amount: Double? = null,
    /** A unit of the cooking site's table (`g`, `tbsp`, `clove`) or any other word, counted as pieces; null for none. */
    val unit: String? = null,
    val text: String,
    /** The account's own ingredient; another account's is refused (404). */
    val ingredientId: Long? = null,
    /** The heading the line sits under, as `# Group` in the text. */
    val group: String? = null,
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
    /** The account's optional toppings of the recipe ([RecipeTopping]), in their order; empty from a server from before them. */
    val toppings: List<RecipeTopping> = emptyList(),
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
    /**
     * The repository the account's bank comes from: its own, or the server's when [shared] (then without
     * a user name or password in it, for an account other than the server's owner). Null when there is none,
     * or the server's site is a checkout on its disk.
     */
    val repoUrl: String? = null,
    /** True when the bank comes from the server's own cooking site, not from a repository the account set. */
    val shared: Boolean = false,
    /** True when the server has a cooking site this account may choose ([CookingRepoSetting] without a URL). */
    val serverSite: Boolean = false,
    /** The address of that site, to name it, without a user name or password; null when it is a checkout on the server's disk. */
    val serverRepoUrl: String? = null,
    /**
     * When the server last fetched its cooking site, for an account whose bank it is ([shared]): a sync within a few
     * minutes of a fetch imports the site as it was then, without fetching it again. Null for any other site, and
     * until the server's first fetch since it started.
     */
    val fetchedAt: String? = null,
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
