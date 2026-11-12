package dev.tobins.mealplanner.recipes

import dev.tobins.mealplanner.units.Dimension
import dev.tobins.mealplanner.units.UnitTable

/**
 * Reads a recipe page from the cooking repo (Hugo Markdown). Understands the
 * front matter fields `title` and `tags`, and the fenced `ingredients` block
 * described in the cooking repo's ADR 0001. Never fails: anything unreadable
 * ends up in [ParsedRecipe.problems].
 */
class HugoRecipeParser(private val units: UnitTable = UnitTable.Default) {

    /** @param path path relative to `content/`, with forward slashes. */
    fun parse(path: String, text: String): ParsedRecipe {
        val lines = text.replace("\r\n", "\n").split('\n')
        val problems = mutableListOf<String>()

        val (frontMatter, body) = splitFrontMatter(lines)
        val title = frontMatterScalar(frontMatter, "title") ?: titleFromPath(path)
        val tags = frontMatterList(frontMatter, "tags")

        var servings: Int? = null
        var sawBlock = false
        val ingredients = mutableListOf<IngredientLine>()
        val instructions = mutableListOf<String>()

        var inBlock = false
        var group: String? = null
        for (line in body) {
            val trimmed = line.trim()
            when {
                !inBlock && trimmed.startsWith("```ingredients") -> {
                    inBlock = true
                    sawBlock = true
                    group = null
                }
                inBlock && trimmed.startsWith("```") -> inBlock = false
                !inBlock -> instructions += line
                trimmed.isEmpty() -> Unit
                trimmed.startsWith("servings:") -> {
                    val value = trimmed.removePrefix("servings:").trim().toIntOrNull()?.takeIf { it > 0 }
                    when {
                        value == null -> problems += "Unreadable servings line: $trimmed"
                        servings == null -> servings = value
                    }
                }
                trimmed.startsWith("#") -> group = trimmed.trimStart('#').trim().ifEmpty { null }
                else -> parseIngredientLine(trimmed, group)
                    ?.let { ingredients += it }
                    ?: run { problems += "Unreadable ingredient line: $trimmed" }
            }
        }
        if (inBlock) problems += "Ingredients block is not closed"

        return ParsedRecipe(
            path = path,
            section = path.substringBefore('/', missingDelimiterValue = ""),
            title = title,
            tags = tags,
            servings = servings,
            ingredients = ingredients,
            instructions = instructions.joinToString("\n").trim(),
            hasIngredientsBlock = sawBlock,
            problems = problems,
        )
    }

    private fun parseIngredientLine(line: String, group: String?): IngredientLine? {
        val parts = line.split('|', limit = 3).map { it.trim() }
        if (parts.size != 3) return null
        val (amountText, unitText, item) = parts

        val amount = if (amountText.isEmpty()) null else parseAmount(amountText) ?: return null
        if (amount == null && unitText.isEmpty() && item.isEmpty()) return null
        if (unitText.isEmpty()) {
            return IngredientLine(amount, unit = null, dimension = null, baseAmount = null, item = item, group = group)
        }

        val known = units.resolve(unitText)
        val dimension = known?.dimension ?: Dimension.COUNT
        val baseAmount = if (known != null && amount != null && dimension != Dimension.COUNT) amount * known.factor else null
        return IngredientLine(
            amount = amount,
            unit = known?.key ?: unitText,
            dimension = dimension,
            baseAmount = baseAmount,
            item = item,
            group = group,
        )
    }

    private fun splitFrontMatter(lines: List<String>): Pair<List<String>, List<String>> {
        if (lines.firstOrNull()?.trim() != "---") return emptyList<String>() to lines
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) return emptyList<String>() to lines
        return lines.subList(1, end + 1) to lines.drop(end + 2)
    }

    /** A top-level `key: value` entry, with surrounding quotes removed. */
    private fun frontMatterScalar(frontMatter: List<String>, key: String): String? =
        frontMatter.firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')?.trim()?.unquote()?.ifEmpty { null }

    /** A top-level list, written either as `key: [a, b]` or as indented `- item` lines. */
    private fun frontMatterList(frontMatter: List<String>, key: String): List<String> {
        val index = frontMatter.indexOfFirst { it.startsWith("$key:") }
        if (index < 0) return emptyList()
        val inline = frontMatter[index].substringAfter(':').trim()
        if (inline.startsWith("[")) {
            return inline.trim('[', ']').split(',').map { it.trim().unquote() }.filter { it.isNotEmpty() }
        }
        return frontMatter.drop(index + 1)
            .takeWhile { it.isBlank() || it.trimStart().startsWith("-") }
            .filter { it.isNotBlank() }
            .map { it.trimStart().removePrefix("-").trim().unquote() }
            .filter { it.isNotEmpty() }
    }

    private fun String.unquote(): String =
        if (length >= 2 && (first() == '"' || first() == '\'') && last() == first()) substring(1, length - 1) else this

    /** `recipes/ala_mario/index.md` -> `Ala mario`; `soup/miso-soup.md` -> `Miso soup`. */
    private fun titleFromPath(path: String): String {
        val segments = path.removeSuffix(".md").split('/')
        val name = if (segments.last() == "index" && segments.size > 1) segments[segments.size - 2] else segments.last()
        return name.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
