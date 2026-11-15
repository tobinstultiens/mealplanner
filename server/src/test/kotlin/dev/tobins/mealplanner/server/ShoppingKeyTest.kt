package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingList
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ShoppingKeyTest {
    /** The strings the app and `shoppingCheck` hold already; they must never change. */
    private val keys = mapOf(
        "i12" to ShoppingKey.Ingredient(12),
        "t:olive oil" to ShoppingKey.Text("olive oil"),
        "ahead:i7" to ShoppingKey.Ahead(7),
        "stock:i8" to ShoppingKey.Stock(8),
        "x:5" to ShoppingKey.Extra(5),
        "dismissed:buy-ahead" to ShoppingKey.Dismissed(ShoppingAdviceGroup.BUY_AHEAD),
        "dismissed:stock-up" to ShoppingKey.Dismissed(ShoppingAdviceGroup.STOCK_UP),
    )

    @Test
    fun eachKindIsTheStringItWasBefore() {
        for ((text, key) in keys) {
            assertEquals(text, key.toString())
            assertEquals(key, ShoppingKey.parse(text))
        }
    }

    @Test
    fun aTextKeyIsWhateverFollowsItsPrefix() {
        // A recipe line's text may look like another kind of key; it is still text.
        assertEquals(ShoppingKey.Text("ahead:i7"), ShoppingKey.parse("t:ahead:i7"))
        assertEquals(ShoppingKey.Text(""), ShoppingKey.parse("t:"))
    }

    @Test
    fun aKeyNoListHasIsRefused() {
        val unknown = listOf(
            "ahead:ix", "ahead:ichicken", "ahead:i", "stock:i1.5", "ix", "i", "", "x:", "x:coffee", "x5",
            "nonsense", "dismissed:everything", "dismissed:",
            // Another spelling of a key names no row: a tick under it would never show.
            "ahead:i007", "i+5", "i 5", "x:05",
        )
        for (text in unknown) {
            assertNull(ShoppingKey.parseOrNull(text), text)
            val refused = assertFailsWith<IllegalArgumentException>(text) { ShoppingKey.parse(text) }
            assertEquals("Unknown shopping item $text", refused.message)
        }
    }

    @Test
    fun aTickUnderAKeyNoListHasIsRefusedAndNothingIsStored() = testApplication {
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = null)
        val db = openDatabase(config.databaseUrl).second
        application { module(config, db, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        val http = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest {
                bearerAuth("alice")
                contentType(ContentType.Application.Json)
            }
        }
        val week = "2026-11-16"
        suspend fun tick(key: String) = http.put("/api/plans/$week/shopping") { setBody(ShoppingCheck(key, true)) }

        assertEquals(HttpStatusCode.BadRequest, tick("nonsense").status)
        assertEquals(HttpStatusCode.BadRequest, tick("ahead:ix").status)
        // A key of a row this week's list does not have (any more) is kept, as before.
        assertEquals(HttpStatusCode.OK, tick("i12").status)
        assertEquals(week, tick("t:olive oil").body<ShoppingList>().weekStart)

        val userId = db.userQueries.selectBySubject("sub-alice").executeAsOne().id
        assertEquals(setOf("i12", "t:olive oil"), db.shoppingQueries.selectChecks(userId, week).executeAsList().map { it.itemKey }.toSet())
    }
}
