package dev.tobins.mealplanner.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MealFood
import dev.tobins.mealplanner.api.MealFoodInput
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.WeekPlan
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlin.math.roundToLong

/*
 * Foods in a slot of the plan (issue 358): a slot holds a recipe, foods, or a recipe with foods next to it, each food in the
 * unit it was picked in ("2 bar"). README, "Foods in a slot".
 */

/** A slot of the plan a food is picked for, with [food] when it is one planned there already, to change or take out. */
@Serializable
data class FoodPlace(val day: Int, val slot: MealSlot, val food: MealFood? = null)

/**
 * What a meal is called where one line names it (a dialog, the skip sheet): its recipe's title, or for foods alone their names
 * in the app's language, one or two as they are and more as the first and how many follow ("Banana + 2 more"). The
 * server's title of foods alone is in English, for apps that do not know foods.
 */
fun PlannedMeal.shownTitle(t: Strings): String = when {
    !foodsOnly || foods.isEmpty() -> title
    foods.size <= 2 -> foods.joinToString(", ") { it.name }
    else -> t(Msg.FOODS_AND_MORE, foods.first().name, foods.size - 1)
}

/** Whether a food can go in [meal]'s slot, as the server lets it: not in a skipped meal, which counts for nothing, nor a prepped one. */
fun takesFoods(meal: PlannedMeal?): Boolean = meal == null || (meal.status != MealStatus.SKIPPED && meal.prep == null)

/**
 * `2 bar · 360 kcal`, `1 piece · 108 kcal`, `150 g · 525 kcal`: a food of a slot in the unit it was picked in, and what it
 * counts; one that counts as nothing says so.
 */
fun MealFood.amountText(t: Strings): String {
    val amount = if (unit == PIECE) {
        t.plural(Plural.PIECES_AMOUNT, if (this.amount == 1.0) 1 else 2, formatAmount(this.amount, t.locale))
    } else {
        "${formatAmount(this.amount, t.locale)} ${unitLabel(unit, t)}"
    }
    return unbrokenParts(if (counted) t(Msg.FOOD_AMOUNT, amount, kcal.roundToLong()) else t(Msg.FOOD_UNCOUNTED, amount))
}

/** The foods of a slot under its meal, a row each: the name and [amountText]; with [onOpen], a tap opens one. */
@Composable
fun FoodRows(foods: List<MealFood>, enabled: Boolean = true, onOpen: ((MealFood) -> Unit)? = null) {
    val t = LocalStrings.current
    Column(Modifier.fillMaxWidth()) {
        for (food in foods) {
            val row = Modifier.fillMaxWidth().let { if (onOpen != null) it.clickable(enabled = enabled) { onOpen(food) } else it }
            Column(row.padding(vertical = 4.dp)) {
                Text(food.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    food.amountText(t),
                    style = MaterialTheme.typography.bodySmall.numeric(),
                    color = if (food.counted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * A food for [place]'s slot on day [FoodPlace.day] of the week starting [weekStart] (issue 358): searched and picked as in
 * the recipe editor (the account's own ingredients and products first, then NEVO foods, "New product" last), in an amount
 * of one of its units, starting in the one it was last picked or saved in ([startingAmount]). For a food planned already,
 * its amount and unit to change, and taking it out. [onDone] gets the week as the server answers the change.
 */
@Composable
fun SlotFoodScreen(api: ApiClient, weekStart: String, place: FoodPlace, onBack: () -> Unit, onDone: (WeekPlan) -> Unit) {
    val t = LocalStrings.current
    val food = place.food
    var catalogue by remember { mutableStateOf<Load<List<Ingredient>>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    // Kept over a re-created activity, as what was typed: the food picked with its amount, or none while searching.
    var row by rememberSaveable(stateSaver = jsonSaver(DraftRow.serializer().nullable)) {
        mutableStateOf(food?.let { DraftRow(0, amountText(it.amount), it.unit, it.name, it.ingredientId, null) })
    }
    var nextKey by rememberSaveable { mutableStateOf(1L) }
    var query by rememberSaveable { mutableStateOf("") }
    var newProduct by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(reload) { catalogue = load { api.ingredients() } }
    // The keyboard makes room below the column instead of sliding the window up under the status bar.
    KeyboardMakesRoom()
    val ingredients = (catalogue as? Load.Ready)?.value

    /** [ingredient] picked: a row in the unit it starts in, as the editor starts one. */
    fun picked(ingredient: Ingredient) {
        val (amount, unit) = startingAmount(ingredient)
        row = DraftRow(nextKey++, amountText(amount), unit, ingredient.name, ingredient.id, null)
        query = ""
    }

    newProduct?.let { name ->
        ProductScreen(
            api, null,
            onDone = { made ->
                catalogue = Load.Ready(ingredients.orEmpty() + made)
                picked(made)
                newProduct = null
            },
            onCancel = { newProduct = null },
            name = name,
            catalogue = ingredients.orEmpty(),
        )
        return
    }

    /** Picks what the search [found]: one of the account's, or a NEVO food made an ingredient of it now. */
    fun pick(found: Found) {
        if (busy) return
        when (found) {
            is Found.Mine -> picked(found.ingredient)
            is Found.NewFood -> {
                busy = true
                scope.launch {
                    val all = ingredients.orEmpty()
                    when (val made = load { api.createIngredient(IngredientInput(nameFor(found.food, all), nevoCode = found.food.nevoCode)) }) {
                        is Load.Ready -> {
                            catalogue = Load.Ready(all + made.value)
                            picked(made.value)
                        }
                        is Load.Failed -> snackbar.showSnackbar(failureSentence(made, Msg.EDITOR_ADD_FAILED))
                        Load.Loading -> Unit
                    }
                    busy = false
                }
            }
        }
    }

    /** Sends [action], and hands the week it answers on; a failure says why, in [failed]'s words, and the screen stays. */
    fun send(failed: Msg, action: suspend () -> WeekPlan) {
        if (busy) return
        busy = true
        scope.launch {
            when (val result = load(action)) {
                is Load.Ready -> onDone(result.value)
                is Load.Failed -> {
                    busy = false
                    snackbar.showSnackbar(failureSentence(result, failed))
                }
                Load.Loading -> busy = false
            }
        }
    }

    val current = row
    val input = current?.let { r ->
        val id = r.ingredientId ?: return@let null
        val amount = r.amountValue ?: return@let null
        MealFoodInput(id, amount, r.unit ?: return@let null)
    }
    // The slot alone in the bar, which has one line, and the day as the first line under it: both fit at the largest font.
    SubScreen(
        t.slot(place.slot, capitalised = true), onBack, snackbar,
        actions = {
            TextButton(enabled = input != null && !busy, onClick = {
                val sent = input ?: return@TextButton
                send(Msg.FOOD_SAVE_FAILED) {
                    if (food == null) api.addFood(weekStart, place.day, place.slot, sent) else api.changeFood(weekStart, place.day, place.slot, food.id, sent)
                }
            }) { Text(t(if (food == null) Msg.ADD else Msg.SAVE)) }
        },
    ) { padding ->
        val failed = catalogue as? Load.Failed
        when {
            failed != null -> Box(Modifier.padding(padding)) { ErrorMessage(t(Msg.INGREDIENTS_LOAD_FAILED), failed) { reload++ } }
            ingredients == null -> LoadingState(Modifier.padding(padding))
            else -> Column(
                Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.ScreenEdge),
                verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
            ) {
                Text(dayLabel(plusDays(weekStart, place.day), t.locale), style = MaterialTheme.typography.titleMedium)
                if (current == null) {
                    val (found, searchFailed) = rememberFound(api, ingredients, query)
                    IngredientSearch(query, { query = it }, found, searchFailed, enabled = !busy, onNewProduct = { newProduct = it }, onPick = ::pick)
                } else {
                    val byId = ingredients.associateBy { it.id }
                    // The name picks another; the cross takes a planned food out, or a picked one back to the search.
                    DraftRowItem(
                        current, current.ingredientId?.let(byId::get), focus, enabled = !busy,
                        onChange = { row = it },
                        onOpen = { row = null },
                        onRemove = {
                            if (food == null) row = null else send(Msg.FOOD_REMOVE_FAILED) { api.removeFood(weekStart, place.day, place.slot, food.id) }
                        },
                    )
                }
            }
        }
    }
}
