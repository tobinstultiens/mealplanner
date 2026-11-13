package dev.tobins.mealplanner.pricing

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.RecipeCost
import dev.tobins.mealplanner.nutrition.NutritionCalculator
import dev.tobins.mealplanner.recipes.IngredientLine

/** Pack sizes as stores write them: "500 g", "1,5 l", "2 x 250 g", "ca. 600 g", "3 stuks", "per stuk". */
object PackSize {
    private const val NUMBER = """(\d+(?:[.,]\d+)?)"""
    private val multi = Regex("""(\d+)\s*x\s*$NUMBER\s*(kilogram|kg|kilo|gram|gr|g|liter|lt|l|ml|cl)\b""")
    private val single = Regex("""$NUMBER\s*(kilogram|kg|kilo|gram|gr|g|liter|lt|l|ml|cl)\b""")
    private val pieces = Regex("""(\d+)\s*(stuks|stuk|st)\b""")

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

    private fun number(text: String) = text.replace(',', '.').toDouble()

    private fun toGrams(amount: Double, unit: String, gramsPerMl: Double?): Double = when (unit) {
        "kilogram", "kg", "kilo" -> amount * 1000
        "g", "gr", "gram" -> amount
        "l", "lt", "liter" -> amount * 1000 * (gramsPerMl ?: 1.0)
        "cl" -> amount * 10 * (gramsPerMl ?: 1.0)
        else -> amount * (gramsPerMl ?: 1.0)
    }
}

/** How much of the regular price an offer leaves to pay. */
object Discounts {
    private val percent = Regex("""(\d+(?:\.\d+)?)\s*%\s*korting""")
    private val free = Regex("""(\d+)\s*\+\s*(\d+)\s*gratis""")
    private val multiPrice = Regex("""(\d+)\s*voor\s*(\d+(?:\.\d+)?)""")
    private val fixedPrice = Regex("""^voor\s*(\d+(?:\.\d+)?)""")
    private val amountOff = Regex("""^(\d+(?:\.\d+)?)\s*korting""")

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
