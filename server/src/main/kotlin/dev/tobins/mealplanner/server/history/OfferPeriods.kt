package dev.tobins.mealplanner.server.history

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.pricing.DealTerms
import java.time.Instant
import java.time.LocalDate

/** What a deal is compared by, in order of preference when two of them have as much history. */
enum class Measure {
    PER_KG,
    PER_LITRE,
    PER_PIECE,

    /** The price of one pack: only between offers of the same pack, for a product whose size can't be read. */
    PER_PACK,

    /** The share of the regular price paid: comparable for one product however its deal is worded. */
    PAID_SHARE,
}

/**
 * What an offer comes to when its deal is used in full ([DealTerms]): the price per kg, litre, piece or pack,
 * and the share of the regular price paid. Lower is better in each. Two offers are compared by one of these,
 * never by their deal text, so a "2+1 gratis" and a "1+1 gratis" of the same product compare by what is paid.
 */
data class Measures(val perKg: Double?, val perLitre: Double?, val perPiece: Double?, val perPack: Double?, val paidShare: Double?) {
    operator fun get(measure: Measure): Double? = when (measure) {
        Measure.PER_KG -> perKg
        Measure.PER_LITRE -> perLitre
        Measure.PER_PIECE -> perPiece
        Measure.PER_PACK -> perPack
        Measure.PAID_SHARE -> paidShare
    }

    companion object {
        /**
         * The measures of [offer], read now. An earlier offer is read again from what the history kept of it
         * (title, pack size, deal, prices), not taken from the readings stored with it: so the offer of today
         * and the earlier ones are read by the same reader, also after a change to how deals are read.
         */
        fun of(offer: Offer) = DealTerms.of(offer).let { Measures(it.eurosPerKg, it.eurosPerLitre, it.eurosPerPiece, it.eurosPerPack, it.paidShare) }
    }
}

/**
 * A run of refreshes in which a product was offered one way ([identity]), with the [deal] as the store
 * wrote it, when it was first and last seen, and the dates the store gave. A row of the history is one;
 * [joinOffers] joins the rows of one offer.
 */
data class OfferPeriod(
    val identity: OfferIdentity,
    val deal: String?,
    val measures: Measures,
    val firstSeen: Instant,
    val lastSeen: Instant,
    val validFrom: LocalDate,
    val validUntil: LocalDate,
) {
    /** The deal as the customer sees it, which "always the same deal" goes by ([dealOf]). */
    val dealKey get() = dealOf(identity)

    /** What makes rows one offer: the pack and the deal as the customer sees it ([joinOffers]). */
    val offerKey get() = identity.product.pack to dealKey

    /** Whether the store's dates of this offer include [day]. */
    fun dated(day: LocalDate) = !day.isBefore(validFrom) && !day.isAfter(validUntil)

    /** Whether the store's dates of this offer share a day with [from] to [until], or run on into them or from them. */
    fun touches(from: LocalDate, until: LocalDate) = !validFrom.isAfter(until.plusDays(1)) && !from.isAfter(validUntil.plusDays(1))

    /**
     * This run and a [later] one of the same offer as one: from the first sighting to the last, over both's
     * dates, measured as the cheaper of them ([cheaper]).
     */
    fun joinedWith(later: OfferPeriod) = (if (cheaper(later.identity, identity)) later else this).copy(
        firstSeen = minOf(firstSeen, later.firstSeen),
        lastSeen = maxOf(lastSeen, later.lastSeen),
        validFrom = minOf(validFrom, later.validFrom),
        validUntil = maxOf(validUntil, later.validUntil),
    )
}

/** An offer of today, as a verdict compares it with its history. */
data class CurrentOffer(val offer: Offer) {
    val identity = OfferSighting(offer).identity
    val measures = Measures.of(offer)
    val validFrom: LocalDate = LocalDate.parse(offer.validFrom)
    val validUntil: LocalDate = LocalDate.parse(offer.validUntil)
    val originalPrice = offer.originalPrice?.takeIf { it > 0 }
    val dealKey get() = dealOf(identity)
    val offerKey get() = identity.product.pack to dealKey

    /**
     * Whether [run] is this offer itself: the same pack and deal on dates that share a day with it or run on into
     * it, whatever was paid then and whichever nights it was seen ([joinOffers]).
     */
    fun isItself(run: OfferPeriod) = run.offerKey == offerKey && run.touches(validFrom, validUntil)
}

/**
 * The deal as the customer sees it: the store's deal text ("25% korting", "VOOR 1.99"), or, for an offer without
 * one (Lidl), its price. The price of a deal with a text is not part of it: AH states a price with some "25%
 * korting" offers, which moves with the price before while the deal stays the same.
 */
private fun dealOf(identity: OfferIdentity): String = identity.deal.ifEmpty { "price ${identity.price}" }

/**
 * Whether [a] is the cheaper row of one offer: the lower price before, or else the lower price. For one deal
 * text the price paid follows the price before (a share of it, or the price the text names), so this is the
 * row with the lowest price paid as well.
 */
private fun cheaper(a: OfferIdentity, b: OfferIdentity): Boolean = compareValuesBy(a, b, { it.originalPrice ?: Double.MAX_VALUE }, { it.price ?: Double.MAX_VALUE }) < 0

/**
 * Joins the rows of one product that are one offer: the same pack and the same deal as the customer sees it
 * ([OfferPeriod.offerKey]: the deal text, or the price where there is none), on store dates that share a day or
 * run on (the later one starts by the day after the earlier one ends). Nothing else decides it:
 * - Nights the product was missing from the page do not: the store parsers skip an item with a missing field
 *   without a word, so a missed night says nothing about the offer. Nights the store was not read say nothing
 *   either.
 * - The same deal again the very next week is the offer continuing, not a second one: an offer that simply
 *   goes on says nothing about how often the product is on offer ([Verdict.ALWAYS_ON_OFFER] is the rule for a
 *   product always on offer).
 * - A price that changed while the deal stayed (AH showing another price before mid-week, and a "25% korting"
 *   price with it) is the same offer. It is measured at its lowest price, the row with the lowest price before
 *   ([cheaper]): the price the customer could have had while it ran, against the lowest price before it, as a
 *   reduction is measured ([REFERENCE_DAYS]). Today's offer is not measured so: [judge] takes it as it is today.
 * Dates that neither share a day nor run on mean the store ended the offer and began it again: two offers.
 */
fun joinOffers(periods: List<OfferPeriod>): List<OfferPeriod> =
    periods.groupBy { it.offerKey }.values.flatMap { same ->
        // Sorted by their dates, each row can only touch the last offer joined so far.
        same.sortedWith(compareBy({ it.validFrom }, { it.firstSeen })).fold(mutableListOf<OfferPeriod>()) { joined, next ->
            val last = joined.lastOrNull()
            if (last != null && next.touches(last.validFrom, last.validUntil)) joined[joined.lastIndex] = last.joinedWith(next) else joined += next
            joined
        }
    }.sortedWith(compareBy({ it.validFrom }, { it.validUntil }, { it.offerKey.toString() }))
