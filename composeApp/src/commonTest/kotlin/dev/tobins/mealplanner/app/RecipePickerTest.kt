package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class RecipePickerTest {
    private fun recipe(
        id: Long,
        title: String,
        slots: List<MealSlot> = emptyList(),
        tags: List<String> = emptyList(),
        kind: RecipeKind = RecipeKind.MEAL,
        plannable: Boolean = true,
        inRotation: Boolean = true,
        added: Boolean = true,
    ) = RecipeSummary(id, title, "", tags, 4, RecipeSource.MANUAL, kind, plannable, mealSlots = slots, added = added, inRotation = inRotation)

    private val recipes = listOf(
        recipe(1, "Oats", listOf(MealSlot.BREAKFAST)),
        recipe(2, "Stew", listOf(MealSlot.DINNER), tags = listOf("winter")),
        recipe(3, "lasagne"),
        recipe(4, "Chilli", listOf(MealSlot.DINNER, MealSlot.LUNCH), tags = listOf("Spicy")),
        recipe(5, "Peanut sauce", kind = RecipeKind.COMPONENT),
        recipe(6, "Draft", plannable = false),
        // Mine but outside the rotation, and one that is only in the bank.
        recipe(7, "Fish pie", listOf(MealSlot.DINNER), tags = listOf("winter"), inRotation = false),
        recipe(8, "Pancakes", listOf(MealSlot.BREAKFAST), inRotation = false),
        recipe(9, "Winter curry", listOf(MealSlot.DINNER), tags = listOf("winter"), added = false, inRotation = false),
    )

    @Test
    fun onlyMealsThatCanBePlannedAreOfferedThoseForTheSlotFirst() {
        assertEquals(listOf("Chilli", "Stew", "lasagne", "Oats"), pickableRecipes(recipes, MealSlot.DINNER, "").rotation.map { it.title })
    }

    @Test
    fun theRotationComesBeforeTheRestOfMyRecipesEachWithTheSlotFirst() {
        val shown = pickableRecipes(recipes, MealSlot.DINNER, "")
        assertEquals(listOf("Chilli", "Stew", "lasagne", "Oats"), shown.rotation.map { it.title })
        assertEquals(listOf("Fish pie", "Pancakes"), shown.others.map { it.title })
        assertEquals(shown.rotation + shown.others, shown.all)
        // For breakfast the pancakes are the one marked for it among the rest.
        assertEquals(listOf("Pancakes", "Fish pie"), pickableRecipes(recipes, MealSlot.BREAKFAST, "").others.map { it.title })
    }

    @Test
    fun aRecipeOnlyInTheBankIsNeverOffered() {
        assertEquals(emptyList(), pickableRecipes(recipes, MealSlot.DINNER, "").all.filter { it.title == "Winter curry" })
        assertEquals(emptyList(), pickableRecipes(recipes, MealSlot.DINNER, "curry").all)
    }

    @Test
    fun theSearchMatchesATitleOrATagWithoutRegardToCase() {
        assertEquals(listOf("Stew"), pickableRecipes(recipes, MealSlot.DINNER, " STE ").all.map { it.title })
        assertEquals(listOf("Chilli"), pickableRecipes(recipes, MealSlot.DINNER, "spicy").all.map { it.title })
        assertEquals(emptyList(), pickableRecipes(recipes, MealSlot.DINNER, "pizza").all)
    }

    @Test
    fun theSearchFindsRecipesInBothGroups() {
        // "winter" is a tag of the stew (in the rotation) and of the fish pie (not): both are found, the rotation one first.
        val shown = pickableRecipes(recipes, MealSlot.DINNER, "winter")
        assertEquals(listOf("Stew"), shown.rotation.map { it.title })
        assertEquals(listOf("Fish pie"), shown.others.map { it.title })
    }
}
