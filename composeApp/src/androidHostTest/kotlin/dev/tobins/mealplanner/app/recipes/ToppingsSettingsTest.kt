package dev.tobins.mealplanner.app.recipes

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dev.tobins.mealplanner.api.RecipeTopping
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.FakeBackend
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.plan.amountText
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A recipe's optional toppings in its settings (issue 226): added from the catalogue with an amount, changed and
 * removed, each change saving the whole list. The fake's chicken dinner starts with feta (30 g) and an egg.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ToppingsSettingsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val backend = FakeBackend(false, false, false, false)
    private val api = ApiClient(backend.settings)
    private val feta = RecipeTopping(2, "Feta", 30.0)
    private val egg = RecipeTopping(9, "Egg", 60.0, 60.0)

    @After
    fun tearDown() {
        api.close()
        backend.close()
    }

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides Strings.English) {
                RecipeDetailScreen(api, 4, null, onBack = {}, onEdit = {})
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Optional toppings").fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * Waits until the server holds [toppings] for the chicken dinner, in this order, and the screen shows the
     * answer: until then the screen is still saving, and its buttons are off.
     */
    private fun waitForToppings(vararg toppings: RecipeTopping) {
        compose.waitUntil(timeoutMillis = 10_000) { backend.toppings[4] == toppings.toList() }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasContentDescription("Remove ", substring = true)).fetchSemanticsNodes().size == toppings.size &&
                toppings.all { compose.onAllNodes(hasText(it.name) and hasText(amountText(it.grams, it.gramsPerPiece, Strings.English)!!)).fetchSemanticsNodes().size == 1 }
        }
    }

    private fun typeAmount(text: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(text)
        compose.onNodeWithText("Save").performClick()
    }

    @Test
    fun aToppingIsAddedFromTheCatalogueChangedAndRemoved() {
        show()
        compose.onNodeWithText("Feta").performScrollTo().assertExists()
        compose.onNodeWithText("1 piece · 60 g").assertExists()

        compose.onNodeWithText("Add a topping").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Greek yoghurt").fetchSemanticsNodes().isNotEmpty() }
        // Feta is a topping already, so the search leaves it out.
        compose.onNodeWithText("Greek yoghurt").performClick()
        compose.onNodeWithText("How much Greek yoghurt?").assertExists()
        typeAmount("50")
        waitForToppings(feta, egg, RecipeTopping(3, "Greek yoghurt", 50.0))

        compose.onNodeWithText("Feta").performScrollTo().performClick()
        typeAmount("40")
        waitForToppings(feta.copy(grams = 40.0), egg, RecipeTopping(3, "Greek yoghurt", 50.0))

        compose.onNode(hasContentDescription("Remove Egg")).performScrollTo().performClick()
        waitForToppings(feta.copy(grams = 40.0), RecipeTopping(3, "Greek yoghurt", 50.0))
    }
}
