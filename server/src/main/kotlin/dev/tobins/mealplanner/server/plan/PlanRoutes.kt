package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.DinnerMove
import dev.tobins.mealplanner.api.GenerateRequest
import dev.tobins.mealplanner.api.MealAdjustment
import dev.tobins.mealplanner.api.MealChoice
import dev.tobins.mealplanner.api.MealFoodInput
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PrepInput
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.SpareMove
import dev.tobins.mealplanner.api.StandardChoice
import dev.tobins.mealplanner.server.respondGenerationOff
import dev.tobins.mealplanner.server.ConflictException
import dev.tobins.mealplanner.server.recipes.generation.GenerationException
import dev.tobins.mealplanner.server.user
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

internal fun RoutingCall.week() = parameters["weekStart"]!!

private fun RoutingCall.day() =
    parameters["day"]?.toIntOrNull()?.takeIf { it in 0..6 } ?: throw IllegalArgumentException("day must be 0 to 6")

private fun RoutingCall.slot() = MealSlot.entries.firstOrNull { it.name.equals(parameters["slot"], ignoreCase = true) }
    ?: throw IllegalArgumentException("slot must be one of ${MealSlot.entries.joinToString()}")

/**
 * Runs [block], answering a [ConflictException] (such as "This meal was eaten already") as 422 with its
 * message followed by [more], and null then. The app's generate dialog reads any 409 as GenerationService's
 * "set up your profile first"; any other refusal it shows as the server wrote it.
 */
private suspend fun <T> RoutingCall.unprocessableOnConflict(more: String = "", block: () -> T): T? = try {
    block()
} catch (e: ConflictException) {
    respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to (e.message ?: "conflict") + more))
    null
}

/** The week plan: planning a week, and rerolling, locking, removing, generating and marking single meals. */

fun Route.planRoutes() {
    route("/api/plans/{weekStart}") {
        get { call.respond(call.user.plans.get(call.week())) }

        // Replans every meal that is not locked.
        post("/generate") { call.respond(call.user.plans.generate(call.week())) }

        // Prepped meals (issue 234), each answering this week's plan: make a batch, body PrepInput; remove one with all its
        // meals; eat the share of one of its skipped meals in a later meal, body {"from": {...}, "to": {"date": ..., "slot": ...}};
        // grow one still to make by a side next to a later meal (issue 269), body {"date": ..., "slot": ..., "side": 0.5}.
        route("/preps") {
            post { call.respond(call.user.preps.create(call.week(), call.receive<PrepInput>())) }
            route("/{id}") {
                delete { call.respond(call.user.preps.remove(call.week(), call.prepId())) }
                post("/spare") { call.respond(call.user.preps.eatSpare(call.week(), call.prepId(), call.receive<SpareMove>())) }
                post("/sides") { call.respond(call.user.preps.addSide(call.week(), call.prepId(), call.receive<PrepSlot>())) }
            }
        }

        // The standard meals for each of their slots, at the portion each gets, and the slots none can take (issue 265).
        get("/standard-meals") { call.respond(call.user.standardMeals.week(call.week())) }

        // The planning screen (issue 255): the dinners chosen for the week and the recipes hidden for it, each change
        // answering the WeekPlanning as it is then; the suggestions; and "Done", which plans the week with the chosen dinners.
        route("/planning") {
            get { call.respond(call.user.planning.get(call.week())) }
            // ?seen=12,15 leaves out what the screen showed already ("Show others"); ?q=pasta searches every meal of mine.
            get("/suggestions") {
                val count = call.request.queryParameters["count"]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("count must be a number") }
                    ?: DEFAULT_SUGGESTIONS
                call.respond(call.user.planning.suggestions(call.week(), call.seen(), count, call.request.queryParameters["q"]))
            }
            route("/picks/{recipeId}") {
                put { call.respond(call.user.planning.pick(call.week(), call.recipeId())) }
                delete { call.respond(call.user.planning.unpick(call.week(), call.recipeId())) }
            }
            route("/not-this-week/{recipeId}") {
                put { call.respond(call.user.planning.hide(call.week(), call.recipeId())) }
                delete { call.respond(call.user.planning.unhide(call.week(), call.recipeId())) }
            }
            post("/done") { call.respond(call.user.planning.done(call.week())) }
        }

        route("/meals/{day}/{slot}") {
            // A standard meal in place of what is there, body {"recipeId": 12, "eaten": true}; locked, as a choice by hand.
            put("/standard") { call.respond(call.user.standardMeals.choose(call.week(), call.day(), call.slot(), call.receive<StandardChoice>())) }
            post("/reroll") { call.respond(call.user.plans.reroll(call.week(), call.day(), call.slot())) }
            // A dinner to another day, body {"toDay": 3}, swapping with the dinner there (issue 255); 409 for one that stays.
            post("/move") {
                if (call.slot() != MealSlot.DINNER) throw IllegalArgumentException("Only a dinner moves to another day")
                call.respond(call.user.plans.moveDinner(call.week(), call.day(), call.receive<DinnerMove>().toDay))
            }
            put("/lock") { call.respond(call.user.plans.setLocked(call.week(), call.day(), call.slot(), locked = true)) }
            delete("/lock") { call.respond(call.user.plans.setLocked(call.week(), call.day(), call.slot(), locked = false)) }
            // The user's own choice of recipe, body {"recipeId": 12}; locked, so replanning keeps it.
            put { call.respond(call.user.plans.choose(call.week(), call.day(), call.slot(), call.receive<MealChoice>().recipeId)) }
            // Not a meal the stock went down for (409): it keeps what was taken, to give back when un-eaten.
            delete { call.respond(call.user.plans.remove(call.week(), call.day(), call.slot())) }
            // Writes a new recipe for this meal with Claude and puts it in the plan; not for a meal that was cooked.
            post("/generate") {
                val service = call.user.generation ?: return@post call.respondGenerationOff()
                val (week, day, slot) = Triple(call.week(), call.day(), call.slot())
                val plans = call.user.plans
                call.unprocessableOnConflict { plans.requireReplaceable(week, day, slot) } ?: return@post
                val recipe = service.generate(call.receive<GenerateRequest>().copy(slot = slot))
                val candidate = call.user.recipes.candidate(recipe.summary.id)
                    ?: throw GenerationException("The generated recipe has no amounts to plan with")
                // Eaten while the recipe was written: the recipe stays in the account's recipes, and the dialog says so.
                val kept = "; the new recipe \"${recipe.summary.title}\" is kept in your recipes"
                call.unprocessableOnConflict(kept) { plans.place(week, day, slot, candidate) }?.let { call.respond(it) }
            }
            // Takes the side next to the meal out of its batch (issue 234); the meal keeps its main.
            delete("/side") { call.respond(call.user.preps.removeSide(call.week(), call.day(), call.slot())) }
            // Body: "EATEN", "SKIPPED" or "PLANNED".
            put("/status") {
                call.respond(call.user.plans.setStatus(call.week(), call.day(), call.slot(), call.receive<MealStatus>()))
            }
            // Foods in the slot (issue 358), each change answering the week: add one, body {"ingredientId": 7, "amount": 2,
            // "unit": "bar"}, by which an empty slot becomes a slot of foods; replace one, same body; take one out, the last of a
            // slot of foods alone taking the meal with it.
            route("/foods") {
                post { call.respond(call.user.plans.addFood(call.week(), call.day(), call.slot(), call.receive<MealFoodInput>())) }
                route("/{foodId}") {
                    put { call.respond(call.user.plans.changeFood(call.week(), call.day(), call.slot(), call.foodId(), call.receive<MealFoodInput>())) }
                    delete { call.respond(call.user.plans.removeFood(call.week(), call.day(), call.slot(), call.foodId())) }
                }
            }
            // What was in the meal (issue 226): its recipe's ingredients with this meal's changes, and their numbers.
            route("/lines") {
                get { call.respond(call.user.plans.lines(call.week(), call.day(), call.slot())) }
                // Body {"lines": [{"ingredientId": 7, "grams": 60}]}: every change, replacing those before; [] resets.
                put {
                    val lines = call.receive<MealAdjustment>().lines
                    call.respond(call.user.plans.adjust(call.week(), call.day(), call.slot(), lines))
                }
            }
        }
    }

    // The shares of skipped prepped meals still in the box on a date, with where each can still be eaten.
    get("/api/preps/spare/{date}") { call.respond(call.user.preps.spare(call.parameters["date"]!!)) }
}

private fun RoutingCall.prepId() = parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Prep id must be a number")

private fun RoutingCall.foodId() = parameters["foodId"]?.toLongOrNull() ?: throw IllegalArgumentException("Food id must be a number")

private fun RoutingCall.recipeId() =parameters["recipeId"]?.toLongOrNull() ?: throw IllegalArgumentException("Recipe id must be a number")

/** The recipes the planning screen showed already, `?seen=12,15`; none without it. */
private fun RoutingCall.seen(): Set<Long> = request.queryParameters["seen"].orEmpty().split(',').filter { it.isNotBlank() }
    .mapTo(HashSet()) { it.trim().toLongOrNull() ?: throw IllegalArgumentException("seen must be recipe ids separated by commas") }

/** Suggestions on one screen of the planning screen, as the owner asked for about six (issue 255). */
private const val DEFAULT_SUGGESTIONS = 6
