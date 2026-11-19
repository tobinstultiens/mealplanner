package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.pricing.Pricing
import dev.tobins.mealplanner.api.RecipeCost
import java.time.LocalDate
import dev.tobins.mealplanner.api.AddedInput
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.ManyAddedInput
import dev.tobins.mealplanner.api.ManyRotationInput
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
import dev.tobins.mealplanner.api.RecipeTopping
import dev.tobins.mealplanner.api.RotationInput
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
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val ROUTINES_KEY = "routines"

/** Section name given to recipes created in the app. */
private const val MANUAL_SECTION = "own"

/** Sections of the cooking site whose pages are building blocks, not meals. */
private val componentSections = setOf("sauces", "spices", "templates", "ideas", "fermenting", "baking", "cocktails")

/** A recipe's base yield and its lines, each with the ingredient it means (null when unknown). */
data class RecipeLines(val servings: Int, val lines: List<Pair<IngredientLine, Ingredient?>>)

/**
 * One account's recipes. Those from its cooking site start in its bank; the account adds the ones it
 * cooks to "my recipes", and the planner picks on its own only from my recipes in the rotation. Recipes
 * made in the app are always mine. See README, "Recipes: the bank, my recipes and the rotation".
 */
class RecipeRepository(
    private val db: Database,
    /** The catalogue the recipes' lines mean ingredients of; also what a planned meal's own lines name ([MealAdjustments]). */
    val ingredients: IngredientRepository,
    private val userId: Long,
    /** Prices with the offers valid between two dates. */
    private val pricing: (LocalDate, LocalDate) -> Pricing = { _, _ -> Pricing.NONE },
) {
    private val q = db.recipeQueries

    /** Prices with this week's offers: what a recipe costs if bought now. */
    private fun currentPricing(): Pricing {
        val today = serverToday()
        return pricing(today, today.plusDays(6))
    }

    /** My recipes: what a released app lists as the account's recipes, so it needs to know nothing of the bank. */
    fun list(): List<RecipeSummary> = summaries(mine())

    /**
     * The bank: the cooking-site recipes on the site that the account has not added. Their nutrition is
     * worked out from what the account has linked, which for texts only bank recipes use is often nothing
     * yet ([IngredientRepository.autoLink] runs when one is added), so it is often incomplete and says so.
     */
    fun bank(): List<RecipeSummary> = summaries(q.selectBank(userId).executeAsList())

    /**
     * My recipes' rows. SQLDelight gives `selectMine` a type of its own, with `addedAt` not null as its
     * WHERE says; read as [Recipe] rows, they go through the same code as every other recipe.
     */
    private fun mine(): List<Recipe> = q.selectMine(userId, ::Recipe).executeAsList()

    private fun summaries(rows: List<Recipe>): List<RecipeSummary> {
        val resolve = ingredients.resolver()
        val prices = currentPricing()
        return rows.map { row ->
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
            toppings = toppings(id),
        )
    }

    /** The account's optional toppings of recipe [id] ([RecipeToppings]), in their order. */
    fun toppings(id: Long): List<RecipeTopping> {
        val rows = q.selectToppings(userId, id).executeAsList()
        if (rows.isEmpty()) return emptyList()
        val catalogue = ingredients.list().associateBy { it.id }
        return rows.mapNotNull { row ->
            catalogue[row.ingredientId]?.let { RecipeTopping(it.id, it.name, row.grams, it.gramsPerPiece) }
        }
    }

    /**
     * One recipe as a plan candidate, even with incomplete nutrition (used when the user
     * puts a recipe in a slot themselves). Null when the recipe has no amounts at all.
     * Callers make sure it is one of my recipes first ([requireMine]).
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
            fibreG = nutrition.perServing.fibreG ?: 0.0,
        )
    }

    /**
     * Grams of fibre in a serving of each of [recipeIds] as the recipe's nutrition is now, by id; recipes
     * that are gone or have no amounts are left out. For the fibre of planned meals ([dev.tobins.mealplanner.api.PlannedMeal.fibreG]).
     */
    fun fibrePerServing(recipeIds: Collection<Long>): Map<Long, Double> {
        val resolve = ingredients.resolver()
        return recipeIds.distinct().mapNotNull { id ->
            val row = q.selectById(userId, id).executeAsOneOrNull() ?: return@mapNotNull null
            val nutrition = nutritionOf(row, linesOf(id), resolve) ?: return@mapNotNull null
            id to (nutrition.perServing.fibreG ?: 0.0)
        }.toMap()
    }

    /**
     * The recipes the planner may pick on its own: my recipes in the rotation that are meals with complete
     * nutrition. One of my recipes outside the rotation is planned only by hand ([candidate]).
     *
     * @param dislikes words for ingredients never to plan; a recipe with a line whose text
     *   or linked ingredient name contains one of them as a word (or its plural) is left out.
     * @param onHand the ingredients on hand in the week being planned ([StockRepository.onHandFor]).
     */
    fun planCandidates(
        onOffer: Set<Long> = emptySet(),
        dislikes: List<String> = emptyList(),
        prices: Pricing = Pricing.NONE,
        onHand: Set<Long> = emptySet(),
    ): List<PlanCandidate> {
        val resolve = ingredients.resolver()
        val disliked = dislikes.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .map { Regex("\\b${Regex.escape(it)}(s|es|en|n)?\\b", RegexOption.IGNORE_CASE) }
        return mine().mapNotNull { row ->
            if (row.kind != RecipeKind.MEAL.name || row.inRotation == 0L) return@mapNotNull null
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
                fibreG = nutrition.perServing.fibreG ?: 0.0,
                tags = Json.decodeFromString(row.tagsJson),
                onHandCount = lines.mapNotNull { resolve(it)?.id }.distinct().count { it in onHand },
                offerCount = lines.mapNotNull { resolve(it)?.id }.distinct().count { it in onOffer },
                costPerServing = costOf(row, lines, resolve, prices)?.perServingWithOffers,
                rating = row.rating?.toInt(),
            )
        }
    }

    /**
     * Each recipe's base yield and its lines with the ingredient each one means, by recipe id; recipes
     * that are gone are left out. One lookup of the catalogue serves them all.
     */
    fun linesWithIngredients(recipeIds: Collection<Long>): Map<Long, RecipeLines> {
        val resolve = ingredients.resolver()
        return recipeIds.distinct().mapNotNull { id ->
            val row = q.selectById(userId, id).executeAsOneOrNull() ?: return@mapNotNull null
            id to RecipeLines(row.servings?.toInt() ?: 1, linesOf(id).map { it to resolve(it) })
        }.toMap()
    }

    /** Creates a recipe of the user's own, or one generated for them ([source] GENERATED): one of mine, in the rotation. */
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
            addedAt = Instant.now().toString(),
            inRotation = 1,
        )
        val id = q.lastInsertId().executeAsOne()
        q.updateSettings(RecipeKind.MEAL.name, Json.encodeToString(input.mealSlots), userId, id)
        replaceIngredients(id, parsed.ingredients)
        find(id)!!
    }

    /** Replaces a recipe of the user's own; imported recipes are edited on the cooking site. */
    fun update(id: Long, input: RecipeInput): RecipeDetail = db.writing {
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
        val days = plannedDays(id)
        if (days.isNotEmpty()) {
            throw ConflictException("Planned on ${days.joinToString(", ", transform = ::dayName)}: choose another recipe there first")
        }
        q.deleteManual(userId, id)
    }

    /**
     * The days the recipe is planned in the current week or a later one. The plan keeps a meal
     * by its stored title, so deleting the recipe would leave it in the plan while the shopping
     * list stopped buying for it; weeks that are over are history and do not count.
     */
    private fun plannedDays(id: Long): List<LocalDate> =
        db.planQueries.selectPlannedFrom(userId, id, serverToday().minusDays(7).toString()).executeAsList()
            .map { LocalDate.parse(it.weekStart).plusDays(it.day) }
            .distinct()

    private fun dayName(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH))

    fun setRating(id: Long, rating: Int?): RecipeDetail {
        require(rating == null || rating in 1..5) { "rating must be 1 to 5, or null" }
        q.selectById(userId, id).executeAsOneOrNull() ?: throw NotFoundException("No recipe with id $id")
        q.setRating(rating?.toLong(), userId, id)
        return find(id)!!
    }

    /**
     * The account's recipe [id], when it is in the bank or one of mine. One whose file left the site
     * while it was only in the bank is as good as not there, as removed recipes were before the bank.
     */
    private fun known(id: Long): Recipe =
        q.selectById(userId, id).executeAsOneOrNull()?.takeIf { it.addedAt != null || it.removedAt == null }
            ?: throw NotFoundException("No recipe with id $id")

    /** The account's recipe [id] as one of mine ([known]); a [ConflictException] while it is only in the bank. */
    fun requireMine(id: Long): Recipe =
        known(id).also { if (it.addedAt == null) throw ConflictException("Add \"${it.title}\" to your recipes first") }

    /** Adds a bank recipe to my recipes, or puts it back in the bank ([changeAdded]). */
    fun setAdded(id: Long, input: AddedInput): RecipeDetail {
        changeAdded(listOf(id), input.added, input.inRotation)
        return find(id)!!
    }

    /** [setAdded] for many recipes at once, answering my recipes as they are then. */
    fun setAdded(input: ManyAddedInput): List<RecipeSummary> {
        changeAdded(input.ids, input.added, input.inRotation)
        return list()
    }

    /** Puts one of my recipes in the rotation or takes it out ([changeRotation]). */
    fun setRotation(id: Long, input: RotationInput): RecipeDetail {
        changeRotation(listOf(id), input.inRotation)
        return find(id)!!
    }

    /** [setRotation] for many recipes at once, answering my recipes as they are then. */
    fun setRotation(input: ManyRotationInput): List<RecipeSummary> {
        changeRotation(input.ids, input.inRotation)
        return list()
    }

    /**
     * Adds [ids] to my recipes, [inRotation] or not, or puts them back in the bank: all of them, or none
     * when one cannot be (not the account's: 404; [requireRemovable], [requireNotRoutine]: 409). Adding
     * one that is added keeps when it was added and sets its rotation; putting back one that is in the
     * bank changes nothing. What is added gets its exact-name ingredients linked, as after a sync; that
     * runs after the change, as it reads every NEVO food and the write lock is everyone's.
     */
    private fun changeAdded(ids: Collection<Long>, added: Boolean, inRotation: Boolean) {
        db.writing {
            val rows = ids.distinct().map(::known)
            if (added) {
                if (!inRotation) rows.forEach(::requireNotRoutine)
                val now = Instant.now().toString()
                rows.forEach { q.addToMine(addedAt = now, inRotation = inRotation.toLong(), userId = userId, id = it.id) }
            } else {
                val mine = rows.filter { it.addedAt != null }
                mine.forEach(::requireRemovable)
                mine.forEach { q.removeFromMine(userId, it.id) }
            }
        }
        if (added) ingredients.autoLink()
    }

    /** Puts [ids], all of them mine, in the rotation or takes them out; none when one cannot be ([requireNotRoutine]). */
    private fun changeRotation(ids: Collection<Long>, inRotation: Boolean) = db.writing {
        val rows = ids.distinct().map(::requireMine)
        if (!inRotation) rows.forEach(::requireNotRoutine)
        rows.forEach { q.setInRotation(inRotation.toLong(), userId, it.id) }
    }

    /**
     * Refuses to put [row] back in the bank while the plan or the routines use it: the shopping list would
     * go on buying for a recipe the account no longer has, as for [delete]. A recipe made in the app has no
     * bank to go back to: it is deleted ([delete]), which putting back never does on its own.
     */
    private fun requireRemovable(row: Recipe) {
        if (row.source != RecipeSource.COOKING_SITE.name) {
            throw ConflictException("\"${row.title}\" was made in the app, so it has no bank to go back to: delete it instead")
        }
        val days = plannedDays(row.id)
        if (days.isNotEmpty()) {
            throw ConflictException("\"${row.title}\" is planned on ${days.joinToString(", ", transform = ::dayName)}: choose another recipe there first")
        }
        requireNotRoutine(row)
    }

    /** The planner pins a routine only from the rotation, so a routine stays in it until another is chosen. */
    private fun requireNotRoutine(row: Recipe) {
        val routines = routines()
        val slot = when (row.id) {
            routines.breakfast -> "breakfast"
            routines.snack -> "snack"
            else -> return
        }
        throw ConflictException("\"${row.title}\" is your $slot routine: choose another routine first")
    }

    /** Titles of the most recently planned recipes, newest first; for variety in generation. */
    fun titles(ids: Collection<Long>): List<String> = ids.mapNotNull { q.selectById(userId, it).executeAsOneOrNull()?.title }

    /** The titles of my recipes, for Claude not to write one of them again. */
    fun allTitles(): List<String> = mine().map { it.title }

    fun routines(): Routines =
        db.userQueries.selectUserMeta(userId, ROUTINES_KEY).executeAsOneOrNull()?.let { Json.decodeFromString<Routines>(it) } ?: Routines()

    /**
     * Pins the breakfast and snack routines. A newly pinned recipe must be one of mine ([requireMine]) and
     * goes in the rotation, from which the planner pins it. One pinned already is kept as it is, so that a
     * released app saving the routines it read is not refused for a pin from before the bank.
     */
    fun saveRoutines(routines: Routines): Routines = db.writing {
        val pinned = routines()
        val ids = listOfNotNull(routines.breakfast, routines.snack)
        ids.forEach { q.selectById(userId, it).executeAsOneOrNull() ?: throw NotFoundException("No recipe with id $it") }
        val new = ids.filter { it != pinned.breakfast && it != pinned.snack }.map(::requireMine)
        new.forEach { q.setInRotation(1, userId, it.id) }
        db.userQueries.upsertUserMeta(userId, ROUTINES_KEY, Json.encodeToString(routines))
        routines
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
        // One problem per line, so that an app can show each unreadable ingredient line on its own.
        require(parsed.problems.isEmpty()) { parsed.problems.joinToString("\n") }
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

    /** Cost per serving of one recipe with [prices]; null when it cannot be priced. */
    fun cost(recipeId: Long, prices: Pricing): RecipeCost? {
        val row = q.selectById(userId, recipeId).executeAsOneOrNull() ?: return null
        return costOf(row, linesOf(recipeId), ingredients.resolver(), prices)
    }

    private fun costOf(row: Recipe, lines: List<IngredientLine>, resolve: (IngredientLine) -> Ingredient?, prices: Pricing): RecipeCost? {
        val servings = row.servings?.toInt() ?: return null
        return prices.cost(lines.map { it to resolve(it) }, servings)
    }

    /** Null when the recipe has no amounts to compute from. */
    private fun nutritionOf(row: Recipe, lines: List<IngredientLine>, resolve: (IngredientLine) -> Ingredient?): RecipeNutrition? {
        val servings = row.servings?.toInt() ?: return null
        if (lines.none { it.amount != null }) return null
        return NutritionCalculator.perServing(lines.map { it to resolve(it) }, servings)
    }

    /**
     * Makes the stored cooking-site recipes match [pages], the full current content
     * of the repo. Files are identified by path; unchanged hashes are skipped; recipes
     * whose file is gone are marked removed, and come back if the file reappears.
     * A new file's recipe goes to the bank. Whether a recipe is added and in the rotation
     * is the account's and never changed here: an added recipe whose file is gone stays one
     * of mine ([RecipeSummary.removedFromSite]), a bank one is hidden until it is back.
     * Pictures are left to [RecipePictures.sync], after this.
     */
    fun syncCookingSite(pages: List<SitePage>, now: Instant = Instant.now()): SyncResult {
        var added = 0
        var updated = 0
        var unchanged = 0
        var removed = 0
        db.writing {
            val existing = q.selectSynced(userId).executeAsList().associateBy { it.sourcePath }
            for ((recipe, hash) in pages) {
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
                            // New on the site: in the bank until the account adds it.
                            addedAt = null,
                            inRotation = 0,
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
            val present = pages.mapTo(HashSet()) { it.recipe.path }
            for (row in existing.values) {
                if (row.sourcePath !in present && row.removedAt == null) {
                    q.markRemoved(now.toString(), userId, row.id)
                    removed++
                }
            }
        }
        val problems = pages.map { it.recipe }.filter { it.problems.isNotEmpty() }.associate { it.path to it.problems }
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
        imageUrl = imageVersion?.let { "/api/recipes/$id/image?v=$it" },
        added = addedAt != null,
        inRotation = inRotation != 0L,
        removedFromSite = removedAt != null,
    )

    private fun Boolean.toLong() = if (this) 1L else 0L
}
