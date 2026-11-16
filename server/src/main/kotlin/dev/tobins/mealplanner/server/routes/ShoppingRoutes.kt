package dev.tobins.mealplanner.server.routes

import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingExtraInput
import dev.tobins.mealplanner.api.ShoppingRowKey
import dev.tobins.mealplanner.server.user
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** The shopping list of a week: ticking rows, using items up, own items, and hiding the advice groups. */
fun Route.shoppingRoutes() {
    route("/api/plans/{weekStart}") {
        get("/shopping") { call.respond(call.user.shopping.get(call.week())) }

        // Ticks an item of the shopping list off, or back on.
        put("/shopping") { call.respond(call.user.shopping.setChecked(call.week(), call.receive<ShoppingCheck>())) }

        // Ticks a ticked row that needs more again, for what it needs now, in one step.
        put("/shopping/again") { call.respond(call.user.shopping.tickAgain(call.week(), call.receive<ShoppingRowKey>())) }

        // Puts a bought item no meal needs any more on hand with what was bought of it; answers with the week's list.
        post("/shopping/use-up") { call.respond(call.user.shopping.useUp(call.week(), call.receive<ShoppingRowKey>())) }

        // Puts an own item (coffee, toilet paper) on the list from this week on; answers with the week's list.
        post("/shopping/extras") {
            call.respond(HttpStatusCode.Created, call.user.shopping.addExtra(call.week(), call.receive<ShoppingExtraInput>()))
        }

        // Takes an own item off every list; answers with the week's list.
        delete("/shopping/extras/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Own item id must be a number")
            call.respond(call.user.shopping.deleteExtra(call.week(), id))
        }

        // PUT hides the buy-ahead or stock-up group of the week's list, DELETE shows it again.
        for (dismissed in listOf(true, false)) {
            route("/shopping/dismissed/{group}", if (dismissed) HttpMethod.Put else HttpMethod.Delete) {
                handle {
                    val group = ShoppingAdviceGroup.fromPath(call.parameters["group"])
                        ?: throw IllegalArgumentException("group must be one of ${ShoppingAdviceGroup.entries.joinToString { it.path }}")
                    call.respond(call.user.shopping.setDismissed(call.week(), group, dismissed))
                }
            }
        }
    }
}
