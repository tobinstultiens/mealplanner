package dev.tobins.mealplanner.app.prices

import dev.tobins.mealplanner.api.OffersState
import dev.tobins.mealplanner.api.PricesState
import dev.tobins.mealplanner.app.ApiClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post

/*
 * The prices' server calls: offers and regular prices.
 * They are extensions of [ApiClient], which keeps the address, the login, the errors and the retries for all of them.
 */

suspend fun ApiClient.offers(date: String): OffersState = http.get("api/offers") { parameter("date", date) }.body()

suspend fun ApiClient.refreshOffers(): OffersState = http.post("api/offers/refresh").body()

suspend fun ApiClient.prices(): PricesState = http.get("api/prices").body()

/** Starts a lookup on the server; it runs for a few minutes, so poll [prices]. */
suspend fun ApiClient.refreshPrices(): PricesState = http.post("api/prices/refresh").body()
