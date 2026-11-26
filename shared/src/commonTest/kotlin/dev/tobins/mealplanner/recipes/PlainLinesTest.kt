package dev.tobins.mealplanner.recipes

import dev.tobins.mealplanner.api.RecipeLineInput
import dev.tobins.mealplanner.units.Dimension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlainLinesTest {
    private fun row(amount: Double?, unit: String?, text: String, group: String? = null) = RecipeLineInput(amount, unit, text, group = group)

    @Test
    fun dutchLinesAreAnAmountAUnitAndAText() {
        assertEquals(
            listOf(
                row(200.0, "g", "kipfilet"),
                row(1.0, "piece", "ui"),
                row(2.0, "tbsp", "olijfolie"),
                row(null, null, "zout"),
                row(0.5, "tsp", "komijn"),
                row(1.5, "kg", "kruimige aardappelen"),
                row(2.0, "clove", "knoflook"),
                row(1.0, "blik", "tomatenblokjes"),
            ),
            PlainLines.read("200 g kipfilet\n1 ui\n2 el olijfolie\nzout\n1/2 tl komijn\n1,5 kg kruimige aardappelen\n2 teentjes knoflook\n1 blik tomatenblokjes"),
        )
    }

    @Test
    fun englishLinesAsPeopleWriteThem() {
        assertEquals(
            listOf(
                row(1.5, "cup", "rice"),
                row(3.0, "clove", "garlic, minced"),
                row(500.0, "ml", "milk"),
                row(0.25, "tsp", "salt"),
                row(2.0, "piece", "large eggs"),
                row(null, null, "salt and pepper to taste"),
            ),
            PlainLines.read("1½ cups of rice\n3 cloves garlic, minced\n500ml milk\n¼ teaspoon salt\n2 large eggs\nsalt and pepper to taste"),
        )
    }

    @Test
    fun whatDoesNotStartWithAnAmountIsAllText() {
        assertEquals(
            listOf(row(null, null, "1-2 el olie"), row(null, null, "a pinch of salt"), row(null, null, "2x 100 g kwark"), row(null, null, "0 g suiker")),
            PlainLines.read("1-2 el olie\na pinch of salt\n2x 100 g kwark\n0 g suiker"),
        )
    }

    @Test
    fun theImportFormatListsAndEmptyLinesAreReadToo() {
        val pasted = """
            ```ingredients
            servings: 2
            # Saus
            - 30 | ml | sojasaus
            * 1 | onion | large
            •  | | sesamzaad

            1-2 | el | olie
            #
            200 g rijst
            ```
        """.trimIndent()
        assertEquals(
            listOf(
                row(30.0, "ml", "sojasaus", "Saus"),
                row(1.0, "onion", "large", "Saus"),
                row(null, null, "sesamzaad", "Saus"),
                row(null, null, "1-2 | el | olie", "Saus"),
                row(200.0, "g", "rijst"),
            ),
            PlainLines.read(pasted),
        )
    }

    @Test
    fun aRowReadsAsTheSameLineOfTheImportFormat() {
        val ui = PlainLines.read("1 ui").single().toIngredientLine()!!
        assertEquals(Dimension.COUNT to "ui", ui.dimension to ui.ingredientText)
        val oil = PlainLines.read("2 el olijfolie").single().toIngredientLine()!!
        assertEquals(HugoRecipeParser().parse("x.md", "```ingredients\n2 | el | olijfolie\n```").ingredients.single(), oil)
    }

    @Test
    fun aLineBreakInATextAUnitOrAGroupIsASpaceAndABarIsKept() {
        val line = RecipeLineInput(1.0, " g\n", "chicken\r\nthigh | skin on\n", 4, "To\nserve").toIngredientLine()!!
        assertEquals(listOf("g", "chicken thigh | skin on", "To serve"), listOf(line.unit, line.item, line.group))
        assertEquals(4L, line.ingredientId)
        assertNull(RecipeLineInput(null, "\n", "\n").toIngredientLine())
        assertEquals("a b", "a  b".oneLine())
    }
}
