package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.LabelNutrition
import dev.tobins.mealplanner.api.MappedLine
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.Portion
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeLineInput
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.recipes.HugoRecipeParser
import dev.tobins.mealplanner.recipes.PlainLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeDraftTest {
    private fun food(code: Int, en: String, nl: String, kcal: Double, protein: Double, basis: FoodBasis = FoodBasis.PER_100_G) =
        Food(code, nl, en, null, "g", basis, Nutrition(kcal, protein, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))

    private val chicken = Ingredient(1, "Chicken thigh fillet", food(1, "Chicken thigh raw", "Kipdijfilet rauw", 180.0, 18.0), 110.0, null, null, null, false)
    private val milk = Ingredient(2, "Coconut milk", food(2, "Coconut milk", "Kokosmelk", 200.0, 2.0, FoodBasis.PER_100_ML), null, null, null, null, false)
    private val oil = Ingredient(3, "Olive oil", food(3, "Olive oil", "Olijfolie", 884.0, 0.0), null, 0.91, null, null, false)
    private val lemon = Ingredient(4, "Lemon", food(4, "Lemon", "Citroen", 30.0, 1.0), 100.0, null, null, null, false)
    private val catalogue = listOf(chicken, milk, oil, lemon)

    /** A recipe of the cooking site as the server gives it: lines without an ingredient of their own, each with the one it means. */
    private val stew = RecipeDetail(
        RecipeSummary(
            9, "Stew", "dinner", listOf("winter", "slow"), 4, RecipeSource.COOKING_SITE, RecipeKind.MEAL, plannable = true,
            mealSlots = listOf(MealSlot.LUNCH, MealSlot.DINNER), keepsDays = 3,
        ),
        HugoRecipeParser().parse("x.md", "```ingredients\n# Stew\n700 | g | chicken thighs\n1/3 | l | coconut milk\n1 | lemon | juiced\n# To serve\n| | salt to taste\n```")
            .ingredients.zip(listOf(chicken, milk, lemon, null)).map { (line, meant) -> MappedLine(line, meant?.id, meant?.name, null) },
        "Brown the chicken.\n\n1. Stew it.",
        problems = listOf("Unreadable ingredient line: 1-2 | el | olie"),
    )

    @Test
    fun aRecipeOpenedAndSavedAsItWasIsTheSameRecipe() {
        val draft = stew.toDraft()
        val input = draft.input()
        assertEquals(stew.ingredients.map { RecipeLineInput(it.line.amount, it.line.unit, it.line.item, null, it.line.group) }, input.lines)
        assertEquals(listOf("Stew", "winter", "slow", "Brown the chicken.\n\n1. Stew it.", 4), listOf(input.title) + input.tags + listOf(input.instructions, input.servings))
        assertEquals(listOf(MealSlot.LUNCH, MealSlot.DINNER), input.mealSlots)
        assertEquals(RecipeKeeps(3, null), draft.keeps)
        assertEquals(listOf("1-2 | el | olie"), draft.unread)
        // A third of a litre shows as 0.33 and still goes back as a third.
        assertEquals("0.33", draft.rows[1].amount)
        assertEquals(1.0 / 3, input.lines!![1].amount)
    }

    @Test
    fun aChangedRowIsSavedWithTheIngredientItShowsAndTheOthersAsTheyWere() {
        val draft = stew.toDraft().let { it.changing(it.rows[0].key) { row -> row.copy(amount = "800") } }
        assertEquals(
            listOf(RecipeLineInput(800.0, "g", "chicken thighs", chicken.id, "Stew"), RecipeLineInput(1.0 / 3, "l", "coconut milk", null, "Stew")),
            draft.input().lines!!.take(2),
        )
        // Changed back, it is as it was again.
        val back = draft.changing(draft.rows[0].key) { it.copy(amount = "700") }
        assertEquals(stew.toDraft().input(), back.input())
    }

    @Test
    fun aPickedIngredientStartsInAUnitItHas() {
        val draft = RecipeDraft(addingTo = "Sauce").adding(chicken).adding(milk).adding(oil)
        assertEquals(
            listOf(RecipeLineInput(1.0, PIECE, "Chicken thigh fillet", 1, "Sauce"), RecipeLineInput(100.0, "ml", "Coconut milk", 2, "Sauce"), RecipeLineInput(100.0, "g", "Olive oil", 3, "Sauce")),
            draft.rows.map { it.input() },
        )
        assertEquals(listOf(0L, 1L, 2L), draft.rows.map { it.key })
        assertEquals(listOf("g", "ml", PIECE), unitChoices(chicken.copy(gramsPerMl = 1.03), null))
        assertEquals(listOf("g", "ml"), unitChoices(milk, null))
        assertEquals(listOf("g", "ml", "tbsp"), unitChoices(oil, "tbsp"))
        assertEquals(listOf("g", PIECE, "lemon"), unitChoices(lemon, "lemon"))
    }

    @Test
    fun aPickedIngredientStartsInTheUnitLastSavedForItWhenItStillHasThatUnit() {
        val row = { ingredient: Ingredient -> RecipeDraft().adding(ingredient).rows.single().input().let { it.amount to it.unit } }
        assertEquals(100.0 to "g", row(chicken.copy(lastUnit = "g")))
        assertEquals(1.0 to PIECE, row(chicken.copy(lastUnit = PIECE)))
        assertEquals(100.0 to "ml", row(oil.copy(lastUnit = "ml")))
        // A unit the ingredient has lost, or one it never had, is as no history: pieces with a weight per piece, else grams.
        assertEquals(1.0 to PIECE, row(chicken.copy(lastUnit = "ml")))
        assertEquals(100.0 to "g", row(oil.copy(lastUnit = PIECE)))
        assertEquals(100.0 to "g", row(oil.copy(lastUnit = "tbsp")))
    }

    /** A product with the values of its label and two portions (issue 351). */
    private val bar = Ingredient(
        6, "Protein bar", null, null, null, null, null, false,
        label = LabelNutrition(FoodBasis.PER_100_G, Nutrition(400.0, 30.0, null, null, null, null, null, null)),
        portions = listOf(Portion("bar", grams = 45.0), Portion("half", grams = 22.5)),
    )

    @Test
    fun aProductsPortionsAreUnitsOfItsRowAfterTheOthers() {
        assertEquals(listOf("g", "bar", "half"), unitChoices(bar, "g"))
        assertEquals(listOf("g", "bar", "half"), unitChoices(bar, "Bar"), "the row's unit is the portion, in any case")
        assertEquals(listOf("g", PIECE, "bar", "half"), unitChoices(bar.copy(gramsPerPiece = 45.0), null))
        val drink = bar.copy(label = bar.label!!.copy(basis = FoodBasis.PER_100_ML))
        assertEquals(listOf("g", "ml", "bar", "half"), unitChoices(drink, null), "a drink's label is counted per volume")
        // One removed since stays the row's unit, as any unit the row had.
        assertEquals(listOf("g", "half", "bar"), unitChoices(bar.copy(portions = bar.portions.drop(1), removedPortions = listOf("bar")), "bar"))
    }

    @Test
    fun aProductStartsInThePortionLastSavedForItWhileItHasIt() {
        val row = { ingredient: Ingredient -> RecipeDraft().adding(ingredient).rows.single().input().let { it.amount to it.unit } }
        assertEquals(1.0 to "half", row(bar.copy(lastUnit = "half")))
        assertEquals(1.0 to "bar", row(bar.copy(lastUnit = "BAR")))
        assertEquals(100.0 to "g", row(bar.copy(lastUnit = "scoop")), "a portion that is gone is as no history")
        assertEquals(100.0 to "g", row(bar))
    }

    @Test
    fun aRowInAPortionCountsItsWeightAndOneWhosePortionIsGoneIsNotCounted() {
        val draft = RecipeDraft().adding(bar).let { it.changing(0) { row -> row.inUnit("bar", bar) } }.changing(0) { it.copy(amount = "2") }
        assertEquals(RowCount.COUNTED, draft.rows.single().count(bar))
        assertEquals(listOf(360.0, 27.0), draft.nutrition(mapOf(bar.id to bar))!!.perServing.let { listOf(it.kcal, it.proteinG) })
        assertEquals("2.25", RecipeDraft().adding(bar).rows.single().inUnit("bar", bar).amount, "100 g is about two and a quarter bars")
        val gone = bar.copy(portions = emptyList(), removedPortions = listOf("bar"), gramsPerPiece = 40.0)
        assertEquals(RowCount.PORTION_GONE, draft.rows.single().count(gone))
        assertFalse(draft.nutrition(mapOf(bar.id to gone))!!.complete)
        assertEquals(RowCount.NO_NUTRITION, draft.rows.single().count(bar.copy(label = null)))
    }

    @Test
    fun anotherUnitKeepsTheQuantityWhereItIsKnown() {
        val oneThigh = RecipeDraft().adding(chicken).rows.single()
        assertEquals("110", oneThigh.inUnit("g", chicken).amount)
        assertEquals("1.75", oneThigh.copy(amount = "180", unit = "g").inUnit(PIECE, chicken).amount, "to a quarter")
        assertEquals("30", oneThigh.copy(amount = "2", unit = "tbsp").inUnit("ml", oil).amount, "a volume to a volume")
        assertEquals("27", oneThigh.copy(amount = "2", unit = "tbsp").inUnit("g", oil).amount, "through its density")
        assertEquals("2", oneThigh.copy(amount = "2", unit = "tbsp").inUnit(PIECE, oil).amount, "unknown: the same number")
        val juiced = stew.toDraft().rows[2].inUnit("g", lemon)
        assertEquals(RecipeLineInput(100.0, "g", "lemon, juiced", lemon.id, "Stew"), juiced.input())
    }

    @Test
    fun theTotalsAreThoseOfTheLinesSavedAndSayWhatIsNotCounted() {
        val draft = RecipeDraft(servings = "2").adding(chicken).pasting(PlainLines.read("200 ml coconut milk\nras el hanout"), listOf(milk.id, null))
        val nutrition = draft.nutrition(catalogue.associateBy { it.id })!!
        assertEquals((180.0 * 1.1 + 400.0) / 2, nutrition.perServing.kcal!!, 1e-9)
        assertEquals(emptyList(), nutrition.uncounted, "a row without an amount is not missing a weight")
        assertEquals(listOf(RowCount.COUNTED, RowCount.COUNTED, RowCount.NO_INGREDIENT), draft.rows.map { it.count(it.ingredientId?.let(catalogue.associateBy { c -> c.id }::get)) })
        assertNull(draft.copy(servings = "0").nutrition(emptyMap()))
        assertEquals(RowCount.WEIGHT_UNKNOWN, draft.rows[0].copy(unit = "slice").count(chicken.copy(gramsPerPiece = null)))
        assertEquals(RowCount.NO_NUTRITION, draft.rows[0].count(chicken.copy(food = null)))
    }

    @Test
    fun pastedLinesGoUnderTheirOwnHeadingOrTheOneRowsAreAddedTo() {
        val draft = RecipeDraft(addingTo = "Main").pasting(PlainLines.read("200 g kip\n# Saus\n2 el sojasaus"), listOf(1L, null))
        assertEquals(listOf("Main", "Saus"), draft.rows.map { it.group })
        assertEquals(listOf(1L, null), draft.rows.map { it.ingredientId })
        assertEquals("Saus", draft.addingTo)
        val renamed = draft.renaming("Saus", " ")
        assertEquals(listOf("Main", null), renamed.rows.map { it.group })
        assertNull(renamed.addingTo)
    }

    @Test
    fun aRecipeIsSavedOnlyWithANameServingsAndRowsThatRead() {
        val good = RecipeDraft(title = "Bowl", servings = "2").adding(chicken)
        assertTrue(good.canSave)
        assertFalse(good.copy(title = " ").canSave)
        assertFalse(good.copy(servings = "101").canSave)
        assertFalse(good.copy(rows = emptyList()).canSave)
        assertFalse(good.changing(0) { it.copy(amount = "a bit") }.canSave)
        assertTrue(good.changing(0) { it.copy(amount = "1/2") }.canSave)
        assertEquals(0.5, good.changing(0) { it.copy(amount = "0,5") }.rows[0].amountValue)
    }

    @Test
    fun aSearchFindsMyIngredientsInEitherLanguageAndFoodsIDoNotHaveYet() {
        val rice = food(5, "Rice white raw", "Rijst wit rauw", 350.0, 7.0)
        val kip = food(1, "Chicken thigh raw", "Kipdijfilet rauw", 180.0, 18.0)
        assertEquals(listOf(Found.Mine(chicken)), found(catalogue, emptyList(), "kipdij"))
        assertEquals(listOf(Found.Mine(chicken), Found.NewFood(rice)), found(catalogue, listOf(kip, rice), "rauw"))
        assertEquals(emptyList(), found(catalogue, listOf(rice), "r"))
        assertEquals("Lemon (NEVO) (NEVO)", nameFor(food(9, "Lemon", "Citroen", 0.0, 0.0), catalogue + lemon.copy(id = 9, name = "lemon (nevo)")))
    }
}
