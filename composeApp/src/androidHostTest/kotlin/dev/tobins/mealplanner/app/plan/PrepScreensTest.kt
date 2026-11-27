package dev.tobins.mealplanner.app.plan

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.tobins.mealplanner.api.MealPrep
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PrepMeal
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.FakeBackend
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.recipes.MealToCook
import dev.tobins.mealplanner.app.recipes.RecipeDetailScreen
import dev.tobins.mealplanner.app.recipes.keepElsewhere
import dev.tobins.mealplanner.app.ui.appClock
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

    /** Uses the fake backend's week with a batch of the chickpea salad still to make, which a side can be added to. */
    private fun withGrowingBatch() {
        api.close()
        backend.close()
        backend = FakeBackend(false, false, false, false, growing = true)
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
        // The lentil soup comes to keep on another device while the Plan is open: after the Plan's own read of my recipes
        // was answered, so only a read made later can know it.
        compose.waitUntil(timeoutMillis = 10_000) { "GET /api/recipes" in backend.answered }
        backend.keepElsewhere(5, RecipeKeeps(days = 2))
        compose.onNodeWithText("Prep a recipe…").performClick()
        // The recipe menu (its button names the recipe chosen, or asks for one) is there once my recipes are. Opening the
        // dialog reads them again, and the menu lists the soup when that answer is in.
        val recipeButton = hasAnyAncestor(isDialog()) and hasClickAction() and
            (hasText("Slow-cooked chicken thighs with coconut, lemon and spinach") or hasText("Pick a recipe that keeps."))
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(recipeButton).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(recipeButton).performClick()
        // In the menu: the Plan behind the dialog may show the soup's meal too.
        val soup = hasText("Lentil soup") and hasAnyAncestor(isPopup())
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(soup).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aWeekPlannedPartlyByHandNamesOnlyTheSlotsNoRecipeIsFor() {
        api.close()
        backend.close()
        // The server names every slot without a meal; the account has lunch and dinner recipes, none for breakfast or a snack.
        backend = FakeBackend(false, false, false, false, emptySlots = listOf(MealSlot.BREAKFAST, MealSlot.DINNER, MealSlot.SNACK))
        api = ApiClient(backend.settings)
        showPlan()
        // The whole sentence: "dinner" is not in it.
        waitFor("No recipes yet for: breakfast, snack. Days stay")
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
    fun anOpenAddSideDialogKeepsItsChoicesWhenTheActivityIsMadeAgain() {
        withGrowingBatch()
        showPlan()
        // Friday's dinner (the lentil soup) can take a side of the salad batch made today.
        compose.openSwapMenu("Fri 20 Nov", "dinner")
        waitFor("Add a prepped side…")
        compose.onNodeWithText("Add a prepped side…").performClick()
        // The dialog starts at half a serving, the larger of the two; a quarter is chosen instead.
        val quarter = hasAnyAncestor(isDialog()) and hasClickAction() and hasText("0.25", substring = true)
        waitFor("Add")
        compose.onNode(quarter).performClick()
        compose.onNode(quarter).assertIsSelected()

        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(quarter).assertIsSelected()
    }

    @Test
    fun anOpenRemovePrepDialogStaysWhenTheActivityIsMadeAgain() {
        withPrep()
        showPlan()
        // Monday's dinner makes the batch: its menu removes the prep.
        compose.waitForPlan(boxCard = true)
        compose.openSwapMenu("Mon 16 Nov", "dinner")
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

    /** Issue 370: before anything is picked, the dialog says what a prep needs and what it does to the slots it takes. */
    @Test
    fun thePrepDialogSaysWhatAPrepNeedsAndDoesBeforeAnythingIsPicked() {
        showPlan()
        compose.onNodeWithText("Prep a recipe…").performClick()
        waitFor("Made on")
        compose.onNode(hasAnyAncestor(isDialog()) and hasText("Cooked once on the day it is made", substring = true)).assertExists()
        compose.onNode(hasAnyAncestor(isDialog()) and hasText("in place of what is planned there", substring = true)).assertExists()
    }

    /** Issue 370: a later day of a batch says the meal comes from the batch and when it was made, and shows no as-written servings. */
    @Test
    fun aLaterMealOfABatchSaysItComesFromTheBatchRatherThanShowingTheRecipeAsWritten() {
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides Strings.English) {
                RecipeDetailScreen(api, 4, null, onBack = {}, onEdit = {}, meal = MealToCook(4, 1.25, 0.0, skipped = false, prepMadeOn = FakeBackend.WEEK_START))
            }
        }
        waitFor("Prepped on Mon 16 Nov: nothing to cook")
        waitFor("In this meal: 1.25 servings")
        compose.onNodeWithText("Makes", substring = true).assertDoesNotExist()
    }

    /** Issue 370: a batch with a meal eaten is not offered for removal: the dialog says what stays and lists only what can still be skipped. */
    @Test
    fun removingABatchOfWhichAMealWasEatenOffersNothingToRemove() {
        val prep = MealPrep(
            1, "2026-11-16", "2026-11-19", false,
            listOf(
                PrepMeal("2026-11-16", MealSlot.DINNER, 1.5, MealStatus.EATEN),
                PrepMeal("2026-11-17", MealSlot.LUNCH, 1.25, MealStatus.SKIPPED),
                PrepMeal("2026-11-18", MealSlot.LUNCH, 1.25, MealStatus.PLANNED),
            ),
        )
        compose.setContent { CompositionLocalProvider(LocalStrings provides Strings.English) { RemovePrepDialog("Salad", prep, onDismiss = {}, onRemove = {}) } }
        waitFor("The prep of Salad was made")
        compose.onNodeWithText("Mark the meals you do not eat skipped instead: Wed 18 Nov lunch.", substring = true).assertExists()
        compose.onNodeWithText("Mon 16 Nov", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Tue 17 Nov", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Remove").assertDoesNotExist()
        compose.onNodeWithText("Close").assertExists()
    }
}
