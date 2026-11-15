package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.server.db.Database
import java.sql.DriverManager
import java.sql.Statement
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
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
        dropBuyAheadColumns(statement)
    }

    /** Version 8 added what keeps, and since when a ticked item is on hand (7.sqm). */
    private fun dropBuyAheadColumns(statement: Statement) {
        statement.execute("ALTER TABLE ingredient DROP COLUMN keeps")
        dropStockAmountColumn(statement)
        statement.execute("ALTER TABLE onHand DROP COLUMN fromWeek")
    }

    /** Version 9 added what is left of a ticked item (8.sqm). */
    private fun dropStockAmountColumn(statement: Statement) {
        statement.execute("ALTER TABLE onHand DROP COLUMN grams")
        dropOwnItems(statement)
    }

    /** Version 10 added the own items of the shopping list (9.sqm); dropping the table drops its index. */
    private fun dropOwnItems(statement: Statement) {
        statement.execute("DROP TABLE shoppingExtra")
        dropStockUses(statement)
    }

    /** Version 11 added what eating a meal took from the stock (10.sqm). */
    private fun dropStockUses(statement: Statement) {
        statement.execute("DROP TABLE stockUse")
        dropTickMemory(statement)
    }

    /** Version 12 added what a tick remembers of its row (11.sqm). */
    private fun dropTickMemory(statement: Statement) {
        for (column in listOf("name", "grams", "pieces")) statement.execute("ALTER TABLE shoppingCheck DROP COLUMN $column")
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
            "ingredientPrice", "planMeal", "shoppingCheck", "shoppingExtra", "stockUse", "offer", "foodLog", "weight",
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

    @Test
    fun aVersionSevenDatabaseKeepsItsPantryAndDecidesWhatKeepsBySection() {
        val file = createTempFile("v7", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        // Version 7 is today's schema without the columns for buying ahead.
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropBuyAheadColumns(statement)
                statement.execute("INSERT INTO appUser (id, subject, createdAt) VALUES (1, 'sub', '2026-11-01T00:00:00Z')")
                statement.execute("INSERT INTO ingredient (userId, name, section) VALUES (1, 'Chicken fillet', 'MEAT'), (1, 'Spinach', NULL)")
                statement.execute("INSERT INTO onHand (ingredientId, addedAt) VALUES (2, '2026-11-01T00:00:00Z')")
                statement.execute("PRAGMA user_version = 7")
            }
        }

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        for (table in listOf("ingredient", "onHand")) {
            assertEquals(columns(fresh, table), columns(url, table), "table $table after migration")
        }
        val ingredients = IngredientRepository(db, FoodRepository(db), 1)
        assertEquals(emptyMap(), ingredients.keepsByHand(), "by the section until set by hand")
        assertEquals("MEAT", db.ingredientQueries.selectIngredientByName(1, "Chicken fillet").executeAsOne().section)
        // What was on hand was put there by hand: it counts at once, in any week.
        assertEquals(setOf(2L), ingredients.onHandFor(LocalDate.parse("2026-11-16")))
        assertEquals(null, db.pantryQueries.selectOnHandWeeks(1).executeAsOne().fromWeek)
        driver.close()
    }

    @Test
    fun aVersionEightDatabaseKeepsItsTicksWhichTheFirstMealStillUsesUp() {
        val file = createTempFile("v8", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        // Version 8 is today's schema without the amount left of a ticked item.
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropStockAmountColumn(statement)
                statement.execute("INSERT INTO appUser (id, subject, createdAt) VALUES (1, 'sub', '2026-11-01T00:00:00Z')")
                statement.execute("INSERT INTO ingredient (userId, name, section) VALUES (1, 'Chicken fillet', 'MEAT'), (1, 'Spinach', NULL)")
                statement.execute(
                    "INSERT INTO onHand (ingredientId, addedAt, fromWeek) VALUES " +
                        "(1, '2026-11-01T00:00:00Z', '2026-11-23'), (2, '2026-11-01T00:00:00Z', NULL)",
                )
                statement.execute("PRAGMA user_version = 8")
            }
        }

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        assertEquals(columns(fresh, "onHand"), columns(url, "onHand"), "table onHand after migration")
        val ingredients = IngredientRepository(db, FoodRepository(db), 1)
        val week = LocalDate.parse("2026-11-23")
        assertEquals(setOf(1L, 2L), ingredients.onHandFor(week))
        assertEquals(setOf(2L), ingredients.onHandFor(week.minusDays(7)), "the tick still counts from its week")
        // Nobody knows how much of the chicken there was: a meal of it uses it up, as before.
        db.planQueries.upsertMeal(1, week.toString(), 0, "DINNER", 1, "Chicken", 2.0, 800.0, 40.0, 0, 0, "EATEN")
        val dinner = MealRef(week, 0, MealSlot.DINNER)
        ingredients.useUp(dinner, mapOf(1L to 300.0, 2L to 100.0))
        assertEquals(setOf(2L), ingredients.onHandFor(week), "spinach was put on hand by hand and stays")
        // Un-eaten, the dinner puts it back as it was: of an unknown amount.
        ingredients.giveBack(dinner)
        assertEquals(setOf(1L, 2L), ingredients.onHandFor(week))
        assertEquals(null, db.pantryQueries.selectStock(1, week.toString(), listOf(1L)).executeAsOne().grams)
        driver.close()
    }

    @Test
    fun aVersionNineDatabaseKeepsItsListsAndStartsWithoutOwnItems() {
        val file = createTempFile("v9", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        // Version 9 is today's schema without the own items.
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropOwnItems(statement)
                statement.execute("INSERT INTO appUser (id, subject, createdAt) VALUES (1, 'sub', '2026-11-01T00:00:00Z')")
                statement.execute("INSERT INTO shoppingCheck (userId, weekStart, itemKey) VALUES (1, '2026-11-16', 'i12'), (1, '2026-11-16', 'dismissed:stock-up')")
                statement.execute("PRAGMA user_version = 9")
            }
        }

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        assertEquals(columns(fresh, "shoppingExtra"), columns(url, "shoppingExtra"), "table shoppingExtra after migration")
        assertEquals(setOf("i12", "dismissed:stock-up"), db.shoppingQueries.selectChecks(1, "2026-11-16").executeAsList().map { it.itemKey }.toSet())
        assertEquals(0L, db.shoppingQueries.countOpenExtras(1).executeAsOne())
        db.shoppingQueries.insertExtra(1, "Coffee", "2026-11-16")
        assertEquals(listOf("Coffee"), db.shoppingQueries.selectExtrasForWeek(1, "2026-11-23").executeAsList().map { it.name })
        driver.close()
    }

    @Test
    fun aVersionTenDatabaseKeepsItsStockAndMealsEatenBeforeGiveNothingBack() {
        val file = createTempFile("v10", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        // Version 10 is today's schema without what meals took from the stock.
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropStockUses(statement)
                statement.execute("INSERT INTO appUser (id, subject, createdAt) VALUES (1, 'sub', '2026-11-01T00:00:00Z')")
                statement.execute("INSERT INTO ingredient (userId, name) VALUES (1, 'Chicken fillet'), (1, 'Rice')")
                statement.execute(
                    "INSERT INTO onHand (ingredientId, addedAt, fromWeek, grams) VALUES " +
                        "(1, '2026-11-01T00:00:00Z', '2026-11-23', 600.0), (2, '2026-11-01T00:00:00Z', '2026-11-16', 1000.0)",
                )
                statement.execute(
                    "INSERT INTO planMeal (userId, weekStart, day, slot, recipeId, title, portions, kcal, proteinG, leftover, locked, status) VALUES " +
                        "(1, '2026-11-23', 0, 'DINNER', 1, 'Chicken', 2.0, 800.0, 40.0, 0, 0, 'EATEN'), " +
                        "(1, '2026-11-23', 1, 'DINNER', 1, 'Chicken', 2.0, 800.0, 40.0, 0, 0, 'PLANNED')",
                )
                statement.execute("PRAGMA user_version = 10")
            }
        }

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        assertEquals(columns(fresh, "stockUse"), columns(url, "stockUse"), "table stockUse after migration")
        val ingredients = IngredientRepository(db, FoodRepository(db), 1)
        val week = LocalDate.parse("2026-11-23")
        fun left(id: Long) = db.pantryQueries.selectStock(1, week.toString(), listOf(id)).executeAsOneOrNull()?.grams
        // Monday's dinner was eaten before what it took was kept: un-eating it gives nothing back.
        ingredients.giveBack(MealRef(week, 0, MealSlot.DINNER))
        assertEquals(600.0 to 1000.0, left(1) to left(2))

        // What a meal takes goes with it: replaced or removed, the meal leaves what it took taken.
        val tuesday = MealRef(week, 1, MealSlot.DINNER)
        fun uses() = db.pantryQueries.selectStockUses(1, week.toString(), 1, "DINNER").executeAsList()
        ingredients.useUp(tuesday, mapOf(1L to 200.0, 2L to 100.0))
        assertEquals(400.0 to 900.0, left(1) to left(2))
        assertEquals(2, uses().size)
        db.planQueries.upsertMeal(1, week.toString(), 1, "DINNER", 2, "Other", 2.0, 800.0, 40.0, 0, 0, "PLANNED")
        assertEquals(emptyList(), uses(), "replaced")
        ingredients.useUp(tuesday, mapOf(1L to 200.0))
        db.planQueries.deleteMeal(1, week.toString(), 1, "DINNER")
        assertEquals(emptyList(), uses(), "removed")
        assertEquals(200.0 to 900.0, left(1) to left(2))
        driver.close()
    }

    @Test
    fun aVersionElevenDatabaseKeepsItsTicksAsPlainTicksThatRememberNothing() {
        val file = createTempFile("v11", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        val week = "2026-11-16"
        // Version 11 is today's schema without what a tick remembers.
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropTickMemory(statement)
                statement.execute("INSERT INTO appUser (id, subject, createdAt) VALUES (1, 'sub', '2026-11-01T00:00:00Z')")
                statement.execute(
                    "INSERT INTO shoppingCheck (userId, weekStart, itemKey) VALUES " +
                        "(1, '$week', 't:beef'), (1, '$week', 't:leek'), (1, '$week', 'dismissed:stock-up')",
                )
                statement.execute("PRAGMA user_version = 11")
            }
        }

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        assertEquals(columns(fresh, "shoppingCheck"), columns(url, "shoppingCheck"), "table shoppingCheck after migration")
        val ticks = db.shoppingQueries.selectChecks(1, week).executeAsList()
        assertEquals(setOf("t:beef", "t:leek", "dismissed:stock-up"), ticks.map { it.itemKey }.toSet())
        assertTrue(ticks.all { it.name == null && it.grams == null && it.pieces == null }, "nothing is known of what was bought")

        // The week's stew needs beef (no catalogue here, so its row is "t:beef") and no leek any more.
        val foods = FoodRepository(db)
        val offers = OfferRepository(db, emptyList())
        val scope = UserScope(1, db, foods, offers, PriceRepository(db, null, foods, offers), null, createTempDirectory("pictures").toFile())
        val stew = scope.recipes.create(RecipeInput("Stew", 2, "400 | g | beef")).summary
        db.planQueries.upsertMeal(1, week, 0, "DINNER", stew.id, stew.title, 2.0, 800.0, 40.0, 0, 0, "PLANNED")
        val list = scope.shopping.get(week)
        // Still ticked as before, without an amount bought: never "more needed", never "no longer needed".
        val beef = list.items.single()
        assertEquals("t:beef" to true, beef.key to beef.checked)
        assertEquals(null to null, beef.boughtGrams to beef.boughtPieces)
        assertEquals(emptyList(), list.noLongerNeeded)
        assertTrue(list.stockUpDismissed)
        driver.close()
    }
}
