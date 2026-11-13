package dev.tobins.mealplanner.server

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.MappedLine
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSettings
import dev.tobins.mealplanner.api.RecipeNutrition
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.SyncResult
import dev.tobins.mealplanner.nutrition.NutritionCalculator
import dev.tobins.mealplanner.planner.PlanCandidate
import dev.tobins.mealplanner.recipes.HugoRecipeParser
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

/** Section name given to recipes created in the app. */
private const val MANUAL_SECTION = "own"

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
                MappedLine(
                    line = line,
                    ingredientId = ingredient?.id,
                    ingredientName = ingredient?.name,
                    grams = ingredient?.takeUnless { it.negligible }?.let { NutritionCalculator.quantityOf(line, it)?.grams },
                    negligible = ingredient?.negligible == true,
                )
            },
            instructions = row.instructions,
            problems = Json.decodeFromString(row.problemsJson),
        )
    }

    /**
     * Recipes the planner may use: meals whose nutrition is complete. Which slots a recipe
     * suits follows from its section on the cooking site until that can be set per recipe.
     */
    fun planCandidates(onOffer: Set<Long> = emptySet()): List<PlanCandidate> {
        val resolve = ingredients.resolver()
        val onHand = ingredients.pantry().onHand.mapTo(HashSet()) { it.id }
        return q.selectActive().executeAsList().mapNotNull { row ->
            if (row.kind != RecipeKind.MEAL.name) return@mapNotNull null
            val lines = linesOf(row.id)
            val nutrition = nutritionOf(row, lines, resolve)?.takeIf { it.complete } ?: return@mapNotNull null
            PlanCandidate(
                recipeId = row.id,
                title = row.title,
                slots = slotsOf(row),
                servings = row.servings!!.toInt(),
                kcal = nutrition.perServing.kcal ?: 0.0,
                proteinG = nutrition.perServing.proteinG ?: 0.0,
                tags = Json.decodeFromString(row.tagsJson),
                onHandCount = lines.mapNotNull { resolve(it)?.id }.distinct().count { it in onHand },
                offerCount = lines.mapNotNull { resolve(it)?.id }.distinct().count { it in onOffer },
            )
        }
    }

    fun onHandIds(): Set<Long> = ingredients.pantry().onHand.mapTo(HashSet()) { it.id }

    /** A recipe's base yield and its lines with the ingredient each one means; null if the recipe is gone. */
    fun linesWithIngredients(recipeId: Long): Pair<Int, List<Pair<IngredientLine, Ingredient?>>>? {
        val row = q.selectById(recipeId).executeAsOneOrNull() ?: return null
        val resolve = ingredients.resolver()
        return (row.servings?.toInt() ?: 1) to linesOf(recipeId).map { it to resolve(it) }
    }

    /** Creates a recipe of the user's own. */
    fun create(input: RecipeInput): RecipeDetail = db.transactionWithResult {
        val parsed = parse(input)
        q.insertRecipe(
            source = RecipeSource.MANUAL.name,
            sourcePath = null,
            contentHash = null,
            section = MANUAL_SECTION,
            title = parsed.title,
            tagsJson = Json.encodeToString(input.tags),
            servings = input.servings.toLong(),
            instructions = input.instructions.trim(),
            hasIngredientsBlock = 1,
            problemsJson = "[]",
            kind = RecipeKind.MEAL.name,
        )
        val id = q.lastInsertId().executeAsOne()
        q.updateSettings(RecipeKind.MEAL.name, Json.encodeToString(input.mealSlots), id)
        replaceIngredients(id, parsed.ingredients)
        find(id)!!
    }

    /** Replaces a recipe of the user's own; imported recipes are edited on the cooking site. */
    fun update(id: Long, input: RecipeInput): RecipeDetail = db.transactionWithResult {
        val row = q.selectById(id).executeAsOneOrNull() ?: throw NotFoundException("No recipe with id $id")
        if (row.source != RecipeSource.MANUAL.name) throw ConflictException("Only your own recipes can be edited here")
        val parsed = parse(input)
        q.updateManual(parsed.title, Json.encodeToString(input.tags), input.servings.toLong(), input.instructions.trim(), "[]", id)
        q.updateSettings(row.kind, Json.encodeToString(input.mealSlots), id)
        replaceIngredients(id, parsed.ingredients)
        find(id)!!
    }

    fun delete(id: Long) {
        val row = q.selectById(id).executeAsOneOrNull() ?: throw NotFoundException("No recipe with id $id")
        if (row.source != RecipeSource.MANUAL.name) throw ConflictException("Only your own recipes can be deleted here")
        q.deleteManual(id)
    }

    fun updateSettings(id: Long, settings: RecipeSettings): RecipeDetail {
        q.selectById(id).executeAsOneOrNull() ?: throw NotFoundException("No recipe with id $id")
        q.updateSettings(settings.kind.name, Json.encodeToString(settings.mealSlots), id)
        return find(id)!!
    }

    private fun parse(input: RecipeInput): ParsedRecipe {
        require(input.title.isNotBlank()) { "title must not be blank" }
        require(input.servings in 1..100) { "servings must be between 1 and 100" }
        val parsed = HugoRecipeParser().parse("$MANUAL_SECTION/x.md", "```ingredients\n${input.ingredients}\n```")
        require(parsed.problems.isEmpty()) { parsed.problems.joinToString("; ") }
        require(parsed.ingredients.isNotEmpty()) { "at least one ingredient line is required" }
        return parsed.copy(title = input.title.trim())
    }

    private fun slotsOf(row: Recipe): Set<MealSlot> =
        row.mealSlotsJson?.let { Json.decodeFromString<List<MealSlot>>(it).toSet() } ?: slotsFor(row.section)

    private fun slotsFor(section: String): Set<MealSlot> = when (section) {
        "deserts" -> setOf(MealSlot.SNACK)
        "sandwiches" -> setOf(MealSlot.LUNCH)
        "salads", "soup" -> setOf(MealSlot.LUNCH, MealSlot.DINNER)
        else -> setOf(MealSlot.DINNER)
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
        return SyncResult(added, updated, unchanged, removed, problems = problems)
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
        mealSlots = slotsOf(this).sortedBy { it.ordinal },
    )

    private fun Boolean.toLong() = if (this) 1L else 0L
}
