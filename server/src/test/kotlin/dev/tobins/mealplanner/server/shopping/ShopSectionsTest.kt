package dev.tobins.mealplanner.server.shopping

import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.SectionChoice
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.NotFoundException
import dev.tobins.mealplanner.server.UserScope
import dev.tobins.mealplanner.server.accounts.Identity
import dev.tobins.mealplanner.server.accounts.UserRepository
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.ingredients.FoodRepository
import dev.tobins.mealplanner.server.openDatabase
import dev.tobins.mealplanner.server.prices.OfferRepository
import dev.tobins.mealplanner.server.prices.PriceRepository
import dev.tobins.mealplanner.server.prices.PriceSource
import dev.tobins.mealplanner.server.prices.StoreProduct
import dev.tobins.mealplanner.server.releasedJson
import dev.tobins.mealplanner.shopping.ShopSection
import dev.tobins.mealplanner.released.api.ShoppingList as ReleasedShoppingList
import io.ktor.serialization.kotlinx.json.DefaultJson
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The shopping list in walking order, and where each ingredient's section comes from. */
class ShopSectionsTest {
    private val header = "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|" +
        "\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|" +
        "\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\""

    /** Real NEVO groups and names; the nutrients don't matter here. */
    private val foods = listOf(
        "Fruits" to "Banana raw",
        "Vegetables" to "Onions raw",
        "Bread" to "Bread wholemeal",
        "Cheese" to "Cheese Gouda 48+",
        "Cold meat cuts" to "Ham cooked",
        "Savoury sauces" to "Pesto",
        "Meat and poultry" to "Chicken fillet raw",
        "Fish, crustacean and shellfish" to "Salmon raw",
        "Milk and milk products" to "Milk semi-skimmed",
        "Eggs" to "Egg chicken raw",
        "Cereal products and types of flour" to "Pasta white raw",
        "Vegetables" to "Tomatoes tinned",
        "Cereal products and types of flour" to "Muesli crunchy plain",
        "Cereal products and types of flour" to "Flour wheat white",
        "Sugar, sweets and sweet sauces" to "Chocolate dark",
        "Alcoholic beverages" to "Beer pilsner",
        "Vegetables" to "Peas green frozen",
    )

    private data class Setup(val db: Database, val user: UserScope, val week: String)

    /**
     * An account whose week has one recipe using every food above under a short name, plus
     * rice (no NEVO food, but an AH category from its price), baking paper (neither) and an
     * unlinked line.
     */
    private fun setup(): Setup {
        val db = openDatabase("jdbc:sqlite::memory:").second
        val foodRepo = FoodRepository(db)
        val csv = (listOf(header) + foods.mapIndexed { i, (group, name) -> "NEVO-Online 2025 9.0|x|$group|${i + 1}|$name|$name||per 100g|100|1|1|1|1|1|1|1" })
        foodRepo.importNevo(createTempFile("nevo", ".csv").toFile().apply { writeText(csv.joinToString("\r\n")) })
        val offers = OfferRepository(db, emptyList())
        val prices = PriceRepository(db, null, foodRepo, offers)
        val accountId = UserRepository(db).resolve(Identity("a", "alice")).id
        val user = UserScope(accountId, db, foodRepo, offers, prices, null, createTempDirectory("pictures").toFile())

        val names = listOf(
            "Banana", "Onion", "Bread", "Gouda", "Ham", "Pesto", "Chicken", "Salmon", "Milk", "Eggs",
            "Pasta", "Tinned tomatoes", "Muesli", "Flour", "Dark chocolate", "Beer", "Frozen peas",
        )
        names.forEachIndexed { i, name -> user.ingredients.create(IngredientInput(name, nevoCode = i + 1)) }
        user.ingredients.create(IngredientInput("Rice"))
        db.priceQueries.upsertPrice("rice", "AH", "AH Basmati rijst", "1 kg", 2.5, 2.5, "2026-11-15T00:00:00Z", "Pasta, rijst, wereldkeuken", "Rijst", null)
        val paper = user.ingredients.create(IngredientInput("Baking paper"))
        user.sections.set(paper.id, SectionChoice(ShopSection.NON_FOOD.name))

        // Listed in no particular order: the list sorts them.
        val lines = (names.shuffled(kotlin.random.Random(1)) + "Rice" + "Baking paper" + "Unobtainium").joinToString("\n") { "100 | g | ${it.lowercase()}" }
        val recipe = user.recipes.edits.create(RecipeInput("Everything", 1, lines))
        val week = "2026-11-16"
        db.planQueries.upsertMeal(accountId, week, 0, MealSlot.DINNER.name, recipe.summary.id, "Everything", 1.0, 500.0, 30.0, 0, 0, MealStatus.PLANNED.name)
        return Setup(db, user, week)
    }

    private val route = listOf(
        "Fruit and vegetables" to listOf("Banana", "Onion"),
        "Bread" to listOf("Bread"),
        "Cold meats and cheese" to listOf("Gouda", "Ham"),
        "Sauces, spices and oil" to listOf("Pesto"),
        "Meat" to listOf("Chicken"),
        "Fish" to listOf("Salmon"),
        "Dairy and eggs" to listOf("Eggs", "Milk"),
        "Pasta, rice and world foods" to listOf("Pasta", "Rice"),
        "Tins and jars" to listOf("Tinned tomatoes"),
        "Breakfast and spreads" to listOf("Muesli"),
        "Baking" to listOf("Flour"),
        "Snacks and sweets" to listOf("Dark chocolate"),
        "Drinks" to listOf("Beer"),
        "Frozen" to listOf("Frozen peas"),
        "Household and non-food" to listOf("Baking paper"),
        "Other" to listOf("Unobtainium"),
    )

    private fun ShoppingList.sections() = items.groupBy { it.category }.map { (category, items) -> category to items.map { it.name } }

    @Test
    fun aTypicalWeeksListFollowsTheRouteThroughTheShop() {
        val (_, user, week) = setup()
        assertEquals(route, user.shopping.view.get(week).sections())
    }

    @Test
    fun tickedAndOnHandItemsKeepTheirPlace() {
        val (_, user, week) = setup()
        val before = user.shopping.view.get(week)
        val milk = before.items.single { it.name == "Milk" }
        user.shopping.ticking.setChecked(week, ShoppingCheck(milk.key, true))
        user.stock.setOnHand(before.items.single { it.name == "Onion" }.key.removePrefix("i").toLong(), true)

        val after = user.shopping.view.get(week)
        assertTrue(after.items.single { it.name == "Milk" }.checked)
        assertTrue(after.items.single { it.name == "Onion" }.onHand)
        assertEquals(before.items.map { it.key }, after.items.map { it.key })
    }

    @Test
    fun theReleasedAppReadsTheListInWalkingOrder() {
        val (_, user, week) = setup()
        val text = DefaultJson.encodeToString(ShoppingList.serializer(), user.shopping.view.get(week))
        // Such an app groups the items by category in the order they come.
        val old = releasedJson.decodeFromString<ReleasedShoppingList>(text)
        assertEquals(route, old.items.groupBy { it.category }.map { (category, items) -> category to items.map { it.name } })
    }

    private class OneProductStore(val product: StoreProduct) : PriceSource {
        override val store = Store.AH
        override suspend fun search(term: String) = listOf(product)
    }

    @Test
    fun aSectionSetByHandWinsAndStaysUntilItIsCleared() = runBlocking {
        val setup = setup()
        val user = setup.user
        val gouda = user.ingredients.list().single { it.name == "Gouda" }
        fun gouda() = user.sections.list().single { it.ingredientId == gouda.id }
        assertEquals(ShopSection.DELI.name, gouda().section)
        assertEquals("NEVO food group: Cheese", gouda().source)

        // Some people buy their cheese pre-packed with the dairy.
        val set = user.sections.set(gouda.id, SectionChoice(ShopSection.DAIRY.name))
        assertEquals(ShopSection.DAIRY.name, set.section)
        assertEquals(ShopSection.DELI.name, set.automatic)
        assertTrue(set.byHand)

        // Editing the ingredient and a new price lookup leave it alone.
        user.ingredients.update(gouda.id, IngredientInput("Gouda", nevoCode = gouda.food!!.nevoCode, gramsPerPiece = 20.0))
        val prices = PriceRepository(setup.db, OneProductStore(StoreProduct("AH Jong belegen Gouda", "500 g", 6.0, null, "Kaas", "Goudse kaas")), FoodRepository(setup.db), OfferRepository(setup.db, emptyList()))
        prices.refresh(pause = Duration.ZERO)
        assertEquals("Albert Heijn: Kaas › Goudse kaas", gouda().source, "the store's category, now known, comes before the food group")
        assertEquals(ShopSection.DAIRY.name, gouda().section)
        assertEquals("Dairy and eggs", user.shopping.view.get(setup.week).items.single { it.name == "Gouda" }.category)

        // Cleared, the automatic section is back.
        assertFalse(user.sections.set(gouda.id, SectionChoice(null)).byHand)
        assertEquals(ShopSection.DELI.name, gouda().section)
    }

    @Test
    fun theStoresCategoryComesBeforeTheFoodGroup() {
        val setup = setup()
        // Pretend AH sells pesto with the pasta.
        setup.db.priceQueries.upsertPrice("pesto", "AH", "AH Pesto", "190 g", 2.0, 10.5, "2026-11-15T00:00:00Z", "Pasta, rijst, wereldkeuken", null, null)
        val pesto = setup.user.sections.list().single { it.name == "Pesto" }
        assertEquals(ShopSection.WORLD.name, pesto.section)
        assertEquals("Albert Heijn: Pasta, rijst, wereldkeuken", pesto.source)
        // A category this version doesn't know falls back to the food group.
        setup.db.priceQueries.upsertPrice("pesto", "AH", "AH Pesto", "190 g", 2.0, 10.5, "2026-11-15T00:00:00Z", "Iets nieuws", null, null)
        assertEquals(ShopSection.SAUCES.name, setup.user.sections.list().single { it.name == "Pesto" }.section)
    }

    @Test
    fun onlyKnownSectionsCanBeSetAndNegligibleIngredientsHaveNone() {
        val setup = setup()
        val salt = setup.user.ingredients.create(IngredientInput("Salt", negligible = true))
        assertTrue(setup.user.sections.list().none { it.ingredientId == salt.id }, "never on the list, so nothing to correct")
        val rice = setup.user.ingredients.list().single { it.name == "Rice" }
        assertFailsWith<IllegalArgumentException> { setup.user.sections.set(rice.id, SectionChoice("AISLE_42")) }
        assertFailsWith<NotFoundException> { setup.user.sections.set(9999, SectionChoice(ShopSection.BREAD.name)) }
    }
}
