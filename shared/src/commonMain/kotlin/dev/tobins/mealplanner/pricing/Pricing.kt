package dev.tobins.mealplanner.pricing

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.RecipeCost
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.nutrition.NutritionCalculator
import dev.tobins.mealplanner.recipes.IngredientLine
import kotlin.math.ceil

/**
 * Pack sizes as stores write them: "500 g", "1,5 l", "2 x 250 g", "ca. 600 g", "3 stuks", "per stuk", and
 * packs of several: "4-pack à 1 liter", "Tray met 24 blikken à 33cl", "3x 16 stuks" (all N times the size).
 * A text that says "alle" ("Alle flessen à 1,5 liter") names any item of that size, not a multiple.
 */
object PackSize {
    private const val NUMBER = """(\d+(?:[.,]\d+)?)"""
    private const val PACK_PREFIX = """(?:^|[\s(])(\d+)(?:-pack\s*à|\s*x|\s+([a-zëï]+)\s+[àx])\s*"""
    private val multi = Regex("""(\d+)\s*x\s*$NUMBER\s*(kilogram|kg|kilo|gram|gr|g|liter|lt|l|ml|cl)\b""")
    private val single = Regex("""$NUMBER\s*(kilogram|kg|kilo|gram|gr|g|liter|lt|l|ml|cl)\b""")
    private val pieces = Regex("""(\d+)\s*(stuks|stuk|st)\b""")
    private val volume = Regex("""\d\s*(liter|lt|l|ml|cl)\b""")
    private const val UNITS = "kilogram|kg|kilo|gram|gr|g|liter|lt|l|ml|cl"

    /** "4-pack à 1 liter", "2 verpakkingen à 200 gram", "24 blikken x 33 cl": a count, then the size of each. A range ("à 100-175 gram") is no size. */
    private val packsOf = Regex("""$PACK_PREFIX$NUMBER\s*($UNITS)\b""")

    /** "8 pakjes à 5 stuks", "3x 16 stuks", "4-pack à 6 stuks": a count, then the pieces in each. */
    private val piecesPerPack = Regex("""$PACK_PREFIX(\d+)\s*(?:stuks|stuk|st)\b""")
    private val everyItem = Regex("""\balle\b""")

    /**
     * Grams in a pack, or null when the text gives no size that can be converted: volumes
     * need [gramsPerMl] (1 g/ml is assumed without it), pieces need [gramsPerPiece].
     */
    fun grams(text: String?, gramsPerPiece: Double? = null, gramsPerMl: Double? = null): Double? {
        val t = text?.lowercase() ?: return null
        multi.find(t)?.let { m ->
            return finite(count(m.groupValues[1]), number(m.groupValues[2])) { count, amount -> count * toGrams(amount, m.groupValues[3], gramsPerMl) }
        }
        packOf(t)?.let { m ->
            return finite(count(m.groupValues[1]), number(m.groupValues[3])) { count, amount -> count * toGrams(amount, m.groupValues[4], gramsPerMl) }
        }
        single.find(t)?.let { m -> return number(m.groupValues[1])?.let { finite(toGrams(it, m.groupValues[2], gramsPerMl)) } }
        val count = pieces(t)?.toDouble()
        return if (count != null && gramsPerPiece != null) finite(count * gramsPerPiece) else null
    }

    /** The number of pieces in a pack written as "3 stuks" (or "per stuk": 1), null when the text counts none. */
    fun pieces(text: String?): Int? {
        val t = text?.lowercase() ?: return null
        piecesInPack(t)?.let { return it }
        return pieces.find(t)?.let { it.groupValues[1].toIntOrNull() } ?: if (t.contains("per stuk")) 1 else null
    }

    /** The pieces of a pack of packs ("8 pakjes à 5 stuks": 40, "3x 16 stuks": 48), null for another text. */
    private fun piecesInPack(t: String): Int? {
        if (everyItem.containsMatchIn(t)) return null
        val m = piecesPerPack.find(t)?.takeIf { it.groupValues[2] !in allUnits } ?: return null
        val total = finite(count(m.groupValues[1]), count(m.groupValues[3])) { a, b -> a * b } ?: return null
        return total.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    /** A pack of several written as "4-pack à 1 liter" or "24 blikken à 33 cl", unless the text speaks of every item of a size ("alle"). */
    private fun packOf(t: String): MatchResult? =
        if (everyItem.containsMatchIn(t)) null else packsOf.find(t)?.takeIf { it.groupValues[2] !in allUnits }

    /** True when [text] gives a volume ("1,5 l", "6 x 330 ml"): a size that is no weight without a density. */
    fun isVolume(text: String): Boolean = volume.containsMatchIn(text.lowercase())

    /**
     * Millilitres in a pack written as a volume ("1,5 l": 1500, "6 x 330 ml": 1980), or null when [text]
     * gives none. Only a volume is read: a weight in the same text is not taken for one.
     */
    fun millilitres(text: String?): Double? {
        val t = text?.lowercase() ?: return null
        multi.findAll(t).firstOrNull { it.groupValues[3] in volumeUnits }?.let { m ->
            return finite(count(m.groupValues[1]), number(m.groupValues[2])) { count, amount -> count * toMillilitres(amount, m.groupValues[3]) }
        }
        packOf(t)?.takeIf { it.groupValues[4] in volumeUnits }?.let { m ->
            return finite(count(m.groupValues[1]), number(m.groupValues[3])) { count, amount -> count * toMillilitres(amount, m.groupValues[4]) }
        }
        return single.findAll(t).firstOrNull { it.groupValues[2] in volumeUnits }?.let { m -> number(m.groupValues[1])?.let { finite(toMillilitres(it, m.groupValues[2])) } }
    }

    private val volumeUnits = setOf("liter", "lt", "l", "ml", "cl")

    /** Words after a count that are a unit, not the kind of pack: "500 g à 3 stuks" is no pack of packs. */
    private val allUnits = UNITS.split('|').toSet()

    private fun toMillilitres(amount: Double, unit: String): Double = when (unit) {
        "l", "lt", "liter" -> amount * 1000
        "cl" -> amount * 10
        else -> amount
    }

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
            if (packGrams != null) return gramsNeeded?.takeIf { it > 0 }?.let { packsFor(it / packGrams)?.let { packs -> PacksToBuy(packs, packGrams, null, deal) } }
            val packPieces = pieces(text)?.takeIf { it > 0 }
            if (packPieces != null) return piecesNeeded?.takeIf { it > 0 }?.let { packsFor(it / packPieces)?.let { packs -> PacksToBuy(packs, null, packPieces, deal) } }
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
    private fun packsFor(ratio: Double): Int? = ceil(ratio - 1e-9).takeIf { it.isFinite() && it <= MAX_PACKS }?.toInt()?.coerceAtLeast(1)

    /** More packs than this to cover one item means the size was misread, not that they are needed. */
    private const val MAX_PACKS = 100_000.0

    /** A number as written, or null when it is too long to be one: a store's text must never make the reader throw. */
    private fun number(text: String): Double? = sane(text.replace(',', '.').toDouble())

    /** A size or count a pack can have: 0, or between a milligram and a million tonnes; a denormal or a huge number is a misprint. */
    private fun sane(value: Double): Double? = value.takeIf { it == 0.0 || it in MIN_SIZE..MAX_SIZE }

    private const val MIN_SIZE = 0.001
    private const val MAX_SIZE = 1e9

    private fun count(text: String): Double? = text.toDouble().let(::sane)

    private fun finite(value: Double?): Double? = value?.takeIf { it.isFinite() }

    private inline fun finite(a: Double?, b: Double?, product: (Double, Double) -> Double): Double? =
        if (a == null || b == null) null else finite(product(a, b))

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
    /** More packs than this in a deal text is no deal: the digits are a misprint or a code. */
    private const val MAX_COUNT = 1000

    private val free = Regex("""(\d+)\s*\+\s*(\d+)\s*gratis""")
    // AH writes "2 stapelen voor 3.99" for "2 voor 3.99": stack that many packs for the price.
    private val multiPrice = Regex("""(\d+)\s*(?:stapelen\s*)?voor\s*(\d+(?:\.\d+)?)""")
    private val stacked = Regex("""(\d+)\s*stapelen""")
    private val fixedPrice = Regex("""^voor\s*(\d+(?:\.\d+)?)""")
    private val halfPriceNth = Regex("""(\d+)\s*e\s*halve prijs""")
    private val spelledNth = mapOf("tweede" to 2, "derde" to 3)
    private val spelledHalfPrice = Regex("""(tweede|derde) halve prijs""")
    private val forAtLeast = Regex(""" bij (\d+) stuks$""")
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
     * What the store says after a "·" ("· Alleen online") is left out, and a few wordings that change
     * nothing for the customer are read (see [read]). The rest must be one of the known forms as a whole: "2 stapelen tot 35%" ("up to", so not a firm percentage) or "voor 1,00
     * per hamburger" are [DealKind.Unknown] rather than read as something they may not be.
     */
    fun kind(deal: String?, price: Double? = null): DealKind {
        if (deal.isNullOrBlank()) return price?.let(DealKind::LowerPrice) ?: DealKind.Unknown(deal)
        return read(deal)?.kind ?: DealKind.Unknown(deal)
    }

    /** A deal read as a whole: its [kind] and the [packs] it wants bought together. */
    private class Reading(val kind: DealKind, val packs: Int)

    /**
     * [deal] as one of the known forms, or null. Around a form three wordings change nothing for the
     * customer and are read: a trailing "!", a leading "Nu", and "op alles" after a percentage (the offer is
     * for the product, so "25% korting op alles" is 25% off it). "bij N stuks" sets how many packs the
     * percentage needs, and is read after a deal that wants N packs itself; after a price it is left
     * unread, as it may mean each pack or all of them ("voor 1,99 bij 2 stuks"). "Tot 25% korting" is no
     * firm percentage and "Gratis beanie bij 2 stuks" is no price: neither is read.
     */
    private fun read(deal: String): Reading? {
        var t = deal.lowercase().replace(',', '.').substringBefore('·').trim().replace(Regex("""\s+"""), " ")
        t = t.trimEnd('!').trimEnd().removePrefix("nu ")
        val atLeastMatch = forAtLeast.find(t)?.also { t = t.removeSuffix(it.value) }
        val atLeast = atLeastMatch?.let { count(it.groupValues[1]) ?: return null }
        val everything = t.endsWith(" op alles")
        if (everything) t = t.removeSuffix(" op alles")
        val kind = known(t) ?: return null
        if (everything && kind !is DealKind.PercentOff) return null
        val own = when (kind) {
            is DealKind.FreeItems -> kind.paid + kind.free
            is DealKind.MultiPrice -> kind.packs
            is DealKind.NthHalfPrice -> kind.nth
            else -> 1
        }
        return when {
            atLeast == null -> Reading(kind, own)
            kind is DealKind.PercentOff -> Reading(kind, atLeast.coerceAtLeast(1))
            own > 1 && own == atLeast -> Reading(kind, own)
            else -> null
        }
    }

    /** The kind [t] (lower case, as read by [read]) is as a whole, or null when it is none of the known forms. */
    private fun known(t: String): DealKind? {
        fun Regex.whole() = matchEntire(t)?.groupValues
        // A number too long to be one makes the whole text unread: null, never an exception.
        fun List<String>.number(i: Int) = get(i).toDouble().takeIf { it.isFinite() }
        free.whole()?.let { return DealKind.FreeItems(count(it[1]) ?: return null, count(it[2]) ?: return null) }
        halfPriceNth.whole()?.let { return DealKind.NthHalfPrice(count(it[1]) ?: return null) }
        spelledHalfPrice.whole()?.let { return DealKind.NthHalfPrice(spelledNth.getValue(it[1])) }
        percent.whole()?.let { return DealKind.PercentOff(it.number(1) ?: return null) }
        pricePerWeight.whole()?.let {
            val grams = if (it[2].isEmpty()) 1000.0 else it.number(2) ?: return null
            return perKg((it.number(1) ?: return null) * 1000 / grams)
        }
        weightForPrice.whole()?.let { return perKg((it.number(2) ?: return null) * 1000 / (it.number(1) ?: return null)) }
        multiPrice.whole()?.let { return DealKind.MultiPrice(count(it[1]) ?: return null, it.number(2) ?: return null) }
        fixedPrice.whole()?.let { return DealKind.LowerPrice(it.number(1) ?: return null) }
        barePrice.whole()?.let { return DealKind.LowerPrice(it.number(0) ?: return null) }
        return (amountOff.whole() ?: amountOffInEuros.whole())?.let { DealKind.AmountOff(it.number(1) ?: return null) }
    }

    /** A count from a deal text: 1 to [MAX_COUNT], else null (0 packs is no deal, a huge count is no count). */
    private fun count(digits: String): Int? = digits.toIntOrNull()?.takeIf { it in 1..MAX_COUNT }

    private fun perKg(euros: Double): DealKind? = euros.takeIf { it.isFinite() }?.let(DealKind::PricePerKg)

    /**
     * How many packs the deal wants bought together: 2 for "2 voor 3,99", "1+1 gratis" and "2e halve
     * prijs", 4 for "3+1 gratis", N for "25% korting bij N stuks"; 1 for a percentage, a price or a deal
     * that says nothing of the kind. The one exception is a deal of unknown kind that says "N stapelen"
     * (AH: stack N packs, "2 stapelen tot 35%"): it says how many packs, though not what they save.
     */
    fun packsNeeded(deal: String?): Int {
        if (deal.isNullOrBlank()) return 1
        val packs = read(deal)?.packs ?: stacked.find(deal.lowercase())?.groupValues?.get(1)?.let(::count)
        return (packs ?: 1).coerceAtLeast(1)
    }

    /**
     * The share of the regular price paid with [offer]: 0.75 for "25% korting", 0.5 for
     * "1+1 gratis". The deal text is read by [kind], the same reading the price history uses, so a
     * text that is not one known form as a whole gives no share of its own. Deals stated as a price
     * ("2 voor 3,99") are compared with [regularPackPrice], the regular price of a pack of the offer's
     * size. Null when the deal cannot be worked out or saves nothing.
     *
     * Three rules are the shop list's own: an online-only deal gives no saving in the shop; the store's
     * price against its price before wins when it gives both; the result must lie between 0.2 and 0.97.
     */
    fun factor(offer: Offer, regularPackPrice: Double?): Double? {
        if (offer.deal?.contains("alleen online", ignoreCase = true) == true) return null
        val own = if (offer.price != null && offer.originalPrice != null && offer.originalPrice > 0) offer.price / offer.originalPrice else null
        val factor = own ?: dealFactor(kind(offer.deal, offer.price), regularPackPrice)
            ?: offer.price?.let { price -> regularPackPrice?.let { price / it } }
        // Above 1 the offer is for a dearer product than the baseline; below 0.2 something was misread.
        return factor?.takeIf { it in 0.2..0.97 }
    }

    /** The share [kind] pays by itself, or by its price against [regular]; null for a price per weight or no known form. */
    private fun dealFactor(kind: DealKind, regular: Double?): Double? = kind.share ?: regular?.let {
        when (kind) {
            is DealKind.MultiPrice -> kind.euros / kind.packs / it
            is DealKind.LowerPrice -> kind.euros / it
            is DealKind.AmountOff -> (it - kind.euros) / it
            else -> null
        }
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
