package dev.tobins.mealplanner.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.tobins.mealplanner.api.MealPrep
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.PrepInput
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.SparePortion
import kotlinx.coroutines.launch

/*
 * Prepped meals (issue 234): one recipe made once and eaten over several days, started by hand on the Plan. The
 * server checks every rule again; the dialog only offers what it would take.
 */

/** The days [this] recipe keeps once made, [vacuum] sealed or in a box; null when it is not prepped that way. */
fun RecipeSummary.keeps(vacuum: Boolean): Int? = if (vacuum) keepsVacuumDays else keepsDays

/** My recipes a prep can be made of: meals with amounts that keep one way or the other. */
fun preppable(recipes: List<RecipeSummary>): List<RecipeSummary> =
    recipes.filter { it.added && it.kind == RecipeKind.MEAL && it.plannable && (it.keepsDays != null || it.keepsVacuumDays != null) }

/**
 * The days of the week starting [weekStart] a prep may be made on: from [today] on. None of a week that is over: the
 * server would take a day that has passed, but a batch is made for the days to come.
 */
fun prepDays(weekStart: String, today: String): List<String> = (0..6).map { plusDays(weekStart, it) }.filter { it >= today }

/**
 * What the Prep dialog has chosen so far: the recipe, the day it is made, how it is kept and the meals it is eaten in.
 * [firstDay] is the earliest day it may be made: today ([prepDays]).
 */
data class PrepChoice(
    val recipe: RecipeSummary?,
    val madeOn: String,
    val firstDay: String,
    val vacuum: Boolean = false,
    val meals: Set<PrepSlot> = emptySet(),
) {
    /** The days it keeps the chosen way; null when the recipe does not keep that way. */
    val keeps: Int? get() = recipe?.keeps(vacuum)

    /** The days a meal of it may be on: from the made-on day to the last day it keeps. */
    val days: List<String> get() = keeps?.let { days -> (0..days).map { plusDays(madeOn, it) } }.orEmpty()

    /** Why it can't be made yet, or null when it can. */
    val problem: Msg?
        get() = when {
            madeOn < firstDay -> Msg.PREP_MADE_ON_PAST
            recipe == null -> Msg.PREP_PICK_RECIPE
            keeps == null -> Msg.PREP_NOT_THIS_WAY
            meals.none { it.date == madeOn } -> Msg.PREP_FIRST_ON_MADE_DAY
            meals.size < 2 -> Msg.PREP_TWO_MEALS
            else -> null
        }

    /** With [recipe], made on [madeOn] or kept [vacuum] sealed: the way it can be kept, and only the meals its days still allow. */
    fun with(recipe: RecipeSummary? = this.recipe, madeOn: String = this.madeOn, vacuum: Boolean = this.vacuum): PrepChoice {
        val way = if (recipe?.keeps(vacuum) == null && recipe?.keeps(!vacuum) != null) !vacuum else vacuum
        val next = copy(recipe = recipe, madeOn = madeOn, vacuum = way)
        return next.copy(meals = meals.filterTo(LinkedHashSet()) { it.date in next.days })
    }

    /** With [slot] on [date] eaten from it, or not any more. */
    fun toggled(date: String, slot: MealSlot): PrepChoice {
        val meal = PrepSlot(date, slot)
        return copy(meals = if (meal in meals) meals - meal else meals + meal)
    }

    /** What the server is sent; only once [problem] is null. */
    fun input(): PrepInput = PrepInput(recipe!!.id, madeOn, vacuum, meals.sortedWith(compareBy({ it.date }, { it.slot.ordinal })))
}

/**
 * The choice "Prep a recipe…" opens with: made on the first of [days] (from [today] on, [prepDays]), with the recipe
 * when only one of [recipes] keeps, kept the way it can be (vacuum sealed for one that keeps only that way).
 */
fun newPrep(recipes: List<RecipeSummary>, days: List<String>, today: String): PrepChoice =
    PrepChoice(recipes.singleOrNull(), days.first(), firstDay = today).with()

/** The meals of [prep] as a sentence names them: `Mon 16 Nov lunch, Tue 17 Nov dinner`. */
fun prepMealsText(prep: MealPrep, t: Strings): String =
    prep.meals.joinToString(", ") { "${dayLabel(it.date, t.locale)} ${t.slot(it.slot)}" }

/**
 * What the card of [this] share in the box says under its title on [today]: when it was skipped and until when it keeps,
 * or on its last day that it is to eat today or throw away.
 */
fun SparePortion.whenText(today: String, t: Strings): String =
    if (lastDay == today) t(Msg.SPARE_LAST_DAY)
    else t(Msg.SPARE_KEEPS, "${dayLabel(skipped.date, t.locale)} ${t.slot(skipped.slot)}", dayLabel(lastDay, t.locale))

/**
 * The Prep dialog: [start] as the choice it opens with, [weekDays] the days the batch may be made on ([prepDays]),
 * [recipes] those it can be made of. [onSave] makes it, and says why when the server refuses.
 */
@Composable
fun PrepDialog(
    start: PrepChoice,
    weekDays: List<String>,
    recipes: List<RecipeSummary>,
    onDismiss: () -> Unit,
    onSave: suspend (PrepInput) -> String?,
) {
    val t = LocalStrings.current
    val scope = rememberCoroutineScope()
    var choice by remember { mutableStateOf(start) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(Msg.PREP_TITLE)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PrepChoices(choice, weekDays, recipes) { choice = it; error = null }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && choice.problem == null,
                onClick = {
                    saving = true
                    scope.launch {
                        error = onSave(choice.input())
                        saving = false
                    }
                },
            ) { Text(t(Msg.PREP_SAVE)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(Msg.CANCEL)) } },
    )
}

/**
 * The Prep dialog's body: the recipe, the day it is made, how it is kept, and a row per day it keeps with its lunch and
 * dinner, so no day past the last can be picked. Under it why it can't be made yet. A dialog is a window of its own, so
 * the screenshot tests take this.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrepChoices(choice: PrepChoice, weekDays: List<String>, recipes: List<RecipeSummary>, onChange: (PrepChoice) -> Unit) {
    val t = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
        if (recipes.isEmpty()) {
            Text(t(Msg.PREP_NO_RECIPES), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Text(t(Msg.PREP_RECIPE), style = MaterialTheme.typography.labelLarge)
        Box {
            var open by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(choice.recipe?.title ?: t(Msg.PREP_PICK_RECIPE))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (recipe in recipes) {
                    DropdownMenuItem(text = { Text(recipe.title) }, onClick = { open = false; onChange(choice.with(recipe = recipe)) })
                }
            }
        }
        Text(t(Msg.PREP_MADE_ON), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
            for (day in weekDays) {
                FilterChip(selected = day == choice.madeOn, onClick = { onChange(choice.with(madeOn = day)) }, label = { Text(dayLabel(day, t.locale)) })
            }
        }
        Text(t(Msg.PREP_WAY), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
            for (vacuum in listOf(false, true)) {
                val days = choice.recipe?.keeps(vacuum)
                FilterChip(
                    selected = choice.vacuum == vacuum && days != null,
                    enabled = days != null,
                    onClick = { onChange(choice.with(vacuum = vacuum)) },
                    label = {
                        val length = days?.let { t.plural(Plural.DAYS, it) } ?: t(Msg.KEEPS_NOT)
                        Text(t(if (vacuum) Msg.PREP_VACUUM else Msg.PREP_IN_A_BOX, length))
                    },
                )
            }
        }
        if (choice.days.isNotEmpty()) Text(t(Msg.PREP_EATEN_ON), style = MaterialTheme.typography.labelLarge)
        for (day in choice.days) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                Text(dayLabel(day, t.locale), modifier = Modifier.padding(top = Spacing.Related))
                for (slot in listOf(MealSlot.LUNCH, MealSlot.DINNER)) {
                    FilterChip(
                        selected = PrepSlot(day, slot) in choice.meals,
                        onClick = { onChange(choice.toggled(day, slot)) },
                        label = { Text(t.slot(slot, capitalised = true)) },
                    )
                }
            }
        }
        choice.problem?.let { Text(t(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Asks whether to remove the prep of [title] with all its meals, naming them. */
@Composable
fun RemovePrepDialog(title: String, prep: MealPrep, onDismiss: () -> Unit, onRemove: () -> Unit) {
    val t = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(Msg.REMOVE_PREP_TITLE, title)) },
        text = { RemovePrepText(prep) },
        confirmButton = { TextButton(onClick = onRemove) { Text(t(Msg.REMOVE)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(Msg.CANCEL)) } },
    )
}

/** The removal question's text: every meal of the prep that goes. */
@Composable
fun RemovePrepText(prep: MealPrep) {
    val t = LocalStrings.current
    Text(t(Msg.REMOVE_PREP_TEXT, prepMealsText(prep, t)))
}

/**
 * A skipped prepped meal's share still in the box on [today]: when it was skipped and until when it keeps, or on its
 * last day that it is to eat today or throw away; and the later lunches and dinners with room it can be eaten in.
 */
@Composable
fun SpareCard(spare: SparePortion, today: String, enabled: Boolean, onEat: (PrepSlot) -> Unit) {
    val t = LocalStrings.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
            Text(t(Msg.SPARE_TITLE, spare.title), style = MaterialTheme.typography.titleMedium)
            Text(spare.whenText(today, t), style = MaterialTheme.typography.bodyMedium)
            if (spare.room.isEmpty()) {
                Text(t(Msg.SPARE_NO_ROOM), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Box {
                    var open by remember { mutableStateOf(false) }
                    OutlinedButton(enabled = enabled, onClick = { open = true }) { Text(t(Msg.SPARE_EAT_ON)) }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        for (slot in spare.room) {
                            DropdownMenuItem(
                                text = { Text("${dayLabel(slot.date, t.locale)} · ${t.slot(slot.slot)}") },
                                onClick = { open = false; onEat(slot) },
                            )
                        }
                    }
                }
            }
        }
    }
}
