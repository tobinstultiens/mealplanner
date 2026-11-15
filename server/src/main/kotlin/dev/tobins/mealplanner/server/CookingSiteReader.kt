package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.recipes.HugoRecipeParser
import dev.tobins.mealplanner.recipes.ParsedRecipe
import java.io.File
import java.security.MessageDigest

/** A recipe page of the cooking site: the recipe, the SHA-256 of the page's text, and the picture it shows. */
data class SitePage(val recipe: ParsedRecipe, val hash: String, val picture: File? = null)

/**
 * Reads every recipe page from a local checkout of the cooking repo.
 * Section index pages (`_index.md`) are not recipes and are skipped.
 */
class CookingSiteReader(private val repoDir: File, private val parser: HugoRecipeParser = HugoRecipeParser()) {
    constructor(repo: CookingRepo) : this(repo.dir, HugoRecipeParser(repo.units()))

    /** Every recipe page, or null when the checkout has no `content/` folder. */
    fun readAll(): List<SitePage>? {
        val content = File(repoDir, "content")
        if (!content.isDirectory) return null
        val pictures = PagePictureFinder(repoDir)
        return content.walkTopDown()
            .filter { it.isFile && it.extension == "md" && it.name != "_index.md" }
            .sortedBy { it.path }
            .map { file ->
                val text = file.readText()
                SitePage(parser.parse(file.relativeTo(content).invariantSeparatorsPath, text), sha256(text), pictures.find(file, text))
            }
            .toList()
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
