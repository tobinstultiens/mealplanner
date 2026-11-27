package dev.tobins.mealplanner.app.recipes

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSettings
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.Routines
import dev.tobins.mealplanner.api.StandardSwitch
import dev.tobins.mealplanner.api.ToppingInput
import dev.tobins.mealplanner.app.ScreenMemory
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.ui.AppError
import dev.tobins.mealplanner.app.ui.Load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the recipe page loads, sends and shows while that runs or fails, without drawing it. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeStateTest {
    private fun summary(added: Boolean = true, inRotation: Boolean = true) = RecipeSummary(
        4, "Pasta", "Dinner", emptyList(), 2, RecipeSource.COOKING_SITE, RecipeKind.MEAL, plannable = true, added = added, inRotation = inRotation,
    )

    private fun detail(summary: RecipeSummary = summary()) = RecipeDetail(summary, emptyList(), "Boil.", emptyList())

    /** A server that answers after [latency] ms with the recipe it holds, changed by what it is asked, and remembers what it was asked. */
    private class FakeServer(var held: RecipeDetail) : RecipeServer {
        val asked = mutableListOf<String>()
        var latency = 100L

        /** The call, by its name, that fails with this; the others go on answering. */
        val failing = mutableMapOf<String, Exception>()
        var routines = Routines()
        var staples = emptyList<String>()

        private suspend fun <T> call(name: String, answer: () -> T): T {
            asked += name
            delay(latency)
            failing[name]?.let { throw it }
            return answer()
        }

        private fun change(change: (RecipeSummary) -> RecipeSummary) = held.copy(summary = change(held.summary)).also { held = it }

        override suspend fun recipe(id: Long) = call("recipe $id") { held }
        override suspend fun routines() = call("routines") { routines }
        override suspend fun setRecipeAdded(id: Long, added: Boolean, inRotation: Boolean) =
            call("added $added $inRotation") { change { it.copy(added = added, inRotation = inRotation) } }
        override suspend fun setMealStatus(weekStart: String, day: Int, slot: MealSlot, status: MealStatus) {
            call("status $weekStart $day $slot $status") {}
        }
        override suspend fun deleteRecipe(id: Long) = call("delete $id") {}
        override suspend fun rateRecipe(id: Long, rating: Int?) = call("rate $rating") { change { it.copy(rating = rating) } }
        override suspend fun setRecipeRotation(id: Long, inRotation: Boolean) = call("rotation $inRotation") { change { it.copy(inRotation = inRotation) } }
        override suspend fun setStandardMeal(id: Long, standard: Boolean) =
            call("standard $standard") { StandardSwitch(change { it.copy(standardMeal = standard) }, staples) }
        override suspend fun updateRecipeSettings(id: Long, settings: RecipeSettings) =
            call("settings ${settings.mealSlots}") { change { it.copy(kind = settings.kind, mealSlots = settings.mealSlots) } }
        override suspend fun setRecipeKeeps(id: Long, keeps: RecipeKeeps) =
            call("keeps ${keeps.days} ${keeps.vacuumDays}") { change { it.copy(keepsDays = keeps.days, keepsVacuumDays = keeps.vacuumDays) } }
        override suspend fun setRecipeToppings(id: Long, toppings: List<ToppingInput>) = call("toppings ${toppings.size}") { held }
        override suspend fun saveRoutines(routines: Routines) = call("save routines") { routines.also { this.routines = it } }
    }

    /** The sentences the page said, and what it announced, with the answer a person gives to "Undo". */
    private class FakeVoice(val shownFor: Long = 4000) : RecipeVoice {
        val said = mutableListOf<String>()
        val announced = mutableListOf<Pair<String, Int>>()
        val offered = mutableListOf<String>()
        var undoTapped = false

        override suspend fun say(sentence: String) {
            said += sentence
            delay(shownFor)
        }

        override suspend fun announceAdded(message: String, unlinked: Int, onUndo: suspend () -> Unit) {
            announced += message to unlinked
            delay(shownFor)
            if (undoTapped) onUndo()
        }

        override suspend fun offerUndo(message: String): Boolean {
            offered += message
            delay(shownFor)
            return undoTapped
        }
    }

    private class Page(val server: FakeServer, val state: RecipeState, val voice: FakeVoice, val memory: ScreenMemory)

    private fun TestScope.page(
        server: FakeServer,
        memory: ScreenMemory = ScreenMemory(),
        voice: FakeVoice = FakeVoice(),
        scope: CoroutineScope = this,
    ) = Page(server, RecipeState(server, scope, memory, 4, { Strings.English }, voice), voice, memory)

    private fun RecipeState.shown() = (recipe as Load.Ready).value.summary

    @Test
    fun aRecipeLoadsWithTheRoutinesAndIsKeptForThePageThatComesBack() = runTest {
        val server = FakeServer(detail())
        server.routines = Routines(breakfast = 4)
        val page = page(server)
        assertEquals(Load.Loading, page.state.recipe)
        assertNull(page.state.routines)

        page.state.refresh()

        assertEquals("Pasta", page.state.shown().title)
        assertEquals(Routines(breakfast = 4), page.state.routines)
        assertEquals(listOf("recipe 4", "routines"), server.asked)
        // A page that comes back starts from what was shown and asks again behind it.
        assertEquals("Pasta", page(server, page.memory).state.shown().title)
    }

    @Test
    fun aRefreshThatFailsKeepsTheRecipeShownAndTellsThroughTheNoticeNotASnackbar() = runTest {
        val server = FakeServer(detail())
        val page = page(server)
        page.state.refresh()
        server.failing["recipe 4"] = IOException("Unable to resolve host")

        page.state.refresh()

        assertEquals("Pasta", page.state.shown().title)
        assertTrue(page.state.loaded.refreshFailed)
        assertTrue(page.voice.said.isEmpty())
    }

    @Test
    fun aRecipeThatCannotBeLoadedAtAllIsShownAsFailedAndTheRetryAsksAgain() = runTest {
        val server = FakeServer(detail())
        server.failing["recipe 4"] = AppError("There is no such recipe.")
        val page = page(server)

        page.state.refresh()
        assertEquals(Load.Failed("There is no such recipe."), page.state.recipe)

        server.failing.clear()
        page.state.reload()
        assertEquals(1, page.state.reloads)
        page.state.refresh()
        assertEquals("Pasta", page.state.shown().title)
    }

    @Test
    fun takingARecipeIntoMyRecipesShowsTheAnswerAndAnnouncesIt() = runTest {
        val server = FakeServer(detail(summary(added = false)))
        val page = page(server)
        page.state.refresh()
        server.asked.clear()

        page.state.add(inRotation = false)
        runCurrent()
        assertTrue(page.state.acting, "the button is greyed while it goes")
        advanceUntilIdle()

        assertEquals(listOf("added true false"), server.asked)
        assertTrue(page.state.shown().added)
        assertFalse(page.state.shown().inRotation, "the planner was told not to pick it")
        assertEquals(listOf("“Pasta” added" to 0), page.voice.announced)
        assertFalse(page.state.acting)
    }

    @Test
    fun undoingTheAddingPutsTheRecipeBackInTheBank() = runTest {
        val server = FakeServer(detail(summary(added = false)))
        val page = page(server, voice = FakeVoice().apply { undoTapped = true })
        page.state.refresh()
        server.asked.clear()

        page.state.add(inRotation = true)
        advanceUntilIdle()

        assertEquals(listOf("added true true", "added false true"), server.asked)
        assertFalse(page.state.shown().added)
    }

    @Test
    fun removingARecipeShowsTheAnswerRemembersWhatItWasAndOffersUndo() = runTest {
        val server = FakeServer(detail(summary(inRotation = false)))
        val page = page(server)
        page.state.refresh()
        server.asked.clear()
        var remembered: Boolean? = null

        page.state.putBack("Pasta", wasInRotation = false) { remembered = it }
        advanceUntilIdle()

        assertFalse(page.state.shown().added)
        assertEquals(false, remembered)
        assertEquals(listOf("“Pasta” is back in the bank"), page.voice.offered)
        assertEquals(listOf("added false true"), server.asked, "no undo was tapped")
    }

    @Test
    fun undoingTheRemovalAddsTheRecipeAgainAsItWas() = runTest {
        val server = FakeServer(detail(summary(inRotation = false)))
        val page = page(server, voice = FakeVoice().apply { undoTapped = true })
        page.state.refresh()
        server.asked.clear()

        page.state.putBack("Pasta", wasInRotation = false) {}
        advanceUntilIdle()

        assertEquals(listOf("added false true", "added true false"), server.asked)
        assertTrue(page.state.shown().added)
        assertFalse(page.state.shown().inRotation)
    }

    @Test
    fun aFailedAddingKeepsWhatWasShownAndSaysSo() = runTest {
        val server = FakeServer(detail(summary(added = false)))
        val page = page(server)
        page.state.refresh()
        server.failing["added true true"] = IOException("Unable to resolve host")

        page.state.add(inRotation = true)
        advanceUntilIdle()

        assertFalse(page.state.shown().added, "the recipe stays as it was")
        assertEquals(listOf("Could not add: the server could not be reached."), page.voice.said)
        assertTrue(page.voice.announced.isEmpty())
        assertFalse(page.state.acting)
    }

    @Test
    fun aRecipeThatIsPlannedSaysSoWhenPutBackAndStays() = runTest {
        val server = FakeServer(detail())
        val page = page(server)
        page.state.refresh()
        server.failing["added false true"] = AppError("It is in this week's plan.")
        var remembered: Boolean? = null

        page.state.putBack("Pasta", wasInRotation = true) { remembered = it }
        advanceUntilIdle()

        assertTrue(page.state.shown().added)
        assertNull(remembered)
        assertEquals(listOf("Could not remove: It is in this week's plan."), page.voice.said)
        assertTrue(page.voice.offered.isEmpty())
    }

    @Test
    fun aSecondActionWhileOneRunsIsIgnoredAndOneAfterwardsGoesThrough() = runTest {
        val server = FakeServer(detail(summary(added = false)))
        val page = page(server)
        page.state.refresh()
        server.asked.clear()

        page.state.add(inRotation = true)
        runCurrent()
        page.state.add(inRotation = true)
        page.state.putBack("Pasta", true) {}
        page.state.markEaten(MealPlace("2026-11-16", 1, MealSlot.DINNER)) {}
        advanceUntilIdle()
        assertEquals(listOf("added true true"), server.asked)

        page.state.putBack("Pasta", true) {}
        advanceUntilIdle()
        assertEquals(listOf("added true true", "added false true"), server.asked)
    }

    @Test
    fun theMealTheRecipeWasOpenedFromIsMarkedEatenOrTheFailureIsSaid() = runTest {
        val server = FakeServer(detail())
        val page = page(server)
        val place = MealPlace("2026-11-16", 2, MealSlot.DINNER)
        var eaten = false

        page.state.markEaten(place) { eaten = true }
        advanceUntilIdle()
        assertTrue(eaten)
        assertEquals(listOf("status 2026-11-16 2 DINNER EATEN"), server.asked)

        eaten = false
        server.failing["status 2026-11-16 2 DINNER EATEN"] = IOException("Unable to resolve host")
        page.state.markEaten(place) { eaten = true }
        advanceUntilIdle()
        assertFalse(eaten)
        assertEquals(listOf("Could not mark the meal as eaten: the server could not be reached."), page.voice.said)
    }

    @Test
    fun deletingLeavesThePageWhenItIsGoneAndSaysWhyWhenItIsNot() = runTest {
        val server = FakeServer(detail())
        val page = page(server)
        page.state.refresh()
        var left = false
        server.failing["delete 4"] = AppError("It is in this week's plan.")

        page.state.delete { left = true }
        advanceUntilIdle()
        assertFalse(left)
        assertEquals(listOf("Could not delete: It is in this week's plan."), page.voice.said)
        assertEquals("Pasta", page.state.shown().title, "the recipe stays")

        server.failing.clear()
        page.state.delete { left = true }
        advanceUntilIdle()
        assertTrue(left)
    }

    @Test
    fun theRotationTheKeepsAndTheStandardMealAreSavedWhileTheControlsAreGreyed() = runTest {
        val server = FakeServer(detail())
        server.staples = listOf("rice")
        val page = page(server)
        page.state.refresh()

        page.state.setInRotation(false)
        runCurrent()
        assertTrue(page.state.acting)
        advanceUntilIdle()
        assertFalse(page.state.shown().inRotation)

        page.state.setKeeps(RecipeKeeps(days = 3, vacuumDays = 5))
        advanceUntilIdle()
        assertEquals(3, page.state.shown().keepsDays)
        assertEquals(5, page.state.shown().keepsVacuumDays)

        page.state.setStandard(true)
        advanceUntilIdle()
        assertTrue(page.state.shown().standardMeal)
        assertEquals(listOf("rice"), page.state.standardMarked?.addedStaples)

        page.state.setStandard(false)
        advanceUntilIdle()
        assertNull(page.state.standardMarked)
        assertFalse(page.state.acting)
    }

    @Test
    fun aFailedRotationKeepsWhatWasShownAndSaysSo() = runTest {
        val server = FakeServer(detail())
        val page = page(server)
        page.state.refresh()
        server.failing["rotation false"] = IOException("Unable to resolve host")

        page.state.setInRotation(false)
        advanceUntilIdle()

        assertTrue(page.state.shown().inRotation)
        assertEquals(listOf("Could not change the rotation: the server could not be reached."), page.voice.said)
        assertFalse(page.state.acting)
    }

    @Test
    fun theRatingTheSettingsAndTheRoutinesAreSavedAndSayNothingWhenTheyFail() = runTest {
        val server = FakeServer(detail())
        val page = page(server)
        page.state.refresh()

        page.state.rate(4)
        page.state.saveSettings(RecipeSettings(RecipeKind.MEAL, listOf(MealSlot.LUNCH)))
        page.state.saveRoutines(Routines(snack = 4))
        advanceUntilIdle()
        assertEquals(4, page.state.shown().rating)
        assertEquals(listOf(MealSlot.LUNCH), page.state.shown().mealSlots)
        assertEquals(Routines(snack = 4), page.state.routines)
        assertFalse(page.state.acting, "none of them greys the controls")

        server.failing["rate null"] = IOException("Unable to resolve host")
        server.failing["save routines"] = IOException("Unable to resolve host")
        page.state.rate(null)
        page.state.saveRoutines(Routines())
        advanceUntilIdle()
        assertEquals(4, page.state.shown().rating)
        assertEquals(Routines(snack = 4), page.state.routines)
        assertTrue(page.voice.said.isEmpty())
    }

    @Test
    fun theToppingsAreSavedAndTheAnswerIsShownOrTheFailureSaid() = runTest {
        val server = FakeServer(detail())
        val page = page(server)
        page.state.refresh()
        val toppings = listOf(ToppingInput(9, 20.0))

        page.state.toppingsSaved(page.state.saveToppings(toppings))
        assertEquals(listOf("recipe 4", "routines", "toppings 1"), server.asked)
        assertTrue(page.voice.said.isEmpty())

        server.failing["toppings 1"] = IOException("Unable to resolve host")
        page.state.toppingsSaved(page.state.saveToppings(toppings))
        assertEquals("Pasta", page.state.shown().title)
        assertEquals(1, page.voice.said.size)
    }

    @Test
    fun leavingThePageEndsWhatItWasDoing() = runTest {
        val server = FakeServer(detail(summary(added = false)))
        val screen = Job(coroutineContext[Job])
        val page = page(server, scope = CoroutineScope(coroutineContext + screen))
        page.state.refresh()

        page.state.add(inRotation = true)
        runCurrent()
        screen.cancel()
        advanceUntilIdle()

        assertFalse(page.state.shown().added, "the answer of a page that was left is not shown")
        assertTrue(page.voice.announced.isEmpty())
    }
}
