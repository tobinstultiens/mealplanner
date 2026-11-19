package dev.tobins.mealplanner.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import dev.tobins.mealplanner.api.MealLineChange
import dev.tobins.mealplanner.api.MealSlot
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * The Adjust screen as it is used (issue 226): every edit sends the meal's whole list of changes, an undo
 * sends the list from before, amounts go in pieces or grams, and an ingredient of the catalogue is added.
 * The fake's dinner starts with more chicken (250 g), the spinach left out and an egg (60 g) added.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AdjustMealScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val backend = FakeBackend(false, false, false, false)
    private val api = ApiClient(backend.settings)
    private val start = backend.lineChanges

    @After
    fun tearDown() {
        api.close()
        backend.close()
    }

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides Strings.English) {
                AdjustMealScreen(api, MealPlace(FakeBackend.WEEK_START, 2, MealSlot.DINNER), onBack = {})
            }
        }
        waitFor("Reset to recipe")
    }

    private fun waitFor(text: String) =
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    /** The meal's list scrolled to what [matcher] finds: lines below the first screenful are not drawn until then. */
    private fun scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
        return compose.onNode(matcher)
    }

    private fun scrollTo(text: String) = scrollTo(hasText(text))

    /** Waits until the server holds [changes], in any order. */
    private fun waitForChanges(vararg changes: MealLineChange) =
        compose.waitUntil(timeoutMillis = 10_000) { backend.lineChanges.toSet() == changes.toSet() }

    private fun typeAmount(text: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(text)
        compose.onNodeWithText("Save").performClick()
    }

    @Test
    fun leavingOutALineCanBeUndoneAndALineLeftOutPutBack() {
        show()
        scrollTo(hasContentDescription("Leave out Olive oil")).performClick()
        waitForChanges(*start.toTypedArray(), MealLineChange(5, 0.0))
        waitFor("Olive oil left out")
        compose.onNodeWithText("Undo").performClick()
        waitForChanges(*start.toTypedArray())
        // The olive oil's line shows it back once the answer to the undo is in.
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Put back").fetchSemanticsNodes().size == 1 }

        scrollTo("Put back").performClick()
        waitForChanges(MealLineChange(1, 250.0), MealLineChange(9, 60.0))
    }

    @Test
    fun undoTakesBackOnlyItsOwnEditWhenAnotherWasSavedSince() {
        show()
        scrollTo(hasContentDescription("Leave out Olive oil")).performClick()
        waitForChanges(*start.toTypedArray(), MealLineChange(5, 0.0))
        waitFor("Olive oil left out")
        // While that snackbar shows, the spinach is put back; then the olive oil's Undo is tapped.
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Put back").fetchSemanticsNodes().size == 2 }
        scrollTo(hasText("Spinach")).assertExists()
        compose.onAllNodesWithText("Put back")[0].performClick()
        waitForChanges(MealLineChange(1, 250.0), MealLineChange(9, 60.0), MealLineChange(5, 0.0))
        compose.onNodeWithText("Undo").performClick()
        waitForChanges(MealLineChange(1, 250.0), MealLineChange(9, 60.0))
    }

    @Test
    fun anAmountIsChangedInGramsOrInPieces() {
        show()
        scrollTo("Chicken thigh fillet").performClick()
        typeAmount("300")
        waitForChanges(MealLineChange(1, 300.0), MealLineChange(8, 0.0), MealLineChange(9, 60.0))

        // The egg's field is in pieces: two of them are 120 g.
        scrollTo("Egg").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("2")
        compose.onNodeWithText("= 120 g").assertExists()
        compose.onNodeWithText("Save").performClick()
        waitForChanges(MealLineChange(1, 300.0), MealLineChange(8, 0.0), MealLineChange(9, 120.0))
    }

    @Test
    fun anIngredientOfTheCatalogueIsAddedAndResetTakesEveryChangeAway() {
        show()
        scrollTo("Add an ingredient").performClick()
        waitFor("Feta")
        compose.onNodeWithText("Feta").performClick()
        compose.onNodeWithText("How much Feta?").assertExists()
        typeAmount("30")
        waitForChanges(*start.toTypedArray(), MealLineChange(2, 30.0))

        scrollTo("Reset to recipe").performClick()
        waitForChanges()
        assertEquals("PUT lines {\"lines\":[]}", backend.changes.last())
    }
}
