package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlanDay
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.nutrition.NutritionCalculator
import dev.tobins.mealplanner.planner.PlanRequest
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.units.Dimension
import dev.tobins.mealplanner.planner.WeekPlanner
import dev.tobins.mealplanner.server.db.Database
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.format.DateTimeParseException
import kotlin.random.Random

/** Thrown when a request cannot be carried out in the current state; mapped to 409. */
class ConflictException(message: String) : RuntimeException(message)

/** Week plans: generating them with [WeekPlanner] and storing the result. */
class PlanRepository(
    private val db: Database,
    private val recipes: RecipeRepository,
    private val profiles: ProfileRepository,
    /** Offers valid between two dates, grouped by ingredient id. */
    private val offersByIngredient: (LocalDate, LocalDate) -> Map<Long, List<dev.tobins.mealplanner.api.Offer>> = { _, _ -> emptyMap() },
) {
    private val q = db.planQueries

    fun get(weekStart: String): WeekPlan = view(parse(weekStart))

    /** Replaces every unlocked meal of the week with a newly planned one. */
    fun generate(weekStart: String, seed: Long = Random.nextLong()): WeekPlan {
        val start = parse(weekStart)
        // Meals already eaten or skipped are history: they stay, like locked ones.
        val kept = stored(start).filter { it.locked || it.status != MealStatus.PLANNED }
        val planned = WeekPlanner.plan(request(start, locked = kept, seed = seed))
        db.transaction {
            q.deleteUnlocked(start.toString())
            planned.filter { new -> kept.none { it.day == new.day && it.slot == new.slot } }.forEach { save(start, it.copy(locked = false)) }
        }
        return view(start)
    }

    /**
     * Picks a different recipe for one meal and keeps the rest of the week. When a dinner
     * changes, the next day's lunch made of its leftovers changes with it.
     */
    fun reroll(weekStart: String, day: Int, slot: MealSlot, seed: Long = Random.nextLong()): WeekPlan {
        val start = parse(weekStart)
        val current = stored(start)
        val target = current.firstOrNull { it.day == day && it.slot == slot }
            ?: throw NotFoundException("No meal planned for day $day $slot")
        val dependentLunch = current.firstOrNull {
            slot == MealSlot.DINNER && it.day == day + 1 && it.slot == MealSlot.LUNCH && it.leftover && !it.locked
        }
        val replaced = listOfNotNull(target, dependentLunch)
        val planned = WeekPlanner.plan(
            request(start, locked = current - replaced.toSet(), seed = seed).copy(excluded = setOf(target.recipeId)),
        )
        db.transaction {
            for (old in replaced) {
                q.deleteMeal(start.toString(), old.day.toLong(), old.slot.name)
                planned.firstOrNull { it.day == old.day && it.slot == old.slot }
                    ?.let { save(start, it.copy(locked = false, status = MealStatus.PLANNED)) }
            }
        }
        return view(start)
    }

    fun setLocked(weekStart: String, day: Int, slot: MealSlot, locked: Boolean): WeekPlan {
        val start = parse(weekStart)
        q.setLocked(if (locked) 1 else 0, start.toString(), day.toLong(), slot.name)
        return view(start)
    }

    fun setStatus(weekStart: String, day: Int, slot: MealSlot, status: MealStatus): WeekPlan {
        val start = parse(weekStart)
        q.setStatus(status.name, start.toString(), day.toLong(), slot.name)
        return view(start)
    }

    /** The meals planned for one date; the plan week is the one starting on that date's Monday. */
    fun mealsOn(date: LocalDate): Triple<LocalDate, Int, List<PlannedMeal>> {
        val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val index = (date.toEpochDay() - start.toEpochDay()).toInt()
        return Triple(start, index, stored(start).filter { it.day == index }.sortedBy { it.slot.ordinal })
    }

    fun remove(weekStart: String, day: Int, slot: MealSlot): WeekPlan {
        val start = parse(weekStart)
        q.deleteMeal(start.toString(), day.toLong(), slot.name)
        return view(start)
    }

    /**
     * What the week's meals need. Each recipe is scaled to the portions planned for it,
     * leftovers included; staples are left out and only named as a reminder.
     */
    fun shoppingList(weekStart: String): ShoppingList {
        val start = parse(weekStart)
        class Need(val name: String, val category: String, val onHand: Boolean) {
            var grams = 0.0
            var gramsKnown = true
            var pieces = 0.0
            val recipes = linkedSetOf<String>()
        }
        val onHand = recipes.onHandIds()
        val needs = linkedMapOf<String, Need>()
        val staples = sortedSetOf<String>()

        val portionsByRecipe = stored(start).groupBy { it.recipeId }.mapValues { (_, meals) -> meals.sumOf { it.portions } to meals.first().title }
        for ((recipeId, planned) in portionsByRecipe) {
            val (portions, title) = planned
            val (servings, lines) = recipes.linesWithIngredients(recipeId) ?: continue
            val factor = portions / servings
            for ((line, ingredient) in lines) {
                if (ingredient?.isStaple == true) {
                    staples += ingredient.name
                    continue
                }
                val key = ingredient?.let { "i${it.id}" } ?: "t:${IngredientRepository.normalise(IngredientLine.coreText(line.ingredientText))}"
                val need = needs.getOrPut(key) {
                    Need(
                        name = ingredient?.name ?: IngredientLine.coreText(line.ingredientText).replaceFirstChar { it.uppercase() },
                        category = ingredient?.food?.groupEn ?: "Other",
                        onHand = ingredient?.id in onHand,
                    )
                }
                need.recipes += title
                val amount = line.amount ?: continue // "salt to taste": needed, but no quantity
                val grams = ingredient?.let { NutritionCalculator.quantityOf(line, it)?.grams }
                    ?: line.baseAmount.takeIf { line.dimension == Dimension.WEIGHT }
                if (grams != null) need.grams += grams * factor else need.gramsKnown = false
                if (line.dimension == Dimension.COUNT) need.pieces += amount * factor
            }
        }
        val checked = q.selectChecks(start.toString()).executeAsList().toSet()
        val offers = offersByIngredient(start, start.plusDays(DAYS - 1L))
        return ShoppingList(
            weekStart = start.toString(),
            items = needs.map { (key, need) ->
                ShoppingItem(
                    key = key,
                    name = need.name,
                    grams = need.grams.takeIf { need.gramsKnown && it > 0 },
                    pieces = need.pieces.takeIf { it > 0 },
                    category = need.category,
                    recipes = need.recipes.toList(),
                    onHand = need.onHand,
                    checked = key in checked,
                    offers = key.removePrefix("i").toLongOrNull()?.takeIf { key.startsWith("i") }?.let { offers[it] }.orEmpty(),
                )
            }.sortedWith(compareBy({ it.category == "Other" }, { it.category }, { it.name.lowercase() })),
            staples = staples.toList(),
        )
    }

    fun setChecked(weekStart: String, check: ShoppingCheck): ShoppingList {
        val start = parse(weekStart)
        if (check.checked) q.insertCheck(start.toString(), check.key) else q.deleteCheck(start.toString(), check.key)
        return shoppingList(weekStart)
    }

    private fun request(start: LocalDate, locked: List<PlannedMeal>, seed: Long) = PlanRequest(
        targets = targets(),
        candidates = recipes.planCandidates(onOffer = offersByIngredient(start, start.plusDays(DAYS - 1L)).keys),
        days = DAYS,
        locked = locked,
        // What was planned the week before counts as recently eaten.
        recent = stored(start.minusDays(DAYS.toLong())).mapTo(HashSet()) { it.recipeId },
        seed = seed,
    )

    private fun targets() = profiles.state().targets
        ?: throw ConflictException("Set up your profile first: the planner needs daily targets")

    private fun view(start: LocalDate): WeekPlan {
        val meals = stored(start)
        return WeekPlan(
            weekStart = start.toString(),
            targets = targets(),
            days = (0 until DAYS).map { day ->
                val ofDay = meals.filter { it.day == day }.sortedBy { it.slot.ordinal }
                PlanDay(start.plusDays(day.toLong()).toString(), ofDay, ofDay.sumOf { it.kcal }, ofDay.sumOf { it.proteinG })
            },
            emptySlots = MealSlot.entries.filter { slot -> meals.none { it.slot == slot } },
        )
    }

    private fun stored(start: LocalDate): List<PlannedMeal> = q.selectWeek(start.toString()).executeAsList().map {
        PlannedMeal(
            day = it.day.toInt(),
            slot = MealSlot.valueOf(it.slot),
            recipeId = it.recipeId,
            title = it.title,
            portions = it.portions,
            kcal = it.kcal,
            proteinG = it.proteinG,
            leftover = it.leftover != 0L,
            locked = it.locked != 0L,
            status = MealStatus.valueOf(it.status),
        )
    }

    private fun save(start: LocalDate, meal: PlannedMeal) = q.upsertMeal(
        start.toString(), meal.day.toLong(), meal.slot.name, meal.recipeId, meal.title, meal.portions,
        meal.kcal, meal.proteinG, if (meal.leftover) 1 else 0, if (meal.locked) 1 else 0, meal.status.name,
    )

    private fun parse(weekStart: String): LocalDate = try {
        LocalDate.parse(weekStart)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("weekStart must be an ISO date such as 2026-11-16")
    }

    private companion object {
        const val DAYS = 7
    }
}
