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
private fun Food.summary(t: Strings): String {
    val per = if (basis == FoodBasis.PER_100_ML) "100 ml" else "100 g"
    val kcal = per100.kcal?.roundToLong() ?: 0
    val protein = per100.proteinG?.roundToLong() ?: 0
    return t(Msg.FOOD_SUMMARY, nameEn, kcal, protein, per)
}

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
    val t = LocalStrings.current
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

    SubScreen(t(Msg.LINK_INGREDIENTS), onBack, snackbar) { padding ->
        when (val current = state) {
            Load.Loading -> LoadingState(Modifier.padding(padding))
            is Load.Failed -> Column(Modifier.padding(padding)) {
                ErrorMessage(t(Msg.INGREDIENTS_LOAD_FAILED), current) { reload++ }
            }
            is Load.Ready -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(Spacing.ScreenEdge),
                verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
            ) {
                item {
                    Text(
                        if (current.value.isEmpty()) t(Msg.ALL_LINKED) else t(Msg.NEEDS_DECISION),
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
                            t(Msg.NUTRITION_DATA, it),
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
            onConfirm = { choice, keeps ->
                placing = null
                scope.launch {
                    val placement = place(entry, choice, keeps, api::setSection, api::setKeeps)
                    placement.saved?.let { saved -> sections = sections?.map { if (it.ingredientId == saved.ingredientId) saved else it } }
                    snackbar.showSnackbar(placement.message(t, entry))
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
                            snackbar.showSnackbar(t(Msg.LINKED, unmapped.text, result.value.name))
                        }
                        is Load.Failed -> snackbar.showSnackbar(t(Msg.LINK_FAILED, result.message))
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
    val t = LocalStrings.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(unmapped.text, style = MaterialTheme.typography.titleMedium)
            Text(
                t.plural(Plural.USED_IN_RECIPES, unmapped.recipeCount, unmapped.exampleRecipe),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val linked = unmapped.linkedIngredient
            if (linked != null) {
                Text(t(Msg.LINKED_NO_WEIGHT, linked.name), style = MaterialTheme.typography.bodyMedium)
                AssistChip(onClick = { onPick(linked.food) }, label = { Text(t(Msg.SET_PIECE_WEIGHT)) })
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                    unmapped.suggestedFoods.take(3).forEach { food ->
                        AssistChip(onClick = { onPick(food) }, label = { Text(food.nameEn) })
                    }
                    AssistChip(onClick = { onPick(null) }, label = { Text(t(Msg.SEARCH_DOTS)) })
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
    val t = LocalStrings.current
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
        title = { Text(t(Msg.LINK_TITLE, unmapped.text)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                val food = selected
                val linked = unmapped.linkedIngredient
                if (linked != null) {
                    Text(t(Msg.LINKED_TO, linked.name))
                } else if (food != null) {
                    Text(food.summary(t))
                    TextButton(onClick = { selected = null }) { Text(t(Msg.CHOOSE_OTHER_FOOD)) }
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(t(Msg.SEARCH_FOODS)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    results.take(6).forEach { result ->
                        TextButton(onClick = { selected = result }) { Text(result.summary(t)) }
                    }
                    if (query.trim().length >= 2 && results.isEmpty()) {
                        Text(t(Msg.NO_FOODS), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (unmapped.countedInPieces) {
                    OutlinedTextField(
                        value = gramsText,
                        onValueChange = { gramsText = it },
                        label = { Text(t(Msg.GRAMS_PER_PIECE)) },
                        supportingText = { Text(t(Msg.GRAMS_PER_PIECE_HINT)) },
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
            ) { Text(if (unmapped.linkedIngredient != null) t(Msg.SAVE) else t(Msg.LINK)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(Msg.CANCEL)) } },
    )
}

@Composable
private fun SectionsIntro(query: String, onQuery: (String) -> Unit, inOther: Int) {
    val t = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Related)) {
        SectionHeader(t(Msg.SHOP_SECTIONS), Modifier.padding(top = Spacing.InsideCard))
        Text(
            t(Msg.SECTIONS_INTRO) + " " + (if (inOther == 0) t(Msg.SECTIONS_ALL_SET) else t(Msg.SECTIONS_SOME_OTHER)) + " " + t(Msg.SECTIONS_TAP),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text(t(Msg.FIND_INGREDIENT)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SectionRow(entry: IngredientSection, onClick: () -> Unit) {
    val t = LocalStrings.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Text(entry.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            listOfNotNull(
                t.sectionTitleOfKey(entry.section).let { if (entry.byHand) t(Msg.SET_BY_HAND, it) else it },
                t(Msg.KEEPS_SHORT).takeIf { entry.keeps },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What came of saving the section and "keeps" of one ingredient: [saved] is what the server has
 * now (null when nothing could be saved), [failure] what went wrong, and [keepsFailed] says it was
 * the second step: the section is saved then, and only "keeps" is not.
 */
internal class Placement(val saved: IngredientSection?, val failure: String? = null, val keepsFailed: Boolean = false) {
    /** The snackbar line: what was saved, or only what failed. */
    fun message(t: Strings, entry: IngredientSection): String = when {
        failure == null -> t(Msg.SECTION_CHANGED, entry.name, t.sectionTitleOfKey(saved!!.section))
        keepsFailed -> t(Msg.KEEPS_CHANGE_FAILED, entry.name, failure)
        else -> t(Msg.SECTION_CHANGE_FAILED, failure)
    }
}

/**
 * Saves the section, then "keeps" when it was changed. The second is a call of its own, so it can
 * fail after the first went through: the result then still carries the saved section.
 */
internal suspend fun place(
    entry: IngredientSection,
    section: String?,
    keeps: Boolean?,
    setSection: suspend (Long, String?) -> IngredientSection,
    setKeeps: suspend (Long, Boolean?) -> IngredientSection,
): Placement {
    val placed = when (val result = load { setSection(entry.ingredientId, section) }) {
        is Load.Ready -> result.value
        else -> return Placement(null, (result as Load.Failed).message)
    }
    if (keeps == entry.keepsChoice()) return Placement(placed)
    return when (val result = load { setKeeps(entry.ingredientId, keeps) }) {
        is Load.Ready -> Placement(result.value)
        else -> Placement(placed, (result as Load.Failed).message, keepsFailed = true)
    }
}

/** Whether the ingredient keeps until next week as set by hand; null when it follows its section. */
private fun IngredientSection.keepsChoice(): Boolean? = keeps.takeIf { keepsByHand }

/**
 * Picks an ingredient's section, or the automatic one (null) with where it comes from; and whether
 * it keeps until next week, or the section's answer (null).
 */
@Composable
private fun SectionDialog(entry: IngredientSection, onDismiss: () -> Unit, onConfirm: (section: String?, keeps: Boolean?) -> Unit) {
    val t = LocalStrings.current
    var choice by remember { mutableStateOf(if (entry.byHand) entry.section else null) }
    var keeps by remember { mutableStateOf(entry.keepsChoice()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(Msg.SECTION_FOR, entry.name)) },
        text = {
            SectionChoices(entry, choice, { choice = it }, keeps, { keeps = it })
        },
        confirmButton = { TextButton(onClick = { onConfirm(choice, keeps) }) { Text(t(Msg.SAVE)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(Msg.CANCEL)) } },
    )
}

/** The dialog's body: the sections to pick from and below them whether the ingredient keeps. */
@Composable
internal fun SectionChoices(entry: IngredientSection, choice: String?, onChoice: (String?) -> Unit, keeps: Boolean?, onKeeps: (Boolean?) -> Unit) {
    val t = LocalStrings.current
    Column(Modifier.verticalScroll(rememberScrollState())) {
        RadioOption(t(Msg.AUTOMATIC, t.sectionTitleOfKey(entry.automatic)), entry.source, selected = choice == null) { onChoice(null) }
        ShopSection.entries.forEach { section ->
            RadioOption(t(section.msg()), null, selected = choice == section.name) { onChoice(section.name) }
        }
        Text(t(Msg.KEEPS_TITLE), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.InsideCard))
        Text(t(Msg.KEEPS_HINT), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // The automatic answer follows the section chosen above.
        val section = ShopSection.fromKey(choice ?: entry.automatic) ?: ShopSection.OTHER
        RadioOption(t(Msg.AUTOMATIC, t(if (section.shelfLife.keepsUntilNextWeek) Msg.KEEPS_YES else Msg.KEEPS_NO)), null, selected = keeps == null) { onKeeps(null) }
        RadioOption(t(Msg.KEEPS_YES), null, selected = keeps == true) { onKeeps(true) }
        RadioOption(t(Msg.KEEPS_NO), null, selected = keeps == false) { onKeeps(false) }
    }
}

/** One of a set of choices: a radio button with its [label] and, under it, a [detail]. The whole row is the target. */
@Composable
internal fun RadioOption(label: String, detail: String?, selected: Boolean, onSelect: () -> Unit) {
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
