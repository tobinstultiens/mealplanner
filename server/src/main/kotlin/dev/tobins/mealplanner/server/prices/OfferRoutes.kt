package dev.tobins.mealplanner.server.prices

import dev.tobins.mealplanner.api.OfferExclusionRequest
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.serverDay
import dev.tobins.mealplanner.server.serverToday
import dev.tobins.mealplanner.server.user
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.time.Instant
import java.time.LocalDate
import dev.tobins.mealplanner.server.logged
import kotlinx.coroutines.launch

/** Store offers and regular prices, and fetching them now. */
fun Route.offerRoutes(offers: OfferRepository, prices: PriceRepository) {
    get("/api/offers") {
        val date = call.request.queryParameters["date"]?.let {
            runCatching { LocalDate.parse(it) }.getOrElse { throw IllegalArgumentException("date must be an ISO date") }
        } ?: serverToday()
        val state = call.user.ingredients.let { offers.state(date, it.matchTerms(), it.list().associate { i -> i.id to i.name }) }
        // A verdict is about today: the offers of another day go without.
        val now = Instant.now()
        call.respond(if (date == serverDay(now)) call.user.dealVerdicts.on(state, now) else state)
    }

    // Whether each of the account's offers is a good deal, by its price history, with what it rests on: for reading
    // the verdicts; the app gets them with the offers and the shopping list (Offer.verdict).
    get("/api/offers/verdicts") { call.respond(call.user.dealVerdicts.current(Instant.now())) }

    // "Not this ingredient": the offers the account said are not one of its ingredients. Each answers the account's list.
    get("/api/offers/exclusions") { call.respond(call.user.offerExclusions.list()) }
    put("/api/offers/exclusions") { call.respond(call.user.offerExclusions.exclude(call.receive<OfferExclusionRequest>())) }
    delete("/api/offers/exclusions") { call.respond(call.user.offerExclusions.takeBack(call.exclusionParameters())) }

    // Fetches every store now, regardless of when it was last fetched.
    post("/api/offers/refresh") { call.respond(offers.refresh()) }
    get("/api/prices") { call.respond(prices.state(call.user.ingredients)) }
    // A lookup takes minutes: it runs in the background and the app polls GET /api/prices.
    post("/api/prices/refresh") {
        call.application.launch { logged("Price lookup") { prices.refresh() } }
        call.respond(prices.state(call.user.ingredients).copy(refreshing = true))
    }
}

/** An exclusion named by the query parameters `store`, `title` and `ingredientId`, as `DELETE /api/offers/exclusions` takes it. */
private fun ApplicationCall.exclusionParameters(): OfferExclusionRequest {
    val parameters = request.queryParameters
    fun required(name: String) = parameters[name] ?: throw IllegalArgumentException("Query parameter $name is required")
    val store = required("store").let { name -> Store.entries.firstOrNull { it.name == name } ?: throw IllegalArgumentException("Unknown store $name") }
    val ingredientId = required("ingredientId").toLongOrNull() ?: throw IllegalArgumentException("ingredientId must be a number")
    return OfferExclusionRequest(store, required("title"), ingredientId)
}
