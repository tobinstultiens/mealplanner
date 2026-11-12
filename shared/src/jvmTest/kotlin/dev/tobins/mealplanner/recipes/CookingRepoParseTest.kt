package dev.tobins.mealplanner.recipes

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the parser over every page of a local checkout of the cooking repo.
 * Skipped when the checkout is not present (for example in CI).
 */
class CookingRepoParseTest {
    private val content = File(System.getProperty("cookingRepo") ?: "../../cooking", "content")

    @Test
    fun everyRecipePageParsesWithoutProblems() {
        if (!content.isDirectory) {
            println("Cooking repo not found at ${content.absolutePath}; skipping")
            return
        }
        val parser = HugoRecipeParser()
        val recipes = content.walkTopDown()
            .filter { it.isFile && it.extension == "md" && it.name != "_index.md" }
            .map { parser.parse(it.relativeTo(content).invariantSeparatorsPath, it.readText()) }
            .toList()

        assertTrue(recipes.size > 50, "expected the full recipe collection, found ${recipes.size}")
        val problems = recipes.filter { it.problems.isNotEmpty() }.associate { it.path to it.problems }
        assertEquals(emptyMap(), problems)

        val withBlock = recipes.filter { it.hasIngredientsBlock }
        assertTrue(withBlock.all { it.servings != null && it.ingredients.isNotEmpty() })
        assertTrue(recipes.all { it.title.isNotBlank() && it.section.isNotBlank() })
        println("Parsed ${recipes.size} pages, ${withBlock.size} with an ingredients block")
    }
}
