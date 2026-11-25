package dev.tobins.mealplanner.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.PlannedMeal
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
 * Planning the week from suggestions (issue 255), against the fake backend's planning: today is Wednesday, so of the days
 * without a dinner that stays only Wednesday, Saturday and Sunday are open; the lentil soup and the shakshuka are chosen,
 * the tuna salad hidden, and ten suggestions are on four grounds. As README "Screenshot tests" says of host tests, each
 * waits for what it looks for, a node or a call the fake has answered, never for time; the Plan is driven through
 * `PlanActions.kt`, and the planning screen's list is scrolled to a node, never to a position.
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

    /** What the planning screen handed back when it planned the week, and whether it opened the recipes. */
    private var planned: WeekPlan? = null
    private var openedRecipes = false

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
            PlanningScreen(api, week, onBack = {}, onPlanned = { planned = it }, onOpenRecipes = { openedRecipes = true })
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

    /** Waits until the fake has answered [call], a method and a path, as `PUT /api/plans/…/picks/21`. */
    private fun waitForAnswer(call: String) = compose.waitUntil(timeoutMillis = 10_000) { call in backend.answered }

    /** The button [label] of the suggestion card titled [title]: the card is one node with all its texts. */
    private fun buttonOfCard(label: String, title: String) = hasText(label) and hasAnyAncestor(hasText(title, substring = true))

    /** Scrolls the screen's list to the node with [text] once; fails when the list has none. */
    private fun scrollOnce(text: String) {
        compose.onNode(list).performScrollToNode(hasText(text, substring = true))
    }

    /**
     * Scrolls the screen's list to the node with [text], which a lazy list composes only near the screen, waiting until
     * the list has it: what an answer of the fake brings.
     */
    private fun scrollTo(text: String) = compose.waitUntil(timeoutMillis = 10_000) { runCatching { scrollOnce(text) }.isSuccess }

    @Test
    fun planTheWeekOpensThePlanningScreenWhoseFillTheRestPlansTheWeekWithoutTheOneTapGenerate() {
        backend.picks.clear()
        showPlan()
        compose.waitForPlan()
        assertEquals(0, count("Replan"), "no one-tap Generate")

        compose.onNodeWithText("Plan the week").performClick()
        // The title is short enough for the largest font; the week's date is the first line.
        waitFor("Week of 16 Nov")
        waitFor("Fill the rest")
        compose.onNodeWithText("Fill the rest").performClick()

        waitForAnswer("POST /api/plans/$week/planning/done")
        compose.waitForPlan()
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
        scrollTo("On offer: minced beef at Jumbo (2 voor 7,50)")
        // Six on a screen: the seventh comes with "Show others".
        scrollTo("Show others")
        assertTrue(runCatching { scrollOnce("Thai green curry") }.isFailure, "the seventh is not on the first screen")
    }

    @Test
    fun onlyTheDaysFromTodayOnAreOpenAndTheStripSaysForHowManyMealsItsCostIs() {
        showPlanning()
        // Wednesday, Saturday and Sunday: Monday and Tuesday have passed, Thursday and Friday have dinners that stay.
        waitFor("1 dinner still to choose")
        waitFor("≈ €21.40 for 7 meals, €2.60 saved by offers")

        scrollTo("Gochujang chicken thighs")
        compose.onNodeWithText("Gochujang chicken thighs", substring = true).performClick()

        waitForAnswer("PUT /api/plans/$week/planning/picks/21")
        scrollTo("A dinner is chosen for every day that is open.")
        // The strip counts each chosen dinner with its leftover lunch, and says so in its number of meals.
        scrollTo("≈ €28.20 for 9 meals, €3.60 saved by offers")
        waitFor("Done")
        val takeOut = hasContentDescription("Take Gochujang chicken thighs with rice and quick-pickled cucumber out")
        // The chosen dinners are one line until opened.
        assertEquals(0, compose.onAllNodes(takeOut).fetchSemanticsNodes().size, "collapsed: no take-out button yet")
        compose.onNode(list).performScrollToNode(hasText("Chosen dinners (3)"))
        compose.onNodeWithText("Show").performClick()
        compose.onNode(list).performScrollToNode(takeOut)
        compose.onNode(takeOut).performClick()
        waitForAnswer("DELETE /api/plans/$week/planning/picks/21")
    }

    @Test
    fun showOthersGivesTheNextOnesWithNoneShownBeforeAndStartAgainGoesBack() {
        showPlanning()
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
    fun withNothingToSuggestTheScreenSaysWhyAndWhereToAddRecipes() {
        use(FakeBackend(planning = true, nothingToSuggest = true))
        showPlanning()

        scrollTo("Nothing to suggest")
        assertEquals(0, count("Start again"), "nothing was shown, so there is nothing to start again from")
        compose.onNodeWithText("Open recipes").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { openedRecipes }
    }

    @Test
    fun somethingElseSearchesEveryMealOfMineMarksTheChosenOnesAndGoesBack() {
        showPlanning()
        scrollTo("Something else")
        compose.onNodeWithText("Something else").performClick()
        waitFor("Search my recipes")
        compose.onNodeWithText("Search my recipes").performTextInput("tuna")

        // Out of the rotation and hidden this week, it is still found, as the only one with "tuna" in its title.
        scrollTo("760 kcal")
        assertTrue(runCatching { scrollOnce("Gochujang chicken thighs") }.isFailure, "what has no tuna in its title is not found")

        // The lentil soup is chosen already: found, and marked so, with nothing to add.
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("lentil")
        scrollTo("✓ Chosen for this week")
        assertEquals(0, count("Add"), "a chosen dinner has no Add")

        // The way back is in the list's header, which a scroll leaves partly under the top bar: its click, not a tap there.
        scrollTo("Back to the suggestions")
        compose.onNodeWithText("Back to the suggestions").performSemanticsAction(SemanticsActions.OnClick)
        scrollTo("Gochujang chicken thighs")
    }

    @Test
    fun notThisWeekHidesTheRecipeAndShowAgainBringsItBack() {
        showPlanning()
        scrollTo("Gochujang chicken thighs")
        compose.onNode(buttonOfCard("Not this week", "Gochujang chicken thighs")).performClick()

        waitForAnswer("PUT /api/plans/$week/planning/not-this-week/21")
        scrollTo("Show again")
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Show again").fetchSemanticsNodes().size == 2 }
        compose.onNode(hasText("Show again") and hasAnyAncestor(hasText("Tuna pasta salad"))).performClick()
        waitForAnswer("DELETE /api/plans/$week/planning/not-this-week/3")
    }

    @Test
    fun thePlanOffersContinuePlanningAndTheChoicesAreThereAfterLeaving() {
        showPlan()
        compose.waitForPlan()
        compose.onNodeWithText("Continue planning: 2 dinners chosen").performClick()
        scrollTo("Chosen dinners")
        scrollTo("Lentil soup")
        scrollTo("Shakshuka")

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForPlan()
        waitFor("Continue planning: 2 dinners chosen")
    }

    @Test
    fun thePlanNextWeekNotificationOpensThePlanningScreenOfThatWeek() {
        showPlan(startWeek = "2026-11-23", startPlanning = true)
        waitFor("Week of 23 Nov")
        waitFor("dinner still to choose")
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
        // The button, not the screen's title of the same words.
        compose.onNode(hasText("Plan the week") and hasClickAction()).performClick()

        waitForAnswer("POST /api/plans/$week/generate")
        compose.waitUntil(timeoutMillis = 10_000) { planned != null }
    }

    @Test
    fun aDinnerMovesBetweenTheDaysFromTodayOnAndAGreyedDaySaysWhy() {
        use(FakeBackend(planning = true, dinnerEaten = true))
        showPlan()
        compose.waitForPlan()
        compose.openSwapMenu("Wed 18 Nov", "dinner")
        compose.onNodeWithText("Move to another day…").performClick()

        waitFor("Move Slow-cooked chicken thighs with coconut, lemon and spinach to")
        // Monday and Tuesday have passed; Friday's lentil soup was eaten, so it stays: each greyed, with why.
        compose.onNode(hasText("Mon 16 Nov", substring = true) and hasText("this day has passed", substring = true)).assertIsNotEnabled()
        compose.onNode(hasText("Lentil soup stays on its day: it was eaten", substring = true)).assertIsNotEnabled()
        // The dialog's list scrolls: the day is scrolled to before it is tapped.
        compose.onNode(hasText("Sat 21 Nov", substring = true) and hasText("swaps with", substring = true)).performScrollTo().performClick()

        waitForAnswer("POST /api/plans/$week/meals/2/DINNER/move")
        assertTrue("POST /api/plans/$week/meals/2/DINNER/move {\"toDay\":5}" in backend.changes, "${backend.changes}")
    }

    @Test
    fun aDinnerOnADayThatHasPassedOrEatenHasNoWayToMove() {
        use(FakeBackend(planning = true, dinnerEaten = true))
        showPlan()
        compose.waitForPlan()
        compose.openSwapMenu("Mon 16 Nov", "dinner")
        assertEquals(0, count("Move to another day…"), "Monday has passed")
    }

    @Test
    fun anEatenDinnerHasNoWayToMove() {
        use(FakeBackend(planning = true, dinnerEaten = true))
        showPlan()
        compose.waitForPlan()
        compose.openSwapMenu("Fri 20 Nov", "dinner")
        assertEquals(0, count("Move to another day…"))
    }

    @Test
    fun anotherRecipeOnADinnerOpensTheSuggestionsForThatMeal() {
        showPlan()
        compose.waitForPlan()
        compose.openSwapMenu("Mon 16 Nov", "dinner")
        compose.onNodeWithText("Another recipe").performClick()

        waitFor("Another dinner for Mon 16 Nov")
        waitFor("Let the planner choose")
        scrollTo("Gochujang chicken thighs")
        compose.onNode(buttonOfCard("Choose", "Gochujang chicken thighs")).performClick()

        waitForAnswer("PUT /api/plans/$week/meals/0/DINNER")
        assertTrue("PUT /api/plans/$week/meals/0/DINNER {\"recipeId\":21}" in backend.changes, "${backend.changes}")
        compose.waitForPlan()
    }

    @Test
    fun theChosenDinnersAreOneLineWithTheirCountUntilOpened() {
        showPlanning()
        waitFor("Chosen dinners (2)")
        waitFor("Lentil soup, Shakshuka")
        assertEquals(0, count("Take Lentil soup out"), "no row of its own while collapsed")
        compose.onNodeWithText("Show").performClick()
        assertEquals(1, compose.onAllNodes(hasContentDescription("Take Lentil soup out")).fetchSemanticsNodes().size)
        compose.onNodeWithText("Hide").performClick()
        waitFor("Lentil soup, Shakshuka")
    }

    @Test
    fun aWeekWhollyInThePastSaysSoAndOffersNoPlanningOrDone() {
        val pastWeek = plusDays(week, -7)
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides Strings.English) { PlanningScreen(api, pastWeek, onBack = {}, onPlanned = { planned = it }) }
        }
        waitFor("This week is over")
        assertEquals(0, count("Done") + count("Fill the rest") + count("Suggestions"))
        assertTrue(backend.requests.none { "/planning" in it }, "decided from the dates, not asked of the server: ${backend.requests}")
    }

    @Test
    fun theCurrentWeekIsNotOverEvenOnItsLastDay() {
        appClock = object : Clock {
            override fun now(): Instant = Instant.parse("${plusDays(week, 6)}T10:30:00Z")
        }
        showPlanning()
        waitFor("Week of 16 Nov")
        assertEquals(0, count("This week is over"))
    }

    @Test
    fun anotherRecipeAgainstAnOlderServerSaysSoAndKeepsThePlannersChoice() {
        use(FakeBackend(planning = false))
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides Strings.English) {
                AnotherDinnerScreen(api, week, PlannedMeal(0, MealSlot.DINNER, 4, "Chicken", 1.0, 810.0, 55.0), onBack = {}, onChanged = {})
            }
        }
        waitFor("This server plans the week in one go")
        waitFor("Let the planner choose")
        assertEquals(0, count("Could not load"), "no load error")
    }
}
