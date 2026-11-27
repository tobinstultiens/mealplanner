package dev.tobins.mealplanner.server.shopping

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingRowKey
import dev.tobins.mealplanner.server.DatabaseBusyException
import dev.tobins.mealplanner.server.RefusingWrites
import dev.tobins.mealplanner.server.UserScope
import dev.tobins.mealplanner.server.accounts.Identity
import dev.tobins.mealplanner.server.accounts.UserRepository
import dev.tobins.mealplanner.server.ingredients.FoodRepository
import dev.tobins.mealplanner.server.openDatabase
import dev.tobins.mealplanner.server.prices.OfferRepository
import dev.tobins.mealplanner.server.prices.PriceRepository
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A change of the shopping list that writes several things is one transaction: when it fails halfway, none of it is
 * stored. Using up a bought item no meal needs any more puts what was bought in the stock and then takes its tick
 * out; the database refuses the second here, as it would a write that waited too long for its turn.
 */
class TickingTest {
    private val week = "2026-11-16"
    private val writes = RefusingWrites(openDatabase("jdbc:sqlite::memory:").first)
    private val db = writes.database
    private val foods = FoodRepository(db)
    private val offers = OfferRepository(db, emptyList())
    private val alice = UserScope(
        UserRepository(db).resolve(Identity("alice", "alice")).id, db, foods, offers, PriceRepository(db, null, foods, offers), null,
        createTempDirectory("pictures").toFile(), today = { LocalDate.parse(week) },
    )
    private val beef = alice.ingredients.create(IngredientInput("Beef")).id

    init {
        alice.profiles.save(Profile(80.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000))
    }

    @Test
    fun aBoughtItemUsedUpIsInTheStockWithoutItsTickOrNotAtAll() {
        // Beef bought for Monday's dinner, which is then taken off the plan: bought, and no meal needs it.
        val stew = alice.recipes.edits.create(RecipeInput("Beef stew", 1, "400 | g | Beef", "Cook.", mealSlots = listOf(MealSlot.DINNER))).summary.id
        alice.plan.changes.choose(week, 0, MealSlot.DINNER, stew)
        alice.shopping.ticking.setChecked(week, ShoppingCheck("i$beef", true))
        val meals = alice.plan.view.get(week).days.flatMapIndexed { day, plan -> plan.meals.map { day to it.slot } }
        for ((day, slot) in meals.reversed()) alice.plan.changes.remove(week, day, slot)
        val before = alice.shopping.view.get(week)
        assertEquals(listOf("i$beef"), before.noLongerNeeded.map { it.key }, "the beef was bought and no meal needs it")
        val bought = assertNotNull(before.noLongerNeeded.single().boughtGrams)
        val pantry = alice.stock.pantry()

        // What was bought goes into the stock; taking out the tick is refused.
        var stocked = false
        writes.refuse = { sql ->
            if (sql.startsWith("INSERT OR IGNORE INTO stock")) stocked = true
            sql.startsWith("DELETE FROM shoppingCheck")
        }
        assertFailsWith<DatabaseBusyException> { alice.shopping.ticking.useUp(week, ShoppingRowKey("i$beef")) }
        writes.refuse = { false }

        assertTrue(stocked, "the stock was written before the tick was refused")
        assertEquals(before, alice.shopping.view.get(week), "the beef is still bought and in the group, and nothing else changed")
        assertEquals(pantry, alice.stock.pantry(), "nothing of it is in the stock")
        val used = alice.shopping.ticking.useUp(week, ShoppingRowKey("i$beef"))
        assertEquals(emptyList(), used.noLongerNeeded, "the same use-up goes through once nothing refuses it")
        assertEquals(bought, alice.stock.pantry().onHandGrams[beef], "and stocks what was bought")
    }
}
