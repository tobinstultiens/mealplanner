package dev.tobins.mealplanner.server.routes

import dev.tobins.mealplanner.api.CookingRepoSetting
import dev.tobins.mealplanner.api.Me
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.ShoppingAdvice
import dev.tobins.mealplanner.api.ShopsSetting
import dev.tobins.mealplanner.server.Account
import dev.tobins.mealplanner.server.CookingSync
import dev.tobins.mealplanner.server.accountId
import dev.tobins.mealplanner.server.user
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** The calling account: who it is, its profile, shops and shopping advice, and the sync of its cooking-site repository. */
fun Route.accountRoutes(cooking: CookingSync) {
    get("/api/ping") { call.respond(mapOf("pong" to true)) }

    get("/api/me") {
        val account = call.principal<Account>()!!
        call.respond(Me(account.username, account.displayName))
    }

    get("/api/profile") { call.respond(call.user.profiles.state()) }

    put("/api/profile") { call.respond(call.user.profiles.save(call.receive<Profile>())) }

    // The stores the account shops at; offers elsewhere don't count for its plans and costs.
    get("/api/shops") { call.respond(call.user.shops.setting()) }

    put("/api/shops") { call.respond(call.user.shops.save(call.receive<ShopsSetting>())) }

    // Whether the shopping list advises buying ahead for next week and stocking up.
    get("/api/shopping-advice") { call.respond(call.user.shoppingAdvice.setting()) }

    put("/api/shopping-advice") { call.respond(call.user.shoppingAdvice.save(call.receive<ShoppingAdvice>())) }

    get("/api/sync/cooking") { call.respond(cooking.status(call.accountId)) }

    // Where the account's bank comes from: a repository of its own, the server's site, or none. The apps sync right after.
    put("/api/sync/cooking/repo") {
        val setting = call.receive<CookingRepoSetting>()
        cooking.setRepo(call.accountId, setting.url, setting.noSite)
        call.respond(cooking.status(call.accountId))
    }

    // Fetches the account's cooking site and brings its recipes in line with it.
    post("/api/sync/cooking") {
        val result = cooking.sync(call.accountId)
        if (result == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "No cooking site repository is set, or it has no content/ folder"),
            )
        } else {
            call.respond(result)
        }
    }
}
