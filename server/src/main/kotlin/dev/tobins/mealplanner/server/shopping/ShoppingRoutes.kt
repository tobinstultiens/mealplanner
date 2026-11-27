package dev.tobins.mealplanner.server.shopping

import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingExtraInput
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.ShoppingRowKey
import dev.tobins.mealplanner.server.plan.week
import dev.tobins.mealplanner.server.user
import io.ktor.http.HttpMethod
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

/**
 * Answers with [list] and the verdicts on its offers (issue 118): every answer that carries the week's list has them,
 * as the app shows the list each answer brings, and keeps it for the store, where it may have no network.
 */
private suspend fun RoutingCall.respondList(list: ShoppingList, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(status, user.judgement.on(list))

/** The shopping list of a week: ticking rows, using items up, own items, and hiding the advice groups. */
fun Route.shoppingRoutes() {
    route("/api/plans/{weekStart}") {
        get("/shopping") { call.respondList(call.user.shopping.view.get(call.week())) }

        // Ticks an item of the shopping list off, or back on.
        put("/shopping") { call.respondList(call.user.shopping.ticking.setChecked(call.week(), call.receive<ShoppingCheck>())) }

        // Ticks a ticked row that needs more again, for what it needs now, in one step.
        put("/shopping/again") { call.respondList(call.user.shopping.ticking.tickAgain(call.week(), call.receive<ShoppingRowKey>())) }

        // Puts a bought item no meal needs any more on hand with what was bought of it; answers with the week's list.
        post("/shopping/use-up") { call.respondList(call.user.shopping.ticking.useUp(call.week(), call.receive<ShoppingRowKey>())) }

        // Puts an own item (coffee, toilet paper) on the list from this week on; answers with the week's list.
        post("/shopping/extras") {
            call.respondList(call.user.shopping.ownItems.addExtra(call.week(), call.receive<ShoppingExtraInput>()), HttpStatusCode.Created)
        }

        // Takes an own item off every list; answers with the week's list.
        delete("/shopping/extras/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Own item id must be a number")
            call.respondList(call.user.shopping.ownItems.deleteExtra(call.week(), id))
        }

        // PUT hides the buy-ahead or stock-up group of the week's list, DELETE shows it again.
        for (dismissed in listOf(true, false)) {
            route("/shopping/dismissed/{group}", if (dismissed) HttpMethod.Put else HttpMethod.Delete) {
                handle {
                    val group = ShoppingAdviceGroup.fromPath(call.parameters["group"])
                        ?: throw IllegalArgumentException("group must be one of ${ShoppingAdviceGroup.entries.joinToString { it.path }}")
                    call.respondList(call.user.shopping.ticking.setDismissed(call.week(), group, dismissed))
                }
            }
        }
    }
}
