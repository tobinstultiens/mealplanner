package dev.tobins.mealplanner.server.routes

import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.server.user
import io.ktor.http.HttpStatusCode
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

    post("/api/log") { call.respond(HttpStatusCode.Created, call.user.tracking.log(call.receive<LogEntry>())) }

    delete("/api/log/{id}") {
        call.user.tracking.deleteLog(call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Log id must be a number"))
        call.respond(HttpStatusCode.NoContent)
    }

    get("/api/weights") { call.respond(call.user.tracking.weights()) }

    put("/api/weights/{date}") { call.respond(call.user.tracking.setWeight(call.parameters["date"]!!, call.receive<Double>())) }

    delete("/api/weights/{date}") { call.respond(call.user.tracking.deleteWeight(call.parameters["date"]!!)) }
}
