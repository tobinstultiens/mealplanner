package dev.tobins.mealplanner.pricing

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.RecipeCost
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.nutrition.NutritionCalculator
import dev.tobins.mealplanner.recipes.IngredientLine
import kotlin.math.ceil

/** Pack sizes as stores write them: "500 g", "1,5 l", "2 x 250 g", "ca. 600 g", "3 stuks", "per stuk". */
object PackSize {
    private const val NUMBER = """(\d+(?:[.,]\d+)?)"""
    private val multi = Regex("""(\d+)\s*x\s*$NUMBER\s*(kilogram|kg|kilo|gram|gr|g|liter|lt|l|ml|cl)\b""")
    private val single = Regex("""$NUMBER\s*(kilogram|kg|kilo|gram|gr|g|liter|lt|l|ml|cl)\b""")
    private val pieces = Regex("""(\d+)\s*(stuks|stuk|st)\b""")
    private val volume = Regex("""\d\s*(liter|lt|l|ml|cl)\b""")

    /**
     * Grams in a pack, or null when the text gives no size that can be converted: volumes
     * need [gramsPerMl] (1 g/ml is assumed without it), pieces need [gramsPerPiece].
     */
    fun grams(text: String?, gramsPerPiece: Double? = null, gramsPerMl: Double? = null): Double? {
        val t = text?.lowercase() ?: return null
        multi.find(t)?.let { m ->
            return m.groupValues[1].toDouble() * toGrams(number(m.groupValues[2]), m.groupValues[3], gramsPerMl)
        }
        single.find(t)?.let { m -> return toGrams(number(m.groupValues[1]), m.groupValues[2], gramsPerMl) }
        val count = pieces.find(t)?.groupValues?.get(1)?.toDouble() ?: if (t.contains("per stuk")) 1.0 else null
        return if (count != null && gramsPerPiece != null) count * gramsPerPiece else null
    }

    /** The number of pieces in a pack written as "3 stuks" (or "per stuk": 1), null when the text counts none. */
    fun pieces(text: String?): Int? {
        val t = text?.lowercase() ?: return null
        return pieces.find(t)?.groupValues?.get(1)?.toInt() ?: if (t.contains("per stuk")) 1 else null
    }

    /** True when [text] gives a volume ("1,5 l", "6 x 330 ml"): a size that is no weight without a density. */
    fun isVolume(text: String): Boolean = volume.containsMatchIn(text.lowercase())

    /**
     * What to pick up for an item that needs [gramsNeeded] (or [piecesNeeded]) when the store sells it as
     * [offer]: the packs to take and how many packs the deal wants. The pack size is read from
     * [Offer.packSize], else from the title. Packs in grams are compared with [gramsNeeded], packs in
     * pieces with [piecesNeeded]; nothing is converted between them, and volumes are not taken for
     * weights. Null when the size cannot be read or the item has no amount of the pack's kind.
     */
    fun toBuy(offer: Offer, gramsNeeded: Double?, piecesNeeded: Double?): PacksToBuy? {
        val deal = Discounts.packsNeeded(offer.deal)
        for (text in listOfNotNull(offer.packSize, offer.title)) {
            if (volume.containsMatchIn(text.lowercase())) continue
            val packGrams = grams(text)?.takeIf { it > 0 }
            if (packGrams != null) return gramsNeeded?.takeIf { it > 0 }?.let { PacksToBuy(packsFor(it / packGrams), packGrams, null, deal) }
            val packPieces = pieces(text)?.takeIf { it > 0 }
            if (packPieces != null) return piecesNeeded?.takeIf { it > 0 }?.let { PacksToBuy(packsFor(it / packPieces), null, packPieces, deal) }
        }
        return null
    }

    /** More packs than this for one deal is a pallet, not stocking up: such an offer is not advised. */
    const val MAX_STOCK_UP_PACKS = 6

    /**
     * The packs a stock-up row advises for [offer] whose pack weighs [packGrams] (null when not known):
     * the packs the deal wants bought together ([Discounts.packsNeeded]), so that its saving is really
     * had. Null when that is not a reasonable amount to stock: more than [MAX_STOCK_UP_PACKS] packs, more
     * than [ShoppingCheck.MAX_GRAMS] in all, or several packs of a size nobody can add up.
     */
    fun stockUpPacks(offer: Offer, packGrams: Double?): Int? {
        val packs = Discounts.packsNeeded(offer.deal)
        if (packs == 1) return 1
        if (packGrams == null || packs > MAX_STOCK_UP_PACKS || packs * packGrams > ShoppingCheck.MAX_GRAMS) return null
        return packs
    }

    /** Whole packs; a hair over a whole number (rounding in the sums) does not call for one more. */
    private fun packsFor(ratio: Double) = ceil(ratio - 1e-9).toInt().coerceAtLeast(1)

    private fun number(text: String) = text.replace(',', '.').toDouble()

    private fun toGrams(amount: Double, unit: String, gramsPerMl: Double?): Double = when (unit) {
        "kilogram", "kg", "kilo" -> amount * 1000
        "g", "gr", "gram" -> amount
        "l", "lt", "liter" -> amount * 1000 * (gramsPerMl ?: 1.0)
        "cl" -> amount * 10 * (gramsPerMl ?: 1.0)
        else -> amount * (gramsPerMl ?: 1.0)
    }
}

/**
 * What to take home for one item: [packs] of the offer's pack, which is [packGrams] heavy or holds
 * [packPieces] pieces (one of them is set), and the [dealPacks] the deal wants bought together.
 */
data class PacksToBuy(val packs: Int, val packGrams: Double?, val packPieces: Int?, val dealPacks: Int) {
    /** True when the deal needs no more packs than the amount does, so its saving is really had. */
    val dealFits: Boolean get() = dealPacks <= packs
}

/** How much of the regular price an offer leaves to pay. */
object Discounts {
    private val percent = Regex("""(\d+(?:\.\d+)?)\s*%\s*korting""")
    private val free = Regex("""(\d+)\s*\+\s*(\d+)\s*gratis""")
    private val multiPrice = Regex("""(\d+)\s*voor\s*(\d+(?:\.\d+)?)""")
    private val fixedPrice = Regex("""^voor\s*(\d+(?:\.\d+)?)""")
    private val halfPriceNth = Regex("""(\d+)\s*e\s*halve prijs""")
    private val amountOff = Regex("""^(\d+(?:\.\d+)?)\s*korting""")
    private val amountOffInEuros = Regex("""(\d+(?:\.\d+)?)\s*euro\s*korting""")
    private val barePrice = Regex("""\d+(?:\.\d+)?""")
    private val pricePerWeight = Regex("""(\d+(?:\.\d+)?)\s*per\s*(?:kilo|kg|(\d+)\s*(?:gram|g))""")
    private val weightForPrice = Regex("""(\d+)\s*(?:gram|g)\s*voor\s*(\d+(?:\.\d+)?)""")

    /**
     * The kind of deal the store wrote as [deal], with the numbers that define it, so that deals can be
     * compared over time ("this 2+1 pays 67 %, it is 1+1 about every four weeks"). [price] is the offer's
     * own price: without a deal text (Lidl) the offer is that lower price.
     *
     * Only what the store says after a "·" ("· Alleen online") is left out. The rest must be one of the
     * known forms as a whole: "2 stapelen tot 35%" or "voor 1,00 per hamburger" are [DealKind.Unknown]
     * rather than read as something they may not be.
     */
    fun kind(deal: String?, price: Double? = null): DealKind {
        if (deal.isNullOrBlank()) return price?.let(DealKind::LowerPrice) ?: DealKind.Unknown(deal)
        val t = deal.lowercase().replace(',', '.').substringBefore('·').trim().replace(Regex("""\s+"""), " ")
        fun Regex.whole() = matchEntire(t)?.groupValues
        fun List<String>.number(i: Int) = get(i).toDouble()
        free.whole()?.let { return DealKind.FreeItems(it[1].toInt(), it[2].toInt()) }
        halfPriceNth.whole()?.let { return DealKind.NthHalfPrice(it[1].toInt()) }
        percent.whole()?.let { return DealKind.PercentOff(it.number(1)) }
        pricePerWeight.whole()?.let { return DealKind.PricePerKg(it.number(1) * 1000 / (it[2].toDoubleOrNull() ?: 1000.0)) }
        weightForPrice.whole()?.let { return DealKind.PricePerKg(it.number(2) * 1000 / it.number(1)) }
        multiPrice.whole()?.let { return DealKind.MultiPrice(it[1].toInt(), it.number(2)) }
        fixedPrice.whole()?.let { return DealKind.LowerPrice(it.number(1)) }
        barePrice.whole()?.let { return DealKind.LowerPrice(it.number(0)) }
        (amountOff.whole() ?: amountOffInEuros.whole())?.let { return DealKind.AmountOff(it.number(1)) }
        return DealKind.Unknown(deal)
    }

    /**
     * How many packs the deal wants bought together: 2 for "2 voor 3,99", "1+1 gratis" and "2e halve
     * prijs", 4 for "3+1 gratis"; 1 for a percentage, a price or a deal that says nothing of the kind.
     */
    fun packsNeeded(deal: String?): Int {
        val text = deal?.lowercase()?.replace(',', '.')?.trim() ?: return 1
        val count = free.find(text)?.let { it.groupValues[1].toInt() + it.groupValues[2].toInt() }
            ?: multiPrice.find(text)?.groupValues?.get(1)?.toInt()
            ?: halfPriceNth.find(text)?.groupValues?.get(1)?.toInt()
        return (count ?: 1).coerceAtLeast(1)
    }

    /**
     * The share of the regular price paid with [offer]: 0.75 for "25% korting", 0.5 for
     * "1+1 gratis". Deals stated as a price ("2 voor 3,99") are compared with
     * [regularPackPrice], the regular price of a pack of the offer's size. Null when the
     * deal cannot be worked out or saves nothing.
     */
    fun factor(offer: Offer, regularPackPrice: Double?): Double? {
        val deal = offer.deal?.lowercase()?.replace(',', '.')?.trim()
        // Online-only deals don't apply in the shop.
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
        // Above 1 the offer is for a dearer product than the baseline; below 0.2 something was misread.
        return factor.takeIf { it in 0.2..0.97 }
    }
}

/**
 * Prices to cost recipes with: euros per kg by ingredient id, and for ingredients on offer
 * the share of that price still paid ([discounts], from [Discounts.factor]).
 */
class Pricing(
    val perKg: Map<Long, Double>,
    val discounts: Map<Long, Double> = emptyMap(),
) {
    /** Stand-in for ingredients without a price: the median of the known ones. */
    private val fallbackPerKg: Double? = perKg.values.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    /**
     * Cost of one of [servings] servings. Negligible ingredients, seasoning and water are free;
     * lines without a weight are skipped. Ingredients without a price are counted at the
     * median price and listed in [RecipeCost.estimated]. Null when nothing could be priced.
     */
    fun cost(lines: List<Pair<IngredientLine, Ingredient?>>, servings: Int): RecipeCost? {
        if (servings <= 0) return null
        var regular = 0.0
        var withOffers = 0.0
        var priced = false
        val estimated = linkedSetOf<String>()
        for ((line, ingredient) in lines) {
            if (ingredient == null || ingredient.negligible || ingredient.name in freeIngredients) continue
            if (NutritionCalculator.isSeasoning(line.ingredientText, ingredient) && line.amount == null) continue
            val grams = NutritionCalculator.quantityOf(line, ingredient)?.grams ?: continue
            val known = perKg[ingredient.id]
            val price = known ?: fallbackPerKg ?: continue
            if (known == null) estimated += ingredient.name
            val lineCost = grams / 1000 * price
            regular += lineCost
            withOffers += lineCost * (discounts[ingredient.id] ?: 1.0)
            priced = true
        }
        if (!priced) return null
        return RecipeCost(regular / servings, withOffers / servings, estimated.toList())
    }

    companion object {
        val NONE = Pricing(emptyMap())

        /** Tap water and salt cost next to nothing, whatever the amount. */
        private val freeIngredients = setOf("Water", "Salt")
    }
}
