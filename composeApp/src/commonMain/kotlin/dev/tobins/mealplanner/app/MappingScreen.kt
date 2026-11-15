package dev.tobins.mealplanner.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.IngredientSection
import dev.tobins.mealplanner.api.MappingRequest
import dev.tobins.mealplanner.api.UnmappedText
import dev.tobins.mealplanner.shopping.ShopSection
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

/** A section's title by its key; a key from a newer server is shown as it is. */
private fun sectionTitle(key: String): String = ShopSection.fromKey(key)?.title ?: key

/**
 * The ingredients to list under "Shop sections": with no [query], those still under Other,
 * as they are the ones that need a decision; otherwise those whose name contains it.
 */
internal fun sectionsToShow(all: List<IngredientSection>, query: String): List<IngredientSection> {
    val text = query.trim()
    return if (text.isEmpty()) all.filter { it.section == ShopSection.OTHER.name } else all.filter { it.name.contains(text, ignoreCase = true) }
}

/**
 * Lists recipe ingredient texts that are not linked to a catalogue ingredient yet
 * and lets the user link each one to a NEVO food. Below them, each ingredient's shop
 * section, which orders the shopping list, can be corrected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MappingScreen(api: ApiClient, onBack: () -> Unit) {
    var state by remember { mutableStateOf<Load<List<UnmappedText>>>(Load.Loading) }
    var reference by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var choosing by remember { mutableStateOf<Pair<UnmappedText, Food?>?>(null) }
    var sections by remember { mutableStateOf<List<IngredientSection>?>(null) }
    var sectionQuery by remember { mutableStateOf("") }
    var placing by remember { mutableStateOf<IngredientSection?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        if (state !is Load.Ready) state = Load.Loading
        state = load { api.unmapped() }
        // Linking can add ingredients, so the sections are loaded again with the texts.
        sections = (load { api.ingredientSections() } as? Load.Ready)?.value
        if (reference == null) reference = (load { api.foodDataset() } as? Load.Ready)?.value?.reference
    }

    SubScreen("Link ingredients", onBack, snackbar) { padding ->
        when (val current = state) {
            Load.Loading -> LoadingState(Modifier.padding(padding))
            is Load.Failed -> Column(Modifier.padding(padding)) {
                ErrorMessage("Could not load ingredients", current.message) { reload++ }
            }
            is Load.Ready -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(Spacing.ScreenEdge),
                verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
            ) {
                item {
                    Text(
                        if (current.value.isEmpty()) "Every ingredient with an amount is linked."
                        else "Ingredients that match a food exactly are linked automatically. These need your decision; most-used first.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                items(current.value, key = { it.text }) { unmapped ->
                    UnmappedCard(unmapped, onPick = { food -> choosing = unmapped to food })
                }
                sections?.let { all ->
                    item(key = "h:sections") {
                        SectionsIntro(sectionQuery, onQuery = { sectionQuery = it }, inOther = all.count { it.section == ShopSection.OTHER.name })
                    }
                    items(sectionsToShow(all, sectionQuery), key = { "s:${it.ingredientId}" }) { entry ->
                        SectionRow(entry, onClick = { placing = entry })
                    }
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

    placing?.let { entry ->
        SectionDialog(
            entry = entry,
            onDismiss = { placing = null },
            onConfirm = { choice ->
                placing = null
                scope.launch {
                    when (val result = load { api.setSection(entry.ingredientId, choice) }) {
                        is Load.Ready -> {
                            sections = sections?.map { if (it.ingredientId == entry.ingredientId) result.value else it }
                            snackbar.showSnackbar("${entry.name} is now under ${sectionTitle(result.value.section)}")
                        }
                        is Load.Failed -> snackbar.showSnackbar("Could not change the section: ${result.message}")
                        Load.Loading -> Unit
                    }
                }
            },
        )
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
        Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(unmapped.text, style = MaterialTheme.typography.titleMedium)
            Text(
                (if (unmapped.recipeCount == 1) "Used in 1 recipe" else "Used in ${unmapped.recipeCount} recipes") +
                    ", e.g. ${unmapped.exampleRecipe}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val linked = unmapped.linkedIngredient
            if (linked != null) {
                Text("Linked to ${linked.name}, but its weight per piece is unknown.", style = MaterialTheme.typography.bodyMedium)
                AssistChip(onClick = { onPick(linked.food) }, label = { Text("Set weight per piece") })
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                    unmapped.suggestedFoods.take(3).forEach { food ->
                        AssistChip(onClick = { onPick(food) }, label = { Text(food.nameEn) })
                    }
                    AssistChip(onClick = { onPick(null) }, label = { Text("Search…") })
                }
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
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                val food = selected
                val linked = unmapped.linkedIngredient
                if (linked != null) {
                    Text("Linked to ${linked.name}.")
                } else if (food != null) {
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
                enabled = if (unmapped.linkedIngredient != null) grams != null else selected != null && (gramsText.isBlank() || grams != null),
                onClick = {
                    val linked = unmapped.linkedIngredient
                    onConfirm(
                        if (linked != null) MappingRequest(text = unmapped.text, ingredientId = linked.id, gramsPerPiece = grams)
                        else MappingRequest(text = unmapped.text, nevoCode = selected!!.nevoCode, gramsPerPiece = grams),
                    )
                },
            ) { Text(if (unmapped.linkedIngredient != null) "Save" else "Link") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionsIntro(query: String, onQuery: (String) -> Unit, inOther: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
        SectionHeader("Shop sections", Modifier.padding(top = Spacing.InsideCard))
        Text(
            "The shopping list follows the route through the shop. " +
                (if (inOther == 0) "Every ingredient has a section." else "Ingredients without a section go last, under Other; these are listed below.") +
                " Tap an ingredient to put it in another section.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("Find an ingredient") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SectionRow(entry: IngredientSection, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Text(entry.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            sectionTitle(entry.section) + if (entry.byHand) " · set by hand" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Picks an ingredient's section, or the automatic one (null) with where it comes from. */
@Composable
private fun SectionDialog(entry: IngredientSection, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var choice by remember { mutableStateOf(if (entry.byHand) entry.section else null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Section for ${entry.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionOption("Automatic: ${sectionTitle(entry.automatic)}", entry.source, selected = choice == null) { choice = null }
                ShopSection.entries.forEach { section ->
                    SectionOption(section.title, null, selected = choice == section.name) { choice = section.name }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(choice) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionOption(label: String, detail: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onSelect, role = Role.RadioButton).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = Spacing.Related)) {
            Text(label)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
