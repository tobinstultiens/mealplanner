package dev.tobins.mealplanner.recipes

import dev.tobins.mealplanner.api.RecipeLineInput
import dev.tobins.mealplanner.units.UnitTable

/**
 * Pasted ingredient lines as a recipe's rows (issue 342), one row per line that says something (not an empty one, nor one of only `|`): plain lines as people
 * write them, in Dutch or English (`200 g kipfilet`, `2 el olijfolie`, `1 ui`, `1½ cups of rice`, `zout`), and lines of
 * the import format (`200 | g | kipfilet`, `# Saus` for the heading of the lines after it). The rows carry no ingredient:
 * the editor looks each up as a save would ([dev.tobins.mealplanner.api.MappedLine]).
 *
 * A plain line is an amount, a unit and a text, each optional and in that order. The amount is a number at the start
 * ([parseAmount]: `1.5`, `1,5`, `1/2`, `1 1/2`, and `½` and the like), the unit the word after it when it is one of
 * [UnitTable] or a word that portions ([IngredientLine.pieceWords]: `blik`, `snufje`), written as its table key (`el` is
 * `tbsp`), and the rest is the text, without a leading "of". An amount without a unit counts pieces (`1 ui` is one
 * piece of onion), as an amount alone says nothing a recipe can count. A line that does not start with an amount
 * (`zout`, `1-2 el olie`, `a pinch of salt`) is all text, without an amount: a row the editor shows as not counted
 * until it is given one.
 */
object PlainLines {
    fun read(text: String, units: UnitTable = UnitTable.Default): List<RecipeLineInput> {
        var group: String? = null
        val rows = mutableListOf<RecipeLineInput>()
        for (raw in text.lines()) {
            val line = raw.trim().replace(bullet, "")
            when {
                // What a block of the import format holds besides its lines.
                // A line of only bars and blanks (`| |`) says nothing, as an empty one; the server refuses the whole paste for it.
                line.all { it == '|' || it.isWhitespace() } || line.startsWith("```") || line.startsWith("servings:") -> Unit
                line.startsWith("#") -> group = line.trimStart('#').trim().ifEmpty { null }
                '|' in line -> rows += importLine(line, group)
                else -> rows += plainLine(line, group, units)
            }
        }
        return rows
    }

    /** A line of the import format, `amount | unit | text`; one whose amount cannot be read is all text. */
    private fun importLine(line: String, group: String?): RecipeLineInput {
        val parts = line.split('|', limit = 3).map { it.trim() }
        if (parts.size != 3) return RecipeLineInput(text = line, group = group)
        val (amountText, unit, text) = parts
        val amount = if (amountText.isEmpty()) null else parseAmount(amountText)?.takeIf { it > 0 } ?: return RecipeLineInput(text = line, group = group)
        return RecipeLineInput(amount, unit.ifEmpty { null }, text, group = group)
    }

    private fun plainLine(line: String, group: String?, units: UnitTable): RecipeLineInput {
        val spelled = vulgarFractions.entries.fold(line) { text, (glyph, fraction) -> text.replace(Regex("(\\d)$glyph"), "$1 $fraction").replace(glyph, fraction) }
        val match = leadingAmount.find(spelled) ?: return RecipeLineInput(text = line, group = group)
        val amount = parseAmount(match.groupValues[1])?.takeIf { it > 0 } ?: return RecipeLineInput(text = line, group = group)
        val glued = match.groupValues[2]
        var words = match.groupValues[3].split(' ').filter { it.isNotEmpty() }

        // `200g`: a number with letters right after it is an amount only when the letters are a unit.
        val unitWord = glued.ifEmpty { words.firstOrNull().orEmpty() }.lowercase().removeSuffix(".")
        val unit = unitOf(unitWord, units)
        if (glued.isNotEmpty() && unit == null) return RecipeLineInput(text = line, group = group)
        if (unit != null && glued.isEmpty()) words = words.drop(1)
        if (unit != null && words.firstOrNull()?.lowercase() in setOf("of", "van")) words = words.drop(1)

        val text = words.joinToString(" ")
        return RecipeLineInput(amount, unit ?: PIECE.takeIf { text.isNotEmpty() }, text, group = group)
    }

    /** [word] as a unit: the key of a unit of [units] (`el` is `tbsp`), or a word that portions (`blik`); null for neither. */
    private fun unitOf(word: String, units: UnitTable): String? =
        units.resolve(word)?.key ?: word.takeIf { it in IngredientLine.pieceWords }

    /** The unit of a count without one: the key of the table's pieces. */
    private const val PIECE = "piece"

    /** `- `, `* `, `• ` or `– ` before a line of a list. */
    private val bullet = Regex("^[-*•–]\\s+")

    /**
     * A number at the start (`1`, `1.5`, `1,5`, `1/2`, `1 1/2`), the letters glued to it if any (units are written in
     * plain letters), and the rest after a space. A number followed by anything else (`1-2`) is no amount.
     */
    private val leadingAmount = Regex("^(\\d+ \\d+/\\d+|\\d+/\\d+|\\d+(?:[.,]\\d+)?)([a-zA-Z]*)(?:\\s+(.*))?$")

    private val vulgarFractions = mapOf("½" to "1/2", "¼" to "1/4", "¾" to "3/4", "⅓" to "1/3", "⅔" to "2/3", "⅛" to "1/8")
}
