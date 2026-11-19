package dev.tobins.mealplanner.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
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
import kotlin.test.assertTrue

/**
 * The Adjust screen as it is used (issue 226): every edit sends the meal's whole list of changes, an undo
 * sends the list from before, amounts go in pieces or grams, an ingredient of the catalogue is added, a quick
 * add-on with one tap, and a later meal left short is named. The fake's dinner starts with more chicken (250 g,
 * 50 g of Saturday's), the spinach left out and an egg (60 g) added.
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

    /** The spinach's line at the top of the list and the olive oil's just below it, so that both show whether they are left out. */
    private fun showSpinachAndOliveOil() = compose.onNode(hasScrollToNodeAction()).performScrollToKey("8:Spinach")

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
        // The olive oil's line shows it back once the answer to the undo is in; the spinach's stays left out.
        showSpinachAndOliveOil()
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
        showSpinachAndOliveOil()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Put back").fetchSemanticsNodes().size == 2 }
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
        // Until the answer is on the screen it is still saving, and its lines can't be tapped.
        waitFor("300 g · recipe: 200 g")

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

    @Test
    fun aQuickAddOnIsAddedWithOneTapAndCanBeUndone() {
        show()
        // The recipe's toppings first, then what was added before; they load after the meal. They come before the
        // meal's lines, so however long the recipe, a known topping needs no scrolling.
        compose.waitUntil(timeoutMillis = 10_000) { runCatching { scrollTo("Greek yoghurt · 50 g") }.isSuccess }
        val chips = scrollTo("Feta · 30 g").getUnclippedBoundsInRoot()
        val firstLine = scrollTo("Chicken thigh fillet").getUnclippedBoundsInRoot()
        assertTrue(chips.bottom < firstLine.top, "the chips at $chips, the first line at $firstLine")

        scrollTo("Feta · 30 g").performClick()
        waitForChanges(*start.toTypedArray(), MealLineChange(2, 30.0))
        waitFor("Feta added")
        compose.onNodeWithText("Undo").performClick()
        waitForChanges(*start.toTypedArray())
    }

    @Test
    fun aToppingTheMealHasAlreadyIsAddedOnTopOfIt() {
        show()
        // The egg topping, with an egg in the meal already: its chip says it is extra, and adds a second one.
        compose.waitUntil(timeoutMillis = 10_000) { runCatching { scrollTo("+ 1 piece Egg") }.isSuccess }
        scrollTo("+ 1 piece Egg").performClick()
        waitForChanges(MealLineChange(1, 250.0), MealLineChange(8, 0.0), MealLineChange(9, 120.0))
    }

    @Test
    fun theLaterMealLeftShortIsNamedUntilEnoughIsLeft() {
        show()
        val warning = "Chicken thigh fillet: 50 g short for Slow-cooked chicken thighs with coconut, lemon and spinach, dinner Sat 21 Nov"
        scrollTo(warning).assertExists()
        compose.onNodeWithText("This meal used what was at home for it, so the shopping list asks for it again.").assertExists()

        // Back to the recipe's 200 g of chicken, the later dinner has its share again.
        scrollTo("Chicken thigh fillet").performClick()
        typeAmount("200")
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Not enough left for a later meal").fetchSemanticsNodes().isEmpty() }
    }
}
