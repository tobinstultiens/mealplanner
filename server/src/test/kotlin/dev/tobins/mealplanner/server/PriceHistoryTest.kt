package dev.tobins.mealplanner.server

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.db.OfferHistory
import dev.tobins.mealplanner.server.offers.OfferSource
import dev.tobins.mealplanner.server.prices.PriceSource
import dev.tobins.mealplanner.server.prices.StoreProduct
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PriceHistoryTest {
    private class FakeSource(override val store: Store, var offers: List<Offer>? = emptyList()) : OfferSource {
        override suspend fun fetch(): List<Offer> = offers ?: error("${store.label} is down")
    }

    private val opened = openDatabase("jdbc:sqlite::memory:")
    private val db: Database = opened.second

    /** Nightly refreshes from Monday 16 November 2026, 03:00 UTC. */
    private fun night(n: Int): Instant = Instant.parse("2026-11-16T03:00:00Z").plus(Duration.ofDays(n.toLong()))

    private fun offer(title: String, deal: String? = "25% korting", price: Double? = null, originalPrice: Double? = null, packSize: String? = null, store: Store = Store.AH) =
        Offer(store, title, deal, price, originalPrice, packSize, validFrom = "2026-11-16", validUntil = "2026-11-29")

    private fun history(): List<OfferHistory> = db.priceHistoryQueries.selectOfferHistory().executeAsList()

    private fun refresh(source: FakeSource, n: Int) = runBlocking { OfferRepository(db, listOf(source)).refresh(now = night(n)) }

    /** Each product's periods in the history, by title: when each was first and last seen, as nights. */
    private fun periods(): Map<String, List<Pair<Int, Int>>> {
        fun nightOf(seen: String) = Duration.between(night(0), Instant.parse(seen)).toDays().toInt()
        return history().groupBy({ it.title }, { nightOf(it.firstSeen) to nightOf(it.lastSeen) })
    }

    /** The titles of the store offers stored now, and whether the last refresh of each store succeeded. */
    private fun stored(): Pair<List<String>, List<Pair<String?, String?>>> =
        db.planQueries.selectOffersValidOn("2026-11-18").executeAsList().map { it.title } to
            db.planQueries.selectSources().executeAsList().map { it.fetchedAt to it.error }

    /** The warnings [logger] logs while [block] runs. */
    private fun warningsOf(logger: Class<*>, block: () -> Unit): List<String> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val log = LoggerFactory.getLogger(logger) as Logger
        log.addAppender(appender)
        try {
            block()
        } finally {
            log.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

    @Test
    fun anOfferWithDatesTheHistoryCannotReadIsLeftOutOfItAndCostsTheStoreNoOffers() {
        val ah = FakeSource(Store.AH, listOf(offer("Kaas"), offer("Ham").copy(validUntil = "29-11-2026")))
        val warned = warningsOf(PriceHistoryRepository::class.java) { refresh(ah, 0) }

        assertEquals(listOf("Ham", "Kaas") to listOf(night(0).toString() to null), stored(), "the store's offers are refreshed")
        assertEquals(2, db.planQueries.countOffers().executeAsOne().n, "both offers are stored, as always")
        assertEquals(mapOf("Kaas" to listOf(0 to 0)), periods(), "the rest of the store is recorded")
        assertTrue(warned.single().contains("Ham, 2026-11-16 to 29-11-2026"), "$warned")

        // The next good refresh records normally: no gap in what was recorded, nothing twice.
        ah.offers = listOf(offer("Kaas"), offer("Ham"))
        refresh(ah, 1)
        assertEquals(mapOf("Ham" to listOf(1 to 1), "Kaas" to listOf(0 to 1)), periods())
    }

    @Test
    fun aFailureToRecordTheHistoryIsLoggedAndLeavesTheStoresOffersRefreshed() {
        val ah = FakeSource(Store.AH, listOf(offer("Kaas")))
        refresh(ah, 0)
        // Whatever the history cannot write (here: the database refuses every new row) is the history's alone.
        opened.first.execute(null, "CREATE TRIGGER broken BEFORE INSERT ON offerHistory BEGIN SELECT RAISE(ABORT, 'no new rows'); END", 0)
        ah.offers = listOf(offer("Kaas"), offer("Ham"))
        val warned = warningsOf(OfferRepository::class.java) { refresh(ah, 1) }

        assertEquals(listOf("Ham", "Kaas") to listOf(night(1).toString() to null), stored(), "the offers are refreshed")
        assertTrue(warned.single().contains("price history"), "$warned")
        assertEquals(mapOf("Kaas" to listOf(0 to 0)), periods(), "nothing of that refresh is recorded, not even what continued")

        // The next good refresh continues from the last recording: no false gap, no row twice.
        opened.first.execute(null, "DROP TRIGGER broken", 0)
        refresh(ah, 2)
        assertEquals(mapOf("Ham" to listOf(2 to 2), "Kaas" to listOf(0 to 2)), periods())
    }

    @Test
    fun theSameOffersRefreshedThreeTimesAreOneRowPerProduct() {
        val ah = FakeSource(Store.AH, listOf(offer("AH Kipfilet"), offer("AH Gouda", "1+1 gratis")))
        repeat(3) { refresh(ah, it) }

        val rows = history()
        assertEquals(listOf("ah gouda", "ah kipfilet"), rows.map { it.productKey })
        assertTrue(rows.all { it.firstSeen == "2026-11-16T03:00:00Z" && it.lastSeen == "2026-11-18T03:00:00Z" }, rows.toString())
    }

    @Test
    fun onlyChangesWriteRowsAndAnOfferThatEndsGetsNone() {
        val ah = FakeSource(Store.AH, listOf(offer("Kaas", "voor 3,00", 3.0), offer("Ham", "2+1 gratis"), offer("Brood"), offer("Melk")))
        refresh(ah, 0)
        refresh(ah, 1)
        ah.offers = listOf(offer("Kaas", "voor 2,50", 2.5), offer("Ham", "1+1 gratis"), offer("Brood"))
        refresh(ah, 2)

        val rows = history()
        assertEquals(6, rows.size, "four products, two of them changed")
        fun periods(title: String) = rows.filter { it.title == title }.map { it.firstSeen to it.lastSeen }
        val (first, second, third) = listOf(0, 1, 2).map { night(it).toString() }
        assertEquals(listOf(first to second, third to third), periods("Kaas"))
        assertEquals(listOf(first to second, third to third), periods("Ham"))
        assertEquals(listOf("2+1 gratis", "1+1 gratis"), rows.filter { it.title == "Ham" }.map { it.deal })
        assertEquals(listOf(first to third), periods("Brood"))
        assertEquals(listOf(first to second), periods("Melk"), "ended: it keeps its last seen")
    }

    @Test
    fun anOfferThatComesBackAfterAGapIsANewPeriod() {
        val ah = FakeSource(Store.AH, listOf(offer("Kaas"), offer("Ham")))
        refresh(ah, 0)
        ah.offers = listOf(offer("Ham"))
        refresh(ah, 1)
        ah.offers = listOf(offer("Kaas"), offer("Ham"))
        refresh(ah, 2)

        val kaas = history().filter { it.title == "Kaas" }.map { it.firstSeen to it.lastSeen }
        assertEquals(listOf(night(0).toString() to night(0).toString(), night(2).toString() to night(2).toString()), kaas)
    }

    @Test
    fun aFailedOrEmptyRefreshEndsNoOffer() {
        val ah = FakeSource(Store.AH, listOf(offer("Kaas")))
        refresh(ah, 0)
        ah.offers = null
        refresh(ah, 1)
        ah.offers = emptyList()
        refresh(ah, 2)
        assertEquals(listOf(night(0).toString()), history().map { it.lastSeen }, "nothing recorded while the store was down or empty")
        ah.offers = listOf(offer("Kaas"))
        refresh(ah, 3)

        val row = history().single()
        assertEquals(night(0).toString() to night(3).toString(), row.firstSeen to row.lastSeen)
    }

    @Test
    fun storesAreRecordedApartAndTheirFailuresStayApart() {
        val ah = FakeSource(Store.AH, listOf(offer("Kaas")))
        val jumbo = FakeSource(Store.JUMBO, listOf(offer("Kaas", store = Store.JUMBO)))
        val offers = OfferRepository(db, listOf(ah, jumbo))
        runBlocking { offers.refresh(now = night(0)) }
        jumbo.offers = null
        runBlocking { offers.refresh(now = night(1)) }
        jumbo.offers = listOf(offer("Kaas", store = Store.JUMBO))
        runBlocking { offers.refresh(now = night(2)) }

        assertEquals(listOf("AH" to night(2).toString(), "JUMBO" to night(2).toString()), history().map { it.store to it.lastSeen })
    }

    @Test
    fun aRefreshNotLaterThanTheLastOneRecordsNothing() {
        val ah = FakeSource(Store.AH, listOf(offer("Kaas")))
        refresh(ah, 1)
        ah.offers = listOf(offer("Ham"))
        refresh(ah, 0)
        refresh(ah, 1)
        assertEquals(listOf("Kaas"), history().map { it.title })
    }

    @Test
    fun eachDealIsStoredWithItsKindNumbersPacksAndShare() {
        val ah = FakeSource(
            Store.AH,
            listOf(
                offer("Lager", null, price = 4.29, packSize = "500 g"),
                offer("Procent", "25% korting"),
                offer("Euro", "1,00 korting", originalPrice = 4.0),
                offer("Twee voor", "2 voor 3,00", packSize = "500 g"),
                offer("Een plus een", "1+1 gratis"),
                offer("Twee plus een", "2+1 gratis"),
                offer("Halve prijs", "2e halve prijs"),
                offer("Per kilo", "1,09 per 500 gram · Alleen in de winkel"),
                offer("Stapel", "2 stapelen tot 35%"),
            ),
        )
        refresh(ah, 0)

        val rows = history().associateBy { it.title }
        fun row(title: String) = rows.getValue(title).let { listOf(it.dealKind, it.dealPacks, it.dealFree, it.dealPercent, it.dealEuros) }
        assertEquals(listOf("LOWER_PRICE", 1L, null, null, 4.29), row("Lager"))
        assertEquals(listOf("PERCENT_OFF", 1L, null, 25.0, null), row("Procent"))
        assertEquals(listOf("AMOUNT_OFF", 1L, null, null, 1.0), row("Euro"))
        assertEquals(listOf("N_FOR_PRICE", 2L, null, null, 3.0), row("Twee voor"))
        assertEquals(listOf("X_PLUS_Y_FREE", 2L, 1L, null, null), row("Een plus een"))
        assertEquals(listOf("X_PLUS_Y_FREE", 3L, 1L, null, null), row("Twee plus een"))
        assertEquals(listOf("NTH_HALF_PRICE", 2L, null, null, null), row("Halve prijs"))
        assertEquals("PRICE_PER_KG", row("Per kilo")[0])
        near(2.18, rows.getValue("Per kilo").dealEuros)
        // "Up to" 35 % is no firm percentage, but stacking two says the deal wants two packs.
        assertEquals(listOf("UNKNOWN", 2L, null, null, null), row("Stapel"))

        fun share(title: String) = rows.getValue(title).paidShare
        near(0.75, share("Procent"))
        near(0.75, share("Euro"))
        near(0.5, share("Een plus een"))
        near(0.667, share("Twee plus een"))
        near(0.75, share("Halve prijs"))
        assertNull(share("Twee voor"), "a price, with no price before to compare with")
        near(3.0, rows.getValue("Twee voor").eurosPerKg)
        near(8.58, rows.getValue("Lager").eurosPerKg)
        val unknown = rows.getValue("Stapel")
        assertEquals("2 stapelen tot 35%", unknown.deal, "the store's text")
        assertEquals(listOf(null, null, null), listOf(unknown.paidShare, unknown.eurosPerKg, unknown.eurosPerPiece))
    }

    @Test
    fun theIngredientAnOfferMatchesIsKeptByNameFromAnyAccount() {
        val foods = FoodRepository(db)
        val users = UserRepository(db)
        IngredientRepository(db, foods, users.resolve(Identity("a", "alice")).id).create(IngredientInput("Kipfilet"))
        IngredientRepository(db, foods, users.resolve(Identity("b", "bob")).id).create(IngredientInput("Gouda"))
        val ah = FakeSource(Store.AH, listOf(offer("AH Kipfilet 500 gram"), offer("AH Jong belegen Gouda"), offer("AH Wasmiddel")))
        refresh(ah, 0)
        assertEquals(mapOf("AH Kipfilet 500 gram" to "Kipfilet", "AH Jong belegen Gouda" to "Gouda", "AH Wasmiddel" to null), history().associate { it.title to it.ingredient })
    }

    @Test
    fun aRunningPeriodGetsItsIngredientOnceACatalogueHasIt() {
        val ah = FakeSource(Store.AH, listOf(offer("AH Kipfilet 500 gram")))
        refresh(ah, 0)
        assertNull(history().single().ingredient)
        IngredientRepository(db, FoodRepository(db), UserRepository(db).resolve(Identity("a", "alice")).id).create(IngredientInput("Kipfilet"))
        refresh(ah, 1)
        assertEquals("Kipfilet" to night(1).toString(), history().single().let { it.ingredient to it.lastSeen })
    }

    @Test
    fun rowsLastSeenOverTwoYearsAgoArePruned() {
        val ah = FakeSource(Store.AH, listOf(offer("Old").copy(validUntil = "9999-12-31")))
        runBlocking { OfferRepository(db, listOf(ah)).refresh(now = Instant.parse("2024-11-01T03:00:00Z")) }
        ah.offers = listOf(offer("Recent").copy(validUntil = "9999-12-31"))
        runBlocking { OfferRepository(db, listOf(ah)).refresh(now = Instant.parse("2024-11-30T03:00:00Z")) }
        ah.offers = listOf(offer("Now"))
        refresh(ah, 0)
        assertEquals(listOf("Now", "Recent"), history().map { it.title }.sorted())
    }

    private class FakeStore(var products: List<StoreProduct>) : PriceSource {
        override val store = Store.AH
        override suspend fun search(term: String) = products
    }

    private fun lookUp(store: FakeStore, at: Instant) = runBlocking {
        PriceRepository(db, store, FoodRepository(db), OfferRepository(db, emptyList())).refresh(now = at, pause = Duration.ZERO)
    }

    @Test
    fun aRegularPriceIsANewRowOnlyWhenItOrTheProductChanges() {
        IngredientRepository(db, FoodRepository(db), UserRepository(db).resolve(Identity("a", "alice")).id).create(IngredientInput("Kipfilet"))
        val store = FakeStore(listOf(StoreProduct("AH Kipfilet", "500 g", 6.0, null, null)))
        lookUp(store, night(0))
        lookUp(store, night(7))
        fun rows() = db.priceHistoryQueries.selectRegularPriceHistory().executeAsList().map { Triple(it.packPrice, it.firstSeen, it.lastSeen) }
        assertEquals(listOf(Triple(6.0, night(0).toString(), night(7).toString())), rows(), "unchanged: one row")

        store.products = listOf(StoreProduct("AH Kipfilet", "500 g", 6.5, null, null))
        lookUp(store, night(14))
        assertEquals(listOf(Triple(6.0, night(0).toString(), night(7).toString()), Triple(6.5, night(14).toString(), night(14).toString())), rows())

        // Not found for a week, then found again at the same price: a new period, so the gap shows.
        store.products = emptyList()
        lookUp(store, night(21))
        store.products = listOf(StoreProduct("AH Kipfilet", "500 g", 6.5, null, null))
        lookUp(store, night(28))
        assertEquals(listOf(night(14).toString() to night(14).toString(), night(28).toString() to night(28).toString()), rows().drop(1).map { it.second to it.third })
    }

    private fun near(expected: Double, actual: Double?) = assertTrue(actual != null && abs(expected - actual) < 0.001, "expected $expected, got $actual")
}
