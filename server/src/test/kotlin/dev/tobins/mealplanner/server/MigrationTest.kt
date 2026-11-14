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
}
