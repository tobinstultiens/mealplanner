package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.LabelNutrition
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.Portion
import dev.tobins.mealplanner.nutrition.Salt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The product form's model (issue 351): what is typed, what it saves and what it refuses. */
class ProductDraftTest {
    private val bar = Ingredient(
        5, "Protein bar", null, 45.0, null, "Snacks", 12.0, isStaple = true,
        label = LabelNutrition(FoodBasis.PER_100_G, Nutrition(400.0, 30.0, null, null, null, null, null, Salt.sodiumMg(0.5)), source = "typed"),
        portions = listOf(Portion("bar", grams = 45.0), Portion("bottle", ml = 330.0)),
    )

    private fun typed(vararg values: Pair<LabelValue, String>) =
        values.fold(ProductDraft(name = "Protein bar")) { draft, (value, text) -> draft.withValue(value, text) }

    @Test
    fun valuesAreReadWithADecimalCommaOrPointAndSaltIsSavedAsSodium() {
        val label = typed(LabelValue.ENERGY to "412,5", LabelValue.PROTEIN to "30.2", LabelValue.SALT to "0,5", LabelValue.SUGARS to "0").label()!!
        assertEquals(FoodBasis.PER_100_G, label.basis)
        assertEquals(listOf(412.5, 30.2, 0.0, 200.0), with(label.per100) { listOf(kcal, proteinG, sugarG, sodiumMg) })
    }

    @Test
    fun aValueLeftEmptyIsUnknownAndNoValueAtAllIsNoLabel() {
        val label = typed(LabelValue.ENERGY to "400").label()!!
        assertNull(label.per100.fibreG)
        assertNull(label.per100.sodiumMg)
        assertNull(typed().label(), "counted by its NEVO food, if it has one")
    }

    @Test
    fun itIsSavedOnlyWithANameValuesThatReadAndItsEnergy() {
        assertTrue(typed(LabelValue.ENERGY to "400").canSave)
        assertFalse(typed(LabelValue.ENERGY to "400").copy(name = " ").canSave)
        assertFalse(typed(LabelValue.ENERGY to "400", LabelValue.FAT to "lots").canSave)
        assertFalse(typed(LabelValue.ENERGY to "400", LabelValue.FAT to "lots").valueValid(LabelValue.FAT))
        assertFalse(typed(LabelValue.ENERGY to "-1").canSave)
        assertTrue(typed(LabelValue.PROTEIN to "30").needsEnergy)
        assertTrue(typed().needsEnergy, "a new product has nothing else to count by")
        val withFood = ProductDraft(name = "Oats", original = bar.copy(label = null, food = Food(1, "Havervlokken", "Oats", null, "Cereals", FoodBasis.PER_100_G, bar.label!!.per100)))
        assertFalse(withFood.needsEnergy, "an ingredient with a NEVO food may have portions only")
        assertTrue(withFood.canSave)
    }

    @Test
    fun aPortionNeedsANameOfItsOwnThatIsNoUnitAndASizeAboveZero() {
        val draft = typed(LabelValue.ENERGY to "400").addingPortion().addingPortion()
        fun with(first: Pair<String, String>, second: Pair<String, String>) = draft
            .changingPortion(0) { it.copy(name = first.first, size = first.second) }
            .changingPortion(1) { it.copy(name = second.first, size = second.second) }
        assertTrue(with("bar" to "45", "half" to "22,5").canSave)
        assertEquals(listOf(Portion("bar", grams = 45.0), Portion("half", grams = 22.5)), with("bar" to "45", "half" to "22,5").input().portions)
        val taken = with("bar" to "45", " Bar" to "50")
        assertEquals(PortionNameProblem.TAKEN, taken.nameProblem(taken.portions[1]))
        assertFalse(taken.canSave)
        val unit = with("bar" to "45", "el" to "15")
        assertEquals(PortionNameProblem.IS_UNIT, unit.nameProblem(unit.portions[1]))
        val bad = with("bar" to "45", "a|b" to "15")
        assertEquals(PortionNameProblem.BAD_CHARACTER, bad.nameProblem(bad.portions[1]))
        assertFalse(with("bar" to "45", "half" to "0").canSave)
        assertFalse(with("bar" to "45", "" to "10").canSave)
        assertEquals(listOf(Portion("bar", grams = 45.0)), draft.removingPortion(1).changingPortion(0) { it.copy(name = "bar", size = "45") }.input().portions)
    }

    @Test
    fun eachThingThatKeepsSaveOffHasAReasonAtItsField() {
        val draft = typed(LabelValue.ENERGY to "400", LabelValue.FAT to "-5", LabelValue.SUGARS to "veel").addingPortion()
        assertEquals(NumberProblem.NEGATIVE, draft.valueProblem(LabelValue.FAT))
        assertEquals(NumberProblem.NOT_A_NUMBER, draft.valueProblem(LabelValue.SUGARS))
        assertNull(draft.valueProblem(LabelValue.FIBRE), "empty is unknown, not wrong")
        assertEquals(NumberProblem.NEEDED, typed(LabelValue.PROTEIN to "30").valueProblem(LabelValue.ENERGY))
        assertEquals(NumberProblem.NEGATIVE, typed(LabelValue.ENERGY to "-0,5").valueProblem(LabelValue.ENERGY))
        val row = draft.portions.single()
        assertEquals(PortionNameProblem.NEEDED to NumberProblem.NEEDED, draft.nameProblem(row) to row.sizeProblem)
        assertEquals(NumberProblem.ZERO, row.copy(size = "0").sizeProblem)
        assertEquals(NumberProblem.NEGATIVE, row.copy(size = "-45").sizeProblem)
        assertEquals(NumberProblem.NOT_A_NUMBER, row.copy(size = "45 g").sizeProblem)
        assertNull(row.copy(size = "22,5").sizeProblem)
    }

    @Test
    fun portionsItHadAndNoLongerHasAreAskedAboutAndCanBePutBack() {
        val draft = bar.toProductDraft(FormatLocale.Dutch)
        assertEquals(emptyList(), draft.removedPortions)
        val without = draft.removingPortion(0).changingPortion(1) { it.copy(name = "fles") }
        assertEquals(bar.portions, without.removedPortions, "a renamed portion is gone under its old name")
        val kept = without.removingPortion(1).keepingRemovedPortions(FormatLocale.Dutch)
        assertEquals(emptyList(), kept.removedPortions)
        assertEquals(listOf("bar" to "45", "bottle" to "330"), kept.portions.map { it.name to it.size })
        assertEquals(bar.portions, kept.input().portions)
    }

    @Test
    fun aNewPortionIsInMillilitresForADrinkAndEachRowKeepsItsOwnUnit() {
        val drink = typed(LabelValue.ENERGY to "60").copy(perMl = true).addingPortion().changingPortion(0) { it.copy(name = "glass", size = "250") }
        assertEquals(listOf(Portion("glass", ml = 250.0)), drink.input().portions)
        assertEquals(FoodBasis.PER_100_ML, drink.label()!!.basis)
        assertEquals(listOf(Portion("glass", grams = 250.0)), drink.changingPortion(0) { it.copy(ml = false) }.input().portions)
    }

    @Test
    fun aProductOpenedAndSavedAsItWasIsTheSameProduct() {
        val draft = bar.toProductDraft(FormatLocale.Dutch)
        assertEquals("0,5", draft.value(LabelValue.SALT))
        assertEquals("", draft.value(LabelValue.FIBRE))
        assertEquals(listOf("45" to false, "330" to true), draft.portions.map { it.size to it.ml })
        val input = draft.input()
        assertEquals(bar.label, input.label)
        assertEquals(bar.portions, input.portions)
        assertEquals(listOf(45.0, 12.0, "Snacks", true), listOf(input.gramsPerPiece, input.pricePerKg, input.category, input.isStaple), "what the form does not show is kept")
    }
}
