package dev.tobins.mealplanner.server.recipes

import dev.tobins.mealplanner.api.GenerateRequest
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.ConflictException
import dev.tobins.mealplanner.server.accounts.ProfileRepository
import dev.tobins.mealplanner.server.ingredients.IngredientRepository
import dev.tobins.mealplanner.server.ingredients.StockRepository
import dev.tobins.mealplanner.server.plan.WeekMeals
import dev.tobins.mealplanner.server.prices.OfferRepository
import dev.tobins.mealplanner.server.recipes.generation.GeneratedRecipe
import dev.tobins.mealplanner.server.recipes.generation.GenerationContext
import dev.tobins.mealplanner.server.recipes.generation.RecipeGenerator
import dev.tobins.mealplanner.server.serverToday
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/** Gathers what a generator should know, runs it, and stores the result as a GENERATED recipe. */
class GenerationService(
    private val generator: RecipeGenerator,
    private val edits: RecipeEdits,
    private val view: RecipeView,
    private val mine: MyRecipes,
    private val ingredients: IngredientRepository,
    private val stock: StockRepository,
    private val profiles: ProfileRepository,
    private val weekMeals: WeekMeals,
    private val offers: OfferRepository,
    /** The stores the account shops at: recipes are written around their offers only. */
    private val shops: () -> Set<Store> = { Store.entries.toSet() },
) {
    suspend fun generate(request: GenerateRequest, today: LocalDate = serverToday()): RecipeDetail {
        val context = context(request, today)
        val generated = generator.generate(context)
        val detail = edits.create(toInput(generated, request.slot), RecipeSource.GENERATED)
        // Exact food-name matches are linked now, so the nutrition is as complete as it can be.
        if (ingredients.autoLink() > 0) return view.find(detail.summary.id)!!
        return detail
    }

    fun context(request: GenerateRequest, today: LocalDate): GenerationContext {
        val targets = profiles.state().targets
            ?: throw ConflictException("Set up your profile first: a recipe is sized to your daily targets")
        val pantry = stock.pantry()
        // What was bought ahead for a later week is at home, but meant for that week's meals.
        val onHandNow = stock.onHandFor(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
        val names = ingredients.list().associate { it.id to it.name }
        val inUse = shops()
        val onOffer = offers.byIngredient(today, today.plusDays(6), ingredients.matchTerms())
            .filterValues { offers -> offers.any { it.store in inUse } }.keys.mapNotNull(names::get)
        return GenerationContext(
            mode = request.mode,
            slot = request.slot,
            wish = request.wish.take(500),
            kcalPerServing = (targets.kcal * request.slot.share).roundToInt(),
            proteinPerServing = (targets.proteinG * request.slot.share).roundToInt(),
            servings = servingsFor(request.slot),
            staples = pantry.staples.map { it.name },
            onHand = pantry.onHand.filter { it.id in onHandNow }.map { it.name },
            onOffer = onOffer.sorted(),
            recent = weekMeals.recentTitles(today),
            existing = mine.allTitles(),
            likes = profiles.profile()?.likes.orEmpty(),
            dislikes = profiles.profile()?.dislikes.orEmpty(),
        )
    }

    private fun toInput(recipe: GeneratedRecipe, slot: MealSlot) = RecipeInput(
        title = recipe.title.trim(),
        servings = recipe.servings,
        ingredients = recipe.ingredients.joinToString("\n") { it.asLine() },
        instructions = buildString {
            if (recipe.description.isNotBlank()) appendLine(recipe.description.trim()).appendLine()
            recipe.steps.forEachIndexed { index, step -> appendLine("${index + 1}. ${step.trim()}") }
        }.trim(),
        tags = recipe.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(5),
        // A dinner that makes several servings also feeds lunches.
        mealSlots = if (slot == MealSlot.DINNER) listOf(MealSlot.LUNCH, MealSlot.DINNER) else listOf(slot),
    )

    companion object {
        /** Dinners make extra for the next day's lunch; the rest is made fresh. */
        fun servingsFor(slot: MealSlot): Int = when (slot) {
            MealSlot.DINNER -> 4
            MealSlot.LUNCH -> 2
            MealSlot.BREAKFAST, MealSlot.SNACK -> 1
        }
    }
}
