package dev.tobins.mealplanner.app.plan

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.FakeBackend
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.Strings
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
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The swap menu of a meal offers what the server does for it, in each state of a meal (issues 271 and 191): "Write a new
 * recipe…" where it can work, no other recipe for a meal that keeps its recipe, and the eaten mark on the row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PlanMenuTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var backend = FakeBackend()
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

    /** Shows the week of [other] instead of the plain one, when a test needs a week with something else in it. */
    private fun show(other: FakeBackend? = null) {
        if (other != null) {
            api.close()
            backend.close()
            backend = other
            api = ApiClient(backend.settings)
        }
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides Strings.English) { PlanScreen(api, { _, _ -> }, {}, {}, {}) }
        }
    }

    /** Opens the swap menu of the [slot] meal of the day headed [day], once the week is there with its box card if [prepped]. */
    private fun openMenuOf(day: String, slot: String = "dinner", prepped: Boolean = false) {
        compose.waitForPlan(boxCard = prepped)
        compose.openSwapMenu(day, slot)
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun assertOffered(vararg texts: String) = texts.forEach { assertEquals(1, count(it), "$it is offered") }

    private fun assertNotOffered(vararg texts: String) = texts.forEach { assertEquals(0, count(it), "$it is not offered") }

    @Test
    fun aMealStillToEatHasEveryEntry() {
        show()
        openMenuOf("Mon 16 Nov")
        assertOffered("Another recipe", "Choose a recipe…", "Write a new recipe…", "Adjust what was in it", "Not eating this")
    }

    @Test
    fun aSkippedMealsMenuLeavesOutWritingANewRecipe() {
        show()
        // The fake week skips Thursday's dinner.
        openMenuOf("Thu 19 Nov")
        assertOffered("Eat it after all", "Another recipe", "Choose a recipe…")
        assertNotOffered("Write a new recipe…")
    }

    @Test
    fun aLockedDinnerGetsSuggestionsForAnotherRecipe() {
        show()
        // Friday's lentil soup was chosen, so it is locked: "Another recipe" opens the suggestions to choose from (issue 255),
        // which a locked dinner may take as it may take a recipe chosen by hand.
        openMenuOf("Fri 20 Nov")
        assertOffered("Another recipe", "Choose a recipe…")
        compose.onNodeWithText("Another recipe").assertIsEnabled()
    }

    @Test
    fun anEatenMealIsMarkedAndItsMenuSaysItKeepsItsRecipe() {
        show()
        // The fake week has Wednesday's breakfast eaten.
        openMenuOf("Wed 18 Nov", slot = "breakfast")
        assertEquals(1, count("✓ Eaten"), "the row is marked as Today marks it")
        assertOffered("Eaten, so its recipe stays", "Adjust what was in it", "Not eating this")
        assertNotOffered("Another recipe", "Choose a recipe…", "Write a new recipe…", "Prep this recipe…")
    }

    @Test
    fun theLeftoverLunchOfAnEatenDinnerKeepsItsRecipe() {
        show(FakeBackend(dinnerEaten = true))
        openMenuOf("Sat 21 Nov", slot = "lunch")
        assertOffered("Cooked with the dinner that was eaten, so it stays", "Adjust what was in it", "Not eating this")
        assertNotOffered("Another recipe", "Choose a recipe…", "Write a new recipe…", "Prep this recipe…")
    }

    @Test
    fun theMealThatMakesAPrepStaysAndOffersRemovingThePrep() {
        // The skipped share in the box is a card above the days.
        show(FakeBackend(prepped = true))
        openMenuOf("Mon 16 Nov", prepped = true)
        assertOffered("It makes a prep, so it stays: remove the prep instead", "Remove the prep…", "Not eating this")
        assertNotOffered("Another recipe", "Choose a recipe…", "Write a new recipe…", "Prep this recipe…")
    }

    @Test
    fun aLaterMealOfAPrepStillToMakeCanBeChosenAgain() {
        show(FakeBackend(prepped = true))
        openMenuOf("Tue 17 Nov", slot = "lunch", prepped = true)
        assertOffered("Choose a recipe…", "Remove the prep…")
        assertNotOffered("It makes a prep, so it stays: remove the prep instead", "Prep this recipe…")
    }

    @Test
    fun aSideThatMakesItsBatchGoesOnlyWithItsPrep() {
        show(FakeBackend(sides = true))
        // Wednesday's dinner has the side the batch is made with.
        openMenuOf("Wed 18 Nov")
        assertOffered("Remove the side's prep…")
        assertNotOffered("Remove the side")
    }

    @Test
    fun aLaterSideOfABatchStillToMakeCanGoOnItsOwn() {
        show(FakeBackend(sides = true))
        openMenuOf("Thu 19 Nov", slot = "lunch")
        assertOffered("Remove the side", "Remove the side's prep…")
    }
}
