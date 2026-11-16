package dev.tobins.mealplanner.pricing

import dev.tobins.mealplanner.api.Offer

/**
 * The kind of a store's deal with the numbers that define it ([Discounts.kind]), so that two deals can be
 * compared without their wording: "1 + 1 gratis" at AH and "1+1 gratis" at Jumbo are the same deal.
 */
sealed interface DealKind {
    /** The share of the regular price paid when the deal is used in full, when the deal alone says it. */
    val share: Double? get() = null

    /** A plain lower price for one pack: "voor 1,99", or a price without a deal text (Lidl). */
    data class LowerPrice(val euros: Double) : DealKind

    /** "25% korting". */
    data class PercentOff(val percent: Double) : DealKind {
        override val share get() = 1 - percent / 100
    }

    /** "1,00 korting", "2 euro korting": [euros] off one pack. */
    data class AmountOff(val euros: Double) : DealKind

    /** "2 voor 3,00": [packs] packs for [euros] together. */
    data class MultiPrice(val packs: Int, val euros: Double) : DealKind

    /** "2+1 gratis": pay for [paid] packs, get [free] more. */
    data class FreeItems(val paid: Int, val free: Int) : DealKind {
        override val share get() = paid.toDouble() / (paid + free)
    }

    /** "2e halve prijs": the [nth] pack at half price, so 2 packs pay 0.75 of theirs. */
    data class NthHalfPrice(val nth: Int) : DealKind {
        override val share get() = (nth - 0.5) / nth
    }

    /** A price by weight, whatever the pack: "1,00 per kilo", "1,09 per 500 gram", "100 gram voor 1.59". */
    data class PricePerKg(val euros: Double) : DealKind

    /** A deal of no known form, kept with the store's [text] (null when there was neither text nor price). */
    data class Unknown(val text: String?) : DealKind
}

/**
 * What an offer comes to when its deal is used in full: its [kind], the [packs] it wants bought together
 * ([Discounts.packsNeeded]), the [paidShare] of the regular price and the price per kg, per piece, per
 * litre or per pack at that share. Worked out from the offer alone, without an ingredient's regular price, so it
 * stays the same for as long as the offer does: "2 voor 3,00" without the store's price before has no share, only
 * its price per kg and per pack.
 */
data class DealTerms(
    val kind: DealKind,
    val packs: Int,
    val paidShare: Double?,
    val eurosPerKg: Double?,
    val eurosPerPiece: Double?,
    /**
     * For a pack written as a volume ("1,5 l", "6 x 330 ml"), which has no price per kg without a density:
     * oil, milk, passata and stock are what stocking up is about, so their deals must be comparable too.
     */
    val eurosPerLitre: Double?,
    /**
     * The price paid for one pack: what compares two offers of the same product and pack when its size can't
     * be read ("2 voor 5,50" of "AH Goudse plakken"), whatever the deal or the store's price before.
     */
    val eurosPerPack: Double?,
) {
    companion object {
        fun of(offer: Offer): DealTerms {
            val kind = Discounts.kind(offer.deal, offer.price)
            val perPack = paidPerPack(kind, offer)
            val original = offer.originalPrice?.takeIf { it > 0 }
            // The store's own prices are exact; the deal's share is the fallback (no price before is given).
            val share = (perPack?.let { paid -> original?.let { paid / it } } ?: kind.share)?.takeIf { it > 0 && it <= 1 }
            val (grams, pieces) = packOf(offer)
            return DealTerms(
                kind = kind,
                packs = Discounts.packsNeeded(offer.deal),
                paidShare = share,
                eurosPerKg = (kind as? DealKind.PricePerKg)?.euros?.let(::usable) ?: perPack?.let { paid -> grams?.let { usable(paid / it * 1000) } },
                eurosPerPiece = perPack?.let { paid -> pieces?.let { usable(paid / it) } },
                eurosPerLitre = perPack?.let { paid -> millilitresOf(offer)?.let { usable(paid / it * 1000) } },
                eurosPerPack = perPack?.let(::usable),
            )
        }

        /** A price that can be stored: finite and above 0, else null. */
        private fun usable(euros: Double): Double? = euros.takeIf { it.isFinite() && it > 0 }

        /**
         * The price of one pack with the deal used in full, or null when it can't be told: a deal of free or
         * half-price packs needs the price before, as the price a store gives with one may be either.
         */
        private fun paidPerPack(kind: DealKind, offer: Offer): Double? = when (kind) {
            is DealKind.LowerPrice -> kind.euros
            is DealKind.MultiPrice -> kind.euros / kind.packs
            is DealKind.PercentOff -> offer.price ?: offer.originalPrice?.let { it * kind.share }
            is DealKind.AmountOff -> offer.price ?: offer.originalPrice?.let { it - kind.euros }
            is DealKind.FreeItems -> offer.originalPrice?.let { it * kind.share }
            is DealKind.NthHalfPrice -> offer.originalPrice?.let { it * kind.share }
            is DealKind.PricePerKg, is DealKind.Unknown -> null
        }?.takeIf { it > 0 }

        /**
         * Grams or pieces in one pack, from the pack size or else the title, as [PackSize.toBuy] reads them.
         * Volumes are left out: without a density they are no weight.
         */
        private fun packOf(offer: Offer): Pair<Double?, Int?> {
            for (text in listOfNotNull(offer.packSize, offer.title)) {
                if (PackSize.isVolume(text)) continue
                PackSize.grams(text)?.takeIf { it > 0 }?.let { return it to null }
                PackSize.pieces(text)?.takeIf { it > 0 }?.let { return null to it }
            }
            return null to null
        }

        /**
         * Millilitres in one pack, from the first of the pack size and the title that gives a size at all: a
         * pack size in grams or pieces is no volume, whatever the title says.
         */
        private fun millilitresOf(offer: Offer): Double? {
            for (text in listOfNotNull(offer.packSize, offer.title)) {
                if (PackSize.isVolume(text)) return PackSize.millilitres(text)?.takeIf { it > 0 }
                if (PackSize.grams(text) != null || PackSize.pieces(text) != null) return null
            }
            return null
        }
    }
}
