package dev.tobins.mealplanner.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
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

    /** Opens the swap menu of the [slot] under the day [label] (the list is lazy: the day is scrolled to first). */
    private fun openMenuOf(label: String, item: Int, slot: String = "dinner") {
        val swap = "Swap $slot"
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollAction()).performScrollToIndex(item)
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        val dayTop = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top
        val buttons = compose.onAllNodesWithContentDescription(swap).fetchSemanticsNodes()
        // The first button of that slot below the day's heading is its own.
        val own = buttons.withIndex().filter { it.value.boundsInRoot.top > dayTop }.minBy { it.value.boundsInRoot.top }.index
        compose.onAllNodesWithContentDescription(swap)[own].performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Adjust what was in it").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun assertOffered(vararg texts: String) = texts.forEach { assertEquals(1, count(it), "$it is offered") }

    private fun assertNotOffered(vararg texts: String) = texts.forEach { assertEquals(0, count(it), "$it is not offered") }

    @Test
    fun aMealStillToEatHasEveryEntry() {
        show()
        openMenuOf("Mon 16 Nov", item = 1)
        assertOffered("Another recipe", "Choose a recipe…", "Write a new recipe…", "Adjust what was in it", "Not eating this")
    }

    @Test
    fun aSkippedMealsMenuLeavesOutWritingANewRecipe() {
        show()
        // The fake week skips Thursday's dinner.
        openMenuOf("Thu 19 Nov", item = 4)
        assertOffered("Eat it after all", "Another recipe", "Choose a recipe…")
        assertNotOffered("Write a new recipe…")
    }

    @Test
    fun aLockedMealCannotBeSwappedForAnother() {
        show()
        // Friday's lentil soup was chosen, so it is locked: choosing again is left, another recipe is greyed.
        openMenuOf("Fri 20 Nov", item = 5)
        assertOffered("Another recipe", "Choose a recipe…")
        compose.onNodeWithText("Another recipe").assertIsNotEnabled()
    }

    @Test
    fun anEatenMealIsMarkedAndItsMenuSaysItKeepsItsRecipe() {
        show()
        // The fake week has Wednesday's breakfast eaten.
        openMenuOf("Wed 18 Nov", item = 3, slot = "breakfast")
        assertEquals(1, count("✓ Eaten"), "the row is marked as Today marks it")
        assertOffered("Eaten, so its recipe stays", "Adjust what was in it", "Not eating this")
        assertNotOffered("Another recipe", "Choose a recipe…", "Write a new recipe…", "Prep this recipe…")
    }

    @Test
    fun theLeftoverLunchOfAnEatenDinnerKeepsItsRecipe() {
        show(FakeBackend(dinnerEaten = true))
        openMenuOf("Sat 21 Nov", item = 6, slot = "lunch")
        assertOffered("Cooked with the dinner that was eaten, so it stays", "Adjust what was in it", "Not eating this")
        assertNotOffered("Another recipe", "Choose a recipe…", "Write a new recipe…", "Prep this recipe…")
    }

    @Test
    fun theMealThatMakesAPrepStaysAndOffersRemovingThePrep() {
        // The skipped share in the box is a card above the days.
        show(FakeBackend(prepped = true))
        openMenuOf("Mon 16 Nov", item = 2)
        assertOffered("It makes a prep, so it stays: remove the prep instead", "Remove the prep…", "Not eating this")
        assertNotOffered("Another recipe", "Choose a recipe…", "Write a new recipe…", "Prep this recipe…")
    }

    @Test
    fun aLaterMealOfAPrepStillToMakeCanBeChosenAgain() {
        show(FakeBackend(prepped = true))
        openMenuOf("Tue 17 Nov", item = 3, slot = "lunch")
        assertOffered("Choose a recipe…", "Remove the prep…")
        assertNotOffered("It makes a prep, so it stays: remove the prep instead", "Prep this recipe…")
    }

    @Test
    fun aSideThatMakesItsBatchGoesOnlyWithItsPrep() {
        show(FakeBackend(sides = true))
        // Wednesday's dinner has the side the batch is made with.
        openMenuOf("Wed 18 Nov", item = 3)
        assertOffered("Remove the side's prep…")
        assertNotOffered("Remove the side")
    }

    @Test
    fun aLaterSideOfABatchStillToMakeCanGoOnItsOwn() {
        show(FakeBackend(sides = true))
        openMenuOf("Thu 19 Nov", item = 4, slot = "lunch")
        assertOffered("Remove the side", "Remove the side's prep…")
    }
}
