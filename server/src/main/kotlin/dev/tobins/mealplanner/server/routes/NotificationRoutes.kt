package dev.tobins.mealplanner.server.routes

import dev.tobins.mealplanner.server.user
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** What is due for the Android app's notifications: facts about the calling account's day, which the app words and posts (issue 225). */
fun Route.notificationRoutes() {
    get("/api/notifications") { call.respond(call.user.notifications.facts()) }
}
