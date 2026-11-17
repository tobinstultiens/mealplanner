package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeNutrition
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecipeListsTest {
    private fun recipe(
        id: Long,
        title: String,
        inRotation: Boolean = true,
        kind: RecipeKind = RecipeKind.MEAL,
        source: RecipeSource = RecipeSource.COOKING_SITE,
        section: String = "Dinner",
        tags: List<String> = emptyList(),
        uncounted: List<String>? = null,
    ) = RecipeSummary(
        id, title, section, tags, 2, source, kind, plannable = true,
        nutrition = uncounted?.let { RecipeNutrition(Nutrition(500.0, 30.0, 20.0, 5.0, 50.0, 6.0, 8.0, 600.0), complete = it.isEmpty(), approximate = false, uncounted = it) },
        inRotation = inRotation,
    )

    private val mine = listOf(
        recipe(1, "Oats", inRotation = true, section = "Breakfast", tags = listOf("quick")),
        recipe(2, "Stew", inRotation = true),
        recipe(3, "Tuna salad", inRotation = false),
        recipe(4, "Peanut sauce", inRotation = true, kind = RecipeKind.COMPONENT, source = RecipeSource.MANUAL),
        recipe(5, "Draft", inRotation = false, kind = RecipeKind.COMPONENT),
    )

    @Test
    fun eachChoiceShowsItsRecipesAndAllShowsEvery() {
        assertEquals(listOf(1L, 2L, 4L), mine.filter(RotationChoice.IN_ROTATION::shows).map { it.id })
        assertEquals(listOf(3L, 5L), mine.filter(RotationChoice.OUT_OF_ROTATION::shows).map { it.id })
        assertEquals(5, mine.count(RotationChoice.ALL::shows))
    }

    @Test
    fun mealsOnlyAndTheSearchWorkWithinTheChoiceAndItsNumbers() {
        val meals = searched(mine, mealsOnly = true, query = "")
        assertEquals(listOf(1L, 2L, 3L), meals.map { it.id })
        assertEquals(
            mapOf(RotationChoice.ALL to 3, RotationChoice.IN_ROTATION to 2, RotationChoice.OUT_OF_ROTATION to 1),
            choiceCounts(meals),
        )
        // Without "Meals only" the sauce and the draft count too.
        assertEquals(
            mapOf(RotationChoice.ALL to 5, RotationChoice.IN_ROTATION to 3, RotationChoice.OUT_OF_ROTATION to 2),
            choiceCounts(searched(mine, mealsOnly = false, query = "")),
        )
        // A search matches the title or a tag, and the numbers follow it.
        assertEquals(listOf(1L), searched(mine, true, " QUICK ").map { it.id })
        assertEquals(mapOf(RotationChoice.ALL to 1, RotationChoice.IN_ROTATION to 0, RotationChoice.OUT_OF_ROTATION to 1), choiceCounts(searched(mine, true, "tuna")))
    }

    @Test
    fun theBankIsFilteredBySectionAndSearch() {
        val bank = listOf(
            recipe(10, "Pancakes", section = "Breakfast", tags = listOf("quick")),
            recipe(11, "Miso aubergine", section = "Dinner"),
            recipe(12, "Soba salad", section = "Lunch"),
            recipe(13, "Harissa stew", section = "Dinner", tags = listOf("spicy")),
            recipe(14, "Unsorted", section = ""),
        )
        assertEquals(listOf("Breakfast", "Dinner", "Lunch"), bankSections(bank))
        assertEquals(listOf(11L, 13L), bankShown(bank, "Dinner", "").map { it.id })
        assertEquals(listOf(13L), bankShown(bank, "Dinner", "SPICY").map { it.id })
        assertEquals(listOf(10L), bankShown(bank, null, "quick").map { it.id })
        assertEquals(5, bankShown(bank, null, " ").size)
    }

    @Test
    fun removingFromMyRecipesLeavesOutThoseMadeInTheAppAndCountsThem() {
        val selected = listOf(
            recipe(1, "From the site"),
            recipe(2, "Written here", source = RecipeSource.MANUAL),
            recipe(3, "Generated", source = RecipeSource.GENERATED),
            recipe(4, "Also from the site"),
        )
        assertEquals(Removal(listOf(1L, 4L), keptMadeInApp = 2), removalOf(selected))
        // Only recipes made in the app: nothing is sent at all.
        assertEquals(Removal(emptyList(), 1), removalOf(selected.filter { it.id == 2L }))
        assertTrue(selected[0].hasBank())
        assertFalse(selected[1].hasBank())
    }

    @Test
    fun theIngredientsStillToLinkAreCountedOverTheAddedRecipes() {
        val added = listOf(
            recipe(1, "Complete", uncounted = emptyList()),
            recipe(2, "Two to link", uncounted = listOf("harissa", "preserved lemon")),
            recipe(3, "No numbers at all"),
            recipe(4, "One to link", uncounted = listOf("miso")),
        )
        assertEquals(3, unlinkedIngredients(added))
        assertEquals(0, unlinkedIngredients(added.take(1)))
    }

    @Test
    fun theRotationIsEmptyWhenNoMealOfMineIsInIt() {
        assertFalse(rotationIsEmpty(mine))
        assertTrue(rotationIsEmpty(emptyList()))
        // A sauce in the rotation is never picked, and a meal outside it neither.
        assertTrue(rotationIsEmpty(mine.filter { it.id in setOf(3L, 4L) }))
    }

    @Test
    fun anUpdatedRecipeReplacesItsRowInPlace() {
        val changed = mine[2].copy(inRotation = true)
        val after = mine.replacing(changed)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), after.map { it.id })
        assertTrue(after[2].inRotation)
        assertEquals(mine[0], after[0])
    }

    @Test
    fun aSelectionKeepsItsTicksAndKnowsWhenItIsOn() {
        val selection = Selection()
        assertFalse(selection.selecting)
        selection.start(first = 3)
        assertTrue(selection.selecting && selection.contains(3))
        selection.toggle(4)
        selection.toggle(3)
        assertEquals(setOf(4L), selection.ids)
        // Taking off the last tick is still selecting: the bar stays until it is closed.
        selection.toggle(4)
        assertTrue(selection.selecting)
        selection.selectAll(listOf(1, 2, 3))
        assertEquals(setOf(1L, 2L, 3L), selection.ids)
        selection.stop()
        assertFalse(selection.selecting)
        // A tick while not selecting changes nothing.
        selection.toggle(9)
        assertFalse(selection.contains(9))
    }
}
