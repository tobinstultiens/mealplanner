package dev.tobins.mealplanner.app.plan

import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.GenerateRequest
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlanDay
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PrepInput
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.SpareMove
import dev.tobins.mealplanner.api.SparePortion
import dev.tobins.mealplanner.api.StandardChosen
import dev.tobins.mealplanner.api.StandardMeal
import dev.tobins.mealplanner.api.StandardMeals
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.app.ScreenMemory
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.ui.AppError
import dev.tobins.mealplanner.app.ui.Load
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the plan screen loads, sends and shows while that runs or fails, without drawing it. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanStateTest {
    private val monday = "2026-11-16"
    private val nextMonday = "2026-11-23"
    private val lunch = PlannedMeal(1, MealSlot.LUNCH, 7, "Chicken", 1.0, 600.0, 40.0)

    private fun weekOf(start: String, vararg meals: PlannedMeal) = WeekPlan(
        start, DailyTargets(2000, 120),
        listOf(PlanDay(start, meals.toList(), meals.sumOf { it.kcal }, meals.sumOf { it.proteinG })),
        emptyList(),
    )

    /** A server that answers after [latency] ms with the plan it was given, and remembers what it was asked, in order. */
    private class FakeServer(private val weeks: MutableMap<String, WeekPlan>) : PlanServer {
        val asked = mutableListOf<String>()
        var latency = 100L

        /** The call, by its name, that fails with this; the others go on answering. */
        val failing = mutableMapOf<String, Exception>()
        var spares = emptyList<SparePortion>()
        var standard: StandardMeals? = null
        var chosen: StandardChosen? = null
        var recipeAnswer: WeekPlan? = null
        var recipes = emptyList<RecipeSummary>()

        private suspend fun <T> call(name: String, answer: () -> T): T {
            asked += name
            delay(latency)
            failing[name]?.let { throw it }
            return answer()
        }

        private fun week(start: String) = weeks.getValue(start)

        /** Puts [meal] in the answer for [start], as a change the server made. */
        fun change(start: String, change: (WeekPlan) -> WeekPlan): WeekPlan = change(week(start)).also { weeks[start] = it }

        override suspend fun plan(weekStart: String) = call("plan $weekStart") { week(weekStart) }
        override suspend fun spareShares(date: String) = call("spares $date") { spares }
        override suspend fun standardMeals(weekStart: String) = call("standard $weekStart") { standard }
        override suspend fun myRecipes() = call("recipes") { recipes }
        override suspend fun rerollMeal(weekStart: String, day: Int, slot: MealSlot) =
            call("reroll $day $slot") { change(weekStart) { it.copy(days = it.days.map { d -> d.copy(meals = d.meals.map { m -> if (m.slot == slot) m.copy(title = "Swapped", recipeId = 99) else m }) }) } }
        override suspend fun moveDinner(weekStart: String, day: Int, toDay: Int) = call("move $day $toDay") { week(weekStart) }
        override suspend fun setMealLocked(weekStart: String, day: Int, slot: MealSlot, locked: Boolean) =
            call("lock $day $slot $locked") { change(weekStart) { it.copy(days = it.days.map { d -> d.copy(meals = d.meals.map { m -> m.copy(locked = locked) }) }) } }
        override suspend fun setMealStatus(weekStart: String, day: Int, slot: MealSlot, status: MealStatus) {
            call("status $day $slot $status") {
                change(weekStart) { it.copy(days = it.days.map { d -> d.copy(meals = d.meals.map { m -> m.copy(status = status) }) }) }
            }
        }
        override suspend fun removeMeal(weekStart: String, day: Int, slot: MealSlot) = call("remove $day $slot") { week(weekStart) }
        override suspend fun removeSide(weekStart: String, day: Int, slot: MealSlot) = call("remove side $day $slot") { week(weekStart) }
        override suspend fun removePrep(weekStart: String, id: Long) = call("remove prep $id") { week(weekStart) }
        override suspend fun createPrep(weekStart: String, input: PrepInput) = call("create prep") { week(weekStart) }
        override suspend fun addSide(weekStart: String, id: Long, side: PrepSlot) = call("add side $id") { week(weekStart) }
        override suspend fun eatSpare(weekStart: String, id: Long, move: SpareMove) = call("eat spare $id") { week(weekStart) }
        override suspend fun chooseStandard(weekStart: String, day: Int, slot: MealSlot, recipeId: Long, eaten: Boolean) =
            call("standard $day $slot $recipeId") { chosen ?: StandardChosen(week(weekStart)) }
        override suspend fun chooseRecipe(weekStart: String, day: Int, slot: MealSlot, recipeId: Long) = call("choose $day $slot $recipeId") { recipeAnswer }
        override suspend fun generateIntoPlan(weekStart: String, day: Int, slot: MealSlot, request: GenerateRequest) =
            call("generate $day $slot") { week(weekStart) }
    }

    private class Screen(val server: FakeServer, val state: PlanState, val said: MutableList<String>, val memory: ScreenMemory)

    /** A screen of the week of [monday], whose [say] shows each sentence for [shownFor] ms, as a snackbar does. */
    private fun TestScope.screen(
        server: FakeServer,
        memory: ScreenMemory = ScreenMemory(),
        shownFor: Long = 4000,
        place: PlanState.Place = PlanState.start(monday, null),
    ): Screen {
        val said = mutableListOf<String>()
        val state = PlanState(server, this, memory, { monday }, { Strings.English }, { said += it; delay(shownFor) }, place)
        return Screen(server, state, said, memory)
    }

    private fun server(vararg meals: PlannedMeal) = FakeServer(mutableMapOf(monday to weekOf(monday, *meals), nextMonday to weekOf(nextMonday)))

    private fun PlanState.shownMeals() = (plan as Load.Ready).value.days.single().meals

    @Test
    fun aWeekLoadsAndIsKeptForTheScreenThatComesBack() = runTest {
        val screen = screen(server(lunch))
        assertEquals(Load.Loading, screen.state.plan)

        screen.state.refresh()

        assertEquals(listOf(lunch), screen.state.shownMeals())
        assertEquals(listOf("plan $monday"), screen.server.asked)
        // A screen that comes back starts from what was shown, and asks again behind it.
        val back = screen(screen.server, screen.memory)
        assertEquals(listOf(lunch), back.state.shownMeals())
    }

    @Test
    fun theSparesTheStandardMealsAndMyRecipesAreLoadedWithTheWeek() = runTest {
        val server = server(lunch)
        server.spares = listOf(SparePortion(3, 8, "Stew", 1.0, PrepSlot(monday, MealSlot.LUNCH), monday, emptyList()))
        server.standard = StandardMeals(monday, listOf(StandardMeal(5, "Eggs", MealSlot.LUNCH, 1.0, 500.0, 30.0, 4.0)))
        server.recipes = listOf(RecipeSummary(7, "Chicken", "Dinner", emptyList(), 2, RecipeSource.MANUAL, RecipeKind.MEAL, plannable = true))
        val screen = screen(server)

        screen.state.refresh()
        screen.state.refreshSpares()
        screen.state.refreshStandard()
        screen.state.refreshMyRecipes()

        assertEquals(1, screen.state.spares.size)
        assertEquals(listOf(5L), screen.state.standard?.meals?.map { it.recipeId })
        assertEquals(listOf(7L), screen.state.myRecipes?.map { it.id })
    }

    @Test
    fun swappingAMealShowsTheServersAnswerAndAsksForTheRestAgain() = runTest {
        val screen = screen(server(lunch))
        screen.state.refresh()
        screen.server.asked.clear()

        screen.state.reroll(lunch)
        runCurrent()
        assertTrue(screen.state.busy, "the screen greys what starts a change while it runs")
        advanceUntilIdle()

        assertEquals("Swapped", screen.state.shownMeals().single().title)
        assertFalse(screen.state.busy)
        assertEquals(listOf("reroll 1 LUNCH", "spares $monday", "standard $monday"), screen.server.asked)
        assertTrue(screen.said.isEmpty())
    }

    @Test
    fun aFailedChangeKeepsWhatWasShownAndSaysSo() = runTest {
        val server = server(lunch)
        val screen = screen(server)
        screen.state.refresh()
        server.failing["reroll 1 LUNCH"] = IOException("Unable to resolve host")

        screen.state.reroll(lunch)
        advanceUntilIdle()

        assertEquals(listOf(lunch), screen.state.shownMeals(), "the plan stays as it was")
        // Never the exception's own text: a plain reason.
        assertEquals(listOf("The server could not be reached."), screen.said)
        assertFalse(screen.state.busy)
    }

    @Test
    fun aSecondChangeWhileOneRunsIsIgnoredAndOneAfterwardsGoesThrough() = runTest {
        val screen = screen(server(lunch))
        screen.state.refresh()
        screen.server.asked.clear()

        screen.state.toggleLock(lunch)
        runCurrent()
        screen.state.reroll(lunch)
        screen.state.move(lunch, 3)
        advanceUntilIdle()

        assertEquals(listOf("lock 1 LUNCH true", "spares $monday", "standard $monday"), screen.server.asked)
        assertTrue(screen.state.shownMeals().single().locked)

        screen.state.reroll(lunch)
        advanceUntilIdle()
        assertEquals("Swapped", screen.state.shownMeals().single().title)
    }

    @Test
    fun theScreenStaysBusyWhileTheSentenceOfAFailureShows() = runTest {
        val server = server(lunch)
        val screen = screen(server, shownFor = 4000)
        screen.state.refresh()
        server.failing["lock 1 LUNCH true"] = AppError("This meal was eaten already.")
        server.asked.clear()

        screen.state.toggleLock(lunch)
        // The answer is in and the sentence is showing.
        advanceTimeBy(1000)
        assertEquals(listOf("This meal was eaten already."), screen.said)
        assertTrue(screen.state.busy)
        screen.state.reroll(lunch)
        runCurrent()
        assertEquals(listOf("lock 1 LUNCH true"), server.asked, "no other change while the sentence shows")

        advanceUntilIdle()
        assertFalse(screen.state.busy)
    }

    @Test
    fun aFailedRefreshKeepsTheWeekShownAndSaysSo() = runTest {
        val server = server(lunch)
        val screen = screen(server)
        screen.state.refresh()
        server.failing["plan $monday"] = IOException("Unable to resolve host")

        screen.state.refresh()

        assertEquals(listOf(lunch), screen.state.shownMeals())
        assertTrue(screen.state.loaded.refreshFailed)
        assertNull(screen.said.firstOrNull(), "a refresh that fails behind content says it in the line at the bottom, not in a snackbar")
    }

    @Test
    fun aWeekThatCannotBeLoadedAtAllIsShownAsFailed() = runTest {
        val server = server(lunch)
        server.failing["plan $monday"] = AppError("Set up your profile first.")
        val screen = screen(server)

        screen.state.refresh()

        assertEquals(Load.Failed("Set up your profile first."), screen.state.plan)
    }

    @Test
    fun steppingToAnotherWeekShowsThatWeeksOwnAndForgetsTheStandardMealsOfTheLast() = runTest {
        val server = server(lunch)
        server.standard = StandardMeals(monday, emptyList())
        val screen = screen(server)
        screen.state.refresh()
        screen.state.refreshStandard()
        assertNotNull(screen.state.standard)

        screen.state.show(nextMonday)

        assertEquals(nextMonday, screen.state.weekStart)
        assertEquals(Load.Loading, screen.state.plan)
        assertNull(screen.state.standard)
        screen.state.refresh()
        assertEquals(nextMonday, (screen.state.plan as Load.Ready).value.weekStart)
    }

    @Test
    fun theWeekShownFollowsTheWeekThatIsThisWeekWhenItMovesOn() = runTest {
        val screen = screen(server(lunch))
        assertFalse(screen.state.followToday(monday))

        assertTrue(screen.state.followToday(nextMonday))

        assertEquals(nextMonday, screen.state.weekStart)
        screen.state.show(monday)
        // A week the person stepped away to stays when this week moves on again.
        assertFalse(screen.state.followToday(nextMonday))
        assertTrue(screen.state.followToday("2026-11-30"))
        assertEquals(monday, screen.state.weekStart)
    }

    @Test
    fun theWeekIsKeptForARecreatedScreen() = runTest {
        val screen = screen(server(lunch))
        screen.state.show(nextMonday)
        screen.state.followToday("2026-11-30")

        assertEquals(PlanState.Place("2026-11-30", nextMonday), screen.state.place)
        val again = screen(server(lunch), place = screen.state.place)
        assertEquals(nextMonday, again.state.weekStart)
    }

    @Test
    fun skippingAMealOffersTheStandardMealsThatCanTakeItsPlace() = runTest {
        val server = server(lunch)
        server.standard = StandardMeals(
            monday,
            listOf(StandardMeal(5, "Eggs", MealSlot.LUNCH, 1.0, 500.0, 30.0, 4.0), StandardMeal(7, "Chicken", MealSlot.LUNCH, 1.0, 600.0, 40.0, 4.0)),
        )
        val screen = screen(server)
        screen.state.refresh()
        screen.state.refreshStandard()
        server.asked.clear()

        screen.state.toggleSkipped(lunch)
        advanceUntilIdle()

        assertEquals(listOf("status 1 LUNCH SKIPPED", "plan $monday"), server.asked.take(2))
        val (meal, choices) = assertNotNull(screen.state.skipped)
        assertEquals(lunch, meal)
        // Not the recipe that was just skipped.
        assertEquals(listOf(5L), choices.map { it.recipeId })

        screen.state.dismissSkipped()
        assertNull(screen.state.skipped)
    }

    @Test
    fun choosingARecipeFromAnOldServerThrowsTheSentenceForThePickerAndKeepsThePlan() = runTest {
        val screen = screen(server(lunch))
        screen.state.refresh()
        var closed = false

        val failure = assertFailsWith<AppError> { screen.state.choose(1, MealSlot.LUNCH, 9, emptySet()) { closed = true } }

        assertEquals(Strings.English(PlanMsg.PLAN_CHOOSE_UNSUPPORTED), failure.message)
        assertFalse(closed, "the picker stays open")
        assertEquals(listOf(lunch), screen.state.shownMeals())
    }

    @Test
    fun choosingAStandardMealShowsItsPlanAndSaysWhichStaplesToRestock() = runTest {
        val server = server(lunch)
        val answer = weekOf(monday, lunch.copy(title = "Eggs"))
        server.chosen = StandardChosen(answer, addedStaples = listOf("eggs", "feta"))
        val screen = screen(server)
        screen.state.refresh()
        var closed = false

        screen.state.choose(1, MealSlot.LUNCH, 5, standardIds = setOf(5L)) { closed = true }
        advanceUntilIdle()

        assertTrue(closed)
        assertEquals("Eggs", screen.state.shownMeals().single().title)
        assertEquals(1, screen.said.size)
        assertTrue("eggs, feta" in screen.said.single(), screen.said.single())
    }

    @Test
    fun aPrepThatFailsGivesTheDialogItsSentenceAndOneThatWorksClosesItFirst() = runTest {
        val server = server(lunch)
        val screen = screen(server)
        screen.state.refresh()
        val input = PrepInput(8, monday, meals = emptyList())
        server.failing["create prep"] = AppError("Nothing to prep.")
        var closed = false

        assertEquals("Nothing to prep.", screen.state.makePrep(input) { closed = true })
        assertFalse(closed)

        server.failing.clear()
        assertNull(screen.state.makePrep(input) { closed = true })
        assertTrue(closed)
    }
}
