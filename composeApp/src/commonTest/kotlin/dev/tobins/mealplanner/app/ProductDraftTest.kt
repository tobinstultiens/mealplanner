package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.FoundProduct
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.LabelNutrition
import dev.tobins.mealplanner.api.LookupProblem
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.PackSize
import dev.tobins.mealplanner.api.Portion
import dev.tobins.mealplanner.api.ProductLookup
import dev.tobins.mealplanner.nutrition.Salt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The product form's model (issues 351 and 354): what is typed or looked up, what it saves and what it refuses. */
class ProductDraftTest {
    private val bar = Ingredient(
        5, "Protein bar", null, 45.0, null, "Snacks", 12.0, isStaple = true,
        label = LabelNutrition(FoodBasis.PER_100_G, Nutrition(400.0, 30.0, null, null, null, null, null, Salt.sodiumMg(0.5)), source = "typed"),
        portions = listOf(Portion("bar", grams = 45.0), Portion("bottle", ml = 330.0)),
    )

    /** A drink yoghurt as AH's lookup gives it: per 100 ml, no fibre on its label, a pack of 1 l. */
    private val optimel = FoundProduct(
        "ah:61758", "Optimel Drinkyoghurt framboos", "Optimel", FoodBasis.PER_100_ML,
        Nutrition(31.0, 3.1, 0.0, 0.0, 3.8, 3.2, null, Salt.sodiumMg(0.08)), pack = PackSize(ml = 1000.0),
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
    fun aValueIsSavedAsItWasGivenWhateverTheFormShowsOfIt() {
        val exact = bar.copy(
            label = LabelNutrition(FoodBasis.PER_100_G, Nutrition(381.70175438, 20.0, null, null, null, null, null, Salt.sodiumMg(0.005)), "off:5056357909515"),
            portions = listOf(Portion("bar", grams = 33.3333)),
        )
        val draft = exact.toProductDraft(FormatLocale.Dutch)
        assertEquals(listOf("381,702", "0,005", "33,333"), listOf(draft.value(LabelValue.ENERGY), draft.value(LabelValue.SALT), draft.portions.single().size))
        assertEquals(exact.label, draft.input().label, "unchanged, every value is as it was, the source too")
        assertEquals(exact.portions, draft.input().portions)
        // Another value changed: the ones left alone are still as they were.
        val changed = draft.withValue(LabelValue.PROTEIN, "21").input().label!!
        assertEquals(381.70175438 to Salt.sodiumMg(0.005), changed.per100.kcal to changed.per100.sodiumMg)
        assertEquals(380.0, draft.withValue(LabelValue.ENERGY, "380").input().label!!.per100.kcal)
    }

    @Test
    fun aValueChangedByHandAfterALookupMarksItsSource() {
        val draft = ProductDraft().filledWith(optimel, "pack", FormatLocale.English)
        assertEquals("ah:61758", draft.source)
        assertEquals("ah:61758;changed", draft.withValue(LabelValue.SUGARS, "3.3").source)
        assertEquals("ah:61758;changed", draft.withValue(LabelValue.FIBRE, "1").source, "a value it did not give is typed by hand")
        assertEquals("ah:61758;changed", draft.copy(perMl = false).source)
        assertEquals("ah:61758", draft.copy(name = "Optimel").addingPortion().source, "the name and the portions are not its values")
        val saved = bar.copy(label = draft.withValue(LabelValue.SUGARS, "3.3").label())
        assertEquals("ah:61758;changed", saved.toProductDraft(FormatLocale.English).input().label!!.source, "once changed, it stays so")
        assertNull(typed(LabelValue.ENERGY to "400").source, "typed from the label")
    }

    @Test
    fun aLookupFillsTheFormWithItsValuesAndOffersItsPackAsAPortion() {
        val draft = ProductDraft(name = "yoghurt").filledWith(optimel, "pack", FormatLocale.English)
        assertEquals("Optimel Drinkyoghurt framboos", draft.name)
        assertTrue(draft.perMl)
        assertEquals(mapOf(LabelValue.ENERGY to "31", LabelValue.PROTEIN to "3.1", LabelValue.SALT to "0.08"), draft.values.filterKeys { it in setOf(LabelValue.ENERGY, LabelValue.PROTEIN, LabelValue.SALT) })
        assertEquals("", draft.value(LabelValue.FIBRE))
        assertEquals(optimel.per100, draft.label()!!.per100)
        assertEquals(listOf(Portion("pack", ml = 1000.0)), draft.input().portions)
        // Looked up again, the pack is there already; one without a table fills no values.
        val again = draft.filledWith(withoutValues, "pack", FormatLocale.English)
        assertEquals(1, again.portions.size)
        assertNull(again.label())
        assertTrue(again.perMl, "as it was")
    }

    /** The drink as a source that has it but no table that reads: values per portion only, or AH without one. */
    private val withoutValues = optimel.copy(basis = null, per100 = Nutrition(null, null, null, null, null, null, null, null))

    @Test
    fun aLookupWithoutValuesGivesTheNameAndPackButNoSourceAndValuesTypedAreTheLabels() {
        for (source in listOf("ah:609144", "off:4000000000006")) {
            val draft = ProductDraft().filledWith(withoutValues.copy(source = source), "pack", FormatLocale.English)
            assertEquals("Optimel Drinkyoghurt framboos", draft.name)
            assertEquals(listOf(Portion("pack", ml = 1000.0)), draft.input().portions)
            assertNull(draft.source, source)
            val typed = draft.withValue(LabelValue.ENERGY, "31").withValue(LabelValue.PROTEIN, "3.1")
            assertNull(typed.input().label!!.source, "$source: every value is the label's")
            assertFalse(typed.changedByHand)
        }
        // A lookup without values after one with them leaves no source behind either.
        assertNull(ProductDraft().filledWith(optimel, "pack", FormatLocale.English).filledWith(withoutValues, "pack", FormatLocale.English).source)
    }

    @Test
    fun valuesWhoseBasisTheSourceDoesNotSayAreKeptAndTheBasisIsChosenBeforeSaving() {
        val bar = optimel.copy(source = "off:5000000000001", name = "Snack bar", basis = null, pack = null)
        val draft = ProductDraft().filledWith(bar, "pack", FormatLocale.English)
        assertTrue(draft.basisNeeded)
        assertEquals("31", draft.value(LabelValue.ENERGY))
        assertFalse(draft.canSave, "per 100 g or per 100 ml first")
        for (ml in listOf(false, true)) {
            val chosen = draft.choosingBasis(ml)
            assertTrue(chosen.canSave)
            assertEquals(if (ml) FoodBasis.PER_100_ML else FoodBasis.PER_100_G, chosen.label()!!.basis)
            assertEquals("off:5000000000001", chosen.label()!!.source, "choosing what the source did not say is no change of its values")
        }
        assertEquals("off:5000000000001;changed", draft.choosingBasis(false).withValue(LabelValue.FAT, "1").source)
    }

    @Test
    fun aNameAnotherIngredientHasIsTakenWhateverItsCaseButNotTheProductsOwn() {
        val catalogue = listOf(bar, bar.copy(id = 9, name = "Optimel Drinkyoghurt framboos"))
        assertTrue(ProductDraft(name = " optimel drinkyoghurt framboos ").nameTaken(catalogue))
        assertFalse(ProductDraft(name = "Optimel Drinkyoghurt aardbei").nameTaken(catalogue))
        assertFalse(bar.toProductDraft(FormatLocale.English).nameTaken(catalogue), "its own name")
        assertTrue(bar.toProductDraft(FormatLocale.English).copy(name = "Optimel Drinkyoghurt framboos").nameTaken(catalogue))
    }

    @Test
    fun aBarcodeIsDigitsAndANameIsSearched() {
        assertEquals("8712800035626", barcodeIn("8712800035626"))
        assertEquals("8712800035626", barcodeIn(" 87 1280 0035626 "))
        assertEquals("12345670", barcodeIn("12345670"))
        listOf("1234567", "123456789012345", "drinkyoghurt", "7up 330 ml", "８７１２８０００３５６２６").forEach { assertNull(barcodeIn(it), it) }
    }

    @Test
    fun theFormSaysWhatTheSourceDidNotGiveOrCouldNotAnswer() {
        val t = Strings.English
        assertEquals(listOf("Not given: fibre."), lookupNotes(ProductLookup(optimel), t))
        assertEquals(
            listOf("Not given: fibre.", "Could not be read: saturated fat, salt.", "The values are of one of the 4 in the pack."),
            lookupNotes(ProductLookup(optimel.copy(per100 = optimel.per100.copy(saturatedFatG = null, sodiumMg = null), unread = listOf("saturatedFatG", "sodiumMg"), packOf = 4)), t),
        )
        assertEquals(listOf("No product found for it.", "Type the values from the label."), lookupNotes(ProductLookup(), t))
        assertEquals(
            listOf("AH did not answer.", "Open Food Facts is not set up on this server, so it was not asked.", "Type the values from the label."),
            lookupNotes(ProductLookup(problems = listOf(LookupProblem.AH_FAILED, LookupProblem.OFF_NOT_CONFIGURED)), t),
        )
        assertEquals(listOf("A source answered in a way this app does not know.", "Type the values from the label."), lookupNotes(ProductLookup(problems = listOf("later_code")), t))
        assertEquals(
            listOf("AH gives no values per 100 g or 100 ml for it.", "Type the values from the label."),
            lookupNotes(ProductLookup(withoutValues.copy(source = "ah:609144"), listOf(LookupProblem.AH_NO_TABLE)), t),
        )
        assertEquals(
            listOf("Open Food Facts gives no values per 100 g or 100 ml for it.", "Type the values from the label."),
            lookupNotes(ProductLookup(withoutValues.copy(source = "off:4000000000006")), t),
        )
        assertEquals(
            listOf("Not given: fibre.", "Open Food Facts does not say whether the values are per 100 g or per 100 ml: choose below."),
            lookupNotes(ProductLookup(optimel.copy(source = "off:5000000000001", basis = null)), t),
        )
        assertEquals("Values from Open Food Facts, changed by hand", sourceLine("off:8712800035626;changed", t))
        assertEquals("AH, met de hand aangepast", sourceLabel("ah:61758;changed", Strings.Dutch))
        assertNull(sourceLabel("typed", t))
        assertEquals("Waarden van AH", sourceLine("ah:61758", Strings.Dutch))
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
