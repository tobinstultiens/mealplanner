package dev.tobins.mealplanner.server.routes

import dev.tobins.mealplanner.server.OfferRepository
import dev.tobins.mealplanner.server.PriceRepository
import dev.tobins.mealplanner.server.serverDay
import dev.tobins.mealplanner.server.serverToday
import dev.tobins.mealplanner.server.user
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
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

    // Fetches every store now, regardless of when it was last fetched.
    post("/api/offers/refresh") { call.respond(offers.refresh()) }
    get("/api/prices") { call.respond(prices.state(call.user.ingredients)) }
    // A lookup takes minutes: it runs in the background and the app polls GET /api/prices.
    post("/api/prices/refresh") {
        call.application.launch { logged("Price lookup") { prices.refresh() } }
        call.respond(prices.state(call.user.ingredients).copy(refreshing = true))
    }
}
