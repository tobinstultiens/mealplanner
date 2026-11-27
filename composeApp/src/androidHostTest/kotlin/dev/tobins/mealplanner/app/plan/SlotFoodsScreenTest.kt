package dev.tobins.mealplanner.app.plan

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import dev.tobins.mealplanner.api.MealFoodInput
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.FakeBackend
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.ui.appClock
import dev.tobins.mealplanner.app.ui.unbrokenParts
import kotlinx.serialization.json.Json
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
 * Foods in a slot of the Plan (issue 358) on the fake server, which keeps the foods it is sent: one searched and picked
 * with an amount and a unit in an empty slot, and a second added to it from its menu.
 *
 * What is tapped is scrolled to first; the food is picked on a screen of its own rather than in a dialog, which on a
 * window taller than a phone never lets Compose get idle under Robolectric (see [RecipeEditorScreenTest]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SlotFoodsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val backend = FakeBackend(writing = true, slotFoods = true)
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

    private fun waitFor(text: String) =
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    /** Types [query] in the food screen's search and picks [result] from what it finds. */
    private fun pick(query: String, result: String) {
        val search = hasText("Add an ingredient") and hasSetTextAction()
        // The search shows once the account's ingredients are loaded.
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(search).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(search).performTextReplacement(query)
        val found = hasText(result) and hasText("per 100", substring = true) and hasClickAction()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(found).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(found).performScrollTo().performClick()
        waitFor("Amount")
    }

    /** The changes the fake server got so far, copied under the list's lock: its thread adds to it while the test reads. */
    private fun changes(): List<String> = synchronized(backend.changes) { backend.changes.toList() }

    /** The foods the app sent to [day]'s snack, in the order it sent them. */
    private fun sent(day: Int): List<MealFoodInput> = changes()
        .filter { it.startsWith("POST /api/plans/${FakeBackend.WEEK_START}/meals/$day/SNACK/foods ") }
        .map { Json.decodeFromString(it.substringAfter("/foods ")) }

    @Test
    fun aFoodIsPickedWithAnAmountAndAUnitInAnEmptySlotAndASecondIsAddedToIt() {
        compose.setContent { CompositionLocalProvider(LocalStrings provides Strings.English) { PlanScreen(api, { _, _ -> }, {}, {}, {}) } }
        compose.waitForPlan()
        val tuesday = "Tue 17 Nov"
        val inTuesday = hasAnyAncestor(hasAnyChild(hasText(tuesday)))

        // Tuesday has no snack: "Add a food…" asks which slot, and opens the food's screen.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(tuesday))
        compose.onNode(hasText("Add a food…") and inTuesday).performScrollTo().performClick()
        compose.onNode(hasText("Snack") and hasAnyAncestor(isPopup())).performClick()

        // The protein bar, found among the account's own, starts in 100 g; two of its bars instead.
        pick("protein", "Protein bar chocolate")
        compose.onNode(hasText("g") and hasClickAction()).performClick()
        compose.onNode(hasText("bar") and hasAnyAncestor(isPopup())).performClick()
        compose.onNode(hasText("Amount") and hasSetTextAction()).performTextReplacement("2")
        waitFor("Protein bar chocolate · 90 g · 342 kcal")
        compose.onNodeWithText("Add").performClick()

        // Back on the Plan where it was, the snack is the bar, in its unit; the day offers foods for every slot, filled or not.
        waitFor(unbrokenParts("2 bar · 342 kcal"))
        compose.onNode(hasText("Add a food…") and inTuesday).performScrollTo().performClick()
        for (slot in listOf("Breakfast", "Lunch", "Dinner", "Snack")) compose.onNode(hasText(slot) and hasAnyAncestor(isPopup())).assertExists()
        compose.onNode(hasText("Breakfast") and hasAnyAncestor(isPopup())).performClick()
        pick("yog", "Greek yoghurt")
        compose.onNodeWithContentDescription("Back").performClick()
        // A second food goes in the snack from the slot itself.
        compose.onNode(hasText("Add another food…") and inTuesday).performScrollTo().performClick()
        // The egg starts in a piece, its weight per piece known.
        pick("egg", "Egg")
        waitFor("Egg · 60 g · 86 kcal")
        compose.onNodeWithText("Add").performClick()
        waitFor(unbrokenParts("1 piece · 86 kcal"))

        assertEquals(listOf(MealFoodInput(40, 2.0, "bar"), MealFoodInput(12, 1.0, "piece")), sent(day = 1))
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(unbrokenParts("1 piece · 86 kcal")))
        compose.onNode(hasText("Protein bar chocolate") and inTuesday, useUnmergedTree = true).assertExists()
    }

    private fun showPlan() {
        compose.setContent { CompositionLocalProvider(LocalStrings provides Strings.English) { PlanScreen(api, { _, _ -> }, {}, {}, {}) } }
        compose.waitForPlan()
    }

    @Test
    fun aSlotOfFoodsOffersWhatFoodsCanDo() {
        showPlan()
        // Monday's snack is two foods alone: its menu has no other recipe and nothing to adjust or write, and removes the slot.
        compose.openSwapMenu("Mon 16 Nov", "snack")
        for (absent in listOf("Another recipe", "Write a new recipe…")) assertEquals(0, compose.onAllNodesWithText(absent).fetchSemanticsNodes().size, absent)
        for (present in listOf("Choose a recipe…", "Add a food…", "Not eating this", "Remove from the plan")) {
            compose.onNode(hasText(present) and hasAnyAncestor(isPopup())).assertExists()
        }
        compose.onNode(hasText("Adjust what was in it") and hasAnyAncestor(isPopup())).assertIsNotEnabled()
        compose.onNode(hasText("Remove from the plan") and hasAnyAncestor(isPopup())).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { "DELETE /api/plans/${FakeBackend.WEEK_START}/meals/0/SNACK" in changes() }
    }

    @Test
    fun aPlannedFoodIsChangedInItsUnitAndTakenOut() {
        showPlan()
        val monday = "Mon 16 Nov"
        val egg = hasText("Egg") and hasClickAction() and hasAnyAncestor(hasAnyChild(hasText(monday)))
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(monday))

        // The egg of Monday's snack opened: its amount is in its own unit, changed to two pieces, then taken out.
        compose.onNode(egg).performScrollTo().performClick()
        val amount = hasText("Amount") and hasSetTextAction()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(amount).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(amount).performTextReplacement("2")
        waitFor("Egg · 120 g · 172 kcal")
        compose.onNodeWithText("Save").performClick()
        waitFor(unbrokenParts("2 pieces · 172 kcal"))
        compose.onNode(egg).performScrollTo().performClick()
        // The row shows once the account's ingredients are loaded.
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(hasContentDescription("Remove Egg")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription("Remove Egg")).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { changes().any { it.startsWith("DELETE /api/plans/${FakeBackend.WEEK_START}/meals/0/SNACK/foods/2") } }

        val put = changes().single { it.startsWith("PUT /api/plans/${FakeBackend.WEEK_START}/meals/0/SNACK/foods/2 ") }
        assertEquals(MealFoodInput(12, 2.0, "piece"), Json.decodeFromString(put.substringAfter("/foods/2 ")))
    }
}
