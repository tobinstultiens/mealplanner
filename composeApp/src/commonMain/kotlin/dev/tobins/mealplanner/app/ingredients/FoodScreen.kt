package dev.tobins.mealplanner.app.ingredients

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MealFoodInput
import dev.tobins.mealplanner.api.RecipeLineInput
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.KeyboardMakesRoom
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.Msg
import dev.tobins.mealplanner.app.createIngredient
import dev.tobins.mealplanner.app.ingredients
import dev.tobins.mealplanner.app.plan.jsonSaver
import dev.tobins.mealplanner.app.recipes.DraftRow
import dev.tobins.mealplanner.app.recipes.DraftRowItem
import dev.tobins.mealplanner.app.recipes.Found
import dev.tobins.mealplanner.app.recipes.IngredientSearch
import dev.tobins.mealplanner.app.recipes.amountText
import dev.tobins.mealplanner.app.recipes.nameFor
import dev.tobins.mealplanner.app.recipes.rememberFound
import dev.tobins.mealplanner.app.recipes.startingAmount
import dev.tobins.mealplanner.app.ui.CommonMsg
import dev.tobins.mealplanner.app.ui.ErrorMessage
import dev.tobins.mealplanner.app.ui.Load
import dev.tobins.mealplanner.app.ui.LoadingState
import dev.tobins.mealplanner.app.ui.Spacing
import dev.tobins.mealplanner.app.ui.SubScreen
import dev.tobins.mealplanner.app.ui.failureSentence
import dev.tobins.mealplanner.app.ui.load
import dev.tobins.mealplanner.nutrition.NutritionCalculator
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.nullable
import kotlin.math.roundToLong

/**
 * A food a [FoodScreen] opens with, chosen before: the account's ingredient [ingredientId] in [amount] of [unit]. A logged
 * food (issue 362) keeps the [kcal] and [proteinG] it counted when it was logged; a food of a slot counts by the values of
 * now, and has none.
 */
data class ChosenFood(
    val ingredientId: Long,
    val name: String,
    val amount: Double,
    val unit: String,
    val kcal: Double? = null,
    val proteinG: Double? = null,
) {
    /** The row it opens as: as it was chosen, so that the amount is the exact one while its text is as shown. */
    fun row(): DraftRow = DraftRow(0, amountText(amount), unit, name, ingredientId, null, original = RecipeLineInput(amount, unit, name, ingredientId))

    /** True when [row] is this food in the amount and unit it was chosen in: saving it would change nothing. */
    fun isAsChosen(row: DraftRow): Boolean = row.ingredientId == ingredientId && row.amountValue == amount && row.unit == unit

    /**
     * True when the kept numbers are not what [ingredient]'s values give for this amount now: its values changed since it
     * was logged. Compared as they are shown, in whole kcal and tenths of a gram of protein.
     */
    fun countsOtherwiseNow(ingredient: Ingredient): Boolean {
        val kept = kcal ?: return false
        val now = NutritionCalculator.ofLines(listOf(row().input() to ingredient), servings = 1)?.perServing ?: return false
        return (now.kcal ?: 0.0).roundToLong() != kept.roundToLong() || ((now.proteinG ?: 0.0) * 10).roundToLong() != ((proteinG ?: 0.0) * 10).roundToLong()
    }
}

/**
 * A food picked in an amount, for a slot of the plan (issue 358, [SlotFoodScreen]) or the log of what was eaten (issue 362,
 * [LogFoodScreen]): searched and picked as in the recipe editor (the account's own ingredients and products first, then
 * NEVO foods, "New product" last), in an amount of one of its units, starting in the one it was last picked or saved in
 * ([startingAmount]). With [food], one chosen before, to change its amount or unit, or pick another by its name, and to take
 * it out with the cross ([remove]). [title] stands in the bar, [heading] (the day) as the first line under it. [save] sends
 * the food; [onDone] gets what the server answers it or [remove], and a failure says why and leaves the screen as it is.
 */
@Composable
fun <T> FoodScreen(
    api: ApiClient,
    title: String,
    heading: String,
    food: ChosenFood?,
    onBack: () -> Unit,
    save: suspend (MealFoodInput) -> T,
    remove: suspend () -> T,
    onDone: (T) -> Unit,
) {
    val t = LocalStrings.current
    var catalogue by remember { mutableStateOf<Load<List<Ingredient>>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    // Kept over a re-created activity, as what was typed: the food picked with its amount, or none while searching.
    var row by rememberSaveable(stateSaver = jsonSaver(DraftRow.serializer().nullable)) {
        mutableStateOf(food?.row())
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
                        is Load.Failed -> snackbar.showSnackbar(failureSentence(made, CommonMsg.EDITOR_ADD_FAILED))
                        Load.Loading -> Unit
                    }
                    busy = false
                }
            }
        }
    }

    /** Sends [action], and hands on what it answers; a failure says why, in [failed]'s words, and the screen stays. */
    fun send(failed: Msg, action: suspend () -> T) {
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
    // The food as it was chosen: saving it changes nothing, so nothing is sent (a logged food would keep its numbers anyway).
    val asChosen = food != null && current != null && food.isAsChosen(current)
    SubScreen(
        title, onBack, snackbar,
        actions = {
            TextButton(enabled = input != null && !busy, onClick = {
                val sent = input ?: return@TextButton
                if (asChosen) onBack() else send(IngredientsMsg.FOOD_SAVE_FAILED) { save(sent) }
            }) { Text(t(if (food == null) CommonMsg.ADD else CommonMsg.SAVE)) }
        },
    ) { padding ->
        val failed = catalogue as? Load.Failed
        when {
            failed != null -> Box(Modifier.padding(padding)) { ErrorMessage(t(CommonMsg.INGREDIENTS_LOAD_FAILED), failed) { reload++ } }
            ingredients == null -> LoadingState(Modifier.padding(padding))
            else -> Column(
                Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.ScreenEdge),
                verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
            ) {
                Text(heading, style = MaterialTheme.typography.titleMedium)
                if (current == null) {
                    val (found, searchFailed) = rememberFound(api, ingredients, query)
                    IngredientSearch(query, { query = it }, found, searchFailed, enabled = !busy, onNewProduct = { newProduct = it }, onPick = ::pick)
                } else {
                    val byId = ingredients.associateBy { it.id }
                    val ingredient = current.ingredientId?.let(byId::get)
                    // The name picks another; the cross takes a chosen food out, or a picked one back to the search. A logged
                    // food in its own amount says what it counted, which it keeps; in another, what the values of now give.
                    DraftRowItem(
                        current, ingredient, focus, enabled = !busy,
                        onChange = { row = it },
                        onOpen = { row = null },
                        onRemove = { if (food == null) row = null else send(IngredientsMsg.FOOD_REMOVE_FAILED) { remove() } },
                        keptKcal = food?.kcal?.takeIf { asChosen },
                    )
                    if (food != null && ingredient?.id == food.ingredientId && food.countsOtherwiseNow(ingredient)) {
                        Text(
                            t(IngredientsMsg.LOG_FOOD_VALUES_CHANGED, food.name),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
