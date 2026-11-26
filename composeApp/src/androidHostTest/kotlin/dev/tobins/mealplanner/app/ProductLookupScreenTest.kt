package dev.tobins.mealplanner.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.LabelNutrition
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.Portion
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.nutrition.Salt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A product's label looked up (issue 354) in its form on the fake server: by name and picked, by barcode, with a source
 * that fails; and a product made from the recipe editor's search, which comes back as a row of the recipe.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProductLookupScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val backend = FakeBackend(writing = true)
    private val api = ApiClient(backend.settings)
    private var saved: Long? = null

    @After
    fun tearDown() {
        api.close()
        backend.close()
    }

    private fun showMapping() = compose.setContent {
        CompositionLocalProvider(LocalStrings provides Strings.English) { MappingScreen(api, onBack = {}) }
    }

    private fun showEditor() = compose.setContent {
        CompositionLocalProvider(LocalStrings provides Strings.English) { RecipeEditorScreen(api, null, onDone = { saved = it }, onCancel = {}) }
    }

    private fun waitFor(text: String) =
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun field(label: String, index: Int = 0): SemanticsNodeInteraction = compose.onAllNodes(hasText(label) and hasSetTextAction())[index]

    private fun type(label: String, text: String, index: Int = 0) = field(label, index).performScrollTo().performTextReplacement(text)

    private fun shown(label: String, index: Int = 0): String = field(label, index).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun amounts() = compose.onAllNodes(hasText("Amount") and hasSetTextAction())

    private fun says(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo().assertExists()

    private fun tap(text: String) = compose.onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()

    /** The body of the last [method] of [path], as the app sent it. */
    private fun sent(method: String, path: String): IngredientInput =
        Json.decodeFromString(backend.changes.last { it.startsWith("$method $path ") }.removePrefix("$method $path "))

    /** "Link ingredients" with the form open on a new product. */
    private fun newProduct() {
        showMapping()
        waitFor("New product")
        tap("New product")
        waitFor("Values per")
    }

    private fun lookUp(text: String) {
        type("Name or barcode", text)
        tap("Look up")
    }

    @Test
    fun aProductLookedUpByNameAndCorrectedIsSavedWithItsSourceAndThatItWasChangedByHand() {
        newProduct()
        lookUp("drinkyoghurt")
        waitFor("Optimel · 4 stuks · pack of 4")
        says("Optimel · 1 l")
        tap("Optimel Drinkyoghurt framboos")
        waitFor("Values from AH")

        assertEquals("Optimel Drinkyoghurt framboos", shown("Name"))
        assertEquals(listOf("31", "3.1", "0.08", ""), listOf(shown("Energy"), shown("Protein"), shown("Salt"), shown("Fibre")))
        says("Not given: fibre.")
        // The pack as a portion, in ml as the values are.
        assertEquals("pack" to "1000", shown("Name", 1) to shown("Size"))
        compose.onNodeWithContentDescription("In ml; tap for the other").assertExists()

        type("of which sugars", "3.3")
        says("Values from AH, changed by hand")
        compose.onNodeWithText("Save").performClick()
        waitFor("Saved Optimel Drinkyoghurt framboos")

        val made = sent("POST", "/api/ingredients")
        val label = made.label!!
        assertEquals("ah:61758;changed", label.source)
        assertEquals(FoodBasis.PER_100_ML, label.basis)
        assertEquals(Nutrition(31.0, 3.1, 0.0, 0.0, 3.8, 3.3, null, Salt.sodiumMg(0.08)), label.per100)
        assertEquals(listOf(Portion("pack", ml = 1000.0)), made.portions)

        waitFor("31 kcal per 100 ml · AH, changed by hand · portions: pack")
        compose.onNodeWithText("Optimel Drinkyoghurt framboos").performScrollTo().performClick()
        waitFor("Edit product")
        says("Values from AH, changed by hand")
    }

    @Test
    fun aBarcodeFillsTheFormAtOnce() {
        newProduct()
        lookUp("8712800035626")
        waitFor("Values from AH")
        assertEquals("Optimel Drinkyoghurt framboos", shown("Name"))
        assertTrue(backend.requests.contains("GET /api/products/barcode/8712800035626"))
        assertTrue(backend.requests.none { it.startsWith("GET /api/products/search") })
    }

    @Test
    fun aSourceThatFailsIsSaidAndTheProductIsTypedAndSaved() {
        backend.productSourceAnswers = false
        newProduct()
        lookUp("drinkyoghurt")
        waitFor("AH did not answer.")
        says("Type the values from the label.")

        type("Name", "Drinkyoghurt")
        type("Energy", "31")
        compose.onNodeWithText("Save").performClick()
        waitFor("Saved Drinkyoghurt")
        val made = sent("POST", "/api/ingredients")
        assertEquals(31.0, made.label!!.per100.kcal)
        assertNull(made.label!!.source, "typed from the label")
    }

    private fun gone(text: String) =
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }

    @Test
    fun aTextTooShortToLookUpSaysWhatIsNeededAndAChangedTextClearsWhatTheLastLookupSaid() {
        backend.productSourceAnswers = false
        newProduct()
        lookUp("drinkyoghurt")
        waitFor("AH did not answer.")
        field("Name or barcode").performTextReplacement("d")
        gone("AH did not answer.")
        tap("Look up")
        waitFor("Type at least 2 characters of a name, or a barcode.")
        assertEquals(1, backend.requests.count { it.startsWith("GET /api/products/search") }, "nothing more asked")
        field("Name or barcode").performTextReplacement("dr")
        gone("Type at least 2 characters")
    }

    @Test
    fun aLookupWithoutValuesGivesNameAndPackAndTheTypedProductHasNoSource() {
        showMapping()
        for ((barcode, said) in listOf(
            FakeBackend.AH_WITHOUT_TABLE to "AH gives no values per 100 g or 100 ml for it.",
            FakeBackend.OFF_WITHOUT_VALUES to "Open Food Facts gives no values per 100 g or 100 ml for it.",
        )) {
            waitFor("New product")
            tap("New product")
            waitFor("Values per")
            lookUp(barcode)
            waitFor(said)
            says("Type the values from the label.")
            compose.onAllNodesWithText("Values from", substring = true, useUnmergedTree = true).assertCountEquals(0)
            compose.onAllNodesWithText("Open Database Licence", substring = true, useUnmergedTree = true).assertCountEquals(0)
            val name = shown("Name")
            assertEquals("330", shown("Size"), "its pack")

            type("Energy", "42")
            compose.onAllNodesWithText("Values from", substring = true, useUnmergedTree = true).assertCountEquals(0)
            compose.onNodeWithText("Save").performClick()
            waitFor("Saved $name")
            assertNull(sent("POST", "/api/ingredients").label!!.source, "$barcode: the values are the label's")
        }
    }

    @Test
    fun valuesWhoseBasisTheSourceDoesNotSayAreSavedOnlyOnceItIsChosen() {
        newProduct()
        lookUp(FakeBackend.OFF_WITHOUT_BASIS)
        waitFor("Open Food Facts does not say whether the values are per 100 g or per 100 ml: choose below.")
        says("Needed: per 100 g or per 100 ml, as on the label")
        assertEquals("380", shown("Energy"))
        compose.onNode(hasText("Save") and hasClickAction()).assertIsNotEnabled()

        tap("100 g")
        compose.onAllNodesWithText("Needed: per 100 g", substring = true, useUnmergedTree = true).assertCountEquals(0)
        says("Values from Open Food Facts")
        compose.onNodeWithText("Save").performClick()
        waitFor("Saved Snack bar")
        val made = sent("POST", "/api/ingredients").label!!
        assertEquals(FoodBasis.PER_100_G to "off:${FakeBackend.OFF_WITHOUT_BASIS}", made.basis to made.source)
    }

    @Test
    fun aNameAnotherIngredientHasIsSaidAtTheNameAndTheFilledFormIsKept() {
        runBlocking { api.createIngredient(IngredientInput("Optimel Drinkyoghurt framboos", label = LabelNutrition(FoodBasis.PER_100_ML, Nutrition(31.0, null, null, null, null, null, null, null)))) }
        newProduct()
        lookUp("8712800035626")
        waitFor("Values from AH")
        says("There is already an ingredient with this name")
        compose.onNode(hasText("Save") and hasClickAction()).assertIsNotEnabled()

        type("Name", "Optimel framboos")
        compose.onNodeWithText("Save").performClick()
        waitFor("Saved Optimel framboos")
        val made = sent("POST", "/api/ingredients")
        assertEquals("ah:61758" to 31.0, made.label!!.source to made.label!!.per100.kcal, "the looked-up values as they were")
    }

    @Test
    fun aRowOfADrinkChangedBetweenMillilitresAndItsPackKeepsItsQuantity() {
        runBlocking {
            api.createIngredient(
                IngredientInput("Drink yoghurt", label = LabelNutrition(FoodBasis.PER_100_ML, Nutrition(31.0, 3.1, null, null, null, null, null, null)), portions = listOf(Portion("pack", ml = 1000.0))),
            )
        }
        showEditor()
        waitFor("Name")
        compose.onNode(hasText("Add an ingredient") and hasSetTextAction()).performTextReplacement("drink")
        val found = hasText("Drink yoghurt") and hasText("per 100", substring = true) and hasClickAction()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(found).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(found).performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { amounts().fetchSemanticsNodes().size == 1 }
        amounts()[0].performTextReplacement("330")

        compose.onAllNodes(hasText("ml") and hasClickAction()).onLast().performScrollTo().performClick()
        compose.onNode(hasText("pack") and hasAnyAncestor(isPopup())).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { shown("Amount") == "0.33" }
        waitFor("Drink yoghurt · 330 ml · 102 kcal")

        compose.onAllNodes(hasText("pack") and hasClickAction()).onLast().performScrollTo().performClick()
        compose.onNode(hasText("ml") and hasAnyAncestor(isPopup())).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { shown("Amount") == "330" }
        waitFor("Drink yoghurt · 330 ml · 102 kcal")
    }

    @Test
    fun aValueOfThreeDecimalsIsShownAndAProductOpenedAndSavedUnchangedKeepsEveryValueAsItWas() {
        val label = LabelNutrition(FoodBasis.PER_100_G, Nutrition(412.345, 30.0, 0.125, null, null, null, null, Salt.sodiumMg(0.005)), "ah:599562")
        val bar = runBlocking { api.createIngredient(IngredientInput("Protein bar", label = label, portions = listOf(Portion("bar", grams = 45.125)))) }
        showMapping()
        waitFor("Protein bar")
        compose.onNodeWithText("Protein bar").performScrollTo().performClick()
        waitFor("Edit product")
        assertEquals(listOf("0.005", "412.345"), listOf(shown("Salt"), shown("Energy")))
        says("Values from AH")
        compose.onNodeWithText("Save").performClick()
        waitFor("Saved Protein bar")

        val again = sent("PUT", "/api/ingredients/${bar.id}")
        assertEquals(label, again.label)
        assertEquals(listOf(Portion("bar", grams = 45.125)), again.portions)
    }

    @Test
    fun aTextTheEditorsSearchDoesNotFindBecomesANewProductThatIsARowOfTheRecipe() {
        showEditor()
        waitFor("Name")
        compose.onNodeWithText("Name").performTextInput("Breakfast")
        val search = compose.onNode(hasText("Add an ingredient") and hasSetTextAction())
        // A row there already, picked from the catalogue.
        search.performTextReplacement("chick")
        val chicken = hasText("Chicken thigh fillet") and hasText("per 100", substring = true) and hasClickAction()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(chicken).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(chicken).performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { amounts().fetchSemanticsNodes().size == 1 }
        search.performTextReplacement("skyr")
        waitFor("No ingredient or food found.")

        // Cancelled, the editor is as it was.
        tap("New product “skyr”")
        waitFor("Values per")
        compose.onNodeWithContentDescription("Cancel").performClick()
        waitFor("Add an ingredient")
        assertEquals("Breakfast", shown("Name"))

        tap("New product “skyr”")
        waitFor("Values per")
        assertEquals("skyr" to "skyr", shown("Name") to shown("Name or barcode"))
        type("Name", "Skyr")
        type("Energy", "63")
        type("Protein", "11")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { amounts().fetchSemanticsNodes().size == 2 }
        assertEquals("Breakfast", shown("Name"))
        // As after picking any ingredient, the new row's amount is the one typed in.
        compose.waitForIdle()
        amounts()[1].assertIsFocused()

        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { saved != null }
        val skyr = runBlocking { api.ingredients() }.single { it.name == "Skyr" }
        val recipe = Json.decodeFromString<RecipeInput>(backend.changes.last { it.startsWith("POST /api/recipes ") }.removePrefix("POST /api/recipes "))
        assertEquals(listOf(1L, skyr.id), recipe.lines!!.map { it.ingredientId })
        assertEquals("Breakfast", recipe.title)
    }
}
