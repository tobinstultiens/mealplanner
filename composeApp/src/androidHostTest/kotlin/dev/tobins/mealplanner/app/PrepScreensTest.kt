package dev.tobins.mealplanner.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.RecipeKeeps
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
 * Rough edges of prepped meals on the screens (issue 263): a recipe's "Keeps once made", the Prep dialog's recipes and the
 * box card's proposal, against the fake backend on its Wednesday.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PrepScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var backend = FakeBackend(false, false, false, false)
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

    /** Uses the fake backend's week with a prep whose skipped lunch is in the box. */
    private fun withPrep() {
        api.close()
        backend.close()
        backend = FakeBackend(false, false, false, false, prepped = true)
        api = ApiClient(backend.settings)
    }

    private fun showRecipe(id: Long) = compose.setContent {
        CompositionLocalProvider(LocalStrings provides Strings.English) { RecipeDetailScreen(api, id, null, onBack = {}, onEdit = {}) }
    }

    private val restoration by lazy { StateRestorationTester(compose) }

    private fun showPlan() {
        restoration.setContent { CompositionLocalProvider(LocalStrings provides Strings.English) { PlanScreen(api, { _, _ -> }, {}, {}, {}) } }
        waitFor("Prep a recipe…")
    }

    private fun waitFor(text: String) = compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun keepsAreBusyWhileSavingSoTheSecondChoiceKeepsTheFirst() {
        showRecipe(4)
        waitFor("In a box: 3 days")
        val answer = CountDownLatch(1)
        backend.keepsAnswer = answer
        compose.onNodeWithText("In a box: 3 days").performScrollTo().performClick()
        compose.onNodeWithText("2 days").performClick()
        // While the first answer is on its way, neither number can be chosen.
        compose.waitUntil(timeoutMillis = 10_000) { backend.changes.any { it.startsWith("PUT /api/recipes/4/keeps") } }
        compose.onNodeWithText("Vacuum sealed: 6 days").assertIsNotEnabled()
        answer.countDown()
        waitFor("In a box: 2 days")
        backend.keepsAnswer = null

        compose.onNodeWithText("Vacuum sealed: 6 days").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("4 days").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { backend.changes.count { it.startsWith("PUT /api/recipes/4/keeps") } == 2 }
        assertEquals("""PUT /api/recipes/4/keeps {"days":2,"vacuumDays":4}""", backend.changes.last { it.startsWith("PUT /api/recipes/4/keeps") })
        waitFor("Vacuum sealed: 4 days")
    }

    @Test
    fun aBuildingBlockHasNoKeeps() {
        showRecipe(7)
        waitFor("Ingredients")
        compose.onNodeWithText("Keeps once made").assertDoesNotExist()
    }

    @Test
    fun thePrepDialogOffersARecipeWhoseKeepsWereSetElsewhereMeanwhile() {
        showPlan()
        // The lentil soup comes to keep on another device while the Plan is open.
        backend.keepElsewhere(5, RecipeKeeps(days = 2))
        compose.onNodeWithText("Prep a recipe…").performClick()
        // Opening the dialog reads my recipes again, whenever the Plan's first read of them came.
        compose.waitUntil(timeoutMillis = 10_000) { backend.requests.count { it == "GET /api/recipes" } >= 2 }
        // The recipe menu (its button names the recipe chosen, or asks for one) lists the soup.
        val recipeButton = hasAnyAncestor(isDialog()) and hasClickAction() and
            (hasText("Slow-cooked chicken thighs with coconut, lemon and spinach") or hasText("Pick a recipe that keeps."))
        compose.onNode(recipeButton).performClick()
        waitFor("Lentil soup")
    }

    @Test
    fun anOpenPrepDialogKeepsItsChoicesWhenTheActivityIsMadeAgain() {
        showPlan()
        compose.onNodeWithText("Prep a recipe…").performClick()
        waitFor("Made on")
        // Made on Friday instead of today, which the dialog opened with.
        val friday = hasAnyAncestor(isDialog()) and hasClickAction() and hasText("Fri 20 Nov")
        compose.onNode(friday).performClick()
        compose.onNode(friday).assertIsSelected()

        // Font size, dark mode or rotation: Android makes the activity again (issue 263).
        restoration.emulateSavedInstanceStateRestore()
        waitFor("Made on")
        compose.onNode(friday).assertIsSelected()
        compose.onNode(hasAnyAncestor(isDialog()) and hasClickAction() and hasText("Wed 18 Nov")).assertIsNotSelected()
    }

    @Test
    fun anOpenRemovePrepDialogStaysWhenTheActivityIsMadeAgain() {
        withPrep()
        showPlan()
        // Monday's dinner makes the batch: its menu removes the prep. Its day comes after the plan's head and the box card.
        waitFor("Eat it on…")
        compose.onNode(hasScrollAction()).performScrollToIndex(2)
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithContentDescription("Swap dinner").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription("Swap dinner")[0].performClick()
        compose.onNodeWithText("Remove the prep…").performClick()
        val title = "Remove the prep of Slow-cooked chicken thighs with coconut, lemon and spinach?"
        waitFor(title)

        restoration.emulateSavedInstanceStateRestore()
        waitFor(title)
        compose.onNode(hasAnyAncestor(isDialog()) and hasText("Remove")).assertExists()
    }

    @Test
    fun theBoxCardOffersNoOneTapPlaceWhenNoneIsFree() {
        withPrep()
        showPlan()
        // Wednesday's dinner and Thursday's lunch have room, but both hold a meal: replacing one is the user's choice from the menu.
        waitFor("Eat it on…")
        compose.onNodeWithText("Eat it on Wed 18 Nov", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Eat it on Thu 19 Nov", substring = true).assertDoesNotExist()
    }

    @Test
    fun theBoxCardEatsTheShareInTheFreePlaceTheServerProposesInOneTap() {
        withPrep()
        backend.spareProposed = PrepSlot("2026-11-19", MealSlot.LUNCH)
        showPlan()
        waitFor("Eat it on Thu 19 Nov · lunch")
        compose.onNodeWithText("Eat it on…").assertExists()
        compose.onNodeWithText("Eat it on Thu 19 Nov · lunch").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { backend.changes.any { it.startsWith("POST /api/plans/${FakeBackend.WEEK_START}/preps/1/spare") } }
        val sent = backend.changes.single { it.startsWith("POST /api/plans/${FakeBackend.WEEK_START}/preps/1/spare") }
        assertTrue(""""to":{"date":"2026-11-19","slot":"LUNCH"}""" in sent, sent)
    }
}
