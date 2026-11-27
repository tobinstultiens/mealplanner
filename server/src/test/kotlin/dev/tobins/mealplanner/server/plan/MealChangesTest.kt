package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.WeekPlan
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
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A change of the plan that writes several meals is one transaction: when it fails halfway, none of it is stored. Moving
 * a dinner to another day takes its leftover lunch along, two rows written one after the other; the database refuses the
 * second here, as it would a write that waited too long for its turn.
 */
class MealChangesTest {
    private val nevo = listOf(
        "\"NEVO-versie/NEVO-version\"|\"Voedingsmiddelgroep\"|\"Food group\"|\"NEVO-code\"|\"Voedingsmiddelnaam/Dutch food name\"|\"Engelse naam/Food name\"|\"Synoniem\"|\"Hoeveelheid/Quantity\"|\"ENERCC (kcal)\"|\"PROT (g)\"|\"FAT (g)\"|\"FASAT (g)\"|\"CHO (g)\"|\"SUGAR (g)\"|\"FIBT (g)\"|\"NA (mg)\"",
        "NEVO-Online 2025 9.0|Vlees|Meat and poultry|10|Rundvlees rauw|Beef raw||per 100g|150|20|4|2|0|0|0|50",
    ).joinToString("\r\n")

    private val week = "2026-11-16"
    private val writes = RefusingWrites(openDatabase("jdbc:sqlite::memory:").first)
    private val db = writes.database
    private val foods = FoodRepository(db).also { f -> f.importNevo(createTempFile().toFile().apply { writeText(nevo) }) }
    private val offers = OfferRepository(db, emptyList())
    private val alice = UserScope(
        UserRepository(db).resolve(Identity("alice", "alice")).id, db, foods, offers, PriceRepository(db, null, foods, offers), null,
        createTempDirectory("pictures").toFile(), today = { LocalDate.parse(week) },
    )

    init {
        alice.ingredients.create(IngredientInput("Beef", nevoCode = 10))
        alice.profiles.save(Profile(80.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT, kcalOverride = 2000))
        for (i in 0..6) alice.recipes.edits.create(RecipeInput("Beef $i", 4, "${1600 + i * 40} | g | Beef", "Cook.", mealSlots = listOf(MealSlot.LUNCH, MealSlot.DINNER)))
    }

    private fun WeekPlan.meal(day: Int, slot: MealSlot): PlannedMeal? = days[day].meals.singleOrNull { it.slot == slot }

    @Test
    fun aDinnerMovedWithItsLeftoverLunchIsStoredWithItOrNotAtAll() {
        alice.plan.weeks.generate(week, seed = 2)
        // Thursday without a dinner: Monday's goes there alone, and the two meals written are it and its leftovers.
        val before = alice.plan.changes.remove(week, 3, MealSlot.DINNER)
        val monday = before.meal(0, MealSlot.DINNER)!!
        assertEquals(monday.recipeId to true, before.meal(1, MealSlot.LUNCH)?.let { it.recipeId to it.leftover }, "Monday's dinner leaves its leftovers for Tuesday")

        // The first meal written goes through, the dinner on Thursday; the second, its leftovers on Friday, is refused.
        var written = 0
        writes.refuse = { sql -> sql.startsWith("INSERT OR REPLACE INTO planMeal") && ++written == 2 }
        assertFailsWith<DatabaseBusyException> { alice.plan.changes.moveDinner(week, 0, 3) }
        writes.refuse = { false }

        assertEquals(2, written, "the dinner was written before its leftovers were refused")
        assertEquals(before, alice.plan.view.get(week), "neither the dinner nor its leftovers moved, and nothing else changed")
        val moved = alice.plan.changes.moveDinner(week, 0, 3)
        assertEquals(monday.recipeId, moved.meal(3, MealSlot.DINNER)?.recipeId, "the same move goes through once nothing refuses it")
        assertEquals(monday.recipeId to true, moved.meal(4, MealSlot.LUNCH)?.let { it.recipeId to it.leftover })
    }
}
