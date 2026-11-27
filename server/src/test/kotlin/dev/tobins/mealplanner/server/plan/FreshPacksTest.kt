package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.planner.FreshUse
import dev.tobins.mealplanner.planner.Pack
import dev.tobins.mealplanner.server.accounts.Identity
import dev.tobins.mealplanner.server.accounts.UserRepository
import dev.tobins.mealplanner.server.ingredients.FoodRepository
import dev.tobins.mealplanner.server.ingredients.IngredientRepository
import dev.tobins.mealplanner.server.openDatabase
import dev.tobins.mealplanner.server.prices.OfferRepository
import dev.tobins.mealplanner.server.prices.PriceRepository
import dev.tobins.mealplanner.server.recipes.RecipeScope
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals

/** The packs of fresh ingredients the planner uses up (issue 222): which ingredients have one and what a recipe takes of it. */
class FreshPacksTest {
    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Groente|Vegetables|63|Ui rauw|Onions raw||per 100g|40|1|0|0|8|6|2|3",
        "NEVO-Online 2025 9.0|Granen|Cereals|100|Rijst rauw|Rice raw||per 100g|350|7|1|0|78|0|1|5",
    ).joinToString("\r\n")

    private val db = openDatabase("jdbc:sqlite::memory:").second
    private val foods = FoodRepository(db).also { f -> f.importNevo(createTempFile().toFile().apply { writeText(nevo) }) }
    private val userId = UserRepository(db).resolve(Identity("a", "alice")).id
    private val ingredients = IngredientRepository(db, foods, userId)
    private val prices = PriceRepository(db, null, foods, OfferRepository(db, emptyList()))
    private val recipes = RecipeScope(db, ingredients, userId)

    private fun price(name: String, packSize: String?, packPrice: Double?, perKg: Double) =
        db.priceQueries.upsertPrice(name, "AH", name, packSize, packPrice, perKg, "2026-11-19T00:00:00Z", null, null, null)

    private val onion = ingredients.create(IngredientInput("Onion", nevoCode = 63, gramsPerPiece = 100.0))
    private val rice = ingredients.create(IngredientInput("Rice", nevoCode = 100))
    private val shallot = ingredients.create(IngredientInput("Shallot"))
    private val ginger = ingredients.create(IngredientInput("Ginger", pricePerKg = 9.0))
    private val everyoneBuysFresh = mapOf(onion.id to false, rice.id to true, shallot.id to false, ginger.id to false)

    init {
        price("Onion", "3 stuks", 1.2, 4.0)
        price("Rice", "1 kg", 2.0, 2.0)
        price("Shallot", "ca. 500 g", null, 6.0)
        price("Ginger", "150 g", 1.5, 10.0)
    }

    @Test
    fun aFreshIngredientHasThePackOfItsRegularPrice() {
        val packs = prices.freshPacks(ingredients, everyoneBuysFresh)

        // Rice keeps until next week, and ginger's price was set by hand: not a product's pack.
        assertEquals(setOf(onion.id, shallot.id), packs.keys)
        assertEquals(Pack(300.0, 1.2), packs[onion.id], "three pieces of 100 g")
        assertEquals(Pack(500.0, 3.0), packs[shallot.id], "without a pack price, the price per kg of the pack")
    }

    @Test
    fun anIngredientWithoutASizeInGramsHasNoPack() {
        price("Onion", "per bos", 1.0, 4.0)
        price("Shallot", null, 1.0, 6.0)

        assertEquals(emptyMap(), prices.freshPacks(ingredients, everyoneBuysFresh))
    }

    @Test
    fun aRecipeTakesTheFreshIngredientsOfAServingAndNotThoseOnHand() {
        recipes.edits.create(RecipeInput("Onion rice", 2, "200 | g | Onion\n300 | g | Rice\n50 | g | Onion"))
        val packs = prices.freshPacks(ingredients, everyoneBuysFresh)

        val uses = recipes.candidates.planCandidates(freshPacks = packs).single().freshUses

        // 250 g of onion for two servings; the rice keeps and has no pack.
        assertEquals(listOf(FreshUse(onion.id, 125.0, Pack(300.0, 1.2))), uses)
        assertEquals(emptyList(), recipes.candidates.planCandidates(freshPacks = packs, onHand = setOf(onion.id)).single().freshUses, "onions on hand are used up first")
        assertEquals(emptyList(), recipes.candidates.planCandidates().single().freshUses, "without packs the recipe opens none")
    }
}
