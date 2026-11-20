package dev.tobins.mealplanner.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import dev.tobins.mealplanner.api.WeekPlan
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
 * Planning the week from suggestions (issue 255), against the fake backend's planning: two dinners chosen (the lentil soup
 * and the shakshuka) of five open days, the tuna salad hidden, and ten suggestions on four grounds. Every test waits for
 * what it looks for, a node or a request the fake answered, and never for time.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PlanningScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var backend = FakeBackend(planning = true)
    private var api = ApiClient(backend.settings)
    private val week = FakeBackend.WEEK_START

    /** What the planning screen handed back when it planned the week, and how often it was left. */
    private var planned: WeekPlan? = null
    private var left = 0

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

    private fun use(other: FakeBackend) {
        api.close()
        backend.close()
        backend = other
        api = ApiClient(backend.settings)
    }

    private fun showPlanning() = compose.setContent {
        CompositionLocalProvider(LocalStrings provides Strings.English) {
            PlanningScreen(api, week, onBack = { left++ }, onPlanned = { planned = it })
        }
    }

    private fun showPlan(startWeek: String? = null, startPlanning: Boolean = false) = compose.setContent {
        CompositionLocalProvider(LocalStrings provides Strings.English) {
            PlanScreen(api, { _, _ -> }, {}, {}, {}, startWeek = startWeek, startPlanning = startPlanning)
        }
    }

    /** The screen's list: a search field scrolls too, but only a lazy list scrolls to an item. */
    private val list = hasScrollToIndexAction()

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    private fun waitFor(text: String) = compose.waitUntil(timeoutMillis = 10_000) { count(text) > 0 }

    /** Waits until the fake was asked for [change], which starts with the method and the path, and answered it. */
    private fun waitForChange(change: String) = compose.waitUntil(timeoutMillis = 10_000) { backend.changes.any { it.startsWith(change) } }

    /** Scrolls the screen's list to the node with [text] once; fails when the list has none. */
    private fun scrollOnce(text: String) {
        compose.onNode(list).performScrollToNode(hasText(text, substring = true))
    }

    /**
     * Scrolls the screen's list to the node with [text], which a lazy list composes only near the screen, waiting until
     * the list has it: what an answer of the fake brings.
     */
    private fun scrollTo(text: String) = compose.waitUntil(timeoutMillis = 10_000) { runCatching { scrollOnce(text) }.isSuccess }

    /** Opens the swap menu of the dinner under the day [label] of the Plan, as PlanMenuTest does. */
    private fun openDinnerMenu(label: String, item: Int) {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(list).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(list).performScrollToIndex(item)
        waitFor(label)
        compose.waitForIdle()
        val dayTop = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top
        val buttons = compose.onAllNodesWithContentDescription("Swap dinner").fetchSemanticsNodes()
        val own = buttons.withIndex().filter { it.value.boundsInRoot.top > dayTop }.minBy { it.value.boundsInRoot.top }.index
        compose.onAllNodesWithContentDescription("Swap dinner")[own].performClick()
        waitFor("Adjust what was in it")
    }

    @Test
    fun planTheWeekOpensThePlanningScreenWhoseFillTheRestPlansTheWeekWithoutTheOneTapGenerate() {
        backend.picks.clear()
        showPlan()
        waitFor("Plan the week")
        assertEquals(0, count("Replan"), "no one-tap Generate")

        compose.onNodeWithText("Plan the week").performClick()
        waitFor("Plan the week of 16 Nov")
        waitFor("Fill the rest")
        compose.onNodeWithText("Fill the rest").performClick()

        waitForChange("POST /api/plans/$week/planning/done")
        // Back on the Plan with the week the server planned; never the old route.
        waitFor("Plan the week")
        assertTrue(backend.requests.none { it.endsWith("/generate") }, "${backend.requests}")
    }

    @Test
    fun eachSuggestionSaysWhyItIsThereWithItsPriceSavingAndNumbers() {
        showPlanning()
        scrollTo("Gochujang chicken thighs")

        scrollTo("On offer: chicken thigh fillet at AH (25% korting)")
        waitFor("€2.35 a serving, €0.85 saved")
        waitFor("800 kcal")
        scrollTo("Uses what is there: feta")
        scrollTo("Favourite: rated 5 of 5 · not eaten for 4 weeks")
        scrollTo("Not tried yet")
        scrollTo("Beef chilli")
        scrollTo("On offer: minced beef at Jumbo (2 voor 7,50)")
        // Six on a screen: the seventh comes with "Show others".
        scrollTo("Show others")
        assertEquals(0, count("Thai green curry"))
    }

    @Test
    fun tappingACardAddsItToTheWeeksDinnersAndTheStripShowsHowTheWeekStands() {
        showPlanning()
        waitFor("3 dinners still to choose")
        waitFor("≈ €21.40, €2.60 saved by offers")

        scrollTo("Gochujang chicken thighs")
        compose.onNodeWithText("Gochujang chicken thighs", substring = true).performClick()

        waitForChange("PUT /api/plans/$week/planning/picks/21")
        // The strip is above the cards: scrolled to, as the list composes only what is near the screen.
        scrollTo("2 dinners still to choose")
        scrollTo("≈ €28.20, €3.60 saved by offers")
        // Among the chosen dinners, with the button that takes it out again.
        val takeOut = hasContentDescription("Take Gochujang chicken thighs with rice and quick-pickled cucumber out")
        compose.onNode(list).performScrollToNode(takeOut)
        compose.onNode(takeOut).performClick()
        waitForChange("DELETE /api/plans/$week/planning/picks/21")
    }

    @Test
    fun showOthersGivesTheNextOnesWithNoneShownBeforeAndStartAgainGoesBack() {
        showPlanning()
        scrollTo("Gochujang chicken thighs")
        scrollTo("Show others")
        compose.onNodeWithText("Show others").performClick()

        scrollTo("Thai green curry")
        assertTrue(runCatching { scrollOnce("Gochujang chicken thighs") }.isFailure, "none of the first screen again")
        scrollTo("Mushroom risotto")
        scrollTo("No more suggestions")
        compose.onNodeWithText("Start again").performClick()
        scrollTo("Gochujang chicken thighs")
    }

    @Test
    fun somethingElseSearchesEveryMealOfMineAndGoesBackToTheSuggestions() {
        showPlanning()
        scrollTo("Something else")
        compose.onNodeWithText("Something else").performClick()
        waitFor("Search my recipes")
        compose.onNodeWithText("Search my recipes").performTextInput("tuna")

        // Out of the rotation and hidden this week, it is still found, as the only one with "tuna" in its title.
        scrollTo("760\u00A0kcal")
        assertTrue(runCatching { scrollOnce("Gochujang chicken thighs") }.isFailure, "what has no tuna in its title is not found")
        // The way back is in the list's header, which a scroll leaves partly under the top bar: its click, not a tap there.
        scrollTo("Back to the suggestions")
        compose.onNodeWithText("Back to the suggestions").performSemanticsAction(SemanticsActions.OnClick)
        scrollTo("Gochujang chicken thighs")
    }

    @Test
    fun notThisWeekHidesTheRecipeAndShowAgainBringsItBack() {
        showPlanning()
        scrollTo("Gochujang chicken thighs")
        compose.onAllNodesWithText("Not this week")[0].performClick()

        waitForChange("PUT /api/plans/$week/planning/not-this-week/21")
        scrollTo("Show again")
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Show again").fetchSemanticsNodes().size == 2 }
        compose.onAllNodesWithText("Show again")[0].performClick()
        waitForChange("DELETE /api/plans/$week/planning/not-this-week/3")
    }

    @Test
    fun thePlanOffersContinuePlanningAndTheChoicesAreThereAfterLeaving() {
        showPlan()
        waitFor("Continue planning: 2 dinners chosen")
        compose.onNodeWithText("Continue planning: 2 dinners chosen").performClick()
        scrollTo("Chosen dinners")
        scrollTo("Lentil soup")
        scrollTo("Shakshuka")

        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("Continue planning: 2 dinners chosen")
    }

    @Test
    fun thePlanNextWeekNotificationOpensThePlanningScreenOfThatWeek() {
        showPlan(startWeek = "2026-11-23", startPlanning = true)
        waitFor("Plan the week of 23 Nov")
    }

    @Test
    fun doneSaysWhyTheServerRefusedAndStays() {
        backend.doneRefusal = "\"Lost\" has no amounts to plan with: take it out first"
        showPlanning()
        waitFor("Fill the rest")
        compose.onNodeWithText("Fill the rest").performClick()

        waitFor("has no amounts to plan with: take it out first")
        assertEquals(null, planned)
    }

    @Test
    fun aServerBeforeThePlanningScreenPlansTheWeekInOneGo() {
        use(FakeBackend())
        showPlanning()
        waitFor("This server plans the week in one go")
        compose.onNodeWithText("Plan the week").performClick()

        waitForChange("POST /api/plans/$week/generate")
        compose.waitUntil(timeoutMillis = 10_000) { planned != null }
    }

    @Test
    fun aDinnerMovesToAnotherDayAndADinnerThatStaysIsNoTarget() {
        use(FakeBackend(planning = true, dinnerEaten = true))
        showPlan()
        openDinnerMenu("Mon 16 Nov", item = 1)
        compose.onNodeWithText("Move to another day…").performClick()

        waitFor("Move Slow-cooked chicken thighs with coconut, lemon and spinach to")
        // Friday's lentil soup was eaten: it stays, so Monday's dinner can't take its day.
        compose.onNodeWithText("Lentil soup stays on its day", substring = true).assertIsNotEnabled()
        compose.onNode(hasText("Tue 17 Nov", substring = true) and hasText("swaps with", substring = true)).performClick()

        waitForChange("POST /api/plans/$week/meals/0/DINNER/move {\"toDay\":1}")
    }

    @Test
    fun anEatenDinnerHasNoWayToMove() {
        use(FakeBackend(planning = true, dinnerEaten = true))
        showPlan()
        openDinnerMenu("Fri 20 Nov", item = 5)
        assertEquals(0, count("Move to another day…"))
    }

    @Test
    fun anotherRecipeOnADinnerOpensTheSuggestionsForThatMeal() {
        showPlan()
        openDinnerMenu("Mon 16 Nov", item = 1)
        compose.onNodeWithText("Another recipe").performClick()

        waitFor("Another dinner for Mon 16 Nov")
        waitFor("Let the planner choose")
        scrollTo("Gochujang chicken thighs")
        compose.onAllNodesWithText("Choose")[0].performClick()

        waitForChange("PUT /api/plans/$week/meals/0/DINNER {\"recipeId\":21}")
        waitFor("Continue planning")
    }
}
