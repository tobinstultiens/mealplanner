package dev.tobins.mealplanner.server.tracking

import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.LogFoodInput
import dev.tobins.mealplanner.api.LogTextInput
import dev.tobins.mealplanner.api.MealFoodInput
import dev.tobins.mealplanner.server.user
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** What was eaten and weighed: a day with its totals, the log of extras, and the weights. */
fun Route.trackingRoutes() {
    // One day: planned meals with their status, extras, and totals against the targets.
    get("/api/days/{date}") { call.respond(call.user.tracking.day(call.parameters["date"]!!)) }

    // A typed entry, as every app sends it.
    post("/api/log") { call.respond(HttpStatusCode.Created, call.user.tracking.log(call.receive<LogEntry>())) }

    // A food of the catalogue in an amount (issue 362), counted now; its amount changed and recounted; any entry typed over.
    post("/api/log/foods") { call.respond(HttpStatusCode.Created, call.user.tracking.logFood(call.receive<LogFoodInput>())) }

    put("/api/log/{id}/food") { call.respond(call.user.tracking.changeFood(call.logId(), call.receive<MealFoodInput>())) }

    put("/api/log/{id}") { call.respond(call.user.tracking.retype(call.logId(), call.receive<LogTextInput>())) }

    delete("/api/log/{id}") {
        call.user.tracking.deleteLog(call.logId())
        call.respond(HttpStatusCode.NoContent)
    }

    get("/api/weights") { call.respond(call.user.tracking.weights()) }

    put("/api/weights/{date}") { call.respond(call.user.tracking.setWeight(call.parameters["date"]!!, call.receive<Double>())) }

    delete("/api/weights/{date}") { call.respond(call.user.tracking.deleteWeight(call.parameters["date"]!!)) }
}

private fun ApplicationCall.logId(): Long = parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Log id must be a number")
