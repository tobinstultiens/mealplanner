package dev.tobins.mealplanner.recipes

import dev.tobins.mealplanner.units.Dimension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HugoRecipeParserTest {
    private val parser = HugoRecipeParser()

    private val gyudon = """
        ---
        tags:
          - Beef
          - Japanese
        title: Gyudon
        ---

        ```ingredients
        servings: 2
        400 | g | beef, thinly sliced (hot pot beef works)
        1 | onion | large
        1 1/2 | cup | rice
        # Sauce
        30 | ml | mirin
        2 | teentjes | garlic
        # Toppings
        | | sliced scallions
        1 | egg | per gyudon, for onsen tamago
        ```

        Steps:
        - Combine the Sauce ingredients.
        - Serve on top of your rice!
    """.trimIndent()

    @Test
    fun readsFrontMatterServingsAndInstructions() {
        val recipe = parser.parse("rice-bowls/gyudon.md", gyudon)

        assertEquals("Gyudon", recipe.title)
        assertEquals(listOf("Beef", "Japanese"), recipe.tags)
        assertEquals("rice-bowls", recipe.section)
        assertEquals(2, recipe.servings)
        assertTrue(recipe.hasIngredientsBlock)
        assertEquals(emptyList(), recipe.problems)
        assertTrue(recipe.instructions.startsWith("Steps:"))
        assertFalse("```" in recipe.instructions)
        assertFalse("mirin" in recipe.instructions)
    }

    @Test
    fun readsIngredientLinesWithUnitsAndGroups() {
        val lines = parser.parse("rice-bowls/gyudon.md", gyudon).ingredients
        assertEquals(7, lines.size)

        val beef = lines[0]
        assertEquals(400.0, beef.amount)
        assertEquals("g", beef.unit)
        assertEquals(Dimension.WEIGHT, beef.dimension)
        assertEquals(400.0, beef.baseAmount)
        assertNull(beef.group)

        val onion = lines[1]
        assertEquals(Dimension.COUNT, onion.dimension)
        assertEquals("onion", onion.unit)
        assertNull(onion.baseAmount)
        assertEquals("onion, large", onion.ingredientText)

        val rice = lines[2]
        assertEquals(1.5, rice.amount)
        assertEquals(1.5 * 236.588, rice.baseAmount!!, absoluteTolerance = 1e-9)

        assertEquals("Sauce", lines[3].group)

        val garlic = lines[4]
        assertEquals("clove", garlic.unit, "Dutch alias resolves to the canonical unit")
        assertEquals("garlic", garlic.ingredientText)

        val scallions = lines[5]
        assertNull(scallions.amount)
        assertNull(scallions.unit)
        assertNull(scallions.dimension)
        assertEquals("Toppings", scallions.group)
    }

    @Test
    fun recipeWithoutBlockKeepsBodyAndIsFlagged() {
        val text = "---\ntitle: \"Rice\"\ntags: [Vegetarian, 'Quick']\n---\n\nWash your rice.\n"
        val recipe = parser.parse("templates/rice.md", text)

        assertEquals("Rice", recipe.title)
        assertEquals(listOf("Vegetarian", "Quick"), recipe.tags)
        assertFalse(recipe.hasIngredientsBlock)
        assertNull(recipe.servings)
        assertEquals("Wash your rice.", recipe.instructions)
    }

    @Test
    fun reportsUnreadableLinesWithoutFailing() {
        val text = """
            ```ingredients
            servings: many
            2-3 | tbsp | oil
            just some prose
            1 | tbsp | soy sauce
        """.trimIndent()
        val recipe = parser.parse("wok/broken.md", text)

        assertEquals("Broken", recipe.title, "title falls back to the file name")
        assertEquals(listOf("soy sauce"), recipe.ingredients.map { it.item })
        assertEquals(4, recipe.problems.size)
        assertNull(recipe.servings)
    }

    @Test
    fun pageBundleTitleFallsBackToFolderName() {
        assertEquals("Ala mario", parser.parse("recipes/ala_mario/index.md", "No front matter").title)
    }

    @Test
    fun containerUnitsAreNotTheIngredient() {
        val text = "```ingredients\n1 | can | black beans, drained (16 oz)\n2 | slice | provolone\n1 | bay leaf |\n3 | Onion | Large (sweet)\n```"
        val lines = parser.parse("x/y.md", text).ingredients

        assertEquals(listOf("black beans, drained (16 oz)", "provolone", "bay leaf", "Onion, Large (sweet)"), lines.map { it.ingredientText })
        assertEquals(
            listOf("black beans", "provolone", "bay leaf", "onion"),
            lines.map { IngredientLine.coreText(it.ingredientText) },
        )
        assertEquals(listOf(false, false, true, true), lines.map { it.unitIsTheIngredient })
    }

    @Test
    fun lookupTextsCoverDescriptorsAndAlternatives() {
        assertEquals(listOf("garlic"), IngredientLine.lookupTexts("Garlic, minced (fresh)"))
        assertEquals(listOf("chopped green onion", "green onion"), IngredientLine.lookupTexts("chopped green onion"))
        assertEquals(
            listOf("dry sherry or white wine", "dry sherry", "white wine"),
            IngredientLine.lookupTexts("dry sherry or white wine"),
        )
        assertEquals(
            listOf("maida/all-purpose flour", "maida", "all-purpose flour"),
            IngredientLine.lookupTexts("Maida/all-purpose flour"),
        )
        assertEquals(listOf("ground pork"), IngredientLine.lookupTexts("ground pork"), "ground pork is not pork")
    }
}
