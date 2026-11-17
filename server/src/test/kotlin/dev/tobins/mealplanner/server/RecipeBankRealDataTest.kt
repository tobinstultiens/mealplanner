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
 * Migrates a copy of a real database across the recipe bank (16.sqm) and checks that every table keeps its
 * rows, every recipe what it had, and that what each account listed before is what it lists after: the
 * recipes on the site or made in the app are added and in the rotation, those that had left the site are
 * not. Prints row counts and integrity checks. Runs only when RECIPES_DB names a database file; that file
 * is copied first and never opened itself:
 * `RECIPES_DB=/path/to/mealplanner.db ./gradlew :server:test --tests '*RecipeBankRealDataTest*' -i`
 */
class RecipeBankRealDataTest {
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

    /** Every recipe in full, by the columns it had before the bank. */
    private fun recipes(url: String) = query(
        url,
        "SELECT id || '|' || userId || '|' || source || '|' || coalesce(sourcePath, 'NULL') || '|' || coalesce(contentHash, 'NULL') || '|' || " +
            "title || '|' || kind || '|' || coalesce(mealSlotsJson, 'NULL') || '|' || coalesce(removedAt, 'NULL') || '|' || " +
            "coalesce(rating, 'NULL') || '|' || coalesce(imageVersion, 'NULL') FROM recipe ORDER BY id",
    )

    @Test
    fun aCopyOfARealDatabaseKeepsEveryRecipeItShowsAddedAndInTheRotation() {
        val original = System.getenv("RECIPES_DB")?.let(::File) ?: return
        val copy = File(createTempDirectory("recipes").toFile(), "copy.db")
        original.copyTo(copy)
        val url = "jdbc:sqlite:${copy.path}"
        println("As copied: version ${version(url)}, checks ${checks(url)}, rows ${counts(url)}")
        migrate(url, BANK_VERSION - 1)
        val before = counts(url)
        val recipes = recipes(url)
        // What each account listed before the bank: its recipes that had not left the site.
        val listed = query(url, "SELECT userId || ':' || id FROM recipe WHERE removedAt IS NULL ORDER BY userId, id")
        println("Before the bank: version ${version(url)}, checks ${checks(url)}, rows $before, listed ${listed.size}")

        migrate(url, BANK_VERSION)
        val after = counts(url)
        println("After: version ${version(url)}, checks ${checks(url)}, rows $after")
        assertEquals(listOf("ok") to emptyList(), checks(url))
        assertEquals(before, after, "every table keeps its rows")
        assertEquals(recipes, recipes(url), "every recipe as it was")
        assertEquals(listed, query(url, "SELECT userId || ':' || id FROM recipe WHERE addedAt IS NOT NULL AND inRotation = 1 ORDER BY userId, id"))
        assertEquals(listOf("0"), query(url, "SELECT count(*) FROM recipe WHERE (addedAt IS NULL) != (inRotation = 0)"), "out of the rotation exactly when in the bank")

        // The server opens it as usual afterwards.
        openDatabase(url).first.close()
        assertEquals(Database.Schema.version, version(url))
    }

    private companion object {
        /** The version that has the recipe bank (16.sqm). */
        const val BANK_VERSION = 17L
    }
}
