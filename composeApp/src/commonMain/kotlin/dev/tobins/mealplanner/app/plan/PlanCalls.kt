package dev.tobins.mealplanner.app.plan

import dev.tobins.mealplanner.api.AddOn
import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.DinnerMove
import dev.tobins.mealplanner.api.DinnerSuggestions
import dev.tobins.mealplanner.api.GenerateRequest
import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.LogTextInput
import dev.tobins.mealplanner.api.MealAdjustment
import dev.tobins.mealplanner.api.MealChoice
import dev.tobins.mealplanner.api.MealFoodInput
import dev.tobins.mealplanner.api.MealLineChange
import dev.tobins.mealplanner.api.MealLines
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.NotificationFacts
import dev.tobins.mealplanner.api.PrepInput
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.SpareMove
import dev.tobins.mealplanner.api.SparePortion
import dev.tobins.mealplanner.api.StandardChoice
import dev.tobins.mealplanner.api.StandardChosen
import dev.tobins.mealplanner.api.StandardMeals
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.api.WeekPlanning
import dev.tobins.mealplanner.app.ApiClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/*
 * The plan's server calls: planning, meals and their changes, preps and sides, standard meals, the day and what is due today.
 * They are extensions of [ApiClient], which keeps the address, the login, the errors and the retries for all of them.
 */

/** What the account's data says is due today, for the notifications (Android's background check). */
suspend fun ApiClient.notifications(): NotificationFacts = http.get("api/notifications").body()

suspend fun ApiClient.logFood(entry: LogEntry): DayView = http.post("api/log") {
    contentType(ContentType.Application.Json)
    setBody(entry)
}.body()

/** Log entry [id] typed over: a typed entry from then on. */
suspend fun ApiClient.retypeLog(id: Long, text: LogTextInput): DayView = http.put("api/log/$id") {
    contentType(ContentType.Application.Json)
    setBody(text)
}.body()

/** Writes a recipe for one meal of the plan and puts it there; can take a minute or two. */
suspend fun ApiClient.generateIntoPlan(weekStart: String, day: Int, slot: MealSlot, request: GenerateRequest): WeekPlan =
    http.post("api/plans/$weekStart/meals/$day/${slot.name}/generate") {
        timeout {
            requestTimeoutMillis = 240_000
            socketTimeoutMillis = 240_000
        }
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

/** What a meal of recipe [id] can have added with one tap; none from a server from before quick add-ons. */
suspend fun ApiClient.addOns(id: Long): List<AddOn> = unlessUnknown { http.get("api/recipes/$id/add-ons").body<List<AddOn>>() }.orEmpty()

suspend fun ApiClient.plan(weekStart: String): WeekPlan = http.get("api/plans/$weekStart").body()

suspend fun ApiClient.generatePlan(weekStart: String): WeekPlan = http.post("api/plans/$weekStart/generate").body()

suspend fun ApiClient.rerollMeal(weekStart: String, day: Int, slot: MealSlot): WeekPlan =
    http.post("api/plans/$weekStart/meals/$day/${slot.name}/reroll").body()

/**
 * Puts the recipe [recipeId] in a meal's slot, locked, and returns the week's plan; null from a
 * server from before the route, which the caller says to the user.
 */
suspend fun ApiClient.chooseRecipe(weekStart: String, day: Int, slot: MealSlot, recipeId: Long): WeekPlan? = unlessUnknown {
    http.put("api/plans/$weekStart/meals/$day/${slot.name}") {
        contentType(ContentType.Application.Json)
        setBody(MealChoice(recipeId))
    }.body()
}

/** The meal in [day]'s [slot] with what was in it: its recipe's ingredients for its portions and this meal's changes. */
suspend fun ApiClient.mealLines(weekStart: String, day: Int, slot: MealSlot): MealLines =
    http.get("api/plans/$weekStart/meals/$day/${slot.name}/lines").body()

/** Says what was in a meal: [changes] replace those it had, none puts it back to its recipe; answers the meal as it is now. */
suspend fun ApiClient.adjustMeal(weekStart: String, day: Int, slot: MealSlot, changes: List<MealLineChange>): MealLines =
    http.put("api/plans/$weekStart/meals/$day/${slot.name}/lines") {
        contentType(ContentType.Application.Json)
        setBody(MealAdjustment(changes))
    }.body()

/**
 * Adds [food] to the slot (issue 358), which an empty slot becomes a slot of foods by, next to what is there otherwise;
 * answers the week.
 */
suspend fun ApiClient.addFood(weekStart: String, day: Int, slot: MealSlot, food: MealFoodInput): WeekPlan =
    http.post("api/plans/$weekStart/meals/$day/${slot.name}/foods") {
        contentType(ContentType.Application.Json)
        setBody(food)
    }.body()

/** Replaces food [id] of the slot with [food]: another amount or unit (issue 358); answers the week. */
suspend fun ApiClient.changeFood(weekStart: String, day: Int, slot: MealSlot, id: Long, food: MealFoodInput): WeekPlan =
    http.put("api/plans/$weekStart/meals/$day/${slot.name}/foods/$id") {
        contentType(ContentType.Application.Json)
        setBody(food)
    }.body()

/** Takes food [id] out of the slot (issue 358); the last of a slot of foods alone takes the meal with it. Answers the week. */
suspend fun ApiClient.removeFood(weekStart: String, day: Int, slot: MealSlot, id: Long): WeekPlan =
    http.delete("api/plans/$weekStart/meals/$day/${slot.name}/foods/$id").body()

suspend fun ApiClient.setMealLocked(weekStart: String, day: Int, slot: MealSlot, locked: Boolean): WeekPlan {
    val path = "api/plans/$weekStart/meals/$day/${slot.name}/lock"
    return if (locked) http.put(path).body() else http.delete(path).body()
}

/** Makes a prep ([PrepInput]) and answers the week starting [weekStart], the one shown. */
suspend fun ApiClient.createPrep(weekStart: String, input: PrepInput): WeekPlan = http.post("api/plans/$weekStart/preps") {
    contentType(ContentType.Application.Json)
    setBody(input)
}.body()

/** Takes prep [id] out of the plan with all its meals, in every week; answers the week starting [weekStart]. */
suspend fun ApiClient.removePrep(weekStart: String, id: Long): WeekPlan = http.delete("api/plans/$weekStart/preps/$id").body()

/**
 * Adds a side of [PrepSlot.side] servings of prep [id], still to make, next to the meal at [side]'s slot, which makes the
 * batch larger (issue 269); answers the week starting [weekStart].
 */
suspend fun ApiClient.addSide(weekStart: String, id: Long, side: PrepSlot): WeekPlan = http.post("api/plans/$weekStart/preps/$id/sides") {
    contentType(ContentType.Application.Json)
    setBody(side)
}.body()

/** Takes the side next to the meal in [day]'s [slot] out of its batch (issue 234, step 2); the meal keeps its main. */
suspend fun ApiClient.removeSide(weekStart: String, day: Int, slot: MealSlot): WeekPlan = http.delete("api/plans/$weekStart/meals/$day/${slot.name}/side").body()

/** The shares of skipped prepped meals still in the box on [date]; none from a server from before preps. */
suspend fun ApiClient.spareShares(date: String): List<SparePortion> = unlessUnknown { http.get("api/preps/spare/$date").body<List<SparePortion>>() }.orEmpty()

/** Eats the share of the skipped meal [SpareMove.from] of prep [id] in [SpareMove.to]; answers the week starting [weekStart]. */
suspend fun ApiClient.eatSpare(weekStart: String, id: Long, move: SpareMove): WeekPlan = http.post("api/plans/$weekStart/preps/$id/spare") {
    contentType(ContentType.Application.Json)
    setBody(move)
}.body()

/** The standard meals for each slot of the week starting [weekStart], and the slots none can take; null from a server before them. */
suspend fun ApiClient.standardMeals(weekStart: String): StandardMeals? = unlessUnknown { http.get("api/plans/$weekStart/standard-meals").body<StandardMeals>() }

/**
 * Takes the meal in [day]'s [slot] of the week starting [weekStart] out of the plan; a standard meal placed over a skipped
 * prepped meal gives the slot back to it (issue 265). The server refuses a meal that keeps its recipe (409).
 */
suspend fun ApiClient.removeMeal(weekStart: String, day: Int, slot: MealSlot): WeekPlan = http.delete("api/plans/$weekStart/meals/$day/${slot.name}").body()

/** Puts standard meal [recipeId] in [day]'s [slot] of the week starting [weekStart], eaten at once when [eaten]. */
suspend fun ApiClient.chooseStandard(weekStart: String, day: Int, slot: MealSlot, recipeId: Long, eaten: Boolean): StandardChosen =
    http.put("api/plans/$weekStart/meals/$day/${slot.name}/standard") {
        contentType(ContentType.Application.Json)
        setBody(StandardChoice(recipeId, eaten))
    }.body()

/** The planning screen of the week starting [weekStart] (issue 255); null from a server before it, which plans in one go. */
suspend fun ApiClient.planning(weekStart: String): WeekPlanning? = unlessUnknown { http.get("api/plans/$weekStart/planning").body<WeekPlanning>() }

/**
 * The next [count] suggestions for the week's dinners, leaving out the recipes [seen] already ("Show others"); with a
 * [query], every meal of mine whose title has each of its words ("Something else"). Null from a server before the
 * planning screen, which has no such route.
 */
suspend fun ApiClient.dinnerSuggestions(weekStart: String, seen: Collection<Long>, count: Int, query: String? = null): DinnerSuggestions? = unlessUnknown {
    http.get("api/plans/$weekStart/planning/suggestions") {
        parameter("count", count)
        if (seen.isNotEmpty()) parameter("seen", seen.joinToString(","))
        query?.let { parameter("q", it) }
    }.body<DinnerSuggestions>()
}

/** Chooses recipe [recipeId] for the week's dinners, or with [chosen] false takes it out again. */
suspend fun ApiClient.chooseDinner(weekStart: String, recipeId: Long, chosen: Boolean): WeekPlanning =
    if (chosen) http.put("api/plans/$weekStart/planning/picks/$recipeId").body() else http.delete("api/plans/$weekStart/planning/picks/$recipeId").body()

/** Hides recipe [recipeId] from the week's suggestions ("Not this week"), or with [hidden] false shows it again. */
suspend fun ApiClient.hideForWeek(weekStart: String, recipeId: Long, hidden: Boolean): WeekPlanning =
    if (hidden) http.put("api/plans/$weekStart/planning/not-this-week/$recipeId").body() else http.delete("api/plans/$weekStart/planning/not-this-week/$recipeId").body()

/** Plans the week with the dinners chosen for it ("Done", or "Fill the rest"), and returns the plan. */
suspend fun ApiClient.planningDone(weekStart: String): WeekPlan = http.post("api/plans/$weekStart/planning/done").body()

/** Moves the dinner of [day] to [toDay], swapping it with the dinner there; the server refuses one that stays (409). */
suspend fun ApiClient.moveDinner(weekStart: String, day: Int, toDay: Int): WeekPlan = http.post("api/plans/$weekStart/meals/$day/${MealSlot.DINNER.name}/move") {
    contentType(ContentType.Application.Json)
    setBody(DinnerMove(toDay))
}.body()
