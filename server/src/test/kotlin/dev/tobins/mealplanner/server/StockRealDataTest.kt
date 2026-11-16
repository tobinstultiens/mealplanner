package dev.tobins.mealplanner.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tobins.mealplanner.server.db.Database
import java.io.File
import java.sql.DriverManager
import java.util.Properties
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Migrates a copy of a real database across the stock's split per source (13.sqm) and checks that no row is
 * lost: every entry of the pantry is by hand or in stock afterwards, with its grams, and every use of the stock
 * is kept. Prints row counts, integrity checks and the sources given. Runs only when STOCK_DB names a database
 * file; that file is copied first and never opened itself:
 * `STOCK_DB=/path/to/mealplanner.db ./gradlew :server:test --tests '*StockRealDataTest*' -i`
 */
class StockRealDataTest {
    private fun query(url: String, sql: String): List<String> = DriverManager.getConnection(url).use { connection ->
        connection.createStatement().executeQuery(sql).use { rows ->
            generateSequence { if (rows.next()) rows.getString(1).orEmpty() else null }.toList()
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
    fun aCopyOfARealDatabaseKeepsEveryEntryOfItsPantryAndStock() {
        val original = System.getenv("STOCK_DB")?.let(::File) ?: return
        val copy = File(createTempDirectory("stock").toFile(), "copy.db")
        original.copyTo(copy)
        val url = "jdbc:sqlite:${copy.path}"
        println("As copied: version ${version(url)}, checks ${checks(url)}, rows ${counts(url)}")
        migrate(url, STOCK_VERSION - 1)
        val before = counts(url)
        println("Before the split: version ${version(url)}, checks ${checks(url)}, rows $before")
        val entries = query(url, "SELECT ingredientId || ':' || coalesce(fromWeek, '-') || ':' || coalesce(grams, '?') FROM onHand ORDER BY ingredientId")
        val uses = query(url, "SELECT userId || ':' || weekStart || ':' || day || ':' || slot || ':' || ingredientId || ':' || fromWeek || ':' || coalesce(grams, '?') FROM stockUse ORDER BY 1")

        migrate(url, STOCK_VERSION)
        val after = counts(url)
        println("After: version ${version(url)}, checks ${checks(url)}, rows $after")
        println("Sources: " + query(url, "SELECT source || ' ' || count(*) FROM stock GROUP BY source"))
        assertEquals(listOf("ok") to emptyList(), checks(url))
        for ((table, n) in before - "onHand") assertEquals(n, after[table], "rows of $table")
        assertEquals(before.getValue("onHand"), after.getValue("onHand") + after.getValue("stock"), "every entry by hand or in stock")
        val split = query(
            url,
            "SELECT ingredientId || ':-:?' FROM onHand UNION ALL " +
                "SELECT ingredientId || ':' || fromWeek || ':' || coalesce(grams, '?') FROM stock ORDER BY 1",
        )
        assertEquals(entries.sorted(), split.sorted(), "each with its week and grams")
        val kept = query(url, "SELECT userId || ':' || weekStart || ':' || day || ':' || slot || ':' || ingredientId || ':' || fromWeek || ':' || coalesce(grams, '?') FROM stockUse ORDER BY 1")
        assertEquals(uses, kept, "every use of the stock")

        // The server opens it as usual afterwards.
        openDatabase(url).first.close()
        assertEquals(Database.Schema.version, version(url))
    }

    private companion object {
        /** The version the stock was split per source with (13.sqm). */
        const val STOCK_VERSION = 14L
    }
}
