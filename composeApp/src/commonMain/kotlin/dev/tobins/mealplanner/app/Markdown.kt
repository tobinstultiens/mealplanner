package dev.tobins.mealplanner.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** A block of a recipe's instructions. [text] still holds inline Markdown (bold, links). */
sealed interface MdBlock {
    val text: String

    data class Heading(val level: Int, override val text: String) : MdBlock
    data class Paragraph(override val text: String) : MdBlock

    /** [marker] is `•` for bullets or the number as written (`1.`). */
    data class ListItem(val marker: String, override val text: String) : MdBlock

    /** A table row, shown in a fixed-width font so columns line up. */
    data class TableRow(override val text: String) : MdBlock
}

private val shortcode = Regex("\\{\\{[<%].*?[>%]\\}\\}")
private val bullet = Regex("^\\s*[-*+]\\s+(.*)")
private val numbered = Regex("^\\s*(\\d+)[.)]\\s+(.*)")
private val heading = Regex("^(#{1,6})\\s+(.*)")
private val tableRule = Regex("^\\s*\\|?[-+:| ]+\\|?\\s*$")

/**
 * Splits the Markdown the cooking site uses into blocks: headings, bullet and numbered
 * lists, paragraphs and simple tables. Hugo shortcodes such as `{{< img >}}` are dropped,
 * since the app has nothing to show for them.
 */
fun parseMarkdown(markdown: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = mutableListOf<String>()
    fun flush() {
        if (paragraph.isNotEmpty()) blocks += MdBlock.Paragraph(paragraph.joinToString(" "))
        paragraph.clear()
    }
    for (raw in markdown.lines()) {
        val line = raw.replace(shortcode, "").trimEnd()
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> flush()
            heading.matches(trimmed) -> {
                flush()
                val (hashes, text) = heading.find(trimmed)!!.destructured
                blocks += MdBlock.Heading(hashes.length, text.trim())
            }
            bullet.matches(line) -> {
                flush()
                blocks += MdBlock.ListItem("•", bullet.find(line)!!.groupValues[1].trim())
            }
            numbered.matches(line) -> {
                flush()
                val (number, text) = numbered.find(line)!!.destructured
                blocks += MdBlock.ListItem("$number.", text.trim())
            }
            trimmed.startsWith("|") -> {
                flush()
                // Rule lines (|---+---|) only draw the table's borders.
                if (!tableRule.matches(trimmed)) {
                    blocks += MdBlock.TableRow(trimmed.trim('|').split('|').joinToString("  ") { it.trim() })
                }
            }
            else -> paragraph += trimmed
        }
    }
    flush()
    return blocks
}

private val inline = Regex("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|\\[(.+?)]\\((.+?)\\)")

/** Inline Markdown as styled text: `**bold**`, `*italic*` and `[links](url)`. */
fun inlineMarkdown(text: String, linkStyle: SpanStyle): AnnotatedString = buildAnnotatedString {
    var position = 0
    for (match in inline.findAll(text)) {
        append(text.substring(position, match.range.first))
        val (bold, italic, linkText, url) = match.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            italic.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
            else -> withLink(LinkAnnotation.Url(url, TextLinkStyles(linkStyle))) { append(linkText) }
        }
        position = match.range.last + 1
    }
    append(text.substring(position))
}

/** Renders recipe instructions written in Markdown. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The cooking site's own "Ingredients" heading is left over once the ingredients block is shown separately.
        val blocks = parseMarkdown(markdown).filterNot { it is MdBlock.Heading && it.text.trimEnd(':').lowercase() in setOf("ingredients", "ingrediënten") }
        for (block in blocks) {
            val text = inlineMarkdown(block.text, linkStyle)
            when (block) {
                is MdBlock.Heading -> Text(
                    text,
                    style = if (block.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                )
                is MdBlock.Paragraph -> Text(
                    text,
                    // A lone label such as "Steps:" reads as a small heading.
                    style = if (block.text.endsWith(":") && block.text.length < 40) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                )
                is MdBlock.ListItem -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(block.marker, Modifier.width(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
                is MdBlock.TableRow -> Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
