package dev.tobins.mealplanner.app.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeTopping
import dev.tobins.mealplanner.api.ToppingInput
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.Msg
import dev.tobins.mealplanner.app.plan.AmountDialog
import dev.tobins.mealplanner.app.plan.IngredientPicker
import dev.tobins.mealplanner.app.plan.amountText
import dev.tobins.mealplanner.app.ui.AppIcons
import dev.tobins.mealplanner.app.ui.BarAction
import dev.tobins.mealplanner.app.ui.Load
import dev.tobins.mealplanner.app.ui.Spacing
import dev.tobins.mealplanner.app.ui.load
import dev.tobins.mealplanner.app.ui.numeric
import kotlinx.coroutines.launch

/** The toppings as `PUT .../toppings` takes them, with [ingredientId] at [grams]: changed where it is, or added at the end. */
fun List<RecipeTopping>.withTopping(ingredientId: Long, grams: Double): List<ToppingInput> {
    val inputs = map { ToppingInput(it.ingredientId, if (it.ingredientId == ingredientId) grams else it.grams) }
    return if (any { it.ingredientId == ingredientId }) inputs else inputs + ToppingInput(ingredientId, grams)
}

/** The toppings as `PUT .../toppings` takes them, without [ingredientId]. */
fun List<RecipeTopping>.withoutTopping(ingredientId: Long): List<ToppingInput> =
    filter { it.ingredientId != ingredientId }.map { ToppingInput(it.ingredientId, it.grams) }

/**
 * A recipe's optional toppings in its settings (issue 226): each with the amount a meal usually gets, tapped to
 * change it, the cross to remove it, and "Add a topping" from the catalogue, in pieces where it counts them. The
 * Adjust screen offers them first. Every change saves the whole list at once, and [onSaved] gets the answer:
 * the recipe as the server has it, or why it failed.
 */
@Composable
internal fun ToppingsSettings(api: ApiClient, recipe: RecipeDetail, onSaved: suspend (Load<RecipeDetail>) -> Unit) {
    val t = LocalStrings.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RecipeTopping?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var adding by remember { mutableStateOf<Ingredient?>(null) }

    fun save(toppings: List<ToppingInput>) {
        busy = true
        scope.launch {
            val result = load { api.setRecipeToppings(recipe.summary.id, toppings) }
            busy = false
            onSaved(result)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        Text(t(Msg.TOPPINGS), style = MaterialTheme.typography.labelLarge)
        Text(t(Msg.TOPPINGS_HINT), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        recipe.toppings.forEach { topping ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !busy) { editing = topping }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Related),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(topping.name)
                    amountText(topping.grams, topping.gramsPerPiece, t)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall.numeric(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                BarAction(AppIcons.Close, t(Msg.TOPPING_REMOVE, topping.name), onClick = { save(recipe.toppings.withoutTopping(topping.ingredientId)) }, enabled = !busy)
            }
        }
        OutlinedButton(enabled = !busy, onClick = { picking = true }) { Text(t(Msg.TOPPING_ADD)) }
    }

    editing?.let { topping ->
        AmountDialog(topping.name, topping.gramsPerPiece, topping.grams, onDismiss = { editing = null }) { grams ->
            editing = null
            save(recipe.toppings.withTopping(topping.ingredientId, grams))
        }
    }
    if (picking) {
        IngredientPicker(api, t(Msg.TOPPING_ADD), left = recipe.toppings.mapTo(HashSet()) { it.ingredientId }, onDismiss = { picking = false }) {
            picking = false
            adding = it
        }
    }
    adding?.let { ingredient ->
        // Prefilled with one piece when the catalogue knows its weight, as on the Adjust screen.
        AmountDialog(ingredient.name, ingredient.gramsPerPiece, ingredient.gramsPerPiece, onDismiss = { adding = null }) { grams ->
            adding = null
            save(recipe.toppings.withTopping(ingredient.id, grams))
        }
    }
}
