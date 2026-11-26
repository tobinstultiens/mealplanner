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
import dev.tobins.mealplanner.api.RecipeLineInput
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSettings
import dev.tobins.mealplanner.api.Routines
import dev.tobins.mealplanner.api.RecipeNutrition
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.RecipeTopping
import dev.tobins.mealplanner.api.RotationInput
import dev.tobins.mealplanner.api.StandardSwitch
import dev.tobins.mealplanner.api.SyncResult
import dev.tobins.mealplanner.nutrition.NutritionCalculator
import dev.tobins.mealplanner.planner.FreshUse
import dev.tobins.mealplanner.planner.Pack
import dev.tobins.mealplanner.planner.PlanCandidate
import dev.tobins.mealplanner.recipes.HugoRecipeParser
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.recipes.toIngredientLine
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
 * made in the app are always mine. A recipe added from the bank is taken: the account's own copy, edited
 * and deleted as one made in the app, which the sync no longer changes (issue 340). See README, "Recipes:
 * the bank, my recipes and the rotation".
 */
class RecipeRepository(
    private val db: Database,
    /** The catalogue the recipes' lines mean ingredients of; also what a planned meal's own lines name ([MealAdjustments]). */
    val ingredients: IngredientRepository,
    private val userId: Long,
    /** Today as the household sees it: this week's prices, and which planned weeks are over. */
    private val today: () -> LocalDate = ::serverToday,
    /** Prices with the offers valid between two dates. */
    private val pricing: (LocalDate, LocalDate) -> Pricing = { _, _ -> Pricing.NONE },
) {
    private val q = db.recipeQueries

    /** Prices with this week's offers: what a recipe costs if bought now. */
    private fun currentPricing(): Pricing {
        val day = today()
        return pricing(day, day.plusDays(6))
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
            ingredients = lines.map { mapped(it, resolve) },
            instructions = row.instructions,
            problems = Json.decodeFromString(row.problemsJson),
            toppings = toppings(id),
        )
    }

    /** [line] with the ingredient it means ([resolve]) and the weight that counts. */
    private fun mapped(line: IngredientLine, resolve: (IngredientLine) -> Ingredient?): MappedLine {
        val ingredient = resolve(line)
        return MappedLine(
            line = line,
            ingredientId = ingredient?.id,
            ingredientName = ingredient?.name,
            grams = ingredient?.takeUnless { it.negligible }?.let { NutritionCalculator.quantityOf(line, it)?.grams },
            negligible = ingredient?.negligible == true,
        )
    }

    /**
     * [lines] as a recipe saved with them would have them, without saving anything (`POST /api/recipes/lines`, issue 342):
     * each as stored, with the ingredient it means for the account now and its weight, as [find] gives them. The app's
     * editor looks pasted lines up with it, as only the server knows the account's links of texts. Refused as a save
     * would refuse the lines (400, and 404 for another account's ingredient).
     */
    fun readDraft(lines: List<RecipeLineInput>): List<MappedLine> {
        val read = readLines(lines).also(::requireOwnIngredients)
        val resolve = ingredients.resolver()
        return read.map { mapped(it, resolve) }
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
     * Callers make sure it is one of my recipes first ([requireMine]). With [freshPacks] (and the ingredients
     * [onHand]) it has its [PlanCandidate.freshUses] as [planCandidates] gives them: for the packs a week opened.
     */
    fun candidate(id: Long, freshPacks: Map<Long, Pack> = emptyMap(), onHand: Set<Long> = emptySet()): PlanCandidate? {
        val row = q.selectById(userId, id).executeAsOneOrNull() ?: return null
        val resolve = ingredients.resolver()
        val lines = linesOf(id)
        val nutrition = nutritionOf(row, lines, resolve) ?: return null
        return PlanCandidate(
            recipeId = row.id,
            title = row.title,
            slots = slotsOf(row),
            servings = row.servings!!.toInt(),
            kcal = nutrition.perServing.kcal ?: 0.0,
            proteinG = nutrition.perServing.proteinG ?: 0.0,
            fibreG = nutrition.perServing.fibreG ?: 0.0,
            freshUses = if (freshPacks.isEmpty()) emptyList() else freshUsesOf(row.servings!!.toInt(), lines, resolve, freshPacks, onHand),
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
     * @param freshPacks the packs of the fresh ingredients ([PriceRepository.freshPacks]); a recipe's
     *   [PlanCandidate.freshUses] are those of its ingredients that have one and are not on hand.
     * @param everyMeal every one of my recipes that is a meal with amounts, in the rotation or not, disliked or not and
     *   with complete nutrition or not: those the user may put in a plan by hand ([PlanRepository.choose]), as the planning
     *   screen's search finds them (issue 255).
     */
    fun planCandidates(
        onOffer: Set<Long> = emptySet(),
        dislikes: List<String> = emptyList(),
        prices: Pricing = Pricing.NONE,
        onHand: Set<Long> = emptySet(),
        freshPacks: Map<Long, Pack> = emptyMap(),
        everyMeal: Boolean = false,
    ): List<PlanCandidate> {
        val resolve = ingredients.resolver()
        val disliked = dislikes.takeUnless { everyMeal }.orEmpty().map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .map { Regex("\\b${Regex.escape(it)}(s|es|en|n)?\\b", RegexOption.IGNORE_CASE) }
        return mine().mapNotNull { row ->
            if (row.kind != RecipeKind.MEAL.name || (row.inRotation == 0L && !everyMeal)) return@mapNotNull null
            val lines = linesOf(row.id)
            if (disliked.isNotEmpty() && lines.any { line ->
                    val names = listOfNotNull(line.ingredientText, resolve(line)?.name)
                    disliked.any { word -> names.any(word::containsMatchIn) }
                }
            ) return@mapNotNull null
            val nutrition = nutritionOf(row, lines, resolve)?.takeIf { it.complete || everyMeal } ?: return@mapNotNull null
            val cost = costOf(row, lines, resolve, prices)
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
                costPerServing = cost?.perServingWithOffers,
                rating = row.rating?.toInt(),
                freshUses = if (freshPacks.isEmpty()) emptyList() else freshUsesOf(row.servings!!.toInt(), lines, resolve, freshPacks, onHand),
                regularCostPerServing = cost?.perServing,
            )
        }
    }

    /** What a serving of the recipe takes of each of its fresh ingredients that is not on hand, in grams. */
    private fun freshUsesOf(
        servings: Int,
        lines: List<IngredientLine>,
        resolve: (IngredientLine) -> Ingredient?,
        freshPacks: Map<Long, Pack>,
        onHand: Set<Long>,
    ): List<FreshUse> = lines.mapNotNull { line ->
        val ingredient = resolve(line)?.takeIf { it.id in freshPacks && it.id !in onHand && !it.negligible } ?: return@mapNotNull null
        NutritionCalculator.quantityOf(line, ingredient)?.grams?.let { ingredient.id to it }
    }.groupBy({ it.first }, { it.second }).map { (id, grams) -> FreshUse(id, grams.sum() / servings, freshPacks.getValue(id)) }

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
    fun create(input: RecipeInput, source: RecipeSource = RecipeSource.MANUAL): RecipeDetail = db.writing {
        require(source != RecipeSource.COOKING_SITE) { "cooking site recipes come from the sync" }
        val lines = linesFrom(input, before = emptyList())
        q.insertRecipe(
            userId = userId,
            source = source.name,
            sourcePath = null,
            contentHash = null,
            section = MANUAL_SECTION,
            title = input.title.trim(),
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
        replaceIngredients(id, lines)
        find(id)!!
    }

    /**
     * Replaces one of my recipes ([requireMine]): one made in the app, or one taken from the cooking site, which is the
     * account's own copy (issue 340). Its kind, rating, picture and the like stay; a bank recipe is the site's (409).
     * A save that changes nothing ([unchanged]) writes nothing, so a taken recipe opened in the editor and saved as it
     * was is not changed in the app and can still go back to the bank (issue 342).
     */
    fun update(id: Long, input: RecipeInput): RecipeDetail = db.writing {
        val row = requireMine(id)
        val before = linesOf(id)
        val lines = linesFrom(input, before)
        if (unchanged(row, before, input, lines)) return@writing find(id)!!
        q.updateMine(
            title = input.title.trim(),
            tagsJson = Json.encodeToString(input.tags),
            servings = input.servings.toLong(),
            instructions = input.instructions.trim(),
            editedAt = Instant.now().toString(),
            userId = userId,
            id = id,
        )
        q.updateSettings(row.kind, Json.encodeToString(input.mealSlots), userId, id)
        replaceIngredients(id, lines)
        find(id)!!
    }

    /**
     * Whether [input], read as [lines], is recipe [row] as it is, with its lines [before]: its title, servings,
     * instructions (both trimmed, as saved), tags, slots and every line with its group and own ingredient. A recipe
     * with no number of servings (a cooking-site file without one) is read as 1, which is what the editor shows and sends
     * for it: saved untouched it stays without, instead of gaining a 1 and counting as edited.
     */
    private fun unchanged(row: Recipe, before: List<IngredientLine>, input: RecipeInput, lines: List<IngredientLine>): Boolean =
        input.title.trim() == row.title && input.servings.toLong() == (row.servings ?: 1L) && input.instructions.trim() == row.instructions &&
            input.tags == Json.decodeFromString<List<String>>(row.tagsJson) && input.mealSlots.toSet() == slotsOf(row) && lines == before

    /**
     * Deletes one of my recipes ([requireMine]), also one taken from the cooking site: the site's file is then in the bank
     * again after the next sync, as a file the account never took. A bank recipe is the site's and is not deleted (409).
     * The route deletes its picture too ([RecipePictures.forget]).
     */
    fun delete(id: Long): Unit = db.writing {
        requireMine(id)
        val days = plannedDays(id)
        if (days.isNotEmpty()) {
            throw ConflictException("Planned on ${days.joinToString(", ", transform = ::dayName)}: choose another recipe there first")
        }
        q.deleteMine(userId, id)
    }

    /**
     * The days the recipe is planned in the current week or a later one, as a meal or as a side next to one
     * (issue 234). The plan keeps a meal by its stored title, so deleting the recipe would leave it in the
     * plan while the shopping list stopped buying for it; weeks that are over are history and do not count.
     */
    private fun plannedDays(id: Long): List<LocalDate> {
        val after = today().minusDays(7).toString()
        val meals = db.planQueries.selectPlannedFrom(userId, id, after).executeAsList().map { it.weekStart to it.day }
        val sides = db.planQueries.selectSidesPlannedFrom(userId, id, after).executeAsList().map { it.weekStart to it.day }
        return (meals + sides).map { (week, day) -> LocalDate.parse(week).plusDays(day) }.distinct().sorted()
    }

    private fun dayName(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH))

    fun setRating(id: Long, rating: Int?): RecipeDetail {
        require(rating == null || rating in 1..5) { "rating must be 1 to 5, or null" }
        db.writing {
            ownRecipe(id)
            q.setRating(rating?.toLong(), userId, id)
        }
        return find(id)!!
    }

    /**
     * The account's recipe [id], when it is in the bank or one of mine. One whose file left the site
     * while it was only in the bank is as good as not there, as removed recipes were before the bank; so are the foods of
     * a slot of the plan (issue 358), which are no recipe to the person: they are not added, chosen, prepped or deleted.
     */
    private fun known(id: Long): Recipe =
        ownRecipe(id).takeIf { it.addedAt != null || it.removedAt == null } ?: throw NotFoundException("No recipe with id $id")

    /** The account's recipe [id], from any source, but not the foods of a slot of the plan (issue 358), which only the plan changes. */
    private fun ownRecipe(id: Long): Recipe =
        q.selectById(userId, id).executeAsOneOrNull()?.takeIf { it.slotFoods == 0L } ?: throw NotFoundException("No recipe with id $id")

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
     * bank to go back to: it is deleted ([delete]), which putting back never does on its own. Nor has a
     * taken one saved in the app since (issue 340): in the bank the next sync would make it the site's file
     * again, and what was changed would be lost without a word.
     */
    private fun requireRemovable(row: Recipe) {
        if (row.source != RecipeSource.COOKING_SITE.name) {
            throw ConflictException("\"${row.title}\" was made in the app, so it has no bank to go back to: delete it instead")
        }
        if (row.editedAt != null) {
            throw ConflictException("\"${row.title}\" was changed in the app, so it is no longer the cooking site's: delete it instead")
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

    /**
     * Sets how many days the recipe keeps once made, in a normal box and vacuum sealed (issue 234): what a prep
     * of it may span. Per account, as the recipe is; any recipe of the account's, also one only in the bank.
     */
    fun setKeeps(id: Long, keeps: RecipeKeeps): RecipeDetail {
        for (days in listOfNotNull(keeps.days, keeps.vacuumDays)) {
            require(days in 1..RecipeKeeps.MAX_DAYS) { "keeps must be 1 to ${RecipeKeeps.MAX_DAYS} days, or null" }
        }
        db.writing {
            ownRecipe(id)
            q.setKeeps(keeps.days?.toLong(), keeps.vacuumDays?.toLong(), userId, id)
        }
        return find(id)!!
    }

    /**
     * Makes one of my meals a standard meal (issue 265), or no longer one. Marking makes the ingredients it is made of
     * staples ([standardIngredients]), as what is always at home, and says which were not yet; unmarking leaves the
     * staples as they are, as other meals may need them. Its own route, so that released apps saving [RecipeSettings]
     * leave it alone. Only a meal of mine with amounts is one: a bank recipe ([requireMine]), a building block and a
     * recipe with nothing to size a portion by are a [ConflictException]; another account's is not there (404).
     */
    fun setStandard(id: Long, standard: Boolean): StandardSwitch {
        val (added, unlinked) = db.writing {
            val row = requireMine(id)
            if (!standard) {
                q.setStandardMeal(0, userId, id)
                return@writing emptyList<String>() to emptyList()
            }
            if (row.kind == RecipeKind.COMPONENT.name) throw ConflictException("A building block is no standard meal: make it a meal first")
            candidate(id) ?: throw ConflictException("This recipe has no amounts to plan with")
            q.setStandardMeal(1, userId, id)
            val (made, unlinked) = standardIngredients(id)
            val added = made.filterNot { it.isStaple }
            for (ingredient in added) ingredients.setStaple(ingredient.id, true)
            added.map { it.name } to unlinked
        }
        return StandardSwitch(find(id)!!, added, unlinked)
    }

    /**
     * What a standard meal [id] is made of, as the pantry's staples go (issue 265): the ingredients its lines are linked
     * to, each once, in the recipe's order, but not those that count for nothing (spices, water), which the shopping
     * list never buys; and the texts of its lines linked to none yet, but not those used to taste.
     */
    internal fun standardIngredients(id: Long): Pair<List<Ingredient>, List<String>> {
        val resolve = ingredients.resolver()
        val linked = ArrayList<Ingredient>()
        val unlinked = LinkedHashSet<String>()
        for (line in linesOf(id)) {
            val ingredient = resolve(line)
            when {
                ingredient != null -> if (!ingredient.negligible) linked += ingredient
                line.amount != null || !NutritionCalculator.isSeasoning(line.ingredientText, null) -> unlinked += IngredientLine.coreText(line.ingredientText)
            }
        }
        return linked.distinctBy { it.id } to unlinked.toList()
    }

    /** My standard meals (issue 265) as plan candidates: the meals marked so that have amounts to size a portion by, by title. */
    fun standardMeals(): List<PlanCandidate> =
        mine().filter { it.standardMeal != 0L && it.kind == RecipeKind.MEAL.name }.mapNotNull { candidate(it.id) }

    fun updateSettings(id: Long, settings: RecipeSettings): RecipeDetail {
        db.writing {
            ownRecipe(id)
            q.updateSettings(settings.kind.name, Json.encodeToString(settings.mealSlots), userId, id)
        }
        return find(id)!!
    }

    /**
     * The lines [input] saves, checked: its [RecipeInput.lines] when it has them, else those of its text, which keep the
     * ingredients of the recipe's lines [before] ([keepingIngredients]). An ingredient that is not the account's is a
     * [NotFoundException], as for toppings.
     */
    private fun linesFrom(input: RecipeInput, before: List<IngredientLine>): List<IngredientLine> {
        require(input.title.isNotBlank()) { "title must not be blank" }
        require(input.servings in 1..100) { "servings must be between 1 and 100" }
        val lines = input.lines?.let(::readLines) ?: keepingIngredients(readText(input.ingredients), before)
        require(lines.isNotEmpty()) { "at least one ingredient line is required" }
        requireOwnIngredients(lines)
        return lines
    }

    /** Refuses [lines] with an ingredient that is not the account's, as for toppings: as one that does not exist (404). */
    private fun requireOwnIngredients(lines: List<IngredientLine>) {
        for (id in lines.mapNotNullTo(LinkedHashSet()) { it.ingredientId }) {
            ingredients.find(id) ?: throw NotFoundException("No ingredient with id $id")
        }
    }

    /** The lines of the import format [text], read as the cooking site's are; a line that cannot be read is refused. */
    private fun readText(text: String): List<IngredientLine> {
        val parsed = HugoRecipeParser().parse("$MANUAL_SECTION/x.md", "```ingredients\n$text\n```")
        // One problem per line, so that an app can show each unreadable ingredient line on its own.
        require(parsed.problems.isEmpty()) { parsed.problems.joinToString("\n") }
        return parsed.ingredients
    }

    /**
     * [lines] as the same lines of the import format read, each with its ingredient ([toIngredientLine], which the app's
     * editor reads them with too), a line break in a text, unit or group as a space. A unit with a `|` is refused: the
     * import format, in which the installed app edits a recipe, would read it as part of the text (issue 342).
     */
    private fun readLines(lines: List<RecipeLineInput>): List<IngredientLine> {
        val parser = HugoRecipeParser()
        return lines.mapIndexed { index, line ->
            val amount = line.amount
            require(amount == null || (amount.isFinite() && amount > 0)) { "Line ${index + 1}: the amount must be above 0, or none" }
            require(line.unit?.contains('|') != true) { "Line ${index + 1}: a unit cannot hold |" }
            line.toIngredientLine(parser) ?: throw IllegalArgumentException("Line ${index + 1}: a line needs an amount, a unit or a text")
        }
    }

    /**
     * [lines] read from text, each with the ingredient of the first line [before] with the same text that had one. So a
     * recipe saved as text, as released apps save it, keeps the ingredients its lines were saved with where their text
     * stayed as it was (amount and unit may change); a line whose text changed is looked up by its new text.
     */
    private fun keepingIngredients(lines: List<IngredientLine>, before: List<IngredientLine>): List<IngredientLine> {
        val kept = HashMap<String, Long>()
        for (line in before) line.ingredientId?.let { kept.putIfAbsent(IngredientRepository.normalise(line.ingredientText), it) }
        return lines.map { it.copy(ingredientId = kept[IngredientRepository.normalise(it.ingredientText)]) }
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
            ingredientId = it.ingredientId,
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
                    row.addedAt != null || (row.contentHash == hash && row.removedAt == null) -> unchanged++
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
                if (row.sourcePath !in present && row.removedAt == null && row.addedAt == null) {
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
            q.insertIngredient(recipeId, index.toLong(), line.amount, line.unit, line.dimension?.name, line.baseAmount, line.item, line.group, line.ingredientId)
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
        keepsDays = keepsDays?.toInt(),
        keepsVacuumDays = keepsVacuumDays?.toInt(),
        standardMeal = standardMeal != 0L,
        edited = editedAt != null,
    )

    private fun Boolean.toLong() = if (this) 1L else 0L
}
