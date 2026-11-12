package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.recipes.HugoRecipeParser
import dev.tobins.mealplanner.recipes.ParsedRecipe
import java.io.File
import java.security.MessageDigest

/**
 * Reads every recipe page from a local checkout of the cooking repo.
 * Section index pages (`_index.md`) are not recipes and are skipped.
 */
class CookingSiteReader(private val repoDir: File, private val parser: HugoRecipeParser = HugoRecipeParser()) {

    /** Each parsed recipe with the SHA-256 of its file, or null when the checkout has no `content/` folder. */
    fun readAll(): List<Pair<ParsedRecipe, String>>? {
        val content = File(repoDir, "content")
        if (!content.isDirectory) return null
        return content.walkTopDown()
            .filter { it.isFile && it.extension == "md" && it.name != "_index.md" }
            .sortedBy { it.path }
            .map { file ->
                val text = file.readText()
                parser.parse(file.relativeTo(content).invariantSeparatorsPath, text) to sha256(text)
            }
            .toList()
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
