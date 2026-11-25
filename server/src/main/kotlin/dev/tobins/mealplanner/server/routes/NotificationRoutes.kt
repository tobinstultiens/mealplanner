package dev.tobins.mealplanner.server.routes

import dev.tobins.mealplanner.server.Account
import dev.tobins.mealplanner.server.user
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * What is due for the Android app's notifications: facts about the calling account's day, which the app words and posts
 * (issue 225), and for the owner the requests for an account that wait ([accountRequests], issue 314).
 */
fun Route.notificationRoutes(accountRequests: (Account) -> List<String>) {
    get("/api/notifications") {
        call.respond(call.user.notifications.facts().copy(accountRequests = accountRequests(call.principal<Account>()!!)))
    }
}
