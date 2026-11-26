package dev.tobins.mealplanner.recipes

import dev.tobins.mealplanner.api.RecipeLineInput

/**
 * [this] line as a recipe saved with it stores it: what the same line of the import format reads as
 * ([HugoRecipeParser.line]), with its ingredient; null when it has no amount, unit or text. The server reads the lines
 * it saves through this, and the app's editor the lines it shows totals for, so that the two agree (issue 342).
 *
 * A line break in the text, the unit or the group is a space ([oneLine]): the installed app edits a recipe as the text
 * of the import format, one line per ingredient, and a line broken in two would be refused when it saves it.
 */
fun RecipeLineInput.toIngredientLine(parser: HugoRecipeParser = HugoRecipeParser()): IngredientLine? =
    parser.line(amount, unit.orEmpty().oneLine(), text.oneLine(), group?.oneLine()?.ifEmpty { null })?.copy(ingredientId = ingredientId)

/** [text] on one line: each line break, with the spaces around it, is one space; trimmed. */
fun String.oneLine(): String = replace(lineBreak, " ").trim()

/** Every character that ends a line somewhere: the import format splits at `\n`, a text field may show `\r` and the rest as one. */
private val lineBreak = Regex("\\s*[\\r\\n\\u000B\\u000C\\u0085\\u2028\\u2029]+\\s*")
