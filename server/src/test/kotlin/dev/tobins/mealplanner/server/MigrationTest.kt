package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.AddedInput
import dev.tobins.mealplanner.api.MealLineChange
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.OfferExclusionRequest
import dev.tobins.mealplanner.api.PrepInput
import dev.tobins.mealplanner.api.PrepSlot
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.StandardChoice
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.ToppingInput
import dev.tobins.mealplanner.api.ToppingsInput
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tobins.mealplanner.recipes.HugoRecipeParser
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.offers.OfferSource
import kotlinx.coroutines.runBlocking
import java.sql.DriverManager
import java.sql.Statement
import java.util.Properties
import java.time.Instant
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    /**
     * A table as SQLite has it: its columns (name, type, not null, default, place in the key), its
     * indexes and its foreign keys, so that a rebuilt table is compared in full with a fresh one.
     */
    private fun shape(url: String, table: String): List<String> = DriverManager.getConnection(url).use { connection ->
        fun rows(pragma: String, vararg columns: String) = connection.createStatement().executeQuery("PRAGMA $pragma($table)").use { rows ->
            generateSequence { if (rows.next()) columns.joinToString("|") { rows.getString(it).orEmpty() } else null }.toList()
        }
        rows("table_info", "name", "type", "notnull", "dflt_value", "pk") +
            rows("index_list", "name", "unique", "origin", "partial").sorted() +
            rows("foreign_key_list", "table", "from", "to", "on_delete").sorted()
    }

    // Older versions are made from today's schema by taking back each version's changes, the newest first.

    /** Version 7 added the recipe pictures' columns (6.sqm). */
    private fun dropRecipePictureColumns(statement: Statement) {
        dropBuyAheadColumns(statement)
        statement.execute("ALTER TABLE recipe DROP COLUMN imageSourceHash")
        statement.execute("ALTER TABLE recipe DROP COLUMN imageVersion")
    }

    /** Version 8 added what keeps, and since when a ticked item is on hand (7.sqm). */
    private fun dropBuyAheadColumns(statement: Statement) {
        dropStockAmountColumn(statement)
        statement.execute("ALTER TABLE ingredient DROP COLUMN keeps")
        statement.execute("ALTER TABLE onHand DROP COLUMN fromWeek")
    }

    /** Version 9 added what is left of a ticked item (8.sqm). */
    private fun dropStockAmountColumn(statement: Statement) {
        dropOwnItems(statement)
        statement.execute("ALTER TABLE onHand DROP COLUMN grams")
    }

    /** Version 10 added the own items of the shopping list (9.sqm); dropping the table drops its index. */
    private fun dropOwnItems(statement: Statement) {
        dropStockUses(statement)
        statement.execute("DROP TABLE shoppingExtra")
    }

    /** Version 11 added what eating a meal took from the stock (10.sqm). */
    private fun dropStockUses(statement: Statement) {
        dropTickMemory(statement)
        statement.execute("DROP TABLE stockUse")
    }

    /** Version 12 added what a tick remembers of its row (11.sqm). */
    private fun dropTickMemory(statement: Statement) {
        dropPriceHistory(statement)
        for (column in listOf("name", "grams", "pieces")) statement.execute("ALTER TABLE shoppingCheck DROP COLUMN $column")
    }

    /** Version 13 added the price history (12.sqm); dropping the tables drops their indexes. */
    private fun dropPriceHistory(statement: Statement) {
        dropStockSources(statement)
        statement.execute("DROP TABLE offerHistory")
        statement.execute("DROP TABLE regularPriceHistory")
    }

    /**
     * Version 14 kept the stock per source (13.sqm): until then one onHand entry per ingredient held both
     * what was put on hand by hand (fromWeek NULL) and what a tick put there, and stockUse named its stock
     * by week alone. The tables as version 13 had them, empty.
     */
    private fun dropStockSources(statement: Statement) {
        dropVerdictRecords(statement)
        statement.execute("DROP TABLE stock")
        statement.execute("DROP TABLE onHand")
        statement.execute("DROP TABLE stockUse")
        statement.execute(
            "CREATE TABLE onHand (ingredientId INTEGER PRIMARY KEY REFERENCES ingredient(id) ON DELETE CASCADE, " +
                "addedAt TEXT NOT NULL, fromWeek TEXT, grams REAL)",
        )
        statement.execute(
            "CREATE TABLE stockUse (userId INTEGER NOT NULL, weekStart TEXT NOT NULL, day INTEGER NOT NULL, slot TEXT NOT NULL, " +
                "ingredientId INTEGER NOT NULL REFERENCES ingredient(id) ON DELETE CASCADE, fromWeek TEXT NOT NULL, addedAt TEXT NOT NULL, grams REAL, " +
                "PRIMARY KEY (userId, weekStart, day, slot, ingredientId), " +
                "FOREIGN KEY (userId, weekStart, day, slot) REFERENCES planMeal(userId, weekStart, day, slot) ON DELETE CASCADE)",
        )
        statement.execute("CREATE INDEX stockUse_ingredient ON stockUse(ingredientId)")
    }

    /** Version 15 added the category, address and price per litre to the history, and its recordings (14.sqm). */
    private fun dropVerdictRecords(statement: Statement) {
        dropTickSavings(statement)
        for (column in listOf("category", "url", "eurosPerLitre")) statement.execute("ALTER TABLE offerHistory DROP COLUMN $column")
        statement.execute("DROP TABLE offerRecording")
    }

    /** Version 16 added what a tick's offer saved (15.sqm). */
    private fun dropTickSavings(statement: Statement) {
        dropRecipeBank(statement)
        for (column in listOf("saving", "savingStore")) statement.execute("ALTER TABLE shoppingCheck DROP COLUMN $column")
    }

    /** Version 17 added whether a recipe is one of the account's and in its rotation (16.sqm). */
    private fun dropRecipeBank(statement: Statement) {
        dropMealLines(statement)
        for (column in listOf("addedAt", "inRotation")) statement.execute("ALTER TABLE recipe DROP COLUMN $column")
    }

    /** Version 18 added what a meal ate that differs from its recipe (17.sqm); dropping the table drops its index. */
    private fun dropMealLines(statement: Statement) {
        dropRecipeToppings(statement)
        statement.execute("DROP TABLE mealLine")
    }

    /** Version 19 added a recipe's optional toppings (18.sqm); dropping the table drops its index. */
    private fun dropRecipeToppings(statement: Statement) {
        dropPreps(statement)
        statement.execute("DROP TABLE recipeTopping")
    }

    /** Version 20 added prepped meals (19.sqm): a recipe's two keeps columns and the batches' tables, with their indexes. */
    private fun dropPreps(statement: Statement) {
        dropStockUseRoles(statement)
        statement.execute("DROP TABLE prepPortion")
        statement.execute("DROP TABLE prep")
        statement.execute("ALTER TABLE recipe DROP COLUMN keepsDays")
        statement.execute("ALTER TABLE recipe DROP COLUMN keepsVacuumDays")
    }

    /** Version 21 kept the stock a side took apart from its meal's (20.sqm): stockUse as version 20 had it, empty. */
    private fun dropStockUseRoles(statement: Statement) {
        dropStandardMeals(statement)
        statement.execute("DROP TABLE stockUse")
        statement.execute(
            "CREATE TABLE stockUse (userId INTEGER NOT NULL, weekStart TEXT NOT NULL, day INTEGER NOT NULL, slot TEXT NOT NULL, " +
                "ingredientId INTEGER NOT NULL REFERENCES ingredient(id) ON DELETE CASCADE, fromWeek TEXT NOT NULL, source TEXT NOT NULL, " +
                "addedAt TEXT NOT NULL, grams REAL, PRIMARY KEY (userId, weekStart, day, slot, ingredientId, fromWeek, source), " +
                "FOREIGN KEY (userId, weekStart, day, slot) REFERENCES planMeal(userId, weekStart, day, slot) ON DELETE CASCADE)",
        )
        statement.execute("CREATE INDEX stockUse_ingredient ON stockUse(ingredientId)")
    }

    /** Version 22 added standard meals (21.sqm): a recipe's column. */
    private fun dropStandardMeals(statement: Statement) {
        dropPlanPicks(statement)
        statement.execute("ALTER TABLE recipe DROP COLUMN standardMeal")
    }

    /** Version 23 added the dinners chosen and the recipes hidden on the planning screen (22.sqm): a table, with its index. */
    private fun dropPlanPicks(statement: Statement) {
        dropOfferExclusions(statement)
        statement.execute("DROP TABLE planPick")
    }

    /** Version 24 added the offers an account said are not one of its ingredients (23.sqm): a table, with its index. */
    private fun dropOfferExclusions(statement: Statement) {
        dropAccountRequests(statement)
        statement.execute("DROP TABLE offerExclusion")
    }

    /** Version 25 added the requests for an account (24.sqm): a table. */
    private fun dropAccountRequests(statement: Statement) {
        dropLineIngredients(statement)
        statement.execute("DROP TABLE accountRequest")
    }

    /**
     * Version 26 added a line's own ingredient and when a recipe was saved in the app (25.sqm): two columns. SQLite drops
     * no column with a foreign key, so the lines' table, empty here, is made again as it was.
     */
    private fun dropLineIngredients(statement: Statement) {
        dropProducts(statement)
        statement.execute("DROP INDEX recipeIngredient_ingredient")
        statement.execute("DROP INDEX recipeIngredient_recipe")
        statement.execute("DROP TABLE recipeIngredient")
        statement.execute(
            "CREATE TABLE recipeIngredient (id INTEGER PRIMARY KEY AUTOINCREMENT, recipeId INTEGER NOT NULL REFERENCES recipe(id) ON DELETE CASCADE, " +
                "position INTEGER NOT NULL, amount REAL, unit TEXT, dimension TEXT, baseAmount REAL, item TEXT NOT NULL, groupName TEXT)",
        )
        statement.execute("CREATE INDEX recipeIngredient_recipe ON recipeIngredient(recipeId, position)")
        statement.execute("ALTER TABLE recipe DROP COLUMN editedAt")
    }

    /** Version 27 added an ingredient's own values and its portions (26.sqm, issue 351): ten columns and a table. */
    private fun dropProducts(statement: Statement) {
        dropSlotFoods(statement)
        statement.execute("DROP TABLE ingredientPortion")
        for (column in labelColumns) statement.execute("ALTER TABLE ingredient DROP COLUMN $column")
    }

    /** Version 28 added the foods of a slot (27.sqm, issue 358): a column of the recipes and one of the planned meals. */
    private fun dropSlotFoods(statement: Statement) {
        statement.execute("ALTER TABLE recipe DROP COLUMN slotFoods")
        statement.execute("ALTER TABLE planMeal DROP COLUMN foodsId")
    }

    /** The columns 27.sqm adds, by table. */
    private val slotFoodsColumns = mapOf("recipe" to "slotFoods", "planMeal" to "foodsId")

    private val labelColumns = listOf(
        "labelBasis", "labelKcal", "labelProteinG", "labelFatG", "labelSaturatedFatG", "labelCarbsG", "labelSugarG",
        "labelFibreG", "labelSodiumMg", "labelSource",
    )

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
            "offerHistory", "regularPriceHistory", "stock", "offerRecording", "mealLine",
        )
        for (table in tables) {
            assertEquals(columns(fresh, table), columns(url, table), "table $table after migration")
        }
        val (_, reopened) = openDatabase(url)
        // Everything from before accounts belongs to account 1, which nobody has claimed yet.
        val owner = reopened.userQueries.selectUnclaimed().executeAsOne()
        assertEquals(1L, owner.id)
        assertTrue(reopened.recipeQueries.selectMine(owner.id).executeAsList().single().rating == null, "existing rows survive, as my recipes")
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
        assertEquals(setOf(2L), StockRepository(db, FoodRepository(db), 1).onHandFor(LocalDate.parse("2026-11-16")))
        assertEquals(listOf(2L), db.pantryQueries.selectOnHandByHand(1).executeAsList())
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
        val ingredients = StockRepository(db, FoodRepository(db), 1)
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
        val ingredients = StockRepository(db, FoodRepository(db), 1)
        val week = LocalDate.parse("2026-11-23")
        fun left(id: Long) = db.pantryQueries.selectStock(1, week.toString(), listOf(id)).executeAsOneOrNull()?.grams
        // Monday's dinner was eaten before what it took was kept: un-eating it gives nothing back.
        ingredients.giveBack(MealRef(week, 0, MealSlot.DINNER))
        assertEquals(600.0 to 1000.0, left(1) to left(2))

        // What a meal takes goes with it: replaced or removed, the meal leaves what it took taken.
        val tuesday = MealRef(week, 1, MealSlot.DINNER)
        fun uses() = db.pantryQueries.selectStockUses(1, week.toString(), 1, "DINNER", MealRef.MEAL).executeAsList()
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

    @Test
    fun aVersionTwelveDatabaseKeepsItsOffersAndPricesAndStartsAnEmptyHistory() {
        val file = createTempFile("v12", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        // Version 12 is today's schema without the price history.
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropPriceHistory(statement)
                statement.execute(
                    "INSERT INTO offer (store, title, deal, price, validFrom, validUntil) " +
                        "VALUES ('LIDL', 'Rundergehakt', NULL, 4.29, '2026-11-16', '2026-11-22')",
                )
                statement.execute("INSERT INTO offerSource (store, fetchedAt) VALUES ('LIDL', '2026-11-16T01:00:00.123Z')")
                statement.execute(
                    "INSERT INTO ingredientPrice (name, store, product, packSize, packPrice, eurosPerKg, fetchedAt) " +
                        "VALUES ('Gouda', 'AH', 'AH Gouda', '500 g', 6.0, 12.0, '2026-11-14T00:00:00Z')",
                )
                statement.execute("PRAGMA user_version = 12")
            }
        }

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        for (table in listOf("offerHistory", "regularPriceHistory")) {
            assertEquals(columns(fresh, table), columns(url, table), "table $table after migration")
        }
        assertEquals("Rundergehakt", db.planQueries.selectOffersValidOn("2026-11-18").executeAsOne().title)
        assertEquals(12.0, db.priceQueries.selectPrices().executeAsOne().eurosPerKg)
        assertEquals(emptyList(), db.priceHistoryQueries.selectOfferHistory().executeAsList(), "history starts on the first refresh")
        assertEquals(emptyList(), db.priceHistoryQueries.selectRegularPriceHistory().executeAsList())

        // The first refresh after the migration starts the history with what the store offers.
        val lidl = object : OfferSource {
            override val store = Store.LIDL
            override suspend fun fetch() = listOf(Offer(Store.LIDL, "Rundergehakt", null, 4.29, validFrom = "2026-11-16", validUntil = "2026-11-22"))
        }
        runBlocking { OfferRepository(db, listOf(lidl)).refresh(now = Instant.parse("2026-11-17T01:00:00Z")) }
        val row = db.priceHistoryQueries.selectOfferHistory().executeAsOne()
        assertEquals(Triple("LOWER_PRICE", "2026-11-17T01:00:00Z", "2026-11-17T01:00:00Z"), Triple(row.dealKind, row.firstSeen, row.lastSeen))
        driver.close()
    }

    private fun single(url: String, sql: String): String = DriverManager.getConnection(url).use { connection ->
        connection.createStatement().executeQuery(sql).use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.joinToString(",") }
    }

    @Test
    fun aVersionThirteenDatabaseKeepsItsStockEachEntryWithTheTickThatPutItThere() {
        val file = createTempFile("v13", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        val week = "2026-11-16"
        val next = "2026-11-23"
        // Version 13 is today's schema with one onHand entry per ingredient, by hand or ticked, and uses named by week.
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropStockSources(statement)
                statement.execute("INSERT INTO appUser (id, subject, createdAt) VALUES (1, 'alice', '2026-11-01T00:00:00Z'), (2, 'bob', '2026-11-01T00:00:00Z')")
                statement.execute(
                    "INSERT INTO ingredient (id, userId, name) VALUES " +
                        "(1, 1, 'Chicken fillet'), (2, 1, 'Rice'), (3, 1, 'Lettuce'), (4, 1, 'Olive oil'), (5, 1, 'Beef'), (6, 2, 'Chicken fillet')",
                )
                // Alice: chicken bought ahead last week, rice stocked up and lettuce used up this week, oil on hand by hand,
                // beef stocked up this week and bought ahead last week (one entry: the second tick added nothing).
                // Bob: chicken of which he has no tick (Alice's tick of his key is hers, and changes nothing of his).
                statement.execute(
                    "INSERT INTO onHand (ingredientId, addedAt, fromWeek, grams) VALUES " +
                        "(1, '2026-11-12T10:00:00Z', '$next', 3600.0), (2, '2026-11-16T10:00:00Z', '$week', 900.0), " +
                        "(3, '2026-11-17T10:00:00Z', '$week', 100.0), (4, '2026-11-01T10:00:00Z', NULL, NULL), " +
                        "(5, '2026-11-10T10:00:00Z', '$week', 800.0), (6, '2026-11-12T10:00:00Z', '$next', 500.0)",
                )
                statement.execute(
                    "INSERT INTO shoppingCheck (userId, weekStart, itemKey) VALUES " +
                        "(1, '2026-11-16', 'ahead:i1'), (1, '$week', 'stock:i2'), (1, '2026-11-09', 'ahead:i5'), (1, '$week', 'stock:i5'), " +
                        "(1, '2026-11-16', 'ahead:i6')",
                )
                statement.execute(
                    "INSERT INTO planMeal (userId, weekStart, day, slot, recipeId, title, portions, kcal, proteinG, leftover, locked, status) " +
                        "VALUES (1, '$week', 0, 'DINNER', 1, 'Bowl', 2.0, 800.0, 40.0, 0, 0, 'EATEN')",
                )
                // Monday's dinner took 100 g of the rice and 50 g of the lettuce.
                statement.execute(
                    "INSERT INTO stockUse (userId, weekStart, day, slot, ingredientId, fromWeek, addedAt, grams) VALUES " +
                        "(1, '$week', 0, 'DINNER', 2, '$week', '2026-11-16T10:00:00Z', 100.0), (1, '$week', 0, 'DINNER', 3, '$week', '2026-11-17T10:00:00Z', 50.0)",
                )
                statement.execute("PRAGMA user_version = 13")
            }
        }

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        for (table in listOf("onHand", "stock", "stockUse")) assertEquals(shape(fresh, table), shape(url, table), "table $table after migration")
        assertEquals("ok" to "", single(url, "PRAGMA integrity_check") to single(url, "PRAGMA foreign_key_check"))
        assertEquals("", single(url, "SELECT name FROM sqlite_master WHERE name LIKE '%_old'"), "the old tables are gone")

        // Every entry is kept, by hand or in stock, with the tick that put it there.
        assertEquals(listOf(4L), db.pantryQueries.selectOnHandByHand(1).executeAsList())
        val stock = single(url, "SELECT ingredientId || ':' || fromWeek || ':' || source || ':' || grams FROM stock ORDER BY ingredientId")
        assertEquals(
            "1:$next:BUY_AHEAD:3600.0,2:$week:STOCK_UP:900.0,3:$week:USE_UP:100.0,5:$week:BUY_AHEAD:800.0,6:$next:USE_UP:500.0",
            stock,
            "the earlier list's tick for one both could have put there; none for what was used up, or for another account's tick",
        )
        val uses = single(url, "SELECT ingredientId || ':' || source || ':' || grams FROM stockUse ORDER BY ingredientId")
        assertEquals("2:STOCK_UP:100.0,3:USE_UP:50.0", uses)
        val ingredients = StockRepository(db, FoodRepository(db), 1)
        assertEquals(listOf(4L, 5L, 1L, 2L, 3L), ingredients.pantry().onHand.map { it.id }, "in the order they came, as before")

        // Un-ticking now takes back that tick's entry alone, and what meals took from it stays taken.
        ingredients.takeBackOnHand(2, LocalDate.parse(week), StockSource.STOCK_UP)
        ingredients.giveBack(MealRef(LocalDate.parse(week), 0, MealSlot.DINNER))
        assertEquals(mapOf(1L to 3600.0, 3L to 150.0, 5L to 800.0), ingredients.pantry().onHandGrams)
        assertEquals(mapOf(6L to 500.0), StockRepository(db, FoodRepository(db), 2).pantry().onHandGrams, "Bob's stock is his")
        driver.close()
    }

    /**
     * Rows as the old server wrote them through its API (the check on a copy of dev.db for PR 126): stock that
     * meals used up, so only what they took is left of it, and a used-up item merged into what was bought ahead.
     */
    @Test
    fun aVersionThirteenDatabaseGivesBackWhatMealsUsedUpAndKeepsAMergedUseUpAtHome() {
        val file = createTempFile("v13", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        val week = "2026-11-16"
        val next = "2026-11-23"
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropStockSources(statement)
                statement.execute("INSERT INTO appUser (id, subject, createdAt) VALUES (1, 'tester', '2026-11-01T00:00:00Z')")
                statement.execute("INSERT INTO ingredient (id, userId, name) VALUES (1, 1, 'Tuna'), (2, 1, 'Chicken fillet'), (3, 1, 'Minced beef')")
                // Tuna stocked up this week (200 g). Chicken bought ahead on this week's list (500 g) and stocked up on
                // next week's, which added nothing. Minced beef bought ahead (262.5 g), then 75 g of it used up on this
                // week's list, which the old server added to that entry and counted from this week.
                statement.execute(
                    "INSERT INTO shoppingCheck (userId, weekStart, itemKey) VALUES " +
                        "(1, '$week', 'stock:i1'), (1, '$week', 'ahead:i2'), (1, '$next', 'stock:i2'), (1, '$week', 'ahead:i3')",
                )
                statement.execute("INSERT INTO onHand (ingredientId, addedAt, fromWeek, grams) VALUES (3, '2026-11-16T00:28:24.382531489Z', '$week', 75.0)")
                statement.execute(
                    "INSERT INTO planMeal (userId, weekStart, day, slot, recipeId, title, portions, kcal, proteinG, leftover, locked, status) VALUES " +
                        "(1, '$week', 0, 'DINNER', 1, 'Tuna bowl', 1.75, 800.0, 40.0, 0, 0, 'EATEN'), " +
                        "(1, '$next', 2, 'DINNER', 2, 'Chicken Boursin', 2.0, 800.0, 40.0, 0, 0, 'EATEN'), " +
                        "(1, '$next', 3, 'LUNCH', 2, 'Chicken Boursin', 1.0, 400.0, 20.0, 1, 0, 'PLANNED'), " +
                        "(1, '$next', 4, 'DINNER', 3, 'Bolognese', 2.0, 800.0, 40.0, 0, 0, 'EATEN'), " +
                        "(1, '$next', 5, 'LUNCH', 3, 'Bolognese', 1.5, 600.0, 30.0, 1, 0, 'EATEN')",
                )
                // The tuna and the chicken were used up by their dinners (the chicken's leftover lunch kept its own share);
                // the bolognese and its lunch took 262.5 g of the merged minced beef.
                statement.execute(
                    "INSERT INTO stockUse (userId, weekStart, day, slot, ingredientId, fromWeek, addedAt, grams) VALUES " +
                        "(1, '$week', 0, 'DINNER', 1, '$week', '2026-11-16T00:28:24.795235719Z', 200.0), " +
                        "(1, '$next', 2, 'DINNER', 2, '$next', '2026-11-16T00:28:25.283499610Z', 333.333333333333), " +
                        "(1, '$next', 3, 'LUNCH', 2, '$next', '2026-11-16T00:28:25.283499610Z', 166.666666666667), " +
                        "(1, '$next', 4, 'DINNER', 3, '$week', '2026-11-16T00:28:24.382531489Z', 150.0), " +
                        "(1, '$next', 5, 'LUNCH', 3, '$week', '2026-11-16T00:28:24.382531489Z', 112.5)",
                )
                statement.execute("PRAGMA user_version = 13")
            }
        }

        val (driver, db) = openDatabase(url)

        assertEquals("ok" to "", single(url, "PRAGMA integrity_check") to single(url, "PRAGMA foreign_key_check"))
        assertEquals("3:$week:USE_UP:75.0", single(url, "SELECT ingredientId || ':' || fromWeek || ':' || source || ':' || grams FROM stock"))
        assertEquals(
            "1:STOCK_UP,2:BUY_AHEAD,2:BUY_AHEAD,3:USE_UP,3:USE_UP",
            single(url, "SELECT ingredientId || ':' || source FROM stockUse ORDER BY ingredientId, day"),
            "a use of an entry that is gone gets the tick that put it there",
        )
        val ingredients = StockRepository(db, FoodRepository(db), 1)
        val ref = { weekStart: String, day: Int, slot: MealSlot -> MealRef(LocalDate.parse(weekStart), day, slot) }

        // Un-eaten, each meal gives back what it took, the used-up stock coming back as it was.
        ingredients.giveBack(ref(week, 0, MealSlot.DINNER))
        for ((day, slot) in listOf(2 to MealSlot.DINNER, 3 to MealSlot.LUNCH, 4 to MealSlot.DINNER, 5 to MealSlot.LUNCH)) ingredients.giveBack(ref(next, day, slot))
        assertEquals(mapOf(1L to 200.0, 2L to 500.0, 3L to 337.5), ingredients.pantry().onHandGrams.mapValues { Math.round(it.value * 1000) / 1000.0 })

        // Un-ticking takes back what each tick put there: the chicken's second tick put nothing there, its first all
        // of it. The merged minced beef matches no tick: un-ticking the buy-ahead leaves it at home, as 13.sqm says.
        ingredients.takeBackOnHand(1, LocalDate.parse(week), StockSource.STOCK_UP)
        ingredients.takeBackOnHand(2, LocalDate.parse(next), StockSource.STOCK_UP)
        assertEquals(setOf(2L, 3L), ingredients.pantry().onHandGrams.keys)
        ingredients.takeBackOnHand(2, LocalDate.parse(next), StockSource.BUY_AHEAD)
        ingredients.takeBackOnHand(3, LocalDate.parse(next), StockSource.BUY_AHEAD)
        assertEquals(mapOf(3L to 337.5), ingredients.pantry().onHandGrams)
        driver.close()
    }

    /** Every table of the database at [url] as SQLite has it ([shape]), by name. */
    private fun shapes(url: String): Map<String, List<String>> =
        single(url, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").split(",")
            .associateWith { shape(url, it) }

    @Test
    fun aVersionFourteenDatabaseKeepsEveryHistoryRowWithTheNewColumnsEmpty() {
        val file = createTempFile("v14", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        val open = "2026-11-16T03:00:00Z"
        // Version 14 is today's schema without the history's category, address and price per litre, and without recordings.
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropVerdictRecords(statement)
                // An oil offer still open at the last recording, one that ended, and another store's.
                statement.execute(
                    "INSERT INTO offerHistory (id, store, productKey, packKey, title, packSize, ingredient, deal, dealKind, dealPacks, dealFree, " +
                        "dealPercent, dealEuros, price, originalPrice, paidShare, eurosPerKg, eurosPerPiece, validFrom, validUntil, firstSeen, lastSeen) VALUES " +
                        "(1, 'AH', 'ah olijfolie', '750 ml', 'AH Olijfolie', '750 ml', 'Olive oil', 'voor 4,50', 'LOWER_PRICE', 1, NULL, NULL, 4.5, " +
                        "NULL, 6.0, 0.75, NULL, NULL, '2026-11-16', '2026-11-22', '2026-11-14T03:00:00Z', '$open'), " +
                        "(2, 'AH', 'ah kipfilet', '500 g', 'AH Kipfilet', '500 g', NULL, '1+1 gratis', 'X_PLUS_Y_FREE', 2, 1, NULL, NULL, " +
                        "NULL, 8.0, 0.5, 8.0, NULL, '2026-11-09', '2026-11-15', '2026-11-09T03:00:00Z', '2026-11-15T03:00:00Z'), " +
                        "(3, 'JUMBO', 'kaas', '', 'Kaas', NULL, NULL, '25% korting', 'PERCENT_OFF', 1, NULL, 25.0, NULL, " +
                        "2.25, 3.0, 0.75, NULL, NULL, '2026-11-16', '2026-11-22', '$open', '$open')",
                )
                statement.execute(
                    "INSERT INTO regularPriceHistory (name, store, product, packSize, packPrice, eurosPerKg, firstSeen, lastSeen) " +
                        "VALUES ('Olive oil', 'AH', 'AH Olijfolie', '750 ml', 6.0, 8.0, '2026-11-14T00:00:00Z', '2026-11-14T00:00:00Z')",
                )
                statement.execute("PRAGMA user_version = 14")
            }
        }
        // Each history row in full, as text, by the columns version 14 has.
        val oldColumns = listOf("offerHistory", "regularPriceHistory").associateWith { columns(url, it) }
        fun rows() = oldColumns.map { (table, columns) ->
            single(url, "SELECT ${columns.joinToString(" || '|' || ") { "coalesce($it, 'NULL')" }} FROM $table ORDER BY id")
        }
        val before = rows()

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        assertEquals(shapes(fresh), shapes(url), "every table as a fresh database has it")
        assertEquals("ok" to "", single(url, "PRAGMA integrity_check") to single(url, "PRAGMA foreign_key_check"))
        assertEquals(before, rows(), "every row as it was")
        assertEquals("0", single(url, "SELECT count(*) FROM offerHistory WHERE category IS NOT NULL OR url IS NOT NULL OR eurosPerLitre IS NOT NULL"))
        assertEquals("0", single(url, "SELECT count(*) FROM offerRecording"), "recordings start with the next refresh")

        // The next refresh continues the open period without a new row, and gives it what it lacked.
        val ah = object : OfferSource {
            override val store = Store.AH
            override suspend fun fetch() = listOf(
                Offer(
                    Store.AH, "AH Olijfolie", "voor 4,50", null, 6.0, "750 ml", "Olie en azijn", "2026-11-16", "2026-11-22",
                    "https://www.ah.nl/producten/product/wi1234",
                ),
            )
        }
        runBlocking { OfferRepository(db, listOf(ah)).refresh(now = Instant.parse("2026-11-17T03:00:00Z")) }
        val periods = db.priceHistoryQueries.selectOfferHistory().executeAsList().filter { it.store == "AH" }
        assertEquals(listOf(2L, 1L), periods.map { it.id }, "no new row")
        val oil = periods.single { it.id == 1L }
        assertEquals(listOf("2026-11-17T03:00:00Z", "Olie en azijn", "https://www.ah.nl/producten/product/wi1234"), listOf(oil.lastSeen, oil.category, oil.url))
        assertTrue(oil.eurosPerLitre?.let { abs(it - 6.0) < 1e-9 } == true, "4,50 for 750 ml: ${oil.eurosPerLitre}")
        assertEquals("AH:1", single(url, "SELECT store || ':' || offers FROM offerRecording"))
        driver.close()
    }

    @Test
    fun aVersionFifteenDatabaseKeepsEveryTickWithNothingKnownOfWhatItSaved() {
        val file = createTempFile("v15", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        val week = "2026-11-16"
        // Version 15 is today's schema without what a tick's offer saved.
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropTickSavings(statement)
                statement.execute("INSERT INTO appUser (id, subject, createdAt) VALUES (1, 'alice', '2026-11-01T00:00:00Z'), (2, 'bob', '2026-11-01T00:00:00Z')")
                statement.execute("INSERT INTO ingredient (id, userId, name) VALUES (1, 1, 'Kipfilet'), (2, 2, 'Kipfilet')")
                // Alice: a row of her meals that remembers what it said, a plain tick from before that, advice and a
                // dismissed group. Bob: his own row.
                statement.execute(
                    "INSERT INTO shoppingCheck (userId, weekStart, itemKey, name, grams, pieces) VALUES " +
                        "(1, '$week', 'i1', 'Kipfilet', 1000.0, NULL), (1, '$week', 't:leek', NULL, NULL, NULL), " +
                        "(1, '$week', 'stock:i1', NULL, NULL, NULL), (1, '$week', 'dismissed:buy-ahead', NULL, NULL, NULL), " +
                        "(2, '$week', 'i2', 'Kipfilet', 500.0, 2.0)",
                )
                statement.execute("PRAGMA user_version = 15")
            }
        }
        val oldColumns = columns(url, "shoppingCheck")
        fun rows() = single(url, "SELECT ${oldColumns.joinToString(" || '|' || ") { "coalesce($it, 'NULL')" }} FROM shoppingCheck ORDER BY userId, itemKey")
        val before = rows()

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        assertEquals(shapes(fresh), shapes(url), "every table as a fresh database has it")
        assertEquals("ok" to "", single(url, "PRAGMA integrity_check") to single(url, "PRAGMA foreign_key_check"))
        assertEquals(before, rows(), "every tick as it was")
        assertEquals("5:0", single(url, "SELECT count(*) || ':' || (count(saving) + count(savingStore)) FROM shoppingCheck"))

        // Alice's chicken is still ticked with what it said, and nothing is known of what it saved: the app
        // works that out as before.
        val foods = FoodRepository(db)
        val offers = OfferRepository(db, emptyList())
        val scope = UserScope(1, db, foods, offers, PriceRepository(db, null, foods, offers), null, createTempDirectory("pictures").toFile())
        val chicken = scope.recipes.create(RecipeInput("Chicken", 1, "1000 | g | kipfilet")).summary
        db.planQueries.upsertMeal(1, week, 0, "DINNER", chicken.id, chicken.title, 1.0, 800.0, 40.0, 0, 0, "PLANNED")
        val row = scope.shopping.get(week).items.single()
        assertEquals(listOf("i1", true, 1000.0, null, null), listOf(row.key, row.checked, row.boughtGrams, row.boughtSaving, row.boughtOfferStore))
        driver.close()
    }

    /**
     * Version 16 is the schema of 15.sqm, which servers before the bank (issue 205) run. Under option 1(a)
     * of that issue every recipe the account sees stays one of its recipes and in the rotation, so a released
     * app lists the same recipes and the planner picks from the same ones. A recipe whose file had left the
     * site was hidden; it goes to the bank, where it stays hidden until its file is back.
     */
    @Test
    fun aVersionSixteenDatabaseKeepsEveryRecipeItShowsAddedAndInTheRotation() {
        val file = createTempFile("v16", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropRecipeBank(statement)
                statement.execute("INSERT INTO appUser (id, subject, createdAt) VALUES (1, 'alice', '2026-11-01T00:00:00Z'), (2, 'bob', '2026-11-01T00:00:00Z')")
                // Alice: a site meal she rated, a site sauce, one whose file left the site, one of her own (her
                // breakfast routine) and one Claude wrote. Bob: a meal of his own site.
                statement.execute(
                    "INSERT INTO recipe (id, userId, source, sourcePath, contentHash, section, title, tagsJson, servings, instructions, " +
                        "hasIngredientsBlock, problemsJson, kind, mealSlotsJson, removedAt, rating, imageSourceHash, imageVersion) VALUES " +
                        "(1, 1, 'COOKING_SITE', 'recipes/stew.md', 'h1', 'recipes', 'Stew', '[]', 4, '', 1, '[]', 'MEAL', '[\"DINNER\"]', NULL, 4, 'p1', 'v1'), " +
                        "(2, 1, 'COOKING_SITE', 'sauces/salsa.md', 'h2', 'sauces', 'Salsa', '[]', 2, '', 1, '[]', 'COMPONENT', NULL, NULL, NULL, NULL, NULL), " +
                        "(3, 1, 'COOKING_SITE', 'recipes/gone.md', 'h3', 'recipes', 'Gone', '[]', 2, '', 1, '[]', 'MEAL', NULL, '2026-11-02T00:00:00Z', 5, NULL, NULL), " +
                        "(4, 1, 'MANUAL', NULL, NULL, 'own', 'Porridge', '[]', 1, '', 1, '[]', 'MEAL', '[\"BREAKFAST\"]', NULL, NULL, NULL, NULL), " +
                        "(5, 1, 'GENERATED', NULL, NULL, 'own', 'Curry', '[]', 4, '', 1, '[]', 'MEAL', NULL, NULL, 3, NULL, NULL), " +
                        "(6, 2, 'COOKING_SITE', 'recipes/stew.md', 'h1', 'recipes', 'Bob stew', '[]', 4, '', 1, '[]', 'MEAL', NULL, NULL, NULL, NULL, NULL)",
                )
                statement.execute(
                    "INSERT INTO recipeIngredient (recipeId, position, amount, unit, item) VALUES (1, 0, 500, 'g', 'beef'), (3, 0, 1, NULL, 'onion'), (6, 0, 400, 'g', 'beef')",
                )
                statement.execute(
                    "INSERT INTO planMeal (userId, weekStart, day, slot, recipeId, title, portions, kcal, proteinG, leftover, locked) " +
                        "VALUES (1, '2026-11-16', 0, 'DINNER', 1, 'Stew', 1.0, 600.0, 40.0, 0, 0)",
                )
                statement.execute("INSERT INTO userMeta (userId, key, value) VALUES (1, 'routines', '{\"breakfast\":4}')")
                statement.execute("PRAGMA user_version = 16")
            }
        }
        val oldColumns = columns(url, "recipe")
        fun recipes() = single(url, "SELECT ${oldColumns.joinToString(" || '|' || ") { "coalesce($it, 'NULL')" }} FROM recipe ORDER BY id")
        val tables = single(url, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").split(",")
        fun counts() = tables.associateWith { single(url, "SELECT count(*) FROM \"$it\"") }
        val before = recipes() to counts()

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        assertEquals(shapes(fresh), shapes(url), "every table as a fresh database has it")
        assertEquals("ok" to "", single(url, "PRAGMA integrity_check") to single(url, "PRAGMA foreign_key_check"))
        assertEquals(before, recipes() to counts(), "every row as it was, in every table")
        assertEquals(
            "1:1:1,2:1:1,3:0:0,4:1:1,5:1:1,6:1:1",
            single(url, "SELECT id || ':' || (addedAt IS NOT NULL) || ':' || inRotation FROM recipe ORDER BY id"),
            "all but the recipe that had left the site are added and in the rotation",
        )

        // The installed app lists what it listed before: every recipe but the one that had left the site.
        val foods = FoodRepository(db)
        val offers = OfferRepository(db, emptyList())
        fun scope(id: Long) = UserScope(id, db, foods, offers, PriceRepository(db, null, foods, offers), null, createTempDirectory("pictures").toFile())
        val alice = scope(1).recipes
        assertEquals(listOf("Curry", "Porridge", "Salsa", "Stew"), alice.list().map { it.title })
        assertTrue(alice.list().all { it.added && it.inRotation && !it.removedFromSite })
        assertEquals(4, alice.list().single { it.title == "Stew" }.rating, "the rating stays")
        assertEquals(emptyList(), alice.bank(), "the recipe that had left the site is hidden in the bank")
        assertEquals(listOf("Bob stew"), scope(2).recipes.list().map { it.title })

        // With its file back on the site, it is in the bank with its rating, not among Alice's recipes.
        val parser = HugoRecipeParser()
        val pages = listOf("recipes/stew.md" to "h1", "sauces/salsa.md" to "h2", "recipes/gone.md" to "h3b").map { (path, hash) ->
            SitePage(parser.parse(path, "---\ntitle: ${path.substringAfter('/').removeSuffix(".md")}\n---\n```ingredients\nservings: 2\n1 | | onion\n```"), hash)
        }
        alice.syncCookingSite(pages)
        val gone = alice.bank().single()
        assertEquals(Triple(3L, false, 5), Triple(gone.id, gone.added, gone.rating))
        assertEquals(listOf("Curry", "Porridge", "Salsa", "Stew"), alice.list().map { it.title })
        driver.close()
    }

    /**
     * Version 17 is the schema of 16.sqm, which servers before adjusted meals (issue 226) run. Only a table is
     * added: every row stays, every meal counts its recipe as before, and the meals can be adjusted from then on.
     */
    @Test
    fun aVersionSeventeenDatabaseKeepsEveryRowAndItsMealsCountTheirRecipes() {
        val file = createTempFile("v17", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        val week = "2026-11-16"
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropMealLines(statement)
                statement.execute("INSERT INTO appUser (id, subject, createdAt) VALUES (1, 'alice', '2026-11-01T00:00:00Z'), (2, 'bob', '2026-11-01T00:00:00Z')")
                statement.execute("INSERT INTO ingredient (id, userId, name) VALUES (1, 1, 'Beef'), (2, 1, 'Egg'), (3, 2, 'Beef')")
                statement.execute(
                    "INSERT INTO recipe (id, userId, source, section, title, tagsJson, servings, instructions, hasIngredientsBlock, problemsJson, kind, addedAt, inRotation) VALUES " +
                        "(1, 1, 'MANUAL', 'own', 'Stew', '[]', 2, '', 1, '[]', 'MEAL', '2026-11-01T00:00:00Z', 1), " +
                        "(2, 2, 'MANUAL', 'own', 'Bob stew', '[]', 2, '', 1, '[]', 'MEAL', '2026-11-01T00:00:00Z', 1)",
                )
                statement.execute("INSERT INTO recipeIngredient (recipeId, position, amount, unit, dimension, baseAmount, item) VALUES (1, 0, 400, 'g', 'WEIGHT', 400, 'beef'), (2, 0, 400, 'g', 'WEIGHT', 400, 'beef')")
                // Alice: an eaten dinner that took beef from her stock, and its leftover lunch. Bob: a planned dinner.
                statement.execute(
                    "INSERT INTO planMeal (userId, weekStart, day, slot, recipeId, title, portions, kcal, proteinG, leftover, locked, status) VALUES " +
                        "(1, '$week', 0, 'DINNER', 1, 'Stew', 1.0, 600.0, 40.0, 0, 0, 'EATEN'), (1, '$week', 1, 'LUNCH', 1, 'Stew', 1.0, 600.0, 40.0, 1, 0, 'PLANNED'), " +
                        "(2, '$week', 0, 'DINNER', 2, 'Bob stew', 1.0, 600.0, 40.0, 0, 0, 'PLANNED')",
                )
                statement.execute("INSERT INTO stock (ingredientId, fromWeek, source, addedAt, grams) VALUES (1, '$week', 'STOCK_UP', '2026-11-15T00:00:00Z', 600.0)")
                statement.execute(
                    "INSERT INTO stockUse (userId, weekStart, day, slot, ingredientId, fromWeek, source, addedAt, grams) VALUES " +
                        "(1, '$week', 0, 'DINNER', 1, '$week', 'STOCK_UP', '2026-11-15T00:00:00Z', 200.0), (1, '$week', 1, 'LUNCH', 1, '$week', 'STOCK_UP', '2026-11-15T00:00:00Z', 200.0)",
                )
                statement.execute("PRAGMA user_version = 17")
            }
        }
        val tables = single(url, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").split(",")
        fun counts() = tables.associateWith { single(url, "SELECT count(*) FROM \"$it\"") }
        fun meals() = single(url, "SELECT userId || ':' || day || ':' || slot || ':' || kcal || ':' || status FROM planMeal ORDER BY userId, day")
        val before = counts() to meals()

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        assertEquals(shapes(fresh), shapes(url), "every table as a fresh database has it")
        assertEquals("ok" to "", single(url, "PRAGMA integrity_check") to single(url, "PRAGMA foreign_key_check"))
        assertEquals(before, counts() to meals(), "every row as it was, in every table")
        assertEquals("0", single(url, "SELECT count(*) FROM mealLine"))

        // Alice's meals count their recipes as stored; her lunch can be adjusted, and its share moves by the egg it gets.
        val foods = FoodRepository(db)
        val offers = OfferRepository(db, emptyList())
        val alice = UserScope(1, db, foods, offers, PriceRepository(db, null, foods, offers), null, createTempDirectory("pictures").toFile())
        alice.profiles.save(Profile(80.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT))
        val plan = alice.plans.get(week)
        assertEquals(listOf(600.0, 600.0), plan.days.flatMap { it.meals }.map { it.kcal })
        assertTrue(plan.days.flatMap { it.meals }.none { it.adjusted })
        val lunch = alice.plans.adjust(week, 1, MealSlot.LUNCH, listOf(MealLineChange(1, 150.0)))
        assertTrue(lunch.adjusted)
        assertEquals("650.0", single(url, "SELECT grams FROM stock"), "the lunch's share of the beef went from 200 g to 150 g")
        alice.plans.setStatus(week, 0, MealSlot.DINNER, MealStatus.PLANNED)
        assertEquals("1000.0", single(url, "SELECT grams FROM stock"), "un-eaten, the dinner gives back both shares")
        driver.close()
    }

    /**
     * Version 18 is the schema of 17.sqm, which servers before a recipe's toppings (issue 226) run. Only a table
     * is added: every row stays, no recipe has a topping, and the meals' add-ons are what they had added.
     */
    @Test
    fun aVersionEighteenDatabaseKeepsEveryRowAndItsRecipesStartWithoutToppings() {
        val file = createTempFile("v18", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        val week = "2026-11-16"
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropRecipeToppings(statement)
                statement.execute("INSERT INTO appUser (id, subject, createdAt) VALUES (1, 'alice', '2026-11-01T00:00:00Z'), (2, 'bob', '2026-11-01T00:00:00Z')")
                statement.execute("INSERT INTO ingredient (id, userId, name) VALUES (1, 1, 'Rice'), (2, 1, 'Egg'), (3, 2, 'Rice')")
                statement.execute(
                    "INSERT INTO recipe (id, userId, source, section, title, tagsJson, servings, instructions, hasIngredientsBlock, problemsJson, kind, addedAt, inRotation) VALUES " +
                        "(1, 1, 'MANUAL', 'own', 'Bowl', '[]', 1, '', 1, '[]', 'MEAL', '2026-11-01T00:00:00Z', 1), " +
                        "(2, 2, 'MANUAL', 'own', 'Bob bowl', '[]', 1, '', 1, '[]', 'MEAL', '2026-11-01T00:00:00Z', 1)",
                )
                statement.execute("INSERT INTO recipeIngredient (recipeId, position, amount, unit, dimension, baseAmount, item) VALUES (1, 0, 100, 'g', 'WEIGHT', 100, 'rice'), (2, 0, 100, 'g', 'WEIGHT', 100, 'rice')")
                // Alice's bowl had an egg added; Bob's is as planned.
                statement.execute(
                    "INSERT INTO planMeal (userId, weekStart, day, slot, recipeId, title, portions, kcal, proteinG, leftover, locked, status) VALUES " +
                        "(1, '$week', 0, 'DINNER', 1, 'Bowl', 1.0, 400.0, 10.0, 0, 0, 'EATEN'), (2, '$week', 0, 'DINNER', 2, 'Bob bowl', 1.0, 400.0, 10.0, 0, 0, 'PLANNED')",
                )
                statement.execute("INSERT INTO mealLine (userId, weekStart, day, slot, ingredientId, grams) VALUES (1, '$week', 0, 'DINNER', 2, 60.0)")
                statement.execute("PRAGMA user_version = 18")
            }
        }
        val tables = single(url, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").split(",")
        fun counts() = tables.associateWith { single(url, "SELECT count(*) FROM \"$it\"") }
        fun lines() = single(url, "SELECT userId || ':' || day || ':' || slot || ':' || ingredientId || ':' || grams FROM mealLine ORDER BY userId")
        val before = counts() to lines()

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        assertEquals(shapes(fresh), shapes(url), "every table as a fresh database has it")
        assertEquals("ok" to "", single(url, "PRAGMA integrity_check") to single(url, "PRAGMA foreign_key_check"))
        assertEquals(before, counts() to lines(), "every row as it was, in every table")
        assertEquals("0", single(url, "SELECT count(*) FROM recipeTopping"))

        // Alice's bowl has no toppings yet; its add-ons are the egg its meal had, and a topping can be set.
        val foods = FoodRepository(db)
        val offers = OfferRepository(db, emptyList())
        fun scope(id: Long) = UserScope(id, db, foods, offers, PriceRepository(db, null, foods, offers), null, createTempDirectory("pictures").toFile())
        val alice = scope(1)
        assertEquals(emptyList(), alice.recipes.find(1)!!.toppings)
        assertEquals(listOf("Egg" to 60.0), alice.toppings.addOns(1).map { it.name to it.grams })
        assertEquals(listOf("Egg"), alice.toppings.set(1, ToppingsInput(listOf(ToppingInput(2, 60.0)))).toppings.map { it.name })
        assertEquals(emptyList(), scope(2).toppings.addOns(2))
        driver.close()
    }

    /**
     * Version 19 is the schema of 18.sqm, which servers before prepped meals (issue 234) run. Two columns and two tables
     * are added: every row stays, no recipe keeps until the account says so, no meal is of a batch, and a dinner with its
     * leftover lunch is cooked and eaten as before. A prep can be made from then on.
     */
    @Test
    fun aVersionNineteenDatabaseKeepsEveryRowAndItsMealsAreOfNoBatch() {
        val file = createTempFile("v19", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        val week = "2026-11-16"
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropPreps(statement)
                statement.execute("INSERT INTO appUser (id, subject, createdAt) VALUES (1, 'alice', '2026-11-01T00:00:00Z'), (2, 'bob', '2026-11-01T00:00:00Z')")
                statement.execute("INSERT INTO ingredient (id, userId, name) VALUES (1, 1, 'Beef'), (2, 1, 'Egg'), (3, 2, 'Beef')")
                statement.execute(
                    "INSERT INTO recipe (id, userId, source, section, title, tagsJson, servings, instructions, hasIngredientsBlock, problemsJson, kind, addedAt, inRotation) VALUES " +
                        "(1, 1, 'MANUAL', 'own', 'Stew', '[]', 2, '', 1, '[]', 'MEAL', '2026-11-01T00:00:00Z', 1), " +
                        "(2, 2, 'MANUAL', 'own', 'Bob stew', '[]', 2, '', 1, '[]', 'MEAL', '2026-11-01T00:00:00Z', 1)",
                )
                statement.execute("INSERT INTO recipeIngredient (recipeId, position, amount, unit, dimension, baseAmount, item) VALUES (1, 0, 400, 'g', 'WEIGHT', 400, 'beef'), (2, 0, 400, 'g', 'WEIGHT', 400, 'beef')")
                statement.execute("INSERT INTO recipeTopping (userId, recipeId, ingredientId, position, grams) VALUES (1, 1, 2, 0, 60.0)")
                // Alice: an eaten dinner that took beef from her stock, its leftover lunch, and an adjusted meal. Bob: a planned dinner.
                statement.execute(
                    "INSERT INTO planMeal (userId, weekStart, day, slot, recipeId, title, portions, kcal, proteinG, leftover, locked, status) VALUES " +
                        "(1, '$week', 0, 'DINNER', 1, 'Stew', 1.0, 600.0, 40.0, 0, 0, 'EATEN'), (1, '$week', 1, 'LUNCH', 1, 'Stew', 1.0, 600.0, 40.0, 1, 0, 'PLANNED'), " +
                        "(1, '$week', 3, 'DINNER', 1, 'Stew', 1.0, 600.0, 40.0, 0, 1, 'PLANNED'), (2, '$week', 0, 'DINNER', 2, 'Bob stew', 1.0, 600.0, 40.0, 0, 0, 'PLANNED')",
                )
                statement.execute("INSERT INTO mealLine (userId, weekStart, day, slot, ingredientId, grams) VALUES (1, '$week', 3, 'DINNER', 2, 60.0)")
                statement.execute("INSERT INTO stock (ingredientId, fromWeek, source, addedAt, grams) VALUES (1, '$week', 'STOCK_UP', '2026-11-15T00:00:00Z', 600.0)")
                statement.execute(
                    "INSERT INTO stockUse (userId, weekStart, day, slot, ingredientId, fromWeek, source, addedAt, grams) VALUES " +
                        "(1, '$week', 0, 'DINNER', 1, '$week', 'STOCK_UP', '2026-11-15T00:00:00Z', 200.0), (1, '$week', 1, 'LUNCH', 1, '$week', 'STOCK_UP', '2026-11-15T00:00:00Z', 200.0)",
                )
                statement.execute("PRAGMA user_version = 19")
            }
        }
        val tables = single(url, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").split(",")
        fun counts() = tables.associateWith { single(url, "SELECT count(*) FROM \"$it\"") }
        fun meals() = single(url, "SELECT userId || ':' || day || ':' || slot || ':' || leftover || ':' || locked || ':' || status FROM planMeal ORDER BY userId, day")
        val before = counts() to meals()

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        assertEquals(shapes(fresh), shapes(url), "every table as a fresh database has it")
        assertEquals("ok" to "", single(url, "PRAGMA integrity_check") to single(url, "PRAGMA foreign_key_check"))
        assertEquals(before, counts() to meals(), "every row as it was, in every table")
        assertEquals("0,0,0", listOf("prep", "prepPortion", "recipe WHERE keepsDays IS NOT NULL OR keepsVacuumDays IS NOT NULL").joinToString(",") { single(url, "SELECT count(*) FROM $it") })

        // Alice's meals are of no batch; the dinner still cooks its leftover lunch, and a prep of the stew can be made.
        val foods = FoodRepository(db)
        val offers = OfferRepository(db, emptyList())
        val alice = UserScope(1, db, foods, offers, PriceRepository(db, null, foods, offers), null, createTempDirectory("pictures").toFile(), today = { LocalDate.parse(week) })
        alice.profiles.save(Profile(80.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT))
        val meals = alice.plans.get(week).days.flatMap { it.meals }
        assertTrue(meals.none { it.prep != null })
        assertEquals(listOf(2.0, 0.0, 1.0), meals.map { it.cookPortions })
        alice.recipes.setKeeps(1, RecipeKeeps(days = 2))
        val prepped = alice.preps.create(week, PrepInput(1, "2026-11-20", meals = listOf(PrepSlot("2026-11-20", MealSlot.DINNER), PrepSlot("2026-11-21", MealSlot.LUNCH))))
        assertEquals(listOf(null, null, null, 1L, 1L), prepped.days.flatMap { it.meals }.map { it.prep?.id })
        assertEquals("1", single(url, "SELECT count(*) FROM planMeal WHERE userId = 2"), "Bob's plan as it was")
        driver.close()
    }

    /**
     * Version 20 is the schema of 19.sqm, which servers before sides (issue 234, step 2) run. stockUse is rebuilt with the
     * role of each share: every row stays, each as its meal's own, so un-eating gives back what it did; a side taken from
     * a batch then keeps its share apart from that of its meal's main.
     */
    @Test
    fun aVersionTwentyDatabaseKeepsWhatEveryMealTookFromTheStockAsItsOwn() {
        val file = createTempFile("v20", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        val week = "2026-11-16"
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropStockUseRoles(statement)
                statement.execute("INSERT INTO appUser (id, subject, createdAt) VALUES (1, 'alice', '2026-11-01T00:00:00Z'), (2, 'bob', '2026-11-01T00:00:00Z')")
                statement.execute("INSERT INTO ingredient (id, userId, name) VALUES (1, 1, 'Beef'), (2, 1, 'Lettuce'), (3, 2, 'Beef')")
                statement.execute(
                    "INSERT INTO recipe (id, userId, source, section, title, tagsJson, servings, instructions, hasIngredientsBlock, problemsJson, kind, addedAt, inRotation, keepsDays) VALUES " +
                        "(1, 1, 'MANUAL', 'own', 'Stew', '[]', 2, '', 1, '[]', 'MEAL', '2026-11-01T00:00:00Z', 1, 3), " +
                        "(2, 1, 'MANUAL', 'own', 'Salad', '[]', 4, '', 1, '[]', 'MEAL', '2026-11-01T00:00:00Z', 1, 3), " +
                        "(3, 2, 'MANUAL', 'own', 'Bob stew', '[]', 2, '', 1, '[]', 'MEAL', '2026-11-01T00:00:00Z', 1, NULL)",
                )
                statement.execute(
                    "INSERT INTO recipeIngredient (recipeId, position, amount, unit, dimension, baseAmount, item) VALUES " +
                        "(1, 0, 400, 'g', 'WEIGHT', 400, 'beef'), (2, 0, 400, 'g', 'WEIGHT', 400, 'lettuce'), (3, 0, 400, 'g', 'WEIGHT', 400, 'beef')",
                )
                // Alice: an eaten dinner and a prepped batch made on Tuesday, each with what it took of the beef; a dinner still to eat.
                // Bob: an eaten dinner that took beef of his own.
                statement.execute(
                    "INSERT INTO planMeal (userId, weekStart, day, slot, recipeId, title, portions, kcal, proteinG, leftover, locked, status) VALUES " +
                        "(1, '$week', 0, 'DINNER', 1, 'Stew', 1.0, 600.0, 40.0, 0, 0, 'EATEN'), (1, '$week', 1, 'DINNER', 1, 'Stew', 1.0, 600.0, 40.0, 0, 1, 'EATEN'), " +
                        "(1, '$week', 2, 'LUNCH', 1, 'Stew', 1.0, 600.0, 40.0, 0, 1, 'PLANNED'), (1, '$week', 3, 'DINNER', 1, 'Stew', 1.0, 600.0, 40.0, 0, 0, 'PLANNED'), " +
                        "(2, '$week', 0, 'DINNER', 3, 'Bob stew', 1.0, 600.0, 40.0, 0, 0, 'EATEN')",
                )
                statement.execute("INSERT INTO prep (id, userId, recipeId, madeOn, lastDay, vacuum) VALUES (1, 1, 1, '2026-11-17', '2026-11-20', 0)")
                statement.execute("INSERT INTO prepPortion (userId, weekStart, day, slot, role, prepId, portions) VALUES (1, '$week', 1, 'DINNER', 'MEAL', 1, NULL), (1, '$week', 2, 'LUNCH', 'MEAL', 1, NULL)")
                statement.execute("INSERT INTO stock (ingredientId, fromWeek, source, addedAt, grams) VALUES (1, '$week', 'STOCK_UP', '2026-11-15T00:00:00Z', 400.0), (3, '$week', 'STOCK_UP', '2026-11-15T00:00:00Z', 800.0)")
                statement.execute(
                    "INSERT INTO stockUse (userId, weekStart, day, slot, ingredientId, fromWeek, source, addedAt, grams) VALUES " +
                        "(1, '$week', 0, 'DINNER', 1, '$week', 'STOCK_UP', '2026-11-15T00:00:00Z', 200.0), (1, '$week', 1, 'DINNER', 1, '$week', 'STOCK_UP', '2026-11-15T00:00:00Z', 200.0), " +
                        "(1, '$week', 2, 'LUNCH', 1, '$week', 'STOCK_UP', '2026-11-15T00:00:00Z', 200.0), (2, '$week', 0, 'DINNER', 3, '$week', 'STOCK_UP', '2026-11-15T00:00:00Z', 200.0)",
                )
                statement.execute("PRAGMA user_version = 20")
            }
        }
        val tables = single(url, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").split(",")
        fun counts() = tables.associateWith { single(url, "SELECT count(*) FROM \"$it\"") }
        val uses = "userId || ':' || day || ':' || slot || ':' || ingredientId || ':' || fromWeek || ':' || source || ':' || addedAt || ':' || grams"
        fun stockUses() = single(url, "SELECT $uses FROM stockUse ORDER BY userId, day, slot")
        val before = counts() to stockUses()

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        assertEquals(shapes(fresh), shapes(url), "every table as a fresh database has it")
        assertEquals("ok" to "", single(url, "PRAGMA integrity_check") to single(url, "PRAGMA foreign_key_check"))
        assertEquals(before, counts() to stockUses(), "every row as it was, in every table")
        assertEquals("MEAL", single(url, "SELECT group_concat(DISTINCT role) FROM stockUse"), "each the share of its meal itself")

        // Alice's eaten dinner un-eaten gives back its beef; the batch un-made gives back both its meals'.
        val foods = FoodRepository(db)
        val offers = OfferRepository(db, emptyList())
        val alice = UserScope(1, db, foods, offers, PriceRepository(db, null, foods, offers), null, createTempDirectory("pictures").toFile(), today = { LocalDate.parse(week) })
        alice.profiles.save(Profile(80.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT))
        alice.plans.setStatus(week, 0, MealSlot.DINNER, MealStatus.PLANNED)
        assertEquals("600.0", single(url, "SELECT grams FROM stock WHERE ingredientId = 1"))
        alice.plans.setStatus(week, 1, MealSlot.DINNER, MealStatus.PLANNED)
        assertEquals("1000.0", single(url, "SELECT grams FROM stock WHERE ingredientId = 1"))

        // A side of the salad next to Thursday's dinner and Friday's: eaten with Thursday's, its lettuce is kept apart from the stew's beef.
        alice.plans.place(week, 4, MealSlot.DINNER, alice.recipes.candidate(1)!!)
        alice.preps.create(week, PrepInput(2, "2026-11-19", meals = listOf(PrepSlot("2026-11-19", MealSlot.DINNER), PrepSlot("2026-11-20", MealSlot.DINNER)), side = 0.5))
        alice.stock.putOnHandFrom(2, LocalDate.parse(week), 1000.0, StockSource.STOCK_UP)
        alice.plans.setStatus(week, 3, MealSlot.DINNER, MealStatus.EATEN)
        assertEquals("MEAL:1:200.0,SIDE:2:50.0", single(url, "SELECT role || ':' || ingredientId || ':' || grams FROM stockUse WHERE userId = 1 AND day = 3 ORDER BY role"))
        assertEquals("1", single(url, "SELECT count(*) FROM stockUse WHERE userId = 2"), "Bob's as it was")
        driver.close()
    }

    @Test
    fun aVersionTwentyOneDatabaseKeepsEveryRowAndNoRecipeIsAStandardMeal() {
        val file = createTempFile("v21", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        val week = "2026-11-16"
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropStandardMeals(statement)
                statement.execute("INSERT INTO appUser (id, subject, createdAt) VALUES (1, 'alice', '2026-11-01T00:00:00Z'), (2, 'bob', '2026-11-01T00:00:00Z')")
                statement.execute("INSERT INTO ingredient (id, userId, name, isStaple) VALUES (1, 1, 'Beef', 0), (2, 1, 'Bread', 1), (3, 2, 'Beef', 0)")
                statement.execute(
                    "INSERT INTO recipe (id, userId, source, section, title, tagsJson, servings, instructions, hasIngredientsBlock, problemsJson, kind, mealSlotsJson, addedAt, inRotation, keepsDays) VALUES " +
                        "(1, 1, 'MANUAL', 'own', 'Stew', '[]', 2, '', 1, '[]', 'MEAL', '[\"LUNCH\",\"DINNER\"]', '2026-11-01T00:00:00Z', 1, 3), " +
                        "(2, 1, 'MANUAL', 'own', 'Beef toast', '[]', 1, '', 1, '[]', 'MEAL', '[\"LUNCH\"]', '2026-11-01T00:00:00Z', 0, NULL), " +
                        "(3, 2, 'MANUAL', 'own', 'Bob stew', '[]', 2, '', 1, '[]', 'MEAL', NULL, '2026-11-01T00:00:00Z', 1, NULL)",
                )
                statement.execute(
                    "INSERT INTO recipeIngredient (recipeId, position, amount, unit, dimension, baseAmount, item) VALUES " +
                        "(1, 0, 400, 'g', 'WEIGHT', 400, 'beef'), (2, 0, 100, 'g', 'WEIGHT', 100, 'beef'), (2, 1, 80, 'g', 'WEIGHT', 80, 'bread'), (3, 0, 400, 'g', 'WEIGHT', 400, 'beef')",
                )
                // Alice: a batch of stew made on Tuesday (eaten) with Wednesday's lunch skipped, what it took of the beef; Bob: a dinner.
                statement.execute(
                    "INSERT INTO planMeal (userId, weekStart, day, slot, recipeId, title, portions, kcal, proteinG, leftover, locked, status) VALUES " +
                        "(1, '$week', 1, 'DINNER', 1, 'Stew', 1.0, 600.0, 40.0, 0, 1, 'EATEN'), (1, '$week', 2, 'LUNCH', 1, 'Stew', 1.0, 600.0, 40.0, 0, 1, 'SKIPPED'), " +
                        "(2, '$week', 0, 'DINNER', 3, 'Bob stew', 1.0, 600.0, 40.0, 0, 0, 'EATEN')",
                )
                statement.execute("INSERT INTO prep (id, userId, recipeId, madeOn, lastDay, vacuum) VALUES (1, 1, 1, '2026-11-17', '2026-11-20', 0)")
                statement.execute("INSERT INTO prepPortion (userId, weekStart, day, slot, role, prepId, portions) VALUES (1, '$week', 1, 'DINNER', 'MEAL', 1, NULL), (1, '$week', 2, 'LUNCH', 'MEAL', 1, NULL)")
                statement.execute("INSERT INTO stock (ingredientId, fromWeek, source, addedAt, grams) VALUES (1, '$week', 'STOCK_UP', '2026-11-15T00:00:00Z', 600.0)")
                statement.execute(
                    "INSERT INTO stockUse (userId, weekStart, day, slot, role, ingredientId, fromWeek, source, addedAt, grams) VALUES " +
                        "(1, '$week', 1, 'DINNER', 'MEAL', 1, '$week', 'STOCK_UP', '2026-11-15T00:00:00Z', 200.0), (1, '$week', 2, 'LUNCH', 'MEAL', 1, '$week', 'STOCK_UP', '2026-11-15T00:00:00Z', 200.0)",
                )
                statement.execute("PRAGMA user_version = 21")
            }
        }
        val tables = single(url, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").split(",")
        fun counts() = tables.associateWith { single(url, "SELECT count(*) FROM \"$it\"") }
        val columns = listOf("id", "userId", "title", "kind", "mealSlotsJson", "addedAt", "inRotation", "keepsDays")
        fun recipes() = single(url, "SELECT ${columns.joinToString(" || '|' || ") { "coalesce($it, 'NULL')" }} FROM recipe ORDER BY id")
        // The lines gain a column in a later version: compared by those they had.
        val lineColumns = columns(url, "recipeIngredient")
        val lines = rowsOf(url, "recipeIngredient", lineColumns)
        val before = Triple(counts(), recipes(), everythingBut(url, "recipe", "recipeIngredient"))

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        assertEquals(shapes(fresh), shapes(url), "every table as a fresh database has it")
        assertEquals("ok" to "", single(url, "PRAGMA integrity_check") to single(url, "PRAGMA foreign_key_check"))
        assertEquals(before, Triple(counts(), recipes(), everythingBut(url, "recipe", "recipeIngredient", "planPick", "offerExclusion", "accountRequest", "ingredientPortion")), "every row as it was, in every table")
        assertEquals(lines, rowsOf(url, "recipeIngredient", lineColumns))
        assertEquals("0", single(url, "SELECT group_concat(DISTINCT standardMeal) FROM recipe"), "no recipe is a standard meal")

        // Alice marks her toast and eats it in place of the skipped lunch: the share stays in the box with its beef, Bob's as it was.
        val foods = FoodRepository(db)
        val offers = OfferRepository(db, emptyList())
        val alice = UserScope(1, db, foods, offers, PriceRepository(db, null, foods, offers), null, createTempDirectory("pictures").toFile(), today = { LocalDate.parse(week) })
        alice.profiles.save(Profile(80.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT))
        assertEquals(listOf("Beef"), alice.recipes.setStandard(2, true).addedStaples)
        alice.standardMeals.choose(week, 2, MealSlot.LUNCH, StandardChoice(2, eaten = true))
        // The toast's own 0.75 servings of beef apart from the share's, which stays taken.
        assertEquals("BOX:1:200.0,MEAL:1:75.0", single(url, "SELECT role || ':' || ingredientId || ':' || grams FROM stockUse WHERE userId = 1 AND day = 2 ORDER BY role"))
        assertEquals("1", single(url, "SELECT count(*) FROM stockUse WHERE userId = 1 AND day = 1"), "Tuesday's as it was")
        assertEquals(1.0, alice.preps.spare("2026-11-18").single().portions)
        assertEquals("0", single(url, "SELECT isStaple FROM ingredient WHERE id = 3"), "Bob's beef is no staple")
        driver.close()
    }

    @Test
    fun aVersionTwentyTwoDatabaseKeepsEveryRowAndStartsWithNoDinnerChosen() {
        val file = createTempFile("v22", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        val week = "2026-11-16"
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropPlanPicks(statement)
                statement.execute("INSERT INTO appUser (id, subject, createdAt) VALUES (1, 'alice', '2026-11-01T00:00:00Z'), (2, 'bob', '2026-11-01T00:00:00Z')")
                statement.execute("INSERT INTO ingredient (id, userId, name, isStaple) VALUES (1, 1, 'Beef', 0), (2, 2, 'Beef', 0)")
                statement.execute(
                    "INSERT INTO recipe (id, userId, source, section, title, tagsJson, servings, instructions, hasIngredientsBlock, problemsJson, kind, mealSlotsJson, addedAt, inRotation) VALUES " +
                        "(1, 1, 'MANUAL', 'own', 'Stew', '[]', 4, '', 1, '[]', 'MEAL', '[\"DINNER\"]', '2026-11-01T00:00:00Z', 1), " +
                        "(2, 1, 'MANUAL', 'own', 'Soup', '[]', 4, '', 1, '[]', 'MEAL', '[\"DINNER\"]', '2026-11-01T00:00:00Z', 1), " +
                        "(3, 2, 'MANUAL', 'own', 'Bob stew', '[]', 4, '', 1, '[]', 'MEAL', NULL, '2026-11-01T00:00:00Z', 1)",
                )
                statement.execute(
                    "INSERT INTO recipeIngredient (recipeId, position, amount, unit, dimension, baseAmount, item) VALUES " +
                        "(1, 0, 1600, 'g', 'WEIGHT', 1600, 'beef'), (2, 0, 1200, 'g', 'WEIGHT', 1200, 'beef'), (3, 0, 1600, 'g', 'WEIGHT', 1600, 'beef')",
                )
                statement.execute(
                    "INSERT INTO planMeal (userId, weekStart, day, slot, recipeId, title, portions, kcal, proteinG, leftover, locked, status) VALUES " +
                        "(1, '$week', 1, 'DINNER', 1, 'Stew', 1.0, 600.0, 40.0, 0, 1, 'EATEN'), (2, '$week', 0, 'DINNER', 3, 'Bob stew', 1.0, 600.0, 40.0, 0, 0, 'PLANNED')",
                )
                statement.execute("PRAGMA user_version = 22")
            }
        }
        val tables = single(url, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").split(",")
        fun counts() = tables.associateWith { single(url, "SELECT count(*) FROM \"$it\"") }
        // The recipes and their lines gain a column in a later version: compared by those they had.
        val recipeColumns = columns(url, "recipe") to columns(url, "recipeIngredient")
        fun recipes() = rowsOf(url, "recipe", recipeColumns.first) to rowsOf(url, "recipeIngredient", recipeColumns.second)
        val before = Triple(counts(), recipes(), everythingBut(url, "planPick", "recipe", "recipeIngredient"))

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        assertEquals(shapes(fresh), shapes(url), "every table as a fresh database has it")
        assertEquals("ok" to "", single(url, "PRAGMA integrity_check") to single(url, "PRAGMA foreign_key_check"))
        assertEquals(
            before,
            Triple(counts(), recipes(), everythingBut(url, "planPick", "recipe", "recipeIngredient", "offerExclusion", "accountRequest", "ingredientPortion")),
            "every row as it was, in every table",
        )
        assertEquals("0", single(url, "SELECT count(*) FROM planPick"), "no dinner chosen, no recipe hidden")

        // Alice chooses her soup and hides her stew; deleting the soup takes its pick along, and Bob has none.
        val foods = FoodRepository(db)
        val offers = OfferRepository(db, emptyList())
        val alice = UserScope(1, db, foods, offers, PriceRepository(db, null, foods, offers), null, createTempDirectory("pictures").toFile(), today = { LocalDate.parse(week) })
        alice.profiles.save(Profile(80.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT))
        alice.planning.pick(week, 2)
        assertEquals(listOf(1L), alice.planning.hide(week, 1).notThisWeek.map { it.recipeId })
        assertEquals(listOf(2L), alice.planning.get(week).picks.map { it.recipeId })
        alice.recipes.delete(2)
        assertEquals("1:1", single(url, "SELECT recipeId || ':' || refused FROM planPick"), "the soup's pick went with it")
        assertEquals("0", single(url, "SELECT count(*) FROM planPick WHERE userId = 2"))
        driver.close()
    }

    @Test
    fun aVersionTwentyThreeDatabaseKeepsEveryRowAndStartsWithNoExclusion() {
        val file = createTempFile("v23", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropOfferExclusions(statement)
                statement.execute("INSERT INTO appUser (id, subject, createdAt) VALUES (1, 'alice', '2026-11-01T00:00:00Z'), (2, 'bob', '2026-11-01T00:00:00Z')")
                statement.execute("INSERT INTO ingredient (id, userId, name, isStaple) VALUES (1, 1, 'Curry powder', 0), (2, 2, 'Curry powder', 0)")
                statement.execute("INSERT INTO ingredientAlias (userId, text, ingredientId) VALUES (1, 'curry', 1), (2, 'curry', 2)")
                statement.execute(
                    "INSERT INTO offer (store, title, deal, price, originalPrice, packSize, category, validFrom, validUntil, url, imageUrl) VALUES " +
                        "('JUMBO', 'Hela curry', '25% korting', NULL, NULL, NULL, NULL, '2026-11-18', '2026-11-24', NULL, NULL)",
                )
                statement.execute("PRAGMA user_version = 23")
            }
        }
        val tables = single(url, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").split(",")
        fun counts() = tables.associateWith { single(url, "SELECT count(*) FROM \"$it\"") }
        val before = counts() to everythingBut(url, "offerExclusion")

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        assertEquals(shapes(fresh), shapes(url), "every table as a fresh database has it")
        assertEquals("ok" to "", single(url, "PRAGMA integrity_check") to single(url, "PRAGMA foreign_key_check"))
        assertEquals(before, counts() to everythingBut(url, "offerExclusion", "accountRequest", "ingredientPortion"), "every row as it was, in every table")
        assertEquals("0", single(url, "SELECT count(*) FROM offerExclusion"), "no exclusion yet")

        // Alice says Jumbo's Hela curry is no curry powder; Bob's match stays.
        val foods = FoodRepository(db)
        val offers = OfferRepository(db, emptyList())
        fun scope(id: Long) = UserScope(id, db, foods, offers, PriceRepository(db, null, foods, offers), null, createTempDirectory("pictures").toFile())
        scope(1).offerExclusions.exclude(OfferExclusionRequest(Store.JUMBO, "Hela curry", 1))
        fun matched(id: Long) = scope(id).ingredients.matchTerms().let { offers.state(LocalDate.parse("2026-11-18"), it).offers.single().ingredientId }
        assertEquals(null to 2L, matched(1) to matched(2))
        assertEquals("1:JUMBO:hela curry:1", single(url, "SELECT userId || ':' || store || ':' || titleKey || ':' || ingredientId FROM offerExclusion"))
        driver.close()
    }

    @Test
    fun aVersionTwentyFourDatabaseKeepsEveryRowAndStartsWithNoAccountRequest() {
        val file = createTempFile("v24", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropAccountRequests(statement)
                statement.execute("INSERT INTO appUser (id, subject, username, createdAt) VALUES (1, 'alice', 'alice', '2026-11-01T00:00:00Z'), (2, 'bob', 'Bob', '2026-11-01T00:00:00Z')")
                statement.execute("INSERT INTO ingredient (id, userId, name, isStaple) VALUES (1, 1, 'Curry powder', 0), (2, 2, 'Curry powder', 0)")
                statement.execute("INSERT INTO userMeta (userId, key, value) VALUES (1, 'profile', '{}')")
                statement.execute("PRAGMA user_version = 24")
            }
        }
        val tables = single(url, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").split(",")
        fun counts() = tables.associateWith { single(url, "SELECT count(*) FROM \"$it\"") }
        val before = counts() to everythingBut(url, "accountRequest")

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        assertEquals(shapes(fresh), shapes(url), "every table as a fresh database has it")
        assertEquals("ok" to "", single(url, "PRAGMA integrity_check") to single(url, "PRAGMA foreign_key_check"))
        assertEquals(before, counts() to everythingBut(url, "accountRequest", "ingredientPortion"), "every row as it was, in every table")
        assertEquals("0", single(url, "SELECT count(*) FROM accountRequest"), "nobody asked yet")
        // The user names of the accounts there are, in any case, are not to be had.
        assertEquals(listOf(1L, 1L, 0L), listOf("ALICE", "bob", "carl").map { db.registrationQueries.countAccountsNamed(it).executeAsOne() })
        driver.close()
    }

    /**
     * Version 25 is the schema of 24.sqm, which servers before lines of their own ingredient (issue 340) run. Every row
     * stays, every line is looked up by its text as before, and the recipes taken from the cooking site long ago are the
     * account's own copies: a later sync changes none of them, also not one whose file had left the site already, which
     * loses that mark, and none keeps or loses its picture, its plan, its prep, its toppings or its being a standard meal.
     */
    @Test
    fun aVersionTwentyFiveDatabaseKeepsEveryRowAndItsTakenRecipesNoLongerFollowTheSite() {
        val file = createTempFile("v25", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        val week = "2026-11-16"
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropLineIngredients(statement)
                statement.execute("INSERT INTO appUser (id, subject, createdAt) VALUES (1, 'alice', '2026-01-01T00:00:00Z'), (2, 'bob', '2026-01-01T00:00:00Z')")
                statement.execute("INSERT INTO ingredient (id, userId, name, isStaple) VALUES (1, 1, 'Beef', 1), (2, 1, 'Egg', 0), (3, 2, 'Beef', 0)")
                statement.execute("INSERT INTO ingredientAlias (userId, text, ingredientId) VALUES (1, 'beef', 1), (2, 'beef', 3)")
                // Alice: a stew taken long ago, with its picture, a prep, a topping, a plan and a standard meal; a soup taken
                // whose file left the site; a curry in the bank and one in the bank whose file left; porridge of her own.
                // Bob: the same stew, taken from the same site.
                statement.execute(
                    "INSERT INTO recipe (id, userId, source, sourcePath, contentHash, section, title, tagsJson, servings, instructions, hasIngredientsBlock, " +
                        "problemsJson, kind, mealSlotsJson, removedAt, rating, imageSourceHash, imageVersion, addedAt, inRotation, keepsDays, standardMeal) VALUES " +
                        "(1, 1, 'COOKING_SITE', 'recipes/stew.md', 'h1', 'recipes', 'Stew', '[\"winter\"]', 4, 'Brown it.', 1, '[]', 'MEAL', '[\"DINNER\"]', NULL, 4, 'p1', 'v1', '2026-02-01T00:00:00Z', 1, 3, 1), " +
                        "(2, 1, 'COOKING_SITE', 'soup/gone.md', 'h2', 'soup', 'Gone soup', '[]', 2, '', 1, '[]', 'MEAL', NULL, '2026-06-01T00:00:00Z', NULL, NULL, NULL, '2026-02-01T00:00:00Z', 0, NULL, 0), " +
                        "(3, 1, 'COOKING_SITE', 'recipes/curry.md', 'h3', 'recipes', 'Curry', '[]', 2, '', 1, '[]', 'MEAL', NULL, NULL, NULL, NULL, NULL, NULL, 0, NULL, 0), " +
                        "(4, 1, 'COOKING_SITE', 'recipes/old.md', 'h4', 'recipes', 'Old', '[]', 2, '', 1, '[]', 'MEAL', NULL, '2026-06-01T00:00:00Z', NULL, NULL, NULL, NULL, 0, NULL, 0), " +
                        "(5, 1, 'MANUAL', NULL, NULL, 'own', 'Porridge', '[]', 1, '', 1, '[]', 'MEAL', '[\"BREAKFAST\"]', NULL, NULL, NULL, NULL, '2026-02-01T00:00:00Z', 1, NULL, 0), " +
                        "(6, 2, 'COOKING_SITE', 'recipes/stew.md', 'h1', 'recipes', 'Bob stew', '[]', 4, '', 1, '[]', 'MEAL', NULL, NULL, NULL, NULL, NULL, '2026-02-01T00:00:00Z', 1, NULL, 0)",
                )
                statement.execute(
                    "INSERT INTO recipeIngredient (recipeId, position, amount, unit, dimension, baseAmount, item, groupName) VALUES " +
                        "(1, 0, 1200, 'g', 'WEIGHT', 1200, 'beef', 'Stew'), (1, 1, 1, 'onion', 'COUNT', NULL, 'large', 'Stew'), (2, 0, 1, NULL, NULL, NULL, 'leek', NULL), " +
                        "(3, 0, 300, 'g', 'WEIGHT', 300, 'beef', NULL), (4, 0, 300, 'g', 'WEIGHT', 300, 'beef', NULL), (5, 0, 80, 'g', 'WEIGHT', 80, 'oats', NULL), " +
                        "(6, 0, 1200, 'g', 'WEIGHT', 1200, 'beef', NULL)",
                )
                statement.execute(
                    "INSERT INTO planMeal (userId, weekStart, day, slot, recipeId, title, portions, kcal, proteinG, leftover, locked, status) VALUES " +
                        "(1, '$week', 1, 'DINNER', 1, 'Stew', 1.0, 600.0, 40.0, 0, 1, 'EATEN'), (1, '$week', 2, 'LUNCH', 1, 'Stew', 1.0, 600.0, 40.0, 0, 1, 'PLANNED'), " +
                        "(2, '$week', 0, 'DINNER', 6, 'Bob stew', 1.0, 600.0, 40.0, 0, 0, 'PLANNED')",
                )
                statement.execute("INSERT INTO prep (id, userId, recipeId, madeOn, lastDay, vacuum) VALUES (1, 1, 1, '2026-11-17', '2026-11-19', 0)")
                statement.execute("INSERT INTO prepPortion (userId, weekStart, day, slot, role, prepId, portions) VALUES (1, '$week', 1, 'DINNER', 'MEAL', 1, NULL), (1, '$week', 2, 'LUNCH', 'MEAL', 1, NULL)")
                statement.execute("INSERT INTO recipeTopping (userId, recipeId, ingredientId, position, grams) VALUES (1, 1, 2, 0, 60.0)")
                statement.execute("INSERT INTO planPick (userId, weekStart, recipeId, refused, position) VALUES (1, '2026-11-23', 1, 0, 0)")
                statement.execute("INSERT INTO userMeta (userId, key, value) VALUES (1, 'routines', '{\"breakfast\":5}')")
                statement.execute("PRAGMA user_version = 25")
            }
        }
        val tables = single(url, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").split(",")
        fun counts() = tables.associateWith { single(url, "SELECT count(*) FROM \"$it\"") }
        val recipeColumns = columns(url, "recipe") - "removedAt"
        val lineColumns = columns(url, "recipeIngredient")
        fun rows() = listOf(rowsOf(url, "recipe", recipeColumns), rowsOf(url, "recipeIngredient", lineColumns))
        fun removed() = single(url, "SELECT id || ':' || (removedAt IS NOT NULL) FROM recipe ORDER BY id")
        val before = listOf(counts(), rows(), everythingBut(url, "recipe", "recipeIngredient"))
        assertEquals("1:0,2:1,3:0,4:1,5:0,6:0", removed())

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        assertEquals(shapes(fresh), shapes(url), "every table as a fresh database has it")
        assertEquals("ok" to "", single(url, "PRAGMA integrity_check") to single(url, "PRAGMA foreign_key_check"))
        val after = listOf(counts(), rows(), everythingBut(url, "recipe", "recipeIngredient", "ingredientPortion"))
        assertEquals(before, after, "every row as it was, in every table")
        assertEquals("1:0,2:0,3:0,4:1,5:0,6:0", removed(), "the taken soup is no longer marked; the bank's curry whose file left still is")
        assertEquals("", single(url, "SELECT group_concat(id) FROM recipeIngredient WHERE ingredientId IS NOT NULL"), "no line has an ingredient of its own")
        assertEquals("", single(url, "SELECT group_concat(id) FROM recipe WHERE editedAt IS NOT NULL"), "none saved in the app")

        // Alice's site: the stew changed, the soup still gone, the curry changed, the old one back.
        val foods = FoodRepository(db)
        val offers = OfferRepository(db, emptyList())
        fun scope(id: Long) = UserScope(id, db, foods, offers, PriceRepository(db, null, foods, offers), null, createTempDirectory("pictures").toFile(), today = { LocalDate.parse(week) })
        val alice = scope(1)
        val stew = alice.recipes.find(1)!!
        val parser = HugoRecipeParser()
        fun site(vararg files: Pair<String, String>) = files.map { (path, hash) ->
            SitePage(parser.parse(path, "---\ntitle: New ${path.substringAfter('/').removeSuffix(".md")}\n---\n```ingredients\nservings: 2\n100 | g | beef\n```"), hash)
        }
        val pages = site("recipes/stew.md" to "h1b", "recipes/curry.md" to "h3b", "recipes/old.md" to "h4")
        // The curry and the old one are the bank's; the stew is taken, and the soup's file is gone from a copy that is hers.
        assertEquals(Triple(2, 0, 1), alice.recipes.syncCookingSite(pages).let { Triple(it.updated, it.removed, it.unchanged) })
        alice.pictures.sync(pages)
        assertEquals(stew, alice.recipes.find(1), "the stew as it was, picture and all")
        assertEquals("/api/recipes/1/image?v=v1", stew.summary.imageUrl)
        assertEquals(listOf("Gone soup", "Porridge", "Stew"), alice.recipes.list().map { it.title })
        assertTrue(alice.recipes.list().none { it.removedFromSite })
        assertEquals(listOf("New curry", "New old"), alice.recipes.bank().map { it.title }, "the bank follows the site; the taken stew is not in it")
        assertEquals(listOf(1L), alice.recipes.standardMeals().map { it.recipeId })
        assertEquals(listOf("Egg"), alice.recipes.toppings(1).map { it.name })
        assertEquals("1:MEAL,2:MEAL", single(url, "SELECT day || ':' || role FROM prepPortion WHERE userId = 1 ORDER BY day"))

        // The stew is hers to edit and stays edited, its file changed or gone; the soup, never edited, goes back to the
        // bank, hidden while its file is away.
        val edited = alice.recipes.update(1, RecipeInput("My stew", 4, "1000 | g | beef", "Brown it well.", listOf("winter")))
        alice.recipes.syncCookingSite(site("recipes/stew.md" to "h1c", "recipes/curry.md" to "h3b", "recipes/old.md" to "h4"))
        assertEquals(edited, alice.recipes.find(1))
        alice.recipes.setAdded(2, AddedInput(added = false))
        alice.recipes.syncCookingSite(site("recipes/curry.md" to "h3b", "recipes/old.md" to "h4"))
        assertEquals(edited, alice.recipes.find(1))
        assertEquals(listOf("My stew", "Porridge"), alice.recipes.list().map { it.title })
        assertEquals(listOf("New curry", "New old"), alice.recipes.bank().map { it.title })
        assertEquals("Bob stew", scope(2).recipes.find(6)!!.summary.title, "Bob's copy is his")
        driver.close()
    }

    /**
     * Version 26 is the schema of 25.sqm, which servers before an ingredient's own values and portions (issue 351) run.
     * Every row stays as it was, no ingredient has values of its own or a portion, and every recipe counts as it did:
     * by its NEVO foods, a drink by its volume, and a line in a unit such as "bar" by the weight per piece.
     */
    @Test
    fun aVersionTwentySixDatabaseKeepsEveryRowAndEveryRecipeCountsAsItDid() {
        val file = createTempFile("v26", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        val week = "2026-11-16"
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropProducts(statement)
                statement.execute("INSERT INTO appUser (id, subject, createdAt) VALUES (1, 'alice', '2026-01-01T00:00:00Z'), (2, 'bob', '2026-01-01T00:00:00Z')")
                statement.execute(
                    "INSERT INTO food (nevoCode, nameNl, nameEn, synonym, groupNl, groupEn, basis, kcal, protein, fat, saturatedFat, carbs, sugar, fibre, sodiumMg) VALUES " +
                        "(10, 'Rundvlees', 'Beef raw', NULL, 'Vlees', 'Meat', 'PER_100_G', 150, 20, 4, 2, 0, 0, 0, 50), " +
                        "(20, 'Mueslireep', 'Muesli bar', NULL, 'Snacks', 'Snacks', 'PER_100_G', 450, 7, 18, 8, 60, 30, 4, 100), " +
                        "(305, 'Melk halfvol', 'Milk semi-skimmed', NULL, 'Zuivel', 'Dairy', 'PER_100_ML', 46, 3, 2, 1, 5, 5, 0, 40)",
                )
                statement.execute(
                    "INSERT INTO ingredient (id, userId, name, nevoCode, gramsPerPiece, gramsPerMl, isStaple) VALUES " +
                        "(1, 1, 'Beef', 10, NULL, NULL, 0), (2, 1, 'Muesli bar', 20, 40, NULL, 0), (3, 1, 'Milk', 305, NULL, NULL, 0), (4, 2, 'Beef', 10, NULL, NULL, 0)",
                )
                statement.execute("INSERT INTO ingredientAlias (userId, text, ingredientId) VALUES (1, 'beef', 1)")
                statement.execute(
                    "INSERT INTO recipe (id, userId, source, section, title, tagsJson, servings, instructions, hasIngredientsBlock, problemsJson, kind, " +
                        "mealSlotsJson, addedAt, inRotation) VALUES " +
                        "(1, 1, 'MANUAL', 'own', 'Stew', '[]', 2, '', 1, '[]', 'MEAL', '[\"DINNER\"]', '2026-02-01T00:00:00Z', 1), " +
                        "(2, 1, 'MANUAL', 'own', 'Snack', '[]', 1, '', 1, '[]', 'MEAL', '[\"SNACK\"]', '2026-02-01T00:00:00Z', 1), " +
                        "(3, 2, 'MANUAL', 'own', 'Stew', '[]', 1, '', 1, '[]', 'MEAL', '[\"DINNER\"]', '2026-02-01T00:00:00Z', 1)",
                )
                statement.execute(
                    "INSERT INTO recipeIngredient (recipeId, position, amount, unit, dimension, baseAmount, item, groupName, ingredientId) VALUES " +
                        "(1, 0, 400, 'g', 'WEIGHT', 400, 'beef', NULL, NULL), (1, 1, 250, 'ml', 'VOLUME', 250, 'milk', NULL, 3), " +
                        "(2, 0, 2, 'bar', 'COUNT', NULL, 'muesli bar', NULL, 2), (3, 0, 400, 'g', 'WEIGHT', 400, 'beef', NULL, 4)",
                )
                statement.execute(
                    "INSERT INTO planMeal (userId, weekStart, day, slot, recipeId, title, portions, kcal, proteinG, leftover, locked, status) VALUES " +
                        "(1, '$week', 1, 'DINNER', 1, 'Stew', 1.0, 357.5, 43.75, 0, 1, 'EATEN'), (1, '$week', 1, 'SNACK', 2, 'Snack', 1.0, 360.0, 5.6, 0, 0, 'PLANNED')",
                )
                statement.execute("PRAGMA user_version = 26")
            }
        }
        val tables = single(url, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").split(",")
        fun counts() = tables.associateWith { single(url, "SELECT count(*) FROM \"$it\"") }
        val ingredientColumns = columns(url, "ingredient")
        val before = listOf(counts(), rowsOf(url, "ingredient", ingredientColumns), everythingBut(url, "ingredient", "ingredientPortion"))

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        assertEquals(shapes(fresh), shapes(url), "every table as a fresh database has it")
        assertEquals("ok" to "", single(url, "PRAGMA integrity_check") to single(url, "PRAGMA foreign_key_check"))
        assertEquals(before, listOf(counts(), rowsOf(url, "ingredient", ingredientColumns), everythingBut(url, "ingredient", "ingredientPortion")), "every row as it was")
        assertEquals("0", single(url, "SELECT count(*) FROM ingredient WHERE " + labelColumns.joinToString(" OR ") { "$it IS NOT NULL" }), "no values of its own")
        assertEquals("0", single(url, "SELECT count(*) FROM ingredientPortion"), "no portions")

        val foods = FoodRepository(db)
        val offers = OfferRepository(db, emptyList())
        fun scope(id: Long) = UserScope(id, db, foods, offers, PriceRepository(db, null, foods, offers), null, createTempDirectory("pictures").toFile(), today = { LocalDate.parse(week) })
        assertTrue(listOf(1L, 2L).flatMap { scope(it).ingredients.list() }.all { it.label == null && it.portions.isEmpty() && it.removedPortions.isEmpty() })
        fun counted(account: Long, recipe: Long) = scope(account).recipes.find(recipe)!!.let { detail ->
            val nutrition = detail.summary.nutrition!!
            listOf(detail.ingredients.map { it.grams }, listOf(nutrition.perServing.kcal, nutrition.perServing.proteinG).map { (it!! * 1000).roundToLong() / 1000.0 }, listOf(nutrition.complete, nutrition.approximate))
        }
        // 400 g of beef and 250 ml of milk counted per 100 ml (weighed at 1 g/ml), in two servings.
        assertEquals(listOf(listOf(400.0, 250.0), listOf(357.5, 43.75), listOf(true, false)), counted(1, 1))
        // Two bars counted in pieces of 40 g, as before: "bar" is no portion of it.
        assertEquals(listOf(listOf(80.0), listOf(360.0, 5.6), listOf(true, false)), counted(1, 2))
        assertEquals(listOf(listOf(400.0), listOf(600.0, 80.0), listOf(true, false)), counted(2, 3))
        driver.close()
    }

    /**
     * Version 27 to 28 (27.sqm, issue 358): every row stays as it was, every recipe is one of the account's as before (none
     * is the foods of a slot) and every planned meal its recipe alone, read as before: the same meals, none with foods, the
     * same recipes mine and in the bank.
     */
    @Test
    fun aVersionTwentySevenDatabaseKeepsEveryRowAndEveryPlanAsItWas() {
        val file = createTempFile("v27", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        openDatabase(url).first.close()
        val week = "2026-11-16"
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                dropSlotFoods(statement)
                statement.execute("INSERT INTO appUser (id, subject, createdAt) VALUES (1, 'alice', '2026-01-01T00:00:00Z'), (2, 'bob', '2026-01-01T00:00:00Z')")
                statement.execute(
                    "INSERT INTO food (nevoCode, nameNl, nameEn, synonym, groupNl, groupEn, basis, kcal, protein, fat, saturatedFat, carbs, sugar, fibre, sodiumMg) VALUES " +
                        "(10, 'Rundvlees', 'Beef raw', NULL, 'Vlees', 'Meat', 'PER_100_G', 150, 20, 4, 2, 0, 0, 0, 50)",
                )
                statement.execute("INSERT INTO ingredient (id, userId, name, nevoCode, gramsPerPiece, gramsPerMl, isStaple) VALUES (1, 1, 'Beef', 10, NULL, NULL, 0), (2, 2, 'Beef', 10, NULL, NULL, 0)")
                statement.execute(
                    "INSERT INTO recipe (id, userId, source, sourcePath, section, title, tagsJson, servings, instructions, hasIngredientsBlock, problemsJson, kind, " +
                        "addedAt, inRotation) VALUES " +
                        "(1, 1, 'MANUAL', NULL, 'own', 'Stew', '[]', 2, '', 1, '[]', 'MEAL', '2026-02-01T00:00:00Z', 1), " +
                        "(2, 1, 'COOKING_SITE', 'recipes/soup.md', 'recipes', 'Soup', '[]', 2, '', 1, '[]', 'MEAL', NULL, 0), " +
                        "(3, 2, 'MANUAL', NULL, 'own', 'Stew', '[]', 1, '', 1, '[]', 'MEAL', '2026-02-01T00:00:00Z', 1)",
                )
                statement.execute(
                    "INSERT INTO recipeIngredient (recipeId, position, amount, unit, dimension, baseAmount, item, groupName, ingredientId) VALUES " +
                        "(1, 0, 400, 'g', 'WEIGHT', 400, 'beef', NULL, 1), (2, 0, 300, 'g', 'WEIGHT', 300, 'beef', NULL, NULL), (3, 0, 400, 'g', 'WEIGHT', 400, 'beef', NULL, 2)",
                )
                statement.execute(
                    "INSERT INTO planMeal (userId, weekStart, day, slot, recipeId, title, portions, kcal, proteinG, leftover, locked, status) VALUES " +
                        "(1, '$week', 0, 'DINNER', 1, 'Stew', 1.0, 300.0, 40.0, 0, 1, 'EATEN'), (1, '$week', 1, 'LUNCH', 1, 'Stew', 1.0, 300.0, 40.0, 1, 0, 'PLANNED'), " +
                        "(1, '$week', 2, 'DINNER', 1, 'Stew', 1.5, 450.0, 60.0, 0, 0, 'SKIPPED'), (2, '$week', 0, 'DINNER', 3, 'Stew', 1.0, 600.0, 80.0, 0, 0, 'PLANNED')",
                )
                statement.execute("INSERT INTO mealLine (userId, weekStart, day, slot, ingredientId, grams) VALUES (1, '$week', 0, 'DINNER', 1, 150)")
                statement.execute("PRAGMA user_version = 27")
            }
        }
        val tables = single(url, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").split(",")
        fun counts() = tables.associateWith { single(url, "SELECT count(*) FROM \"$it\"") }
        val recipeColumns = columns(url, "recipe")
        val mealColumns = columns(url, "planMeal")
        fun meals() = single(url, "SELECT ${mealColumns.joinToString(" || '|' || ") { "coalesce($it, 'NULL')" }} FROM planMeal ORDER BY userId, weekStart, day, slot")
        fun rows() = listOf(counts(), rowsOf(url, "recipe", recipeColumns), meals(), everythingBut(url, "recipe", "planMeal"))
        val before = rows()

        val (driver, db) = openDatabase(url)

        assertEquals(Database.Schema.version.toInt(), version(url))
        val fresh = "jdbc:sqlite:${createTempFile("fresh", ".db").toFile().apply { delete() }.path}"
        openDatabase(fresh).first.close()
        assertEquals(shapes(fresh), shapes(url), "every table as a fresh database has it")
        assertEquals("ok" to "", single(url, "PRAGMA integrity_check") to single(url, "PRAGMA foreign_key_check"))
        assertEquals(before, rows(), "every row as it was")
        assertEquals("3:0", single(url, "SELECT count(*) || ':' || sum(slotFoods) FROM recipe"), "no recipe is the foods of a slot")
        assertEquals("4:0", single(url, "SELECT count(*) || ':' || count(foodsId) FROM planMeal"), "no meal has foods")

        val foods = FoodRepository(db)
        val offers = OfferRepository(db, emptyList())
        fun scope(id: Long) = UserScope(id, db, foods, offers, PriceRepository(db, null, foods, offers), null, createTempDirectory("pictures").toFile(), today = { LocalDate.parse(week) })
        val alice = scope(1)
        val meals = alice.plans.meals(LocalDate.parse(week))
        assertEquals(listOf("0 DINNER Stew 1.0 300.0 EATEN", "1 LUNCH Stew 1.0 300.0 PLANNED", "2 DINNER Stew 1.5 450.0 SKIPPED"), meals.map { "${it.day} ${it.slot} ${it.title} ${it.portions} ${it.kcal} ${it.status}" })
        assertTrue(meals.all { it.foodsId == null && !it.foodsOnly && it.foods.isEmpty() && it.recipeId == 1L }, "every meal is its recipe alone")
        assertEquals(listOf(1L) to listOf(2L), alice.recipes.list().map { it.id } to alice.recipes.bank().map { it.id }, "the same recipes mine and in the bank")
        assertEquals(listOf(3L), scope(2).plans.meals(LocalDate.parse(week)).map { it.recipeId })
        driver.close()
    }

    /** The rows of [table] in [columns] only, by id: a table compared by the columns it had before a migration added one. */
    private fun rowsOf(url: String, table: String, columns: List<String>): String =
        single(url, "SELECT ${columns.joinToString(" || '|' || ") { "coalesce($it, 'NULL')" }} FROM \"$table\" ORDER BY id")

    /**
     * Every row of every table of the file but [tables], to compare before and after a migration that adds a column to one
     * of them; a table a later migration adds is one of them too. The ingredients' label columns, which 26.sqm adds, and
     * the columns of slots' foods, which 27.sqm adds, are left out, so that a migration before them is compared by what it changed.
     */
    private fun everythingBut(url: String, vararg tables: String): List<String> = DriverManager.getConnection(url).use { connection ->
        fun query(sql: String): List<String> = connection.createStatement().executeQuery(sql).use { rows ->
            val count = rows.metaData.columnCount
            generateSequence { if (rows.next()) (1..count).joinToString("|") { rows.getString(it).orEmpty() } else null }.toList()
        }
        query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .filter { it !in tables }
            .flatMap { name ->
                val columns = columns(url, name).filterNot { name == "ingredient" && it in labelColumns || slotFoodsColumns[name] == it }
                listOf(name) + query("SELECT ${columns.joinToString { "\"$it\"" }} FROM \"$name\"")
            }
    }

    /** Everything the file holds: its schema, its version and every row of every table, to compare before and after. */
    private fun everything(url: String): List<String> = DriverManager.getConnection(url).use { connection ->
        fun query(sql: String): List<String> = connection.createStatement().executeQuery(sql).use { rows ->
            val count = rows.metaData.columnCount
            generateSequence { if (rows.next()) (1..count).joinToString("|") { rows.getString(it).orEmpty() } else null }.toList()
        }
        val tables = query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        query("SELECT type, name, sql FROM sqlite_master ORDER BY name") + query("PRAGMA user_version") +
            tables.flatMap { table -> listOf(table) + query("SELECT * FROM \"$table\"") }
    }

    /**
     * docs/deploy.md says a migration that fails changes nothing. The migrations of a start run in one
     * transaction together with the new `user_version` (SQLDelight's JdbcSqliteDriver), and SQLite's DDL is
     * transactional, so a failure half-way, after tables were rebuilt and rows moved, rolls it all back.
     */
    @Test
    fun aMigrationThatFailsLeavesTheDatabaseAsItWas() {
        val file = createTempFile("v1", ".db").toFile().apply { delete() }
        val url = "jdbc:sqlite:${file.path}"
        val v1 = checkNotNull(javaClass.getResource("/schema/v1.sql")).readText()
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                v1.lines().joinToString("\n") { it.substringBefore("--") }
                    .split(";").map { it.trim() }.filter { it.isNotEmpty() }
                    .forEach(statement::execute)
                statement.execute("INSERT INTO recipe (source, section, title, tagsJson, instructions, hasIngredientsBlock, problemsJson, kind) VALUES ('MANUAL', 'own', 'Old', '[]', '', 1, '[]', 'MEAL')")
                statement.execute("PRAGMA user_version = 1")
            }
        }
        val before = everything(url)

        // All real migrations run, then one more statement fails: the last step of a migration going wrong.
        val failing = object : SqlSchema<QueryResult.Value<Unit>> by Database.Schema {
            override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> {
                Database.Schema.migrate(driver, oldVersion, newVersion, *callbacks)
                driver.execute(null, "ALTER TABLE noSuchTable ADD COLUMN x TEXT", 0, null)
                return QueryResult.Unit
            }
        }
        val properties = Properties().apply { put("foreign_keys", "true"); put("journal_mode", "WAL") }
        assertFailsWith<Exception> { JdbcSqliteDriver(url, properties, failing) }

        assertEquals(before, everything(url))
        assertEquals(1, version(url))
        // And the next start, with the real migrations, still works.
        openDatabase(url).first.close()
        assertEquals(Database.Schema.version.toInt(), version(url))
    }
}
