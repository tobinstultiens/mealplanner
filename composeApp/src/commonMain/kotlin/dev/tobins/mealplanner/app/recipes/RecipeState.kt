package dev.tobins.mealplanner.app.recipes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.api.RecipeSettings
import dev.tobins.mealplanner.api.Routines
import dev.tobins.mealplanner.api.StandardSwitch
import dev.tobins.mealplanner.api.ToppingInput
import dev.tobins.mealplanner.app.LoadState
import dev.tobins.mealplanner.app.Msg
import dev.tobins.mealplanner.app.ScreenMemory
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.ui.Load
import dev.tobins.mealplanner.app.ui.failureSentence
import dev.tobins.mealplanner.app.ui.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** How the recipe page tells the person something; the screen shows it as a snackbar. Each returns when it is gone. */
internal interface RecipeVoice {
    suspend fun say(sentence: String)

    /** Says that the recipe was added ([message]), with how many of its ingredients are [unlinked] and "Undo", which runs [onUndo]. */
    suspend fun announceAdded(message: String, unlinked: Int, onUndo: suspend () -> Unit)

    /** Says [message] with "Undo"; true when the person tapped it. */
    suspend fun offerUndo(message: String): Boolean
}

/**
 * What the recipe page loads, sends and shows while that runs or fails; the composable draws what is here and tells it
 * what the person did. A plain class made by the screen (`remember`) that runs in the screen's own [scope], so leaving
 * the page ends what it was doing, as it always did (see `docs/architecture.md`, "A screen's state class").
 *
 * It holds the recipe as [LoadState] (kept in the [ScreenMemory], and a failed refresh keeps what was shown), the daily
 * routines ([routines]), whether a change runs ([acting]) and what marking the recipe a standard meal made staples
 * ([standardMarked]). Cook mode, the choice to let the planner pick an added recipe and which dialog is open stay with the
 * composable.
 *
 * The controls that start a change are greyed while one runs ([acting]), so most changes are sent at once; adding a
 * recipe, putting it back and marking a meal eaten are ignored while another change runs. A failed change is told with
 * [RecipeVoice.say] and the recipe stays as it was; rating, the planner settings and the routines say nothing when they fail.
 */
internal class RecipeState(
    private val server: RecipeServer,
    private val scope: CoroutineScope,
    memory: ScreenMemory,
    private val recipeId: Long,
    private val strings: () -> Strings,
    private val voice: RecipeVoice,
) {
    /** The recipe as it is loaded, for [RefreshNotice][dev.tobins.mealplanner.app.RefreshNotice]. */
    val loaded: LoadState<RecipeDetail> = LoadState(memory, "recipe:$recipeId")

    /** What the page shows: the spinner, the recipe, or why there is none. */
    val recipe: Load<RecipeDetail> get() = loaded.value

    /** The recipes pinned as the breakfast or snack of every day; null until known, and the page works without them. */
    var routines: Routines? by mutableStateOf(null)
        private set

    /** True while a change runs; the controls that start another are greyed. */
    var acting: Boolean by mutableStateOf(false)
        private set

    /** What marking the recipe a standard meal here made staples (issue 265), until the person leaves the recipe. */
    var standardMarked: StandardSwitch? by mutableStateOf(null)
        private set

    /** How often the person asked to load again; a key of the page's load. */
    var reloads: Int by mutableIntStateOf(0)
        private set

    /** Loads the recipe again: the retry of an error or of a failed refresh. */
    fun reload() {
        reloads++
    }

    /** Asks for the recipe again behind what is shown (a failure keeps it, see [LoadState.refresh]) and then for the routines. */
    suspend fun refresh() {
        loaded.refresh { server.recipe(recipeId) }
        (load { server.routines() } as? Load.Ready)?.value?.let { routines = it }
    }

    /** Shows the recipe the server answered after a change, or tells why the change failed with [failed]. */
    private suspend fun settle(result: Load<RecipeDetail>, failed: Msg) {
        when (result) {
            is Load.Ready -> loaded.value = result
            is Load.Failed -> voice.say(failureSentence(result, failed))
            Load.Loading -> Unit
        }
    }

    /** Runs [action] while [acting] and settles with its answer. */
    private fun act(failed: Msg, action: suspend () -> RecipeDetail) {
        acting = true
        scope.launch {
            val result = load(action)
            acting = false
            settle(result, failed)
        }
    }

    /**
     * Takes a recipe of the bank into my recipes, for the planner to pick when [inRotation], and says so with "Undo".
     * A recipe of the bank is read-only until it is added.
     */
    fun add(inRotation: Boolean) {
        if (acting) return
        acting = true
        scope.launch {
            val result = load { server.setRecipeAdded(recipeId, added = true, inRotation = inRotation) }
            acting = false
            settle(result, RecipesMsg.ADDED_FAILED)
            if (result is Load.Ready) {
                val summary = result.value.summary
                voice.announceAdded(strings()(RecipesMsg.ADDED_ONE, summary.title), unlinkedIngredients(listOf(summary))) {
                    settle(load { server.setRecipeAdded(recipeId, added = false, inRotation = true) }, RecipesMsg.REMOVE_FAILED)
                }
            }
        }
    }

    /**
     * Puts a recipe from the cooking site back in the bank, and offers "Undo"; a recipe that is planned says so (409) and stays.
     * [wasInRotation] is what it was in my recipes: it goes to [onPutBack] so that the page's switch shows it, and so that
     * adding the recipe again, with "Undo" or after, keeps it.
     */
    fun putBack(title: String, wasInRotation: Boolean, onPutBack: (Boolean) -> Unit) {
        if (acting) return
        acting = true
        scope.launch {
            val result = load { server.setRecipeAdded(recipeId, added = false, inRotation = true) }
            acting = false
            settle(result, RecipesMsg.REMOVE_FAILED)
            if (result is Load.Ready) {
                onPutBack(wasInRotation)
                if (voice.offerUndo(strings()(RecipesMsg.REMOVED_ONE, title))) {
                    settle(load { server.setRecipeAdded(recipeId, added = true, inRotation = wasInRotation) }, RecipesMsg.ADDED_FAILED)
                }
            }
        }
    }

    /**
     * Sets the planned meal the recipe was opened from to eaten, by the route Today and the Plan use (they load it again
     * when they come back); [onEaten] runs when it went through.
     */
    fun markEaten(place: MealPlace, onEaten: () -> Unit) {
        if (acting) return
        acting = true
        scope.launch {
            val result = load { server.setMealStatus(place.weekStart, place.day, place.slot, MealStatus.EATEN) }
            acting = false
            when (result) {
                is Load.Ready -> onEaten()
                is Load.Failed -> voice.say(failureSentence(result, RecipesMsg.COOK_MARK_EATEN_FAILED))
                Load.Loading -> Unit
            }
        }
    }

    /** Deletes the recipe; [onDeleted] runs when it is gone, to leave the page. */
    fun delete(onDeleted: () -> Unit) {
        acting = true
        scope.launch {
            val result = load { server.deleteRecipe(recipeId) }
            acting = false
            when (result) {
                is Load.Ready -> onDeleted()
                is Load.Failed -> voice.say(failureSentence(result, RecipesMsg.DELETE_FAILED))
                Load.Loading -> Unit
            }
        }
    }

    /** Rates the recipe, or clears the rating for null; a failure changes nothing and says nothing. */
    fun rate(rating: Int?) {
        scope.launch { (load { server.rateRecipe(recipeId, rating) } as? Load.Ready)?.let { loaded.value = it } }
    }

    fun setInRotation(inRotation: Boolean) = act(RecipesMsg.ROTATION_FAILED) { server.setRecipeRotation(recipeId, inRotation) }

    /** Marks the recipe a standard meal or not, and keeps what that made staples ([standardMarked]) when it was marked. */
    fun setStandard(standard: Boolean) {
        acting = true
        scope.launch {
            val result = load { server.setStandardMeal(recipeId, standard) }
            acting = false
            when (result) {
                is Load.Ready -> {
                    standardMarked = result.value.takeIf { standard }
                    loaded.value = Load.Ready(result.value.recipe)
                }
                is Load.Failed -> voice.say(failureSentence(result, RecipesMsg.STANDARD_FAILED))
                Load.Loading -> Unit
            }
        }
    }

    /** Sets which meals the planner may use the recipe for; a failure changes nothing and says nothing. */
    fun saveSettings(settings: RecipeSettings) {
        scope.launch { (load { server.updateRecipeSettings(recipeId, settings) } as? Load.Ready)?.let { loaded.value = it } }
    }

    /** Sets how long the recipe keeps once made. */
    fun setKeeps(keeps: RecipeKeeps) = act(RecipesMsg.KEEPS_FAILED) { server.setRecipeKeeps(recipeId, keeps) }

    /** Saves the whole list of toppings; the answer goes to [toppingsSaved] once the toppings' own busy state is released. */
    suspend fun saveToppings(toppings: List<ToppingInput>): Load<RecipeDetail> = load { server.setRecipeToppings(recipeId, toppings) }

    /** Shows the recipe after the toppings were saved, or tells why that failed. */
    suspend fun toppingsSaved(result: Load<RecipeDetail>) = settle(result, RecipesMsg.TOPPINGS_FAILED)

    /** Pins or unpins the recipe as the breakfast or snack of every day; a failure changes nothing and says nothing. */
    fun saveRoutines(updated: Routines) {
        scope.launch { (load { server.saveRoutines(updated) } as? Load.Ready)?.let { routines = it.value } }
    }
}
