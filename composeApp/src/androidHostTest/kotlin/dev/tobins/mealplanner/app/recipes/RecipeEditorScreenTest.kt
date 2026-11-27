package dev.tobins.mealplanner.app.recipes

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.api.RecipeLineInput
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.FakeBackend
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.ingredients
import dev.tobins.mealplanner.app.ui.headline
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Writing a recipe as rows (issue 342) on the fake server, which saves recipes and looks lines up as the server does:
 * picking from the search, pasting lines, the totals, and editing a recipe taken from the cooking site.
 *
 * The screen is Robolectric's own, and what is tapped is scrolled to first: on a window much taller than a phone a
 * dialog with a text field never lets Compose get idle under Robolectric (seen at 2600 dp), whatever the screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RecipeEditorScreenTest {
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

    private fun showEditor(recipeId: Long?) = compose.setContent {
        CompositionLocalProvider(LocalStrings provides Strings.English) { RecipeEditorScreen(api, recipeId, onDone = { saved = it }, onCancel = {}) }
    }

    private fun waitFor(text: String, substring: Boolean = false) =
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    private fun amounts() = compose.onAllNodes(hasText("Amount") and hasSetTextAction())

    private fun amount(index: Int): SemanticsNodeInteraction = amounts()[index]

    private fun waitForRows(count: Int) = compose.waitUntil(timeoutMillis = 10_000) { amounts().fetchSemanticsNodes().size == count }

    private val search get() = compose.onNode(hasText("Add an ingredient") and hasSetTextAction())

    /** Types [query] in the search and picks [result] from what it finds, which then has a row of its own. */
    private fun pick(query: String, result: String) {
        val rows = amounts().fetchSemanticsNodes().size
        search.performTextReplacement(query)
        val found = hasText(result, substring = true) and hasText("per 100", substring = true) and hasClickAction()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(found).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(found).performScrollTo().performClick()
        waitForRows(rows + 1)
    }

    /** Sets the unit of the last row, showing [shown], to [unit] from its menu. */
    private fun lastUnit(shown: String, unit: String) {
        compose.onAllNodes(hasText(shown) and hasClickAction()).onLast().performScrollTo().performClick()
        compose.onNode(hasText(unit) and hasAnyAncestor(isPopup())).performClick()
    }

    /** Types [text] in the one field of the dialog that is open. */
    private fun typeInDialog(text: String) = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement(text)

    private fun save() {
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { saved != null }
    }

    /** The body of the last [method] of [path], as the app sent it. */
    private fun sent(method: String, path: String): RecipeInput =
        Json.decodeFromString(backend.changes.last { it.startsWith("$method $path ") }.removePrefix("$method $path "))

    /** The text the totals under the title show. */
    private fun totals(): String =
        compose.onNode(hasText("Per serving:", substring = true)).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

    private fun note(text: String) = compose.onNodeWithText(text, substring = true, useUnmergedTree = true)

    @Test
    fun aRecipeOfFiveIngredientsIsMadeBySearchingAndPickingWithoutTypingAUnit() {
        showEditor(null)
        waitFor("Name")
        compose.onNodeWithText("Name").performTextInput("Chicken and rice")
        compose.onNodeWithText("Servings").performTextReplacement("2")
        pick("chick", "Chicken thigh fillet")
        amount(0).assertIsFocused()
        amount(0).performTextReplacement("2")
        pick("yog", "Greek yoghurt")
        amount(1).performTextReplacement("150")
        // Found by its food's Dutch name.
        pick("spinazie", "Spinach")
        pick("olive", "Olive oil")
        lastUnit("g", "ml")
        amount(3).performTextReplacement("15")
        // A food the account does not have: picking it makes it one of its ingredients.
        pick("rice", "Rice white raw")
        note("Rice white raw · 100 g · 350 kcal").assertExists()
        save()

        val input = sent("POST", "/api/recipes")
        val rice = runBlocking { api.ingredients() }.single { it.name == "Rice white raw" }
        assertEquals(listOf(1L, 3L, 8L, 5L, rice.id), input.lines!!.map { it.ingredientId })
        assertEquals(listOf(2.0 to PIECE, 150.0 to "g", 100.0 to "g", 15.0 to "ml", 100.0 to "g"), input.lines!!.map { it.amount to it.unit })
        assertTrue(input.lines!!.none { '|' in it.text } && input.ingredients.isEmpty())
        assertTrue(backend.changes.any { it.startsWith("POST /api/ingredients ") && "\"nevoCode\":500" in it })
        val recipe = runBlocking { api.recipe(saved!!) }
        assertTrue(recipe.summary.nutrition!!.complete)
        assertEquals(rice.id, recipe.ingredients.last().line.ingredientId)
    }

    @Test
    fun anIngredientNeverUsedStartsInPiecesWhenItHasAWeightPerPieceAndInGramsWhenNot() {
        showEditor(null)
        waitFor("Name")
        pick("egg", "Egg")
        pick("yog", "Greek yoghurt")
        note("Egg · 60 g").assertExists()
        note("Greek yoghurt · 100 g").assertExists()
        compose.onAllNodes(hasText(unitLabel(PIECE, Strings.English)) and hasClickAction()).assertCountEquals(1)
        compose.onAllNodes(hasText("g") and hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun anIngredientStartsInTheUnitItWasLastSavedIn() {
        fun recipe(title: String, amount: Double, unit: String) =
            RecipeInput(title, 2, instructions = "Cook.", lines = listOf(RecipeLineInput(amount, unit, "Egg", 12, null)))
        runBlocking {
            api.createRecipe(recipe("Omelette", 120.0, "g"))
            assertEquals("g", api.ingredients().single { it.id == 12L }.lastUnit)
        }
        showEditor(null)
        waitFor("Name")
        pick("egg", "Egg")
        note("Egg · 100 g").assertExists()

        runBlocking {
            api.createRecipe(recipe("Boiled", 2.0, PIECE))
            assertEquals(PIECE, api.ingredients().single { it.id == 12L }.lastUnit)
        }
    }

    @Test
    fun theTotalsShownWhileWritingAreTheSavedRecipes() {
        showEditor(null)
        waitFor("Name")
        compose.onNodeWithText("Name").performTextInput("Coconut chicken")
        compose.onNodeWithText("Servings").performTextReplacement("3")
        pick("chick", "Chicken thigh fillet")
        amount(0).performTextReplacement("3")
        pick("kokos", "Coconut milk")
        amount(1).performTextReplacement("400")
        pick("oil", "Olive oil")
        lastUnit("g", "ml")
        val shown = totals()
        save()
        val nutrition = runBlocking { api.recipe(saved!!) }.summary.nutrition!!
        assertEquals(Strings.English(RecipesMsg.EDITOR_PER_SERVING, nutrition.headline(Strings.English)), shown)
        // The oil changed from 100 g to ml is still 100 g (109.9 ml shown), not a rounded 110 ml.
        assertEquals("Per serving: 755 kcal · 22 g protein", shown.replace(Typography.nbsp, ' '))
    }

    @Test
    fun pastedLinesBecomeRowsAndTheOneThatMeansNothingIsChosenInTheEditor() {
        showEditor(null)
        waitFor("Name")
        compose.onNodeWithText("Name").performTextInput("Bowl")
        paste("200 g kipdijfilet\n2 el olijfolie\n150 g rijst")
        note("Chicken thigh fillet · 200 g · 360 kcal").assertExists()
        note("Olive oil · 27 g").assertExists()
        note("Not counted: tap to choose the ingredient").assertExists()

        compose.onNodeWithText("rijst").performScrollTo().performClick()
        val inDialog = hasAnyAncestor(isDialog())
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(hasText("Rice white raw", substring = true) and inDialog and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Rice white raw", substring = true) and inDialog and hasClickAction()).performScrollTo().performClick()
        waitFor("Ingredient: Rice white raw")
        compose.onNode(hasText("Save") and inDialog).performClick()
        // The ingredient is made as the row is saved.
        waitFor("Rice white raw · 150 g · 525 kcal", substring = true)
        save()

        val lines = sent("POST", "/api/recipes").lines!!
        assertEquals(listOf("kipdijfilet" to "g", "olijfolie" to "tbsp", "rijst" to "g"), lines.map { it.text to it.unit })
        assertTrue(lines.all { it.ingredientId != null }, "no unlinked line")
        assertTrue(runBlocking { api.recipe(saved!!) }.ingredients.all { it.ingredientId != null })
    }

    @Test
    fun aPastedRowLeftAsTextIsSavedAndSaidToBeNotCounted() {
        showEditor(null)
        waitFor("Name")
        compose.onNodeWithText("Name").performTextInput("Bowl")
        paste("200 g kipdijfilet\n2 el olijfolie\n1 tl ras el hanout")
        note("Not counted: tap to choose the ingredient").assertExists()
        waitFor("Incomplete: 1 ingredient is not counted yet (ras el hanout).")
        save()

        assertEquals(RecipeLineInput(1.0, "tsp", "ras el hanout"), sent("POST", "/api/recipes").lines!![2])
        assertEquals(listOf("ras el hanout"), runBlocking { api.recipe(saved!!) }.summary.nutrition!!.uncounted)
    }

    private fun paste(lines: String) {
        compose.onNodeWithText("Paste lines").performScrollTo().performClick()
        typeInDialog(lines)
        val rows = amounts().fetchSemanticsNodes().size
        compose.onNodeWithText("Add as rows").performClick()
        waitForRows(rows + lines.lines().size)
    }

    /** The taken stew as the editor saves it unchanged: each line, tag, the steps and slots as they were read. */
    private fun asRead(recipe: RecipeDetail) = RecipeInput(
        recipe.summary.title, recipe.summary.servings!!, instructions = recipe.instructions, tags = recipe.summary.tags,
        mealSlots = recipe.summary.mealSlots,
        lines = recipe.ingredients.map { RecipeLineInput(it.line.amount, it.line.unit, it.line.item, it.line.ingredientId, it.line.group) },
    )

    /** The editor of recipe [id], and once it is saved the recipe's own screen, as the app opens one after the other. */
    private fun editThenOpen(id: Long) = compose.setContent {
        var editing by remember { mutableStateOf(true) }
        CompositionLocalProvider(LocalStrings provides Strings.English) {
            if (editing) RecipeEditorScreen(api, id, onDone = { saved = it; editing = false }, onCancel = {})
            else RecipeDetailScreen(api, id, null, onBack = {}, onEdit = {})
        }
    }

    @Test
    fun aTakenRecipeWithOneAmountChangedKeepsAllElseAndIsThenDeletedNotPutBack() {
        val before = runBlocking { api.recipe(4) }
        editThenOpen(4)
        waitForRows(6)
        compose.onNodeWithText("Stew").assertExists()
        compose.onNodeWithText("To serve").assertExists()
        compose.onNodeWithText("meal prep").assertExists()
        amount(0).performTextReplacement("800")
        save()

        val expected = asRead(before).let { it.copy(lines = listOf(it.lines!![0].copy(amount = 800.0, ingredientId = 1)) + it.lines!!.drop(1)) }
        assertEquals(expected, sent("PUT", "/api/recipes/4").copy(ingredients = ""))
        assertFalse(backend.changes.any { it.startsWith("PUT /api/recipes/4/keeps") }, "how long it keeps stays as it was")
        val after = runBlocking { api.recipe(4) }
        assertEquals(RecipeKeeps(3, 6), RecipeKeeps(after.summary.keepsDays, after.summary.keepsVacuumDays))

        // Changed in the app, it has no bank to go back to.
        waitFor("In my rotation")
        compose.onNodeWithContentDescription("More").performClick()
        compose.onAllNodesWithText("Remove from my recipes").assertCountEquals(0)
        compose.onNodeWithText("Delete").performClick()
        waitFor("Your copy, with what you changed, is deleted for good.", substring = true)
    }

    @Test
    fun aTakenRecipeOpenedAndSavedAsItWasCanStillGoBackToTheBank() {
        val before = runBlocking { api.recipe(4) }
        editThenOpen(4)
        waitForRows(6)
        save()
        assertEquals(asRead(before), sent("PUT", "/api/recipes/4").copy(ingredients = ""))

        waitFor("In my rotation")
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Remove from my recipes").assertExists()
        compose.onAllNodesWithText("Delete").assertCountEquals(0)
    }

    /** Issue 344: the editor shows 1 for a recipe with no servings, and saving that unchanged is no change, as on the server. */
    @Test
    fun aTakenRecipeWithoutServingsOpenedAndSavedAsItWasCanStillGoBackToTheBank() {
        backend.withoutServings(4)
        editThenOpen(4)
        waitForRows(6)
        save()
        assertEquals(1, sent("PUT", "/api/recipes/4").servings)
        assertNull(runBlocking { api.recipe(4) }.summary.servings)

        waitFor("In my rotation")
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Remove from my recipes").assertExists()
        compose.onAllNodesWithText("Delete").assertCountEquals(0)
    }

    @Test
    fun anEmptyPastedLineIsLeftOutAndTheOthersAreLookedUp() {
        showEditor(null)
        waitFor("Name")
        compose.onNodeWithText("Name").performTextInput("Bowl")
        compose.onNodeWithText("Paste lines").performScrollTo().performClick()
        typeInDialog("200 g kipdijfilet\n| |\n2 el olijfolie")
        compose.onNodeWithText("Add as rows").performClick()
        waitForRows(2)
        note("Chicken thigh fillet · 200 g · 360 kcal").assertExists()
        note("Olive oil · 27 g").assertExists()
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun aFoodPickedInARowsDialogAndThenCancelledIsNoIngredientOfTheAccount() {
        showEditor(null)
        waitFor("Name")
        compose.onNodeWithText("Name").performTextInput("Bowl")
        paste("150 g rijst")
        val before = runBlocking { api.ingredients() }

        compose.onNodeWithText("rijst").performScrollTo().performClick()
        val inDialog = hasAnyAncestor(isDialog())
        val food = hasText("Rice white raw", substring = true) and inDialog and hasClickAction()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(food).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(food).performScrollTo().performClick()
        waitFor("Ingredient: Rice white raw")
        compose.onNode(hasText("Cancel") and inDialog).performClick()

        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(inDialog).fetchSemanticsNodes().isEmpty() }
        note("Not counted: tap to choose the ingredient").assertExists()
        assertEquals(before, runBlocking { api.ingredients() })
        assertFalse(backend.changes.any { it.startsWith("POST /api/ingredients") })
    }

    @Test
    fun tagsGroupsAndHowLongItKeepsAreSetInTheEditor() {
        showEditor(null)
        waitFor("Name")
        compose.onNodeWithText("Name").performTextInput("Eggs on spinach")
        compose.onNode(hasText("Add a tag") and hasSetTextAction()).performTextInput("quick")
        compose.onNode(hasText("Add a tag") and hasSetTextAction()).performImeAction()
        compose.onNodeWithText("New group").performScrollTo().performClick()
        typeInDialog("Eggs")
        compose.onNode(hasText("Save") and hasAnyAncestor(isDialog())).performClick()
        pick("egg", "Egg")
        compose.onNodeWithText("In a box: not prepped", substring = true).performScrollTo().performClick()
        compose.onNode(hasText("2 days") and hasAnyAncestor(isPopup())).performClick()
        save()

        val input = sent("POST", "/api/recipes")
        assertEquals(listOf("quick"), input.tags)
        assertEquals(RecipeLineInput(1.0, PIECE, "Egg", 12, "Eggs"), input.lines!!.single())
        assertEquals("""PUT /api/recipes/$saved/keeps {"days":2,"vacuumDays":null}""", backend.changes.last())
    }

    @Test
    fun theKeyboardMovesFromRowToRowAndThenToTheSearch() {
        showEditor(null)
        waitFor("Name")
        pick("chick", "Chicken thigh fillet")
        pick("yog", "Greek yoghurt")
        amount(1).assertIsFocused()
        amount(0).performScrollTo().performClick()
        amount(0).performImeAction()
        amount(1).assertIsFocused()
        amount(1).performImeAction()
        search.assertIsFocused()
    }

    /** Whether [node] lies wholly within the part of the editor's scrolling column that shows. */
    private fun whollyShown(node: SemanticsNodeInteraction): Boolean {
        val shown = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
        val bounds = node.fetchSemanticsNode().boundsInRoot
        return bounds.top >= shown.top && bounds.bottom <= shown.bottom
    }

    /**
     * On a phone's screen, with rows above the search: the row just picked shows its whole amount field, and what a search
     * finds comes into view by itself while typing, without scrolling by hand. The keyboard is not drawn here; on a phone
     * the scrolling column ends above it ([KeyboardMakesRoom], `imePadding`), so the same holds above the keyboard.
     */
    @Test
    fun theRowPickedAndWhatTheSearchFindsComeIntoViewByThemselves() {
        showEditor(null)
        waitFor("Name")
        for ((query, name) in listOf("chick" to "Chicken thigh fillet", "yog" to "Greek yoghurt", "spin" to "Spinach")) {
            pick(query, name)
            val picked = amount(amounts().fetchSemanticsNodes().size - 1)
            picked.assertIsFocused()
            assertTrue(whollyShown(picked), "the amount field of $name")
        }
        search.performTextReplacement("egg")
        val egg = hasText("Egg") and hasText("per 100", substring = true) and hasClickAction()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(egg).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertTrue(whollyShown(compose.onNode(egg)), "what the search found")
    }
}
