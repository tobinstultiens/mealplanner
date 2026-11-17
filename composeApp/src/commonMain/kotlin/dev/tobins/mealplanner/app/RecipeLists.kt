package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary

/*
 * What the Recipes tab and the bank show of the recipes the server sent: which rows a choice, a
 * search and a section leave, and what a bulk action does with a selection. Kept apart from the
 * screens so that it is tested without drawing them.
 */

/** The choice above the list of my recipes: all of them, those the planner picks from, or those it leaves alone. */
enum class RotationChoice(val label: Msg) {
    ALL(Msg.FILTER_ALL),
    IN_ROTATION(Msg.FILTER_IN_ROTATION),
    OUT_OF_ROTATION(Msg.FILTER_OUT_OF_ROTATION),
    ;

    /** Whether [recipe] is one this choice shows. */
    fun shows(recipe: RecipeSummary): Boolean = when (this) {
        ALL -> true
        IN_ROTATION -> recipe.inRotation
        OUT_OF_ROTATION -> !recipe.inRotation
    }
}

/** Whether [this] matches what was typed in a search field: the title or a tag, without regard to case. */
internal fun RecipeSummary.matches(query: String): Boolean {
    val needle = query.trim()
    return needle.isEmpty() || title.contains(needle, ignoreCase = true) || tags.any { it.contains(needle, ignoreCase = true) }
}

/** The recipes "Meals only" and the search leave, before a choice picks among them. */
internal fun searched(recipes: List<RecipeSummary>, mealsOnly: Boolean, query: String): List<RecipeSummary> =
    recipes.filter { (!mealsOnly || it.kind == RecipeKind.MEAL) && it.matches(query) }

/** How many of [searched] recipes each choice shows, for the number next to its name. */
internal fun choiceCounts(searched: List<RecipeSummary>): Map<RotationChoice, Int> =
    RotationChoice.entries.associateWith { choice -> searched.count(choice::shows) }

/** How many of [mine] that match [query] "Meals only" leaves out, so that a recipe just added does not seem to have vanished. */
internal fun notMealsHidden(mine: List<RecipeSummary>, query: String): Int =
    mine.count { it.kind != RecipeKind.MEAL && it.matches(query) }

/** The sections of the cooking site that the bank's recipes are in, for its filter. */
internal fun bankSections(bank: List<RecipeSummary>): List<String> =
    bank.map { it.section }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }

/** The bank's recipes in [section] (all when null) that match [query]. */
internal fun bankShown(bank: List<RecipeSummary>, section: String?, query: String): List<RecipeSummary> =
    bank.filter { (section == null || it.section == section) && it.matches(query) }

/**
 * What "Remove from my recipes" does with a selection: only recipes from the cooking site have a bank to
 * go back to, so [ids] leaves out those made in the app, and [keptMadeInApp] counts them for the message.
 * The server refuses to put such a recipe back (it is deleted instead, on its own screen).
 */
internal data class Removal(val ids: List<Long>, val keptMadeInApp: Int)

internal fun removalOf(selected: List<RecipeSummary>): Removal {
    val (fromSite, madeHere) = selected.partition { it.source == RecipeSource.COOKING_SITE }
    return Removal(fromSite.map { it.id }, madeHere.size)
}

/** Whether the recipe can be put back in the bank (a cooking-site recipe), or is deleted instead. */
internal fun RecipeSummary.hasBank(): Boolean = source == RecipeSource.COOKING_SITE

/** How many ingredients of [recipes] are not linked to a food yet: what the user is told to link after adding. */
internal fun unlinkedIngredients(recipes: List<RecipeSummary>): Int =
    recipes.sumOf { recipe -> recipe.nutrition?.takeIf { !it.complete }?.uncounted?.size ?: 0 }

/** [recipes] with the recipe that [updated] names replaced by it, in place. */
internal fun List<RecipeSummary>.replacing(updated: RecipeSummary): List<RecipeSummary> =
    map { if (it.id == updated.id) updated else it }

/** Whether the planner has nothing to pick from: none of my recipes is a meal in the rotation. */
internal fun rotationIsEmpty(mine: List<RecipeSummary>): Boolean = mine.none { it.inRotation && it.kind == RecipeKind.MEAL }
