package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.pricing.Pricing
import dev.tobins.mealplanner.api.RecipeCost
import java.time.LocalDate
import java.time.ZoneId
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.MappedLine
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSettings
import dev.tobins.mealplanner.api.Routines
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

private const val ROUTINES_KEY = "routines"

/** Section name given to recipes created in the app. */
private const val MANUAL_SECTION = "own"

/** Sections of the cooking site whose pages are building blocks, not meals. */
private val componentSections = setOf("sauces", "spices", "templates", "ideas", "fermenting", "baking", "cocktails")

/** One account's recipes. */
class RecipeRepository(
    private val db: Database,
    private val ingredients: IngredientRepository,
    private val userId: Long,
    /** Prices with the offers valid between two dates. */
    private val pricing: (LocalDate, LocalDate) -> Pricing = { _, _ -> Pricing.NONE },
) {
    private val q = db.recipeQueries

    /** Prices with this week's offers: what a recipe costs if bought now. */
    private fun currentPricing(): Pricing {
        val today = LocalDate.now(ZoneId.of("Europe/Amsterdam"))
        return pricing(today, today.plusDays(6))
    }

    fun list(): List<RecipeSummary> {
        val resolve = ingredients.resolver()
        val prices = currentPricing()
        return q.selectActive(userId).executeAsList().map { row ->
            val lines = linesOf(row.id)
            row.toSummary(nutritionOf(row, lines, resolve), costOf(row, lines, resolve, prices))
        }
    }

    fun find(id: Long): RecipeDetail? {
        val row = q.selectById(userId, id).executeAsOneOrNull() ?: return null
        val resolve = ingredients.resolver()
        val lines = linesOf(id)
        return RecipeDetail(
            summary = row.toSummary(nutritionOf(row, lines, resolve), costOf(row, lines, resolve, currentPricing())),
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
    /**
     * One recipe as a plan candidate, even with incomplete nutrition (used when the user
     * puts a recipe in a slot themselves). Null when the recipe has no amounts at all.
     */
    fun candidate(id: Long): PlanCandidate? {
        val row = q.selectById(userId, id).executeAsOneOrNull() ?: return null
        val resolve = ingredients.resolver()
        val nutrition = nutritionOf(row, linesOf(id), resolve) ?: return null
        return PlanCandidate(
            recipeId = row.id,
            title = row.title,
            slots = slotsOf(row),
            servings = row.servings!!.toInt(),
            kcal = nutrition.perServing.kcal ?: 0.0,
            proteinG = nutrition.perServing.proteinG ?: 0.0,
        )
    }

    /**
     * @param dislikes words for ingredients never to plan; a recipe with a line whose text
     *   or linked ingredient name contains one of them as a word (or its plural) is left out.
     */
    fun planCandidates(
        onOffer: Set<Long> = emptySet(),
        dislikes: List<String> = emptyList(),
        prices: Pricing = Pricing.NONE,
    ): List<PlanCandidate> {
        val resolve = ingredients.resolver()
        val disliked = dislikes.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .map { Regex("\\b${Regex.escape(it)}(s|es|en|n)?\\b", RegexOption.IGNORE_CASE) }
        val onHand = ingredients.pantry().onHand.mapTo(HashSet()) { it.id }
        return q.selectActive(userId).executeAsList().mapNotNull { row ->
            if (row.kind != RecipeKind.MEAL.name) return@mapNotNull null
            val lines = linesOf(row.id)
            if (disliked.isNotEmpty() && lines.any { line ->
                    val names = listOfNotNull(line.ingredientText, resolve(line)?.name)
                    disliked.any { word -> names.any(word::containsMatchIn) }
                }
            ) return@mapNotNull null
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
                costPerServing = costOf(row, lines, resolve, prices)?.perServingWithOffers,
                rating = row.rating?.toInt(),
            )
        }
    }

    fun onHandIds(): Set<Long> = ingredients.pantry().onHand.mapTo(HashSet()) { it.id }

    /** A recipe's base yield and its lines with the ingredient each one means; null if the recipe is gone. */
    fun linesWithIngredients(recipeId: Long): Pair<Int, List<Pair<IngredientLine, Ingredient?>>>? {
        val row = q.selectById(userId, recipeId).executeAsOneOrNull() ?: return null
        val resolve = ingredients.resolver()
        return (row.servings?.toInt() ?: 1) to linesOf(recipeId).map { it to resolve(it) }
    }

    /** Creates a recipe of the user's own, or one generated for them ([source] GENERATED). */
    fun create(input: RecipeInput, source: RecipeSource = RecipeSource.MANUAL): RecipeDetail = db.transactionWithResult {
        require(source != RecipeSource.COOKING_SITE) { "cooking site recipes come from the sync" }
        val parsed = parse(input)
        q.insertRecipe(
            userId = userId,
            source = source.name,
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
        q.updateSettings(RecipeKind.MEAL.name, Json.encodeToString(input.mealSlots), userId, id)
        replaceIngredients(id, parsed.ingredients)
        find(id)!!
    }

    /** Replaces a recipe of the user's own; imported recipes are edited on the cooking site. */
    fun update(id: Long, input: RecipeInput): RecipeDetail = db.transactionWithResult {
        val row = q.selectById(userId, id).executeAsOneOrNull() ?: throw NotFoundException("No recipe with id $id")
        if (row.source == RecipeSource.COOKING_SITE.name) throw ConflictException("Recipes from the cooking site are edited there")
        val parsed = parse(input)
        q.updateManual(parsed.title, Json.encodeToString(input.tags), input.servings.toLong(), input.instructions.trim(), "[]", userId, id)
        q.updateSettings(row.kind, Json.encodeToString(input.mealSlots), userId, id)
        replaceIngredients(id, parsed.ingredients)
        find(id)!!
    }

    fun delete(id: Long) {
        val row = q.selectById(userId, id).executeAsOneOrNull() ?: throw NotFoundException("No recipe with id $id")
        if (row.source == RecipeSource.COOKING_SITE.name) throw ConflictException("Recipes from the cooking site are removed there")
        q.deleteManual(userId, id)
    }

    fun setRating(id: Long, rating: Int?): RecipeDetail {
        require(rating == null || rating in 1..5) { "rating must be 1 to 5, or null" }
        q.selectById(userId, id).executeAsOneOrNull() ?: throw NotFoundException("No recipe with id $id")
        q.setRating(rating?.toLong(), userId, id)
        return find(id)!!
    }

    /** Titles of the most recently planned recipes, newest first; for variety in generation. */
    fun titles(ids: Collection<Long>): List<String> = ids.mapNotNull { q.selectById(userId, it).executeAsOneOrNull()?.title }

    fun allTitles(): List<String> = q.selectActive(userId).executeAsList().map { it.title }

    fun routines(): Routines =
        db.userQueries.selectUserMeta(userId, ROUTINES_KEY).executeAsOneOrNull()?.let { Json.decodeFromString<Routines>(it) } ?: Routines()

    fun saveRoutines(routines: Routines): Routines {
        listOfNotNull(routines.breakfast, routines.snack).forEach {
            q.selectById(userId, it).executeAsOneOrNull() ?: throw NotFoundException("No recipe with id $it")
        }
        db.userQueries.upsertUserMeta(userId, ROUTINES_KEY, Json.encodeToString(routines))
        return routines
    }

    fun updateSettings(id: Long, settings: RecipeSettings): RecipeDetail {
        q.selectById(userId, id).executeAsOneOrNull() ?: throw NotFoundException("No recipe with id $id")
        q.updateSettings(settings.kind.name, Json.encodeToString(settings.mealSlots), userId, id)
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
    /** Cost per serving of one recipe with [prices]; null when it cannot be priced. */
    fun cost(recipeId: Long, prices: Pricing): RecipeCost? {
        val row = q.selectById(userId, recipeId).executeAsOneOrNull() ?: return null
        return costOf(row, linesOf(recipeId), ingredients.resolver(), prices)
    }

    private fun costOf(row: Recipe, lines: List<IngredientLine>, resolve: (IngredientLine) -> Ingredient?, prices: Pricing): RecipeCost? {
        val servings = row.servings?.toInt() ?: return null
        return prices.cost(lines.map { it to resolve(it) }, servings)
    }

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
            val existing = q.selectSynced(userId).executeAsList().associateBy { it.sourcePath }
            for ((recipe, hash) in parsed) {
                val row = existing[recipe.path]
                when {
                    row == null -> {
                        q.insertRecipe(
                            userId = userId,
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
                            userId = userId,
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
                    q.markRemoved(now.toString(), userId, row.id)
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

    private fun Recipe.toSummary(nutrition: RecipeNutrition?, cost: RecipeCost? = null) = RecipeSummary(
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
        rating = rating?.toInt(),
        cost = cost,
    )

    private fun Boolean.toLong() = if (this) 1L else 0L
}
