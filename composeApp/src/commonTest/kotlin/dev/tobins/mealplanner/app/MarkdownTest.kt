package dev.tobins.mealplanner.app

import androidx.compose.ui.text.SpanStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownTest {
    @Test
    fun splitsHeadingsListsParagraphsAndTables() {
        val blocks = parseMarkdown(
            """
            ## Spice up your rice
            What is nice to do
            is adding whole seeds.

            Steps:
            - Combine the sauce.
            * Serve on rice!
            1. Wash your rice.
            2) Fry it.

            |------+-------|
            | Rice | Water |
            |------+-------|
            | Sushi Rice | 1 + 1/3 |
            {{< img name="ala_mario-1" lazy=false >}}
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                MdBlock.Heading(2, "Spice up your rice"),
                MdBlock.Paragraph("What is nice to do is adding whole seeds."),
                MdBlock.Paragraph("Steps:"),
                MdBlock.ListItem("•", "Combine the sauce."),
                MdBlock.ListItem("•", "Serve on rice!"),
                MdBlock.ListItem("1.", "Wash your rice."),
                MdBlock.ListItem("2.", "Fry it."),
                MdBlock.TableRow("Rice  Water"),
                MdBlock.TableRow("Sushi Rice  1 + 1/3"),
            ),
            blocks,
        )
    }

    @Test
    fun emptyAndShortcodeOnlyTextGivesNoBlocks() {
        assertEquals(emptyList(), parseMarkdown(""))
        assertEquals(emptyList(), parseMarkdown("{{< img name=\"x\" >}}\n\n{{<toc-tree>}}"))
    }

    @Test
    fun inlineMarkupBecomesPlainStyledText() {
        val text = inlineMarkdown("Use **two** eggs, *gently*, see [Deeg](https://www.ah.nl/x).", SpanStyle())
        assertEquals("Use two eggs, gently, see Deeg.", text.text)
        assertEquals(2, text.spanStyles.size)
        assertEquals(1, text.getLinkAnnotations(0, text.length).size)
        assertEquals("No markup, 2 * 3 = 6", inlineMarkdown("No markup, 2 * 3 = 6", SpanStyle()).text)
    }
}
