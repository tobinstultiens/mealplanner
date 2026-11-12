package dev.tobins.mealplanner.server

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.MappedLine
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeNutrition
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.SyncResult
import dev.tobins.mealplanner.nutrition.NutritionCalculator
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.recipes.ParsedRecipe
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.db.Recipe
import dev.tobins.mealplanner.units.Dimension
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.Properties

/** Opens (and creates or migrates) the SQLite database at [url], e.g. `jdbc:sqlite:/data/mealplanner.db`. */
fun openDatabase(url: String): Pair<SqlDriver, Database> {
    url.removePrefix("jdbc:sqlite:").takeIf { it.isNotEmpty() && !it.startsWith(":") }
        ?.let { File(it).absoluteFile.parentFile?.mkdirs() }
    val driver = JdbcSqliteDriver(url, Properties().apply { put("foreign_keys", "true") }, Database.Schema)
    return driver to Database(driver)
}

/** Sections of the cooking site whose pages are building blocks, not meals. */
private val componentSections = setOf("sauces", "spices", "templates", "ideas", "fermenting", "baking", "cocktails")

class RecipeRepository(private val db: Database, private val ingredients: IngredientRepository) {
    private val q = db.recipeQueries

    fun list(): List<RecipeSummary> {
        val resolve = ingredients.resolver()
        return q.selectActive().executeAsList().map { row -> row.toSummary(nutritionOf(row, linesOf(row.id), resolve)) }
    }

    fun find(id: Long): RecipeDetail? {
        val row = q.selectById(id).executeAsOneOrNull() ?: return null
        val resolve = ingredients.resolver()
        val lines = linesOf(id)
        return RecipeDetail(
            summary = row.toSummary(nutritionOf(row, lines, resolve)),
            ingredients = lines.map { line ->
                val ingredient = resolve(line)
                MappedLine(line, ingredient?.id, ingredient?.name, ingredient?.let { NutritionCalculator.quantityOf(line, it)?.grams })
            },
            instructions = row.instructions,
            problems = Json.decodeFromString(row.problemsJson),
        )
    }

    private fun linesOf(recipeId: Long): List<IngredientLine> = q.selectIngredients(recipeId).executeAsList().map {
        IngredientLine(
            amount = it.amount,
            unit = it.unit,
            dimension = it.dimension?.let(Dimension::valueOf),
            baseAmount = it.baseAmount,
            item = it.item,
            group = it.groupName,
        )
    }

    /** Null when the recipe has no amounts to compute from. */
    private fun nutritionOf(row: Recipe, lines: List<IngredientLine>, resolve: (IngredientLine) -> Ingredient?): RecipeNutrition? {
        val servings = row.servings?.toInt() ?: return null
        if (lines.none { it.amount != null }) return null
        return NutritionCalculator.perServing(lines.map { it to resolve(it) }, servings)
    }

    /**
     * Makes the stored cooking-site recipes match [parsed], the full current content
     * of the repo. Files are identified by path; unchanged hashes are skipped; recipes
     * whose file is gone are marked removed, and come back if the file reappears.
     */
    fun syncCookingSite(parsed: List<Pair<ParsedRecipe, String>>, now: Instant = Instant.now()): SyncResult {
        var added = 0
        var updated = 0
        var unchanged = 0
        var removed = 0
        db.transaction {
            val existing = q.selectSynced().executeAsList().associateBy { it.sourcePath }
            for ((recipe, hash) in parsed) {
                val row = existing[recipe.path]
                when {
                    row == null -> {
                        q.insertRecipe(
                            source = RecipeSource.COOKING_SITE.name,
                            sourcePath = recipe.path,
                            contentHash = hash,
                            section = recipe.section,
                            title = recipe.title,
                            tagsJson = Json.encodeToString(recipe.tags),
                            servings = recipe.servings?.toLong(),
                            instructions = recipe.instructions,
                            hasIngredientsBlock = recipe.hasIngredientsBlock.toLong(),
                            problemsJson = Json.encodeToString(recipe.problems),
                            kind = (if (recipe.section in componentSections) RecipeKind.COMPONENT else RecipeKind.MEAL).name,
                        )
                        replaceIngredients(q.lastInsertId().executeAsOne(), recipe.ingredients)
                        added++
                    }
                    row.contentHash == hash && row.removedAt == null -> unchanged++
                    else -> {
                        q.updateSynced(
                            contentHash = hash,
                            section = recipe.section,
                            title = recipe.title,
                            tagsJson = Json.encodeToString(recipe.tags),
                            servings = recipe.servings?.toLong(),
                            instructions = recipe.instructions,
                            hasIngredientsBlock = recipe.hasIngredientsBlock.toLong(),
                            problemsJson = Json.encodeToString(recipe.problems),
                            id = row.id,
                        )
                        replaceIngredients(row.id, recipe.ingredients)
                        updated++
                    }
                }
            }
            val present = parsed.mapTo(HashSet()) { it.first.path }
            for (row in existing.values) {
                if (row.sourcePath !in present && row.removedAt == null) {
                    q.markRemoved(now.toString(), row.id)
                    removed++
                }
            }
        }
        val problems = parsed.map { it.first }.filter { it.problems.isNotEmpty() }.associate { it.path to it.problems }
        return SyncResult(added, updated, unchanged, removed, problems)
    }

    private fun replaceIngredients(recipeId: Long, lines: List<IngredientLine>) {
        q.deleteIngredients(recipeId)
        lines.forEachIndexed { index, line ->
            q.insertIngredient(recipeId, index.toLong(), line.amount, line.unit, line.dimension?.name, line.baseAmount, line.item, line.group)
        }
    }

    private fun Recipe.toSummary(nutrition: RecipeNutrition?) = RecipeSummary(
        id = id,
        title = title,
        section = section,
        tags = Json.decodeFromString(tagsJson),
        servings = servings?.toInt(),
        source = RecipeSource.valueOf(source),
        kind = RecipeKind.valueOf(kind),
        plannable = hasIngredientsBlock != 0L && servings != null,
        nutrition = nutrition,
    )

    private fun Boolean.toLong() = if (this) 1L else 0L
}
