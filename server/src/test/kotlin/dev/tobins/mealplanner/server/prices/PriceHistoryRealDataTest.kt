package dev.tobins.mealplanner.server.prices

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.openDatabase
import dev.tobins.mealplanner.server.prices.offers.OfferSource
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
 *
 * The database may be of any version up to the current one; each migration that touches the price history
 * is compared on its own, if the database is from before it.
 */
class PriceHistoryRealDataTest {
    private fun query(url: String, sql: String): List<String> = DriverManager.getConnection(url).use { connection ->
        connection.createStatement().executeQuery(sql).use { rows ->
            generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
        }
    }

    private fun tables(url: String) = query(url, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")

    private fun counts(url: String): Map<String, Long> = tables(url).associateWith { query(url, "SELECT count(*) FROM \"$it\"").single().toLong() }

    private fun checks(url: String) = query(url, "PRAGMA integrity_check") to query(url, "PRAGMA foreign_key_check")

    private fun version(url: String) = query(url, "PRAGMA user_version").single().toLong()

    private fun columns(url: String, table: String) = DriverManager.getConnection(url).use { connection ->
        connection.createStatement().executeQuery("PRAGMA table_info($table)").use { rows ->
            generateSequence { if (rows.next()) rows.getString("name") else null }.toList()
        }
    }

    /** The price history's rows in full, as text, by the columns its tables have now, so that a migration can be compared. */
    private fun historyRows(url: String): () -> Map<String, List<String>> {
        val columns = listOf("offerHistory", "regularPriceHistory").filter { it in tables(url) }.associateWith { columns(url, it) }
        return {
            columns.mapValues { (table, names) ->
                query(url, "SELECT ${names.joinToString(" || '|' || ") { "coalesce($it, 'NULL')" }} FROM $table ORDER BY id")
            }
        }
    }

    /** Runs the migrations from the database's version up to [target], as [openDatabase] would; a newer one is left as it is. */
    private fun migrate(url: String, target: Long) {
        val from = version(url)
        if (from >= target) return
        val driver = JdbcSqliteDriver(url, Properties().apply { put("foreign_keys", "true") })
        Database.Schema.migrate(driver, from, target)
        driver.execute(null, "PRAGMA user_version = $target", 0)
        driver.close()
    }

    /** Migrates up to [target] alone and checks that no row of the tables there were before is lost or changed. */
    private fun compareMigration(url: String, target: Long) {
        if (version(url) >= target) return println("Already at version ${version(url)}: the migration to $target is not compared")
        migrate(url, target - 1)
        val before = counts(url)
        val rows = historyRows(url)
        val historyBefore = rows()
        println("Before version $target: checks ${checks(url)}, rows $before")
        migrate(url, target)
        val after = counts(url)
        println("After: version ${version(url)}, checks ${checks(url)}, rows $after")
        for ((table, n) in before) assertEquals(n, after[table], "rows of $table")
        assertEquals(historyBefore, rows(), "the history's rows, as they were")
        assertEquals(listOf("ok") to emptyList(), checks(url))
    }

    @Test
    fun aCopyOfARealDatabaseMigratesAndItsOffersRecordOnlyChanges() {
        val original = System.getenv("PRICE_HISTORY_DB")?.let(::File) ?: return
        val copy = File(createTempDirectory("price-history").toFile(), "copy.db")
        original.copyTo(copy)
        val url = "jdbc:sqlite:${copy.path}"
        println("As copied: version ${version(url)}, checks ${checks(url)}, rows ${counts(url)}")
        compareMigration(url, HISTORY_VERSION)
        compareMigration(url, VERDICT_VERSION)

        val (driver, db) = openDatabase(url)

        val stored = db.planQueries.selectOffersOverlapping(until = "9999-12-31", from = "0000-01-01").executeAsList().map {
            Offer(Store.valueOf(it.store), it.title, it.deal, it.price, it.originalPrice, it.packSize, it.category, it.validFrom, it.validUntil, it.url, imageUrl = it.imageUrl)
        }
        // After the last fetch and the last recording, on the day of the last fetch, so that every stored offer still runs.
        val first = (query(url, "SELECT max(fetchedAt) FROM offerSource") + query(url, "SELECT max(lastSeen) FROM offerHistory"))
            .filterNotNull().maxOf(Instant::parse).plusSeconds(60)
        val sources = stored.groupBy { it.store }.map { (store, offers) -> Replay(store, offers) }
        val offers = OfferRepository(db, sources)
        val history = { query(url, "SELECT count(*) FROM offerHistory").single() }
        for (n in 0 until 5) {
            val ms = measureTimeMillis { runBlocking { offers.refresh(now = first.plus(Duration.ofMinutes(n * 10L))) } }
            println("Refresh ${n + 1} of ${stored.size} offers: $ms ms, history rows ${history()}")
        }
        println("Deal kinds: " + query(url, "SELECT store || ' ' || dealKind || ' ' || count(*) FROM offerHistory GROUP BY store, dealKind ORDER BY store, count(*) DESC"))
        println("Unknown: " + query(url, "SELECT store || ' ' || coalesce(deal, '(none)') || ' ' || count(*) FROM offerHistory WHERE dealKind = 'UNKNOWN' GROUP BY store, deal"))
        println(
            "With a category, an address, a price per litre: " + query(
                url,
                "SELECT store || ' ' || count(category) || ' ' || count(url) || ' ' || count(eurosPerLitre) || ' of ' || count(*) FROM offerHistory GROUP BY store",
            ),
        )
        // A few offers change their price: only those get a row.
        sources.forEach { source -> source.offers = source.offers.mapIndexed { i, o -> if (i < 2) o.copy(price = (o.price ?: 1.0) + 0.1) else o } }
        val ms = measureTimeMillis { runBlocking { offers.refresh(now = first.plus(Duration.ofMinutes(60))) } }
        println("Refresh with 2 changed offers per store: $ms ms, history rows ${history()}")
        println("Recordings: " + query(url, "SELECT store || ' ' || seenAt || ' ' || offers FROM offerRecording ORDER BY store, seenAt"))
        driver.close()
    }

    private class Replay(override val store: Store, var offers: List<Offer>) : OfferSource {
        override suspend fun fetch() = offers
    }

    private companion object {
        /** The version the price history came with (12.sqm); later migrations move rows of their own (13.sqm: the stock). */
        const val HISTORY_VERSION = 13L

        /** The version that added the category, address and price per litre to the history, and its recordings (14.sqm). */
        const val VERDICT_VERSION = 15L
    }
}
