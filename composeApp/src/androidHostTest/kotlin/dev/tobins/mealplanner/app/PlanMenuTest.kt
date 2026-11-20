package dev.tobins.mealplanner.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
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

/** The swap menu of a meal: "Write a new recipe…" is offered where it can work, not greyed with no reason (issue 271). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PlanMenuTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val backend = FakeBackend()
    private val api = ApiClient(backend.settings)

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

    /** Opens the swap menu of the dinner under the day [label] (the list is lazy: the day is scrolled to first). */
    private fun openDinnerMenuOf(label: String, item: Int) {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithContentDescription("Swap dinner").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollAction()).performScrollToIndex(item)
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        val dayTop = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top
        val buttons = compose.onAllNodesWithContentDescription("Swap dinner").fetchSemanticsNodes()
        // The first dinner button below the day's heading is its own.
        val own = buttons.withIndex().filter { it.value.boundsInRoot.top > dayTop }.minBy { it.value.boundsInRoot.top }.index
        compose.onAllNodesWithContentDescription("Swap dinner")[own].performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Another recipe").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun show() = compose.setContent {
        CompositionLocalProvider(LocalStrings provides Strings.English) { PlanScreen(api, { _, _ -> }, {}, {}, {}) }
    }

    @Test
    fun aMealStillToEatCanHaveANewRecipeWritten() {
        show()
        openDinnerMenuOf("Mon 16 Nov", item = 1)
        assertEquals(1, compose.onAllNodesWithText("Write a new recipe…").fetchSemanticsNodes().size)
    }

    @Test
    fun aSkippedMealsMenuLeavesOutWritingANewRecipe() {
        show()
        // The fake week skips Thursday's dinner.
        openDinnerMenuOf("Thu 19 Nov", item = 4)
        assertEquals(1, compose.onAllNodesWithText("Eat it after all").fetchSemanticsNodes().size, "this is the skipped meal's menu")
        assertEquals(0, compose.onAllNodesWithText("Write a new recipe…").fetchSemanticsNodes().size)
    }
}
