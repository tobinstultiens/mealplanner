package dev.tobins.mealplanner.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
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
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.ImeAction
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.LabelNutrition
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.Portion
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeLineInput
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
 * A product with the values of its label and a named portion (issue 351), made and changed from "Link ingredients" on the
 * fake server, then picked in the recipe editor in its portion.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProductScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val backend = FakeBackend(writing = true)
    private val api = ApiClient(backend.settings)
    private var editing by mutableStateOf(false)
    private var saved: Long? = null

    @After
    fun tearDown() {
        api.close()
        backend.close()
    }

    /** "Link ingredients", or the recipe editor once [editing]. */
    private fun show() = compose.setContent {
        CompositionLocalProvider(LocalStrings provides Strings.English) {
            if (editing) RecipeEditorScreen(api, null, onDone = { saved = it }, onCancel = {}) else MappingScreen(api, onBack = {})
        }
    }

    private fun waitFor(text: String) =
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun field(label: String, index: Int = 0): SemanticsNodeInteraction = compose.onAllNodes(hasText(label) and hasSetTextAction())[index]

    private fun type(label: String, text: String, index: Int = 0) = field(label, index).performScrollTo().performTextReplacement(text)

    private val barLabel = LabelNutrition(FoodBasis.PER_100_G, Nutrition(400.0, 30.0, null, null, null, null, null, null))

    /** The body of the last [method] of [path], as the app sent it. */
    private fun sent(method: String, path: String): IngredientInput =
        Json.decodeFromString(backend.changes.last { it.startsWith("$method $path ") }.removePrefix("$method $path "))

    @Test
    fun aProductIsMadeWithItsLabelAndAPortionAndPickedInThatPortionInTheEditor() {
        show()
        waitFor("New product")
        compose.onNodeWithText("New product").performScrollTo().performClick()
        waitFor("Values per")
        type("Name", "Protein bar")
        type("Energy", "400")
        type("Protein", "30")
        // As a Dutch label writes it.
        type("Salt", "0,5")
        type("Fat", "15")
        compose.onNodeWithText("Add a portion").performScrollTo().performClick()
        type("Name", "bar", index = 1)
        type("Size", "45")
        compose.onNodeWithText("Save").performClick()
        waitFor("Saved Protein bar")

        val made = sent("POST", "/api/ingredients")
        assertEquals(FoodBasis.PER_100_G, made.label!!.basis)
        assertEquals(listOf(400.0, 30.0, 15.0, Salt.sodiumMg(0.5)), with(made.label!!.per100) { listOf(kcal, proteinG, fatG, sodiumMg) })
        assertNull(made.label!!.per100.fibreG, "a value left empty is unknown")
        assertEquals(listOf(Portion("bar", grams = 45.0)), made.portions)
        waitFor("400 kcal per 100 g · portions: bar")

        editing = true
        waitFor("Name")
        compose.onNodeWithText("Name").performTextInput("Snack")
        compose.onNode(hasText("Add an ingredient") and hasSetTextAction()).performTextReplacement("protein")
        val found = hasText("Protein bar") and hasClickAction()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(found).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("400 kcal · 30 g protein per 100 g", substring = true).assertExists()
        compose.onNode(found).performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(hasText("Amount") and hasSetTextAction()).fetchSemanticsNodes().size == 1 }
        compose.onAllNodes(hasText("g") and hasClickAction()).onLast().performScrollTo().performClick()
        compose.onNode(hasText("bar") and hasAnyAncestor(isPopup())).performClick()
        field("Amount").performTextReplacement("2")

        compose.onNodeWithText("Protein bar · 90 g · 360 kcal", substring = true, useUnmergedTree = true).assertExists()
        val totals = compose.onNode(hasText("Per serving:", substring = true)).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        assertEquals("Per serving: 360 kcal · 27 g protein", totals.replace(Typography.nbsp, ' '))
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { saved != null }
        val recipe = Json.decodeFromString<RecipeInput>(backend.changes.last { it.startsWith("POST /api/recipes ") }.removePrefix("POST /api/recipes "))
        val bar = runBlocking { api.ingredients() }.single { it.name == "Protein bar" }
        assertEquals(listOf(RecipeLineInput(2.0, "bar", "Protein bar", bar.id)), recipe.lines)
    }

    /** "Link ingredients" with the form open on a new product. */
    private fun newProduct() {
        show()
        waitFor("New product")
        compose.onNodeWithText("New product").performScrollTo().performClick()
        waitFor("Values per")
    }

    private fun saveButton() = compose.onNode(hasText("Save") and hasClickAction())

    private fun says(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo().assertExists()

    @Test
    fun whatKeepsSaveOffIsSaidAtTheFieldThatCausesIt() {
        newProduct()
        saveButton().assertIsNotEnabled()
        says("Needed: the product's name")
        says("Needed: the energy in kcal from the label")
        type("Name", "Protein bar")
        type("Energy", "400")
        saveButton().assertIsEnabled()

        type("Fat", "-5")
        says("May not be negative")
        saveButton().assertIsNotEnabled()
        type("Fat", "lots")
        says("Not a number")
        type("Fat", "15")
        saveButton().assertIsEnabled()

        compose.onNodeWithText("Add a portion").performScrollTo().performClick()
        says("Needed: a name for the portion")
        says("Needed: its size in g")
        saveButton().assertIsNotEnabled()
        type("Name", "g", index = 1)
        says("“g” is a unit already: give the portion another name")
        type("Name", "bar", index = 1)
        type("Size", "0")
        says("Must be more than 0")
        type("Size", "45")
        saveButton().assertIsEnabled()
        assertEquals(0, compose.onAllNodes(hasText("Needed", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun theKeyboardsNextGoesFromTheSaltIntoThePortionsAndThroughThem() {
        newProduct()
        // Without a portion the salt is the last field: Done.
        assertEquals(ImeAction.Done, field("Salt").fetchSemanticsNode().config[SemanticsProperties.ImeAction])
        compose.onNodeWithText("Add a portion").performScrollTo().performClick()
        compose.onNodeWithText("Add a portion").performScrollTo().performClick()
        assertEquals(ImeAction.Next, field("Salt").fetchSemanticsNode().config[SemanticsProperties.ImeAction])

        field("Salt").performScrollTo().performTextReplacement("0,5")
        field("Salt").performImeAction()
        field("Name", index = 1).assertIsFocused()
        field("Name", index = 1).performImeAction()
        field("Size", index = 0).assertIsFocused()
        field("Size", index = 0).performImeAction()
        field("Name", index = 2).assertIsFocused()
        field("Name", index = 2).performImeAction()
        field("Size", index = 1).assertIsFocused()
        assertEquals(ImeAction.Done, field("Size", index = 1).fetchSemanticsNode().config[SemanticsProperties.ImeAction])
    }

    @Test
    fun removingAPortionAsksFirstAndKeepingItPutsItBack() {
        val bar = runBlocking {
            api.createIngredient(IngredientInput("Protein bar", label = barLabel, portions = listOf(Portion("bar", grams = 45.0), Portion("half", grams = 22.5))))
        }
        show()
        waitFor("Protein bar")
        compose.onNodeWithText("Protein bar").performScrollTo().performClick()
        waitFor("Edit product")
        compose.onNodeWithContentDescription("Remove the portion bar").performScrollTo().performClick()
        saveButton().performClick()
        waitFor("Remove “bar”?")
        compose.onNodeWithText("Recipes with a line in “bar” will no longer count that line, until a portion of that name is added again.").assertExists()
        compose.onNodeWithText("Keep it").performClick()
        waitFor("Edit product")
        assertEquals(listOf("half", "bar"), compose.onAllNodes(hasText("Name") and hasSetTextAction()).fetchSemanticsNodes().drop(1).map { it.config[SemanticsProperties.EditableText].text })
        assertTrue(backend.changes.none { it.startsWith("PUT /api/ingredients/${bar.id} ") }, "nothing saved")

        compose.onNodeWithContentDescription("Remove the portion bar").performScrollTo().performClick()
        saveButton().performClick()
        waitFor("Remove and save")
        compose.onNodeWithText("Remove and save").performClick()
        waitFor("Saved Protein bar")
        assertEquals(listOf(Portion("half", grams = 22.5)), sent("PUT", "/api/ingredients/${bar.id}").portions)
    }

    @Test
    fun aRowInAPortionThatIsGoneSaysSoAndItsMenuDoesNotOfferIt() {
        val recipe = runBlocking {
            val bar = api.createIngredient(IngredientInput("Protein bar", label = barLabel, portions = listOf(Portion("bar", grams = 45.0), Portion("half", grams = 22.5))))
            val made = api.createRecipe(RecipeInput("Snack", 1, lines = listOf(RecipeLineInput(2.0, "bar", "Protein bar", bar.id))))
            api.updateIngredient(bar.id, IngredientInput("Protein bar", label = barLabel, portions = listOf(Portion("half", grams = 22.5))))
            made.summary.id
        }
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides Strings.English) { RecipeEditorScreen(api, recipe, onDone = {}, onCancel = {}) }
        }
        waitFor("Snack")
        compose.onNodeWithText("Protein bar · the portion bar is gone, not counted", substring = true, useUnmergedTree = true).assertExists()
        compose.onAllNodes(hasText("bar") and hasClickAction()).onLast().performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(hasText("half") and hasAnyAncestor(isPopup())).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("bar") and hasAnyAncestor(isPopup())).assertCountEquals(0)
    }

    @Test
    fun aProductIsChangedInItsFormAndKeepsWhatTheFormDoesNotShow() {
        val bar = runBlocking {
            api.createIngredient(IngredientInput("Protein bar", gramsPerPiece = 45.0, isStaple = true, label = null, portions = listOf(Portion("bar", grams = 45.0))))
        }
        show()
        waitFor("Protein bar")
        compose.onNodeWithText("Protein bar").performScrollTo().performClick()
        waitFor("Edit product")
        type("Size", "50")
        compose.onNodeWithContentDescription("In g; tap for the other").performScrollTo().performClick()
        compose.onNodeWithText("100 ml").performScrollTo().performClick()
        type("Energy", "60,5")
        compose.onNodeWithText("Save").performClick()
        waitFor("Saved Protein bar")

        val changed = sent("PUT", "/api/ingredients/${bar.id}")
        assertEquals(listOf(Portion("bar", ml = 50.0)), changed.portions)
        assertEquals(FoodBasis.PER_100_ML to 60.5, changed.label!!.basis to changed.label!!.per100.kcal)
        assertEquals(45.0 to true, changed.gramsPerPiece to changed.isStaple, "kept as it was")
    }
}
