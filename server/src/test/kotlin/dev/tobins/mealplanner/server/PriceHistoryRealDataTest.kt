package dev.tobins.mealplanner.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.offers.OfferSource
import kotlinx.coroutines.runBlocking
import java.io.File
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.Properties
import kotlin.io.path.createTempDirectory
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Migrates a copy of a real database and replays its stored offers through nightly refreshes, printing row
 * counts, integrity checks and how long each refresh takes. Runs only when PRICE_HISTORY_DB names a database
 * file; that file is copied first and never opened itself:
 * `PRICE_HISTORY_DB=/path/to/dev.db ./gradlew :server:test --tests '*PriceHistoryRealDataTest*' -i`
 */
class PriceHistoryRealDataTest {
    private fun query(url: String, sql: String): List<String> = DriverManager.getConnection(url).use { connection ->
        connection.createStatement().executeQuery(sql).use { rows ->
            generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
        }
    }

    private fun counts(url: String): Map<String, Long> =
        query(url, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .associateWith { query(url, "SELECT count(*) FROM \"$it\"").single().toLong() }

    private fun checks(url: String) = query(url, "PRAGMA integrity_check") to query(url, "PRAGMA foreign_key_check")

    private fun version(url: String) = query(url, "PRAGMA user_version").single().toLong()

    /** Runs the migrations from the database's version up to [target], as [openDatabase] would. */
    private fun migrate(url: String, target: Long) {
        val driver = JdbcSqliteDriver(url, Properties().apply { put("foreign_keys", "true") })
        val from = version(url)
        if (from < target) Database.Schema.migrate(driver, from, target)
        driver.execute(null, "PRAGMA user_version = $target", 0)
        driver.close()
    }

    @Test
    fun aCopyOfARealDatabaseMigratesAndItsOffersRecordOnlyChanges() {
        val original = System.getenv("PRICE_HISTORY_DB")?.let(::File) ?: return
        val copy = File(createTempDirectory("price-history").toFile(), "copy.db")
        original.copyTo(copy)
        val url = "jdbc:sqlite:${copy.path}"
        println("As copied: version ${version(url)}, checks ${checks(url)}, rows ${counts(url)}")
        // Up to the version before the last migration first, so that the last one is compared on its own.
        val last = Database.Schema.version
        migrate(url, last - 1)
        val before = counts(url)
        println("Before the last migration: version ${version(url)}, checks ${checks(url)}, rows $before")
        migrate(url, last)
        val after = counts(url)
        println("After: version ${version(url)}, checks ${checks(url)}, rows $after")
        for ((table, n) in before) assertEquals(n, after[table], "rows of $table")
        assertEquals(listOf("ok") to emptyList(), checks(url))

        val (driver, db) = openDatabase(url)

        val stored = db.planQueries.selectOffersOverlapping(until = "9999-12-31", from = "0000-01-01").executeAsList().map {
            Offer(Store.valueOf(it.store), it.title, it.deal, it.price, it.originalPrice, it.packSize, it.category, it.validFrom, it.validUntil, it.url, imageUrl = it.imageUrl)
        }
        // On the day the database was last fetched, so that every stored offer still runs.
        val first = Instant.parse(query(url, "SELECT max(fetchedAt) FROM offerSource").single())
        val sources = stored.groupBy { it.store }.map { (store, offers) -> Replay(store, offers) }
        val offers = OfferRepository(db, sources)
        val history = { if ("offerHistory" in counts(url)) query(url, "SELECT count(*) FROM offerHistory").single() else "no table" }
        for (n in 0 until 5) {
            val ms = measureTimeMillis { runBlocking { offers.refresh(now = first.plus(Duration.ofMinutes(n * 10L))) } }
            println("Refresh ${n + 1} of ${stored.size} offers: $ms ms, history rows ${history()}")
        }
        println("Deal kinds: " + query(url, "SELECT store || ' ' || dealKind || ' ' || count(*) FROM offerHistory GROUP BY store, dealKind ORDER BY store, count(*) DESC"))
        println("Unknown: " + query(url, "SELECT store || ' ' || coalesce(deal, '(none)') || ' ' || count(*) FROM offerHistory WHERE dealKind = 'UNKNOWN' GROUP BY store, deal"))
        // A few offers change their price: only those get a row.
        sources.forEach { source -> source.offers = source.offers.mapIndexed { i, o -> if (i < 2) o.copy(price = (o.price ?: 1.0) + 0.1) else o } }
        val ms = measureTimeMillis { runBlocking { offers.refresh(now = first.plus(Duration.ofMinutes(60))) } }
        println("Refresh with 2 changed offers per store: $ms ms, history rows ${history()}")
        driver.close()
    }

    private class Replay(override val store: Store, var offers: List<Offer>) : OfferSource {
        override suspend fun fetch() = offers
    }
}
