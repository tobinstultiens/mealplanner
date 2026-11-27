package dev.tobins.mealplanner.server.recipes

import dev.tobins.mealplanner.api.AddedInput
import dev.tobins.mealplanner.api.ManyAddedInput
import dev.tobins.mealplanner.api.ManyRotationInput
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.RotationInput
import dev.tobins.mealplanner.api.Routines
import dev.tobins.mealplanner.server.ConflictException
import dev.tobins.mealplanner.server.NotFoundException
import dev.tobins.mealplanner.server.db.Recipe
import dev.tobins.mealplanner.server.ingredients.IngredientRepository
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Which of the account's recipes are mine, and changing that. Those from its cooking site start in its bank; the
 * account adds the ones it cooks to "my recipes", and the planner picks on its own only from my recipes in the
 * rotation. Recipes made in the app are always mine. A recipe added from the bank is taken: the account's own copy,
 * edited and deleted as one made in the app, which the sync no longer changes (issue 340). This adds recipes and puts
 * them back in the bank, puts them in the rotation and takes them out, pins the routines and deletes. See README,
 * "Recipes: the bank, my recipes and the rotation".
 */
class MyRecipes(
    private val store: RecipeStore,
    private val view: RecipeView,
    private val ingredients: IngredientRepository,
    /** Today as the household sees it: which planned weeks are over. */
    private val today: () -> LocalDate,
) {
    /**
     * The account's recipe [id], when it is in the bank or one of mine. One whose file left the site
     * while it was only in the bank is as good as not there, as removed recipes were before the bank; so are the foods of
     * a slot of the plan (issue 358), which are no recipe to the person: they are not added, chosen, prepped or deleted.
     */
    private fun known(id: Long): Recipe =
        ownRecipe(id).takeIf { it.addedAt != null || it.removedAt == null } ?: throw NotFoundException("No recipe with id $id")

    /** The account's recipe [id], from any source, but not the foods of a slot of the plan (issue 358), which only the plan changes. */
    internal fun ownRecipe(id: Long): Recipe =
        store.byId(id)?.takeIf { it.slotFoods == 0L } ?: throw NotFoundException("No recipe with id $id")

    /** The account's recipe [id] as one of mine ([known]); a [ConflictException] while it is only in the bank. */
    fun requireMine(id: Long): Recipe =
        known(id).also { if (it.addedAt == null) throw ConflictException("Add \"${it.title}\" to your recipes first") }

    /** Adds a bank recipe to my recipes, or puts it back in the bank ([changeAdded]). */
    fun setAdded(id: Long, input: AddedInput): RecipeDetail {
        changeAdded(listOf(id), input.added, input.inRotation)
        return view.find(id)!!
    }

    /** [setAdded] for many recipes at once, answering my recipes as they are then. */
    fun setAdded(input: ManyAddedInput): List<RecipeSummary> {
        changeAdded(input.ids, input.added, input.inRotation)
        return view.list()
    }

    /** Puts one of my recipes in the rotation or takes it out ([changeRotation]). */
    fun setRotation(id: Long, input: RotationInput): RecipeDetail {
        changeRotation(listOf(id), input.inRotation)
        return view.find(id)!!
    }

    /** [setRotation] for many recipes at once, answering my recipes as they are then. */
    fun setRotation(input: ManyRotationInput): List<RecipeSummary> {
        changeRotation(input.ids, input.inRotation)
        return view.list()
    }

    /**
     * Adds [ids] to my recipes, [inRotation] or not, or puts them back in the bank: all of them, or none
     * when one cannot be (not the account's: 404; [requireRemovable], [requireNotRoutine]: 409). Adding
     * one that is added keeps when it was added and sets its rotation; putting back one that is in the
     * bank changes nothing. What is added gets its exact-name ingredients linked, as after a sync; that
     * runs after the change, as it reads every NEVO food and the write lock is everyone's.
     */
    private fun changeAdded(ids: Collection<Long>, added: Boolean, inRotation: Boolean) {
        store.writing {
            val rows = ids.distinct().map(::known)
            if (added) {
                if (!inRotation) rows.forEach(::requireNotRoutine)
                val now = Instant.now()
                rows.forEach { store.addToMine(it.id, addedAt = now, inRotation = inRotation) }
            } else {
                val mine = rows.filter { it.addedAt != null }
                mine.forEach(::requireRemovable)
                mine.forEach { store.removeFromMine(it.id) }
            }
        }
        if (added) ingredients.autoLink()
    }

    /** Puts [ids], all of them mine, in the rotation or takes them out; none when one cannot be ([requireNotRoutine]). */
    private fun changeRotation(ids: Collection<Long>, inRotation: Boolean) = store.writing {
        val rows = ids.distinct().map(::requireMine)
        if (!inRotation) rows.forEach(::requireNotRoutine)
        rows.forEach { store.setInRotation(it.id, inRotation) }
    }

    /**
     * Refuses to put [row] back in the bank while the plan or the routines use it: the shopping list would
     * go on buying for a recipe the account no longer has, as for [delete]. A recipe made in the app has no
     * bank to go back to: it is deleted ([delete]), which putting back never does on its own. Nor has a
     * taken one saved in the app since (issue 340): in the bank the next sync would make it the site's file
     * again, and what was changed would be lost without a word.
     */
    private fun requireRemovable(row: Recipe) {
        if (row.source != RecipeSource.COOKING_SITE.name) {
            throw ConflictException("\"${row.title}\" was made in the app, so it has no bank to go back to: delete it instead")
        }
        if (row.editedAt != null) {
            throw ConflictException("\"${row.title}\" was changed in the app, so it is no longer the cooking site's: delete it instead")
        }
        val days = plannedDays(row.id)
        if (days.isNotEmpty()) {
            throw ConflictException("\"${row.title}\" is planned on ${days.joinToString(", ", transform = ::dayName)}: choose another recipe there first")
        }
        requireNotRoutine(row)
    }

    /** The planner pins a routine only from the rotation, so a routine stays in it until another is chosen. */
    private fun requireNotRoutine(row: Recipe) {
        val routines = routines()
        val slot = when (row.id) {
            routines.breakfast -> "breakfast"
            routines.snack -> "snack"
            else -> return
        }
        throw ConflictException("\"${row.title}\" is your $slot routine: choose another routine first")
    }

    /**
     * Deletes one of my recipes ([requireMine]), also one taken from the cooking site: the site's file is then in the bank
     * again after the next sync, as a file the account never took. A bank recipe is the site's and is not deleted (409).
     * The route deletes its picture too ([RecipePictures.forget]).
     */
    fun delete(id: Long): Unit = store.writing {
        requireMine(id)
        val days = plannedDays(id)
        if (days.isNotEmpty()) {
            throw ConflictException("Planned on ${days.joinToString(", ", transform = ::dayName)}: choose another recipe there first")
        }
        store.deleteMine(id)
    }

    /**
     * The days the recipe is planned in the current week or a later one, as a meal or as a side next to one
     * (issue 234). The plan keeps a meal by its stored title, so deleting the recipe would leave it in the
     * plan while the shopping list stopped buying for it; weeks that are over are history and do not count.
     */
    private fun plannedDays(id: Long): List<LocalDate> = store.plannedDays(id, after = today().minusDays(7)).distinct().sorted()

    private fun dayName(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH))

    /** The titles of my recipes, for Claude not to write one of them again. */
    fun allTitles(): List<String> = store.mine().map { it.title }

    fun routines(): Routines = store.routines()

    /**
     * Pins the breakfast and snack routines. A newly pinned recipe must be one of mine ([requireMine]) and
     * goes in the rotation, from which the planner pins it. One pinned already is kept as it is, so that a
     * released app saving the routines it read is not refused for a pin from before the bank.
     */
    fun saveRoutines(routines: Routines): Routines = store.writing {
        val pinned = routines()
        val ids = listOfNotNull(routines.breakfast, routines.snack)
        ids.forEach { store.byId(it) ?: throw NotFoundException("No recipe with id $it") }
        val new = ids.filter { it != pinned.breakfast && it != pinned.snack }.map(::requireMine)
        new.forEach { store.setInRotation(it.id, true) }
        store.saveRoutines(routines)
        routines
    }
}
