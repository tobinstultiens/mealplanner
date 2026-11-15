package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.server.db.Database
import java.sql.DriverManager
import java.sql.Statement
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

    /** Version 7 added the recipe pictures' columns (6.sqm); older versions are made from today's schema without them. */
    private fun dropRecipePictureColumns(statement: Statement) {
        statement.execute("ALTER TABLE recipe DROP COLUMN imageSourceHash")
        statement.execute("ALTER TABLE recipe DROP COLUMN imageVersion")
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
        // Version 4 is today's schema without the shop section and picture columns.
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropRecipePictureColumns(statement)
                statement.execute("ALTER TABLE offer DROP COLUMN imageUrl")
                statement.execute("ALTER TABLE ingredientPrice DROP COLUMN imageUrl")
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

    @Test
    fun aVersionFiveDatabaseKeepsItsOffersAndPricesWithoutPictures() {
        val file = createTempFile("v5", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        // Version 5 is today's schema without the picture columns.
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropRecipePictureColumns(statement)
                statement.execute("ALTER TABLE offer DROP COLUMN imageUrl")
                statement.execute("ALTER TABLE ingredientPrice DROP COLUMN imageUrl")
                statement.execute(
                    "INSERT INTO offer (store, title, deal, price, validFrom, validUntil, url) " +
                        "VALUES ('LIDL', 'Rundergehakt', NULL, 4.29, '2026-11-16', '2026-11-22', 'https://www.lidl.nl/p/rundergehakt/p1')",
                )
                statement.execute("INSERT INTO offerSource (store, fetchedAt) VALUES ('LIDL', '2026-11-15T00:00:00Z')")
                statement.execute(
                    "INSERT INTO ingredientPrice (name, store, product, eurosPerKg, fetchedAt, category, subCategory) " +
                        "VALUES ('Gouda', 'AH', 'AH Gouda', 12.0, '2026-11-14T00:00:00Z', 'Kaas', 'Goudse kaas')",
                )
                statement.execute("INSERT INTO meta (key, value) VALUES ('pricesFetchedAt', '2026-11-14T00:00:00Z')")
                statement.execute("PRAGMA user_version = 5")
            }
        }

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        for (table in listOf("offer", "ingredientPrice")) {
            assertEquals(columns(fresh, table), columns(url, table), "table $table after migration")
        }
        val offer = db.planQueries.selectOffersValidOn("2026-11-18").executeAsOne()
        assertEquals("Rundergehakt" to 4.29, offer.title to offer.price)
        assertEquals(null, offer.imageUrl, "until the store is fetched again")
        assertEquals("2026-11-15T00:00:00Z", db.planQueries.selectSources().executeAsOne().fetchedAt, "the next fetch comes at its usual time")
        val price = db.priceQueries.selectPrices().executeAsOne()
        assertEquals(Triple(12.0, "Kaas", "Goudse kaas"), Triple(price.eurosPerKg, price.category, price.subCategory))
        assertEquals(null, price.imageUrl, "until the next weekly lookup")
        assertEquals("2026-11-14T00:00:00Z", db.foodQueries.selectMeta("pricesFetchedAt").executeAsOneOrNull(), "no early lookup for pictures")
        driver.close()
    }

    @Test
    fun aVersionSixDatabaseKeepsItsRecipesWithoutPicturesUntilTheNextSync() {
        val file = createTempFile("v6", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        // Version 6 is today's schema without the recipe picture columns.
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropRecipePictureColumns(statement)
                statement.execute("INSERT INTO appUser (id, subject, createdAt) VALUES (1, 'sub', '2026-11-01T00:00:00Z')")
                statement.execute(
                    "INSERT INTO recipe (userId, source, sourcePath, contentHash, section, title, tagsJson, servings, instructions, hasIngredientsBlock, problemsJson, kind, rating) " +
                        "VALUES (1, 'COOKING_SITE', 'recipes/ala_mario/index.md', 'abc', 'recipes', 'Ala Mario', '[]', 2, 'Bake.', 1, '[]', 'MEAL', 4)",
                )
                statement.execute("INSERT INTO recipeIngredient (recipeId, position, amount, unit, item) VALUES (1, 0, 200, 'g', 'salmon')")
                statement.execute("PRAGMA user_version = 6")
            }
        }

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        assertEquals(columns(fresh, "recipe"), columns(url, "recipe"), "table recipe after migration")
        val recipe = db.recipeQueries.selectById(1, 1).executeAsOne()
        assertEquals(Triple("Ala Mario", "abc", 4L), Triple(recipe.title, recipe.contentHash, recipe.rating))
        assertEquals(null to null, recipe.imageSourceHash to recipe.imageVersion, "the next sync reads the picture")
        assertEquals(1, db.recipeQueries.selectIngredients(1).executeAsList().size)
        driver.close()
    }
}
