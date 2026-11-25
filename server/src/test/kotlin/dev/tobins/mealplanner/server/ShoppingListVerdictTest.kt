package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.SectionChoice
import dev.tobins.mealplanner.api.ShoppingAdvice
import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.offers.OfferSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The stock-up advice of the shopping list goes by the price verdict (issue 118, step d), end to end: from the list's
 * route through a price history recorded night by night, as the server records it, to the advice. AH offers rice 1+1
 * in three of the nine weeks before this one and 2+1 this week, which is worse than usual; Jumbo has 10% off, with no
 * history, which saves less.
 */
class ShoppingListVerdictTest {
    private val today = serverToday()
    private val monday = today.with(DayOfWeek.MONDAY)
    private val week = monday.toString()

    /** The history starts on the Monday nine weeks back; night [n] is [n] days after it, at 03:00 UTC. */
    private val base = monday.minusWeeks(9)
    private val nightsBefore = (today.toEpochDay() - base.toEpochDay()).toInt()

    private fun night(n: Int): Instant = base.plusDays(n.toLong()).atStartOfDay().toInstant(ZoneOffset.UTC).plus(Duration.ofHours(3))

    /** AH's offers on night [night]: rice 1+1 in weeks 1, 4 and 7, and 2+1 from week 9, valid for each Monday to Sunday. */
    private class Ah(private val base: LocalDate) : OfferSource {
        override val store = Store.AH
        var night = 0
        override suspend fun fetch(): List<Offer> {
            val monday = base.plusDays(night - night % 7L)
            val until = monday.plusDays(6).toString()
            val deal = if (night >= 63) "2+1 gratis" else if (night / 7 in listOf(1, 4, 7)) "1+1 gratis" else null
            val rice = deal?.let { Offer(Store.AH, "AH Rijst 1 kg", it, packSize = "1 kg", validFrom = monday.toString(), validUntil = until) }
            // Something on offer every night, so that every night is a night the store was read.
            return listOfNotNull(rice, Offer(Store.AH, "AH Jong belegen kaas", "25% korting", validFrom = monday.toString(), validUntil = until))
        }
    }

    /** A started server with Alice's staple rice and, when [recorded], the nights of history up to yesterday. */
    private suspend fun ApplicationTestBuilder.start(recorded: Boolean): HttpClient {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        val db = openDatabase(config.databaseUrl).second
        val ah = Ah(base)
        val jumbo = object : OfferSource {
            override val store = Store.JUMBO
            override suspend fun fetch() = listOf(Offer(Store.JUMBO, "Jumbo Rijst 1 kg", "10% korting", packSize = "1 kg", validFrom = week, validUntil = monday.plusDays(6).toString()))
        }
        if (recorded) {
            val history = OfferRepository(db, listOf(ah))
            for (n in 0 until nightsBefore) {
                ah.night = n
                history.refresh(now = night(n))
            }
        }
        ah.night = nightsBefore
        application { module(config, db, offerSources = listOf(ah, jumbo), priceSource = null, generator = null, verifier = TestTokens) }
        val http = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest {
                bearerAuth("alice")
                contentType(ContentType.Application.Json)
            }
        }
        val rice = http.post("/api/ingredients") { setBody(IngredientInput("Rijst", pricePerKg = 3.0)) }.body<Ingredient>()
        http.put("/api/ingredients/${rice.id}/section") { setBody(SectionChoice("WORLD")) }
        http.put("/api/pantry/staples/${rice.id}")
        http.post("/api/offers/refresh")
        return http
    }

    private suspend fun HttpClient.list() = get("/api/plans/$week/shopping").body<ShoppingList>()

    /** The stores of the offers the advice shows, for each stock-up row. */
    private fun ShoppingList.advisedAt() = stockUp.map { row -> row.name to row.offers.map { it.store } }

    @Test
    fun anOfferWorseThanUsualIsLeftOutOfStockUpAndTheNextBestOneAdvised() = testApplication {
        val http = start(recorded = true)
        // AH's 2+1 saves most, but is worse than the 1+1 it had before: Jumbo's is advised.
        assertEquals(listOf("Rijst" to listOf(Store.JUMBO)), http.list().advisedAt())
    }

    @Test
    fun withoutAHistoryTheBestOfferIsAdvisedAsBefore() = testApplication {
        val http = start(recorded = false)
        assertEquals(Store.AH, http.list().stockUp.single().offers.first().store)
    }

    @Test
    fun aDismissedOrSwitchedOffStockUpGivesNoAdviceAndComesBackAsItWas() = testApplication {
        val http = start(recorded = true)
        http.put("/api/plans/$week/shopping/dismissed/${ShoppingAdviceGroup.STOCK_UP.path}")
        assertEquals(emptyList(), http.list().stockUp)
        http.delete("/api/plans/$week/shopping/dismissed/${ShoppingAdviceGroup.STOCK_UP.path}")
        assertEquals(listOf("Rijst" to listOf(Store.JUMBO)), http.list().advisedAt())
        http.put("/api/shopping-advice") { setBody(ShoppingAdvice(stockUp = false)) }
        assertEquals(emptyList(), http.list().stockUp)
    }
}
