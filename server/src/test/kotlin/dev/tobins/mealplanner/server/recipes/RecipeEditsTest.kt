package dev.tobins.mealplanner.server.recipes

import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.server.DatabaseBusyException
import dev.tobins.mealplanner.server.RefusingWrites
import dev.tobins.mealplanner.server.UserScope
import dev.tobins.mealplanner.server.accounts.Identity
import dev.tobins.mealplanner.server.accounts.UserRepository
import dev.tobins.mealplanner.server.ingredients.FoodRepository
import dev.tobins.mealplanner.server.openDatabase
import dev.tobins.mealplanner.server.prices.OfferRepository
import dev.tobins.mealplanner.server.prices.PriceRepository
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A change of a recipe that writes several rows is one transaction: when it fails halfway, none of it is stored. Saving
 * an edited recipe writes its title and servings, its slots, and its lines one after the other; the database refuses
 * the second line here, as it would a write that waited too long for its turn.
 */
class RecipeEditsTest {
    private val writes = RefusingWrites(openDatabase("jdbc:sqlite::memory:").first)
    private val db = writes.database
    private val foods = FoodRepository(db)
    private val offers = OfferRepository(db, emptyList())
    private val alice = UserScope(
        UserRepository(db).resolve(Identity("alice", "alice")).id, db, foods, offers, PriceRepository(db, null, foods, offers), null,
        createTempDirectory("pictures").toFile(),
    )

    init {
        alice.ingredients.create(IngredientInput("Beef"))
        alice.ingredients.create(IngredientInput("Rice"))
    }

    @Test
    fun anEditedRecipeIsStoredWithAllItsLinesOrNotAtAll() {
        val id = alice.recipes.edits.create(RecipeInput("Beef bowl", 2, "300 | g | beef", "Cook.", mealSlots = listOf(MealSlot.DINNER))).summary.id
        val before = alice.recipes.view.find(id)
        val edit = RecipeInput("Beef and rice", 4, "600 | g | beef\n200 | g | rice", "Cook both.", tags = listOf("quick"), mealSlots = listOf(MealSlot.LUNCH))

        // The recipe's row, its slots and its first line go through; the second line is refused.
        var written = 0
        writes.refuse = { sql -> sql.startsWith("INSERT INTO recipeIngredient") && ++written == 2 }
        assertFailsWith<DatabaseBusyException> { alice.recipes.edits.update(id, edit) }
        writes.refuse = { false }

        assertEquals(2, written, "the first line was written before the second was refused")
        assertEquals(before, alice.recipes.view.find(id), "neither its title, servings, slots nor lines changed")
        val saved = alice.recipes.edits.update(id, edit)
        assertEquals("Beef and rice" to listOf("beef", "rice"), saved.summary.title to saved.ingredients.map { it.line.item }, "the same edit goes through once nothing refuses it")
    }
}
