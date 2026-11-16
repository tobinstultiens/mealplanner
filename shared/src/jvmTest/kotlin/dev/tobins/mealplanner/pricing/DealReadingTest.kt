package dev.tobins.mealplanner.pricing

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs every deal text of the store fixtures and of the tables in docs/data-sources.md through the
 * shopping list's reading before the price history's reading was used for it ([OldReading], kept here as
 * a copy) and through [Discounts.factor] now, and says which results differ. Each difference is listed in
 * [accepted] with why; a new wording that reads differently must be added there on purpose.
 */
class DealReadingTest {
    private fun offer(deal: String) =
        Offer(Store.AH, "Product", deal, null, null, "500 g", null, "2026-11-16", "2026-11-22")

    /** Regular prices of a pack to compare stated prices with, and none. */
    private val regulars = listOf(null, 2.0, 5.0)

    private fun texts(): Set<String> {
        val dealLike = Regex("""korting|gratis|halve prijs|stapelen|per kilo|per kg|gram voor|^voor |^\d+ voor |^\d+[.,]\d+( ·.*)?$""", RegexOption.IGNORE_CASE)
        val quoted = Regex(""""([^"\\\n]{1,60})"""")
        val sources = File("../server/src/test/resources/offers").listFiles().orEmpty().toList() + File("../docs/data-sources.md")
        val found = sources.flatMap { file -> quoted.findAll(file.readText()).map { it.groupValues[1] }.toList() }
            .filter { dealLike.containsMatchIn(it) }
        return (found + wordingsSeenInTheStores).toSet()
    }

    /** Wordings of the stores' dev database that are not in the fixtures, or only in prose. */
    private val wordingsSeenInTheStores = listOf(
        "3e halve prijs", "2e halve prijs", "2 stapelen tot 35%", "2 stapelen voor 3.99", "3 stapelen voor 7.99",
        "2 gratis blikjes bij 1 multipack", "Gratis beanie bij 2 stuks", "voor 1,00 per hamburger",
        "1,09 per 500 gram", "100 gram voor 1.59", "Gratis bezorging", "2+1 gratis · Alleen in de winkel", "2 euro korting",
    )

    /** Old and new share of one deal text with each regular price; only the ones that differ. */
    private fun differences(): Map<String, List<Pair<Double?, Double?>>> = texts().sorted().mapNotNull { text ->
        val pairs = regulars.map { regular ->
            OldReading.factor(offer(text), regular) to Discounts.factor(offer(text), regular)
        }
        if (pairs.all { (old, new) -> same(old, new) }) null else text to pairs
    }.toMap()

    private fun same(a: Double?, b: Double?) = (a == null && b == null) || (a != null && b != null && abs(a - b) < 1e-9)

    @Test
    fun everyDifferenceBetweenTheOldAndTheNewReadingIsOnPurpose() {
        val differing = differences()
        differing.forEach { (text, pairs) ->
            println("DEAL-DIFF $text: " + regulars.zip(pairs).joinToString { (regular, p) -> "regular=$regular old=${p.first} new=${p.second}" })
        }
        assertEquals(accepted.keys, differing.keys, "differences that are not on the accepted list, or accepted ones that are gone")
    }

    /** Why each text reads differently now. */
    private val accepted = mapOf(
        "3e halve prijs" to "fix: the third pack at half price pays 2.5 of 3 (0.83), the old rule took every half price for 0.75",
        "2 euro korting" to "fix: the old rule only read \"2 korting\"; 2 euro off a pack of 5 pays 0.6 (needs the regular price)",
        "voor 1,00 per hamburger" to "fix: a price per hamburger is no price of the pack; the old rule read it as \"voor 1,00\" (0.5 or 0.2 of the regular price)",
    )
}

/** The shopping list's own reading of a deal text before it used [Discounts.kind]: patterns searched anywhere in the text. */
private object OldReading {
    private val percent = Regex("""(\d+(?:\.\d+)?)\s*%\s*korting""")
    private val free = Regex("""(\d+)\s*\+\s*(\d+)\s*gratis""")
    private val multiPrice = Regex("""(\d+)\s*(?:stapelen\s*)?voor\s*(\d+(?:\.\d+)?)""")
    private val fixedPrice = Regex("""^voor\s*(\d+(?:\.\d+)?)""")
    private val amountOff = Regex("""^(\d+(?:\.\d+)?)\s*korting""")

    fun factor(offer: Offer, regularPackPrice: Double?): Double? {
        val deal = offer.deal?.lowercase()?.replace(',', '.')?.trim()
        if (deal != null && "alleen online" in deal) return null
        val factor = when {
            offer.price != null && offer.originalPrice != null && offer.originalPrice > 0 -> offer.price / offer.originalPrice
            deal == null -> offer.price?.let { price -> regularPackPrice?.let { price / it } }
            else -> percent.find(deal)?.let { 1 - it.groupValues[1].toDouble() / 100 }
                ?: free.find(deal)?.let { m ->
                    val paid = m.groupValues[1].toDouble()
                    paid / (paid + m.groupValues[2].toDouble())
                }
                ?: (if ("halve prijs" in deal) 0.75 else null)
                ?: regularPackPrice?.let { regular ->
                    multiPrice.find(deal)?.let { m -> m.groupValues[2].toDouble() / m.groupValues[1].toDouble() / regular }
                        ?: fixedPrice.find(deal)?.let { it.groupValues[1].toDouble() / regular }
                        ?: amountOff.find(deal)?.let { (regular - it.groupValues[1].toDouble()) / regular }
                }
                ?: offer.price?.let { price -> regularPackPrice?.let { price / it } }
        } ?: return null
        return factor.takeIf { it in 0.2..0.97 }
    }
}
