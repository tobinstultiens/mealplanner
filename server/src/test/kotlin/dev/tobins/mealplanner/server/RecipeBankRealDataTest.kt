package dev.tobins.mealplanner.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.server.db.Database
import java.io.File
import java.sql.DriverManager
import java.util.Properties
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Migrates a copy of a real database across the recipe bank (16.sqm) and checks that every table keeps its
 * rows, every recipe what it had, and that what each account listed before is what it lists after: the
 * recipes on the site or made in the app are added and in the rotation, those that had left the site are
 * not. The same across the recipes taken from the site becoming the account's own (25.sqm, issue 340).
 * And across an ingredient's own values and portions (26.sqm, issue 351), and the foods of a slot (27.sqm, issue 358).
 * Prints row counts and integrity checks. Runs only when RECIPES_DB names a database file; that file
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

    /**
     * Across 25.sqm (issue 340): every table keeps its rows and every recipe and line what it had; only the taken
     * recipes whose file had left the site lose that mark, and no line has an ingredient of its own. Then every account's
     * recipes read as they did, and a sync of a site with no file at all leaves every taken one as it was: the bank's
     * are hidden, the taken ones stay. Run as the test above.
     */
    @Test
    fun aCopyOfARealDatabaseKeepsEveryRowAndEveryTakenRecipeAsItWas() {
        val original = System.getenv("RECIPES_DB")?.let(::File) ?: return
        val copy = File(createTempDirectory("recipes").toFile(), "copy.db")
        original.copyTo(copy)
        val url = "jdbc:sqlite:${copy.path}"
        migrate(url, TAKEN_VERSION - 1)
        val before = counts(url)
        val recipes = query(url, "SELECT ${columnsOf(url, "recipe").filter { it != "removedAt" }.joined()} FROM recipe ORDER BY id")
        val lines = query(url, "SELECT ${columnsOf(url, "recipeIngredient").joined()} FROM recipeIngredient ORDER BY id")
        val unmarked = query(url, "SELECT id FROM recipe WHERE removedAt IS NOT NULL AND addedAt IS NOT NULL AND source = 'COOKING_SITE' ORDER BY id")
        val stillMarked = query(url, "SELECT id FROM recipe WHERE removedAt IS NOT NULL AND NOT (addedAt IS NOT NULL AND source = 'COOKING_SITE') ORDER BY id")
        println("Before: version ${version(url)}, checks ${checks(url)}, rows $before, taken copies losing the removed mark ${unmarked.size}")

        migrate(url, TAKEN_VERSION)
        println("After: version ${version(url)}, checks ${checks(url)}, rows ${counts(url)}")
        assertEquals(listOf("ok") to emptyList(), checks(url))
        assertEquals(before, counts(url), "every table keeps its rows")
        assertEquals(recipes, query(url, "SELECT ${columnsOf(url, "recipe").filter { it != "removedAt" && it != "editedAt" }.joined()} FROM recipe ORDER BY id"))
        assertEquals(lines, query(url, "SELECT ${columnsOf(url, "recipeIngredient").filter { it != "ingredientId" }.joined()} FROM recipeIngredient ORDER BY id"))
        assertEquals(stillMarked, query(url, "SELECT id FROM recipe WHERE removedAt IS NOT NULL ORDER BY id"))
        assertEquals(listOf("0", "0"), query(url, "SELECT (SELECT count(*) FROM recipeIngredient WHERE ingredientId IS NOT NULL) UNION ALL SELECT count(*) FROM recipe WHERE editedAt IS NOT NULL"))

        val (driver, db) = openDatabase(url)
        val foods = FoodRepository(db)
        val offers = OfferRepository(db, emptyList())
        for (account in UserRepository(db).all()) {
            val recipes = UserScope(account, db, foods, offers, PriceRepository(db, null, foods, offers), null, createTempDirectory("pictures").toFile()).recipes
            val taken = recipes.list().filter { it.source == RecipeSource.COOKING_SITE }.associate { it.id to recipes.find(it.id) }
            val result = recipes.syncCookingSite(emptyList())
            println("Account $account: ${taken.size} taken, ${recipes.list().size} mine; a sync of no file hides ${result.removed}")
            assertEquals(taken, taken.keys.associateWith { recipes.find(it) }, "account $account: every taken recipe as it was")
            assertEquals(emptyList(), recipes.bank())
        }
        driver.close()
    }

    /**
     * Across 26.sqm (issue 351): every table keeps its rows and every row what it had, no ingredient has values of its own
     * or a portion, and every account's ingredients and recipes read as before: with no label and no portion anywhere, a
     * recipe is counted by the NEVO foods and weights per piece alone, as it was. Run as the tests above; prints each
     * account's recipes and how many of them are counted in full.
     */
    @Test
    fun aCopyOfARealDatabaseKeepsEveryRowAndEveryIngredientWithoutValuesOrPortionsOfItsOwn() {
        val original = System.getenv("RECIPES_DB")?.let(::File) ?: return
        val copy = File(createTempDirectory("recipes").toFile(), "copy.db")
        original.copyTo(copy)
        val url = "jdbc:sqlite:${copy.path}"
        migrate(url, PRODUCTS_VERSION - 1)
        val tables = query(url, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        fun rows() = tables.associateWith { table -> query(url, "SELECT ${columnsOf(url, table).filterNot { table == "ingredient" && it.startsWith("label") }.joined()} FROM \"$table\"") }
        val before = counts(url)
        val rows = rows()
        println("Before: version ${version(url)}, checks ${checks(url)}, rows $before")

        migrate(url, PRODUCTS_VERSION)
        println("After: version ${version(url)}, checks ${checks(url)}, rows ${counts(url)}")
        assertEquals(listOf("ok") to emptyList(), checks(url))
        assertEquals(before + ("ingredientPortion" to 0L), counts(url), "every table keeps its rows; no portion yet")
        assertEquals(rows, rows(), "every row as it was")
        assertEquals(listOf("0"), query(url, "SELECT count(*) FROM ingredient WHERE labelBasis IS NOT NULL OR labelKcal IS NOT NULL OR labelSource IS NOT NULL"))

        val (driver, db) = openDatabase(url)
        val foods = FoodRepository(db)
        val offers = OfferRepository(db, emptyList())
        for (account in UserRepository(db).all()) {
            val scope = UserScope(account, db, foods, offers, PriceRepository(db, null, foods, offers), null, createTempDirectory("pictures").toFile())
            assertTrue(scope.ingredients.list().all { it.label == null && it.portions.isEmpty() && it.removedPortions.isEmpty() }, "account $account")
            val recipes = scope.recipes.list()
            println("Account $account: ${recipes.size} recipes, ${recipes.count { it.nutrition?.complete == true }} counted in full")
        }
        driver.close()
    }

    /**
     * Across 27.sqm (issue 358): every table keeps its rows and every row what it had, no recipe is the foods of a slot and no
     * planned meal has foods, and every account reads its recipes, its bank and every planned meal of every week as the
     * rows had them before. Run as the tests above; prints each account's weeks and meals.
     */
    @Test
    fun aCopyOfARealDatabaseKeepsEveryRowAndEveryPlannedMealAsItWas() {
        val original = System.getenv("RECIPES_DB")?.let(::File) ?: return
        val copy = File(createTempDirectory("recipes").toFile(), "copy.db")
        original.copyTo(copy)
        val url = "jdbc:sqlite:${copy.path}"
        migrate(url, SLOT_FOODS_VERSION - 1)
        val tables = query(url, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        val added = mapOf("recipe" to "slotFoods", "planMeal" to "foodsId")
        fun rows() = tables.associateWith { table -> query(url, "SELECT ${columnsOf(url, table).filterNot { added[table] == it }.joined()} FROM \"$table\"") }
        fun meals(account: Long) = query(
            url,
            "SELECT weekStart || ' ' || day || ' ' || slot || ' ' || recipeId || ' ' || title || ' ' || printf('%.3f', portions) || ' ' || printf('%.3f', kcal) || ' ' || status " +
                "FROM planMeal WHERE userId = $account ORDER BY weekStart, day, slot",
        )
        val before = counts(url)
        val rows = rows()
        val accounts = query(url, "SELECT id FROM appUser ORDER BY id").map { it.toLong() }
        val plans = accounts.associateWith(::meals)
        val lists = accounts.associateWith { account ->
            query(url, "SELECT id FROM recipe WHERE userId = $account AND addedAt IS NOT NULL ORDER BY id") to
                query(url, "SELECT id FROM recipe WHERE userId = $account AND addedAt IS NULL AND removedAt IS NULL ORDER BY id")
        }
        println("Before: version ${version(url)}, checks ${checks(url)}, rows $before")

        migrate(url, SLOT_FOODS_VERSION)
        println("After: version ${version(url)}, checks ${checks(url)}, rows ${counts(url)}")
        assertEquals(listOf("ok") to emptyList(), checks(url))
        assertEquals(before, counts(url), "every table keeps its rows")
        assertEquals(rows, rows(), "every row as it was")
        assertEquals(listOf("0", "0"), query(url, "SELECT count(*) FROM recipe WHERE slotFoods != 0 UNION ALL SELECT count(*) FROM planMeal WHERE foodsId IS NOT NULL"))

        val (driver, db) = openDatabase(url)
        val foods = FoodRepository(db)
        val offers = OfferRepository(db, emptyList())
        for (account in accounts) {
            val scope = UserScope(account, db, foods, offers, PriceRepository(db, null, foods, offers), null, createTempDirectory("pictures").toFile())
            val weeks = query(url, "SELECT DISTINCT weekStart FROM planMeal WHERE userId = $account ORDER BY weekStart").map(java.time.LocalDate::parse)
            val read = weeks.flatMap { week ->
                scope.plans.meals(week).sortedWith(compareBy({ it.day }, { it.slot.name })).map { meal ->
                    assertTrue(meal.foodsId == null && !meal.foodsOnly && meal.foods.isEmpty(), "account $account, $week: $meal")
                    "$week ${meal.day} ${meal.slot} ${meal.recipeId} ${meal.title} ${"%.3f".format(java.util.Locale.ROOT, meal.portions)} ${"%.3f".format(java.util.Locale.ROOT, meal.kcal)} ${meal.status}"
                }
            }
            println("Account $account: ${weeks.size} weeks, ${read.size} meals")
            assertEquals(plans.getValue(account), read, "account $account: every planned meal as it was")
            val ids = scope.recipes.list().map { it.id.toString() }.sortedBy(String::toLong) to scope.recipes.bank().map { it.id.toString() }.sortedBy(String::toLong)
            assertEquals(lists.getValue(account), ids, "account $account: the same recipes mine and in the bank")
        }
        driver.close()
    }

    private fun columnsOf(url: String, table: String) = query(url, "SELECT name FROM pragma_table_info('$table')")

    private fun List<String>.joined() = joinToString(" || '|' || ") { "coalesce($it, 'NULL')" }

    private companion object {
        /** The version that has the recipe bank (16.sqm). */
        const val BANK_VERSION = 17L

        /** The version whose recipes taken from the site are the account's own and whose lines can know their ingredient (25.sqm). */
        const val TAKEN_VERSION = 26L

        /** The version whose ingredients can have values of their own and portions (26.sqm). */
        const val PRODUCTS_VERSION = 27L

        /** The version whose slots of the plan can hold foods (27.sqm). */
        const val SLOT_FOODS_VERSION = 28L
    }
}
