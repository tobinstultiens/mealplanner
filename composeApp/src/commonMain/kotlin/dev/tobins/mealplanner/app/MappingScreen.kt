package dev.tobins.mealplanner.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.MappingRequest
import dev.tobins.mealplanner.api.UnmappedText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/** `Chicken fillet raw · 109 kcal, 23 g protein per 100 g`. */
private fun Food.summary(): String {
    val per = if (basis == FoodBasis.PER_100_ML) "100 ml" else "100 g"
    val kcal = per100.kcal?.roundToLong() ?: 0
    val protein = per100.proteinG?.roundToLong() ?: 0
    return "$nameEn · $kcal kcal, $protein g protein per $per"
}

/**
 * Lists recipe ingredient texts that are not linked to a catalogue ingredient yet
 * and lets the user link each one to a NEVO food.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MappingScreen(api: ApiClient, onBack: () -> Unit) {
    var state by remember { mutableStateOf<Load<List<UnmappedText>>>(Load.Loading) }
    var reference by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var choosing by remember { mutableStateOf<Pair<UnmappedText, Food?>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        if (state !is Load.Ready) state = Load.Loading
        state = load { api.unmapped() }
        if (reference == null) reference = (load { api.foodDataset() } as? Load.Ready)?.value?.reference
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Link ingredients") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val current = state) {
            Load.Loading -> CenteredBox(Modifier.padding(padding)) { CircularProgressIndicator() }
            is Load.Failed -> Column(Modifier.padding(padding)) {
                ErrorMessage("Could not load ingredients", current.message) { reload++ }
            }
            is Load.Ready -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        if (current.value.isEmpty()) "Every ingredient with an amount is linked."
                        else "Link each ingredient to a food so recipes get nutrition values. Most-used first.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                items(current.value, key = { it.text }) { unmapped ->
                    UnmappedCard(unmapped, onPick = { food -> choosing = unmapped to food })
                }
                reference?.let {
                    item {
                        Text(
                            "Nutrition data: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    choosing?.let { (unmapped, preselected) ->
        LinkDialog(
            api = api,
            unmapped = unmapped,
            preselected = preselected,
            onDismiss = { choosing = null },
            onConfirm = { request ->
                choosing = null
                scope.launch {
                    when (val result = load { api.map(request) }) {
                        is Load.Ready -> {
                            reload++
                            snackbar.showSnackbar("Linked “${unmapped.text}” to ${result.value.name}")
                        }
                        is Load.Failed -> snackbar.showSnackbar("Could not link: ${result.message}")
                        Load.Loading -> Unit
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnmappedCard(unmapped: UnmappedText, onPick: (Food?) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(unmapped.text, style = MaterialTheme.typography.titleMedium)
            Text(
                (if (unmapped.recipeCount == 1) "Used in 1 recipe" else "Used in ${unmapped.recipeCount} recipes") +
                    ", e.g. ${unmapped.exampleRecipe}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                unmapped.suggestedFoods.take(3).forEach { food ->
                    AssistChip(onClick = { onPick(food) }, label = { Text(food.nameEn) })
                }
                AssistChip(onClick = { onPick(null) }, label = { Text("Search…") })
            }
        }
    }
}

/** Confirms a link: pick or search a food, and give a weight per piece when the recipes count pieces. */
@Composable
private fun LinkDialog(
    api: ApiClient,
    unmapped: UnmappedText,
    preselected: Food?,
    onDismiss: () -> Unit,
    onConfirm: (MappingRequest) -> Unit,
) {
    var selected by remember { mutableStateOf(preselected) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Food>>(emptyList()) }
    var gramsText by remember { mutableStateOf("") }
    val grams = gramsText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }

    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = emptyList()
        } else {
            delay(300) // wait for a pause in typing
            results = (load { api.searchFoods(query.trim()) } as? Load.Ready)?.value.orEmpty()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link “${unmapped.text}”") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val food = selected
                if (food != null) {
                    Text(food.summary())
                    TextButton(onClick = { selected = null }) { Text("Choose another food") }
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search foods (English or Dutch)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    results.take(6).forEach { result ->
                        TextButton(onClick = { selected = result }) { Text(result.summary()) }
                    }
                    if (query.trim().length >= 2 && results.isEmpty()) {
                        Text("No foods found.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (unmapped.countedInPieces) {
                    OutlinedTextField(
                        value = gramsText,
                        onValueChange = { gramsText = it },
                        label = { Text("Grams per piece") },
                        supportingText = { Text("Recipes count this in pieces; without a weight it cannot be counted.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null && (gramsText.isBlank() || grams != null),
                onClick = {
                    onConfirm(MappingRequest(text = unmapped.text, nevoCode = selected!!.nevoCode, gramsPerPiece = grams))
                },
            ) { Text("Link") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
