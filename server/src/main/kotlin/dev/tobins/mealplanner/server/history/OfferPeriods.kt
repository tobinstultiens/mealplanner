package dev.tobins.mealplanner.server.history

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.pricing.DealTerms
import dev.tobins.mealplanner.server.serverDay
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
 * [joinGaps] joins the rows of one offer.
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
    /** The way of offering that "always the same deal" goes by: the deal and its price, whatever the price before. */
    val dealKey get() = identity.deal to identity.price

    /** What makes rows one offer: the pack, the deal and its price; not the price before (see [joinGaps]). */
    val offerKey get() = offerKey(identity)

    /** Whether the product was offered this way at the refresh at [instant]. */
    fun covers(instant: Instant) = instant in firstSeen..lastSeen

    /** Whether the store's dates of this offer share a day with [from] to [until]. */
    fun overlaps(from: LocalDate, until: LocalDate) = !validFrom.isAfter(until) && !from.isAfter(validUntil)

    /**
     * This run and a [later] one of the same offer as one: from the first sighting to the last, over both's
     * dates, measured against the lower of their prices before (see [joinGaps]).
     */
    fun joinedWith(later: OfferPeriod) = (if (lowerPriceBefore(later.identity, identity)) later else this).copy(
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
    val dealKey get() = identity.deal to identity.price
    val offerKey get() = offerKey(identity)

    /** Whether [run] is this offer itself: the same pack, deal and price, on dates that share a day with it. */
    fun isItself(run: OfferPeriod) = run.offerKey == offerKey && run.overlaps(validFrom, validUntil)

    /** The offer measured against [priceBefore] (see [joinGaps]). */
    fun withPriceBefore(priceBefore: Double) = CurrentOffer(offer.copy(originalPrice = priceBefore))
}

private fun offerKey(identity: OfferIdentity) = Triple(identity.product.pack, identity.deal, identity.price)

/** Whether [a] has a price before and [b] none, or a higher one. */
private fun lowerPriceBefore(a: OfferIdentity, b: OfferIdentity): Boolean =
    a.originalPrice != null && (b.originalPrice == null || a.originalPrice < b.originalPrice)

/**
 * Most server days with a refresh that did not see a product, between two rows of an offer the store dated
 * as two that run on, for the rows still to be one offer. The store parsers skip an item with a missing field
 * without a word, so a product can miss one night and be back the next unchanged; that is no new offer.
 * Nights the store was not read at all are no nights without the offer, so they do not count.
 */
const val MAX_MISSED_DAYS = 1

/**
 * Joins the rows of one product that are one offer: the same pack, deal and price ([OfferPeriod.offerKey]),
 * and either
 * - store dates that share a day: the store says it is one offer, however many nights it was missing from the
 *   page in between, or
 * - dates that run on (the later one starts the day after the earlier one ends) with at most
 *   [MAX_MISSED_DAYS] days between the rows on which the store was read ([recordings]) without the product.
 * Dates that neither overlap nor run on mean the store ended the offer and began it again.
 *
 * A price before that changed while the offer ran (AH showing another price before mid-week) does not make
 * another offer: the deal and what is paid are the same. The joined offer is measured against the lowest
 * price before seen while it ran, as a reduction is measured against the lowest price before it (see
 * [REFERENCE_DAYS]); a share against a price raised mid-offer would look deeper than it is.
 */
fun joinGaps(periods: List<OfferPeriod>, recordings: List<Instant>): List<OfferPeriod> =
    periods.groupBy { it.offerKey }.values.flatMap { same ->
        same.sortedBy { it.firstSeen }.fold(mutableListOf<OfferPeriod>()) { joined, next ->
            val last = joined.lastOrNull()
            if (last != null && oneOffer(last, next, recordings)) joined[joined.lastIndex] = last.joinedWith(next) else joined += next
            joined
        }
    }.sortedBy { it.firstSeen }

private fun oneOffer(earlier: OfferPeriod, later: OfferPeriod, recordings: List<Instant>): Boolean {
    if (later.overlaps(earlier.validFrom, earlier.validUntil)) return true
    val missed = recordings.filter { it > earlier.lastSeen && it < later.firstSeen }.mapTo(HashSet(), ::serverDay)
    return missed.size <= MAX_MISSED_DAYS && later.validFrom == earlier.validUntil.plusDays(1)
}
