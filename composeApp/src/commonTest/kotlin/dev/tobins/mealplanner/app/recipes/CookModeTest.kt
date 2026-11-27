package dev.tobins.mealplanner.app.recipes

import dev.tobins.mealplanner.api.MealSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CookModeTest {
    @Test
    fun aNumberedListIsTheSteps() {
        val steps = cookSteps("Prepare everything first.\n\n1. Brown the chicken.\n2. Add the milk.\n3) Simmer.\n\nServe with rice.")
        assertEquals(listOf("Brown the chicken.", "Add the milk.", "Simmer. Serve with rice."), steps)
    }

    @Test
    fun aParagraphRightAfterAnItemBelongsToIt() {
        assertEquals(listOf("Chop. Keep the skins.", "Fry."), cookSteps("1. Chop.\n\nKeep the skins.\n\n2. Fry."))
    }

    @Test
    fun withoutANumberedListEveryLineIsAStep() {
        val steps = cookSteps("## Method\n\nSteps:\n\nBrown the chicken in a pan.\nKeep the heat high.\n\nAdd the milk.\n\n- a bullet")
        assertEquals(listOf("Brown the chicken in a pan.", "Keep the heat high.", "Add the milk."), steps)
    }

    @Test
    fun fourStepsTypedOnePerLineAreFourSteps() {
        val typed = "Chop the onion\nFry it in butter\nAdd the rice and water\nSimmer for 15 minutes"
        assertEquals(4, cookSteps(typed).size)
        assertEquals("Add the rice and water", cookSteps(typed)[2])
    }

    @Test
    fun noInstructionsMeansNoSteps() {
        assertEquals(emptyList(), cookSteps(""))
        assertEquals(emptyList(), cookSteps("  \n\n"))
    }

    @Test
    fun theFirstStepNotDoneIsTheCurrentOne() {
        var progress = CookProgress()
        assertEquals(0, progress.currentStep(3))
        progress = progress.toggleStep(0)
        assertEquals(1, progress.currentStep(3))
        progress = progress.toggleStep(2).toggleStep(1)
        assertNull(progress.currentStep(3))
        // Taking a step back makes it the current one again.
        assertEquals(1, progress.toggleStep(1).currentStep(3))
    }

    @Test
    fun anIngredientIsTickedAndUntickedByTappingItAgain() {
        val ticked = CookProgress().toggleIngredient(2).toggleIngredient(0)
        assertEquals(setOf(0, 2), ticked.ingredients)
        assertEquals(setOf(2), ticked.toggleIngredient(0).ingredients)
        assertEquals(emptySet(), ticked.steps)
    }

    @Test
    fun theTicksSurviveBeingSaved() {
        val saver = CookProgress.Saver
        val scope = androidx.compose.runtime.saveable.SaverScope { true }
        val progress = CookProgress(setOf(1, 4), setOf(0))
        assertEquals(progress, saver.restore(with(saver) { scope.save(progress)!! }))
        val none = CookProgress()
        assertEquals(none, saver.restore(with(saver) { scope.save(none)!! }))
    }

    @Test
    fun theMealSurvivesBeingSavedWithItsPlace() {
        val saver = MealToCook.Saver
        val scope = androidx.compose.runtime.saveable.SaverScope { true }
        val meal = MealToCook(4, 0.75, 1.5, skipped = false, place = MealPlace("2026-11-16", 2, MealSlot.DINNER), planned = true)
        assertEquals(meal, saver.restore(with(saver) { scope.save(meal)!! }))
        val fromList = MealToCook(4, 0.75, 1.5, skipped = true)
        assertEquals(fromList, saver.restore(with(saver) { scope.save(fromList)!! }))
    }
}
