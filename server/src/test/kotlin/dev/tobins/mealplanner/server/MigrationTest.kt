package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.server.db.Database
import java.sql.DriverManager
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationTest {
    private fun columns(url: String, table: String): List<String> = DriverManager.getConnection(url).use { connection ->
        connection.createStatement().executeQuery("PRAGMA table_info($table)").use { rows ->
            generateSequence { if (rows.next()) rows.getString("name") else null }.toList()
        }
    }

    private fun version(url: String): Int = DriverManager.getConnection(url).use { connection ->
        connection.createStatement().executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
    }

    @Test
    fun aVersionOneDatabaseIsMigratedToTheCurrentSchema() {
        val file = createTempFile("v1", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        val v1 = checkNotNull(javaClass.getResource("/schema/v1.sql")).readText()
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                // Comments go first: some contain semicolons. The schema has no "--" inside strings.
                v1.lines().joinToString("\n") { it.substringBefore("--") }
                    .split(";").map { it.trim() }.filter { it.isNotEmpty() }
                    .forEach(statement::execute)
                statement.execute("INSERT INTO recipe (source, section, title, tagsJson, instructions, hasIngredientsBlock, problemsJson, kind) VALUES ('MANUAL', 'own', 'Old', '[]', '', 1, '[]', 'MEAL')")
                statement.execute("PRAGMA user_version = 1")
            }
        }

        val (driver, database) = openDatabase(url)
        driver.close()

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        val tables = listOf(
            "appUser", "userMeta", "recipe", "recipeIngredient", "ingredient", "ingredientAlias", "onHand",
            "ingredientPrice", "planMeal", "shoppingCheck", "offer", "foodLog", "weight",
        )
        for (table in tables) {
            assertEquals(columns(fresh, table), columns(url, table), "table $table after migration")
        }
        val (_, reopened) = openDatabase(url)
        // Everything from before accounts belongs to account 1, which nobody has claimed yet.
        val owner = reopened.userQueries.selectUnclaimed().executeAsOne()
        assertEquals(1L, owner.id)
        assertTrue(reopened.recipeQueries.selectActive(owner.id).executeAsList().single().rating == null, "existing rows survive")
    }

    @Test
    fun aVersionFourDatabaseKeepsItsRowsAndLooksUpPricesAgainForTheirCategory() {
        val file = createTempFile("v4", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        // Version 4 is today's schema without the shop section columns.
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE ingredient DROP COLUMN section")
                statement.execute("ALTER TABLE ingredientPrice DROP COLUMN category")
                statement.execute("ALTER TABLE ingredientPrice DROP COLUMN subCategory")
                statement.execute("INSERT INTO appUser (id, subject, createdAt) VALUES (1, 'sub', '2026-11-01T00:00:00Z')")
                statement.execute("INSERT INTO ingredient (userId, name, isStaple) VALUES (1, 'Gouda', 1)")
                statement.execute("INSERT INTO onHand (ingredientId, addedAt) VALUES (1, '2026-11-01T00:00:00Z')")
                statement.execute("INSERT INTO ingredientPrice (name, store, product, eurosPerKg, fetchedAt) VALUES ('Gouda', 'AH', 'AH Gouda', 12.0, '2026-11-14T00:00:00Z')")
                statement.execute("INSERT INTO meta (key, value) VALUES ('pricesFetchedAt', '2026-11-14T00:00:00Z'), ('other', 'kept')")
                statement.execute("PRAGMA user_version = 4")
            }
        }

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        for (table in listOf("ingredient", "ingredientPrice")) {
            assertEquals(columns(fresh, table), columns(url, table), "table $table after migration")
        }
        val gouda = db.ingredientQueries.selectIngredientByName(1, "Gouda").executeAsOne()
        assertEquals(null, gouda.section, "automatic until set by hand")
        assertEquals(1L, gouda.isStaple)
        assertEquals(1, db.pantryQueries.selectOnHand(1).executeAsList().size)
        val price = db.priceQueries.selectPrices().executeAsOne()
        assertEquals(12.0, price.eurosPerKg, "the price stays until the lookup replaces it")
        assertEquals(null, price.category)
        assertEquals(null, db.foodQueries.selectMeta("pricesFetchedAt").executeAsOneOrNull(), "so the next check looks prices up again")
        assertEquals("kept", db.foodQueries.selectMeta("other").executeAsOneOrNull())
        driver.close()
    }
}
