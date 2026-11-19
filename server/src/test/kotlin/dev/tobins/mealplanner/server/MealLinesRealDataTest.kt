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
 * Migrates a copy of a real database across adjusted meals (17.sqm) and a recipe's toppings (18.sqm), and
 * checks that every table keeps its rows and every planned meal what it had, with no line or topping of its
 * own yet. Prints row counts and integrity checks. Runs only when MEALS_DB names a database file; that file is copied first and never opened itself:
 * `MEALS_DB=/path/to/mealplanner.db ./gradlew :server:test --tests '*MealLinesRealDataTest*' -i`
 */
class MealLinesRealDataTest {
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

    /** Every planned meal in full, and what each took from the stock. */
    private fun meals(url: String) = query(
        url,
        "SELECT userId || '|' || weekStart || '|' || day || '|' || slot || '|' || recipeId || '|' || title || '|' || portions || '|' || " +
            "kcal || '|' || proteinG || '|' || leftover || '|' || locked || '|' || status FROM planMeal ORDER BY userId, weekStart, day, slot",
    ) + query(url, "SELECT userId || '|' || weekStart || '|' || day || '|' || slot || '|' || ingredientId || '|' || coalesce(grams, 'NULL') FROM stockUse ORDER BY 1")

    @Test
    fun aCopyOfARealDatabaseKeepsEveryMealAsItWas() {
        val original = System.getenv("MEALS_DB")?.let(::File) ?: return
        val copy = File(createTempDirectory("meals").toFile(), "copy.db")
        original.copyTo(copy)
        val url = "jdbc:sqlite:${copy.path}"
        println("As copied: version ${version(url)}, checks ${checks(url)}, rows ${counts(url)}")
        migrate(url, LINES_VERSION - 1)
        val before = counts(url)
        val meals = meals(url)
        println("Before adjusted meals: version ${version(url)}, checks ${checks(url)}, rows $before, meals and uses ${meals.size}")

        migrate(url, LINES_VERSION)
        val after = counts(url)
        println("After: version ${version(url)}, checks ${checks(url)}, rows $after")
        assertEquals(listOf("ok") to emptyList(), checks(url))
        assertEquals(before + ("mealLine" to 0L), after, "every table keeps its rows, and the new one starts empty")
        assertEquals(meals, meals(url), "every meal as it was")

        // The server opens it as usual afterwards.
        openDatabase(url).first.close()
        assertEquals(Database.Schema.version, version(url))
    }

    @Test
    fun aCopyOfARealDatabaseKeepsEveryRowWhenRecipesGetToppings() {
        val original = System.getenv("MEALS_DB")?.let(::File) ?: return
        val copy = File(createTempDirectory("toppings").toFile(), "copy.db")
        original.copyTo(copy)
        val url = "jdbc:sqlite:${copy.path}"
        migrate(url, TOPPINGS_VERSION - 1)
        val before = counts(url)
        val meals = meals(url)
        val lines = query(url, "SELECT count(*) FROM mealLine")
        println("Before toppings: version ${version(url)}, checks ${checks(url)}, rows $before")

        migrate(url, TOPPINGS_VERSION)
        val after = counts(url)
        println("After: version ${version(url)}, checks ${checks(url)}, rows $after")
        assertEquals(listOf("ok") to emptyList(), checks(url))
        assertEquals(before + ("recipeTopping" to 0L), after, "every table keeps its rows, and the new one starts empty")
        assertEquals(meals to lines, meals(url) to query(url, "SELECT count(*) FROM mealLine"), "every meal and its lines as they were")

        openDatabase(url).first.close()
        assertEquals(Database.Schema.version, version(url))
    }

    private companion object {
        /** The version that has adjusted meals (17.sqm). */
        const val LINES_VERSION = 18L

        /** The version that has a recipe's toppings (18.sqm). */
        const val TOPPINGS_VERSION = 19L
    }
}
