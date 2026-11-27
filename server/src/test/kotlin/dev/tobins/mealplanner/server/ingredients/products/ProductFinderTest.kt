package dev.tobins.mealplanner.server.ingredients.products

import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.LookupProblem
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.PackSize
import dev.tobins.mealplanner.api.ProductHit
import dev.tobins.mealplanner.api.ProductLookup
import dev.tobins.mealplanner.api.hasValues
import dev.tobins.mealplanner.nutrition.Salt
import dev.tobins.mealplanner.server.BuildInfo
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A product's label looked up at AH and Open Food Facts (issue 354), on their recorded answers ([RecordedSources]). */
class ProductFinderTest {
    private val sources = RecordedSources()

    private val offHost = "world.openfoodfacts.org"

    @Test
    fun aBarPer100GramsGivesItsEightValuesItsBasisAndItsPack() = runBlocking {
        val found = sources.finder().ahProduct("599562", null)

        assertEquals(emptyList(), found.problems)
        val bar = found.product!!
        assertEquals("ah:599562", bar.source)
        assertEquals("AH Proteïne reep witte choco pistache", bar.name)
        assertEquals("AH", bar.brand)
        assertEquals(FoodBasis.PER_100_G, bar.basis)
        assertEquals(Nutrition(388.0, 33.0, 18.0, 7.8, 28.0, 4.4, 8.6, Salt.sodiumMg(0.68)), bar.per100)
        assertEquals(emptyList(), bar.unread)
        assertEquals(PackSize(grams = 50.0), bar.pack)
    }

    @Test
    fun aDrinkPer100MillilitresGivesItsValuesPerMillilitreAndAValueNotGivenIsEmpty() = runBlocking {
        val drink = sources.finder().ahProduct("61758", null).product!!

        assertEquals(FoodBasis.PER_100_ML, drink.basis)
        // Its table per 200 ml comes second and is not read.
        assertEquals(Nutrition(31.0, 3.1, 0.0, 0.0, 3.8, 3.2, null, Salt.sodiumMg(0.08)), drink.per100)
        assertEquals(emptyList(), drink.unread, "no fibre on the label is not given, not unread")
        assertEquals(PackSize(ml = 1000.0), drink.pack)
    }

    @Test
    fun lessThanAValueWithADecimalCommaAndAnEnergyInKjAndKcalAreReadAndWhatDoesNotReadIsSaid() = runBlocking {
        // The recorded bar, its salt written as some labels write it and its fibre as no number.
        sources.ahEdit = { it.replace("\"0.68 g\"", "\"<0,5 g\"").replace("\"8.6 g\"", "\"spoor\"") }
        val bar = sources.finder().ahProduct("599562", null).product!!

        assertEquals(388.0, bar.per100.kcal, "1618.0 kJ (388.0 kcal)")
        assertEquals(Salt.sodiumMg(0.5), bar.per100.sodiumMg)
        assertNull(bar.per100.fibreG)
        assertEquals(listOf("fibreG"), bar.unread)
    }

    @Test
    fun aBundleIsFoundAsItsFirstItemAndGivesTheValuesOfThatItem() = runBlocking {
        val hits = sources.finder().search("drinkyoghurt")
        assertEquals(emptyList(), hits.problems)
        assertEquals(11, hits.products.size)
        assertEquals(ProductHit(61758, "Optimel Drinkyoghurt framboos", "Optimel", "1 l"), hits.products.first())
        val bundle = hits.products.single { it.name == "Optimel Proteine extra framboos aardbei 4-pack" }
        assertEquals(ProductHit(585938, bundle.name, "Optimel", "4 stuks", packOf = 4), bundle)

        val item = sources.finder().ahProduct(bundle.id.toString(), bundle.packOf.toString()).product!!
        assertEquals("Optimel Proteine extra framboos aardbei", item.name)
        assertEquals(55.0, item.per100.kcal)
        assertEquals(4, item.packOf)
        assertEquals(PackSize(ml = 330.0, single = true), item.pack)
    }

    @Test
    fun aBundleLookedUpItselfHasNoTableWhichIsSaid() = runBlocking {
        val found = sources.finder().ahProduct("609144", null)

        assertEquals(listOf(LookupProblem.AH_NO_TABLE), found.problems)
        assertNull(found.product!!.basis)
        assertEquals("Optimel Proteine extra framboos aardbei 4-pack", found.product!!.name)
    }

    @Test
    fun anUnknownProductIsNotFound() = runBlocking {
        assertEquals(ProductLookup(), sources.finder().ahProduct("123", null))
    }

    @Test
    fun aSourceThatFailsIsSaidAndNoRequestFails() = runBlocking {
        sources.ahStatus = HttpStatusCode.ServiceUnavailable

        assertEquals(listOf(LookupProblem.AH_FAILED), sources.finder().search("drinkyoghurt").problems)
        assertEquals(ProductLookup(problems = listOf(LookupProblem.AH_FAILED)), sources.finder().ahProduct("599562", null))
        // A barcode goes on to Open Food Facts, which has this one.
        val byBarcode = sources.finder().barcode("8712800035626")
        assertEquals(listOf(LookupProblem.AH_FAILED), byBarcode.problems)
        assertEquals("off:8712800035626", byBarcode.product!!.source)
    }

    @Test
    fun aBarcodeAhKnowsGivesAhsValuesWithoutAskingOpenFoodFacts() = runBlocking {
        val found = sources.finder().barcode("8712800035626")

        assertEquals(emptyList(), found.problems)
        assertEquals("ah:61758", found.product!!.source)
        assertEquals(31.0, found.product!!.per100.kcal)
        assertTrue(sources.requests.none { it.startsWith(offHost) })
        // The 14 digits of AH's own barcode field are the same product.
        assertEquals("ah:61758", sources.finder().barcode("08712800035626").product!!.source)
    }

    @Test
    fun aBarcodeAhDoesNotKnowGivesThoseOfOpenFoodFactsAsItsSource() = runBlocking {
        sources.unknownAtAh += "8712800035626"
        val found = sources.finder().barcode("8712800035626")

        assertEquals(emptyList(), found.problems)
        val drink = found.product!!
        assertEquals("off:8712800035626", drink.source)
        assertEquals("Optimel drinkyoghurt framboos", drink.name)
        // Its quantity is in ml, so its values per 100 g are those per 100 ml.
        assertEquals(FoodBasis.PER_100_ML, drink.basis)
        assertEquals(Nutrition(31.0, 3.1, 0.0, 0.0, 3.8, 3.3, null, Salt.sodiumMg(0.0775)), drink.per100)
        assertEquals(PackSize(ml = 100.0), drink.pack)
        assertEquals(listOf("MealPlanner/${BuildInfo.version} (owner@example.org)"), sources.offAgents)
    }

    @Test
    fun aBarcodeWhoseTableAhCannotReadGoesOnToOpenFoodFacts() = runBlocking {
        // The recorded drink with its table per 100 ml read as one per portion: AH has it, but no values per 100.
        sources.ahEdit = { it.replace("100.0 Milliliter", "250.0 Milliliter") }
        val found = sources.finder().barcode("8712800035626")

        assertEquals(listOf(LookupProblem.AH_NO_TABLE), found.problems)
        assertEquals("off:8712800035626", found.product!!.source)

        // Without Open Food Facts, AH's product comes without values, its name and pack still of use.
        val ahOnly = sources.finder(withOff = false).barcode("8712800035626")
        assertEquals(listOf(LookupProblem.AH_NO_TABLE, LookupProblem.OFF_NOT_CONFIGURED), ahOnly.problems)
        assertEquals("ah:61758", ahOnly.product!!.source)
        assertNull(ahOnly.product!!.basis)
        assertEquals(PackSize(ml = 1000.0), ahOnly.product!!.pack)
    }

    @Test
    fun openFoodFactsWithoutAQuantityGivesItsValuesAndLeavesTheirBasisOpen() {
        // The recorded drink without its quantity: nothing says whether its values are per 100 g or per 100 ml.
        val body = RecordedSources.recording("off-v3-product-8712800035626.json")!!.replace(Regex("""\s*"quantity": "100 ml","""), "")
        val drink = OpenFoodFactsReader.product(body, "8712800035626")!!

        assertNull(drink.basis)
        assertTrue(drink.hasValues)
        assertEquals(31.0, drink.per100.kcal)
        assertNull(drink.pack)
    }

    @Test
    fun aBarcodeNeitherKnowsIsNotFound() = runBlocking {
        assertEquals(ProductLookup(), sources.finder().barcode("8710000000005"))
        assertEquals(1, sources.requests.count { it.startsWith(offHost) })
    }

    @Test
    fun withoutAContactOpenFoodFactsIsNeverAsked() = runBlocking {
        val found = sources.finder(withOff = false).barcode("8710000000005")

        assertEquals(ProductLookup(problems = listOf(LookupProblem.OFF_NOT_CONFIGURED)), found)
        assertTrue(sources.requests.none { it.startsWith(offHost) })
    }

    @Test
    fun theSixteenthRequestToOpenFoodFactsInAMinuteIsHeldBack() = runBlocking {
        val finder = sources.finder()
        repeat(15) {
            assertEquals(ProductLookup(), finder.barcode("8710000000005"))
            sources.now = sources.now.plusSeconds(1)
        }
        assertEquals(15, sources.requests.count { it.startsWith(offHost) })

        assertEquals(ProductLookup(problems = listOf(LookupProblem.OFF_BUSY)), finder.barcode("8710000000005"))
        assertEquals(15, sources.requests.count { it.startsWith(offHost) }, "held back, not sent")

        // A minute after the first, one place is free again.
        sources.now = sources.now.plus(Duration.ofSeconds(45))
        assertEquals(ProductLookup(), finder.barcode("8710000000005"))
        assertEquals(16, sources.requests.count { it.startsWith(offHost) })
    }

    @Test
    fun whatTheAppSendsIsCheckedBeforeASourceIsAsked() = runBlocking {
        val finder = sources.finder()
        for (bad in listOf(null, "", "1234567", "123456789012345", "8712800035626x", "../8712800035626", "８７１２８０００３５６２６")) {
            runCatching { finder.barcode(bad) }.let { assertTrue(it.exceptionOrNull() is IllegalArgumentException, "barcode $bad") }
        }
        for (bad in listOf(null, "", "-1", "1e3", "61758/../1", "1234567890")) {
            runCatching { finder.ahProduct(bad, null) }.let { assertTrue(it.exceptionOrNull() is IllegalArgumentException, "id $bad") }
        }
        runCatching { finder.ahProduct("61758", "0") }.let { assertTrue(it.exceptionOrNull() is IllegalArgumentException) }
        for (bad in listOf(null, " a ", "x".repeat(81))) {
            runCatching { finder.search(bad) }.let { assertTrue(it.exceptionOrNull() is IllegalArgumentException, "search $bad") }
        }
        assertEquals(emptyList(), sources.requests)
        assertEquals("0036000291452", ProductFinder.barcodeOf("036000291452"), "a UPC-A as the EAN-13 it is")
        assertEquals("12345670", ProductFinder.barcodeOf("12345670"))
    }
}
