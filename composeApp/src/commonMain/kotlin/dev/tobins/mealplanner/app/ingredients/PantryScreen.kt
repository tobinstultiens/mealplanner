package dev.tobins.mealplanner.app.ingredients

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.Pantry
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.Msg
import dev.tobins.mealplanner.app.RefreshNotice
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.rememberFrontReturns
import dev.tobins.mealplanner.app.rememberLoad
import dev.tobins.mealplanner.app.shopping.weightText
import dev.tobins.mealplanner.app.ui.ErrorMessage
import dev.tobins.mealplanner.app.ui.Load
import dev.tobins.mealplanner.app.ui.LoadingState
import dev.tobins.mealplanner.app.ui.Spacing
import dev.tobins.mealplanner.app.ui.SubScreen
import dev.tobins.mealplanner.app.ui.dayMonthLabel
import dev.tobins.mealplanner.app.ui.failureSentence
import dev.tobins.mealplanner.app.ui.load
import kotlinx.coroutines.launch

/**
 * What the pantry says under an on-hand ingredient: `280 g left · for the week of 23 Nov`, from the
 * amount left and the week the stock was bought for, each only when known (an amount that is not
 * known is left out, never shown as none). Stock of several weeks names each: `for the weeks of
 * 16 Nov, 23 Nov`. A server that does not give the weeks gives the first one only. Null for what is not on hand and when neither is known.
 */
internal fun pantryNote(pantry: Pantry, id: Long, strings: Strings): String? {
    if (pantry.onHand.none { it.id == id }) return null
    val left = pantry.onHandGrams[id]?.let { strings(Msg.PANTRY_LEFT, weightText(it, strings.locale)) }
    // A date never breaks across lines: its day and month are joined by a no-break space.
    fun date(iso: String) = dayMonthLabel(iso, strings.locale).replace(' ', '\u00A0')
    val weeks = pantry.onHandWeeks[id].orEmpty().ifEmpty { listOfNotNull(pantry.onHandFrom[id]) }
    val week = when (weeks.size) {
        0 -> null
        1 -> strings(Msg.PANTRY_FROM_WEEK, date(weeks.single()))
        else -> strings(Msg.PANTRY_FROM_WEEKS, weeks.joinToString(", ") { date(it) })
    }
    return listOfNotNull(left, week).joinToString(" · ").ifEmpty { null }
}

/**
 * What is at home. A staple is always there and never goes on the shopping list;
 * an on-hand item is a perishable to use up, which the planner favours. One bought from the
 * shopping list's advice says how much of it is left, when that is known.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantryScreen(api: ApiClient, onBack: () -> Unit) {
    val t = LocalStrings.current
    val loaded = rememberLoad<Pair<List<Ingredient>, Pantry>>("pantry")
    var state by loaded
    var reload by remember { mutableIntStateOf(0) }
    val frontReturns = rememberFrontReturns()
    val listState = rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(loaded, reload, frontReturns) { loaded.refresh { api.ingredients() to api.pantry() } }

    SubScreen(t(Msg.PANTRY), onBack, snackbar, notice = { RefreshNotice(loaded, aboveSystemBar = true) { reload++ } }) { padding ->
        when (val current = state) {
            Load.Loading -> LoadingState(Modifier.padding(padding))
            is Load.Failed -> Column(Modifier.padding(padding)) {
                ErrorMessage(t(Msg.PANTRY_LOAD_FAILED), current) { reload++ }
            }
            is Load.Ready -> {
                val (ingredients, pantry) = current.value
                val staples = pantry.staples.mapTo(HashSet()) { it.id }
                val onHand = pantry.onHand.mapTo(HashSet()) { it.id }

                fun update(action: suspend () -> Pantry) {
                    scope.launch {
                        when (val result = load(action)) {
                            is Load.Ready -> state = Load.Ready(ingredients to result.value)
                            is Load.Failed -> snackbar.showSnackbar(failureSentence(result))
                            Load.Loading -> Unit
                        }
                    }
                }
                // What is already in the pantry comes first, then the rest by name.
                val shown = ingredients
                    .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                    .sortedWith(compareBy({ it.id !in onHand }, { it.id !in staples }, { it.name.lowercase() }))

                Column(Modifier.padding(padding).fillMaxSize()) {
                    Text(
                        t(Msg.PANTRY_SUMMARY, staples.size, onHand.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.ScreenEdge),
                    )
                    OutlinedTextField(
                        query, { query = it },
                        label = { Text(t(Msg.SEARCH_INGREDIENTS)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.ScreenEdge, vertical = 8.dp),
                    )
                    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = Spacing.ScreenEdge)) {
                        items(shown, key = { it.id }) { ingredient ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = Spacing.ScreenEdge, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.Related),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(ingredient.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    pantryNote(pantry, ingredient.id, t)?.let { note ->
                                        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                FilterChip(
                                    selected = ingredient.id in staples,
                                    onClick = { update { api.setStaple(ingredient.id, ingredient.id !in staples) } },
                                    label = { Text(t(Msg.STAPLE)) },
                                )
                                FilterChip(
                                    selected = ingredient.id in onHand,
                                    onClick = { update { api.setOnHand(ingredient.id, ingredient.id !in onHand) } },
                                    label = { Text(t(Msg.ON_HAND)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
