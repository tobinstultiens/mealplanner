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
    ) = RecipeSummary(id, title, "", tags, 4, RecipeSource.MANUAL, kind, plannable, mealSlots = slots)

    private val recipes = listOf(
        recipe(1, "Oats", listOf(MealSlot.BREAKFAST)),
        recipe(2, "Stew", listOf(MealSlot.DINNER), tags = listOf("winter")),
        recipe(3, "lasagne"),
        recipe(4, "Chilli", listOf(MealSlot.DINNER, MealSlot.LUNCH), tags = listOf("Spicy")),
        recipe(5, "Peanut sauce", kind = RecipeKind.COMPONENT),
        recipe(6, "Draft", plannable = false),
    )

    @Test
    fun onlyMealsThatCanBePlannedAreOfferedThoseForTheSlotFirst() {
        assertEquals(listOf("Chilli", "Stew", "lasagne", "Oats"), pickableRecipes(recipes, MealSlot.DINNER, "").map { it.title })
    }

    @Test
    fun theSearchMatchesATitleOrATagWithoutRegardToCase() {
        assertEquals(listOf("Stew"), pickableRecipes(recipes, MealSlot.DINNER, " STE ").map { it.title })
        assertEquals(listOf("Chilli"), pickableRecipes(recipes, MealSlot.DINNER, "spicy").map { it.title })
        assertEquals(emptyList(), pickableRecipes(recipes, MealSlot.DINNER, "pizza"))
    }
}
