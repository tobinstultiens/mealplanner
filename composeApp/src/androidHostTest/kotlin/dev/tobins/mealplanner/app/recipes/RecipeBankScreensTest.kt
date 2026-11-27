package dev.tobins.mealplanner.app.recipes

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.BackHandler
import dev.tobins.mealplanner.app.FakeBackend
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.plan.PlanScreen
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val isSwitch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

/**
 * The screens of the recipe bank on the fake server: the Recipes tab with its choice, row switches and
 * selection, the bank with its Add buttons, a recipe opened from either, and the plan's words about an
 * empty rotation. The fake answers as the server does, refusals included, and against an older server
 * has no bank and no flags.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RecipeBankScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val backends = mutableListOf<FakeBackend>()
    private val apis = mutableListOf<ApiClient>()

    /** A tall phone, so that every row of a list is on screen: a lazy list only composes the rows it shows. */
    @Before
    fun tallPhone() {
        RuntimeEnvironment.setQualifiers("en-rGB-w411dp-h1600dp")
    }

    @After
    fun tearDown() {
        apis.forEach { it.close() }
        backends.forEach { it.close() }
    }

    private fun backend(olderServer: Boolean = false, emptyWeek: Boolean = false, emptyRecipes: Boolean = false) =
        FakeBackend(emptyWeek = emptyWeek, emptyRecipes = emptyRecipes, olderServer = olderServer).also { backends += it }

    private fun client(backend: FakeBackend) = ApiClient(backend.settings).also { apis += it }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) =
        compose.setContent { CompositionLocalProvider(LocalStrings provides Strings.English) { content() } }

    private fun waitFor(text: String, substring: Boolean = false) =
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForDescription(description: String) =
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }

    private fun waitUntilTrue(what: () -> Boolean) = compose.waitUntil(timeoutMillis = 10_000) { what() }

    private fun showList(api: ApiClient, onOpenBank: () -> Unit = {}) =
        show { RecipeListScreen(api, onOpen = {}, onOpenMapping = {}, onOpenBank = onOpenBank, onNewRecipe = {}) }

    private fun selectFromMenu() {
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Select recipes").performClick()
    }

    @Test
    fun theChoiceShowsEachGroupWithItsNumberAndMealsOnlyWorksWithinIt() {
        val fake = backend()
        showList(client(fake))
        waitFor("Tuna pasta salad")
        waitFor("All (6)")
        waitFor("Rotation (4)")
        waitFor("Not in rotation (2)")

        compose.onNodeWithText("Not in rotation (2)").performClick()
        waitFor("Shakshuka")
        compose.onAllNodesWithText("Overnight oats with blueberries").assertCountEquals(0)
        compose.onNodeWithText("Tuna pasta salad").assertExists()

        compose.onNodeWithText("Rotation (4)").performClick()
        waitFor("Overnight oats with blueberries")
        compose.onAllNodesWithText("Tuna pasta salad").assertCountEquals(0)

        // The sauce is no meal: it shows, and is marked as skipped by the planner, once "Meals only" is off.
        compose.onAllNodesWithText("Peanut sauce").assertCountEquals(0)
        compose.onNodeWithText("Meals only").performClick()
        waitFor("Peanut sauce")
        waitFor("Rotation (5)")
        waitFor("Not a meal: the planner skips it")
    }

    @Test
    fun aRowShowsWhetherItIsInTheRotationAndItsSwitchChangesThatWithoutOpeningIt() {
        val fake = backend()
        var opened = false
        show { RecipeListScreen(client(fake), onOpen = { opened = true }, onOpenMapping = {}, onOpenBank = {}, onNewRecipe = {}) }
        waitFor("Tuna pasta salad")
        compose.onNodeWithContentDescription("Not in the rotation: Tuna pasta salad").assertIsOff()
        compose.onNodeWithContentDescription("In the rotation: Overnight oats with blueberries").assertIsOn()

        compose.onNodeWithContentDescription("Not in the rotation: Tuna pasta salad").performClick()
        waitFor("Rotation (5)")
        waitFor("Not in rotation (1)")
        // The description says what the switch is now, not what it was.
        compose.onNodeWithContentDescription("In the rotation: Tuna pasta salad").assertIsOn()
        compose.onAllNodesWithContentDescription("Not in the rotation: Tuna pasta salad").assertCountEquals(0)
        assertEquals(listOf("""PUT /api/recipes/3/rotation {"inRotation":true}"""), fake.changes)
        assertFalse(opened)
    }

    @Test
    fun aRefusedChangeShowsTheServersSentenceAndTheRowGoesBack() {
        val fake = backend()
        fake.refusing = "“Shakshuka” is planned on Fri 20 Nov"
        showList(client(fake))
        waitFor("Shakshuka")
        compose.onNodeWithContentDescription("Not in the rotation: Shakshuka").performClick()
        waitFor("Could not change the rotation: “Shakshuka” is planned on Fri 20 Nov.")
        compose.onNodeWithContentDescription("Not in the rotation: Shakshuka").assertIsOff()
        waitFor("Not in rotation (2)")
    }

    @Test
    fun aLongPressStartsSelectingAndTheBarChangesTheRotationOfAllSelected() {
        val fake = backend()
        showList(client(fake))
        waitFor("Tuna pasta salad")
        compose.onNodeWithText("Tuna pasta salad").performTouchInput { longClick() }
        waitFor("1 selected")
        compose.onNodeWithText("Shakshuka").performClick()
        waitFor("2 selected")
        // While selecting the rows have no switch to change a single one by accident.
        compose.onAllNodesWithContentDescription("Not in the rotation: Shakshuka").assertCountEquals(0)

        compose.onNodeWithText("Add to rotation").performClick()
        waitFor("2 recipes are in the rotation now")
        waitFor("Rotation (6)")
        assertEquals(listOf("""PUT /api/recipes/rotation {"ids":[3,6],"inRotation":true}"""), fake.changes)
        // Done: the bar is gone and the rows have their switches again.
        compose.onAllNodesWithText("Select all").assertCountEquals(0)
        waitForDescription("In the rotation: Shakshuka")
    }

    @Test
    fun selectAllTakesWhatTheChoiceShowsAndTakingOutOfTheRotationIsOneCall() {
        val fake = backend()
        showList(client(fake))
        waitFor("Tuna pasta salad")
        compose.onNodeWithText("Rotation (4)").performClick()
        selectFromMenu()
        waitFor("0 selected")
        compose.onNodeWithText("Select all").performClick()
        waitFor("4 selected")

        compose.onNodeWithText("Take out of rotation").performClick()
        // The breakfast routine cannot leave the rotation: the server refuses all and the sentence is shown.
        waitFor("is the breakfast routine, so it cannot leave the rotation", substring = true)
        assertEquals(listOf("""PUT /api/recipes/rotation {"ids":[1,2,4,5],"inRotation":false}"""), fake.changes)
        compose.onNodeWithText("4 selected").assertExists()
    }

    @Test
    fun removingFromMyRecipesLeavesOutWhatWasMadeInTheAppAndSaysHowManyStayed() {
        val fake = backend()
        showList(client(fake))
        waitFor("Tuna pasta salad")
        compose.onNodeWithText("Meals only").performClick()
        waitFor("Peanut sauce")
        compose.onNodeWithText("Tuna pasta salad").performTouchInput { longClick() }
        waitFor("1 selected")
        compose.onNodeWithText("Peanut sauce").performClick()
        waitFor("2 selected")

        compose.onNodeWithText("Remove from my recipes").performClick()
        waitFor("1 recipe is back in the bank. 1 recipe made or changed in the app stays", substring = true)
        // Only the recipe from the site was sent: the app-made one is never offered to the put-back route.
        assertEquals(listOf("""PUT /api/recipes/added {"ids":[3],"added":false,"inRotation":true}"""), fake.changes)
        compose.onAllNodesWithText("Tuna pasta salad").assertCountEquals(0)
        compose.onNodeWithText("Peanut sauce").assertExists()
    }

    @Test
    fun removingOnlyRecipesMadeInTheAppSendsNothingAndSaysWhy() {
        val fake = backend()
        showList(client(fake))
        waitFor("Tuna pasta salad")
        compose.onNodeWithText("Meals only").performClick()
        waitFor("Peanut sauce")
        compose.onNodeWithText("Peanut sauce").performTouchInput { longClick() }
        waitFor("1 selected")
        compose.onNodeWithText("Remove from my recipes").performClick()
        waitFor("Nothing was removed", substring = true)
        assertEquals(emptyList(), fake.changes)
    }

    @Test
    fun aRecipeThatIsPlannedCannotBeRemovedAndTheServersSentenceIsShown() {
        val fake = backend()
        showList(client(fake))
        waitFor("Chickpea salad with feta and roasted peppers")
        compose.onNodeWithText("Chickpea salad with feta and roasted peppers").performTouchInput { longClick() }
        waitFor("1 selected")
        compose.onNodeWithText("Remove from my recipes").performClick()
        waitFor("is planned on Fri 20 Nov, so it cannot go back to the bank yet", substring = true)
        // Nothing changed: the recipe is still listed and still selected.
        compose.onNodeWithText("1 selected").assertExists()
        compose.onNodeWithText("Chickpea salad with feta and roasted peppers").assertExists()
    }

    @Test
    fun withNoRecipesTheTabOffersTheBankAndANewRecipe() {
        val fake = backend(emptyRecipes = true)
        var bank = 0
        showList(client(fake), onOpenBank = { bank++ })
        waitFor("No recipes yet. Add some from the recipe bank, or write one yourself.")
        compose.onNodeWithText("New recipe", substring = false).assertExists()
        compose.onNodeWithText("Browse the bank (5)").performClick()
        assertEquals(1, bank)
    }

    @Test
    fun theTabOffersTheBankWithItsNumberAndASyncThatFoundNewRecipesOpensIt() {
        val fake = backend()
        var bank = 0
        showList(client(fake), onOpenBank = { bank++ })
        waitFor("Browse the bank")
        waitFor("5 recipes in the bank")

        compose.onNodeWithContentDescription("Sync the cooking site").performClick()
        waitFor("3 new recipes in the bank")
        compose.onNodeWithText("Show").performClick()
        assertEquals(1, bank)
    }

    @Test
    fun againstAnOlderServerEveryRecipeIsMineAndInTheRotationAndNoBankIsOffered() {
        val fake = backend(olderServer = true)
        showList(client(fake))
        waitFor("Tuna pasta salad")
        waitUntilTrue { "GET /api/recipes/bank" in fake.requests }
        compose.waitForIdle()
        compose.onAllNodesWithText("Browse the bank", substring = true).assertCountEquals(0)
        // The flags are not there to be read: all six meals are in the rotation.
        waitFor("All (6)")
        waitFor("Rotation (6)")
        waitFor("Not in rotation (0)")
        compose.onNodeWithContentDescription("In the rotation: Tuna pasta salad").assertIsOn()
    }

    @Test
    fun againstAnOlderServerAFailedChangeShowsTheErrorLineAndTheRowGoesBack() {
        val fake = backend(olderServer = true)
        showList(client(fake))
        waitFor("Tuna pasta salad")
        compose.onNodeWithContentDescription("In the rotation: Tuna pasta salad").performClick()
        // The older server has no such route (404): the usual error line, and the switch is as it was.
        waitFor("Could not change the rotation:", substring = true)
        compose.onNodeWithContentDescription("In the rotation: Tuna pasta salad").assertIsOn()
    }

    @Test
    fun theBankListsWhatIsNotMineWithAnAddButtonAndAddingMovesItWithUndo() {
        val fake = backend()
        show { RecipeBankScreen(client(fake), onBack = {}, onOpen = {}, onOpenMapping = {}) }
        waitFor("Greek yoghurt pancakes")
        // The recipes of mine are not here, and every section of the site has a chip.
        compose.onAllNodesWithText("Tuna pasta salad").assertCountEquals(0)
        waitFor("All sections")
        waitFor("Breakfast")

        compose.onNodeWithContentDescription("Add Greek yoghurt pancakes to my recipes").performClick()
        waitFor("“Greek yoghurt pancakes” added")
        compose.onAllNodesWithText("Greek yoghurt pancakes").assertCountEquals(0)
        assertEquals(listOf("""PUT /api/recipes/9/added {"added":true,"inRotation":true}"""), fake.changes)

        compose.onNodeWithText("Undo").performClick()
        waitFor("Greek yoghurt pancakes")
        assertEquals("""PUT /api/recipes/9/added {"added":false,"inRotation":true}""", fake.changes.last())
    }

    @Test
    fun addingARecipeWithIngredientsNotLinkedSaysHowManyAndOffersTheLinkingScreen() {
        val fake = backend()
        var linking = 0
        show { RecipeBankScreen(client(fake), onBack = {}, onOpen = {}, onOpenMapping = { linking++ }) }
        waitFor("Harissa chickpea stew")
        compose.onNodeWithContentDescription("Add Harissa chickpea stew to my recipes").performClick()
        waitFor("2 ingredients to link", substring = true)
        waitFor("Link")
        compose.onNodeWithText("Link").performClick()
        assertEquals(1, linking)
    }

    @Test
    fun aSectionAndASearchNarrowTheBankAndSeveralAreAddedWithTheBar() {
        val fake = backend()
        show { RecipeBankScreen(client(fake), onBack = {}, onOpen = {}, onOpenMapping = {}) }
        waitFor("Soba noodle salad")
        compose.onNodeWithText("Dinner").performClick()
        compose.onAllNodesWithText("Soba noodle salad").assertCountEquals(0)
        compose.onNodeWithText("Miso aubergine with sesame rice").assertExists()
        compose.onNodeWithText("All sections").performClick()
        waitFor("Soba noodle salad")

        selectFromMenu()
        waitFor("0 selected")
        compose.onNodeWithText("Soba noodle salad").performClick()
        compose.onNodeWithText("Banana oat cookies").performClick()
        waitFor("2 selected")
        // The Add buttons give way to the checkboxes while selecting.
        compose.onAllNodesWithContentDescription("Add Soba noodle salad to my recipes").assertCountEquals(0)
        compose.onNodeWithText("Add to my recipes").performClick()
        waitFor("2 recipes added")
        assertEquals(listOf("""PUT /api/recipes/added {"ids":[11,12],"added":true,"inRotation":true}"""), fake.changes)
        compose.onAllNodesWithText("Soba noodle salad").assertCountEquals(0)
    }

    @Test
    fun aRecipeOfTheBankIsReadOnlyUntilAddedAndTheChoiceOfTheRotationIsAskedWithTheAdding() {
        val fake = backend()
        show { RecipeDetailScreen(client(fake), 9, null, onBack = {}, onEdit = {}) }
        waitFor("Add to my recipes")
        waitFor("Let the planner pick it")
        waitFor("Ingredients")
        // No rating, rotation, "Plan as" or routine until it is mine.
        compose.onAllNodesWithText("Rating").assertCountEquals(0)
        compose.onAllNodesWithText("Plan as").assertCountEquals(0)
        compose.onAllNodesWithText("In my rotation").assertCountEquals(0)

        compose.onAllNodes(isSwitch).onFirst().performClick()
        compose.onNodeWithText("Add to my recipes").performClick()
        waitFor("“Greek yoghurt pancakes” added")
        assertEquals(listOf("""PUT /api/recipes/9/added {"added":true,"inRotation":false}"""), fake.changes)
        // Now it is one of mine, out of the rotation as asked.
        waitFor("In my rotation")
        waitFor("Only planned when you choose it")
        waitFor("Plan as")
    }

    @Test
    fun aRecipeOfMineHasTheRotationSwitchWithAWordUnderIt() {
        val fake = backend()
        show { RecipeDetailScreen(client(fake), 3, null, onBack = {}, onEdit = {}) }
        waitFor("In my rotation")
        waitFor("Only planned when you choose it")
        compose.onAllNodes(isSwitch).onFirst().assertIsOff().performClick()
        waitFor("The planner may pick this")
        compose.onAllNodes(isSwitch).onFirst().assertIsOn()
        assertEquals(listOf("""PUT /api/recipes/3/rotation {"inRotation":true}"""), fake.changes)
    }

    @Test
    fun aRecipeNoLongerOnTheSiteSaysSoOnItsRow() {
        val fake = backend()
        showList(client(fake))
        waitFor("Shakshuka")
        waitFor("No longer on the cooking site")
        compose.onAllNodesWithText("No longer on the cooking site").assertCountEquals(1)
    }

    @Test
    fun aRecipeNoLongerOnTheSiteSaysSoOnItsScreen() {
        val fake = backend()
        show { RecipeDetailScreen(client(fake), 6, null, onBack = {}, onEdit = {}) }
        waitFor("No longer on the cooking site")
    }

    @Test
    fun aPlannedRecipeCannotBeRemovedFromItsScreenAndTheSentenceIsShown() {
        val fake = backend()
        show { RecipeDetailScreen(client(fake), 2, null, onBack = {}, onEdit = {}) }
        waitFor("In my rotation")
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Remove from my recipes").performClick()
        waitFor("Could not remove: “Chickpea salad with feta and roasted peppers” is planned on Fri 20 Nov", substring = true)
        // Still one of mine.
        compose.onNodeWithText("In my rotation").assertExists()
    }

    @Test
    fun aRecipeFromTheSiteGoesBackToTheBankWithUndoAndAnAppMadeOneOffersDelete() {
        val fake = backend()
        show { RecipeDetailScreen(client(fake), 3, null, onBack = {}, onEdit = {}) }
        waitFor("In my rotation")
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Remove from my recipes").performClick()
        waitFor("“Tuna pasta salad” is back in the bank")
        // It is the bank's recipe now: read-only, with the way to add it again.
        waitFor("Add to my recipes")
        compose.onNodeWithText("Undo").performClick()
        waitFor("In my rotation")
        assertEquals("""PUT /api/recipes/3/added {"added":true,"inRotation":false}""", fake.changes.last())
    }

    /**
     * Removes the tuna pasta salad (out of the rotation to begin with, in it when [inRotation]) from its screen, which turns
     * into the bank's view; then adds it again and says what was sent.
     */
    private fun removeThenAdd(inRotation: Boolean) {
        val id = 3L
        val fake = backend()
        show { RecipeDetailScreen(client(fake), id, null, onBack = {}, onEdit = {}) }
        waitFor("In my rotation")
        if (inRotation) {
            compose.onAllNodes(isSwitch).onFirst().performClick()
            waitFor("The planner may pick this")
        }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Remove from my recipes").performClick()
        waitFor("Add to my recipes")
        waitFor("Let the planner pick it")
        val pick = compose.onAllNodes(isSwitch).onFirst()
        if (inRotation) pick.assertIsOn() else pick.assertIsOff()
        compose.onNodeWithText("Add to my recipes").performClick()
        waitFor("In my rotation")
        assertEquals("""PUT /api/recipes/$id/added {"added":true,"inRotation":$inRotation}""", fake.changes.last())
    }

    @Test
    fun afterARemoveTheBankViewsSwitchIsOffForARecipeOutOfTheRotationAndAddingKeepsIt() = removeThenAdd(inRotation = false)

    @Test
    fun afterARemoveTheBankViewsSwitchIsOnForARecipeInTheRotationAndAddingKeepsIt() = removeThenAdd(inRotation = true)

    @Test
    fun takingOffTheLastTickLeavesSelectingInTheBankAndOnTheRecipesTab() {
        val fake = backend()
        show { RecipeBankScreen(client(fake), onBack = {}, onOpen = {}, onOpenMapping = {}) }
        waitFor("Soba noodle salad")
        selectFromMenu()
        waitFor("0 selected")
        compose.onNodeWithText("Soba noodle salad").performClick()
        waitFor("1 selected")
        compose.onNodeWithText("Soba noodle salad").performClick()
        // Nothing is left to act on: the bar is gone and the rows have their Add buttons again.
        waitForDescription("Add Soba noodle salad to my recipes")
        compose.onAllNodesWithText("0 selected").assertCountEquals(0)
    }

    @Test
    fun takingOffTheLastTickLeavesSelectingOnTheRecipesTab() {
        val fake = backend()
        showList(client(fake))
        waitFor("Tuna pasta salad")
        compose.onNodeWithText("Lentil soup").performTouchInput { longClick() }
        waitFor("1 selected")
        compose.onNodeWithText("Lentil soup").performClick()
        compose.onAllNodesWithText("1 selected").assertCountEquals(0)
        compose.onAllNodesWithText("0 selected").assertCountEquals(0)
        compose.onAllNodesWithText("Recipes").onFirst().assertExists()
    }

    @Test
    fun backWhileSelectingLeavesSelectingFirstAndThenTheScreen() {
        val fake = backend()
        var left = 0
        var dispatcher: androidx.activity.OnBackPressedDispatcher? = null
        // As App has it: the screen's own handler, with the bank's inside it.
        show {
            dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            BackHandler(enabled = true) { left++ }
            RecipeBankScreen(client(fake), onBack = { left++ }, onOpen = {}, onOpenMapping = {})
        }
        waitFor("Soba noodle salad")
        selectFromMenu()
        waitFor("0 selected")
        compose.runOnUiThread { dispatcher!!.onBackPressed() }
        compose.waitForIdle()
        compose.onAllNodesWithText("0 selected").assertCountEquals(0)
        assertEquals(0, left)
        compose.runOnUiThread { dispatcher!!.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, left)
    }

    @Test
    fun aRecipeMadeInTheAppIsDeletedNotPutBack() {
        val fake = backend()
        var left = false
        show { RecipeDetailScreen(client(fake), 7, null, onBack = { left = true }, onEdit = {}) }
        waitFor("In my rotation")
        compose.onNodeWithContentDescription("More").performClick()
        compose.onAllNodesWithText("Remove from my recipes").assertCountEquals(0)
        compose.onNodeWithText("Delete").performClick()
        waitFor("Delete “Peanut sauce”?")
        compose.onNodeWithText("The recipe is deleted for good.").assertExists()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        waitUntilTrue { left }
        assertEquals(listOf("DELETE /api/recipes/7 "), fake.changes)
    }

    @Test
    fun anEmptyPlanBecauseTheRotationIsEmptySaysSoAndLinksToTheRecipesTab() {
        val fake = backend(emptyWeek = true, emptyRecipes = true)
        var recipesTab = 0
        show { PlanScreen(client(fake), onOpenRecipe = { _, _ -> }, onOpenProfile = {}, onOpenRecipes = { recipesTab++ }, onOpenBank = {}) }
        waitFor("Your rotation is empty", substring = true)
        compose.onNodeWithText("Open recipes").performClick()
        assertEquals(1, recipesTab)
    }

    @Test
    fun aPlanWithRecipesInTheRotationKeepsItsOldWordsForEmptySlots() {
        val fake = backend(emptyWeek = true)
        show { PlanScreen(client(fake), onOpenRecipe = { _, _ -> }, onOpenProfile = {}, onOpenRecipes = {}, onOpenBank = {}) }
        waitFor("Nothing planned for this week yet.")
        waitUntilTrue { "GET /api/recipes" in fake.requests }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Your rotation is empty", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun thePickerShowsTheRotationUnderItsHeadingThenTheRestAndABankRow() {
        val fake = backend()
        val api = client(fake)
        var browsed = 0
        show {
            val recipes = androidx.compose.runtime.produceState<List<dev.tobins.mealplanner.api.RecipeSummary>?>(null) { value = api.recipes() }.value
            recipes?.let {
                RecipePickerBody(it, dev.tobins.mealplanner.api.MealSlot.DINNER, onBrowseBank = { browsed++ }, onPick = {})
            }
        }
        waitFor("In my rotation")
        waitFor("My other recipes")
        compose.onNodeWithText("Browse the bank…").performClick()
        assertEquals(1, browsed)
        // A recipe only in the bank is not among them.
        compose.onAllNodesWithText("Greek yoghurt pancakes").assertCountEquals(0)
    }

    @Test
    fun whatMealsOnlyLeavesOutIsSaidWithAWayToShowIt() {
        val fake = backend()
        showList(client(fake))
        waitFor("1 recipe not shown (not a meal)")
        compose.onAllNodesWithText("Peanut sauce").assertCountEquals(0)
        compose.onNodeWithText("Show them").performClick()
        waitFor("Peanut sauce")
        // Everything is shown now, so nothing is left to say.
        compose.onAllNodesWithText("1 recipe not shown (not a meal)").assertCountEquals(0)
    }

    @Test
    fun severalAddedAtOnceSayHowManyIngredientsAreToLinkWithTheWayToTheLinkingScreen() {
        val fake = backend()
        var linking = 0
        show { RecipeBankScreen(client(fake), onBack = {}, onOpen = {}, onOpenMapping = { linking++ }) }
        waitFor("Harissa chickpea stew")
        selectFromMenu()
        compose.onNodeWithText("Harissa chickpea stew").performClick()
        compose.onNodeWithText("Soba noodle salad").performClick()
        waitFor("2 selected")
        compose.onNodeWithText("Add to my recipes").performClick()
        waitFor("2 recipes added. 2 ingredients to link.")
        waitFor("Link")
        compose.onNodeWithText("Link").performClick()
        assertEquals(1, linking)
    }
}
