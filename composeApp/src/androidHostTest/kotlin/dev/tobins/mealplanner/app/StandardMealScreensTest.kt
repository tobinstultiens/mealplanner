package dev.tobins.mealplanner.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Standard meals on the screens (issue 265), against the fake backend on its Wednesday: the chips under a skipped meal on
 * Today, the Plan's skip sheet, the picker with them first, and the recipe's switch.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StandardMealScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var backend = FakeBackend(standardMeals = true)
    private var api = ApiClient(backend.settings)

    @Before
    fun fixToday() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        appClock = object : Clock {
            override fun now(): Instant = Instant.parse("${FakeBackend.TODAY}T10:30:00Z")
        }
    }

    @After
    fun tearDown() {
        api.close()
        backend.close()
        appClock = Clock.System
    }

    /** Uses a fake backend of its own: [standardMeals] false is a server from before them; [prepped] has a batch still to make. */
    private fun useBackend(standardMeals: Boolean, prepped: Boolean = false) {
        api.close()
        backend.close()
        backend = FakeBackend(standardMeals = standardMeals, prepped = prepped)
        api = ApiClient(backend.settings)
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) =
        compose.setContent { CompositionLocalProvider(LocalStrings provides Strings.English) { content() } }

    private fun waitFor(text: String) =
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    /** The calls that put a standard meal in a slot, as sent. */
    private fun chosen() = backend.changes.filter { it.contains("/standard ") && it.startsWith("PUT /api/plans/") }

    // --- Today

    @Test
    fun aSkippedMealOnTodayOffersTheStandardMealsForItsSlotAndOneTapEatsIt() {
        show { TodayScreen(api, { _, _ -> }, {}) }
        waitFor("Instead, one of my standard meals:")
        assertEquals(1, count("Tuna pasta salad · 610"))
        assertEquals(1, count("no eggs at home"), "the chip says what is out, and can still be chosen")
        // One place takes them: the skipped lunch, not the eaten breakfast or the dinner still to eat.
        assertEquals(1, count("Instead, one of my standard meals:"))

        compose.onNodeWithText("Shakshuka · 700", substring = true).performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { chosen().isNotEmpty() }
        assertEquals("PUT /api/plans/${FakeBackend.WEEK_START}/meals/2/LUNCH/standard {\"recipeId\":6,\"eaten\":true}", chosen().single())
        waitFor("Back among the staples, to restock: Eggs")
    }

    @Test
    fun theChipsWaitWhileOneIsBeingPlacedSoADoubleTapSendsOneRequest() {
        val answer = CountDownLatch(1)
        backend.standardChoiceAnswer = answer
        show { TodayScreen(api, { _, _ -> }, {}) }
        waitFor("Instead, one of my standard meals:")
        compose.onNodeWithText("Shakshuka · 700", substring = true).performScrollTo().performClick()
        // The first tap is still being sent (the fake holds its answer): both chips are off, and a second tap goes nowhere.
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(hasText("Shakshuka · 700", substring = true) and isNotEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Shakshuka · 700", substring = true).assertIsNotEnabled()
        compose.onNodeWithText("Tuna pasta salad · 610", substring = true).assertIsNotEnabled()
        compose.onNodeWithText("Shakshuka · 700", substring = true).performClick()
        answer.countDown()
        waitFor("Back among the staples, to restock: Eggs")
        assertEquals(1, chosen().size, "one request, so no 409 and no failure snackbar")
    }

    @Test
    fun aSkippedMealOfADayToComeIsPutThereStillToEat() {
        show { TodayScreen(api, { _, _ -> }, {}) }
        waitFor("Instead, one of my standard meals:")
        // Thursday's dinner is skipped in the fake week.
        compose.onNodeWithContentDescription("Next day").performClick()
        waitFor("Thu 19 Nov")
        waitFor("Instead, one of my standard meals:")
        compose.onNodeWithText("Tuna pasta salad · 610", substring = true).performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { chosen().isNotEmpty() }
        assertEquals("PUT /api/plans/${FakeBackend.WEEK_START}/meals/3/DINNER/standard {\"recipeId\":3,\"eaten\":false}", chosen().single())
    }

    @Test
    fun withoutStandardMealsASkippedMealIsAsBefore() {
        useBackend(standardMeals = false)
        show { TodayScreen(api, { _, _ -> }, {}) }
        compose.onNodeWithContentDescription("Next day").performClick()
        waitFor("Skipped")
        // The app asked, and the older server did not know the route.
        compose.waitUntil(timeoutMillis = 10_000) { backend.requests.any { it.endsWith("/standard-meals") } }
        assertEquals(0, count("Instead, one of my standard meals:"))
    }

    // --- The Plan

    /** Shows the Plan of [backend], and once it is all there the menu of the [slot] meal ("lunch", "dinner") of the day headed [day]. */
    private fun openMenuOnPlan(day: String, slot: String, prepped: Boolean = false) {
        show { PlanScreen(api, { _, _ -> }, {}, {}, {}) }
        // The week of a prep has the card of the share in the box above the days.
        compose.waitForPlan(boxCard = prepped)
        compose.openSwapMenu(day, slot)
    }

    @Test
    fun theSkipSheetLeavesOutTheRecipeThatWasSkipped() {
        // Tuesday's lunch is the tuna pasta salad, itself a standard meal for lunch: the sheet offers the shakshuka only.
        openMenuOnPlan("Tue 17 Nov", "lunch")
        compose.onNodeWithText("Not eating this").performClick()
        waitFor("Eat one of your standard meals instead?")
        assertEquals(1, count("Shakshuka · 700"))
        assertEquals(0, count("Tuna pasta salad · 610"))
    }

    @Test
    fun aStandardMealIsTakenOutOfThePlanOnceTheUserConfirms() {
        // Tuesday's lunch is the tuna pasta salad, a standard meal the planner put there as far as the app can tell (the plan
        // does not say who did): removing it asks first. It is what gives a skipped prepped meal its slot back.
        openMenuOnPlan("Tue 17 Nov", "lunch")
        // The entry is there once the week's standard meals are, which the menu does not wait for.
        waitFor("Remove from the plan")
        compose.onNodeWithText("Remove from the plan").performClick()
        waitFor("Remove Tuna pasta salad from the plan?")
        assertTrue(backend.changes.none { it.startsWith("DELETE") }, "nothing is removed before the user confirms")
        compose.onNodeWithText("Remove").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { backend.changes.any { it == "DELETE /api/plans/${FakeBackend.WEEK_START}/meals/1/LUNCH" } }
    }

    @Test
    fun cancellingTheQuestionKeepsTheMealInThePlan() {
        openMenuOnPlan("Tue 17 Nov", "lunch")
        waitFor("Remove from the plan")
        compose.onNodeWithText("Remove from the plan").performClick()
        waitFor("Remove Tuna pasta salad from the plan?")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { count("Remove Tuna pasta salad from the plan?") == 0 }
        compose.waitUntilPlanIsIdle()
        assertTrue(backend.changes.none { it.startsWith("DELETE") })
    }

    @Test
    fun aMealOfAnotherRecipeHasNoEntryToTakeItOut() {
        // The tuna pasta salad's menu has the entry once the week's standard meals are in; the chicken's has none then.
        openMenuOnPlan("Tue 17 Nov", "lunch")
        waitFor("Remove from the plan")
        // Closes the menu; the screen opens nothing here.
        compose.onNodeWithText("Adjust what was in it").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { count("Remove from the plan") == 0 }
        compose.openSwapMenu("Mon 16 Nov", "dinner")
        assertEquals(0, count("Remove from the plan"), "the chicken thighs are no standard meal")
    }

    @Test
    fun skippingAMealOnThePlanShowsTheStandardMealsAtOnceAndOneTapPutsOneThere() {
        openMenuOnPlan("Mon 16 Nov", "dinner")
        compose.onNodeWithText("Not eating this").performClick()
        waitFor("Eat one of your standard meals instead?")
        assertTrue(backend.changes.any { it.contains("/meals/0/DINNER/status") && it.contains("SKIPPED") })
        assertEquals(1, count("no eggs at home"))

        compose.onNodeWithText("Tuna pasta salad · 610", substring = true).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { chosen().isNotEmpty() }
        assertEquals("PUT /api/plans/${FakeBackend.WEEK_START}/meals/0/DINNER/standard {\"recipeId\":3,\"eaten\":false}", chosen().single())
        compose.waitUntil(timeoutMillis = 10_000) { count("Eat one of your standard meals instead?") == 0 }
    }

    @Test
    fun aSlotNoStandardMealCanTakeGetsNoSkipSheet() {
        useBackend(standardMeals = true, prepped = true)
        // Monday's dinner makes the prep: the server lists it as closed, so skipping it offers nothing.
        openMenuOnPlan("Mon 16 Nov", "dinner", prepped = true)
        compose.onNodeWithText("Not eating this").performClick()
        // Done once the meals can be tapped again: by then the standard meals were asked for again and the sheet would show.
        compose.waitUntilPlanIsIdle()
        assertTrue(backend.changes.any { it.contains("/meals/0/DINNER/status") })
        assertTrue(backend.requests.count { it.endsWith("/standard-meals") } >= 2, "asked again after the skip")
        assertEquals(0, count("Eat one of your standard meals instead?"))
    }

    @Test
    fun leavingTheSkipSheetKeepsTheSkip() {
        openMenuOnPlan("Mon 16 Nov", "dinner")
        compose.onNodeWithText("Not eating this").performClick()
        waitFor("Leave it empty")
        compose.onNodeWithText("Leave it empty").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { count("Eat one of your standard meals instead?") == 0 }
        assertEquals(emptyList(), chosen())
    }

    @Test
    fun withoutStandardMealsSkippingOnThePlanIsAsBefore() {
        useBackend(standardMeals = false)
        openMenuOnPlan("Mon 16 Nov", "dinner")
        compose.onNodeWithText("Not eating this").performClick()
        compose.waitUntilPlanIsIdle()
        assertTrue(backend.changes.any { it.contains("/status") })
        assertEquals(0, count("Eat one of your standard meals instead?"), "with none for the slot the skip is as it was")
    }

    @Test
    fun thePickerListsTheStandardMealsFirstAndPutsOneInByItsOwnRoute() {
        openMenuOnPlan("Mon 16 Nov", "dinner")
        compose.onNodeWithText("Choose a recipe…").performClick()
        // Their order is `pickableRecipes`' (StandardMealsTest); here, that they are there under their heading.
        waitFor("My standard meals")
        val heading = compose.onNodeWithText("My standard meals").fetchSemanticsNode().boundsInRoot.top
        assertTrue(compose.onNodeWithText("Shakshuka").fetchSemanticsNode().boundsInRoot.top > heading)
        assertEquals(0, count("This meal is from a prepped batch"), "Monday's dinner is no prepped meal")

        compose.onNodeWithText("Shakshuka").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { chosen().isNotEmpty() }
        assertEquals("PUT /api/plans/${FakeBackend.WEEK_START}/meals/0/DINNER/standard {\"recipeId\":6,\"eaten\":false}", chosen().single())
    }

    @Test
    fun thePickerSaysWhenChoosingMakesAPrepStillToMakeSmaller() {
        useBackend(standardMeals = true, prepped = true)
        // Tuesday's lunch is a later meal of the chicken thighs, a batch no meal of which was eaten yet.
        openMenuOnPlan("Tue 17 Nov", "lunch", prepped = true)
        compose.onNodeWithText("Choose a recipe…").performClick()
        waitFor("This meal is from a prepped batch still to make: another recipe here makes the batch smaller.")
    }

    @Test
    fun standardMealsThatComeAfterThePickerOpenedShowAtItsTop() {
        val answer = CountDownLatch(1)
        backend.standardMealsAnswer = answer
        openMenuOnPlan("Mon 16 Nov", "dinner")
        compose.onNodeWithText("Choose a recipe…").performClick()
        waitFor("In my rotation")
        assertEquals(0, count("My standard meals"))
        // They go above the recipes shown so far: the list moves to show them rather than keep those in view.
        answer.countDown()
        waitFor("My standard meals")
        compose.onNodeWithText("My standard meals").assertIsDisplayed()
    }

    // --- The recipe's switch

    @Test
    fun markingARecipeAStandardMealSaysWhatItMadeStaples() {
        show { RecipeDetailScreen(api, 4, null, onBack = {}, onEdit = {}) }
        waitFor("Standard meal: I can always make this")
        // A tap on the words toggles it, as the whole line is the switch.
        compose.onNode(isToggleable() and hasText("Standard meal: I can always make this")).performScrollTo().performClick()
        waitFor("Now staples: Chicken thigh fillet, Spinach")
        waitFor("Not linked to an ingredient, so not a staple: juiced")
        assertTrue(backend.changes.any { it == "PUT /api/recipes/4/standard {\"standard\":true}" }, backend.changes.toString())
    }

    @Test
    fun theRotationsLineTogglesFromItsWordsToo() {
        show { RecipeDetailScreen(api, 4, null, onBack = {}, onEdit = {}) }
        waitFor("In my rotation")
        compose.onNode(isToggleable() and hasText("In my rotation")).performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { backend.changes.any { it == "PUT /api/recipes/4/rotation {\"inRotation\":false}" } }
    }

    @Test
    fun aBuildingBlockHasNoStandardMealSwitch() {
        show { RecipeDetailScreen(api, 7, null, onBack = {}, onEdit = {}) }
        waitFor("Ingredients")
        assertEquals(0, count("Standard meal: I can always make this"))
    }
}
