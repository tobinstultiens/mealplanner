package dev.tobins.mealplanner.app.plan

import dev.tobins.mealplanner.api.BatchToMake
import dev.tobins.mealplanner.api.ClosedSlot
import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.LaterShortfall
import dev.tobins.mealplanner.api.MealAdjustment
import dev.tobins.mealplanner.api.MealFood
import dev.tobins.mealplanner.api.MealFoodInput
import dev.tobins.mealplanner.api.MealLine
import dev.tobins.mealplanner.api.MealLines
import dev.tobins.mealplanner.api.MealPrep
import dev.tobins.mealplanner.api.MealSide
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PlanDay
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PrepMeal
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.RecipeLineInput
import dev.tobins.mealplanner.api.SparePortion
import dev.tobins.mealplanner.api.StandardMeal
import dev.tobins.mealplanner.api.StandardMeals
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.api.WeekRest
import dev.tobins.mealplanner.app.FakeBackend
import dev.tobins.mealplanner.app.FakeBackend.Companion.TODAY
import dev.tobins.mealplanner.app.FakeBackend.Companion.WEEK_START
import dev.tobins.mealplanner.app.ingredients.names
import dev.tobins.mealplanner.app.tracking.logOf
import dev.tobins.mealplanner.app.ui.daysBetween
import dev.tobins.mealplanner.app.ui.mondayOf
import dev.tobins.mealplanner.app.ui.plusDays
import dev.tobins.mealplanner.nutrition.NutritionCalculator
import dev.tobins.mealplanner.recipes.toIngredientLine
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/* The plan's part of [FakeBackend]: the sample week, its preps and sides, the day, the foods in its slots and what was in a meal. */

/** [input] as the server lists a food: its ingredient's name, and its weight and numbers as the shared code counts them. */
internal fun FakeBackend.foodOf(id: Long, input: MealFoodInput): MealFood {
    val ingredient = catalogue.single { it.id == input.ingredientId }
    val line = RecipeLineInput(input.amount, input.unit, ingredient.name, ingredient.id).toIngredientLine()!!
    val counted = NutritionCalculator.perServing(listOf(line to ingredient), servings = 1).perServing
    return MealFood(
        id, ingredient.id, ingredient.name, input.amount, input.unit, NutritionCalculator.quantityOf(line, ingredient)?.grams,
        counted.kcal ?: 0.0, counted.proteinG ?: 0.0, counted.fibreG ?: 0.0,
    )
}

/** `POST`, `PUT` and `DELETE /api/plans/{week}/meals/{day}/{slot}/foods[/{id}]`: the slot's foods changed, and the week. */
internal fun FakeBackend.foodsRoute(method: String, path: String, parts: List<String>, sent: String): String {
    changes += "$method $path $sent".trim()
    val slot = Triple(parts[2], parts[4].toInt(), MealSlot.valueOf(parts[5]))
    val foods = plannedFoods[slot].orEmpty()
    val id = parts.getOrNull(7)?.toLong()
    plannedFoods[slot] = when (method) {
        "POST" -> foods + foodOf((plannedFoods.values.flatten().maxOfOrNull { it.id } ?: 0) + 1, json.decodeFromString<MealFoodInput>(sent))
        "PUT" -> foods.map { if (it.id == id) foodOf(it.id, json.decodeFromString<MealFoodInput>(sent)) else it }
        else -> foods.filterNot { it.id == id }
    }
    return json.encodeToString(plan(parts[2]))
}

/**
 * [meals] of [day] of the week starting [weekStart] with their slots' foods, as the server answers them: a slot of foods
 * alone where there is no meal, titled by their names, and foods next to a meal counted with it.
 */
internal fun FakeBackend.withFoods(weekStart: String, day: Int, meals: List<PlannedMeal>): List<PlannedMeal> {
    val next = meals.map { meal ->
        val foods = plannedFoods[Triple(weekStart, day, meal.slot)].orEmpty()
        if (foods.isEmpty()) meal else meal.copy(foods = foods, kcal = meal.kcal + foods.sumOf { it.kcal }, proteinG = meal.proteinG + foods.sumOf { it.proteinG })
    }
    val alone = MealSlot.entries.filter { slot -> meals.none { it.slot == slot } }.mapNotNull { slot ->
        val foods = plannedFoods[Triple(weekStart, day, slot)]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        PlannedMeal(
            day, slot, 900L + day * 4 + slot.ordinal, foods.joinToString(", ") { it.name }, 1.0, foods.sumOf { it.kcal }, foods.sumOf { it.proteinG },
            locked = true, fibreG = foods.sumOf { it.fibreG }, foods = foods, foodsOnly = true,
        )
    }
    return (next + alone).sortedBy { it.slot.ordinal }
}

internal fun FakeBackend.meal(day: Int, slot: MealSlot, id: Long, title: String, kcal: Double, protein: Double, fibre: Double, cost: Double = 2.4) =
    PlannedMeal(day, slot, id, title, 1.0, kcal, protein, cost = cost, fibreG = fibre)

/** Three meals a day; a title is long on purpose, to show how rows cope. */
internal fun FakeBackend.planDay(index: Int): PlanDay {
    val meals = listOf(
        meal(index, MealSlot.BREAKFAST, 1, "Overnight oats with blueberries", 460.0, 24.0, 8.2, cost = 1.1)
            .let { if (index == 2) it.copy(status = MealStatus.EATEN) else it },
        meal(index, MealSlot.LUNCH, 2L + index % 2, if (index % 2 == 0) "Chickpea salad with feta and roasted peppers" else "Tuna pasta salad", 640.0, 42.0, if (index % 2 == 0) 11.4 else 4.1),
        meal(index, MealSlot.DINNER, 4, "Slow-cooked chicken thighs with coconut, lemon and spinach", 810.0, 55.0, 5.3, cost = 3.9),
    ).map { meal ->
        when {
            // Wednesday's dinner got more chicken and an egg, and no spinach; Thursday's dinner is left out;
            // Friday's was chosen by the user, with Saturday's lunch its leftovers.
            index == 2 && meal.slot == MealSlot.DINNER -> meal.copy(kcal = 935.0, proteinG = 72.0, fibreG = 3.1, adjusted = true)
            index == 3 && meal.slot == MealSlot.DINNER -> meal.copy(status = MealStatus.SKIPPED)
            index == 4 && meal.slot == MealSlot.DINNER ->
                meal.copy(recipeId = 5, title = "Lentil soup", locked = true, fibreG = 12.6, status = if (dinnerEaten) MealStatus.EATEN else MealStatus.PLANNED)
            index == 5 && meal.slot == MealSlot.LUNCH -> meal.copy(recipeId = 5, title = "Lentil soup", leftover = true, fibreG = 12.6)
            standardMeals && index == 2 && meal.slot == MealSlot.LUNCH -> meal.copy(status = MealStatus.SKIPPED)
            else -> meal
        }
    }.map { meal -> if (prepped) prepped(meal) else meal }.map { meal -> if (sides || mixed) withSide(meal) else meal }.map { meal ->
        // What the server says to cook: Friday's lentil soup also feeds Saturday's lunch, which cooks nothing; the prep's Monday dinner cooks the batch.
        meal.copy(cookPortions = when {
            meal.prep != null -> if (meal.leftover) 0.0 else meal.prep!!.portions
            meal.status == MealStatus.SKIPPED || meal.leftover -> 0.0
            index == 4 && meal.slot == MealSlot.DINNER -> 2.0
            else -> meal.portions
        })
    }
    val withFoods = withFoods(WEEK_START, index, meals)
    // As the server counts them: each meal with its side.
    val counted = withFoods.filter { it.status != MealStatus.SKIPPED }
    return PlanDay(plusDays(WEEK_START, index), withFoods, counted.sumOf { it.totalKcal }, counted.sumOf { it.totalProteinG }, counted.sumOf { it.totalFibreG!! })
}

/**
 * The chickpea salad prepped as a side of half a serving: made today (Wednesday) with the dinner, which goes over the day's
 * calories with it, and eaten next to Thursday's lunch and Friday's lentil soup; of a [mixed] batch, Thursday's lunch is
 * a serving of the salad itself.
 */
internal val FakeBackend.sidePrep get() = MealPrep(
    id = 2, madeOn = TODAY, lastDay = plusDays(TODAY, 3), vacuum = false,
    meals = listOf(2 to MealSlot.DINNER, 3 to MealSlot.LUNCH, 4 to MealSlot.DINNER).map { (day, slot) ->
        val asMeal = mixed && day == 3
        PrepMeal(plusDays(WEEK_START, day), slot, if (asMeal) 1.0 else 0.5, MealStatus.PLANNED, side = !asMeal)
    },
)

/** [meal] as the week of [sides] has it: with half a serving of the salad next to it, the salad itself for a [mixed] batch, or as it was. */
internal fun FakeBackend.withSide(meal: PlannedMeal): PlannedMeal {
    val date = plusDays(WEEK_START, meal.day)
    val share = sidePrep.meals.firstOrNull { it.date == date && it.slot == meal.slot } ?: return meal
    if (!share.side) {
        return meal(meal.day, meal.slot, 2, "Chickpea salad with feta and roasted peppers", 640.0, 42.0, 11.4)
            .copy(leftover = true, locked = true, prep = sidePrep)
    }
    val prepDay = date == sidePrep.madeOn
    val side = MealSide(
        2, "Chickpea salad with feta and roasted peppers", 0.5, 320.0, 21.0, 5.7, cost = 1.2, cookPortions = if (prepDay) sidePrep.portions else 0.0, prep = sidePrep,
    )
    return meal.copy(side = side)
}

/**
 * The chicken thighs prepped: made on Monday's dinner and eaten on Tuesday's and Wednesday's lunches and Thursday's
 * dinner, in a box for three days. Wednesday's lunch was skipped, so its share is in the box ([spare]).
 */
internal val FakeBackend.prep get() = MealPrep(
    id = 1, madeOn = WEEK_START, lastDay = plusDays(WEEK_START, 3), vacuum = vacuum,
    meals = listOf(0 to MealSlot.DINNER, 1 to MealSlot.LUNCH, 2 to MealSlot.LUNCH, 3 to MealSlot.DINNER).map { (day, slot) ->
        PrepMeal(plusDays(WEEK_START, day), slot, 1.0, if (day == 2) MealStatus.SKIPPED else MealStatus.PLANNED)
    },
)

internal val FakeBackend.spare get() = SparePortion(
    prepId = 1, recipeId = 4, title = "Slow-cooked chicken thighs with coconut, lemon and spinach", portions = 1.0,
    skipped = PrepSlot(plusDays(WEEK_START, 2), MealSlot.LUNCH), lastDay = prep.lastDay,
    room = listOf(PrepSlot(plusDays(WEEK_START, 2), MealSlot.DINNER), PrepSlot(plusDays(WEEK_START, 3), MealSlot.LUNCH)),
)

/** [meal] as the week of [prepped] has it: one of the prep's, or as it was. */
internal fun FakeBackend.prepped(meal: PlannedMeal): PlannedMeal {
    val ofPrep = prep.meals.firstOrNull { it.date == plusDays(WEEK_START, meal.day) && it.slot == meal.slot } ?: return meal
    val chicken = meal(meal.day, meal.slot, 4, "Slow-cooked chicken thighs with coconut, lemon and spinach", 810.0, 55.0, 5.3, cost = 3.9)
    return chicken.copy(status = ofPrep.status, leftover = meal.day > 0, locked = true, prep = prep)
}

/** The chicken thighs prepped on the Sunday before the week, eaten then and at Monday's lunch: the week's only meal ([tailOnly]). */
internal val FakeBackend.tailPrep get() = MealPrep(
    id = 3, madeOn = plusDays(WEEK_START, -1), lastDay = plusDays(WEEK_START, 1), vacuum = false,
    meals = listOf(PrepMeal(plusDays(WEEK_START, -1), MealSlot.DINNER, 1.5, MealStatus.EATEN), PrepMeal(WEEK_START, MealSlot.LUNCH, 1.25, MealStatus.PLANNED)),
)

/** The week of [tailOnly]: Monday's lunch from last week's batch, nothing else; as the server says, no breakfast, dinner or snack in it. */
internal fun FakeBackend.tailWeek(weekStart: String) = WeekPlan(
    weekStart = weekStart,
    targets = targets,
    days = (0..6).map { day ->
        val meals = if (day == 0) {
            listOf(
                meal(0, MealSlot.LUNCH, 4, "Slow-cooked chicken thighs with coconut, lemon and spinach", 1012.5, 68.75, 6.6, cost = 4.9)
                    .copy(portions = 1.25, leftover = true, locked = true, prep = tailPrep, cookPortions = 0.0),
            )
        } else {
            emptyList()
        }
        PlanDay(plusDays(weekStart, day), meals, meals.sumOf { it.kcal }, meals.sumOf { it.proteinG }, meals.sumOf { it.fibreG ?: 0.0 })
    },
    emptySlots = listOf(MealSlot.BREAKFAST, MealSlot.DINNER, MealSlot.SNACK),
)

/** The batch of [growing]: today's dinner makes it and it keeps three days, with no side of it in the week yet. */
internal val FakeBackend.growingPrep get() = MealPrep(
    id = 2, madeOn = TODAY, lastDay = plusDays(TODAY, 3), vacuum = false,
    meals = listOf(PrepMeal(TODAY, MealSlot.DINNER, 1.0, MealStatus.PLANNED)),
)

internal fun FakeBackend.plan(weekStart: String) = if (tailOnly) tailWeek(weekStart) else WeekPlan(
    weekStart = weekStart,
    targets = targets,
    days = (0..6).map { if (emptyWeek) PlanDay(plusDays(weekStart, it), emptyList(), 0.0, 0.0, 0.0) else planDay(it) },
    emptySlots = if (emptyWeek) MealSlot.entries else emptySlots,
    batchesToMake = if (growing) listOf(BatchToMake(2, "Chickpea salad with feta and roasted peppers", growingPrep)) else emptyList(),
    dinnersChosen = if (planning) picks.size else 0,
    cost = if (emptyWeek) null else 61.40,
    costWithoutOffers = if (emptyWeek) null else 68.95,
    rest = if (emptyWeek || !weekRest) emptyList() else listOf(
        WeekRest("Crème fraîche", 100.0, 1.10), WeekRest("Koriander", 12.0, 0.60), WeekRest("Bieslook", 8.0, 0.40),
        WeekRest("Gember", 25.0, 0.20), WeekRest("Peterselie", 10.0, 0.10),
    ),
)

/** The standard meals of [standardMeals] for each of their slots, sized as the server would; the eaten breakfast takes none. */
internal fun FakeBackend.standardOffer(weekStart: String) = StandardMeals(
    weekStart,
    meals = listOf(MealSlot.LUNCH, MealSlot.DINNER).flatMap { slot ->
        listOf(
            StandardMeal(3, "Tuna pasta salad", slot, 1.0, 610.0, 38.0, 4.0),
            StandardMeal(6, "Shakshuka", slot, 1.25, 700.0, 36.0, 6.0, missing = listOf("Eggs")),
        )
    },
    // With [prepped], Monday's dinner makes the batch, which takes no standard meal, skipped or not.
    closed = listOfNotNull(
        ClosedSlot(2, MealSlot.BREAKFAST, "This meal was eaten already"),
        ClosedSlot(0, MealSlot.DINNER, "This meal makes the prepped batch, which stays in the plan with it").takeIf { prepped },
    ),
)

internal fun FakeBackend.day(date: String): DayView {
    val planned = plan(mondayOf(date)).days.first { it.date == date }
    val extras = logOf(date)
    val kcal = extras.sumOf { it.kcal }
    val protein = extras.sumOf { it.proteinG }
    // A typed extra has no fibre; a logged food has its own.
    val fibre = extras.sumOf { it.fibreG ?: 0.0 }
    return DayView(
        date = date,
        weekStart = mondayOf(date),
        dayIndex = daysBetween(mondayOf(date), date),
        targets = targets,
        meals = planned.meals,
        extras = extras,
        // The breakfast was eaten: 460 kcal, 24 g protein and 8.2 g fibre.
        eatenKcal = 460.0 + kcal,
        eatenProteinG = 24.0 + protein,
        expectedKcal = planned.kcal + kcal,
        expectedProteinG = planned.proteinG + protein,
        eatenFibreG = 8.2 + fibre,
        expectedFibreG = planned.fibreG?.plus(fibre),
    )
}

/**
 * The chicken dinner with what was in it: the recipe's lines for one serving, one of them linked to no
 * ingredient, with [lineChanges] applied. An egg is not in the catalogue the screens list, so that the
 * pantry's picture stays as it is; its line counts it in pieces.
 */
internal fun FakeBackend.mealLines(method: String, sent: String): MealLines {
    if (method == "PUT") {
        lineChanges = json.decodeFromString<MealAdjustment>(sent).lines
        changes += "PUT lines $sent"
    }
    val changed = lineChanges.associate { it.ingredientId to it.grams }
    val recipe = listOf(MealLine(1, "Chicken thigh fillet", 200.0, 200.0), MealLine(8, "Spinach", 75.0, 75.0), MealLine(5, "Olive oil", 10.0, 10.0))
        .map { line -> changed[line.ingredientId]?.let { line.copy(grams = it, changed = true) } ?: line } +
        MealLine(null, "Coconut milk", 100.0)
    val added = lineChanges.filter { change -> recipe.none { it.ingredientId == change.ingredientId } }.map { change ->
        val ingredient = names.getValue(change.ingredientId)
        MealLine(ingredient.id, ingredient.name, change.grams, fromRecipe = false, changed = true, gramsPerPiece = ingredient.gramsPerPiece)
    }
    val adjusted = lineChanges.isNotEmpty()
    // More chicken than the recipe's takes what was bought for Saturday's dinner of the same recipe.
    val shortfalls = changed[1]?.takeIf { it > 200.0 }?.let {
        listOf(LaterShortfall(5, MealSlot.DINNER, "Slow-cooked chicken thighs with coconut, lemon and spinach", 1, "Chicken thigh fillet", it - 200.0))
    }.orEmpty()
    return MealLines(
        WEEK_START, 2, MealSlot.DINNER, 4, "Slow-cooked chicken thighs with coconut, lemon and spinach", 1.0, recipe + added,
        kcal = if (adjusted) 935.0 else 810.0, proteinG = if (adjusted) 72.0 else 55.0, fibreG = if (adjusted) 3.1 else 5.3,
        complete = false, uncounted = listOf("Coconut milk"), adjusted = adjusted, shortfalls = shortfalls,
    )
}
