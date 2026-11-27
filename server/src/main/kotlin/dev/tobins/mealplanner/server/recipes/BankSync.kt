package dev.tobins.mealplanner.server.recipes

import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.SyncResult
import java.time.Instant

/** Sections of the cooking site whose pages are building blocks, not meals. */
private val componentSections = setOf("sauces", "spices", "templates", "ideas", "fermenting", "baking", "cocktails")

/**
 * Brings the account's bank in line with its cooking site, as [CookingSync] read it: the recipes' rows and lines. The
 * clone, the schedule and the pictures are [CookingSync]'s and [RecipePictures]'.
 */
class BankSync(private val store: RecipeStore) {
    /**
     * Makes the bank's cooking-site recipes match [pages], the full current content
     * of the repo. Files are identified by path; unchanged hashes are skipped; recipes
     * whose file is gone are marked removed (hidden), and come back if the file reappears.
     * A new file's recipe goes to the bank. Whether a recipe is added and in the rotation
     * is the account's and never changed here. An added recipe is taken (issue 340): the
     * account's own copy, which a sync leaves as it is, whether its file changed or went,
     * and counts as unchanged; its row keeps the file's path, so the file is not offered
     * as new again. Pictures are left to [RecipePictures.sync], after this.
     */
    fun syncCookingSite(pages: List<SitePage>, now: Instant = Instant.now()): SyncResult {
        var added = 0
        var updated = 0
        var unchanged = 0
        var removed = 0
        store.writing {
            val existing = store.synced().associateBy { it.sourcePath }
            for ((recipe, hash) in pages) {
                val row = existing[recipe.path]
                when {
                    row == null -> {
                        val id = store.insert(
                            source = RecipeSource.COOKING_SITE,
                            sourcePath = recipe.path,
                            contentHash = hash,
                            section = recipe.section,
                            title = recipe.title,
                            tags = recipe.tags,
                            servings = recipe.servings,
                            instructions = recipe.instructions,
                            hasIngredientsBlock = recipe.hasIngredientsBlock,
                            problems = recipe.problems,
                            kind = if (recipe.section in componentSections) RecipeKind.COMPONENT else RecipeKind.MEAL,
                            // New on the site: in the bank until the account adds it.
                            addedAt = null,
                            inRotation = false,
                        )
                        store.replaceLines(id, recipe.ingredients)
                        added++
                    }
                    row.addedAt != null || (row.contentHash == hash && row.removedAt == null) -> unchanged++
                    else -> {
                        store.updateSynced(
                            id = row.id,
                            contentHash = hash,
                            section = recipe.section,
                            title = recipe.title,
                            tags = recipe.tags,
                            servings = recipe.servings,
                            instructions = recipe.instructions,
                            hasIngredientsBlock = recipe.hasIngredientsBlock,
                            problems = recipe.problems,
                        )
                        store.replaceLines(row.id, recipe.ingredients)
                        updated++
                    }
                }
            }
            val present = pages.mapTo(HashSet()) { it.recipe.path }
            for (row in existing.values) {
                if (row.sourcePath !in present && row.removedAt == null && row.addedAt == null) {
                    store.markRemoved(row.id, now)
                    removed++
                }
            }
        }
        val problems = pages.map { it.recipe }.filter { it.problems.isNotEmpty() }.associate { it.path to it.problems }
        return SyncResult(added, updated, unchanged, removed, problems = problems)
    }
}
