package dev.tobins.mealplanner.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.RecipeDetail

/**
 * The steps of a recipe's instructions as cook mode shows them, one card each. The numbered list is
 * the steps (a paragraph right after an item belongs to it); an instruction text without one has every
 * non-empty line as a step, since steps typed in the recipe editor come one per line (the cooking
 * site's pages do too: none has a sentence wrapped over lines). A lone label such as "Steps:", headings and table rows are no step.
 */
fun cookSteps(instructions: String): List<String> {
    val blocks = parseMarkdown(instructions)
    val numbered = blocks.any { it is MdBlock.ListItem && it.marker != "•" }
    if (!numbered) return linesAsParagraphs(instructions)
    val steps = mutableListOf<String>()
    var inStep = false
    for (block in blocks) {
        when {
            block is MdBlock.ListItem && block.marker != "•" -> { steps += block.text; inStep = true }
            block is MdBlock.Paragraph && inStep && !block.isLabel() -> steps[steps.lastIndex] = steps.last() + " " + block.text
            else -> inStep = false
        }
    }
    return steps
}

/** The paragraphs of [instructions] read with a blank line between all lines, so each line is one. */
private fun linesAsParagraphs(instructions: String): List<String> =
    parseMarkdown(instructions.lines().filter { it.isNotBlank() }.joinToString("\n\n"))
        .filterIsInstance<MdBlock.Paragraph>().filterNot { it.isLabel() }.map { it.text }

private fun MdBlock.Paragraph.isLabel() = text.endsWith(":") && text.length < 40

/**
 * What is ticked while cooking: the [ingredients] by their place in the recipe and the [steps] by
 * their number. The step to do now is the first one not done, so tapping a step moves on to the next.
 * Kept through a rotation, forgotten when the recipe is left; nothing goes to the server.
 */
data class CookProgress(val ingredients: Set<Int> = emptySet(), val steps: Set<Int> = emptySet()) {
    fun toggleIngredient(index: Int) = copy(ingredients = ingredients.toggled(index))

    /** Marks a step done, or not done again when it was. */
    fun toggleStep(index: Int) = copy(steps = steps.toggled(index))

    /** The first step not done among [count], or null when all are. */
    fun currentStep(count: Int): Int? = (0 until count).firstOrNull { it !in steps }

    private fun Set<Int>.toggled(index: Int) = if (index in this) this - index else this + index

    companion object {
        /** Keeps the ticks across a re-created screen: the ingredients, a -1, then the steps. */
        val Saver = listSaver<CookProgress, Int>(
            save = { it.ingredients.toList() + -1 + it.steps.toList() },
            restore = { v ->
                val cut = v.indexOf(-1)
                CookProgress(v.take(cut).toSet(), v.drop(cut + 1).toSet())
            },
        )
    }
}

/**
 * Cook mode of a recipe: big rows to tick the ingredients (the amounts of [factor] times the recipe,
 * as the recipe screen shows them) and the steps one per card, the current one highlighted.
 * [onMarkEaten] is given for a meal still planned and shows its button once the last step is done;
 * [top] is shown above the ingredients (the "As written" switch of a recipe opened from a meal).
 */
@Composable
fun CookMode(recipe: RecipeDetail, factor: Double?, progress: CookProgress, onProgress: (CookProgress) -> Unit, onMarkEaten: (() -> Unit)?, modifier: Modifier = Modifier, top: @Composable () -> Unit = {}) {
    val t = LocalStrings.current
    val steps = cookSteps(recipe.instructions)
    val current = progress.currentStep(steps.size)
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.ScreenEdge),
        verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
    ) {
        top()
        if (recipe.ingredients.isNotEmpty()) {
            Text(t(Msg.INGREDIENTS), style = MaterialTheme.typography.titleLarge)
            var group: String? = null
            recipe.ingredients.forEachIndexed { index, mapped ->
                val line = factor?.let { mapped.line.scaled(it) } ?: mapped.line
                if (line.group != group) {
                    group = line.group
                    group?.let { SectionHeader(it) }
                }
                CookRow(line.displayText(t.locale), done = index in progress.ingredients) { onProgress(progress.toggleIngredient(index)) }
            }
        }
        if (steps.isEmpty()) {
            if (recipe.ingredients.isEmpty()) Text(t(Msg.COOK_NO_INSTRUCTIONS), color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Nothing to step through: the meal can be marked eaten from the end of the ingredients.
            else if (onMarkEaten != null) Button(onClick = onMarkEaten, Modifier.fillMaxWidth()) { Text(t(Msg.COOK_MARK_EATEN)) }
        } else {
            Text(t(Msg.COOK_STEPS), style = MaterialTheme.typography.titleLarge)
            val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
            steps.forEachIndexed { index, step ->
                val done = index in progress.steps
                val highlighted = index == current
                Card(
                    Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { onProgress(progress.toggleStep(index)) }.alpha(if (done) DONE_ALPHA else 1f),
                    colors = CardDefaults.cardColors(
                        containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                        Text(
                            t(Msg.COOK_STEP_NUMBER, index + 1) + if (highlighted) " · " + t(Msg.COOK_CURRENT_STEP) else "",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            inlineMarkdown(step, linkStyle),
                            style = MaterialTheme.typography.titleMedium,
                            textDecoration = if (done) TextDecoration.LineThrough else null,
                        )
                    }
                }
            }
            if (current == null) {
                Text(t(Msg.COOK_ALL_DONE), style = MaterialTheme.typography.titleMedium)
                if (onMarkEaten != null) Button(onClick = onMarkEaten, Modifier.fillMaxWidth()) { Text(t(Msg.COOK_MARK_EATEN)) }
            }
        }
    }
}

private const val DONE_ALPHA = 0.5f

/** One ingredient: a tick and the amount with its name, in a row of at least 56 dp so a floury thumb hits it; ticked rows are dimmed. */
@Composable
private fun CookRow(text: String, done: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Checkbox, onClick = onToggle).alpha(if (done) DONE_ALPHA else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Related),
    ) {
        // The row takes the tap; the box only shows the state.
        Checkbox(checked = done, onCheckedChange = null)
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
