package dev.tobins.mealplanner.app.plan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import dev.tobins.mealplanner.api.GenerateRequest
import dev.tobins.mealplanner.api.MealPrep
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PrepInput
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.SpareMove
import dev.tobins.mealplanner.api.SparePortion
import dev.tobins.mealplanner.api.StandardChosen
import dev.tobins.mealplanner.api.StandardMeal
import dev.tobins.mealplanner.api.StandardMeals
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.app.LoadState
import dev.tobins.mealplanner.app.ScreenMemory
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.followedDate
import dev.tobins.mealplanner.app.ui.AppError
import dev.tobins.mealplanner.app.ui.Load
import dev.tobins.mealplanner.app.ui.failureSentence
import dev.tobins.mealplanner.app.ui.load
import dev.tobins.mealplanner.app.ui.plusDays
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the plan screen loads, sends and shows while that runs or fails; the composable draws what is here and tells it
 * what the person did. It is a plain class made by the screen (`remember`, with [Place] saved for a re-created
 * activity) and runs in the screen's own [scope], so leaving the screen ends what it was doing, as it always did.
 * It uses no `ViewModel` because the web client has none and the screen keeps nothing
 * that must outlive it: what it loaded stays in the [ScreenMemory].
 *
 * It holds the week shown ([weekStart], [plan]), the shares in the box ([spares]), the week's standard meals
 * ([standard]), my recipes ([myRecipes]), the week after ([nextWeek], while the Prep dialog needs it) and the sheet of
 * standard meals offered for a meal just skipped ([skipped]). Which dialog is open and what is chosen in it stays
 * with the composable.
 *
 * A change ([change]) runs one at a time: while one runs ([busy]) another is ignored, and the screen greys what
 * starts one. What went wrong with a change is said with [say], which returns when the person has seen it; [busy]
 * stays until then. A failed refresh keeps what was shown (see [LoadState.refresh]).
 */
internal class PlanState(
    private val server: PlanServer,
    private val scope: CoroutineScope,
    private val memory: ScreenMemory,
    /** Today's ISO date, as the screen last read it. */
    private val today: () -> String,
    private val strings: () -> Strings,
    /** Shows a sentence to the person (a snackbar) and returns when it is gone. */
    private val say: suspend (String) -> Unit,
    place: Place,
) {
    /**
     * What the screen keeps for a re-created activity: the week that was this week when it last looked, which the shown
     * week follows when it moves on ([followedDate]), and the week shown.
     */
    data class Place(val thisWeek: String, val weekStart: String)

    private var thisWeek = place.thisWeek

    /** The Monday of the week shown. */
    var weekStart: String by mutableStateOf(place.weekStart)
        private set

    /** The shown week's plan as it is loaded, for [RefreshNotice][dev.tobins.mealplanner.app.RefreshNotice]; a new week has a new one. */
    var loaded: LoadState<WeekPlan> by mutableStateOf(LoadState(memory, planKey(place.weekStart)))
        private set

    /** What the screen shows of the week: the spinner, the plan, or why there is none. */
    val plan: Load<WeekPlan> get() = loaded.value

    /** True while a change runs, and while its failure is shown. */
    var busy: Boolean by mutableStateOf(false)
        private set

    /** The shares of skipped prepped meals in the box today. */
    var spares: List<SparePortion> by mutableStateOf(emptyList())
        private set

    /** The shown week's standard meals, for the skip sheet and the picker; null until known and from a server from before them. */
    var standard: StandardMeals? by mutableStateOf(null)
        private set

    /** The meal just skipped with the standard meals offered for its place, until the person chooses or leaves. */
    var skipped: Pair<PlannedMeal, List<StandardMeal>>? by mutableStateOf(null)
        private set

    /** The week after the one shown, while the Prep dialog is open; a batch may be eaten into it. */
    var nextWeek: WeekPlan? by mutableStateOf(null)
        private set

    /** My recipes, null until they are known: the screen works without them. */
    var myRecipes: List<RecipeSummary>? by mutableStateOf(null)
        private set

    /** How often the person asked to load again; a key of the screen's loads. */
    var reloads: Int by mutableIntStateOf(0)
        private set

    /** How many preps were started; my recipes are asked again for each, as what keeps may have been set elsewhere meanwhile. */
    var prepsStarted: Int by mutableIntStateOf(0)
        private set

    val place: Place get() = Place(thisWeek, weekStart)

    /** Loads everything again: the retry of an error or of a failed refresh, and a screen opened over this one closing. */
    fun reload() {
        reloads++
    }

    fun prepStarted() {
        prepsStarted++
    }

    /** Shows [week], which the person stepped to. */
    fun show(week: String) = open(week)

    /**
     * Notices that the week that is this week has moved on (the app was open past Sunday): the week shown follows it
     * when it was the one that was this week. True when it moved, so that the screen shows it from its top.
     */
    fun followToday(now: String): Boolean {
        if (now == thisWeek) return false
        open(followedDate(thisWeek, weekStart, now))
        thisWeek = now
        return true
    }

    /** Shows [plan], which a screen opened over this one made, and asks again for what follows from it. */
    fun showPlanned(plan: WeekPlan) {
        open(plan.weekStart)
        loaded.value = Load.Ready(plan)
        scope.launch {
            refreshSpares()
            refreshStandard()
        }
    }

    private fun open(week: String) {
        if (week == weekStart) return
        weekStart = week
        loaded = LoadState(memory, planKey(week))
        standard = null
        nextWeek = null
    }

    /** Asks for the week again behind what is shown; a failure keeps it (see [LoadState.refresh]). */
    suspend fun refresh() {
        loaded.refresh { server.plan(weekStart) }
    }

    /** Asks for the shares in the box today again; a failure leaves the card as it was, as the plan says what went wrong. */
    suspend fun refreshSpares() {
        (load { server.spareShares(today()) } as? Load.Ready)?.let { spares = it.value }
    }

    /** Asks for the week's standard meals again, as what the plan holds says which slots can take one; a failure keeps those shown. */
    suspend fun refreshStandard() {
        val week = weekStart
        // An answer for a week left meanwhile is not the shown week's.
        (load { server.standardMeals(weekStart) } as? Load.Ready)?.let { if (week == weekStart) standard = it.value }
    }

    /** Asks for my recipes again; without them the screen shows what it can, so a failure says nothing. */
    suspend fun refreshMyRecipes() {
        runCatching { server.myRecipes() }.onSuccess { myRecipes = it }
    }

    /** Asks for the week after the shown one, which the Prep dialog needs. */
    suspend fun loadNextWeek() {
        val week = weekStart
        (load { server.plan(plusDays(weekStart, 7)) } as? Load.Ready)?.let { if (week == weekStart) nextWeek = it.value }
    }

    /**
     * Runs a change on the server and shows the plan it returns, then asks for the spares and standard meals again and runs
     * [then]; a failure is said and the plan stays as it was. Ignored while another change runs.
     */
    private fun change(then: suspend () -> Unit = {}, action: suspend () -> WeekPlan) {
        if (busy) return
        busy = true
        // The week the change was made in gets its answer, though the person steps to another meanwhile.
        val target = loaded
        scope.launch {
            when (val result = load(action)) {
                is Load.Ready -> {
                    target.value = result
                    refreshSpares()
                    refreshStandard()
                    then()
                }
                is Load.Failed -> say(failureSentence(result))
                Load.Loading -> Unit
            }
            busy = false
        }
    }

    fun reroll(meal: PlannedMeal) = change { server.rerollMeal(weekStart, meal.day, meal.slot) }

    fun move(meal: PlannedMeal, toDay: Int) = change { server.moveDinner(weekStart, meal.day, toDay) }

    fun toggleLock(meal: PlannedMeal) = change { server.setMealLocked(weekStart, meal.day, meal.slot, !meal.locked) }

    /** Takes the meal out of the plan; a standard meal over a skipped prepped meal gives the slot back to it. */
    fun remove(meal: PlannedMeal) = change { server.removeMeal(weekStart, meal.day, meal.slot) }

    fun removeSide(meal: PlannedMeal) = change { server.removeSide(weekStart, meal.day, meal.slot) }

    fun removePrep(prep: MealPrep) = change { server.removePrep(weekStart, prep.id) }

    fun eatSpare(spare: SparePortion, slot: PrepSlot) = change { server.eatSpare(weekStart, spare.prepId, SpareMove(spare.skipped, slot)) }

    /** Marks the meal skipped or planned again; skipped, the standard meals that can take its place are offered at once ([skipped]). */
    fun toggleSkipped(meal: PlannedMeal) {
        val status = if (meal.status == MealStatus.SKIPPED) MealStatus.PLANNED else MealStatus.SKIPPED
        val offer: suspend () -> Unit = {
            val choices = standardChoices(standard, meal.day, meal.slot, skipped = meal.recipeId)
            if (status == MealStatus.SKIPPED && choices.isNotEmpty()) skipped = meal to choices
        }
        change(then = offer) {
            server.setMealStatus(weekStart, meal.day, meal.slot, status)
            server.plan(weekStart)
        }
    }

    /** Puts [choice] in the place of the skipped [meal]. */
    fun chooseInPlaceOfSkipped(meal: PlannedMeal, choice: StandardMeal) {
        skipped = null
        change { server.chooseStandard(weekStart, meal.day, meal.slot, choice.recipeId, eaten = false).also(::sayRestocked).plan }
    }

    fun dismissSkipped() {
        skipped = null
    }

    /**
     * Puts the recipe [recipeId] in [day]'s [slot] and shows the plan, by the route of the standard meals when it is one of
     * [standardIds] (it keeps a skipped prepped meal's share in the box); [closePicker] runs once it went in. A refusal, or a
     * server from before the route, is thrown as an [AppError] for the picker to show; the picker then stays open.
     */
    suspend fun choose(day: Int, slot: MealSlot, recipeId: Long, standardIds: Set<Long>, closePicker: () -> Unit) {
        val result = if (recipeId in standardIds) {
            load { server.chooseStandard(weekStart, day, slot, recipeId, eaten = false).also(::sayRestocked).plan }
        } else {
            load { server.chooseRecipe(weekStart, day, slot, recipeId) }
        }
        when (result) {
            is Load.Ready -> {
                loaded.value = Load.Ready(result.value ?: throw AppError(strings()(PlanMsg.PLAN_CHOOSE_UNSUPPORTED)))
                closePicker()
                refreshSpares()
                refreshStandard()
            }
            is Load.Failed -> throw AppError(failureSentence(result))
            Load.Loading -> Unit
        }
    }

    /** Writes a recipe for [meal] and puts it in the plan; what goes wrong is thrown for the dialog that asked. */
    suspend fun generate(meal: PlannedMeal, request: GenerateRequest) {
        loaded.value = Load.Ready(server.generateIntoPlan(weekStart, meal.day, meal.slot, request))
    }

    /** Makes the prep [input] and shows the plan; null when it went through ([closeDialog] ran first), else the sentence for the dialog. */
    suspend fun makePrep(input: PrepInput, closeDialog: () -> Unit): String? = when (val result = load { server.createPrep(weekStart, input) }) {
        is Load.Ready -> {
            loaded.value = result
            closeDialog()
            refreshSpares()
            null
        }
        is Load.Failed -> failureSentence(result)
        Load.Loading -> null
    }

    /** Adds a side of [side] next to a meal from prep [prepId] and shows the plan; null when it went through, else the sentence for the dialog. */
    suspend fun addSide(prepId: Long, side: PrepSlot, closeDialog: () -> Unit): String? = when (val result = load { server.addSide(weekStart, prepId, side) }) {
        is Load.Ready -> {
            loaded.value = result
            closeDialog()
            null
        }
        is Load.Failed -> failureSentence(result)
        Load.Loading -> null
    }

    /** Tells which ingredients choosing a standard meal made staples again, so the shopping list names them to restock. */
    private fun sayRestocked(chosen: StandardChosen) {
        if (chosen.addedStaples.isNotEmpty()) scope.launch { say(strings()(PlanMsg.STANDARD_RESTOCK, chosen.addedStaples.joinToString(", "))) }
    }

    companion object {
        /** The place the screen starts at: this week, or [startWeek], which a tapped notification asks for. */
        fun start(now: String, startWeek: String?) = Place(now, startWeek ?: now)

        /** Saves [Place] as values Android can keep, and makes a new state at that place with [create]. */
        fun saver(create: (Place) -> PlanState): Saver<PlanState, Any> = listSaver(
            save = { listOf(it.place.thisWeek, it.place.weekStart) },
            restore = { (thisWeek, weekStart) -> create(Place(thisWeek as String, weekStart as String)) },
        )

        private fun planKey(week: String) = "plan:$week"
    }
}
