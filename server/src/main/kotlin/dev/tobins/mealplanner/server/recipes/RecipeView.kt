package dev.tobins.mealplanner.server.recipes

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.MappedLine
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.RecipeCost
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeNutrition
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.RecipeTopping
import dev.tobins.mealplanner.nutrition.NutritionCalculator
import dev.tobins.mealplanner.pricing.Pricing
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.server.db.Recipe
import dev.tobins.mealplanner.server.ingredients.IngredientRepository
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * The recipes as the app shows them: my recipes, the bank, and one recipe with its lines, toppings, nutrition and what
 * it costs this week. Every change of a recipe answers with what this gives.
 */
class RecipeView(
    private val store: RecipeStore,
    private val ingredients: IngredientRepository,
    /** Today as the household sees it: this week's prices. */
    private val today: () -> LocalDate,
    /** Prices with the offers valid between two dates. */
    private val pricing: (LocalDate, LocalDate) -> Pricing,
) {
    /** Prices with this week's offers: what a recipe costs if bought now. */
    private fun currentPricing(): Pricing {
        val day = today()
        return pricing(day, day.plusDays(6))
    }

    /** My recipes: what a released app lists as the account's recipes, so it needs to know nothing of the bank. */
    fun list(): List<RecipeSummary> = summaries(store.mine())

    /**
     * The bank: the cooking-site recipes on the site that the account has not added. Their nutrition is
     * worked out from what the account has linked, which for texts only bank recipes use is often nothing
     * yet ([IngredientRepository.autoLink] runs when one is added), so it is often incomplete and says so.
     */
    fun bank(): List<RecipeSummary> = summaries(store.bank())

    private fun summaries(rows: List<Recipe>): List<RecipeSummary> {
        val resolve = ingredients.resolver()
        val prices = currentPricing()
        return rows.map { row ->
            val lines = store.linesOf(row.id)
            row.toSummary(nutritionOf(row, lines, resolve), costOf(row, lines, resolve, prices))
        }
    }

    fun find(id: Long): RecipeDetail? {
        val row = store.byId(id) ?: return null
        val resolve = ingredients.resolver()
        val lines = store.linesOf(id)
        return RecipeDetail(
            summary = row.toSummary(nutritionOf(row, lines, resolve), costOf(row, lines, resolve, currentPricing())),
            ingredients = lines.map { mapped(it, resolve) },
            instructions = row.instructions,
            problems = Json.decodeFromString(row.problemsJson),
            toppings = toppings(id),
        )
    }

    /** [line] with the ingredient it means ([resolve]) and the weight that counts. */
    internal fun mapped(line: IngredientLine, resolve: (IngredientLine) -> Ingredient?): MappedLine {
        val ingredient = resolve(line)
        return MappedLine(
            line = line,
            ingredientId = ingredient?.id,
            ingredientName = ingredient?.name,
            grams = ingredient?.takeUnless { it.negligible }?.let { NutritionCalculator.quantityOf(line, it)?.grams },
            negligible = ingredient?.negligible == true,
        )
    }

    /** The account's optional toppings of recipe [id] ([RecipeToppings]), in their order. */
    fun toppings(id: Long): List<RecipeTopping> {
        val rows = store.toppings(id)
        if (rows.isEmpty()) return emptyList()
        val catalogue = ingredients.list().associateBy { it.id }
        return rows.mapNotNull { row ->
            catalogue[row.ingredientId]?.let { RecipeTopping(it.id, it.name, row.grams, it.gramsPerPiece) }
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
}

/** The slots recipe [row] suits: those the account chose, else those of its section of the cooking site. */
internal fun slotsOf(row: Recipe): Set<MealSlot> =
    row.mealSlotsJson?.let { Json.decodeFromString<List<MealSlot>>(it).toSet() } ?: slotsFor(row.section)

private fun slotsFor(section: String): Set<MealSlot> = when (section) {
    "deserts" -> setOf(MealSlot.SNACK)
    "sandwiches" -> setOf(MealSlot.LUNCH)
    "salads", "soup" -> setOf(MealSlot.LUNCH, MealSlot.DINNER)
    else -> setOf(MealSlot.DINNER)
}

/** Cost per serving of recipe [row] with its [lines] and [prices]; null without a number of servings. */
internal fun costOf(row: Recipe, lines: List<IngredientLine>, resolve: (IngredientLine) -> Ingredient?, prices: Pricing): RecipeCost? {
    val servings = row.servings?.toInt() ?: return null
    return prices.cost(lines.map { it to resolve(it) }, servings)
}

/** Null when the recipe has no amounts to compute from. */
internal fun nutritionOf(row: Recipe, lines: List<IngredientLine>, resolve: (IngredientLine) -> Ingredient?): RecipeNutrition? {
    val servings = row.servings?.toInt() ?: return null
    if (lines.none { it.amount != null }) return null
    return NutritionCalculator.perServing(lines.map { it to resolve(it) }, servings)
}
