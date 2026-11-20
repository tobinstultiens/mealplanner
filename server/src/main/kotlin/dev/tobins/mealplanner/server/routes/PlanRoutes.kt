package dev.tobins.mealplanner.server.routes

import dev.tobins.mealplanner.api.GenerateRequest
import dev.tobins.mealplanner.api.MealAdjustment
import dev.tobins.mealplanner.api.MealChoice
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.PrepInput
import dev.tobins.mealplanner.api.SpareMove
import dev.tobins.mealplanner.server.respondGenerationOff
import dev.tobins.mealplanner.server.ConflictException
import dev.tobins.mealplanner.server.generation.GenerationException
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
        // meals; eat the share of one of its skipped meals in a later meal, body {"from": {...}, "to": {"date": ..., "slot": ...}}.
        route("/preps") {
            post { call.respond(call.user.preps.create(call.week(), call.receive<PrepInput>())) }
            route("/{id}") {
                delete { call.respond(call.user.preps.remove(call.week(), call.prepId())) }
                post("/spare") { call.respond(call.user.preps.eatSpare(call.week(), call.prepId(), call.receive<SpareMove>())) }
            }
        }

        route("/meals/{day}/{slot}") {
            post("/reroll") { call.respond(call.user.plans.reroll(call.week(), call.day(), call.slot())) }
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
