package dev.tobins.mealplanner.app.recipes

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.FakeBackend
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.MemorySettingsStorage
import dev.tobins.mealplanner.app.NotificationKind
import dev.tobins.mealplanner.app.NotificationPlanner
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.saveNotificationOn
import kotlin.time.Instant
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import dev.tobins.mealplanner.api.NotificationFacts
import dev.tobins.mealplanner.api.DinnerToday
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cook mode as it is used: opened from the recipe, ticked off, kept through a re-created screen, and the meal marked eaten at the end. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CookModeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val backend = FakeBackend(false, false, false, false)
    private val api = ApiClient(backend.settings)
    private val planned = MealToCook(4, 0.75, 1.5, skipped = false, place = MealPlace("2026-11-16", 2, MealSlot.DINNER), planned = true)

    @After
    fun tearDown() {
        api.close()
        backend.close()
    }

    private fun show(meal: MealToCook?, initiallyCooking: Boolean = true) {
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides Strings.English) {
                RecipeDetailScreen(api, 4, null, {}, {}, meal = meal, initiallyCooking = initiallyCooking)
            }
        }
    }

    private fun waitFor(text: String) = compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun tapStep(number: Int) = compose.onNodeWithText("Step $number", substring = true).performScrollTo().performClick()

    @Test
    fun tappingAStepMovesTheCurrentOneOnAndMarkEatenComesWithTheLastStep() {
        show(planned)
        waitFor("Step 1 · Now")
        compose.onNodeWithText("525 g chicken thigh fillet", substring = true).assertExists()
        compose.onNodeWithText("Mark as eaten").assertDoesNotExist()

        tapStep(1)
        compose.onNodeWithText("Step 2 · Now").assertExists()
        tapStep(2)
        tapStep(3)
        compose.onNodeWithText("Mark as eaten").performScrollTo().performClick()

        compose.waitUntil(timeoutMillis = 10_000) { backend.changes.any { it.startsWith("PUT /api/plans/2026-11-16/meals/2/DINNER/status") } }
        assertTrue(backend.changes.single { it.contains("/status") }.endsWith("\"EATEN\""))
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Mark as eaten").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aMealThatIsNotPlannedHasNothingToMarkEaten() {
        show(planned.copy(planned = false))
        waitFor("Step 1 · Now")
        tapStep(1)
        tapStep(2)
        tapStep(3)
        compose.onNodeWithText("All steps done", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("Mark as eaten").assertDoesNotExist()
        assertEquals(emptyList(), backend.changes.filter { it.contains("/status") })
    }

    @Test
    fun theTicksSurviveARecreatedScreen() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalStrings provides Strings.English) {
                RecipeDetailScreen(api, 4, null, {}, {}, meal = planned, initiallyCooking = true)
            }
        }
        waitFor("Step 1 · Now")
        tapStep(1)
        compose.onNodeWithText("Step 2 · Now").assertExists()

        restoration.emulateSavedInstanceStateRestore()
        waitFor("Step 2 · Now")
        compose.onNodeWithText("Step 1 · Now").assertDoesNotExist()
    }

    @Test
    fun aRecipeWithIngredientsButNoStepsStillOffersMarkEaten() {
        val recipe = kotlinx.coroutines.runBlocking { api.recipe(4) }.copy(instructions = "")
        var eaten = 0
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides Strings.English) {
                CookMode(recipe, null, CookProgress(), {}, onMarkEaten = { eaten++ })
            }
        }
        compose.onNodeWithText("Steps").assertDoesNotExist()
        compose.onNodeWithText("Mark as eaten").performScrollTo().performClick()
        assertEquals(1, eaten)
    }

    @Test
    fun theAsWrittenSwitchChangesTheAmountsInCookMode() {
        show(planned)
        waitFor("Step 1 · Now")
        compose.onNodeWithText("525 g chicken thigh fillet", substring = true).assertExists()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performClick()
        compose.onNodeWithText("700 g chicken thigh fillet", substring = true).assertExists()
    }

    @Test
    fun theSystemBackLeavesCookModeForTheRecipe() {
        var left = false
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides Strings.English) {
                RecipeDetailScreen(api, 4, null, { left = true }, {}, meal = planned, initiallyCooking = true)
            }
        }
        waitFor("Step 1 · Now")

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor("Cook")
        compose.onNodeWithText("Step 1 · Now").assertDoesNotExist()
        assertEquals(false, left, "the recipe stays open")
    }

    @Test
    fun theCookButtonOpensCookModeAndBackLeavesItForTheRecipe() {
        show(null, initiallyCooking = false)
        waitFor("Cook")
        compose.onNodeWithText("Cook").performClick()
        waitFor("Step 1 · Now")

        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("Cook")
        compose.onNodeWithText("Step 1 · Now").assertDoesNotExist()
    }

    /** Issue 263: cook mode says what it cooks for whom, as the recipe does, also on the day a batch is made. */
    @Test
    fun cookModeOfAPrepDaySaysWhatItCooksForTheLaterMeals() {
        show(MealToCook(4, 1.5, 4.0, skipped = false, prepMadeOn = "2026-11-18"))
        waitFor("Step 1 · Now")
        compose.onNodeWithText("Cook 4 servings: 1.5 now, 2.5 for the prep's later meals").assertExists()
        // The recipe's 2 servings twice over: the batch.
        compose.onNodeWithText("1400 g chicken thigh fillet", substring = true).assertExists()
    }

    /** Issue 263: a later meal of a batch cooks nothing; cook mode shows what is in its box, under a heading that says so. */
    @Test
    fun cookModeOfALaterPreppedMealShowsWhatIsInItsBox() {
        show(MealToCook(4, 1.25, 0.0, skipped = false, prepMadeOn = "2026-11-16"))
        waitFor("Step 1 · Now")
        compose.onNodeWithText("Prepped on Mon 16 Nov: nothing to cook").assertExists()
        compose.onNodeWithText("In this meal: 1.25 servings").assertExists()
        compose.onNodeWithText("Ingredients").assertDoesNotExist()
    }

    /**
     * Issue 263: "Tonight's side" opens the side's recipe with its dinner's place, as the notification planner makes it. A
     * side follows its meal (the owner's decision), so "Mark as eaten" marks the dinner, and its side with it.
     */
    @Test
    fun theSideOfTonightsNotificationMarksItsDinnerEaten() {
        val storage = MemorySettingsStorage().apply { saveNotificationOn(NotificationKind.DINNER_TODAY, true) }
        val clock = object : Clock {
            override fun now() = Instant.parse("2026-11-21T16:00:00Z")
        }
        val facts = NotificationFacts("2026-11-21", dinner = DinnerToday(4, "Slow-cooked chicken thighs", 0.5, 1.5, "2026-11-16", 5, prep = true, side = true))
        val side = NotificationPlanner(storage, clock, TimeZone.UTC).due(facts, null, Strings.English).single().opening?.meal
        show(side)
        waitFor("Cook 1.5 servings: 0.5 now, 1 for the prep's later meals")
        tapStep(1)
        tapStep(2)
        tapStep(3)
        compose.onNodeWithText("Mark as eaten").performScrollTo().performClick()

        compose.waitUntil(timeoutMillis = 10_000) { backend.changes.any { it.contains("/status") } }
        assertEquals("PUT /api/plans/2026-11-16/meals/5/DINNER/status \"EATEN\"", backend.changes.single { it.contains("/status") })
    }
}
