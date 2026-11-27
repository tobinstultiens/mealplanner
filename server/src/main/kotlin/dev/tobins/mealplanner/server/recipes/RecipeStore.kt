package dev.tobins.mealplanner.server.recipes

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.Routines
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.db.Recipe
import dev.tobins.mealplanner.server.db.SelectSynced
import dev.tobins.mealplanner.server.db.SelectToppings
import dev.tobins.mealplanner.server.writing
import dev.tobins.mealplanner.units.Dimension
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

private const val ROUTINES_KEY = "routines"

/**
 * What the recipes' rules read and write in the database for one account: the recipes and their lines (`Recipe.sq`;
 * writing toppings is [RecipeToppings]' and the pictures are [RecipePictures]'), the routines among the account's
 * settings, and where a recipe is planned (`Plan.sq`). Rows in, values out, and no rule of what may change. It is built for the account [userId], so a
 * caller never names one, and every query it runs is of that account only; a recipe's lines are read and written by
 * the recipe's id, which callers read with the account's id first. The rules ([RecipeView], [MyRecipes],
 * [RecipeEdits], ...) call it and no query.
 */
class RecipeStore(private val db: Database, private val userId: Long) {
    private val q = db.recipeQueries

    /**
     * Runs [body] in a transaction that holds the write lock ([writing]); inside another one on this thread it joins
     * that one, so a rule that calls another rule's change still makes one transaction of the two.
     */
    fun <T> writing(body: () -> T): T = db.writing(body)

    /** The account's recipe [id], of any source and also the foods of a slot of the plan; null when it has none such. */
    fun byId(id: Long): Recipe? = q.selectById(userId, id).executeAsOneOrNull()

    /**
     * My recipes' rows. SQLDelight gives `selectMine` a type of its own, with `addedAt` not null as its
     * WHERE says; read as [Recipe] rows, they go through the same code as every other recipe.
     */
    fun mine(): List<Recipe> = q.selectMine(userId, ::Recipe).executeAsList()

    /** The bank's rows: the cooking-site recipes still on the site that the account has not added, by title. */
    fun bank(): List<Recipe> = q.selectBank(userId).executeAsList()

    /** The lines of recipe [recipeId], in their order. */
    fun linesOf(recipeId: Long): List<IngredientLine> = q.selectIngredients(recipeId).executeAsList().map {
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

    /** Recipe [recipeId]'s lines become [lines], in their order, in place of those it had. */
    fun replaceLines(recipeId: Long, lines: List<IngredientLine>) {
        q.deleteIngredients(recipeId)
        lines.forEachIndexed { index, line ->
            q.insertIngredient(recipeId, index.toLong(), line.amount, line.unit, line.dimension?.name, line.baseAmount, line.item, line.group, line.ingredientId)
        }
    }

    /** The toppings of recipe [recipeId] ([RecipeToppings]), in their order. */
    fun toppings(recipeId: Long): List<SelectToppings> = q.selectToppings(userId, recipeId).executeAsList()

    /** Writes a new recipe with these values, without its lines or slots, and gives its id. */
    fun insert(
        source: RecipeSource,
        sourcePath: String?,
        contentHash: String?,
        section: String,
        title: String,
        tags: List<String>,
        servings: Int?,
        instructions: String,
        hasIngredientsBlock: Boolean,
        problems: List<String>,
        kind: RecipeKind,
        addedAt: Instant?,
        inRotation: Boolean,
    ): Long {
        q.insertRecipe(
            userId = userId,
            source = source.name,
            sourcePath = sourcePath,
            contentHash = contentHash,
            section = section,
            title = title,
            tagsJson = Json.encodeToString(tags),
            servings = servings?.toLong(),
            instructions = instructions,
            hasIngredientsBlock = hasIngredientsBlock.toLong(),
            problemsJson = Json.encodeToString(problems),
            kind = kind.name,
            addedAt = addedAt?.toString(),
            inRotation = inRotation.toLong(),
        )
        return q.lastInsertId().executeAsOne()
    }

    /** One of my recipes [id] gets these values, as saved in the app at [editedAt]; one only in the bank is left as it is. */
    fun updateMine(id: Long, title: String, tags: List<String>, servings: Int, instructions: String, editedAt: Instant) {
        q.updateMine(
            title = title,
            tagsJson = Json.encodeToString(tags),
            servings = servings.toLong(),
            instructions = instructions,
            editedAt = editedAt.toString(),
            userId = userId,
            id = id,
        )
    }

    /** Recipe [id]'s kind and slots; a recipe that is no meal is no standard meal either. */
    fun setSettings(id: Long, kind: RecipeKind, mealSlots: List<MealSlot>) =
        q.updateSettings(kind.name, Json.encodeToString(mealSlots), userId, id)

    /** Deletes one of my recipes [id] with its lines; one only in the bank stays. */
    fun deleteMine(id: Long) = q.deleteMine(userId, id)

    fun setRating(id: Long, rating: Int?) = q.setRating(rating?.toLong(), userId, id)

    fun setKeeps(id: Long, days: Int?, vacuumDays: Int?) = q.setKeeps(days?.toLong(), vacuumDays?.toLong(), userId, id)

    fun setStandardMeal(id: Long, standard: Boolean) = q.setStandardMeal(standard.toLong(), userId, id)

    /** Recipe [id] is one of mine, [inRotation] or not; one added already keeps when it was added. */
    fun addToMine(id: Long, addedAt: Instant, inRotation: Boolean) =
        q.addToMine(addedAt = addedAt.toString(), inRotation = inRotation.toLong(), userId = userId, id = id)

    /** Recipe [id] is back in the bank: out of the rotation and no standard meal. */
    fun removeFromMine(id: Long) = q.removeFromMine(userId, id)

    fun setInRotation(id: Long, inRotation: Boolean) = q.setInRotation(inRotation.toLong(), userId, id)

    /** The days recipe [id] is planned in weeks that started after [after], as a meal or as a side next to one, in no order. */
    fun plannedDays(id: Long, after: LocalDate): List<LocalDate> {
        val meals = db.planQueries.selectPlannedFrom(userId, id, after.toString()).executeAsList().map { it.weekStart to it.day }
        val sides = db.planQueries.selectSidesPlannedFrom(userId, id, after.toString()).executeAsList().map { it.weekStart to it.day }
        return (meals + sides).map { (week, day) -> LocalDate.parse(week).plusDays(day) }
    }

    fun routines(): Routines =
        db.userQueries.selectUserMeta(userId, ROUTINES_KEY).executeAsOneOrNull()?.let { Json.decodeFromString<Routines>(it) } ?: Routines()

    fun saveRoutines(routines: Routines) = db.userQueries.upsertUserMeta(userId, ROUTINES_KEY, Json.encodeToString(routines))

    /** Every cooking-site recipe of the account, in the bank, one of mine or removed from the site. */
    fun synced(): List<SelectSynced> = q.selectSynced(userId).executeAsList()

    /** Cooking-site recipe [id] gets the values of its file as it is now, and is on the site again. */
    fun updateSynced(
        id: Long,
        contentHash: String,
        section: String,
        title: String,
        tags: List<String>,
        servings: Int?,
        instructions: String,
        hasIngredientsBlock: Boolean,
        problems: List<String>,
    ) {
        q.updateSynced(
            contentHash = contentHash,
            section = section,
            title = title,
            tagsJson = Json.encodeToString(tags),
            servings = servings?.toLong(),
            instructions = instructions,
            hasIngredientsBlock = hasIngredientsBlock.toLong(),
            problemsJson = Json.encodeToString(problems),
            userId = userId,
            id = id,
        )
    }

    /** Cooking-site recipe [id]'s file left the site at [at]: it is hidden, and its picture forgotten. */
    fun markRemoved(id: Long, at: Instant) = q.markRemoved(at.toString(), userId, id)

    private fun Boolean.toLong() = if (this) 1L else 0L
}
