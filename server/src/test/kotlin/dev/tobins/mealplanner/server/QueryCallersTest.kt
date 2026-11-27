package dev.tobins.mealplanner.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which files of the server may call a query of the database (`db.planQueries`, `userQueries`, ...), as the code has it
 * today (docs/architecture.md, "Rules and SQL go in different classes"). A file outside the list that names a query fails
 * the test; so does an entry no file needs any more, so that the list only gets shorter. A feature that is split has its
 * store on the list and none of its rule classes; the others are on it as they are until they are split.
 *
 * It reads the source files for the name of a generated queries class (`planQueries`), outside comments, nothing more.
 */
class QueryCallersTest {
    private val allowed: Set<String> = setOf(
        "Application.kt",
        "Database.kt",
        "accounts/ProfileRepository.kt",
        "accounts/Registrations.kt",
        "accounts/UserRepository.kt",
        "ingredients/FoodRepository.kt",
        "ingredients/IngredientRepository.kt",
        "ingredients/StockRepository.kt",
        "plan/Batches.kt",
        "plan/MealAdjustments.kt",
        "plan/PlanStore.kt",
        "plan/PlanningRepository.kt",
        "plan/PrepRepository.kt",
        "plan/SlotFoods.kt",
        "prices/OfferExclusionRepository.kt",
        "prices/OfferRepository.kt",
        "prices/PriceHistoryRepository.kt",
        "prices/PriceRepository.kt",
        "recipes/RecipePictures.kt",
        "recipes/RecipeStore.kt",
        "recipes/RecipeToppings.kt",
        "shopping/ShoppingAdviceRepository.kt",
        "shopping/ShoppingStore.kt",
        "shopping/ShopsRepository.kt",
        "tracking/TrackingRepository.kt",
    )

    /** The classes with the plan's rules (#385): they go through [dev.tobins.mealplanner.server.plan.PlanStore], never a query. */
    private val planRules: Set<String> = setOf(
        "plan/Eating.kt",
        "plan/FoodChanges.kt",
        "plan/KeptMeals.kt",
        "plan/MealChanges.kt",
        "plan/WeekCosts.kt",
        "plan/WeekMeals.kt",
        "plan/WeekPlans.kt",
        "plan/WeekShortfalls.kt",
        "plan/WeekView.kt",
    )

    /** The classes with the recipes' rules (#386): they go through [dev.tobins.mealplanner.server.recipes.RecipeStore], never a query. */
    private val recipeRules: Set<String> = setOf(
        "recipes/BankSync.kt",
        "recipes/MyRecipes.kt",
        "recipes/RecipeCandidates.kt",
        "recipes/RecipeEdits.kt",
        "recipes/RecipeView.kt",
    )

    /** The classes with the shopping list's rules (#387): they go through [dev.tobins.mealplanner.server.shopping.ShoppingStore], never a query. */
    private val shoppingRules: Set<String> = setOf(
        "shopping/BuyingAhead.kt",
        "shopping/ListAdvice.kt",
        "shopping/OwnItems.kt",
        "shopping/ShoppingListView.kt",
        "shopping/Ticking.kt",
        "shopping/WeekNeeds.kt",
    )

    private val sourceRoot = File("src/main/kotlin/dev/tobins/mealplanner/server")
    private val query = Regex("\\b[a-z][A-Za-z]*Queries\\b")

    /** Every production file, by its path under the server's package, with whether it names a query outside a comment. */
    private fun callers(): Set<String> = sourceRoot.walkTopDown().filter { it.extension == "kt" }.filter { file ->
        file.readLines().map { it.trim() }.filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }.any { query.containsMatchIn(it) }
    }.mapTo(HashSet()) { it.relativeTo(sourceRoot).invariantSeparatorsPath }

    @Test
    fun onlyTheFilesOnTheListCallAQuery() {
        assertEquals(emptySet(), callers() - allowed, "files that call a query and are not on the list in QueryCallersTest")
    }

    @Test
    fun theListHasNoFileThatCallsNoQueryAnyMore() {
        assertEquals(emptySet(), allowed - callers(), "entries of the list that call no query: remove them")
    }

    @Test
    fun theRulesOfThePlanAreNotOnTheListAndHoldNoDatabase() = assertRules(planRules, "the plan's", "PlanStore")

    @Test
    fun theRulesOfTheRecipesAreNotOnTheListAndHoldNoDatabase() = assertRules(recipeRules, "the recipes'", "RecipeStore")

    @Test
    fun theRulesOfTheShoppingListAreNotOnTheListAndHoldNoDatabase() = assertRules(shoppingRules, "the shopping list's", "ShoppingStore")

    /** The files [rules], of the classes with [whose] rules, are there, are not on the list, and reach the database through [store] only. */
    private fun assertRules(rules: Set<String>, whose: String, store: String) {
        assertEquals(emptyList(), rules.filterNot { File(sourceRoot, it).isFile }, "files of $whose rules that are not there: rename them here too")
        assertEquals(emptySet(), rules intersect allowed, "a class with $whose rules may not call a query")
        val holding = rules.filter { File(sourceRoot, it).readText().contains("import dev.tobins.mealplanner.server.db.Database\n") }
        assertEquals(emptyList(), holding, "a class with $whose rules reaches the database only through $store")
    }
}
