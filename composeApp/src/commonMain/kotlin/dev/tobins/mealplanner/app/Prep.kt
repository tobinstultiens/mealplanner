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
import androidx.compose.material3.Button
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
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PrepInput
import dev.tobins.mealplanner.api.PrepMeal
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.SparePortion
import dev.tobins.mealplanner.api.WeekPlan
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
 * The days of the week starting [weekStart] a prep may be made on: from [today] on. None of a week that is over: a batch
 * is made for the days to come, and the server refuses a day that has passed.
 */
fun prepDays(weekStart: String, today: String): List<String> = (0..6).map { plusDays(weekStart, it) }.filter { it >= today }

/**
 * What the Prep dialog has chosen so far: the recipe, the day it is made, how it is kept and the lunches and dinners it is
 * eaten in, each as the meal itself or as a side of [PrepSlot.side] servings next to the meal planned there, which keeps
 * its own (issue 234, step 2): one batch may be both (issue 269). [side] is how a lunch or dinner picked next is eaten,
 * the dialog's "Eaten as": as the meal (null) or as a side of that many servings. [firstDay] is the earliest day it may be
 * made: today ([prepDays]).
 */
data class PrepChoice(
    val recipe: RecipeSummary?,
    val madeOn: String,
    val firstDay: String,
    val vacuum: Boolean = false,
    val meals: Set<PrepSlot> = emptySet(),
    val side: Double? = null,
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
            // A batch is made once for several meals; as a side that may be one meal short, so say why.
            meals.size < 2 -> if (meals.any { it.side == null }) Msg.PREP_TWO_MEALS else Msg.PREP_TWO_SIDES
            else -> null
        }

    /** With [recipe], made on [madeOn] or kept [vacuum] sealed: the way it can be kept, and only the meals its days still allow. */
    fun with(recipe: RecipeSummary? = this.recipe, madeOn: String = this.madeOn, vacuum: Boolean = this.vacuum): PrepChoice {
        val way = if (recipe?.keeps(vacuum) == null && recipe?.keeps(!vacuum) != null) !vacuum else vacuum
        val next = copy(recipe = recipe, madeOn = madeOn, vacuum = way)
        return next.copy(meals = meals.filterTo(LinkedHashSet()) { it.date in next.days })
    }

    /** The lunch or dinner at [date]'s [slot] as picked, with how it is eaten; null when it is not picked. */
    fun picked(date: String, slot: MealSlot): PrepSlot? = meals.firstOrNull { it.date == date && it.slot == slot }

    /** With [slot] on [date] eaten from it the way [side] says, or not any more when it was picked that way already. */
    fun toggled(date: String, slot: MealSlot): PrepChoice {
        val old = picked(date, slot)
        val others = meals.filterTo(LinkedHashSet()) { it != old }
        return copy(meals = if (old != null && old.side == side) others else others + PrepSlot(date, slot, side))
    }

    /** Whether [slot] on [date] can be eaten the way [side] says: as a side only next to a meal of [hosts] ([sideHosts]). */
    fun canTake(date: String, slot: MealSlot, hosts: Set<PrepSlot>): Boolean = side == null || PrepSlot(date, slot) in hosts

    /**
     * After a tap on [slot] on [date]: [toggled] where it [canTake] the way [side] says; else, when it was picked another
     * way, no longer picked, so that a slot picked can always be taken off.
     */
    fun tapped(date: String, slot: MealSlot, hosts: Set<PrepSlot>): PrepChoice =
        if (canTake(date, slot, hosts)) toggled(date, slot) else copy(meals = meals.filterTo(LinkedHashSet()) { it != picked(date, slot) })

    /** With the lunches and dinners picked next eaten as the meals themselves ([side] null) or as a side of [side] servings. */
    fun eatenAs(side: Double?): PrepChoice = copy(side = side)

    /** What the server is sent, each meal saying how it is eaten (issue 269); only once [problem] is null. */
    fun input(): PrepInput = PrepInput(recipe!!.id, madeOn, vacuum, meals.sortedWith(compareBy({ it.date }, { it.slot.ordinal })))
}

/**
 * The choice "Prep a recipe…" opens with: made on the first of [days] (from [today] on, [prepDays]), with the recipe
 * when only one of [recipes] keeps, kept the way it can be (vacuum sealed for one that keeps only that way).
 */
fun newPrep(recipes: List<RecipeSummary>, days: List<String>, today: String): PrepChoice =
    PrepChoice(recipes.singleOrNull(), days.first(), firstDay = today).with()

/** Made, so cooked and taken from the stock: one of its meals (or a meal with one of its sides) was eaten. The server then refuses to remove it. */
val MealPrep.made: Boolean get() = meals.any { it.status == MealStatus.EATEN }

/**
 * The meals of [prep] as a sentence names them, each with what sets it apart: `Mon 16 Nov lunch (eaten), Tue 17 Nov dinner
 * (side, skipped)`. So a question about the prep shows which of them were eaten or skipped already.
 */
fun prepMealsText(prep: MealPrep, t: Strings): String = prepMealsText(prep.meals, t, noteSides = true)

/** [meals] of a prep as [prepMealsText] names them; a side noted as one only with [noteSides]. */
private fun prepMealsText(meals: List<PrepMeal>, t: Strings, noteSides: Boolean): String =
    meals.joinToString(", ") { meal ->
        val name = "${dayLabel(meal.date, t.locale)} ${t.slot(meal.slot)}"
        val notes = listOfNotNull(
            Msg.PREP_NOTE_SIDE.takeIf { noteSides && meal.side },
            Msg.PREP_NOTE_EATEN.takeIf { meal.status == MealStatus.EATEN },
            Msg.PREP_NOTE_SKIPPED.takeIf { meal.status == MealStatus.SKIPPED },
        )
        if (notes.isEmpty()) name else t(Msg.PREP_MEAL_NOTES, name, notes.joinToString(", ") { t(it) })
    }

/**
 * Whether [this] week has meals of its own: not only those of prepped batches, made in it or in an earlier week, which are
 * there before the week is planned (issue 263). Only such a week is planned already, and only its empty slots say a recipe
 * is missing: before that, the server's empty slots are merely those the batches leave.
 */
val WeekPlan.hasOwnMeals: Boolean
    get() = days.any { day -> day.meals.any { it.prep == null } }

/**
 * The shares in the box ([spares], those of [today]) the week starting [weekStart] shows a card for: the weeks from today's
 * to that of the share's last day, where it can still be eaten. Not a week that is over, nor one after it keeps (issue 263).
 */
fun sparesShown(spares: List<SparePortion>, weekStart: String, today: String): List<SparePortion> =
    spares.filter { weekStart <= it.lastDay && plusDays(weekStart, 6) >= today }

/** `Wed 18 Nov · dinner`: a lunch or dinner a share in the box can go to. */
fun slotLabel(slot: PrepSlot, t: Strings): String = "${dayLabel(slot.date, t.locale)} · ${t.slot(slot.slot)}"

/** What the card of [this] share in the box is titled: a portion of the recipe, or a side. */
fun SparePortion.titleText(t: Strings): String = t(if (side) Msg.SPARE_SIDE_TITLE else Msg.SPARE_TITLE, title)

/**
 * What the card of [this] share in the box says under its title on [today]: when it was skipped and until when it keeps,
 * or on its last day that it is to eat today or throw away.
 */
fun SparePortion.whenText(today: String, t: Strings): String =
    if (lastDay == today) t(Msg.SPARE_LAST_DAY)
    else t(Msg.SPARE_KEEPS, "${dayLabel(skipped.date, t.locale)} ${t.slot(skipped.slot)}", dayLabel(lastDay, t.locale))

/**
 * The Prep dialog: [start] as the choice it opens with, [weekDays] the days the batch may be made on ([prepDays]),
 * [recipes] those it can be made of, [hosts] the meals a side can go next to ([sideHosts]). [onSave] makes it, and
 * says why when the server refuses.
 */
@Composable
fun PrepDialog(
    start: PrepChoice,
    weekDays: List<String>,
    recipes: List<RecipeSummary>,
    hosts: Set<PrepSlot>,
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
                PrepChoices(choice, weekDays, recipes, hosts) { choice = it; error = null }
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
 * The Prep dialog's body: the recipe, the day it is made, how it is kept, how the lunches and dinners picked next are
 * eaten (as the meal, or as a side next to it), and a row per day it keeps with its lunch and dinner, so no day past the
 * last can be picked. Each picked one says how it is eaten, so one batch can be both (issue 269); as a side, only the
 * meals of [hosts] can be picked. Under it why it can't be made yet. A dialog is a window of its own, so the screenshot
 * tests take this.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrepChoices(choice: PrepChoice, weekDays: List<String>, recipes: List<RecipeSummary>, hosts: Set<PrepSlot>, onChange: (PrepChoice) -> Unit) {
    val t = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
        if (recipes.isEmpty()) {
            Text(t(Msg.PREP_NO_RECIPES), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        // What a prep needs and what it does to the slots, before anything is picked: an empty week says nothing else.
        if (choice.side == null) {
            Text(t(Msg.PREP_MEAL_HINT), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text(t(Msg.PREP_EATEN_AS), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
            for (side in listOf(null) + SIDE_PORTIONS) {
                FilterChip(
                    selected = choice.side == side,
                    onClick = { onChange(choice.eatenAs(side)) },
                    label = { Text(side?.let { t(Msg.PREP_AS_SIDE, servingsText(it, t)) } ?: t(Msg.PREP_AS_MEAL)) },
                )
            }
        }
        Text(t(Msg.PREP_EATEN_AS_HINT), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (choice.side != null) {
            Text(t(Msg.PREP_SIDE_HINT), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (choice.days.isNotEmpty()) Text(t(Msg.PREP_EATEN_ON), style = MaterialTheme.typography.labelLarge)
        for (day in choice.days) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                Text(dayLabel(day, t.locale), modifier = Modifier.padding(top = Spacing.Related))
                for (slot in listOf(MealSlot.LUNCH, MealSlot.DINNER)) {
                    val picked = choice.picked(day, slot)
                    FilterChip(
                        selected = picked != null,
                        enabled = picked != null || choice.canTake(day, slot, hosts),
                        onClick = { onChange(choice.tapped(day, slot, hosts)) },
                        label = { Text(prepSlotText(slot, picked?.side, t)) },
                    )
                }
            }
        }
        choice.problem?.let { Text(t(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A lunch or dinner of the Prep dialog as it is eaten: `Dinner`, or as a side `Dinner · side 0.5`. */
fun prepSlotText(slot: MealSlot, side: Double?, t: Strings): String {
    val name = t.slot(slot, capitalised = true)
    return side?.let { t(Msg.PREP_SLOT_SIDE, name, formatAmount(it, t.locale)) } ?: name
}

/**
 * Asks whether to remove the prep of [title] with all its meals, naming them. A prep that was [made] can't be removed (the
 * server refuses), so then it says so beforehand, and what to do instead, with nothing to confirm (issue 263).
 */
@Composable
fun RemovePrepDialog(title: String, prep: MealPrep, onDismiss: () -> Unit, onRemove: () -> Unit) {
    val t = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(if (prep.made) Msg.REMOVE_PREP_MADE_TITLE else Msg.REMOVE_PREP_TITLE, title)) },
        text = { RemovePrepText(prep) },
        confirmButton = {
            if (prep.made) TextButton(onClick = onDismiss) { Text(t(Msg.CLOSE)) } else TextButton(onClick = onRemove) { Text(t(Msg.REMOVE)) }
        },
        dismissButton = { if (!prep.made) TextButton(onClick = onDismiss) { Text(t(Msg.CANCEL)) } },
    )
}

/** The removal dialog's text ([removePrepText]). */
@Composable
fun RemovePrepText(prep: MealPrep) {
    Text(removePrepText(prep, LocalStrings.current))
}

/**
 * What removing [prep] does, naming its meals: every meal goes, of a prep eaten as sides every side, as the meals they go
 * with stay. Of one eaten both ways (issue 269) in two parts, as two different things happen: the meals that go, and the
 * meals that stay with their main and lose the side. Of a prep that was [made]: that it stays, and its meals are to be
 * skipped instead, in the words of how it is eaten.
 */
fun removePrepText(prep: MealPrep, t: Strings): String {
    val text = when {
        prep.meals.all { it.side } -> if (prep.made) Msg.REMOVE_PREP_SIDES_MADE_TEXT else Msg.REMOVE_PREP_SIDES_TEXT
        prep.meals.none { it.side } -> if (prep.made) Msg.REMOVE_PREP_MADE_TEXT else Msg.REMOVE_PREP_TEXT
        prep.made -> Msg.REMOVE_PREP_MIXED_MADE_TEXT
        else -> {
            val (sides, meals) = prep.meals.partition { it.side }
            return t(Msg.REMOVE_PREP_MIXED_TEXT, prepMealsText(meals, t, noteSides = false), prepMealsText(sides, t, noteSides = false))
        }
    }
    return t(text, prepMealsText(prep, t))
}

/**
 * A skipped prepped meal's share still in the box on [today]: when it was skipped and until when it keeps, or on its
 * last day that it is to eat today or throw away; the next free lunch or dinner to eat it in with one tap, as the server
 * proposes it ([SparePortion.proposed]: one that is empty, so a tap never replaces a planned meal, issue 263), and
 * "Eat it on…" with the other later lunches and dinners that have room for it, where replacing a meal is the user's choice.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpareCard(spare: SparePortion, today: String, enabled: Boolean, onEat: (PrepSlot) -> Unit) {
    val proposal = spare.proposed?.takeIf { it in spare.room }
    val t = LocalStrings.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
            Text(spare.titleText(t), style = MaterialTheme.typography.titleMedium)
            Text(spare.whenText(today, t), style = MaterialTheme.typography.bodyMedium)
            if (spare.room.isEmpty()) {
                Text(t(Msg.SPARE_NO_ROOM), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related), verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                    proposal?.let { slot -> Button(enabled = enabled, onClick = { onEat(slot) }) { Text(t(Msg.SPARE_EAT_PROPOSED, slotLabel(slot, t))) } }
                    val others = spare.room - setOfNotNull(proposal)
                    if (others.isNotEmpty()) {
                        Box {
                            var open by remember { mutableStateOf(false) }
                            OutlinedButton(enabled = enabled, onClick = { open = true }) { Text(t(Msg.SPARE_EAT_ON)) }
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                for (slot in others) {
                                    DropdownMenuItem(text = { Text(slotLabel(slot, t)) }, onClick = { open = false; onEat(slot) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
