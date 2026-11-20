package dev.tobins.mealplanner.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone
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
        assertEquals(0, count("Instead, one of my standard meals:"))
        assertTrue(backend.requests.any { it.endsWith("/standard-meals") }, "the app asked, and the older server did not know the route")
    }

    // --- The Plan

    /** Opens the swap menu of the [slot] ("lunch", "dinner") of the day headed [label], the Plan's list item [item]. */
    private fun openMenuOf(label: String, slot: String, item: Int) {
        waitFor("Prep a recipe…")
        compose.onNode(hasScrollAction()).performScrollToIndex(item)
        waitFor(label)
        // The positions are read once the list has settled where it was scrolled to.
        compose.waitForIdle()
        val dayTop = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top
        val buttons = compose.onAllNodesWithContentDescription("Swap $slot").fetchSemanticsNodes()
        val own = buttons.withIndex().filter { it.value.boundsInRoot.top > dayTop }.minBy { it.value.boundsInRoot.top }.index
        compose.onAllNodesWithContentDescription("Swap $slot")[own].performClick()
        waitFor("Not eating this")
    }

    @Test
    fun theSkipSheetLeavesOutTheRecipeThatWasSkipped() {
        show { PlanScreen(api, { _, _ -> }, {}, {}, {}) }
        // Tuesday's lunch is the tuna pasta salad, itself a standard meal for lunch: the sheet offers the shakshuka only.
        openMenuOf("Tue 17 Nov", "lunch", item = 2)
        compose.onNodeWithText("Not eating this").performClick()
        waitFor("Eat one of your standard meals instead?")
        assertEquals(1, count("Shakshuka · 700"))
        assertEquals(0, count("Tuna pasta salad · 610"))
    }

    @Test
    fun aStandardMealCanBeTakenOutOfThePlan() {
        show { PlanScreen(api, { _, _ -> }, {}, {}, {}) }
        // Tuesday's lunch is the tuna pasta salad, a standard meal: removing it is what gives a skipped prepped meal its slot back.
        openMenuOf("Tue 17 Nov", "lunch", item = 2)
        compose.onNodeWithText("Remove from the plan").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { backend.changes.any { it == "DELETE /api/plans/${FakeBackend.WEEK_START}/meals/1/LUNCH" } }
    }

    @Test
    fun aMealOfAnotherRecipeHasNoEntryToTakeItOut() {
        show { PlanScreen(api, { _, _ -> }, {}, {}, {}) }
        openMenuOf("Mon 16 Nov", "dinner", item = 1)
        assertEquals(0, count("Remove from the plan"), "the chicken thighs are no standard meal")
    }

    /** Opens the swap menu of the dinner of the first day (Monday) on the Plan. */
    private fun openMondayDinnerMenu() {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithContentDescription("Swap dinner").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollAction()).performScrollToIndex(1)
        compose.onAllNodesWithContentDescription("Swap dinner")[0].performClick()
        waitFor("Another recipe")
    }

    @Test
    fun skippingAMealOnThePlanShowsTheStandardMealsAtOnceAndOneTapPutsOneThere() {
        show { PlanScreen(api, { _, _ -> }, {}, {}, {}) }
        openMondayDinnerMenu()
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
        show { PlanScreen(api, { _, _ -> }, {}, {}, {}) }
        // Monday's dinner makes the prep: the server lists it as closed, so skipping it offers nothing.
        waitFor("Prep a recipe…")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Mon 16 Nov"))
        val dayTop = compose.onNodeWithText("Mon 16 Nov").fetchSemanticsNode().boundsInRoot.top
        val buttons = compose.onAllNodesWithContentDescription("Swap dinner").fetchSemanticsNodes()
        val own = buttons.withIndex().filter { it.value.boundsInRoot.top > dayTop }.minBy { it.value.boundsInRoot.top }.index
        compose.onAllNodesWithContentDescription("Swap dinner")[own].performClick()
        waitFor("Not eating this")
        compose.onNodeWithText("Not eating this").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { backend.changes.any { it.contains("/meals/0/DINNER/status") } }
        compose.waitUntil(timeoutMillis = 10_000) { backend.requests.count { it.endsWith("/standard-meals") } >= 2 }
        compose.waitForIdle()
        assertEquals(0, count("Eat one of your standard meals instead?"))
    }

    @Test
    fun leavingTheSkipSheetKeepsTheSkip() {
        show { PlanScreen(api, { _, _ -> }, {}, {}, {}) }
        openMondayDinnerMenu()
        compose.onNodeWithText("Not eating this").performClick()
        waitFor("Leave it empty")
        compose.onNodeWithText("Leave it empty").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { count("Eat one of your standard meals instead?") == 0 }
        assertEquals(emptyList(), chosen())
    }

    @Test
    fun withoutStandardMealsSkippingOnThePlanIsAsBefore() {
        useBackend(standardMeals = false)
        show { PlanScreen(api, { _, _ -> }, {}, {}, {}) }
        openMondayDinnerMenu()
        compose.onNodeWithText("Not eating this").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { backend.changes.any { it.contains("/status") } }
        compose.waitForIdle()
        assertEquals(0, count("Eat one of your standard meals instead?"), "with none for the slot the skip is as it was")
    }

    @Test
    fun thePickerListsTheStandardMealsFirstAndPutsOneInByItsOwnRoute() {
        show { PlanScreen(api, { _, _ -> }, {}, {}, {}) }
        openMondayDinnerMenu()
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
        show { PlanScreen(api, { _, _ -> }, {}, {}, {}) }
        // Tuesday's lunch is a later meal of the chicken thighs, a batch no meal of which was eaten yet. The box card comes
        // before the days, so Tuesday is the list's fourth item.
        openMenuOf("Tue 17 Nov", "lunch", item = 3)
        compose.onNodeWithText("Choose a recipe…").performClick()
        waitFor("This meal is from a prepped batch still to make: another recipe here makes the batch smaller.")
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
