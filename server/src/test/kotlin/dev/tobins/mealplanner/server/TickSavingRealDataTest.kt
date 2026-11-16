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
 * Migrates a copy of a real database across the ticks' kept saving (15.sqm) and checks that every table
 * keeps its rows and every tick what it had, with nothing known of what it saved. Prints row counts and
 * integrity checks. Runs only when TICKS_DB names a database file; that file is copied first and never
 * opened itself:
 * `TICKS_DB=/path/to/mealplanner.db ./gradlew :server:test --tests '*TickSavingRealDataTest*' -i`
 */
class TickSavingRealDataTest {
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

    /** Every tick in full, by the columns it had before the saving was kept. */
    private fun ticks(url: String) = query(
        url,
        "SELECT userId || '|' || weekStart || '|' || itemKey || '|' || coalesce(name, 'NULL') || '|' || coalesce(grams, 'NULL') || '|' || " +
            "coalesce(pieces, 'NULL') FROM shoppingCheck ORDER BY userId, weekStart, itemKey",
    )

    @Test
    fun aCopyOfARealDatabaseKeepsEveryTickWithNothingKnownOfWhatItSaved() {
        val original = System.getenv("TICKS_DB")?.let(::File) ?: return
        val copy = File(createTempDirectory("ticks").toFile(), "copy.db")
        original.copyTo(copy)
        val url = "jdbc:sqlite:${copy.path}"
        println("As copied: version ${version(url)}, checks ${checks(url)}, rows ${counts(url)}")
        migrate(url, SAVING_VERSION - 1)
        val before = counts(url)
        val ticks = ticks(url)
        println("Before the saving was kept: version ${version(url)}, checks ${checks(url)}, rows $before")

        migrate(url, SAVING_VERSION)
        val after = counts(url)
        println("After: version ${version(url)}, checks ${checks(url)}, rows $after")
        assertEquals(listOf("ok") to emptyList(), checks(url))
        assertEquals(before, after, "every table keeps its rows")
        assertEquals(ticks, ticks(url), "every tick as it was")
        assertEquals(listOf("0"), query(url, "SELECT count(saving) + count(savingStore) FROM shoppingCheck"), "nothing known of what a tick saved")

        // The server opens it as usual afterwards.
        openDatabase(url).first.close()
        assertEquals(Database.Schema.version, version(url))
    }

    private companion object {
        /** The version that keeps what a tick's offer saved (15.sqm). */
        const val SAVING_VERSION = 16L
    }
}
