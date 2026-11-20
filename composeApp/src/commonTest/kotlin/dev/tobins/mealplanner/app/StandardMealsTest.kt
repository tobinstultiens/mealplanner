package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.ClosedSlot
import dev.tobins.mealplanner.api.MealPrep
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PrepMeal
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.StandardMeal
import dev.tobins.mealplanner.api.StandardMeals
import dev.tobins.mealplanner.api.StandardSwitch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the app makes of standard meals (issue 265): which are offered where, what a chip says, and the picker's groups. */
class StandardMealsTest {
    private val t = Strings.English

    private fun meal(id: Long, title: String, slot: MealSlot, kcal: Double = 600.0, missing: List<String> = emptyList()) =
        StandardMeal(id, title, slot, 1.25, kcal, 30.0, 5.0, missing)

    private val offer = StandardMeals(
        "2026-11-16",
        meals = listOf(meal(1, "Eggs on bread", MealSlot.LUNCH), meal(1, "Eggs on bread", MealSlot.SNACK), meal(2, "Yoghurt bowl", MealSlot.SNACK)),
        closed = listOf(ClosedSlot(2, MealSlot.LUNCH, "This meal was eaten already")),
    )

    @Test
    fun aSlotIsOfferedTheStandardMealsSetForItUnlessNoneCanTakeItsPlace() {
        assertEquals(listOf("Eggs on bread"), standardChoices(offer, 1, MealSlot.LUNCH).map { it.title }, "not the yoghurt bowl, set for snack only")
        assertEquals(listOf("Eggs on bread", "Yoghurt bowl"), standardChoices(offer, 1, MealSlot.SNACK).map { it.title })
        assertEquals(listOf("Yoghurt bowl"), standardChoices(offer, 1, MealSlot.SNACK, skipped = 1).map { it.title }, "not the recipe just skipped")
        assertEquals(emptyList(), standardChoices(offer, 2, MealSlot.LUNCH), "Wednesday's lunch is closed")
        assertEquals(emptyList(), standardChoices(offer, 1, MealSlot.DINNER))
        assertEquals(emptyList(), standardChoices(null, 1, MealSlot.LUNCH), "a server before standard meals offers none")
    }

    @Test
    fun aChipSaysItsCaloriesOnOneLineAndWhatIsNotAtHome() {
        assertEquals("Eggs on bread · 553 kcal", meal(1, "Eggs on bread", MealSlot.LUNCH, kcal = 552.5).chipText(t))
        assertNull(meal(1, "Eggs on bread", MealSlot.LUNCH).missingText(t))
        assertEquals("no eggs at home", meal(1, "Eggs on bread", MealSlot.LUNCH, missing = listOf("Eggs")).missingText(t))
        assertEquals("no eggs or feta at home", meal(1, "x", MealSlot.LUNCH, missing = listOf("Eggs", "Feta")).missingText(t))
        assertEquals("no eggs, feta or bread at home", meal(1, "x", MealSlot.LUNCH, missing = listOf("Eggs", "Feta", "Bread")).missingText(t))
        assertEquals("geen eieren in huis", meal(1, "x", MealSlot.LUNCH, missing = listOf("Eieren")).missingText(Strings.Dutch))
    }

    @Test
    fun theSwitchSaysWhatMarkingMadeStaplesAndWhatItCouldNot() {
        val detail = RecipeDetail(recipe(1, "Eggs on bread"), emptyList(), "", emptyList())
        assertEquals(
            listOf("Now staples: Eggs, Bread", "Not linked to an ingredient, so not a staple: jam"),
            standardReport(StandardSwitch(detail, listOf("Eggs", "Bread"), listOf("jam")), t),
        )
        assertEquals(listOf("Its ingredients were staples already"), standardReport(StandardSwitch(detail), t))
    }

    private fun prepped(leftover: Boolean, status: MealStatus, eaten: Boolean) = PlannedMeal(
        1, MealSlot.LUNCH, 4, "Chicken", 1.0, 600.0, 40.0, leftover = leftover, locked = true, status = status,
        prep = MealPrep(1, "2026-11-16", "2026-11-19", false, listOf(PrepMeal("2026-11-16", MealSlot.DINNER, 1.0, if (eaten) MealStatus.EATEN else MealStatus.PLANNED))),
    )

    @Test
    fun onlyAPlannedLaterMealOfABatchStillToMakeSaysChoosingMakesTheBatchSmaller() {
        assertTrue(shrinksPrep(prepped(leftover = true, MealStatus.PLANNED, eaten = false)))
        assertFalse(shrinksPrep(prepped(leftover = true, MealStatus.SKIPPED, eaten = false)), "a skipped one's share stays in the box")
        assertFalse(shrinksPrep(prepped(leftover = true, MealStatus.PLANNED, eaten = true)), "a made batch's meal stays")
        assertFalse(shrinksPrep(prepped(leftover = false, MealStatus.PLANNED, eaten = false)), "the meal that makes it stays")
        assertFalse(shrinksPrep(PlannedMeal(1, MealSlot.LUNCH, 4, "Chicken", 1.0, 600.0, 40.0)))
        assertFalse(shrinksPrep(null))
    }

    private fun recipe(id: Long, title: String, inRotation: Boolean = true) =
        RecipeSummary(id, title, "", emptyList(), 2, RecipeSource.MANUAL, RecipeKind.MEAL, true, mealSlots = listOf(MealSlot.LUNCH), inRotation = inRotation)

    @Test
    fun thePickerListsTheStandardMealsForTheSlotFirstUnderTheirOwnHeading() {
        val recipes = listOf(recipe(1, "Eggs on bread", inRotation = false), recipe(2, "Stew"), recipe(3, "Fish pie", inRotation = false), recipe(4, "Toast"))
        val shown = pickableRecipes(recipes, MealSlot.LUNCH, "", standard = setOf(1L, 4L))
        assertEquals(listOf("Eggs on bread", "Toast"), shown.standard.map { it.title }, "out of the rotation or in it")
        assertEquals(listOf("Eggs on bread", "Toast", "Stew", "Fish pie"), shown.all.map { it.title })
        assertTrue(shown.headings)
        // Without standard meals the picker is as before: no heading with one group.
        assertFalse(pickableRecipes(recipes.filter { it.inRotation }, MealSlot.LUNCH, "").headings)
        assertEquals(listOf("Toast"), pickableRecipes(recipes, MealSlot.LUNCH, "toa", standard = setOf(1L, 4L)).all.map { it.title })
    }
}
